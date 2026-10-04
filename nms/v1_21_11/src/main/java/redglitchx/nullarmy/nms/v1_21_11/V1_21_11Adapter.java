package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.nav.BlockView;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * NullArmy adapter for Paper 1.21.11.
 *
 * <h2>The spawn contract</h2>
 * {@link #spawnNull} does not report success because an object was constructed.
 * It performs four steps and verifies each one, and any failure destroys the
 * partial body and throws with the reason:
 * <ol>
 *   <li><b>Announce.</b> Broadcast
 *       {@code ClientboundPlayerInfoUpdatePacket} for the profile. The client
 *       refuses to build a player entity for a UUID it has no info entry for
 *       ({@code ClientPacketListener.createEntityFromPacket} logs "Server
 *       attempted to add player prior to sending player info" and drops the
 *       entity), so this has to happen first. It is also what puts a Null in the
 *       tab list and what carries the configured skin.</li>
 *   <li><b>Register.</b> {@code ServerLevel.addFreshEntity} - the returned
 *       boolean is checked, not ignored.</li>
 *   <li><b>Verify.</b> The body must be valid, present in the level's entity
 *       index, still have its packet listener, and - the part that decides
 *       whether anybody can see it - have a {@code TrackedEntity} in
 *       {@code ChunkMap.entityMap}.</li>
 *   <li><b>Report.</b> Only then is the body returned and counted.</li>
 * </ol>
 *
 * <p>Known deliberate choices:</p>
 * <ul>
 *   <li>We do <b>not</b> call {@code PlayerList#placeNewPlayer}. That drives full
 *       join semantics (playerdata files, advancements, statistics,
 *       PlayerJoinEvent, the online-player list and the max-player count) -
 *       risks R-01/R-02/R-03. Nulls are level entities plus explicit player-info
 *       packets instead.</li>
 *   <li>CraftBukkit is <b>not</b> relocated on Paper 1.20.5+, hence
 *       {@code org.bukkit.craftbukkit.CraftServer} with no version segment.</li>
 *   <li>{@code ClientboundAddEntityPacket} is used because
 *       {@code ClientboundAddPlayerPacket} no longer exists in 1.21.4+.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class V1_21_11Adapter implements VersionAdapter {

    private static final String MC_VERSION = "1.21.11";
    private static final double BODY_WIDTH = 0.6;
    private static final double BODY_HEIGHT = 1.8;

    /**
     * The view distance a Null asks for. {@code ClientInformation.createDefault()}
     * requests 2, and {@code ChunkMap.getPlayerViewDistance} clamps it into
     * {@code [2, server view distance]}, so 2 is what the tracker computes for
     * every Null. Matching it exactly is what keeps the probe test honest.
     */
    private static final int NULL_VIEW_DISTANCE = 2;

    private final List<NullBody> active = Collections.synchronizedList(new ArrayList<>());

    /** Whether a Null's player-info entry is listed in the client's tab overlay. */
    private volatile boolean tabListed = true;

    @Override
    public void setTabListing(boolean listed) {
        this.tabListed = listed;
    }

    @Override
    public String minecraftVersion() { return MC_VERSION; }

    @Override
    public boolean supports(String serverVersion) {
        return serverVersion != null && serverVersion.startsWith(MC_VERSION);
    }

    // --------------------------------------------------------------------- spawn

    @Override
    public NullBody spawnNull(SpawnRequest request) {
        if (request == null) {
            throw new IllegalStateException("no spawn request");
        }
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        MinecraftServer server = craftServer.getServer();

        World bukkitWorld = Bukkit.getWorld(request.worldName());
        if (bukkitWorld == null) {
            throw new IllegalStateException("world not found: " + request.worldName());
        }
        ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();

        // Re-verify safety server-side. Never trust the caller.
        //
        // Ground spawns need solid ground under the feet. An airborne spawn is
        // the /null airdrop sky path: the body must start in free space with no
        // solid block intersecting it, and it is then delivered by ordinary
        // vanilla gravity - which is exactly why the air drop documents real
        // fall damage instead of pretending there is none.
        boolean safe = request.airborne()
                ? isAirborneSpawnSafe(request.worldName(), request.position())
                : isSpawnSafe(request.worldName(), request.position());
        if (!safe) {
            throw new IllegalStateException("refusing to spawn Null at unsafe position "
                    + request.position()
                    + (request.airborne()
                        ? " - the air-drop position is not clear of blocks"
                        : " - no teleporting out of bad spots"));
        }
        if (!isEntitySpaceFree(level, request.position())) {
            throw new IllegalStateException("refusing to spawn Null at " + request.position()
                    + " - another entity is already standing there");
        }

        String name = request.profileName();
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalStateException("a Null needs a profile name");
        }
        if (name.length() > 16) {
            // A longer name is rejected by the protocol's PLAYER_NAME codec, which
            // would fail the whole spawn far away from the real cause.
            throw new IllegalStateException("profile name is longer than 16 characters: " + name);
        }

        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        // Every Null and the Commander wear one configured skin. The request
        // carries it; if it is absent we fall back to whatever SkinConfig has,
        // and if that is empty too the profile is left alone rather than faked
        // (IMPLEMENTATION_PLAN.md A-05).
        SkinApplicator.apply(profile, request.skinValue(), request.skinSignature());

        NullPlayer npc = new NullPlayer(server, level, profile, request, this);
        if (npc.connection == null) {
            // Cannot happen with the constructor above, and is checked anyway:
            // registering a ServerPlayer without a listener is what crashed
            // MinecraftServer.tickChildren on 2026-10-04.
            throw new IllegalStateException("the Null has no packet listener; refusing to register it");
        }

        npc.setPos(request.position().x(), request.position().y(), request.position().z());

        // 1. Player info first. Without it every client silently drops the
        //    add-entity packet and the Null exists only on the server.
        if (!announce(npc)) {
            discardQuietly(npc);
            throw new IllegalStateException("could not send the player-info packet for "
                    + name + ", so no client would have been able to render it");
        }

        // 2. Register in the world, and check the answer.
        boolean added;
        try {
            added = level.addFreshEntity(npc);
        } catch (Throwable t) {
            withdraw(npc);
            discardQuietly(npc);
            throw new IllegalStateException("the server threw while adding the Null to the world: "
                    + describe(t), t);
        }
        if (!added) {
            withdraw(npc);
            discardQuietly(npc);
            throw new IllegalStateException("the server refused to add the Null to "
                    + request.worldName() + " (addFreshEntity returned false)");
        }

        // 3. Verify. A returned object is not a spawn.
        String failure = verify(level, npc);
        if (failure != null) {
            withdraw(npc);
            discardQuietly(npc);
            throw new IllegalStateException(failure);
        }

        active.add(npc);
        return npc;
    }

    /**
     * Checks the things that decide whether this Null really exists for the
     * server and for clients.
     *
     * @return null when everything is in order, otherwise the reason to report
     */
    private String verify(ServerLevel level, NullPlayer npc) {
        if (npc.connection == null) {
            return "the Null lost its packet listener after registration";
        }
        if (!npc.valid) {
            return "the level did not mark the Null as a valid entity"
                    + " (it was never added to the world's entity manager)";
        }
        if (npc.isRemoved()) {
            return "the Null was removed again immediately after being added";
        }
        Entity indexed = null;
        try {
            indexed = level.getEntity(npc.getUUID());
        } catch (Throwable t) {
            return "the level's entity index could not be read: " + describe(t);
        }
        if (indexed != npc) {
            return "the Null is not in the level's entity index, so the server"
                    + " would forget it on the next chunk operation";
        }
        if (!Tracking.isTracked(level, npc.getId())) {
            return "the chunk map is not tracking the Null, so no client can see it"
                    + " (Paper logged an illegal addEntity, or the entity type has"
                    + " no client tracking range)";
        }
        return null;
    }

    /**
     * Broadcasts the player-info entry every client needs before it will render
     * this body - and which is also what makes it appear in the tab list with a
     * plain name and the configured skin.
     *
     * @return true when the packet was built and handed to the player list
     */
    private boolean announce(NullPlayer npc) {
        try {
            PlayerList players = minecraftServer().getPlayerList();
            if (players == null) {
                return false;
            }
            players.broadcastAll(infoPacket(npc));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The player-info entry a client needs before it will render this body.
     *
     * <p>Without {@code UPDATE_LISTED} the entry still exists - so the entity is
     * still rendered and still carries the skin - but the client keeps it out of
     * the tab overlay, which is what {@code nulls.show-in-tab-list: false} means.</p>
     */
    private ClientboundPlayerInfoUpdatePacket infoPacket(NullPlayer npc) {
        if (tabListed) {
            return ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(npc));
        }
        java.util.EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions =
                java.util.EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.INITIALIZE_CHAT,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER);
        return new ClientboundPlayerInfoUpdatePacket(actions, List.of(npc));
    }

    /** Removes the tab-list/info entry of a body that is gone or never arrived. */
    private void withdraw(NullPlayer npc) {
        try {
            PlayerList players = minecraftServer().getPlayerList();
            if (players == null) {
                return;
            }
            players.broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(npc.getUUID())));
        } catch (Throwable ignored) {
            // A ghost tab entry is cosmetic; a thrown exception here is not.
        }
    }

    private MinecraftServer minecraftServer() {
        return ((CraftServer) Bukkit.getServer()).getServer();
    }

    private static void discardQuietly(NullPlayer npc) {
        try {
            npc.discard();
        } catch (Throwable ignored) {
            // The body never made it into the world; nothing more can be done.
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown failure";
        }
        String message = t.getMessage();
        return t.getClass().getSimpleName() + (message == null || message.isEmpty()
                ? "" : ": " + message);
    }

    // ------------------------------------------------------------------ viewers

    /**
     * Sends the info entries of every live Null to one player.
     *
     * <p>A player who joins after a squad exists would otherwise see bodies the
     * server is tracking but the client cannot build, because the info packets
     * were broadcast before they connected.</p>
     *
     * @return how many Nulls were announced to that player
     */
    @Override
    public int refreshViewer(UUID viewerId) {
        if (viewerId == null) {
            return 0;
        }
        org.bukkit.entity.Player bukkitPlayer = Bukkit.getPlayer(viewerId);
        if (bukkitPlayer == null || !bukkitPlayer.isOnline()) {
            return 0;
        }
        ServerPlayer viewer;
        try {
            viewer = ((CraftPlayer) bukkitPlayer).getHandle();
        } catch (Throwable t) {
            return 0;
        }
        if (viewer == null || viewer.connection == null) {
            return 0;
        }
        int announced = 0;
        List<NullBody> bodies;
        synchronized (active) {
            bodies = new ArrayList<>(active);
        }
        for (NullBody body : bodies) {
            if (!(body instanceof NullPlayer) || !body.isAlive()) {
                continue;
            }
            try {
                viewer.connection.send(infoPacket((NullPlayer) body));
                announced++;
            } catch (Throwable ignored) {
                // One unannounceable Null must not stop the rest.
            }
        }
        return announced;
    }

    @Override
    public boolean isTracked(NullBody body) {
        if (!(body instanceof NullPlayer)) {
            return false;
        }
        NullPlayer npc = (NullPlayer) body;
        ServerLevel level = npc.serverLevelOrNull();
        return level != null && Tracking.isTracked(level, npc.getId());
    }

    @Override
    public int viewerCount(NullBody body) {
        if (!(body instanceof NullPlayer)) {
            return -1;
        }
        NullPlayer npc = (NullPlayer) body;
        ServerLevel level = npc.serverLevelOrNull();
        return level == null ? -1 : Tracking.viewerCount(level, npc.getId());
    }

    @Override
    public boolean packetListenerReady(NullBody body) {
        return body instanceof NullPlayer && ((NullPlayer) body).packetListenerReady();
    }

    @Override
    public List<String> trackingDiagnostics() {
        return Tracking.capabilityReport();
    }

    // ------------------------------------------------------- smoke-test viewer

    @Override
    public NullBody createViewerProbe(String worldName, Vec3d position) {
        if (worldName == null || position == null) {
            return null;
        }
        return createViewerProbeAt(worldName, position);
    }

    /** Creates the probe at a position that is inside the chunk it is given. */
    private NullBody createViewerProbeAt(String worldName, Vec3d position) {
        World bukkitWorld = Bukkit.getWorld(worldName);
        if (bukkitWorld == null) {
            return null;
        }
        ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
        MinecraftServer server = minecraftServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "nullprobe");
        SpawnRequest request = new SpawnRequest(UUID.randomUUID(), "nullprobe", worldName,
                position, 36 * 64, "", "", true);
        NullPlayer probe = new NullPlayer(server, level, profile, request, this, true);
        probe.setPos(position.x(), position.y(), position.z());
        if (!level.addFreshEntity(probe)) {
            discardQuietly(probe);
            return null;
        }
        // Make the probe behave like a client that has already received the
        // chunks around it: a matching tracking view, and an empty send queue
        // (isChunkTracked refuses to pair an entity in a chunk that is still
        // pending for the viewer).
        try {
            probe.setChunkTrackingView(
                    ChunkTrackingView.of(probe.chunkPosition(), NULL_VIEW_DISTANCE));
        } catch (Throwable ignored) {
            // The pairing call below reports whether it worked.
        }
        Tracking.clearPendingChunks(probe.connection);
        // And the same thing through the public API: ChunkMap.isChunkTracked
        // refuses to pair an entity that sits in a chunk still marked pending for
        // the viewer, and registering the probe queued its whole view.
        try {
            net.minecraft.world.level.ChunkPos centre = probe.chunkPosition();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    probe.connection.chunkSender.dropChunk(probe,
                            new net.minecraft.world.level.ChunkPos(centre.x + dx, centre.z + dz));
                }
            }
        } catch (Throwable t) {
            org.bukkit.Bukkit.getLogger().warning("[NullArmy] the viewer probe could not drop"
                    + " its pending chunks: " + describe(t));
        }
        Tracking.clearPendingChunks(probe.connection);
        probe.clearRecordedPackets();
        active.add(probe);
        return probe;
    }

    @Override
    public boolean pairProbe(NullBody viewer, NullBody target) {
        if (!(viewer instanceof NullPlayer) || !(target instanceof NullPlayer)) {
            return false;
        }
        NullPlayer probe = (NullPlayer) viewer;
        NullPlayer body = (NullPlayer) target;
        ServerLevel level = body.serverLevelOrNull();
        if (level == null) {
            return false;
        }
        return Tracking.pair(level, body, probe);
    }

    @Override
    public boolean announceTo(NullBody viewer, NullBody target) {
        if (!(viewer instanceof NullPlayer) || !(target instanceof NullPlayer)) {
            return false;
        }
        NullPlayer to = (NullPlayer) viewer;
        if (to.connection == null) {
            return false;
        }
        try {
            to.connection.send(infoPacket((NullPlayer) target));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public List<String> probePackets(NullBody viewer) {
        return viewer instanceof NullPlayer ? ((NullPlayer) viewer).recordedPackets()
                : Collections.emptyList();
    }

    // ------------------------------------------------------------- portal travel

    /**
     * {@inheritDoc}
     *
     * <p>Order matters and is the whole safety story: server thread, world
     * present, destination collision-safe (real ground under the feet), body is
     * one of ours, chunk is loaded - and only then does anything move. The
     * velocity is cleared inside {@link NullPlayer#portalTo}, so a Null cannot
     * arrive carrying a fall it earned in the other place.</p>
     */
    @Override
    public boolean portalTravel(String worldName, NullBody body, Vec3d destination) {
        if (worldName == null || body == null || destination == null) {
            return false;
        }
        if (!Bukkit.isPrimaryThread()) {
            // Never relocate an entity off the main thread (spec 2.4).
            return false;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        if (!(body instanceof NullPlayer)) {
            return false;
        }
        if (!body.isAlive()) {
            return false;
        }
        // Loading the chunk is implicit in the safety check below; a destination
        // in an unloaded chunk is not a place to send a body.
        if (!isSpawnSafe(worldName, destination)) {
            return false;
        }
        return ((NullPlayer) body).portalTo(destination.x(), destination.y(), destination.z());
    }

    @Override
    public BlockView blockView(String worldName) {
        World bukkitWorld = Bukkit.getWorld(worldName);
        if (bukkitWorld == null) {
            throw new IllegalStateException("world not found: " + worldName);
        }
        ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
        return new LevelBlockView(level);
    }

    @Override
    public void playPortalEffects(String worldName, Vec3d at, int count) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return;
        }
        // Cosmetic only. These effects must never move an entity or create blocks.
        for (int i = 0; i < count; i++) {
            double angle = (2.0 * Math.PI * i) / Math.max(1, count);
            double dx = Math.cos(angle) * 1.5;
            double dz = Math.sin(angle) * 1.5;
            world.spawnParticle(org.bukkit.Particle.PORTAL,
                    at.x() + dx, at.y() + 1.0, at.z() + dz, 8, 0.2, 0.4, 0.2, 0.01);
        }
        world.playSound(new org.bukkit.Location(world, at.x(), at.y(), at.z()),
                org.bukkit.Sound.BLOCK_PORTAL_TRIGGER, 0.6f, 1.2f);
    }

    /**
     * Safety check for a sky-delivered Null: the body's block space must be
     * free of solid blocks, but there is deliberately no requirement for ground
     * beneath the feet. Only the air-drop path uses this.
     */
    @Override
    public boolean isAirborneSpawnSafe(String worldName, Vec3d position) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        return columnIsClear(level, position);
    }

    @Override
    public boolean isSpawnSafe(String worldName, Vec3d position) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        if (!columnIsClear(level, position)) {
            return false;
        }
        int minX = (int) Math.floor(position.x() - BODY_WIDTH / 2.0);
        int maxX = (int) Math.floor(position.x() + BODY_WIDTH / 2.0);
        int minZ = (int) Math.floor(position.z() - BODY_WIDTH / 2.0);
        int maxZ = (int) Math.floor(position.z() + BODY_WIDTH / 2.0);
        int feetY = (int) Math.floor(position.y());
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                // Need solid ground under the feet.
                BlockState below = level.getBlockState(new BlockPos(x, feetY - 1, z));
                if (below.isAir()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** True when the 1x2 (rounded to the body's width) space is free of blocks. */
    private boolean columnIsClear(ServerLevel level, Vec3d position) {
        int minX = (int) Math.floor(position.x() - BODY_WIDTH / 2.0);
        int maxX = (int) Math.floor(position.x() + BODY_WIDTH / 2.0);
        int minZ = (int) Math.floor(position.z() - BODY_WIDTH / 2.0);
        int maxZ = (int) Math.floor(position.z() + BODY_WIDTH / 2.0);
        int feetY = (int) Math.floor(position.y());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = feetY; y < feetY + (int) Math.ceil(BODY_HEIGHT); y++) {
                    if (y > level.getMaxY()) {
                        return false;
                    }
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * True when no other entity occupies the body's box.
     *
     * <p>Block checks alone let two Nulls be placed inside each other, which the
     * client renders as one flickering body and which vanilla's own collision
     * resolution then pushes apart unpredictably.</p>
     */
    @Override
    public boolean isEntitySpaceFree(String worldName, Vec3d position) {
        World world = Bukkit.getWorld(worldName);
        if (world == null || position == null) {
            return false;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        return isEntitySpaceFree(level, position);
    }

    private boolean isEntitySpaceFree(ServerLevel level, Vec3d position) {
        try {
            AABB box = new AABB(
                    position.x() - BODY_WIDTH / 2.0, position.y(),
                    position.z() - BODY_WIDTH / 2.0,
                    position.x() + BODY_WIDTH / 2.0, position.y() + BODY_HEIGHT,
                    position.z() + BODY_WIDTH / 2.0);
            // The cast picks the (Entity, AABB, Predicate) overload: without it
            // the call is ambiguous against the EntityTypeTest variant.
            java.util.function.Predicate<Entity> anything = entity -> entity != null;
            List<Entity> inside = level.getEntities((Entity) null, box, anything);
            for (Entity entity : inside) {
                if (entity == null || !entity.isAlive()) {
                    continue;
                }
                // Items and experience orbs on the ground are not occupants.
                if (entity instanceof net.minecraft.world.entity.item.ItemEntity
                        || entity instanceof net.minecraft.world.entity.ExperienceOrb) {
                    continue;
                }
                return false;
            }
            return true;
        } catch (Throwable t) {
            // Fail closed: an unreadable entity list is not a safe spawn spot.
            return false;
        }
    }

    @Override
    public List<NullBody> activeIn(String worldName) {
        List<NullBody> out = new ArrayList<>();
        synchronized (active) {
            for (NullBody body : active) {
                if (body.isAlive()) {
                    out.add(body);
                }
            }
        }
        return out;
    }

    /**
     * Drops a body from the active list and withdraws its player-info entry.
     *
     * <p>Called by {@link NullPlayer} on destroy, so a Null never leaves a ghost
     * in anybody's tab list.</p>
     */
    void forget(NullBody body) {
        if (body == null) {
            return;
        }
        active.remove(body);
        if (body instanceof NullPlayer) {
            withdraw((NullPlayer) body);
        }
    }

    /**
     * A {@link BlockView} backed by a real {@link ServerLevel}.
     *
     * <p>Read-only. Never force-loads a chunk: unloaded chunks are treated as
     * solid so a Null will path around them rather than dragging them in
     * (spec 2.5).</p>
     */
    private static final class LevelBlockView implements BlockView {
        private final ServerLevel level;

        LevelBlockView(ServerLevel level) { this.level = level; }

        @Override
        public boolean isSolid(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return true; // fail closed: do not path into unloaded terrain
            }
            return !level.getBlockState(pos).isAir();
        }

        @Override
        public double extraCost(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return 1000.0;
            }
            // TODO(phase-4): lava, fire, cactus, powder snow, deep water, fall risk
            return 0.0;
        }

        @Override
        public int minY() { return level.getMinY(); }

        @Override
        public int maxY() { return level.getMaxY() - 1; }
    }
}
