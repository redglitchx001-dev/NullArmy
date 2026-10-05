package redglitchx.nullarmy.core.drops;

/**
 * What a defeated Null leaves on the ground.
 *
 * <p><b>P-10.</b> A Null used to vanish with its whole kit - netherite armour,
 * tools, blocks and all - because the death listener cleared the drops to keep
 * chat quiet. The owner wants the loot: a Null is beatable, and beating one
 * should pay.</p>
 *
 * <p>This is only the decision. Whether a particular item is dropped, where it
 * lands and how it is protected is the listener's job; the rule that decides
 * <em>that</em> a drop happens lives here, where it is tested.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class DeathDrops {

    /** Shipped {@code drops.chance}. */
    public static final double DEFAULT_CHANCE = 1.0D;

    private DeathDrops() {
    }

    /** Clamps a configured chance into 0..1. */
    public static double clampChance(double chance) {
        if (!Double.isFinite(chance)) {
            return DEFAULT_CHANCE;
        }
        return Math.max(0.0D, Math.min(1.0D, chance));
    }

    /**
     * Whether one item is dropped.
     *
     * @param enabled {@code drops.enabled}
     * @param chance  {@code drops.chance}
     * @param roll    a number in 0..1 drawn by the caller
     */
    public static boolean shouldDrop(boolean enabled, double chance, double roll) {
        if (!enabled) {
            return false;
        }
        double c = clampChance(chance);
        if (c <= 0.0D) {
            return false;
        }
        if (c >= 1.0D) {
            return true;
        }
        double r = Math.max(0.0D, Math.min(1.0D, roll));
        return r < c;
    }

    /**
     * True when the plugin is allowed to drop anything at all.
     *
     * <p>The legacy {@code nulls.no-death-drops} key is the exact inverse of
     * {@code drops.enabled}; an existing config that set it to true keeps
     * winning, so an upgrade never starts littering the ground on a server whose
     * owner already said no.</p>
     */
    public static boolean enabled(boolean dropsEnabled, boolean legacyNoDrops) {
        return dropsEnabled && !legacyNoDrops;
    }
}
