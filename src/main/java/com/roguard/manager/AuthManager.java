package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.entity.Player;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Password hashing and login state. */
public class AuthManager {

    private static final String PREFIX = "pbkdf2";
    private static final int ITERATIONS = 60_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final RoGuard plugin;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> attempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessions = new ConcurrentHashMap<>();

    public AuthManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    // ==================== STATE ====================

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.contains(uuid);
    }

    public void markAuthenticated(UUID uuid) {
        authenticated.add(uuid);
        attempts.remove(uuid);
        sessions.put(uuid, System.currentTimeMillis());
    }

    public void clear(UUID uuid) {
        authenticated.remove(uuid);
    }

    public int addAttempt(UUID uuid) {
        return attempts.merge(uuid, 1, Integer::sum);
    }

    public boolean hasValidSession(UUID uuid, String ip) {
        if (!plugin.getConfigManager().isSessionEnabled()) return false;

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        if (data.getLastIp() == null || !data.getLastIp().equals(ip)) return false;

        Long last = sessions.get(uuid);
        if (last == null) return false;

        long window = plugin.getConfigManager().getSessionMinutes() * 60_000L;
        return System.currentTimeMillis() - last < window;
    }

    // ==================== ACCOUNT ====================

    public boolean register(UUID uuid, String name, String password, String ip) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        if (data.isRegistered()) return false;

        data.setPassword(hash(password));
        data.setName(name);
        if (ip != null && !ip.isEmpty()) data.setLastIp(ip);
        data.setLastLogin(System.currentTimeMillis());
        plugin.getPlayerDataManager().save(uuid);
        return true;
    }

    public boolean verify(UUID uuid, String password) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        if (!data.isRegistered()) return false;

        if (!matches(password, data.getPassword())) return false;

        // Transparently upgrade old SHA-256 records.
        if (!data.getPassword().startsWith(PREFIX + "$")) {
            data.setPassword(hash(password));
            plugin.getPlayerDataManager().save(uuid);
        }
        return true;
    }

    public void recordLogin(UUID uuid, String name, String ip) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        data.setName(name);
        if (ip != null && !ip.isEmpty()) data.setLastIp(ip);
        data.setLastLogin(System.currentTimeMillis());
        data.addJoin();
        plugin.getPlayerDataManager().saveAsync(uuid);
    }

    public boolean changePassword(UUID uuid, String oldPass, String newPass) {
        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(uuid);
        if (!data.isRegistered() || !matches(oldPass, data.getPassword())) return false;

        data.setPassword(hash(newPass));
        plugin.getPlayerDataManager().save(uuid);
        return true;
    }

    /** Starts Discord 2FA if the account is linked. */
    public boolean beginTwoFactor(Player player) {
        if (!plugin.getConfigManager().isPlayer2faEnabled()) return false;

        PlayerDataManager.PlayerData data = plugin.getPlayerDataManager().get(player.getUniqueId());
        if (!data.isLinked() || data.getDiscordId() == null || data.getDiscordId().isEmpty()) {
            return false;
        }

        plugin.getTwoFactorManager().addPending(player.getUniqueId());
        if (plugin.getTwoFaBot() != null) {
            String id = plugin.getTwoFactorManager().createRequest(player.getUniqueId());
            plugin.getTwoFaBot().sendVerification(data.getDiscordId(), player.getName(), id);
        }
        return true;
    }

    // ==================== HASHING ====================

    private String hash(String password) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] key = derive(password, salt, ITERATIONS);
            return PREFIX + "$" + ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(key);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash password", error);
        }
    }

    private boolean matches(String password, String stored) {
        if (stored == null || stored.isEmpty()) return false;

        try {
            if (stored.startsWith(PREFIX + "$")) {
                String[] parts = stored.split("\\$", 4);
                if (parts.length != 4) return false;

                int iterations = Integer.parseInt(parts[1]);
                byte[] salt = Base64.getDecoder().decode(parts[2]);
                byte[] expected = Base64.getDecoder().decode(parts[3]);
                return MessageDigest.isEqual(expected, derive(password, salt, iterations));
            }

            return MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.UTF_8),
                    legacy(password).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return false;
        }
    }

    private byte[] derive(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private String legacy(String password) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }
}
