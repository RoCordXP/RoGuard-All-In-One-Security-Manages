package com.roguard.listener;

import com.roguard.RoGuard;
import com.roguard.dialog.PlayerDialogs;
import com.roguard.inventory.InventoryManager;
import com.roguard.util.TextUtil;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Handles every player manager dialog action and keeps inventories in sync. */
public class PlayerManagerListener implements Listener {

    private final RoGuard plugin;
    private final Map<UUID, String> filters = new ConcurrentHashMap<>();

    public PlayerManagerListener(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== DIALOG ROUTING ====================

    @EventHandler
    public void onClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Key key = event.getIdentifier();
        if (!key.namespace().equals("roguard")) return;

        String path = key.value();
        if (!path.startsWith("pm/")) return;

        Player viewer = connection.getPlayer();
        if (!viewer.hasPermission("roguard.playermanager")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        DialogResponseView view = event.getDialogResponseView();
        if (view != null) {
            String typed = view.getText(PlayerDialogs.IN_SEARCH);
            if (typed != null) filters.put(viewer.getUniqueId(), typed);
        }

        String filter = filters.getOrDefault(viewer.getUniqueId(), "");

        if (path.equals(PlayerDialogs.OPEN)
                || path.equals(PlayerDialogs.SEARCH)
                || path.equals(PlayerDialogs.BACK)) {
            openList(viewer, filter, 0);
            return;
        }

        if (path.startsWith(PlayerDialogs.PAGE)) {
            openList(viewer, filter, parseInt(path.substring(PlayerDialogs.PAGE.length())));
            return;
        }

        if (path.startsWith(PlayerDialogs.VIEW)) {
            UUID id = parseUuid(path.substring(PlayerDialogs.VIEW.length()));
            if (id != null) openPlayer(viewer, id);
            return;
        }

        if (path.startsWith(PlayerDialogs.FORM)) {
            String rest = path.substring(PlayerDialogs.FORM.length());
            int split = rest.indexOf('/');
            if (split <= 0) return;
            UUID id = parseUuid(rest.substring(split + 1));
            if (id != null) openForm(viewer, id, rest.substring(0, split));
            return;
        }

        if (path.startsWith(PlayerDialogs.RUN)) {
            String rest = path.substring(PlayerDialogs.RUN.length());
            String[] parts = rest.split("/");
            if (parts.length < 2) return;

            UUID id = parseUuid(parts[1]);
            if (id == null) return;

            String extra = parts.length > 2 ? parts[2] : null;
            String reason = view == null ? null : view.getText(PlayerDialogs.IN_REASON);
            String duration = view == null ? null : view.getText(PlayerDialogs.IN_DURATION);

            run(viewer, id, parts[0], reason, duration, extra);
        }
    }

    // ==================== DIALOG OPENERS ====================

    private void openList(Player viewer, String filter, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                viewer.showDialog(plugin.getPlayerDialogs().list(filter, page));
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            }
        });
    }

    private void openPlayer(Player viewer, UUID id) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                viewer.showDialog(plugin.getPlayerDialogs().player(Bukkit.getOfflinePlayer(id)));
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            }
        });
    }

    private void openForm(Player viewer, UUID id, String action) {
        if (!viewer.hasPermission("roguard.punish")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(id);
            try {
                if (action.equals("gamemode")) {
                    viewer.showDialog(plugin.getPlayerDialogs().gamemodeForm(target));
                } else if (action.equals("clear") || action.equals("kill")
                        || action.equals("resetpassword")) {
                    viewer.showDialog(plugin.getPlayerDialogs().confirmForm(target, action));
                } else if (action.startsWith("temp")) {
                    viewer.showDialog(plugin.getPlayerDialogs().timedForm(target, action));
                } else {
                    viewer.showDialog(plugin.getPlayerDialogs().reasonForm(target, action));
                }
            } catch (Throwable error) {
                viewer.sendMessage(plugin.getLanguageManager().msg("general.dialog-unsupported"));
            }
        });
    }

    // ==================== ACTIONS ====================

    private void run(Player viewer, UUID id, String action,
                     String reason, String duration, String extra) {

        boolean punishment = switch (action) {
            case "inventory", "ender" -> false;
            default -> true;
        };
        if (punishment && !viewer.hasPermission("roguard.punish")) {
            viewer.sendMessage(plugin.getLanguageManager().msg("security.no-permission"));
            return;
        }

        String finalReason = (reason == null || reason.isBlank())
                ? plugin.getLanguageManager().get("playermanager.default-reason") : reason;

        Bukkit.getScheduler().runTask(plugin, () -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(id);
            Player online = target.getPlayer();
            String name = target.getName() == null ? id.toString() : target.getName();

            Long expires = null;
            if (action.startsWith("temp")) {
                long millis = TextUtil.parseDuration(duration);
                if (millis <= 0) {
                    viewer.sendMessage(plugin.getLanguageManager()
                            .msg("playermanager.invalid-duration"));
                    return;
                }
                expires = System.currentTimeMillis() + millis;
            }

            boolean needsOnline = switch (action) {
                case "ender", "heal", "feed", "kill", "tpto", "bring",
                     "freeze", "unfreeze", "gamemode", "kick" -> true;
                default -> false;
            };
            if (needsOnline && online == null) {
                viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.target-offline"));
                return;
            }

            switch (action) {
                case "inventory" -> {
                    if (online != null) {
                        plugin.getInventoryManager().openLive(viewer, online);
                    } else if (!plugin.getInventoryManager().openOffline(viewer, target)) {
                        viewer.sendMessage(plugin.getLanguageManager()
                                .msg("playermanager.no-snapshot"));
                    }
                    return;
                }
                case "ender" -> {
                    plugin.getInventoryManager().openEnder(viewer, online);
                    return;
                }
                case "clear" -> {
                    if (online != null) plugin.getInventoryManager().clear(online);
                    else plugin.getSnapshotStore().clearSnapshot(id);
                }
                case "heal" -> {
                    // Player#getMaxHealth exists across the entire supported
                    // range, while the Attribute constant was renamed after 1.21.7.
                    online.setHealth(online.getMaxHealth());
                    online.setFoodLevel(20);
                    online.setSaturation(20f);
                    online.setFireTicks(0);
                }
                case "feed" -> {
                    online.setFoodLevel(20);
                    online.setSaturation(20f);
                }
                case "kill" -> online.setHealth(0);
                case "tpto" -> viewer.teleportAsync(online.getLocation());
                case "bring" -> online.teleportAsync(viewer.getLocation());
                case "freeze" -> plugin.getPunishmentManager().setFrozen(id, true);
                case "unfreeze" -> plugin.getPunishmentManager().setFrozen(id, false);
                case "op" -> target.setOp(true);
                case "deop" -> target.setOp(false);
                case "gamemode" -> {
                    GameMode mode;
                    try {
                        mode = GameMode.valueOf(extra);
                    } catch (Exception ignored) {
                        return;
                    }
                    plugin.getVerifyManager().allowGamemode(online.getUniqueId());
                    online.setGameMode(mode);
                    viewer.sendMessage(plugin.getLanguageManager().msg(
                            "playermanager.done-gamemode",
                            "%player%", name, "%value%", mode.name()));
                    openPlayer(viewer, id);
                    return;
                }
                case "resetpassword" -> plugin.getPlayerDataManager().resetPassword(id);
                case "kick" -> plugin.getPunishmentManager().kick(online, finalReason);
                case "ban", "tempban" ->
                        plugin.getPunishmentManager().ban(target, finalReason, viewer.getName(), expires);
                case "banip", "tempbanip" -> {
                    String ip = plugin.getPunishmentManager().addressOf(target);
                    if (ip == null || ip.isEmpty()) {
                        viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.no-address"));
                        return;
                    }
                    plugin.getPunishmentManager().ban(target, finalReason, viewer.getName(), expires);
                    plugin.getPunishmentManager().banAddress(target, finalReason, viewer.getName(), expires);
                }
                case "mute", "tempmute" ->
                        plugin.getPunishmentManager().mute(id, finalReason, viewer.getName(), expires);
                case "unban" -> plugin.getPunishmentManager().unban(target);
                case "unmute" -> plugin.getPunishmentManager().unmute(id);
                default -> {
                    return;
                }
            }

            String time = expires == null ? ""
                    : TextUtil.duration(expires - System.currentTimeMillis());

            viewer.sendMessage(plugin.getLanguageManager().msg("playermanager.done-" + action,
                    "%player%", name, "%reason%", finalReason, "%time%", time));

            openPlayer(viewer, id);
        });
    }

    // ==================== INVENTORY SYNC ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getInventoryManager().isManaged(top)) return;

        InventoryManager.Kind kind = plugin.getInventoryManager().kindOf(top);
        if (kind != InventoryManager.Kind.ENDER
                && event.getClickedInventory() == top
                && InventoryManager.isFiller(event.getRawSlot())) {
            event.setCancelled(true);
            return;
        }

        sync(event.getWhoClicked().getUniqueId(), top);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getInventoryManager().isManaged(top)) return;

        InventoryManager.Kind kind = plugin.getInventoryManager().kindOf(top);
        if (kind != InventoryManager.Kind.ENDER) {
            for (int slot : event.getRawSlots()) {
                if (slot < top.getSize() && InventoryManager.isFiller(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        sync(event.getWhoClicked().getUniqueId(), top);
    }

    /** Applies the edit one tick later, once Bukkit finished the click. */
    private void sync(UUID viewerId, Inventory gui) {
        InventoryManager manager = plugin.getInventoryManager();
        InventoryManager.Session session = manager.getSession(viewerId);
        if (session == null) return;

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (session.kind == InventoryManager.Kind.OFFLINE) {
                plugin.getSnapshotStore().saveFromGui(session.targetId, gui);
                return;
            }

            Player target = Bukkit.getPlayer(session.targetId);
            if (target == null) return;

            if (session.kind == InventoryManager.Kind.ENDER) manager.pushEnder(gui, target);
            else manager.push(gui, target);
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!plugin.getInventoryManager().isManaged(top)) return;

        UUID viewerId = event.getPlayer().getUniqueId();
        InventoryManager.Session session = plugin.getInventoryManager().getSession(viewerId);

        if (session != null) {
            if (session.kind == InventoryManager.Kind.OFFLINE) {
                plugin.getSnapshotStore().saveFromGui(session.targetId, top);
            } else {
                Player target = Bukkit.getPlayer(session.targetId);
                if (target != null) {
                    if (session.kind == InventoryManager.Kind.ENDER) {
                        plugin.getInventoryManager().pushEnder(top, target);
                    } else {
                        plugin.getInventoryManager().push(top, target);
                    }
                }
            }
        }

        plugin.getInventoryManager().close(viewerId);
    }

    // ==================== HELPERS ====================

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return 0;
        }
    }
}
