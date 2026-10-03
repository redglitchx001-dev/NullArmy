package redglitchx.nullarmy.core.brain;

import java.util.Comparator;
import java.util.List;

/**
 * The deterministic local decision maker.
 *
 * <p>Spec 5: "Use a deterministic, inspectable local state machine/utility
 * planner as the final authority." Spec 2.2: this runs server-side and is the
 * thing that validates before acting. An external agent may <em>advise</em>;
 * this class decides.</p>
 *
 * <p>Determinism is a requirement, not a nicety: given the same context the
 * planner must always choose the same state, so a bug can be reproduced from a
 * log. There is no randomness here - randomness lives only in bounded idle
 * behaviour, which is deliberately kept out of the planner.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class UtilityPlanner {

    /** Everything the planner is allowed to know. No hidden or through-wall data. */
    public interface Context {
        /** Current health in [0, max]. */
        double healthFraction();
        /** Food level in [0, 20]. */
        int foodLevel();
        /** True if a threat was perceived by line of sight this tick. */
        boolean threatVisible();
        /** True if the squad is short of a resource it needs for its objective. */
        boolean needsResupply();
        /** True if the Null is stuck and has exhausted cheap recovery options. */
        boolean isStuck();
        /** True if a build order is pending and materials are available. */
        boolean hasBuildOrder();
        /** True if the plugin is shutting down or the squad was dismissed. */
        boolean shutdownRequested();
    }

    private static final double HEALTH_RETREAT_THRESHOLD = 0.25;
    private static final int FOOD_CRITICAL = 6;

    /**
     * Chooses the next state.
     *
     * <p>Order matters and is fixed. Safety-relevant checks come first so they
     * cannot be outranked by a commanded objective - spec 5: "Do not let
     * 'lifelike' randomness override danger checks or commanded objectives",
     * and conversely a danger check must not be drowned out by a goal.</p>
     *
     * @param objectives candidate objectives, any of which may be cancelled or expired
     * @param nowTick    current server tick
     */
    public NullState choose(Context context, List<Objective> objectives, long nowTick) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }

        // 1. Shutdown is terminal and cannot be overridden.
        if (context.shutdownRequested()) {
            return NullState.SAFE_SHUTDOWN;
        }

        // 2. Survival outranks orders.
        if (context.healthFraction() <= HEALTH_RETREAT_THRESHOLD) {
            return NullState.RETREAT;
        }
        if (context.foodLevel() <= FOOD_CRITICAL) {
            return NullState.HEAL;
        }

        // 3. Being stuck gets its own recovery state - never a teleport.
        if (context.isStuck()) {
            return NullState.CROSS_OBSTACLE;
        }

        Objective best = bestObjective(objectives, nowTick);

        if (best == null) {
            // No live objective: fall back to cheap, safe behaviour.
            if (context.needsResupply()) {
                return NullState.RESUPPLY;
            }
            if (context.threatVisible()) {
                return NullState.COMBAT;
            }
            return NullState.IDLE;
        }

        // 4. A live objective decides, but resupply can pre-empt work orders.
        if (context.needsResupply() && best.kind() != Objective.Kind.ATTACK
                && best.kind() != Objective.Kind.ATTACK_EXTREME) {
            return NullState.RESUPPLY;
        }

        return stateFor(best.kind());
    }

    /**
     * Picks the highest-priority live objective.
     *
     * <p>Ties resolve to the lower id (older objective) so the result is stable.</p>
     */
    private static Objective bestObjective(List<Objective> objectives, long nowTick) {
        if (objectives == null || objectives.isEmpty()) {
            return null;
        }
        return objectives.stream()
                .filter(o -> !o.isCancelled())
                .filter(o -> !o.isExpired(nowTick))
                .sorted(Comparator
                        .comparingInt(Objective::priority).reversed()
                        .thenComparingLong(Objective::id))
                .findFirst()
                .orElse(null);
    }

    private static NullState stateFor(Objective.Kind kind) {
        switch (kind) {
            case FOLLOW_OWNER: return NullState.FOLLOW;
            case FORM_UP: return NullState.FORM_UP;
            case ATTACK: return NullState.COMBAT;
            case ATTACK_EXTREME: return NullState.COMBAT;
            case BUILD:
            case BUILD_STRUCTURE: return NullState.BUILD;
            case MINE: return NullState.MINE;
            case PATROL: return NullState.PATROL;
            case SCOUT: return NullState.SCOUT;
            case REGROUP: return NullState.REGROUP;
            case RESUPPLY: return NullState.RESUPPLY;
            default: return NullState.IDLE;
        }
    }
}
