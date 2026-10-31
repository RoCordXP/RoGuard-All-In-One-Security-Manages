package com.roguard.inventory;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live inventory viewer.
 *
 * Layout of the 54 slot window:
 *   0-26   storage        player slots 9-35
 *   27-35  hotbar         player slots 0-8
 *   36-39  armour
 *   40     offhand
 *   41-44  crafting grid  only while the player has their own inventory open
 *   45-53  filler
 */
public class InventoryManager {

    public static final int SIZE = 54;
    public static final int ARMOUR = 36;
    public static final int OFFHAND = 40;
    public static final int CRAFT = 41;
    public static final int FILLER = 45;

    private final RoGuard plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public InventoryManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== OPEN ====================

    public boolean openLive(Player viewer, Player target) {
        if (viewer.equals(target) && !plugin.getConfigManager().allowSelfInventory()) {
            return false;
        }

        close(viewer.getUniqueId());

        Holder holder = new Holder(target.getUniqueId(), Kind.LIVE);
        Inventory gui = Bukkit.createInventory(holder, SIZE, title("inventory-title", target.getName()));
        holder.inventory = gui;

        pull(gui, target);
        decorate(gui);
        viewer.openInventory(gui);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(target.getUniqueId());
            if (live == null || !viewer.isOnline()) {
                if (viewer.isOnline()) viewer.closeInventory();
                close(viewer.getUniqueId());
                return;
            }
            pull(gui, live);
        }, 20L, 20L);

        sessions.put(viewer.getUniqueId(), new Session(target.getUniqueId(), Kind.LIVE, task));
        return true;
    }

    public boolean openOffline(Player viewer, OfflinePlayer target) {
        close(viewer.getUniqueId());

        UUID id = target.getUniqueId();
        String name = target.getName() == null ? id.toString() : target.getName();

        Holder holder = new Holder(id, Kind.OFFLINE);
        Inventory gui = Bukkit.createInventory(holder, SIZE, title("inventory-title-offline", name));
        holder.inventory = gui;

        if (!plugin.getSnapshotStore().load(id, gui)) return false;

        decorate(gui);
        viewer.openInventory(gui);
        sessions.put(viewer.getUniqueId(), new Session(id, Kind.OFFLINE, null));
        return true;
    }

    public boolean openEnder(Player viewer, Player target) {
        close(viewer.getUniqueId());

        Holder holder = new Holder(target.getUniqueId(), Kind.ENDER);
        Inventory gui = Bukkit.createInventory(holder, 27, title("ender-title", target.getName()));
        holder.inventory = gui;
        gui.setContents(target.getEnderChest().getContents());

        viewer.openInventory(gui);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player live = Bukkit.getPlayer(target.getUniqueId());
            if (live == null || !viewer.isOnline()) {
                if (viewer.isOnline()) viewer.closeInventory();
                close(viewer.getUniqueId());
                return;
            }
            gui.setContents(live.getEnderChest().getContents());
        }, 20L, 20L);

        sessions.put(viewer.getUniqueId(), new Session(target.getUniqueId(), Kind.ENDER, task));
        return true;
    }

    private net.kyori.adventure.text.Component title(String key, String name) {
        return TextUtil.component(
                plugin.getLanguageManager().get("playermanager." + key, "%player%", name));
    }

    public void close(UUID viewerId) {
        Session session = sessions.remove(viewerId);
        if (session != null && session.task != null) session.task.cancel();
    }

    public Session getSession(UUID viewerId) {
        return sessions.get(viewerId);
    }

    public boolean isManaged(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public Kind kindOf(Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) return holder.kind;
        return null;
    }

    // ==================== SYNC ====================

    public void pull(Inventory gui, Player target) {
        PlayerInventory inv = target.getInventory();

        for (int i = 0; i < 27; i++) gui.setItem(i, copy(inv.getItem(i + 9)));
        for (int i = 0; i < 9; i++) gui.setItem(27 + i, copy(inv.getItem(i)));

        ItemStack[] armour = inv.getArmorContents();
        for (int i = 0; i < 4 && i < armour.length; i++) gui.setItem(ARMOUR + i, copy(armour[i]));

        gui.setItem(OFFHAND, copy(inv.getItemInOffHand()));

        ItemStack[] grid = readCraft(target);
        for (int i = 0; i < 4; i++) gui.setItem(CRAFT + i, grid == null ? null : copy(grid[i]));
    }

    public void push(Inventory gui, Player target) {
        PlayerInventory inv = target.getInventory();

        for (int i = 0; i < 27; i++) inv.setItem(i + 9, copy(gui.getItem(i)));
        for (int i = 0; i < 9; i++) inv.setItem(i, copy(gui.getItem(27 + i)));

        ItemStack[] armour = new ItemStack[4];
        for (int i = 0; i < 4; i++) armour[i] = copy(gui.getItem(ARMOUR + i));
        inv.setArmorContents(armour);

        ItemStack offhand = copy(gui.getItem(OFFHAND));
        inv.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);

        writeCraft(target, gui);
        target.updateInventory();
    }

    public void pushEnder(Inventory gui, Player target) {
        target.getEnderChest().setContents(gui.getContents());
    }

    public void clear(Player target) {
        PlayerInventory inv = target.getInventory();
        inv.clear();
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInOffHand(new ItemStack(Material.AIR));
        target.updateInventory();
    }

    private ItemStack[] readCraft(Player target) {
        try {
            InventoryView view = target.getOpenInventory();
            Inventory top = view.getTopInventory();
            if (top.getType() != InventoryType.CRAFTING || top.getSize() < 5) return null;

            ItemStack[] grid = new ItemStack[4];
            for (int i = 0; i < 4; i++) grid[i] = top.getItem(i + 1);
            return grid;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void writeCraft(Player target, Inventory gui) {
        try {
            InventoryView view = target.getOpenInventory();
            Inventory top = view.getTopInventory();
            if (top.getType() != InventoryType.CRAFTING || top.getSize() < 5) return;

            for (int i = 0; i < 4; i++) top.setItem(i + 1, copy(gui.getItem(CRAFT + i)));
        } catch (Throwable ignored) {}
    }

    private void decorate(Inventory gui) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.displayName(net.kyori.adventure.text.Component.text(" "));
            pane.setItemMeta(meta);
        }
        for (int i = FILLER; i < SIZE; i++) gui.setItem(i, pane);
    }

    public static boolean isFiller(int slot) {
        return slot >= FILLER && slot < SIZE;
    }

    private ItemStack copy(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) return null;
        return stack.clone();
    }

    // ==================== TYPES ====================

    public enum Kind { LIVE, OFFLINE, ENDER }

    public static class Session {
        public final UUID targetId;
        public final Kind kind;
        public final BukkitTask task;

        public Session(UUID targetId, Kind kind, BukkitTask task) {
            this.targetId = targetId;
            this.kind = kind;
            this.task = task;
        }
    }

    public static class Holder implements InventoryHolder {
        public final UUID targetId;
        public final Kind kind;
        private Inventory inventory;

        public Holder(UUID targetId, Kind kind) {
            this.targetId = targetId;
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
