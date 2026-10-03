package redglitchx.nullarmy.core.config;

/**
 * Tunable resource caps.
 *
 * <p>Spec 9 requires configurable caps for live NPCs, squads, path searches,
 * block inspections, packet sends, particle effects, endpoint calls, active
 * builders, schematic dimensions and AI JSON size. The spec lists *what* to
 * cap but never gives values, so these are the documented starting defaults
 * from IMPLEMENTATION_PLAN.md A-09 / 7.1.</p>
 *
 * <p><b>These are starting points, not measurements.</b> Phase 9 replaces them
 * with values derived from profiling.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Caps {

    private int maxLiveNpcs = 64;
    private int maxNpcsPerSquad = 24;
    private int maxSquadsPerOwner = 2;
    private int summonHardCap = 24;

    private int concurrentPathSearches = 4;
    private int pathSlicesPerTick = 8;
    private int pathMaxExpansions = 4096;
    private int blockInspectionsPerTick = 256;

    private int packetsPerNpcPerTick = 32;
    private int portalEffectsPerSummon = 16;

    private int concurrentBuilders = 4;
    private int schematicMaxSide = 32;

    private int endpointCallsPerMinute = 20;
    private long endpointTimeoutMillis = 3000L;
    private int maxAiJsonBytes = 8192;

    /** Spec 3: at least 15 visual portal effects per summon event. */
    private static final int MIN_PORTAL_EFFECTS = 15;

    public int maxLiveNpcs() { return maxLiveNpcs; }
    public int maxNpcsPerSquad() { return maxNpcsPerSquad; }
    public int maxSquadsPerOwner() { return maxSquadsPerOwner; }
    public int summonHardCap() { return summonHardCap; }
    public int concurrentPathSearches() { return concurrentPathSearches; }
    public int pathSlicesPerTick() { return pathSlicesPerTick; }
    public int pathMaxExpansions() { return pathMaxExpansions; }
    public int blockInspectionsPerTick() { return blockInspectionsPerTick; }
    public int packetsPerNpcPerTick() { return packetsPerNpcPerTick; }
    public int portalEffectsPerSummon() { return portalEffectsPerSummon; }
    public int concurrentBuilders() { return concurrentBuilders; }
    public int schematicMaxSide() { return schematicMaxSide; }
    public int endpointCallsPerMinute() { return endpointCallsPerMinute; }
    public long endpointTimeoutMillis() { return endpointTimeoutMillis; }
    public int maxAiJsonBytes() { return maxAiJsonBytes; }

    public Caps withMaxLiveNpcs(int v) { maxLiveNpcs = positive(v, "maxLiveNpcs"); return this; }
    public Caps withSummonHardCap(int v) { summonHardCap = positive(v, "summonHardCap"); return this; }
    public Caps withPortalEffectsPerSummon(int v) {
        if (v < MIN_PORTAL_EFFECTS) {
            throw new IllegalArgumentException(
                    "portalEffectsPerSummon must be >= " + MIN_PORTAL_EFFECTS + " (spec 3), got " + v);
        }
        portalEffectsPerSummon = v;
        return this;
    }
    public Caps withPathMaxExpansions(int v) { pathMaxExpansions = positive(v, "pathMaxExpansions"); return this; }
    public Caps withMaxAiJsonBytes(int v) { maxAiJsonBytes = positive(v, "maxAiJsonBytes"); return this; }
    public Caps withEndpointTimeoutMillis(long v) {
        if (v <= 0) {
            throw new IllegalArgumentException("endpointTimeoutMillis must be > 0");
        }
        endpointTimeoutMillis = v;
        return this;
    }

    /** The spec floor for summon visuals. */
    public static int minPortalEffects() { return MIN_PORTAL_EFFECTS; }

    private static int positive(int v, String name) {
        if (v <= 0) {
            throw new IllegalArgumentException(name + " must be > 0, got " + v);
        }
        return v;
    }
}
