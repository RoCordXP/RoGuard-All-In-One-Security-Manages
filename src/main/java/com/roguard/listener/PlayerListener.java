package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.UUID;

/** Chat bridge events, mute enforcement, freeze and auto deop. */
public class PlayerListener implements Listener {

    private final RoGuard plugin;

    public PlayerListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== CHAT ====================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChatMute(AsyncChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.getPunishmentManager().isMuted(uuid)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(plugin.getPunishmentManager().muteMessage(uuid));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfigManager().isChatBridgeEnabled()) return;
        if (plugin.getChatBot() == null) return;
        if (!plugin.getAuthManager().isAuthenticated(event.getPlayer().getUniqueId())) return;

        plugin.getChatBot().chat(event.getPlayer().getName(), TextUtil.plain(event.message()));
    }

    // ==================== JOIN AND QUIT ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (plugin.getConfigManager().isChatBridgeEnabled() && plugin.getChatBot() != null) {
            plugin.getChatBot().joinLeave(player.getName(), true,
                    Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers());
        }

        // Apply an inventory edit made while the player was away.
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.getSnapshotStore().applyPending(player), 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        UUID uuid = player.getUniqueId();

        plugin.getConfigManager().removeTemporary(name);
        plugin.getPunishmentManager().setFrozen(uuid, false);
        plugin.getInventoryManager().close(uuid);

        if (plugin.getConfigManager().isAutoDeopEnabled() && player.isOp()) {
            player.setOp(false);
            plugin.getLogger().info("Removed operator from " + name);
        }

        plugin.getSnapshotStore().saveAsync(player);
        plugin.getPlayerDataManager().saveAsync(uuid);

        if (plugin.getConfigManager().isChatBridgeEnabled() && plugin.getChatBot() != null) {
            int online = Math.max(0, Bukkit.getOnlinePlayers().size() - 1);
            plugin.getChatBot().joinLeave(name, false, online, Bukkit.getMaxPlayers());
        }
    }

    // ==================== DEATH ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!plugin.getConfigManager().isChatBridgeEnabled()) return;
        if (plugin.getChatBot() == null) return;

        // Flatten the component so custom item names lose every colour marker.
        String message = TextUtil.plain(event.deathMessage());
        if (message.isBlank()) return;

        plugin.getChatBot().death(message);
    }

    // ==================== ADVANCEMENT ====================

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (!plugin.getConfigManager().isChatBridgeEnabled()) return;
        if (plugin.getChatBot() == null) return;
        if (!announced(event.getAdvancement())) return;

        plugin.getChatBot().advancement(event.getPlayer().getName(),
                prettify(event.getAdvancement()));
    }

    /**
     * Minecraft also fires this for hidden technical entries such as the
     * recipe root called On Inventory Change. Only announce real ones.
     */
    private boolean announced(Advancement advancement) {
        String key = advancement.getKey().getKey().toLowerCase(Locale.ROOT);
        if (key.startsWith("recipes/") || key.contains("/recipes/")
                || key.equals("root") || key.endsWith("/root")) {
            return false;
        }

        try {
            Object display = advancement.getDisplay();
            if (display == null) return false;

            // Paper uses doesAnnounceToChat, older Bukkit uses shouldAnnounceChat.
            for (String method : new String[]{"doesAnnounceToChat", "shouldAnnounceChat"}) {
                try {
                    Object value = display.getClass().getMethod(method).invoke(display);
                    if (value instanceof Boolean announce) return announce;
                } catch (ReflectiveOperationException ignored) {}
            }
            return true;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private String prettify(Advancement advancement) {
        try {
            String plain = TextUtil.plain(advancement.displayName());
            if (!plain.isBlank()) return plain;
        } catch (Throwable ignored) {}

        String key = advancement.getKey().getKey();
        String clean = key.substring(key.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String word : clean.split(" ")) {
            if (word.isEmpty()) continue;
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)).append(' ');
        }
        return out.toString().trim();
    }

    // ==================== FREEZE ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;

        UUID uuid = event.getPlayer().getUniqueId();
        if (!plugin.getPunishmentManager().isFrozen(uuid)) return;

        event.setCancelled(true);
        event.getPlayer().sendActionBar(
                plugin.getLanguageManager().component("playermanager.frozen-message"));
    }
}
