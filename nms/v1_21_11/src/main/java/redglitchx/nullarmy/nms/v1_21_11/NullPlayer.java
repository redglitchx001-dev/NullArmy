package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The server-authoritative body of a Null on 1.21.11.
 *
 * <h3>Why extend {@code ServerPlayer} at all</h3>
 * ADR-001: one entity means one hitbox, one inventory and one item ledger,
 * which is what spec 1.2 ("the NPC inventory is authoritative") and spec 2.2
 * (server authority) require. A packet-only fake cannot hold a real inventory
 * or participate in authoritative combat (spec 1.5).
 *
 * <h3>What makes a Null actually visible</h3>
 * Two server facts drive the whole design, and both were verified against the
 * Paper 1.21.11 sources rather than assumed:
 * <ol>
 *   <li>{@code ServerLevel.onTrackingStart} adds any {@code ServerPlayer} it is
 *       given to {@code ServerLevel.players} and hands it to
 *       {@code ChunkMap.addEntity}, which creates a {@code TrackedEntity}. That
 *       is what makes nearby clients receive
 *       {@code ClientboundAddEntityPacket} - so registration alone does put a
 *       Null in front of players.</li>
 *   <li>The client <b>drops</b> that packet unless it already holds a player-info
 *       entry for the same UUID ({@code ClientPacketListener
 *       #createEntityFromPacket}: "Server attempted to add player prior to
 *       sending player info"). Normal joins get that entry from
 *       {@code PlayerList.placeNewPlayer}, which a Null never goes through. The
 *       adapter therefore broadcasts
 *       {@code ClientboundPlayerInfoUpdatePacket} <i>before</i> the body is
 *       added to the level, and a remove packet when it goes.</li>
 * </ol>
 *
 * <h3>Connection safety</h3>
 * {@code MinecraftServer.tickChildren} walks {@code level.players()} once per
 * second and calls {@code entityplayer.connection.send(...)}. A Null with a null
 * listener therefore crashed the server tick loop (crash-2026-10-04). Every Null
 * gets a real {@code ServerGamePacketListenerImpl} in its constructor - before
 * registration - whose {@code Connection} has no channel and discards every
 * outbound packet. Nothing is redirected to another player, and nothing is
 * queued: {@code Connection.send} would otherwise park packets in its unbounded
 * {@code pendingActions} queue because a channel-less connection never reports
 * itself as connected.
 *
 * <h3>How movement stays honest</h3>
 * There is no {@code teleport} and no {@code setPos} entry point on
 * {@link NullBody}. The only way to move a Null is {@link #applySteering}, which
 * feeds a bounded force into the entity's real vanilla movement via
 * {@link MoverType#SELF}. The no-teleport rule is enforced by the interface, not
 * by convention. The one exception is {@link #portalTo}, called only by the
 * adapter's verified portal crossing.
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

    /** Hard bound on what a viewer probe records, so a probe cannot grow. */
    private static final int PROBE_PACKET_LIMIT = 1024;

    /** How often a Null drops the chunk queue its own listener never drains. */
    private static final int QUEUE_SWEEP_INTERVAL = 100;

    private final ItemLedger inventory;
    private final V1_21_11Adapter adapter;
    private final Vec3d[] pendingForce = new Vec3d[1];

    /** Non-null only for a viewer probe: outbound packet class names. */
    private final List<String> recordedPackets;

    private int tickFailures;
    private int upkeepFailures;
    private boolean upkeepDisabled;

    NullPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
               VersionAdapter.SpawnRequest request, V1_21_11Adapter adapter) {
        this(server, level, profile, request, adapter, false);
    }

    /**
     * @param recording true for the runtime smoke-test probe, whose outbound
     *     packets are recorded instead of discarded so the pairing path can be
     *     proven end to end without a real client
     */
    NullPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
               VersionAdapter.SpawnRequest request, V1_21_11Adapter adapter,
               boolean recording) {
        super(server, level, profile, ClientInformation.createDefault());
        this.adapter = adapter;
        this.inventory = new ItemLedger(request.inventoryCapacity(), 200);
        this.recordedPackets = recording ? Collections.synchronizedList(new ArrayList<>()) : null;

        // The server's player work can send packets to this entity before its
        // first entity tick - MinecraftServer.tickChildren does it every second
        // for everything in ServerLevel.players. Install a non-null listener
        // before registration; packets for the client that does not exist are
        // discarded, never queued and never redirected.
        this.connection = new ServerGamePacketListenerImpl(server,
                new NullConnection(this.recordedPackets), this,
                CommonListenerCookie.createInitial(profile, false));
    }

    /**
     * A real NMS connection with no channel: outbound packets are dropped on the
     * floor (or recorded, for a viewer probe).
     *
     * <p>Every entry point that could otherwise park work in
     * {@code Connection.pendingActions} is overridden, because that queue is
     * unbounded and only ever drained when a channel is connected.</p>
     *
     * <p>The superclass is fully qualified because ServerPlayer inherits a
     * nested {@code WaypointTransmitter.Connection} with the same simple name.</p>
     */
    private static final class NullConnection extends net.minecraft.network.Connection {

        private final List<String> sink;

        private NullConnection(List<String> sink) {
            super(PacketFlow.SERVERBOUND);
            this.sink = sink;
        }

        @Override
        public void send(Packet<?> packet) {
            // A Null has no client. Do not queue or redirect its packets.
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener) {
            // No channel exists, so there is no send future to complete.
            record(packet);
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
            // Explicit-flush sends are discarded too.
            record(packet);
        }

        @Override
        public void runOnceConnected(java.util.function.Consumer<net.minecraft.network.Connection> action) {
            // The base implementation appends to pendingActions and waits for a
            // channel that will never exist. There is nothing to run and nothing
            // to remember.
        }

        @Override
        public void flushChannel() {
            // Same story: the base implementation queues a flush forever.
        }

        private void record(Packet<?> packet) {
            if (sink == null || packet == null) {
                return;
            }
            synchronized (sink) {
                if (sink.size() < PROBE_PACKET_LIMIT) {
                    sink.add(packet.getClass().getSimpleName());
                }
            }
        }
    }

    // ------------------------------------------------------------ NullBody impl

    @Override
    public int id() { return getId(); }

    @Override
    public String profileName() { return getGameProfile().name(); }

    /** The profile UUID this body is registered under. */
    public UUID profileId() { return getUUID(); }

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

    /**
     * Alive means: not removed, above zero health, <b>and</b> still a valid
     * entity of this level.
     *
     * <p>{@code valid} is CraftBukkit's marker, cleared when the body leaves the
     * world - including when its chunk unloads, which for a {@code noSave()}
     * player entity means it is gone for good. Reporting such a body as alive
     * would leave a squad list full of ghosts, so it is part of the test.</p>
     */
    @Override
    public boolean isAlive() {
        return super.isAlive() && !isRemoved() && this.valid;
    }

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
     * <p>This is <b>not</b> ordinary movement: the only caller is
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

    // ------------------------------------------------------------- diagnostics

    /** True when the packet listener is installed. Must always be true. */
    public boolean packetListenerReady() { return this.connection != null; }

    /** True for the smoke-test probe, whose outbound packets are recorded. */
    public boolean isProbe() { return recordedPackets != null; }

    /** Packet class names this body's listener was asked to send. */
    public List<String> recordedPackets() {
        if (recordedPackets == null) {
            return Collections.emptyList();
        }
        synchronized (recordedPackets) {
            return Collections.unmodifiableList(new ArrayList<>(recordedPackets));
        }
    }

    /** Empties the probe's record. */
    public void clearRecordedPackets() {
        if (recordedPackets != null) {
            synchronized (recordedPackets) {
                recordedPackets.clear();
            }
        }
    }

    /** The level this body belongs to, or null when it has none. */
    ServerLevel serverLevelOrNull() {
        try {
            return level() instanceof ServerLevel ? (ServerLevel) level() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------- ticking

    /**
     * Drives movement through vanilla physics.
     *
     * <p>Overridden because a server-only {@code ServerPlayer} must not run
     * normal client synchronization: {@code ServerPlayer.tick()} sends chunk
     * cache centers, flushes menus, triggers advancements and writes player
     * statistics, none of which apply to a body with no client and no account.
     * What a Null does keep is real world interaction ({@code baseTick}: fire,
     * water, suffocation) and real collision-respecting movement.</p>
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

    /**
     * The player-list upkeep pass.
     *
     * <p>{@code PlayerList.tick()} calls this for listed players only and a Null
     * is never listed. It is overridden anyway so that anything which does reach
     * it (another plugin, a future Paper build) gets the Null's own tick rather
     * than food, statistics, keep-alives and playerdata writes for an account
     * that does not exist.</p>
     */
    @Override
    public void doTick() {
        tick();
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

        upkeep();
        worldInteraction();

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

        // The chunk sender of a Null's listener is never drained (that happens in
        // ServerGamePacketListenerImpl#tick, which the server runs for listed
        // players only). Empty it so nothing accumulates for the session.
        if (this.tickCount % QUEUE_SWEEP_INTERVAL == 0) {
            Tracking.clearPendingChunks(this.connection);
        }
    }

    /**
     * The timers the server would normally decay in {@code Player#tick}.
     *
     * <p>Without this a Null that is hurt once keeps {@code invulnerableTime} at
     * its hurt value forever and becomes permanently damage-immune, and a dead
     * body never advances its death animation.</p>
     */
    private void upkeep() {
        if (upkeepDisabled) {
            return;
        }
        try {
            if (this.invulnerableTime > 0) {
                this.invulnerableTime--;
            }
            if (this.hurtTime > 0) {
                this.hurtTime--;
            }
            if (this.deathTime < 20 && getHealth() <= 0.0F) {
                this.deathTime++;
            }
        } catch (Throwable t) {
            upkeepFailures++;
            if (upkeepFailures >= TICK_FAILURE_LIMIT) {
                upkeepDisabled = true;
                org.bukkit.Bukkit.getLogger().warning("[NullArmy] Null upkeep disabled after "
                        + upkeepFailures + " failures: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
    }

    /**
     * Real interaction with the world: fire, water, suffocation, freeze.
     *
     * <p>{@code Entity.baseTick()} also runs the portal countdown, which is
     * neutralised here (see {@link #handlePortal()}), so standing in one of the
     * plugin's arrival portals can never send a Null to the Nether.</p>
     */
    private void worldInteraction() {
        if (upkeepDisabled) {
            return;
        }
        try {
            baseTick();
        } catch (Throwable t) {
            upkeepFailures++;
            if (upkeepFailures >= TICK_FAILURE_LIMIT) {
                upkeepDisabled = true;
                org.bukkit.Bukkit.getLogger().warning("[NullArmy] Null world interaction disabled"
                        + " after " + upkeepFailures + " failures: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
    }

    // --------------------------------------------------------- portal containment

    /**
     * A Null never enters a portal on its own.
     *
     * <p>The plugin builds temporary arrival portals out of real portal blocks.
     * A Null standing in one must not be transported anywhere: the only
     * relocation a Null ever makes is the adapter's verified portal crossing.</p>
     */
    @Override
    public void setAsInsidePortal(Portal portal, BlockPos pos) {
        // Deliberately empty.
    }

    /** The portal countdown. Empty for the same reason. */
    @Override
    protected void handlePortal() {
        // Deliberately empty.
    }

    /** A Null cannot use portals, with or without passengers. */
    @Override
    public boolean canUsePortal(boolean ignorePassenger) {
        return false;
    }

    // ------------------------------------------------------------------- cleanup

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

    /**
     * Equips this body, replacing what is in the listed slots.
     *
     * <p>Applying the same loadout twice must not duplicate anything: every slot
     * named here is <b>set</b>, never added to. Slots the caller does not name
     * are left alone.</p>
     */
    @Override
    public void setLoadout(List<LoadoutSlot> slots) {
        org.bukkit.inventory.PlayerInventory inv;
        try {
            inv = getBukkitEntity().getInventory();
        } catch (Throwable t) {
            return;
        }
        if (inv == null) {
            return;
        }
        if (slots == null || slots.isEmpty()) {
            inv.clear();
            return;
        }
        for (LoadoutSlot slot : slots) {
            if (slot == null) {
                continue;
            }
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
                case LoadoutSlot.SLOT_BOOTS:
                    inv.setBoots(stack);
                    break;
                case LoadoutSlot.SLOT_LEGGINGS:
                    inv.setLeggings(stack);
                    break;
                case LoadoutSlot.SLOT_CHESTPLATE:
                    inv.setChestplate(stack);
                    break;
                case LoadoutSlot.SLOT_HELMET:
                    inv.setHelmet(stack);
                    break;
                case LoadoutSlot.SLOT_OFFHAND:
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
        // The body is already in the world, so run vanilla's own equipment pass:
        // it applies the armour attribute modifiers (an iron chestplate has to
        // actually protect) and broadcasts ClientboundSetEquipmentPacket to
        // whoever is tracking this Null. Without it a client keeps rendering the
        // old kit and the armour is decoration only.
        Tracking.syncEquipment(this);
    }
}
