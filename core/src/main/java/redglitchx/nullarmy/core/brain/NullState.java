package redglitchx.nullarmy.core.brain;

/**
 * The behavioural states a Null can occupy.
 *
 * <p>Enumerated from spec 5 ("Use a deterministic, inspectable local state
 * machine/utility planner ... include states such as: idle, follow, form up,
 * patrol, scout, investigate, combat, retreat, resupply, scavenge, heal, build,
 * mine, cross obstacle, board/steer vehicle, regroup, wait-for-supply, and safe
 * shutdown").</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public enum NullState {
    IDLE,
    FOLLOW,
    FORM_UP,
    PATROL,
    SCOUT,
    INVESTIGATE,
    COMBAT,
    RETREAT,
    RESUPPLY,
    SCAVENGE,
    HEAL,
    BUILD,
    MINE,
    CROSS_OBSTACLE,
    BOARD_VEHICLE,
    REGROUP,
    WAIT_FOR_SUPPLY,
    SAFE_SHUTDOWN;

    /**
     * @return true for states where the Null must stay put even if tempted to
     *     chase an objective - shutting down outranks everything
     */
    public boolean isTerminal() { return this == SAFE_SHUTDOWN; }

    /** @return true for states that must never be interrupted by optional idle behaviour */
    public boolean isUrgent() {
        return this == COMBAT || this == RETREAT || this == HEAL || this == SAFE_SHUTDOWN;
    }
}
