package com.roguard.listener;

import com.roguard.RoGuard;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

/** Command approval, blocked commands and safe logging. */
public class CommandListener implements Listener {

    private static final String[] SENSITIVE = {
            "login", "l", "register", "reg", "changepassword", "changepass", "cp"
    };

    private final RoGuard plugin;

    public CommandListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== PLAYER COMMANDS ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();
        String safe = censor(message);

        // The built in logger is disabled, so print a safe audit line.
        Bukkit.getLogger().info(player.getName() + " issued server command: " + safe);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().logCommand(player.getName(), safe);
        }

        boolean bypassed = plugin.getConfigManager().isBypassed(player.getName())
                || player.hasPermission("roguard.bypass");

        if (plugin.getConfigManager().isBlocked(message) && !bypassed) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLanguageManager().msg("security.command-blocked"));
            return;
        }

        if (!plugin.getConfigManager().isVerifyEnabled()) return;
        if (!plugin.getConfigManager().requiresVerify(message)) return;

        String command = message.startsWith("/") ? message.substring(1) : message;

        if (plugin.getVerifyManager().consumeCommand(player.getName() + ":" + command)) {
            plugin.getVerifyManager().allowGamemode(player.getUniqueId());
            return;
        }

        if (bypassed || fromTrustedPlugin()) {
            plugin.getVerifyManager().allowGamemode(player.getUniqueId());
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.getLanguageManager().msg("security.verify-required"));

        String id = plugin.getVerifyManager().submit(player.getName(), message, false, null);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().requestApproval(id, player.getName(), message);
        }
    }

    // ==================== CONSOLE COMMANDS ====================

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        String raw = event.getCommand() == null ? "" : event.getCommand().trim();
        String command = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (command.isEmpty()) return;

        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().logCommand("CONSOLE", censor("/" + command));
        }

        if (!plugin.getConfigManager().isVerifyEnabled()) return;
        if (!plugin.getConfigManager().requiresVerify(command)) return;

        if (plugin.getVerifyManager().consumeCommand("console:" + command)) return;
        if (fromTrustedPlugin()) return;

        event.setCancelled(true);
        plugin.getLogger().warning("Console command held for approval: /" + command);

        String id = plugin.getVerifyManager().submit("CONSOLE", "/" + command, true, null);
        if (plugin.getSecurityBot() != null) {
            plugin.getSecurityBot().requestApproval(id, "CONSOLE", "/" + command);
        }
    }

    // ==================== HELPERS ====================

    /** Walks the stack only when the feature is on, since it is costly. */
    private boolean fromTrustedPlugin() {
        if (!plugin.getConfigManager().isPluginDispatchDetection()) return false;

        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            String name = element.getClassName().toLowerCase(Locale.ROOT);
            for (String prefix : plugin.getConfigManager().getPluginBypass()) {
                if (name.contains(prefix)) return true;
            }
        }
        return false;
    }

    /** Replaces every argument of a password command with a marker. */
    public static String censor(String command) {
        String value = command.startsWith("/") ? command.substring(1) : command;
        String[] parts = value.trim().split("\\s+");
        if (parts.length == 0) return command;

        String base = parts[0].toLowerCase(Locale.ROOT);
        if (base.contains(":")) base = base.substring(base.indexOf(':') + 1);

        for (String sensitive : SENSITIVE) {
            if (base.equals(sensitive)) return "/" + parts[0] + " ***";
        }
        return command;
    }
}
