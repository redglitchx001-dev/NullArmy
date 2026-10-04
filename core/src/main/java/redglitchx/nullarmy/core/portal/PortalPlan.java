package redglitchx.nullarmy.core.portal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * How many portals one summon opens, and how many Nulls walk out of each.
 *
 * <p>Requirement: for every summon the plugin picks a random number of portals
 * from 1 up to a configured hard maximum, and randomly distributes the Nulls
 * among them, so both the portal count and the count per portal vary between
 * summons. Two rules are not negotiable:</p>
 * <ul>
 *   <li>a portal that is built always has at least one Null assigned to it -
 *       an empty doorway is scenery, not an arrival;</li>
 *   <li>no requested Null is ever dropped to make the arithmetic work. Whatever
 *       does not fit into the portals is reported as {@link #spill()} and the
 *       caller spawns it through the ordinary safe-ground path instead.</li>
 * </ul>
 *
 * <p>Pure logic with an injected {@link Random}, so the limits and the
 * distribution can be tested without a server.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PortalPlan {

    /** Absolute ceiling on portals for one summon, whatever the config says. */
    public static final int HARD_PORTAL_CEILING = 16;

    private final int nulls;
    private final int maxPortals;
    private final int maxPerPortal;
    private final List<Integer> distribution;
    private final int spill;

    private PortalPlan(int nulls, int maxPortals, int maxPerPortal,
                       List<Integer> distribution, int spill) {
        this.nulls = nulls;
        this.maxPortals = maxPortals;
        this.maxPerPortal = maxPerPortal;
        this.distribution = Collections.unmodifiableList(new ArrayList<>(distribution));
        this.spill = spill;
    }

    /**
     * Plans a random arrival.
     *
     * @param nulls        how many Nulls this summon granted (already capped)
     * @param maxPortals   configured hard maximum of portals per summon
     * @param maxPerPortal how many Nulls may share one doorway before the
     *                     position search cannot keep them out of each other
     * @param random       source of the variation; injected for tests
     */
    public static PortalPlan of(int nulls, int maxPortals, int maxPerPortal, Random random) {
        int portals = clampPortals(maxPortals);
        int perPortal = Math.max(1, maxPerPortal);
        if (nulls <= 0) {
            return new PortalPlan(0, portals, perPortal, Collections.emptyList(), 0);
        }
        Random source = random == null ? new Random() : random;

        // A portal without a Null is pointless, so the count never exceeds the
        // squad; and every Null needs a doorway that can hold it, so the count
        // never drops below ceil(nulls / perPortal).
        int needed = (nulls + perPortal - 1) / perPortal;
        int allowed = Math.min(portals, nulls);
        if (needed > allowed) {
            // The configured maximum is too small for the squad: use every portal
            // allowed and report the rest as spill rather than losing them.
            List<Integer> flat = new ArrayList<>();
            for (int i = 0; i < allowed; i++) {
                flat.add(perPortal);
            }
            return new PortalPlan(nulls, portals, perPortal, flat, nulls - allowed * perPortal);
        }

        int count = needed == allowed ? allowed : needed + source.nextInt(allowed - needed + 1);
        List<Integer> spread = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            spread.add(1);
        }
        int remaining = nulls - count;
        int spill = 0;
        while (remaining > 0) {
            List<Integer> room = new ArrayList<>();
            for (int i = 0; i < spread.size(); i++) {
                if (spread.get(i) < perPortal) {
                    room.add(i);
                }
            }
            if (room.isEmpty()) {
                spill = remaining;
                break;
            }
            int pick = room.get(source.nextInt(room.size()));
            spread.set(pick, spread.get(pick) + 1);
            remaining--;
        }
        return new PortalPlan(nulls, portals, perPortal, spread, spill);
    }

    /** An empty plan: no portals, every Null goes through the fallback path. */
    public static PortalPlan none(int nulls) {
        return new PortalPlan(Math.max(0, nulls), 0, 1, Collections.emptyList(), Math.max(0, nulls));
    }

    private static int clampPortals(int configured) {
        return Math.max(1, Math.min(HARD_PORTAL_CEILING, configured));
    }

    /** How many doorways this summon opens. */
    public int portalCount() { return distribution.size(); }

    /** How many Nulls emerge from one doorway. */
    public int nullsAt(int portal) {
        return portal < 0 || portal >= distribution.size() ? 0 : distribution.get(portal);
    }

    /** The per-portal counts, in build order. Never null. */
    public List<Integer> distribution() { return distribution; }

    /** Total Nulls assigned to portals. */
    public int assigned() {
        int sum = 0;
        for (int value : distribution) {
            sum += value;
        }
        return sum;
    }

    /**
     * Nulls that could not be given a doorway.
     *
     * <p>Never silently dropped: the caller must spawn them through the ordinary
     * collision-safe ground path and say so.</p>
     */
    public int spill() { return spill; }

    /** How many Nulls this plan was made for. */
    public int nulls() { return nulls; }

    public int maxPortals() { return maxPortals; }

    public int maxPerPortal() { return maxPerPortal; }

    /**
     * Removes one doorway - the one whose site turned out to be unbuildable -
     * and redistributes its Nulls over the rest.
     *
     * <p>Redistribution is deterministic (round-robin) because it happens after a
     * failure the owner is being told about; a second random roll would make the
     * report and the world disagree.</p>
     *
     * @return a new plan, never null
     */
    public PortalPlan withoutPortal(int index) {
        if (index < 0 || index >= distribution.size()) {
            return this;
        }
        List<Integer> rest = new ArrayList<>(distribution);
        int freed = rest.remove(index);
        if (rest.isEmpty()) {
            return new PortalPlan(nulls, maxPortals, maxPerPortal, rest, freed + spill);
        }
        int cursor = 0;
        int remaining = freed;
        while (remaining > 0) {
            boolean moved = false;
            for (int i = 0; i < rest.size() && remaining > 0; i++) {
                int slot = (cursor + i) % rest.size();
                if (rest.get(slot) < maxPerPortal) {
                    rest.set(slot, rest.get(slot) + 1);
                    remaining--;
                    moved = true;
                }
            }
            cursor = (cursor + 1) % rest.size();
            if (!moved) {
                break;
            }
        }
        return new PortalPlan(nulls, maxPortals, maxPerPortal, rest, spill + remaining);
    }

    /** One line for chat and the log: "3 portals (2/3/1 Nulls), 0 unplaced". */
    public String describe() {
        if (distribution.isEmpty()) {
            return "no portals (" + nulls + " Null(s) by safe ground)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(distribution.size()).append(distribution.size() == 1 ? " portal (" : " portals (");
        for (int i = 0; i < distribution.size(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(distribution.get(i));
        }
        sb.append(" Null").append(distribution.size() == 1 ? ")" : "s)");
        if (spill > 0) {
            sb.append(", ").append(spill).append(" without a doorway");
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "PortalPlan{nulls=" + nulls + ", portals=" + portalCount()
                + ", distribution=" + distribution + ", spill=" + spill + "}";
    }
}
