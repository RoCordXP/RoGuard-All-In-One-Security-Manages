package com.roguard.listener;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import com.roguard.RoGuard;
import com.roguard.dialog.Dialogs;
import com.roguard.manager.PlayerDataManager;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shows the login form during the configuration phase so the player never
 * enters the world unauthenticated and the vanilla background stays visible.
 */
public class AuthDialogListener implements Listener {

    private final RoGuard plugin;

    private final Map<UUID, CompletableFuture<Boolean>> waiting = new ConcurrentHashMap<>();
    private final Map<UUID, Audience> audiences = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, String> failures = new ConcurrentHashMap<>();
    private final Set<UUID> closed = ConcurrentHashMap.newKeySet();

    public AuthDialogListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== CONFIGURATION PHASE ====================

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        if (!plugin.getConfigManager().isAuthEnabled()) return;
        if (!plugin.getConfigManager().useDialog()) return;
        if (!Dialogs.isSupported()) return;

        PlayerConfigurationConnection connection = event.getConnection();
        UUID uuid = connection.getProfile().getId();
        String name = connection.getProfile().getName();
        if (uuid == null || name == null) return;

        // ViaVersion can allow pre-1.21.7 clients onto a modern server. Those
        // clients cannot render native dialogs, so let AuthListener use chat mode.
        if (!clientSupportsDialogs(uuid)) return;

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        data.setName(name);

        Audience audience = connection.getAudience();
        audiences.put(uuid, audience);
        names.put(uuid, name);
        attempts.put(uuid, 0);

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        future.completeOnTimeout(false,
                plugin.getConfigManager().getLoginTimeout(), TimeUnit.SECONDS);
        waiting.put(uuid, future);

        if (!show(uuid, audience, name, null)) {
            cleanup(uuid);
            return;
        }

        boolean passed;
        try {
            passed = future.join();
        } catch (Exception ignored) {
            passed = false;
        }

        if (passed) {
            plugin.getAuthManager().markAuthenticated(uuid);
        } else if (!closed.contains(uuid)) {
            String reason = failures.getOrDefault(uuid,
                    plugin.getLanguageManager().get("auth.kick-timeout"));
            connection.disconnect(com.roguard.util.TextUtil.component(reason));
        }

        cleanup(uuid);
    }

    /** Returns false when the dialog could not be displayed. */
    private boolean show(UUID uuid, Audience audience, String name, String error) {
        try {
            PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
            audience.showDialog(data.isRegistered()
                    ? plugin.getDialogs().login(name, error)
                    : plugin.getDialogs().register(name, error));
            return true;
        } catch (Throwable failure) {
            // The dialog could not be built. Let the player in and fall back
            // to chat login instead of blocking the connection.
            plugin.getLogger().warning("Dialog login unavailable, using chat login: " + failure);
            complete(uuid, false);
            closed.add(uuid);
            return false;
        }
    }

    // ==================== BUTTONS ====================

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        Key key = event.getIdentifier();

        if (key.equals(Dialogs.LOGIN)) {
            handleLogin(event);
        } else if (key.equals(Dialogs.REGISTER)) {
            handleRegister(event);
        } else if (key.equals(Dialogs.SETTINGS_SAVE)) {
            handleSettings(event);
        }
    }

    private void handleLogin(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;

        UUID uuid = connection.getProfile().getId();
        if (uuid == null) return;

        DialogResponseView view = event.getDialogResponseView();
        String password = view == null ? null : view.getText(Dialogs.IN_PASSWORD);
        String name = names.getOrDefault(uuid, "Player");
        Audience audience = audiences.get(uuid);

        if (password == null || password.isEmpty()) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.login-usage"));
            return;
        }

        if (plugin.getAuthManager().verify(uuid, password)) {
            plugin.getAuthManager().recordLogin(uuid, name, address(connection));
            complete(uuid, true);
            return;
        }

        int used = attempts.merge(uuid, 1, Integer::sum);
        if (used >= plugin.getConfigManager().getMaxAttempts()) {
            failures.put(uuid, plugin.getLanguageManager().get("auth.kick-attempts"));
            complete(uuid, false);
            return;
        }

        retry(uuid, audience, name, plugin.getLanguageManager().get("auth.login-wrong"));
    }

    private void handleRegister(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerConfigurationConnection connection)) return;

        UUID uuid = connection.getProfile().getId();
        if (uuid == null) return;

        DialogResponseView view = event.getDialogResponseView();
        String password = view == null ? null : view.getText(Dialogs.IN_PASSWORD);
        String confirm = view == null ? null : view.getText(Dialogs.IN_CONFIRM);
        String name = names.getOrDefault(uuid, "Player");
        Audience audience = audiences.get(uuid);

        if (password == null || password.isEmpty() || confirm == null || confirm.isEmpty()) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.register-usage"));
            return;
        }
        if (!password.equals(confirm)) {
            retry(uuid, audience, name, plugin.getLanguageManager().get("auth.register-mismatch"));
            return;
        }

        int min = plugin.getConfigManager().getMinPassword();
        if (password.length() < min) {
            retry(uuid, audience, name, plugin.getLanguageManager()
                    .get("auth.register-short", "%min%", String.valueOf(min)));
            return;
        }

        plugin.getAuthManager().register(uuid, name, password, address(connection));
        plugin.getAuthManager().recordLogin(uuid, name, address(connection));
        complete(uuid, true);
    }

    private void handleSettings(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Player player = connection.getPlayer();
        if (!player.hasPermission("roguard.settings")) {
            player.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        DialogResponseView view = event.getDialogResponseView();
        if (view == null) return;

        for (String id : Dialogs.FEATURES.keySet()) {
            Boolean value = view.getBoolean(id);
            if (value == null) continue;
            plugin.getConfigManager().setFeature(
                    plugin.getConfigManager().featurePath(id), value);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            plugin.getConfigManager().save();
            plugin.getConfigManager().reload();
            plugin.getLanguageManager().reload();
            player.sendMessage(plugin.getLanguageManager().msg("general.settings-saved"));
        });
    }

    // ==================== HELPERS ====================

    private String address(PlayerConfigurationConnection connection) {
        try {
            SocketAddress address = connection.getAddress();
            if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
                return inet.getAddress().getHostAddress();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /**
     * Uses ViaVersion when present. Protocol 772 is Minecraft 1.21.7.
     * Without ViaVersion, the client necessarily matches the modern server.
     */
    private boolean clientSupportsDialogs(UUID uuid) {
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            Object version = api.getClass()
                    .getMethod("getPlayerVersion", UUID.class)
                    .invoke(api, uuid);
            return !(version instanceof Number number) || number.intValue() >= 772;
        } catch (ClassNotFoundException ignored) {
            return true;
        } catch (ReflectiveOperationException ignored) {
            // ViaVersion exists but changed API: fail open to the server protocol.
            return true;
        }
    }

    private void retry(UUID uuid, Audience audience, String name, String error) {
        if (audience == null) return;
        show(uuid, audience, name, error);
    }

    private void complete(UUID uuid, boolean success) {
        CompletableFuture<Boolean> future = waiting.get(uuid);
        if (future != null) future.complete(success);
    }

    private void cleanup(UUID uuid) {
        waiting.remove(uuid);
        audiences.remove(uuid);
        names.remove(uuid);
        attempts.remove(uuid);
        failures.remove(uuid);
        closed.remove(uuid);
    }

    @EventHandler
    public void onClose(PlayerConnectionCloseEvent event) {
        UUID uuid = event.getPlayerUniqueId();
        closed.add(uuid);
        complete(uuid, false);
    }
}
