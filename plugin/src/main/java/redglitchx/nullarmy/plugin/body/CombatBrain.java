package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.combat.Ballistics;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.V3Settings;

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
 *   <li><b>Bows.</b> Beyond 8 blocks it draws, aims with lead and arc
 *       ({@link Ballistics}) and releases a real arrow.</li>
 *   <li><b>Retreat.</b> Below 30 % health it backs off and eats or drinks.</li>
 * </ul>
 *
 * <p>Who to fight is decided by the rules: an explicit order, or retaliation
 * when {@code combat.retaliate} is on. With {@code combat.initiate: false} (the
 * default) a Null never starts a fight with anyone on its own.</p>
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

    CombatBrain(NullArmyPlugin plugin, NullBrain brain) {
        this.plugin = plugin;
        this.brain = brain;
    }

    /** True while the mind has a living target it is allowed to fight. */
    boolean active(Mind mind, Player handle) {
        V3Settings v3 = brain.settings();
        if (v3 == null || !v3.combatEnabled() || handle == null) {
            mind.combatTarget = null;
            return false;
        }
        if (mind.combatTarget == null && v3.initiate()) {
            LivingEntity hostile = nearestHostile(handle);
            if (hostile != null) {
                mind.combatTarget = hostile.getUniqueId();
                mind.combatUntil = brain.now() + 20L * 20L;
            }
        }
        if (mind.combatTarget == null) {
            return false;
        }
        Entity entity = Bukkit.getEntity(mind.combatTarget);
        if (!(entity instanceof LivingEntity) || entity.isDead() || entity.getWorld() != handle.getWorld()
                || entity.getLocation().distanceSquared(handle.getLocation()) > GIVE_UP_DISTANCE * GIVE_UP_DISTANCE
                || brain.now() > mind.combatUntil) {
            endFight(mind, handle);
            return false;
        }
        return true;
    }

    private void endFight(Mind mind, Player handle) {
        mind.combatTarget = null;
        mind.critJumped = false;
        if (mind.bowDrawStart >= 0 && handle != null) {
            handle.clearActiveItem();
        }
        mind.bowDrawStart = -1L;
        brain.raiseShield(handle, mind, false);
    }

    private LivingEntity nearestHostile(Player handle) {
        LivingEntity best = null;
        double bestDist = 12.0D * 12.0D;
        for (Entity near : handle.getNearbyEntities(12.0D, 4.0D, 12.0D)) {
            if (near instanceof Monster && !near.isDead()) {
                double d = near.getLocation().distanceSquared(handle.getLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = (LivingEntity) near;
                }
            }
        }
        return best;
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
        NullBrain.Intent intent = new NullBrain.Intent();
        intent.look = new Vec3d(eyeAt.getX(), eyeAt.getY() - 0.2D, eyeAt.getZ());
        intent.lookHeadOnly = false;

        // Retreat and heal when badly hurt.
        double max = NullBrain.maxHealth(handle);
        if (body.health() < max * 0.3D) {
            brain.raiseShield(handle, mind, false);
            cancelBow(mind, handle);
            if (dist < 6.0D) {
                intent.dx = -dx / Math.max(1.0e-6, dist);
                intent.dz = -dz / Math.max(1.0e-6, dist);
                intent.gait = NullBody.GAIT_SPRINT;
                intent.lookHeadOnly = true;
            } else {
                brain.maybeEat(body, mind, handle);
            }
            return intent;
        }

        // Ranged.
        if (v3.bows() && dist > BOW_MIN_DISTANCE && hasBowAndArrow(handle)) {
            brain.raiseShield(handle, mind, false);
            return bow(body, mind, handle, target, pos, intent, now);
        }
        cancelBow(mind, handle);
        holdMeleeWeapon(handle);

        float cooldown = handle.getAttackCooldown();
        if (dist > MELEE_REACH - 0.3D) {
            // Close the gap; sprinting, so the first hit carries sprint knockback.
            intent.dx = dx / dist;
            intent.dz = dz / dist;
            intent.gait = dist > 4.0D ? NullBody.GAIT_SPRINT : NullBody.GAIT_RUN;
            if (dist <= MELEE_REACH && cooldown >= 0.95F && handle.isSprinting()) {
                strike(handle, mind, target, now);
            }
            brain.raiseShield(handle, mind, v3.shields() && cooldown < 0.6F && dist < 5.0D);
            return intent;
        }

        // Strafe at 2-3 blocks.
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

        if (cooldown >= 0.95F && dist <= MELEE_REACH) {
            if (v3.crits() && !mind.critJumped && body.onGround() && !body.inWater()) {
                // Jump now, strike on the way down: vanilla's critical hit.
                brain.raiseShield(handle, mind, false);
                intent.jump = true;
                mind.critJumped = true;
                mind.critJumpTick = now;
            } else if (mind.critJumped) {
                boolean falling = !body.onGround() && body.velocity().y() < 0.0D && body.fallDistance() > 0.0D;
                if (falling || now - mind.critJumpTick > 14) {
                    strike(handle, mind, target, now);
                    mind.critJumped = false;
                }
            } else {
                strike(handle, mind, target, now);
            }
        } else {
            brain.raiseShield(handle, mind, v3.shields() && cooldown < 0.6F && !mind.critJumped);
        }
        return intent;
    }

    /** One vanilla swing: arm swing, then {@code Player#attack}. */
    private void strike(Player handle, Mind mind, LivingEntity target, long now) {
        if (handle.isHandRaised()) {
            handle.clearActiveItem();
        }
        mind.shieldUp = false;
        handle.swingMainHand();
        handle.attack(target);
        mind.nextAttackTick = now + 2;
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
        intent.lookHeadOnly = false;
        intent.gait = NullBody.GAIT_STOP;
        // Vanilla shoots along the entity yaw (the travel frame), not the head.
        boolean aligned = Math.abs(wrap(body.bodyYaw() - aim.yaw())) < 2.5F
                && Math.abs(body.pitch() - aim.pitch()) < 2.5F;
        if (now - mind.bowDrawStart >= 22 && aligned) {
            body.releaseUseItem();
            mind.bowDrawStart = -1L;
        }
        return intent;
    }

    /** The firing solution for a fully drawn bow at this target. */
    public Ballistics.Aim aimAt(Player handle, LivingEntity target) {
        Location eye = handle.getEyeLocation();
        Location at = target.getLocation();
        Vector velocity = target.getVelocity();
        if (plugin.adapter() != null && plugin.adapter().isNullEntity(target.getUniqueId())) {
            NullBody body = plugin.adapter().bodyOf(target.getUniqueId());
            if (body != null) {
                Vec3d v = body.velocity();
                velocity = new Vector(v.x(), v.y(), v.z());
            }
        }
        double targetCentreY = at.getY() + target.getHeight() * 0.6D;
        return Ballistics.solve(eye.getX(), eye.getY(), eye.getZ(), at.getX(), targetCentreY, at.getZ(),
                velocity.getX(), velocity.getY(), velocity.getZ(), Ballistics.FULL_DRAW_SPEED);
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

    /** Sword first, then axe, in hand. */
    private static void holdMeleeWeapon(Player handle) {
        PlayerInventory inv = handle.getInventory();
        ItemStack held = inv.getItemInMainHand();
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
        if (mind != null) {
            mind.combatTarget = target;
            mind.combatUntil = brain.now() + Math.max(20, ticks);
        }
    }
}
