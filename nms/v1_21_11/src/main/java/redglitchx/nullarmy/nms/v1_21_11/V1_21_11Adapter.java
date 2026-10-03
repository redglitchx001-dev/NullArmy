package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;

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
 * <p><b>STATUS: UNVERIFIED.</b> This class has never been compiled or run. It
 * cannot be: the build environment has no JDK and no access to
 * {@code repo.papermc.io}, so the dev bundle is unavailable
 * (IMPLEMENTATION_PLAN.md blocker B-1). Treat every NMS signature here as a
 * hypothesis to confirm against a real 1.21.11 dev bundle during Phase 1
 * verification item V-04.</p>
 *
 * <p>Known deliberate choices:</p>
 * <ul>
 *   <li>We do <b>not</b> call {@code PlayerList#placeNewPlayer}. That drives
 *       full join semantics (playerdata files, advancements, statistics,
 *       PlayerJoinEvent) - risk R-01/R-02/R-03. The Null is added to the level
 *       and presented to viewers with packets instead.</li>
 *   <li>CraftBukkit is <b>not</b> relocated on Paper 1.20.5+, hence
 *       {@code org.bukkit.craftbukkit.CraftServer} with no version segment
 *       (IMPLEMENTATION_PLAN.md section 3.2).</li>
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

    private final List<NullBody> active = Collections.synchronizedList(new ArrayList<>());

    @Override
    public String minecraftVersion() { return MC_VERSION; }

    @Override
    public boolean supports(String serverVersion) {
        return serverVersion != null && serverVersion.startsWith(MC_VERSION);
    }

    @Override
    public NullBody spawnNull(SpawnRequest request) {
        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        MinecraftServer server = craftServer.getServer();

        World bukkitWorld = Bukkit.getWorld(request.worldName());
        if (bukkitWorld == null) {
            throw new IllegalStateException("world not found: " + request.worldName());
        }
        ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();

        // Re-verify safety server-side. Never trust the caller.
        if (!isSpawnSafe(request.worldName(), request.position())) {
            throw new IllegalStateException("refusing to spawn Null at unsafe position "
                    + request.position() + " - no teleporting out of bad spots");
        }

        GameProfile profile = new GameProfile(UUID.randomUUID(), request.profileName());
        // A pure black skin needs a REAL Mojang-hosted texture plus its signature.
        // If none is configured the Null keeps the default skin rather than
        // pretending (IMPLEMENTATION_PLAN.md A-05).
        SkinApplicator.apply(profile);

        ServerPlayer npc = new NullPlayer(server, level, profile, request, this);

        npc.setPos(request.position().x(), request.position().y(), request.position().z());
        level.addFreshEntity(npc);

        NullBody body = (NullBody) npc;
        active.add(body);
        return body;
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

    @Override
    public boolean isSpawnSafe(String worldName, Vec3d position) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }
        ServerLevel level = ((CraftWorld) world).getHandle();
        int minX = (int) Math.floor(position.x() - BODY_WIDTH / 2.0);
        int maxX = (int) Math.floor(position.x() + BODY_WIDTH / 2.0);
        int minZ = (int) Math.floor(position.z() - BODY_WIDTH / 2.0);
        int maxZ = (int) Math.floor(position.z() + BODY_WIDTH / 2.0);
        int feetY = (int) Math.floor(position.y());

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = feetY; y < feetY + (int) Math.ceil(BODY_HEIGHT); y++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (!state.isAir()) {
                        return false;
                    }
                }
                // Need solid ground under the feet.
                BlockState below = level.getBlockState(new BlockPos(x, feetY - 1, z));
                if (below.isAir()) {
                    return false;
                }
            }
        }
        return true;
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

    /** Drops a dead Null from the active list. Called by {@link NullPlayer}. */
    void forget(NullBody body) { active.remove(body); }

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
