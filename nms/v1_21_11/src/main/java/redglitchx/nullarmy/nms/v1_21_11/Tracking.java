package redglitchx.nullarmy.nms.v1_21_11;

import net.minecraft.server.level.ChunkMap;
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
    static boolean pair(ServerLevel level, int entityId, ServerPlayer viewer) {
        Object tracked = trackedEntity(level, entityId);
        if (tracked == null || viewer == null) {
            return false;
        }
        Method method = updatePlayer(tracked.getClass());
        if (method == null) {
            return false;
        }
        try {
            method.invoke(tracked, viewer);
        } catch (Throwable t) {
            return false;
        }
        return viewerCount(level, entityId) > 0;
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
