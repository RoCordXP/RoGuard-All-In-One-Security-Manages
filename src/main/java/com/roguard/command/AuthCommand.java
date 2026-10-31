package com.roguard.command;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Chat based authentication for clients without dialog support. */
public class AuthCommand implements CommandExecutor {

    private final RoGuard plugin;

    public AuthCommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return switch (command.getName().toLowerCase()) {
            case "login" -> login(sender, args);
            case "register" -> register(sender, args);
            case "changepassword" -> changePassword(sender, args);
            case "unregister" -> unregister(sender, args);
            default -> true;
        };
    }

    private boolean login(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.login-usage"));
            return true;
        }

        if (plugin.getAuthManager().isAuthenticated(player.getUniqueId())) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.login-already"));
            return true;
        }

        PlayerDataManager.PlayerData data =
                plugin.getPlayerDataManager().get(player.getUniqueId());
        if (!data.isRegistered()) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.not-registered"));
            return true;
        }

        if (!plugin.getAuthManager().verify(player.getUniqueId(), args[0])) {
            int used = plugin.getAuthManager().addAttempt(player.getUniqueId());
            if (used >= plugin.getConfigManager().getMaxAttempts()) {
                player.kick(plugin.getLanguageManager().component("auth.kick-attempts"));
                return true;
            }
            player.sendMessage(plugin.getLanguageManager().msg("auth.login-wrong"));
            return true;
        }

        plugin.getAuthManager().markAuthenticated(player.getUniqueId());
        plugin.getAuthManager().recordLogin(player.getUniqueId(), player.getName(), address(player));
        player.sendMessage(plugin.getLanguageManager()
                .msg("auth.login-success", "%player%", player.getName()));

        plugin.getAuthListener().afterLogin(player, true);
        return true;
    }

    private boolean register(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.register-usage"));
            return true;
        }

        PlayerDataManager.PlayerData data =
                plugin.getPlayerDataManager().get(player.getUniqueId());
        if (data.isRegistered()) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.register-already"));
            return true;
        }

        if (!args[0].equals(args[1])) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.register-mismatch"));
            return true;
        }

        int min = plugin.getConfigManager().getMinPassword();
        if (args[0].length() < min) {
            player.sendMessage(plugin.getLanguageManager()
                    .msg("auth.register-short", "%min%", String.valueOf(min)));
            return true;
        }

        plugin.getAuthManager().register(player.getUniqueId(),
                player.getName(), args[0], address(player));
        plugin.getAuthManager().markAuthenticated(player.getUniqueId());
        plugin.getAuthManager().recordLogin(
                player.getUniqueId(), player.getName(), address(player));
        player.sendMessage(plugin.getLanguageManager().msg("auth.register-success"));

        plugin.getAuthListener().afterLogin(player, true);
        return true;
    }

    private boolean changePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return true;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-usage"));
            return true;
        }

        if (args[0].equals(args[1])) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-same"));
            return true;
        }

        int min = plugin.getConfigManager().getMinPassword();
        if (args[1].length() < min) {
            player.sendMessage(plugin.getLanguageManager()
                    .msg("auth.register-short", "%min%", String.valueOf(min)));
            return true;
        }

        if (plugin.getAuthManager().changePassword(player.getUniqueId(), args[0], args[1])) {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-success"));
        } else {
            player.sendMessage(plugin.getLanguageManager().msg("auth.changepass-wrong"));
        }
        return true;
    }

    private boolean unregister(CommandSender sender, String[] args) {
        if (!sender.hasPermission("roguard.unregister")) {
            sender.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(plugin.getLanguageManager().msg("auth.unregister-usage"));
            return true;
        }

        if (plugin.getPlayerDataManager().delete(args[0])) {
            sender.sendMessage(plugin.getLanguageManager()
                    .msg("auth.unregister-success", "%player%", args[0]));
        } else {
            sender.sendMessage(plugin.getLanguageManager()
                    .msg("auth.unregister-missing", "%player%", args[0]));
        }
        return true;
    }

    private String address(Player player) {
        return player.getAddress() == null
                ? "" : player.getAddress().getAddress().getHostAddress();
    }
}
