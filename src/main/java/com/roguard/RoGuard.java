package com.roguard;

import com.roguard.antivpn.VpnChecker;
import com.roguard.command.AuthCommand;
import com.roguard.command.RoGuardCommand;
import com.roguard.command.TwoFaCommand;
import com.roguard.config.ConfigManager;
import com.roguard.config.LanguageManager;
import com.roguard.dialog.Dialogs;
import com.roguard.dialog.PlayerDialogs;
import com.roguard.discord.ChatBot;
import com.roguard.discord.ConsoleAppender;
import com.roguard.discord.EmojiResolver;
import com.roguard.discord.SecurityBot;
import com.roguard.discord.TwoFaBot;
import com.roguard.inventory.InventoryManager;
import com.roguard.inventory.SnapshotStore;
import com.roguard.listener.*;
import com.roguard.logging.SensitiveLogFilter;
import com.roguard.manager.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public class RoGuard extends JavaPlugin {

    private static RoGuard instance;

    private ConfigManager configManager;
    private LanguageManager languageManager;

    private PlayerDataManager playerDataManager;
    private AuthManager authManager;
    private TwoFactorManager twoFactorManager;
    private VerifyManager verifyManager;
    private PunishmentManager punishmentManager;
    private LoginShieldManager loginShieldManager;

    private InventoryManager inventoryManager;
    private SnapshotStore snapshotStore;
    private VpnChecker vpnChecker;

    private Dialogs dialogs;
    private PlayerDialogs playerDialogs;

    private SecurityBot securityBot;
    private ChatBot chatBot;
    private TwoFaBot twoFaBot;

    private AuthListener authListener;

    // ==================== LIFECYCLE ====================

    @Override
    public void onLoad() {
        // Must run before any command can be logged by the server.
        try {
            SensitiveLogFilter.install();
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Password log protection failed to install", error);
        }
    }

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        languageManager = new LanguageManager(this);

        playerDataManager = new PlayerDataManager(this);
        authManager = new AuthManager(this);
        twoFactorManager = new TwoFactorManager(this);
        verifyManager = new VerifyManager(this);
        punishmentManager = new PunishmentManager(this);
        loginShieldManager = new LoginShieldManager(this);

        snapshotStore = new SnapshotStore(this);
        inventoryManager = new InventoryManager(this);
        vpnChecker = new VpnChecker(this);

        dialogs = new Dialogs(this);
        playerDialogs = new PlayerDialogs(this);

        startBots();
        registerListeners();
        registerCommands();

        if (configManager.isConsoleMirrorEnabled()) {
            try {
                ConsoleAppender.register(this);
            } catch (Throwable ignored) {}
        }

        getLogger().info("RoGuard enabled using " + configManager.getLanguageFile()
                + (Dialogs.isSupported() ? " with dialog support" : " in chat mode"));
    }

    @Override
    public void onDisable() {
        if (configManager != null) configManager.clearTemporary();
        if (playerDataManager != null) playerDataManager.saveAll();

        if (snapshotStore != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                snapshotStore.saveFromPlayer(online);
            }
        }

        ConsoleAppender.unregister();

        if (chatBot != null) chatBot.status(false);
        if (securityBot != null) securityBot.shutdown();
        if (chatBot != null) chatBot.shutdown();
        if (twoFaBot != null) twoFaBot.shutdown();

        SensitiveLogFilter.uninstall();

        getLogger().info("RoGuard disabled");
    }

    // ==================== SETUP ====================

    private void startBots() {
        String securityToken = configManager.securityToken();
        if (!ConfigManager.isPlaceholder(securityToken)) {
            try {
                securityBot = new SecurityBot(this, securityToken);
                securityBot.start();
                getLogger().info("Security bot connected");
            } catch (Exception error) {
                getLogger().log(Level.SEVERE, "Security bot failed to start", error);
            }
        }

        String chatToken = configManager.chatToken();
        if (configManager.isChatBridgeEnabled() && !ConfigManager.isPlaceholder(chatToken)) {
            try {
                chatBot = new ChatBot(this, chatToken);
                chatBot.start();
                getLogger().info("Chat bot connected");
            } catch (Exception error) {
                getLogger().log(Level.SEVERE, "Chat bot failed to start", error);
            }
        }

        String twofaToken = configManager.twofaToken();
        if (configManager.isPlayer2faEnabled() && !ConfigManager.isPlaceholder(twofaToken)) {
            try {
                twoFaBot = new TwoFaBot(this, twofaToken);
                twoFaBot.start();
                getLogger().info("Two factor bot connected");
            } catch (Exception error) {
                getLogger().log(Level.SEVERE, "Two factor bot failed to start", error);
            }
        }
    }

    private void registerListeners() {
        authListener = new AuthListener(this);

        var manager = Bukkit.getPluginManager();
        manager.registerEvents(authListener, this);
        manager.registerEvents(new PlayerListener(this), this);
        manager.registerEvents(new CommandListener(this), this);
        manager.registerEvents(new AntiVpnListener(this), this);
        manager.registerEvents(new GamemodeListener(this), this);
        manager.registerEvents(new ShopkeeperListener(this), this);

        if (Dialogs.isSupported()) {
            try {
                manager.registerEvents(new AuthDialogListener(this), this);
                manager.registerEvents(new PlayerManagerListener(this), this);
            } catch (Throwable error) {
                getLogger().warning("Dialog listeners unavailable: " + error);
            }
        }

        // Catch players that were already online during a live reload.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                authListener.begin(online);
            }
        });
    }

    private void registerCommands() {
        RoGuardCommand main = new RoGuardCommand(this);
        getCommand("roguard").setExecutor(main);
        getCommand("roguard").setTabCompleter(main);

        AuthCommand auth = new AuthCommand(this);
        getCommand("login").setExecutor(auth);
        getCommand("register").setExecutor(auth);
        getCommand("changepassword").setExecutor(auth);
        getCommand("unregister").setExecutor(auth);

        getCommand("2fa").setExecutor(new TwoFaCommand(this));
    }

    /** Reloads configuration, language and cached punishment data. */
    public void reloadEverything() {
        configManager.reload();
        languageManager.reload();
        punishmentManager.reload();
        EmojiResolver.clearCache();
    }

    // ==================== ACCESS ====================

    public static RoGuard getInstance() { return instance; }

    public ConfigManager getConfigManager() { return configManager; }
    public LanguageManager getLanguageManager() { return languageManager; }

    public PlayerDataManager getPlayerDataManager() { return playerDataManager; }
    public AuthManager getAuthManager() { return authManager; }
    public TwoFactorManager getTwoFactorManager() { return twoFactorManager; }
    public VerifyManager getVerifyManager() { return verifyManager; }
    public PunishmentManager getPunishmentManager() { return punishmentManager; }
    public LoginShieldManager getLoginShieldManager() { return loginShieldManager; }

    public InventoryManager getInventoryManager() { return inventoryManager; }
    public SnapshotStore getSnapshotStore() { return snapshotStore; }
    public VpnChecker getVpnChecker() { return vpnChecker; }

    public Dialogs getDialogs() { return dialogs; }
    public PlayerDialogs getPlayerDialogs() { return playerDialogs; }

    public SecurityBot getSecurityBot() { return securityBot; }
    public ChatBot getChatBot() { return chatBot; }
    public TwoFaBot getTwoFaBot() { return twoFaBot; }

    public AuthListener getAuthListener() { return authListener; }
}
