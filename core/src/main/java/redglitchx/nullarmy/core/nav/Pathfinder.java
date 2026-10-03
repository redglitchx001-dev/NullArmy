package redglitchx.nullarmy.core.nav;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded, incremental voxel-aware A*.
 *
 * <p>Spec 5: "Use bounded, incremental voxel-aware path planning ... replan a
 * slice at a time." Spec 9 caps "simultaneous path searches" and forbids
 * blocking the tick thread on expensive pathfinding.</p>
 *
 * <p>Two properties matter and are enforced structurally:</p>
 * <ol>
 *   <li><b>Bounded.</b> At most {@code maxExpansions} nodes are expanded. When
 *       the budget runs out the search returns the best partial path found so
 *       far with status {@code PARTIAL_BUDGET_EXHAUSTED} - it never keeps
 *       grinding.</li>
 *   <li><b>Deterministic.</b> Ties break on insertion order, so the same world
 *       and endpoints always yield the same path. Spec 5 requires steering
 *       values "deterministic enough to debug".</li>
 * </ol>
 *
 * <p>Movement model: 8-way horizontal, plus step up / step down of at most
 * {@code maxStepHeight}. Diagonal moves are rejected unless both orthogonal
 * neighbours are free, so a Null can never cut a corner through a wall.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Pathfinder {

    private static final int[] DIRS = {
            1, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, -1,
            1, 0, 1, 1, 0, -1, -1, 0, 1, -1, 0, -1
    };

    private final int maxExpansions;
    private final int maxStepHeight;

    /** Monotonic tie-breaker so equal-cost paths resolve identically every run. */
    private long seq = 0;

    public Pathfinder(int maxExpansions, int maxStepHeight) {
        if (maxExpansions <= 0) {
            throw new IllegalArgumentException("maxExpansions must be > 0");
        }
        if (maxStepHeight < 0) {
            throw new IllegalArgumentException("maxStepHeight must be >= 0");
        }
        this.maxExpansions = maxExpansions;
        this.maxStepHeight = maxStepHeight;
    }

    /**
     * @param view   block collision + cost source
     * @param sx,sy,sz start cell (must be free)
     * @param gx,gy,gz goal cell (must be free)
     */
    public PathResult search(BlockView view, int sx, int sy, int sz, int gx, int gy, int gz) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (isBlocked(view, sx, sy, sz) || isBlocked(view, gx, gy, gz)) {
            return new PathResult(PathResult.Status.INVALID_ENDPOINT, new ArrayList<>(), 0);
        }

        Map<PathResult.Node, PathResult.Node> cameFrom = new HashMap<>();
        Map<PathResult.Node, Double> gScore = new HashMap<>();
        PriorityQueue<Open> open = new PriorityQueue<>(
                Comparator.<Open>comparingDouble(o -> o.f).thenComparingLong(o -> o.seq));

        PathResult.Node start = new PathResult.Node(sx, sy, sz);
        PathResult.Node goal = new PathResult.Node(gx, gy, gz);

        gScore.put(start, 0.0);
        open.add(new Open(start, 0.0, heuristic(sx, sy, sz, gx, gy, gz), seq++));

        int expanded = 0;
        PathResult.Node best = start;
        double bestH = heuristic(sx, sy, sz, gx, gy, gz);

        while (!open.isEmpty()) {
            if (expanded >= maxExpansions) {
                return new PathResult(
                        PathResult.Status.PARTIAL_BUDGET_EXHAUSTED,
                        reconstruct(cameFrom, best),
                        expanded);
            }

            Open current = open.poll();
            PathResult.Node node = current.node;

            if (node.equals(goal)) {
                return new PathResult(
                        PathResult.Status.COMPLETE,
                        reconstruct(cameFrom, node),
                        expanded);
            }

            // Stale heap entry.
            double known = gScore.getOrDefault(node, Double.POSITIVE_INFINITY);
            if (current.g > known) {
                continue;
            }

            expanded++;

            double h = heuristic(node.x(), node.y(), node.z(), gx, gy, gz);
            if (h < bestH) {
                bestH = h;
                best = node;
            }

            for (int i = 0; i < DIRS.length; i += 3) {
                int dx = DIRS[i];
                int dz = DIRS[i + 2];

                // Vertical variants: 0 = level, +1 = step up, -1 = step down.
                for (int dy = -maxStepHeight; dy <= maxStepHeight; dy++) {
                    int nx = node.x() + dx;
                    int ny = node.y() + dy;
                    int nz = node.z() + dz;

                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    if (isBlocked(view, nx, ny, nz)) {
                        continue;
                    }
                    // Headroom for the body.
                    if (isBlocked(view, nx, ny + 1, nz)) {
                        continue;
                    }
                    // No corner cutting through solid blocks.
                    if (dx != 0 && dz != 0) {
                        if (isBlocked(view, node.x() + dx, ny, node.z())
                                || isBlocked(view, node.x(), ny, node.z() + dz)) {
                            continue;
                        }
                    }
                    // Need solid ground to stand on.
                    if (!isBlocked(view, nx, ny - 1, nz)) {
                        continue;
                    }
                    // Step-up ceiling: the column above the step must be clear.
                    if (dy > 0 && !columnClear(view, node.x(), node.y(), node.z(), dy)) {
                        continue;
                    }

                    PathResult.Node next = new PathResult.Node(nx, ny, nz);
                    double step = (dx != 0 && dz != 0) ? 1.4142135623730951 : 1.0;
                    double tentative = known + step + Math.abs(dy) * 0.5
                            + view.extraCost(nx, ny, nz);

                    if (tentative < gScore.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                        gScore.put(next, tentative);
                        cameFrom.put(next, node);
                        open.add(new Open(next, tentative, tentative
                                + heuristic(nx, ny, nz, gx, gy, gz), seq++));
                    }
                }
            }
        }

        return new PathResult(PathResult.Status.UNREACHABLE, reconstruct(cameFrom, best), expanded);
    }


    private boolean columnClear(BlockView view, int x, int y, int z, int up) {
        for (int i = 1; i <= up + 1; i++) {
            if (isBlocked(view, x, y + i, z)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlocked(BlockView view, int x, int y, int z) {
        if (y < view.minY() || y > view.maxY()) {
            return true;
        }
        return view.isSolid(x, y, z);
    }

    private static double heuristic(int x, int y, int z, int gx, int gy, int gz) {
        int dx = Math.abs(x - gx);
        int dy = Math.abs(y - gy);
        int dz = Math.abs(z - gz);
        double straight = Math.min(dx, dz);
        double diagonal = Math.abs(dx - dz);
        return straight * 1.4142135623730951 + diagonal + dy * 1.2;
    }

    private static List<PathResult.Node> reconstruct(
            Map<PathResult.Node, PathResult.Node> cameFrom, PathResult.Node end) {
        ArrayDeque<PathResult.Node> path = new ArrayDeque<>();
        PathResult.Node cursor = end;
        while (cursor != null) {
            path.addFirst(cursor);
            cursor = cameFrom.get(cursor);
        }
        return new ArrayList<>(path);
    }

    private static final class Open {
        private final PathResult.Node node;
        private final double g;
        private final double f;
        private final long seq;

        Open(PathResult.Node node, double g, double f, long seq) {
            this.node = node;
            this.g = g;
            this.f = f;
            this.seq = seq;
        }
    }
}
