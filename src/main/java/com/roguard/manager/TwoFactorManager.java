package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Discord two factor state for linked players. */
public class TwoFactorManager {

    private final RoGuard plugin;
    private final SecureRandom random = new SecureRandom();

    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<String, UUID> requests = new ConcurrentHashMap<>();
    private final Map<String, UUID> linkCodes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> codeExpiry = new ConcurrentHashMap<>();

    public TwoFactorManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public boolean isPending(UUID uuid) {
        return pending.contains(uuid);
    }

    public void addPending(UUID uuid) {
        pending.add(uuid);
    }

    public void removePending(UUID uuid) {
        pending.remove(uuid);
    }

    public String createRequest(UUID uuid) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        requests.put(id, uuid);
        return id;
    }

    public UUID consumeRequest(String id) {
        return requests.remove(id);
    }

    /** Called when Discord approves the login. */
    public void approve(UUID uuid) {
        pending.remove(uuid);

        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) return;

        plugin.getServer().getScheduler().runTask(plugin, () -> {
            plugin.getLoginShieldManager().release(player);
            player.sendMessage(plugin.getLanguageManager().msg("2fa.verify-success"));
            player.showTitle(net.kyori.adventure.title.Title.title(
                    plugin.getLanguageManager().component("2fa.title-done"),
                    plugin.getLanguageManager().component("2fa.title-done-sub")
            ));
            plugin.getServer().getScheduler().runTaskLater(plugin, player::resetTitle, 60L);
        });
    }

    // ==================== LINK CODES ====================

    public String generateCode(UUID uuid) {
        linkCodes.entrySet().removeIf(entry -> entry.getValue().equals(uuid));

        String code = String.format("%06d", random.nextInt(1_000_000));
        linkCodes.put(code, uuid);

        long expiry = System.currentTimeMillis()
                + plugin.getConfigManager().codeExpire() * 1000L;
        codeExpiry.put(uuid, expiry);

        return code;
    }

    public UUID consumeCode(String code) {
        UUID uuid = linkCodes.get(code);
        if (uuid == null) return null;

        Long expiry = codeExpiry.get(uuid);
        if (expiry != null && System.currentTimeMillis() > expiry) {
            linkCodes.remove(code);
            codeExpiry.remove(uuid);
            return null;
        }

        linkCodes.remove(code);
        codeExpiry.remove(uuid);
        return uuid;
    }
}
