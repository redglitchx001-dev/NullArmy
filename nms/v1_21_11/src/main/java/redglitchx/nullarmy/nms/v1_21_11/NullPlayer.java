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
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.Vec3;

import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.BodySettings;
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
 * {@link NullBody}. The only way to move a Null is a movement intent
 * ({@link #setMovement}, or the legacy {@link #applySteering}), which becomes the
 * same strafe/forward input a client sends and is run through vanilla
 * {@code travel}: friction, gravity, collisions, step height, water and ladders
 * are the server's own. The no-teleport rule is enforced by the interface, not
 * by convention. The one exception is {@link #portalTo}, called only by the
 * adapter's verified portal crossing.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullPlayer extends ServerPlayer implements NullBody {

    /**
     * A body that keeps throwing from {@code tick()} is removed rather than
     * allowed to keep throwing inside the server's entity loop. Three strikes:
     * one failure may be transient (a chunk boundary, a chunk unload race),
     * three in a row is a broken body.
     */
    private static final int TICK_FAILURE_LIMIT = 3;

    /** Hard bound on what a viewer probe records, so a probe cannot grow. */
    private static final int PROBE_PACKET_LIMIT = 1024;

    /** How often a Null drops the chunk queue its own listener never drains. */
    private static final int QUEUE_SWEEP_INTERVAL = 100;

    /** A movement intent that is not refreshed for this many ticks expires. */
    private static final int INTENT_TTL = 5;

    /** A look target that is not refreshed for this many ticks expires. */
    private static final int LOOK_TTL = 80;

    /** Degrees per tick the coupled head-and-body yaw may turn. */
    private static final float BODY_TURN_PER_TICK = 36.0F;

    /** Vanilla's jump cooldown ({@code LivingEntity.noJumpDelay}). */
    private static final int JUMP_COOLDOWN = 10;

    private final ItemLedger inventory;
    private final V1_21_11Adapter adapter;

    /** Non-null only for a viewer probe: outbound packet class names. */
    private final List<String> recordedPackets;

    private int tickFailures;
    private int upkeepFailures;
    private boolean upkeepDisabled;
    private int optionalFailures;

    // Movement intent, written by the plugin's brain once per tick.
    private double moveX;
    private double moveZ;
    private int gait = GAIT_STOP;
    private boolean sneakKey;
    private int jumpLatch;
    private int intentAge = INTENT_TTL + 1;
    private int jumpCooldown;

    // Look intent; body and head always turn together.
    private Vec3d lookTarget;
    private int lookAge = LOOK_TTL + 1;

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
        markClientLoaded();
        silenceAdvancements();
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
    public UUID uuid() { return getUUID(); }

    @Override
    public Vec3d bodyPosition() {
        return new Vec3d(getX(), getY(), getZ());
    }

    /**
     * Legacy steering entry point, kept for every existing caller: a force is
     * turned into the same movement intent the brain sends, so it is still fed
     * through vanilla travel physics and can never exceed a player's speed.
     */
    @Override
    public void applySteering(Vec3d force) {
        if (force == null || !isFinite(force)) {
            return;
        }
        double horizontal = Math.sqrt(force.x() * force.x() + force.z() * force.z());
        if (horizontal < 1.0e-4) {
            setMovement(0.0D, 0.0D, GAIT_STOP, false, false);
            return;
        }
        double throttle = Math.min(1.0D, horizontal / 0.22D);
        setMovement(force.x() / horizontal * throttle, force.z() / horizontal * throttle,
                horizontal >= 0.25D ? GAIT_SPRINT : GAIT_RUN, false, false);
    }

    @Override
    public void setMovement(double dirX, double dirZ, int gait, boolean jump, boolean sneak) {
        if (!Double.isFinite(dirX) || !Double.isFinite(dirZ)) {
            dirX = 0.0D;
            dirZ = 0.0D;
        }
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (length > 1.0D) {
            dirX /= length;
            dirZ /= length;
        }
        this.moveX = dirX;
        this.moveZ = dirZ;
        this.gait = gait;
        this.sneakKey = sneak;
        if (jump) {
            this.jumpLatch = 5;
        }
        this.intentAge = 0;
    }

    @Override
    public void lookAt(Vec3d target) {
        setLookTarget(target, false);
    }

    @Override
    public void setLookTarget(Vec3d target, boolean headOnly) {
        if (target != null && !isFinite(target)) {
            return;
        }
        this.lookTarget = target;
        this.lookAge = 0;
    }

    @Override
    public float headYaw() { return getYHeadRot(); }

    @Override
    public float bodyYaw() { return getYRot(); }

    @Override
    public float pitch() { return getXRot(); }

    @Override
    public Vec3d velocity() {
        Vec3 delta = getDeltaMovement();
        return delta == null ? Vec3d.ZERO : new Vec3d(delta.x, delta.y, delta.z);
    }

    @Override
    public double fallDistance() { return (double) this.fallDistance; }

    @Override
    public boolean inWater() { return isInWater(); }

    @Override
    public boolean horizontalCollision() { return this.horizontalCollision; }

    @Override
    public int deathTicks() {
        if (isRemoved() || !this.valid) {
            return Integer.MAX_VALUE;
        }
        return isDeadOrDying() ? this.deathTime : -1;
    }

    @Override
    public boolean releaseUseItem() {
        if (!isUsingItem()) {
            return false;
        }
        releaseUsingItem();
        return true;
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
     * <p>This is <b>not</b> ordinary movement. Callers are restricted to
     * {@link V1_21_11Adapter#portalTravel} and the post-impact fallback in
     * {@link V1_21_11Adapter#enderPearlTeleport}; both verify a same-world,
     * loaded, collision-safe destination first. Velocity is cleared so the body
     * cannot arrive mid-fall, and a non-finite destination is refused outright.</p>
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
        if (isDeadOrDying() && !isRemoved()) {
            // The end of the death animation: vanilla's poof of smoke.
            try {
                level().broadcastEntityEvent(this, (byte) 60);
            } catch (Throwable ignored) {
                // Cosmetic.
            }
        }
        discard();
    }

    // ------------------------------------------------------------ combat rules

    /**
     * "Can {@code other} hurt this body?" - asked by vanilla for every melee hit
     * and every player-owned arrow.
     *
     * <p>{@code ServerPlayer} answers with the world's PvP flag, which is why a
     * Null on a {@code pvp=false} server could not be hit at all. A Null is not
     * a player account, so the plugin's own rule decides instead:
     * {@code combat.players-can-hit-nulls} for real players (and the self-test
     * probe that stands in for one), and Null-versus-Null for ordered fights.
     * A Null hitting a <i>real</i> player still goes through that player's own
     * {@code canHarmPlayer}, so server PvP rules for humans are untouched.</p>
     */
    @Override
    public boolean canHarmPlayer(net.minecraft.world.entity.player.Player other) {
        BodySettings settings = adapter == null ? BodySettings.DEFAULTS : adapter.bodySettings();
        if (other instanceof NullPlayer attacker && !attacker.isProbe()) {
            return settings.nullsCanHitNulls();
        }
        return settings.playersCanHitNulls();
    }

    /**
     * Marks the body's (non-existent) client as loaded.
     *
     * <p>Since 1.21.4 {@code ServerPlayer.isInvulnerableTo} returns true for
     * every damage source until {@code connection.hasClientLoaded()}. A real
     * client reports that with a packet, or the server times out after 60 ticks
     * - but the timeout is counted down in {@code ServerPlayer.tick()}, which a
     * Null overrides. That is the root cause of Nulls that could not be hit, did
     * not take fall damage and could not die. The field is written directly; if
     * that ever fails the per-tick fallback below runs the vanilla timeout.</p>
     */
    private void markClientLoaded() {
        try {
            java.lang.reflect.Field timer =
                    ServerGamePacketListenerImpl.class.getDeclaredField("clientLoadedTimeoutTimer");
            timer.setAccessible(true);
            timer.setInt(this.connection, 0);
        } catch (Throwable ignored) {
            // ensureClientLoaded() runs the vanilla countdown instead.
        }
    }

    /** The per-tick fallback for {@link #markClientLoaded()}. */
    private void ensureClientLoaded() {
        try {
            if (!this.connection.hasClientLoaded()) {
                markClientLoaded();
                if (!this.connection.hasClientLoaded()) {
                    this.connection.tickClientLoadTimeout();
                }
            }
        } catch (Throwable ignored) {
            // Never fatal: the worst case is the old invulnerable body.
        }
    }

    /**
     * Stops advancement criteria for this body.
     *
     * <p>A Null is not an account: killing a mob or picking up a diamond must not
     * make "Null has made the advancement [Monster Hunter]" appear in public
     * chat, which is exactly what PlayerAdvancements would do.</p>
     */
    private void silenceAdvancements() {
        try {
            getAdvancements().stopListening();
        } catch (Throwable ignored) {
            // The plugin also clears advancement messages for Nulls.
        }
    }

    /** Called by the adapter once the body is in the world and verified. */
    void afterRegistration() {
        markClientLoaded();
        silenceAdvancements();
    }

    // ------------------------------------------------------------- diagnostics

    /** True when the packet listener is installed. Must always be true. */
    public boolean packetListenerReady() { return this.connection != null; }

    /** True for the smoke-test probe, whose outbound packets are recorded. */
    @Override
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
     * Runs the body through vanilla physics.
     *
     * <p>Overridden because a server-only {@code ServerPlayer} must not run
     * normal client synchronization: {@code ServerPlayer.tick()} sends chunk
     * cache centers, flushes menus, triggers advancements and writes player
     * statistics, none of which apply to a body with no client and no account.
     * What a Null does keep is everything a body needs: world interaction
     * ({@code baseTick}), vanilla travel physics, fall damage, entity
     * collisions, item use, attack cooldown and item pickup.</p>
     */
    @Override
    public void tick() {
        try {
            tickBody();
        } catch (Throwable t) {
            tickFailures++;
            if (tickFailures <= TICK_FAILURE_LIMIT) {
                org.bukkit.Bukkit.getLogger().log(Level.WARNING,
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
            org.bukkit.Bukkit.getLogger().warning("[NullArmy] a Null reached an invalid"
                    + " position and was removed; the server is unaffected.");
            destroyQuietly();
            return;
        }

        ensureClientLoaded();
        upkeep();
        int deathBefore = this.deathTime;
        worldInteraction();
        if (isRemoved()) {
            return;
        }
        if (isDeadOrDying()) {
            // The death animation: the client tips the body over while health is
            // zero; vanilla's tickDeath counts it. If this build's baseTick did
            // not, count it here so the plugin knows when the animation is over.
            if (this.deathTime == deathBefore && this.deathTime < 60) {
                this.deathTime++;
            }
            setMovement(0.0D, 0.0D, GAIT_STOP, false, false);
            return;
        }

        optional(this::tickItemUse);
        optional(this::updateSwingTime);
        optional(this::applyLook);
        // A player's attack meter advances in Player#tick, which a Null never
        // runs: its own tick is here. Left alone, the meter sits at its floor
        // for the whole life of the body - so every blow was struck at minimum
        // strength and a brain that waits for a loaded weapon never swung at
        // all. Advance it, as vanilla does, and a Null hits like a player.
        this.attackStrengthTicker++;
        applyMovement();
        BodySettings settings = adapter == null ? BodySettings.DEFAULTS : adapter.bodySettings();
        if (settings.collisions()) {
            optional(this::pushEntities);
        }
        if (settings.pickupItems()) {
            optional(this::touchNearby);
        }
        if ((this.tickCount & 1) == 0) {
            // Vanilla's own equipment pass: applies armour attributes and tells
            // every viewer what the body holds and wears. Changes made through the
            // Bukkit inventory (the loadout GUI, the builder's tool choice) show
            // up within two ticks.
            Tracking.syncEquipment(this);
        }

        // The chunk sender of a Null's listener is never drained (that happens in
        // ServerGamePacketListenerImpl#tick, which the server runs for listed
        // players only). Empty it so nothing accumulates for the session.
        if (this.tickCount % QUEUE_SWEEP_INTERVAL == 0) {
            Tracking.clearPendingChunks(this.connection);
        }
    }

    /** Runs a non-essential step; a body keeps ticking if one of them fails. */
    private void optional(Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            optionalFailures++;
            if (optionalFailures <= 3) {
                org.bukkit.Bukkit.getLogger().warning("[NullArmy] a Null body step failed ("
                        + t.getClass().getSimpleName() + ": " + t.getMessage()
                        + "); the body keeps ticking.");
            }
        }
    }

    /**
     * The timers the server would normally decay in {@code ServerPlayer#tick}
     * and {@code Player#tick}.
     *
     * <p>Without this a Null that is hurt once keeps {@code invulnerableTime} at
     * its hurt value forever and becomes permanently damage-immune, and its
     * attack cooldown never recharges, so every swing would be a weak one.</p>
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
            AttackTicker.increment(this);
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
     * Real interaction with the world: fire, water, suffocation, freeze,
     * potion effects and the death countdown.
     *
     * <p>{@code Entity.baseTick()} also runs the portal countdown, which is
     * neutralised here (see {@link #handlePortal()}), so standing in a portal
     * can never send a Null to the Nether.</p>
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

    /**
     * Item use the way {@code LivingEntity.tick} drives it: eating counts down
     * and completes, a drawn bow keeps charging, a raised shield starts blocking
     * after its delay. Switching away from the item stops using it.
     */
    private void tickItemUse() {
        if (!isUsingItem()) {
            return;
        }
        net.minecraft.world.item.ItemStack inHand = getItemInHand(getUsedItemHand());
        net.minecraft.world.item.ItemStack using = getUseItem();
        if (net.minecraft.world.item.ItemStack.isSameItem(inHand, using)) {
            updateUsingItem(using);
        } else {
            stopUsingItem();
        }
    }

    /** Turns head and body through one shared yaw; independent head turns are impossible. */
    private void applyLook() {
        if (lookAge <= LOOK_TTL) {
            lookAge++;
        }
        boolean moving = intentAge <= INTENT_TTL && gait != GAIT_STOP
                && (moveX * moveX + moveZ * moveZ) > 4.0e-4;
        float moveYaw = moving ? yawOf(moveX, moveZ) : Float.NaN;
        Vec3d look = lookAge <= LOOK_TTL ? lookTarget : null;

        float body = getYRot();
        float pitch = getXRot();
        float yawGoal = body;
        float pitchGoal = pitch;
        if (look != null) {
            double dx = look.x() - getX();
            double dy = look.y() - getEyeY();
            double dz = look.z() - getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            if (horizontal > 1.0e-3) {
                yawGoal = (float) Math.toDegrees(Math.atan2(-dx, dz));
            }
            pitchGoal = (float) Math.toDegrees(-Math.atan2(dy, Math.max(1.0e-3, horizontal)));
        } else if (moving) {
            yawGoal = moveYaw;
            pitchGoal = pitch * 0.8F;
        }

        float yaw = wrap(approach(body, yawGoal, BODY_TURN_PER_TICK));
        float newPitch = approach(pitch, Math.max(-90.0F, Math.min(90.0F, pitchGoal)), 20.0F);
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        setXRot(newPitch);
    }

    /**
     * Movement through vanilla physics.
     *
     * <p>The intent becomes the same strafe/forward input a client sends, in the
     * body's own frame; {@code travel} then applies friction, gravity,
     * collisions, step height, water and ladders exactly as for a player. When
     * the server's real Elytra state is active, that same vanilla travel call
     * runs fall-flying physics; no flight position or velocity is scripted here.
     * Fall damage is checked the way a real player's movement packet triggers
     * it. Without that explicit Elytra state, a Null cannot fly, clip into a
     * block or outrun a sprinting player.</p>
     */
    private void applyMovement() {
        if (intentAge <= INTENT_TTL) {
            intentAge++;
        }
        boolean expired = intentAge > INTENT_TTL;
        double mx = expired ? 0.0D : moveX;
        double mz = expired ? 0.0D : moveZ;
        int g = expired ? GAIT_STOP : gait;
        double throttle = Math.sqrt(mx * mx + mz * mz);
        boolean moving = g != GAIT_STOP && throttle > 0.02D;
        boolean sneaking = !expired && sneakKey;
        boolean usingItem = isUsingItem();

        boolean sprint = moving && g == GAIT_SPRINT && !sneaking && !usingItem;
        if (isSprinting() != sprint) {
            setSprinting(sprint);
        }
        if (isShiftKeyDown() != sneaking) {
            setShiftKeyDown(sneaking);
        }
        optional(() -> {
            Pose wanted = sneaking ? Pose.CROUCHING : Pose.STANDING;
            Pose now = getPose();
            if ((now == Pose.STANDING || now == Pose.CROUCHING) && now != wanted) {
                setPose(wanted);
            }
        });

        double ix = 0.0D;
        double iz = 0.0D;
        if (moving) {
            double scale = g == GAIT_WALK ? 0.6D : 1.0D;
            if (sneaking) {
                scale *= 0.3D;
            }
            if (usingItem) {
                scale *= 0.2D;
            }
            double nx = mx / throttle;
            double nz = mz / throttle;
            double magnitude = Math.min(1.0D, throttle) * scale;
            double yaw = Math.toRadians(getYRot());
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            ix = (nx * cos + nz * sin) * magnitude;
            iz = (-nx * sin + nz * cos) * magnitude;
        }

        if (jumpCooldown > 0) {
            jumpCooldown--;
        }
        boolean wantJump = jumpLatch > 0;
        if (jumpLatch > 0) {
            jumpLatch--;
        }
        if (moving && this.horizontalCollision && onGround()) {
            // Auto-step over a one-block rise, like a player tapping space.
            wantJump = true;
        }
        if (isInWater() || isInLava()) {
            if (wantJump || isUnderWater() || (moving && this.horizontalCollision)) {
                // Swimming up: vanilla's jumpInLiquid impulse.
                setDeltaMovement(getDeltaMovement().add(0.0D, 0.04D, 0.0D));
            }
        } else if (wantJump && onGround() && jumpCooldown == 0) {
            jumpFromGround();
            jumpCooldown = JUMP_COOLDOWN;
            jumpLatch = 0;
        }

        setSpeed((float) getAttributeValue(Attributes.MOVEMENT_SPEED));
        double x0 = getX();
        double y0 = getY();
        double z0 = getZ();
        travel(new Vec3(ix, 0.0D, iz));
        // What a real player's movement packet does after every move: supporting
        // block bookkeeping and fall damage (Feather Falling included). This is
        // also what makes fallDistance grow while falling, which vanilla's
        // critical hit needs. Whether fall damage is allowed at all is decided by
        // the plugin's combat.fall-damage rule in the damage event.
        doCheckFallDamage(getX() - x0, getY() - y0, getZ() - z0, onGround());
    }

    /** Picks up item entities and orbs the body walks over, like Player#aiStep. */
    private void touchNearby() {
        AABB reach = getBoundingBox().inflate(1.0D, 0.5D, 1.0D);
        List<net.minecraft.world.entity.Entity> nearby = level().getEntities(this, reach);
        for (net.minecraft.world.entity.Entity entity : nearby) {
            // Exactly Player#touch: every entity in reach is told it was touched.
            // Items and orbs are picked up, arrows are collected, and a slime
            // hurts the body just as it would hurt a player.
            if (entity != null && !entity.isRemoved()) {
                entity.playerTouch(this);
            }
        }
    }

    private static float yawOf(double dirX, double dirZ) {
        return (float) Math.toDegrees(Math.atan2(-dirX, dirZ));
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

    private static float approach(float current, float target, float maxStep) {
        float delta = wrap(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    // --------------------------------------------------------- portal containment

    /**
     * A Null never enters a portal on its own.
     *
     * <p>A Null standing in a real Nether portal must not be transported
     * anywhere: the only relocation a Null ever makes is the adapter's verified
     * portal crossing.</p>
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
