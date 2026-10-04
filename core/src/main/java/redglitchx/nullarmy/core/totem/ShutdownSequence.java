package redglitchx.nullarmy.core.totem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The ordered, timed shutdown a Totem Of Null starts.
 *
 * <p>When the tagged totem pops or is truly destroyed, every Null - including
 * the Commander - dies or despawns <b>one at a time</b> with a visible short
 * delay between them. Nothing is deleted in one tick: the point is that the
 * server watches the army go out member by member.</p>
 *
 * <p>While the sequence runs, summons are cancelled and no new Null may appear.
 * This class only computes the schedule and answers "is it still running"; the
 * plugin owns the tasks that execute it.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ShutdownSequence {

    /** Default visible gap between two Nulls going out, in ticks. */
    public static final int DEFAULT_DELAY_TICKS = 10;

    /** One member of the sequence. */
    public static final class Step {
        private final int index;
        private final String label;
        private final boolean commander;
        private final long atTick;

        Step(int index, String label, boolean commander, long atTick) {
            this.index = index;
            this.label = label;
            this.commander = commander;
            this.atTick = atTick;
        }

        public int index() { return index; }
        public String label() { return label; }
        public boolean commander() { return commander; }
        public long atTick() { return atTick; }

        @Override
        public String toString() {
            return "#" + index + " " + label + (commander ? " (Commander)" : "") + " @" + atTick;
        }
    }

    /** A body offered to the sequence. */
    public static final class Entry {
        private final String label;
        private final boolean commander;

        public Entry(String label, boolean commander) {
            this.label = label == null ? "a Null" : label;
            this.commander = commander;
        }

        public String label() { return label; }
        public boolean commander() { return commander; }
    }

    private ShutdownSequence() {
    }

    /**
     * Builds the schedule.
     *
     * <p>Ordinary Nulls go first in the order they are given; every Commander is
     * held back to the end, so the last body to fall is the one leading them.
     * The gap is at least one tick, because a zero gap is the single-tick mass
     * delete this exists to avoid.</p>
     *
     * @param entries    the bodies to shut down
     * @param startTick  the server tick the first step runs on
     * @param delayTicks visible gap between steps
     */
    public static List<Step> schedule(List<Entry> entries, long startTick, int delayTicks) {
        List<Step> steps = new ArrayList<>();
        if (entries == null || entries.isEmpty()) {
            return Collections.unmodifiableList(steps);
        }
        int delay = Math.max(1, delayTicks);
        List<Entry> ordinary = new ArrayList<>();
        List<Entry> commanders = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.commander()) {
                commanders.add(entry);
            } else {
                ordinary.add(entry);
            }
        }
        List<Entry> ordered = new ArrayList<>(ordinary.size() + commanders.size());
        ordered.addAll(ordinary);
        ordered.addAll(commanders);
        long tick = startTick;
        for (int i = 0; i < ordered.size(); i++) {
            Entry entry = ordered.get(i);
            steps.add(new Step(i, entry.label(), entry.commander(), tick));
            tick += delay;
        }
        return Collections.unmodifiableList(steps);
    }

    /** The tick the last step runs on. {@code startTick - 1} for an empty plan. */
    public static long endTick(List<Step> steps, long startTick) {
        if (steps == null || steps.isEmpty()) {
            return startTick - 1;
        }
        return steps.get(steps.size() - 1).atTick();
    }

    /** True while the sequence is running: summons must be refused. */
    public static boolean isRunning(List<Step> steps, long startTick, long now) {
        if (steps == null || steps.isEmpty()) {
            return false;
        }
        return now >= startTick && now <= endTick(steps, startTick);
    }

    /** Steps due at or before {@code now}. */
    public static List<Step> due(List<Step> steps, long now) {
        List<Step> out = new ArrayList<>();
        if (steps == null) {
            return out;
        }
        for (Step step : steps) {
            if (step.atTick() <= now) {
                out.add(step);
            }
        }
        return out;
    }

    /** "12 steps, one every 10 ticks, Commander last, done at tick 1234". */
    public static String describe(List<Step> steps, int delayTicks) {
        if (steps == null || steps.isEmpty()) {
            return "no Nulls to shut down";
        }
        return steps.size() + " step(s), one every " + Math.max(1, delayTicks)
                + " tick(s), Commander last";
    }
}
