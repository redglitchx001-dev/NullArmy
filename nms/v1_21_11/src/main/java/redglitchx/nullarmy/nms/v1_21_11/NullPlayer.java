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
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;

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

    private final ItemLedger inventory;
    private final V1_21_11Adapter adapter;
    private final Vec3d[] pendingForce = new Vec3d[1];

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
    public String profileName() { return getGameProfile().getName(); }

    @Override
    public Vec3d position() {
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
        Vec3d from = position();
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

    @Override
    public ItemLedger inventory() { return inventory; }

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
        Vec3d force = pendingForce[0];
        pendingForce[0] = null;

        if (force != null && !isRemoved()) {
            // Blend toward the desired velocity, then clamp - never snap.
            Vec3d desired = force.clampLength(MAX_SPEED);
            Vec3 current = getDeltaMovement();
            Vec3 blended = current.add(new Vec3(desired.x(), desired.y(), desired.z()))
                    .scale(0.5)
                    .normalize()
                    .scale(Math.min(MAX_SPEED, desired.length()));
            setDeltaMovement(blended);
        }

        // Real collision-respecting movement. This is the only thing that ever
        // changes a Null's position.
        move(MoverType.SELF, getDeltaMovement());
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
