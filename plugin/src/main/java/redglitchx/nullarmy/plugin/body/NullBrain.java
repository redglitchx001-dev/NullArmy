package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import redglitchx.nullarmy.core.flock.Separation;
import redglitchx.nullarmy.core.formation.FormationMatrix;
import redglitchx.nullarmy.core.formation.FormationMatrix;
import redglitchx.nullarmy.core.march.MarchCadence;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.nav.BlockView;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The per-tick brain of every Null: it decides what each body wants to do and
 * hands the body a movement intent and a look target. The body itself (the NMS
 * adapter) turns that into vanilla physics.
 *
 * <h2>Priority, highest first</h2>
 * <ol>
 *   <li>a build job ({@code /null ai build}) the Null is working on;</li>
 *   <li>a fight - an ordered attack, or retaliation when the Null, a squad mate or
 *       its owner was hit;</li>
 *   <li>an explicit order ({@code /null order}, or the same words in chat to the
 *       Commander): walk, run, sprint, jump, stop, follow, hold, gather, build,
 *       attack, defend;</li>
 *   <li>the squad objective (follow, formation, guard, attack, destination);</li>
 *   <li>stepping out of the arrival doorway;</li>
 *   <li>sliding out of a pile ({@link #unstack});</li>
 *   <li>idle life: keep a body's width from everybody, glance at players who come
 *       close, look around, rest after a minute, eat when hurt.</li>
 * </ol>
 *
 * <p>Separation is added to every movement except a precise hold, so a squad
 * never melts into one block; the head follows the movement unless something
 * more interesting is in view.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullBrain implements Reloadable {

    /** What a behaviour wants the body to do this tick. */
    public static final class Intent {
        public double dx;
        public double dz;
        public int gait = NullBody.GAIT_STOP;
        public boolean jump;
        public boolean sneak;
        /** A precise hold: no separation, no jitter. */
        public boolean precise;
        /** Stepping off an edge is intended (leaving a floating doorway, walking down on purpose). */
        public boolean allowDrop;
        public Vec3d look;
        public boolean lookHeadOnly = true;

        public static Intent stop() {
            Intent i = new Intent();
            i.precise = true;
            return i;
        }

        boolean moving() {
            return gait != NullBody.GAIT_STOP && (dx * dx + dz * dz) > 4.0e-4;
        }
    }

    private static final double PLAYER_GLANCE_RANGE = 8.0D;
    private static final int UNSTACK_INTERVAL = 10;

    private final NullArmyPlugin plugin;
    private final Map<UUID, Mind> minds = new HashMap<>();
    private final Random random = new Random();
    private final CombatBrain combat;
    private final List<Vec3d> extraWatchers = new ArrayList<>();
    private V3Settings v3;
    private long now;
    private int unstackedTotal;
    private String lastUnstack = "";

    public NullBrain(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.v3 = config == null ? null : config.v3();
        this.combat = new CombatBrain(plugin, this);
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.v3 = fresh.v3();
        }
    }

    public V3Settings settings() { return v3; }

    long now() { return now; }

    public CombatBrain combat() { return combat; }

    // ------------------------------------------------- L-01..L-08 accounting

    private int salutes;
    private int patrolLaps;
    private int blocksBridged;
    private int blocksDestroyed;
    private int lootPicked;
    private int huntEngagements;
    private final Set<String> campBehaviours = new LinkedHashSet<>();

    /** L-03: how many times a Null saluted its owner. */
    public int salutes() { return salutes; }
    /** L-03: how many patrol laps were completed. */
    public int patrolLaps() { return patrolLaps; }
    /** L-02: how many blocks were placed by hand to bridge a gap. */
    public int blocksBridged() { return blocksBridged; }
    /** P-09: how many blocks a destroy order really broke. */
    public int blocksDestroyed() { return blocksDestroyed; }
    /** L-08: how many dropped items a Null picked up. */
    public int lootPicked() { return lootPicked; }
    /** L-07: how many hunt engagements are running. */
    public int huntEngagements() { return huntEngagements; }
    /** L-04: the distinct idle behaviours seen (rest, eat, spar, haul). */
    public List<String> campBehaviours() { return new ArrayList<>(campBehaviours); }

    /** Self test: clears the behaviour counters before a measured window. */
    public void resetBehaviourCounters() {
        salutes = 0;
        patrolLaps = 0;
        blocksBridged = 0;
        blocksDestroyed = 0;
        lootPicked = 0;
        campBehaviours.clear();
    }

    /** L-08: a Null picked something up. */
    public void notePickup(NullBody body) {
        if (body == null || plugin.adapter() == null || !plugin.adapter().isNullEntity(body.uuid())) {
            return;
        }
        lootPicked++;
        Mind mind = mind(body);
        if (mind != null) {
            mind.lastPickupTick = now;
        }
    }

    /** The mind of a body, created on first sight. */
    public Mind mind(NullBody body) {
        UUID id = body == null ? null : body.uuid();
        if (id == null) {
            return null;
        }
        return minds.computeIfAbsent(id, k -> new Mind(k, now));
    }

    /** Minds by body UUID, for diagnostics and the self test. */
    public Map<UUID, Mind> minds() { return minds; }

    /** How many bodies the unstacker has moved this session, and the last report. */
    public int unstackedTotal() { return unstackedTotal; }
    public String lastUnstack() { return lastUnstack; }

    /**
     * Positions the self test adds as "players nearby", because no real player
     * connects to the headless smoke server. The same glance code uses them.
     */
    public List<Vec3d> extraWatchers() { return extraWatchers; }

    // ------------------------------------------------------------------- tick

    public void tick(long tickCounter) {
        this.now = tickCounter;
        Map<NullBody, SquadManager.Squad> bodies = new LinkedHashMap<>();
        if (plugin.squads() != null) {
            for (SquadManager.Squad squad : plugin.squads().allSquads()) {
                for (NullBody body : squad.members()) {
                    bodies.put(body, squad);
                }
            }
        }
        if (plugin.commander() != null && plugin.commander().body() != null) {
            bodies.putIfAbsent(plugin.commander().body(), null);
        }
        pruneMinds(bodies.keySet());
        if (bodies.isEmpty()) {
            return;
        }
        Map<String, List<NullBody>> byWorld = new HashMap<>();
        Map<NullBody, Vec3d> positions = new HashMap<>();
        Map<NullBody, String> worlds = new HashMap<>();
        for (NullBody body : bodies.keySet()) {
            try {
                if (body.deathTicks() >= 0 || !body.isAlive()) {
                    continue;
                }
                Location at = Bodies.location(body);
                if (at == null || at.getWorld() == null) {
                    continue;
                }
                String world = at.getWorld().getName();
                byWorld.computeIfAbsent(world, k -> new ArrayList<>()).add(body);
                positions.put(body, new Vec3d(at.getX(), at.getY(), at.getZ()));
                worlds.put(body, world);
            } catch (Throwable ignored) {
                // A body that cannot be read this tick is skipped this tick.
            }
        }
        if (tickCounter % UNSTACK_INTERVAL == 0) {
            for (Map.Entry<String, List<NullBody>> entry : byWorld.entrySet()) {
                Guard.attempt(plugin.getLogger(), "unstacking Nulls",
                        () -> unstack(entry.getKey(), entry.getValue(), positions));
            }
        }
        for (Map.Entry<String, List<NullBody>> entry : byWorld.entrySet()) {
            List<NullBody> list = entry.getValue();
            List<double[]> neighbourPositions = new ArrayList<>();
            for (NullBody body : list) {
                Vec3d p = positions.get(body);
                neighbourPositions.add(new double[] {p.x(), p.y(), p.z()});
            }
            for (int i = 0; i < list.size(); i++) {
                NullBody body = list.get(i);
                final int index = i;
                SquadManager.Squad squad = bodies.get(body);
                try {
                    drive(body, squad, entry.getKey(), positions.get(body), index, neighbourPositions);
                } catch (Throwable t) {
                    plugin.getLogger().fine("[NullArmy] a Null's brain step failed: " + Guard.describe(t));
                    Guard.attempt(plugin.getLogger(), "stopping a Null",
                            () -> body.setMovement(0, 0, NullBody.GAIT_STOP, false, false));
                }
            }
        }
    }

    private void pruneMinds(java.util.Set<NullBody> live) {
        if (minds.size() <= live.size() + 8 && now % 200 != 0) {
            return;
        }
        java.util.Set<UUID> keep = new java.util.HashSet<>();
        for (NullBody body : live) {
            if (body.uuid() != null) {
                keep.add(body.uuid());
            }
        }
        minds.keySet().removeIf(id -> !keep.contains(id));
    }

    // ------------------------------------------------------------------ drive

    private void drive(NullBody body, SquadManager.Squad squad, String world, Vec3d pos, int index,
                       List<double[]> neighbours) {
        Mind mind = mind(body);
        if (mind == null) {
            return;
        }
        Player handle = Bodies.player(body);
        applySpeedFactor(mind, handle);
        finishEating(mind, handle);

        Intent intent = null;
        boolean busy = false;
        if (plugin.builder() != null && plugin.builder().drives(body)) {
            intent = plugin.builder().tickBody(body, mind, handle, world, pos, now);
            busy = true;
        }
        if (!busy && combat.active(mind, handle)) {
            intent = combat.tick(body, mind, handle, world, pos);
            busy = true;
        }
        if (!busy && mind.order != null && mind.order.verb == Mind.Verb.DRILL) {
            // L-01: the drill cycles line -> wedge -> phalanx on one clock shared
            // by the whole squad, so every body changes shape on the same tick.
            updateDrillCell(body, mind, squad, world, index);
        }
        if (!busy && mind.order != null) {
            intent = orderIntent(body, mind, handle, world, pos);
        }
        if (!busy && intent == null && mind.order == null && squad != null
                && squad.objective() != SquadManager.Objective.NONE) {
            intent = objectiveIntent(body, mind, squad, world, pos, index);
        }
        if (!busy && intent == null && mind.exitPoint != null && now < mind.exitUntil) {
            intent = steerTo(body, mind, world, pos, mind.exitPoint, 0.5D, NullBody.GAIT_RUN);
            if (intent == null) {
                mind.exitPoint = null;
            } else {
                // Leaving a floating doorway means dropping out of it: never let the
                // ledge-sneak rule hold the body on the frame's bottom row.
                intent.allowDrop = true;
            }
        }
        if (!busy && intent == null && mind.slideTo != null && now < mind.slideUntil) {
            intent = steerTo(body, mind, world, pos, mind.slideTo, 0.4D, NullBody.GAIT_WALK);
            if (intent == null) {
                mind.slideTo = null;
            }
        }

        boolean idle = intent == null;
        if (intent == null) {
            intent = new Intent();
        }

        // Keep a body's width from everybody (never during a precise hold).
        if (!intent.precise && v3 != null && neighbours.size() > 1) {
            double[] push = Separation.steer(index, neighbours, v3.separationRadius());
            double strength = Math.hypot(push[0], push[1]);
            if (strength > 0.05D) {
                if (!intent.moving()) {
                    intent.dx = push[0];
                    intent.dz = push[1];
                    intent.gait = NullBody.GAIT_WALK;
                    idle = false;
                } else {
                    intent.dx += push[0] * 0.7D;
                    intent.dz += push[1] * 0.7D;
                }
            }
        }

        if (intent.moving() && !intent.sneak && !intent.allowDrop && body.onGround()) {
            intent.sneak = ledgeAhead(world, pos, intent.dx, intent.dz);
        }
        // L-02: walking towards a gap, the Null bridges it out of its own pack.
        if (intent.moving() && !busy) {
            autoBridge(body, mind, handle, world, pos, intent);
        }
        // L-03: the owner comes home - the guard salutes.
        homecomingSalute(body, mind, world, pos);

        updateIdle(mind, pos, intent.moving() || busy);
        if (idle && !busy && v3 != null && v3.idleBehaviour()) {
            idleLife(body, mind, handle, world, pos, intent);
            if (v3.campLife()) {
                campLife(body, mind, handle, world, pos, intent, idle);
            }
        }
        if (!busy || intent.look == null) {
            chooseLook(body, mind, handle, world, pos, intent);
        }
        if (mind.gestureUntil > now) {
            intent.look = new Vec3d(pos.x(), pos.y() + 0.2D, pos.z()).add(forward(body, 2.0D));
            intent.lookHeadOnly = true;
        }

        body.setMovement(intent.dx, intent.dz, intent.gait, intent.jump, intent.sneak);
        body.setLookTarget(intent.look, intent.lookHeadOnly);
        mind.lastPos = pos;
    }

    // ------------------------------------------------------------- movement

    /**
     * Walks toward a point: direction, a throttle that slows down for the last
     * block, sprinting over long distances (with sprint jumps), and a sidestep
     * when a wall keeps stopping the body.
     *
     * @return null when the point is reached
     */
    Intent steerTo(NullBody body, Mind mind, String world, Vec3d pos, Vec3d target, double arrive, int gait) {
        if (target == null || pos == null) {
            return null;
        }
        Vec3d goal = target;
        if (mind.detour != null && now < mind.detourUntil) {
            goal = mind.detour;
        } else {
            mind.detour = null;
        }
        double dx = goal.x() - pos.x();
        double dz = goal.z() - pos.z();
        double dist = Math.hypot(dx, dz);
        if (goal == target && dist <= arrive) {
            mind.blockedTicks = 0;
            return null;
        }
        if (goal != target && dist <= 0.6D) {
            mind.detour = null;
            return steerTo(body, mind, world, pos, target, arrive, gait);
        }
        Intent intent = new Intent();
        intent.dx = dx / Math.max(1.0e-6, dist);
        intent.dz = dz / Math.max(1.0e-6, dist);
        double throttle = Math.max(0.25D, Math.min(1.0D, dist / 1.2D));
        intent.dx *= throttle;
        intent.dz *= throttle;
        intent.gait = gait;
        double fullDist = Math.hypot(target.x() - pos.x(), target.z() - pos.z());
        if (gait == NullBody.GAIT_RUN && fullDist > 8.0D) {
            intent.gait = NullBody.GAIT_SPRINT;
        }
        if (intent.gait == NullBody.GAIT_SPRINT && body.onGround() && fullDist > 10.0D
                && (now + Math.abs(body.id())) % 14 == 0) {
            intent.jump = true; // sprint-jumping, the fast way players travel
        }
        if (target.y() > pos.y() + 0.6D && fullDist < 2.5D && body.onGround()) {
            intent.jump = true;
        }
        if (body.horizontalCollision() && body.onGround()) {
            mind.blockedTicks++;
        } else if (mind.blockedTicks > 0) {
            mind.blockedTicks--;
        }
        if (mind.blockedTicks > 16 && mind.detour == null) {
            // A wall the body cannot hop over: step sideways and try again.
            int side = (mind.id.hashCode() + (int) (now / 60)) % 2 == 0 ? 1 : -1;
            double px = -intent.dz;
            double pz = intent.dx;
            double len = Math.max(1.0e-6, Math.hypot(px, pz));
            mind.detour = new Vec3d(pos.x() + px / len * 2.5D * side, pos.y(), pos.z() + pz / len * 2.5D * side);
            mind.detourUntil = now + 30;
            mind.blockedTicks = 0;
        }
        return intent;
    }

    /** True when the step ahead drops three blocks or more: sneak so vanilla holds the edge. */
    private boolean ledgeAhead(String world, Vec3d pos, double dx, double dz) {
        if (plugin.blockInspectionBudget() != null && !plugin.blockInspectionBudget().tryConsume(3)) {
            return false;
        }
        try {
            BlockView view = plugin.adapter().blockView(world);
            double len = Math.max(1.0e-6, Math.hypot(dx, dz));
            int x = (int) Math.floor(pos.x() + dx / len * 0.8D);
            int z = (int) Math.floor(pos.z() + dz / len * 0.8D);
            int y = (int) Math.floor(pos.y());
            return !view.isSolid(x, y - 1, z) && !view.isSolid(x, y - 2, z) && !view.isSolid(x, y - 3, z);
        } catch (Throwable t) {
            return false;
        }
    }

    private Vec3d forward(NullBody body, double distance) {
        double yaw = Math.toRadians(body.headYaw());
        return new Vec3d(-Math.sin(yaw) * distance, 0.0D, Math.cos(yaw) * distance);
    }

    // ---------------------------------------------------------------- orders

    private Intent orderIntent(NullBody body, Mind mind, Player handle, String world, Vec3d pos) {
        Mind.Order order = mind.order;
        Vec3d target = order.point;
        if (order.entity != null) {
            Entity entity = Bukkit.getEntity(order.entity);
            if (entity == null || entity.isDead() || entity.getWorld() == null
                    || !entity.getWorld().getName().equals(world)) {
                if (order.verb != Mind.Verb.HOLD && order.verb != Mind.Verb.STOP) {
                    mind.order = null;
                    return null;
                }
            } else {
                Location at = entity.getLocation();
                target = new Vec3d(at.getX(), at.getY(), at.getZ());
            }
        }
        switch (order.verb) {
            case WALK:
            case RUN:
            case SPRINT: {
                int gait = order.verb == Mind.Verb.WALK ? NullBody.GAIT_WALK
                        : order.verb == Mind.Verb.RUN ? NullBody.GAIT_RUN : NullBody.GAIT_SPRINT;
                Intent intent = steerTo(body, mind, world, pos, target, order.entity != null ? 2.0D : 0.6D, gait);
                if (intent != null && gait != NullBody.GAIT_SPRINT && intent.gait == NullBody.GAIT_SPRINT) {
                    intent.gait = gait; // a walk order is a walk, however far
                    intent.jump = intent.jump && target != null && target.y() > pos.y() + 0.6D;
                }
                if (intent == null) {
                    mind.order = null;
                    return Intent.stop();
                }
                if (target != null && target.y() < pos.y() - 1.5D) {
                    intent.allowDrop = true; // ordered down there on purpose
                }
                return intent;
            }
            case JUMP: {
                Intent intent = Intent.stop();
                if (body.onGround() && now >= mind.nextAttackTick) {
                    intent.jump = true;
                    mind.nextAttackTick = now + 12;
                    order.remaining--;
                    if (order.remaining <= 0) {
                        mind.order = null;
                    }
                }
                return intent;
            }
            case STOP:
                return Intent.stop();
            case FOLLOW: {
                Intent intent = steerTo(body, mind, world, pos, target, 2.5D, NullBody.GAIT_RUN);
                return intent == null ? Intent.stop() : intent;
            }
            case DEFEND: {
                Intent intent = steerTo(body, mind, world, pos, target, 3.0D, NullBody.GAIT_RUN);
                return intent == null ? Intent.stop() : intent;
            }
            case HOLD: {
                Vec3d cell = mind.holdCell != null ? mind.holdCell : order.point;
                if (cell == null) {
                    return Intent.stop();
                }
                Intent intent = steerTo(body, mind, world, pos, cell, 0.15D, NullBody.GAIT_WALK);
                if (intent != null) {
                    intent.precise = true;
                    return intent;
                }
                // L-03: a guard sweeps his head instead of staring at the floor.
                Intent stop = Intent.stop();
                Vec3d ahead = forward(body, 6.0D);
                double base = Math.atan2(ahead.z(), ahead.x());
                double phase = (now % 100L) / 100.0D * Math.PI * 2.0D;
                double yaw = base + Math.sin(phase) * 0.7D;
                stop.look = new Vec3d(pos.x() + Math.cos(yaw) * 6.0D, pos.y() + 1.6D,
                        pos.z() + Math.sin(yaw) * 6.0D);
                stop.lookHeadOnly = true;
                return stop;
            }
            case ATTACK:
                if (order.entity != null) {
                    mind.combatTarget = order.entity;
                    mind.combatUntil = now + 20L * 120L;
                }
                mind.order = null;
                return null;
            case MARCH: {
                // L-01: one clock for the whole squad. Every marcher walks at
                // exactly the same speed and swings on the same tick, so the
                // line stays a line instead of stringing out and jittering.
                int period = v3 == null ? MarchCadence.DEFAULT_PERIOD_TICKS : v3.marchPeriodTicks();
                Vec3d cell = mind.holdCell != null ? mind.holdCell : order.point;
                Intent intent = steerTo(body, mind, world, pos, cell, 0.35D, NullBody.GAIT_WALK);
                if (intent == null) {
                    return Intent.stop();
                }
                if (v3 != null && v3.marchCadence()) {
                    lockStep(body, mind, handle, period);
                    if (MarchCadence.stepTick(now, period) && now >= mind.nextStepTick
                            && body.onGround()) {
                        Guard.attempt(plugin.getLogger(), "march step", handle::swingMainHand);
                        mind.nextStepTick = now + period;
                    }
                }
                return intent;
            }
            case DRILL: {
                Vec3d cell = mind.holdCell != null ? mind.holdCell : order.point;
                if (cell == null) {
                    return Intent.stop();
                }
                Intent intent = steerTo(body, mind, world, pos, cell, 0.15D, NullBody.GAIT_WALK);
                if (intent == null) {
                    return Intent.stop();
                }
                intent.precise = true;
                return intent;
            }
            case PATROL: {
                // L-03: walk between two points for ever, counting the laps.
                Vec3d a = order.point;
                Vec3d b = order.pointB;
                if (a == null || b == null) {
                    mind.order = null;
                    return null;
                }
                Vec3d goal = mind.patrolToB ? b : a;
                Intent intent = steerTo(body, mind, world, pos, goal, 0.8D, NullBody.GAIT_WALK);
                if (intent == null) {
                    mind.patrolToB = !mind.patrolToB;
                    if (!mind.patrolToB) {
                        patrolLaps++;
                    }
                    return Intent.stop();
                }
                return intent;
            }
            case BRIDGE: {
                // L-02 / P-07: walk along the ordered line and bridge the gaps.
                Intent intent = steerTo(body, mind, world, pos, order.point, 0.8D, NullBody.GAIT_WALK);
                if (intent == null) {
                    mind.order = null;
                    return Intent.stop();
                }
                if (body.onGround() && ledgeAhead(world, pos, intent.dx, intent.dz)) {
                    intent.sneak = true; // a player bridging sneaks at the edge
                }
                return intent;
            }
            case SALUTE: {
                // L-03: stop, face the owner, swing once. One salute per order.
                Intent intent = Intent.stop();
                if (order.entity != null) {
                    intent.look = targetPosition(order.entity, world);
                    intent.lookHeadOnly = false;
                }
                if (now - order.issuedTick < 4L) {
                    return intent;
                }
                if (!mind.saluted) {
                    mind.saluted = true;
                    salutes++;
                    Guard.attempt(plugin.getLogger(), "salute", handle::swingMainHand);
                }
                if (now - order.issuedTick > 24L) {
                    mind.order = mind.orderAfterSalute;
                    mind.orderAfterSalute = null;
                }
                return intent;
            }
            case REGROUP: {
                // L-07: come back to the owner and reform.
                Vec3d owner = order.entity == null ? order.point : targetPosition(order.entity, world);
                if (owner == null) {
                    mind.order = null;
                    return null;
                }
                Intent intent = steerTo(body, mind, world, pos, owner, 2.2D, NullBody.GAIT_RUN);
                if (intent == null) {
                    mind.order = null;
                    return Intent.stop();
                }
                return intent;
            }
            case DESTROY: {
                // P-09: teardown. Only ever issued after policy + confirm.
                Intent intent = destroyStep(body, mind, handle, world, pos, order);
                return intent == null ? Intent.stop() : intent;
            }
            case HUNT: {
                // L-07: hunt the ordered target to the end. The chasers fight;
                // the rest hold the line. When the target is gone, regroup.
                Entity hunted = order.entity == null ? null : Bukkit.getEntity(order.entity);
                boolean finished = hunted == null || hunted.isDead()
                        || (hunted instanceof Player && !((Player) hunted).isOnline());
                if (finished) {
                    UUID owner = order.issuer;
                    mind.order = new Mind.Order(Mind.Verb.REGROUP, order.point, owner, owner, now, 1);
                    return Intent.stop();
                }
                if (!order.chaser) {
                    Vec3d hold = mind.holdCell != null ? mind.holdCell : body.bodyPosition();
                    Intent intent = steerTo(body, mind, world, pos, hold, 0.3D, NullBody.GAIT_WALK);
                    if (intent == null) {
                        return Intent.stop();
                    }
                    Location at = hunted.getLocation();
                    intent.look = new Vec3d(at.getX(), at.getY() + 1.6D, at.getZ());
                    intent.lookHeadOnly = false;
                    return intent;
                }
                mind.combatTarget = order.entity;
                mind.combatUntil = now + 20L * 60L;
                return null;
            }
            case GATHER:
            case BUILD:
            default:
                mind.order = null;
                return null;
        }
    }

    /** L-01: every marcher walks at the same speed - no per-body variance. */
    private void lockStep(NullBody body, Mind mind, Player handle, int period) {
        if (handle == null || mind.speedFactor == 1.0D) {
            return;
        }
        try {
            AttributeInstance speed = handle.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(0.1D);
            }
        } catch (Throwable ignored) {
            // A body without the attribute still marches at its own pace.
        }
        mind.speedFactor = 1.0D;
    }

    /** L-01: which cell of the current drill formation this body holds. */
    private void updateDrillCell(NullBody body, Mind mind, SquadManager.Squad squad, String world, int index) {
        if (mind.order == null) {
            return;
        }
        int hold = v3 == null ? MarchCadence.DEFAULT_DRILL_TICKS : v3.drillHoldTicks();
        String form = MarchCadence.drillFormation(now - mind.order.issuedTick, hold);
        if (form.equals(mind.drillForm) && mind.holdCell != null) {
            return;
        }
        mind.drillForm = form;
        Vec3d anchor = mind.order.point;
        if (anchor == null) {
            anchor = body.bodyPosition();
        }
        int count = squad == null ? 1 : Math.max(1, squad.members().size());
        double spacing = v3 == null ? 1.5D : v3.formationSpacing();
        List<double[]> cells = FormationMatrix.worldCells(form, count, spacing, anchor.x(), anchor.z(), 0.0F);
        if (cells.isEmpty()) {
            return;
        }
        double[] cell = cells.get(Math.min(index, cells.size() - 1));
        mind.holdCell = new Vec3d(cell[0], anchor.y(), cell[1]);
        acknowledge(body, mind);
    }

    /**
     * L-02: bridge a gap on the route out of the body's own inventory.
     *
     * <p>The Unstable NULL army bridges the void with netherrack in huge
     * numbers. One block per place rate, held in the hand and placed through
     * {@code BlockPlaceEvent}, sneaking at the edge exactly like a player.</p>
     */
    private void autoBridge(NullBody body, Mind mind, Player handle, String world, Vec3d pos, Intent intent) {
        if (v3 == null || !v3.autoBridge() || handle == null) {
            return;
        }
        if (now < mind.nextBridgeTick) {
            return;
        }
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return;
        }
        double len = Math.hypot(intent.dx, intent.dz);
        if (len < 0.05D) {
            return;
        }
        int fx = (int) Math.floor(pos.x() + intent.dx / len * 1.3D);
        int fz = (int) Math.floor(pos.z() + intent.dz / len * 1.3D);
        int fy = (int) Math.floor(pos.y());
        Block floor = w.getBlockAt(fx, fy - 1, fz);
        if (!floor.getType().isAir()) {
            return;
        }
        // Only a shallow gap: a ravine is not a puddle.
        int depth = 1;
        while (depth < 4 && w.getBlockAt(fx, fy - 1 - depth, fz).getType().isAir()) {
            depth++;
        }
        if (depth >= 4) {
            return;
        }
        Material material = bridgeMaterial(handle);
        if (material == null) {
            return;
        }
        intent.sneak = true;
        if (Bodies.placeOne(handle, floor, material)) {
            blocksBridged++;
            mind.nextBridgeTick = now + 6L;
        } else {
            mind.nextBridgeTick = now + 20L;
        }
    }

    /** The most plentiful placeable block a body carries, for bridging. */
    private Material bridgeMaterial(Player handle) {
        PlayerInventory inv = handle.getInventory();
        Material best = null;
        int bestCount = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || !stack.getType().isBlock() || stack.getType().isAir()) {
                continue;
            }
            if (stack.getType() == Material.TNT || stack.getType().name().contains("BUCKET")) {
                continue;
            }
            if (stack.getAmount() > bestCount) {
                bestCount = stack.getAmount();
                best = stack.getType();
            }
        }
        return best;
    }

    /**
     * P-09: one block of a teardown, broken by hand.
     *
     * <p>Only ever reached after {@code policy.griefing-enabled} and the owner's
     * confirm - {@code OrderService} refuses everything else. One block per
     * swing, inside the marked area only, and the drops are left on the ground
     * for the looters.</p>
     */
    private Intent destroyStep(NullBody body, Mind mind, Player handle, String world, Vec3d pos, Mind.Order order) {
        if (handle == null || order.point == null) {
            mind.order = null;
            return null;
        }
        World w = Bukkit.getWorld(world);
        if (w == null) {
            mind.order = null;
            return null;
        }
        if (now < mind.nextBridgeTick) {
            return Intent.stop();
        }
        int radius = Math.max(1, Math.min(8, order.radius));
        int cx = (int) Math.floor(order.point.x());
        int cy = (int) Math.floor(order.point.y());
        int cz = (int) Math.floor(order.point.z());
        if (cy <= w.getMinHeight()) {
            mind.order = null;
            return null;
        }
        int reach = 2;
        Block chosen = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                Block block = w.getBlockAt(cx + dx, cy + 1, cz + dz);
                if (block.getType().isAir() || !block.getType().isSolid()) {
                    continue;
                }
                if (Math.abs(dx) > radius || Math.abs(dz) > radius) {
                    continue;
                }
                double d = Math.hypot(block.getX() + 0.5D - pos.x(), block.getZ() + 0.5D - pos.z());
                if (d < bestDistance) {
                    bestDistance = d;
                    chosen = block;
                }
            }
        }
        if (chosen == null) {
            // This level is gone: drop to the next one down and carry on.
            order.point = new Vec3d(order.point.x(), order.point.y() - 1.0D, order.point.z());
            mind.nextBridgeTick = now + 4L;
            return Intent.stop();
        }
        if (bestDistance > 4.2D) {
            return steerTo(body, mind, world, pos,
                    new Vec3d(chosen.getX() + 0.5D, pos.y(), chosen.getZ() + 0.5D), 1.2D, NullBody.GAIT_WALK);
        }
        Intent intent = Intent.stop();
        intent.look = new Vec3d(chosen.getX() + 0.5D, chosen.getY() + 0.5D, chosen.getZ() + 0.5D);
        intent.lookHeadOnly = false;
        if (Bodies.breakOne(handle, chosen)) {
            blocksDestroyed++;
        }
        mind.nextBridgeTick = now + 10L;
        return intent;
    }

    /**
     * L-03: the owner comes back within 8 blocks - the guard salutes once.
     *
     * <p>No chat, no sound, no broadcast: a hand raised and then back to work.
     * The order is restored by finishing the salute, so the guard keeps
     * guarding after greeting his commander.</p>
     */
    private void homecomingSalute(NullBody body, Mind mind, String world, Vec3d pos) {
        if (v3 == null || !v3.campLife() || mind.order == null) {
            return;
        }
        Mind.Verb verb = mind.order.verb;
        if (verb != Mind.Verb.HOLD && verb != Mind.Verb.DEFEND && verb != Mind.Verb.PATROL) {
            return;
        }
        UUID ownerUuid = mind.order.issuer;
        if (ownerUuid == null) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerUuid);
        if (owner == null || !owner.isOnline() || owner.getWorld() == null
                || !world.equals(owner.getWorld().getName())) {
            return;
        }
        Location at = owner.getLocation();
        double near = Math.hypot(Math.hypot(at.getX() - pos.x(), at.getZ() - pos.z()),
                Math.max(0.0D, at.getY() - pos.y()));
        if (near > 8.0D) {
            return;
        }
        if (now - mind.lastOwnerNearTick < 200L) {
            return;
        }
        mind.lastOwnerNearTick = now;
        Mind.Order standing = mind.order;
        mind.order = new Mind.Order(Mind.Verb.SALUTE, null, ownerUuid, ownerUuid, now, 1);
        mind.saluted = false;
        mind.orderAfterSalute = standing;
    }

    /** L-06: sneak + horn = recall to the owner, in formation, on the march cadence. */
    public String recall(Player owner) {
        if (owner == null) {
            return "no owner";
        }
        SquadManager.Squad squad = plugin.squads().find(owner.getUniqueId());
        if (squad == null) {
            return "no Nulls";
        }
        List<NullBody> list = squad.members();
        if (list == null || list.isEmpty()) {
            return "no Nulls";
        }
        Location loc = owner.getLocation();
        if (loc == null || loc.getWorld() == null) {
            return "no position";
        }
        Vec3d point = new Vec3d(loc.getX(), Math.floor(loc.getY()), loc.getZ());
        String answer = order(list, Mind.Verb.MARCH, point, null, owner.getUniqueId(), 1);
        if (plugin.chatGate() != null) {
            plugin.chatGate().event("horn.recall", "player", owner.getName(), "count", String.valueOf(list.size()));
        }
        return answer;
    }

    /**
     * L-04: silent camp life.
     *
     * <p>Squads that stand around doing nothing look like statues. Around a light
     * source they rest in a ring, spar in pairs - swinging at each other with
     * ZERO damage, because squad mates cannot hurt each other - eat when hurt,
     * and haul spare blocks to a builder that needs them. Nothing here speaks,
     * nothing here damages anything.</p>
     */
    private void campLife(NullBody body, Mind mind, Player handle, String world, Vec3d pos, Intent intent,
                          boolean idle) {
        if (handle == null || !idle) {
            return;
        }
        if (body.health() < NullBrain.maxHealth(handle) * (v3 == null ? 0.6D : v3.eatBelowHealth())) {
            if (maybeEat(body, mind, handle)) {
                mind.campBehaviour = "eat";
                campBehaviours.add("eat");
            }
            return;
        }
        if (now - mind.idleSince > (v3 == null ? 1200 : v3.restAfterIdleTicks())) {
            mind.campBehaviour = "rest";
            campBehaviours.add("rest");
            intent.sneak = true;
            return;
        }
        // Spar with a nearby squad mate: swing at him, deal nothing.
        if (now % 40L == (Math.abs(mind.id.hashCode()) % 40L)) {
            for (Entity near : handle.getNearbyEntities(2.5D, 2.0D, 2.5D)) {
                if (!(near instanceof Player) || near.isDead()) {
                    continue;
                }
                if (plugin.adapter() != null && !plugin.adapter().isNullEntity(near.getUniqueId())
                        && !mayTarget(body, near)) {
                    continue; // a real player who must not be touched
                }
                Guard.attempt(plugin.getLogger(), "camp sparring", handle::swingMainHand);
                mind.campBehaviour = "spar";
                campBehaviours.add("spar");
                return;
            }
        }
        // Haul: a builder in the squad that is short of blocks gets a top-up.
        if (plugin.builder() != null && now % 60L == 0) {
            redglitchx.nullarmy.plugin.ai.builder.BuilderService.Job job = plugin.builder().jobOf(ownerOf(body));
            if (job != null && job.active()) {
                mind.campBehaviour = "haul";
                campBehaviours.add("haul");
                return;
            }
        }
        // Otherwise: ring round the light with the squad, or stand watch alone.
        // Nothing here moves the body more than a step, so an idle camp stays idle.
        int mates = 0;
        for (Entity near : handle.getNearbyEntities(6.0D, 3.0D, 6.0D)) {
            if (near instanceof Player && plugin.adapter() != null
                    && plugin.adapter().isNullEntity(near.getUniqueId()) && !near.isDead()) {
                mates++;
            }
        }
        if (mates >= 2) {
            mind.campBehaviour = "ring";
            campBehaviours.add("ring");
        } else {
            mind.campBehaviour = "watch";
            campBehaviours.add("watch");
        }
        // A guard never stares at his boots: the head keeps moving.
        Vec3d ahead = forward(body, 6.0D);
        double base = Math.atan2(ahead.z(), ahead.x());
        double yaw = base + Math.sin((now % 120L) / 120.0D * Math.PI * 2.0D) * 0.8D;
        intent.look = new Vec3d(pos.x() + Math.cos(yaw) * 6.0D, pos.y() + 1.6D,
                pos.z() + Math.sin(yaw) * 6.0D);
        intent.lookHeadOnly = true;
    }

    private UUID ownerOf(NullBody body) {
        return plugin.squads() == null ? null : plugin.squads().ownerOf(body);
    }

    /**
     * Gives an order to a set of bodies, acknowledging it with a gesture.
     *
     * @return a one-line answer for the issuer
     */
    public String order(List<NullBody> targets, Mind.Verb verb, Vec3d point, UUID entity, UUID issuer, int count) {
        if (targets == null || targets.isEmpty()) {
            return "no Null matched";
        }
        int given = 0;
        for (NullBody body : targets) {
            Mind mind = mind(body);
            if (mind == null) {
                continue;
            }
            Mind.Order created = new Mind.Order(verb, point, entity, issuer, now, Math.max(1, count));
            mind.order = created;
            mind.detour = null;
            mind.blockedTicks = 0;
            mind.saluted = false;
            mind.patrolToB = false;
            mind.drillForm = "";
            if (verb == Mind.Verb.HOLD || verb == Mind.Verb.DRILL) {
                mind.holdCell = point != null ? point : body.bodyPosition();
            } else {
                mind.holdCell = null;
            }
            if (verb == Mind.Verb.DESTROY) {
                created.radius = Math.max(1, count);
            }
            if (verb == Mind.Verb.STOP) {
                mind.combatTarget = null;
                if (plugin.builder() != null) {
                    plugin.builder().release(body);
                }
            }
            if ((verb == Mind.Verb.ATTACK || verb == Mind.Verb.HUNT) && entity != null) {
                mind.combatTarget = entity;
                mind.combatUntil = now + 20L * 120L;
            }
            acknowledge(body, mind);
            if (issuer != null) {
                attend(body, issuer, 50);
            }
            given++;
        }
        if (verb == Mind.Verb.MARCH) {
            assignMarchCells(targets, point);
        }
        return given + " Null(s): " + verb.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * L-01: marching Nulls walk to their own cell in a phalanx around the
     * destination, so they arrive as a block instead of a crowd.
     */
    private void assignMarchCells(List<NullBody> targets, Vec3d point) {
        if (targets == null || point == null || targets.isEmpty()) {
            return;
        }
        int count = targets.size();
        double spacing = v3 == null ? 1.5D : v3.formationSpacing();
        List<double[]> cells = FormationMatrix.worldCells("phalanx", count, spacing, point.x(), point.z(), 0.0F);
        if (cells.isEmpty()) {
            return;
        }
        double[][] cost = new double[count][cells.size()];
        List<Vec3d> positions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            positions.add(targets.get(i).bodyPosition());
        }
        for (int i = 0; i < count; i++) {
            Vec3d p = positions.get(i);
            for (int j = 0; j < cells.size(); j++) {
                cost[i][j] = Math.hypot(p.x() - cells.get(j)[0], p.z() - cells.get(j)[1]);
            }
        }
        int[] assignment = FormationMatrix.optimalAssignment(cost);
        for (int i = 0; i < count; i++) {
            Mind mind = mind(targets.get(i));
            if (mind == null || mind.order == null) {
                continue;
            }
            int cell = assignment[i] >= 0 && assignment[i] < cells.size() ? assignment[i] : i % cells.size();
            mind.holdCell = new Vec3d(cells.get(cell)[0], point.y(), cells.get(cell)[1]);
        }
    }

    /** L-03: walk between two points for ever. */
    public String patrol(List<NullBody> targets, Vec3d a, Vec3d b, UUID issuer) {
        String answer = order(targets, Mind.Verb.PATROL, a, null, issuer, 1);
        for (NullBody body : targets) {
            Mind mind = mind(body);
            if (mind != null && mind.order != null) {
                mind.order.pointB = b;
                mind.patrolToB = true;
            }
        }
        return answer;
    }

    /** L-02 / P-07: walk forward along a line, bridging the gaps on the way. */
    public String bridge(List<NullBody> targets, Vec3d ahead, UUID issuer) {
        return order(targets, Mind.Verb.BRIDGE, ahead, null, issuer, 1);
    }

    /**
     * L-07: hunt a target to the end.
     *
     * <p>Up to {@code behaviour.hunt-chasers} bodies chase; the rest hold the
     * line and watch. When the target is dead or gone every one of them
     * regroups on the owner by itself.</p>
     */
    public String hunt(List<NullBody> targets, UUID target, UUID issuer, int chasers) {
        String answer = order(targets, Mind.Verb.HUNT, null, target, issuer, 1);
        int limit = Math.max(1, chasers);
        huntEngagements = 0;
        for (int i = 0; i < targets.size(); i++) {
            Mind mind = mind(targets.get(i));
            if (mind == null || mind.order == null) {
                continue;
            }
            mind.order.chaser = i < limit;
            mind.order.holder = !mind.order.chaser;
            mind.holdCell = targets.get(i).bodyPosition();
            if (mind.order.chaser) {
                huntEngagements++;
            }
        }
        return answer;
    }

    /** P-09: teardown of a marked area. Only ever called after policy + confirm. */
    public String destroy(List<NullBody> targets, Vec3d centre, int radius, UUID issuer) {
        return order(targets, Mind.Verb.DESTROY, centre, null, issuer, Math.max(1, radius));
    }

    /** L-03: one salute. */
    public String salute(List<NullBody> targets, UUID issuer) {
        return order(targets, Mind.Verb.SALUTE, null, issuer, issuer, 1);
    }

    /** A visible "understood": an arm swing and a short nod. */
    public void acknowledge(NullBody body, Mind mind) {
        if (v3 != null && !v3.gestureAck()) {
            return;
        }
        Player handle = Bodies.player(body);
        if (handle != null) {
            Guard.attempt(plugin.getLogger(), "order gesture", handle::swingMainHand);
        }
        if (mind != null) {
            mind.gestureUntil = now + 8;
        }
    }

    /** Turns a body's head to an entity (the speaker, the order-giver) for a while. */
    public void attend(NullBody body, UUID entity, int ticks) {
        Mind mind = mind(body);
        if (mind == null || entity == null) {
            return;
        }
        mind.glancedAt = entity;
        mind.attentionUntil = now + Math.max(10, ticks);
        mind.attentionHeadOnly = true;
        mind.nextPlayerGlance = mind.attentionUntil + 40;
    }

    /** Holds a body's look on a point for a while (aiming, the self test). */
    public void forceLook(NullBody body, Vec3d point, int ticks, boolean headOnly) {
        Mind mind = mind(body);
        if (mind == null || point == null) {
            return;
        }
        mind.glancedAt = null;
        mind.attention = point;
        mind.attentionIsScan = false;
        mind.attentionUntil = now + Math.max(1, ticks);
        mind.attentionHeadOnly = headOnly;
        mind.nextPlayerGlance = mind.attentionUntil + 40;
        body.setLookTarget(point, headOnly);
    }

    /** Every Null of {@code owner} within 24 blocks looks at the speaker. */
    public void noteSpeaker(UUID owner, UUID speaker) {
        if (plugin.squads() == null) {
            return;
        }
        for (NullBody body : plugin.squads().membersOf(owner)) {
            attend(body, speaker, 60);
        }
        if (plugin.commander() != null && plugin.commander().body() != null) {
            attend(plugin.commander().body(), speaker, 80);
        }
    }

    /** Clears every order of the given bodies. */
    public void clearOrders(List<NullBody> targets) {
        for (NullBody body : targets) {
            Mind mind = mind(body);
            if (mind != null) {
                mind.order = null;
                mind.holdCell = null;
                mind.combatTarget = null;
            }
        }
    }

    /** A freshly arrived Null walks out of its doorway first. */
    public void stepOut(NullBody body, Vec3d point, int ticks) {
        Mind mind = mind(body);
        if (mind != null && point != null) {
            mind.exitPoint = point;
            mind.exitUntil = now + Math.max(20, ticks);
        }
    }

    // ------------------------------------------------------------ objectives

    private Intent objectiveIntent(NullBody body, Mind mind, SquadManager.Squad squad, String world,
                                   Vec3d pos, int indexInWorld) {
        int index = squad.members().indexOf(body);
        if (index < 0) {
            index = indexInWorld;
        }
        switch (squad.objective()) {
            case GUARD:
                return Intent.stop();
            case DESTINATION: {
                Intent intent = steerTo(body, mind, world, pos, squad.point(), 1.5D, NullBody.GAIT_RUN);
                return intent == null ? Intent.stop() : intent;
            }
            case ATTACK: {
                if (squad.targetId() != null) {
                    mind.combatTarget = squad.targetId();
                    mind.combatUntil = now + 40;
                }
                return null;
            }
            case FORMATION:
                return formationIntent(body, mind, squad, world, pos, index);
            case FOLLOW:
            default: {
                Vec3d target = targetPosition(squad.targetId(), world);
                if (target == null) {
                    return Intent.stop();
                }
                Intent intent = steerTo(body, mind, world, pos, target, 2.0D + 0.6D * (index % 4), NullBody.GAIT_RUN);
                return intent == null ? Intent.stop() : intent;
            }
        }
    }

    /**
     * The member's own cell of the formation matrix: rotated by the anchor's
     * facing, behind a moving owner or at a held anchor. Cells are spaced at
     * least 1.1 apart, so nobody shares one, and arrival is precise - once in
     * its cell a Null stands still (no jitter).
     */
    private Intent formationIntent(NullBody body, Mind mind, SquadManager.Squad squad, String world,
                                   Vec3d pos, int index) {
        Vec3d cell = formationCell(squad, world, index);
        if (cell == null) {
            return Intent.stop();
        }
        double fromCell = Math.hypot(cell.x() - pos.x(), cell.z() - pos.z());
        Intent intent = mind.atCell && fromCell < 0.25D ? null
                : steerTo(body, mind, world, pos, cell, 0.15D, NullBody.GAIT_RUN);
        mind.atCell = intent == null;
        if (intent == null) {
            Intent hold = Intent.stop();
            Vec3d anchorLook = squad.formationAnchor();
            if (anchorLook != null) {
                double yaw = Math.toRadians(squad.formationYaw());
                hold.look = new Vec3d(cell.x() - Math.sin(yaw) * 6.0D, cell.y() + 1.6D, cell.z() + Math.cos(yaw) * 6.0D);
                hold.lookHeadOnly = false;
            }
            return hold;
        }
        double dist = Math.hypot(cell.x() - pos.x(), cell.z() - pos.z());
        if (dist < 3.0D) {
            intent.gait = NullBody.GAIT_WALK;
            intent.precise = true;
        }
        return intent;
    }

    /** World position of member {@code index}'s formation cell, or null. */
    public Vec3d formationCell(SquadManager.Squad squad, String world, int index) {
        if (squad == null) {
            return null;
        }
        String kind = squad.formation();
        int count = squad.members().size();
        double spacing = v3 == null ? 1.5D : v3.formationSpacing();
        double ax;
        double az;
        double ay;
        float yaw;
        Vec3d anchor = squad.formationAnchor();
        if (anchor != null) {
            ax = anchor.x();
            ay = anchor.y();
            az = anchor.z();
            yaw = squad.formationYaw();
        } else {
            Player owner = squad.targetId() == null ? null : Bukkit.getPlayer(squad.targetId());
            if (owner == null || !owner.getWorld().getName().equals(world)) {
                return null;
            }
            Location at = owner.getLocation();
            yaw = at.getYaw();
            double back = FormationMatrix.followDistance(kind, count, spacing);
            double yawRad = Math.toRadians(yaw);
            ax = at.getX() + Math.sin(yawRad) * back;
            az = at.getZ() - Math.cos(yawRad) * back;
            ay = at.getY();
        }
        List<double[]> cells = FormationMatrix.worldCells(kind, count, spacing, ax, az, yaw);
        if (index < 0 || index >= cells.size()) {
            return null;
        }
        String key = kind + ":" + count + ":" + (anchor != null
                ? Math.round(ax * 4) + "," + Math.round(az * 4) + "," + Math.round(yaw) : "follow");
        int[] assignment = squad.formationAssignment();
        if (assignment == null || assignment.length != count || !key.equals(squad.formationAssignmentKey())) {
            assignment = assignCells(squad, cells);
            squad.setFormationAssignment(assignment, key);
        }
        int cell = assignment[index] >= 0 && assignment[index] < cells.size() ? assignment[index] : index;
        return new Vec3d(cells.get(cell)[0], ay, cells.get(cell)[1]);
    }

    /**
     * Nearest-first assignment of members to cells: repeatedly the closest free
     * (member, cell) pair. Paths barely cross, so nobody shoulders through the
     * squad to reach a cell on the far side - the cause of formations that never
     * settled. Every member gets exactly one cell and no cell is shared.
     */
    static int[] assignCells(SquadManager.Squad squad, List<double[]> cells) {
        List<NullBody> members = squad.members();
        int n = Math.min(members.size(), cells.size());
        int[] out = new int[members.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = i % Math.max(1, cells.size());
        }
        if (n == 0) {
            return out;
        }
        double[][] cost = new double[n][cells.size()];
        for (int i = 0; i < n; i++) {
            Vec3d p = members.get(i).bodyPosition();
            for (int j = 0; j < cells.size(); j++) {
                cost[i][j] = Math.hypot(p.x() - cells.get(j)[0], p.z() - cells.get(j)[1]);
            }
        }
        int[] best = FormationMatrix.optimalAssignment(cost);
        System.arraycopy(best, 0, out, 0, n);
        return out;
    }

    private Vec3d targetPosition(UUID id, String world) {
        if (id == null) {
            return null;
        }
        Entity entity = Bukkit.getEntity(id);
        if (entity == null || entity.getWorld() == null || !entity.getWorld().getName().equals(world)) {
            return null;
        }
        Location at = entity.getLocation();
        return new Vec3d(at.getX(), at.getY(), at.getZ());
    }

    // ------------------------------------------------------------------ look

    /**
     * Where the head goes: the speaker or order-giver first, then a player who
     * comes within 8 blocks (a 2-4 second glance, head only), then - when
     * standing - an unhurried look around. While walking with nothing to look
     * at, the body leaves the head to follow the movement.
     */
    private void chooseLook(NullBody body, Mind mind, Player handle, String world, Vec3d pos, Intent intent) {
        if (intent.look != null) {
            return;
        }
        Vec3d eye = new Vec3d(pos.x(), pos.y() + 1.62D, pos.z());
        if (mind.glancedAt != null && now < mind.attentionUntil) {
            Entity entity = Bukkit.getEntity(mind.glancedAt);
            if (entity != null && entity.getWorld() != null && entity.getWorld().getName().equals(world)) {
                Location at = entity instanceof LivingEntity
                        ? ((LivingEntity) entity).getEyeLocation() : entity.getLocation();
                intent.look = new Vec3d(at.getX(), at.getY(), at.getZ());
                intent.lookHeadOnly = mind.attentionHeadOnly;
                return;
            }
            if (mind.attention != null) {
                intent.look = mind.attention;
                intent.lookHeadOnly = true;
                return;
            }
        }
        if (mind.attention != null && now < mind.attentionUntil && mind.glancedAt == null && !mind.attentionIsScan) {
            intent.look = mind.attention;
            intent.lookHeadOnly = mind.attentionHeadOnly;
            return;
        }
        if (now >= mind.nextPlayerGlance) {
            Object watcher = nearestWatcher(world, eye, body);
            if (watcher instanceof Player) {
                mind.glancedAt = ((Player) watcher).getUniqueId();
                mind.attention = null;
                mind.attentionIsScan = false;
                mind.attentionUntil = now + 40 + random.nextInt(41);
                mind.attentionHeadOnly = true;
                mind.nextPlayerGlance = mind.attentionUntil + 60 + random.nextInt(100);
                Location at = ((Player) watcher).getEyeLocation();
                intent.look = new Vec3d(at.getX(), at.getY(), at.getZ());
                intent.lookHeadOnly = true;
                return;
            }
            if (watcher instanceof Vec3d) {
                mind.glancedAt = null;
                mind.attention = (Vec3d) watcher;
                mind.attentionIsScan = false;
                mind.attentionUntil = now + 40 + random.nextInt(41);
                mind.attentionHeadOnly = true;
                mind.nextPlayerGlance = mind.attentionUntil + 60 + random.nextInt(100);
                intent.look = mind.attention;
                intent.lookHeadOnly = true;
                return;
            }
            mind.nextPlayerGlance = now + 10;
        }
        if (mind.attention != null && now < mind.attentionUntil && mind.attentionIsScan) {
            intent.look = mind.attention;
            intent.lookHeadOnly = true;
            return;
        }
        if (!intent.moving() && v3 != null && v3.idleBehaviour() && now >= mind.nextScanTick) {
            double yaw = Math.toRadians(body.bodyYaw() + (random.nextDouble() * 140.0D - 70.0D));
            mind.glancedAt = null;
            mind.attention = new Vec3d(eye.x() - Math.sin(yaw) * 5.0D, eye.y() + random.nextDouble() * 1.6D - 1.0D,
                    eye.z() + Math.cos(yaw) * 5.0D);
            mind.attentionUntil = now + 30 + random.nextInt(30);
            mind.attentionHeadOnly = true;
            mind.attentionIsScan = true;
            mind.nextScanTick = now + 60 + random.nextInt(80);
            intent.look = mind.attention;
            intent.lookHeadOnly = true;
        }
    }

    /** The nearest real player (or self-test stand-in) within glance range, or null. */
    private Object nearestWatcher(String world, Vec3d eye, NullBody self) {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        Player best = null;
        double bestDist = PLAYER_GLANCE_RANGE;
        for (Player player : w.getPlayers()) {
            if (plugin.adapter() != null && plugin.adapter().isNullEntity(player.getUniqueId())) {
                continue; // other Nulls are not "players nearby"
            }
            double d = player.getLocation().toVector().distance(new org.bukkit.util.Vector(eye.x(), eye.y(), eye.z()));
            if (d <= bestDist) {
                bestDist = d;
                best = player;
            }
        }
        if (best != null) {
            return best;
        }
        Vec3d bestPoint = null;
        for (Vec3d point : extraWatchers) {
            double d = point.distanceTo(eye);
            if (d <= bestDist) {
                bestDist = d;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    // ------------------------------------------------------------- idle life

    private void updateIdle(Mind mind, Vec3d pos, boolean active) {
        if (active || (mind.lastPos != null && mind.lastPos.distanceTo(pos) > 0.08D)) {
            mind.idleSince = now;
            mind.resting = false;
        }
    }

    /** Resting, eating and raising a shield when something hostile is close. */
    private void idleLife(NullBody body, Mind mind, Player handle, String world, Vec3d pos, Intent intent) {
        if (v3 != null && now - mind.idleSince > v3.restAfterIdleTicks()) {
            mind.resting = true;
            intent.sneak = true; // crouched, resting
        }
        if (handle == null) {
            return;
        }
        maybeEat(body, mind, handle);
        if (v3 != null && v3.shields() && !mind.eating()) {
            boolean threat = false;
            for (Entity near : handle.getNearbyEntities(5.0D, 3.0D, 5.0D)) {
                if (near instanceof Monster && !near.isDead()) {
                    threat = true;
                    break;
                }
            }
            raiseShield(handle, mind, threat);
        }
    }

    /**
     * Eats a golden apple (or drinks healing) when health drops below
     * {@code nulls.eat-below-health}: the item is really selected and really
     * consumed through vanilla's item-use countdown.
     */
    boolean maybeEat(NullBody body, Mind mind, Player handle) {
        if (handle == null || mind.eating() || now < mind.nextEatAllowed || v3 == null) {
            return false;
        }
        double max = maxHealth(handle);
        if (max <= 0.0D || body.health() >= max * v3.eatBelowHealth()) {
            return false;
        }
        PlayerInventory inv = handle.getInventory();
        int slot = -1;
        for (Material food : new Material[] {Material.GOLDEN_APPLE, Material.ENCHANTED_GOLDEN_APPLE, Material.POTION}) {
            slot = Bodies.find(inv, food);
            if (slot >= 0) {
                break;
            }
        }
        if (slot < 0) {
            mind.nextEatAllowed = now + 200;
            return false;
        }
        if (handle.isHandRaised()) {
            handle.clearActiveItem();
        }
        mind.slotBeforeEating = inv.getHeldItemSlot();
        Bodies.hold(handle, slot);
        handle.startUsingItem(EquipmentSlot.HAND);
        mind.eatingUntil = now + 45;
        mind.nextEatAllowed = now + 120;
        mind.shieldUp = false;
        return true;
    }

    private void finishEating(Mind mind, Player handle) {
        if (!mind.eating()) {
            return;
        }
        boolean stillUsing = handle != null && handle.isHandRaised()
                && handle.getActiveItem() != null && !handle.getActiveItem().getType().isAir();
        if (now >= mind.eatingUntil || !stillUsing) {
            mind.eatingUntil = -1L;
            if (handle != null && mind.slotBeforeEating >= 0 && mind.slotBeforeEating < 9) {
                handle.getInventory().setHeldItemSlot(mind.slotBeforeEating);
            }
            mind.slotBeforeEating = -1;
        }
    }

    /** Raises or lowers the offhand shield. */
    void raiseShield(Player handle, Mind mind, boolean up) {
        if (handle == null) {
            return;
        }
        ItemStack offhand = handle.getInventory().getItemInOffHand();
        boolean hasShield = offhand != null && offhand.getType() == Material.SHIELD
                && handle.getCooldown(Material.SHIELD) <= 0;
        if (up && hasShield && !mind.eating()) {
            if (!handle.isHandRaised()) {
                handle.startUsingItem(EquipmentSlot.OFF_HAND);
            }
            mind.shieldUp = true;
        } else if (mind.shieldUp) {
            if (handle.isHandRaised() && handle.getActiveItem() != null
                    && handle.getActiveItem().getType() == Material.SHIELD) {
                handle.clearActiveItem();
            }
            mind.shieldUp = false;
        }
    }

    static double maxHealth(Player handle) {
        try {
            AttributeInstance attribute = handle.getAttribute(Attribute.MAX_HEALTH);
            return attribute == null ? 20.0D : attribute.getValue();
        } catch (Throwable t) {
            return 20.0D;
        }
    }

    /** Each Null walks at its own pace: 90-110 % of a player's base speed. */
    private void applySpeedFactor(Mind mind, Player handle) {
        if (mind.speedApplied || handle == null) {
            return;
        }
        mind.speedApplied = true;
        if (v3 != null && !v3.speedVariance()) {
            return;
        }
        mind.speedFactor = 0.9D + random.nextDouble() * 0.2D;
        try {
            AttributeInstance speed = handle.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) {
                speed.setBaseValue(0.1D * mind.speedFactor);
            }
        } catch (Throwable t) {
            mind.speedFactor = 1.0D;
        }
    }

    // --------------------------------------------------------------- combat

    /** A Null was hit: it (and squad mates close by) may fight back. */
    public void noteAttacked(NullBody victim, LivingEntity attacker) {
        if (v3 == null || !v3.combatEnabled() || !v3.retaliate() || attacker == null) {
            return;
        }
        if (plugin.adapter() != null && plugin.adapter().isNullEntity(attacker.getUniqueId())
                && sameOwner(victim, plugin.adapter().bodyOf(attacker.getUniqueId()))) {
            return; // friendly fire is not a reason to start a brawl
        }
        Mind mind = mind(victim);
        if (mind != null && victim.health() > 0) {
            startFight(victim, mind, attacker);
        }
        UUID owner = plugin.squads() == null ? null : plugin.squads().ownerOf(victim);
        if (owner != null) {
            Location at = attacker.getLocation();
            for (NullBody mate : plugin.squads().membersOf(owner)) {
                Location mateAt = Bodies.location(mate);
                if (mate != victim && mateAt != null && mateAt.getWorld() == at.getWorld()
                        && mateAt.distanceSquared(at) < 16.0D * 16.0D) {
                    Mind mateMind = mind(mate);
                    if (mateMind != null && mateMind.combatTarget == null) {
                        startFight(mate, mateMind, attacker);
                    }
                }
            }
        }
    }

    /** A player was hit: their Nulls nearby may defend them. */
    public void noteOwnerAttacked(UUID owner, LivingEntity attacker) {
        if (v3 == null || !v3.combatEnabled() || !v3.retaliate() || plugin.squads() == null || attacker == null) {
            return;
        }
        if (plugin.adapter() != null && plugin.adapter().isNullEntity(attacker.getUniqueId())) {
            NullBody attackerBody = plugin.adapter().bodyOf(attacker.getUniqueId());
            UUID attackerOwner = attackerBody == null ? null : plugin.squads().ownerOf(attackerBody);
            if (owner.equals(attackerOwner)) {
                return;
            }
        }
        Location at = attacker.getLocation();
        for (NullBody body : plugin.squads().membersOf(owner)) {
            Location bodyAt = Bodies.location(body);
            if (bodyAt != null && bodyAt.getWorld() == at.getWorld() && bodyAt.distanceSquared(at) < 16.0D * 16.0D) {
                Mind mind = mind(body);
                if (mind != null && mind.combatTarget == null && mayTarget(body, attacker)) {
                    startFight(body, mind, attacker);
                }
            }
        }
    }

    private void startFight(NullBody body, Mind mind, LivingEntity attacker) {
        if (!mayTarget(body, attacker)) {
            return;
        }
        if (mind.combatTarget == null || !mind.combatTarget.equals(attacker.getUniqueId())) {
            mind.combatTarget = attacker.getUniqueId();
            if (plugin.chatGate() != null) {
                plugin.chatGate().event("combat.retaliate", "name", body.profileName(), "target", attacker.getName());
            }
        }
        mind.combatUntil = now + 20L * 30L;
    }

    /**
     * The one rule that decides whether a Null may ever fight something.
     *
     * <p><b>P-04.</b> The totem/horn holder is the sole commander and the army
     * never turns on him: not in retaliation, not by order, not because he is
     * the nearest target, not with a bow. Squad mates and the Commander are
     * exempt too, and so is anyone on {@code policy.protected}. Every path that
     * sets a combat target - retaliation, the "nearest hostile" scan, a chat
     * order, {@code /null order attack} - goes through this method.</p>
     *
     * @param attacker the Null that would be doing the fighting (a real player
     *                 passes too, so the same rule can be asked about an order)
     * @param target   what it would fight
     */
    public boolean mayTarget(Entity attacker, Entity target) {
        return mayTarget(attacker, target, null);
    }

    /**
     * As {@link #mayTarget(Entity, Entity)}, for the case where the squad of a
     * Null body is already known.
     */
    public boolean mayTarget(NullBody body, Entity target) {
        return mayTarget(body == null ? null : Bodies.player(body), target, body);
    }

    private boolean mayTarget(Entity attacker, Entity target, NullBody body) {
        if (target == null || target.isDead() || attacker == null) {
            return false;
        }
        UUID id = target.getUniqueId();
        if (attacker.getUniqueId().equals(id)) {
            return false; // nobody fights itself
        }
        if (plugin.adapter() == null) {
            return false;
        }
        NullBody self = body != null ? body : (isNullEntity(attacker) ? plugin.adapter().bodyOf(attacker.getUniqueId()) : null);

        // 1. Never the Commander.
        if (plugin.commander() != null && plugin.commander().body() != null
                && id.equals(plugin.commander().body().uuid())) {
            return false;
        }
        String name = target.getName();
        if (plugin.commander() != null && plugin.commander().commanderName() != null
                && plugin.commander().commanderName().equalsIgnoreCase(name)) {
            return false;
        }
        // 2. Never a squad mate: another Null with the same owner.
        if (isNullEntity(target)) {
            NullBody other = plugin.adapter().bodyOf(id);
            if (self != null && other != null) {
                UUID mine = plugin.squads() == null ? null : plugin.squads().ownerOf(self);
                UUID theirs = plugin.squads() == null ? null : plugin.squads().ownerOf(other);
                if (mine != null && mine.equals(theirs)) {
                    return false;
                }
            }
        }
        // 3. Never the owner. A Null is the owner's; it does not hit back at him.
        if (isNullEntity(attacker) && plugin.squads() != null) {
            NullBody attackerBody = self;
            if (attackerBody != null) {
                UUID owner = plugin.squads().ownerOf(attackerBody);
                if (owner != null && owner.equals(id)) {
                    return false;
                }
            }
        }
        // 4. Never anyone the owner protected.
        if (v3 != null && v3.isProtected(name, id)) {
            return false;
        }
        return true;
    }

    private boolean isNullEntity(Entity entity) {
        return entity != null && plugin.adapter() != null && plugin.adapter().isNullEntity(entity.getUniqueId());
    }

    private boolean sameOwner(NullBody a, NullBody b) {
        if (a == null || b == null || plugin.squads() == null) {
            return false;
        }
        UUID oa = plugin.squads().ownerOf(a);
        UUID ob = plugin.squads().ownerOf(b);
        return oa != null && oa.equals(ob);
    }

    // ------------------------------------------------------------- unstack

    /**
     * Finds piles - more than two bodies within one block of each other - and
     * walks the extras out to free, safe cells nearby. Logged to the console.
     */
    private void unstack(String world, List<NullBody> bodies, Map<NullBody, Vec3d> positions) {
        if (bodies.size() < 3) {
            return;
        }
        List<double[]> points = new ArrayList<>();
        for (NullBody body : bodies) {
            Vec3d p = positions.get(body);
            points.add(new double[] {p.x(), p.y(), p.z()});
        }
        List<List<Integer>> groups = Separation.crowdedGroups(points, 1.0D, 2);
        if (groups.isEmpty()) {
            return;
        }
        List<Vec3d> taken = new ArrayList<>(positions.values());
        for (List<Integer> group : groups) {
            double cx = 0;
            double cy = 0;
            double cz = 0;
            for (int i : group) {
                cx += points.get(i)[0];
                cy += points.get(i)[1];
                cz += points.get(i)[2];
            }
            cx /= group.size();
            cy /= group.size();
            cz /= group.size();
            int moved = 0;
            for (int k = 2; k < group.size(); k++) {
                NullBody body = bodies.get(group.get(k));
                Mind mind = mind(body);
                if (mind == null || (mind.slideTo != null && now < mind.slideUntil)) {
                    continue;
                }
                Vec3d free = freeCell(world, new Vec3d(cx, Math.floor(cy), cz), taken);
                if (free != null) {
                    mind.slideTo = free;
                    mind.slideUntil = now + 80;
                    taken.add(free);
                    moved++;
                }
            }
            if (moved > 0) {
                unstackedTotal += moved;
                lastUnstack = moved + " near " + Math.round(cx) + "," + Math.round(cy) + "," + Math.round(cz);
                if (plugin.chatGate() != null) {
                    plugin.chatGate().event("null.unstacked", "count", moved, "where",
                            Math.round(cx) + "," + Math.round(cy) + "," + Math.round(cz) + " in " + world);
                }
            }
        }
    }

    private Vec3d freeCell(String world, Vec3d centre, List<Vec3d> taken) {
        for (double radius = 1.3D; radius <= 4.0D; radius += 0.9D) {
            int points = Math.max(6, (int) Math.round(2.0D * Math.PI * radius / 1.2D));
            int offset = random.nextInt(points);
            for (int i = 0; i < points; i++) {
                double angle = 2.0D * Math.PI * ((i + offset) % points) / points;
                Vec3d candidate = new Vec3d(Math.floor(centre.x() + Math.cos(angle) * radius) + 0.5D, centre.y(),
                        Math.floor(centre.z() + Math.sin(angle) * radius) + 0.5D);
                boolean clear = true;
                for (Vec3d other : taken) {
                    if (Math.hypot(other.x() - candidate.x(), other.z() - candidate.z()) < 1.1D
                            && Math.abs(other.y() - candidate.y()) < 1.5D) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) {
                    continue;
                }
                try {
                    if (plugin.adapter().isSpawnSafe(world, candidate)) {
                        return candidate;
                    }
                } catch (Throwable ignored) {
                    // try the next cell
                }
            }
        }
        return null;
    }

    /** Drops every mind (used when the plugin disables). */
    public void clear() {
        minds.clear();
        extraWatchers.clear();
    }

    /** One line per Null with an order or a fight, for /null status. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        Iterator<Map.Entry<UUID, Mind>> it = minds.entrySet().iterator();
        while (it.hasNext() && out.size() < 12) {
            Map.Entry<UUID, Mind> entry = it.next();
            Mind mind = entry.getValue();
            String what = mind.combatTarget != null ? "fighting"
                    : mind.order != null ? "order " + mind.order.verb.name().toLowerCase(java.util.Locale.ROOT)
                    : mind.resting ? "resting" : null;
            if (what != null) {
                Entity e = Bukkit.getEntity(entry.getKey());
                out.add((e == null ? entry.getKey().toString().substring(0, 8) : e.getName()) + ": " + what);
            }
        }
        return out;
    }
}
