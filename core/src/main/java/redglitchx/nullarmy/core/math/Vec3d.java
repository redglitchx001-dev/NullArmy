package redglitchx.nullarmy.core.math;

/**
 * Immutable 3d double vector.
 *
 * <p>Exists because {@code core} must not depend on Bukkit or NMS
 * (see ADR-004). Every geometry-bearing core type uses this instead of
 * {@code org.bukkit.util.Vector}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Vec3d {

    public static final Vec3d ZERO = new Vec3d(0.0, 0.0, 0.0);

    private final double x;
    private final double y;
    private final double z;

    public Vec3d(double x, double y, double z) {
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            throw new IllegalArgumentException("Vec3d components must not be NaN");
        }
        if (Double.isInfinite(x) || Double.isInfinite(y) || Double.isInfinite(z)) {
            throw new IllegalArgumentException("Vec3d components must not be infinite");
        }
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    public Vec3d add(Vec3d o) { return new Vec3d(x + o.x, y + o.y, z + o.z); }
    public Vec3d sub(Vec3d o) { return new Vec3d(x - o.x, y - o.y, z - o.z); }
    public Vec3d scale(double s) { return new Vec3d(x * s, y * s, z * s); }

    public double dot(Vec3d o) { return x * o.x + y * o.y + z * o.z; }
    public double lengthSquared() { return x * x + y * y + z * z; }
    public double length() { return Math.sqrt(lengthSquared()); }

    /** Horizontal (XZ) distance - the measure that matters for body separation. */
    public double horizontalLength() { return Math.sqrt(x * x + z * z); }

    /**
     * Returns a copy scaled to {@code max} if longer, otherwise this.
     * This is the primitive that keeps every steering force bounded (spec 5).
     */
    public Vec3d clampLength(double max) {
        if (max < 0.0) {
            throw new IllegalArgumentException("max must be >= 0");
        }
        double len = length();
        if (len <= max || len == 0.0) {
            return this;
        }
        return scale(max / len);
    }

    /** Unit vector, or ZERO if this is a zero-length vector. */
    public Vec3d normalize() {
        double len = length();
        if (len == 0.0) {
            return ZERO;
        }
        return scale(1.0 / len);
    }

    public double distanceTo(Vec3d o) { return sub(o).length(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Vec3d)) {
            return false;
        }
        Vec3d v = (Vec3d) o;
        return Double.compare(v.x, x) == 0 && Double.compare(v.y, y) == 0 && Double.compare(v.z, z) == 0;
    }

    @Override
    public int hashCode() {
        int result = Double.hashCode(x);
        result = 31 * result + Double.hashCode(y);
        result = 31 * result + Double.hashCode(z);
        return result;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
