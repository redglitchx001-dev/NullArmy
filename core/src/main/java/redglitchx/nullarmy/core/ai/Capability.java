package redglitchx.nullarmy.core.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * What the plugin can do with and without an AI model configured.
 *
 * <p><b>NullArmy is designed to be complete with zero endpoints.</b> Adding a
 * model is an upgrade, not a prerequisite. This enum is the single source of
 * truth for that promise: it is used by {@code /null features} to tell an owner
 * exactly what they lose by staying offline, instead of leaving them to
 * guess.</p>
 *
 * <p>Three levels:</p>
 * <ul>
 *   <li>{@link Availability#ALWAYS} - fully functional offline. Nothing about
 *       it touches a model.</li>
 *   <li>{@link Availability#LOCAL_FALLBACK} - works offline using deterministic
 *       local logic; a model only makes it smarter or more varied.</li>
 *   <li>{@link Availability#AI_ONLY} - genuinely needs a model. Offline it is
 *       simply unavailable, and the plugin says so instead of pretending.</li>
 * </ul>
 *
 * <p>Pure data: no Bukkit, no I/O, unit testable with a bare JRE.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public enum Capability {

    // ------------------------------------------------------------- always ---

    SUMMON_HORN(Availability.ALWAYS, "Summoning",
            "Blow a goat horn renamed to 'Call Horn', then type how many Nulls to summon.",
            "Works exactly the same offline."),

    SUMMON_TOTEM(Availability.ALWAYS, "Summoning",
            "Summon from a Totem Of Null.",
            "Works exactly the same offline."),

    PORTAL_VISUALS(Availability.ALWAYS, "Visuals",
            "At least 15 portal effects per summon, and Nulls walk out of them.",
            "Works exactly the same offline."),

    NPC_MOVEMENT(Availability.ALWAYS, "NPCs",
            "Nulls walk out of the portal, never share coordinates, never spawn inside blocks.",
            "Works exactly the same offline."),

    BOIDS_FLOCKING(Availability.ALWAYS, "NPCs",
            "Flock separation, cohesion and alignment via a spatial hash.",
            "Works exactly the same offline."),

    LOCAL_PATHFINDING(Availability.ALWAYS, "NPCs",
            "A* pathfinding across real block collision, with a per-tick budget.",
            "Works exactly the same offline."),

    COLLISION_SAFETY(Availability.ALWAYS, "Safety",
            "Server-side re-verification that a spawn position is collision-safe.",
            "Works exactly the same offline."),

    ITEM_LEDGER(Availability.ALWAYS, "Safety",
            "Hash-chained ledger for created, counted and burned items.",
            "Works exactly the same offline."),

    COMMANDER_SPAWN(Availability.ALWAYS, "Commander",
            "/null commander - the Commander steps out of a portal.",
            "Works exactly the same offline."),

    COMMANDER_LOADOUT(Availability.ALWAYS, "Commander",
            "/null loadout - the full 41-slot inventory editor, same slots as a real player.",
            "Works exactly the same offline."),

    SHARED_SKIN(Availability.ALWAYS, "Commander",
            "Every Null and the Commander wear a configurable Minecraft skin.",
            "Works offline from cache or from the bundled skin; a network fetch needs internet."),

    MACE_TECHNIQUES(Availability.ALWAYS, "Combat",
            "12 mace PvP techniques chosen by a deterministic selector.",
            "Works exactly the same offline - the selector is pure local logic."),

    ELYTRA_TECHNIQUES(Availability.ALWAYS, "Combat",
            "11 elytra PvP techniques chosen by a deterministic selector.",
            "Works exactly the same offline - the selector is pure local logic."),

    BLOCK_PLAN_VALIDATION(Availability.ALWAYS, "Safety",
            "Every proposed build is validated locally: palette, bounds, cost, support, protection.",
            "Works exactly the same offline. Validation never trusts the model."),

    GRIEFING_POLICY(Availability.ALWAYS, "Safety",
            "Griefing, explosives, wither and moderation flags, all off by default.",
            "Works exactly the same offline."),

    SAFE_SHUTDOWN(Availability.ALWAYS, "Safety",
            "On disable, Nulls enter a real SAFE_SHUTDOWN state instead of vanishing.",
            "Works exactly the same offline."),

    // ------------------------------------------------------ local fallback --

    COMBAT_DECISIONS(Availability.LOCAL_FALLBACK, "AI",
            "Choosing which mace or elytra technique to use in a fight.",
            "Uses the deterministic local selector. A model can suggest an intent, but the "
                    + "local validator still has the final say either way."),

    TARGET_PRIORITY(Availability.LOCAL_FALLBACK, "AI",
            "Ranking which threat to deal with first.",
            "Uses the local utility planner. A model may re-rank it; the validator decides."),

    ROUTE_CHOICE(Availability.LOCAL_FALLBACK, "AI",
            "Picking a route to a destination.",
            "Uses local A*. A model may express a preference; the server owns movement."),

    FORMATION(Availability.LOCAL_FALLBACK, "AI",
            "Squad formation, spacing and orientation.",
            "Uses deterministic formations. A model may propose one, clamped to local limits."),

    IDLE_BEHAVIOUR(Availability.LOCAL_FALLBACK, "AI",
            "What Nulls do when they have nothing to do, so they never freeze.",
            "Uses a local idle-action picker. A model only adds variety."),

    LOGISTICS(Availability.LOCAL_FALLBACK, "AI",
            "Loadout priorities and resupply requests.",
            "Uses local rules. A model may advise; the item ledger still governs every item."),

    TRIAGE(Availability.LOCAL_FALLBACK, "AI",
            "Which ally to help first.",
            "Uses local rules. A model may only reorder what it was told about."),

    SCOUTING(Availability.LOCAL_FALLBACK, "AI",
            "Summarising what the squad can see.",
            "Uses local observations. A model never receives hidden data either way."),

    // ------------------------------------------------------------- ai only --

    NATURAL_CHAT(Availability.AI_ONLY, "AI",
            "Free-form chat replies from Nulls.",
            "Unavailable offline. Nulls stay quiet rather than emitting canned filler."),

    DESCRIBED_BUILDS(Availability.AI_ONLY, "AI",
            "Turning a sentence like 'build a small bridge' into a block plan.",
            "Unavailable offline. Existing plans and manual builds still work."),

    REDSTONE_READING(Availability.AI_ONLY, "AI",
            "Interpreting a redstone circuit from line-of-sight evidence.",
            "Unavailable offline. Nulls simply do not attempt redstone analysis."),

    MINING_PLANS(Availability.AI_ONLY, "AI",
            "Choosing which visible blocks to mine and in what order.",
            "Unavailable offline. Nulls do not mine without a plan source."),

    AI_AUDIT(Availability.AI_ONLY, "AI",
            "A second model reviewing the first model's proposals.",
            "Unavailable offline - and unnecessary, since no model is running.");

    /** Whether a capability needs a model configured. */
    public enum Availability {
        /** Fully functional with no endpoints at all. */
        ALWAYS,
        /** Works offline via deterministic local logic; a model only improves it. */
        LOCAL_FALLBACK,
        /** Genuinely needs a model. Offline it is unavailable, and we say so. */
        AI_ONLY
    }

    private final Availability availability;
    private final String group;
    private final String description;
    private final String offlineBehaviour;

    Capability(Availability availability, String group, String description, String offlineBehaviour) {
        this.availability = availability;
        this.group = group;
        this.description = description;
        this.offlineBehaviour = offlineBehaviour;
    }

    public Availability availability() { return availability; }
    public String group() { return group; }
    public String description() { return description; }

    /** What actually happens when no AI endpoint is configured. */
    public String offlineBehaviour() { return offlineBehaviour; }

    /**
     * Whether this capability is usable right now.
     *
     * @param aiEnabled whether {@code ai.enabled} is true and at least one
     *                  endpoint resolves
     */
    public boolean isUsable(boolean aiEnabled) {
        return availability != Availability.AI_ONLY || aiEnabled;
    }

    /** Every capability in a given availability class. */
    public static List<Capability> with(Availability availability) {
        List<Capability> out = new ArrayList<>();
        for (Capability c : values()) {
            if (c.availability() == availability) {
                out.add(c);
            }
        }
        return out;
    }

    /** Capabilities that are unavailable with no AI configured. */
    public static List<Capability> lostWithoutAi() {
        return with(Availability.AI_ONLY);
    }

    public static int countAlways() {
        return with(Availability.ALWAYS).size();
    }

    public static int countLocalFallback() {
        return with(Availability.LOCAL_FALLBACK).size();
    }

    public static int countAiOnly() {
        return with(Availability.AI_ONLY).size();
    }

    public static int count() {
        return values().length;
    }
}
