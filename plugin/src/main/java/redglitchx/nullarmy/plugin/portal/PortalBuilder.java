package redglitchx.nullarmy.plugin.portal;

import org.bukkit.Axis;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.portal.PortalFrame;
import redglitchx.nullarmy.core.zone.SummonZone;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds temporary arrival doorways in the real world.
 *
 * <h2>What a doorway is</h2>
 * <p>An upright, complete obsidian frame - 4 wide, 5 tall, 14 blocks - around a
 * 2 x 3 opening filled with real {@link Material#NETHER_PORTAL} blocks
 * ({@link PortalFrame}). The portal is visible and physical; portal-travel events
 * for blocks owned by this manager are cancelled, so nobody is sent to the
 * Nether. Portal particles and sounds add the visual flourish.</p>
 *
 * <h2>Where</h2>
 * <p>Inside the summon zone only, at random spots around the summoner. A
 * <b>ground</b> doorway stands on solid support with a two-block apron of air
 * (and a solid floor) in front of its opening; a <b>floating</b> one hangs
 * {@code portals.air-height-min..max} blocks above the ground and its Nulls drop
 * out of it with real fall damage. Every frame, interior and apron block must be
 * air before anything is placed - no terrain is ever cut into - and every block
 * that is changed is recorded and restored when the doorway closes.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PortalBuilder {

    /** Frame width in blocks, including both columns. */
    public static final int WIDTH = PortalFrame.OUTER_WIDTH;

    /** Frame height in blocks, including the top and bottom rows. */
    public static final int HEIGHT = PortalFrame.OUTER_HEIGHT;

    /** Opening width. */
    public static final int INTERIOR_WIDTH = PortalFrame.INNER_WIDTH;

    /** Opening height. */
    public static final int INTERIOR_HEIGHT = PortalFrame.INNER_HEIGHT;

    /** One doorway standing in the world. */
    public static final class BuiltPortal {

        private final String worldName;
        private final Map<BlockKey, BlockData> changed = new LinkedHashMap<>();
        private int restoredBlockCount;
        private boolean restoreRequested;
        private final List<Vec3d> exits;
        private final Vec3d center;
        private final long expiresAtTick;
        private final long builtTick;
        private final PortalFrame frame;
        private final Set<UUID> assigned = new HashSet<>();
        private boolean restored;

        BuiltPortal(String worldName, PortalFrame frame, List<Vec3d> exits, long builtTick, long expiresAtTick) {
            this.worldName = worldName;
            this.frame = frame;
            double[] c = frame.center();
            this.center = new Vec3d(c[0], c[1], c[2]);
            this.exits = Collections.unmodifiableList(new ArrayList<>(exits));
            this.builtTick = builtTick;
            this.expiresAtTick = expiresAtTick;
        }

        public String worldName() { return worldName; }

        public Vec3d center() { return center; }

        /** Spawn spots: inside the opening first, then the apron of a ground doorway. */
        public List<Vec3d> exits() { return exits; }

        public long expiresAtTick() { return expiresAtTick; }

        public long builtTick() { return builtTick; }

        public boolean isRestored() { return restored; }

        boolean restorationRequested() { return restoreRequested; }

        void requestRestore() { restoreRequested = true; }

        /** Total number of blocks this doorway changed, including blocks already restored. */
        public int blockCount() { return restoredBlockCount + changed.size(); }

        /** How many changed blocks still need restoration. */
        public int pendingRestoreCount() { return changed.size(); }

        public PortalFrame frame() { return frame; }

        /** Where a Null that arrived here walks to. */
        public Vec3d stepOutPoint() {
            double[] p = frame.stepOutPoint();
            return new Vec3d(p[0], p[1], p[2]);
        }

        /** Remembers a Null that came through this doorway. */
        public void assign(UUID body) {
            if (body != null) {
                assigned.add(body);
            }
        }

        public Set<UUID> assigned() { return Collections.unmodifiableSet(assigned); }

        void record(BlockKey key, BlockData original) {
            changed.putIfAbsent(key, original);
        }

        public boolean owns(World world, int x, int y, int z) {
            if (world == null || !world.getName().equals(worldName)) {
                return false;
            }
            return changed.containsKey(new BlockKey(x, y, z));
        }

        public boolean owns(Location location) {
            return location != null && location.getWorld() != null
                    && owns(location.getWorld(), location.getBlockX(),
                            location.getBlockY(), location.getBlockZ());
        }

        int restore(World world, Logger logger) {
            if (restored || world == null) {
                return 0;
            }
            int done = 0;
            List<BlockKey> restoredKeys = new ArrayList<>();
            for (Map.Entry<BlockKey, BlockData> entry : changed.entrySet()) {
                BlockKey key = entry.getKey();
                BlockData original = entry.getValue();
                try {
                    Block block = world.getBlockAt(key.x, key.y, key.z);
                    // physics=false: restoring air must not make sand fall, water
                    // flow or a torch pop off. The world goes back, nothing more.
                    block.setBlockData(original == null
                            ? Material.AIR.createBlockData() : original, false);
                    restoredKeys.add(key);
                    done++;
                } catch (Throwable t) {
                    // One stubborn block must not stop the rest of the undo. Keep
                    // its snapshot so a later cleanup pass can retry it.
                    logger.fine("[NullArmy] could not restore a portal block at "
                            + key.x + "," + key.y + "," + key.z + ": " + Guard.describe(t));
                }
            }
            for (BlockKey key : restoredKeys) {
                changed.remove(key);
            }
            restoredBlockCount += restoredKeys.size();
            restored = changed.isEmpty();
            return done;
        }
    }

    /** A block position, usable as a map key. */
    static final class BlockKey {
        final int x;
        final int y;
        final int z;

        BlockKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof BlockKey)) {
                return false;
            }
            BlockKey key = (BlockKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override
        public int hashCode() {
            return (x * 31 + y) * 31 + z;
        }
    }

    /** Why a site was refused, for the report the owner sees. */
    public static final class Refusal {
        private final String reason;
        private final int sitesTried;

        Refusal(String reason, int sitesTried) {
            this.reason = reason;
            this.sitesTried = sitesTried;
        }

        public String reason() { return reason; }
        public int sitesTried() { return sitesTried; }
    }

    /** A successful build: the portal, or the reason there is none. */
    public static final class Result {
        private final BuiltPortal portal;
        private final Refusal refusal;

        private Result(BuiltPortal portal, Refusal refusal) {
            this.portal = portal;
            this.refusal = refusal;
        }

        static Result built(BuiltPortal portal) { return new Result(portal, null); }
        static Result refused(String reason, int tried) {
            return new Result(null, new Refusal(reason, tried));
        }

        public boolean succeeded() { return portal != null; }
        public BuiltPortal portal() { return portal; }
        public Refusal refusal() { return refusal; }
    }

    /** Where to look and what kind of doorway to prefer. */
    public static final class Request {
        final SummonZone zone;
        final int originY;
        final double floatingChance;
        final int airMin;
        final int airMax;
        final long lifetimeTicks;
        final List<BuiltPortal> avoid;
        final boolean particlesEnabled;

        public Request(SummonZone zone, int originY, double floatingChance, int airMin, int airMax,
                       long lifetimeTicks, List<BuiltPortal> avoid) {
            this(zone, originY, floatingChance, airMin, airMax, lifetimeTicks, avoid, true);
        }

        public Request(SummonZone zone, int originY, double floatingChance, int airMin, int airMax,
                       long lifetimeTicks, List<BuiltPortal> avoid, boolean particlesEnabled) {
            this.zone = zone;
            this.originY = originY;
            this.floatingChance = floatingChance;
            this.airMin = airMin;
            this.airMax = airMax;
            this.lifetimeTicks = lifetimeTicks;
            this.avoid = avoid == null ? Collections.emptyList() : avoid;
            this.particlesEnabled = particlesEnabled;
        }
    }

    private static final int SITE_ATTEMPTS = 90;

    private static long expiryTick(long now, long lifetimeTicks) {
        // Keep the legacy 3-second floor, while avoiding overflow for callers
        // that use an unusually large lifetime. Persistence is handled by the
        // manager's sweep rather than by changing this recorded deadline.
        long duration = Math.max(60L, lifetimeTicks);
        return now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration;
    }

    private final Logger logger;
    private final Random random = new Random();

    public PortalBuilder(Logger logger) {
        this.logger = logger;
    }

    /** The legacy entry point: a ground-or-floating doorway near {@code origin}. */
    public Result build(VersionAdapter adapter, World world, Vec3d origin,
                        int searchRadius, long lifetimeTicks, long now) {
        if (world == null || origin == null) {
            return Result.refused("no world or origin to build in", 0);
        }
        SummonZone zone = new SummonZone(origin.x(), origin.z(), Math.max(SummonZone.MIN_SIZE, searchRadius * 4));
        return build(adapter, world, origin, new Request(zone, (int) Math.floor(origin.y()), 0.0D, 4, 12,
                lifetimeTicks, null), now);
    }

    /**
     * Builds one doorway inside the request's zone.
     *
     * <p>Sites are tried at random distances (3 to 22 blocks, never past the
     * zone) and angles around {@code origin}, both orientations, with the
     * opening facing the summoner. A floating site is tried with probability
     * {@code floatingChance}, a ground site otherwise (and as the fallback).</p>
     */
    public Result build(VersionAdapter adapter, World world, Vec3d origin, Request request, long now) {
        if (world == null || origin == null || request == null || request.zone == null) {
            return Result.refused("no world, origin or zone to build in", 0);
        }
        int tried = 0;
        String lastReason = "no site was tried";
        double maxRadius = Math.max(3.0D, Math.min(22.0D, request.zone.half() - 4.0D));
        for (int attempt = 0; attempt < SITE_ATTEMPTS; attempt++) {
            double radius = 3.0D + random.nextDouble() * Math.max(0.5D, maxRadius - 3.0D);
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int cx = (int) Math.floor(origin.x() + Math.cos(angle) * radius);
            int cz = (int) Math.floor(origin.z() + Math.sin(angle) * radius);
            boolean floating = random.nextDouble() < request.floatingChance;
            for (int kindTry = 0; kindTry < 2; kindTry++) {
                PortalFrame.Kind kind = (floating ^ kindTry == 1) ? PortalFrame.Kind.FLOATING : PortalFrame.Kind.GROUND;
                for (boolean widthOnX : new boolean[] {random.nextBoolean(), true, false}) {
                    tried++;
                    PortalFrame frame = siteFor(world, cx, cz, request, kind, widthOnX, origin);
                    if (frame == null) {
                        lastReason = "no ground within reach of the zone height";
                        continue;
                    }
                    String problem = problemWith(adapter, world, frame, request);
                    if (problem != null) {
                        lastReason = problem;
                        continue;
                    }
                    List<Vec3d> exits = exitSpots(adapter, world, frame);
                    if (exits.isEmpty()) {
                        lastReason = "no collision-safe spot inside the opening";
                        continue;
                    }
                    BuiltPortal portal = new BuiltPortal(world.getName(), frame, exits, now,
                            expiryTick(now, request.lifetimeTicks));
                    try {
                        place(world, frame, portal, request.particlesEnabled);
                    } catch (RuntimeException failure) {
                        return Result.refused("could not safely place a doorway (" + Guard.describe(failure) + ")",
                                tried);
                    }
                    return Result.built(portal);
                }
            }
        }
        return Result.refused("no valid doorway site inside the " + request.zone.size() + "x" + request.zone.size()
                + " zone (" + tried + " sites tried; last problem: " + lastReason + ")", tried);
    }

    /**
     * The frame for a site: for a ground doorway the base sits on the highest
     * standable block near the zone height; for a floating one it hangs a random
     * {@code air-height-min..max} above that ground. The opening faces the summoner.
     */
    private PortalFrame siteFor(World world, int cx, int cz, Request request, PortalFrame.Kind kind,
                                boolean widthOnX, Vec3d origin) {
        Integer ground = groundTop(world, cx, cz, request.originY);
        if (ground == null) {
            return null;
        }
        int baseX = widthOnX ? cx - 1 : cx;
        int baseZ = widthOnX ? cz : cz - 1;
        double across = widthOnX ? origin.z() - (cz + 0.5D) : origin.x() - (cx + 0.5D);
        int exitSide = across >= 0 ? 1 : -1;
        int baseY = ground + 1;
        if (kind == PortalFrame.Kind.FLOATING) {
            int gap = request.airMin + random.nextInt(Math.max(1, request.airMax - request.airMin + 1));
            baseY = ground + 1 + gap;
            if (baseY + PortalFrame.OUTER_HEIGHT >= world.getMaxHeight()) {
                return null;
            }
        }
        return new PortalFrame(baseX, baseY, baseZ, widthOnX, kind, exitSide);
    }

    /** The highest solid block with two blocks of air above it, within +-8 of the zone height. */
    private static Integer groundTop(World world, int x, int z, int originY) {
        int top = Math.min(world.getMaxHeight() - 8, originY + 8);
        int bottom = Math.max(world.getMinHeight(), originY - 8);
        for (int y = top; y >= bottom; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isSolid() && world.getBlockAt(x, y + 1, z).getType().isAir()
                    && world.getBlockAt(x, y + 2, z).getType().isAir()) {
                return y;
            }
        }
        return null;
    }

    /** Why this frame may not be built here, or null when it may. */
    String problemWith(VersionAdapter adapter, World world, PortalFrame frame, Request request) {
        int[] fp = frame.footprint();
        if (!request.zone.containsBox(fp[0], fp[1], fp[2] + 1, fp[3] + 1)) {
            return "the doorway would reach outside the summon zone";
        }
        for (BuiltPortal other : request.avoid) {
            if (other == null || other.isRestored() || !other.worldName().equals(world.getName())) {
                continue;
            }
            int[] ofp = other.frame().footprint();
            boolean overlap = fp[0] <= ofp[2] + 1 && fp[2] >= ofp[0] - 1 && fp[1] <= ofp[3] + 1 && fp[3] >= ofp[1] - 1
                    && Math.abs(other.frame().baseY() - frame.baseY()) < PortalFrame.OUTER_HEIGHT + 2;
            if (overlap) {
                return "another doorway already stands there";
            }
        }
        if (frame.baseY() < world.getMinHeight() + 1 || frame.baseY() + PortalFrame.OUTER_HEIGHT > world.getMaxHeight()) {
            return "the frame would leave the world's height limits";
        }
        List<String> problems = frame.siteProblems(lookup(world), request.airMin, request.airMax);
        return problems.isEmpty() ? null : problems.get(0);
    }

    /** Read access to the world for the core rules. */
    static PortalFrame.Lookup lookup(World world) {
        return new PortalFrame.Lookup() {
            @Override
            public String type(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return "VOID_AIR";
                }
                return world.getBlockAt(x, y, z).getType().name();
            }

            @Override
            public boolean solid(int x, int y, int z) {
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    return false;
                }
                return world.getBlockAt(x, y, z).getType().isSolid();
            }
        };
    }

    /** Problems with a doorway as it stands now; empty when its obsidian and portal blocks are complete. */
    public List<String> validate(World world, BuiltPortal portal, int airMin, int airMax) {
        if (world == null || portal == null) {
            return Collections.singletonList("no doorway");
        }
        return portal.frame().builtProblems(lookup(world), airMin, airMax);
    }

    private List<Vec3d> exitSpots(VersionAdapter adapter, World world, PortalFrame frame) {
        List<Vec3d> out = new ArrayList<>();
        // Inside the opening, standing on the bottom row of the frame: the frame is
        // not built yet, so the spot is checked against the block it will stand on.
        for (double[] spot : frame.insideSpots()) {
            out.add(new Vec3d(spot[0], spot[1], spot[2]));
        }
        if (frame.kind() == PortalFrame.Kind.GROUND) {
            List<int[]> floor = frame.apronFloorCells();
            for (int[] cell : floor) {
                Vec3d spot = new Vec3d(cell[0] + 0.5D, cell[1] + 1, cell[2] + 0.5D);
                if (isSafe(adapter, world.getName(), spot) && isFree(adapter, world.getName(), spot)) {
                    out.add(spot);
                }
            }
        }
        return out;
    }

    private boolean isSafe(VersionAdapter adapter, String worldName, Vec3d spot) {
        try {
            return adapter != null && adapter.isSpawnSafe(worldName, spot);
        } catch (Throwable t) {
            return false; // fail closed
        }
    }

    private boolean isFree(VersionAdapter adapter, String worldName, Vec3d spot) {
        try {
            return adapter == null || adapter.isEntitySpaceFree(worldName, spot);
        } catch (Throwable t) {
            return false;
        }
    }

    private void place(World world, PortalFrame frame, BuiltPortal portal, boolean particlesEnabled) {
        BlockData obsidian = Material.OBSIDIAN.createBlockData();
        BlockData portalData = Material.NETHER_PORTAL.createBlockData();
        if (!(portalData instanceof Orientable)) {
            throw new IllegalStateException("NETHER_PORTAL block data does not expose an orientation axis");
        }
        ((Orientable) portalData).setAxis(frame.widthOnX() ? Axis.X : Axis.Z);
        try {
            for (int[] cell : frame.frameCells()) {
                Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
                portal.record(new BlockKey(cell[0], cell[1], cell[2]), block.getBlockData());
                // physics=false: no neighbour updates, no fire, no falling sand.
                block.setBlockData(obsidian, false);
            }
            for (int[] cell : frame.interiorCells()) {
                Block block = world.getBlockAt(cell[0], cell[1], cell[2]);
                portal.record(new BlockKey(cell[0], cell[1], cell[2]), block.getBlockData());
                // These are real portal blocks, not particles pretending to be one.
                // PortalManager cancels travel events originating in this doorway.
                block.setBlockData(portalData, false);
            }
        } catch (RuntimeException failure) {
            // A partial doorway must never be left behind when a block write fails.
            portal.restore(world, logger);
            throw failure;
        }
        effects(world, portal, true, particlesEnabled);
    }

    /** Portal swirl inside the opening, plus the open/close sounds. */
    static void effects(World world, BuiltPortal portal, boolean withSound) {
        effects(world, portal, withSound, true);
    }

    /** Cosmetic particles can be disabled without affecting the physical doorway or its sounds. */
    static void effects(World world, BuiltPortal portal, boolean withSound, boolean particlesEnabled) {
        try {
            Vec3d c = portal.center();
            Location mouth = new Location(world, c.x(), c.y() - 0.4D, c.z());
            if (particlesEnabled) {
                double spreadAlong = 0.45D;
                boolean onX = portal.frame().widthOnX();
                world.spawnParticle(org.bukkit.Particle.PORTAL, mouth, 30,
                        onX ? spreadAlong : 0.1D, 0.8D, onX ? 0.1D : spreadAlong, 0.05D);
            }
            if (withSound) {
                world.playSound(mouth, org.bukkit.Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.2f);
                world.playSound(mouth, org.bukkit.Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f, 0.8f);
            }
        } catch (Throwable ignored) {
            // Cosmetic only.
        }
    }
}
