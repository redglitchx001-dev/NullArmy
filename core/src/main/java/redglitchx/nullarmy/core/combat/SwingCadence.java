package redglitchx.nullarmy.core.combat;

/**
 * How often a Null swings, and how often it lands a critical.
 *
 * <p><b>P-02.</b> Two complaints, one root cause each:</p>
 * <ol>
 *   <li>"they barely attack" - the combat brain only ever swung at a
 *       <em>full</em> attack cooldown, so a Null threw one blow every 13 ticks
 *       instead of the stream of blows a player produces. A real player does not
 *       wait: he swings the moment the meter is high enough to be worth it.</li>
 *   <li>"crits almost never happen" - a crit needed a deliberate jump-and-fall
 *       cycle that could take seconds to set up, so the x1.5 was a rarity.</li>
 * </ol>
 *
 * <p>The cadence lives here, in pure code, so the swing rate and crit rate are
 * asserted rather than watched.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SwingCadence {

    /**
     * A Null swings as soon as its cooldown reaches this fraction, not at 1.0.
     *
     * <p>0.55 keeps roughly half the weapon's damage but more than doubles the
     * number of blows: spam is what the owner asked for.</p>
     */
    public static final float MIN_COOLDOWN = 0.55F;

    /** A critical hit deals 1.5x damage (vanilla). */
    public static final double CRIT_MULTIPLIER = 1.5D;

    /** A crit lands on every second swing while the fighter is falling. */
    public static final int CRIT_EVERY_SWINGS = 2;

    private SwingCadence() {
    }

    /** True when the cooldown meter is high enough to be worth swinging. */
    public static boolean ready(float cooldown) {
        return Float.isFinite(cooldown) && cooldown >= MIN_COOLDOWN;
    }

    /**
     * True when the next swing should be delivered as a critical hit.
     *
     * <p>A crit needs the vanilla condition - falling, not on the ground, not
     * sprinting - and it happens on every second swing at most, so a fighter
     * raining blows produces a crit every one to two swings.</p>
     */
    public static boolean critDue(int swingsSinceCrit, boolean falling) {
        if (!falling || swingsSinceCrit < 1) {
            return false;
        }
        return swingsSinceCrit % CRIT_EVERY_SWINGS == 1;
    }

    /**
     * How many swings a weapon can throw in {@code ticks}.
     *
     * @param ticks             the window, in ticks
     * @param fullCooldownTicks ticks to a full cooldown for the weapon
     * @param threshold         the cooldown fraction a Null swings at
     */
    public static int swingsIn(int ticks, int fullCooldownTicks, float threshold) {
        int span = Math.max(1, ticks);
        int full = Math.max(1, fullCooldownTicks);
        float gate = Math.max(0.05F, Math.min(1.0F, threshold));
        float cooldown = 0.0F;
        int swings = 0;
        for (int t = 0; t < span; t++) {
            cooldown = Math.min(1.0F, cooldown + 1.0F / full);
            if (cooldown >= gate) {
                swings++;
                cooldown = 0.0F;
            }
        }
        return swings;
    }

    /** The damage a swing deals: base, or base x1.5 for a critical. */
    public static double damage(double base, boolean critical) {
        return critical ? base * CRIT_MULTIPLIER : base;
    }
}
