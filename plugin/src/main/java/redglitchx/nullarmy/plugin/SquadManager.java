package redglitchx.nullarmy.plugin;
import redglitchx.nullarmy.plugin.skin.SkinData;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.config.PluginConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns squads and their Nulls.
 *
 * <p>Implements the commander rule from spec 3: exactly two Commanders for any
 * squad of two or more, with roles <b>stored</b> rather than re-rolled each
 * tick or restart.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SquadManager {

    /** One squad belonging to one owner. */
    public static final class Squad {
        private final UUID owner;
        private final List<NullBody> members = new ArrayList<>();
        private final List<Integer> commanderIds = new ArrayList<>();

        Squad(UUID owner) { this.owner = owner; }

        public UUID owner() { return owner; }
        public List<NullBody> members() { return Collections.unmodifiableList(members); }

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

    private final NullArmyPlugin plugin;
    private final VersionAdapter adapter;
    private final Caps caps;
    private final PluginConfig config;
    private final Map<UUID, List<Squad>> byOwner = new LinkedHashMap<>();

    private boolean shutdownRequested;
    private int liveCount;

    SquadManager(NullArmyPlugin plugin, VersionAdapter adapter, Caps caps, PluginConfig config) {
        this.plugin = plugin;
        this.adapter = adapter;
        this.caps = caps;
        this.config = config;
    }

    /**
     * Creates a squad of {@code count} Nulls for {@code owner}.
     *
     * @return the created squad
     * @throws IllegalStateException if a limit would be exceeded. The spec is
     *     explicit: "reject excessive counts clearly instead of partially
     *     spawning a surprise army" - so nothing spawns on rejection.
     */
    public Squad createSquad(UUID owner, String worldName, Vec3d origin, int count) {
        if (shutdownRequested) {
            throw new IllegalStateException("plugin is shutting down");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        if (count > caps.summonHardCap()) {
            throw new IllegalStateException("requested " + count
                    + " Nulls but the hard cap is " + caps.summonHardCap());
        }
        if (liveCount + count > caps.maxLiveNpcs()) {
            throw new IllegalStateException("server Null limit reached ("
                    + liveCount + "/" + caps.maxLiveNpcs() + " live)");
        }
        List<Squad> existing = byOwner.computeIfAbsent(owner, k -> new ArrayList<>());
        if (existing.size() >= caps.maxSquadsPerOwner()) {
            throw new IllegalStateException("owner already has " + existing.size()
                    + " squads (max " + caps.maxSquadsPerOwner() + ")");
        }

        // Verify EVERY spawn position before committing anything, so we never
        // end up with a half-spawned squad.
        List<Vec3d> positions = planSpawnPositions(worldName, origin, count);
        for (Vec3d p : positions) {
            if (!adapter.isSpawnSafe(worldName, p)) {
                throw new IllegalStateException(
                        "no collision-safe spawn position near " + origin
                                + " - refusing to spawn through terrain");
            }
        }

        Squad squad = new Squad(owner);
        try {
            for (int i = 0; i < positions.size(); i++) {
                // EVERY Null wears the skin of the one configured account,
                // not just the Commander. Resolved from cache so a summon
                // never waits on a network call.
                String skinValue = "";
                String skinSignature = "";
                if (plugin != null && plugin.skins() != null) {
                    SkinData skin = plugin.skins().resolveCached(plugin.skins().skinOwner());
                    if (skin != null && skin.complete()) {
                        skinValue = skin.value();
                        skinSignature = skin.signature();
                    }
                }

                NullBody body = adapter.spawnNull(new VersionAdapter.SpawnRequest(
                        owner, NameGenerator.next(), worldName, positions.get(i), 36 * 64,
                        skinValue, skinSignature));
                squad.members.add(body);
                liveCount++;
            }
        } catch (RuntimeException e) {
            // Roll back whatever spawned so a failed summon leaves nothing behind.
            for (NullBody body : squad.members) {
                body.destroy();
                liveCount--;
            }
            throw e;
        }

        assignCommanders(squad);

        if (config.caps().portalEffectsPerSummon() > 0) {
            // Cosmetic only. Spec 3: at least 15 effects, and surplus effects
            // close empty - they never create extra Nulls.
            adapter.playPortalEffects(worldName, origin,
                    Math.max(Caps.minPortalEffects(), config.caps().portalEffectsPerSummon()));
        }

        existing.add(squad);
        return squad;
    }

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
     * Spreads spawn positions on a ring so Nulls do not start inside each
     * other (spec 1.3 - no clumping, including at spawn).
     */
    private List<Vec3d> planSpawnPositions(String worldName, Vec3d origin, int count) {
        List<Vec3d> out = new ArrayList<>();
        if (count == 1) {
            out.add(origin);
            return out;
        }
        double radius = 1.5 + (count * 0.15);
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / count;
            out.add(new Vec3d(
                    origin.x() + Math.cos(angle) * radius,
                    origin.y(),
                    origin.z() + Math.sin(angle) * radius));
        }
        return out;
    }

    /** Spec 5: SAFE_SHUTDOWN is a state the Nulls walk into, not an instant delete. */
    public void requestSafeShutdown() {
        shutdownRequested = true;
    }

    public boolean isShutdownRequested() { return shutdownRequested; }

    public int liveCount() { return liveCount; }

    public List<Squad> squadsOf(UUID owner) {
        List<Squad> squads = byOwner.get(owner);
        return squads == null ? Collections.emptyList() : Collections.unmodifiableList(squads);
    }

    /** Per-tick driver. Applies bounded steering work and reaps the dead. */
    public void tick(long tickCounter) {
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                List<NullBody> dead = new ArrayList<>();
                for (NullBody body : squad.members) {
                    if (!body.isAlive()) {
                        dead.add(body);
                        continue;
                    }
                    if (shutdownRequested) {
                        // Stop issuing movement; the Null simply stands down.
                        body.applySteering(Vec3d.ZERO);
                    }
                }
                for (NullBody body : dead) {
                    squad.members.remove(body);
                    liveCount--;
                }
                if (!squad.members.isEmpty() && squad.commanders().isEmpty()) {
                    // Commander succession after a loss (spec 3).
                    assignCommanders(squad);
                }
            }
        }
    }

    /** Emergency stop: dismiss every squad immediately (spec 8 kill switch). */
    public void dismissAll() {
        for (List<Squad> squads : byOwner.values()) {
            for (Squad squad : squads) {
                for (NullBody body : squad.members) {
                    body.destroy();
                    liveCount--;
                }
                squad.members.clear();
            }
        }
        byOwner.clear();
        liveCount = 0;
    }
}
