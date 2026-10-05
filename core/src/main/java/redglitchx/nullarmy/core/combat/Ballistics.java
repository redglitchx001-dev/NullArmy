package redglitchx.nullarmy.core.combat;

/**
 * Aiming a bow the way a good player does: lead a moving target and arc the
 * shot over distance.
 *
 * <p>The arrow model is vanilla's: each tick the arrow moves by its velocity,
 * then the velocity is multiplied by 0.99 (air drag) and 0.05 is taken off its
 * vertical part (gravity). A fully drawn bow launches at 3 blocks per tick. The
 * solver estimates the flight time, moves the aim point to where the target
 * will be by then, finds the pitch that drops the arrow onto that point by
 * simulating the flight, and repeats until the estimate settles.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Ballistics {

    public static final double ARROW_GRAVITY = 0.05D;
    public static final double ARROW_DRAG = 0.99D;
    public static final double FULL_DRAW_SPEED = 3.0D;

    /** Vanilla's critical-hit damage multiplier. */
    public static final double CRIT_MULTIPLIER = 1.5D;

    /** A firing solution. Yaw/pitch use Minecraft's convention (negative pitch = up). */
    public static final class Aim {
        private final float yaw;
        private final float pitch;
        private final double aimX;
        private final double aimY;
        private final double aimZ;
        private final int flightTicks;
        private final boolean reachable;

        Aim(float yaw, float pitch, double aimX, double aimY, double aimZ, int flightTicks, boolean reachable) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.aimX = aimX;
            this.aimY = aimY;
            this.aimZ = aimZ;
            this.flightTicks = flightTicks;
            this.reachable = reachable;
        }

        public float yaw() { return yaw; }
        public float pitch() { return pitch; }

        /** Where the target is predicted to be when the arrow arrives. */
        public double aimX() { return aimX; }
        public double aimY() { return aimY; }
        public double aimZ() { return aimZ; }
        public int flightTicks() { return flightTicks; }
        public boolean reachable() { return reachable; }

        /** A point along the firing direction, for code that aims by look target. */
        public double[] lookPoint(double fromX, double fromY, double fromZ, double distance) {
            double yawRad = Math.toRadians(yaw);
            double pitchRad = Math.toRadians(pitch);
            double h = Math.cos(pitchRad);
            return new double[] {
                fromX - Math.sin(yawRad) * h * distance,
                fromY - Math.sin(pitchRad) * distance,
                fromZ + Math.cos(yawRad) * h * distance};
        }
    }

    private Ballistics() {
    }

    /**
     * @param sx shooter eye position
     * @param tx target centre position now
     * @param vx target velocity, blocks per tick
     * @param speed launch speed, blocks per tick (3.0 at full draw)
     */
    public static Aim solve(double sx, double sy, double sz, double tx, double ty, double tz,
                            double vx, double vy, double vz, double speed) {
        double v = speed > 0.1D ? speed : FULL_DRAW_SPEED;
        double px = tx;
        double py = ty;
        double pz = tz;
        double ticks = Math.hypot(tx - sx, tz - sz) / v;
        double angle = 0.0D;
        boolean reachable = true;
        for (int iteration = 0; iteration < 6; iteration++) {
            px = tx + vx * ticks;
            pz = tz + vz * ticks;
            py = ty + Math.max(-0.5D, Math.min(0.5D, vy)) * Math.min(ticks, 10.0D);
            double horizontal = Math.hypot(px - sx, pz - sz);
            double[] solution = solvePitch(horizontal, py - sy, v);
            angle = solution[0];
            reachable = solution[2] > 0.5D;
            double newTicks = solution[1];
            if (Math.abs(newTicks - ticks) < 0.05D) {
                ticks = newTicks;
                break;
            }
            ticks = newTicks;
        }
        float yaw = (float) Math.toDegrees(Math.atan2(-(px - sx), pz - sz));
        float pitch = (float) -Math.toDegrees(angle);
        return new Aim(yaw, pitch, px, py, pz, (int) Math.round(ticks), reachable);
    }

    /**
     * Low-arc pitch (radians, positive = up) that brings the arrow to
     * {@code dy} at horizontal distance {@code horizontal}.
     *
     * @return {angle, flight ticks, 1 when reachable / 0 otherwise}
     */
    static double[] solvePitch(double horizontal, double dy, double speed) {
        double lo = Math.toRadians(-40.0D);
        double hi = Math.toRadians(45.0D);
        double[] atHi = heightAt(horizontal, hi, speed);
        if (Double.isNaN(atHi[0]) || atHi[0] < dy) {
            return new double[] {hi, Double.isNaN(atHi[1]) ? 200.0D : atHi[1], 0.0D};
        }
        double ticks = atHi[1];
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2.0D;
            double[] at = heightAt(horizontal, mid, speed);
            if (Double.isNaN(at[0]) || at[0] < dy) {
                lo = mid;
            } else {
                hi = mid;
                ticks = at[1];
            }
        }
        return new double[] {hi, ticks, 1.0D};
    }

    /**
     * Simulates a launch and returns {height, ticks} when the arrow has covered
     * {@code horizontal} blocks; NaN height when it never gets that far.
     */
    public static double[] heightAt(double horizontal, double angle, double speed) {
        double x = 0.0D;
        double y = 0.0D;
        double vxz = Math.cos(angle) * speed;
        double vy = Math.sin(angle) * speed;
        for (int tick = 1; tick <= 200; tick++) {
            double nx = x + vxz;
            double ny = y + vy;
            if (nx >= horizontal) {
                double f = vxz <= 1.0e-9 ? 1.0D : (horizontal - x) / vxz;
                return new double[] {y + (ny - y) * f, tick - 1 + f};
            }
            x = nx;
            y = ny;
            vxz *= ARROW_DRAG;
            vy = vy * ARROW_DRAG - ARROW_GRAVITY;
            if (y < -64.0D) {
                break;
            }
        }
        return new double[] {Double.NaN, Double.NaN};
    }

    /**
     * Signed lead of an aim in the target's direction of travel: positive when
     * the aim point lies ahead of the target's current position.
     */
    public static double leadAlongVelocity(Aim aim, double tx, double tz, double vx, double vz) {
        double speed = Math.hypot(vx, vz);
        if (speed < 1.0e-9) {
            return 0.0D;
        }
        return ((aim.aimX() - tx) * vx + (aim.aimZ() - tz) * vz) / speed;
    }
}
