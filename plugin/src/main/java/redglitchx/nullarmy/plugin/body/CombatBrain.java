package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.combat.AimSkill;
import redglitchx.nullarmy.core.combat.Ballistics;
import redglitchx.nullarmy.core.combat.CombatSituation;
import redglitchx.nullarmy.core.combat.PvpArsenal;
import redglitchx.nullarmy.core.combat.ReachGate;
import redglitchx.nullarmy.core.combat.SwingCadence;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.kit.KitItems;

import java.util.Random;
import java.util.UUID;

/**
 * How a Null fights - with vanilla's own combat, nothing scripted on top.
 *
 * <ul>
 *   <li><b>Cooled swings.</b> A Null attacks through {@code Player#attack} only
 *       when its attack cooldown is full, so damage, knockback and sweeping are
 *       vanilla's.</li>
 *   <li><b>Critical hits.</b> With {@code combat.crits}, it jumps and strikes on
 *       the way down - vanilla's crit condition (falling, not sprinting) gives the
 *       x1.5 and the particles.</li>
 *   <li><b>Sprint knockback.</b> Closing a gap it sprints, and the first hit lands
 *       while sprinting.</li>
 *   <li><b>Strafing.</b> In melee range it circles at 2-3 blocks, switching
 *       direction now and then.</li>
 *   <li><b>Shields.</b> Between swings the offhand shield goes up; it comes down
 *       for the swing. An axe on the raised shield disables it (see
 *       {@link NullLifecycleListener}).</li>
 *   <li><b>Wind charges.</b> On an explicit attack order, a real charge is
 *       thrown at medium range, consumed from inventory, and subject to a
 *       per-body cooldown. Its vanilla wind burst is not cancelled.</li>
 *   <li><b>Commander mobility.</b> Explicit mid-range pursuits may deploy the
 *       Commander's real Elytra and spend real firework rockets. The exact
 *       previous chest item is restored on landing or when the pursuit ends;
 *       ordinary Nulls and retaliatory fights never initiate flight.</li>
 *   <li><b>Commander mace planning.</b> The live selector reads the actual
 *       inventory, target armour and fall state. Supported smash, Wind Burst,
 *       Density and elytra-dive choices select a real mace for vanilla
 *       {@code Player#attack}; shield-break, pearl and water-placement plans
 *       remain planning-only.</li>
 *   <li><b>Bows.</b> Beyond 8 blocks it draws, aims with lead and arc
 *       ({@link Ballistics}) and releases a real arrow.</li>
 *   <li><b>Retreat.</b> Below 30 % health it backs off and eats or drinks.</li>
 * </ul>
 *
 * <p>Combat pursuit and target-facing require an explicit attack or hunt order.
 * Configured retaliation may defend at melee range, but never makes a Null
 * acquire, face, or run toward a target on its own. There is no autonomous
 * nearest-hostile scan.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CombatBrain {

    private static final double MELEE_REACH = 3.0D;
    private static final double STRAFE_DISTANCE = 2.5D;
    private static final double BOW_MIN_DISTANCE = 8.0D;
    private static final double GIVE_UP_DISTANCE = 32.0D;

    private final NullArmyPlugin plugin;
    private final NullBrain brain;
    private final ElytraFlightController flight;
    private final Random random = new Random();

    /** How many swings were taken, and how many of them were criticals (P-02). */
    private int swings;
    private int crits;
    /** How many swings the reach gate refused (P-01). */
    private int reachRefusals;
    /** How many aimed shots the aim model sent wide on purpose (P-05). */
    private int aimedShots;
    /** Swings since the last critical, for the crit cadence. */
    private int sinceCrit;

    CombatBrain(NullArmyPlugin plugin, NullBrain brain) {
        this.plugin = plugin;
        this.brain = brain;
        this.flight = new ElytraFlightController(plugin);
    }

    public int swings() { return swings; }
    public int crits() { return crits; }
    public int reachRefusals() { return reachRefusals; }
    public int aimedShots() { return aimedShots; }

    /**
     * Why the last combat tick did not swing: one short word, for the self test.
     *
     * <p>"A swing did not land" is not a diagnosis. This is the branch the
     * fighter actually took, so a failing check says which rule stopped it.</p>
     */
    public String lastNote() { return lastNote; }

    private String lastNote = "no fight yet";
    private PvpArsenal.Technique lastCommanderTechnique = PvpArsenal.Technique.DISENGAGE;

    /** Last supported melee technique selected for the Commander, for diagnostics. */
    public PvpArsenal.Technique lastCommanderTechnique() { return lastCommanderTechnique; }

    /** Whether this Commander currently has a managed Elytra pursuit in progress. */
    public boolean elytraFlightActive(NullBody body) {
        return body != null && flight.active(body.uuid());
    }

    /** Restores a body's chest equipment immediately when its order is cancelled. */
    public void stopFlight(NullBody body, Player handle) {
        flight.stop(body, handle);
    }

    /** Stops flight from a death event that has the entity UUID rather than a body wrapper. */
    void stopFlight(UUID id, Player handle) {
        flight.stop(id, handle);
    }

    /** Restores all active flight equipment before the brain is discarded. */
    void stopAllFlight() {
        flight.stopAll();
    }

    /** Self test: clears the swing / crit counters before a measured window. */
    public void resetCounters() {
        swings = 0;
        crits = 0;
        reachRefusals = 0;
        aimedShots = 0;
        sinceCrit = 0;
    }

    /** The reach a Null may strike at: the configured value, never above vanilla. */
    private double reach() {
        V3Settings v3 = brain.settings();
        return v3 == null ? ReachGate.VANILLA_REACH : v3.meleeReach();
    }

    private float threshold() {
        V3Settings v3 = brain.settings();
        return v3 == null ? SwingCadence.MIN_COOLDOWN : v3.attackThreshold();
    }

    private double aimSkill() {
        V3Settings v3 = brain.settings();
        return v3 == null ? AimSkill.DEFAULT : v3.aimSkill();
    }

    /**
     * P-01: the strike gate. Eye to target inside {@code combat.melee-reach}
     * (never more than vanilla's 3.0) <em>and</em> a clear line of sight, so a
     * Null can no longer hit through a wall or round a corner.
     */
    boolean strikeAllowed(Player handle, LivingEntity target) {
        if (handle == null || target == null) {
            return false;
        }
        org.bukkit.World world = handle.getWorld();
        Location eye = handle.getEyeLocation();
        Location at = target.getLocation();
        double ty = at.getY() + Math.min(1.8D, target.getHeight() * 0.6D);
        ReachGate.Occlusion occlusion = (x, y, z) -> {
            org.bukkit.block.Block block = new Location(world, x, y, z).getBlock();
            return block.getType().isOccluding();
        };
        return ReachGate.strikeAllowed(eye.getX(), eye.getY(), eye.getZ(), at.getX(), ty, at.getZ(),
                reach(), occlusion);
    }

    /** True while the mind has a living target it is allowed to fight. */
    boolean active(Mind mind, Player handle) {
        V3Settings v3 = brain.settings();
        if (v3 == null || !v3.combatEnabled() || handle == null) {
            endFight(mind, handle);
            return false;
        }
        if (mind.combatTarget == null) {
            return false;
        }
        Entity entity = Bukkit.getEntity(mind.combatTarget);
        // P-04: an order or retaliation can never put the owner, the Commander,
        // a squad mate or a protected player on the business end of a Null.
        // Checked every tick, not only when the target was first set.
        if (entity != null && !brain.mayTarget(handle, entity)) {
            endFight(mind, handle);
            return false;
        }
        if (!(entity instanceof LivingEntity) || entity.isDead() || entity.getWorld() != handle.getWorld()
                || entity.getLocation().distanceSquared(handle.getLocation()) > GIVE_UP_DISTANCE * GIVE_UP_DISTANCE
                || brain.now() > mind.combatUntil) {
            endFight(mind, handle);
            return false;
        }
        return true;
    }

    void endFight(Mind mind, Player handle) {
        mind.combatTarget = null;
        mind.combatPursuit = false;
        mind.critJumped = false;
        if (mind.bowDrawStart >= 0 && handle != null) {
            handle.clearActiveItem();
        }
        mind.bowDrawStart = -1L;
        flight.stop(mind.id, handle);
        brain.raiseShield(handle, mind, false);
    }

    NullBrain.Intent tick(NullBody body, Mind mind, Player handle, String world, Vec3d pos) {
        V3Settings v3 = brain.settings();
        LivingEntity target = (LivingEntity) Bukkit.getEntity(mind.combatTarget);
        long now = brain.now();
        Location targetAt = target.getLocation();
        Vec3d targetPos = new Vec3d(targetAt.getX(), targetAt.getY(), targetAt.getZ());
        Location eyeAt = target.getEyeLocation();
        double dx = targetPos.x() - pos.x();
        double dz = targetPos.z() - pos.z();
        double dist = Math.hypot(dx, dz);
        double distance3d = handle.getLocation().distance(targetAt);
        boolean commanderBody = isCommander(body);
        NullBrain.Intent intent = new NullBrain.Intent();
        if (mind.combatPursuit) {
            intent.look = new Vec3d(eyeAt.getX(), eyeAt.getY() - 0.2D, eyeAt.getZ());
        }

        // Retreat and heal when badly hurt.
        double max = NullBrain.maxHealth(handle);
        if (body.health() < max * 0.3D) {
            flight.stop(mind.id, handle);
            lastNote = "retreating";
            brain.raiseShield(handle, mind, false);
            cancelBow(mind, handle);
            if (dist < 6.0D) {
                intent.dx = -dx / Math.max(1.0e-6, dist);
                intent.dz = -dz / Math.max(1.0e-6, dist);
                intent.gait = NullBody.GAIT_SPRINT;
            } else if (!brain.maybeEat(body, mind, handle) && mind.combatPursuit
                    && drinkCombatPotion(body, mind, handle, distance3d, now)) {
                cancelBow(mind, handle);
                brain.raiseShield(handle, mind, false);
                lastNote = "drinking a combat potion while retreating";
            }
            return intent;
        }

        // A retaliatory target is never a pursuit order. It must not inherit a
        // previously deployed Elytra or start a new flight.
        if (!commanderBody || !mind.combatPursuit) {
            flight.stop(mind.id, handle);
        }
        // Defend only if the retaliatory target is already inside melee reach;
        // do not face or close the gap otherwise.
        if (!mind.combatPursuit && dist > reach()) {
            lastNote = "holding position: no attack order";
            brain.raiseShield(handle, mind, false);
            cancelBow(mind, handle);
            return intent;
        }

        // Tactical drinks are only used for an explicit pursuit, at a safe
        // stand-off distance, and never while the Commander is already gliding.
        if (mind.combatPursuit && drinkCombatPotion(body, mind, handle, distance3d, now)) {
            cancelBow(mind, handle);
            brain.raiseShield(handle, mind, false);
            lastNote = "drinking a tactical combat potion";
            return intent;
        }

        // Long explicit Commander pursuits use the real Elytra before falling
        // back to a bow or a sprint. Ordinary Nulls and retaliation never enter
        // the flight controller.
        if (mind.combatPursuit && commanderBody) {
            NullBrain.Intent flightIntent = flight.pursue(body, handle, target, distance3d, dx, dz, now);
            if (flightIntent != null) {
                brain.raiseShield(handle, mind, false);
                mind.shieldUp = false;
                cancelBow(mind, handle);
                lastNote = handle.isGliding() ? "Commander Elytra glide at " + (int) distance3d
                        : "Commander Elytra takeoff at " + (int) distance3d;
                return flightIntent;
            }
        }

        // A Wind Charge is a real, non-blocking vanilla projectile. It is
        // available only to an explicitly ordered pursuer; retaliation never
        // starts a ranged action or approaches its target.
        if (mind.combatPursuit && dist > Math.max(reach(), 4.0D)
                && dist <= BOW_MIN_DISTANCE && now >= mind.nextWindChargeTick
                && hasWindCharge(handle) && throwWindCharge(handle, target)) {
            mind.nextWindChargeTick = now + 40L;
            brain.raiseShield(handle, mind, false);
            cancelBow(mind, handle);
            lastNote = "threw wind charge at " + (int) dist;
            return intent;
        }

        // Ranged.
        if (v3.bows() && dist > BOW_MIN_DISTANCE && hasBowAndArrow(handle)) {
            lastNote = "shooting at " + (int) dist;
            brain.raiseShield(handle, mind, false);
            return bow(body, mind, handle, target, pos, intent, now);
        }
        cancelBow(mind, handle);
        PvpArsenal.Technique technique = commanderBody
                ? planCommanderTechnique(handle, target, dist) : PvpArsenal.Technique.DISENGAGE;
        if (commanderBody) {
            lastCommanderTechnique = technique;
        }
        boolean useMace = commanderBody && PvpArsenal.usesMaceForAttack(technique)
                && dist <= reach() + 0.5D;
        holdMeleeWeapon(handle, useMace);

        float cooldown = attackCooldown(handle, mind, now);
        double reach = reach();
        float gate = threshold();
        // A shield that is up stays up: a player cannot swing and block at the
        // same time, and a guard who drops his guard to take a free swing is
        // not guarding.
        boolean ready = SwingCadence.ready(cooldown);
        boolean blocking = mind.shieldUp || holdingShield(handle);
        if (ready && mind.shieldUp) {
            // The brain's own guard comes down on the tick it means to hit, as a
            // player's does - and the swing lands on that same tick, because a
            // player who stops blocking to hit does hit in the same motion.
            brain.raiseShield(handle, mind, false);
            mind.shieldUp = false;
            blocking = holdingShield(handle);
        }
        if (mind.combatPursuit && dist > reach - 0.3D) {
            // Close the gap; running, so the first hit carries sprint knockback.
            // No shield on the way in: a raised shield is a fifth of a body's
            // speed, and a Null that blocks while it charges never arrives.
            brain.raiseShield(handle, mind, false);
            mind.shieldUp = false;
            intent.dx = dx / dist;
            intent.dz = dz / dist;
            intent.gait = dist > 4.0D ? NullBody.GAIT_SPRINT : NullBody.GAIT_RUN;
            // P-02: swing as soon as the meter is worth it, not only at full.
            if (ready && strikeAllowed(handle, target)) {
                strike(handle, mind, target, now, fallingFor(body));
            } else {
                lastNote = "closing to " + String.format(java.util.Locale.ROOT, "%.1f", dist)
                        + (ready ? "" : " (cooldown " + String.format(java.util.Locale.ROOT, "%.2f", cooldown)
                        + " < " + gate + ")");
            }
            return intent;
        }

        // Ordered fighters strafe at 2-3 blocks. Retaliation never moves toward
        // (or circles) its attacker, even when the attacker is already in reach.
        if (mind.combatPursuit) {
            if (now >= mind.nextStrafeFlip) {
                mind.strafeDir = -mind.strafeDir;
                mind.nextStrafeFlip = now + 30 + (Math.abs(mind.id.hashCode()) % 25);
            }
            double nx = dx / Math.max(1.0e-6, dist);
            double nz = dz / Math.max(1.0e-6, dist);
            double radial = (dist - STRAFE_DISTANCE) * 0.8D;
            intent.dx = -nz * mind.strafeDir * 0.6D + nx * radial;
            intent.dz = nx * mind.strafeDir * 0.6D + nz * radial;
            intent.gait = NullBody.GAIT_RUN;
        }

        if (ready && dist <= reach && !blocking) {
            // P-01: the strike still has to be legal - inside reach AND with a
            // clear line of sight. A Null that cannot see its target does not
            // swing at it, and it certainly does not damage it.
            if (!strikeAllowed(handle, target)) {
                reachRefusals++;
                mind.critJumped = false;
                lastNote = "reach gate refused at "
                        + String.format(java.util.Locale.ROOT, "%.1f", dist) + " (reach "
                        + String.format(java.util.Locale.ROOT, "%.1f", reach) + ")";
                brain.raiseShield(handle, mind, v3.shields() && !mind.critJumped);
                return intent;
            }
            boolean falling = fallingFor(body);
            if (v3.crits() && SwingCadence.critDue(sinceCrit + 1, true) && !mind.critJumped
                    && body.onGround() && !body.inWater()) {
                // Jump now, strike on the way down: vanilla's critical hit.
                brain.raiseShield(handle, mind, false);
                intent.jump = true;
                mind.critJumped = true;
                mind.critJumpTick = now;
            } else if (mind.critJumped) {
                if (falling || now - mind.critJumpTick > 14) {
                    strike(handle, mind, target, now, falling);
                    mind.critJumped = false;
                }
            } else {
                strike(handle, mind, target, now, falling);
            }
        } else {
            lastNote = ready ? "out of reach at " + String.format(java.util.Locale.ROOT, "%.1f", dist)
                    : "cooldown " + String.format(java.util.Locale.ROOT, "%.2f", cooldown);
            brain.raiseShield(handle, mind, v3.shields() && cooldown < gate && !mind.critJumped);
        }
        return intent;
    }

    /**
     * Uses only a potion that fits the current explicit fight state: Regeneration
     * below 70% health, Strength otherwise. Drinking is never attempted in melee,
     * while airborne, while gliding, or without an explicit pursuit order.
     */
    private boolean drinkCombatPotion(NullBody body, Mind mind, Player handle, double distance, long now) {
        if (body == null || mind == null || handle == null || !mind.combatPursuit || mind.eating()
                || now < mind.nextEatAllowed || !handle.isOnGround() || handle.isGliding()
                || flight.active(mind.id) || distance < 5.0D || distance > 18.0D) {
            return false;
        }
        double max = NullBrain.maxHealth(handle);
        if (max <= 0.0D) {
            return false;
        }
        PlayerInventory inventory = handle.getInventory();
        boolean needsRegeneration = body.health() < max * 0.70D;
        int slot;
        if (needsRegeneration) {
            if (handle.hasPotionEffect(PotionEffectType.REGENERATION)) {
                return false;
            }
            slot = KitItems.potionSlot(inventory, "strong_regeneration", "regeneration");
            if (slot < 0) {
                return false;
            }
        } else {
            if (handle.hasPotionEffect(PotionEffectType.STRENGTH)) {
                return false;
            }
            slot = KitItems.potionSlot(inventory, "strong_strength", "strength");
            if (slot < 0) {
                return false;
            }
        }
        if (handle.isHandRaised()) {
            handle.clearActiveItem();
        }
        mind.slotBeforeEating = inventory.getHeldItemSlot();
        if (Bodies.hold(handle, slot) < 0) {
            mind.slotBeforeEating = -1;
            return false;
        }
        handle.startUsingItem(EquipmentSlot.HAND);
        mind.eatingUntil = now + 45L;
        mind.nextEatAllowed = now + 120L;
        mind.shieldUp = false;
        return true;
    }

    /**
     * How loaded the attack meter is, 0..1.
     *
     * <p>Vanilla's own meter is the truth when it is moving; a body whose meter
     * is not advancing (a body whose NMS tick does not run it) would otherwise
     * stand there at its floor and never swing. So the cadence is also counted
     * here, from the weapon's own cooldown, and the slower of the two wins: a
     * Null can never swing faster than its weapon allows.</p>
     */
    private float attackCooldown(Player handle, Mind mind, long now) {
        float reported = handle.getAttackCooldown();
        int weapon = weaponCooldownTicks(handle);
        if (weapon <= 0) {
            return reported;
        }
        long since = now - mind.lastSwingTick;
        float counted = Math.max(0.0F, Math.min(1.0F, since / (float) weapon));
        return Math.max(reported, counted);
    }

    /** Ticks to a full cooldown for the weapon in hand (a sword is ~12). */
    private int weaponCooldownTicks(Player handle) {
        float speed = 4.0F;
        try {
            org.bukkit.attribute.AttributeInstance attribute =
                    handle.getAttribute(org.bukkit.attribute.Attribute.ATTACK_SPEED);
            if (attribute != null && attribute.getValue() > 0.0D) {
                speed = (float) attribute.getValue();
            }
        } catch (Throwable ignored) {
            // The vanilla default (4.0 attacks per second) is a fine answer.
        }
        return Math.max(2, Math.round(20.0F / speed));
    }

    /** True when this body currently has a shield up, whoever raised it. */
    private boolean holdingShield(Player handle) {
        return handle.isHandRaised() && handle.getActiveItem() != null
                && handle.getActiveItem().getType() == Material.SHIELD;
    }

    /**
     * True when vanilla would call this a falling strike (the crit condition).
     *
     * <p>A body off the ground is a falling body. Measuring its downward
     * velocity and its fall distance as well looked precise and was not: those
     * two readings can lag a tick behind the body itself, so a crit jump was
     * waited out instead of struck on the way down - which is a fighter that
     * jumps and never lands the blow it jumped for.</p>
     */
    private boolean fallingFor(NullBody body) {
        return !body.onGround();
    }

    /** One vanilla swing: arm swing, then {@code Player#attack}. */
    private void strike(Player handle, Mind mind, LivingEntity target, long now, boolean falling) {
        if (handle.isHandRaised()) {
            handle.clearActiveItem();
        }
        mind.shieldUp = false;
        handle.swingMainHand();
        handle.attack(target);
        mind.nextAttackTick = now + 2;
        mind.lastSwingTick = now;
        // P-02 accounting: swings and crits, so the rate is measured not assumed.
        swings++;
        boolean critical = falling && handle.getAttackCooldown() < 0.9F;
        if (critical) {
            crits++;
            sinceCrit = 0;
        } else {
            sinceCrit++;
        }
    }

    /** Draw, aim with lead and arc, release. */
    private NullBrain.Intent bow(NullBody body, Mind mind, Player handle, LivingEntity target, Vec3d pos,
                                 NullBrain.Intent intent, long now) {
        PlayerInventory inv = handle.getInventory();
        if (inv.getItemInMainHand() == null || inv.getItemInMainHand().getType() != Material.BOW) {
            int slot = Bodies.find(inv, Material.BOW);
            if (slot < 0) {
                return intent;
            }
            Bodies.hold(handle, slot);
            mind.bowDrawStart = -1L;
            return intent;
        }
        if (mind.bowDrawStart < 0) {
            handle.startUsingItem(EquipmentSlot.HAND);
            mind.bowDrawStart = now;
        }
        Ballistics.Aim aim = aimAt(handle, target);
        double[] point = aim.lookPoint(pos.x(), pos.y() + 1.62D, pos.z(), 10.0D);
        intent.look = new Vec3d(point[0], point[1], point[2]);
        intent.gait = NullBody.GAIT_STOP;
        /*
         * P-05: imperfect aim. The firing solution above is exactly right, which
         * is the bug - a Null never missed. Three separate imperfections are
         * applied to every shot: an angular error, a reaction delay before the
         * string is loosed, and beyond 12 blocks a chance of missing outright
         * (the aim is thrown wide instead of cancelled, so a real arrow flies
         * and a real miss is visible).
         */
        if (mind.aimReadyTick < 0) {
            mind.aimReadyTick = now + AimSkill.reactionTicks(aimSkill(), random.nextDouble());
            mind.aimMiss = AimSkill.fullMiss(distTo(target, pos), aimSkill(), random.nextDouble());
            mind.aimYawError = AimSkill.angleErrorDeg(aimSkill(), random.nextDouble() * 2.0D - 1.0D);
            mind.aimPitchError = AimSkill.angleErrorDeg(aimSkill(), random.nextDouble() * 2.0D - 1.0D);
        }
        double aimYaw = aim.yaw() + mind.aimYawError + (mind.aimMiss ? mind.aimYawError * 3.0D : 0.0D);
        double aimPitch = aim.pitch() + mind.aimPitchError;
        // Vanilla shoots along the entity yaw (the travel frame), not the head.
        boolean aligned = Math.abs(wrap((float) (body.bodyYaw() - aimYaw))) < 2.5F
                && Math.abs(body.pitch() - aimPitch) < 2.5F;
        if (now - mind.bowDrawStart >= 22 && aligned && now >= mind.aimReadyTick) {
            body.releaseUseItem();
            mind.bowDrawStart = -1L;
            mind.aimReadyTick = -1L;
            aimedShots++;
        }
        return intent;
    }

    /** Last seen position of each aimed-at target: {x, y, z, tick}. */
    private final java.util.Map<UUID, double[]> lastSeen = new java.util.HashMap<>();
    private final java.util.Map<UUID, Vector> seenVelocity = new java.util.HashMap<>();

    /**
     * How fast a target really moves, in blocks per tick, from its position one
     * tick ago. An entity's stored velocity is the value after ground friction -
     * about half of what it actually covers per tick while walking - so leading
     * with it would aim well behind a runner.
     */
    Vector observedVelocity(LivingEntity target) {
        Location at = target.getLocation();
        long now = brain.now();
        double[] last = lastSeen.get(target.getUniqueId());
        Vector velocity = seenVelocity.get(target.getUniqueId());
        if (last == null || now - (long) last[3] > 5) {
            velocity = target.getVelocity();
        } else if (now > (long) last[3]) {
            double dt = now - last[3];
            velocity = new Vector((at.getX() - last[0]) / dt, (at.getY() - last[1]) / dt, (at.getZ() - last[2]) / dt);
            seenVelocity.put(target.getUniqueId(), velocity);
        }
        if (last == null || now > (long) last[3]) {
            lastSeen.put(target.getUniqueId(), new double[] {at.getX(), at.getY(), at.getZ(), now});
        }
        if (lastSeen.size() > 256) {
            lastSeen.clear();
            seenVelocity.clear();
        }
        return velocity == null ? new Vector() : velocity;
    }

    /** The firing solution for a fully drawn bow at this target. */
    public Ballistics.Aim aimAt(Player handle, LivingEntity target) {
        Location eye = handle.getEyeLocation();
        Location at = target.getLocation();
        Vector velocity = observedVelocity(target);
        // P-05: the lead is wrong too. A perfect lead is a perfect predictor;
        // a Null is not one, so the lead time carries a skill-derived error.
        double leadError = AimSkill.leadErrorFraction(aimSkill(), random.nextDouble() * 2.0D - 1.0D);
        double targetCentreY = at.getY() + target.getHeight() * 0.6D;
        return Ballistics.solve(eye.getX(), eye.getY(), eye.getZ(), at.getX(), targetCentreY, at.getZ(),
                velocity.getX() * (1.0D + leadError), velocity.getY() * (1.0D + leadError),
                velocity.getZ() * (1.0D + leadError), Ballistics.FULL_DRAW_SPEED);
    }

    /** Flat distance from a position to a target, for the aim model. */
    private static double distTo(LivingEntity target, Vec3d from) {
        Location at = target.getLocation();
        return Math.hypot(at.getX() - from.x(), at.getZ() - from.z());
    }

    private void cancelBow(Mind mind, Player handle) {
        if (mind.bowDrawStart >= 0) {
            mind.bowDrawStart = -1L;
            if (handle.isHandRaised() && handle.getActiveItem() != null
                    && handle.getActiveItem().getType() == Material.BOW) {
                handle.clearActiveItem();
            }
        }
    }

    private static boolean hasBowAndArrow(Player handle) {
        PlayerInventory inv = handle.getInventory();
        return Bodies.find(inv, Material.BOW) >= 0 && (Bodies.find(inv, Material.ARROW) >= 0
                || Bodies.find(inv, Material.SPECTRAL_ARROW) >= 0 || Bodies.find(inv, Material.TIPPED_ARROW) >= 0);
    }

    private boolean isCommander(NullBody body) {
        return body != null && plugin.commander() != null && plugin.commander().body() == body;
    }

    /** Builds the selector's primitive-only snapshot from the Commander's real inventory and target. */
    private PvpArsenal.Technique planCommanderTechnique(Player handle, LivingEntity target, double distance) {
        PlayerInventory inventory = handle.getInventory();
        int maceSlot = Bodies.find(inventory, Material.MACE);
        ItemStack mace = maceSlot < 0 ? null : inventory.getItem(maceSlot);
        AttributeInstance armor = null;
        try {
            armor = target.getAttribute(Attribute.ARMOR);
        } catch (RuntimeException ignored) {
            // Some custom living entities do not expose the vanilla armor attribute.
        }
        double targetArmor = armor == null ? 0.0D : Math.max(0.0D, armor.getValue());
        Vector velocity = handle.getVelocity();
        double fallSpeed = Math.max(0.0D, -velocity.getY() * 20.0D);
        CombatSituation situation = CombatSituation.builder()
                .distanceToTarget(distance)
                .heightAboveTarget(handle.getLocation().getY() - target.getLocation().getY())
                .fallSpeed(fallSpeed)
                .ownHealth(handle.getHealth())
                .targetHealth(target.getHealth())
                .targetArmor(targetArmor)
                .hasMace(mace != null && !mace.getType().isAir())
                .hasElytra(Bodies.count(inventory, Material.ELYTRA) > 0)
                .hasWindCharge(Bodies.count(inventory, Material.WIND_CHARGE) > 0)
                .hasShield(Bodies.count(inventory, Material.SHIELD) > 0)
                .hasBow(Bodies.count(inventory, Material.BOW) > 0)
                .hasCrossbow(Bodies.count(inventory, Material.CROSSBOW) > 0)
                .hasTrident(Bodies.count(inventory, Material.TRIDENT) > 0)
                .hasEnderPearl(Bodies.count(inventory, Material.ENDER_PEARL) > 0)
                .hasWaterBucket(Bodies.count(inventory, Material.WATER_BUCKET) > 0)
                .elytraDeployed(handle.isGliding())
                .targetBlocking(target instanceof Player && ((Player) target).isBlocking())
                .inRain(handle.getWorld().hasStorm())
                .inWater(handle.isInWater())
                .fireworks(Bodies.count(inventory, Material.FIREWORK_ROCKET))
                .maceHasDensity(KitItems.level(mace, "density") > 0)
                .maceHasBreach(KitItems.level(mace, "breach") > 0)
                .maceHasWindBurst(KitItems.level(mace, "wind_burst") > 0)
                .build();
        return plugin.commander().plan(situation);
    }

    private static boolean hasWindCharge(Player handle) {
        return Bodies.find(handle.getInventory(), Material.WIND_CHARGE) >= 0;
    }

    /**
     * Throws one genuine Bukkit Wind Charge toward a target and consumes one
     * item only after a valid projectile has spawned. Wind charges have their
     * own vanilla burst; no explosion or projectile-cancellation hook is added.
     */
    private static boolean throwWindCharge(Player handle, LivingEntity target) {
        PlayerInventory inventory = handle.getInventory();
        int slot = Bodies.find(inventory, Material.WIND_CHARGE);
        if (slot < 0) {
            return false;
        }
        Vector velocity = target.getEyeLocation().toVector()
                .subtract(handle.getEyeLocation().toVector());
        if (velocity.lengthSquared() < 1.0e-6D) {
            return false;
        }
        velocity.normalize().multiply(1.35D);
        Bodies.hold(handle, slot);
        try {
            WindCharge projectile = handle.launchProjectile(WindCharge.class, velocity);
            if (projectile == null || !projectile.isValid()) {
                return false;
            }
            projectile.setIsIncendiary(false);
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == Material.WIND_CHARGE) {
                if (stack.getAmount() <= 1) {
                    inventory.setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - 1);
                }
            }
            handle.swingMainHand();
            return true;
        } catch (RuntimeException launchFailure) {
            return false;
        }
    }

    /** Holds the selected Commander mace tactic, otherwise sword first and axe second. */
    private static void holdMeleeWeapon(Player handle, boolean preferMace) {
        PlayerInventory inv = handle.getInventory();
        ItemStack held = inv.getItemInMainHand();
        if (preferMace) {
            if (held != null && held.getType() == Material.MACE) {
                return;
            }
            int mace = Bodies.find(inv, Material.MACE);
            if (mace >= 0) {
                Bodies.hold(handle, mace);
                return;
            }
        }
        if (held != null && (held.getType().name().endsWith("_SWORD") || held.getType().name().endsWith("_AXE"))) {
            return;
        }
        for (String suffix : new String[] {"_SWORD", "_AXE"}) {
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack != null && stack.getType().name().endsWith(suffix)) {
                    Bodies.hold(handle, i);
                    return;
                }
            }
        }
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0F;
        if (d >= 180.0F) {
            d -= 360.0F;
        }
        if (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    /** Starts a fight on purpose (ordered attack, self test). */
    public void engage(NullBody body, UUID target, int ticks) {
        Mind mind = brain.mind(body);
        if (mind == null || target == null) {
            return;
        }
        // P-04: an order is not a loophole. The owner, the Commander, squad
        // mates and protected players are never valid targets, whoever asks.
        Entity entity = Bukkit.getEntity(target);
        if (entity != null && !brain.mayTarget(body, entity)) {
            return;
        }
        mind.combatTarget = target;
        mind.combatPursuit = true;
        mind.combatUntil = brain.now() + Math.max(20, ticks);
        if (plugin.chatGate() != null) {
            plugin.chatGate().event("combat.ordered", "name", body.profileName(),
                    "target", entity == null ? target.toString().substring(0, 8) : entity.getName());
        }
    }
}
