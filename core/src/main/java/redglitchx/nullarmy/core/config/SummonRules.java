package redglitchx.nullarmy.core.config;

/**
 * The arithmetic behind "how many Nulls do I actually get?".
 *
 * <p>Spec 3 is explicit: apply a configurable hard cap and a live-NPC budget,
 * and <b>reject excessive counts clearly instead of partially spawning a
 * surprise army</b>. That is a decision, not a side effect, so it lives here as
 * pure logic that can be tested without a server (ADR-004) and is then reused
 * verbatim by the chat summon flow and by the commands that spawn Nulls.</p>
 *
 * <p>Nothing in this class touches Bukkit, NMS or the file system.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SummonRules {

    private SummonRules() {
    }

    /** What a request resolves to, and why. */
    public static final class Decision {

        private final int requested;
        private final int granted;
        private final boolean clamped;
        private final String explanation;

        Decision(int requested, int granted, boolean clamped, String explanation) {
            this.requested = requested;
            this.granted = granted;
            this.clamped = clamped;
            this.explanation = explanation == null ? "" : explanation;
        }

        /** What the player asked for. */
        public int requested() { return requested; }

        /** How many may actually spawn. Never negative. */
        public int granted() { return granted; }

        /** True when {@link #granted()} is lower than what was asked for. */
        public boolean clamped() { return clamped; }

        /** True when nothing may spawn. */
        public boolean refused() { return granted <= 0; }

        /** A player-facing sentence explaining the outcome. Never null. */
        public String explanation() { return explanation; }

        @Override
        public String toString() {
            return "Decision{requested=" + requested + ", granted=" + granted
                    + ", clamped=" + clamped + ", explanation='" + explanation + "'}";
        }
    }

    /** Free Null slots right now. Never negative, never above {@code maxLive}. */
    public static int remainingCapacity(int liveCount, int maxLiveNpcs) {
        int max = Math.max(0, maxLiveNpcs);
        int live = Math.max(0, liveCount);
        return Math.max(0, max - live);
    }

    /**
     * Resolves a requested summon count against both caps.
     *
     * @param requested    what the player typed
     * @param hardCap      {@code limits.summon-hard-cap} - one summon never exceeds it
     * @param liveCount    Nulls already alive on the server
     * @param maxLiveNpcs  {@code limits.max-live-npcs} - the server-wide ceiling
     */
    public static Decision decide(int requested, int hardCap, int liveCount, int maxLiveNpcs) {
        int cap = Math.max(0, hardCap);
        int free = remainingCapacity(liveCount, maxLiveNpcs);

        if (requested <= 0) {
            return new Decision(requested, 0, true,
                    "The count must be at least 1.");
        }
        if (cap <= 0) {
            return new Decision(requested, 0, true,
                    "Summoning is disabled: limits.summon-hard-cap is 0.");
        }
        if (free <= 0) {
            return new Decision(requested, 0, true,
                    "The server Null limit is reached (" + Math.max(0, liveCount)
                            + "/" + Math.max(0, maxLiveNpcs) + " live). Dismiss some first.");
        }

        int granted = Math.min(requested, Math.min(cap, free));
        if (granted >= requested) {
            return new Decision(requested, granted, false,
                    granted == 1 ? "1 Null will come." : granted + " Nulls will come.");
        }

        String why;
        if (cap < free) {
            why = "capped at the summon limit of " + cap;
        } else {
            why = "capped at " + free + " - only " + free + " of "
                    + Math.max(0, maxLiveNpcs) + " Null slots are free";
        }
        return new Decision(requested, granted, true,
                "You asked for " + requested + ", " + why
                        + ". " + (granted == 1 ? "1 Null will come." : granted + " Nulls will come."));
    }

    /**
     * Narrows a count to the number of collision-safe positions that actually
     * exist. Spawning the rest is impossible without teleporting or clipping
     * into terrain, both of which the spec forbids - so the honest answer is to
     * say how many made it.
     */
    public static Decision decideSafeSpots(int wanted, int safeSpotsAvailable) {
        int wantedPositive = Math.max(0, wanted);
        int spots = Math.max(0, safeSpotsAvailable);
        if (spots >= wantedPositive) {
            return new Decision(wantedPositive, wantedPositive, false,
                    wantedPositive == 1 ? "1 safe spot." : wantedPositive + " safe spots.");
        }
        if (spots == 0) {
            return new Decision(wantedPositive, 0, true,
                    "No collision-safe ground nearby. Stand somewhere with clear floor around you.");
        }
        return new Decision(wantedPositive, spots, true,
                "Only " + spots + " collision-safe spot" + (spots == 1 ? "" : "s")
                        + " for " + wantedPositive + " Nulls. "
                        + (spots == 1 ? "1 Null spawned" : spots + " Nulls spawned")
                        + " - the rest were not spawned rather than clipping into terrain.");
    }
}
