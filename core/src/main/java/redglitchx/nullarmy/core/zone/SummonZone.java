package redglitchx.nullarmy.core.zone;

import java.util.ArrayList;
import java.util.List;

/**
 * The square area a summon may use, centred on the horn user.
 *
 * <p>{@code summon.zone-size} is the edge length in blocks (default 100,
 * clamped to 16..200). Every portal frame, every arrival spot and every AI
 * build step must lie inside it; a summon that cannot find room inside says so
 * instead of quietly building outside.</p>
 *
 * <p>Bounds are inclusive on both sides and measured on block coordinates, so
 * {@code contains} answers the same question for a block and for a body
 * standing on it.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SummonZone {

    public static final int MIN_SIZE = 16;
    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 100;

    private final double centerX;
    private final double centerZ;
    private final int size;

    public SummonZone(double centerX, double centerZ, int size) {
        if (!Double.isFinite(centerX) || !Double.isFinite(centerZ)) {
            throw new IllegalArgumentException("zone centre must be finite");
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.size = clampSize(size);
    }

    /** The configured size forced into 16..200. */
    public static int clampSize(int size) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    public double centerX() { return centerX; }
    public double centerZ() { return centerZ; }
    public int size() { return size; }
    public double half() { return size / 2.0D; }

    public double minX() { return centerX - half(); }
    public double maxX() { return centerX + half(); }
    public double minZ() { return centerZ - half(); }
    public double maxZ() { return centerZ + half(); }

    /** True when the point lies inside the zone (edges included). */
    public boolean contains(double x, double z) {
        return x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /** True when the whole axis-aligned box lies inside the zone. */
    public boolean containsBox(double boxMinX, double boxMinZ, double boxMaxX, double boxMaxZ) {
        return contains(Math.min(boxMinX, boxMaxX), Math.min(boxMinZ, boxMaxZ))
                && contains(Math.max(boxMinX, boxMaxX), Math.max(boxMinZ, boxMaxZ));
    }

    /** True when the block at (bx, bz) - the cube bx..bx+1 - lies inside. */
    public boolean containsBlock(int bx, int bz) {
        return containsBox(bx, bz, bx + 1, bz + 1);
    }

    /** The point moved inside the zone, keeping {@code margin} from the edge. */
    public double[] clampInside(double x, double z, double margin) {
        double m = Math.max(0.0D, Math.min(margin, half()));
        double cx = Math.max(minX() + m, Math.min(maxX() - m, x));
        double cz = Math.max(minZ() + m, Math.min(maxZ() - m, z));
        return new double[] {cx, cz};
    }

    /** Points along the border, at most {@code spacing} apart, corners included. */
    public List<double[]> outline(double spacing) {
        double step = Math.max(0.5D, spacing);
        List<double[]> out = new ArrayList<>();
        int perSide = Math.max(1, (int) Math.ceil(size / step));
        for (int i = 0; i < perSide; i++) {
            double t = (double) i / perSide;
            out.add(new double[] {minX() + t * size, minZ()});
            out.add(new double[] {maxX(), minZ() + t * size});
            out.add(new double[] {maxX() - t * size, maxZ()});
            out.add(new double[] {minX(), maxZ() - t * size});
        }
        return out;
    }

    /** "100x100 centred on 12,-40 (x -38..62, z -90..10)". */
    public String describe() {
        return size + "x" + size + " centred on " + round(centerX) + "," + round(centerZ)
                + " (x " + round(minX()) + ".." + round(maxX()) + ", z " + round(minZ()) + ".."
                + round(maxZ()) + ")";
    }

    private static String round(double v) {
        long r = Math.round(v);
        return Math.abs(v - r) < 1.0e-9 ? Long.toString(r) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
