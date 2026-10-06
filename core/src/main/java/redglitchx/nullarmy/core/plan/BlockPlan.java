package redglitchx.nullarmy.core.plan;

import redglitchx.nullarmy.core.ledger.ItemId;

import java.util.Collections;
import java.util.List;

/**
 * A bounded, validated block placement plan.
 *
 * <p>Produced either by the {@code /schematics} folder or by the deterministic local planner.
 * Spec 4: "Validate dimensions, palette, block states, rotations, material
 * costs, support rules, world bounds, protection, and every placement locally
 * before approval."</p>
 *
 * <p>The plan is a <em>proposal</em>. Nothing here places a block. Nulls still
 * have to walk to each position and place it physically.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BlockPlan {

    /** One placement: a position plus an index into {@link #palette}. */
    public static final class Placement {
        private final int x;
        private final int y;
        private final int z;
        private final int paletteIndex;

        public Placement(int x, int y, int z, int paletteIndex) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.paletteIndex = paletteIndex;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public int paletteIndex() { return paletteIndex; }
    }

    private final String name;
    private final List<ItemId> palette;
    private final List<Placement> placements;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    public BlockPlan(String name, List<ItemId> palette, List<Placement> placements,
                     int sizeX, int sizeY, int sizeZ) {
        if (palette == null || palette.isEmpty()) {
            throw new IllegalArgumentException("palette must not be empty");
        }
        this.name = name == null ? "unnamed" : name;
        this.palette = Collections.unmodifiableList(palette);
        this.placements = Collections.unmodifiableList(placements);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
    }

    public String name() { return name; }
    public List<ItemId> palette() { return palette; }
    public List<Placement> placements() { return placements; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }

    /** Total block count, which is also the total item cost (1 item per block). */
    public int totalBlocks() { return placements.size(); }

    /**
     * Per-material cost, derived from the palette.
     *
     * <p>Spec 4: "Count every block/item needed before a build and stop with a
     * clear shortage report."</p>
     */
    public java.util.Map<ItemId, Integer> materialCost() {
        java.util.Map<ItemId, Integer> cost = new java.util.LinkedHashMap<>();
        for (Placement p : placements) {
            if (p.paletteIndex() < 0 || p.paletteIndex() >= palette.size()) {
                throw new IllegalStateException("placement references unknown palette index "
                        + p.paletteIndex());
            }
            ItemId id = palette.get(p.paletteIndex());
            cost.merge(id, 1, Integer::sum);
        }
        return cost;
    }
}
