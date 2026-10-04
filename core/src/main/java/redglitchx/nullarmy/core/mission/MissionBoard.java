package redglitchx.nullarmy.core.mission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one mission the whole NullArmy is working on, and the history of the last
 * few.
 *
 * <p>One objective at a time, on purpose: a mission exists so the Commander and
 * every active Null pull in the same direction and can report progress against a
 * single thing. Starting a second mission stops the first, and stopping is always
 * safe - the board never removes an entity, a block or a task by itself.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MissionBoard {

    /** How many finished missions are kept for the report. */
    private static final int HISTORY_LIMIT = 8;

    private Mission active;
    private final List<Mission> history = new ArrayList<>();

    /**
     * Starts a mission, stopping any that is running.
     *
     * @return the started mission, or null when the kind is unknown
     */
    public Mission start(MissionKind kind, long now, int squadSize, String stopReason) {
        if (kind == null) {
            return null;
        }
        if (active != null && active.isRunning()) {
            active.stop(now, stopReason == null ? "replaced by " + kind.title() : stopReason);
            remember(active);
        }
        active = new Mission(kind, now, squadSize);
        return active;
    }

    /** The mission currently running, or null. */
    public Mission active() {
        return active != null && active.isRunning() ? active : null;
    }

    public boolean isRunning() { return active() != null; }

    /**
     * Records progress on the running mission.
     *
     * @return true when the mission completed on this call
     */
    public boolean advance(int amount, long now) {
        Mission mission = active();
        if (mission == null) {
            return false;
        }
        boolean finished = mission.advance(amount, now);
        if (finished) {
            remember(mission);
            active = null;
        }
        return finished;
    }

    /**
     * Stops the running mission.
     *
     * @return true when something was stopped
     */
    public boolean stop(long now, String reason) {
        Mission mission = active();
        if (mission == null) {
            return false;
        }
        mission.stop(now, reason);
        remember(mission);
        active = null;
        return true;
    }

    /** Advances the clock; a mission that ran out of time stops itself. */
    public boolean tick(long now) {
        Mission mission = active();
        if (mission == null) {
            return false;
        }
        if (mission.tick(now)) {
            remember(mission);
            active = null;
            return true;
        }
        return false;
    }

    private void remember(Mission mission) {
        if (mission == null) {
            return;
        }
        history.add(0, mission);
        while (history.size() > HISTORY_LIMIT) {
            history.remove(history.size() - 1);
        }
    }

    /** The last few missions, newest first. */
    public List<Mission> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** What {@code /null mission} prints. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        Mission mission = active();
        if (mission == null) {
            out.add("no mission running - /null mission start <kind> begins one");
        } else {
            Collections.addAll(out, mission.describe());
        }
        int shown = 0;
        for (Mission past : history) {
            if (shown >= 3) {
                break;
            }
            if (mission != null && past == mission) {
                continue;
            }
            out.add("  last: " + past.report());
            shown++;
        }
        return out;
    }

    /** Every mission key, for tab completion and the help text. */
    public static List<String> kinds() {
        List<String> out = new ArrayList<>();
        for (MissionKind kind : MissionKind.values()) {
            out.add(kind.key());
        }
        return Collections.unmodifiableList(out);
    }
}
