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

    /** Hard lifetime of one shot, in ticks (8s). */
    private static final int SHOT_LIFETIME_TICKS = 160;

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
        private boolean apexReached;
        private int tntRemaining;
        private int droppedByAir;

        Shot(UUID owner, Entity cart, long startTick, Location start, int tntRemaining) {
            this.owner = owner;
            this.cart = cart;
            this.startTick = startTick;
            this.lastLocation = start;
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

        Vector direction = eye.getDirection();
        if (direction == null || direction.lengthSquared() < 1.0E-6) {
            direction = new Vector(0, 0, 1);
        }
        direction = direction.normalize().multiply(1.15);
        Vector velocity = new Vector(direction.getX(), 1.35, direction.getZ());
        Location spawn = eye.clone().add(direction.clone().multiply(1.2));

        Entity cart = world.spawnEntity(spawn, EntityType.TNT_MINECART);
        if (cart == null) {
            return new Result(false, "The server refused to create the minecart.");
        }
        cart.setVelocity(velocity);
        if (!registry.track(cart)) {
            cart.remove();
            return new Result(false, "Too many tracked entities (" + registry.maxTracked()
                    + "); use /null stop to clear them first.");
        }

        shots.add(new Shot(player.getUniqueId(), cart, plugin.currentTick(), spawn,
                current.witherCannonTntPerShot()));
        consecutiveFailures = 0;
        return new Result(true, "Shot away. Watch the sky - the doors open at the top of the arc.");
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
            shot.lastLocation = cart.getLocation();
        }

        if (!shot.apexReached) {
            if (!alive) {
                // Lost the cart before the arc topped out: still deliver the show.
                shot.apexReached = true;
                openSkyPortals(shot.lastLocation);
                shot.tntRemaining = Math.min(shot.tntRemaining, 1);
            } else {
                Vector velocity = cart.getVelocity();
                if (velocity == null || velocity.getY() <= 0.0
                        || tickCounter - shot.startTick > 60) {
                    shot.apexReached = true;
                    openSkyPortals(shot.lastLocation);
                }
            }
            return false;
        }

        // Drop phase: exactly one TNT per tick, so a shot can never empty a
        // payload into a single tick.
        if (shot.tntRemaining > 0) {
            if (dropOne(shot)) {
                shot.tntRemaining--;
            }
            return false;
        }

        // Delivery done.
        if (alive) {
            cart.remove();
        }
        registry.untrack(cart);
        return true;
    }

    private boolean dropOne(Shot shot) {
        Location at = shot.lastLocation;
        if (at == null || at.getWorld() == null) {
            return true;
        }
        World world = at.getWorld();
        double spreadX = (random.nextDouble() - 0.5) * 0.4;
        double spreadZ = (random.nextDouble() - 0.5) * 0.4;
        Location drop = at.clone().add(spreadX, -0.5, spreadZ);
        Entity tnt = world.spawnEntity(drop, EntityType.TNT);
        if (tnt == null) {
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

    private void finish(Shot shot) {
        registry.untrack(shot.cart);
        try {
            Player owner = Bukkit.getPlayer(shot.owner);
            if (owner != null && owner.isOnline()) {
                owner.sendMessage(PluginText.PREFIX + "Shot complete: " + shot.droppedByAir
                        + " TNT delivered through the sky portals.");
                if (config != null && config.witherCannonBlocksDamage()) {
                    owner.sendMessage(PluginText.PREFIX + "Block damage is enabled for this shot.");
                } else {
                    owner.sendMessage(PluginText.PREFIX + "Block damage is off: the blasts are visual only.");
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

    private void noteFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_LIMIT && !breaker.isOpen()) {
            breaker.trip(consecutiveFailures + " consecutive failures", null, plugin.getLogger(),
                    "Use /null reload or restart the server to re-arm it.");
        }
    }
}
