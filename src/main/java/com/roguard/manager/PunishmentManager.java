package com.roguard.manager;

import com.roguard.RoGuard;
import com.roguard.util.TextUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bans, address bans, mutes and freezes. */
public class PunishmentManager {

    private final RoGuard plugin;
    private final File muteFile;
    private YamlConfiguration mutes;

    /** Cached mute state so the chat handler never reads YAML. */
    private final Map<UUID, Mute> muteCache = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    public PunishmentManager(RoGuard plugin) {
        this.plugin = plugin;
        this.muteFile = new File(plugin.getDataFolder(), "mutes.yml");
        load();
    }

    private void load() {
        if (!muteFile.exists()) {
            try {
                muteFile.getParentFile().mkdirs();
                muteFile.createNewFile();
            } catch (IOException ignored) {}
        }

        mutes = YamlConfiguration.loadConfiguration(muteFile);
        muteCache.clear();

        for (String key : mutes.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                muteCache.put(uuid, new Mute(
                        mutes.getString(key + ".reason", ""),
                        mutes.getLong(key + ".expires", -1L)));
            } catch (Exception ignored) {}
        }
    }

    public void reload() {
        load();
    }

    private void persist() {
        try {
            mutes.save(muteFile);
        } catch (IOException error) {
            plugin.getLogger().warning("Could not save mutes.yml: " + error.getMessage());
        }
    }

    // ==================== BAN ====================

    public void ban(OfflinePlayer target, String reason, String source, Long expiresAt) {
        String name = target.getName();
        if (name == null) return;

        Date expiry = expiresAt == null ? null : new Date(expiresAt);
        Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, expiry, source);

        Player online = target.getPlayer();
        if (online != null) {
            online.kick(TextUtil.component(screen(reason, expiresAt)));
        }
    }

    public void banAddress(OfflinePlayer target, String reason, String source, Long expiresAt) {
        String ip = addressOf(target);
        if (ip == null || ip.isEmpty()) return;

        Date expiry = expiresAt == null ? null : new Date(expiresAt);
        Bukkit.getBanList(BanList.Type.IP).addBan(ip, reason, expiry, source);

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getAddress() == null) continue;
            if (!ip.equals(online.getAddress().getAddress().getHostAddress())) continue;
            online.kick(TextUtil.component(screen(reason, expiresAt)));
        }
    }

    public boolean isBanned(OfflinePlayer target) {
        String name = target.getName();
        return name != null && Bukkit.getBanList(BanList.Type.NAME).isBanned(name);
    }

    public void unban(OfflinePlayer target) {
        String name = target.getName();
        if (name != null) Bukkit.getBanList(BanList.Type.NAME).pardon(name);

        String ip = addressOf(target);
        if (ip != null && !ip.isEmpty()) Bukkit.getBanList(BanList.Type.IP).pardon(ip);
    }

    public void kick(Player target, String reason) {
        String screen = plugin.getLanguageManager().get("punishment.kick-screen", "%reason%", reason);
        target.kick(TextUtil.component(screen));
    }

    private String screen(String reason, Long expiresAt) {
        if (expiresAt == null) {
            return plugin.getLanguageManager().get("punishment.ban-screen", "%reason%", reason);
        }
        return plugin.getLanguageManager().get("punishment.tempban-screen",
                "%reason%", reason,
                "%time%", TextUtil.duration(expiresAt - System.currentTimeMillis()));
    }

    public String addressOf(OfflinePlayer target) {
        Player online = target.getPlayer();
        if (online != null && online.getAddress() != null) {
            return online.getAddress().getAddress().getHostAddress();
        }
        return plugin.getPlayerDataManager().get(target.getUniqueId()).getLastIp();
    }

    // ==================== MUTE ====================

    public void mute(UUID uuid, String reason, String source, Long expiresAt) {
        long expires = expiresAt == null ? -1L : expiresAt;

        mutes.set(uuid + ".reason", reason);
        mutes.set(uuid + ".source", source);
        mutes.set(uuid + ".expires", expires);
        persist();

        muteCache.put(uuid, new Mute(reason, expires));
    }

    public void unmute(UUID uuid) {
        mutes.set(uuid.toString(), null);
        persist();
        muteCache.remove(uuid);
    }

    public boolean isMuted(UUID uuid) {
        Mute mute = muteCache.get(uuid);
        if (mute == null) return false;

        if (mute.expires > 0 && System.currentTimeMillis() > mute.expires) {
            unmute(uuid);
            return false;
        }
        return true;
    }

    public String muteReason(UUID uuid) {
        Mute mute = muteCache.get(uuid);
        return mute == null ? "" : mute.reason;
    }

    public String muteMessage(UUID uuid) {
        Mute mute = muteCache.get(uuid);
        if (mute == null) return "";

        if (mute.expires > 0) {
            return plugin.getLanguageManager().msg("punishment.muted-temp",
                    "%reason%", mute.reason,
                    "%time%", TextUtil.duration(mute.expires - System.currentTimeMillis()));
        }
        return plugin.getLanguageManager().msg("punishment.muted", "%reason%", mute.reason);
    }

    // ==================== FREEZE ====================

    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    public void setFrozen(UUID uuid, boolean value) {
        if (value) frozen.add(uuid);
        else frozen.remove(uuid);
    }

    private record Mute(String reason, long expires) {}
}
