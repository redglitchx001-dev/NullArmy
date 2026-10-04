package redglitchx.nullarmy.core.squad;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a squad size into a stored role per member.
 *
 * <p>Deterministic and index-stable: the same size always produces the same
 * layout, so a report the Commander gives matches what the squad is doing, and a
 * re-run after a reload does not shuffle everybody.</p>
 *
 * <p>Small squads get the roles that matter first (someone watches, someone
 * moves); bigger squads add ranged support, escort and a medic. The Commander
 * slot is always index 0 when the squad has one, which is the same rule
 * {@code SquadManager} uses when it designates commanders.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class RoleAssignment {

    /** Fill order once the guaranteed roles are placed. */
    private static final SquadRole[] FILL_ORDER = {
            SquadRole.GUARD, SquadRole.RANGED, SquadRole.SCOUT,
            SquadRole.ESCORT, SquadRole.MEDIC
    };

    private RoleAssignment() {
    }

    /**
     * Assigns one role per member.
     *
     * @param size         squad size
     * @param hasCommander whether index 0 is a designated Commander
     * @return one role per index, never null and never empty for size &gt; 0
     */
    public static List<SquadRole> assign(int size, boolean hasCommander) {
        List<SquadRole> out = new ArrayList<>();
        if (size <= 0) {
            return Collections.unmodifiableList(out);
        }
        int index = 0;
        if (hasCommander) {
            out.add(SquadRole.COMMANDER);
            index = 1;
        }
        // Guaranteed coverage first, then fill. A squad of two still has someone
        // watching and someone moving.
        for (SquadRole role : guaranteed(size)) {
            if (index >= size) {
                break;
            }
            out.add(role);
            index++;
        }
        int filler = 0;
        while (index < size) {
            out.add(FILL_ORDER[filler % FILL_ORDER.length]);
            filler++;
            index++;
        }
        return Collections.unmodifiableList(out);
    }

    /** The roles a squad of this size must contain, in priority order. */
    private static List<SquadRole> guaranteed(int size) {
        List<SquadRole> roles = new ArrayList<>();
        roles.add(SquadRole.GUARD);
        if (size >= 2) {
            roles.add(SquadRole.SCOUT);
        }
        if (size >= 3) {
            roles.add(SquadRole.RANGED);
        }
        if (size >= 5) {
            roles.add(SquadRole.ESCORT);
        }
        if (size >= 6) {
            roles.add(SquadRole.MEDIC);
        }
        return roles;
    }

    /** Role -&gt; how many members hold it. */
    public static Map<SquadRole, Integer> counts(List<SquadRole> roles) {
        Map<SquadRole, Integer> out = new EnumMap<>(SquadRole.class);
        if (roles != null) {
            for (SquadRole role : roles) {
                if (role != null) {
                    out.merge(role, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** "2 guard, 1 scout, 1 ranged" - the shape of one line of a squad report. */
    public static String describe(List<SquadRole> roles) {
        Map<SquadRole, Integer> counts = counts(roles);
        if (counts.isEmpty()) {
            return "no roles assigned";
        }
        StringBuilder sb = new StringBuilder();
        for (SquadRole role : SquadRole.values()) {
            Integer count = counts.get(role);
            if (count == null || count == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(count).append(' ').append(role.key());
        }
        return sb.toString();
    }

    /** The standoff a role keeps, in blocks. Ranged stays back; medics close in. */
    public static double standoff(SquadRole role) {
        if (role == null) {
            return 2.0;
        }
        switch (role) {
            case RANGED:
                return 6.0;
            case SCOUT:
                return 4.0;
            case MEDIC:
            case ESCORT:
                return 1.5;
            case GUARD:
            case COMMANDER:
            default:
                return 2.0;
        }
    }
}
