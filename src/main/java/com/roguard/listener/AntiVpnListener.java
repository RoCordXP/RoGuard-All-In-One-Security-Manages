package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Rejects connections coming through a VPN, proxy or hosting network. */
public class AntiVpnListener implements Listener {

    private final RoGuard plugin;

    public AntiVpnListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!plugin.getConfigManager().isAntiVpnEnabled()) return;

        Player player = event.getPlayer();
        String name = player.getName();

        if (plugin.getConfigManager().isVpnBypassed(name)) return;
        if (plugin.getConfigManager().isBypassed(name)) return;
        if (player.hasPermission("roguard.bypass")) return;
        if (player.getAddress() == null) return;

        String ip = player.getAddress().getAddress().getHostAddress();

        // The lookup is cached and runs off the main thread.
        plugin.getVpnChecker().check(ip).thenAccept(result -> {
            if (!result.flagged) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                player.kick(TextUtil.component(plugin.getConfigManager().vpnKickMessage()));
            });

            plugin.getLogger().warning("Blocked " + name + " from " + ip + ": " + result.reason);

            if (plugin.getSecurityBot() != null) {
                plugin.getSecurityBot().logVpn(name, ip, result.reason);
            }
        });
    }
}
