package redglitchx.nullarmy.core.combat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Commander's PvP technique library: <b>mace</b> and <b>elytra</b>, with a
 * deterministic selector that picks the best applicable technique for a
 * {@link CombatSituation}.
 *
 * <p>Everything here is pure: no Bukkit, no I/O, no randomness. Given the same
 * situation it always returns the same technique, which makes it unit testable
 * with nothing but a JRE and makes the Commander's behaviour predictable and
 * debuggable.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PvpArsenal {

    private PvpArsenal() {
    }

    /** Which family of play a technique belongs to. */
    public enum Discipline {
        MACE,
        ELYTRA,
        MOVEMENT
    }

    /**
     * Every technique the Commander knows.
     *
     * <p>{@code priority} is only used to break ties between techniques that
     * both apply; the higher number wins.</p>
     */
    public enum Technique {

        // ------------------------------------------------------------ mace ----

        FULL_SMASH(Discipline.MACE, 90,
                "Fall from full height and land the mace smash directly on the target.",
                s -> s.hasMace() && s.smashIsLethal() && s.heightAboveTarget() >= 6.0),

        COMBO_DOUBLE_SMASH(Discipline.MACE, 88,
                "Smash, let Wind Burst bounce you back up, then smash a second time.",
                s -> s.hasMace() && s.maceHasWindBurst() && s.smashIsLethal() && s.targetHealth() > 10.0),

        WIND_BURST_RECOVERY(Discipline.MACE, 86,
                "Use Wind Burst after the smash to bounce back up instead of eating the fall.",
                s -> s.hasMace() && s.maceHasWindBurst() && s.fallSpeed() > 8.0 && !s.smashIsLethal()),

        DENSITY_BURST(Discipline.MACE, 84,
                "Density adds damage per block fallen - take the longest fall available.",
                s -> s.hasMace() && s.maceHasDensity() && s.heightAboveTarget() >= 3.0),

        BREACH_SHIELD_BREAK(Discipline.MACE, 82,
                "Breach cuts through armour, so hit the shielded target with the mace instead of a sword.",
                s -> s.hasMace() && s.maceHasBreach() && s.targetBlocking()),

        HOTBAR_SWAP_SMASH(Discipline.MACE, 80,
                "Fall holding the sword, swap to the mace at the last moment so the target cannot pre-shield.",
                s -> s.hasMace() && s.fallSpeed() > 6.0 && s.heightAboveTarget() >= 2.0),

        PEARL_SMASH(Discipline.MACE, 76,
                "Throw an ender pearl straight up, then smash down onto the target.",
                s -> s.hasMace() && s.hasEnderPearl() && s.heightAboveTarget() < 6.0
                        && s.distanceToTarget() < 8.0),

        WIND_CHARGE_LAUNCH(Discipline.MACE, 74,
                "Fire a wind charge downward to gain height, then convert it into a smash.",
                s -> s.hasMace() && s.hasWindCharge() && s.heightAboveTarget() < 6.0),

        ELYTRA_DIVE_SMASH(Discipline.MACE, 78,
                "Dive with the elytra to build speed, then switch to the mace for the hit.",
                s -> s.hasMace() && s.hasElytra() && s.elytraDeployed() && s.distanceToTarget() < 12.0),

        AERIAL_JUKE(Discipline.MACE, 60,
                "Fire a wind charge sideways mid-fall to dodge an arrow or a counter-smash.",
                s -> s.hasWindCharge() && s.fallSpeed() > 4.0 && !s.smashIsLethal()),

        MLG_WATER_RESET(Discipline.MACE, 58,
                "The smash missed - place water to cancel the fall damage and reset.",
                s -> s.hasWaterBucket() && s.fallSpeed() > 10.0 && !s.smashIsLethal()),

        NO_COMMIT(Discipline.MACE, 10,
                "Refuse to jump: the fall is not lethal, so committing just gives away height.",
                s -> s.hasMace() && !s.smashIsLethal()),

        // ---------------------------------------------------------- elytra ----

        ROCKET_CHAIN(Discipline.ELYTRA, 70,
                "Chain fireworks to hold speed during a long chase.",
                s -> s.elytraDeployed() && s.fireworks() > 2 && s.distanceToTarget() > 20.0),

        FIREWORK_CONSERVE(Discipline.ELYTRA, 68,
                "Stop boosting and glide - fireworks are nearly gone, so spend them only to escape.",
                s -> s.elytraDeployed() && s.fireworks() <= 2),

        STRAFE_RUN(Discipline.ELYTRA, 66,
                "Strafe left/right while gliding so incoming shots cannot lead you.",
                s -> s.elytraDeployed() && s.distanceToTarget() > 6.0),

        DRIVE_BY_BOW(Discipline.ELYTRA, 64,
                "Hold the bow drawn through the pass and release at the closest point.",
                s -> s.elytraDeployed() && s.hasBow() && s.distanceToTarget() > 8.0
                        && s.distanceToTarget() < 40.0),

        CROSSBOW_PUNISH(Discipline.ELYTRA, 62,
                "Close in fast and fire a crossbow burst at point-blank range.",
                s -> s.elytraDeployed() && s.hasCrossbow() && s.distanceToTarget() < 10.0),

        SWOOP_SWORD(Discipline.ELYTRA, 60,
                "Dive through the target with a sword for a critical hit.",
                s -> s.elytraDeployed() && !s.hasMace() && s.distanceToTarget() < 6.0),

        WIND_CHARGE_BOOST(Discipline.ELYTRA, 58,
                "Use a wind charge instead of a firework - free boost that costs no fireworks.",
                s -> s.elytraDeployed() && s.hasWindCharge() && s.fireworks() <= 2),

        RIPTIDE_LAUNCH(Discipline.ELYTRA, 56,
                "In rain or water, riptide the trident to regain height without fireworks.",
                s -> s.hasTrident() && (s.inRain() || s.inWater()) && s.heightAboveTarget() < 4.0),

        PEARL_CHAIN(Discipline.ELYTRA, 54,
                "Throw an ender pearl to teleport behind the target and reset the engagement.",
                s -> s.hasEnderPearl() && s.distanceToTarget() > 12.0),

        LANDING_CANCEL(Discipline.ELYTRA, 52,
                "Retract the elytra to drop fast onto the target instead of overshooting.",
                s -> s.elytraDeployed() && s.distanceToTarget() < 4.0),

        RETREAT_CLIMB(Discipline.ELYTRA, 50,
                "Low health - climb away vertically and live to re-engage.",
                s -> s.elytraDeployed() && s.ownHealth() <= 8.0),

        // -------------------------------------------------------- movement ----

        SHIELD_TURTLE(Discipline.MOVEMENT, 40,
                "Raise the shield and close the distance rather than trading at range.",
                s -> s.hasShield() && s.targetBlocking() && s.distanceToTarget() < 6.0),

        DISENGAGE(Discipline.MOVEMENT, 5,
                "Nothing applies - back off, reset spacing, and wait for a better opening.",
                s -> true);

        private final Discipline discipline;
        private final int priority;
        private final String description;
        private final Predicate<CombatSituation> applies;

        Technique(Discipline discipline, int priority, String description,
                  Predicate<CombatSituation> applies) {
            this.discipline = discipline;
            this.priority = priority;
            this.description = description;
            this.applies = applies;
        }

        public Discipline discipline() { return discipline; }
        public int priority() { return priority; }
        public String description() { return description; }

        /** True when this technique is a sensible answer to the given situation. */
        public boolean applies(CombatSituation situation) {
            return applies.test(situation);
        }
    }

    /**
     * Picks the highest-priority technique that applies to the situation.
     *
     * <p>{@link Technique#DISENGAGE} applies to everything, so this never
     * returns null - there is always a safe answer.</p>
     */
    public static Technique select(CombatSituation situation) {
        Technique best = Technique.DISENGAGE;
        for (Technique t : Technique.values()) {
            if (t == Technique.DISENGAGE) {
                continue;
            }
            if (t.applies(situation) && t.priority() > best.priority()) {
                best = t;
            }
        }
        return best;
    }

    /** Every technique of a given discipline, in priority order. */
    public static List<Technique> forDiscipline(Discipline discipline) {
        List<Technique> out = new ArrayList<>();
        for (Technique t : Technique.values()) {
            if (t.discipline() == discipline) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return out;
    }

    /** Techniques that apply right now, best first. Useful for debugging. */
    public static List<Technique> applicable(CombatSituation situation) {
        List<Technique> out = new ArrayList<>();
        for (Technique t : Technique.values()) {
            if (t.applies(situation)) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
        return out;
    }

    /** Every technique the Commander knows, grouped by discipline. */
    public static Set<Technique> all() {
        return EnumSet.copyOf(Arrays.asList(Technique.values()));
    }

    public static int count() {
        return Technique.values().length;
    }
}
