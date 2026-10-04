package redglitchx.nullarmy.plugin.spectacle;

import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;

import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Every entity NullArmy creates for a spectacle feature, in one place.
 *
 * <p>The rule this class exists to enforce: <b>if the plugin spawned it, the
 * plugin can remove it.</b> Before this existed, {@code /null stop} and
 * {@code onDisable} had no idea what was in the world, so a failed fire or a
 * restart could leave TNT and minecarts behind forever.</p>
 *
 * <p>It also owns the one thing only a listener can do: when
 * {@code wither-cannon.blocks-damage} is not fully opted in, the explosions of
 * tracked entities are stripped of their block list, so the show happens and
 * the map is untouched.</p>
 *
 * <p>Nothing here runs off the main thread, and nothing here throws.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class EntityRegistry implements Listener, Reloadable {

    private final NullArmyPlugin plugin;
    private final Map<UUID, Entity> tracked = new LinkedHashMap<>();

    private int maxTracked;

    public EntityRegistry(NullArmyPlugin plugin) {
        this.plugin = plugin;
        PluginConfig config = plugin.pluginConfig();
        this.maxTracked = config == null ? 256 : config.maxTrackedEntities();
    }

    @Override
    public void onConfigReloaded(PluginConfig config) {
        if (config != null) {
            this.maxTracked = config.maxTrackedEntities();
        }
    }

    public int maxTracked() { return maxTracked; }

    /**
     * Starts tracking an entity.
     *
     * @return false when the ceiling is reached - the caller must then decide
     *     whether to skip or remove the entity, never just add it anyway
     */
    public boolean track(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            if (tracked.size() >= maxTracked && !tracked.containsKey(entity.getUniqueId())) {
                return false;
            }
            tracked.put(entity.getUniqueId(), entity);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void untrack(Entity entity) {
        if (entity == null) {
            return;
        }
        try {
            tracked.remove(entity.getUniqueId());
        } catch (Throwable ignored) {
            // A vanished entity is already untracked in spirit.
        }
    }

    public void untrack(UUID id) {
        if (id != null) {
            tracked.remove(id);
        }
    }

    public boolean isTracked(Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            return tracked.containsKey(entity.getUniqueId());
        } catch (Throwable t) {
            return false;
        }
    }

    public int size() { return tracked.size(); }

    /** Drops entries whose entity is gone. Returns how many were dropped. */
    public int sweep() {
        int removed = 0;
        try {
            List<UUID> stale = new ArrayList<>();
            for (Map.Entry<UUID, Entity> entry : tracked.entrySet()) {
                Entity entity = entry.getValue();
                if (entity == null || entity.isDead() || !entity.isValid()) {
                    stale.add(entry.getKey());
                }
            }
            for (UUID id : stale) {
                tracked.remove(id);
                removed++;
            }
        } catch (Throwable t) {
            // Never let bookkeeping escape into the tick loop.
            tracked.clear();
        }
        return removed;
    }

    /** Removes every tracked entity from the world. Returns how many were removed. */
    public int sweepAll() {
        int removed = 0;
        try {
            for (Entity entity : new ArrayList<>(tracked.values())) {
                if (entity == null) {
                    continue;
                }
                try {
                    if (entity.isValid()) {
                        entity.remove();
                        removed++;
                    }
                } catch (Throwable ignored) {
                    // Keep going: one stubborn entity must not block the sweep.
                }
            }
        } catch (Throwable t) {
            if (plugin != null) {
                plugin.getLogger().warning("[NullArmy] Entity sweep problem: " + Guard.describe(t));
            }
        } finally {
            tracked.clear();
        }
        return removed;
    }

    /** Entity type names currently tracked, for {@code /null status} and {@code /null debug}. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        try {
            for (Entity entity : tracked.values()) {
                if (entity == null) {
                    continue;
                }
                out.add(entity.getType().name() + " @ "
                        + (int) Math.floor(entity.getLocation().getX()) + ","
                        + (int) Math.floor(entity.getLocation().getY()) + ","
                        + (int) Math.floor(entity.getLocation().getZ()));
            }
        } catch (Throwable ignored) {
            // Diagnostics must never break the command that asked for them.
        }
        return out;
    }

    /**
     * Keeps tracked explosions inside the configured policy.
     *
     * <p>When block damage has not been fully opted into, the explosion still
     * plays - particles, sound, knockback, entity damage - but every block it
     * would have destroyed is removed from the list first.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        Guard.attempt(plugin.getLogger(), "explosion policy", () -> {
            Entity entity = event.getEntity();
            if (!isTracked(entity)) {
                return;
            }
            untrack(entity);
            PluginConfig config = plugin.pluginConfig();
            boolean mayBreakBlocks = config != null && config.witherCannonBlocksDamage();
            if (!mayBreakBlocks) {
                event.blockList().clear();
                event.setYield(0.0F);
            }
        });
    }
}
