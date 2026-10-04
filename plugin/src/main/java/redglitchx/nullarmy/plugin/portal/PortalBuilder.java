package redglitchx.nullarmy.plugin.portal;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds a real, temporary portal a Null can walk out of.
 *
 * <h2>Why blocks and not particles</h2>
 * A particle effect is not an entrance: nothing is there, nobody comes out of
 * anything, and the client can walk straight through it. This builder places a
 * real frame of {@code OBSIDIAN} with a real {@code NETHER_PORTAL} interior, so
 * the doorway exists in the world, lights the area, throws vanilla portal
 * particles of its own, and the Null is spawned in front of its opening.
 *
 * <h2>Why it can never damage a map</h2>
 * <ul>
 *   <li><b>Nothing is overwritten.</b> A site is only used when every one of the
 *       16 blocks the frame and interior need is already air. One existing block
 *       - a wall, a torch, a leaf, a player's build - and the site is refused and
 *       the next candidate is tried.</li>
 *   <li><b>Everything is recorded.</b> Each block the builder touches is stored
 *       with its original {@link BlockData}, so {@link BuiltPortal#restore} puts
 *       the world back exactly as it was. Air goes back to air.</li>
 *   <li><b>No physics, no fire, no spread.</b> Blocks are written with physics
 *       off, so placing a portal block cannot set anything alight, update a
 *       neighbour into falling, or create a second portal.</li>
 *   <li><b>No travel.</b> {@link PortalManager} cancels the portal events for
 *       these blocks, so nobody is sent to the Nether by a Null's doorway.</li>
 * </ul>
 *
 * <h2>Shape</h2>
 * Four blocks wide, four tall, standing on verified ground: two obsidian columns,
 * an obsidian lintel, obsidian feet, and a two-wide three-tall portal interior
 * whose lowest row is at the level a Null stands on. Exit spots are the blocks
 * immediately in front of and behind that opening, each one validated as
 * collision-safe and free of other entities before anything is spawned there.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PortalBuilder {

    /** Frame width in blocks, including both columns. */
    public static final int WIDTH = 4;

    /** Frame height in blocks, including the feet row and the lintel. */
    public static final int HEIGHT = 4;

    /** Interior width: the doorway a Null steps out of. */
    public static final int INTERIOR_WIDTH = 2;

    /** Interior height. */
    public static final int INTERIOR_HEIGHT = 3;

    /** One built doorway, with everything needed to undo it. */
    public static final class BuiltPortal {

        private final String worldName;
        private final Map<BlockKey, BlockData> changed = new LinkedHashMap<>();
        private final List<Vec3d> exits;
        private final Vec3d center;
        private final long expiresAtTick;
        private boolean restored;

        BuiltPortal(String worldName, Vec3d center, List<Vec3d> exits, long expiresAtTick) {
            this.worldName = worldName;
            this.center = center;
            this.exits = Collections.unmodifiableList(new ArrayList<>(exits));
            this.expiresAtTick = expiresAtTick;
        }

        public String worldName() { return worldName; }

        /** The point the doorway is centred on, at foot level. */
        public Vec3d center() { return center; }

        /** Validated spots a Null may be spawned at, in front of and behind the opening. */
        public List<Vec3d> exits() { return exits; }

        public long expiresAtTick() { return expiresAtTick; }

        public boolean isRestored() { return restored; }

        /** How many blocks this portal occupies. */
        public int blockCount() { return changed.size(); }

        void record(BlockKey key, BlockData original) {
            changed.put(key, original);
        }

        /** True when this portal owns that block, for the travel-cancel listener. */
        public boolean owns(World world, int x, int y, int z) {
            if (world == null || !world.getName().equals(worldName)) {
                return false;
            }
            return changed.containsKey(new BlockKey(x, y, z));
        }

        /** True when this portal owns the block at that location. */
        public boolean owns(Location location) {
            return location != null && location.getWorld() != null
                    && owns(location.getWorld(), location.getBlockX(),
                            location.getBlockY(), location.getBlockZ());
        }

        /**
         * Puts every block back the way it was.
         *
         * @return how many blocks were restored
         */
        int restore(World world, Logger logger) {
            if (restored || world == null) {
                return 0;
            }
            int done = 0;
            for (Map.Entry<BlockKey, BlockData> entry : changed.entrySet()) {
                BlockKey key = entry.getKey();
                BlockData original = entry.getValue();
                try {
                    Block block = world.getBlockAt(key.x, key.y, key.z);
                    // physics=false: restoring air must not make sand fall, water
                    // flow or a torch pop off. The world goes back, nothing more.
                    block.setBlockData(original == null
                            ? Material.AIR.createBlockData() : original, false);
                    done++;
                } catch (Throwable t) {
                    // One stubborn block must not stop the rest of the undo.
                    logger.fine("[NullArmy] could not restore a portal block at "
                            + key.x + "," + key.y + "," + key.z + ": " + Guard.describe(t));
                }
            }
            changed.clear();
            restored = true;
            return done;
        }
    }

    /** A block position, usable as a map key. */
    private static final class BlockKey {
        private final int x;
        private final int y;
        private final int z;

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

    private final Logger logger;

    public PortalBuilder(Logger logger) {
        this.logger = logger;
    }

    /**
     * Searches for a site near {@code origin} and builds a doorway on it.
     *
     * @param adapter       used for the collision and entity-space checks
     * @param world         the world to build in
     * @param origin        where the summoner stood
     * @param searchRadius  how far to look, in blocks
     * @param lifetimeTicks how long the doorway may stay before it is restored
     * @param now           the current server tick
     * @return the built portal, or a refusal naming the real reason
     */
    public Result build(VersionAdapter adapter, World world, Vec3d origin,
                        int searchRadius, long lifetimeTicks, long now) {
        if (world == null || origin == null) {
            return Result.refused("no world or origin to build in", 0);
        }
        int radius = Math.max(1, Math.min(24, searchRadius));
        int tried = 0;
        // Ring 0 first (right where the summoner stood), then outward. Both
        // orientations are tried at every candidate: a doorway that fits along X
        // often does not fit along Z, and vice versa.
        for (int ring = 0; ring <= radius; ring++) {
            double r = ring * 1.5;
            int points = ring == 0 ? 1 : Math.max(6, (int) Math.round(2.0 * Math.PI * r / 1.5));
            for (int i = 0; i < points; i++) {
                double angle = (2.0 * Math.PI * i) / points;
                int cx = (int) Math.floor(origin.x() + (ring == 0 ? 0 : Math.cos(angle) * r));
                int cz = (int) Math.floor(origin.z() + (ring == 0 ? 0 : Math.sin(angle) * r));
                for (int dy = 0; dy <= 2; dy++) {
                    int feetY = (int) Math.floor(origin.y()) + dy;
                    for (boolean widthOnX : new boolean[] {true, false}) {
                        tried++;
                        Result result = trySite(adapter, world, cx, feetY, cz, widthOnX,
                                lifetimeTicks, now);
                        if (result.succeeded()) {
                            return result;
                        }
                    }
                }
            }
        }
        return Result.refused("no site near you has " + (WIDTH * HEIGHT)
                + " free blocks for a doorway and safe ground to step out onto ("
                + tried + " sites tried)", tried);
    }

    /** Attempts one site in one orientation. Never throws. */
    private Result trySite(VersionAdapter adapter, World world, int cx, int feetY, int cz,
                           boolean widthOnX, long lifetimeTicks, long now) {
        try {
            if (!siteIsClear(world, cx, feetY, cz, widthOnX)) {
                return Result.refused("site is not clear", 1);
            }
            if (!siteHasGround(world, cx, feetY, cz, widthOnX)) {
                return Result.refused("site has no ground under the frame", 1);
            }
            List<Vec3d> exits = exitSpots(adapter, world, cx, feetY, cz, widthOnX);
            if (exits.isEmpty()) {
                return Result.refused("no collision-safe spot to step out onto", 1);
            }
            BuiltPortal portal = new BuiltPortal(world.getName(),
                    new Vec3d(cx, feetY, cz), exits, now + Math.max(20L, lifetimeTicks));
            place(world, cx, feetY, cz, widthOnX, portal);
            return Result.built(portal);
        } catch (Throwable t) {
            logger.fine("[NullArmy] portal site refused: " + Guard.describe(t));
            return Result.refused("the site could not be checked (" + Guard.describe(t) + ")", 1);
        }
    }

    /** True when every block the frame and interior need is already air. */
    private boolean siteIsClear(World world, int cx, int feetY, int cz, boolean widthOnX) {
        for (int w = 0; w < WIDTH; w++) {
            for (int h = 0; h < HEIGHT; h++) {
                int x = widthOnX ? cx - 1 + w : cx;
                int z = widthOnX ? cz : cz - 1 + w;
                int y = feetY + h;
                if (y > world.getMaxHeight() || y < world.getMinHeight()) {
                    return false;
                }
                if (!world.getBlockAt(x, y, z).getType().isAir()) {
                    // Never overwrite an existing block. Not one.
                    return false;
                }
            }
        }
        return true;
    }

    /** True when the frame's feet row rests on something solid. */
    private boolean siteHasGround(World world, int cx, int feetY, int cz, boolean widthOnX) {
        for (int w = 0; w < WIDTH; w++) {
            int x = widthOnX ? cx - 1 + w : cx;
            int z = widthOnX ? cz : cz - 1 + w;
            if (world.getBlockAt(x, feetY - 1, z).getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The validated spots Nulls emerge at.
     *
     * <p>In front of the opening first, then behind it: a doorway is a passage,
     * and a squad of four should not be stacked in one block. Every spot is
     * checked for block collision <b>and</b> for another entity already standing
     * there, so nobody appears inside a wall, a block or a body.</p>
     */
    private List<Vec3d> exitSpots(VersionAdapter adapter, World world, int cx, int feetY, int cz,
                                  boolean widthOnX) {
        List<Vec3d> out = new ArrayList<>();
        int[][] candidates = widthOnX
                ? new int[][] {{cx, cz + 1}, {cx + 1, cz + 1}, {cx, cz - 1}, {cx + 1, cz - 1}}
                : new int[][] {{cx + 1, cz}, {cx + 1, cz + 1}, {cx - 1, cz}, {cx - 1, cz + 1}};
        for (int[] candidate : candidates) {
            Vec3d spot = new Vec3d(candidate[0] + 0.5, feetY, candidate[1] + 0.5);
            if (!isSafe(adapter, world.getName(), spot)) {
                continue;
            }
            if (!isFree(adapter, world.getName(), spot)) {
                continue;
            }
            out.add(spot);
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
            // An adapter without the entity check reports "free"; the block check
            // above still has to pass, so this can never make a spot unsafe.
            return adapter == null || adapter.isEntitySpaceFree(worldName, spot);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Writes the frame and the interior, recording every block it changes. */
    private void place(World world, int cx, int feetY, int cz, boolean widthOnX,
                       BuiltPortal portal) {
        BlockData frame = Material.OBSIDIAN.createBlockData();
        BlockData interior = portalInterior(widthOnX);
        for (int w = 0; w < WIDTH; w++) {
            for (int h = 0; h < HEIGHT; h++) {
                int x = widthOnX ? cx - 1 + w : cx;
                int z = widthOnX ? cz : cz - 1 + w;
                int y = feetY + h;
                boolean inside = w >= 1 && w <= INTERIOR_WIDTH && h < INTERIOR_HEIGHT;
                Block block = world.getBlockAt(x, y, z);
                portal.record(new BlockKey(x, y, z), block.getBlockData());
                // physics=false: no neighbour updates, no fire, no falling sand.
                block.setBlockData(inside ? interior : frame, false);
            }
        }
        // Vanilla's own portal sound and particles, at the opening.
        try {
            Location mouth = new Location(world,
                    widthOnX ? cx + 0.5 : cx + 0.5,
                    feetY + 1.0,
                    widthOnX ? cz + 0.5 : cz + 0.5);
            world.spawnParticle(org.bukkit.Particle.PORTAL, mouth, 40, 0.4, 0.9, 0.4, 0.02);
            world.playSound(mouth, org.bukkit.Sound.BLOCK_PORTAL_TRIGGER, 0.8f, 1.0f);
            world.playSound(mouth, org.bukkit.Sound.BLOCK_PORTAL_AMBIENT, 0.5f, 1.0f);
        } catch (Throwable t) {
            logger.fine("[NullArmy] portal effects skipped: " + Guard.describe(t));
        }
    }

    /**
     * The interior block, with its axis turned the way the frame is.
     *
     * <p>A portal whose texture runs the wrong way looks broken even though it
     * works, so the axis state is set explicitly. If this server cannot parse the
     * state string, the plain block data is used instead - the doorway still
     * builds.</p>
     */
    private BlockData portalInterior(boolean widthOnX) {
        try {
            return org.bukkit.Bukkit.createBlockData(Material.NETHER_PORTAL,
                    "[axis=" + (widthOnX ? "x" : "z") + "]");
        } catch (Throwable t) {
            return Material.NETHER_PORTAL.createBlockData();
        }
    }
}
