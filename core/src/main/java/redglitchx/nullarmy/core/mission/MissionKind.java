package redglitchx.nullarmy.core.mission;

import java.util.Locale;

/**
 * The kinds of NullArmy mission the Commander can run.
 *
 * <p>These are original NullArmy content: broad adventure themes (scouting a
 * hidden outpost, a corridor rescue, holding a banner, a squad tournament, a
 * gate vigil, a negotiated accord, a supply run) written for this plugin. No
 * character, item, line of dialogue or plot is taken from any other work.</p>
 *
 * <p>Every kind is safe by default: none of them destroy blocks, spawn
 * explosives or attack players. A mission coordinates movement, roles and
 * reporting - the same validated objectives {@code /null follow|guard|formation}
 * already use.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public enum MissionKind {

    /** Secret-base scouting: walk the perimeter, mark what is there, report. */
    SCOUT_OUTPOST("scout-outpost", "Operation Quiet Compass",
            "Walk the ground around the marked outpost and report what stands there.",
            "perimeter sectors walked", 6, 6000, "scout"),

    /** Prison rescue: reach a held ally and escort them out. Nobody is hurt. */
    CORRIDOR_RESCUE("corridor-rescue", "The Long Corridor",
            "Reach the far end of the corridor, collect the held ally, and escort them back.",
            "corridor stages cleared", 5, 6000, "escort"),

    /** Territorial objective: carry a banner to a point and hold it. */
    BANNER_HOLD("banner-hold", "Banner of the Hollow Field",
            "Carry the Null banner to the hollow field and hold it for the count.",
            "hold intervals completed", 8, 7200, "guard"),

    /** Tournament: squads run a timed formation course. Points, no PvP. */
    NULL_TRIALS("null-trials", "The Null Trials",
            "Run the formation course in order and finish inside the time.",
            "course gates passed", 7, 4800, "ranged"),

    /** Siege defence: hold a gate line while waves arrive. Waves are markers. */
    GATE_VIGIL("gate-vigil", "Vigil at the Broken Gate",
            "Hold the gate line through every watch of the night.",
            "watches held", 6, 9000, "guard"),

    /** Shifting alliances: meet two factions and carry an accord between them. */
    ASH_ACCORD("ash-accord", "The Accord of Ash",
            "Travel between the two campfires and carry the accord wording to each.",
            "parleys carried", 4, 6000, "medic"),

    /** Server-wide story event: a supply run announced to everyone online. */
    SUPPLY_RUN("supply-run", "The Quiet Supply Run",
            "Move the supply train along the road and report every stop.",
            "stops reported", 5, 5400, "escort");

    private final String key;
    private final String title;
    private final String briefing;
    private final String unit;
    private final int goal;
    private final int durationTicks;
    private final String leadRole;

    MissionKind(String key, String title, String briefing, String unit,
                int goal, int durationTicks, String leadRole) {
        this.key = key;
        this.title = title;
        this.briefing = briefing;
        this.unit = unit;
        this.goal = goal;
        this.durationTicks = durationTicks;
        this.leadRole = leadRole;
    }

    /** Config and command spelling. */
    public String key() { return key; }

    /** The mission's own name, shown in chat and in the report. */
    public String title() { return title; }

    /** What the squad is being asked to do, in one sentence. */
    public String briefing() { return briefing; }

    /** What one unit of progress is called. */
    public String unit() { return unit; }

    /** How many units finish the mission. */
    public int goal() { return goal; }

    /** How long the mission may run before it is called off. */
    public int durationTicks() { return durationTicks; }

    /** The role that leads this mission, as a {@code SquadRole} key. */
    public String leadRole() { return leadRole; }

    /** Parses a key or a name; null when it is not one. */
    public static MissionKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        for (MissionKind kind : values()) {
            if (kind.key.equals(value) || kind.name().toLowerCase(Locale.ROOT).equals(value)
                    || kind.title.toLowerCase(Locale.ROOT).equals(value)) {
                return kind;
            }
        }
        return null;
    }
}
