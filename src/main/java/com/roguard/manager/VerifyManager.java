package com.roguard.manager;

import com.roguard.RoGuard;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks commands and actions waiting for Discord approval. */
public class VerifyManager {

    public static final String ACTION_SHOPKEEPER = "shopkeeper";

    private final RoGuard plugin;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Commands already approved, consumed once on the next execution. */
    private final Set<String> approved = ConcurrentHashMap.newKeySet();

    /** Players allowed to change gamemode exactly once. */
    private final Set<UUID> gamemodePass = ConcurrentHashMap.newKeySet();

    /** Players allowed to edit shops until the timestamp. */
    private final Map<UUID, Long> shopPass = new ConcurrentHashMap<>();

    public VerifyManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public String submit(String sender, String command, boolean console, String action) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        pending.put(id, new Pending(sender, command, console, action));

        long ticks = plugin.getConfigManager().verifyTimeout() * 20L;
        plugin.getServer().getScheduler().runTaskLaterAsynchronously(
                plugin, () -> pending.remove(id), ticks);

        return id;
    }

    public Pending consume(String id) {
        return pending.remove(id);
    }

    // ==================== APPROVED COMMANDS ====================

    public void approveCommand(String key) {
        approved.add(key);
    }

    public boolean consumeCommand(String key) {
        return approved.remove(key);
    }

    // ==================== GAMEMODE ====================

    public void allowGamemode(UUID uuid) {
        gamemodePass.add(uuid);
    }

    public boolean consumeGamemode(UUID uuid) {
        return gamemodePass.remove(uuid);
    }

    // ==================== SHOPKEEPER ====================

    public void allowShop(UUID uuid) {
        long until = System.currentTimeMillis()
                + plugin.getConfigManager().getShopkeeperDuration() * 1000L;
        shopPass.put(uuid, until);
    }

    public boolean canEditShop(UUID uuid) {
        Long until = shopPass.get(uuid);
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            shopPass.remove(uuid);
            return false;
        }
        return true;
    }

    public void clear(UUID uuid) {
        gamemodePass.remove(uuid);
        shopPass.remove(uuid);
    }

    public record Pending(String sender, String command, boolean console, String action) {}
}
