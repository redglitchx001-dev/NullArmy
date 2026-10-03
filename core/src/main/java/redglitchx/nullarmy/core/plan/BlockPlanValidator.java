package redglitchx.nullarmy.core.plan;

import redglitchx.nullarmy.core.ledger.ItemId;
import redglitchx.nullarmy.core.ledger.ItemLedger;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local validation for a {@link BlockPlan}.
 *
 * <p>This is the safety gate BuilderAgent's JSON must pass before a single
 * Null moves. Spec 7: "The local builder checks inventory, support, placement,
 * protections, cost, and path before execution."</p>
 *
 * <p>Failure is explicit and specific. A rejected plan returns a reason string
 * naming the problem - spec 9 wants debug output that "explains why an action
 * was rejected".</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BlockPlanValidator {

    /** Outcome: either approved, or rejected with a precise reason. */
    public static final class Result {
        private final boolean approved;
        private final String reason;

        private Result(boolean approved, String reason) {
            this.approved = approved;
            this.reason = reason;
        }

        public boolean approved() { return approved; }
        public String reason() { return reason; }

        @Override
        public String toString() {
            return approved ? "APPROVED" : ("REJECTED: " + reason);
        }
    }

    private static final Result OK = new Result(true, null);

    private final int maxSide;
    private final int maxPlacements;

    public BlockPlanValidator(int maxSide, int maxPlacements) {
        if (maxSide <= 0) {
            throw new IllegalArgumentException("maxSide must be > 0");
        }
        if (maxPlacements <= 0) {
            throw new IllegalArgumentException("maxPlacements must be > 0");
        }
        this.maxSide = maxSide;
        this.maxPlacements = maxPlacements;
    }

    /**
     * @param plan        the proposal
     * @param ledger      the squad's authoritative supply (may be null to skip cost checks)
     * @param worldMinY   lowest buildable Y in the target world
     * @param worldMaxY   highest buildable Y in the target world
     * @param knownSolid  positions already solid in the world, used for support checks
     */
    public Result validate(BlockPlan plan, ItemLedger ledger,
                           int worldMinY, int worldMaxY, Set<Long> knownSolid) {
        if (plan == null) {
            return reject("plan is null");
        }

        // ---- dimensions -----------------------------------------------------
        if (plan.sizeX() <= 0 || plan.sizeY() <= 0 || plan.sizeZ() <= 0) {
            return reject("dimensions must be positive, got "
                    + plan.sizeX() + "x" + plan.sizeY() + "x" + plan.sizeZ());
        }
        if (plan.sizeX() > maxSide || plan.sizeY() > maxSide || plan.sizeZ() > maxSide) {
            return reject("dimension exceeds max side " + maxSide + ": "
                    + plan.sizeX() + "x" + plan.sizeY() + "x" + plan.sizeZ());
        }
        if (plan.totalBlocks() > maxPlacements) {
            return reject("plan has " + plan.totalBlocks() + " blocks, cap is " + maxPlacements);
        }
        if (plan.placements().isEmpty()) {
            return reject("plan contains no placements");
        }

        // ---- palette integrity ---------------------------------------------
        for (ItemId id : plan.palette()) {
            if (id == null || id.isAir()) {
                return reject("palette must not contain air or null");
            }
        }

        // ---- per-placement checks ------------------------------------------
        Set<Long> seen = new HashSet<>();
        for (BlockPlan.Placement p : plan.placements()) {
            if (p.paletteIndex() < 0 || p.paletteIndex() >= plan.palette().size()) {
                return reject("placement at " + p.x() + "," + p.y() + "," + p.z()
                        + " references palette index " + p.paletteIndex()
                        + " outside palette size " + plan.palette().size());
            }
            if (p.x() < 0 || p.x() >= plan.sizeX()
                    || p.y() < 0 || p.y() >= plan.sizeY()
                    || p.z() < 0 || p.z() >= plan.sizeZ()) {
                return reject("placement " + p.x() + "," + p.y() + "," + p.z()
                        + " is outside declared bounds "
                        + plan.sizeX() + "x" + plan.sizeY() + "x" + plan.sizeZ());
            }
            long key = key(p.x(), p.y(), p.z());
            if (!seen.add(key)) {
                return reject("duplicate placement at " + p.x() + "," + p.y() + "," + p.z());
            }
        }

        // ---- world bounds ---------------------------------------------------
        if (worldMaxY > worldMinY) {
            for (BlockPlan.Placement p : plan.placements()) {
                if (p.y() < worldMinY || p.y() > worldMaxY) {
                    return reject("placement Y " + p.y() + " outside world bounds "
                            + worldMinY + ".." + worldMaxY);
                }
            }
        }

        // ---- support: every block needs a solid neighbour below or beside ----
        if (knownSolid != null && !knownSolid.isEmpty()) {
            for (BlockPlan.Placement p : plan.placements()) {
                if (!hasSupport(plan, p, seen, knownSolid)) {
                    return reject("placement at " + p.x() + "," + p.y() + "," + p.z()
                            + " has no supporting neighbour - Nulls cannot place floating blocks");
                }
            }
        }

        // ---- material cost --------------------------------------------------
        if (ledger != null) {
            Map<ItemId, Integer> cost = plan.materialCost();
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<ItemId, Integer> e : cost.entrySet()) {
                int have = ledger.count(e.getKey());
                if (have < e.getValue()) {
                    if (missing.length() > 0) {
                        missing.append(", ");
                    }
                    missing.append(e.getKey()).append(" need ").append(e.getValue())
                            .append(" have ").append(have);
                }
            }
            if (missing.length() > 0) {
                return reject("insufficient materials: " + missing);
            }
        }

        return OK;
    }

    private boolean hasSupport(BlockPlan plan, BlockPlan.Placement p,
                               Set<Long> planCells, Set<Long> knownSolid) {
        // A block is supported if anything exists directly below it, or beside it.
        int[][] offsets = {
                {0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
        };
        for (int[] o : offsets) {
            long k = key(p.x() + o[0], p.y() + o[1], p.z() + o[2]);
            if (planCells.contains(k) || knownSolid.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static Result reject(String reason) { return new Result(false, reason); }

    /** Packs local plan coordinates into a long for set membership. */
    private static long key(int x, int y, int z) {
        return (((long) x) & 0xFFFFF) << 40
                | (((long) y) & 0xFFFFF) << 20
                | (((long) z) & 0xFFFFF);
    }
}
