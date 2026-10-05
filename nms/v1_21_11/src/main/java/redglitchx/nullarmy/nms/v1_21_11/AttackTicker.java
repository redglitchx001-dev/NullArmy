package redglitchx.nullarmy.nms.v1_21_11;

import java.lang.reflect.Field;

/**
 * Advances the vanilla attack-cooldown counter of a body.
 *
 * <p>{@code attackStrengthTicker} is what {@code getAttackStrengthScale} - and
 * therefore {@code HumanEntity#getAttackCooldown()}, the damage of every swing
 * and the critical-hit condition - is computed from. Vanilla increments it in
 * the living-entity tick that a Null overrides, so without this every Null swing
 * would count as an uncharged tap.</p>
 *
 * <p>The field is looked up reflectively along the class hierarchy because its
 * declaring class has moved between versions (Player vs. LivingEntity); a
 * missing field only means swings stay at vanilla's minimum strength.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class AttackTicker {

    private static volatile Field field;
    private static volatile boolean missing;

    private AttackTicker() {
    }

    static void increment(Object body) {
        Field f = resolve(body);
        if (f == null) {
            return;
        }
        try {
            int now = f.getInt(body);
            if (now < 10_000) {
                f.setInt(body, now + 1);
            }
        } catch (Throwable ignored) {
            // A failed read is the old behaviour, never a crash.
        }
    }

    /** True when the counter could be found on this server build. */
    static boolean available(Object body) {
        return resolve(body) != null;
    }

    private static Field resolve(Object body) {
        if (field != null || missing || body == null) {
            return field;
        }
        synchronized (AttackTicker.class) {
            if (field != null || missing) {
                return field;
            }
            for (Class<?> type = body.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                try {
                    Field candidate = type.getDeclaredField("attackStrengthTicker");
                    candidate.setAccessible(true);
                    field = candidate;
                    return field;
                } catch (NoSuchFieldException next) {
                    // Keep walking up.
                } catch (Throwable t) {
                    break;
                }
            }
            missing = true;
            return null;
        }
    }
}
