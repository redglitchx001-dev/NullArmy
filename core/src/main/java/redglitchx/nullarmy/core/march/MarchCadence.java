package redglitchx.nullarmy.core.march;

/**
 * Locked step.
 *
 * <p><b>L-01.</b> A squad that "marches" by running the ordinary follow steering
 * per body arrives strung out and jittering, because every body recomputes its
 * own speed every tick. A real march is one shared clock: the whole squad
 * changes gait on the same tick, so the line stays a line.</p>
 *
 * <p>Pure, so the cadence is unit tested rather than watched.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MarchCadence {

    /** Ticks between two steps of the march (0.4 s). */
    public static final int DEFAULT_PERIOD_TICKS = 8;

    /** How long the drill holds one formation before cycling, ticks (4 s). */
    public static final int DEFAULT_DRILL_TICKS = 80;

    /** The formations the drill cycles through, in order. */
    private static final String[] DRILL = {"line", "wedge", "phalanx"};

    private MarchCadence() {
    }

    /** A sane step period: at least 2 ticks, at most 2 seconds. */
    public static int clampPeriod(int configured) {
        if (configured <= 0) {
            return DEFAULT_PERIOD_TICKS;
        }
        return Math.max(2, Math.min(40, configured));
    }

    /** Which step of the march a tick belongs to. */
    public static int phase(long tick, int period) {
        int p = clampPeriod(period);
        return (int) Math.floorMod(tick, (long) p);
    }

    /** True on the tick the whole squad takes a step. */
    public static boolean stepTick(long tick, int period) {
        return phase(tick, period) == 0;
    }

    /**
     * How far a body advances on this tick, as a fraction of one step.
     *
     * <p>Every body gets the same value on the same tick, so the line does not
     * shear. The squad covers one full step per period.</p>
     */
    public static double advanceFraction(long tick, int period) {
        int p = clampPeriod(period);
        // One quarter of the period is the push, the rest is the glide: the
        // slight hitch is what makes a march look like a march.
        int phase = phase(tick, p);
        if (phase != 0) {
            return 0.0D;
        }
        return 1.0D;
    }

    /** Which formation the drill is holding at a tick. */
    public static String drillFormation(long tick, int holdTicks) {
        int hold = holdTicks <= 0 ? DEFAULT_DRILL_TICKS : holdTicks;
        long index = Math.floorDiv(tick, (long) hold) % DRILL.length;
        return DRILL[(int) index];
    }

    /** How many formations the drill cycles through. */
    public static int drillLength() {
        return DRILL.length;
    }
}
