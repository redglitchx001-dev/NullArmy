package redglitchx.nullarmy.nms;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.nav.BlockView;

import java.util.List;
import java.util.UUID;

/**
 * The version adapter SPI.
 *
 * <p>Everything that touches NMS or packets lives behind this interface, so the
 * rest of the plugin never sees a version-specific class. Spec 1.1: "Keep NMS
 * code isolated behind version adapters."</p>
 *
 * <p>Each supported server version gets one implementation, compiled against
 * its own dev bundle by {@code paperweight-userdev}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public interface VersionAdapter {

    /** The Minecraft version this adapter targets, e.g. {@code "1.21.11"}. */
    String minecraftVersion();

    /**
     * @param serverVersion the version string reported by the running server
     * @return true if this adapter can drive that server
     */
    boolean supports(String serverVersion);

    /** A request to bring one Null into existence. */
    final class SpawnRequest {
        private final UUID owner;
        private final String profileName;
        private final String worldName;
        private final Vec3d position;
        private final int inventoryCapacity;

        public SpawnRequest(UUID owner, String profileName, String worldName,
                            Vec3d position, int inventoryCapacity) {
            this.owner = owner;
            this.profileName = profileName;
            this.worldName = worldName;
            this.position = position;
            this.inventoryCapacity = inventoryCapacity;
        }

        public UUID owner() { return owner; }
        public String profileName() { return profileName; }
        public String worldName() { return worldName; }
        public Vec3d position() { return position; }
        public int inventoryCapacity() { return inventoryCapacity; }
    }

    /**
     * Spawns a Null at a position the caller has already verified as safe.
     *
     * <p>The adapter must re-verify: safe spawn validation is a server-authority
     * concern (spec 3) and must never be assumed from an unverified caller.</p>
     *
     * @throws IllegalStateException if the position is not collision-safe
     */
    NullBody spawnNull(SpawnRequest request);

    /** A block-collision/cost view rooted at a world, for pathfinding. */
    BlockView blockView(String worldName);

    /** Plays the cosmetic summon portal effects. Never moves any entity. */
    void playPortalEffects(String worldName, Vec3d at, int count);

    /**
     * @return true if a 1-block-wide, 2-block-tall body fits at this position
     *     without intersecting a solid block
     */
    boolean isSpawnSafe(String worldName, Vec3d position);

    /** All Nulls currently live in a world. */
    List<NullBody> activeIn(String worldName);
}
