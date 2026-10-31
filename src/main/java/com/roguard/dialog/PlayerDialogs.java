package com.roguard.dialog;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.ConfirmationType;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Builds the player manager dialogs. */
public final class PlayerDialogs {

    public static final String IN_SEARCH = "search";
    public static final String IN_REASON = "reason";
    public static final String IN_DURATION = "duration";
    public static final String IN_GAMEMODE = "mode";

    public static final String OPEN = "pm/open";
    public static final String SEARCH = "pm/search";
    public static final String BACK = "pm/back";
    public static final String PAGE = "pm/page/";
    public static final String VIEW = "pm/view/";
    public static final String FORM = "pm/form/";
    public static final String RUN = "pm/run/";

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH);

    private final RoGuard plugin;

    public PlayerDialogs(RoGuard plugin) {
        this.plugin = plugin;
    }

    private Component text(String key, String... pairs) {
        return plugin.getLanguageManager().component("playermanager." + key, pairs);
    }

    private String raw(String key, String... pairs) {
        return plugin.getLanguageManager().get("playermanager." + key, pairs);
    }

    public static Key key(String path) {
        return Key.key(Dialogs.NAMESPACE, path);
    }

    private ActionButton action(String labelKey, String hintKey, String path, int width) {
        return ActionButton.builder(text(labelKey))
                .tooltip(text(hintKey))
                .width(width)
                .action(DialogAction.customClick(key(path), null))
                .build();
    }

    /** A label only button. Clicking it simply closes the dialog. */
    private ActionButton label(String value) {
        return ActionButton.builder(TextUtil.component(value)).width(200).build();
    }

    // ==================== PLAYER LIST ====================

    public List<OfflinePlayer> collect(String filter) {
        List<OfflinePlayer> result = new ArrayList<>();
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();

        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            String name = offline.getName();
            if (name == null) continue;
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            result.add(offline);
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            String name = online.getName();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) continue;
            boolean known = result.stream()
                    .anyMatch(p -> p.getUniqueId().equals(online.getUniqueId()));
            if (!known) result.add(online);
        }

        result.sort(Comparator
                .comparing((OfflinePlayer p) -> !p.isOnline())
                .thenComparing(p -> p.getName() == null ? "" : p.getName().toLowerCase(Locale.ROOT)));

        return result;
    }

    public Dialog list(String filter, int page) {
        List<OfflinePlayer> players = collect(filter);

        int size = plugin.getConfigManager().pageSize();
        int totalPages = Math.max(1, (int) Math.ceil(players.size() / (double) size));
        int safePage = Math.max(0, Math.min(page, totalPages - 1));
        int from = safePage * size;
        int to = Math.min(from + size, players.size());

        List<ActionButton> buttons = new ArrayList<>();

        for (int i = from; i < to; i++) {
            OfflinePlayer target = players.get(i);
            String name = nameOf(target);
            String mark = target.isOnline() ? raw("online-mark") : raw("offline-mark");

            buttons.add(ActionButton.builder(TextUtil.component(mark + name))
                    .tooltip(text("open-hint"))
                    .width(145)
                    .action(DialogAction.customClick(key(VIEW + target.getUniqueId()), null))
                    .build());
        }

        if (buttons.isEmpty()) buttons.add(label(raw("empty")));

        buttons.add(action("search-button", "search-hint", SEARCH, 145));

        if (safePage > 0) {
            buttons.add(action("previous", "previous", PAGE + (safePage - 1), 145));
        }
        if (safePage < totalPages - 1) {
            buttons.add(action("next", "next", PAGE + (safePage + 1), 145));
        }

        String summary = raw("summary",
                "%count%", String.valueOf(players.size()),
                "%page%", String.valueOf(safePage + 1),
                "%total%", String.valueOf(totalPages));

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("title"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(TextUtil.component(summary))))
                        .inputs(List.of(
                                DialogInput.text(IN_SEARCH, text("search-field"))
                                        .width(300)
                                        .maxLength(32)
                                        .initial(filter == null ? "" : filter)
                                        .build()))
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
    }

    // ==================== PLAYER DETAIL ====================

    public Dialog player(OfflinePlayer target) {
        UUID id = target.getUniqueId();
        String name = nameOf(target);
        Player online = target.getPlayer();

        boolean banned = plugin.getPunishmentManager().isBanned(target);
        boolean muted = plugin.getPunishmentManager().isMuted(id);
        boolean frozen = plugin.getPunishmentManager().isFrozen(id);

        // ---- Header ----
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(TextUtil.component(raw("player-summary",
                "%status%", online != null ? raw("status-online") : raw("status-offline")))));

        if (banned) body.add(DialogBody.plainMessage(text("banned-note")));
        if (muted) body.add(DialogBody.plainMessage(
                TextUtil.component(raw("muted-note",
                        "%reason%", plugin.getPunishmentManager().muteReason(id)))));
        if (frozen) body.add(DialogBody.plainMessage(text("frozen-note")));

        // ---- Statistics ----
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-playtime", "%value%", playtime(target)))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-kills", "%value%", String.valueOf(stat(target, Statistic.PLAYER_KILLS))))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-mobkills", "%value%", String.valueOf(stat(target, Statistic.MOB_KILLS))))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-deaths", "%value%", String.valueOf(stat(target, Statistic.DEATHS))))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-mined", "%value%", String.valueOf(mined(target))))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-joins", "%value%", String.valueOf(
                        plugin.getPlayerDataManager().get(id).getJoins())))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-firstjoin", "%value%", date(target.getFirstPlayed())))));
        body.add(DialogBody.plainMessage(TextUtil.component(
                raw("stat-lastseen", "%value%", online != null
                        ? raw("status-online") : date(target.getLastPlayed())))));

        if (online != null) {
            body.add(DialogBody.plainMessage(TextUtil.component(raw("stat-health",
                    "%value%", Math.round(online.getHealth()) + " / 20"))));
            body.add(DialogBody.plainMessage(TextUtil.component(raw("stat-food",
                    "%value%", online.getFoodLevel() + " / 20"))));
            body.add(DialogBody.plainMessage(TextUtil.component(raw("stat-level",
                    "%value%", String.valueOf(online.getLevel())))));
            body.add(DialogBody.plainMessage(TextUtil.component(raw("stat-world",
                    "%value%", online.getWorld().getName()))));
        }

        String address = plugin.getPunishmentManager().addressOf(target);
        if (address != null && !address.isEmpty()) {
            body.add(DialogBody.plainMessage(TextUtil.component(
                    raw("stat-ip", "%value%", address))));
        }

        // ---- Actions ----
        List<ActionButton> buttons = new ArrayList<>();

        buttons.add(action("open-inventory", "open-inventory-hint", RUN + "inventory/" + id, 150));
        buttons.add(action("open-ender", "open-ender-hint", RUN + "ender/" + id, 150));
        buttons.add(action("clear-inventory", "clear-inventory-hint", FORM + "clear/" + id, 150));
        buttons.add(action("resetpassword", "resetpassword-hint", FORM + "resetpassword/" + id, 150));

        if (online != null) {
            buttons.add(action("heal", "heal-hint", RUN + "heal/" + id, 150));
            buttons.add(action("feed", "feed-hint", RUN + "feed/" + id, 150));
            buttons.add(action("teleport-to", "teleport-to-hint", RUN + "tpto/" + id, 150));
            buttons.add(action("bring", "bring-hint", RUN + "bring/" + id, 150));
            buttons.add(action("gamemode", "gamemode-hint", FORM + "gamemode/" + id, 150));
            buttons.add(action("kill", "kill-hint", FORM + "kill/" + id, 150));

            if (frozen) {
                buttons.add(action("unfreeze", "unfreeze-hint", RUN + "unfreeze/" + id, 150));
            } else {
                buttons.add(action("freeze", "freeze-hint", RUN + "freeze/" + id, 150));
            }

            buttons.add(action("kick", "kick-hint", FORM + "kick/" + id, 150));
        }

        if (target.isOp()) {
            buttons.add(action("deop", "deop-hint", RUN + "deop/" + id, 150));
        } else {
            buttons.add(action("op", "op-hint", RUN + "op/" + id, 150));
        }

        if (banned) {
            buttons.add(action("unban", "unban-hint", RUN + "unban/" + id, 150));
        } else {
            buttons.add(action("ban", "ban-hint", FORM + "ban/" + id, 150));
            buttons.add(action("tempban", "tempban-hint", FORM + "tempban/" + id, 150));
            buttons.add(action("banip", "banip-hint", FORM + "banip/" + id, 150));
            buttons.add(action("tempbanip", "tempbanip-hint", FORM + "tempbanip/" + id, 150));
        }

        if (muted) {
            buttons.add(action("unmute", "unmute-hint", RUN + "unmute/" + id, 150));
        } else {
            buttons.add(action("mute", "mute-hint", FORM + "mute/" + id, 150));
            buttons.add(action("tempmute", "tempmute-hint", FORM + "tempmute/" + id, 150));
        }

        buttons.add(action("back", "back", BACK, 150));

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(TextUtil.component(raw("player-title", "%player%", name)))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
    }

    // ==================== FORMS ====================

    /** Confirmation only, used by clear, kill and reset password. */
    public Dialog confirmForm(OfflinePlayer target, String action) {
        String name = nameOf(target);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("form-" + action))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(TextUtil.component(
                                        raw("form-target", "%player%", name))),
                                DialogBody.plainMessage(text("warning-permanent"))))
                        .build())
                .type(confirmButtons(action, target.getUniqueId())));
    }

    /** Reason only, used by ban, banip, mute and kick. */
    public Dialog reasonForm(OfflinePlayer target, String action) {
        String name = nameOf(target);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("form-" + action))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(TextUtil.component(
                                raw("form-target", "%player%", name)))))
                        .inputs(List.of(
                                DialogInput.text(IN_REASON, text("reason-field"))
                                        .width(300)
                                        .maxLength(120)
                                        .initial(raw("default-reason"))
                                        .build()))
                        .build())
                .type(confirmButtons(action, target.getUniqueId())));
    }

    /** Reason plus duration, used by every temporary punishment. */
    public Dialog timedForm(OfflinePlayer target, String action) {
        String name = nameOf(target);

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("form-" + action))
                        .canCloseWithEscape(true)
                        .body(List.of(
                                DialogBody.plainMessage(TextUtil.component(
                                        raw("form-target", "%player%", name))),
                                DialogBody.plainMessage(text("duration-hint"))))
                        .inputs(List.of(
                                DialogInput.text(IN_REASON, text("reason-field"))
                                        .width(300).maxLength(120)
                                        .initial(raw("default-reason")).build(),
                                DialogInput.text(IN_DURATION, text("duration-field"))
                                        .width(300).maxLength(24)
                                        .initial(raw("default-duration")).build()))
                        .build())
                .type(confirmButtons(action, target.getUniqueId())));
    }

    /** Gamemode picker. */
    public Dialog gamemodeForm(OfflinePlayer target) {
        String name = nameOf(target);

        List<ActionButton> buttons = new ArrayList<>();
        for (GameMode mode : GameMode.values()) {
            buttons.add(ActionButton.builder(Component.text(pretty(mode.name())))
                    .width(140)
                    .action(DialogAction.customClick(
                            key(RUN + "gamemode/" + target.getUniqueId() + "/" + mode.name()), null))
                    .build());
        }
        buttons.add(action("cancel", "cancel", VIEW + target.getUniqueId(), 140));

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(text("form-gamemode"))
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(TextUtil.component(
                                raw("form-target", "%player%", name)))))
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build()));
    }

    private ConfirmationType confirmButtons(String action, UUID id) {
        return DialogType.confirmation(
                ActionButton.builder(text("confirm"))
                        .width(150)
                        .action(DialogAction.customClick(key(RUN + action + "/" + id), null))
                        .build(),
                ActionButton.builder(text("cancel"))
                        .width(150)
                        .action(DialogAction.customClick(key(VIEW + id), null))
                        .build());
    }

    // ==================== HELPERS ====================

    private String nameOf(OfflinePlayer target) {
        return target.getName() == null ? target.getUniqueId().toString() : target.getName();
    }

    private String pretty(String value) {
        String lower = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private int stat(OfflinePlayer target, Statistic statistic) {
        try {
            return target.getStatistic(statistic);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private String playtime(OfflinePlayer target) {
        try {
            long ticks = target.getStatistic(Statistic.PLAY_ONE_MINUTE);
            return TextUtil.duration(ticks * 50L);
        } catch (Throwable ignored) {
            return "0s";
        }
    }

    private long mined(OfflinePlayer target) {
        long total = 0;
        for (Material material : Material.values()) {
            if (!material.isBlock() || material.isLegacy()) continue;
            try {
                total += target.getStatistic(Statistic.MINE_BLOCK, material);
            } catch (Throwable ignored) {}
        }
        return total;
    }

    private String date(long millis) {
        if (millis <= 0) return "-";
        return DATE.format(new Date(millis));
    }
}
