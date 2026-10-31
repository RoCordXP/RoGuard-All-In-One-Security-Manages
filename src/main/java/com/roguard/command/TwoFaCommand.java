package com.roguard.command;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Links or unlinks a Discord account for two factor verification. */
public class TwoFaCommand implements CommandExecutor {

    private final RoGuard plugin;

    public TwoFaCommand(RoGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getLanguageManager().msg("general.player-only"));
            return true;
        }

        if (!plugin.getConfigManager().isPlayer2faEnabled()) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.disabled"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(plugin.getLanguageManager().msg("2fa.usage"));
            return true;
        }

        PlayerDataManager.PlayerData data =
                plugin.getPlayerDataManager().get(player.getUniqueId());

        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (data.isLinked()) {
                    player.sendMessage(plugin.getLanguageManager().msg("2fa.already-linked"));
                    return true;
                }
                String code = plugin.getTwoFactorManager().generateCode(player.getUniqueId());
                player.sendMessage(plugin.getLanguageManager()
                        .msg("2fa.code-sent", "%code%", code));
                player.sendMessage(plugin.getLanguageManager().msg("2fa.code-hint"));
            }
            case "disable" -> {
                if (!data.isLinked()) {
                    player.sendMessage(plugin.getLanguageManager().msg("2fa.not-linked"));
                    return true;
                }
                data.setLinked(false);
                data.setDiscordId(null);
                plugin.getPlayerDataManager().save(player.getUniqueId());
                player.sendMessage(plugin.getLanguageManager().msg("2fa.disable-success"));
            }
            default -> player.sendMessage(plugin.getLanguageManager().msg("2fa.usage"));
        }
        return true;
    }
}
