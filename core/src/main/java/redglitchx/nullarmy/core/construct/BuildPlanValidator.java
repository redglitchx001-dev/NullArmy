package redglitchx.nullarmy.core.construct;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Decides whether a step list may be executed at all.
 *
 * <p>Every rule here protects the world or the owner, never style: steps stay
 * inside the summon zone (and inside a sane height band), PLACE only uses real
 * placeable blocks, a step may only name a Null that exists, a block is never
 * placed twice at the same spot, and the plan is not longer than
 * {@code ai.builder.max-steps}. A plan that fails is sent back to the model once
 * with these reasons; a second failure means the offline planner takes over.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BuildPlanValidator {

    private BuildPlanValidator() {
    }

    /**
     * @param steps       the plan
     * @param halfSize    half the zone edge: |x| and |z| must not exceed it
     * @param minY        lowest relative y allowed
     * @param maxY        highest relative y allowed
     * @param maxSteps    longest plan allowed
     * @param nullNames   names a step may address (case-insensitive); empty set = any name refused
     * @param isPlaceable true for a material name that is a placeable block
     * @return every problem found; empty when the plan may run
     */
    public static List<String> validate(List<BuildStep> steps, int halfSize, int minY, int maxY,
                                        int maxSteps, Set<String> nullNames,
                                        Predicate<String> isPlaceable) {
        List<String> problems = new ArrayList<>();
        if (steps == null || steps.isEmpty()) {
            problems.add("the plan has no steps");
            return problems;
        }
        if (maxSteps > 0 && steps.size() > maxSteps) {
            problems.add("the plan has " + steps.size() + " steps; the limit is " + maxSteps);
        }
        Set<String> names = new HashSet<>();
        if (nullNames != null) {
            for (String name : nullNames) {
                if (name != null) {
                    names.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
        Set<String> placed = new HashSet<>();
        for (int i = 0; i < steps.size() && problems.size() < 12; i++) {
            BuildStep step = steps.get(i);
            String where = "step " + (i + 1) + " (" + step + ")";
            if (step == null) {
                problems.add("step " + (i + 1) + " is empty");
                continue;
            }
            if (!step.nullName().isEmpty() && !step.nullName().equalsIgnoreCase("any")
                    && !names.contains(step.nullName().toLowerCase(Locale.ROOT))) {
                problems.add(where + " names \"" + step.nullName() + "\", which is not one of your Nulls");
            }
            if (step.action() == BuildStep.Action.WAIT) {
                continue;
            }
            if (Math.abs(step.x()) > halfSize || Math.abs(step.z()) > halfSize) {
                problems.add(where + " is outside the zone (|x| and |z| must be <= " + halfSize + ")");
            }
            if (step.y() < minY || step.y() > maxY) {
                problems.add(where + " is outside the height band " + minY + ".." + maxY);
            }
            if (step.action() == BuildStep.Action.PLACE) {
                if (isPlaceable != null && !isPlaceable.test(step.block())) {
                    problems.add(where + " uses \"" + step.block() + "\", which is not a placeable block");
                }
                String key = step.x() + "," + step.y() + "," + step.z();
                if (!placed.add(key)) {
                    problems.add(where + " places a second block at " + key);
                }
            }
        }
        return problems;
    }
}
