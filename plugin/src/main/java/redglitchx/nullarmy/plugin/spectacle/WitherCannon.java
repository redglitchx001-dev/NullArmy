package redglitchx.nullarmy.plugin.spectacle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Orbital Wither Cannon: an arc of wither-blue skulls delivered through
 * portals opened in the sky above a marked target.
 *
 * <h2>Wither skulls, not minecarts</h2>
 * <p>v3 flew a TNT minecart up and dropped TNT out of the sky portals. v4 fires
 * <b>wither skulls</b> - {@link WitherSkull} with {@code setCharged(true)}, the
 * blue wither skull - so the barrage looks and hits like the Unstable Universe
 * cannon. No TNT minecart and no TNT entity is ever created by this class; the
 * payload count ({@code wither-cannon.minecarts-per-shot}, default 24) is kept
 * and now means <i>skulls per shot</i>.</p>
 *
 * <h2>It is off, and it stays off until you say otherwise</h2>
 * <p>Six independent things have to agree before a single entity is created:
 * {@code wither-cannon.enabled}, {@code policy.explosives-enabled},
 * {@code policy.wither-enabled}, the configured permission
 * ({@code nullarmy.admin}), the per-player charge/cooldown, and an explicit
 * confirm step. When one is missing the player is told <b>which one</b>.</p>
 *
 * <h2>It cannot hurt the map by accident</h2>
 * <p>Block damage needs a fourth opt-in
 * ({@code policy.griefing-enabled} plus {@code wither-cannon.blocks-damage}).
 * Without both, {@link EntityRegistry} strips the block list out of every
 * explosion this cannon causes: the blast, sound, light, knockback and entity
 * damage all still happen, and no block is ever destroyed.</p>
 *
 * <h2>It never hurts the owner or his Nulls</h2>
 * <p>{@link CannonGuard} cancels any damage a cannon skull would do to the
 * firing owner or to one of his Nulls, whatever else is configured.</p>
 *
 * <h2>It cannot dump a hundred skulls in one tick</h2>
 * <p>Each shot is a tracked {@link Shot} advanced once per tick which spawns
 * <b>one</b> skull per tick at most, has a hard lifetime, and registers every
 * entity it creates so {@code /null stop}, {@code /null dismiss} and
 * {@code onDisable} can remove them.</p>
 *
 * <h2>Aiming</h2>
 * <p>{@code /null cannon aim} hands out a fishing rod named {@code NullAim}. The
 * Null stands where he stands (the launch column) and looks: every tick the
 * look ray is traced up to {@code wither-cannon.range} blocks and the hit is
 * marked with a soul-flame particle. Right-clicking locks the target: a marker
 * burst, and the coordinates printed to the one who aimed it.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class WitherCannon implements Reloadable {

    /** Portal clusters drawn in the sky. Spec 3's floor applies per cluster. */
    private static final int SKY_CLUSTERS = 3;

    /** Hard lifetime of one shot, in ticks (8s). A shot outliving this is stuck. */
    private static final int SHOT_LIFETIME_TICKS = 160;

    /** Consecutive failed skull spawns before a shot gives up and says so. */
    private static final int DROP_FAILURE_LIMIT = 3;

    /** Shots that may be in the air at once, so a macro cannot fill the entity cap. */
    private static final int MAX_CONCURRENT_SHOTS = 3;

    /** Downward velocity of each dropped skull. */
    private static final double SKULL_DROP_SPEED = 0.55;

    /** Consecutive failures before the cannon switches itself off. */
    private static final int FAILURE_LIMIT = 3;

    /** Minecraft's per-tick gravity for a projectile without drag. */
    private static final double GRAVITY = 0.035D;

    /** How long a fire confirmation stays open, in ticks. */
    private static final int CONFIRM_WINDOW_TICKS = 20 * 20;

    /** The name of the aiming rod. */
    public static final String AIM_ROD_NAME = "NullAim";

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

    /** One shot in flight: an arc, the sky portals, then the skulls. */
    private static final class Shot {
        private final UUID owner;
        private final String world;
        /** The launch column: where the Null stands when the shot leaves. */
        private final Vec3d launch;
        /** The locked target, or the traced aim point. */
        private final Vec3d target;
        /** Simulated position of the shell, advanced one tick at a time. */
        private Vec3d position;
        private Vec3d velocity;
        /** Where the portals open and the skulls come out. */
        private Vec3d apex;
        private final long startTick;
        private long apexTick;
        private boolean apexReached;
        private int volleysRemaining;
        private int skullsRemaining;
        private int skullsThisVolley;
        private int spawnedTotal;
        /** Consecutive failed skull spawns. Bounded, so a shot cannot retry forever. */
        private int dropFailures;
        /** Why the shot ended, in the owner's words. */
        private String outcome = "";
        private final List<Entity> live = new ArrayList<>();

        Shot(UUID owner, String world, Vec3d launch, Vec3d target, Vec3d position, Vec3d velocity,
             long startTick, int volleysRemaining, int skullsThisVolley) {
            this.owner = owner;
            this.world = world;
            this.launch = launch;
            this.target = target;
            this.position = position;
            this.velocity = velocity;
            this.startTick = startTick;
            this.apex = position;
            this.volleysRemaining = volleysRemaining;
            this.skullsThisVolley = skullsThisVolley;
            this.skullsRemaining = skullsThisVolley;
        }
    }

    /** Per-player charge and cooldown bookkeeping. */
    private static final class Charge {
        private int remaining;
        private long refillAtMillis;
    }

    /** A player holding the aiming rod. */
    private static final class Aim {
        private final UUID player;
        /** Last traced aim point, or null when the ray found nothing. */
        private Vec3d traced;
        /** The locked target, or null when nothing is locked yet. */
        private Vec3d locked;
        private String lockedWorld = "";
        private long lockedTick;

        Aim(UUID player) {
            this.player = player;
        }
    }

    /** A fire waiting for its confirm. */
    private static final class Pending {
        private final UUID player;
        private final int shots;
        private final long openedTick;

        Pending(UUID player, int shots, long openedTick) {
            this.player = player;
            this.shots = shots;
            this.openedTick = openedTick;
        }
    }

    private final NullArmyPlugin plugin;
    private final EntityRegistry registry;
    private final List<Shot> shots = new ArrayList<>();
    private final Map<UUID, Charge> charges = new HashMap<>();
    private final Map<UUID, Aim> aiming = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Set<UUID> skulls = new HashSet<>();
    private final Guard.Breaker breaker = new Guard.Breaker("wither cannon");
    private final Random random = new Random(20_260_101L);

    private PluginConfig config;
    private int consecutiveFailures;
    private int skullsSpawned;
    private int shotsFired;

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

    // ------------------------------------------------------------------ gating

    /** True when gating allows this player to fire right now, ignoring charges. */
    public boolean available(Player player) {
        PluginConfig current = config;
        if (current == null || !current.witherCannonUsable()) {
            return false;
        }
        return player != null && player.hasPermission(current.witherCannonPermission());
    }

    /** Fires the configured number of shots. Never throws; the Result says what happened and why. */
    public Result fire(Player player) {
        return fire(player, config == null ? 3 : config.witherCannonShots());
    }

    /** Fires {@code shots} volleys. Never throws; the Result says what happened and why. */
    public Result fire(Player player, int shots) {
        try {
            return fireChecked(player, shots);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] wither cannon failure: " + Guard.describe(t));
            noteFailure();
            return new Result(false, "The cannon failed: " + Guard.describe(t));
        }
    }

    private Result fireChecked(Player player, int requested) {
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
        int volleys = Math.max(1, Math.min(20, requested));
        if (this.shots.size() >= MAX_CONCURRENT_SHOTS) {
            return new Result(false, MAX_CONCURRENT_SHOTS + " shots are already in the air;"
                    + " wait for one to finish.");
        }

        Location eye = player.getEyeLocation();
        World world = eye == null ? null : eye.getWorld();
        if (world == null) {
            return new Result(false, "Could not read your world.");
        }

        // The launch column is where the Null stands; the target is the locked
        // aim point when there is one, otherwise whatever the ray finds.
        Aim aim = aiming.get(player.getUniqueId());
        Vec3d target = null;
        if (aim != null && aim.locked != null && world.getName().equals(aim.lockedWorld)) {
            target = aim.locked;
        }
        if (target == null) {
            Vec3d traced = trace(player);
            if (traced == null) {
                return new Result(false, "Nothing to aim at: hold the NullAim rod, look at the ground"
                        + " and right-click to lock a target first.");
            }
            target = traced;
        }

        Location feet = player.getLocation();
        Vec3d launch = new Vec3d(feet.getX(), feet.getY() + 1.6D, feet.getZ());
        double height = Math.max(6.0D, current.witherCannonHeight());
        double maxY = world.getMaxHeight() - 8.0D;
        if (launch.y() + height > maxY) {
            height = Math.max(6.0D, maxY - launch.y());
        }
        // v0y for an arc that tops out `height` above the launch: v0 = sqrt(2gh).
        double rise = Math.sqrt(2.0D * GRAVITY * height);
        long riseTicks = Math.max(10L, (long) Math.ceil(rise / GRAVITY));
        Vec3d apex = new Vec3d(target.x(), Math.min(maxY, launch.y() + height), target.z());
        Vec3d velocity = new Vec3d((apex.x() - launch.x()) / riseTicks, rise,
                (apex.z() - launch.z()) / riseTicks);

        String cooldown = checkCharge(player, current);
        if (cooldown != null) {
            return new Result(false, cooldown);
        }

        int perShot = current.witherCannonPerShot();
        Shot shot = new Shot(player.getUniqueId(), world.getName(), launch, target, launch,
                velocity, plugin.currentTick(), volleys, Math.max(1, perShot));
        shots.add(shot);
        consecutiveFailures = 0;
        shotsFired++;
        return new Result(true, "Fire. " + volleys + " volley(s) of " + perShot
                + " wither skulls, pattern " + current.witherCannonPattern()
                + ", onto " + coords(target) + ".");
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

    // -------------------------------------------------------------------- aim

    /** Gives (or re-gives) the NullAim rod and switches this player into aiming. */
    public String aim(Player player) {
        if (player == null) {
            return "Only a player can aim the cannon.";
        }
        PluginConfig current = config;
        if (current != null && !current.witherCannonEnabled()) {
            return "The wither cannon is off: wither-cannon.enabled is false in config.yml.";
        }
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(AIM_ROD_NAME);
            meta.setLore(List.of(
                    "Orbital Wither Cannon aim",
                    "Stand where you are: that is the launch column.",
                    "Look at the ground: the ray reaches "
                            + (current == null ? 120 : current.witherCannonRange()) + " blocks.",
                    "Right-click to lock the target."));
            rod.setItemMeta(meta);
        }
        Map<Integer, ItemStack> left = player.getInventory().addItem(rod);
        if (!left.isEmpty()) {
            return "Your inventory is full: free a slot and try again.";
        }
        aiming.put(player.getUniqueId(), new Aim(player.getUniqueId()));
        return "NullAim is in your hand. Stand: launch column. Look: the ray marks the ground."
                + " Right-click: lock the target.";
    }

    /** Stops aiming and forgets the locked target. */
    public String cancelAim(Player player) {
        if (player == null) {
            return "no player";
        }
        aiming.remove(player.getUniqueId());
        return "Aim released.";
    }

    /** The locked target of one player, or null. */
    public Vec3d lockedTarget(UUID player) {
        Aim aim = player == null ? null : aiming.get(player);
        return aim == null ? null : aim.locked;
    }

    /** True when this player is holding the aiming rod. */
    public boolean isAiming(UUID player) {
        return player != null && aiming.containsKey(player);
    }

    /** Locks whatever the player is looking at right now. Returns the printed line. */
    public String lock(Player player) {
        if (player == null) {
            return "no player";
        }
        if (!aiming.containsKey(player.getUniqueId())) {
            return "Run /null cannon aim first: the rod is what you aim with.";
        }
        Vec3d traced = trace(player);
        if (traced == null) {
            return "The ray found nothing inside " + (config == null ? 120 : config.witherCannonRange())
                    + " blocks: look at the ground.";
        }
        Aim aim = aiming.get(player.getUniqueId());
        aim.locked = traced;
        aim.lockedWorld = player.getWorld() == null ? "" : player.getWorld().getName();
        aim.lockedTick = plugin.currentTick();
        World world = player.getWorld();
        if (world != null) {
            Location at = new Location(world, traced.x(), traced.y() + 0.2D, traced.z());
            try {
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 40, 0.4D, 0.4D, 0.4D, 0.02D);
                world.spawnParticle(Particle.SMOKE, at, 30, 0.6D, 0.2D, 0.6D, 0.01D);
            } catch (Throwable ignored) {
                // A particle failure is never a targeting failure.
            }
        }
        return "Target locked: " + coords(traced)
                + ". Run /null cannon fire to bring it down.";
    }

    /**
     * The traced aim point: a real ray trace along the player's look, stopped by
     * the first solid block or by the configured range.
     */
    private Vec3d trace(Player player) {
        if (player == null) {
            return null;
        }
        int range = config == null ? 120 : config.witherCannonRange();
        try {
            RayTraceResult hit = player.rayTraceBlocks(range);
            if (hit == null || hit.getHitBlock() == null) {
                return null;
            }
            Location at = hit.getHitPosition().toLocation(player.getWorld());
            return new Vec3d(at.getX(), at.getY(), at.getZ());
        } catch (Throwable t) {
            // Older servers trace through the world instead; same answer either way.
            Location eye = player.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            World world = eye.getWorld();
            for (double step = 1.0D; step <= range; step += 0.5D) {
                Location at = eye.clone().add(dir.clone().multiply(step));
                if (at.getBlock() != null && !at.getBlock().getType().isAir()) {
                    return new Vec3d(at.getX(), at.getY(), at.getZ());
                }
            }
            return null;
        }
    }

    /** Coordinates in the player's own words: three integers separated by spaces. */
    private static String coords(Vec3d at) {
        if (at == null) {
            return "nowhere";
        }
        return (int) Math.floor(at.x()) + " " + (int) Math.floor(at.y()) + " " + (int) Math.floor(at.z());
    }

    /** Per-tick aim marker: one soul flame on the traced point, never a chat line. */
    public void tickAim() {
        if (aiming.isEmpty()) {
            return;
        }
        for (Aim aim : new ArrayList<>(aiming.values())) {
            Player player = Bukkit.getPlayer(aim.player);
            if (player == null || !player.isOnline() || !holdsRod(player)) {
                continue;
            }
            Vec3d traced = trace(player);
            aim.traced = traced;
            if (traced == null || player.getWorld() == null) {
                continue;
            }
            Location at = new Location(player.getWorld(), traced.x(), traced.y() + 0.15D, traced.z());
            try {
                player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, at, 2, 0.05D, 0.05D, 0.05D, 0.0D);
            } catch (Throwable ignored) {
                // Cosmetic only.
            }
        }
    }

    private boolean holdsRod(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (isRod(hand)) {
            return true;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return isRod(off);
    }

    private boolean isRod(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FISHING_ROD) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && AIM_ROD_NAME.equals(meta.getDisplayName());
    }

    // ---------------------------------------------------------------- confirm

    /** Opens a confirm window for a fire. Nothing is created until it is confirmed. */
    public String requestFire(Player player, int shots) {
        if (player == null) {
            return "Only a player can fire the cannon.";
        }
        PluginConfig current = config;
        if (current == null || !current.witherCannonUsable()) {
            return current == null ? "no config loaded" : current.witherCannonBlockedReason();
        }
        if (!player.hasPermission(current.witherCannonPermission())) {
            return "You need the permission " + current.witherCannonPermission() + ".";
        }
        int count = Math.max(1, Math.min(20, shots <= 0 ? current.witherCannonShots() : shots));
        pending.put(player.getUniqueId(), new Pending(player.getUniqueId(), count, plugin.currentTick()));
        return count + " volley(s) x " + current.witherCannonPerShot() + " wither skulls, pattern "
                + current.witherCannonPattern() + ", block damage "
                + (current.witherCannonBlocksDamage() ? "ON" : "off") + ". Type /null cannon confirm to fire,"
                + " or /null cannon cancel to stand down.";
    }

    /** Confirms an open fire request. */
    public Result confirm(Player player) {
        if (player == null) {
            return new Result(false, "Only a player can fire the cannon.");
        }
        Pending open = pending.remove(player.getUniqueId());
        if (open == null) {
            return new Result(false, "Nothing to confirm: run /null cannon fire [shots] first.");
        }
        if (plugin.currentTick() - open.openedTick > CONFIRM_WINDOW_TICKS) {
            return new Result(false, "That confirmation expired: run /null cannon fire [shots] again.");
        }
        return fire(player, open.shots);
    }

    /** Cancels an open fire request and every shot in the air. */
    public String cancel(Player player) {
        if (player == null) {
            return "no player";
        }
        pending.remove(player.getUniqueId());
        for (Shot shot : new ArrayList<>(shots)) {
            if (player.getUniqueId().equals(shot.owner)) {
                cleanup(shot);
                shots.remove(shot);
            }
        }
        return "Standing down. No further skulls will be launched.";
    }

    /** True when this player has a fire waiting for its confirm. */
    public boolean pendingConfirm(UUID player) {
        Pending open = player == null ? null : pending.get(player);
        if (open == null) {
            return false;
        }
        if (plugin.currentTick() - open.openedTick > CONFIRM_WINDOW_TICKS) {
            pending.remove(player);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------- tick

    /** Advances every shot in flight. One tick, bounded work, no exceptions. */
    public void tick(long tickCounter) {
        tickPending(tickCounter);
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

    private void tickPending(long tickCounter) {
        if (pending.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            if (tickCounter - entry.getValue().openedTick > CONFIRM_WINDOW_TICKS) {
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.sendMessage(PluginText.PREFIX + "The cannon confirmation expired; nothing was fired.");
                }
                it.remove();
            }
        }
    }

    /** @return true when the shot is done */
    private boolean advance(Shot shot, long tickCounter) {
        PluginConfig current = config;
        // A shot that has outlived its window is stuck - it is ended here rather
        // than left retrying for the rest of the session with its entities behind.
        if (tickCounter - shot.startTick > SHOT_LIFETIME_TICKS) {
            shot.outcome = "the shot timed out after " + (SHOT_LIFETIME_TICKS / 20) + "s";
            cleanup(shot);
            return true;
        }

        if (!shot.apexReached) {
            // Simulated ballistics: real gravity, one step per tick. The shell
            // carries no entity at all, so nothing can explode on the way up.
            shot.position = new Vec3d(shot.position.x() + shot.velocity.x(),
                    shot.position.y() + shot.velocity.y(),
                    shot.position.z() + shot.velocity.z());
            shot.velocity = new Vec3d(shot.velocity.x(), shot.velocity.y() - GRAVITY, shot.velocity.z());
            if (shot.position.y() > shot.apex.y()) {
                shot.apex = new Vec3d(shot.position.x(), shot.position.y(), shot.position.z());
            }
            if (shot.velocity.y() <= 0.0D || tickCounter - shot.startTick > 90) {
                shot.apexReached = true;
                shot.apexTick = tickCounter;
                World world = Bukkit.getWorld(shot.world);
                if (world != null) {
                    openSkyPortals(new Location(world, shot.apex.x(), shot.apex.y(), shot.apex.z()));
                }
            }
            return false;
        }

        // Fuse: the doors are open, the barrage has not started yet.
        int fuse = current == null ? 60 : current.witherCannonFuseTicks();
        if (tickCounter - shot.apexTick < fuse) {
            return false;
        }

        // Delivery phase: exactly one skull per tick, so a shot can never empty a
        // payload into a single tick. Failures are counted, and a shot that cannot
        // deliver gives up instead of retrying for ever.
        if (shot.skullsRemaining > 0) {
            if (dropOne(shot)) {
                shot.skullsRemaining--;
                shot.spawnedTotal++;
                shot.dropFailures = 0;
            } else {
                shot.dropFailures++;
                if (shot.dropFailures >= DROP_FAILURE_LIMIT) {
                    shot.outcome = shot.skullsRemaining + " skulls could not be created";
                    cleanup(shot);
                    return true;
                }
            }
            return false;
        }

        shot.volleysRemaining--;
        if (shot.volleysRemaining <= 0) {
            shot.outcome = shot.spawnedTotal + " wither skulls delivered";
            cleanup(shot);
            return true;
        }
        // Next volley on the configured delay, from the same portals.
        int delay = Math.max(1, current == null ? 10 : current.witherCannonShotDelayTicks());
        shot.skullsRemaining = shot.skullsThisVolley;
        shot.apexTick = tickCounter - fuse + delay;
        return false;
    }

    /**
     * Creates one wither skull at the portals, aimed at the target.
     *
     * @return true when an entity really exists now; false is counted, and the
     *     shot gives up after {@link #DROP_FAILURE_LIMIT} of them
     */
    private boolean dropOne(Shot shot) {
        World world = Bukkit.getWorld(shot.world);
        if (world == null || shot.apex == null) {
            shot.outcome = "the delivery point was lost";
            return false;
        }
        PluginConfig current = config;
        String pattern = current == null ? "sphere" : current.witherCannonPattern().toLowerCase();
        double radius = current == null ? 8.0D : current.witherCannonRadius();
        double[] offset = spread(pattern, radius, shot.spawnedTotal, shot.skullsThisVolley);
        Location at = new Location(world, shot.apex.x() + offset[0], shot.apex.y() + offset[1],
                shot.apex.z() + offset[2]);
        Entity skull;
        try {
            skull = world.spawnEntity(at, EntityType.WITHER_SKULL);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] wither skull spawn failed: " + Guard.describe(t));
            return false;
        }
        if (skull == null || !skull.isValid()) {
            return false;
        }
        if (skull instanceof WitherSkull) {
            // The blue skull: charged is what makes it blue, and dangerous.
            ((WitherSkull) skull).setCharged(true);
        }
        Vector aim = new Vector(shot.target.x() - at.getX(),
                shot.target.y() - at.getY(),
                shot.target.z() - at.getZ());
        double distance = aim.length();
        if (distance < 0.001D) {
            aim = new Vector(0.0D, -1.0D, 0.0D);
            distance = 1.0D;
        }
        aim = aim.normalize().multiply(Math.min(2.2D, 0.35D + distance / 60.0D));
        try {
            skull.setVelocity(aim);
        } catch (Throwable t) {
            skull.remove();
            return false;
        }
        if (!registry.track(skull)) {
            skull.remove();
            return false;
        }
        skulls.add(skull.getUniqueId());
        shot.live.add(skull);
        skullsSpawned++;
        return true;
    }

    /**
     * Where inside the pattern this skull comes out of the portal.
     *
     * <ul>
     *   <li>{@code sphere} - a shell around the portals: everything lands close
     *       together, the classic concentrated strike.</li>
     *   <li>{@code rain} - a wide flat disc: the barrage covers ground.</li>
     *   <li>{@code line} - a straight rank, one after another along a line.</li>
     * </ul>
     */
    private double[] spread(String pattern, double radius, int index, int total) {
        double[] out = new double[3];
        if ("line".equals(pattern)) {
            double span = Math.max(3.0D, radius * 1.5D);
            double t = total <= 1 ? 0.0D : (index / (double) (total - 1)) - 0.5D;
            out[0] = t * span;
            out[2] = t * span * 0.25D;
            out[1] = random.nextDouble() * 0.5D;
            return out;
        }
        if ("rain".equals(pattern)) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double r = Math.sqrt(random.nextDouble()) * radius;
            out[0] = Math.cos(angle) * r;
            out[2] = Math.sin(angle) * r;
            out[1] = random.nextDouble() * 2.0D;
            return out;
        }
        // sphere: evenly-ish spread over the surface of a shell.
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double height = (random.nextDouble() - 0.5D) * 2.0D;
        double r = Math.sqrt(Math.max(0.0D, 1.0D - height * height)) * Math.min(radius, 3.0D);
        out[0] = Math.cos(angle) * r;
        out[2] = Math.sin(angle) * r;
        out[1] = height * 1.5D;
        return out;
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
        if (shot.spawnedTotal <= 0) {
            noteFailure();
        } else {
            consecutiveFailures = 0;
        }
        try {
            Player owner = Bukkit.getPlayer(shot.owner);
            if (owner != null && owner.isOnline()) {
                if (shot.spawnedTotal <= 0) {
                    owner.sendMessage(PluginText.PREFIX + "Barrage failed: no skulls were delivered. "
                            + (shot.outcome.isEmpty() ? "The reason was not recorded." : shot.outcome)
                            + ".");
                } else {
                    owner.sendMessage(PluginText.PREFIX + "Barrage complete: " + shot.spawnedTotal
                            + " wither skulls through the sky portals"
                            + (shot.outcome.isEmpty() ? "." : " (" + shot.outcome + ")."));
                    if (config != null && config.witherCannonBlocksDamage()) {
                        owner.sendMessage(PluginText.PREFIX + "Block damage is enabled for this barrage.");
                    } else {
                        owner.sendMessage(PluginText.PREFIX + "Block damage is off: the blasts are"
                                + " visual and physical, but no block is destroyed.");
                    }
                }
            }
        } catch (Throwable ignored) {
            // A missing player is not a failure.
        }
    }

    /** Releases every entity of one shot. */
    private void cleanup(Shot shot) {
        for (Entity entity : shot.live) {
            registry.untrack(entity);
            skulls.remove(entity.getUniqueId());
            try {
                if (entity.isValid() && !entity.isDead() && entity.getTicksLived() < 200) {
                    // A skull already on its way down is left to finish its flight:
                    // removing it mid-air would be the dishonest "nothing happened".
                    registry.untrack(entity);
                }
            } catch (Throwable ignored) {
                // A dead entity needs nothing.
            }
        }
        shot.live.clear();
    }

    /** Emergency stop: removes every tracked shot entity immediately. */
    public void stopAll() {
        for (Shot shot : new ArrayList<>(shots)) {
            Guard.attempt(plugin.getLogger(), "stopping a cannon barrage", () -> {
                for (Entity entity : new ArrayList<>(shot.live)) {
                    registry.untrack(entity);
                    skulls.remove(entity.getUniqueId());
                    if (entity.isValid() && !entity.isDead()) {
                        entity.remove();
                    }
                }
                shot.live.clear();
            });
        }
        shots.clear();
        pending.clear();
    }

    /** Shots currently in flight, for {@code /null status}. */
    public int shotsInFlight() {
        return shots.size();
    }

    /** Wither skulls this cannon has created, for the self test. */
    public int skullsSpawned() {
        return skullsSpawned;
    }

    /** Barrages fired, for the self test. */
    public int shotsFired() {
        return shotsFired;
    }

    /** True when this entity is a wither skull launched by the cannon. */
    public boolean isCannonSkull(Entity entity) {
        return entity != null && skulls.contains(entity.getUniqueId());
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
        return "READY - " + shots.size() + "/" + MAX_CONCURRENT_SHOTS + " barrages in flight, "
                + current.witherCannonShots() + " volleys x " + current.witherCannonPerShot()
                + " wither skulls, pattern " + current.witherCannonPattern()
                + ", range " + current.witherCannonRange() + ", charge "
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

    // ------------------------------------------------------------------ guard

    /**
     * The cannon's own listener: turns the aiming rod into a targeting tool and
     * makes sure a barrage never turns on the hand that fired it.
     *
     * <p>Register it with the plugin manager; everything it does is bounded and
     * never throws out of an event handler.</p>
     */
    public final class CannonGuard implements Listener {

        @EventHandler(priority = EventPriority.NORMAL)
        public void onFish(PlayerFishEvent event) {
            if (event == null || event.getPlayer() == null) {
                return;
            }
            if (!isAiming(event.getPlayer().getUniqueId()) || !holdsRod(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
            String line = lock(event.getPlayer());
            event.getPlayer().sendMessage(PluginText.PREFIX + line);
        }

        @EventHandler(priority = EventPriority.NORMAL)
        public void onInteract(PlayerInteractEvent event) {
            if (event == null || event.getPlayer() == null || event.getHand() != EquipmentSlot.HAND) {
                return;
            }
            if (!isAiming(event.getPlayer().getUniqueId()) || !holdsRod(event.getPlayer())) {
                return;
            }
            org.bukkit.event.block.Action action = event.getAction();
            if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                    && action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            event.setCancelled(true);
            event.getPlayer().sendMessage(PluginText.PREFIX + lock(event.getPlayer()));
        }

        /**
         * A cannon skull never hurts the one who fired it, nor one of his Nulls.
         *
         * <p>Everything else the barrage hits is the operator's business - that is
         * what the confirm step is for - but the owner and his army are exempt by
         * construction, not by configuration.</p>
         */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
        public void onDamage(EntityDamageByEntityEvent event) {
            if (event == null || event.getEntity() == null) {
                return;
            }
            Entity damager = event.getDamager();
            if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Entity) {
                damager = (Entity) ((Projectile) damager).getShooter();
            }
            if (!isCannonSkull(damager)) {
                return;
            }
            Entity victim = event.getEntity();
            if (!(victim instanceof Player)) {
                return;
            }
            UUID id = victim.getUniqueId();
            boolean isNull = plugin.adapter() != null && plugin.adapter().isNullEntity(id);
            if (!isNull) {
                return;
            }
            if (isSomebodysNull(id)) {
                event.setCancelled(true);
            }
        }

        /** Block damage is only ever what {@code blocks-damage} says it is. */
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            if (event == null || !isCannonSkull(event.getEntity())) {
                return;
            }
            if (config == null || !config.witherCannonBlocksDamage()) {
                event.blockList().clear();
            }
        }
    }

    /** Builds the guard listener. Registered once by the plugin. */
    public CannonGuard guard() {
        return new CannonGuard();
    }

    /**
     * True when this Null belongs to somebody who has the cannon in the air or
     * in the hand right now - the one exemption the barrage cannot be talked
     * out of.
     */
    private boolean isSomebodysNull(UUID nullId) {
        if (plugin.squads() == null || plugin.adapter() == null) {
            return false;
        }
        redglitchx.nullarmy.nms.NullBody body = plugin.adapter().bodyOf(nullId);
        if (body == null) {
            return false;
        }
        UUID owner = plugin.squads().ownerOf(body);
        if (owner == null) {
            return false;
        }
        for (Shot shot : shots) {
            if (owner.equals(shot.owner)) {
                return true;
            }
        }
        return aiming.containsKey(owner);
    }
}
