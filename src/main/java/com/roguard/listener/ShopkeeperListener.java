package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.manager.VerifyManager;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;

/**
 * Holds the Shopkeepers trade editor behind an approval.
 *
 * The editor opens on sneak plus right click, so the event is caught at the
 * lowest priority and cancelled before the Shopkeepers plugin reacts.
 */
public class ShopkeeperListener implements Listener {

    private final RoGuard plugin;
    private Boolean shopkeepersPresent;

    public ShopkeeperListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        // The event fires for both hands, only handle the main one.
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().isSneaking()) return;

        if (!plugin.getConfigManager().isShopkeeperEnabled()) return;
        if (!plugin.getConfigManager().isVerifyEnabled()) return;
        if (!hasShopkeepers()) return;

        Player player = event.getPlayer();
        Entity entity = event.getRightClicked();

        if (!isShopkeeper(entity)) return;

        if (plugin.getConfigManager().isBypassed(player.getName())
                || player.hasPermission("roguard.bypass")) {
            return;
        }

        if (plugin.getVerifyManager().canEditShop(player.getUniqueId())) return;

        event.setCancelled(true);
        player.sendMessage(plugin.getLanguageManager().msg("security.shopkeeper-required"));

        String label = "Shop edit: " + describe(entity);
        String id = plugin.getVerifyManager()
                .submit(player.getName(), label, false, VerifyManager.ACTION_SHOPKEEPER);

        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().requestApproval(id, player.getName(), label);
        }
    }

    private boolean hasShopkeepers() {
        if (shopkeepersPresent == null) {
            shopkeepersPresent = plugin.getServer().getPluginManager()
                    .getPlugin("Shopkeepers") != null;
        }
        return shopkeepersPresent;
    }

    /**
     * Shopkeepers tags its entities in several ways depending on version,
     * so every known marker is checked.
     */
    private boolean isShopkeeper(Entity entity) {
        if (entity == null) return false;

        for (String marker : plugin.getConfigManager().getShopkeeperMetadata()) {
            if (entity.hasMetadata(marker)) return true;
            if (entity.getScoreboardTags().contains(marker)) return true;
        }

        try {
            for (NamespacedKey key : entity.getPersistentDataContainer().getKeys()) {
                String namespace = key.getNamespace().toLowerCase(Locale.ROOT);
                String value = key.getKey().toLowerCase(Locale.ROOT);
                if (namespace.contains("shopkeeper") || value.contains("shopkeeper")) return true;
            }
        } catch (Throwable ignored) {}

        // Shopkeeper mobs are always silent, invulnerable and have no gravity.
        try {
            if (entity.isSilent() && entity.isInvulnerable() && !entity.hasGravity()) return true;
        } catch (Throwable ignored) {}

        return false;
    }

    private String describe(Entity entity) {
        if (entity.customName() != null) {
            return com.roguard.util.TextUtil.plain(entity.customName());
        }
        return entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
