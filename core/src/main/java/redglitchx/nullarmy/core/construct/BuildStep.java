package redglitchx.nullarmy.core.construct;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One physical step of a build: walk somewhere, break a block, place a block,
 * pick something up, or wait.
 *
 * <p>Coordinates are block coordinates <b>relative to the zone origin</b> (the
 * horn user's block position when the zone was opened), so an AI plan never has
 * to know absolute world coordinates and can never reach outside the zone.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BuildStep {

    /** What a step does. Nothing else exists: there is no "paste" and no "fill". */
    public enum Action {
        MOVE, BREAK, PLACE, PICKUP, WAIT;

        /** Case-insensitive lookup; null when unknown. */
        public static Action parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    private final String nullName;
    private final Action action;
    private final int x;
    private final int y;
    private final int z;
    private final String block;
    private final int ticks;

    public BuildStep(String nullName, Action action, int x, int y, int z, String block, int ticks) {
        if (action == null) {
            throw new IllegalArgumentException("a step needs an action");
        }
        this.nullName = nullName == null ? "" : nullName.trim();
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null ? "" : block.trim().toUpperCase(Locale.ROOT);
        this.ticks = Math.max(0, Math.min(20 * 60, ticks));
    }

    public static BuildStep move(int x, int y, int z) {
        return new BuildStep("", Action.MOVE, x, y, z, "", 0);
    }

    public static BuildStep place(int x, int y, int z, String block) {
        return new BuildStep("", Action.PLACE, x, y, z, block, 0);
    }

    public static BuildStep breakAt(int x, int y, int z) {
        return new BuildStep("", Action.BREAK, x, y, z, "", 0);
    }

    public static BuildStep pickup(int x, int y, int z) {
        return new BuildStep("", Action.PICKUP, x, y, z, "", 0);
    }

    public static BuildStep waitTicks(int ticks) {
        return new BuildStep("", Action.WAIT, 0, 0, 0, "", ticks);
    }

    /** The Null this step is for; "" means "whichever Null is free". */
    public String nullName() { return nullName; }
    public Action action() { return action; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    /** Material name for PLACE, upper case; "" otherwise. */
    public String block() { return block; }

    /** Ticks for WAIT. */
    public int ticks() { return ticks; }

    /** The same step assigned to a named Null. */
    public BuildStep forNull(String name) {
        return new BuildStep(name, action, x, y, z, block, ticks);
    }

    /** The JSON object form the AI is asked to produce. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!nullName.isEmpty()) {
            out.put("null", nullName);
        }
        out.put("action", action.name());
        out.put("x", (double) x);
        out.put("y", (double) y);
        out.put("z", (double) z);
        if (!block.isEmpty()) {
            out.put("block", block);
        }
        if (action == Action.WAIT) {
            out.put("ticks", (double) ticks);
        }
        return out;
    }

    @Override
    public String toString() {
        return action + (nullName.isEmpty() ? "" : "[" + nullName + "]") + "(" + x + "," + y + "," + z
                + (block.isEmpty() ? "" : " " + block) + (action == Action.WAIT ? " " + ticks + "t" : "") + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof BuildStep)) {
            return false;
        }
        BuildStep s = (BuildStep) o;
        return action == s.action && x == s.x && y == s.y && z == s.z && ticks == s.ticks
                && block.equals(s.block) && nullName.equalsIgnoreCase(s.nullName);
    }

    @Override
    public int hashCode() {
        return ((action.hashCode() * 31 + x) * 31 + y) * 31 + z + block.hashCode();
    }
}
