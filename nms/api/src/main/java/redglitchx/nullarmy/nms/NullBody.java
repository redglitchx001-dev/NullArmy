package redglitchx.nullarmy.nms;

import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.math.Vec3d;

import java.util.List;

/**
 * The server-authoritative body of one Null.
 *
 * <p>This is what the plugin and core are allowed to do to a Null. Note what is
 * <b>absent</b>: there is no general {@code teleport} or {@code setPosition}
 * operation. Ordinary movement, path recovery, formation, and summoning use
 * vanilla movement only. The distinct owner-triggered {@code /null tp}
 * Ender-Pearl cannon is handled through the vanilla projectile pipeline; it is
 * not exposed as a relocation primitive here and is never used by AI or summon
 * code. {@link #applySteering} remains the normal way to move a Null.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public interface NullBody {

    /** Stable runtime id. */
    int id();

    /** The profile/display name, e.g. {@code uH3WR2v0ti0uTHJ}. */
    String profileName();

    /**
     * The body's current server-side position.
     *
     * <p>Named {@code bodyPosition()} rather than {@code position()} on
     * purpose: since 1.21.9 every {@code net.minecraft.world.entity.Entity}
     * implements {@code ItemOwner}, which declares
     * {@code net.minecraft.world.phys.Vec3 position()}. A class cannot
     * implement {@code position()} twice with two different return types, and
     * an NMS entity cannot return the version-neutral {@link Vec3d}, so the
     * SPI method must have its own name.</p>
     */
    Vec3d bodyPosition();

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

    /**
     * Restores health, capped at this body's maximum.
     *
     * <p>Never throws and never revives a dead body: {@code /null heal} on a
     * squad that is already gone is a no-op with an honest message, not an
     * error.</p>
     */
    void heal(double amount);

    /** The authoritative inventory. Never null. */
    ItemLedger inventory();

    /** Removes the Null from the world. Does not drop items implicitly. */
    void destroy();

    /**
     * Equips this Null with a loadout.
     *
     * <p>Slots not listed are left untouched. An unrecognised material is
     * skipped, never fatal. This is how the Commander gets the kit the owner
     * arranged in the loadout GUI.</p>
     */
    void setLoadout(java.util.List<LoadoutSlot> slots);

    /**
     * The loadout currently worn, in the same slot numbering as
     * {@link #setLoadout(List)}.
     *
     * <p>Empty slots are omitted, never returned as {@code null} entries and
     * never invented. Used by {@code /null drop}, which empties a Null's
     * inventory and hands the materials back to the world.</p>
     */
    List<LoadoutSlot> loadout();

    // ------------------------------------------------------------- body control

    /** No movement input this tick. */
    int GAIT_STOP = 0;
    /** An unhurried walk (about 60 % of normal walking input). */
    int GAIT_WALK = 1;
    /** Full walking input without sprinting - what a player gets holding W. */
    int GAIT_RUN = 2;
    /** Full input with the vanilla sprint modifier. */
    int GAIT_SPRINT = 3;

    /**
     * The entity/profile UUID. The plugin resolves the body's Bukkit player
     * through it (inventory, attributes, swings, item use) so every one of those
     * actions runs through the same server code a real player's would.
     */
    default java.util.UUID uuid() { return null; }

    /**
     * The movement intent for the next ticks, shaped exactly like a client's
     * input: a world-space horizontal direction whose length (0..1) is the
     * throttle, a gait, a jump request and the sneak key.
     *
     * <p>The adapter turns it into vanilla travel input. Friction, gravity,
     * collisions, step height, water, ladders and fall damage stay vanilla, so
     * a Null can never fly, clip into a block or move faster than a player. An
     * intent that is not refreshed expires after a few ticks: a Null whose
     * brain stops sending orders stops walking.</p>
     */
    default void setMovement(double dirX, double dirZ, int gait, boolean jump, boolean sneak) {
        applySteering(gait == GAIT_STOP ? Vec3d.ZERO : new Vec3d(dirX, 0.0, dirZ).scale(0.2));
    }

    /**
     * Where the body should look. A null target clears the look so movement
     * resumes controlling orientation. {@code headOnly} is retained for API
     * compatibility but adapters must keep head and body orientation coupled;
     * independent head turns are not used by NullArmy.
     */
    default void setLookTarget(Vec3d target, boolean headOnly) {
        if (target != null) {
            lookAt(target);
        }
    }

    /** Head yaw in degrees, the value clients render. */
    default float headYaw() { return 0.0F; }

    /** Travel-frame yaw in degrees (the entity yaw that movement input is relative to). */
    default float bodyYaw() { return 0.0F; }

    /** Pitch in degrees. */
    default float pitch() { return 0.0F; }

    /** True when the body stands on a block (vanilla's own onGround flag). */
    default boolean onGround() { return true; }

    /** Current velocity in blocks per tick. */
    default Vec3d velocity() { return Vec3d.ZERO; }

    /** Accumulated fall distance in blocks; above zero only while falling. */
    default double fallDistance() { return 0.0D; }

    /** True while the body is in water. */
    default boolean inWater() { return false; }

    /** True when the last move was stopped by a block side (walking into a wall). */
    default boolean horizontalCollision() { return false; }

    /**
     * Death progress: -1 while alive, ticks since death while the death
     * animation plays, {@link Integer#MAX_VALUE} once the body has left the world.
     */
    default int deathTicks() { return isAlive() ? -1 : Integer.MAX_VALUE; }

    /**
     * Releases the item being used (draws a bow to completion and shoots), the
     * way letting go of the use key does. Returns false when nothing is in use.
     */
    default boolean releaseUseItem() { return false; }

    /** True for the self-test viewer probe. */
    default boolean isProbe() { return false; }
}
