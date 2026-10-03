package redglitchx.nullarmy.nms;

import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.math.Vec3d;

/**
 * The server-authoritative body of one Null.
 *
 * <p>This is what the plugin and core are allowed to do to a Null. Note what is
 * <b>absent</b>: there is no {@code teleport} and no {@code setPosition}.
 * Spec 1.2 and 5 forbid teleportation outright, including as a recovery
 * mechanism, so the interface simply does not offer it. {@link #applySteering}
 * is the only way to move a Null.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public interface NullBody {

    /** Stable runtime id. */
    int id();

    /** The profile/display name, e.g. {@code uH3WR2v0ti0uTHJ}. */
    String profileName();

    Vec3d position();

    /**
     * Applies a bounded steering force for this tick.
     *
     * <p>The adapter is responsible for feeding this through the entity's real
     * vanilla movement pipeline, so every step is explainable as ordinary
     * physics. Position is never snapped.</p>
     */
    void applySteering(Vec3d force);

    /** Turns to face a point. Cosmetic + required for legal attacks. */
    void lookAt(Vec3d target);

    boolean isAlive();
    double health();

    /** The authoritative inventory. Never null. */
    ItemLedger inventory();

    /** Removes the Null from the world. Does not drop items implicitly. */
    void destroy();
}
