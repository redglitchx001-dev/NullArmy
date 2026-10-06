package redglitchx.nullarmy.plugin.totem;

import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.item.SummonItems;
import redglitchx.nullarmy.plugin.util.Guard;

/**
 * Watches for the one thing that ends a NullArmy: its Totem Of Null being
 * consumed or destroyed.
 *
 * <h2>What counts as "destroyed" - exactly</h2>
 * <ol>
 *   <li><b>It pops.</b> A player or Null dies while holding the tagged totem,
 *       vanilla resurrects them and shrinks the item by one. That is
 *       {@link EntityResurrectEvent} with our tag in the hand the event names,
 *       and it is not cancelled. The item is gone, so the army goes with it.</li>
 *   <li><b>A dropped totem is destroyed.</b> Fire, lava, an explosion, cactus or
 *       any other damage to the item entity ({@link EntityDamageEvent} on an
 *       {@link Item} holding our tag, not cancelled). Vanilla destroys an item
 *       entity on any damage it does not cancel, so this is the moment it stops
 *       existing.</li>
 *   <li><b>A dropped totem despawns.</b> The five-minute item lifetime ends
 *       ({@link ItemDespawnEvent}). Gated by {@code totem.despawn-triggers-shutdown}
 *       because walking away from a dropped totem is an easy thing to do by
 *       accident.</li>
 * </ol>
 *
 * <h2>What does NOT count</h2>
 * <ul>
 *   <li>Moving it between inventory slots, chests, hoppers or shulker boxes -
 *       nothing is consumed, so nothing happens. There is deliberately no
 *       inventory listener here.</li>
 *   <li>Renaming, enchanting or repairing it - the persistent-data tag survives
 *       all three, and recognition is by tag.</li>
 *   <li>Dropping it on the ground, or throwing it - it still exists.</li>
 *   <li>An ordinary Totem of Undying - no tag, no exact name, no effect.</li>
 *   <li>A {@code /null reload}, a restart, or the plugin disabling.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class TotemWatcher implements Listener, Reloadable {

    private final NullArmyPlugin plugin;
    private PluginConfig config;

    public TotemWatcher(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    /** How many shutdowns this watcher has triggered this session. */
    private int triggered;

    public int triggeredCount() { return triggered; }

    /** True when the shutdown feature is switched on in config. */
    private boolean enabled() {
        return config == null || config.totemShutdownEnabled();
    }

    // ------------------------------------------------------------------- the pop

    /**
     * The totem activated: vanilla is about to consume it.
     *
     * <p>Runs at MONITOR so every other plugin has had its say, and only acts when
     * the event was not cancelled - a cancelled resurrect means the item was never
     * consumed, so the army must not go out.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onResurrect(EntityResurrectEvent event) {
        Guard.attempt(plugin.getLogger(), "totem resurrect watch", () -> {
            if (event == null || event.isCancelled() || !enabled()) {
                return;
            }
            LivingEntity entity = event.getEntity();
            if (entity == null) {
                return;
            }
            ItemStack held = heldItem(entity, event.getHand());
            if (!SummonItems.isTotemOfNull(held)) {
                // An ordinary Totem of Undying, or nothing at all.
                return;
            }
            silenceTotemPop(entity);
            trigger("The Totem Of Null popped for " + nameOf(entity));
        });
    }

    /**
     * Suppress the pop before vanilla finishes the resurrect pipeline, then send
     * a stop packet as a client-side fallback. The entity's prior silent state
     * is restored two ticks later; the Totem Of Null is a shutdown trigger, not
     * a sound effect.
     */
    private void silenceTotemPop(LivingEntity entity) {
        if (entity == null || entity.getWorld() == null) {
            return;
        }
        org.bukkit.World world = entity.getWorld();
        org.bukkit.Location at = entity.getLocation().clone();
        boolean wasSilent = entity.isSilent();
        try {
            if (!wasSilent) {
                entity.setSilent(true);
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> Guard.attempt(plugin.getLogger(),
                    "silencing the Totem Of Null sound", () -> {
                        try {
                            for (Player viewer : world.getPlayers()) {
                                if (viewer.getLocation().distanceSquared(at) <= 64.0D * 64.0D) {
                                    viewer.stopSound(Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS);
                                }
                            }
                        } finally {
                            if (!wasSilent && entity.isValid()) {
                                entity.setSilent(false);
                            }
                        }
                    }), 2L);
        } catch (Throwable t) {
            if (!wasSilent) {
                try {
                    entity.setSilent(false);
                } catch (Throwable ignored) {
                    // Preserve the original failure; the entity may already be gone.
                }
            }
            plugin.getLogger().fine("[NullArmy] totem sound suppression deferred: " + Guard.describe(t));
        }
    }

    /** The item in the hand vanilla named, or the other hand, or nothing. */
    private ItemStack heldItem(LivingEntity entity, org.bukkit.inventory.EquipmentSlot hand) {
        try {
            EntityEquipment equipment = entity.getEquipment();
            if (equipment == null) {
                return null;
            }
            ItemStack primary = hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                    ? equipment.getItemInOffHand() : equipment.getItemInMainHand();
            if (SummonItems.isTotemOfNull(primary)) {
                return primary;
            }
            ItemStack secondary = hand == org.bukkit.inventory.EquipmentSlot.OFF_HAND
                    ? equipment.getItemInMainHand() : equipment.getItemInOffHand();
            return SummonItems.isTotemOfNull(secondary) ? secondary : primary;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------- destroyed

    /**
     * A dropped totem taking damage.
     *
     * <p>Item entities have no health: any damage vanilla does not cancel
     * destroys the stack, so this is the moment of destruction, not a warning.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        Guard.attempt(plugin.getLogger(), "totem damage watch", () -> {
            if (event == null || event.isCancelled() || !enabled()) {
                return;
            }
            Entity entity = event.getEntity();
            if (!(entity instanceof Item)) {
                return;
            }
            ItemStack stack = ((Item) entity).getItemStack();
            if (!SummonItems.isTotemOfNull(stack)) {
                return;
            }
            trigger("The Totem Of Null was destroyed by "
                    + event.getCause().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        });
    }

    /** A dropped totem running out of its five minutes. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDespawn(ItemDespawnEvent event) {
        Guard.attempt(plugin.getLogger(), "totem despawn watch", () -> {
            if (event == null || event.isCancelled() || !enabled()) {
                return;
            }
            if (config != null && !config.totemDespawnTriggersShutdown()) {
                return;
            }
            Item entity = event.getEntity();
            ItemStack stack = entity == null ? null : entity.getItemStack();
            if (!SummonItems.isTotemOfNull(stack)) {
                return;
            }
            trigger("The Totem Of Null despawned on the ground");
        });
    }

    // ------------------------------------------------------------------ trigger

    /**
     * Starts the global shutdown.
     *
     * <p>Idempotent: a totem that pops twice, or that is destroyed while the army
     * is already going out, does not restart or double the sequence.</p>
     */
    private void trigger(String why) {
        if (plugin.shutdown() == null) {
            return;
        }
        boolean started = Guard.attempt(plugin.getLogger(), "starting the totem shutdown",
                () -> plugin.shutdown().start(why));
        if (started && plugin.shutdown().isRunning()) {
            triggered++;
        }
        plugin.getLogger().info("[NullArmy] " + why + " (shutdown "
                + (plugin.shutdown().isRunning() ? "running" : "not started: "
                        + plugin.shutdown().describe()) + ").");
    }

    /** Who the totem belonged to, in words. */
    private String nameOf(LivingEntity entity) {
        if (entity instanceof Player) {
            return ((Player) entity).getName();
        }
        try {
            return entity.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        } catch (Throwable t) {
            return "someone";
        }
    }
}
