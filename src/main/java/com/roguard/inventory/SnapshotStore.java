package com.roguard.inventory;

import com.roguard.RoGuard;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Saves a copy of every inventory so offline players can still be edited.
 * Writes happen on quit and on shutdown, edits apply on the next join.
 */
public class SnapshotStore {

    private final RoGuard plugin;
    private final File folder;

    public SnapshotStore(RoGuard plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "inventories");
        this.folder.mkdirs();
    }

    private File fileFor(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    // ==================== SAVE ====================

    public void saveFromPlayer(Player player) {
        PlayerInventory inv = player.getInventory();
        YamlConfiguration config = new YamlConfiguration();

        for (int i = 0; i < 36; i++) config.set("slot." + i, inv.getItem(i));

        ItemStack[] armour = inv.getArmorContents();
        for (int i = 0; i < armour.length; i++) config.set("armour." + i, armour[i]);

        config.set("offhand", inv.getItemInOffHand());
        config.set("ender", player.getEnderChest().getContents());
        config.set("name", player.getName());
        config.set("saved", System.currentTimeMillis());

        write(uuidOf(player), config);
    }

    /** Saves off the main thread. Used during quit handling. */
    public void saveAsync(Player player) {
        PlayerInventory inv = player.getInventory();
        YamlConfiguration config = new YamlConfiguration();

        for (int i = 0; i < 36; i++) config.set("slot." + i, inv.getItem(i));
        ItemStack[] armour = inv.getArmorContents();
        for (int i = 0; i < armour.length; i++) config.set("armour." + i, armour[i]);
        config.set("offhand", inv.getItemInOffHand());
        config.set("ender", player.getEnderChest().getContents());
        config.set("name", player.getName());
        config.set("saved", System.currentTimeMillis());

        UUID uuid = uuidOf(player);
        if (!plugin.isEnabled()) {
            write(uuid, config);
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> write(uuid, config));
    }

    public void saveFromGui(UUID uuid, Inventory gui) {
        YamlConfiguration config = read(uuid);

        for (int i = 0; i < 9; i++) config.set("slot." + i, gui.getItem(27 + i));
        for (int i = 0; i < 27; i++) config.set("slot." + (i + 9), gui.getItem(i));
        for (int i = 0; i < 4; i++) config.set("armour." + i, gui.getItem(InventoryManager.ARMOUR + i));

        config.set("offhand", gui.getItem(InventoryManager.OFFHAND));
        config.set("pending", true);

        write(uuid, config);
    }

    public void clearSnapshot(UUID uuid) {
        YamlConfiguration config = read(uuid);
        for (int i = 0; i < 36; i++) config.set("slot." + i, null);
        for (int i = 0; i < 4; i++) config.set("armour." + i, null);
        config.set("offhand", null);
        config.set("pending", true);
        write(uuid, config);
    }

    // ==================== LOAD ====================

    public boolean load(UUID uuid, Inventory gui) {
        File file = fileFor(uuid);
        if (!file.exists()) return false;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        for (int i = 0; i < 9; i++) gui.setItem(27 + i, config.getItemStack("slot." + i));
        for (int i = 0; i < 27; i++) gui.setItem(i, config.getItemStack("slot." + (i + 9)));
        for (int i = 0; i < 4; i++) {
            gui.setItem(InventoryManager.ARMOUR + i, config.getItemStack("armour." + i));
        }
        gui.setItem(InventoryManager.OFFHAND, config.getItemStack("offhand"));

        // The crafting grid cannot exist for an offline player.
        for (int i = 0; i < 4; i++) gui.setItem(InventoryManager.CRAFT + i, null);

        return true;
    }

    public boolean hasPending(UUID uuid) {
        File file = fileFor(uuid);
        if (!file.exists()) return false;
        return YamlConfiguration.loadConfiguration(file).getBoolean("pending", false);
    }

    public void applyPending(Player player) {
        UUID uuid = uuidOf(player);
        if (!hasPending(uuid)) return;

        YamlConfiguration config = read(uuid);
        PlayerInventory inv = player.getInventory();

        for (int i = 0; i < 36; i++) inv.setItem(i, config.getItemStack("slot." + i));

        ItemStack[] armour = new ItemStack[4];
        for (int i = 0; i < 4; i++) armour[i] = config.getItemStack("armour." + i);
        inv.setArmorContents(armour);

        ItemStack offhand = config.getItemStack("offhand");
        inv.setItemInOffHand(offhand == null ? new ItemStack(Material.AIR) : offhand);

        player.updateInventory();

        config.set("pending", false);
        write(uuid, config);

        player.sendMessage(plugin.getLanguageManager().msg("playermanager.inventory-changed"));
    }

    // ==================== IO ====================

    private UUID uuidOf(Player player) {
        return player.getUniqueId();
    }

    private YamlConfiguration read(UUID uuid) {
        File file = fileFor(uuid);
        return file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
    }

    private void write(UUID uuid, YamlConfiguration config) {
        try {
            config.save(fileFor(uuid));
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save inventory snapshot: " + error.getMessage());
        }
    }
}
