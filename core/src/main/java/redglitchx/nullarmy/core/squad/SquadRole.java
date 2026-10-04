package redglitchx.nullarmy.core.squad;

import java.util.Locale;

/**
 * What one Null is responsible for inside a squad.
 *
 * <p>Roles are stored, not re-rolled: a Null keeps its role until the squad is
 * reorganised by an order, by the AI coordinator, or by a loss that forces
 * succession. That is what makes "the squad works as a team" mean something
 * instead of a random label per tick.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public enum SquadRole {

    /** Leads the squad; the one the AI coordinator speaks through. */
    COMMANDER("commander", "holds the squad together and reports"),

    /** Moves ahead and reports what is there. */
    SCOUT("scout", "walks the perimeter and reports contact"),

    /** Holds a position and watches the approach. */
    GUARD("guard", "holds the position and watches"),

    /** Stays beside the owner or a protected Null. */
    ESCORT("escort", "stays with the person it is escorting"),

    /** Keeps distance and supports from range. */
    RANGED("ranged", "supports from range"),

    /** Falls back to the hurt and tops them up. */
    MEDIC("medic", "moves to whoever is hurt");

    private final String key;
    private final String duty;

    SquadRole(String key, String duty) {
        this.key = key;
        this.duty = duty;
    }

    /** Config/command spelling, always lower case. */
    public String key() { return key; }

    /** One line saying what this role actually does. */
    public String duty() { return duty; }

    /** Parses a role name; null when it is not one. */
    public static SquadRole parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        for (SquadRole role : values()) {
            if (role.key.equals(value) || role.name().toLowerCase(Locale.ROOT).equals(value)) {
                return role;
            }
        }
        return null;
    }
}
