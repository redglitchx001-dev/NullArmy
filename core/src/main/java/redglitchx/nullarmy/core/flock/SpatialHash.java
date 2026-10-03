package redglitchx.nullarmy.core.flock;

import redglitchx.nullarmy.core.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uniform spatial hash over 3d points.
 *
 * <p>Spec 5 requires Boids to run "in local spatial cells rather than
 * comparing every NPC to every other NPC". This is that structure: neighbour
 * queries are O(nearby) instead of O(n).</p>
 *
 * <p>Rebuilt each steering pass. Cheap by design (spec 9: use spatial hashing
 * for crowd checks).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SpatialHash {

    private final double cellSize;
    private final Map<CellKey, List<Entry>> cells = new HashMap<>();

    public SpatialHash(double cellSize) {
        if (cellSize <= 0.0) {
            throw new IllegalArgumentException("cellSize must be > 0");
        }
        this.cellSize = cellSize;
    }

    public void clear() { cells.clear(); }

    public void insert(int id, Vec3d position) {
        CellKey key = keyOf(position);
        cells.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry(id, position));
    }

    /**
     * Collects every entry within {@code radius} of {@code origin}.
     *
     * <p>Scans only the 27 cells around the origin's cell, so cost is bounded
     * regardless of how many Nulls exist server-wide.</p>
     */
    public List<Entry> query(Vec3d origin, double radius) {
        int reach = (int) Math.ceil(radius / cellSize);
        int cx = (int) Math.floor(origin.x() / cellSize);
        int cy = (int) Math.floor(origin.y() / cellSize);
        int cz = (int) Math.floor(origin.z() / cellSize);

        List<Entry> out = new ArrayList<>();
        double r2 = radius * radius;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dy = -reach; dy <= reach; dy++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    List<Entry> bucket = cells.get(new CellKey(cx + dx, cy + dy, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (Entry e : bucket) {
                        if (e.position.distanceTo(origin) <= radius || e.position.sub(origin).lengthSquared() <= r2) {
                            out.add(e);
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Unmodifiable view of the raw buckets - diagnostics only. */
    public Map<CellKey, List<Entry>> cells() { return Collections.unmodifiableMap(cells); }

    private CellKey keyOf(Vec3d p) {
        return new CellKey(
                (int) Math.floor(p.x() / cellSize),
                (int) Math.floor(p.y() / cellSize),
                (int) Math.floor(p.z() / cellSize));
    }

    /** A hashed cell coordinate. */
    public static final class CellKey {
        private final int x;
        private final int y;
        private final int z;

        CellKey(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CellKey)) {
                return false;
            }
            CellKey c = (CellKey) o;
            return x == c.x && y == c.y && z == c.z;
        }

        @Override
        public int hashCode() {
            int result = x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }
    }

    /** An inserted point. */
    public static final class Entry {
        private final int id;
        private final Vec3d position;

        Entry(int id, Vec3d position) {
            this.id = id;
            this.position = position;
        }

        public int id() { return id; }
        public Vec3d position() { return position; }
    }
}
