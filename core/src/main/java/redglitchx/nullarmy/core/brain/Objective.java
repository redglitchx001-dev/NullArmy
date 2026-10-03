package redglitchx.nullarmy.core.brain;

/**
 * A goal assigned to a Null or squad.
 *
 * <p>Spec 5: "Give objectives priorities, timeouts, cancellation, and recovery
 * paths." Every objective therefore carries all four.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Objective {

    /** What kind of goal this is. */
    public enum Kind {
        FOLLOW_OWNER,
        FORM_UP,
        ATTACK,
        ATTACK_EXTREME,
        BUILD,
        MINE,
        PATROL,
        SCOUT,
        REGROUP,
        RESUPPLY,
        BUILD_STRUCTURE
    }

    private final long id;
    private final Kind kind;
    private final int priority;
    private final long createdTick;
    private final long timeoutTicks;
    private final int targetEntityId;
    private final String argument;
    private boolean cancelled;

    private Objective(Builder b, long id, long createdTick) {
        this.id = id;
        this.createdTick = createdTick;
        this.kind = b.kind;
        this.priority = b.priority;
        this.timeoutTicks = b.timeoutTicks;
        this.targetEntityId = b.targetEntityId;
        this.argument = b.argument;
        this.cancelled = false;
    }

    public static Builder builder(Kind kind) { return new Builder(kind); }

    public long id() { return id; }
    public Kind kind() { return kind; }

    /** Higher wins. Equal priority resolves to the older objective (lower id). */
    public int priority() { return priority; }

    public long createdTick() { return createdTick; }
    public long timeoutTicks() { return timeoutTicks; }
    public boolean hasTimeout() { return timeoutTicks > 0; }

    /** Target entity id, or -1 when the objective is positional rather than targeted. */
    public int targetEntityId() { return targetEntityId; }
    public boolean hasTarget() { return targetEntityId >= 0; }

    /** Free-form argument, e.g. a structure name. May be null. */
    public String argument() { return argument; }

    public boolean isCancelled() { return cancelled; }
    public void cancel() { cancelled = true; }

    /** @return true if this objective has outlived its timeout */
    public boolean isExpired(long nowTick) {
        return hasTimeout() && (nowTick - createdTick) >= timeoutTicks;
    }

    public static final class Builder {
        private final Kind kind;
        private int priority = 0;
        private long timeoutTicks = 0L;
        private int targetEntityId = -1;
        private String argument = null;

        Builder(Kind kind) {
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            this.kind = kind;
        }

        public Builder priority(int v) { priority = v; return this; }
        public Builder timeoutTicks(long v) { timeoutTicks = v; return this; }
        public Builder targetEntityId(int v) { targetEntityId = v; return this; }
        public Builder argument(String v) { argument = v; return this; }

        public Objective build(long id, long createdTick) {
            return new Objective(this, id, createdTick);
        }
    }
}
