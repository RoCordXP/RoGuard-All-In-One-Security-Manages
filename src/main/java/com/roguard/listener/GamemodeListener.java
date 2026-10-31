package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;

import java.util.Locale;

/**
 * Holds gamemode changes behind an approval.
 * Covers commands, the F3 plus F4 menu and other plugins.
 */
public class GamemodeListener implements Listener {

    private final RoGuard plugin;

    public GamemodeListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();

        // Allowed once after an approval or by the player manager.
        if (plugin.getVerifyManager().consumeGamemode(player.getUniqueId())) return;

        if (!plugin.getConfigManager().isGamemodeProtectionEnabled()) return;
        if (!plugin.getConfigManager().isVerifyEnabled()) return;

        if (plugin.getConfigManager().isBypassed(player.getName())
                || player.hasPermission("roguard.bypass")) {
            return;
        }

        // Authentication handles its own restrictions.
        if (plugin.getConfigManager().isAuthEnabled()
                && !plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String command = "/gamemode " + event.getNewGameMode().name().toLowerCase(Locale.ROOT);
        player.sendMessage(plugin.getLanguageManager().msg("security.gamemode-required"));

        String id = plugin.getVerifyManager().submit(player.getName(), command, false, null);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().requestApproval(id, player.getName(), command);
        }
    }
}
