package redglitchx.nullarmy.core.construct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The offline builder: turns a goal like "build a hut" into physical steps
 * without any AI model.
 *
 * <p>It knows five shapes - hut, bridge, wall, tower, platform - plus wood
 * gathering. Every placement is ordered so that the block has a neighbour to be
 * placed against (bottom-up, outside-in), and a MOVE is emitted whenever the
 * next block would be out of a player's reach, so the executor never has to
 * guess where to stand. Coordinates are relative to the zone origin, exactly
 * like an AI plan.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class FallbackPlanner {

    /** The shapes this planner can build. */
    public enum Kind { HUT, BRIDGE, WALL, TOWER, PLATFORM, THRONE, GATHER }

    /** A finished plan. */
    public static final class Plan {
        private final Kind kind;
        private final List<BuildStep> steps;
        private final Map<String, Integer> materials;
        private final int shortfall;

        Plan(Kind kind, List<BuildStep> steps, Map<String, Integer> materials, int shortfall) {
            this.kind = kind;
            this.steps = Collections.unmodifiableList(steps);
            this.materials = Collections.unmodifiableMap(materials);
            this.shortfall = shortfall;
        }

        public Kind kind() { return kind; }
        public List<BuildStep> steps() { return steps; }

        /** Material -&gt; how many blocks of it the plan places. */
        public Map<String, Integer> materials() { return materials; }

        /** Blocks the plan needs beyond what was available (wood is gathered for them). */
        public int shortfall() { return shortfall; }

        public int placements() {
            int n = 0;
            for (BuildStep step : steps) {
                if (step.action() == BuildStep.Action.PLACE) {
                    n++;
                }
            }
            return n;
        }

        public String describe() {
            return kind.name().toLowerCase(Locale.ROOT) + ": " + placements() + " blocks in "
                    + steps.size() + " steps" + (shortfall > 0 ? ", " + shortfall + " blocks short" : "");
        }
    }

    /** A player's reach for placing, with a margin. */
    static final double REACH = 4.0D;

    private static final Pattern NUMBER = Pattern.compile("(\\d{1,3})");

    private FallbackPlanner() {
    }

    /** Which shape a free-text goal asks for; null when none of them. */
    public static Kind kindOf(String goal) {
        if (goal == null) {
            return null;
        }
        String g = goal.toLowerCase(Locale.ROOT);
        // P-07: a throne is a chair, not a hut, so it has to be matched first -
        // "throne" shares no keyword with the others, but "build me a throne
        // room" would otherwise fall through to the hut branch.
        if (g.contains("throne") || g.contains("chair") || g.contains("seat")) {
            return Kind.THRONE;
        }
        if (g.contains("bridge")) {
            return Kind.BRIDGE;
        }
        if (g.contains("tower") || g.contains("pillar")) {
            return Kind.TOWER;
        }
        if (g.contains("wall") || g.contains("fence")) {
            return Kind.WALL;
        }
        if (g.contains("platform") || g.contains("floor") || g.contains("deck")) {
            return Kind.PLATFORM;
        }
        if (g.contains("hut") || g.contains("house") || g.contains("shelter") || g.contains("cabin")
                || g.contains("home") || g.contains("base")) {
            return Kind.HUT;
        }
        if (g.contains("gather") || g.contains("wood") || g.contains("chop") || g.contains("log")) {
            return Kind.GATHER;
        }
        return null;
    }

    /** The first number in the goal, clamped, or {@code fallback}. */
    static int sizeOf(String goal, int fallback, int min, int max) {
        if (goal != null) {
            Matcher m = NUMBER.matcher(goal);
            if (m.find()) {
                try {
                    return Math.max(min, Math.min(max, Integer.parseInt(m.group(1))));
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        return fallback;
    }

    /**
     * Plans a shape.
     *
     * @param goal      free text ("a 7 block bridge", "small hut")
     * @param ax        anchor x relative to the zone origin (where the build starts)
     * @param ay        anchor y relative to the zone origin (the builders' feet level)
     * @param az        anchor z relative to the zone origin
     * @param facing    0 = +z (south), 1 = -x (west), 2 = -z (north), 3 = +x (east)
     * @param available material -&gt; count the squad carries (placeable blocks only)
     * @return the plan, or null when the goal names no known shape
     */
    public static Plan plan(String goal, int ax, int ay, int az, int facing, Map<String, Integer> available) {
        Kind kind = kindOf(goal);
        if (kind == null || kind == Kind.GATHER) {
            return null;
        }
        List<int[]> blocks;
        switch (kind) {
            case BRIDGE:
                blocks = bridge(sizeOf(goal, 8, 2, 32));
                break;
            case THRONE:
                blocks = throne();
                break;
            case WALL:
                blocks = wall(sizeOf(goal, 7, 2, 24), 3);
                break;
            case TOWER:
                blocks = tower(sizeOf(goal, 6, 3, 12));
                break;
            case PLATFORM:
                blocks = platform(sizeOf(goal, 5, 2, 12));
                break;
            case HUT:
            default:
                blocks = hut(sizeOf(goal, 5, 4, 9));
                break;
        }
        List<int[]> world = new ArrayList<>();
        for (int[] local : blocks) {
            int[] rotated = rotate(local[0], local[2], facing);
            world.add(new int[] {ax + rotated[0], ay + local[1], az + rotated[1],
                    local.length > 3 ? local[3] : PREF_STOCK});
        }
        return assemble(kind, world, available, ax, ay, az, facing);
    }

    /**
     * Wood gathering: walk to the nearest logs, break them, pick the drops up.
     *
     * @param logs   known log positions relative to the zone origin
     * @param fromX  where the gatherer stands
     * @param wanted how many logs to fetch
     */
    public static Plan gather(List<int[]> logs, int fromX, int fromY, int fromZ, int wanted) {
        List<BuildStep> steps = new ArrayList<>();
        if (logs == null || logs.isEmpty() || wanted <= 0) {
            return new Plan(Kind.GATHER, steps, new LinkedHashMap<>(), Math.max(0, wanted));
        }
        List<int[]> sorted = new ArrayList<>(logs);
        final int fx = fromX;
        final int fy = fromY;
        final int fz = fromZ;
        sorted.sort(Comparator.comparingDouble(p -> dist2(p[0], p[1], p[2], fx, fy, fz)));
        int taken = 0;
        for (int[] log : sorted) {
            if (taken >= wanted) {
                break;
            }
            steps.add(BuildStep.move(log[0] + 1, Math.min(log[1], fromY), log[2]));
            steps.add(BuildStep.breakAt(log[0], log[1], log[2]));
            steps.add(BuildStep.pickup(log[0], Math.min(log[1], fromY), log[2]));
            taken++;
        }
        Map<String, Integer> materials = new LinkedHashMap<>();
        return new Plan(Kind.GATHER, steps, materials, Math.max(0, wanted - taken));
    }

    // ------------------------------------------------------------------ shapes
    // Local frame: x = right, z = forward (away from the builder), y = up.

    static List<int[]> bridge(int length) {
        List<int[]> out = new ArrayList<>();
        for (int i = 1; i <= length; i++) {
            out.add(new int[] {0, -1, i});
        }
        return out;
    }

    static List<int[]> wall(int length, int height) {
        List<int[]> out = new ArrayList<>();
        int half = length / 2;
        for (int y = 0; y < height; y++) {
            for (int x = -half; x < length - half; x++) {
                out.add(new int[] {x, y, 2});
            }
        }
        return out;
    }

    static List<int[]> tower(int height) {
        List<int[]> out = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int[] cell : ring3x3()) {
                out.add(new int[] {cell[0], y, cell[1] + 3});
            }
        }
        out.add(new int[] {0, height, 3});
        return out;
    }

    static List<int[]> platform(int size) {
        List<int[]> out = new ArrayList<>();
        int half = size / 2;
        for (int z = 1; z <= size; z++) {
            for (int x = -half; x < size - half; x++) {
                out.add(new int[] {x, 0, z});
            }
        }
        return out;
    }

    static List<int[]> hut(int size) {
        List<int[]> out = new ArrayList<>();
        int half = size / 2;
        int minX = -half;
        int maxX = size - half - 1;
        int minZ = 2;
        int maxZ = minZ + size - 1;
        int height = 3;
        for (int y = 0; y < height; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                    boolean door = z == minZ && x == 0 && y < 2;
                    if (edge && !door) {
                        out.add(new int[] {x, y, z});
                    }
                }
            }
        }
        // Roof, row by row from the front wall, so every block touches the last.
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                out.add(new int[] {x, height, z});
            }
        }
        return out;
    }

    /** Material preference slots a shape can ask for (see {@link #assemble}). */
    static final int PREF_STOCK = 0;
    static final int PREF_GOLD = 1;
    static final int PREF_WOOL = 2;

    static final String GOLD = "GOLD_BLOCK";
    static final String WOOL = "RED_WOOL";

    /**
     * A real chair: a 2x2 seat, a one-block-high back, armrests, and gold /
     * wool accents on the back corners.
     *
     * <p>It is built facing back towards whoever asked for it, so the owner -
     * who stands behind the anchor - looks at the seat, not at the back.</p>
     *
     * <p>Every block is placed by hand and bottom-up, so each one has a
     * neighbour to be placed against.</p>
     */
    static List<int[]> throne() {
        List<int[]> out = new ArrayList<>();
        // Seat: 2x2 on the builder's level, two blocks ahead.
        for (int x = 0; x <= 1; x++) {
            for (int z = 2; z <= 3; z++) {
                out.add(new int[] {x, 0, z, PREF_WOOL});
            }
        }
        // Back: one block high, on the far side of the seat.
        for (int x = 0; x <= 1; x++) {
            out.add(new int[] {x, 1, 3, PREF_STOCK});
        }
        // Armrests: one block up, either side of the near end of the seat.
        out.add(new int[] {-1, 1, 2, PREF_STOCK});
        out.add(new int[] {2, 1, 2, PREF_STOCK});
        // Gold accents crowning the back corners.
        out.add(new int[] {-1, 1, 3, PREF_GOLD});
        out.add(new int[] {2, 1, 3, PREF_GOLD});
        return out;
    }

    /**
     * Where a "build it in front of me" goal should start: {@code blocks} ahead
     * of the speaker, along his facing.
     *
     * @param ax     speaker x relative to the zone origin
     * @param az     speaker z relative to the zone origin
     * @param facing 0 = +z (south), 1 = -x (west), 2 = -z (north), 3 = +x (east)
     */
    public static int[] anchorAhead(int ax, int ay, int az, int facing, int blocks) {
        int[] forward = rotate(0, Math.max(0, blocks), facing);
        return new int[] {ax + forward[0], ay, az + forward[1]};
    }

    private static int[][] ring3x3() {
        return new int[][] {{-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}};
    }

    /** Local (right, forward) to relative world (dx, dz) for a cardinal facing. */
    static int[] rotate(int right, int forward, int facing) {
        switch (Math.floorMod(facing, 4)) {
            case 1: // facing -x (west): forward = -x, right = -z
                return new int[] {-forward, -right};
            case 2: // facing -z (north): forward = -z, right = +x
                return new int[] {right, -forward};
            case 3: // facing +x (east): forward = +x, right = +z
                return new int[] {forward, right};
            case 0: // facing +z (south): forward = +z, right = -x
            default:
                return new int[] {-right, forward};
        }
    }

    /**
     * Turns a block list into steps: a MOVE whenever the next block is out of
     * reach of where the builder last stood, then the PLACE. Materials are drawn
     * from what the squad carries, most plentiful first.
     */
    private static Plan assemble(Kind kind, List<int[]> blocks, Map<String, Integer> available,
                                 int ax, int ay, int az, int facing) {
        List<BuildStep> steps = new ArrayList<>();
        Map<String, Integer> stock = new LinkedHashMap<>();
        if (available != null) {
            List<Map.Entry<String, Integer>> entries = new ArrayList<>(available.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue() == null ? 0 : b.getValue(),
                    a.getValue() == null ? 0 : a.getValue()));
            for (Map.Entry<String, Integer> entry : entries) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    stock.put(entry.getKey().toUpperCase(Locale.ROOT), entry.getValue());
                }
            }
        }
        Map<String, Integer> used = new LinkedHashMap<>();
        int shortfall = 0;
        int[] stand = {ax, ay, az};
        int[] back = rotate(0, -1, facing);
        for (int[] block : blocks) {
            if (dist2(block[0] + 0.5, block[1] + 0.5, block[2] + 0.5,
                    stand[0] + 0.5, stand[1] + 1.6, stand[2] + 0.5) > REACH * REACH) {
                int[] spot = standingSpotFor(kind, block, ay, back);
                stand = spot;
                steps.add(BuildStep.move(spot[0], spot[1], spot[2]));
            }
            int pref = block.length > 3 ? block[3] : PREF_STOCK;
            String material = pref == PREF_STOCK ? take(stock) : takePreferred(stock, pref);
            if (material == null) {
                material = pref == PREF_GOLD ? GOLD : pref == PREF_WOOL ? WOOL : "OAK_PLANKS";
                shortfall++;
            }
            used.merge(material, 1, Integer::sum);
            steps.add(BuildStep.place(block[0], block[1], block[2], material));
        }
        return new Plan(kind, steps, used, shortfall);
    }

    /**
     * Where to stand to place a block: one block "behind" it (towards the
     * builder) at the builders' level - or, for a bridge, on the block just
     * placed before it, which is how a player bridges.
     */
    private static int[] standingSpotFor(Kind kind, int[] block, int ay, int[] back) {
        if (kind == Kind.BRIDGE) {
            return new int[] {block[0] + back[0], block[1] + 1, block[2] + back[1]};
        }
        return new int[] {block[0] + back[0] * 2, ay, block[2] + back[1] * 2};
    }

    /**
     * The accent material, if the squad carries any; otherwise the normal stock
     * block, so a throne is still a chair when nobody packed gold.
     */
    private static String takePreferred(Map<String, Integer> stock, int pref) {
        String wanted = pref == PREF_GOLD ? GOLD : WOOL;
        Integer have = stock.get(wanted);
        if (have != null && have > 0) {
            stock.put(wanted, have - 1);
            return wanted;
        }
        return take(stock);
    }

    private static String take(Map<String, Integer> stock) {
        for (Map.Entry<String, Integer> entry : stock.entrySet()) {
            if (entry.getValue() > 0) {
                entry.setValue(entry.getValue() - 1);
                return entry.getKey();
            }
        }
        return null;
    }

    private static double dist2(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }
}
