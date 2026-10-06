package redglitchx.nullarmy.plugin.spectacle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.config.SummonRules;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * The air drop: portals in the sky and on the ground, a squad delivered through
 * them, and the same visual language the Wither Cannon uses.
 *
 * <h2>Two delivery modes, and the honest difference between them</h2>
 * <ul>
 *   <li><b>Safe emergence</b> ({@code drop-nulls-from-sky: false}) - every Null
 *       walks out of ground-level portal effects at a position the adapter
 *       verified as collision-safe. Nothing spawns mid-air, so nothing falls and
 *       nothing takes fall damage. This is the only mode that cannot conflict
 *       with the rule "a Null never starts anywhere unsafe".</li>
 *   <li><b>Sky drop</b> ({@code drop-nulls-from-sky: true}, the default
 *       <i>inside an already opt-in feature</i>) - Nulls appear just under the
 *       sky portal and descend under real vanilla gravity, including real fall
 *       damage. They do <b>not</b> teleport at the end: the velocity is
 *       bounded so an <i>air</i> fall is survivable, and once a Null touches
 *       ground the normal squad steering takes over, so it walks to the
 *       summoner exactly like one that was summoned on the ground.</li>
 * </ul>
 *
 * <p>Like the cannon, an air drop is off until the owner enables it, needs a
 * permission, needs {@code policy.explosives-enabled} and
 * {@code policy.wither-enabled} for the TNT part, and tracks every entity it
 * creates. A cross-world air drop is refused instead of guessed: the squads
 * live in one world, and pretending otherwise would produce Nulls in a place
 * the owner cannot see.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Airdrop implements Reloadable {

    /** Sky portal height above the summoner's feet, unless configured otherwise. */
    private static final int DEFAULT_HEIGHT = 12;

    /** Portal clusters drawn at the sky mouth and around the ground exit. */
    private static final int GROUND_CLUSTERS = 3;

    /** Default squad size when no count is given. */
    private static final int DEFAULT_COUNT = 8;

    /** Hard lifetime of a pending TNT drop, in ticks. */
    private static final int DROP_LIFETIME_TICKS = 100;

    /** The outcome of an airdrop attempt, ready to print. */
    public static final class Result {
        private final boolean dropped;
        private final String message;

        Result(boolean dropped, String message) {
            this.dropped = dropped;
            this.message = message;
        }

        public boolean dropped() { return dropped; }
        public String message() { return message; }
    }

    /** TNT waiting to be released one per tick. */
    private static final class PendingDrop {
        private final UUID owner;
        private final Location at;
        private final long startTick;
        private int remaining;

        PendingDrop(UUID owner, Location at, long startTick, int remaining) {
            this.owner = owner;
            this.at = at;
            this.startTick = startTick;
            this.remaining = remaining;
        }
    }

    private final NullArmyPlugin plugin;
    private final EntityRegistry registry;
    private final List<PendingDrop> pending = new ArrayList<>();
    private final Random random = new Random(20_260_101L);

    private PluginConfig config;

    public Airdrop(NullArmyPlugin plugin, EntityRegistry registry) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = plugin.pluginConfig();
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    /** True when the gates allow this player to call an air drop. */
    public boolean available(Player player) {
        PluginConfig current = config;
        if (current == null || !current.airdropEnabled()) {
            return false;
        }
        return player != null && player.hasPermission(current.airdropPermission());
    }

    /** Runs one air drop. Never throws; the Result explains what happened. */
    public Result drop(Player player, int requested) {
        try {
            return dropChecked(player, requested);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] airdrop failure: " + Guard.describe(t));
            return new Result(false, "The air drop failed: " + Guard.describe(t));
        }
    }

    private Result dropChecked(Player player, int requested) {
        PluginConfig current = config;
        if (player == null) {
            return new Result(false, "Only a player can call an air drop.");
        }
        if (current == null || !current.airdropEnabled()) {
            return new Result(false, "Air drops are off: airdrop.enabled is false in config.yml.");
        }
        if (!player.hasPermission(current.airdropPermission())) {
            return new Result(false, "You need the permission " + current.airdropPermission() + ".");
        }
        if (!Bukkit.isPrimaryThread()) {
            return new Result(false, "Air drops have to be called from the server thread.");
        }

        Location feet = player.getLocation();
        World world = feet == null ? null : feet.getWorld();
        if (world == null) {
            return new Result(false, "Could not read your world.");
        }

        Caps caps = current.caps();
        int wanted = requested > 0 ? requested : Math.min(DEFAULT_COUNT, caps.summonHardCap());
        SummonRules.Decision decision = SummonRules.decide(
                wanted, caps.summonHardCap(), plugin.squads().liveCount(), caps.maxLiveNpcs());
        if (decision.refused()) {
            return new Result(false, decision.explanation());
        }
        if (plugin.spawnBreaker().isOpen()) {
            return new Result(false, "Null creation is disabled this session: "
                    + plugin.spawnBreaker().reason());
        }

        // Deliberate scope limit: a squad belongs to one world. Refuse rather
        // than guess when the owner has moved worlds since the last summon.
        SquadManager.Squad owned = plugin.squads().find(player.getUniqueId());
        if (owned != null && owned.worldName() != null && !owned.worldName().isEmpty()
                && !owned.worldName().equals(world.getName())) {
            return new Result(false, "Your squad is in " + owned.worldName()
                    + " and you are in " + world.getName()
                    + ". Air drops cannot cross worlds; run /null dismiss first.");
        }

        int height = Math.max(3, Math.min(60, current.airdropHeight()));
        Location sky = feet.clone().add(0, height, 0);
        Location mouth = sky.clone().add(0, -1.0, 0);
        boolean fromSky = current.airdropDropNullsFromSky();

        // The show comes first: the sky doors open, then the ground exit.
        skyPortals(sky);
        groundPortals(feet);

        SquadManager.Squad squad;
        try {
            squad = fromSky
                    ? plugin.squads().createSquadAirborne(player.getUniqueId(),
                            world.getName(), toVec(mouth), decision.granted())
                    : plugin.squads().createSquad(player.getUniqueId(),
                            world.getName(), toVec(feet), decision.granted());
        } catch (IllegalStateException refusal) {
            return new Result(false, "Air drop failed: " + refusal.getMessage());
        }

        int spawned = squad.members().size();
        int tnt = 0;
        if (current.explosivesEnabled() && current.witherEnabled() && current.airdropTntPerDrop() > 0) {
            pending.add(new PendingDrop(player.getUniqueId(), sky, plugin.currentTick(),
                    current.airdropTntPerDrop()));
            tnt = current.airdropTntPerDrop();
        }

        StringBuilder message = new StringBuilder();
        message.append("Air drop: portals open above you");
        if (fromSky) {
            message.append("; ").append(spawned)
                    .append(spawned == 1 ? " Null is" : " Nulls are")
                    .append(" dropping out of the sky portal through ")
                    .append(height).append(" blocks of air, then walking to you.")
                    .append(" They take real fall damage if the landing is longer than expected.");
        } else {
            message.append("; ").append(spawned)
                    .append(spawned == 1 ? " Null emerged" : " Nulls emerged")
                    .append(" from the ground portal effects onto safe ground.");
        }
        if (spawned < decision.granted()) {
            message.append(" Only ").append(spawned).append(" of ")
                    .append(decision.granted()).append(" positions were usable.");
        }
        if (tnt > 0) {
            message.append(" ").append(tnt).append(" TNT incoming.");
        } else {
            message.append(" TNT was skipped (explosives/wither policy or tnt-per-drop is 0).");
        }
        return new Result(true, message.toString());
    }

    /** Opens portal clusters at the sky mouth. Cosmetic only. */
    private void skyPortals(Location sky) {
        if (sky == null || sky.getWorld() == null) {
            return;
        }
        String world = sky.getWorld().getName();
        int effects = portalEffects();
        for (int i = 0; i < GROUND_CLUSTERS; i++) {
            double offset = (i - 1) * 2.0;
            Vec3d point = new Vec3d(sky.getX() + offset, sky.getY(), sky.getZ() + offset);
            Guard.attempt(plugin.getLogger(), "airdrop sky portals",
                    () -> plugin.adapter().playPortalEffects(world, point, effects));
        }
    }

    /** Opens portal clusters around the ground exit. Cosmetic only. */
    private void groundPortals(Location feet) {
        if (feet == null || feet.getWorld() == null) {
            return;
        }
        String world = feet.getWorld().getName();
        int effects = portalEffects();
        for (int i = 0; i < GROUND_CLUSTERS; i++) {
            double angle = (2.0 * Math.PI * i) / GROUND_CLUSTERS;
            Vec3d point = new Vec3d(feet.getX() + Math.cos(angle) * 2.0, feet.getY(),
                    feet.getZ() + Math.sin(angle) * 2.0);
            Guard.attempt(plugin.getLogger(), "airdrop ground portals",
                    () -> plugin.adapter().playPortalEffects(world, point, effects));
        }
    }

    private int portalEffects() {
        PluginConfig current = config;
        if (current != null && !current.portalParticlesEnabled()) {
            return 0;
        }
        return Math.max(Caps.minPortalEffects(), current == null ? 16 : current.airdropPortals());
    }

    /** Releases at most one TNT per tick per air drop. */
    public void tick(long tickCounter) {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<PendingDrop> it = pending.iterator();
        while (it.hasNext()) {
            PendingDrop drop = it.next();
            try {
                if (drop.remaining <= 0 || tickCounter - drop.startTick > DROP_LIFETIME_TICKS) {
                    it.remove();
                    continue;
                }
                dropOne(drop.at);
                drop.remaining--;
            } catch (Throwable t) {
                plugin.getLogger().warning("[NullArmy] airdrop TNT skipped: " + Guard.describe(t));
                it.remove();
            }
        }
    }

    private void dropOne(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        double spreadX = (random.nextDouble() - 0.5) * 0.6;
        double spreadZ = (random.nextDouble() - 0.5) * 0.6;
        Entity tnt = at.getWorld().spawnEntity(at.clone().add(spreadX, 0.0, spreadZ), EntityType.TNT);
        if (tnt == null) {
            return;
        }
        tnt.setVelocity(new Vector(spreadX, -0.22, spreadZ));
        if (!registry.track(tnt)) {
            tnt.remove();
        }
    }

    /** Cancels pending TNT releases (tracked entities are removed by the registry). */
    public void stopAll() {
        pending.clear();
    }

    /** True while an air drop still owes TNT, for {@code /null status}. */
    public boolean hasPending() {
        return !pending.isEmpty();
    }

    private static Vec3d toVec(Location location) {
        return new Vec3d(location.getX(), location.getY(), location.getZ());
    }
}
