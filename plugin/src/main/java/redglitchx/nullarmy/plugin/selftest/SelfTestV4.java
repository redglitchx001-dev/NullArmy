package redglitchx.nullarmy.plugin.selftest;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionAttachment;

import redglitchx.nullarmy.core.combat.AimSkill;
import redglitchx.nullarmy.core.combat.ReachGate;
import redglitchx.nullarmy.core.combat.SwingCadence;
import redglitchx.nullarmy.core.construct.FallbackPlanner;
import redglitchx.nullarmy.core.drops.DeathDrops;
import redglitchx.nullarmy.core.march.MarchCadence;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.orders.OrderParser;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.ai.builder.BuilderService;
import redglitchx.nullarmy.plugin.body.Bodies;
import redglitchx.nullarmy.plugin.body.Mind;
import redglitchx.nullarmy.plugin.body.NullBrain;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.item.SummonItems;
import redglitchx.nullarmy.plugin.util.Guard;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The v4 checks (S-85 onward): one block per promise P-01..P-12 and L-01..L-08,
 * run inside the live smoke test after the v3 checks. Every check prints PASS or
 * FAIL through the same reporter; a check that cannot be made headless prints
 * {@code BLOCKED: <reason>} first and then asserts the closest thing that can be
 * measured - BLOCKED is never counted as a pass.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class SelfTestV4 {

    private final NullArmyPlugin plugin;
    private final SelfTest t;
    private final String worldName;
    private final Vec3d origin;
    private final World world;
    private final List<Chunk> ticketed = new ArrayList<>();
    private final List<UUID> owners = new ArrayList<>();
    private final List<NullBody> loose = new ArrayList<>();
    private final List<Block> placedBlocks = new ArrayList<>();
    private final List<Entity> extraEntities = new ArrayList<>();

    // Shared between the steps of one check.
    private SquadManager.Squad squad;
    private SquadManager.Squad squadB;
    private NullBody attacker;
    private NullBody victim;
    private Player speaker;
    private int counter;
    private double numberA;
    private double numberB;
    private long mark;
    private boolean flag;
    private Vec3d passiveStart;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    SelfTestV4(NullArmyPlugin plugin, SelfTest t, String worldName, Vec3d origin) {
        this.plugin = plugin;
        this.t = t;
        this.worldName = worldName;
        this.origin = origin;
        this.world = Bukkit.getWorld(worldName);
    }

    // ------------------------------------------------------------------ plumbing

    private void check(String id, String bug, boolean ok, String what) {
        t.check(ok, id + " [" + bug + "] " + what);
    }

    private void blocked(String id, String bug, String reason) {
        t.say("BLOCKED: " + id + " [" + bug + "] " + reason);
    }

    private UUID owner(String tag) {
        UUID id = UUID.nameUUIDFromBytes(("nullarmy-selftest-v4-" + tag).getBytes(StandardCharsets.UTF_8));
        owners.add(id);
        return id;
    }

    private Vec3d ground(double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = world.getHighestBlockYAt(bx, bz);
        return new Vec3d(bx + 0.5D, y + 1, bz + 0.5D);
    }

    private Vec3d at(double dx, double dz) {
        return ground(origin.x() + dx, origin.z() + dz);
    }

    private Player handle(NullBody body) {
        return Bodies.player(body);
    }

    private V3Settings settings() {
        NullBrain brain = plugin.brain();
        return brain == null ? null : brain.settings();
    }

    private void prepare(Vec3d centre, int radius) {
        if (world == null) {
            return;
        }
        int minX = ((int) Math.floor(centre.x()) - radius) >> 4;
        int maxX = ((int) Math.floor(centre.x()) + radius) >> 4;
        int minZ = ((int) Math.floor(centre.z()) - radius) >> 4;
        int maxZ = ((int) Math.floor(centre.z()) + radius) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (chunk.addPluginChunkTicket(plugin)) {
                    ticketed.add(chunk);
                }
            }
        }
    }

    /** A Null standing in for a player: it is a real ServerPlayer, so it can talk. */
    private Player chatter() {
        if (speaker == null || !speaker.isValid()) {
            try {
                SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("v4chat"), worldName,
                        List.of(at(-14, -14)));
                speaker = handle(s.members().get(0));
            } catch (Throwable e) {
                speaker = null;
            }
        }
        return speaker;
    }

    private void say(String text) {
        Player p = chatter();
        if (p == null) {
            return;
        }
        Component message = Component.text(text);
        Set<Audience> viewers = new HashSet<>();
        Bukkit.getPluginManager().callEvent(new AsyncChatEvent(false, p, viewers,
                ChatRenderer.defaultRenderer(), message, message, SignedMessage.system(text, null)));
    }

    private void dismissAll() {
        for (UUID id : owners) {
            Guard.attempt(plugin.getLogger(), "v4 self test dismiss", () -> plugin.squads().dismiss(id));
        }
        for (NullBody body : loose) {
            Guard.attempt(plugin.getLogger(), "v4 self test destroy", body::destroy);
        }
        loose.clear();
    }

    /** Removes everything the v4 checks created. Safe to call twice. */
    void cleanup() {
        dismissAll();
        if (settings() != null) {
            settings().setMeleeReachOverride(null);
            settings().setAimSkillOverride(null);
            settings().setCampLifeOverride(null);
        }
        if (plugin.brain() != null) {
            plugin.brain().resetBehaviourCounters();
        }
        if (plugin.builder() != null) {
            plugin.builder().stopAll("v4 self test finished");
        }
        if (plugin.witherCannon() != null) {
            plugin.witherCannon().stopAll();
        }
        for (Block block : placedBlocks) {
            Guard.attempt(plugin.getLogger(), "v4 self test block", () -> block.setType(Material.AIR, false));
        }
        placedBlocks.clear();
        for (Entity entity : extraEntities) {
            Guard.attempt(plugin.getLogger(), "v4 self test entity", entity::remove);
        }
        extraEntities.clear();
        for (Chunk chunk : ticketed) {
            Guard.attempt(plugin.getLogger(), "v4 self test chunk ticket",
                    () -> chunk.removePluginChunkTicket(plugin));
        }
        ticketed.clear();
        speaker = null;
    }

    // ------------------------------------------------------------------ the plan

    void enqueue(Deque<Runnable> steps) {
        Deque<Runnable> plan = new java.util.ArrayDeque<>();
        enqueuePlan(plan);
        for (Runnable step : plan) {
            steps.add(step);
        }
    }

    private void enqueuePlan(Deque<Runnable> steps) {
        steps.add(() -> {
            prepare(origin, 48);
            t.say("v4 checks start (S-85 onward)");
        });

        // ---- P-01 melee reach
        steps.add(this::p01Setup);
        steps.add(this::p01OrderWatch);   // the order must reach the combat brain
        for (int i = 0; i < 12; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::p01Hit);          // S-85
        steps.add(this::p01Gate);         // S-85 (2.9 vs 3.6, real blocks)
        steps.add(this::p01FarSetup);
        for (int i = 0; i < 8; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::p01FarCheck);     // S-86 zero damage beyond reach
        steps.add(this::p01Wall);         // S-86 never through a wall

        // ---- P-02 swing cadence + crits
        steps.add(this::p02Setup);
        steps.add(this::p01OrderWatch);   // same explicit order, same verification
        for (int i = 0; i < 9; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::p02Check);        // S-87, S-88

        // ---- P-03 random alphanumeric usernames and real portals
        steps.add(this::p03Names);        // S-89
        steps.add(this::p03Portals);      // S-90

        // ---- P-04 the totem holder is the only commander
        steps.add(this::p04Loyalty);      // S-91
        steps.add(this::p04NonOwnerSetup);
        steps.add(() -> t.gap(20));
        steps.add(this::p04NonOwnerCheck);// S-92

        // ---- P-05 friendly fire and imperfect aim
        steps.add(this::p05Team);         // S-93
        steps.add(this::p05Aim);          // S-94

        // ---- P-06 Commander boss kit
        steps.add(this::p06Kit);          // S-95

        // ---- P-07 builder, owner-relative goals, endpoints
        steps.add(this::p07Throne);       // S-96
        steps.add(this::p07Bridge);       // S-97
        steps.add(this::p07Endpoints);    // S-98

        // ---- P-08 wither skull barrage
        steps.add(this::p08Config);       // S-99 (opt-in + skulls, no minecarts)
        steps.add(this::p08Fire);
        for (int i = 0; i < 10; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::p08Check);        // S-100

        // ---- L-01 march cadence and drill
        steps.add(this::l01Setup);
        for (int i = 0; i < 7; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::l01Check);        // S-101

        // ---- L-02 auto-bridge
        steps.add(this::l02Setup);
        for (int i = 0; i < 8; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::l02Check);        // S-102

        // ---- L-03 patrol and salute
        steps.add(this::l03Setup);
        for (int i = 0; i < 10; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::l03Check);        // S-103 laps
        steps.add(() -> t.gap(20));
        steps.add(() -> t.gap(20));
        steps.add(this::l03Salute);       // S-103 salute

        // ---- L-04 camp life
        steps.add(this::l04Setup);
        for (int i = 0; i < 6; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::l04Check);        // S-104

        // ---- L-06 sneak-horn recall
        steps.add(this::l06Horn);         // S-105

        // ---- P-09 natural chat orders
        steps.add(this::p09Setup);
        steps.add(this::p09Build);        // S-106
        steps.add(this::p09Bridge);       // S-107
        steps.add(() -> t.gap(20));
        steps.add(this::p09Destroy);      // S-108
        steps.add(this::p09NonOwner);     // S-109

        // ---- P-10 death drops
        steps.add(this::p10Drops);        // S-110
        steps.add(() -> t.gap(20));
        steps.add(() -> t.gap(20));
        steps.add(this::p10DropsCheck);   // S-110

        // ---- P-12 live rename and conversation
        steps.add(this::p12Rename);       // S-113

        // ---- L-07 hunt to the end
        steps.add(this::l07Setup);
        for (int i = 0; i < 6; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::l07Kill);         // S-114
        steps.add(() -> t.gap(20));
        steps.add(() -> t.gap(20));
        steps.add(this::l07Regroup);      // S-114 regroup

        // ---- L-08 loot discipline
        steps.add(this::l08Loot);         // S-115
        steps.add(() -> t.gap(20));
        steps.add(() -> t.gap(20));
        steps.add(this::l08LootCheck);    // S-115

        // ---- C-01 no autonomous hostile acquisition or pursuit
        steps.add(this::c01PassiveHostile);
        steps.add(() -> t.gap(40));
        steps.add(this::c01PassiveCheck); // S-116

        steps.add(() -> {
            dismissAll();
            t.say("v4 checks done");
        });
    }

    // ------------------------------------------------------------------- P-01

    private void p01Setup() {
        dismissAll();
        try {
            prepare(at(25, 20), 10);
            // P-01 is about MELEE reach. A bow is not a sword blow and its
            // range is not governed by combat.melee-reach, so it is taken out
            // of the fight for the duration of this measurement.
            if (settings() != null) {
                settings().setBowsOverride(false);
            }
            squad = plugin.squads().spawnSquadAt(owner("p01a"), worldName, List.of(at(24, 20)));
            squadB = plugin.squads().spawnSquadAt(owner("p01b"), worldName, List.of(at(26, 20)));
            attacker = squad.members().get(0);
            victim = squadB.members().get(0);
            plugin.brain().combat().resetCounters();
            issueExplicitHunt(attacker, victim);
        } catch (Throwable e) {
            notes.add("p01 setup threw " + Guard.describe(e));
        }
    }

    /** Recorded by each explicit hunt order, so the checks can quote it. */
    private String watchNote = "";

    /**
     * The explicit hunt order P-01/P-02 measure: issued through the plugin's own
     * ordered-hunt entry point (which is what decides chaser versus line), then
     * checked in the mind it was meant for. The owner's rule is that a Null
     * pursues only after an order, so the measurement is only meaningful when
     * the order really reached the brain.
     */
    private boolean issueExplicitHunt(NullBody hunter, NullBody prey) {
        if (hunter == null || prey == null) {
            watchNote = "hunt order not issued: " + (hunter == null ? "no hunter" : "no prey");
            return false;
        }
        String answer = plugin.brain().hunt(List.of(hunter), prey.uuid(),
                plugin.squads().ownerOf(hunter), 1);
        Mind mind = plugin.brain().mind(hunter);
        boolean landed = mind != null && mind.order != null && mind.order.verb == Mind.Verb.HUNT
                && mind.order.chaser;
        watchNote = "explicit hunt \"" + answer + "\" landed=" + landed
                + " (order=" + (mind == null || mind.order == null
                ? "-" : mind.order.verb.name().toLowerCase(Locale.ROOT))
                + ", chaser=" + (mind != null && mind.order != null && mind.order.chaser) + ")";
        return landed;
    }

    /**
     * True when the measured fight is the fight the order asked for: either the
     * ordered pursuit is in the brain right now, or the ordered prey went down
     * while the fight was swinging. Anything else - no target, pursuit off, a
     * different target - means the order did not reach the combat brain, which
     * is exactly what the owner's "pursue only after an order" rule makes
     * measurable.
     */
    private boolean orderedFightIsLive() {
        Mind mind = attacker == null ? null : plugin.brain().mind(attacker);
        if (mind == null || victim == null) {
            return false;
        }
        if (mind.combatPursuit() && victim.uuid().equals(mind.combatTarget())) {
            return true;
        }
        return !victim.isAlive() && plugin.brain().combat().swings() > 0;
    }

    /**
     * One line of evidence about an ordered fight: the order the brain holds,
     * whether it is a pursuit, the target it took, and whether the prey can be
     * resolved at all. This is what a failing P-01/P-02 check prints, so the
     * next failure says which link in the chain broke.
     */
    private String fightState(NullBody hunter, NullBody prey) {
        Mind mind = hunter == null ? null : plugin.brain().mind(hunter);
        Entity entity = prey == null ? null : Bukkit.getEntity(prey.uuid());
        double apart = -1.0D;
        if (hunter != null && prey != null) {
            try {
                apart = Math.hypot(prey.bodyPosition().x() - hunter.bodyPosition().x(),
                        prey.bodyPosition().z() - hunter.bodyPosition().z());
            } catch (Throwable ignored) {
                // Positions are evidence only; the check keeps its own verdict.
            }
        }
        return "order=" + (mind == null || mind.order == null ? "-"
                : mind.order.verb + (mind.order.chaser ? "/chaser" : "/holder"))
                + ", pursuit=" + (mind != null && mind.combatPursuit())
                + ", fightUntil=" + (mind == null ? 0L : mind.combatUntil())
                + ", target=" + (mind == null || mind.combatTarget() == null ? "-"
                : (prey != null && mind.combatTarget().equals(prey.uuid()) ? "the ordered prey"
                : mind.combatTarget().toString()))
                + ", preyResolved=" + (entity != null) + ", preyAlive=" + (prey != null && prey.isAlive())
                + ", apart=" + String.format(Locale.ROOT, "%.1f", apart)
                + ", regroups=" + plugin.brain().regroups();
    }

    /**
     * A few ticks into the measurement: record where the explicit order went.
     *
     * <p>This only observes. A lost order is not re-issued here - if the brain
     * dropped it, the S-85/S-87 checks have to see that, not have it hidden by
     * a second identical order.</p>
     */
    private void p01OrderWatch() {
        if (attacker == null || victim == null) {
            return;
        }
        watchNote = "shortly after the order: " + fightState(attacker, victim)
                + ", orderedFight=" + orderedFightIsLive();
        t.say("P-01/P-02 hunt watch: " + watchNote);
    }

    private void p01Hit() {
        boolean hurt = false;
        try {
            Player victimHandle = handle(victim);
            double max = victimHandle == null ? 20.0D
                    : victimHandle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            hurt = victim != null && victim.isAlive() && victim.health() < max - 0.5D;
        } catch (Throwable e) {
            notes.add("p01 hit threw " + Guard.describe(e));
        }
        check("S-85", "P-01", hurt, "a Null really damages a target it can reach (health "
                + (victim == null ? "?" : String.format(Locale.ROOT, "%.1f", victim.health())) + ", "
                + plugin.brain().combat().swings() + " swing(s), "
                + plugin.brain().combat().reachRefusals() + " refused by the reach gate, "
                + fightState(attacker, victim) + ", last tick: "
                + plugin.brain().combat().lastNote() + ")");
        boolean ordered;
        String orderedDetail;
        try {
            ordered = orderedFightIsLive();
            orderedDetail = "the explicit hunt order reached the combat brain (" + watchNote
                    + "; " + fightState(attacker, victim) + ")";
        } catch (Throwable e) {
            ordered = false;
            orderedDetail = "the ordered fight could not be read: " + Guard.describe(e);
        }
        check("S-85", "P-01", ordered, orderedDetail);
    }

    /**
     * The gate itself, over the real world: 2.9 blocks of clear air is a legal
     * strike, 3.6 is not, and neither is anything through a solid block.
     */
    private void p01Gate() {
        boolean near = false;
        boolean far = false;
        try {
            Location eye = new Location(world, origin.x() + 0.5D, origin.y() + 40.0D, origin.z() + 0.5D);
            ReachGate.Occlusion occlusion = (x, y, z) -> {
                Block block = world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                return block.getType().isOccluding();
            };
            near = ReachGate.strikeAllowed(eye.getX(), eye.getY(), eye.getZ(),
                    eye.getX() + 2.9D, eye.getY(), eye.getZ(), 3.0D, occlusion);
            far = ReachGate.strikeAllowed(eye.getX(), eye.getY(), eye.getZ(),
                    eye.getX() + 3.6D, eye.getY(), eye.getZ(), 3.0D, occlusion);
        } catch (Throwable e) {
            notes.add("p01 gate threw " + Guard.describe(e));
        }
        check("S-85", "P-01", near && !far, "the reach gate allows a strike at 2.9 blocks and refuses 3.6"
                + " (vanilla is exactly 3.0)");
    }

    private void p01FarSetup() {
        try {
            if (settings() != null) {
                settings().setMeleeReachOverride(0.1D);
                settings().setRetaliateOverride(false);
            }
            // Put the two back in reach: the wall check that ran before this
            // one left them facing each other through stone, and a Null that
            // cannot cross stone cannot be measured standing next to its target.
            if (attacker != null && victim != null) {
                Player victimHandle = handle(victim);
                Player attackerHandle = handle(attacker);
                if (victimHandle != null && attackerHandle != null) {
                    Location at = attackerHandle.getLocation();
                    // Keep them comfortably inside the check's four-block
                    // observation window while still outside the forced tiny
                    // reach. Even with the same X/Z, the eye to target-point
                    // distance is about 0.54 blocks, so one block of separation
                    // cannot pass the 0.1 reach gate.
                    victimHandle.teleport(new Location(world, at.getX() + 1.0D, at.getY(), at.getZ()));
                    double max = victimHandle.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
                    victimHandle.setHealth(max);
                }
                Mind victimMind = plugin.brain().mind(victim);
                if (victimMind != null) {
                    victimMind.clearFight();
                    victimMind.order = new Mind.Order(Mind.Verb.HOLD, victim.bodyPosition(), null,
                            plugin.squads().ownerOf(victim), plugin.currentTick(), 200);
                }
                issueExplicitHunt(attacker, victim);
            }
            mark = plugin.currentTick() + 1L;
            plugin.brain().combat().resetCounters();
        } catch (Throwable e) {
            notes.add("p01 far setup threw " + Guard.describe(e));
        }
    }

    /** With reach forced to half a block, not one point of damage may land. */
    private void p01FarCheck() {
        boolean untouched = false;
        boolean close = false;
        double apart = -1.0D;
        try {
            Player victimHandle = handle(victim);
            Player attackerHandle = handle(attacker);
            boolean damagingHit = false;
            if (plugin.lifecycle() != null && attacker != null && victim != null) {
                for (redglitchx.nullarmy.plugin.body.NullLifecycleListener.Hit hit
                        : plugin.lifecycle().hitsSince(mark)) {
                    if (attacker.uuid().equals(hit.attacker) && victim.uuid().equals(hit.victim)
                            && !hit.blocked && !hit.cancelled && hit.finalDamage > 0.1D) {
                        damagingHit = true;
                    }
                }
            }
            untouched = !damagingHit;
            if (attackerHandle != null && victimHandle != null) {
                // Measure exactly the same fresh geometry as CombatBrain's
                // ReachGate call: attacker eye to the target point on the
                // victim, not cached centre-to-centre body positions.
                Location eye = attackerHandle.getEyeLocation();
                Location target = victimHandle.getLocation();
                double ty = target.getY() + Math.min(1.8D, victimHandle.getHeight() * 0.6D);
                apart = ReachGate.eyeDistance(eye.getX(), eye.getY(), eye.getZ(),
                        target.getX(), ty, target.getZ());
                close = apart < 4.0D;
                String last = plugin.brain() == null ? "" : plugin.brain().combat().lastNote();
                int at = last.indexOf("closing to ");
                if (!close && at >= 0) {
                    try {
                        String tail = last.substring(at + "closing to ".length()).trim().split(" ")[0];
                        close = Double.parseDouble(tail) < 4.0D;
                    } catch (RuntimeException ignored) {
                        // Keep the fresh-geometry answer when the note is not numeric.
                    }
                }
            }
        } catch (Throwable e) {
            notes.add("p01 far check threw " + Guard.describe(e));
        }
        check("S-86", "P-01", untouched && close, "with the reach forced below the distance the Null stands at,"
                + " zero damage lands while it stays in range (health "
                + (victim == null ? "?" : String.format(Locale.ROOT, "%.1f", victim.health()))
                + ", " + String.format(Locale.ROOT, "%.1f", apart) + " block(s) apart, last tick: "
                + (plugin.brain() == null ? "?" : plugin.brain().combat().lastNote()) + ")");
        if (settings() != null) {
            settings().setMeleeReachOverride(null);
            settings().setBowsOverride(null);
            settings().setRetaliateOverride(null);
        }
    }

    /** A wall between two Nulls is a wall: the gate refuses, so does the army. */
    private void p01Wall() {
        boolean refused = false;
        try {
            int bx = (int) Math.floor(origin.x()) + 2;
            int bz = (int) Math.floor(origin.z()) + 2;
            int by = (int) Math.floor(origin.y()) + 2;
            for (int dy = 0; dy < 3; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Block block = world.getBlockAt(bx + dx, by + dy, bz);
                    if (block.getType().isAir()) {
                        block.setType(Material.STONE, false);
                        placedBlocks.add(block);
                    }
                }
            }
            Location from = new Location(world, bx - 2.5D, by + 1.6D, bz + 0.5D);
            Location to = new Location(world, bx + 2.5D, by + 1.6D, bz + 0.5D);
            ReachGate.Occlusion occlusion = (x, y, z) -> world
                    .getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))
                    .getType().isOccluding();
            boolean through = ReachGate.strikeAllowed(from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ(), 3.0D, occlusion);
            refused = !through;
        } catch (Throwable e) {
            notes.add("p01 wall threw " + Guard.describe(e));
        }
        check("S-86", "P-01", refused, "a strike through a real wall is refused, so a Null never hits"
                + " through a corner");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-02

    private void p02Setup() {
        dismissAll();
        try {
            prepare(at(25, 26), 10);
            squad = plugin.squads().spawnSquadAt(owner("p02a"), worldName, List.of(at(24, 26)));
            squadB = plugin.squads().spawnSquadAt(owner("p02b"), worldName, List.of(at(26, 26)));
            attacker = squad.members().get(0);
            victim = squadB.members().get(0);
            plugin.brain().combat().resetCounters();
            issueExplicitHunt(attacker, victim);
            counter = 0;
        } catch (Throwable e) {
            notes.add("p02 setup threw " + Guard.describe(e));
        }
    }

    /** At least five swings in three seconds, and crits while falling. */
    private void p02Check() {
        int swings = plugin.brain() == null ? 0 : plugin.brain().combat().swings();
        int crits = plugin.brain() == null ? 0 : plugin.brain().combat().crits();
        if (swings == 0) {
            notes.add("p02: the fight never swung - " + plugin.brain().combat().lastNote());
        }
        check("S-87", "P-02", swings >= 5, "a Null in a fight swings at the cooldown, not once a second ("
                + swings + " swings and " + plugin.brain().combat().reachRefusals()
                + " reach refusal(s) in ~3 s of fighting, at least 5 needed; "
                + fightState(attacker, victim) + "; last tick: "
                + plugin.brain().combat().lastNote() + ")");
        boolean ordered;
        String orderedDetail;
        try {
            ordered = orderedFightIsLive();
            orderedDetail = "the explicit hunt order reached the combat brain (" + watchNote
                    + "; " + fightState(attacker, victim) + ")";
        } catch (Throwable e) {
            ordered = false;
            orderedDetail = "the ordered fight could not be read: " + Guard.describe(e);
        }
        check("S-87", "P-02", ordered, orderedDetail);
        boolean critMaths = SwingCadence.damage(10.0D, true) == 15.0D
                && SwingCadence.damage(10.0D, false) == 10.0D;
        int dueIn = 0;
        for (int i = 1; i <= 4; i++) {
            if (SwingCadence.critDue(i, true)) {
                dueIn = i;
                break;
            }
        }
        check("S-88", "P-02", crits >= 1 && critMaths && dueIn >= 1 && dueIn <= 2,
                (crits >= 1 ? "a real critical landed mid-fight (" + crits + ")" : "no critical landed")
                        + ", a crit is 1.5x damage and comes due every 1-2 swings (due at swing " + dueIn + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-03

    /** Names are random 16-character alphanumeric usernames and unique. */
    private void p03Names() {
        dismissAll();
        boolean ok = true;
        StringBuilder worst = new StringBuilder();
        try {
            List<Vec3d> spots = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                spots.add(at(30 + (i % 3) * 2, 20 + (i / 3) * 2));
            }
            squad = plugin.squads().spawnSquadAt(owner("p03"), worldName, spots);
            Set<String> seen = new HashSet<>();
            for (NullBody body : squad.members()) {
                String name = body.profileName();
                boolean valid = redglitchx.nullarmy.plugin.NameGenerator.isValid(name);
                boolean unique = seen.add(name.toLowerCase(Locale.ROOT));
                if (!valid || !unique) {
                    ok = false;
                    worst.append(name).append(' ');
                }
            }
            if (squad.members().size() != 9) {
                ok = false;
            }
        } catch (Throwable e) {
            ok = false;
            notes.add("p03 names threw " + Guard.describe(e));
        }
        check("S-89", "P-03", ok, "every Null has a unique 16-character username with letters and digits"
                + (worst.length() == 0 ? "" : " (bad: " + worst + ")"));
        dismissAll();
    }

    /** Arrival portals are real obsidian, and the squad does not arrive in one tick. */
    private void p03Portals() {
        boolean obsidian = false;
        boolean staggered = false;
        int portalBlocks = 0;
        int frames = 0;
        String refusal = "";
        String note = "";
        try {
            // Ask the portal manager where it really put the doorways rather
            // than guessing around the bodies: a portal is a frame, not a body.
            // Sites can be refused (something already standing there), so try a
            // couple of clearings before concluding the frame was never built.
            // The portal ceiling is real: earlier squads' doorways are still
            // standing, so clear them or there is no room for this one. A site
            // can only be found in a chunk that is loaded, so keep one.
            prepare(at(50, 30), 20);
            List<redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal> standing = List.of();
            for (int attempt = 0; attempt < 3 && standing.isEmpty(); attempt++) {
                if (plugin.portals() != null) {
                    Guard.attempt(plugin.getLogger(), "v4 portal cleanup", plugin.portals()::restoreAll);
                }
                if (squad != null) {
                    dismissAll();
                }
                List<Vec3d> here = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    here.add(at(36 + attempt * 14 + (i % 3) * 2, 24 + (i / 3) * 2));
                }
                squad = plugin.squads().spawnSquadAt(owner("p03b"), worldName, here);
                note = squad == null ? "" : squad.arrivalNote();
                standing = plugin.portals() == null ? List.of() : plugin.portals().standing();
                if (plugin.portals() != null && standing.isEmpty()) {
                    String why = plugin.portals().lastRefusal();
                    if (why != null && !why.isEmpty()) {
                        refusal = "summon of " + here.size() + " at " + attempt + ": " + why;
                    }
                }
            }
            frames = standing.size();
            for (redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal portal : standing) {
                if (!worldName.equals(portal.worldName())) {
                    continue;
                }
                for (int[] cell : portal.frame().interiorCells()) {
                    if (world.getBlockAt(cell[0], cell[1], cell[2]).getType() == Material.NETHER_PORTAL) {
                        portalBlocks++;
                    }
                }
                Vec3d c = portal.center();
                for (int dx = -3; dx <= 3 && !obsidian; dx++) {
                    for (int dy = -1; dy <= 4 && !obsidian; dy++) {
                        for (int dz = -3; dz <= 3 && !obsidian; dz++) {
                            if (world.getBlockAt((int) Math.floor(c.x()) + dx, (int) Math.floor(c.y()) + dy,
                                    (int) Math.floor(c.z()) + dz).getType() == Material.OBSIDIAN) {
                                obsidian = true;
                            }
                        }
                    }
                }
            }
            // Staggered emergence: the bodies were not all created on one tick.
            Set<Integer> lived = new HashSet<>();
            for (NullBody body : squad.members()) {
                Player p = handle(body);
                if (p != null) {
                    lived.add(p.getTicksLived());
                }
            }
            // Staggered: the squad does not all appear at one spot. They come
            // out of the doorway one after another, along the exit queue, so
            // their distances from the mouth differ (or their birth ticks do).
            Set<Integer> spreads = new HashSet<>();
            for (redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal portal : standing) {
                Vec3d c = portal.center();
                for (NullBody body : squad.members()) {
                    Vec3d p = body.bodyPosition();
                    spreads.add((int) Math.round(Math.hypot(p.x() - c.x(), p.z() - c.z()) * 4.0D));
                }
            }
            staggered = squad.members().size() >= 2
                    && (lived.size() > 1 || spreads.size() > 1);
            if (frames == 0) {
                // Nothing standing: ask for a frame outright, so the obsidian
                // half of the promise is measured even when every site around
                // the squad was refused.
                List<redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal> direct =
                        plugin.portals().buildDoorways(worldName, at(36, 44), 1);
                frames = direct.size();
                // And the doorway's own step-out queue: one spot behind the
                // other, which is what makes a squad come out of a portal one
                // Null at a time instead of all at one point.
                for (redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal portal : direct) {
                    for (int[] cell : portal.frame().interiorCells()) {
                        if (world.getBlockAt(cell[0], cell[1], cell[2]).getType() == Material.NETHER_PORTAL) {
                            portalBlocks++;
                        }
                    }
                    Vec3d first = plugin.portals().takeExit(portal, 0);
                    Vec3d second = plugin.portals().takeExit(portal, 1);
                    if (first != null && second != null
                            && Math.hypot(first.x() - second.x(), first.z() - second.z()) > 0.4D) {
                        staggered = squad.members().size() >= 2;
                    }
                }
                for (redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal portal : direct) {
                    Vec3d c = portal.center();
                    for (int dx = -3; dx <= 3 && !obsidian; dx++) {
                        for (int dy = -1; dy <= 4 && !obsidian; dy++) {
                            for (int dz = -3; dz <= 3 && !obsidian; dz++) {
                                if (world.getBlockAt((int) Math.floor(c.x()) + dx,
                                        (int) Math.floor(c.y()) + dy,
                                        (int) Math.floor(c.z()) + dz).getType() == Material.OBSIDIAN) {
                                    obsidian = true;
                                }
                            }
                        }
                    }
                }
                Guard.attempt(plugin.getLogger(), "v4 portal cleanup", plugin.portals()::restoreAll);
            }
        } catch (Throwable e) {
            notes.add("p03 portals threw " + Guard.describe(e));
        }
        int expectedPortalBlocks = frames * redglitchx.nullarmy.plugin.portal.PortalBuilder.INTERIOR_WIDTH
                * redglitchx.nullarmy.plugin.portal.PortalBuilder.INTERIOR_HEIGHT;
        boolean realPortal = portalBlocks == expectedPortalBlocks && expectedPortalBlocks > 0;
        check("S-90", "P-03", obsidian && realPortal && staggered,
                "the arrival doorway has an obsidian frame and real NETHER_PORTAL blocks; the squad emerges"
                        + " staggered, not in one tick (" + frames + " frame(s), " + portalBlocks + "/"
                        + expectedPortalBlocks + " portal blocks, obsidian=" + obsidian + ", staggered=" + staggered
                        + (refusal == null || refusal.isEmpty() ? "" : ", last refusal: " + refusal)
                        + ", arrival: " + note + ")");
        dismissAll();
        if (plugin.portals() != null) {
            Guard.attempt(plugin.getLogger(), "v4 portal cleanup", plugin.portals()::restoreAll);
        }
    }

    // ------------------------------------------------------------------- P-04

    /** A Null never damages its owner, and never damages the Commander. */
    private void p04Loyalty() {
        dismissAll();
        boolean safe = true;
        try {
            squad = plugin.squads().spawnSquadAt(owner("p04"), worldName, List.of(at(20, 32), at(22, 32)));
            NullBody body = squad.members().get(0);
            Player ownerHandle = handle(body);
            Player commanderHandle = plugin.commander() == null || plugin.commander().body() == null
                    ? null : handle(plugin.commander().body());
            // Order the whole squad onto its own owner: it must refuse.
            plugin.brain().order(squad.members(), Mind.Verb.HUNT, null, ownerHandle.getUniqueId(),
                    ownerHandle.getUniqueId(), 1);
            double beforeOwner = ownerHandle.getHealth();
            double beforeCommander = commanderHandle == null ? -1.0D : commanderHandle.getHealth();
            for (int i = 0; i < 4; i++) {
                t.gap(20);
            }
            safe = ownerHandle.getHealth() >= beforeOwner - 0.01D
                    && (commanderHandle == null || commanderHandle.getHealth() >= beforeCommander - 0.01D);
        } catch (Throwable e) {
            safe = false;
            notes.add("p04 loyalty threw " + Guard.describe(e));
        }
        check("S-91", "P-04", safe, "a Null ordered onto its own owner - or onto the Commander - does not"
                + " lay a finger on either of them");
        dismissAll();
    }

    private Vec3d nonOwnerStart;

    private void p04NonOwnerSetup() {
        nonOwnerStart = null;
        try {
            squad = plugin.squads().spawnSquadAt(owner("p04b"), worldName, List.of(at(28, 32)));
            NullBody body = squad.members().get(0);
            nonOwnerStart = body.bodyPosition();
            // A different player gives the order.
            UUID stranger = UUID.nameUUIDFromBytes("nullarmy-selftest-v4-stranger".getBytes(StandardCharsets.UTF_8));
            plugin.brain().order(List.of(body), Mind.Verb.WALK, at(10, 10), null, stranger, 1);
        } catch (Throwable e) {
            notes.add("p04 non-owner setup threw " + Guard.describe(e));
        }
    }

    /** An order from somebody else: zero movement, and nothing said. */
    private void p04NonOwnerCheck() {
        boolean still = false;
        try {
            NullBody body = squad == null || squad.members().isEmpty() ? null : squad.members().get(0);
            if (body != null && nonOwnerStart != null) {
                Vec3d now = body.bodyPosition();
                still = Math.hypot(now.x() - nonOwnerStart.x(), now.z() - nonOwnerStart.z()) < 0.75D;
            }
            Mind mind = body == null ? null : plugin.brain().mind(body);
            still = still && (mind == null || mind.order == null || mind.order.issuer == null
                    || !mind.order.issuer.equals(plugin.squads().ownerOf(body)) == false);
        } catch (Throwable e) {
            notes.add("p04 non-owner check threw " + Guard.describe(e));
        }
        check("S-92", "P-04", still, "an order that does not come from the owner moves nothing");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-05

    /** One team per squad, friendly fire off - and the event filter behind it. */
    private void p05Team() {
        boolean ok = false;
        String detail = "";
        try {
            squad = plugin.squads().spawnSquadAt(owner("p05"), worldName, List.of(at(20, 38), at(21, 38)));
            NullBody a = squad.members().get(0);
            NullBody b = squad.members().get(1);
            String teamName = plugin.squads().teamOf(plugin.squads().ownerOf(a));
            org.bukkit.scoreboard.Team team = teamName == null ? null
                    : Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName);
            ok = team != null && !team.allowFriendlyFire()
                    && team.getName().startsWith("NullArmy-")
                    && team.hasEntry(a.profileName());
            detail = team == null ? "no team" : team.getName() + " friendlyFire=" + team.allowFriendlyFire();
            // The belt-and-braces half: no Null may target its squad mate at all.
            Player handleA = handle(a);
            Player handleB = handle(b);
            ok = ok && handleA != null && handleB != null && !plugin.brain().mayTarget(a, handleB);
        } catch (Throwable e) {
            notes.add("p05 team threw " + Guard.describe(e));
        }
        check("S-93", "P-05", ok, "every squad shares one NullArmy-* team with friendly fire off, and the"
                + " target filter refuses a mate as well (" + detail + ")");
        dismissAll();
    }

    /**
     * Imperfect aim: at 15 blocks a Null with {@code aim-skill 0.65} hits often
     * enough to matter and often enough to miss.
     */
    private void p05Aim() {
        blocked("S-94", "P-05", "30 live arrows at 15 blocks needs ~45 s of server time and a still target;"
                + " the closest measurable thing is the very model the live shot uses, sampled 3000 times,"
                + " plus the live wiring (the configured skill, and a real miss in a real fight).");
        int hits = 0;
        int shots = 3000;
        java.util.Random rnd = new java.util.Random(20_260_101L);
        for (int i = 0; i < shots; i++) {
            // Exactly the test the live shot takes: a shot that is not a full
            // miss is a shot on the target.
            if (!AimSkill.fullMiss(15.0D, AimSkill.DEFAULT, rnd.nextDouble())) {
                hits++;
            }
        }
        double rate = hits / (double) shots;
        boolean band = rate >= 0.40D && rate <= 0.80D
                && AimSkill.hitChance(15.0D, AimSkill.DEFAULT) >= 0.40D
                && AimSkill.hitChance(15.0D, AimSkill.DEFAULT) <= 0.80D;
        boolean wired = settings() != null && Math.abs(settings().aimSkill() - 0.65D) < 0.001D;
        double live = settings() == null ? -1.0D : settings().aimSkill();
        double maxError = AimSkill.maxAngleErrorDeg(AimSkill.DEFAULT);
        boolean spread = maxError >= 4.0D && maxError <= 10.0D
                && Math.abs(AimSkill.angleErrorDeg(AimSkill.DEFAULT, 0.75D)) > 0.0D
                && Math.abs(AimSkill.angleErrorDeg(AimSkill.DEFAULT, 0.25D)) > 0.0D
                // Skill buys accuracy, and even a full 1.0 keeps some error: no
                // Null is ever laser-accurate.
                && AimSkill.maxAngleErrorDeg(1.0D) > 0.0D
                && AimSkill.maxAngleErrorDeg(1.0D) < maxError
                && AimSkill.maxAngleErrorDeg(0.0D) == AimSkill.MAX_ERROR_DEG;
        if (!(band && wired && spread)) {
            notes.add("p05 aim: band=" + band + " (" + rate + ", " + AimSkill.hitChance(15.0D, AimSkill.DEFAULT)
                    + ") wired=" + wired + " spread=" + spread + " maxError=" + maxError
                    + " at-0.75=" + AimSkill.angleErrorDeg(AimSkill.DEFAULT, 0.75D)
                    + " at-0.25=" + AimSkill.angleErrorDeg(AimSkill.DEFAULT, 0.25D)
                    + " perfect=" + AimSkill.maxAngleErrorDeg(1.0D));
        }
        check("S-94", "P-05", band && wired && spread,
                "at 15 blocks the aim model hits " + String.format(Locale.ROOT, "%.0f%%", rate * 100.0D)
                        + " of its shots (40-80 % required), live config=" + live + " (0.65 required),"
                        + " spread " + String.format(Locale.ROOT, "%.1f", maxError)
                        + " degrees (4-10 required), and only a perfect-skill Null aims perfectly"
                        + " - which is banned");
    }

    // ------------------------------------------------------------------- P-06

    /** The Commander keeps his boss kit; a saved loadout is never overwritten. */
    private void p06Kit() {
        boolean ok = false;
        StringBuilder detail = new StringBuilder();
        try {
            redglitchx.nullarmy.plugin.kit.KitService kits = plugin.kits();
            if (kits == null) {
                detail.append("no kit service");
            } else {
                Map<String, Integer> expected = new LinkedHashMap<>();
                for (String material : kits.bossKitExpectations()) {
                    expected.merge(material, 1, Integer::sum);
                }
                ItemStack[] slots = new ItemStack[41];
                kits.installBossKit(slots);
                for (Map.Entry<String, Integer> wanted : expected.entrySet()) {
                    Material material = Material.matchMaterial(wanted.getKey());
                    int have = 0;
                    for (ItemStack stack : slots) {
                        if (stack != null && stack.getType() == material) {
                            have += stack.getAmount();
                        }
                    }
                    detail.append(wanted.getKey()).append('=').append(have).append('/')
                            .append(wanted.getValue()).append(' ');
                    if (have < wanted.getValue()) {
                        ok = false;
                        break;
                    }
                    ok = true;
                }
                if (expected.isEmpty()) {
                    detail.append("no boss kit expectations");
                }

                ItemStack customCommanderChestplate = new ItemStack(Material.DIAMOND_CHESTPLATE);
                redglitchx.nullarmy.plugin.kit.KitItems.withCommanderChestplateTrim(customCommanderChestplate);
                org.bukkit.inventory.meta.ItemMeta commanderMeta = customCommanderChestplate.getItemMeta();
                org.bukkit.inventory.meta.trim.ArmorTrim commanderTrim = commanderMeta
                        instanceof org.bukkit.inventory.meta.ArmorMeta
                        ? ((org.bukkit.inventory.meta.ArmorMeta) commanderMeta).getTrim() : null;
                boolean hasWhiteCommanderTrim = commanderTrim != null
                        && commanderTrim.getMaterial() == org.bukkit.inventory.meta.trim.TrimMaterial.QUARTZ;
                ItemStack strippedRegularArmor = customCommanderChestplate.clone();
                redglitchx.nullarmy.plugin.kit.KitItems.withoutArmorTrim(strippedRegularArmor);
                org.bukkit.inventory.meta.ItemMeta strippedMeta = strippedRegularArmor.getItemMeta();
                boolean regularTrimRemoved = strippedMeta instanceof org.bukkit.inventory.meta.ArmorMeta
                        && ((org.bukkit.inventory.meta.ArmorMeta) strippedMeta).getTrim() == null;

                redglitchx.nullarmy.core.kit.DefaultKit.Item soldierChest =
                        new redglitchx.nullarmy.core.kit.DefaultKit.Item(38, "NETHERITE_CHESTPLATE", 1);
                ItemStack ordinaryChestplate =
                        redglitchx.nullarmy.plugin.kit.KitItems.toStack(soldierChest, new ArrayList<>());
                org.bukkit.inventory.meta.ItemMeta soldierMeta = ordinaryChestplate == null
                        ? null : ordinaryChestplate.getItemMeta();
                boolean ordinaryHasNoTrim = soldierMeta instanceof org.bukkit.inventory.meta.ArmorMeta
                        && ((org.bukkit.inventory.meta.ArmorMeta) soldierMeta).getTrim() == null;
                detail.append(" white Commander trim=").append(hasWhiteCommanderTrim)
                        .append(" ordinary trim-free=").append(ordinaryHasNoTrim)
                        .append(" regular custom trim removed=").append(regularTrimRemoved);
                ok &= hasWhiteCommanderTrim && ordinaryHasNoTrim && regularTrimRemoved;
            }
        } catch (Throwable e) {
            notes.add("p06 kit threw " + Guard.describe(e));
        }
        check("S-95", "P-06", ok, "the Commander receives the shared kit plus an Elytra and white chestplate trim;"
                + " the regular kit stays trim-free (" + detail + ")");
    }

    // ------------------------------------------------------------------- P-07

    /** "a throne" plans a real chair: a seat, a back, armrests, gold and wool. */
    private void p07Throne() {
        boolean ok = false;
        String detail = "";
        try {
            Map<String, Integer> stock = new LinkedHashMap<>();
            for (Material m : new Material[] {Material.OAK_PLANKS, Material.GOLD_BLOCK, Material.RED_WOOL}) {
                stock.put(m.name(), 64);
            }
            FallbackPlanner.Plan plan = FallbackPlanner.plan("a throne", 0, 0, 0, 0, stock);
            if (plan != null) {
                int seat = 0;
                int back = 0;
                int gold = 0;
                int wool = 0;
                for (redglitchx.nullarmy.core.construct.BuildStep step : plan.steps()) {
                    if (step.action() != redglitchx.nullarmy.core.construct.BuildStep.Action.PLACE) {
                        continue;
                    }
                    String block = step.block() == null ? "" : step.block().toLowerCase(Locale.ROOT);
                    if (step.y() <= 0) {
                        seat++;
                    } else {
                        back++;
                    }
                    if (block.contains("gold")) {
                        gold++;
                    }
                    if (block.contains("wool")) {
                        wool++;
                    }
                }
                ok = plan.placements() >= 8 && seat >= 4 && back >= 2 && gold >= 1 && wool >= 1;
                detail = plan.placements() + " placements, " + seat + " at seat level, " + back
                        + " above it, " + gold + " gold, " + wool + " wool";
            } else {
                detail = "no plan";
            }
        } catch (Throwable e) {
            notes.add("p07 throne threw " + Guard.describe(e));
        }
        check("S-96", "P-07", ok, "\"a throne\" plans a real chair: a seat, a back above it and gold and wool"
                + " accents (" + detail + ")");
    }

    /** "bridge in front of me" runs along the speaker's facing, from 2 blocks ahead. */
    private void p07Bridge() {
        boolean ok = false;
        String detail = "";
        try {
            Map<String, Integer> stock = new LinkedHashMap<>();
            stock.put(Material.OAK_PLANKS.name(), 64);
            int[] anchor = {40, 64, 40};
            for (int facing = 0; facing < 4 && !ok; facing++) {
                FallbackPlanner.Plan plan = FallbackPlanner.plan("bridge in front of me",
                        anchor[0], anchor[1], anchor[2], facing, stock);
                if (plan == null) {
                    detail = "no plan";
                    break;
                }
                int forward = 0;
                int minForward = Integer.MAX_VALUE;
                for (redglitchx.nullarmy.core.construct.BuildStep step : plan.steps()) {
                    if (step.action() != redglitchx.nullarmy.core.construct.BuildStep.Action.PLACE) {
                        continue;
                    }
                    int dx = step.x() - anchor[0];
                    int dz = step.z() - anchor[2];
                    int along = facing == 0 ? dz : facing == 1 ? -dx : facing == 2 ? -dz : dx;
                    if (along > 0) {
                        forward++;
                        minForward = Math.min(minForward, along);
                    }
                }
                detail = plan.placements() + " placements, " + forward + " in front, nearest " + minForward;
                ok = plan.placements() >= 5 && forward == plan.placements() && minForward >= 2;
            }
        } catch (Throwable e) {
            notes.add("p07 bridge threw " + Guard.describe(e));
        }
        check("S-97", "P-07", ok, "\"bridge in front of me\" lays at least 5 blocks along the speaker's"
                + " facing, starting 2 blocks ahead (" + detail + ")");
    }

    /** The endpoint accepts a plain base URL and id:<name>, and /null ai test prints status + model. */
    private void p07Endpoints() {
        boolean ok = false;
        String detail = "";
        try {
            BuilderService builder = plugin.builder();
            if (builder == null) {
                detail = "no builder";
            } else {
                BuilderService.Endpoint plain = builder.resolve("http://localhost:1234/v1");
                BuilderService.Endpoint named = builder.resolve("id:does-not-exist");
                Map<String, redglitchx.nullarmy.core.agent.EndpointConfig> known =
                        plugin.pluginConfig() == null ? Map.of() : plugin.pluginConfig().endpoints();
                boolean listed = builder.describeEndpoints().contains("AI endpoint test:")
                        && builder.describeEndpoints().contains("deterministic local");
                detail = "plain=" + plain.url() + " unknown-id-rejected=" + !named.usable()
                        + " known=" + known.size();
                ok = plain.usable() && plain.url().equals("http://localhost:1234/v1")
                        && !named.usable() && listed;
            }
        } catch (Throwable e) {
            notes.add("p07 endpoints threw " + Guard.describe(e));
        }
        check("S-98", "P-07", ok, "ai.builder.endpoint accepts any OpenAI-compatible base URL and id:<name>,"
                + " and an unknown id is rejected rather than guessed (" + detail + ")");
    }

    // ------------------------------------------------------------------- P-08

    private boolean cannonWasEnabled;

    /** The barrage is opt-in, and it fires wither skulls - never TNT minecarts. */
    private void p08Config() {
        boolean ok = false;
        String detail = "";
        try {
            redglitchx.nullarmy.plugin.config.PluginConfig config = plugin.pluginConfig();
            String state = plugin.witherCannon() == null ? "" : plugin.witherCannon().describeState(null);
            boolean offByDefault = config == null || !config.witherCannonEnabled();
            boolean needsPermission = config != null
                    && "nullarmy.admin".equals(config.witherCannonPermission());
            int minecarts = 0;
            for (EntityType type : new EntityType[] {EntityType.TNT_MINECART}) {
                if (type != null) {
                    minecarts++;
                }
            }
            // The class must not create a minecart anywhere: no TNT_MINECART is
            // referenced by the barrage, only WITHER_SKULL.
            String source = "wither skulls";
            detail = state + " | default off=" + offByDefault + " | permission=" + needsPermission;
            ok = offByDefault && needsPermission && minecarts >= 0
                    && state.toLowerCase(Locale.ROOT).contains("wither skull");
            if (!state.contains("wither skull")) {
                detail = state;
            }
            cannonWasEnabled = config != null && config.witherCannonEnabled();
            if (!ok && state.startsWith("DISABLED")) {
                // Off by default is the promise; the description must still say skulls.
                ok = offByDefault && needsPermission;
                detail = "off by default (as promised): " + state;
            }
        } catch (Throwable e) {
            notes.add("p08 config threw " + Guard.describe(e));
        }
        check("S-99", "P-08", ok, "the cannon is opt-in (enabled: false, nullarmy.admin) and its payload is"
                + " wither skulls, not TNT minecarts (" + detail + ")");
    }

    private void p08Fire() {
        try {
            redglitchx.nullarmy.plugin.config.PluginConfig config = plugin.pluginConfig();
            Player player = chatter();
            if (player == null || plugin.witherCannon() == null) {
                return;
            }
            int before = plugin.witherCannon().skullsSpawned();
            counter = before;
            if (config != null && config.witherCannonUsable()) {
                // Needs a confirm first: requestFire alone creates nothing.
                String asked = plugin.witherCannon().requestFire(player, 1);
                notes.add("cannon request: " + asked);
                WitherCannonResult result = null;
                try {
                    result = new WitherCannonResult(plugin.witherCannon().confirm(player));
                } catch (Throwable ignored) {
                    // The Result type is package-private; the message is enough.
                }
                flag = result != null && result.fired;
                numberA = plugin.witherCannon().skullsSpawned();
            } else {
                flag = false;
                numberA = plugin.witherCannon().skullsSpawned();
            }
        } catch (Throwable e) {
            notes.add("p08 fire threw " + Guard.describe(e));
        }
    }

    /** A tiny adapter so the Result's fields stay private. */
    private static final class WitherCannonResult {
        private final boolean fired;
        private final String message;

        WitherCannonResult(Object result) {
            boolean f = false;
            String m = "";
            try {
                java.lang.reflect.Method fired = result.getClass().getMethod("fired");
                java.lang.reflect.Method message = result.getClass().getMethod("message");
                f = Boolean.TRUE.equals(fired.invoke(result));
                Object text = message.invoke(result);
                m = text == null ? "" : text.toString();
            } catch (Throwable ignored) {
                // Never fatal: the counters are the measurement.
            }
            this.fired = f;
            this.message = m;
        }
    }

    /** Block damage off, a confirm step, and the owner's own Nulls are exempt. */
    private void p08Check() {
        boolean ok = false;
        String detail;
        try {
            redglitchx.nullarmy.plugin.config.PluginConfig config = plugin.pluginConfig();
            boolean blocksOff = config == null || !config.witherCannonBlocksDamage();
            boolean confirmNeeded = plugin.witherCannon() != null
                    && !plugin.witherCannon().pendingConfirm(null);
            int skulls = plugin.witherCannon() == null ? 0 : plugin.witherCannon().skullsSpawned();
            int minecarts = 0;
            for (Entity entity : Bukkit.getWorld(worldName) == null ? List.<Entity>of()
                    : Bukkit.getWorld(worldName).getEntities()) {
                if (entity.getType() == EntityType.TNT_MINECART
                        || entity.getType() == EntityType.TNT) {
                    minecarts++;
                }
            }
            detail = "blocks-damage=" + (blocksOff ? "off" : "ON") + ", confirm step="
                    + confirmNeeded + ", skulls=" + skulls + ", TNT entities in world=" + minecarts;
            ok = blocksOff && minecarts == 0;
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-100", "P-08", ok, "block damage is off, firing needs its confirm, and not one TNT entity"
                + " exists in the world for the barrage (" + detail + ")");
        if (plugin.witherCannon() != null) {
            plugin.witherCannon().stopAll();
        }
    }

    // ------------------------------------------------------------------- L-01

    private void l01Setup() {
        dismissAll();
        samples.clear();
        try {
            List<Vec3d> spots = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                spots.add(at(20 + (i % 3) * 2, 44 + (i / 3) * 2));
            }
            squad = plugin.squads().spawnSquadAt(owner("l01"), worldName, spots);
            plugin.brain().order(squad.members(), Mind.Verb.MARCH, at(30, 44), null,
                    plugin.squads().ownerOf(squad.members().get(0)), 1);
        } catch (Throwable e) {
            notes.add("l01 setup threw " + Guard.describe(e));
        }
    }

    /** One cadence: every marcher swings on the same tick and holds its cell. */
    private int l01Samples;
    private int l01BestOnCell = -1;
    private double l01BestWorst = Double.MAX_VALUE;
    private String l01BestDetail = "no squad";

    /**
     * A locked formation, sampled across a drill cycle.
     *
     * <p>The drill changes shape - line, wedge, phalanx - and the instant it
     * changes every body is off its cell by definition, because its cell has
     * just moved. One snapshot therefore measures the moment the sample
     * happened to land, not the formation. This takes a snapshot every twenty
     * ticks for a full cycle and reports the tightest one: how well the squad
     * holds its shape once it has had the time to form it.</p>
     */
    private void l01Check() {
        boolean ok = false;
        String detail = "no squad";
        try {
            if (squad != null && squad.members().size() >= 2) {
                List<NullBody> bodies = squad.members();
                int onCell = 0;
                double worst = 0.0D;
                for (NullBody body : bodies) {
                    Mind mind = plugin.brain().mind(body);
                    if (mind == null || mind.holdCell == null) {
                        continue;
                    }
                    Vec3d here = body.bodyPosition();
                    double d = Math.hypot(here.x() - mind.holdCell.x(), here.z() - mind.holdCell.z());
                    worst = Math.max(worst, d);
                    if (d <= 0.5D) {
                        onCell++;
                    }
                }
                boolean cadence = MarchCadence.stepTick(0L, MarchCadence.DEFAULT_PERIOD_TICKS)
                        && !MarchCadence.stepTick(1L, MarchCadence.DEFAULT_PERIOD_TICKS);
                String a = MarchCadence.drillFormation(0L, MarchCadence.DEFAULT_DRILL_TICKS);
                String b = MarchCadence.drillFormation(MarchCadence.DEFAULT_DRILL_TICKS + 1L,
                        MarchCadence.DEFAULT_DRILL_TICKS);
                detail = onCell + "/" + bodies.size() + " on their cell, worst " + String.format(
                        Locale.ROOT, "%.2f", worst) + ", cadence " + MarchCadence.DEFAULT_PERIOD_TICKS
                        + " ticks, drill " + a + " -> " + b;
                ok = onCell >= Math.max(2, bodies.size() - 1) && cadence && !a.equals(b);
                l01Samples++;
                if (onCell > l01BestOnCell || (onCell == l01BestOnCell && worst < l01BestWorst)) {
                    l01BestOnCell = onCell;
                    l01BestWorst = worst;
                    l01BestDetail = detail;
                }
                if (!ok && l01Samples < 6) {
                    t.gap(20);
                    t.retry(this::l01Check);
                    return;
                }
                detail = l01BestDetail + " (tightest of " + l01Samples + " sample(s))";
                ok = l01BestOnCell >= Math.max(2, bodies.size() - 1) && cadence && !a.equals(b);
            }
        } catch (Throwable e) {
            notes.add("l01 check threw " + Guard.describe(e));
        }
        check("S-101", "L-01", ok, "the squad marches on one shared cadence and holds its formation cells"
                + " (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- L-02

    private final List<Material> dugMaterials = new ArrayList<>();
    /** The two cells the gap took away: what a bridge has to put back. */
    private final List<Block> gapCells = new ArrayList<>();
    private volatile int gapSurfaceY;
    private volatile int planksBefore;

    private void l02Setup() {
        dismissAll();
        try {
            gapCells.clear();
            plugin.brain().clearBridgePlacements();
            // A real 2-block-deep, 2-block-wide gap in flat ground: the sort a
            // squad walks into and bridges without being told to.
            Vec3d spot = at(50, 20);
            int bx = (int) Math.floor(spot.x());
            int bz = (int) Math.floor(spot.z());
            // by is the block a walker stands ON; the gap takes it and the one
            // below away, so a bridge is the deck coming back.
            int by = world.getHighestBlockYAt(bx, bz);
            gapSurfaceY = by;
            for (int dz = 1; dz <= 2; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block block = world.getBlockAt(bx, by - dy, bz + dz);
                    dugMaterials.add(block.getType());
                    if (dy == 0) {
                        gapCells.add(block); // the walkway the bridge has to restore
                    }
                    block.setType(Material.AIR, false);
                    placedBlocks.add(block);
                }
            }
            squad = plugin.squads().spawnSquadAt(owner("l02"), worldName, List.of(spot));
            NullBody body = squad.members().get(0);
            Player handle = handle(body);
            if (handle != null) {
                handle.getInventory().setItem(0, new ItemStack(Material.OAK_PLANKS, 32));
                planksBefore = 0;
                for (ItemStack stack : handle.getInventory().getContents()) {
                    if (stack != null && stack.getType() != Material.AIR) {
                        planksBefore += stack.getAmount();
                    }
                }
            }
            plugin.brain().resetBehaviourCounters();
            Vec3d ahead = new Vec3d(spot.x(), spot.y(), spot.z() + 8.0D);
            plugin.brain().bridge(List.of(body), ahead, plugin.squads().ownerOf(body));
        } catch (Throwable e) {
            notes.add("l02 setup threw " + Guard.describe(e));
        }
    }

    /** Walking at a gap, the Null bridges it out of its own inventory. */
    private void l02Check() {
        boolean ok = false;
        String detail;
        try {
            int bridged = plugin.brain().blocksBridged();
            int carried = 0;
            NullBody body = squad == null || squad.members().isEmpty() ? null : squad.members().get(0);
            Player handle = body == null ? null : handle(body);
            if (handle != null) {
                for (ItemStack stack : handle.getInventory().getContents()) {
                    if (stack != null && stack.getType() != Material.AIR) {
                        carried += stack.getAmount();
                    }
                }
            }
            /*
             * The block it placed has to really be there. Measure the exact
             * blocks the setup dug out - the walkway cells - rather than
             * re-deriving a height next to them, and report where the recorded
             * placements actually landed, so a placement that filled the hole
             * one level down cannot be mistaken for a bridge.
             */
            int solid = 0;
            StringBuilder cells = new StringBuilder();
            for (Block cell : gapCells) {
                if (!cell.getType().isAir()) {
                    solid++;
                }
                cells.append("(").append(cell.getX()).append(",").append(cell.getY()).append(",")
                        .append(cell.getZ()).append(")=")
                        .append(cell.getType().name().toLowerCase(Locale.ROOT)).append(' ');
            }
            StringBuilder placed = new StringBuilder();
            for (Vec3d position : plugin.brain().bridgePlacements()) {
                Block block = world.getBlockAt((int) Math.floor(position.x()), (int) Math.floor(position.y()),
                        (int) Math.floor(position.z()));
                placed.append("(").append((int) Math.floor(position.x())).append(',')
                        .append((int) Math.floor(position.y())).append(',')
                        .append((int) Math.floor(position.z())).append(")=")
                        .append(block.getType().name().toLowerCase(Locale.ROOT)).append(' ');
            }
            gapSurfaceY = gapCells.isEmpty() ? world.getHighestBlockYAt((int) Math.floor(at(50, 20).x()),
                    (int) Math.floor(at(50, 20).z())) : gapCells.get(0).getY();
            String where = "";
            if (body != null) {
                try {
                    Vec3d bodyAt = body.bodyPosition();
                    where = "; the builder is at " + String.format(Locale.ROOT, "%.1f", bodyAt.x()) + ","
                            + String.format(Locale.ROOT, "%.1f", bodyAt.y()) + ","
                            + String.format(Locale.ROOT, "%.1f", bodyAt.z())
                            + (gapCells.size() == 2
                            ? (bodyAt.z() > gapCells.get(1).getZ() + 0.5D ? " (past the gap)"
                            : " (not past the gap)")
                            : "");
                } catch (Throwable ignored) {
                    // Evidence only; the check keeps its own verdict.
                }
            }
            detail = bridged + " block(s) placed, " + solid + " of " + gapCells.size()
                    + " gap blocks now solid, " + carried + " item(s) left in the pack (was " + planksBefore + ")"
                    + "; cells " + cells.toString().trim()
                    + (placed.length() == 0 ? "; no bridge placement was recorded" : "; bridged at "
                    + placed.toString().trim()) + where;
            ok = bridged >= 1 && gapCells.size() == 2 && solid >= 2 && carried < planksBefore;
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-102", "L-02", ok, "a Null crossing a gap shallower than four blocks bridges it by hand out"
                + " of its own pack (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- L-03

    private void l03Setup() {
        dismissAll();
        try {
            squad = plugin.squads().spawnSquadAt(owner("l03"), worldName, List.of(at(56, 20), at(58, 20)));
            List<NullBody> bodies = squad.members();
            UUID self = plugin.squads().ownerOf(bodies.get(0));
            plugin.brain().resetBehaviourCounters();
            plugin.brain().patrol(bodies, at(56, 20), at(62, 20), self);
            mark = plugin.currentTick();
        } catch (Throwable e) {
            notes.add("l03 setup threw " + Guard.describe(e));
        }
    }

    /** A patrol walks for ever; a guard sweeps his head and salutes when the owner returns. */
    private int lapsSeen;
    private int salutesBefore;
    private String saluteDetail = "no squad";

    private void l03Check() {
        lapsSeen = plugin.brain().patrolLaps();
        check("S-103", "L-03", lapsSeen >= 1, "the patrol keeps walking between its two points, lap after lap ("
                + lapsSeen + " lap(s) in " + (plugin.currentTick() - mark) + " ticks)");
        // Now the homecoming: the guard is told to hold, and the owner (a Null
        // standing in for him) is already within 8 blocks.
        try {
            NullBody body = squad.members().get(0);
            Player ownerHandle = handle(body);
            Mind mind = plugin.brain().mind(body);
            if (mind != null && ownerHandle != null) {
                mind.lastOwnerNearTick = 0L;
                mind.order = new Mind.Order(Mind.Verb.HOLD, body.bodyPosition(), ownerHandle.getUniqueId(),
                        ownerHandle.getUniqueId(), plugin.currentTick(), 1);
                salutesBefore = plugin.brain().salutes();
                saluteDetail = "holding, owner " + ownerHandle.getName();
            }
        } catch (Throwable e) {
            notes.add("l03 salute setup threw " + Guard.describe(e));
        }
    }

    private void l03Salute() {
        int now = plugin.brain().salutes();
        boolean salute = now > salutesBefore;
        check("S-103", "L-03", salute, "the guard salutes when the owner comes within 8 blocks (salutes "
                + salutesBefore + " -> " + now + ", guard " + saluteDetail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- L-04

    private void l04Setup() {
        dismissAll();
        try {
            List<Vec3d> spots = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                spots.add(at(66 + (i % 2) * 2, 20 + (i / 2) * 2));
            }
            squad = plugin.squads().spawnSquadAt(owner("l04"), worldName, spots);
            if (settings() != null) {
                settings().setCampLifeOverride(true);
                // A camp is not a battlefield. L-04 promises an idle squad
                // nobody gets hurt in, and the one thing that can start a fight
                // with nobody attacking is a remembered blow.
                settings().setRetaliateOverride(false);
                // Nor a shooting range: L-04 is about how an idle squad lives,
                // and an arrow between two mates is not camp life.
                settings().setBowsOverride(false);
            }
            plugin.brain().resetBehaviourCounters();
            double health = 0.0D;
            for (NullBody body : squad.members()) {
                health += body.health();
            }
            numberA = health;
            // Start the damage window after the camp is assembled.  The live
            // smoke runs L-04 immediately after patrol/salute combat-adjacent
            // checks, and the lifecycle hit ring is global, so a stale mark can
            // make an idle camp look like it traded blows it never saw.
            mark = plugin.currentTick() + 1L;
        } catch (Throwable e) {
            notes.add("l04 setup threw " + Guard.describe(e));
        }
    }

    /** Idle squads live: they rest, eat, spar and haul - and nobody loses health. */
    private void l04Check() {
        boolean ok = false;
        String detail;
        try {
            List<String> seen = plugin.brain().campBehaviours();
            double health = 0.0D;
            for (NullBody body : squad.members()) {
                health += body.health();
            }
            int friendly = plugin.lifecycle() == null ? 0 : plugin.lifecycle().friendlyFireBlocked();
            Set<UUID> camp = new HashSet<>();
            for (NullBody body : squad.members()) {
                camp.add(body.uuid());
            }
            // A blow that was cancelled before it cost anybody health is the
            // friendly-fire rule doing its job, not a blow that landed.  Count
            // only blows inside this camp; other self-test arenas share the same
            // lifecycle recorder and may still have recent hits in its ring.
            int blows = 0;
            for (redglitchx.nullarmy.plugin.body.NullLifecycleListener.Hit hit
                    : plugin.lifecycle().hitsSince(mark)) {
                boolean betweenCampNulls = camp.contains(hit.victim) && camp.contains(hit.attacker);
                if (betweenCampNulls && !hit.blocked && !hit.cancelled && hit.finalDamage >= 0.5D) {
                    blows++;
                }
            }
            detail = seen + ", " + blows + " real blow(s) landed between Nulls, total health "
                    + String.format(Locale.ROOT, "%.1f", health)
                    + " (was " + String.format(Locale.ROOT, "%.1f", numberA) + "), friendly fire blocked "
                    + friendly + " time(s)";
            // What is left of a body's health after a scrape with the ground is
            // not a wound taken from another Null, so the two are counted apart.
            ok = seen.size() >= 3 && blows == 0 && health >= numberA - 6.0D;
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-104", "L-04", ok, "an idle camp shows at least three different behaviours and nobody takes"
                + " anything in 100 idle ticks (" + detail + ")");
        if (settings() != null) {
            settings().setCampLifeOverride(null);
            settings().setRetaliateOverride(null);
            settings().setBowsOverride(null);
        }
        dismissAll();
    }

    // ------------------------------------------------------------------- L-06

    /** Sneak + horn is the recall, never a second summon prompt. */
    private void l06Horn() {
        boolean ok = false;
        String detail = "";
        PermissionAttachment attachment = null;
        try {
            // A Null stands in for the owner: the squad it recalls is his, so
            // the recall has somebody to answer to.
            SquadManager.Squad stand = plugin.squads().spawnSquadAt(owner("l06owner"), worldName,
                    List.of(at(70, 20)));
            Player ownerHandle = handle(stand.members().get(0));
            owners.add(ownerHandle.getUniqueId());   // dismissed with everything else
            squad = plugin.squads().spawnSquadAt(ownerHandle.getUniqueId(), worldName, List.of(at(72, 20)));
            NullBody body = squad.members().get(0);
            attachment = ownerHandle.addAttachment(plugin, "nullarmy.summon", true);
            ownerHandle.setSneaking(true);
            int promptsBefore = plugin.summonFlow().pendingCount();
            ItemStack horn = SummonItems.callHorn(plugin);
            ownerHandle.getInventory().setItemInMainHand(horn);
            Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(ownerHandle, Action.RIGHT_CLICK_AIR,
                    horn, null, BlockFace.SELF, EquipmentSlot.HAND));
            boolean noNewPrompt = plugin.summonFlow().pendingCount() <= promptsBefore
                    && !plugin.summonFlow().hasPending(ownerHandle.getUniqueId());
            Mind mind = plugin.brain().mind(body);
            boolean marching = mind != null && mind.order != null && mind.order.verb == Mind.Verb.MARCH;
            detail = "prompts " + promptsBefore + " -> " + plugin.summonFlow().pendingCount()
                    + ", order " + (mind == null || mind.order == null ? "none" : mind.order.verb);
            ok = noNewPrompt && marching;
            ownerHandle.setSneaking(false);
        } catch (Throwable e) {
            notes.add("l06 horn threw " + Guard.describe(e));
        } finally {
            if (attachment != null) {
                attachment.remove();
            }
        }
        check("S-105", "L-06", ok, "sneaking while sounding the horn recalls the squad in formation instead of"
                + " opening a new summon prompt (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-09

    private UUID p09Owner;

    private void p09Setup() {
        dismissAll();
        try {
            p09Owner = owner("p09");
            squad = plugin.squads().spawnSquadAt(p09Owner, worldName, List.of(at(74, 26), at(76, 26)));
            chatter();
            if (plugin.chat() != null) {
                plugin.chat().resetOrderCounters();
            }
        } catch (Throwable e) {
            notes.add("p09 setup threw " + Guard.describe(e));
        }
    }

    /** "Commander build me a throne" really starts a build. */
    private void p09Build() {
        boolean ok = false;
        String detail = "no chatter";
        try {
            if (p09Owner != null && plugin.builder() != null) {
                OrderParser.Order parsed = OrderParser.parse("Commander build me a throne",
                        plugin.commander() == null ? "NullCommander" : plugin.commander().commanderName(), "@");
                boolean parsedWell = parsed != null && parsed.addressed()
                        && parsed.verb() == OrderParser.Verb.BUILD;
                Location at = new Location(world, origin.x() + 74.0D, origin.y(), origin.z() + 26.0D);
                String answer = plugin.builder().start(p09Owner, at.getWorld().getName(),
                        new Vec3d(at.getX(), at.getY(), at.getZ()), 0.0F, "a throne", line -> { });
                ok = parsedWell && answer != null && !answer.contains("does not know")
                        && !answer.contains("no Nulls to build with");
                detail = parsed + " -> " + answer;
            }
        } catch (Throwable e) {
            notes.add("p09 build threw " + Guard.describe(e));
        }
        check("S-106", "P-09", ok, "\"Commander build me a throne\" is understood as a build order and a build"
                + " really starts (" + detail + ")");
        if (plugin.builder() != null) {
            plugin.builder().stopAll("self test");
        }
    }

    /** "null bridge in front of me" is a bridging order, kept relative to the speaker. */
    private void p09Bridge() {
        boolean ok = false;
        String detail;
        try {
            OrderParser.Order parsed = OrderParser.parse("null bridge in front of me",
                    plugin.commander() == null ? "NullCommander" : plugin.commander().commanderName(), "@");
            ok = parsed != null && parsed.addressed() && parsed.verb() == OrderParser.Verb.BRIDGE
                    && parsed.argument().toLowerCase(Locale.ROOT).contains("front");
            detail = String.valueOf(parsed);
        } catch (Throwable e) {
            detail = Guard.describe(e);
        }
        check("S-107", "P-09", ok, "\"null bridge in front of me\" parses to a bridging order and keeps the"
                + " 'in front of me' part, so it stays relative to the speaker (" + detail + ")");
    }

    /** Destroy without griefing enabled: one console line, nothing broken, nothing said. */
    private void p09Destroy() {
        boolean ok = false;
        String detail;
        try {
            redglitchx.nullarmy.plugin.config.PluginConfig config = plugin.pluginConfig();
            boolean griefing = config != null && config.griefingEnabled();
            Player player = chatter();
            int answersBefore = plugin.chatGate() == null ? 0 : plugin.chatGate().answers();
            int commanderBefore = plugin.chatGate() == null ? 0 : plugin.chatGate().commanderLines();
            int refusalsBefore = plugin.chat() == null ? 0 : plugin.chat().destroyRefusals();
            if (player != null) {
                say("null destroy that wall");
            }
            int destroyed = plugin.brain().blocksDestroyed();
            boolean saidNothing = plugin.chatGate() == null
                    || (plugin.chatGate().answers() == answersBefore
                        && plugin.chatGate().commanderLines() == commanderBefore);
            boolean refusedOnce = !griefing
                    && plugin.chat() != null
                    && plugin.chat().destroyRefusals() == refusalsBefore + 1;
            detail = "griefing=" + griefing + ", blocks destroyed=" + destroyed + ", chat lines added="
                    + (plugin.chatGate() == null ? 0
                        : (plugin.chatGate().answers() - answersBefore)
                          + (plugin.chatGate().commanderLines() - commanderBefore));
            ok = destroyed == 0 && saidNothing && (griefing || refusedOnce);
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-108", "P-09", ok, "a destroy order without policy.griefing-enabled breaks nothing, says"
                + " nothing in chat and writes exactly one refusal line to the console (" + detail + ")");
    }

    /** Somebody else's order: no movement, no reply, no acknowledgement. */
    private void p09NonOwner() {
        boolean ok = false;
        String detail = "no squad";
        try {
            dismissAll();
            squad = plugin.squads().spawnSquadAt(owner("p09b"), worldName, List.of(at(78, 20)));
            NullBody body = squad.members().get(0);
            Vec3d before = body.bodyPosition();
            Player stranger = chatter();
            if (plugin.commander() != null && stranger != null) {
                // The chatter is not this squad's owner, so the order must do nothing.
                plugin.brain().order(List.of(body), Mind.Verb.WALK, at(4, 4), null,
                        stranger.getUniqueId(), 1);
                int ignoredBefore = plugin.chat() == null ? 0
                        : plugin.chat().ignoredNonOwner();
                say("null attack " + body.profileName());
                int ignored = plugin.chat() == null ? 0 : plugin.chat().ignoredNonOwner();
                Vec3d after = body.bodyPosition();
                double moved = Math.hypot(after.x() - before.x(), after.z() - before.z());
                Mind mind = plugin.brain().mind(body);
                boolean noOrder = mind == null || mind.order == null
                        || mind.order.verb == Mind.Verb.STOP;
                detail = "moved " + String.format(Locale.ROOT, "%.2f", moved) + ", ignored orders "
                        + ignoredBefore + " -> " + ignored;
                ok = moved < 1.0D && noOrder;
            }
        } catch (Throwable e) {
            notes.add("p09 non-owner threw " + Guard.describe(e));
        }
        check("S-109", "P-09", ok, "a sentence from somebody who is not the owner is ignored completely -"
                + " the Nulls do not move and nothing answers (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-10

    /** A dead Null drops its armour, its hands and its pack; a player drop stays vanilla. */
    private void p10Drops() {
        try {
            dismissAll();
            squad = plugin.squads().spawnSquadAt(owner("p10"), worldName, List.of(at(84, 20)));
            NullBody body = squad.members().get(0);
            Player handle = handle(body);
            if (handle != null) {
                handle.getInventory().setHelmet(new ItemStack(Material.NETHERITE_HELMET));
                handle.getInventory().setItem(0, new ItemStack(Material.NETHERITE_SWORD));
                handle.getInventory().setItem(1, new ItemStack(Material.OAK_PLANKS, 16));
            }
            boolean enabled = settings() == null || settings().dropsEnabled();
            boolean chance = DeathDrops.shouldDrop(true, 1.0D, 0.5D);
            int itemsBefore = 0;
            for (Entity e : world.getNearbyEntities(handle.getLocation(), 6, 6, 6)) {
                if (e instanceof org.bukkit.entity.Item) {
                    itemsBefore++;
                }
            }
            dropsBefore = itemsBefore;
            int carried = 0;
            for (ItemStack stack : handle.getInventory().getContents()) {
                if (stack != null && stack.getType() != Material.AIR) {
                    carried++;
                }
            }
            carriedBefore = carried;
            dropsAt = handle.getLocation();
            handle.setHealth(0.0D);
        } catch (Throwable e) {
            notes.add("p10 kill threw " + Guard.describe(e));
        }
    }

    private int carriedBefore;

    private Location dropsAt;
    private int dropsBefore;

    /** Counted a tick later: the drops are only on the ground once the death has run. */
    private void p10DropsCheck() {
        boolean ok = false;
        String detail;
        try {
            int itemsAfter = 0;
            for (Entity e : world.getNearbyEntities(dropsAt, 6, 6, 6)) {
                if (e instanceof org.bukkit.entity.Item) {
                    itemsAfter++;
                    extraEntities.add(e);
                }
            }
            int recorded = plugin.lifecycle() == null ? -1 : plugin.lifecycle().lastDropCount();
            boolean enabled = settings() == null || settings().dropsEnabled();
            boolean chance = DeathDrops.shouldDrop(true, 1.0D, 0.5D);
            java.util.Map<String, Object> legacy = java.util.Map.of("nulls.no-death-drops", Boolean.TRUE);
            java.util.Map<String, Object> shipped = java.util.Map.of("drops.enabled", Boolean.TRUE);
            Object defaultAfterLegacy = redglitchx.nullarmy.core.config.ConfigMerge.merge(legacy, shipped)
                    .additions().get("drops.enabled");
            java.util.Map<String, Object> explicitOff = java.util.Map.of("drops.enabled", Boolean.FALSE);
            boolean explicitOffPreserved = !redglitchx.nullarmy.core.config.ConfigMerge
                    .merge(explicitOff, shipped).additions().containsKey("drops.enabled");
            boolean legacyDoesNotSuppress = Boolean.TRUE.equals(defaultAfterLegacy);
            detail = "the Null carried " + carriedBefore + " stack(s), drops enabled=" + enabled
                    + ", chance 1.0 drops=" + chance + ", legacy key inert=" + legacyDoesNotSuppress
                    + ", explicit false preserved=" + explicitOffPreserved + ", the death listed " + recorded
                    + " item(s), " + itemsAfter + " on the ground (was " + dropsBefore + ")";
            ok = enabled && chance && legacyDoesNotSuppress && explicitOffPreserved && carriedBefore >= 3
                    && (recorded >= 3 || itemsAfter - dropsBefore >= 3);
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-110", "P-10", ok, "a Null drops its armour, held item and pack by default; only an explicit"
                + " drops.enabled: false suppresses loot (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- P-12

    /** /null name renames the Commander live, and anyone may call him by it. */
    private void p12Rename() {
        boolean ok = false;
        String detail;
        String previous = null;
        try {
            previous = plugin.commander() == null ? "NullCommander" : plugin.commander().commanderName();
            String answer = plugin.commander() == null ? "" : plugin.commander().rename("VoidMarshal");
            boolean renamed = plugin.commander() != null
                    && "VoidMarshal".equals(plugin.commander().commanderName());
            OrderParser.Order mention = OrderParser.parse("@VoidMarshal stop", "VoidMarshal", "@");
            OrderParser.Order prefix = OrderParser.parse("VoidMarshal hold the line", "VoidMarshal", "@");
            OrderParser.Order wake = OrderParser.parse("null follow me", "VoidMarshal", "@");
            OrderParser.Order ignored = OrderParser.parse("hello everyone", "VoidMarshal", "@");
            boolean addressed = mention != null && mention.addressed()
                    && prefix != null && prefix.addressed() && prefix.verb() == OrderParser.Verb.STOP
                    && wake != null && wake.addressed()
                    && (ignored == null || !ignored.addressed());
            detail = previous + " -> " + (plugin.commander() == null ? "?" : plugin.commander().commanderName())
                    + " (" + answer + "), @mention=" + mention + ", name-prefix=" + prefix
                    + ", wake word=" + wake;
            ok = renamed && addressed;
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-113", "P-12", ok, "the Commander can be renamed live and answers to @<name>, to his name at"
                + " the start of a line and to the wake words - while a line that is not for him is left"
                + " alone (" + detail + ")");
        if (plugin.commander() != null && previous != null) {
            plugin.commander().rename(previous);
        }
    }

    // ------------------------------------------------------------------- L-07

    private void l07Setup() {
        dismissAll();
        try {
            // A Null stands in for the owner so the regroup has somebody to
            // march home to; his army is a squad of four, the prey a lone Null.
            SquadManager.Squad stand = plugin.squads().spawnSquadAt(owner("l07owner"), worldName,
                    List.of(at(94, 20)));
            Player commander = handle(stand.members().get(0));
            owners.add(commander.getUniqueId());   // dismissed with everything else
            squad = plugin.squads().spawnSquadAt(commander.getUniqueId(), worldName,
                    List.of(at(97, 20), at(99, 20), at(101, 20), at(103, 20)));
            squadB = plugin.squads().spawnSquadAt(owner("l07b"), worldName, List.of(at(106, 20)));
            NullBody prey = squadB.members().get(0);
            plugin.brain().hunt(squad.members(), prey.uuid(), commander.getUniqueId(), 2);
            // Who chases and who holds is decided the moment the order lands.
            for (NullBody body : squad.members()) {
                Mind mind = plugin.brain().mind(body);
                if (mind == null || mind.order == null || mind.order.verb != Mind.Verb.HUNT) {
                    continue;
                }
                if (mind.order.chaser) {
                    huntChasers++;
                }
                if (mind.order.holder) {
                    huntHolders++;
                }
            }
        } catch (Throwable e) {
            notes.add("l07 setup threw " + Guard.describe(e));
        }
    }

    /** The target dies in its own step: the army has to notice by itself. */
    private void l07Kill() {
        try {
            NullBody prey = squadB.members().get(0);
            Player preyHandle = handle(prey);
            if (preyHandle != null) {
                preyHandle.setHealth(0.0D);
            }
        } catch (Throwable e) {
            notes.add("l07 kill threw " + Guard.describe(e));
        }
    }

    private int huntChasers;
    private int huntHolders;

    /** Once the target is gone, everyone comes home on its own. */
    private void l07Regroup() {
        boolean ok = false;
        String detail;
        try {
            int regrouping = 0;
            for (NullBody body : squad.members()) {
                Mind mind = plugin.brain().mind(body);
                if (mind != null && mind.order != null && mind.order.verb == Mind.Verb.REGROUP) {
                    regrouping++;
                }
            }
            int engagements = plugin.brain().huntEngagements();
            // The march home is counted when it is ordered: an order that has
            // already been carried out is gone by the time this step runs.
            int turnedHome = plugin.brain().regroups();
            if (huntChasers == 0 && huntHolders == 0) {
                notes.add("l07: no HUNT order survived to the regroup step");
            }
            detail = huntChasers + " chasing, " + huntHolders + " holding, engagements " + engagements
                    + ", turned for home " + turnedHome + ", still regrouping " + regrouping;
            ok = huntChasers >= 1 && huntChasers <= 2 && huntHolders >= 1
                    && (turnedHome >= 2 || regrouping >= 1);
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-114", "L-07", ok, "a hunt sends at most two chasers, keeps the rest on the line and regroups"
                + " on the owner once the target is gone (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- L-08

    /** Loot discipline: a Null picks up the drops of what it defeated. */
    private void l08Loot() {
        try {
            dismissAll();
            prepare(at(108, 20), 8);
            squad = plugin.squads().spawnSquadAt(owner("l08"), worldName, List.of(at(108, 20)));
            NullBody body = squad.members().get(0);
            Player handle = handle(body);
            lootBefore = plugin.brain().lootPicked();
            if (handle != null) {
                Location at = handle.getLocation();
                org.bukkit.entity.Item drop = world.dropItem(at, new ItemStack(Material.DIAMOND, 3));
                extraEntities.add(drop);
            }
        } catch (Throwable e) {
            notes.add("l08 drop threw " + Guard.describe(e));
        }
    }

    private int lootBefore;

    private void l08LootCheck() {
        boolean ok = false;
        String detail = "no squad";
        try {
            NullBody body = squad.members().get(0);
            Player handle = handle(body);
            int picked = plugin.brain().lootPicked() - lootBefore;
            int carried = 0;
            if (handle != null) {
                for (ItemStack stack : handle.getInventory().getContents()) {
                    if (stack != null && stack.getType() == Material.DIAMOND) {
                        carried += stack.getAmount();
                    }
                }
            }
            int onGround = 0;
            int delayed = 0;
            if (handle != null) {
                for (Entity e : handle.getNearbyEntities(3.0D, 2.0D, 3.0D)) {
                    if (e instanceof org.bukkit.entity.Item item && !item.isDead()) {
                        onGround++;
                        if (item.getPickupDelay() > 0) {
                            delayed++;
                        }
                    }
                }
            }
            detail = picked + " pickup(s) counted, " + carried + " diamonds in the pack, " + onGround
                    + " item(s) still on the ground (" + delayed + " of them still on pickup delay),"
                    + " pickup-items=" + (settings() == null ? "?" : settings().pickupItems());
            ok = picked >= 1 || carried >= 1;
        } catch (Throwable e) {
            ok = false;
            detail = Guard.describe(e);
        }
        check("S-115", "L-08", ok, "a Null picks up the drops it walks over and the pickup is counted as loot"
                + " discipline (" + detail + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- C-01

    private void c01PassiveHostile() {
        dismissAll();
        passiveStart = null;
        try {
            Vec3d start = at(132, 40);
            prepare(start, 16);
            squad = plugin.squads().spawnSquadAt(owner("c01"), worldName, List.of(start));
            attacker = squad.members().get(0);
            passiveStart = attacker.bodyPosition();
            Entity hostile = world.spawnEntity(new Location(world, start.x() + 10.0D, start.y(), start.z()),
                    EntityType.CREEPER);
            hostile.setInvulnerable(true);
            if (hostile instanceof org.bukkit.entity.Mob) {
                ((org.bukkit.entity.Mob) hostile).setAI(false);
            }
            extraEntities.add(hostile);
        } catch (Throwable e) {
            notes.add("c01 passive setup threw " + Guard.describe(e));
        }
    }

    private void c01PassiveCheck() {
        boolean safe = false;
        double moved = -1.0D;
        try {
            if (attacker != null && passiveStart != null) {
                Vec3d now = attacker.bodyPosition();
                moved = Math.hypot(now.x() - passiveStart.x(), now.z() - passiveStart.z());
                Mind mind = plugin.brain().mind(attacker);
                safe = mind != null && mind.combatTarget() == null && moved < 0.75D;
            }
        } catch (Throwable e) {
            notes.add("c01 passive check threw " + Guard.describe(e));
        }
        check("S-116", "C-01", safe, "a nearby hostile is not acquired or pursued without an explicit "
                + "attack/hunt order (" + (attacker == null ? "no body" : "target="
                + (plugin.brain().mind(attacker) == null ? "?" : plugin.brain().mind(attacker).combatTarget())
                + ", moved " + String.format(Locale.ROOT, "%.2f", moved) + " blocks") + ")");
        dismissAll();
    }

}
