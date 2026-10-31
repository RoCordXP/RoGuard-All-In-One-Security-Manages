package com.roguard.manager;

import com.roguard.RoGuard;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Protects an unauthenticated player on clients that cannot show dialogs.
 *
 * The real position is stored, the player is moved to an empty holding spot
 * high above the world and blinded, so nothing around their base is revealed.
 * The exact original position is restored once they authenticate.
 */
public class LoginShieldManager {

    private final RoGuard plugin;
    private final Map<UUID, Location> realLocations = new ConcurrentHashMap<>();
    private final Map<UUID, Float> walkSpeeds = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> flyStates = new ConcurrentHashMap<>();

    public LoginShieldManager(RoGuard plugin) {
        this.plugin = plugin;
    }

    public boolean isShielded(UUID uuid) {
        return realLocations.containsKey(uuid);
    }

    public Location getRealLocation(UUID uuid) {
        return realLocations.get(uuid);
    }

    /** Applies the shield. Must run on the main thread. */
    public void apply(Player player) {
        UUID uuid = player.getUniqueId();
        if (realLocations.containsKey(uuid)) return;

        Location real = player.getLocation().clone();
        realLocations.put(uuid, real);

        var config = plugin.getConfigManager();

        if (config.useBlindness()) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 1, false, false, false));
        }

        // Freeze movement without fighting the move event every tick.
        walkSpeeds.put(uuid, player.getWalkSpeed());
        flyStates.put(uuid, player.getAllowFlight());
        player.setWalkSpeed(0f);
        player.setAllowFlight(true);
        player.setFlying(true);

        if (config.hideLocation()) {
            Location hold = buildHoldingSpot(real.getWorld(), config.getHoldHeight());
            player.teleportAsync(hold);
        }

        if (config.hidePlayers()) {
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (other.equals(player)) continue;
                player.hidePlayer(plugin, other);
                other.hidePlayer(plugin, player);
            }
        }
    }

    /** Removes the shield and puts the player back exactly where they were. */
    public void release(Player player) {
        UUID uuid = player.getUniqueId();
        Location real = realLocations.remove(uuid);

        player.removePotionEffect(PotionEffectType.BLINDNESS);

        Float speed = walkSpeeds.remove(uuid);
        player.setWalkSpeed(speed == null ? 0.2f : speed);

        Boolean fly = flyStates.remove(uuid);
        boolean allowFly = fly != null && fly;
        player.setFlying(false);
        player.setAllowFlight(allowFly);

        if (plugin.getConfigManager().hidePlayers()) {
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (other.equals(player)) continue;
                player.showPlayer(plugin, other);
                other.showPlayer(plugin, player);
            }
        }

        if (real != null && plugin.getConfigManager().hideLocation()) {
            player.teleportAsync(real);
        }
    }

    /** Drops stored state without touching the player object. */
    public void forget(UUID uuid) {
        realLocations.remove(uuid);
        walkSpeeds.remove(uuid);
        flyStates.remove(uuid);
    }

    private Location buildHoldingSpot(World world, int height) {
        Location spawn = world.getSpawnLocation();
        return new Location(world, spawn.getX() + 0.5, height, spawn.getZ() + 0.5, 0f, 0f);
    }
}
