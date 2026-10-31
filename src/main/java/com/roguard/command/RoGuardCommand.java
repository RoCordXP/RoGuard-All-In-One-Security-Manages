package com.roguard.command;

import com.roguard.RoGuard;
import com.roguard.dialog.Dialogs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class RoGuardCommand implements CommandExecutor, TabCompleter {

    private final RoGuard plugin;

    public RoGuardCommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!check(sender, "roguard.reload")) return true;
                plugin.reloadEverything();
                sender.sendMessage(plugin.getLanguageManager().msg("general.reloaded"));
            }
            case "status" -> {
                if (!check(sender, "roguard.status")) return true;
                status(sender);
            }
            case "settings" -> {
                if (!check(sender, "roguard.settings")) return true;
                openSettings(sender);
            }
            case "playermanager", "pm" -> {
                if (!check(sender, "roguard.playermanager")) return true;
                openManager(sender);
            }
            default -> sender.sendMessage(plugin.getLanguageManager().msg("general.unknown"));
        }
        return true;
    }

    private boolean check(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
        return false;
    }

    private void status(CommandSender sender) {
        sender.sendMessage(Component.text("RoGuard", NamedTextColor.AQUA));
        line(sender, "Security bot", plugin.getSecurityBot() != null);
        line(sender, "Chat bot", plugin.getChatBot() != null);
        line(sender, "2FA bot", plugin.getTwoFaBot() != null);
        line(sender, "Dialogs", Dialogs.isSupported());
        line(sender, "Auth", plugin.getConfigManager().isAuthEnabled());
        line(sender, "Player 2FA", plugin.getConfigManager().isPlayer2faEnabled());
        line(sender, "Command approval", plugin.getConfigManager().isVerifyEnabled());
        line(sender, "Command log", plugin.getConfigManager().isCommandLogEnabled());
        line(sender, "Console mirror", plugin.getConfigManager().isConsoleMirrorEnabled());
        line(sender, "Auto deop", plugin.getConfigManager().isAutoDeopEnabled());
        line(sender, "Anti VPN", plugin.getConfigManager().isAntiVpnEnabled());
        line(sender, "Shop protection", plugin.getConfigManager().isShopkeeperEnabled());
        line(sender, "Gamemode lock", plugin.getConfigManager().isGamemodeProtectionEnabled());
        line(sender, "Chat bridge", plugin.getConfigManager().isChatBridgeEnabled());
    }

    private void line(CommandSender sender, String label, boolean enabled) {
        sender.sendMessage(Component.text(label + "  ", NamedTextColor.GRAY)
                .append(Component.text(enabled ? "on" : "off",
                        enabled ? NamedTextColor.GREEN : NamedTextColor.RED)));
    }

    private void openSettings(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return;
        }
        if (!Dialogs.isSupported()) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            return;
        }
        try {
            player.showDialog(plugin.getDialogs().settings());
        } catch (Throwable error) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            plugin.getLogger().warning("Settings dialog failed: " + error);
        }
    }

    private void openManager(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return;
        }
        if (!plugin.getConfigManager().isPlayerManagerEnabled()) {
            player.sendMessage(plugin.getLanguageManager().msg("general.unknown"));
            return;
        }
        if (!Dialogs.isSupported()) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            return;
        }
        try {
            player.showDialog(plugin.getPlayerDialogs().list("", 0));
        } catch (Throwable error) {
            player.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            plugin.getLogger().warning("Player manager dialog failed: " + error);
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(Component.text("RoGuard", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("/roguard reload", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/roguard status", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/roguard settings", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/roguard playermanager", NamedTextColor.YELLOW));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length != 1) return options;

        if (sender.hasPermission("roguard.reload")) options.add("reload");
        if (sender.hasPermission("roguard.status")) options.add("status");
        if (sender.hasPermission("roguard.settings")) options.add("settings");
        if (sender.hasPermission("roguard.playermanager")) options.add("playermanager");

        options.removeIf(option -> !option.startsWith(args[0].toLowerCase()));
        return options;
    }
}
