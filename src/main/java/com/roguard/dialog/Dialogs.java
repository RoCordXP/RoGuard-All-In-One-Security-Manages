package com.roguard.dialog;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the authentication and settings dialogs.
 * Only call these when isSupported returns true.
 */
public final class Dialogs {

    public static final String NAMESPACE = "roguard";

    public static final Key LOGIN = Key.key(NAMESPACE, "auth/login");
    public static final Key REGISTER = Key.key(NAMESPACE, "auth/register");
    public static final Key SETTINGS_SAVE = Key.key(NAMESPACE, "settings/save");

    // Input ids must be lowercase alphanumeric only.
    public static final String IN_PASSWORD = "password";
    public static final String IN_CONFIRM = "confirm";

    /** Switch id mapped to the language suffix. */
    public static final Map<String, String> FEATURES = new LinkedHashMap<>();
    static {
        FEATURES.put("auth", "auth");
        FEATURES.put("player2fa", "player2fa");
        FEATURES.put("commandverification", "commandverification");
        FEATURES.put("commandlog", "commandlog");
        FEATURES.put("consolemirror", "consolemirror");
        FEATURES.put("autodeop", "autodeop");
        FEATURES.put("antivpn", "antivpn");
        FEATURES.put("shopkeeper", "shopkeeper");
        FEATURES.put("gamemode", "gamemode");
        FEATURES.put("chatbridge", "chatbridge");
    }

    private static Boolean supported;

    private final RoGuard plugin;

    public Dialogs(RoGuard plugin) {
        this.plugin = plugin;
    }

    public static boolean isSupported() {
        if (supported == null) {
            try {
                Class.forName("io.papermc.paper.dialog.Dialog");
                Class.forName("io.papermc.paper.registry.data.dialog.DialogBase");
                supported = true;
            } catch (Throwable ignored) {
                supported = false;
            }
        }
        return supported;
    }

    private Component text(String key, String... pairs) {
        return plugin.getLanguageManager().component("dialog." + key, pairs);
    }

    private Component plain(String value) {
        return TextUtil.component(value);
    }

    // ==================== LOGIN ====================

    public Dialog login(String playerName, String error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text("login-greeting", "%player%", playerName)));
        if (error != null && !error.isEmpty()) {
            body.add(DialogBody.plainMessage(plain(error)));
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("login-title"))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text(IN_PASSWORD, text("login-field"))
                                        .width(280)
                                        .maxLength(64)
                                        .build()))
                        .build())
                .type(DialogType.notice(
                        ActionButton.builder(text("login-submit"))
                                .tooltip(text("login-submit-hint"))
                                .width(200)
                                .action(DialogAction.customClick(LOGIN, null))
                                .build())));
    }

    // ==================== REGISTER ====================

    public Dialog register(String playerName, String error) {
        String min = String.valueOf(plugin.getConfigManager().getMinPassword());

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text("register-greeting", "%player%", playerName)));
        body.add(DialogBody.plainMessage(text("register-hint", "%min%", min)));
        if (error != null && !error.isEmpty()) {
            body.add(DialogBody.plainMessage(plain(error)));
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("register-title"))
                        .canCloseWithEscape(false)
                        .body(body)
                        .inputs(List.of(
                                DialogInput.text(IN_PASSWORD, text("register-field"))
                                        .width(280).maxLength(64).build(),
                                DialogInput.text(IN_CONFIRM, text("register-confirm"))
                                        .width(280).maxLength(64).build()))
                        .build())
                .type(DialogType.notice(
                        ActionButton.builder(text("register-submit"))
                                .tooltip(text("register-submit-hint"))
                                .width(200)
                                .action(DialogAction.customClick(REGISTER, null))
                                .build())));
    }

    // ==================== SETTINGS ====================

    public Dialog settings() {
        List<DialogInput> inputs = new ArrayList<>();

        for (Map.Entry<String, String> entry : FEATURES.entrySet()) {
            inputs.add(DialogInput.bool(entry.getKey(), text("feature-" + entry.getValue()))
                    .initial(plugin.getConfigManager().isFeature(entry.getKey()))
                    .build());
        }

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("settings-title"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(text("settings-note"))))
                        .inputs(inputs)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(text("settings-save"))
                                .tooltip(text("settings-save-hint"))
                                .width(150)
                                .action(DialogAction.customClick(SETTINGS_SAVE, null))
                                .build(),
                        ActionButton.builder(text("settings-cancel"))
                                .tooltip(text("settings-cancel-hint"))
                                .width(150)
                                .build())));
    }
}
