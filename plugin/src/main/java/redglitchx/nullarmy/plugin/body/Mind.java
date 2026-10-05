package redglitchx.nullarmy.plugin.body;

import redglitchx.nullarmy.core.math.Vec3d;

import java.util.UUID;

/**
 * Everything one Null remembers between ticks.
 *
 * <p>Plain mutable state owned by {@link NullBrain} on the main thread. Nothing
 * here is persisted: a Null that is gone takes its mind with it.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Mind {

    /** The verbs of {@code /null order} and of chat orders. */
    public enum Verb {
        WALK, RUN, SPRINT, JUMP, STOP, FOLLOW, HOLD, GATHER, BUILD, ATTACK, DEFEND,
        /** L-01: march in locked step. */
        MARCH,
        /** L-01: cycle line -> wedge -> phalanx at a held anchor. */
        DRILL,
        /** L-03: walk between two points for ever. */
        PATROL,
        /** L-02: walk forward and bridge the gaps on the way. */
        BRIDGE,
        /** L-03: salute the owner when he comes back. */
        SALUTE,
        /** L-07: come back to the owner and reform. */
        REGROUP,
        /** P-09: teardown, only with policy.griefing-enabled and a confirm. */
        DESTROY,
        /** L-07: hunt a target to the end. */
        HUNT
    }

    /** One explicit order. */
    public static final class Order {
        public final Verb verb;
        /** The order's point. Mutable: a teardown walks down through a marked area. */
        public Vec3d point;
        public final UUID entity;
        public final UUID issuer;
        public final long issuedTick;
        public int remaining;

        public Order(Verb verb, Vec3d point, UUID entity, UUID issuer, long issuedTick, int remaining) {
            this.verb = verb;
            this.point = point;
            this.entity = entity;
            this.issuer = issuer;
            this.issuedTick = issuedTick;
            this.remaining = remaining;
        }

        /** Second point of a patrol (L-03). */
        public Vec3d pointB;

        /** Radius of a destroy order, in blocks (P-09). */
        public int radius = 2;

        /** True when this body is one of the chasers of a hunt (L-07). */
        public boolean chaser;

        /** True when this body guards the line while others chase (L-07). */
        public boolean holder;
    }

    final UUID id;
    final long bornTick;
    double speedFactor = 1.0D;
    boolean speedApplied;

    Order order;

    // Attention: who or what the head is turned to.
    Vec3d attention;
    long attentionUntil;
    boolean attentionHeadOnly = true;
    /** True when the current attention is only an idle look-around (players outrank it). */
    boolean attentionIsScan;
    long nextScanTick;
    long nextPlayerGlance;
    UUID glancedAt;

    // Idle and rest.
    long idleSince;
    boolean resting;
    Vec3d lastPos;

    // Unstacking.
    Vec3d slideTo;
    long slideUntil;

    // Being stuck against something while walking.
    int blockedTicks;
    Vec3d detour;
    long detourUntil;

    // Portal step-out.
    Vec3d exitPoint;
    long exitUntil;

    // Combat.
    UUID combatTarget;
    long combatUntil;
    int strafeDir = 1;
    long nextStrafeFlip;
    boolean critJumped;
    long critJumpTick;
    long bowDrawStart = -1L;
    /** P-05 aim: when the shot may be loosed, and how wrong this one is. */
    long aimReadyTick = -1L;
    boolean aimMiss;
    double aimYawError;
    double aimPitchError;
    long nextAttackTick;
    boolean shieldUp;

    // Eating and drinking.
    long eatingUntil = -1L;
    int slotBeforeEating = -1;
    long nextEatAllowed;

    // Gesture acknowledging an order.
    long gestureUntil;

    /** Formation hold: the cell this Null was told to hold, if any. */
    Vec3d holdCell;

    /** L-03: which end of a patrol this Null is walking to. */
    boolean patrolToB;

    /** L-01: next tick this marching Null may take its step gesture. */
    long nextStepTick;

    /** L-02/L-07: next tick this Null may place a bridge block. */
    long nextBridgeTick;

    /** L-04: the idle behaviour this Null is performing right now. */
    String campBehaviour = "";

    /** L-01: which drill formation this body's cell was computed for. */
    String drillForm = "";

    /** L-03: this body has already saluted for the current order. */
    boolean saluted;

    /** L-03: last tick the owner was close enough to be saluted. */
    long lastOwnerNearTick;

    /** L-03: the order to go back to once the salute is finished. */
    Mind.Order orderAfterSalute;

    /** L-08: tick this Null picked something up. */
    long lastPickupTick;

    /** True once the Null reached its formation cell (left only when pushed > 0.35 away). */
    boolean atCell;

    private static String describeEntity(UUID id) {
        org.bukkit.entity.Entity e = org.bukkit.Bukkit.getEntity(id);
        return e == null ? "gone" : e.getType().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** One line for diagnostics. */
    public String describe() {
        return "order=" + (order == null ? "-" : order.verb) + " exit=" + (exitPoint == null ? "-"
                : String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", exitPoint.x(), exitPoint.y(), exitPoint.z()))
                + " exitUntil=" + exitUntil + " slide=" + (slideTo != null) + " fight=" + (combatTarget == null ? "-"
                : describeEntity(combatTarget))
                + " detour=" + (detour != null) + " blocked=" + blockedTicks + " atCell=" + atCell
                + " resting=" + resting + " eating=" + eating();
    }

    Mind(UUID id, long bornTick) {
        this.id = id;
        this.bornTick = bornTick;
        this.idleSince = bornTick;
    }

    public UUID id() { return id; }
    public double speedFactor() { return speedFactor; }
    public Order order() { return order; }
    public UUID combatTarget() { return combatTarget; }
    public boolean resting() { return resting; }
    public boolean eating() { return eatingUntil >= 0; }
}
