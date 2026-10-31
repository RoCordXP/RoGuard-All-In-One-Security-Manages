package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Stores account data. Everything is cached in memory and written lazily. */
public class PlayerDataManager {

    private final RoGuard plugin;
    private final File folder;

    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Map<String, UUID> byName = new ConcurrentHashMap<>();

    public PlayerDataManager(RoGuard plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "playerdata");
        this.folder.mkdirs();
        indexNames();
    }

    /** Builds the name index once at startup so lookups never scan the disk. */
    private void indexNames() {
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
                String name = cfg.getString("name");
                if (name == null) continue;
                UUID uuid = UUID.fromString(file.getName().replace(".yml", ""));
                byName.put(name.toLowerCase(Locale.ROOT), uuid);
            } catch (Exception ignored) {
                // Skip malformed files.
            }
        }
    }

    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::read);
    }

    public PlayerData getByName(String name) {
        UUID uuid = byName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : get(uuid);
    }

    private PlayerData read(UUID uuid) {
        PlayerData data = new PlayerData(uuid);
        File file = new File(folder, uuid + ".yml");
        if (!file.exists()) return data;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        data.password = cfg.getString("password");
        data.name = cfg.getString("name");
        data.discordId = cfg.getString("discord");
        data.linked = cfg.getBoolean("linked", false);
        data.lastIp = cfg.getString("ip");
        data.lastLogin = cfg.getLong("last-login", 0L);
        data.joins = cfg.getInt("joins", 0);
        return data;
    }

    public void save(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("password", data.password);
        cfg.set("name", data.name);
        cfg.set("discord", data.discordId);
        cfg.set("linked", data.linked);
        cfg.set("ip", data.lastIp);
        cfg.set("last-login", data.lastLogin);
        cfg.set("joins", data.joins);

        try {
            cfg.save(new File(folder, uuid + ".yml"));
            if (data.name != null) byName.put(data.name.toLowerCase(Locale.ROOT), uuid);
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save player data: " + error.getMessage());
        }
    }

    /** Saves off the main thread. Used on quit. */
    public void saveAsync(UUID uuid) {
        if (!plugin.isEnabled()) {
            save(uuid);
            return;
        }
        plugin.getServer().getAsyncScheduler().runNow(plugin, task -> save(uuid));
    }

    public void saveAll() {
        for (UUID uuid : cache.keySet()) save(uuid);
    }

    public boolean delete(String name) {
        PlayerData data = getByName(name);
        if (data == null) return false;

        cache.remove(data.uuid);
        byName.remove(name.toLowerCase(Locale.ROOT));
        return new File(folder, data.uuid + ".yml").delete();
    }

    /** Clears only the stored password so the player registers again. */
    public boolean resetPassword(UUID uuid) {
        PlayerData data = get(uuid);
        if (data.password == null) return false;
        data.password = null;
        save(uuid);
        return true;
    }

    public static class PlayerData {
        public final UUID uuid;
        private String password;
        private String name;
        private String discordId;
        private boolean linked;
        private String lastIp;
        private long lastLogin;
        private int joins;

        public PlayerData(UUID uuid) {
            this.uuid = uuid;
        }

        public boolean isRegistered() { return password != null && !password.isEmpty(); }

        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }

        public String getName() { return name; }
        public void setName(String value) { name = value; }

        public String getDiscordId() { return discordId; }
        public void setDiscordId(String value) { discordId = value; }

        public boolean isLinked() { return linked; }
        public void setLinked(boolean value) { linked = value; }

        public String getLastIp() { return lastIp; }
        public void setLastIp(String value) { lastIp = value; }

        public long getLastLogin() { return lastLogin; }
        public void setLastLogin(long value) { lastLogin = value; }

        public int getJoins() { return joins; }
        public void addJoin() { joins++; }
    }
}
