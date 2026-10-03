package redglitchx.nullarmy.core.nav;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of one pathfinding attempt.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PathResult {

    /** Why the search stopped. */
    public enum Status {
        /** Reached the goal. {@code nodes} is a complete path. */
        COMPLETE,
        /** Ran out of node budget. {@code nodes} is the best partial path. */
        PARTIAL_BUDGET_EXHAUSTED,
        /** No route exists within the searched region. */
        UNREACHABLE,
        /** Start or goal was invalid (solid, out of bounds). */
        INVALID_ENDPOINT
    }

    private final Status status;
    private final List<Node> nodes;
    private final int expanded;

    PathResult(Status status, List<Node> nodes, int expanded) {
        this.status = status;
        this.nodes = Collections.unmodifiableList(nodes);
        this.expanded = expanded;
    }

    public Status status() { return status; }
    public List<Node> nodes() { return nodes; }
    public int expanded() { return expanded; }

    /** True only for a route that actually reaches the goal. */
    public boolean isComplete() { return status == Status.COMPLETE; }

    /** A path with fewer than two nodes cannot be walked; treat as no route. */
    public boolean isWalkable() { return nodes.size() >= 2; }

    /** A single grid cell in a path. */
    public static final class Node {
        private final int x;
        private final int y;
        private final int z;

        Node(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Node)) {
                return false;
            }
            Node n = (Node) o;
            return x == n.x && y == n.y && z == n.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }
}
