package redglitchx.nullarmy.core.nav;

/**
 * A read-only view of block collision and terrain cost.
 *
 * <p>{@code core} has no world access (ADR-004), so pathfinding depends on
 * this interface instead. The version adapter supplies a real implementation;
 * tests supply a fake. Every method must be cheap and must never mutate the
 * world.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public interface BlockView {

    /**
     * @return true if a Null's body cannot occupy this block
     */
    boolean isSolid(int x, int y, int z);

    /**
     * Extra traversal cost for this block (lava, fire, cactus, powder snow,
     * deep water, dangerous drops). 0 means "no extra cost".
     *
     * <p>Spec 5: "Assign terrain costs for danger, height, liquids, fall risk,
     * fire/lava, darkness, protected areas, and formation disruption."</p>
     */
    double extraCost(int x, int y, int z);

    /** Lowest block Y this view can answer for. */
    int minY();

    /** Highest block Y this view can answer for. */
    int maxY();
}
