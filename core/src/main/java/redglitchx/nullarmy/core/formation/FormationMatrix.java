package redglitchx.nullarmy.core.formation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Formations as fixed cell offsets.
 *
 * <p>Every formation is a list of cells in the anchor's own frame - x to the
 * anchor's right, z forward - spaced at least {@link #MIN_SPACING} apart. The
 * cells are rotated by the anchor's facing and added to its position, so a
 * square stays a square whichever way the owner looks, and every Null has a cell
 * of its own: two members can never be sent to the same spot, which is what used
 * to make "square" and "line" pile Nulls up.</p>
 *
 * <p>Minecraft yaw: 0 faces +Z (south), 90 faces -X (west). Facing south, the
 * right hand points west, so {@code right = (-cos yaw, -sin yaw)} and
 * {@code forward = (-sin yaw, cos yaw)}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class FormationMatrix {

    /** Two cells are never closer than this. */
    public static final double MIN_SPACING = 1.1D;

    private static final List<String> KINDS = Collections.unmodifiableList(Arrays.asList(
            "line", "rank", "column", "square", "wedge", "phalanx", "arrow", "encircle", "turtle"));

    private FormationMatrix() {
    }

    /** Every formation name, in menu order. */
    public static List<String> kinds() { return KINDS; }

    public static boolean isKnown(String kind) {
        return kind != null && KINDS.contains(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** Spacing forced to at least {@link #MIN_SPACING}. */
    public static double spacing(double configured) {
        return Double.isFinite(configured) ? Math.max(MIN_SPACING, Math.min(8.0D, configured)) : 1.5D;
    }

    /**
     * Local cells {right, forward} for {@code count} members. Index i is member
     * i's cell; the list always has exactly {@code count} entries.
     */
    public static List<double[]> offsets(String kind, int count, double spacing) {
        int n = Math.max(0, count);
        double s = spacing(spacing);
        String k = kind == null ? "line" : kind.trim().toLowerCase(Locale.ROOT);
        List<double[]> out = new ArrayList<>(n);
        switch (k) {
            case "column":
                for (int i = 0; i < n; i++) {
                    out.add(new double[] {0.0D, -i * s});
                }
                break;
            case "rank": {
                int width = Math.max(1, Math.min(n, 5));
                grid(out, n, width, s);
                break;
            }
            case "square": {
                int side = Math.max(1, (int) Math.ceil(Math.sqrt(n)));
                grid(out, n, side, s);
                break;
            }
            case "phalanx": {
                int width = Math.max(1, Math.min(n, (int) Math.ceil(Math.sqrt(n * 2.0D))));
                grid(out, n, width, s);
                break;
            }
            case "wedge": {
                // A V: the point at the front, arms trailing back-left and back-right.
                out.add(new double[] {0.0D, 0.0D});
                for (int i = 1; out.size() < n; i++) {
                    out.add(new double[] {i * s, -i * s});
                    if (out.size() < n) {
                        out.add(new double[] {-i * s, -i * s});
                    }
                }
                break;
            }
            case "arrow": {
                // A filled arrowhead: row r has 2r+1 cells, r rows deep.
                for (int row = 0; out.size() < n; row++) {
                    for (int c = -row; c <= row && out.size() < n; c++) {
                        out.add(new double[] {c * s, -row * s});
                    }
                }
                break;
            }
            case "encircle":
                ring(out, n, Math.max(5.0D, ringRadius(n, s)));
                break;
            case "turtle":
                ring(out, n, ringRadius(n, s));
                break;
            case "line":
            default:
                for (int i = 0; i < n; i++) {
                    out.add(new double[] {(i - (n - 1) / 2.0D) * s, 0.0D});
                }
                break;
        }
        while (out.size() > n) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Rows of {@code width} cells, centred left-right, rows going backwards. */
    private static void grid(List<double[]> out, int n, int width, double s) {
        int rows = (int) Math.ceil(n / (double) width);
        for (int i = 0; i < n; i++) {
            int row = i / width;
            int col = i % width;
            int inRow = row == rows - 1 ? n - row * width : width;
            out.add(new double[] {(col - (inRow - 1) / 2.0D) * s, -row * s + (rows - 1) * s / 2.0D});
        }
    }

    /** A ring whose neighbouring cells are at least {@code s} apart. */
    private static void ring(List<double[]> out, int n, double radius) {
        for (int i = 0; i < n; i++) {
            double angle = 2.0D * Math.PI * i / Math.max(1, n);
            out.add(new double[] {Math.cos(angle) * radius, Math.sin(angle) * radius});
        }
    }

    private static double ringRadius(int n, double s) {
        if (n <= 1) {
            return 0.0D;
        }
        // Chord between neighbours = 2 r sin(pi/n) >= s.
        return Math.max(1.5D, s / (2.0D * Math.sin(Math.PI / n)));
    }

    /** Rotates a local {right, forward} offset by a Minecraft yaw into world {dx, dz}. */
    public static double[] rotate(double right, double forward, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        return new double[] {right * rightX + forward * forwardX, right * rightZ + forward * forwardZ};
    }

    /**
     * World cells {x, z}: the formation's local cells rotated by the anchor's yaw
     * and placed at the anchor.
     */
    public static List<double[]> worldCells(String kind, int count, double spacing,
                                            double anchorX, double anchorZ, float yawDegrees) {
        List<double[]> out = new ArrayList<>();
        for (double[] local : offsets(kind, count, spacing)) {
            double[] d = rotate(local[0], local[1], yawDegrees);
            out.add(new double[] {anchorX + d[0], anchorZ + d[1]});
        }
        return out;
    }

    /** Smallest distance between any two cells; +infinity for fewer than two. */
    public static double minPairDistance(List<double[]> cells) {
        double best = Double.POSITIVE_INFINITY;
        if (cells == null) {
            return best;
        }
        for (int i = 0; i < cells.size(); i++) {
            for (int j = i + 1; j < cells.size(); j++) {
                double dx = cells.get(i)[0] - cells.get(j)[0];
                double dz = cells.get(i)[1] - cells.get(j)[1];
                best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
            }
        }
        return best;
    }

    /** How far behind the anchor a following formation's centre sits, in blocks. */
    public static double followDistance(String kind, int count, double spacing) {
        double depth = 0.0D;
        for (double[] cell : offsets(kind, count, spacing)) {
            depth = Math.max(depth, Math.abs(cell[1]));
        }
        return 2.0D + depth;
    }

    /**
     * The assignment of rows (members) to columns (cells) with the smallest total
     * cost - the Hungarian method, O(n^3). With Euclidean distances as costs the
     * optimal matching has no crossing paths, so members never have to shoulder
     * through a cell another member already holds.
     *
     * @param cost cost[i][j] = cost of member i taking cell j (rows <= columns)
     * @return assignment[i] = cell of member i
     */
    public static int[] optimalAssignment(double[][] cost) {
        int n = cost.length;
        if (n == 0) {
            return new int[0];
        }
        int m = cost[0].length;
        if (m < n) {
            throw new IllegalArgumentException("more members than cells");
        }
        double[] u = new double[n + 1];
        double[] v = new double[m + 1];
        int[] p = new int[m + 1];
        int[] way = new int[m + 1];
        for (int i = 1; i <= n; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minv = new double[m + 1];
            boolean[] used = new boolean[m + 1];
            java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
            do {
                used[j0] = true;
                int i0 = p[j0];
                double delta = Double.POSITIVE_INFINITY;
                int j1 = 0;
                for (int j = 1; j <= m; j++) {
                    if (!used[j]) {
                        double cur = cost[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minv[j]) {
                            minv[j] = cur;
                            way[j] = j0;
                        }
                        if (minv[j] < delta) {
                            delta = minv[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= m; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minv[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);
            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }
        int[] out = new int[n];
        for (int j = 1; j <= m; j++) {
            if (p[j] != 0) {
                out[p[j] - 1] = j - 1;
            }
        }
        return out;
    }
}
