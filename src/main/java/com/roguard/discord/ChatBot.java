package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.internal.utils.JDALogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.awt.Color;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Minecraft and Discord chat bridge. */
public class ChatBot extends ListenerAdapter {

    private static final Color GREEN = new Color(0x7CFF9E);
    private static final Color RED = new Color(0xFF6B6B);
    private static final Color GREY = new Color(0x4A4A4A);
    private static final Color GOLD = new Color(0xFFD35B);
    private static final Color BLUE = new Color(0x5BC8FF);

    private final RoGuard plugin;
    private final String token;
    private JDA jda;

    public ChatBot(RoGuard plugin, String token) {
        this.plugin = plugin;
        this.token = token;
    }

    public void start() throws Exception {
        try { JDALogger.setFallbackLoggerEnabled(false); } catch (Exception ignored) {}

        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_EMOJIS_AND_STICKERS)
                .enableCache(CacheFlag.EMOJI)
                .disableCache(CacheFlag.VOICE_STATE, CacheFlag.STICKER, CacheFlag.SCHEDULED_EVENTS)
                .addEventListeners(this)
                .build();
        jda.awaitReady();

        status(true);
    }

    public void shutdown() {
        if (jda != null) jda.shutdown();
    }

    public JDA getJda() { return jda; }

    private String lang(String key, String... pairs) {
        return EmojiResolver.resolve(
                plugin.getLanguageManager().plain("discord." + key, pairs), jda);
    }

    private TextChannel channel() {
        if (jda == null) return null;
        String id = plugin.getConfigManager().channelChat();
        if (id == null || id.isBlank()) return null;
        try {
            return jda.getTextChannelById(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== OUTGOING ====================

    public void status(boolean online) {
        TextChannel channel = channel();
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setDescription(lang(online ? "server-start" : "server-stop"))
                .setColor(online ? GREEN : RED)
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    /** Player chat. Uses a webhook so the skin and name show up. */
    public void chat(String name, String message) {
        String clean = sanitise(TextUtil.stripAll(message));
        String webhook = plugin.getConfigManager().chatWebhook();

        if (webhook == null || webhook.isBlank()) {
            TextChannel channel = channel();
            if (channel != null) channel.sendMessage("**" + name + "** " + clean).queue();
            return;
        }

        Bukkit.getServer().getAsyncScheduler().runNow(plugin, task -> {
            String json = "{\"username\":\"" + escape(name)
                    + "\",\"avatar_url\":\"https://mc-heads.net/avatar/" + name
                    + "/128\",\"content\":\"" + escape(clean) + "\"}";
            post(webhook, json);
        });
    }

    public void joinLeave(String name, boolean joined, int online, int max) {
        TextChannel channel = channel();
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setThumbnail("https://mc-heads.net/avatar/" + name + "/64")
                .setColor(joined ? GREEN : RED)
                .setDescription(lang(joined ? "player-join" : "player-leave", "%player%", name))
                .setFooter(lang("online-footer",
                        "%online%", String.valueOf(online),
                        "%max%", String.valueOf(max)))
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    /** Death notice. All colour markers are removed first. */
    public void death(String message) {
        TextChannel channel = channel();
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(GREY)
                .setDescription(lang("player-death", "%message%", TextUtil.stripAll(message)))
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    public void advancement(String name, String advancement) {
        TextChannel channel = channel();
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(GOLD)
                .setDescription(lang("player-advancement",
                        "%player%", name,
                        "%advancement%", advancement))
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    // ==================== INCOMING ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;

        String message = event.getMessage().getContentRaw().trim();
        if (message.isEmpty()) return;

        String lower = message.toLowerCase();
        String author = event.getMember() != null
                ? event.getMember().getEffectiveName() : event.getAuthor().getName();

        if (lower.equals("ip")) {
            List<String> lines = plugin.getConfigManager().ipResponse();
            if (lines == null || lines.isEmpty()) return;

            EmbedBuilder embed = new EmbedBuilder()
                    .setTitle(lang("ip-title"))
                    .setColor(BLUE)
                    .setDescription(EmojiResolver.resolve(String.join("\n", lines), jda))
                    .setTimestamp(Instant.now());

            event.getChannel().sendMessageEmbeds(embed.build()).queue();
            return;
        }

        if (lower.equals("online") || lower.equals("playerlist")) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Collection<? extends Player> players = Bukkit.getOnlinePlayers();

                EmbedBuilder embed = new EmbedBuilder()
                        .setTitle(lang("online-title"))
                        .setColor(GREEN)
                        .setDescription(players.isEmpty()
                                ? lang("online-empty")
                                : players.stream().map(Player::getName)
                                    .collect(Collectors.joining(", ")))
                        .setFooter(lang("online-footer",
                                "%online%", String.valueOf(players.size()),
                                "%max%", String.valueOf(Bukkit.getMaxPlayers())))
                        .setTimestamp(Instant.now());

                event.getChannel().sendMessageEmbeds(embed.build()).queue();
            });
            return;
        }

        String chatChannel = plugin.getConfigManager().channelChat();
        if (!event.getChannel().getId().equals(chatChannel)) return;

        String formatted = TextUtil.colour(plugin.getLanguageManager()
                .plain("discord.to-minecraft", "%player%", author, "%message%", message));

        Bukkit.getScheduler().runTask(plugin,
                () -> Bukkit.broadcast(TextUtil.component(formatted)));
    }

    // ==================== HELPERS ====================

    private void post(String url, String json) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            try (OutputStream stream = connection.getOutputStream()) {
                stream.write(json.getBytes(StandardCharsets.UTF_8));
            }
            connection.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "");
    }

    private String sanitise(String value) {
        return value.replace("@everyone", "@ everyone").replace("@here", "@ here");
    }
}
