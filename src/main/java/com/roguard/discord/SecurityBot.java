package com.roguard.discord;

import com.roguard.RoGuard;
import com.roguard.logging.SensitiveLogFilter;
import com.roguard.manager.VerifyManager;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Approvals, command logging, VPN reports and the console mirror. */
public class SecurityBot extends ListenerAdapter {

    private static final Color ORANGE = new Color(0xFFB347);
    private static final Color GREEN = new Color(0x7CFF9E);
    private static final Color RED = new Color(0xFF6B6B);
    private static final Color BLUE = new Color(0x5BC8FF);

    private final RoGuard plugin;
    private final String token;
    private JDA jda;

    private final List<String> consoleBuffer = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public SecurityBot(RoGuard plugin, String token) {
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

        if (plugin.getConfigManager().isConsoleMirrorEnabled()) {
            int seconds = plugin.getConfigManager().getConsoleBatchSeconds();
            scheduler.scheduleAtFixedRate(this::flush, seconds, seconds, TimeUnit.SECONDS);
        }
    }

    public void shutdown() {
        flush();
        scheduler.shutdownNow();
        if (jda != null) jda.shutdown();
    }

    public JDA getJda() { return jda; }

    private String lang(String key, String... pairs) {
        return EmojiResolver.resolve(
                plugin.getLanguageManager().plain("discord." + key, pairs), jda);
    }

    private TextChannel channel(String id) {
        if (jda == null || id == null || id.isBlank()) return null;
        try {
            return jda.getTextChannelById(id);
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== APPROVALS ====================

    public void requestApproval(String id, String sender, String command) {
        TextChannel channel = channel(plugin.getConfigManager().channelVerify());
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(lang("verify-title"))
                .setColor(ORANGE)
                .addField(lang("log-player"), sender, true)
                .addField(lang("log-command"), "`" + command + "`", true)
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build())
                .setActionRow(
                        Button.success("ok_" + id, lang("btn-approve")),
                        Button.danger("no_" + id, lang("btn-deny")))
                .queue();
    }

    public void logCommand(String sender, String command) {
        if (!plugin.getConfigManager().isCommandLogEnabled()) return;

        TextChannel channel = channel(plugin.getConfigManager().channelCommandLog());
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(BLUE)
                .addField(lang("log-player"), sender, true)
                .addField(lang("log-command"), "`" + command + "`", true)
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    public void logVpn(String name, String ip, String reason) {
        TextChannel channel = channel(plugin.getConfigManager().channelIpLog());
        if (channel == null) return;

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(lang("vpn-title"))
                .setColor(RED)
                .addField(lang("vpn-player"), name, true)
                .addField(lang("vpn-address"), "`" + ip + "`", true)
                .addField(lang("vpn-reason"), reason, true)
                .setTimestamp(Instant.now());

        channel.sendMessageEmbeds(embed.build()).queue();
    }

    // ==================== BUTTONS ====================

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        boolean approve = id.startsWith("ok_");
        boolean deny = id.startsWith("no_");
        if (!approve && !deny) return;

        VerifyManager.Pending pending = plugin.getVerifyManager().consume(id.substring(3));
        if (pending == null) {
            event.reply(lang("verify-expired")).setEphemeral(true).queue();
            return;
        }

        if (approve) {
            Bukkit.getScheduler().runTask(plugin, () -> apply(pending));
        } else if (!pending.console()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayerExact(pending.sender());
                if (player != null) {
                    player.sendMessage(plugin.getLanguageManager().msg("security.command-denied"));
                }
            });
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setTitle(lang(approve ? "verify-approved" : "verify-denied"))
                .setColor(approve ? GREEN : RED)
                .addField(lang("log-player"), pending.sender(), true)
                .addField(lang("log-command"), "`" + pending.command() + "`", true)
                .setFooter(event.getUser().getName())
                .setTimestamp(Instant.now());

        event.editMessageEmbeds(embed.build()).setComponents().queue();
    }

    /** Runs an approved action on the main thread. */
    private void apply(VerifyManager.Pending pending) {
        if (VerifyManager.ACTION_SHOPKEEPER.equals(pending.action())) {
            Player player = Bukkit.getPlayerExact(pending.sender());
            if (player == null) return;

            plugin.getVerifyManager().allowShop(player.getUniqueId());
            player.sendMessage(plugin.getLanguageManager().msg("security.shopkeeper-approved",
                    "%time%", String.valueOf(plugin.getConfigManager().getShopkeeperDuration())));
            return;
        }

        String command = pending.command().startsWith("/")
                ? pending.command().substring(1) : pending.command();

        if (pending.console()) {
            plugin.getVerifyManager().approveCommand("console:" + command);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            return;
        }

        Player player = Bukkit.getPlayerExact(pending.sender());
        if (player == null) return;

        plugin.getVerifyManager().approveCommand(player.getName() + ":" + command);
        plugin.getVerifyManager().allowGamemode(player.getUniqueId());
        player.performCommand(command);
        player.sendMessage(plugin.getLanguageManager().msg("security.command-approved"));
    }

    // ==================== CONSOLE CHANNEL ====================

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        if (!plugin.getConfigManager().isConsoleMirrorEnabled()) return;

        String channelId = plugin.getConfigManager().channelConsole();
        if (channelId == null || channelId.isBlank()) return;
        if (!event.getChannel().getId().equals(channelId)) return;

        String raw = event.getMessage().getContentRaw().trim();
        if (raw.isEmpty() || raw.startsWith("[") || raw.startsWith("```")) return;

        List<String> allowed = plugin.getConfigManager().consoleAllowed();
        if (allowed != null && !allowed.isEmpty() && !allowed.contains(event.getAuthor().getId())) {
            event.getChannel().sendMessage(lang("verify-expired")).queue();
            return;
        }

        String command = raw.startsWith("/") ? raw.substring(1).trim() : raw;
        if (command.isEmpty()) return;

        String sender = "Discord:" + event.getAuthor().getName();

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean restricted = plugin.getConfigManager().isVerifyEnabled()
                        && plugin.getConfigManager().requiresVerify(command);

                if (restricted) {
                    String id = plugin.getVerifyManager().submit(sender, "/" + command, true, null);
                    requestApproval(id, sender, "/" + command);
                    event.getChannel().sendMessage("`" + lang("console-pending")
                            + " /" + command + "`").queue();
                    return;
                }

                plugin.getVerifyManager().approveCommand("console:" + command);
                boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                event.getChannel().sendMessage("`" + lang(ok ? "console-ran" : "verify-denied")
                        + " /" + command + "`").queue();
            } catch (Exception error) {
                event.getChannel().sendMessage("`" + error.getMessage() + "`").queue();
            }
        });
    }

    public void mirror(String line) {
        if (jda == null || line == null) return;
        if (!plugin.getConfigManager().isConsoleMirrorEnabled()) return;
        if (SensitiveLogFilter.shouldDrop(line)) return;

        String clean = line
                .replaceAll("\u001B\\[[;\\d]*m", "")
                .replaceAll("^\\[\\d{2}:\\d{2}:\\d{2}.*?\\]\\s*", "")
                .trim();

        if (clean.isEmpty() || clean.length() > 220) return;

        synchronized (consoleBuffer) {
            consoleBuffer.add(clean);
            if (consoleBuffer.size() >= plugin.getConfigManager().getConsoleBatchSize()) flush();
        }
    }

    private void flush() {
        if (jda == null) return;

        List<String> lines;
        synchronized (consoleBuffer) {
            if (consoleBuffer.isEmpty()) return;
            lines = new ArrayList<>(consoleBuffer);
            consoleBuffer.clear();
        }

        TextChannel channel = channel(plugin.getConfigManager().channelConsole());
        if (channel == null) return;

        StringBuilder out = new StringBuilder("```\n");
        for (String line : lines) out.append(line).append('\n');
        out.append("```");

        String message = out.length() > 1990 ? out.substring(0, 1987) + "```" : out.toString();
        channel.sendMessage(message).queue();
    }
}
