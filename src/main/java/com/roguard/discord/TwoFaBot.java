package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.manager.PlayerDataManager;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Account linking and login approval for players. */
public class TwoFaBot extends ListenerAdapter {

    private static final Color GREEN = new Color(0x7CFF9E);
    private static final Color RED = new Color(0xFF6B6B);
    private static final Color BLUE = new Color(0x5BC8FF);

    private final RoGuard plugin;
    private final String token;
    private JDA jda;

    public TwoFaBot(RoGuard plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start() throws Exception {
        JDALogger.setFallbackLoggerEnabled(false);

        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.DIRECT_MESSAGES, GatewayIntent.GUILD_EMOJIS_AND_STICKERS)
                .enableCache(CacheFlag.EMOJI)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .addEventListeners(this)
                .build();
        jda.awaitReady();
    }

    public void shutdown() {
        if (jda != null) jda.shutdown();
    }

    public JDA getJda() { return jda; }

    private String lang(String key, String... pairs) {
        return EmojiResolver.resolve(
                plugin.getLanguageManager().plain("discord." + key, pairs), jda);
    }

    // ==================== LINK CODES ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        String linkChannel = plugin.getConfigManager().channelLink();
        if (linkChannel == null || linkChannel.isBlank()) return;
        if (!event.getChannel().getId().equals(linkChannel)) return;

        String code = event.getMessage().getContentRaw().trim();
        if (!code.matches("\\d{6}")) {
            event.getMessage().delete().queue(s -> {}, e -> {});
            return;
        }

        UUID uuid = plugin.getTwoFactorManager().consumeCode(code);
        event.getMessage().delete().queue(s -> {}, e -> {});

        if (uuid == null) {
            event.getChannel().sendMessage(lang("link-invalid"))
                    .queue(msg -> msg.delete().queueAfter(6, TimeUnit.SECONDS));
            return;
        }

        String discordId = event.getAuthor().getId();
        String discordName = event.getAuthor().getName();

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        data.setDiscordId(discordId);
        data.setLinked(true);
        plugin.getPlayerDataManager().save(uuid);

        String mcName = data.getName() == null ? uuid.toString() : data.getName();

        // Confirm privately so the public channel stays clean.
        event.getAuthor().openPrivateChannel().queue(dm -> {
            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(lang("link-title"))
                    .setColor(GREEN)
                    .addField("Minecraft", mcName, true)
                    .addField("Discord", discordName, true)
                    .setTimestamp(Instant.now());
            dm.sendMessageEmbeds(embed.build()).queue(s -> {}, e -> {});
        }, e -> {});

        logLink(mcName, discordName, discordId);

        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.kick(plugin.getLanguageManager().component("2fa.link-kick"));
            }
        });
    }

    private void logLink(String mcName, String discordName, String discordId) {
        String id = plugin.getConfigManager().channelLinkLog();
        if (id == null || id.isBlank()) return;

        TextChannel channel = jda.getTextChannelById(id);
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(lang("link-log-title"))
                .setColor(BLUE)
                .addField("Minecraft", mcName, true)
                .addField("Discord", discordName + "\n`" + discordId + "`", true)
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    // ==================== LOGIN APPROVAL ====================

    public void sendVerification(String discordId, String playerName, String requestId) {
        if (jda == null) return;

        jda.retrieveUserById(discordId).queue(user -> {
            if (user == null) return;

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(lang("twofa-title"))
                    .setColor(GREEN)
                    .setDescription(lang("twofa-body"))
                    .addField("Minecraft", playerName, true)
                    .setTimestamp(Instant.now());

            user.openPrivateChannel().queue(dm -> dm.sendMessageEmbeds(embed.build())
                    .setActionRow(
                            Button.success("fa_ok_" + requestId, lang("btn-verify")),
                            Button.danger("fa_no_" + requestId, lang("btn-reject")))
                    .queue(s -> {}, e -> {}), e -> {});
        }, e -> {});
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean approve = id.startsWith("fa_ok_");
        boolean deny = id.startsWith("fa_no_");
        if (!approve && !deny) return;

        UUID uuid = plugin.getTwoFactorManager().consumeRequest(id.substring(6));
        if (uuid == null) {
            event.reply(lang("verify-expired")).setEphemeral(true).queue();
            return;
        }

        if (approve) {
            plugin.getTwoFactorManager().approve(uuid);
        } else {
            plugin.getTwoFactorManager().removePending(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    player.kick(plugin.getLanguageManager().component("2fa.verify-denied"));
                }
            });
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(lang(approve ? "twofa-approved" : "twofa-denied"))
                .setColor(approve ? GREEN : RED)
                .setDescription(lang(approve ? "twofa-approved-body" : "twofa-denied-body"))
                .setTimestamp(Instant.now());

        event.editMessageEmbeds(embed.build()).setComponents().queue();
    }
}
