package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Chat based authentication for clients that cannot show dialogs.
 * The player is shielded until they authenticate.
 */
public class AuthListener implements Listener {

    private final RoGuard plugin;
    private final Map<UUID, BukkitTask> kickTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> promptTasks = new HashMap<>();

    public AuthListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== JOIN ====================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer());
    }

    public void begin(Player player) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;

        UUID uuid = player.getUniqueId();
        stop(uuid);

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        data.setName(player.getName());

        // The dialog already authenticated this connection.
        if (plugin.getAuthManager().isAuthenticated(uuid)) {
            afterLogin(player, false);
            return;
        }

        String ip = addressOf(player);

        if (plugin.getAuthManager().hasValidSession(uuid, ip)) {
            plugin.getAuthManager().markAuthenticated(uuid);
            player.sendMessage(plugin.getLanguageManager()
                    .msg("auth.login-success", "%player%", player.getName()));
            afterLogin(player, false);
            return;
        }

        // Shield first so nothing around the real position is revealed.
        plugin.getLoginShieldManager().apply(player);

        boolean registered = data.isRegistered();
        showPrompt(player, registered);

        BukkitTask prompt = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || plugin.getAuthManager().isAuthenticated(uuid)) {
                stopPrompt(uuid);
                return;
            }
            showPrompt(player, plugin.getPlayerDataManager().get(uuid).isRegistered());
        }, 60L, 80L);
        promptTasks.put(uuid, prompt);

        int timeout = plugin.getConfigManager().getLoginTimeout();
        BukkitTask kick = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && !plugin.getAuthManager().isAuthenticated(uuid)) {
                player.kick(plugin.getLanguageManager().component("auth.kick-timeout"));
            }
        }, timeout * 20L);
        kickTasks.put(uuid, kick);
    }

    private void showPrompt(Player player, boolean registered) {
        String key = registered ? "auth.prompt-login" : "auth.prompt-register";
        player.sendMessage(plugin.getLanguageManager().msg(key));

        player.showTitle(Title.title(
                plugin.getLanguageManager().component(
                        registered ? "auth.title-login" : "auth.title-register"),
                plugin.getLanguageManager().component(
                        registered ? "auth.title-login-sub" : "auth.title-register-sub"),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(5), Duration.ofMillis(300))));
    }

    /** Called after a successful password check from any source. */
    public void afterLogin(Player player, boolean releaseShield) {
        UUID uuid = player.getUniqueId();
        stop(uuid);

        boolean needsTwoFactor = plugin.getAuthManager().beginTwoFactor(player);

        if (needsTwoFactor) {
            // Dialog users enter the world only after their password is valid,
            // but 2FA is still pending. Shield them now so the base remains hidden.
            if (!plugin.getLoginShieldManager().isShielded(uuid)) {
                plugin.getLoginShieldManager().apply(player);
            }
            player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-required"));
            player.showTitle(Title.title(
                    plugin.getLanguageManager().component("2fa.title-verify"),
                    plugin.getLanguageManager().component("2fa.title-verify-sub"),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(8), Duration.ofMillis(300))));
            return;
        }

        player.resetTitle();
        if (releaseShield || plugin.getLoginShieldManager().isShielded(uuid)) {
            plugin.getLoginShieldManager().release(player);
        }
    }

    public void stop(UUID uuid) {
        BukkitTask kick = kickTasks.remove(uuid);
        if (kick != null) kick.cancel();
        stopPrompt(uuid);
    }

    private void stopPrompt(UUID uuid) {
        BukkitTask prompt = promptTasks.remove(uuid);
        if (prompt != null) prompt.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        stop(uuid);
        plugin.getAuthManager().clear(uuid);
        plugin.getTwoFactorManager().removePending(uuid);
        plugin.getLoginShieldManager().forget(uuid);
        plugin.getVerifyManager().clear(uuid);
    }

    // ==================== RESTRICTIONS ====================

    /** True while the player may not interact with the world. */
    private boolean locked(UUID uuid) {
        if (!plugin.getConfigManager().isAuthEnabled()) return false;
        return !plugin.getAuthManager().isAuthenticated(uuid)
                || plugin.getTwoFactorManager().isPending(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        if (!locked(event.getPlayer().getUniqueId())) return;
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (plugin.getAuthManager().isAuthenticated(uuid)) {
            if (plugin.getTwoFactorManager().isPending(uuid)) {
                event.setCancelled(true);
                player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-required"));
            }
            return;
        }

        String command = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        if (command.startsWith("/")) command = command.substring(1);
        if (command.contains(":")) command = command.substring(command.indexOf(':') + 1);

        boolean allowed = switch (command) {
            case "login", "l", "register", "reg" -> true;
            default -> false;
        };

        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().msg("auth.blocked-until-login"));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (locked(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** Unauthenticated players cannot be hurt. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (locked(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageBy(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Mobs ignore unauthenticated players. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player player && locked(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private String addressOf(Player player) {
        return player.getAddress() == null
                ? "" : player.getAddress().getAddress().getHostAddress();
    }
}
