package redglitchx.nullarmy.core.portal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The geometry of an arrival doorway, and the rules that make one valid.
 *
 * <pre>
 *   h=4  O O O O      O = obsidian frame (14 blocks)
 *   h=3  O . . O      . = interior, 2 wide x 3 tall, left as AIR -
 *   h=2  O . . O          nothing in it is a portal block, so nothing that
 *   h=1  O . . O          enters it is ever teleported (one-way by design)
 *   h=0  O O O O
 *        w=0 1 2 3
 * </pre>
 *
 * <p>A <b>ground</b> doorway stands on solid support under all four bottom
 * blocks and has a two-block apron of air in front of its opening, with a
 * solid floor, so the Nulls that step out land on their feet. A <b>floating</b>
 * doorway hangs {@code air-height-min..max} blocks above the ground below it;
 * Nulls step out and fall, taking real fall damage. A site is only used when
 * every frame and interior block is air beforehand: no terrain is ever cut
 * into.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PortalFrame {

    public static final int OUTER_WIDTH = 4;
    public static final int OUTER_HEIGHT = 5;
    public static final int INNER_WIDTH = 2;
    public static final int INNER_HEIGHT = 3;
    public static final int APRON_DEPTH = 2;

    /** Where a doorway stands. */
    public enum Kind { GROUND, FLOATING }

    /** Read access to the world, so the rules can run in tests. */
    public interface Lookup {
        /** Upper-case material name at a block, e.g. "AIR", "OBSIDIAN", "GRASS_BLOCK". */
        String type(int x, int y, int z);

        /** True when a body can stand on the block. */
        boolean solid(int x, int y, int z);
    }

    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final boolean widthOnX;
    private final Kind kind;
    private final int exitSide;

    /**
     * @param baseX    x of the bottom-left frame block (w=0, h=0)
     * @param widthOnX true when the frame's width runs along x
     * @param exitSide +1 or -1: which side of the frame plane the Nulls step out on
     */
    public PortalFrame(int baseX, int baseY, int baseZ, boolean widthOnX, Kind kind, int exitSide) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.widthOnX = widthOnX;
        this.kind = kind == null ? Kind.GROUND : kind;
        this.exitSide = exitSide >= 0 ? 1 : -1;
    }

    public int baseX() { return baseX; }
    public int baseY() { return baseY; }
    public int baseZ() { return baseZ; }
    public boolean widthOnX() { return widthOnX; }
    public Kind kind() { return kind; }
    public int exitSide() { return exitSide; }

    /** The same frame with the other exit side. */
    public PortalFrame flipped() {
        return new PortalFrame(baseX, baseY, baseZ, widthOnX, kind, -exitSide);
    }

    /** World block of frame cell (w, h), with an offset d perpendicular to the plane. */
    public int[] cell(int w, int h, int d) {
        int x = baseX + (widthOnX ? w : d);
        int z = baseZ + (widthOnX ? d : w);
        return new int[] {x, baseY + h, z};
    }

    /** The 14 frame blocks. */
    public List<int[]> frameCells() {
        List<int[]> out = new ArrayList<>();
        for (int h = 0; h < OUTER_HEIGHT; h++) {
            for (int w = 0; w < OUTER_WIDTH; w++) {
                if (w == 0 || w == OUTER_WIDTH - 1 || h == 0 || h == OUTER_HEIGHT - 1) {
                    out.add(cell(w, h, 0));
                }
            }
        }
        return out;
    }

    /** The 6 interior blocks (left as air). */
    public List<int[]> interiorCells() {
        List<int[]> out = new ArrayList<>();
        for (int h = 1; h <= INNER_HEIGHT; h++) {
            for (int w = 1; w <= INNER_WIDTH; w++) {
                out.add(cell(w, h, 0));
            }
        }
        return out;
    }

    /** All 20 blocks of the 4x5 face. */
    public List<int[]> faceCells() {
        List<int[]> out = new ArrayList<>(frameCells());
        out.addAll(interiorCells());
        return out;
    }

    /** The four blocks a ground doorway stands on. */
    public List<int[]> supportCells() {
        List<int[]> out = new ArrayList<>();
        for (int w = 0; w < OUTER_WIDTH; w++) {
            out.add(cell(w, -1, 0));
        }
        return out;
    }

    /**
     * The air in front of the opening on the exit side: the two interior columns,
     * {@link #APRON_DEPTH} deep, from the floor level up to the top of the opening.
     */
    public List<int[]> apronCells() {
        List<int[]> out = new ArrayList<>();
        int hMin = kind == Kind.GROUND ? 0 : 1;
        for (int d = 1; d <= APRON_DEPTH; d++) {
            for (int w = 1; w <= INNER_WIDTH; w++) {
                for (int h = hMin; h <= INNER_HEIGHT; h++) {
                    out.add(cell(w, h, d * exitSide));
                }
            }
        }
        return out;
    }

    /** The solid floor a ground doorway's apron needs (where the Nulls land). */
    public List<int[]> apronFloorCells() {
        List<int[]> out = new ArrayList<>();
        for (int d = 1; d <= APRON_DEPTH; d++) {
            for (int w = 1; w <= INNER_WIDTH; w++) {
                out.add(cell(w, -1, d * exitSide));
            }
        }
        return out;
    }

    /** Where a Null starts: standing on the bottom frame row inside the opening. */
    public List<double[]> insideSpots() {
        List<double[]> out = new ArrayList<>();
        for (int w = 1; w <= INNER_WIDTH; w++) {
            int[] c = cell(w, 1, 0);
            out.add(new double[] {c[0] + 0.5D, c[1], c[2] + 0.5D});
        }
        return out;
    }

    /** A point two and a half blocks out of the opening, where a Null walks to. */
    public double[] stepOutPoint() {
        int[] a = cell(1, 0, 3 * exitSide);
        int[] b = cell(2, 0, 3 * exitSide);
        double y = kind == Kind.GROUND ? baseY : baseY + 1;
        return new double[] {(a[0] + b[0]) / 2.0D + 0.5D, y, (a[2] + b[2]) / 2.0D + 0.5D};
    }

    /** Centre of the opening, for particles and for the zone check. */
    public double[] center() {
        int[] a = cell(1, 2, 0);
        int[] b = cell(2, 2, 0);
        return new double[] {(a[0] + b[0]) / 2.0D + 0.5D, baseY + 2.5D, (a[2] + b[2]) / 2.0D + 0.5D};
    }

    /** Block bounds {minX, minZ, maxX, maxZ} of the face plus the apron. */
    public int[] footprint() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        List<int[]> all = new ArrayList<>(faceCells());
        all.addAll(apronCells());
        for (int[] c : all) {
            minX = Math.min(minX, c[0]);
            minZ = Math.min(minZ, c[2]);
            maxX = Math.max(maxX, c[0]);
            maxZ = Math.max(maxZ, c[2]);
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /** True when a point is within the frame's own volume (the plane +-0.7). */
    public boolean containsBody(double x, double y, double z) {
        double along = widthOnX ? x - baseX : z - baseZ;
        double across = widthOnX ? z - (baseZ + 0.5D) : x - (baseX + 0.5D);
        return along >= 0.0D && along <= OUTER_WIDTH && Math.abs(across) < 0.8D
                && y >= baseY - 0.5D && y <= baseY + OUTER_HEIGHT;
    }

    // ----------------------------------------------------------------- validity

    public static boolean isAir(String type) {
        return type == null || type.equals("AIR") || type.equals("CAVE_AIR") || type.equals("VOID_AIR");
    }

    /**
     * Problems that stop this site from being built; empty when it may be.
     *
     * @param airMin lowest gap below a floating doorway
     * @param airMax highest gap below a floating doorway
     */
    public List<String> siteProblems(Lookup world, int airMin, int airMax) {
        List<String> out = new ArrayList<>();
        for (int[] c : faceCells()) {
            if (!isAir(world.type(c[0], c[1], c[2]))) {
                out.add("frame would cut into " + world.type(c[0], c[1], c[2]).toLowerCase(Locale.ROOT)
                        + " at " + c[0] + "," + c[1] + "," + c[2]);
                return out;
            }
        }
        for (int[] c : apronCells()) {
            if (!isAir(world.type(c[0], c[1], c[2]))) {
                out.add("no clear apron in front of the opening");
                return out;
            }
        }
        if (kind == Kind.GROUND) {
            for (int[] c : supportCells()) {
                if (!world.solid(c[0], c[1], c[2])) {
                    out.add("no solid support under the frame");
                    return out;
                }
            }
            for (int[] c : apronFloorCells()) {
                if (!world.solid(c[0], c[1], c[2])) {
                    out.add("no solid floor in front of the opening");
                    return out;
                }
            }
        } else {
            int gap = gapBelow(world, airMax + 4);
            if (gap < airMin || gap > airMax) {
                out.add("a floating doorway must hang " + airMin + ".." + airMax
                        + " blocks above the ground (gap here: " + (gap < 0 ? "none found" : gap) + ")");
            }
        }
        return out;
    }

    /**
     * Problems with a doorway that has been built; empty when it is a complete,
     * upright, one-way frame.
     */
    public List<String> builtProblems(Lookup world, int airMin, int airMax) {
        List<String> out = new ArrayList<>();
        for (int[] c : frameCells()) {
            String type = world.type(c[0], c[1], c[2]);
            if (!"OBSIDIAN".equals(type)) {
                out.add("frame block at " + c[0] + "," + c[1] + "," + c[2] + " is " + type);
            }
        }
        for (int[] c : interiorCells()) {
            String type = world.type(c[0], c[1], c[2]);
            if (!isAir(type)) {
                out.add("interior block at " + c[0] + "," + c[1] + "," + c[2] + " is " + type
                        + " (must stay air)");
            }
        }
        if (kind == Kind.GROUND) {
            for (int[] c : supportCells()) {
                if (!world.solid(c[0], c[1], c[2])) {
                    out.add("ground doorway lost its support at " + c[0] + "," + c[1] + "," + c[2]);
                }
            }
        } else {
            int gap = gapBelow(world, airMax + 4);
            if (gap < airMin || gap > airMax) {
                out.add("floating doorway gap " + gap + " is outside " + airMin + ".." + airMax);
            }
        }
        return out;
    }

    /**
     * Air blocks between the bottom frame row and the first solid block below the
     * opening's columns; -1 when none is found within {@code limit}.
     */
    public int gapBelow(Lookup world, int limit) {
        int best = -1;
        for (int w = 1; w <= INNER_WIDTH; w++) {
            int[] c = cell(w, 0, 0);
            int gap = -1;
            for (int dy = 1; dy <= limit; dy++) {
                if (world.solid(c[0], c[1] - dy, c[2])) {
                    gap = dy - 1;
                    break;
                }
            }
            if (gap < 0) {
                return -1;
            }
            best = Math.max(best, gap);
        }
        return best;
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(Locale.ROOT) + " doorway at " + baseX + "," + baseY + "," + baseZ
                + (widthOnX ? " (x-wide)" : " (z-wide)");
    }
}
