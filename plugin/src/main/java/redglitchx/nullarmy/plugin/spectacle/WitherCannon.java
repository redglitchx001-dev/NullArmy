package redglitchx.nullarmy.plugin.spectacle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The Wither Cannon: a real TNT minecart that arcs into the sky, opens portals
 * at the apex and drops TNT out of them.
 *
 * <h2>It is off, and it stays off until you say otherwise</h2>
 * <p>Five independent things have to agree before a single entity is created:
 * {@code wither-cannon.enabled}, {@code policy.explosives-enabled},
 * {@code policy.wither-enabled}, the configured permission, and the per-player
 * charge/cooldown. When one is missing the player is told <b>which one</b>.</p>
 *
 * <h2>It cannot hurt the map by accident</h2>
 * <p>Block damage needs a fourth opt-in
 * ({@code policy.griefing-enabled} plus {@code wither-cannon.blocks-damage}).
 * Without both, {@link EntityRegistry} strips the block list out of every
 * explosion this cannon causes: the blast, sound, light, knockback and entity
 * damage all still happen, and no block is ever destroyed.</p>
 *
 * <h2>It cannot dump two hundred TNT in one tick</h2>
 * <p>Each shot is a tracked {@link Shot} advanced once per tick which drops
 * <b>one</b> TNT per tick at most, has a hard lifetime, and registers every
 * entity it creates so {@code /null stop}, {@code /null dismiss} and
 * {@code onDisable} can remove them.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class WitherCannon implements Reloadable {

    /** Portal clusters drawn in the sky. Spec 3's floor applies per cluster. */
    private static final int SKY_CLUSTERS = 3;

    /** Hard lifetime of one shot, in ticks (8s). A shot outliving this is stuck. */
    private static final int SHOT_LIFETIME_TICKS = 160;

    /** Consecutive failed TNT spawns before a shot gives up and says so. */
    private static final int DROP_FAILURE_LIMIT = 3;

    /** Shots that may be in the air at once, so a macro cannot fill the entity cap. */
    private static final int MAX_CONCURRENT_SHOTS = 3;

    /** Downward velocity of each dropped TNT. */
    private static final double TNT_DROP_SPEED = 0.18;

    /** Consecutive failures before the cannon switches itself off. */
    private static final int FAILURE_LIMIT = 3;

    /** The outcome of a fire attempt, ready to print. */
    public static final class Result {
        private final boolean fired;
        private final String message;

        Result(boolean fired, String message) {
            this.fired = fired;
            this.message = message;
        }

        public boolean fired() { return fired; }
        public String message() { return message; }
    }

    /** One shot in flight. */
    private static final class Shot {
        private final UUID owner;
        private final Entity cart;
        private final long startTick;
        private Location lastLocation;
        /** The highest point the cart reached: where the doors open. */
        private Location apex;
        private boolean apexReached;
        private int tntRemaining;
        private int droppedByAir;
        /** Consecutive failed TNT spawns. Bounded, so a shot cannot retry forever. */
        private int dropFailures;
        /** Why the shot ended, in the owner's words. */
        private String outcome = "";

        Shot(UUID owner, Entity cart, long startTick, Location start, int tntRemaining) {
            this.owner = owner;
            this.cart = cart;
            this.startTick = startTick;
            this.lastLocation = start;
            this.apex = start;
            this.tntRemaining = tntRemaining;
        }
    }

    /** Per-player charge and cooldown bookkeeping. */
    private static final class Charge {
        private int remaining;
        private long refillAtMillis;
    }

    private final NullArmyPlugin plugin;
    private final EntityRegistry registry;
    private final List<Shot> shots = new ArrayList<>();
    private final Map<UUID, Charge> charges = new HashMap<>();
    private final Guard.Breaker breaker = new Guard.Breaker("wither cannon");
    private final Random random = new Random(20_260_101L);

    private PluginConfig config;
    private int consecutiveFailures;

    public WitherCannon(NullArmyPlugin plugin, EntityRegistry registry) {
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

    /** True when gating allows this player to fire right now, ignoring charges. */
    public boolean available(Player player) {
        PluginConfig current = config;
        if (current == null || !current.witherCannonUsable()) {
            return false;
        }
        return player != null && player.hasPermission(current.witherCannonPermission());
    }

    /** Fires one shot. Never throws; the Result says what happened and why. */
    public Result fire(Player player) {
        try {
            return fireChecked(player);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] wither cannon failure: " + Guard.describe(t));
            noteFailure();
            return new Result(false, "The cannon failed: " + Guard.describe(t));
        }
    }

    private Result fireChecked(Player player) {
        PluginConfig current = config;
        if (player == null) {
            return new Result(false, "Only a player can aim the cannon.");
        }
        if (current == null || !current.witherCannonEnabled()) {
            return new Result(false, "The wither cannon is off: wither-cannon.enabled is false in config.yml.");
        }
        if (!current.explosivesEnabled()) {
            return new Result(false, "Explosives are disabled: set policy.explosives-enabled to true in config.yml.");
        }
        if (!current.witherEnabled()) {
            return new Result(false, "Wither content is disabled: set policy.wither-enabled to true in config.yml.");
        }
        if (!player.hasPermission(current.witherCannonPermission())) {
            return new Result(false, "You need the permission " + current.witherCannonPermission() + ".");
        }
        if (breaker.isOpen()) {
            return new Result(false, "The cannon is disabled for this session: " + breaker.reason());
        }
        if (!Bukkit.isPrimaryThread()) {
            return new Result(false, "The cannon has to be fired from the server thread.");
        }

        Location eye = player.getEyeLocation();
        World world = eye == null ? null : eye.getWorld();
        if (world == null) {
            return new Result(false, "Could not read your world.");
        }

        String cooldown = checkCharge(player, current);
        if (cooldown != null) {
            return new Result(false, cooldown);
        }

        if (shots.size() >= MAX_CONCURRENT_SHOTS) {
            return new Result(false, MAX_CONCURRENT_SHOTS + " shots are already in the air;"
                    + " wait for one to finish.");
        }

        Vector direction = eye.getDirection();
        if (direction == null || direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(0, 0, 1);
        }
        direction = direction.normalize().multiply(1.15);
        Vector velocity = new Vector(direction.getX(), 1.35, direction.getZ());

        // The cart has to be created in free air. A minecart spawned inside a
        // block is destroyed (or explodes) the same tick, which is how a shot
        // used to "succeed" and then deliver nothing.
        Location spawn = launchSite(eye, direction, world);
        if (spawn == null) {
            return new Result(false, "There is no free air in front of you to launch from -"
                    + " step back into the open and try again.");
        }

        Entity cart;
        try {
            cart = world.spawnEntity(spawn, EntityType.TNT_MINECART);
        } catch (Throwable t) {
            noteFailure();
            return new Result(false, "The server threw while creating the minecart: "
                    + Guard.describe(t));
        }
        if (cart == null || !cart.isValid()) {
            noteFailure();
            return new Result(false, "The server created no minecart, so nothing was fired."
                    + " (spawnEntity returned " + (cart == null ? "null" : "an invalid entity") + ")");
        }
        try {
            cart.setVelocity(velocity);
        } catch (Throwable t) {
            cart.remove();
            return new Result(false, "The minecart could not be given its arc: " + Guard.describe(t));
        }
        if (!registry.track(cart)) {
            cart.remove();
            return new Result(false, "Too many tracked entities (" + registry.maxTracked()
                    + "); use /null stop to clear them first.");
        }

        shots.add(new Shot(player.getUniqueId(), cart, plugin.currentTick(), spawn,
                current.witherCannonTntPerShot()));
        consecutiveFailures = 0;
        return new Result(true, "Shot away (minecart #" + cart.getEntityId() + "). Watch the sky -"
                + " the doors open at the top of the arc and " + current.witherCannonTntPerShot()
                + " TNT come through them.");
    }

    /**
     * Finds free air to launch from.
     *
     * <p>Tries the natural spot in front of the player's eye first, then a little
     * higher and a little further out. Every candidate has to be inside the world
     * height and free of blocks, because a cart created inside a wall is a cart
     * that never flies.</p>
     *
     * @return a validated launch location, or null when there is nowhere to fire from
     */
    private Location launchSite(Location eye, Vector direction, World world) {
        double[][] offsets = {{1.2, 0.0}, {1.6, 0.4}, {2.0, 0.8}, {1.2, 1.0}, {2.4, 0.0}};
        for (double[] offset : offsets) {
            Location candidate = eye.clone()
                    .add(direction.clone().multiply(offset[0]))
                    .add(0.0, offset[1], 0.0);
            if (candidate.getY() < world.getMinHeight() + 1
                    || candidate.getY() > world.getMaxHeight() - 2) {
                continue;
            }
            if (candidate.getBlock().getType().isAir()
                    && candidate.clone().add(0, 1, 0).getBlock().getType().isAir()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Charge and cooldown.
     *
     * @return null when the shot may proceed, otherwise the refusal message
     */
    private String checkCharge(Player player, PluginConfig current) {
        long now = System.currentTimeMillis();
        Charge charge = charges.get(player.getUniqueId());
        if (charge == null) {
            charge = new Charge();
            charge.remaining = current.witherCannonMaxCharge();
            charges.put(player.getUniqueId(), charge);
        }
        if (charge.remaining <= 0) {
            if (now < charge.refillAtMillis) {
                long seconds = Math.max(1L, (charge.refillAtMillis - now + 999L) / 1000L);
                return "The cannon is cooling down for another " + seconds + "s.";
            }
            charge.remaining = current.witherCannonMaxCharge();
        }
        charge.remaining--;
        if (charge.remaining <= 0) {
            charge.refillAtMillis = now + (current.witherCannonCooldownSeconds() * 1000L);
        }
        return null;
    }

    /** Advances every shot in flight. One tick, bounded work, no exceptions. */
    public void tick(long tickCounter) {
        if (shots.isEmpty()) {
            return;
        }
        Iterator<Shot> it = shots.iterator();
        while (it.hasNext()) {
            Shot shot = it.next();
            boolean finished;
            try {
                finished = advance(shot, tickCounter);
            } catch (Throwable t) {
                plugin.getLogger().warning("[NullArmy] cannon shot ended early: " + Guard.describe(t));
                noteFailure();
                finished = true;
            }
            if (finished) {
                finish(shot);
                it.remove();
            }
        }
    }

    /** @return true when the shot is done */
    private boolean advance(Shot shot, long tickCounter) {
        Entity cart = shot.cart;
        boolean alive = cart != null && !cart.isDead() && cart.isValid();
        if (alive) {
            Location now = cart.getLocation();
            if (now != null) {
                shot.lastLocation = now;
                if (shot.apex == null || now.getY() > shot.apex.getY()) {
                    shot.apex = now;
                }
            }
        }

        // A shot that has outlived its window is stuck - it is ended here rather
        // than left retrying for the rest of the session with its entities behind.
        if (tickCounter - shot.startTick > SHOT_LIFETIME_TICKS) {
            shot.outcome = "the shot timed out after " + (SHOT_LIFETIME_TICKS / 20) + "s";
            cleanup(shot);
            return true;
        }

        if (!shot.apexReached) {
            if (!alive) {
                // Lost the cart before the arc topped out (a roof, a wall, another
                // plugin). The show still happens, from the highest point it
                // actually reached - never from the player's own eye position.
                shot.apexReached = true;
                shot.outcome = "the minecart was destroyed on the way up";
                openSkyPortals(deliveryPoint(shot));
                shot.tntRemaining = Math.min(shot.tntRemaining, 1);
            } else {
                Vector velocity = cart.getVelocity();
                if (velocity == null || velocity.getY() <= 0.0
                        || tickCounter - shot.startTick > 60) {
                    shot.apexReached = true;
                    openSkyPortals(deliveryPoint(shot));
                }
            }
            return false;
        }

        // Drop phase: exactly one TNT per tick, so a shot can never empty a
        // payload into a single tick. Failures are counted, and a shot that cannot
        // deliver gives up instead of retrying for ever.
        if (shot.tntRemaining > 0) {
            if (dropOne(shot)) {
                shot.tntRemaining--;
                shot.dropFailures = 0;
            } else {
                shot.dropFailures++;
                if (shot.dropFailures >= DROP_FAILURE_LIMIT) {
                    shot.outcome = shot.tntRemaining + " TNT could not be created";
                    cleanup(shot);
                    return true;
                }
            }
            return false;
        }

        // Delivery done: take the cart out before it can land and explode somewhere
        // nobody asked for.
        shot.outcome = shot.droppedByAir + " TNT delivered through the sky portals";
        cleanup(shot);
        return true;
    }

    /** Where the doors open and the TNT comes out: the highest point reached. */
    private Location deliveryPoint(Shot shot) {
        if (shot.apex != null && shot.apex.getWorld() != null) {
            return shot.apex;
        }
        return shot.lastLocation;
    }

    /** Removes the cart and stops tracking it, whatever state it is in. */
    private void cleanup(Shot shot) {
        registry.untrack(shot.cart);
        try {
            if (shot.cart != null && shot.cart.isValid() && !shot.cart.isDead()) {
                shot.cart.remove();
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] cannon cart cleanup skipped: " + Guard.describe(t));
        }
    }

    /**
     * Creates one TNT at the delivery point.
     *
     * @return true when an entity really exists now; false is counted, and the
     *     shot gives up after {@link #DROP_FAILURE_LIMIT} of them
     */
    private boolean dropOne(Shot shot) {
        Location at = deliveryPoint(shot);
        if (at == null || at.getWorld() == null) {
            shot.outcome = "the delivery point was lost";
            return false;
        }
        World world = at.getWorld();
        double spreadX = (random.nextDouble() - 0.5) * 0.4;
        double spreadZ = (random.nextDouble() - 0.5) * 0.4;
        Location drop = at.clone().add(spreadX, -0.5, spreadZ);
        Entity tnt;
        try {
            tnt = world.spawnEntity(drop, EntityType.TNT);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] TNT spawn failed: " + Guard.describe(t));
            return false;
        }
        if (tnt == null || !tnt.isValid()) {
            return false;
        }
        tnt.setVelocity(new Vector(spreadX, -TNT_DROP_SPEED, spreadZ));
        if (tnt instanceof TNTPrimed) {
            // 3s fuse: it falls, it lands, then it goes off. Long enough to be
            // seen, short enough to connect with the ground.
            ((TNTPrimed) tnt).setFuseTicks(60);
        }
        if (!registry.track(tnt)) {
            tnt.remove();
            return false;
        }
        shot.droppedByAir++;
        return true;
    }

    /** Opens several portal clusters in the sky at the apex. Cosmetic only. */
    private void openSkyPortals(Location at) {
        if (at == null || at.getWorld() == null) {
            return;
        }
        String world = at.getWorld().getName();
        int effects = Math.max(15, config == null ? 16 : config.airdropPortals());
        for (int i = 0; i < SKY_CLUSTERS; i++) {
            double offset = (i - (SKY_CLUSTERS - 1) / 2.0) * 2.5;
            Vec3d point = new Vec3d(at.getX() + offset, at.getY() + 1.0, at.getZ() + offset * 0.5);
            Guard.attempt(plugin.getLogger(), "sky portal effects",
                    () -> plugin.adapter().playPortalEffects(world, point, effects));
        }
    }

    /**
     * Reports the shot honestly.
     *
     * <p>A shot that delivered nothing says so, with the reason. Reporting
     * "complete" over an empty sky is exactly the failure this class is here to
     * stop.</p>
     */
    private void finish(Shot shot) {
        cleanup(shot);
        if (shot.droppedByAir <= 0) {
            noteFailure();
        } else {
            consecutiveFailures = 0;
        }
        try {
            Player owner = Bukkit.getPlayer(shot.owner);
            if (owner != null && owner.isOnline()) {
                if (shot.droppedByAir <= 0) {
                    owner.sendMessage(PluginText.PREFIX + "Shot failed: no TNT was delivered. "
                            + (shot.outcome.isEmpty() ? "The reason was not recorded." : shot.outcome)
                            + ".");
                } else {
                    owner.sendMessage(PluginText.PREFIX + "Shot complete: " + shot.droppedByAir
                            + " TNT delivered through the sky portals"
                            + (shot.outcome.isEmpty() ? "." : " (" + shot.outcome + ")."));
                    if (config != null && config.witherCannonBlocksDamage()) {
                        owner.sendMessage(PluginText.PREFIX + "Block damage is enabled for this shot.");
                    } else {
                        owner.sendMessage(PluginText.PREFIX + "Block damage is off: the blasts are"
                                + " visual only.");
                    }
                }
            }
        } catch (Throwable ignored) {
            // A missing player is not a failure.
        }
    }

    /** Emergency stop: removes every tracked shot entity immediately. */
    public void stopAll() {
        for (Shot shot : new ArrayList<>(shots)) {
            Guard.attempt(plugin.getLogger(), "stopping a cannon shot", () -> {
                registry.untrack(shot.cart);
                if (shot.cart != null && shot.cart.isValid()) {
                    shot.cart.remove();
                }
            });
        }
        shots.clear();
    }

    /** Shots currently in flight, for {@code /null status}. */
    public int shotsInFlight() {
        return shots.size();
    }

    /**
     * The cannon's state in one line, for {@code /null status} and {@code /null debug}.
     *
     * <p>When it cannot fire, the line names the exact setting or permission that
     * is missing rather than just saying "off".</p>
     */
    public String describeState(Player viewer) {
        PluginConfig current = config;
        if (current == null) {
            return "no config loaded";
        }
        if (!current.witherCannonEnabled()) {
            return "DISABLED - set wither-cannon.enabled: true";
        }
        if (!current.explosivesEnabled()) {
            return "BLOCKED - set policy.explosives-enabled: true";
        }
        if (!current.witherEnabled()) {
            return "BLOCKED - set policy.wither-enabled: true";
        }
        if (viewer != null && !viewer.hasPermission(current.witherCannonPermission())) {
            return "BLOCKED for you - needs permission " + current.witherCannonPermission();
        }
        if (breaker.isOpen()) {
            return "LATCHED OFF this session - " + breaker.reason()
                    + " (run /null reload to re-arm)";
        }
        return "READY - " + shots.size() + "/" + MAX_CONCURRENT_SHOTS + " shots in flight, "
                + current.witherCannonTntPerShot() + " TNT per shot, charge "
                + current.witherCannonMaxCharge() + ", cooldown "
                + current.witherCannonCooldownSeconds() + "s, block damage "
                + (current.witherCannonBlocksDamage() ? "ON" : "off");
    }

    /** Charges left for one player, for {@code /null status}. */
    public int chargesLeft(UUID player) {
        Charge charge = player == null ? null : charges.get(player);
        if (charge == null) {
            return config == null ? 0 : config.witherCannonMaxCharge();
        }
        return Math.max(0, charge.remaining);
    }

    private void noteFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_LIMIT && !breaker.isOpen()) {
            breaker.trip(consecutiveFailures + " consecutive failures", null, plugin.getLogger(),
                    "Use /null reload or restart the server to re-arm it.");
        }
    }
}
