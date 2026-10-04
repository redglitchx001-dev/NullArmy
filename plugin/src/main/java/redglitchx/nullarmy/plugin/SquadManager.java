package redglitchx.nullarmy.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.nav.BlockView;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.skin.SkinData;
import redglitchx.nullarmy.plugin.util.Guard;

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

    private final NullArmyPlugin plugin;
    private final Logger logger;
    private final VersionAdapter adapter;
    private final Map<UUID, List<Squad>> byOwner = new LinkedHashMap<>();

    private PluginConfig config;
    private Caps caps;

    private boolean shutdownRequested;

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

        List<Vec3d> spots = planSpawnSpots(worldName, origin, count);
        if (spots.isEmpty()) {
            discardEmptyOwnerEntry(owner, existing);
            throw new IllegalStateException("no collision-safe ground within "
                    + config.spawnSearchRadius() + " blocks of you");
        }

        Squad squad = spawnAll(owner, worldName, spots, false, existing);

        // The portal is cosmetic and comes last: a Null is never hidden behind
        // an exception thrown by a particle effect.
        playPortals(worldName, origin);
        return squad;
    }

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

    /** Spawns every Null of a new squad, rolling all of them back on failure. */
    private Squad spawnAll(UUID owner, String worldName, List<Vec3d> spots,
                           boolean airborne, List<Squad> existing) {
        Squad squad = new Squad(owner, worldName);
        try {
            for (Vec3d spot : spots) {
                NullBody body = spawnOne(owner, worldName, spot, airborne);
                squad.members.add(body);
            }
        } catch (RuntimeException e) {
            // Roll back everything this squad created: a failed summon must
            // leave the world exactly as it was.
            for (NullBody body : squad.members) {
                Guard.attempt(logger, "rolling back a failed Null",
                        () -> body.destroy());
            }
            squad.members.clear();
            discardEmptyOwnerEntry(owner, existing);
            throw e;
        }

        assignCommanders(squad);
        existing.add(squad);
        return squad;
    }

    /** Removes an owner entry that was only created by a failed preflight. */
    private void discardEmptyOwnerEntry(UUID owner, List<Squad> existing) {
        if (existing != null && existing.isEmpty()) {
            byOwner.remove(owner);
        }
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
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Nulls may only be created on the main server thread");
        }

        String skinValue = "";
        String skinSignature = "";
        try {
            if (plugin.skins() != null && config != null) {
                SkinData skin = plugin.skins().resolveCached(config.nullSkinName());
                if (skin != null && skin.complete()) {
                    skinValue = skin.value();
                    skinSignature = skin.signature();
                }
            }
        } catch (Throwable t) {
            // Cosmetic only: a Null without a skin is still a Null.
            logger.fine("[NullArmy] skin lookup skipped: " + Guard.describe(t));
        }

        VersionAdapter.SpawnRequest request = new VersionAdapter.SpawnRequest(
                owner, NameGenerator.next(), worldName, spot, 36 * 64,
                skinValue, skinSignature, airborne);
        try {
            NullBody body = adapter.spawnNull(request);
            if (body == null) {
                throw new IllegalStateException("the adapter returned no entity");
            }
            return body;
        } catch (Throwable t) {
            String reason = Guard.describe(t);
            plugin.spawnBreaker().trip(reason, t, logger,
                    "Null creation is now latched off; the server is unaffected."
                            + " Restart or run /null reload to try again after fixing the cause.");
            throw new IllegalStateException("the server refused to create the NPC: " + reason);
        }
    }

    /** Plays the summon portal effects. Cosmetic, throttled, never fatal. */
    public void playPortals(String worldName, Vec3d at) {
        int effects = Math.max(Caps.minPortalEffects(),
                caps == null ? Caps.minPortalEffects() : caps.portalEffectsPerSummon());
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

        // Ring 0 is the summoner's own feet block: always try it first.
        if (isSafe(worldName, origin)) {
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
                if (isSafe(worldName, candidate)) {
                    out.add(candidate);
                }
            }
        }
        return out;
    }

    private static boolean alreadyUsed(List<Vec3d> used, Vec3d candidate) {
        for (Vec3d existing : used) {
            double dx = existing.x() - candidate.x();
            double dz = existing.z() - candidate.z();
            if (dx * dx + dz * dz < 0.25) {
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
                if (!shutdownRequested) {
                    steer(squad, tickCounter);
                }
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
                it.remove();
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

    private void steerOne(Squad squad, NullBody body, Vec3d target, int index) {
        try {
            Vec3d here = body.bodyPosition();
            Vec3d delta = target.sub(here);
            double distance = delta.horizontalLength();

            if (distance <= ARRIVE_DISTANCE) {
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
                if (Guard.attempt(logger, "dismissing a Null", () -> body.destroy())) {
                    removed++;
                }
            }
            squad.members.clear();
        }
        return removed;
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
