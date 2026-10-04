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
        private final String skinValue;
        private final String skinSignature;
        private final boolean airborne;

        /**
         * Full form, carrying the skin this Null must wear.
         *
         * <p>Every Null and the Commander wear the skin of one configured
         * Minecraft account. Pass empty strings when no skin has been
         * resolved yet - the Null then keeps the default skin rather than
         * the plugin pretending it applied one.</p>
         */
        public SpawnRequest(UUID owner, String profileName, String worldName,
                            Vec3d position, int inventoryCapacity,
                            String skinValue, String skinSignature) {
            this(owner, profileName, worldName, position, inventoryCapacity,
                    skinValue, skinSignature, false);
        }

        /**
         * Full form, including whether the Null starts in the air.
         *
         * <p>An airborne Null is delivered by the {@code /null airdrop} sky
         * path. It is the one case where "solid ground below the feet" is not
         * a requirement: the body must start in free space and is then moved
         * by ordinary vanilla gravity, which is also why an air drop is
         * documented as carrying real fall damage.</p>
         */
        public SpawnRequest(UUID owner, String profileName, String worldName,
                            Vec3d position, int inventoryCapacity,
                            String skinValue, String skinSignature, boolean airborne) {
            this.owner = owner;
            this.profileName = profileName;
            this.worldName = worldName;
            this.position = position;
            this.inventoryCapacity = inventoryCapacity;
            this.skinValue = skinValue == null ? "" : skinValue;
            this.skinSignature = skinSignature == null ? "" : skinSignature;
            this.airborne = airborne;
        }

        /** Convenience form with no skin - existing call sites keep working. */
        public SpawnRequest(UUID owner, String profileName, String worldName,
                            Vec3d position, int inventoryCapacity) {
            this(owner, profileName, worldName, position, inventoryCapacity, "", "");
        }

        public UUID owner() { return owner; }
        public String profileName() { return profileName; }
        public String worldName() { return worldName; }
        public Vec3d position() { return position; }
        public int inventoryCapacity() { return inventoryCapacity; }

        /** Base64 texture value, or "" when no skin is available. */
        public String skinValue() { return skinValue; }

        /** Mojang signature for {@link #skinValue()}, or "" when absent. */
        public String skinSignature() { return skinSignature; }

        /**
         * True when this Null must start in mid-air and fall, instead of
         * standing on verified ground. Only the sky path of an air drop sets
         * this, and it is why that path is documented to cause fall damage.
         */
        public boolean airborne() { return airborne; }
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

    /**
     * Moves an existing body through a portal: the one deliberate, visible
     * exception to the no-teleport rule.
     *
     * <p>Implementations must verify the destination (world present, spot
     * collision-safe), perform the move on the server thread only, and clear
     * any carried velocity so the body does not arrive mid-fall. Callers are
     * expected to play portal effects at both ends: this is a relocation the
     * player is meant to <b>see</b>, never a silent snap.</p>
     *
     * @return true when the body is now at the destination
     */
    default boolean portalTravel(String worldName, NullBody body, Vec3d destination) {
        // Adapters that have not implemented it refuse honestly rather than
        // pretending the body moved.
        return false;
    }

    /** A block-collision/cost view rooted at a world, for pathfinding. */
    BlockView blockView(String worldName);

    /** Plays the cosmetic summon portal effects. Never moves any entity. */
    void playPortalEffects(String worldName, Vec3d at, int count);

    /**
     * @return true if a 1-block-wide, 2-block-tall body fits at this position
     *     without intersecting a solid block
     */
    boolean isSpawnSafe(String worldName, Vec3d position);

    /**
     * Safety check for a sky-delivered body: the body's space must be free of
     * solid blocks, and - unlike {@link #isSpawnSafe} - ground beneath the feet
     * is explicitly <b>not</b> required, because the body is delivered by
     * vanilla gravity. Only the air-drop sky path uses this.
     *
     * <p>The default is deliberately the strict ground check, so an adapter
     * that has not implemented the sky path refuses to drop a Null through a
     * roof rather than dropping it anyway.</p>
     */
    default boolean isAirborneSpawnSafe(String worldName, Vec3d position) {
        return isSpawnSafe(worldName, position);
    }

    /** All Nulls currently live in a world. */
    List<NullBody> activeIn(String worldName);

    // --------------------------------------------------------------- visibility

    /**
     * True when the server is tracking this body for clients.
     *
     * <p>Registration alone is not visibility: a body the chunk map does not
     * track produces no packets, so no client ever renders it. The spawn path
     * checks this before reporting success, and {@code /null debug} reports it.</p>
     */
    default boolean isTracked(NullBody body) { return false; }

    /**
     * How many player connections are currently paired with this body.
     *
     * @return -1 when the adapter cannot read that
     */
    default int viewerCount(NullBody body) { return -1; }

    /**
     * True when the body's packet listener is installed.
     *
     * <p>A {@code ServerPlayer} without one crashes
     * {@code MinecraftServer.tickChildren}, which walks {@code level.players()}
     * and sends time packets through it every second.</p>
     */
    default boolean packetListenerReady(NullBody body) { return body != null; }

    /**
     * Sends the player-info entries of every live Null to one player.
     *
     * <p>A client drops the add-entity packet for a player UUID it has no info
     * entry for, so anyone who joins after a squad exists needs this.</p>
     *
     * @return how many Nulls were announced
     */
    default int refreshViewer(UUID viewerId) { return 0; }

    /**
     * Whether spawned Nulls are listed in clients' tab lists.
     *
     * <p>Either way a Null is <b>announced</b> with a player-info entry, because a
     * client refuses to render a player entity it has no entry for. This only
     * decides whether that entry is listed in the tab overlay.</p>
     */
    default void setTabListing(boolean listed) {
        // Adapters that cannot honour it stay as they are rather than pretending.
    }

    /** Adapter-specific tracking diagnostics for {@code /null debug}. */
    default List<String> trackingDiagnostics() { return java.util.Collections.emptyList(); }

    /**
     * True when no other entity occupies the body box at this position.
     *
     * <p>Block checks alone can place two Nulls inside each other. The default
     * is permissive so an adapter without the check does not refuse every spawn;
     * the 1.21.11 adapter implements it.</p>
     */
    default boolean isEntitySpaceFree(String worldName, Vec3d position) { return true; }

    // ------------------------------------------------------------- smoke test

    /**
     * Creates a throwaway body whose outbound packets are recorded instead of
     * discarded, so the client-facing pairing path can be verified on a server
     * with no client connected.
     *
     * @return the probe, or null when this adapter does not support it
     */
    default NullBody createViewerProbe(String worldName, Vec3d position) { return null; }

    /**
     * Runs the tracker's pairing pass between a probe and a target body.
     *
     * @return true when the probe is paired with the target afterwards
     */
    default boolean pairProbe(NullBody viewer, NullBody target) { return false; }

    /** Packet class names the probe's listener was asked to send. */
    default List<String> probePackets(NullBody viewer) { return java.util.Collections.emptyList(); }

    /**
     * Sends one body's player-info entry to another body's listener.
     *
     * <p>This is the same packet {@link #refreshViewer(UUID)} sends to a joining
     * player, and the client refuses to build a player entity without it. The
     * smoke test uses a probe as the receiving side so the packet path can be
     * proven on a server with no client connected.</p>
     *
     * @return true when the packet was handed to the viewer's listener
     */
    default boolean announceTo(NullBody viewer, NullBody target) { return false; }
}
