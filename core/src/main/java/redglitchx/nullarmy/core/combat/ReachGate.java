package redglitchx.nullarmy.core.combat;

/**
 * The one and only melee reach gate.
 *
 * <p><b>P-01.</b> Reach was previously "close enough" arithmetic scattered
 * through the combat brain, so a Null could land a hit from well outside
 * vanilla range and, worse, through a solid wall. Vanilla survival melee reach
 * is exactly 3.0 blocks measured from the attacker's eye to the target's hit
 * box, and it additionally requires an unobstructed line of sight. Nothing in
 * the plugin may strike without passing both halves of this gate.</p>
 *
 * <p>Pure: no Bukkit, no NMS. The caller supplies the occlusion test so the
 * same rule runs in the live world and in a unit test.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ReachGate {

    /** Vanilla survival melee reach, eye to target. */
    public static final double VANILLA_REACH = 3.0D;

    /** How finely the line of sight segment is sampled, in blocks. */
    public static final double LOS_STEP = 0.25D;

    /** True when the block at those world coordinates stops a hit. */
    public interface Occlusion {
        boolean solid(double x, double y, double z);
    }

    private ReachGate() {
    }

    /** Distance from an eye to a point. */
    public static double eyeDistance(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** True when a point lies within {@code reach} of an eye. */
    public static boolean inReach(double ex, double ey, double ez, double tx, double ty, double tz, double reach) {
        if (!Double.isFinite(reach) || reach <= 0.0D) {
            return false;
        }
        return eyeDistance(ex, ey, ez, tx, ty, tz) <= reach;
    }

    /**
     * True when nothing solid stands between the eye and the point.
     *
     * <p>The endpoints themselves are not tested: the eye is inside the
     * attacker's own head and the target point is on the target's own body, so
     * testing either would block every strike.</p>
     */
    public static boolean lineOfSight(double ex, double ey, double ez, double tx, double ty, double tz,
                                      Occlusion occlusion) {
        if (occlusion == null) {
            return true;
        }
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6D) {
            return true;
        }
        int samples = Math.max(1, (int) Math.floor(length / LOS_STEP));
        for (int i = 1; i < samples; i++) {
            double t = (double) i / (double) samples;
            if (occlusion.solid(ex + dx * t, ey + dy * t, ez + dz * t)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The full gate: inside reach <em>and</em> an unobstructed line of sight.
     *
     * <p>A corner counts as blocked, because the sample that passes through the
     * corner block reports solid.</p>
     */
    public static boolean strikeAllowed(double ex, double ey, double ez, double tx, double ty, double tz,
                                        double reach, Occlusion occlusion) {
        return inReach(ex, ey, ez, tx, ty, tz, reach)
                && lineOfSight(ex, ey, ez, tx, ty, tz, occlusion);
    }

    /** A reach value clamped to something a vanilla client could also do. */
    public static double clampReach(double configured) {
        if (!Double.isFinite(configured) || configured <= 0.0D) {
            return VANILLA_REACH;
        }
        return Math.min(VANILLA_REACH, configured);
    }
}
