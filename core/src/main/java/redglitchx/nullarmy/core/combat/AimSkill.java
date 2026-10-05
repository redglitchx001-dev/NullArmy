package redglitchx.nullarmy.core.combat;

/**
 * Imperfect aim.
 *
 * <p><b>P-05.</b> "their aim is too perfect" - and it was: the combat brain
 * handed the ballistics solver the target's exact position and fired the moment
 * it was aligned, so a Null never missed. Perfect aim is banned. A Null now aims
 * with a skill-derived angular error, a lead error, a reaction delay and a
 * distance-dependent chance of a complete miss.</p>
 *
 * <p>{@code combat.aim-skill} is 0..1 and defaults to {@value #DEFAULT}. At the
 * default a shot carries a {@link #angleErrorDeg(double, double) yaw/pitch error}
 * of up to about {@value #MAX_ERROR_DEG} - {@value #ERROR_SPAN_DEG} * skill
 * degrees, so nothing is ever laser-accurate.</p>
 *
 * <p>Pure: deterministic in its inputs, so the accuracy envelope is unit
 * tested instead of eyeballed.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class AimSkill {

    /** Default {@code combat.aim-skill}. */
    public static final double DEFAULT = 0.65D;

    /** Worst-case angular error at skill 0, degrees. */
    public static final double MAX_ERROR_DEG = 10.0D;

    /** How much of that error perfect skill would remove, degrees. */
    public static final double ERROR_SPAN_DEG = 6.0D;

    /** Beyond this range a shot can miss outright. */
    public static final double FULL_MISS_RANGE = 12.0D;

    /** Shortest reaction delay, ticks (0.3 s at 20 tps). */
    public static final int MIN_REACTION_TICKS = 6;

    /** Longest reaction delay, ticks (0.8 s at 20 tps). */
    public static final int MAX_REACTION_TICKS = 16;

    private AimSkill() {
    }

    /** Clamps a configured skill into 0..1. */
    public static double clamp(double skill) {
        if (!Double.isFinite(skill)) {
            return DEFAULT;
        }
        return Math.max(0.0D, Math.min(1.0D, skill));
    }

    /** Largest yaw/pitch error this skill produces, degrees (4..10). */
    public static double maxAngleErrorDeg(double skill) {
        return MAX_ERROR_DEG - ERROR_SPAN_DEG * clamp(skill);
    }

    /**
     * One angular error, degrees.
     *
     * @param roll a number in -1..1 (0 = dead centre)
     */
    public static double angleErrorDeg(double skill, double roll) {
        double r = Math.max(-1.0D, Math.min(1.0D, roll));
        return maxAngleErrorDeg(skill) * r;
    }

    /**
     * How wrong the lead is, as a fraction of the correct lead time.
     * A skilled Null is within about a tenth of the lead; a hopeless one is off
     * by a third of it.
     */
    public static double leadErrorFraction(double skill, double roll) {
        double r = Math.max(-1.0D, Math.min(1.0D, roll));
        return r * (1.0D - clamp(skill)) * 0.33D;
    }

    /** Reaction delay in ticks (6..16 at the default skill). */
    public static long reactionTicks(double skill, double unit) {
        double u = Math.max(0.0D, Math.min(1.0D, unit));
        double span = MAX_REACTION_TICKS - MIN_REACTION_TICKS;
        // A sharper Null reacts nearer the short end of the window.
        double biased = (1.0D - clamp(skill)) * 0.5D + u * 0.5D;
        return MIN_REACTION_TICKS + Math.round(span * biased);
    }

    /**
     * Chance that a shot at this distance lands, 0..1.
     *
     * <p>At the default skill a standing target 15 blocks away is hit roughly
     * 70 % of the time - inside the 40-80 % band the owner asked for, and
     * visibly short of "always".</p>
     */
    public static double hitChance(double distance, double skill) {
        double d = Math.max(0.0D, distance);
        double base = 0.35D + 0.60D * clamp(skill);
        double falloff = Math.max(0.0D, d - FULL_MISS_RANGE) * 0.015D;
        return Math.max(0.0D, Math.min(1.0D, base - falloff));
    }

    /**
     * True when the shot misses outright.
     *
     * <p>Inside {@value #FULL_MISS_RANGE} blocks a Null never misses wholesale -
     * it just aims imprecisely. Beyond that range the chance grows with
     * distance.</p>
     */
    public static boolean fullMiss(double distance, double skill, double unit) {
        double d = Math.max(0.0D, distance);
        if (d <= FULL_MISS_RANGE) {
            return false;
        }
        double chance = hitChance(d, skill);
        return unit >= chance;
    }

    /**
     * Applies the aim error to a firing solution.
     *
     * @return {yaw, pitch} in degrees, already wrapped to -180..180
     */
    public static double[] applyErrorDeg(double yaw, double pitch, double skill, double rollYaw, double rollPitch) {
        return new double[] {wrapDeg(yaw + angleErrorDeg(skill, rollYaw)),
                clampPitch(pitch + angleErrorDeg(skill, rollPitch))};
    }

    private static double wrapDeg(double degrees) {
        double d = degrees % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }

    private static double clampPitch(double pitch) {
        return Math.max(-90.0D, Math.min(90.0D, pitch));
    }
}
