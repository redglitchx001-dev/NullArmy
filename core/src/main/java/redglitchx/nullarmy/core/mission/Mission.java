package redglitchx.nullarmy.core.mission;

/**
 * One running mission.
 *
 * <p>A mission is a small state machine the plugin drives: it knows its kind, how
 * much progress has been made, when it started and when it gives up. Everything
 * that could hurt somebody - block damage, explosives, PvP - is out of scope by
 * construction: there is no field for it here and no code path that adds one.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Mission {

    /** What a mission is doing. */
    public enum State {
        /** Not running. */
        IDLE,
        /** Under way and reporting progress. */
        RUNNING,
        /** Every unit of progress was reached. */
        COMPLETE,
        /** Stopped by an order, or the time ran out. */
        STOPPED
    }

    private final MissionKind kind;
    private final long startTick;
    private final int squadSize;

    private State state = State.RUNNING;
    private int progress;
    private long endTick = -1L;
    private String note = "";

    public Mission(MissionKind kind, long startTick, int squadSize) {
        if (kind == null) {
            throw new IllegalArgumentException("a mission needs a kind");
        }
        this.kind = kind;
        this.startTick = startTick;
        this.squadSize = Math.max(0, squadSize);
    }

    public MissionKind kind() { return kind; }
    public State state() { return state; }
    public long startTick() { return startTick; }
    public int squadSize() { return squadSize; }
    public int progress() { return progress; }
    public int goal() { return kind.goal(); }
    public long endTick() { return endTick; }
    public String note() { return note; }

    /** True while the squad is working on it. */
    public boolean isRunning() { return state == State.RUNNING; }

    /**
     * Records progress.
     *
     * @param amount units completed; a negative or zero amount changes nothing
     * @return true when this call finished the mission
     */
    public boolean advance(int amount, long now) {
        if (state != State.RUNNING || amount <= 0) {
            return false;
        }
        progress = Math.min(kind.goal(), progress + amount);
        if (progress >= kind.goal()) {
            state = State.COMPLETE;
            endTick = now;
            note = "objective reached";
            return true;
        }
        return false;
    }

    /** Stops the mission safely: no entity is left mid-task, nothing is removed. */
    public void stop(long now, String reason) {
        if (state != State.RUNNING) {
            return;
        }
        state = State.STOPPED;
        endTick = now;
        note = reason == null || reason.trim().isEmpty() ? "stopped" : reason.trim();
    }

    /**
     * Checks the clock.
     *
     * @return true when this call ended the mission because time ran out
     */
    public boolean tick(long now) {
        if (state != State.RUNNING) {
            return false;
        }
        if (now - startTick >= kind.durationTicks()) {
            state = State.STOPPED;
            endTick = now;
            note = "time ran out after " + (kind.durationTicks() / 20) + "s";
            return true;
        }
        return false;
    }

    /** How much of the objective is done, 0..1. */
    public double completion() {
        return kind.goal() <= 0 ? 0.0 : (double) progress / (double) kind.goal();
    }

    /** One readable line: "The Null Trials - 3/7 course gates passed (running)". */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind.title()).append(" - ").append(progress).append('/').append(kind.goal())
                .append(' ').append(kind.unit())
                .append(" (").append(state.name().toLowerCase(java.util.Locale.ROOT)).append(')');
        if (!note.isEmpty()) {
            sb.append(": ").append(note);
        }
        return sb.toString();
    }

    /** Two lines for chat: the briefing and the progress. */
    public String[] describe() {
        return new String[] {
                kind.title() + " [" + kind.key() + "] - " + kind.briefing(),
                "progress " + progress + "/" + kind.goal() + " " + kind.unit()
                        + ", squad of " + squadSize + ", state "
                        + state.name().toLowerCase(java.util.Locale.ROOT)
                        + (note.isEmpty() ? "" : " (" + note + ")")
        };
    }
}
