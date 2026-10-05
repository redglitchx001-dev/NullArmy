package redglitchx.nullarmy.core.flock;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeping bodies apart: per-body separation steering and crowd detection.
 *
 * <p>Separation is what stops a squad from melting into one block: every body
 * steers away from neighbours closer than the separation radius, harder the
 * closer they are. Crowd detection finds groups of more than two bodies packed
 * within one block of each other - the "pile of Nulls" the owner reported - so
 * the extras can be walked out to free cells.</p>
 *
 * <p>Positions are {x, y, z}; two bodies more than {@link #SAME_LEVEL} blocks
 * apart vertically are on different levels and never push each other.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Separation {

    /** Vertical gap beyond which two bodies are on different levels. */
    public static final double SAME_LEVEL = 1.5D;

    private Separation() {
    }

    /**
     * Steering away from close neighbours.
     *
     * @param self   index of the body in {@code positions}
     * @param radius separation radius in blocks
     * @return {dx, dz}, length at most 1
     */
    public static double[] steer(int self, List<double[]> positions, double radius) {
        if (positions == null || self < 0 || self >= positions.size() || radius <= 0.0D) {
            return new double[] {0.0D, 0.0D};
        }
        double[] me = positions.get(self);
        double sx = 0.0D;
        double sz = 0.0D;
        for (int i = 0; i < positions.size(); i++) {
            if (i == self) {
                continue;
            }
            double[] other = positions.get(i);
            if (Math.abs(other[1] - me[1]) > SAME_LEVEL) {
                continue;
            }
            double dx = me[0] - other[0];
            double dz = me[2] - other[2];
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d >= radius) {
                continue;
            }
            if (d < 1.0e-3) {
                // Exactly on top of each other: split deterministically by index
                // so the two bodies go opposite ways instead of both standing still.
                double angle = (self * 2.399963229728653D) + i * 0.7D;
                dx = Math.cos(angle);
                dz = Math.sin(angle);
                d = 1.0e-3;
            } else {
                dx /= d;
                dz /= d;
            }
            double weight = (radius - d) / radius;
            sx += dx * weight;
            sz += dz * weight;
        }
        double length = Math.sqrt(sx * sx + sz * sz);
        if (length > 1.0D) {
            sx /= length;
            sz /= length;
        }
        return new double[] {sx, sz};
    }

    /**
     * Groups of more than {@code moreThan} bodies that are chained together by
     * gaps smaller than {@code within} (on the same level).
     *
     * @return index groups, each larger than {@code moreThan}
     */
    public static List<List<Integer>> crowdedGroups(List<double[]> positions, double within, int moreThan) {
        List<List<Integer>> out = new ArrayList<>();
        if (positions == null || positions.size() <= moreThan) {
            return out;
        }
        int n = positions.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double[] a = positions.get(i);
                double[] b = positions.get(j);
                if (Math.abs(a[1] - b[1]) > SAME_LEVEL) {
                    continue;
                }
                double dx = a[0] - b[0];
                double dz = a[2] - b[2];
                if (dx * dx + dz * dz < within * within) {
                    int ra = find(parent, i);
                    int rb = find(parent, j);
                    if (ra != rb) {
                        parent[ra] = rb;
                    }
                }
            }
        }
        java.util.Map<Integer, List<Integer>> groups = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> group : groups.values()) {
            if (group.size() > moreThan) {
                out.add(group);
            }
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    /** Smallest horizontal distance between two bodies on the same level. */
    public static double minPairDistance(List<double[]> positions) {
        double best = Double.POSITIVE_INFINITY;
        if (positions == null) {
            return best;
        }
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                double[] a = positions.get(i);
                double[] b = positions.get(j);
                if (Math.abs(a[1] - b[1]) > SAME_LEVEL) {
                    continue;
                }
                best = Math.min(best, Math.hypot(a[0] - b[0], a[2] - b[2]));
            }
        }
        return best;
    }
}
