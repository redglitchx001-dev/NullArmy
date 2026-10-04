package redglitchx.nullarmy.core.combat;

/**
 * An immutable snapshot of everything the technique selector is allowed to
 * know about a fight.
 *
 * <p>It is deliberately made of <b>primitives only</b> so {@code core} stays
 * free of any Bukkit dependency and the selector can be unit tested with
 * nothing but a JRE.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CombatSituation {

    /**
     * Damage the mace hit itself contributes, before any fall-distance bonus.
     * A smash is not only the fall: leaving this out makes the model reject
     * genuinely lethal smashes.
     */
    private static final double MACE_BASE_DAMAGE = 6.0;

    private final double distanceToTarget;
    private final double heightAboveTarget;
    private final double fallSpeed;
    private final double ownHealth;
    private final double targetHealth;
    private final double targetArmor;
    private final boolean hasMace;
    private final boolean hasElytra;
    private final boolean hasWindCharge;
    private final boolean hasShield;
    private final boolean hasBow;
    private final boolean hasCrossbow;
    private final boolean hasTrident;
    private final boolean hasEnderPearl;
    private final boolean waterBucket;
    private final boolean elytraDeployed;
    private final boolean targetBlocking;
    private final boolean inRain;
    private final boolean inWater;
    private final int fireworks;
    private final boolean maceHasDensity;
    private final boolean maceHasBreach;
    private final boolean maceHasWindBurst;

    private CombatSituation(Builder b) {
        this.distanceToTarget = b.distanceToTarget;
        this.heightAboveTarget = b.heightAboveTarget;
        this.fallSpeed = b.fallSpeed;
        this.ownHealth = b.ownHealth;
        this.targetHealth = b.targetHealth;
        this.targetArmor = b.targetArmor;
        this.hasMace = b.hasMace;
        this.hasElytra = b.hasElytra;
        this.hasWindCharge = b.hasWindCharge;
        this.hasShield = b.hasShield;
        this.hasBow = b.hasBow;
        this.hasCrossbow = b.hasCrossbow;
        this.hasTrident = b.hasTrident;
        this.hasEnderPearl = b.hasEnderPearl;
        this.waterBucket = b.waterBucket;
        this.elytraDeployed = b.elytraDeployed;
        this.targetBlocking = b.targetBlocking;
        this.inRain = b.inRain;
        this.inWater = b.inWater;
        this.fireworks = b.fireworks;
        this.maceHasDensity = b.maceHasDensity;
        this.maceHasBreach = b.maceHasBreach;
        this.maceHasWindBurst = b.maceHasWindBurst;
    }

    public static Builder builder() { return new Builder(); }

    /** Horizontal distance to the target, in blocks. */
    public double distanceToTarget() { return distanceToTarget; }

    /** How far above the target we are. Positive = we are higher. */
    public double heightAboveTarget() { return heightAboveTarget; }

    /** Downward speed in blocks/second when falling. 0 when not falling. */
    public double fallSpeed() { return fallSpeed; }

    public double ownHealth() { return ownHealth; }
    public double targetHealth() { return targetHealth; }
    public double targetArmor() { return targetArmor; }

    public boolean hasMace() { return hasMace; }
    public boolean hasElytra() { return hasElytra; }
    public boolean hasWindCharge() { return hasWindCharge; }
    public boolean hasShield() { return hasShield; }
    public boolean hasBow() { return hasBow; }
    public boolean hasCrossbow() { return hasCrossbow; }
    public boolean hasTrident() { return hasTrident; }
    public boolean hasEnderPearl() { return hasEnderPearl; }
    public boolean hasWaterBucket() { return waterBucket; }
    public boolean elytraDeployed() { return elytraDeployed; }
    public boolean targetBlocking() { return targetBlocking; }
    public boolean inRain() { return inRain; }
    public boolean inWater() { return inWater; }
    public int fireworks() { return fireworks; }

    public boolean maceHasDensity() { return maceHasDensity; }
    public boolean maceHasBreach() { return maceHasBreach; }
    public boolean maceHasWindBurst() { return maceHasWindBurst; }

    /** A mace one-shots when the fall is high enough and the target is soft enough. */
    public boolean smashIsLethal() {
        if (!hasMace || fallSpeed <= 0.0) {
            return false;
        }
        // Total smash damage = the mace hit itself + fall distance + the speed
        // already carried. Density adds damage per block fallen; the threshold
        // stays deliberately conservative so a marginal smash is refused.
        double bonus = maceHasDensity ? 0.5 : 0.0;
        double effective = MACE_BASE_DAMAGE
                + (heightAboveTarget + fallSpeed * 0.35) * (1.0 + bonus);
        return effective >= targetHealth + targetArmor;
    }

    public static final class Builder {
        private double distanceToTarget;
        private double heightAboveTarget;
        private double fallSpeed;
        private double ownHealth = 20.0;
        private double targetHealth = 20.0;
        private double targetArmor;
        private boolean hasMace;
        private boolean hasElytra;
        private boolean hasWindCharge;
        private boolean hasShield;
        private boolean hasBow;
        private boolean hasCrossbow;
        private boolean hasTrident;
        private boolean hasEnderPearl;
        private boolean waterBucket;
        private boolean elytraDeployed;
        private boolean targetBlocking;
        private boolean inRain;
        private boolean inWater;
        private int fireworks;
        private boolean maceHasDensity;
        private boolean maceHasBreach;
        private boolean maceHasWindBurst;

        public Builder distanceToTarget(double v) { this.distanceToTarget = v; return this; }
        public Builder heightAboveTarget(double v) { this.heightAboveTarget = v; return this; }
        public Builder fallSpeed(double v) { this.fallSpeed = v; return this; }
        public Builder ownHealth(double v) { this.ownHealth = v; return this; }
        public Builder targetHealth(double v) { this.targetHealth = v; return this; }
        public Builder targetArmor(double v) { this.targetArmor = v; return this; }
        public Builder hasMace(boolean v) { this.hasMace = v; return this; }
        public Builder hasElytra(boolean v) { this.hasElytra = v; return this; }
        public Builder hasWindCharge(boolean v) { this.hasWindCharge = v; return this; }
        public Builder hasShield(boolean v) { this.hasShield = v; return this; }
        public Builder hasBow(boolean v) { this.hasBow = v; return this; }
        public Builder hasCrossbow(boolean v) { this.hasCrossbow = v; return this; }
        public Builder hasTrident(boolean v) { this.hasTrident = v; return this; }
        public Builder hasEnderPearl(boolean v) { this.hasEnderPearl = v; return this; }
        public Builder hasWaterBucket(boolean v) { this.waterBucket = v; return this; }
        public Builder elytraDeployed(boolean v) { this.elytraDeployed = v; return this; }
        public Builder targetBlocking(boolean v) { this.targetBlocking = v; return this; }
        public Builder inRain(boolean v) { this.inRain = v; return this; }
        public Builder inWater(boolean v) { this.inWater = v; return this; }
        public Builder fireworks(int v) { this.fireworks = Math.max(0, v); return this; }
        public Builder maceHasDensity(boolean v) { this.maceHasDensity = v; return this; }
        public Builder maceHasBreach(boolean v) { this.maceHasBreach = v; return this; }
        public Builder maceHasWindBurst(boolean v) { this.maceHasWindBurst = v; return this; }

        public CombatSituation build() { return new CombatSituation(this); }
    }
}
