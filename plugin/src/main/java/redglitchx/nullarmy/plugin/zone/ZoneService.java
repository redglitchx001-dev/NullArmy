package redglitchx.nullarmy.plugin.zone;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.zone.SummonZone;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The summon zone of each owner.
 *
 * <p>A summon opens a zone of {@code summon.zone-size} centred on the horn user;
 * portals, arrivals and AI build steps all stay inside it. {@code /null zone}
 * shows its border to the player as a five-second particle outline (visible to
 * that player only) and writes the coordinates to the console.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ZoneService {

    /** A zone, the world it is in, and its origin block (relative coordinates start here). */
    public static final class Record {
        private final String world;
        private final SummonZone zone;
        private final int originX;
        private final int originY;
        private final int originZ;

        Record(String world, SummonZone zone, int originX, int originY, int originZ) {
            this.world = world;
            this.zone = zone;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        public String world() { return world; }
        public SummonZone zone() { return zone; }
        public int originX() { return originX; }
        public int originY() { return originY; }
        public int originZ() { return originZ; }

        public String describe() {
            return zone.describe() + " in " + world + ", origin block " + originX + "," + originY + "," + originZ;
        }
    }

    private final NullArmyPlugin plugin;
    private final Map<UUID, Record> zones = new HashMap<>();

    public ZoneService(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    private int size() {
        return plugin.pluginConfig() == null ? SummonZone.DEFAULT_SIZE : plugin.pluginConfig().v3().zoneSize();
    }

    /** Opens (or re-opens) the owner's zone centred on {@code centre}. */
    public Record open(UUID owner, String world, Vec3d centre) {
        Record record = new Record(world, new SummonZone(centre.x(), centre.z(), size()),
                (int) Math.floor(centre.x()), (int) Math.floor(centre.y()), (int) Math.floor(centre.z()));
        if (owner != null) {
            zones.put(owner, record);
        }
        return record;
    }

    /** The owner's zone, or a fresh one centred on them when they have none (or changed world). */
    public Record zoneOf(Player owner) {
        Record record = zones.get(owner.getUniqueId());
        Location at = owner.getLocation();
        if (record == null || !record.world().equals(at.getWorld().getName())) {
            record = open(owner.getUniqueId(), at.getWorld().getName(), new Vec3d(at.getX(), at.getY(), at.getZ()));
        }
        return record;
    }

    /** The owner's zone without creating one, or null. */
    public Record existing(UUID owner) {
        return owner == null ? null : zones.get(owner);
    }

    /** Draws the border for {@code seconds} to one player and logs the coordinates. */
    public void showOutline(Player player, Record record, int seconds) {
        if (player == null || record == null) {
            return;
        }
        plugin.getLogger().info("[NullArmy] summon zone for " + player.getName() + ": " + record.describe());
        World world = Bukkit.getWorld(record.world());
        if (world == null) {
            return;
        }
        final int[] runs = {0};
        final int total = Math.max(1, seconds * 2);
        final BukkitTask[] task = new BukkitTask[1];
        final Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(0xA855F7), 1.6F);
        final List<double[]> points = record.zone().outline(2.0D);
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Guard.attempt(plugin.getLogger(), "drawing the zone outline", () -> {
                if (!player.isOnline() || runs[0]++ >= total) {
                    task[0].cancel();
                    return;
                }
                double y = player.getLocation().getY() + 1.2D;
                Location here = player.getLocation();
                for (double[] p : points) {
                    double dx = p[0] - here.getX();
                    double dz = p[1] - here.getZ();
                    if (dx * dx + dz * dz > 96.0D * 96.0D) {
                        continue; // only what the player can see; keeps the packet count sane
                    }
                    player.spawnParticle(Particle.DUST, p[0], y, p[1], 1, 0.0D, 0.0D, 0.0D, 0.0D, dust);
                }
            });
        }, 0L, 10L);
    }

    public void clear() {
        zones.clear();
    }
}
