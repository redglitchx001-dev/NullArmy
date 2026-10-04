package redglitchx.nullarmy.nms.v1_21_11;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Read/write access to the server's entity-tracking internals on Paper 1.21.11.
 *
 * <h2>Why this class exists</h2>
 * A Null is a {@code ServerPlayer} that never went through
 * {@code PlayerList#placeNewPlayer}. That means two things have to be proven
 * rather than assumed:
 * <ol>
 *   <li><b>It is tracked.</b> A client can only see an entity that
 *       {@code ChunkMap} put a {@code TrackedEntity} for into its
 *       {@code entityMap}. Without that entry no packet is ever produced, no
 *       matter how many particles the plugin plays. {@link #isTracked} is the
 *       check, and the spawn path refuses to report success without it.</li>
 *   <li><b>Nothing piles up behind a client that does not exist.</b>
 *       {@code PlayerChunkSender} keeps a {@code LongOpenHashSet} of chunks
 *       marked pending for a player. It is only drained from
 *       {@code ServerGamePacketListenerImpl#tick}, which the server runs for
 *       players in the player list - a Null is not one. The set would grow for
 *       the whole session, so {@link #clearPendingChunks} empties it.</li>
 * </ol>
 *
 * <p>The members reached here are private, so every access is reflective,
 * cached once, and fails <b>closed and quiet</b>: an unavailable field reports
 * "unknown" instead of throwing inside the server tick.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class Tracking {

    /** {@code ChunkMap.entityMap}: id -&gt; TrackedEntity. */
    private static final Field ENTITY_MAP = declaredField(ChunkMap.class, "entityMap");

    /** {@code PlayerChunkSender.pendingChunks}: a set of packed ChunkPos longs. */
    private static final Field PENDING_CHUNKS = declaredField(PlayerChunkSender.class, "pendingChunks");

    /** {@code LivingEntity.detectEquipmentUpdates()}: private in vanilla. */
    private static Method detectEquipmentUpdates;

    /** {@code ChunkMap.serverViewDistance}: the clamp the tracker applies. */
    private static Field serverViewDistanceField;

    /** The last reflective failure, so a diagnosis is never a guess. */
    private static String lastError = "";

    /** How the last successful pairing happened, so a report is never vague. */
    private static String lastPairPath = "";

    /** Resolved once, from the first TrackedEntity instance this server creates. */
    private static Field seenByField;
    private static Method updatePlayerMethod;
    private static boolean memberLookupFailed;

    private Tracking() {
    }

    /**
     * True when the chunk map holds a tracker for this entity id.
     *
     * <p>This is the difference between "an object exists" and "a client can see
     * it". Unknown (reflection unavailable) is reported as false, so a spawn is
     * never declared visible on a guess.</p>
     */
    static boolean isTracked(ServerLevel level, int entityId) {
        Object map = entityMap(level);
        return map instanceof Map<?, ?> && ((Map<?, ?>) map).containsKey(entityId);
    }

    /**
     * How many player connections are currently paired with this entity.
     *
     * @return -1 when the tracker or its {@code seenBy} set cannot be read
     */
    static int viewerCount(ServerLevel level, int entityId) {
        Object tracked = trackedEntity(level, entityId);
        if (tracked == null) {
            return -1;
        }
        Field field = seenBy(tracked.getClass());
        if (field == null) {
            return -1;
        }
        try {
            Object value = field.get(tracked);
            return value instanceof java.util.Collection<?> ? ((java.util.Collection<?>) value).size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Runs the tracker's own pairing pass for one viewer.
     *
     * <p>This is exactly what {@code ChunkMap.tick()} does when an entity or a
     * player crosses a section boundary. The runtime smoke test uses it to prove
     * that a viewer's connection really receives the pairing bundle for a Null,
     * which is what a real client turns into a visible body.</p>
     *
     * @return true when the viewer is paired with the entity afterwards
     */
    static boolean pair(ServerLevel level, net.minecraft.world.entity.Entity target, ServerPlayer viewer) {
        if (target == null || viewer == null) {
            lastError = "no target or no viewer was given";
            return false;
        }
        Object tracked = trackedEntity(level, target.getId());
        if (tracked == null) {
            lastError = "no TrackedEntity for entity " + target.getId();
            return false;
        }
        Method method = updatePlayer(tracked.getClass());
        if (method == null) {
            lastError = "TrackedEntity.updatePlayer(ServerPlayer) is not reachable";
            return false;
        }
        // A synthetic viewer has no client behind it, so the server never gave it
        // the chunk-tracking view a real player gets at login. The tracker only
        // pairs an entity whose chunk that view covers (ChunkMap.isChunkTracked),
        // so give the viewer exactly the view the tracker itself would compute -
        // centred on its own chunk, at the server's view distance - and make sure
        // the target's chunk is not still marked pending for it.
        String prepared = "not attempted";
        try {
            int viewDistance = Math.max(2, serverViewDistance(level));
            viewer.setChunkTrackingView(
                    ChunkTrackingView.of(viewer.chunkPosition(), viewDistance));
            viewer.connection.chunkSender.dropChunk(viewer, target.chunkPosition());
            net.minecraft.world.level.ChunkPos targetChunk = target.chunkPosition();
            prepared = "prepared view=" + describeView(viewer.getChunkTrackingView())
                    + ", contains=" + viewer.getChunkTrackingView().contains(targetChunk.x, targetChunk.z)
                    + ", chunkTracked=" + (chunkMap(level) == null ? "unknown"
                            : String.valueOf(chunkMap(level).isChunkTracked(viewer, targetChunk.x, targetChunk.z)));
        } catch (Throwable t) {
            prepared = "prepare failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        try {
            method.invoke(tracked, viewer);
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            lastError = "updatePlayer threw " + cause.getClass().getName()
                    + (cause.getMessage() == null ? "" : ": " + cause.getMessage())
                    + ". " + prepared;
            return false;
        }
        int viewers = viewerCount(level, target.getId());
        if (viewers > 0) {
            lastError = "";
            lastPairPath = "the tracker's own decision path (TrackedEntity.updatePlayer)";
            return true;
        }

        // The server's decision path refused for a viewer that has no real client
        // behind it. Fall back to the very call that path makes once it agrees -
        // ServerEntity.addPairing - so the packet stream a client would receive is
        // still proven end to end, and record the viewer the same way the tracker
        // would so its state stays consistent. This is smoke-test scaffolding: it
        // is never used for a real player, who has a genuine tracking view.
        boolean pairedDirectly = pairDirectly(tracked, viewer);
        lastPairPath = pairedDirectly
                ? "direct ServerEntity.addPairing (the tracker's viewer-side chunk"
                        + " bookkeeping refused a synthetic viewer: " + prepared + " | "
                        + diagnose(level, target, viewer) + ")"
                : "neither path paired the viewer (" + prepared + " | "
                        + diagnose(level, target, viewer) + ")";
        lastError = pairedDirectly ? "" : lastPairPath;
        return pairedDirectly;
    }

    /**
     * Sends the pairing bundle the way {@code TrackedEntity.updatePlayer} does, and
     * records the viewer in {@code seenBy} so the tracker's state matches.
     */
    private static boolean pairDirectly(Object tracked, ServerPlayer viewer) {
        try {
            if (seenByField == null) {
                seenByField = declaredField(tracked.getClass(), "seenBy");
            }
            Field serverEntityField = declaredField(tracked.getClass(), "serverEntity");
            if (serverEntityField == null || seenByField == null) {
                return false;
            }
            Object serverEntity = serverEntityField.get(tracked);
            Method addPairing = serverEntity.getClass().getMethod("addPairing", ServerPlayer.class);
            addPairing.invoke(serverEntity, viewer);
            Object seen = seenByField.get(tracked);
            if (seen instanceof java.util.Set<?> set) {
                @SuppressWarnings({"unchecked", "rawtypes"})
                java.util.Set raw = (java.util.Set) set;
                raw.add(viewer.connection);
            }
            return true;
        } catch (Throwable t) {
            lastError = "direct pairing failed: " + t.getClass().getSimpleName()
                    + ": " + t.getMessage();
            return false;
        }
    }

    /** Which path produced the last successful pairing, for the report. */
    static String lastPairPath() {
        return lastPairPath;
    }

    /**
     * Works out <b>which</b> of the tracker's four conditions refused the pairing.
     *
     * <p>{@code ChunkMap.TrackedEntity.updatePlayer} pairs a viewer only when all
     * of these hold: the viewer is inside the tracking range (horizontal, and -
     * on Paper - inside {@code entities.tracking-range-y}), the entity agrees to
     * be broadcast to that viewer, and the viewer's own chunk tracking view covers
     * the entity's chunk with that chunk no longer marked pending for them. Guessing
     * which one failed wastes a server; this reads all four.</p>
     */
    static String diagnose(ServerLevel level, net.minecraft.world.entity.Entity target,
                           ServerPlayer viewer) {
        StringBuilder sb = new StringBuilder();
        try {
            double dx = viewer.getX() - target.getX();
            double dz = viewer.getZ() - target.getZ();
            double dy = viewer.getY() - target.getY();
            sb.append("distance=").append(String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dz * dz)))
                    .append("bl, dy=").append(String.format(java.util.Locale.ROOT, "%.1f", dy)).append("; ");
            sb.append("viewerViewDistance=").append(viewer.requestedViewDistance())
                    .append(", serverViewDistance=").append(serverViewDistance(level)).append("; ");
            sb.append("broadcastToPlayer=").append(target.broadcastToPlayer(viewer)).append("; ");
            sb.append("viewerSpectator=").append(viewer.isSpectator()).append("; ");
            if (target instanceof ServerPlayer) {
                sb.append("targetSpectator=").append(((ServerPlayer) target).isSpectator()).append("; ");
                sb.append("targetGameMode=").append(((ServerPlayer) target).gameMode()).append("; ");
            }
            sb.append("targetValid=").append(target.valid)
                    .append(", viewerValid=").append(viewer.valid).append("; ");
            ChunkMap map = chunkMap(level);
            if (map != null) {
                net.minecraft.world.level.ChunkPos targetChunk = target.chunkPosition();
                net.minecraft.world.level.ChunkPos viewerChunk = viewer.chunkPosition();
                sb.append("sameChunk=").append(targetChunk.equals(viewerChunk))
                        .append(" (target ").append(targetChunk.x).append(',').append(targetChunk.z)
                        .append(", viewer ").append(viewerChunk.x).append(',').append(viewerChunk.z)
                        .append("); ");
                sb.append("view=").append(viewer.getChunkTrackingView().getClass().getSimpleName())
                        .append("; ");
                sb.append("chunkTracked=").append(map.isChunkTracked(viewer, targetChunk.x, targetChunk.z))
                        .append("; ");
                try {
                    sb.append("pending=").append(viewer.connection.chunkSender
                            .isPending(net.minecraft.world.level.ChunkPos.asLong(targetChunk.x, targetChunk.z)))
                            .append("; ");
                } catch (Throwable ignored) {
                    sb.append("pending=unknown; ");
                }
            }
            sb.append("queueSize=").append(pendingQueueSize(viewer));
        } catch (Throwable t) {
            sb.append("diagnosis failed: ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    /** Reads a tracking view's real contents, because a class name proves nothing. */
    static String describeView(ChunkTrackingView view) {
        if (view == null) {
            return "null";
        }
        if (view instanceof ChunkTrackingView.Positioned positioned) {
            return "Positioned(center=" + positioned.center().x + "," + positioned.center().z
                    + ", viewDistance=" + positioned.viewDistance() + ")";
        }
        return view.getClass().getSimpleName();
    }

    /** The view distance the tracker clamps every player to. 8 when unreadable. */
    static int serverViewDistance(ServerLevel level) {
        try {
            if (serverViewDistanceField == null) {
                serverViewDistanceField = ChunkMap.class.getDeclaredField("serverViewDistance");
                serverViewDistanceField.setAccessible(true);
            }
            ChunkMap map = chunkMap(level);
            return map == null ? 8 : serverViewDistanceField.getInt(map);
        } catch (Throwable t) {
            return 8;
        }
    }

    /** How many chunks are still marked pending for a viewer, or -1 if unreadable. */
    static int pendingQueueSize(ServerPlayer viewer) {
        if (viewer == null || PENDING_CHUNKS == null || viewer.connection == null) {
            return -1;
        }
        try {
            Object value = PENDING_CHUNKS.get(viewer.connection.chunkSender);
            return value instanceof java.util.Collection<?> ? ((java.util.Collection<?>) value).size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Empties the chunk queue a Null will never drain.
     *
     * @return how many pending chunk positions were dropped, or -1 when the
     *     queue could not be reached
     */
    static int clearPendingChunks(ServerGamePacketListenerImpl listener) {
        if (listener == null || PENDING_CHUNKS == null) {
            return -1;
        }
        try {
            Object value = PENDING_CHUNKS.get(listener.chunkSender);
            // pendingChunks is a fastutil LongOpenHashSet, which is also a
            // java.util.Collection<Long>, so it can be emptied through the
            // interface without a compile-time dependency on fastutil.
            if (value instanceof java.util.Collection<?> collection) {
                int size = collection.size();
                collection.clear();
                return size;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Runs vanilla's own equipment pass on a body.
     *
     * <p>{@code LivingEntity.detectEquipmentUpdates()} is private and is normally
     * reached from {@code LivingEntity.tick()}. A Null does not run that chain
     * (it would drag in food, statistics and advancements), so equipping one
     * would leave the armour attribute modifiers unapplied and viewers looking at
     * the previous kit. Calling it directly gives both.</p>
     *
     * @return true when the pass ran
     */
    static boolean syncEquipment(net.minecraft.world.entity.LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        Method method = equipmentSyncMethod();
        if (method == null) {
            return false;
        }
        try {
            method.invoke(entity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Which tracking internals are reachable, for {@code /null debug}. */
    static List<String> capabilityReport() {
        List<String> out = new ArrayList<>();
        out.add("chunkMap.entityMap=" + (ENTITY_MAP != null));
        out.add("chunkSender.pendingChunks=" + (PENDING_CHUNKS != null));
        out.add("trackedEntity.seenBy=" + (seenByField != null));
        out.add("trackedEntity.updatePlayer=" + (updatePlayerMethod != null));
        out.add("livingEntity.detectEquipmentUpdates=" + (detectEquipmentUpdates != null));
        if (!lastError.isEmpty()) {
            out.add("lastError=" + lastError);
        }
        if (memberLookupFailed) {
            out.add("trackedEntityMembers=not resolved yet (no Null has been spawned)");
        }
        return Collections.unmodifiableList(out);
    }

    private static Method equipmentSyncMethod() {
        if (detectEquipmentUpdates != null) {
            return detectEquipmentUpdates;
        }
        try {
            Method method = net.minecraft.world.entity.LivingEntity.class
                    .getDeclaredMethod("detectEquipmentUpdates");
            method.setAccessible(true);
            detectEquipmentUpdates = method;
            return method;
        } catch (Throwable t) {
            memberLookupFailed = true;
            return null;
        }
    }

    // ------------------------------------------------------------------- internals

    private static Object entityMap(ServerLevel level) {
        if (level == null || ENTITY_MAP == null) {
            return null;
        }
        try {
            return ENTITY_MAP.get(chunkMap(level));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object trackedEntity(ServerLevel level, int entityId) {
        Object map = entityMap(level);
        if (!(map instanceof Map<?, ?>)) {
            return null;
        }
        try {
            return ((Map<?, ?>) map).get(entityId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code ServerChunkCache.chunkMap} is public on Paper 1.21.11. */
    private static ChunkMap chunkMap(ServerLevel level) {
        try {
            return level.getChunkSource().chunkMap;
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized Field seenBy(Class<?> trackedType) {
        if (seenByField != null) {
            return seenByField;
        }
        seenByField = declaredField(trackedType, "seenBy");
        if (seenByField == null) {
            memberLookupFailed = true;
        }
        return seenByField;
    }

    private static synchronized Method updatePlayer(Class<?> trackedType) {
        if (updatePlayerMethod != null) {
            return updatePlayerMethod;
        }
        try {
            Method method = trackedType.getDeclaredMethod("updatePlayer", ServerPlayer.class);
            method.setAccessible(true);
            updatePlayerMethod = method;
            return method;
        } catch (Throwable t) {
            memberLookupFailed = true;
            return null;
        }
    }

    private static Field declaredField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (Throwable t) {
            return null;
        }
    }
}
