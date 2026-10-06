package redglitchx.nullarmy.plugin.body;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Firework;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real, Commander-only Elytra pursuit flight.
 *
 * <p>Flight is entered only from an explicit combat pursuit. It uses the
 * Commander's actual Elytra and firework stacks, lets vanilla travel/collision
 * physics move the body, and temporarily swaps (rather than discards) the exact
 * chest item. The chest item and the same, possibly damaged, Elytra are put
 * back when the pursuit ends, lands, or is dismissed.</p>
 */
public final class ElytraFlightController {

    public static final double MIN_DEPLOY_DISTANCE = 18.0D;
    private static final double END_DISTANCE = 9.0D;
    private static final double BOOST_DISTANCE = 20.0D;
    private static final int BOOST_INTERVAL_TICKS = 40;
    private static final int RETRY_INTERVAL_TICKS = 10;
    private static final int ELYTRA_DURABILITY_INTERVAL_TICKS = 20;
    private static final int TAKEOFF_TIMEOUT_TICKS = 60;
    private static final int MAX_FLIGHT_TICKS = 240;

    /** Equipment surface kept small so the lossless swap can be checked in self-test. */
    public interface GearAccess {
        ItemStack chestplate();
        void chestplate(ItemStack item);
        ItemStack storage(int slot);
        void storage(int slot, ItemStack item);
        void store(ItemStack item);
    }

    /** A reversible chest-slot swap. The stored chest item is a full Bukkit copy. */
    public static final class EquipmentSwap {
        private final int elytraSlot;
        private final ItemStack originalChestplate;

        private EquipmentSwap(int elytraSlot, ItemStack originalChestplate) {
            this.elytraSlot = elytraSlot;
            this.originalChestplate = copy(originalChestplate);
        }

        /**
         * Returns the Elytra to its original storage slot when possible, restores
         * the exact previous chestplate, and preserves any item another plugin
         * placed in the chest while the Commander was gliding.
         */
        public void restore(GearAccess gear) {
            if (gear == null || elytraSlot < 0) {
                // An Elytra that was already equipped is not ours to move.
                return;
            }
            ItemStack worn = gear.chestplate();
            boolean wornWasElytra = isElytra(worn);
            boolean wasOriginalChestplate = same(worn, originalChestplate);

            if (worn != null && !worn.getType().isAir() && !wornWasElytra && !wasOriginalChestplate) {
                gear.store(worn.clone());
            }
            gear.chestplate(copy(originalChestplate));

            // Keep Elytra durability accumulated during flight. Never recreate
            // one if it broke or another plugin removed it from the chest slot.
            if (wornWasElytra) {
                ItemStack current = worn.clone();
                ItemStack preferred = gear.storage(elytraSlot);
                if (preferred == null || preferred.getType().isAir()) {
                    gear.storage(elytraSlot, current);
                } else {
                    gear.store(current);
                }
            }
        }
    }

    private static final class Flight {
        private final EquipmentSwap swap;
        private final long startedTick;
        private final long takeoffDeadline;
        private final UUID target;
        private long nextJumpTick;
        private long nextBoostTick;
        private long nextDurabilityTick;
        private boolean glided;

        private Flight(EquipmentSwap swap, long now, boolean alreadyGliding, UUID target) {
            this.swap = swap;
            this.startedTick = now;
            this.takeoffDeadline = now + TAKEOFF_TIMEOUT_TICKS;
            this.target = target;
            this.nextJumpTick = now;
            this.nextBoostTick = now;
            this.nextDurabilityTick = now + ELYTRA_DURABILITY_INTERVAL_TICKS;
            this.glided = alreadyGliding;
        }
    }

    private final NullArmyPlugin plugin;
    private final Map<UUID, Flight> flights = new HashMap<>();

    public ElytraFlightController(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    /** Pure policy gate: an ordinary Null or a retaliatory target never starts flight. */
    public static boolean wantsFlight(boolean commander, boolean explicitPursuit,
                                      double distance, boolean hasElytra) {
        return commander && explicitPursuit && Double.isFinite(distance)
                && distance >= MIN_DEPLOY_DISTANCE && hasElytra;
    }

    /**
     * Takes off toward an explicitly ordered target, or returns null when normal
     * combat should keep control. A gliding intent still goes through NullBody's
     * vanilla travel path; no position is teleported or velocity-clamped here.
     */
    NullBrain.Intent pursue(NullBody body, Player handle, LivingEntity target, double distance,
                            double dirX, double dirZ, long now) {
        if (body == null || handle == null || target == null || body.uuid() == null
                || !isCommander(body)) {
            return null;
        }
        UUID id = body.uuid();
        Flight flight = flights.get(id);
        if (flight == null) {
            boolean hasElytra = hasElytra(handle.getInventory());
            if (!wantsFlight(true, true, distance, hasElytra) || unsafeToTakeOff(handle, body)) {
                return null;
            }
            EquipmentSwap swap = equip(new PlayerGear(handle));
            if (swap == null) {
                return null;
            }
            flight = new Flight(swap, now, handle.isGliding(), target.getUniqueId());
            flights.put(id, flight);
        }

        if (!target.getUniqueId().equals(flight.target)
                || !target.isValid() || target.isDead()
                || target.getWorld() != handle.getWorld()
                || distance <= END_DISTANCE
                || now - flight.startedTick > MAX_FLIGHT_TICKS
                || handle.isInWater() || handle.isInLava()) {
            stop(id, handle);
            return null;
        }

        ItemStack chest = handle.getInventory().getChestplate();
        if (!isUsableElytra(chest)) {
            stop(id, handle);
            return null;
        }

        if (!handle.isGliding() && !body.onGround() && body.velocity().y() < -0.02D
                && body.fallDistance() >= 0.4D) {
            // The body first jumps into open air, then opens the real Elytra on
            // the downstroke. The server's own travelFallFlying handles glide.
            handle.setGliding(true);
        }
        if (handle.isGliding()) {
            if (!flight.glided) {
                flight.nextDurabilityTick = now + ELYTRA_DURABILITY_INTERVAL_TICKS;
            }
            flight.glided = true;
            if (now >= flight.nextDurabilityTick) {
                boolean usable;
                try {
                    usable = damageElytraForFlight(handle);
                } catch (Throwable t) {
                    plugin.getLogger().fine("[NullArmy] Commander Elytra wear skipped: "
                            + Guard.describe(t));
                    stop(id, handle);
                    return null;
                }
                if (!usable) {
                    // Vanilla stops at one durability point instead of deleting
                    // the wings. Restore the borrowed gear and end this flight.
                    stop(id, handle);
                    return null;
                }
                flight.nextDurabilityTick = now + ELYTRA_DURABILITY_INTERVAL_TICKS;
            }
        }

        if (flight.glided && (body.onGround() || !handle.isGliding())) {
            stop(id, handle);
            return null;
        }
        if (!flight.glided && now >= flight.takeoffDeadline) {
            stop(id, handle);
            return null;
        }

        NullBrain.Intent intent = new NullBrain.Intent();
        double horizontal = Math.hypot(dirX, dirZ);
        if (horizontal > 1.0e-6D) {
            intent.dx = dirX / horizontal;
            intent.dz = dirZ / horizontal;
        }
        intent.gait = NullBody.GAIT_SPRINT;
        intent.look = new Vec3d(target.getEyeLocation().getX(), target.getEyeLocation().getY() - 0.2D,
                target.getEyeLocation().getZ());
        if (!handle.isGliding() && body.onGround() && now >= flight.nextJumpTick) {
            intent.jump = true;
            flight.nextJumpTick = now + 20L;
        }
        if (handle.isGliding()) {
            boostWithRealRocket(handle, flight, distance, now);
        }
        return intent;
    }

    /** Stops one body's flight and restores its inventory before it is removed. */
    public void stop(NullBody body, Player handle) {
        stop(body == null ? null : body.uuid(), handle);
    }

    /** Stops a flight from a lifecycle event that has the Bukkit entity UUID. */
    public void stop(UUID id, Player handle) {
        if (id == null) {
            return;
        }
        Flight flight = flights.remove(id);
        if (flight == null) {
            return;
        }
        Player resolved = handle;
        if (resolved == null && org.bukkit.Bukkit.getEntity(id) instanceof Player) {
            resolved = (Player) org.bukkit.Bukkit.getEntity(id);
        }
        final Player player = resolved;
        if (player == null) {
            return;
        }
        // Leave already-fired rockets to their ordinary vanilla fuse and
        // explosion. Flight teardown changes the state/gear, not projectiles.
        Guard.attempt(plugin.getLogger(), "stopping Commander gliding", () -> player.setGliding(false));
        Guard.attempt(plugin.getLogger(), "restoring Commander flight equipment",
                () -> flight.swap.restore(new PlayerGear(player)));
    }

    /** Restores any still-tracked flight sessions; called before brain shutdown. */
    public void stopAll() {
        for (UUID id : new java.util.ArrayList<>(flights.keySet())) {
            stop(id, null);
        }
    }

    public boolean active(UUID id) {
        return id != null && flights.containsKey(id);
    }

    /** Starts with a real stored Elytra, or adopts one already worn. */
    public static EquipmentSwap equip(GearAccess gear) {
        if (gear == null) {
            return null;
        }
        ItemStack chest = gear.chestplate();
        if (isUsableElytra(chest)) {
            // It was already equipped; there is no prior chest item to replace.
            return new EquipmentSwap(-1, null);
        }
        int slot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack item = gear.storage(i);
            if (isUsableElytra(item)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return null;
        }
        ItemStack elytra = gear.storage(slot);
        ItemStack previousChest = chest == null || chest.getType().isAir() ? null : chest.clone();
        gear.chestplate(elytra.clone());
        gear.storage(slot, null);
        return new EquipmentSwap(slot, previousChest);
    }

    private boolean isCommander(NullBody body) {
        return plugin.commander() != null && plugin.commander().body() == body;
    }

    private static boolean hasElytra(PlayerInventory inventory) {
        if (inventory == null) {
            return false;
        }
        if (isUsableElytra(inventory.getChestplate())) {
            return true;
        }
        for (int slot = 0; slot < 36; slot++) {
            if (isUsableElytra(inventory.getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean unsafeToTakeOff(Player handle, NullBody body) {
        if (handle.isInWater() || handle.isInLava() || handle.isInsideVehicle()) {
            return true;
        }
        if (!body.onGround() || handle.isGliding()) {
            return false;
        }
        Location at = handle.getLocation();
        Block head = handle.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + 1, at.getBlockZ());
        Block above = head.getRelative(0, 1, 0);
        return !head.isPassable() || !above.isPassable();
    }

    private void boostWithRealRocket(Player handle, Flight flight, double distance, long now) {
        if (distance <= BOOST_DISTANCE || now < flight.nextBoostTick) {
            return;
        }
        PlayerInventory inventory = handle.getInventory();
        int slot = Bodies.find(inventory, Material.FIREWORK_ROCKET);
        if (slot < 0) {
            return;
        }
        ItemStack stack = inventory.getItem(slot);
        if (stack == null || stack.getType() != Material.FIREWORK_ROCKET || stack.getAmount() <= 0) {
            return;
        }
        // Conserve the last two rockets for the next explicit engagement.
        if (stack.getAmount() <= 2 && Bodies.count(inventory, Material.FIREWORK_ROCKET) <= 2) {
            flight.nextBoostTick = now + BOOST_INTERVAL_TICKS;
            return;
        }

        Firework rocket = null;
        try {
            ItemStack oneRocket = stack.clone();
            oneRocket.setAmount(1);
            rocket = handle.getWorld().spawn(handle.getLocation(), Firework.class);
            rocket.setItem(oneRocket);
            if (!rocket.setAttachedTo(handle)) {
                rocket.remove();
                flight.nextBoostTick = now + RETRY_INTERVAL_TICKS;
                return;
            }
            ItemStack remaining = stack.clone();
            if (remaining.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                remaining.setAmount(remaining.getAmount() - 1);
                inventory.setItem(slot, remaining);
            }
            flight.nextBoostTick = now + BOOST_INTERVAL_TICKS;
        } catch (Throwable t) {
            if (rocket != null && rocket.isValid()) {
                rocket.remove();
            }
            flight.nextBoostTick = now + RETRY_INTERVAL_TICKS;
            plugin.getLogger().fine("[NullArmy] Commander rocket boost skipped: " + Guard.describe(t));
        }
    }

    /** Applies the vanilla one-point-per-second Elytra wear absent on an unlisted ServerPlayer. */
    private static boolean damageElytraForFlight(Player handle) {
        ItemStack elytra = handle.getInventory().getChestplate();
        if (!isUsableElytra(elytra)) {
            return false;
        }
        org.bukkit.inventory.meta.ItemMeta meta = elytra.getItemMeta();
        if (!(meta instanceof Damageable) || meta.isUnbreakable()) {
            return true;
        }
        int unbreaking = Math.max(0, elytra.getEnchantmentLevel(Enchantment.UNBREAKING));
        if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) {
            return true;
        }

        int maximum = elytra.getType().getMaxDurability();
        int damage = ((Damageable) meta).getDamage();
        int nextDamage = Math.min(maximum - 1, damage + 1);
        ((Damageable) meta).setDamage(nextDamage);
        elytra.setItemMeta(meta);
        handle.getInventory().setChestplate(elytra);
        return nextDamage < maximum - 1;
    }

    private static boolean isElytra(ItemStack item) {
        return item != null && item.getType() == Material.ELYTRA && item.getAmount() > 0;
    }

    private static boolean isUsableElytra(ItemStack item) {
        if (!isElytra(item)) {
            return false;
        }
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof Damageable)) {
            return true;
        }
        int maximum = item.getType().getMaxDurability();
        // Elytra stop working at one remaining durability and are never broken.
        return maximum <= 1 || ((Damageable) meta).getDamage() < maximum - 1;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() ? null : item.clone();
    }

    private static boolean same(ItemStack left, ItemStack right) {
        if (left == null || left.getType().isAir()) {
            return right == null || right.getType().isAir();
        }
        return right != null && !right.getType().isAir()
                && left.getAmount() == right.getAmount() && left.isSimilar(right);
    }

    /** Bukkit-backed equipment adapter; overflow is dropped rather than lost. */
    private static final class PlayerGear implements GearAccess {
        private final Player player;
        private final PlayerInventory inventory;

        private PlayerGear(Player player) {
            this.player = player;
            this.inventory = player.getInventory();
        }

        @Override
        public ItemStack chestplate() {
            return inventory.getChestplate();
        }

        @Override
        public void chestplate(ItemStack item) {
            inventory.setChestplate(copy(item));
        }

        @Override
        public ItemStack storage(int slot) {
            return slot >= 0 && slot < 36 ? inventory.getItem(slot) : null;
        }

        @Override
        public void storage(int slot, ItemStack item) {
            if (slot >= 0 && slot < 36) {
                inventory.setItem(slot, copy(item));
            }
        }

        @Override
        public void store(ItemStack item) {
            if (item == null || item.getType().isAir()) {
                return;
            }
            Map<Integer, ItemStack> left = inventory.addItem(item.clone());
            for (ItemStack overflow : left.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), overflow);
            }
        }
    }
}
