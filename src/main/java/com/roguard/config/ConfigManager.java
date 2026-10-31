package com.roguard.config;

import com.roguard.RoGuard;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads config.yml, setup.yml, commands.yml and userbypass.yml.
 *
 * Every value used in a hot path is cached in a field at load time so
 * event handlers never read from disk or walk a YAML tree.
 */
public class ConfigManager {

    private final RoGuard plugin;

    private FileConfiguration config;
    private FileConfiguration setup;
    private FileConfiguration commands;
    private FileConfiguration bypass;

    private File configFile;
    private File setupFile;
    private File commandsFile;
    private File bypassFile;

    // Cached feature switches
    private boolean featAuth, featPlayer2fa, featVerify, featCommandLog;
    private boolean featConsole, featAutoDeop, featAntiVpn;
    private boolean featShopkeeper, featGamemode, featChatBridge, featPlayerManager;

    // Cached auth settings
    private boolean useDialog, sessionEnabled, blindness, hideLocation, hidePlayers;
    private int minPassword, loginTimeout, maxAttempts, sessionMinutes, holdHeight;

    // Cached performance settings
    private boolean asyncDiscord, pluginDispatchDetection;
    private int consoleBatchSize, consoleBatchSeconds;

    // Cached command sets
    private final List<String> verifyRequired = new ArrayList<>();
    private final List<String> blockedCommands = new ArrayList<>();
    private final List<String> pluginBypass = new ArrayList<>();
    private final Set<String> gamemodeAliases = new HashSet<>();
    private final Set<String> shopkeeperMeta = new HashSet<>();
    private boolean shopkeeperEnabled;
    private int shopkeeperDuration;
    private boolean gamemodeEnabled;

    // Cached bypass lists
    private final Set<String> permanentBypass = ConcurrentHashMap.newKeySet();
    private final Set<String> temporaryBypass = ConcurrentHashMap.newKeySet();
    private final Set<String> vpnBypass = ConcurrentHashMap.newKeySet();

    private String languageFile = "english.yml";

    public ConfigManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== LOADING ====================

    public void load() {
        plugin.getDataFolder().mkdirs();
        new File(plugin.getDataFolder(), "playerdata").mkdirs();
        new File(plugin.getDataFolder(), "inventories").mkdirs();

        configFile = prepare("config.yml");
        setupFile = prepare("setup.yml");
        commandsFile = prepare("commands.yml");
        bypassFile = prepare("userbypass.yml");

        config = YamlConfiguration.loadConfiguration(configFile);
        setup = YamlConfiguration.loadConfiguration(setupFile);
        commands = YamlConfiguration.loadConfiguration(commandsFile);
        bypass = YamlConfiguration.loadConfiguration(bypassFile);

        cacheValues();
    }

    private File prepare(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        return file;
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(configFile);
        setup = YamlConfiguration.loadConfiguration(setupFile);
        commands = YamlConfiguration.loadConfiguration(commandsFile);
        bypass = YamlConfiguration.loadConfiguration(bypassFile);
        cacheValues();
    }

    private void cacheValues() {
        languageFile = config.getString("language", "english.yml");

        featAuth = config.getBoolean("features.auth", true);
        featPlayer2fa = config.getBoolean("features.player-2fa", true);
        featVerify = config.getBoolean("features.command-verification", true);
        featCommandLog = config.getBoolean("features.command-log", true);
        featConsole = config.getBoolean("features.console-mirror", true);
        featAutoDeop = config.getBoolean("features.auto-deop", true);
        featAntiVpn = config.getBoolean("features.anti-vpn", true);
        featShopkeeper = config.getBoolean("features.shopkeeper-protection", true);
        featGamemode = config.getBoolean("features.gamemode-protection", true);
        featChatBridge = config.getBoolean("features.chat-bridge", true);
        featPlayerManager = config.getBoolean("features.player-manager", true);

        useDialog = config.getBoolean("auth.use-dialog", true);
        minPassword = config.getInt("auth.min-password-length", 6);
        loginTimeout = config.getInt("auth.login-timeout", 120);
        maxAttempts = config.getInt("auth.max-login-attempts", 5);
        sessionEnabled = config.getBoolean("auth.session-enabled", false);
        sessionMinutes = config.getInt("auth.session-timeout-minutes", 30);
        blindness = config.getBoolean("auth.chat-login.blindness", true);
        hideLocation = config.getBoolean("auth.chat-login.hide-location", true);
        holdHeight = config.getInt("auth.chat-login.hold-height", 320);
        hidePlayers = config.getBoolean("auth.chat-login.hide-players", true);

        asyncDiscord = config.getBoolean("performance.async-discord", true);
        pluginDispatchDetection = config.getBoolean("performance.plugin-dispatch-detection", true);
        consoleBatchSize = config.getInt("performance.console-batch-size", 20);
        consoleBatchSeconds = config.getInt("performance.console-batch-seconds", 3);

        verifyRequired.clear();
        for (String entry : commands.getStringList("verify-required")) {
            verifyRequired.add(normalise(entry));
        }

        blockedCommands.clear();
        for (String entry : commands.getStringList("blocked")) {
            blockedCommands.add(normalise(entry));
        }

        pluginBypass.clear();
        for (String entry : commands.getStringList("plugin-bypass")) {
            pluginBypass.add(entry.toLowerCase(Locale.ROOT));
        }

        gamemodeEnabled = commands.getBoolean("gamemode.enabled", true);
        gamemodeAliases.clear();
        for (String entry : commands.getStringList("gamemode.aliases")) {
            gamemodeAliases.add(normalise(entry));
        }

        shopkeeperEnabled = commands.getBoolean("shopkeeper.enabled", true);
        shopkeeperDuration = commands.getInt("shopkeeper.bypass-duration", 60);
        shopkeeperMeta.clear();
        for (String entry : commands.getStringList("shopkeeper.detect-metadata")) {
            shopkeeperMeta.add(entry.toLowerCase(Locale.ROOT));
        }

        permanentBypass.clear();
        for (String name : bypass.getStringList("permanent")) {
            permanentBypass.add(name.toLowerCase(Locale.ROOT));
        }
        temporaryBypass.clear();
        for (String name : bypass.getStringList("temporary")) {
            temporaryBypass.add(name.toLowerCase(Locale.ROOT));
        }

        vpnBypass.clear();
        for (String name : setup.getStringList("anti-vpn.bypass-players")) {
            vpnBypass.add(name.toLowerCase(Locale.ROOT));
        }
    }

    private String normalise(String command) {
        String value = command.toLowerCase(Locale.ROOT).trim();
        if (value.startsWith("/") && !value.equals("//")) value = value.substring(1);
        return value;
    }

    // ==================== SAVING ====================

    public void save() {
        try { config.save(configFile); } catch (IOException ignored) {}
        try { setup.save(setupFile); } catch (IOException ignored) {}
    }

    public void setFeature(String key, boolean value) {
        config.set("features." + key, value);
    }

    public void setAuthDialog(boolean value) {
        config.set("auth.use-dialog", value);
    }

    // ==================== FEATURES ====================

    public boolean isAuthEnabled() { return featAuth; }
    public boolean isPlayer2faEnabled() { return featPlayer2fa; }
    public boolean isVerifyEnabled() { return featVerify; }
    public boolean isCommandLogEnabled() { return featCommandLog; }
    public boolean isConsoleMirrorEnabled() { return featConsole; }
    public boolean isAutoDeopEnabled() { return featAutoDeop; }
    public boolean isAntiVpnEnabled() { return featAntiVpn; }
    public boolean isShopkeeperEnabled() { return featShopkeeper && shopkeeperEnabled; }
    public boolean isGamemodeProtectionEnabled() { return featGamemode && gamemodeEnabled; }
    public boolean isChatBridgeEnabled() { return featChatBridge; }
    public boolean isPlayerManagerEnabled() { return featPlayerManager; }

    public boolean isFeature(String key) {
        return switch (key) {
            case "auth" -> featAuth;
            case "player2fa" -> featPlayer2fa;
            case "commandverification" -> featVerify;
            case "commandlog" -> featCommandLog;
            case "consolemirror" -> featConsole;
            case "autodeop" -> featAutoDeop;
            case "antivpn" -> featAntiVpn;
            case "shopkeeper" -> featShopkeeper;
            case "gamemode" -> featGamemode;
            case "chatbridge" -> featChatBridge;
            default -> false;
        };
    }

    /** Maps a dialog switch id to the config path under features. */
    public String featurePath(String id) {
        return switch (id) {
            case "auth" -> "auth";
            case "player2fa" -> "player-2fa";
            case "commandverification" -> "command-verification";
            case "commandlog" -> "command-log";
            case "consolemirror" -> "console-mirror";
            case "autodeop" -> "auto-deop";
            case "antivpn" -> "anti-vpn";
            case "shopkeeper" -> "shopkeeper-protection";
            case "gamemode" -> "gamemode-protection";
            case "chatbridge" -> "chat-bridge";
            default -> id;
        };
    }

    // ==================== AUTH ====================

    public boolean useDialog() { return useDialog; }
    public int getMinPassword() { return minPassword; }
    public int getLoginTimeout() { return loginTimeout; }
    public int getMaxAttempts() { return maxAttempts; }
    public boolean isSessionEnabled() { return sessionEnabled; }
    public int getSessionMinutes() { return sessionMinutes; }
    public boolean useBlindness() { return blindness; }
    public boolean hideLocation() { return hideLocation; }
    public int getHoldHeight() { return holdHeight; }
    public boolean hidePlayers() { return hidePlayers; }

    // ==================== PERFORMANCE ====================

    public boolean isAsyncDiscord() { return asyncDiscord; }
    public boolean isPluginDispatchDetection() { return pluginDispatchDetection; }
    public int getConsoleBatchSize() { return consoleBatchSize; }
    public int getConsoleBatchSeconds() { return consoleBatchSeconds; }

    // ==================== COMMANDS ====================

    public boolean requiresVerify(String command) {
        String cmd = stripSlash(command);
        for (String prefix : verifyRequired) {
            if (prefix.equals("//")) {
                if (cmd.startsWith("/")) return true;
                continue;
            }
            if (cmd.equals(prefix) || cmd.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    public boolean isBlocked(String command) {
        String cmd = stripSlash(command);
        for (String prefix : blockedCommands) {
            if (cmd.equals(prefix) || cmd.startsWith(prefix + " ")) return true;
        }
        return false;
    }

    public boolean isGamemodeCommandProtected() {
        if (!isGamemodeProtectionEnabled()) return false;
        for (String alias : gamemodeAliases) {
            if (verifyRequired.contains(alias)) return true;
        }
        return true;
    }

    public List<String> getPluginBypass() { return pluginBypass; }
    public Set<String> getShopkeeperMetadata() { return shopkeeperMeta; }
    public int getShopkeeperDuration() { return shopkeeperDuration; }

    private String stripSlash(String command) {
        String cmd = command.toLowerCase(Locale.ROOT).trim();
        if (cmd.startsWith("/") && !cmd.startsWith("//")) cmd = cmd.substring(1);
        int colon = cmd.indexOf(':');
        int space = cmd.indexOf(' ');
        if (colon > 0 && (space < 0 || colon < space)) cmd = cmd.substring(colon + 1);
        return cmd;
    }

    // ==================== BYPASS ====================

    public boolean isBypassed(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return permanentBypass.contains(lower) || temporaryBypass.contains(lower);
    }

    public void removeTemporary(String name) {
        if (temporaryBypass.remove(name.toLowerCase(Locale.ROOT))) saveBypass();
    }

    public void clearTemporary() {
        if (!temporaryBypass.isEmpty()) {
            temporaryBypass.clear();
            saveBypass();
        }
    }

    private void saveBypass() {
        bypass.set("temporary", new ArrayList<>(temporaryBypass));
        try { bypass.save(bypassFile); } catch (IOException ignored) {}
    }

    public boolean isVpnBypassed(String name) {
        return vpnBypass.contains(name.toLowerCase(Locale.ROOT));
    }

    // ==================== SETUP ACCESS ====================

    public String securityToken() { return setup.getString("security-bot.token", ""); }
    public String channelVerify() { return setup.getString("security-bot.channels.verify", ""); }
    public String channelCommandLog() { return setup.getString("security-bot.channels.command-log", ""); }
    public String channelConsole() { return setup.getString("security-bot.channels.console", ""); }
    public String channelIpLog() { return setup.getString("security-bot.channels.ip-log", ""); }
    public int verifyTimeout() { return setup.getInt("security-bot.verify-timeout", 60); }
    public List<String> consoleAllowed() { return setup.getStringList("security-bot.console-allowed-users"); }

    public String twofaToken() { return setup.getString("twofa-bot.token", ""); }
    public String channelLink() { return setup.getString("twofa-bot.channels.link", ""); }
    public String channelLinkLog() { return setup.getString("twofa-bot.channels.link-log", ""); }
    public int codeExpire() { return setup.getInt("twofa-bot.code-expire", 300); }

    public String chatToken() { return setup.getString("chat-bot.token", ""); }
    public String channelChat() { return setup.getString("chat-bot.channel", ""); }
    public String chatWebhook() { return setup.getString("chat-bot.webhook", ""); }
    public List<String> ipResponse() { return setup.getStringList("chat-bot.ip-response"); }

    public String vpnKickMessage() { return setup.getString("anti-vpn.kick-message", "&cVPN detected."); }
    public boolean allowSelfInventory() { return setup.getBoolean("player-manager.allow-self-inventory", true); }
    public int pageSize() { return Math.max(5, setup.getInt("player-manager.page-size", 20)); }

    public String getLanguageFile() { return languageFile; }

    public static boolean isPlaceholder(String token) {
        return token == null || token.isBlank() || token.startsWith("YOUR_");
    }
}
