package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * The server-authoritative body of a Null on 1.21.11.
 *
 * <p><b>STATUS: UNVERIFIED.</b> Never compiled, never run - the build
 * environment has no JDK and no dev bundle (blocker B-1). Every NMS signature
 * here is a hypothesis for Phase 1 verification V-04.</p>
 *
 * <h3>Why extend {@code ServerPlayer} at all</h3>
 * ADR-001: one entity means one hitbox, one inventory and one item ledger,
 * which is what spec 1.2 ("the NPC inventory is authoritative") and spec 2.2
 * (server authority) require. A packet-only fake cannot hold a real inventory
 * or participate in authoritative combat (spec 1.5).
 *
 * <h3>How movement stays honest</h3>
 * There is no {@code teleport} and no {@code setPos} entry point on
 * {@link NullBody}. The only way to move a Null is {@link #applySteering},
 * which feeds a bounded force into the entity's real vanilla movement via
 * {@link MoverType#SELF}. So the no-teleport rule is enforced by the
 * interface, not by convention.
 *
 * <h3>Outstanding NMS risk</h3>
 * A {@code ServerPlayer} normally has a non-null {@code connection}, and parts
 * of {@code ServerPlayer#tick} assume one. Overriding {@link #tick()} below is
 * the mitigation, but this is the single riskiest unverified area of the
 * adapter (R-01, R-02, R-03). Confirm against a real server before trusting it.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullPlayer extends ServerPlayer implements NullBody {

    /** Blocks per tick. Bounded so no steering force can exceed legal speed. */
    private static final double MAX_SPEED = 0.28;

    /**
     * A body that keeps throwing from {@code tick()} is removed rather than
     * allowed to keep throwing inside the server's entity loop. Three strikes:
     * one failure may be transient (a chunk boundary, a chunk unload race),
     * three in a row is a broken body.
     */
    private static final int TICK_FAILURE_LIMIT = 3;

    /** Vanilla gravity per tick. Applied so an airborne Null really falls. */
    private static final double GRAVITY = 0.08D;

    /** Vanilla terminal fall speed: the air drop must not exceed real physics. */
    private static final double TERMINAL_FALL_SPEED = 3.92D;

    /** Anything smaller than this is noise, and normalising it yields NaN. */
    private static final double MIN_SPEED = 1.0e-4D;

    private final ItemLedger inventory;
    private final V1_21_11Adapter adapter;
    private final Vec3d[] pendingForce = new Vec3d[1];
    private int tickFailures;

    NullPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
               VersionAdapter.SpawnRequest request, V1_21_11Adapter adapter) {
        super(server, level, profile, ClientInformation.createDefault());
        this.adapter = adapter;
        this.inventory = new ItemLedger(request.inventoryCapacity(), 200);
    }

    // ------------------------------------------------------------ NullBody impl

    @Override
    public int id() { return getId(); }

    @Override
    public String profileName() { return getGameProfile().name(); }

    @Override
    public Vec3d bodyPosition() {
        return new Vec3d(getX(), getY(), getZ());
    }

    @Override
    public void applySteering(Vec3d force) {
        if (force == null) {
            return;
        }
        pendingForce[0] = force;
    }

    @Override
    public void lookAt(Vec3d target) {
        if (target == null) {
            return;
        }
        Vec3d from = bodyPosition();
        Vec3d delta = target.sub(from);
        if (delta.horizontalLength() < 1e-6) {
            return;
        }
        double yaw = Math.toDegrees(Math.atan2(-delta.x(), delta.z()));
        double pitch = Math.toDegrees(-Math.atan2(delta.y(), delta.horizontalLength()));
        setYRot((float) yaw);
        setXRot((float) pitch);
    }

    @Override
    public boolean isAlive() { return super.isAlive() && !isRemoved(); }

    @Override
    public double health() { return getHealth(); }

    /**
     * Tops the body up without ever exceeding its maximum, and without
     * reviving a dead one. A negative or absurd amount is ignored.
     */
    @Override
    public void heal(double amount) {
        if (amount <= 0.0D || !isAlive()) {
            return;
        }
        try {
            float max = getMaxHealth();
            float now = getHealth();
            float target = (float) Math.min(max, now + amount);
            if (target > now) {
                setHealth(target);
            }
        } catch (Throwable ignored) {
            // Healing is best-effort: /null heal reports what it could not do.
        }
    }

    @Override
    public ItemLedger inventory() { return inventory; }

    /**
     * Relocates the body to a destination the adapter has already verified.
     *
     * <p>This is <b>not</b> part of ordinary movement: the only caller is
     * {@link V1_21_11Adapter#portalTravel}, which checks the world, the chunk
     * and the collision safety of the destination first, and the player sees
     * portal effects at both ends. Velocity is cleared so the body cannot arrive
     * mid-fall, and a non-finite destination is refused outright.</p>
     *
     * @return true when the body is at the destination
     */
    boolean portalTo(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        try {
            setDeltaMovement(0.0D, 0.0D, 0.0D);
            setPos(x, y, z);
            setDeltaMovement(0.0D, 0.0D, 0.0D);
            return true;
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning(
                    "[NullArmy] portal arrival failed; the Null stays where it was: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    @Override
    public void destroy() {
        if (adapter != null) {
            adapter.forget(this);
        }
        discard();
    }

    // ------------------------------------------------------------------- ticking

    /**
     * Drives movement through vanilla physics.
     *
     * <p>Overridden because a {@code ServerPlayer} with no client connection
     * cannot rely on the normal player tick. We keep only what a Null needs:
     * movement integration and living-entity upkeep.</p>
     *
     * <p><b>UNVERIFIED.</b> The exact set of {@code super} calls needed to keep
     * a connectionless {@code ServerPlayer} stable must be established
     * empirically on a real server.</p>
     */
    @Override
    public void tick() {
        try {
            tickBody();
        } catch (Throwable t) {
            tickFailures++;
            if (tickFailures <= TICK_FAILURE_LIMIT) {
                org.bukkit.Bukkit.getLogger().log(Level.SEVERE,
                        "[NullArmy] a Null failed to tick (" + tickFailures
                                + " time(s)); the server is unaffected: " + t, t);
            }
            if (tickFailures >= TICK_FAILURE_LIMIT) {
                // Remove the body instead of letting it throw again next tick.
                // This is the difference between one lost Null and a crashed
                // server: nothing may keep throwing inside the entity loop.
                destroyQuietly();
            }
        }
    }

    /** The real per-tick work, isolated so a failure can be contained. */
    private void tickBody() {
        if (isRemoved()) {
            return;
        }

        // A position that is already NaN cannot be recovered without a
        // teleport, and NaN coordinates are what actually kills a server:
        // every chunk/block lookup downstream throws or spins. Throw the body
        // away instead - one lost Null, not a dead server.
        if (!Double.isFinite(getX()) || !Double.isFinite(getY()) || !Double.isFinite(getZ())) {
            org.bukkit.Bukkit.getLogger().severe("[NullArmy] a Null reached an invalid"
                    + " position and was removed; the server is unaffected.");
            destroyQuietly();
            return;
        }

        Vec3 delta = getDeltaMovement();
        if (delta == null || !isFinite(delta)) {
            delta = Vec3.ZERO;
        }

        // Gravity, the way vanilla applies it: every tick, and the collision
        // resolution inside move() cancels whatever the ground stops. Without
        // this an airborne Null would hang in the sky for ever and the air
        // drop's real-fall-damage promise would be a lie.
        if (!onGround()) {
            delta = delta.add(0.0D, -GRAVITY, 0.0D);
            if (delta.y < -TERMINAL_FALL_SPEED) {
                delta = new Vec3(delta.x, -TERMINAL_FALL_SPEED, delta.z);
            }
        }

        Vec3d force = pendingForce[0];
        pendingForce[0] = null;

        if (force != null && isFinite(force)) {
            // Blend toward the desired velocity, then clamp - never snap.
            Vec3d desired = force.clampLength(MAX_SPEED);
            double speed = Math.min(MAX_SPEED, desired.length());
            if (speed > MIN_SPEED) {
                Vec3 blended = delta.add(new Vec3(desired.x(), desired.y(), desired.z()))
                        .scale(0.5D);
                double length = blended.length();
                if (length <= MIN_SPEED) {
                    // The blend cancelled itself out; take the desired direction
                    // directly rather than normalising a zero vector (NaN).
                    blended = new Vec3(desired.x(), desired.y(), desired.z()).scale(speed);
                } else {
                    blended = blended.scale(speed / length);
                }
                delta = blended;
            }
        }

        // Last line of defence before touching the world.
        if (!isFinite(delta)) {
            delta = Vec3.ZERO;
        }

        setDeltaMovement(delta);
        // Real collision-respecting movement. This is the only thing that ever
        // changes a Null's position.
        move(MoverType.SELF, delta);
    }

    /** True when every component is a real number, never NaN or infinite. */
    private static boolean isFinite(Vec3 v) {
        return v != null
                && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /** True when every component is a real number, never NaN or infinite. */
    private static boolean isFinite(Vec3d v) {
        return v != null
                && Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    /** Removes this body without letting the cleanup itself throw. */
    private void destroyQuietly() {
        try {
            if (adapter != null) {
                adapter.forget(this);
            }
        } catch (Throwable ignored) {
            // Best effort.
        }
        try {
            discard();
        } catch (Throwable ignored) {
            // Nothing further can be done for this body.
        }
    }

    /**
     * Reads what the body is currently wearing or carrying.
     *
     * <p>This is a read of the real player inventory, so there is exactly one
     * source of truth. Slots that are empty are omitted rather than reported as
     * air.</p>
     */
    @Override
    public List<LoadoutSlot> loadout() {
        List<LoadoutSlot> out = new ArrayList<>();
        org.bukkit.inventory.PlayerInventory inv;
        try {
            inv = getBukkitEntity().getInventory();
        } catch (Throwable t) {
            return Collections.emptyList();
        }
        if (inv == null) {
            return Collections.emptyList();
        }
        addSlot(out, LoadoutSlot.SLOT_BOOTS, inv.getBoots());
        addSlot(out, LoadoutSlot.SLOT_LEGGINGS, inv.getLeggings());
        addSlot(out, LoadoutSlot.SLOT_CHESTPLATE, inv.getChestplate());
        addSlot(out, LoadoutSlot.SLOT_HELMET, inv.getHelmet());
        addSlot(out, LoadoutSlot.SLOT_OFFHAND, inv.getItemInOffHand());
        for (int i = 0; i <= 35; i++) {
            addSlot(out, i, inv.getItem(i));
        }
        return out;
    }

    /** Adds one occupied slot, skipping air and unreadable stacks. */
    private static void addSlot(List<LoadoutSlot> out, int slot, org.bukkit.inventory.ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            org.bukkit.Material type = stack.getType();
            if (type == null || type.isAir()) {
                return;
            }
            out.add(LoadoutSlot.of(slot, type.name(), stack.getAmount()));
        } catch (Throwable ignored) {
            // An unreadable stack is skipped, never fatal.
        }
    }

    @Override
    public void setLoadout(java.util.List<redglitchx.nullarmy.nms.LoadoutSlot> slots) {
        org.bukkit.inventory.PlayerInventory inv = getBukkitEntity().getInventory();
        if (slots == null || slots.isEmpty()) {
            inv.clear();
            return;
        }
        for (redglitchx.nullarmy.nms.LoadoutSlot slot : slots) {
            org.bukkit.Material material = org.bukkit.Material.matchMaterial(slot.material());
            if (material == null || material.isAir()) {
                // Unknown on this server version: skip it rather than fail the summon.
                continue;
            }
            org.bukkit.inventory.ItemStack stack =
                    new org.bukkit.inventory.ItemStack(material, slot.count());

            // A real player inventory is 41 slots: 0-35 storage and hotbar,
            // then 36-40 are NOT part of the main inventory - Bukkit exposes
            // them as dedicated armour/offhand slots. Writing 36-40 with
            // setItem() would silently do nothing, so map them explicitly.
            switch (slot.slot()) {
                case redglitchx.nullarmy.nms.LoadoutSlot.SLOT_BOOTS:
                    inv.setBoots(stack);
                    break;
                case redglitchx.nullarmy.nms.LoadoutSlot.SLOT_LEGGINGS:
                    inv.setLeggings(stack);
                    break;
                case redglitchx.nullarmy.nms.LoadoutSlot.SLOT_CHESTPLATE:
                    inv.setChestplate(stack);
                    break;
                case redglitchx.nullarmy.nms.LoadoutSlot.SLOT_HELMET:
                    inv.setHelmet(stack);
                    break;
                case redglitchx.nullarmy.nms.LoadoutSlot.SLOT_OFFHAND:
                    inv.setItemInOffHand(stack);
                    break;
                default:
                    int index = slot.slot();
                    if (index >= 0 && index < 36) {
                        inv.setItem(index, stack);
                    }
                    break;
            }
        }
    }
}
