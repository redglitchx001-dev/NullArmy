package redglitchx.nullarmy.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.portal.PortalPlan;
import redglitchx.nullarmy.core.squad.RoleAssignment;
import redglitchx.nullarmy.core.squad.SquadRole;
import redglitchx.nullarmy.plugin.portal.PortalBuilder;
import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.nav.BlockView;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.skin.SkinData;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns squads and their Nulls: creation, bookkeeping, objectives and cleanup.
 *
 * <p>Implements the commander rule from spec 3: exactly two Commanders for any
 * squad of two or more, with roles <b>stored</b> rather than re-rolled each
 * tick or restart.</p>
 *
 * <h2>What changed in this revision</h2>
 * <ul>
 *   <li><b>Spawning is now survivable.</b> Every spawn goes through a guarded
 *       call; a failure rolls the whole squad back, latches the NMS breaker and
 *       reports the real reason instead of throwing out of the command.</li>
 *   <li><b>Safe spots are searched, not assumed.</b> The old code demanded that
 *       every ring position around the summoner already be clear, which failed
 *       indoors; now each Null gets its own searched, collision-safe position
 *       and the player is told exactly how many made it.</li>
 *   <li><b>Objectives exist.</b> {@code /null follow|formation|guard|attack}
 *       store an objective that the tick loop turns into bounded steering
 *       forces. Nulls walk; there is still no pathfinding around walls, and the
 *       commands say so rather than pretending.</li>
 *   <li><b>Nothing is orphaned.</b> Dead Nulls are reaped, {@code dismissAll}
 *       is exception-safe per member, and the live count can never drift.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SquadManager implements Reloadable {

    /** The objective a squad is currently walking towards. */
    public enum Objective {
        /** Nothing stored: Nulls stand where they are. */
        NONE,
        /** Walk to the summoner and stop nearby. */
        FOLLOW,
        /** Walk to a formation slot around the summoner. */
        FORMATION,
        /** Hold position and face the nearest player. Never move. */
        GUARD,
        /** Walk towards a target player (combat itself lands in Phase 5). */
        ATTACK,
        /** Walk to a stored point. */
        DESTINATION
    }

    /** One squad belonging to one owner. */
    public static final class Squad {

        private final UUID owner;
        private final List<NullBody> members = new ArrayList<>();
        private final List<Integer> commanderIds = new ArrayList<>();
        private final String worldName;

        private Objective objective = Objective.NONE;
        private UUID targetId;
        private String targetLabel = "";
        private Vec3d point;
        private String formation = "line";
        /** How this squad fights: aggressive, balanced or defensive. */
        private String tactics = "balanced";
        /** How this squad arrived: portals used, and what could not be built. */
        private String arrivalNote = "";
        /** Every spawn that did not happen, with its real reason. */
        private final List<String> spawnFailures = new ArrayList<>();
        /** One stored role per member, index-aligned with {@link #members}. */
        private List<SquadRole> roles = Collections.emptyList();
        /** A held formation's anchor; null means "behind the target player". */
        private Vec3d formationAnchor;
        private float formationYaw;
        /** Member index -> formation cell index, nearest-first; recomputed when the shape changes. */
        private int[] formationAssignment;
        private String formationAssignmentKey = "";

        Squad(UUID owner, String worldName) {
            this.owner = owner;
            this.worldName = worldName;
        }

        public UUID owner() { return owner; }
        public String worldName() { return worldName; }
        public List<NullBody> members() { return Collections.unmodifiableList(members); }

        public Objective objective() { return objective; }
        public String targetLabel() { return targetLabel; }
        public String formation() { return formation; }
        public String tactics() { return tactics; }

        /** The stored destination, or null. */
        public Vec3d point() { return point; }

        /** The player the objective is about (follow, formation, attack), or null. */
        public UUID targetId() { return targetId; }

        /** A held formation's fixed anchor, or null when it follows its player. */
        public Vec3d formationAnchor() { return formationAnchor; }

        /** The facing a held formation is rotated by. */
        public float formationYaw() { return formationYaw; }

        /** Member index -> cell index of the current formation, or null when not computed yet. */
        public int[] formationAssignment() { return formationAssignment; }

        public String formationAssignmentKey() { return formationAssignmentKey; }

        public void setFormationAssignment(int[] assignment, String key) {
            this.formationAssignment = assignment;
            this.formationAssignmentKey = key == null ? "" : key;
        }

        /** How this squad walked in, for the summon message and {@code /null status}. */
        public String arrivalNote() { return arrivalNote; }

        /** Nulls that were requested but did not spawn, each with its reason. */
        public List<String> spawnFailures() {
            return Collections.unmodifiableList(new ArrayList<>(spawnFailures));
        }

        /** The stored role of one member, or null when the index is out of range. */
        public SquadRole roleOf(int index) {
            return index < 0 || index >= roles.size() ? null : roles.get(index);
        }

        /** The stored roles, index-aligned with {@link #members()}. */
        public List<SquadRole> roles() { return roles; }

        void setRoles(List<SquadRole> assigned) {
            this.roles = assigned == null ? Collections.emptyList() : assigned;
        }

        /** The designated commanders. Never more than two, and stable. */
        public List<NullBody> commanders() {
            List<NullBody> out = new ArrayList<>();
            for (NullBody body : members) {
                if (commanderIds.contains(body.id())) {
                    out.add(body);
                }
            }
            return out;
        }
    }

    private static final int COMMANDER_COUNT = 2;

    /** Walk speed cap handed to the adapter, in blocks per tick. */
    private static final double WALK_SPEED = 0.22;

    /** Stop distance for "come here" objectives. */
    private static final double ARRIVE_DISTANCE = 2.0;

    /**
     * Minimum distance between two planned spawn spots, in blocks.
     *
     * <p>A body is 0.6 wide, so anything under that puts two Nulls inside each
     * other - and the adapter now refuses a spot another entity already occupies,
     * which is what turned one tight doorway into a lost Null.</p>
     */
    private static final double MIN_SPOT_SPACING = 1.1;

    /** How close a Null must be to react to a greeting. */
    private static final double GREET_DISTANCE = 24.0;

    private final NullArmyPlugin plugin;
    private final Logger logger;
    private final VersionAdapter adapter;
    private final Map<UUID, List<Squad>> byOwner = new LinkedHashMap<>();

    private PluginConfig config;
    private Caps caps;

    private boolean shutdownRequested;
    private String lastArrivalNote = "";

    SquadManager(NullArmyPlugin plugin, VersionAdapter adapter, Caps caps, PluginConfig config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.adapter = adapter;
        this.caps = caps;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh == null) {
            return;
        }
        this.config = fresh;
        this.caps = fresh.caps();
    }

    // ------------------------------------------------------------------- spawning

    /**
     * Creates a squad of {@code count} Nulls for {@code owner}.
     *
     * @return the created squad
     * @throws IllegalStateException if a limit would be exceeded or no safe
     *     ground exists. On refusal nothing spawns - spec 3 forbids a partial
     *     surprise army, and the caller turns the message into chat.
     */
    public Squad createSquad(UUID owner, String worldName, Vec3d origin, int count) {
        List<Squad> existing = preflight(owner, count);

        // Real doorways first: a random number of temporary portals, a random
        // split of the squad between them, and a validated exit spot per Null.
        Arrival arrival = planArrival(owner, worldName, origin, count);
        if (arrival.spots.isEmpty()) {
            discardEmptyOwnerEntry(owner, existing);
            throw new IllegalStateException(arrival.failure == null
                    ? "no collision-safe ground within " + config.spawnSearchRadius()
                        + " blocks of you"
                    : arrival.failure);
        }

        pendingDoors.clear();
        pendingDoors.putAll(arrival.doors);
        Squad squad;
        try {
            squad = spawnAll(owner, worldName, arrival.spots, false, existing);
        } finally {
            pendingDoors.clear();
        }
        squad.arrivalNote = arrival.describe(count);
        lastArrivalNote = squad.arrivalNote;

        // The particle effects are the last thing that happens, and only around
        // the doorways that were really built: a Null is never hidden behind an
        // exception thrown by a cosmetic effect, and an effect is never the only
        // evidence that an arrival happened.
        for (Vec3d mouth : arrival.mouths) {
            playPortals(worldName, mouth);
        }
        return squad;
    }

    /** Doorway of each planned spot while a squad is being spawned. */
    private final Map<Vec3d, PortalBuilder.BuiltPortal> pendingDoors = new LinkedHashMap<>();

    /** What one summon's arrival planning produced. */
    private static final class Arrival {
        private final Map<Vec3d, PortalBuilder.BuiltPortal> doors = new LinkedHashMap<>();
        private final List<Vec3d> spots = new ArrayList<>();
        private final List<Vec3d> mouths = new ArrayList<>();
        private int portalsBuilt;
        private int throughPortals;
        private int onOpenGround;
        private String failure;
        private String portalNote = "";

        String describe(int requested) {
            StringBuilder sb = new StringBuilder();
            if (portalsBuilt > 0) {
                sb.append(portalsBuilt).append(portalsBuilt == 1 ? " portal doorway" : " portal doorways")
                        .append(" opened, ").append(throughPortals).append(" Null(s) walked out of them");
            } else {
                sb.append("no doorway could be built");
            }
            if (onOpenGround > 0) {
                sb.append(portalsBuilt > 0 ? "; " : "; ").append(onOpenGround)
                        .append(" arrived on verified open ground instead");
            }
            sb.append(" (").append(spots.size()).append('/').append(requested).append(" spawned spots)");
            if (!portalNote.isEmpty()) {
                sb.append(' ').append(portalNote);
            }
            return sb.toString();
        }
    }

    /**
     * Plans where a squad arrives.
     *
     * <p>Order of work: pick a random number of doorways up to the configured
     * hard maximum, build the ones whose site is clear and whose exit spots are
     * collision-safe and free of other entities, hand each doorway the Nulls the
     * plan gave it, and put everything that has no doorway on the ordinary
     * searched safe ground. A Null is never dropped to make the arithmetic
     * work - {@code requested - spots.size()} is reported to the owner.</p>
     */
    private Arrival planArrival(UUID owner, String worldName, Vec3d origin, int count) {
        Arrival arrival = new Arrival();
        PortalPlan plan = plugin.portals() == null
                ? PortalPlan.none(count) : plugin.portals().plan(count);
        redglitchx.nullarmy.core.zone.SummonZone zone = plugin.zones() == null
                ? new redglitchx.nullarmy.core.zone.SummonZone(origin.x(), origin.z(),
                        config == null ? 100 : config.v3().zoneSize())
                : plugin.zones().open(owner, worldName, origin).zone();

        List<PortalBuilder.BuiltPortal> doorways = plan.portalCount() > 0 && plugin.portals() != null
                ? plugin.portals().buildDoorways(worldName, origin, plan.portalCount(), zone)
                : new ArrayList<>();
        arrival.portalsBuilt = doorways.size();
        if (plan.portalCount() > 0 && doorways.size() < plan.portalCount()) {
            // Sites ran out: the Nulls of the doorways that were not built go to
            // open ground rather than disappearing.
            plan = shrink(plan, doorways.size());
            arrival.portalNote = "(" + (plan.portalCount() == 0 ? "no site was clear"
                    : "only " + doorways.size() + " site(s) were clear") + ")";
        }

        int assigned = 0;
        for (int index = 0; index < doorways.size() && index < plan.distribution().size(); index++) {
            PortalBuilder.BuiltPortal doorway = doorways.get(index);
            int wanted = plan.nullsAt(index);
            arrival.mouths.add(doorway.center());
            for (int n = 0; n < wanted; n++) {
                Vec3d spot = plugin.portals().takeExit(doorway, n % Math.max(1, doorway.exits().size()));
                if (spot == null || arrival.spots.contains(spot)) {
                    break; // this doorway is full; the rest go to open ground
                }
                arrival.spots.add(spot);
                arrival.doors.put(spot, doorway);
                arrival.throughPortals++;
                assigned++;
            }
        }

        int remaining = count - assigned;
        if (remaining > 0) {
            // Open ground, searched the way it always was: rings of increasing
            // radius, never a spot already taken - and now never within a body
            // width of a doorway arrival either, which is what used to make the
            // adapter refuse the spot and cost the summon a Null.
            List<Vec3d> ground = planSpawnSpots(worldName, origin,
                    remaining + arrival.spots.size() + 6);
            for (Vec3d spot : ground) {
                if (remaining <= 0) {
                    break;
                }
                if (tooClose(arrival.spots, spot, MIN_SPOT_SPACING) || !zone.contains(spot.x(), spot.z())) {
                    continue;
                }
                arrival.spots.add(spot);
                arrival.onOpenGround++;
                remaining--;
            }
        }
        if (arrival.spots.isEmpty()) {
            String portalWhy = plugin.portals() == null ? "" : plugin.portals().lastRefusal();
            arrival.failure = redglitchx.nullarmy.core.text.MessageTemplates.render("zone.refused",
                    "size", zone.size(), "reason", (portalWhy == null || portalWhy.isEmpty()
                            ? "no doorway site was clear" : portalWhy)
                            + ", and no collision-safe ground within " + config.spawnSearchRadius()
                            + " blocks of you inside the zone");
        }
        if (plan.spill() > 0 && arrival.spots.size() < count) {
            arrival.portalNote = "(" + (count - arrival.spots.size())
                    + " of " + count + " could not be given a safe spot)";
        }
        return arrival;
    }

    /** Drops doorways from a plan until it matches what was really built. */
    private PortalPlan shrink(PortalPlan plan, int built) {
        PortalPlan current = plan;
        while (current.portalCount() > built) {
            PortalPlan next = current.withoutPortal(current.portalCount() - 1);
            if (next.portalCount() == current.portalCount()) {
                break;
            }
            current = next;
        }
        return current;
    }

    /** The arrival note of the most recent summon, for the message the owner gets. */
    public String lastArrivalNote() { return lastArrivalNote; }

    /**
     * Creates a squad delivered out of the sky (the {@code /null airdrop} path).
     *
     * <p>Every position is checked against {@link VersionAdapter#isAirborneSpawnSafe}
     * first, so a Null is never dropped inside a roof or a solid wall. Positions
     * that are blocked are simply left out and reported: dropping fewer Nulls on
     * a clear, honest message is better than dropping one into stone.</p>
     *
     * <p>The Nulls then fall under real vanilla gravity, which is why this path
     * is documented to carry real fall damage. They do not teleport at the end:
     * once one lands, normal steering walks it to the summoner.</p>
     */
    public Squad createSquadAirborne(UUID owner, String worldName, Vec3d mouth, int count) {
        List<Squad> existing = preflight(owner, count);

        List<Vec3d> spots = planAirSpots(worldName, mouth, count);
        if (spots.isEmpty()) {
            discardEmptyOwnerEntry(owner, existing);
            throw new IllegalStateException("the sky portal is blocked by blocks - "
                    + "air drops need a clear column of air");
        }

        return spawnAll(owner, worldName, spots, true, existing);
    }

    /** Every shared guard for creating a squad. Throws with a player-safe reason. */
    private List<Squad> preflight(UUID owner, int count) {
        if (shutdownRequested) {
            throw new IllegalStateException("the plugin is shutting down");
        }
        if (plugin.shutdown() != null && plugin.shutdown().isRunning()) {
            // A Totem Of Null is sending the army out one body at a time. Nothing
            // new may appear until the last one has gone.
            throw new IllegalStateException("a Totem Of Null shutdown is running ("
                    + plugin.shutdown().remaining() + " Null(s) still to go)");
        }
        if (count <= 0) {
            throw new IllegalStateException("the count must be at least 1");
        }
        if (count > caps.summonHardCap()) {
            throw new IllegalStateException("requested " + count
                    + " Nulls but the hard cap is " + caps.summonHardCap());
        }
        if (liveCount() + count > caps.maxLiveNpcs()) {
            throw new IllegalStateException("server Null limit reached ("
                    + liveCount() + "/" + caps.maxLiveNpcs() + " live)");
        }
        if (plugin.spawnBreaker().isOpen()) {
            throw new IllegalStateException("Null creation is disabled this session: "
                    + plugin.spawnBreaker().reason()
                    + " (restart or run /null reload to re-arm it)");
        }
        List<Squad> existing = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        if (existing.size() >= caps.maxSquadsPerOwner()) {
            throw new IllegalStateException("you already have " + existing.size()
                    + " squads (max " + caps.maxSquadsPerOwner() + ")");
        }
        return existing;
    }

    /**
     * Spawns every Null of a new squad.
     *
     * <p>Each spawn is checked on its own. A body that the adapter created but
     * that is not actually in the world - no packet listener, not alive, not
     * tracked - is destroyed, is <b>not</b> counted, and its reason is kept for
     * the owner. When the failure is systemic (the NMS breaker has latched) the
     * rest are not attempted and everything this squad created is rolled back, so
     * a broken server does not end up with half an army nobody can see.</p>
     */
    private Squad spawnAll(UUID owner, String worldName, List<Vec3d> spots,
                           boolean airborne, List<Squad> existing) {
        Squad squad = new Squad(owner, worldName);
        List<Vec3d> used = new ArrayList<>(spots);
        for (Vec3d spot : spots) {
            if (plugin.spawnBreaker().isOpen()) {
                squad.spawnFailures.add("the NMS spawn path is latched off: "
                        + plugin.spawnBreaker().reason());
                break;
            }
            try {
                PortalBuilder.BuiltPortal door = pendingDoors.get(spot);
                NullBody body = spawnWithRetry(owner, worldName, spot, airborne, door != null, used);
                String problem = verifyBody(body);
                if (problem != null) {
                    Guard.attempt(logger, "cleaning up an unusable Null", body::destroy);
                    squad.spawnFailures.add(problem);
                    continue;
                }
                equip(body, squad);
                squad.members.add(body);
                if (door != null) {
                    door.assign(body.uuid());
                    if (plugin.brain() != null) {
                        plugin.brain().stepOut(body, door.stepOutPoint(), 100);
                    }
                }
                if (plugin.skinChain() != null) {
                    plugin.skinChain().noteSpawned(body, false);
                }
            } catch (RuntimeException e) {
                squad.spawnFailures.add(e.getMessage() == null
                        ? Guard.describe(e) : e.getMessage());
                if (plugin.spawnBreaker().isOpen()) {
                    // Systemic: stop, and leave the world exactly as it was.
                    break;
                }
            } catch (Throwable t) {
                squad.spawnFailures.add(Guard.describe(t));
                logger.log(Level.WARNING, "[NullArmy] a spawn attempt failed: " + Guard.describe(t));
            }
        }

        if (squad.members.isEmpty()) {
            for (NullBody body : squad.members) {
                Guard.attempt(logger, "rolling back a failed Null", body::destroy);
            }
            squad.members.clear();
            discardEmptyOwnerEntry(owner, existing);
            String reason = squad.spawnFailures.isEmpty()
                    ? "the server created no Null at all" : squad.spawnFailures.get(0);
            throw new IllegalStateException(reason);
        }

        assignCommanders(squad);
        squad.setRoles(RoleAssignment.assign(squad.members.size(), !squad.commanderIds.isEmpty()));
        existing.add(squad);
        return squad;
    }

    /**
     * Spawns at a spot, and when that one spot is refused - occupied, unsafe, or
     * an add cancelled by a protection plugin - tries the ring of neighbouring
     * cells before giving up. A refusal never latches the spawn breaker: the next
     * cell, and the next summon, may be perfectly fine.
     */
    NullBody spawnWithRetry(UUID owner, String worldName, Vec3d spot, boolean airborne,
                            boolean portalMouth, List<Vec3d> used) {
        try {
            return spawnOne(owner, worldName, spot, airborne, portalMouth);
        } catch (VersionAdapter.SpawnRefusedException refusal) {
            for (Vec3d cell : neighbourRing(spot)) {
                if (tooClose(used, cell, MIN_SPOT_SPACING)) {
                    continue;
                }
                boolean safe = airborne ? isAirborneSafe(worldName, cell) : isSafe(worldName, cell);
                if (!safe || !isFree(worldName, cell)) {
                    continue;
                }
                try {
                    NullBody body = spawnOne(owner, worldName, cell, airborne);
                    used.add(cell);
                    lastRetryNote = "spawn at " + round(spot.x()) + "," + round(spot.y()) + "," + round(spot.z())
                            + " was refused (" + refusal.getMessage() + "); the neighbouring cell "
                            + round(cell.x()) + "," + round(cell.y()) + "," + round(cell.z()) + " was used";
                    retries++;
                    logger.info("[NullArmy] " + lastRetryNote);
                    return body;
                } catch (VersionAdapter.SpawnRefusedException again) {
                    // try the next neighbour
                }
            }
            throw refusal;
        }
    }

    /** Neighbouring cells around a refused spot: an inner ring of 8, an outer ring of 12. */
    static List<Vec3d> neighbourRing(Vec3d spot) {
        List<Vec3d> out = new ArrayList<>();
        int[][] inner = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        for (int[] d : inner) {
            out.add(new Vec3d(spot.x() + d[0] * 1.2, spot.y(), spot.z() + d[1] * 1.2));
        }
        for (int i = 0; i < 12; i++) {
            double angle = 2.0 * Math.PI * i / 12.0;
            out.add(new Vec3d(spot.x() + Math.cos(angle) * 2.4, spot.y(), spot.z() + Math.sin(angle) * 2.4));
        }
        return out;
    }

    private int forcedRefusals;
    private int retries;
    private String lastRetryNote = "";

    /** Self test: the next {@code count} spawn attempts are refused as a protection plugin would. */
    public void forceRefusals(int count) {
        this.forcedRefusals = Math.max(0, count);
    }

    /** How many refused spawns were rescued by a neighbouring cell this session. */
    public int spawnRetries() { return retries; }

    public String lastRetryNote() { return lastRetryNote; }

    /**
     * Proves a body is really there.
     *
     * <p>A returned object is not a spawn. This is the plugin-side half of the
     * check the adapter does at registration time: the body has to be alive, it
     * has to have a packet listener (a {@code ServerPlayer} without one crashes
     * {@code MinecraftServer.tickChildren}), and the server has to be tracking it
     * so clients can see it.</p>
     *
     * @return null when the body is usable, otherwise the reason to report
     */
    private String verifyBody(NullBody body) {
        if (body == null) {
            return "the adapter returned no entity";
        }
        try {
            if (!adapter.packetListenerReady(body)) {
                return "the Null has no packet listener, so it was removed instead of"
                        + " being registered (that is what crashed the server tick loop)";
            }
            if (!body.isAlive()) {
                return "the Null was not alive immediately after registration";
            }
            if (!adapter.isTracked(body)) {
                return "the server is not tracking the Null, so nobody would have been"
                        + " able to see it; it was removed";
            }
        } catch (Throwable t) {
            return "the Null could not be verified after registration: " + Guard.describe(t);
        }
        return null;
    }

    /**
     * Puts the default kit on a new body and checks that it really landed.
     *
     * <p>A kit failure never costs the summon: the Null stays, and the shortfall
     * is recorded so {@code /null kit} and the summon message can say so.</p>
     */
    private void equip(NullBody body, Squad squad) {
        if (plugin.kits() == null) {
            return;
        }
        try {
            if (!plugin.kits().applyTo(body)) {
                String problem = plugin.kits().verify(body);
                squad.spawnFailures.add("a Null spawned without its full kit: "
                        + (problem == null ? "unverified" : problem));
            }
        } catch (Throwable t) {
            squad.spawnFailures.add("the default kit could not be applied: " + Guard.describe(t));
        }
    }

    /** Removes an owner entry that was only created by a failed preflight. */
    private void discardEmptyOwnerEntry(UUID owner, List<Squad> existing) {
        if (existing != null && existing.isEmpty()) {
            byOwner.remove(owner);
        }
    }

    /**
     * Spawns a squad at exact spots, without doorways - used by the self test,
     * which needs bodies in known places (a 3x3 crowd, a formation anchor).
     */
    public Squad spawnSquadAt(UUID owner, String worldName, List<Vec3d> spots) {
        List<Squad> existing = preflight(owner, spots.size());
        return spawnAll(owner, worldName, spots, false, existing);
    }

    /** Spread in the air under a sky mouth: rings of eight, capped at 3 blocks. */
    public List<Vec3d> planAirSpots(String worldName, Vec3d mouth, int count) {
        List<Vec3d> out = new ArrayList<>();
        if (mouth == null || count <= 0) {
            return out;
        }
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / Math.max(1, Math.min(8, count));
            double radius = Math.min(3.0, 0.9 * (1 + (i / 8)));
            Vec3d spot = new Vec3d(mouth.x() + Math.cos(angle) * radius, mouth.y(),
                    mouth.z() + Math.sin(angle) * radius);
            if (isAirborneSafe(worldName, spot)) {
                out.add(spot);
            }
        }
        return out;
    }

    private boolean isAirborneSafe(String worldName, Vec3d spot) {
        try {
            return adapter.isAirborneSpawnSafe(worldName, spot);
        } catch (Throwable t) {
            logger.fine("[NullArmy] sky spot check skipped: " + Guard.describe(t));
            return false; // fail closed: never drop a Null on an unverified spot
        }
    }

    /**
     * Spawns one Null through the adapter.
     *
     * <p>The only place in the plugin that creates an NPC, so the thread check,
     * the breaker and the failure reporting all live here exactly once.</p>
     */
    public NullBody spawnOne(UUID owner, String worldName, Vec3d spot, boolean airborne) {
        return spawnOne(owner, worldName, spot, airborne, false);
    }

    private NullBody spawnOne(UUID owner, String worldName, Vec3d spot, boolean airborne,
                              boolean portalMouth) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Nulls may only be created on the main server thread");
        }

        if (forcedRefusals > 0) {
            forcedRefusals--;
            throw new VersionAdapter.SpawnRefusedException("refused at " + round(spot.x()) + ","
                    + round(spot.y()) + "," + round(spot.z()) + " (self test: simulated protection plugin)");
        }
        String skinValue = "";
        String skinSignature = "";
        try {
            SkinData skin = plugin.skinChain() == null ? null : plugin.skinChain().current(false);
            if ((skin == null || !skin.complete()) && plugin.skins() != null && config != null) {
                skin = plugin.skins().resolveCached(config.nullSkinName());
            }
            if (skin != null && skin.complete()) {
                skinValue = skin.value();
                skinSignature = skin.signature();
            }
        } catch (Throwable t) {
            // Cosmetic only: a Null without a skin is still a Null.
            logger.fine("[NullArmy] skin lookup skipped: " + Guard.describe(t));
        }

        VersionAdapter.SpawnRequest request = new VersionAdapter.SpawnRequest(
                owner, uniqueProfileName(), worldName, spot, 36 * 64,
                skinValue, skinSignature, airborne, crowdTest, portalMouth);
        try {
            NullBody body = adapter.spawnNull(request);
            if (body == null) {
                throw new IllegalStateException("the adapter returned no entity");
            }
            // Every Null this plugin creates gets the configured default kit, in
            // the right slots, here - the one place an NPC is born. Applying it
            // later (or only from the squad path) is how a Null ends up naked.
            postSpawn(body);
            joinTeam(owner, body);
            if (plugin.kits() != null && config != null && config.kitAppliesToNulls()) {
                plugin.kits().applyTo(body);
            }
            return body;
        } catch (VersionAdapter.SpawnRefusedException refusal) {
            // This position was wrong, not the server. Latching the whole spawn
            // path for one bad spot would turn a single refused Null into "no
            // Null can ever be created again this session".
            throw refusal;
        } catch (Throwable t) {
            String reason = Guard.describe(t);
            plugin.spawnBreaker().trip(reason, t, logger,
                    "Null creation is now latched off; the server is unaffected."
                            + " Restart or run /null reload to try again after fixing the cause.");
            throw new IllegalStateException("the server refused to create the NPC: " + reason);
        }
    }

    private boolean crowdTest;

    /** Self test only: the next spawns may overlap other bodies (the 3x3 crowd check). */
    public void allowCrowding(boolean allowed) {
        this.crowdTest = allowed;
    }

    // ------------------------------------------------------- squad team (P-05/L-05)

    /** Every Null of one owner shares one scoreboard team, friendly fire off. */
    private static final String TEAM_PREFIX = "NullArmy-";

    /**
     * P-05 / L-05: squad discipline.
     *
     * <p>"they hit them self with bows bruh" - an arrow loosed at an enemy that
     * clips a squad mate still hurt the mate, because nothing told the server
     * these bodies are on the same side. Every Null of one owner is now put on a
     * scoreboard team with friendly fire switched off, which is the vanilla rule
     * for both melee and projectiles, and the damage listener cancels anything
     * that still slips through on top of that.</p>
     *
     * <p>Teams are per owner, not global: two players' armies are different
     * armies and may fight each other.</p>
     */
    private String teamName(UUID owner) {
        return owner == null ? TEAM_PREFIX + "none" : TEAM_PREFIX + owner.toString().substring(0, 8);
    }

    private void joinTeam(UUID owner, NullBody body) {
        Guard.attempt(logger, "putting a Null on its squad's scoreboard team", () -> {
            Player handle = redglitchx.nullarmy.plugin.body.Bodies.player(body);
            if (handle == null) {
                return;
            }
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String name = teamName(owner);
            org.bukkit.scoreboard.Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
            }
            team.setAllowFriendlyFire(false);
            team.addEntry(handle.getName());
        });
    }

    /** Takes a Null off its team when it goes, so the team never grows forever. */
    private void leaveTeam(NullBody body) {
        Guard.attempt(logger, "taking a Null off its scoreboard team", () -> {
            String name = body == null ? null : body.profileName();
            if (name == null || name.isEmpty()) {
                return;
            }
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            for (org.bukkit.scoreboard.Team team : new ArrayList<>(board.getTeams())) {
                if (team.getName().startsWith(TEAM_PREFIX) && team.hasEntry(name)) {
                    team.removeEntry(name);
                }
            }
        });
    }

    /** The team one owner's army belongs to (for /null status). */
    public String teamOf(UUID owner) {
        if (owner == null) {
            return null;
        }
        org.bukkit.scoreboard.Team team =
                Bukkit.getScoreboardManager().getMainScoreboard().getTeam(teamName(owner));
        return team == null ? null : team.getName();
    }

    /** A Null is a survival body whatever the server's default game mode is. */
    private void postSpawn(NullBody body) {
        Guard.attempt(logger, "setting a Null to survival", () -> {
            Player handle = redglitchx.nullarmy.plugin.body.Bodies.player(body);
            if (handle != null && handle.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
                handle.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
        });
    }

    /**
     * A random alphanumeric profile name that nobody else is using.
     *
     * <p>The configured skin account is the <b>texture</b> source only - it is
     * never a Null's name, and two Nulls never share one. Names are checked
     * against live Nulls, the Commander and every online player, because a
     * duplicate shows up in the tab list as two identical entries and confuses
     * anything that looks players up by name.</p>
     */
    /**
     * Names handed out since the last prune.
     *
     * <p>{@code allMembers()} cannot be the only guard: a squad is spawned one
     * body at a time. This set remembers names issued during that sequence and
     * is pruned against the current roster so it cannot grow without bound.</p>
     */
    private final java.util.Set<String> issuedNames = new java.util.HashSet<>();

    private String uniqueProfileName() {
        java.util.Set<String> taken = new java.util.HashSet<>();
        try {
            for (NullBody body : allMembers()) {
                String name = body.profileName();
                if (name != null) {
                    taken.add(name.toLowerCase(Locale.ROOT));
                }
            }
            if (plugin.commander() != null && plugin.commander().commanderName() != null) {
                taken.add(plugin.commander().commanderName().toLowerCase(Locale.ROOT));
            }
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online != null && online.getName() != null) {
                    taken.add(online.getName().toLowerCase(Locale.ROOT));
                }
            }
        } catch (Throwable t) {
            logger.fine("[NullArmy] name uniqueness check skipped: " + Guard.describe(t));
        }
        if (issuedNames.size() > 2048) {
            // Prune against reality rather than growing for ever.
            issuedNames.retainAll(taken);
        }
        // The generator loops until it has a fresh 16-character alphanumeric
        // candidate. Remember it immediately so bodies in this same spawn batch
        // cannot collide before they join allMembers().
        java.util.Set<String> lowered = new java.util.HashSet<>(taken);
        lowered.addAll(issuedNames);
        String candidate = NameGenerator.next(lowered);
        issuedNames.add(candidate.toLowerCase(Locale.ROOT));
        return candidate;
    }

    /** Plays the summon portal effects. Cosmetic, throttled, never fatal. */
    public void playPortals(String worldName, Vec3d at) {
        boolean particles = config == null || config.portalParticlesEnabled();
        int effects = particles ? Math.max(Caps.minPortalEffects(),
                caps == null ? Caps.minPortalEffects() : caps.portalEffectsPerSummon()) : 0;
        Guard.attempt(logger, "portal effects",
                () -> adapter.playPortalEffects(worldName, at, effects));
    }

    /**
     * Finds up to {@code count} collision-safe spawn positions around
     * {@code origin}: rings of increasing radius, never the same block twice.
     */
    public List<Vec3d> planSpawnSpots(String worldName, Vec3d origin, int count) {
        List<Vec3d> out = new ArrayList<>();
        if (count <= 0) {
            return out;
        }
        int radius = Math.max(1, config == null ? 6 : config.spawnSearchRadius());

        // Ring 0 is the summoner's own feet block: always try it first, unless
        // somebody (usually the summoner) is already standing in it.
        if (isSafe(worldName, origin) && isFree(worldName, origin)) {
            out.add(origin);
        }
        double step = 1.5;
        for (int ring = 1; ring <= radius && out.size() < count; ring++) {
            double r = ring * step;
            int points = Math.max(6, (int) Math.round(2.0 * Math.PI * r / step));
            for (int i = 0; i < points && out.size() < count; i++) {
                double angle = (2.0 * Math.PI * i) / points;
                Vec3d candidate = new Vec3d(
                        origin.x() + Math.cos(angle) * r,
                        origin.y(),
                        origin.z() + Math.sin(angle) * r);
                if (alreadyUsed(out, candidate)) {
                    continue;
                }
                if (isSafe(worldName, candidate) && isFree(worldName, candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    /** True when a candidate is too close to a spot that is already planned. */
    private static boolean alreadyUsed(List<Vec3d> used, Vec3d candidate) {
        return tooClose(used, candidate, MIN_SPOT_SPACING);
    }

    /** True when any of {@code used} is within {@code minDistance} of the candidate. */
    private static boolean tooClose(List<Vec3d> used, Vec3d candidate, double minDistance) {
        if (used == null || candidate == null) {
            return false;
        }
        double limit = minDistance * minDistance;
        for (Vec3d existing : used) {
            if (existing == null) {
                continue;
            }
            double dx = existing.x() - candidate.x();
            double dy = existing.y() - candidate.y();
            double dz = existing.z() - candidate.z();
            if (dx * dx + dy * dy + dz * dz < limit) {
                return true;
            }
        }
        return false;
    }

    /** Collision safety check that can never throw: an error means "not safe". */
    public boolean isSafe(String worldName, Vec3d position) {
        try {
            return adapter.isSpawnSafe(worldName, position);
        } catch (Throwable t) {
            logger.fine("[NullArmy] spawn-safety check failed: " + Guard.describe(t));
            return false;
        }
    }

    /**
     * Entity-occupancy check that can never throw.
     *
     * <p>Block safety alone lets two Nulls be planned into the same space; the
     * adapter then refuses the second one, which is a lost Null the owner is told
     * about. Checking first means the planner picks a different spot instead.</p>
     */
    public boolean isFree(String worldName, Vec3d position) {
        try {
            return adapter.isEntitySpaceFree(worldName, position);
        } catch (Throwable t) {
            logger.fine("[NullArmy] entity-space check failed: " + Guard.describe(t));
            return false;
        }
    }

    // ------------------------------------------------------------------- bookkeeping

    /**
     * Designates commanders. Stored, not recomputed - spec 3: "Store those
     * roles; do not randomly reassign them on every tick or restart."
     */
    private void assignCommanders(Squad squad) {
        squad.commanderIds.clear();
        int wanted = Math.min(COMMANDER_COUNT, squad.members.size());
        for (int i = 0; i < wanted; i++) {
            squad.commanderIds.add(squad.members.get(i).id());
        }
    }

    /**
     * Stops every squad walking, without latching the plugin into shutdown.
     *
     * <p>This is what the Totem Of Null sequence uses: the Nulls should stand
     * still while they go out one at a time, but the plugin has to accept summons
     * again once the last one is gone. {@link #requestSafeShutdown} is the
     * permanent version, for {@code onDisable} only.</p>
     *
     * @return how many Nulls were stopped
     */
    public int standDown() {
        int stopped = 0;
        for (Squad squad : allSquads()) {
            squad.objective = Objective.NONE;
            squad.targetId = null;
            squad.point = null;
            for (NullBody body : squad.members) {
                if (Guard.attempt(logger, "standing a Null down",
                        () -> body.applySteering(Vec3d.ZERO))) {
                    stopped++;
                }
            }
        }
        return stopped;
    }

    /** Spec 5: SAFE_SHUTDOWN is a state the Nulls walk into, not an instant delete. */
    public void requestSafeShutdown() {
        shutdownRequested = true;
        forEachSquad(squad -> {
            squad.objective = Objective.NONE;
            for (NullBody body : squad.members) {
                Guard.attempt(logger, "stopping a Null for shutdown",
                        () -> body.applySteering(Vec3d.ZERO));
            }
        });
    }

    public boolean isShutdownRequested() { return shutdownRequested; }

    /**
     * Drops one body from whichever squad holds it.
     *
     * <p>Used by the sequential shutdown so the live count falls the moment a
     * Null goes out, instead of a tick later when the reaper notices.</p>
     *
     * @return true when a squad was holding it
     */
    public boolean forget(NullBody body) {
        if (body == null) {
            return false;
        }
        boolean removed = false;
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                if (squad.members.remove(body)) {
                    removed = true;
                }
            }
        }
        forgetAllEmpty();
        return removed;
    }

    /** Drops squads that have no members left, and owners with no squads. */
    public void forgetAllEmpty() {
        for (java.util.Iterator<Map.Entry<UUID, List<Squad>>> owner = byOwner.entrySet().iterator();
                owner.hasNext();) {
            List<Squad> squads = owner.next().getValue();
            squads.removeIf(squad -> squad.members.isEmpty());
            if (squads.isEmpty()) {
                owner.remove();
            }
        }
    }

    /**
     * Bodies the adapter still knows about that no squad holds.
     *
     * <p>These are the ones a failed rollback, a chunk unload or a bug could leave
     * behind. The shutdown director removes them too, so "every Null" really means
     * every Null.</p>
     */
    public List<NullBody> orphanedBodies() {
        List<NullBody> out = new ArrayList<>();
        try {
            if (adapter == null) {
                return out;
            }
            List<NullBody> known = allMembers();
            for (Squad squad : allSquads()) {
                for (NullBody body : squad.members) {
                    if (!known.contains(body)) {
                        out.add(body);
                    }
                }
            }
            for (String worldName : worldNames()) {
                for (NullBody body : adapter.activeIn(worldName)) {
                    if (body != null && !known.contains(body) && !out.contains(body)) {
                        out.add(body);
                    }
                }
            }
        } catch (Throwable t) {
            logger.fine("[NullArmy] orphan scan skipped: " + Guard.describe(t));
        }
        return out;
    }

    /** The worlds that currently have squads in them. */
    private List<String> worldNames() {
        List<String> out = new ArrayList<>();
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                if (squad.worldName != null && !out.contains(squad.worldName)) {
                    out.add(squad.worldName);
                }
            }
        }
        if (out.isEmpty()) {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                if (world != null) {
                    out.add(world.getName());
                }
            }
        }
        return out;
    }

    /** The stored roles of an owner's first squad, for the AI coordinator. */
    public List<SquadRole> rolesOf(UUID owner) {
        Squad squad = find(owner);
        return squad == null ? Collections.emptyList() : squad.roles();
    }

    /**
     * Stores a role for every member of an owner's squads.
     *
     * <p>Roles are stored, not re-rolled per tick: the Commander's report, the AI
     * coordinator and {@code /null roles} all read the same assignment.</p>
     *
     * @return how many Nulls now have a stored role
     */
    public int assignRoles(UUID owner) {
        int assigned = 0;
        for (Squad squad : squadsOf(owner)) {
            if (squad.members.isEmpty()) {
                continue;
            }
            squad.setRoles(RoleAssignment.assign(squad.members.size(),
                    !squad.commanderIds.isEmpty()));
            assigned += squad.members.size();
        }
        return assigned;
    }

    /** "1 commander, 2 guard, 1 scout, 1 ranged" for one owner. */
    public String roleSummary(UUID owner) {
        List<SquadRole> all = new ArrayList<>();
        for (Squad squad : squadsOf(owner)) {
            all.addAll(squad.roles());
        }
        return RoleAssignment.describe(all);
    }

    /** One line per role that is actually filled, saying what it does. */
    public List<String> roleDuties(UUID owner) {
        List<String> out = new ArrayList<>();
        List<SquadRole> all = new ArrayList<>();
        for (Squad squad : squadsOf(owner)) {
            all.addAll(squad.roles());
        }
        Map<SquadRole, Integer> counts = RoleAssignment.counts(all);
        for (Map.Entry<SquadRole, Integer> entry : counts.entrySet()) {
            out.add(entry.getValue() + " " + entry.getKey().key() + ": "
                    + entry.getKey().duty());
        }
        return out;
    }

    /**
     * Points every Null with a role at what that role does.
     *
     * <p>Roles are not decoration: a scout walks further out, a medic closes in,
     * ranged support keeps its distance. This turns the stored role into the
     * standoff distance the steering loop uses.</p>
     */
    private double roleStandoff(Squad squad, int index) {
        SquadRole role = squad.roleOf(index);
        if (role == null) {
            return standoff(squad);
        }
        return RoleAssignment.standoff(role);
    }

    /** Live Nulls, counted from the squads: this can never drift. */
    public int liveCount() {
        int count = 0;
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                count += squad.members.size();
            }
        }
        return count;
    }

    public List<Squad> squadsOf(UUID owner) {
        List<Squad> squads = byOwner.get(owner);
        return squads == null ? Collections.emptyList() : Collections.unmodifiableList(squads);
    }

    /** Every squad on the server. */
    public List<Squad> allSquads() {
        List<Squad> out = new ArrayList<>();
        for (List<Squad> squads : byOwner.values()) {
            out.addAll(squads);
        }
        return out;
    }

    /** Every live Null belonging to one owner. */
    public List<NullBody> membersOf(UUID owner) {
        List<NullBody> out = new ArrayList<>();
        for (Squad squad : squadsOf(owner)) {
            out.addAll(squad.members);
        }
        return out;
    }

    /** Every live Null on the server. */
    public List<NullBody> allMembers() {
        List<NullBody> out = new ArrayList<>();
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                out.addAll(squad.members);
            }
        }
        return out;
    }

    /** Finds one Null by entity id or by profile-name prefix. Null when absent. */
    public NullBody find(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        String needle = token.trim().toLowerCase(Locale.ROOT);
        for (NullBody body : allMembers()) {
            try {
                if (String.valueOf(body.id()).equals(needle)) {
                    return body;
                }
                String name = body.profileName();
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                    return body;
                }
            } catch (Throwable ignored) {
                // A misbehaving body is simply not a match.
            }
        }
        return null;
    }

    /** The first squad belonging to {@code owner}, or null when it has none. */
    public Squad find(UUID owner) {
        if (owner == null) {
            return null;
        }
        List<Squad> squads = byOwner.get(owner);
        return squads == null || squads.isEmpty() ? null : squads.get(0);
    }

    /** True when the entity belongs to a Null this manager owns. */
    public boolean owns(NullBody body) {
        return body != null && allMembers().contains(body);
    }

    /** The owner of a Null, or null when it is not ours. */
    public UUID ownerOf(NullBody body) {
        if (body == null) {
            return null;
        }
        for (Map.Entry<UUID, List<Squad>> entry : byOwner.entrySet()) {
            for (Squad squad : entry.getValue()) {
                if (squad.members.contains(body)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- per tick

    /** Per-tick driver: reaps the dead, then applies bounded steering work. */
    public void tick(long tickCounter) {
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                reap(squad);
                if (!squad.members.isEmpty() && squad.commanders().isEmpty()) {
                    // Commander succession after a loss (spec 3).
                    assignCommanders(squad);
                }
                // Movement, look and behaviour are driven by NullBrain, which the
                // plugin ticks right after this; nothing here steers bodies.
            }
        }
    }

    private void reap(Squad squad) {
        Iterator<NullBody> it = squad.members.iterator();
        while (it.hasNext()) {
            NullBody body = it.next();
            boolean alive;
            try {
                alive = body.isAlive();
            } catch (Throwable t) {
                alive = false;
            }
            if (!alive) {
                int deathTicks;
                try {
                    deathTicks = body.deathTicks();
                } catch (Throwable t) {
                    deathTicks = Integer.MAX_VALUE;
                }
                if (deathTicks >= 0 && deathTicks < 22) {
                    // The death animation is still playing: the body tips over on
                    // every client first, then goes with vanilla's puff of smoke.
                    continue;
                }
                it.remove();
                leaveTeam(body);
                Guard.attempt(logger, "forgetting a dead Null", () -> body.destroy());
            }
        }
    }

    /**
     * Turns the squad's objective into bounded steering forces.
     *
     * <p>Every cost is drawn from the per-tick block-inspection budget, so a
     * hundred Nulls can never turn into a hundred lookups in one tick. Nulls
     * that would have to step off a ledge or into a wall simply stop and wait -
     * they never teleport and never clip.</p>
     */
    private void steer(Squad squad, long tickCounter) {
        if (squad.objective == Objective.NONE || squad.members.isEmpty()) {
            return;
        }
        Vec3d target = resolveTarget(squad);
        if (target == null) {
            return;
        }
        for (int index = 0; index < squad.members.size(); index++) {
            NullBody body = squad.members.get(index);
            Vec3d slot = target;
            if (squad.objective == Objective.FORMATION) {
                slot = formationSlot(squad.formation, target, index, squad.members.size());
            }
            steerOne(squad, body, slot, index);
        }
    }

    private Vec3d resolveTarget(Squad squad) {
        if (squad.objective == Objective.GUARD) {
            // Guard holds position: the "target" is where the Null already is.
            return null;
        }
        if (squad.objective == Objective.DESTINATION) {
            return squad.point;
        }
        if (squad.targetId == null) {
            return null;
        }
        try {
            Player player = Bukkit.getPlayer(squad.targetId);
            if (player == null || !player.isOnline()) {
                return null;
            }
            Location location = player.getLocation();
            if (location == null || location.getWorld() == null) {
                return null;
            }
            if (!location.getWorld().getName().equals(squad.worldName)) {
                return null;
            }
            return new Vec3d(location.getX(), location.getY(), location.getZ());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * How close this squad wants to get.
     *
     * <p>Aggressive closes to melee range, defensive holds a gap and watches,
     * balanced uses the classic two blocks. This is the whole meaning of
     * {@code /null tactics} - it is real, not cosmetic.</p>
     */
    private static double standoff(Squad squad) {
        if (squad == null || squad.tactics == null) {
            return ARRIVE_DISTANCE;
        }
        switch (squad.tactics) {
            case "aggressive":
                return 1.2;
            case "defensive":
                return 4.5;
            case "balanced":
            default:
                return ARRIVE_DISTANCE;
        }
    }

    private void steerOne(Squad squad, NullBody body, Vec3d target, int index) {
        try {
            Vec3d here = body.bodyPosition();
            Vec3d delta = target.sub(here);
            double distance = delta.horizontalLength();

            // Tactics are not decoration: they change the standoff a Null keeps,
            // and a stored role refines it (a scout works further out, a medic
            // closes in, ranged support stays back).
            double standoff = squad.roles().isEmpty() ? standoff(squad) : roleStandoff(squad, index);
            if (distance <= standoff) {
                body.applySteering(Vec3d.ZERO);
                if (squad.objective == Objective.ATTACK) {
                    body.lookAt(target);
                }
                return;
            }

            Vec3d direction = new Vec3d(delta.x() / distance, 0.0, delta.z() / distance);
            if (!mayStep(squad.worldName, here, direction)) {
                // A ledge, a wall or a hazard. Stop rather than cheat.
                body.applySteering(Vec3d.ZERO);
                body.lookAt(target);
                return;
            }
            body.applySteering(direction.scale(WALK_SPEED));
            body.lookAt(target);
        } catch (Throwable t) {
            Guard.attempt(logger, "steering a Null", () -> body.applySteering(Vec3d.ZERO));
        }
    }

    /**
     * Conservative "can I take a step this way?" test: the destination must be
     * free at foot and head height, and there must be solid ground under it.
     * Unloaded terrain counts as solid inside the BlockView, so this fails
     * closed.
     */
    private boolean mayStep(String worldName, Vec3d here, Vec3d direction) {
        if (plugin.blockInspectionBudget() == null) {
            return true;
        }
        // Two lookups per step: the column ahead, and the floor beneath it.
        if (!plugin.blockInspectionBudget().tryConsume(2)) {
            return false;
        }
        try {
            BlockView view = adapter.blockView(worldName);
            int x = (int) Math.floor(here.x() + direction.x());
            int y = (int) Math.floor(here.y());
            int z = (int) Math.floor(here.z() + direction.z());
            boolean floor = view.isSolid(x, y - 1, z);
            boolean feet = view.isSolid(x, y, z);
            boolean head = view.isSolid(x, y + 1, z);
            return floor && !feet && !head;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Formation offsets, in blocks, relative to the centre.
     *
     * <p>Deliberately simple and physical: every slot is reachable on foot
     * without pathfinding. Anything cleverer belongs in Phase 4.</p>
     */
    private Vec3d formationSlot(String formation, Vec3d center, int index, int total) {
        String kind = formation == null ? "line" : formation.toLowerCase(Locale.ROOT);
        switch (kind) {
            case "square": {
                int side = Math.max(2, (int) Math.ceil(Math.sqrt(Math.max(1, total))));
                double radius = side;
                double angle = (2.0 * Math.PI * index) / Math.max(1, total);
                return new Vec3d(center.x() + Math.cos(angle) * radius, center.y(),
                        center.z() + Math.sin(angle) * radius);
            }
            case "encircle": {
                double radius = 5.0;
                double angle = (2.0 * Math.PI * index) / Math.max(1, total);
                return new Vec3d(center.x() + Math.cos(angle) * radius, center.y(),
                        center.z() + Math.sin(angle) * radius);
            }
            case "turtle": {
                double radius = 1.5;
                double angle = (2.0 * Math.PI * index) / Math.max(1, total);
                return new Vec3d(center.x() + Math.cos(angle) * radius, center.y(),
                        center.z() + Math.sin(angle) * radius);
            }
            case "line":
            default: {
                double offset = (index / 2 + 1) * ((index % 2 == 0) ? 1.5 : -1.5);
                return new Vec3d(center.x() + offset, center.y(), center.z() + offset);
            }
        }
    }

    // ---------------------------------------------------------------- objectives

    /** Stores "walk to this player" for every squad of {@code owner}. */
    public int follow(UUID owner, UUID target, String label) {
        return setObjective(owner, Objective.FOLLOW, target, label, null, null);
    }

    /** Stores a combat objective. The fight itself is Phase 5; walking is real. */
    public int attack(UUID owner, UUID target, String label) {
        return setObjective(owner, Objective.ATTACK, target, label, null, null);
    }

    /** Stores a formation around a player. */
    public int formation(UUID owner, UUID target, String label, String formation) {
        return setObjective(owner, Objective.FORMATION, target, label, null, formation);
    }

    /** Stores "hold position and watch". */
    public int guard(UUID owner) {
        int count = 0;
        for (Squad squad : squadsOf(owner)) {
            squad.objective = Objective.GUARD;
            squad.targetId = null;
            squad.targetLabel = "holding position";
            squad.point = null;
            count += squad.members.size();
        }
        return count;
    }

    /** Stores a destination point. */
    public int destination(UUID owner, Vec3d point, String label) {
        return setObjective(owner, Objective.DESTINATION, null, label, point, null);
    }

    /** Clears objectives: Nulls stop where they are. */
    public int clearObjectives(UUID owner) {
        int count = 0;
        for (Squad squad : squadsOf(owner)) {
            squad.objective = Objective.NONE;
            squad.targetId = null;
            squad.targetLabel = "";
            squad.point = null;
            for (NullBody body : squad.members) {
                Guard.attempt(logger, "stopping a Null",
                        () -> body.applySteering(Vec3d.ZERO));
            }
            count += squad.members.size();
        }
        return count;
    }

    private int setObjective(UUID owner, Objective objective, UUID target, String label,
                             Vec3d point, String formation) {
        int count = 0;
        for (Squad squad : squadsOf(owner)) {
            squad.objective = objective;
            squad.targetId = target;
            squad.targetLabel = label == null ? "" : label;
            squad.point = point;
            squad.formationAnchor = null;
            if (formation != null) {
                squad.formation = formation;
            }
            count += squad.members.size();
        }
        return count;
    }

    /**
     * A formation held at a fixed anchor and facing: every member walks to its own
     * cell of the rotated matrix and stands still there.
     */
    public int holdFormation(UUID owner, Vec3d anchor, float yaw, String formation) {
        int count = 0;
        for (Squad squad : squadsOf(owner)) {
            squad.objective = Objective.FORMATION;
            squad.targetLabel = "holding " + (formation == null ? squad.formation : formation);
            squad.point = anchor;
            squad.formationAnchor = anchor;
            squad.formationYaw = yaw;
            if (formation != null) {
                squad.formation = formation;
            }
            count += squad.members.size();
        }
        return count;
    }

    /** How many Nulls are doing something, and what. For {@code /null status}. */
    public String objectiveSummary() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                String key = squad.objective == Objective.NONE ? "idle" : squad.objective.name().toLowerCase(Locale.ROOT);
                counts.merge(key, squad.members.size(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return "no squads";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getValue()).append(' ').append(entry.getKey());
        }
        return sb.toString();
    }

    /** Describes every live Null for {@code /null list}. */
    public List<String> describeMembers() {
        List<String> out = new ArrayList<>();
        for (Squad squad : allSquads()) {
            for (NullBody body : squad.members) {
                out.add(describe(body, squad));
            }
        }
        return out;
    }

    /** One Null's details for {@code /null list} and {@code /null info}. */
    public String describe(NullBody body, Squad squad) {
        try {
            Vec3d pos = body.bodyPosition();
            return "#" + body.id() + " " + body.profileName()
                    + " hp=" + round(body.health())
                    + " at " + (int) Math.floor(pos.x()) + "," + (int) Math.floor(pos.y())
                    + "," + (int) Math.floor(pos.z())
                    + (squad == null ? "" : " [" + squad.objective.name().toLowerCase(Locale.ROOT) + "]");
        } catch (Throwable t) {
            return "#? unreadable: " + Guard.describe(t);
        }
    }

    private static String round(double value) {
        return String.valueOf(Math.round(value * 10.0) / 10.0);
    }

    // ---------------------------------------------------------------- mass actions

    /** Heals every Null of an owner. Returns how many were healed. */
    public int healAll(UUID owner, double amount) {
        int healed = 0;
        for (NullBody body : membersOf(owner)) {
            try {
                body.heal(amount);
                healed++;
            } catch (Throwable t) {
                logger.fine("[NullArmy] heal skipped: " + Guard.describe(t));
            }
        }
        return healed;
    }

    /** Puts one loadout slot on every Null of an owner. Returns how many were touched. */
    public int equipAll(UUID owner, LoadoutSlot slot) {
        if (slot == null) {
            return 0;
        }
        int touched = 0;
        List<LoadoutSlot> single = Collections.singletonList(slot);
        for (NullBody body : membersOf(owner)) {
            try {
                body.setLoadout(single);
                touched++;
            } catch (Throwable t) {
                logger.fine("[NullArmy] equip skipped: " + Guard.describe(t));
            }
        }
        return touched;
    }

    /**
     * Takes everything out of every Null of an owner and hands it back as
     * material for the caller to drop in the world. The Nulls are emptied,
     * so nothing is duplicated.
     */
    public List<String> drainInventories(UUID owner) {
        List<String> summary = new ArrayList<>();
        for (NullBody body : membersOf(owner)) {
            try {
                List<LoadoutSlot> slots = body.loadout();
                if (slots.isEmpty()) {
                    continue;
                }
                for (LoadoutSlot slot : slots) {
                    summary.add(slot.count() + "x " + slot.material() + " from #" + body.id());
                }
                body.setLoadout(Collections.emptyList());
            } catch (Throwable t) {
                logger.fine("[NullArmy] inventory drain skipped: " + Guard.describe(t));
            }
        }
        return summary;
    }

    /** Emergency stop: dismiss every squad immediately (spec 8 kill switch). */
    public void dismissAll() {
        forEachSquad(squad -> {
            for (NullBody body : squad.members) {
                leaveTeam(body);
                Guard.attempt(logger, "dismissing a Null", () -> body.destroy());
            }
            squad.members.clear();
            squad.objective = Objective.NONE;
        });
        byOwner.clear();
    }

    /** Dismisses one owner's squads. Returns how many Nulls were removed. */
    public int dismiss(UUID owner) {
        List<Squad> squads = byOwner.remove(owner);
        if (squads == null) {
            return 0;
        }
        int removed = 0;
        for (Squad squad : squads) {
            for (NullBody body : squad.members) {
                leaveTeam(body);
                if (Guard.attempt(logger, "dismissing a Null", () -> body.destroy())) {
                    removed++;
                }
            }
            squad.members.clear();
        }
        return removed;
    }

    // ------------------------------------------------------- portal, voice, realism

    /**
     * Walks an owner's Nulls through a portal to a destination.
     *
     * <p>This is the single visible exception to the no-teleport rule, and it is
     * deliberately loud: portal effects are played at the origin and at the
     * arrival point, the destination is verified collision-safe by the adapter
     * before anyone moves, and a Null that cannot make the crossing simply stays
     * put. Returns how many arrived.</p>
     */
    public int portalAll(UUID owner, String worldName, Vec3d destination) {
        if (owner == null || worldName == null || destination == null) {
            return 0;
        }
        List<NullBody> members = membersOf(owner);
        if (members.isEmpty()) {
            return 0;
        }
        // One safe spot per Null, so they arrive side by side instead of stacked.
        List<Vec3d> spots = planSpawnSpots(worldName, destination, members.size());
        if (spots.isEmpty()) {
            return 0;
        }
        int moved = 0;
        for (int i = 0; i < members.size() && i < spots.size(); i++) {
            NullBody body = members.get(i);
            Vec3d from = null;
            try {
                from = body.bodyPosition();
            } catch (Throwable ignored) {
                // Reported as a non-mover below.
            }
            if (from != null) {
                playPortals(worldName, from);
            }
            boolean ok = false;
            try {
                ok = adapter.portalTravel(worldName, body, spots.get(i));
            } catch (Throwable t) {
                logger.log(Level.WARNING, "[NullArmy] portal travel failed: " + Guard.describe(t));
            }
            if (ok) {
                moved++;
                playPortals(worldName, spots.get(i));
            }
        }
        return moved;
    }

    /**
     * A visible gesture from every nearby Null.
     *
     * <p>Realism without packets: a Null cannot wave an arm, but it can turn to
     * face the player, step back a little, and make the sounds a player would
     * hear. That reads as attention, which is what a gesture is for.</p>
     */
    public int emote(UUID owner, String gesture, Location where) {
        List<NullBody> members = membersOf(owner);
        if (members.isEmpty() || gesture == null) {
            return 0;
        }
        Vec3d look = where == null ? null
                : new Vec3d(where.getX(), where.getY() + 1.0, where.getZ());
        int done = 0;
        for (NullBody body : members) {
            try {
                if (look != null) {
                    body.lookAt(look);
                }
                Player handle = redglitchx.nullarmy.plugin.body.Bodies.player(body);
                switch (gesture) {
                    case "wave":
                    case "salute":
                    case "nod":
                    case "point":
                        if (handle != null) {
                            handle.swingMainHand();
                        }
                        if (plugin.brain() != null) {
                            plugin.brain().acknowledge(body, plugin.brain().mind(body));
                        }
                        break;
                    case "dance":
                        // A real jump: vanilla physics, nothing faked.
                        body.setMovement(0, 0, NullBody.GAIT_STOP, true, false);
                        break;
                    case "sit":
                        body.setMovement(0, 0, NullBody.GAIT_STOP, false, true);
                        break;
                    default:
                        break;
                }
                done++;
            } catch (Throwable t) {
                logger.log(Level.FINE, "[NullArmy] gesture skipped: " + Guard.describe(t));
            }
        }
        playGestureSound(where, gesture);
        return done;
    }

    /** Makes an owner's Nulls turn to face someone. Returns how many did. */
    public int greet(UUID owner, Player target) {
        List<NullBody> members = membersOf(owner);
        if (members.isEmpty() || target == null) {
            return 0;
        }
        Location at = target.getLocation();
        if (at == null || at.getWorld() == null) {
            return 0;
        }
        Vec3d look = new Vec3d(at.getX(), at.getY() + 1.4, at.getZ());
        int done = 0;
        for (NullBody body : members) {
            try {
                Vec3d bodyPosition = body.bodyPosition();
                if (bodyPosition.distanceTo(look) > GREET_DISTANCE) {
                    continue;
                }
                if (plugin.brain() != null) {
                    plugin.brain().attend(body, target.getUniqueId(), 60);
                    plugin.brain().acknowledge(body, plugin.brain().mind(body));
                } else {
                    body.lookAt(look);
                }
                done++;
            } catch (Throwable t) {
                logger.log(Level.FINE, "[NullArmy] greeting skipped: " + Guard.describe(t));
            }
        }
        playGestureSound(at, "wave");
        // Chat silence: the greeting is the gesture. The issuer gets the count as
        // the command's answer; the greeted player gets no plugin message.
        return done;
    }

    /** The tactics of an owner's squad, or "balanced" when they have none. */
    public String tactics(UUID owner) {
        List<Squad> squads = owner == null ? null : byOwner.get(owner);
        if (squads == null || squads.isEmpty()) {
            return "balanced";
        }
        return squads.get(0).tactics;
    }

    /** Sets the tactics of every squad an owner has. False when they have none. */
    public boolean setTactics(UUID owner, String style) {
        List<Squad> squads = owner == null ? null : byOwner.get(owner);
        if (squads == null || squads.isEmpty() || style == null) {
            return false;
        }
        for (Squad squad : squads) {
            squad.tactics = style;
        }
        return true;
    }

    /** One readable line per occupied slot in an owner's Nulls' inventories. */
    public List<String> describeInventories(UUID owner) {
        List<String> out = new ArrayList<>();
        List<NullBody> members = membersOf(owner);
        for (NullBody body : members) {
            try {
                ItemLedger ledger = body.inventory();
                if (ledger == null) {
                    continue;
                }
                String summary = ledger.contents().isEmpty()
                        ? "empty"
                        : ledger.contents().size() + " stack(s), " + ledger.total() + " item(s)";
                out.add(shortName(body) + ": " + summary);
            } catch (Throwable t) {
                out.add("a Null: unreadable (" + Guard.describe(t) + ")");
            }
        }
        return out;
    }

    /** A short, stable label for a body: commander mark plus profile name. */
    private static String shortName(NullBody body) {
        try {
            String name = body.profileName();
            return name == null || name.isEmpty() ? ("#" + body.id()) : name;
        } catch (Throwable t) {
            return "a Null";
        }
    }

    /** A gesture sound, if the world is still there. Never fatal. */
    private void playGestureSound(Location where, String gesture) {
        if (where == null || where.getWorld() == null) {
            return;
        }
        Guard.attempt(logger, "gesture sound", () -> {
            org.bukkit.Sound sound = "dance".equals(gesture)
                    ? org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT
                    : org.bukkit.Sound.ENTITY_VILLAGER_AMBIENT;
            where.getWorld().playSound(where, sound, 0.4f, 1.4f);
        });
    }

    private void forEachSquad(java.util.function.Consumer<Squad> action) {
        for (List<Squad> squads : new ArrayList<>(byOwner.values())) {
            for (Squad squad : new ArrayList<>(squads)) {
                try {
                    action.accept(squad);
                } catch (Throwable t) {
                    logger.log(Level.FINE, "[NullArmy] squad action skipped: " + Guard.describe(t));
                }
            }
        }
    }
}
