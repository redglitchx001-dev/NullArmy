package redglitchx.nullarmy.core.spectacle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Geometry of an orbital barrage.
 *
 * <p><b>P-08.</b> The wither cannon used to lob a handful of TNT minecarts on a
 * hand-tuned arc. The owner's reference is a bombardment: a huge sphere of
 * wither-blue skulls converging on one point from the sky. The shape is
 * computed here, in pure code, so the dispersion is unit tested instead of
 * judged by eye.</p>
 *
 * <p>Every pattern is built from antipodal pairs: for each skull at
 * {@code +p} there is one at {@code -p}. That is what makes the barrage read as
 * a sphere in the air while its mean impact point stays exactly on the locked
 * target - which is the property the self test measures.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Barrage {

    /** The barrage shapes. */
    public enum Pattern {
        SPHERE, RAIN, LINE;

        /** Case-insensitive lookup; {@link #SPHERE} when unknown. */
        public static Pattern parse(String raw) {
            if (raw == null) {
                return SPHERE;
            }
            try {
                return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return SPHERE;
            }
        }
    }

    private Barrage() {
    }

    /**
     * Offsets around a locked target, one entry per projectile.
     *
     * @param pattern sphere, rain or line
     * @param count   how many projectiles in one barrage
     * @param radius  horizontal radius of the shape, blocks
     * @param height  how far above the target the skulls appear, blocks
     * @param seed    any long; the same seed gives the same barrage
     * @return a list of {dx, dy, dz} offsets, always {@code count} long
     */
    public static List<double[]> offsets(Pattern pattern, int count, double radius, double height, long seed) {
        int n = Math.max(0, count);
        List<double[]> out = new ArrayList<>(n);
        if (n == 0) {
            return out;
        }
        double spread = Math.max(0.5D, radius);
        double lift = Math.max(2.0D, height);
        Random random = new Random(seed);
        int half = n / 2;
        for (int i = 0; i < half; i++) {
            double[] p = one(pattern, i, half, spread, lift, random);
            out.add(p);
            out.add(new double[] {-p[0], p[1], -p[2]});
        }
        if (out.size() < n) {
            // An odd count: one last skull straight down the middle.
            out.add(new double[] {0.0D, lift, 0.0D});
        }
        return out;
    }

    private static double[] one(Pattern pattern, int index, int half, double spread, double lift, Random random) {
        switch (pattern) {
            case LINE: {
                double step = spread * 2.0D / Math.max(1.0D, half);
                double along = -spread + step * index;
                return new double[] {along, lift + random.nextDouble() * lift * 0.25D, 0.0D};
            }
            case RAIN: {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double r = spread * Math.sqrt(random.nextDouble());
                return new double[] {Math.cos(angle) * r, lift + random.nextDouble() * lift * 0.6D,
                        Math.sin(angle) * r};
            }
            case SPHERE:
            default: {
                // Fibonacci sphere: evenly spread, deterministic, no clumping.
                double k = index + 0.5D;
                double phi = Math.acos(1.0D - 2.0D * k / Math.max(1.0D, half));
                double theta = Math.PI * (1.0D + Math.sqrt(5.0D)) * k;
                double sinPhi = Math.sin(phi);
                double x = Math.cos(theta) * sinPhi;
                double z = Math.sin(theta) * sinPhi;
                double y = Math.abs(Math.cos(phi));
                return new double[] {x * spread, lift * 0.5D + y * lift, z * spread};
            }
        }
    }

    /** Impact points: the offsets resolved against a locked target. */
    public static List<double[]> landings(Pattern pattern, int count, double radius, double height, long seed,
                                          double tx, double ty, double tz) {
        List<double[]> out = new ArrayList<>();
        for (double[] off : offsets(pattern, count, radius, height, seed)) {
            // The skulls fall, so only the horizontal offset survives to impact.
            out.add(new double[] {tx + off[0] * 0.35D, ty, tz + off[2] * 0.35D});
        }
        return out;
    }

    /** The mean point of a set of points: {x, y, z}. */
    public static double[] centroid(List<double[]> points) {
        double[] mean = new double[] {0.0D, 0.0D, 0.0D};
        if (points == null || points.isEmpty()) {
            return mean;
        }
        for (double[] p : points) {
            mean[0] += p[0];
            mean[1] += p[1];
            mean[2] += p[2];
        }
        int n = points.size();
        mean[0] /= n;
        mean[1] /= n;
        mean[2] /= n;
        return mean;
    }

    /** Distance from the centroid of {@code points} to {@code (tx, ty, tz)}. */
    public static double dispersion(List<double[]> points, double tx, double ty, double tz) {
        double[] mean = centroid(points);
        double dx = mean[0] - tx;
        double dy = mean[1] - ty;
        double dz = mean[2] - tz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
