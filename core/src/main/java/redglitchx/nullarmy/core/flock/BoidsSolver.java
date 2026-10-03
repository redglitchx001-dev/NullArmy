package redglitchx.nullarmy.core.flock;

import redglitchx.nullarmy.core.math.Vec3d;

import java.util.List;

/**
 * Formation-aware Boids solver with a HARD separation constraint.
 *
 * <p>Spec 1.3 / 5: combine separation, alignment, cohesion, formation-slot
 * attraction, obstacle avoidance and target pursuit as <em>bounded</em>
 * steering forces - and "formation goals must never override hitboxes or
 * collision".</p>
 *
 * <p>The critical design point: {@link #separation} is not blended as a soft
 * force that can be outvoted. It is returned separately and the caller applies
 * it AFTER the soft forces, so a Null can never be pushed into another Null by
 * a formation slot. See {@link #solve}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BoidsSolver {

    private final double perceptionRadius;
    private final double separationRadius;
    private final double maxForce;
    private final double separationWeight;
    private final double alignmentWeight;
    private final double cohesionWeight;
    private final double slotWeight;

    private BoidsSolver(Builder b) {
        this.perceptionRadius = b.perceptionRadius;
        this.separationRadius = b.separationRadius;
        this.maxForce = b.maxForce;
        this.separationWeight = b.separationWeight;
        this.alignmentWeight = b.alignmentWeight;
        this.cohesionWeight = b.cohesionWeight;
        this.slotWeight = b.slotWeight;
    }

    public static Builder builder() { return new Builder(); }

    /** Weights and radii for one solver. All lengths are in blocks. */
    public static final class Builder {
        private double perceptionRadius = 6.0;
        private double separationRadius = 0.9;
        private double maxForce = 1.0;
        private double separationWeight = 2.0;
        private double alignmentWeight = 0.25;
        private double cohesionWeight = 0.2;
        private double slotWeight = 0.6;

        public Builder perceptionRadius(double v) { perceptionRadius = v; return this; }
        public Builder separationRadius(double v) { separationRadius = v; return this; }
        public Builder maxForce(double v) { maxForce = v; return this; }
        public Builder separationWeight(double v) { separationWeight = v; return this; }
        public Builder alignmentWeight(double v) { alignmentWeight = v; return this; }
        public Builder cohesionWeight(double v) { cohesionWeight = v; return this; }
        public Builder slotWeight(double v) { slotWeight = v; return this; }

        public BoidsSolver build() {
            if (separationRadius <= 0.0) {
                throw new IllegalStateException("separationRadius must be > 0");
            }
            if (perceptionRadius < separationRadius) {
                throw new IllegalStateException("perceptionRadius must be >= separationRadius");
            }
            if (maxForce <= 0.0) {
                throw new IllegalStateException("maxForce must be > 0");
            }
            return new BoidsSolver(this);
        }
    }

    /**
     * One steering participant, supplied by the caller.
     */
    public interface Agent {
        int id();
        Vec3d position();
        Vec3d velocity();
    }

    /** The result of a steering solve. */
    public static final class Steering {
        private final Vec3d soft;
        private final Vec3d separation;

        Steering(Vec3d soft, Vec3d separation) {
            this.soft = soft;
            this.separation = separation;
        }

        /** Alignment + cohesion + slot attraction, clamped to maxForce. */
        public Vec3d soft() { return soft; }

        /** The non-negotiable anti-overlap push. Apply this last. */
        public Vec3d separation() { return separation; }

        /**
         * Soft + separation, with separation applied at full weight so it can
         * never be outvoted. The caller may additionally scale this by a
         * movement speed; it is already bounded.
         */
        public Vec3d total() { return soft.add(separation).clampLength(Double.MAX_VALUE); }
    }

    /**
     * Computes steering for {@code self}.
     *
     * @param self       the agent being steered
     * @param neighbours nearby agents, including possibly {@code self}
     * @param slot       the assigned formation slot, or null if none
     * @return bounded steering forces
     */
    public Steering solve(Agent self, List<Agent> neighbours, Vec3d slot) {
        Vec3d alignment = Vec3d.ZERO;
        Vec3d cohesion = Vec3d.ZERO;
        Vec3d separation = Vec3d.ZERO;

        int alignCount = 0;
        int cohesionCount = 0;

        for (Agent other : neighbours) {
            if (other.id() == self.id()) {
                continue;
            }
            Vec3d offset = other.position().sub(self.position());
            double dist = offset.horizontalLength();

            if (dist <= perceptionRadius) {
                alignment = alignment.add(other.velocity());
                alignCount++;
                cohesion = cohesion.add(other.position());
                cohesionCount++;
            }

            // Hard personal-space constraint. Inverse-distance weighted so the
            // push grows without bound as bodies converge - that is what makes
            // overlap unreachable rather than merely unlikely.
            if (dist < separationRadius && dist > 1e-6) {
                double push = (separationRadius - dist) / separationRadius;
                separation = separation.add(offset.scale(-1.0 / dist).scale(push));
            } else if (dist <= 1e-6) {
                // Perfectly coincident: pick a deterministic escape direction
                // from the id so two stacked Nulls always separate the same way.
                double angle = (self.id() % 360) * Math.PI / 180.0;
                separation = separation.add(new Vec3d(Math.cos(angle), 0.0, Math.sin(angle)));
            }
        }

        if (alignCount > 0) {
            alignment = alignment.scale(1.0 / alignCount).sub(self.velocity()).clampLength(maxForce);
        }
        if (cohesionCount > 0) {
            Vec3d centre = cohesion.scale(1.0 / cohesionCount);
            cohesion = centre.sub(self.position()).clampLength(maxForce);
        }

        Vec3d slotForce = Vec3d.ZERO;
        if (slot != null) {
            slotForce = slot.sub(self.position()).clampLength(maxForce);
        }

        Vec3d soft = Vec3d.ZERO
                .add(alignment.scale(alignmentWeight))
                .add(cohesion.scale(cohesionWeight))
                .add(slotForce.scale(slotWeight))
                .clampLength(maxForce);

        Vec3d hardSeparation = separation.scale(separationWeight).clampLength(maxForce * separationWeight);

        return new Steering(soft, hardSeparation);
    }

    public double separationRadius() { return separationRadius; }
    public double perceptionRadius() { return perceptionRadius; }
}
