package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;

import java.util.UUID;

/**
 * Small helpers for reaching a Null's server-side player through the Bukkit API.
 *
 * <p>A Null is a real {@code ServerPlayer} in the world's entity index, so
 * {@link Bukkit#getEntity(UUID)} returns its {@code CraftPlayer}. Everything done
 * through it - inventory, attributes, swings, item use, block breaking - runs the
 * same server code (and fires the same events) as for a human player, which is
 * the whole point: no shortcut exists that a protection plugin cannot see.</p>
 *
 * <p>Slot numbering is the player-inventory numbering used everywhere in the
 * plugin: 0-8 hotbar, 9-35 storage, 36 boots, 37 leggings, 38 chestplate,
 * 39 helmet, 40 offhand.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Bodies {

    public static final int SLOTS = 41;

    private Bodies() {
    }

    /** The body's Bukkit player, or null when it cannot be resolved. */
    public static Player player(NullBody body) {
        if (body == null) {
            return null;
        }
        try {
            UUID id = body.uuid();
            if (id == null) {
                return null;
            }
            Entity entity = Bukkit.getEntity(id);
            return entity instanceof Player ? (Player) entity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The body's location in its world, or null. */
    public static Location location(NullBody body) {
        Player handle = player(body);
        return handle == null ? null : handle.getLocation();
    }

    /** A location for a position in a named world, or null when the world is gone. */
    public static Location at(String worldName, Vec3d position) {
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null || position == null) {
            return null;
        }
        return new Location(world, position.x(), position.y(), position.z());
    }

    /** The stack in a player slot (36-40 are armour and offhand). Never null. */
    public static ItemStack get(PlayerInventory inv, int slot) {
        ItemStack stack;
        switch (slot) {
            case 36: stack = inv.getBoots(); break;
            case 37: stack = inv.getLeggings(); break;
            case 38: stack = inv.getChestplate(); break;
            case 39: stack = inv.getHelmet(); break;
            case 40: stack = inv.getItemInOffHand(); break;
            default: stack = slot >= 0 && slot < 36 ? inv.getItem(slot) : null; break;
        }
        return stack == null ? new ItemStack(Material.AIR) : stack;
    }

    /** Writes a player slot; null or air empties it. */
    public static void set(PlayerInventory inv, int slot, ItemStack stack) {
        ItemStack value = stack == null || stack.getType().isAir() ? null : stack;
        switch (slot) {
            case 36: inv.setBoots(value); break;
            case 37: inv.setLeggings(value); break;
            case 38: inv.setChestplate(value); break;
            case 39: inv.setHelmet(value); break;
            case 40: inv.setItemInOffHand(value); break;
            default:
                if (slot >= 0 && slot < 36) {
                    inv.setItem(slot, value);
                }
                break;
        }
    }

    /** A deep copy of all 41 slots. */
    public static ItemStack[] snapshot(PlayerInventory inv) {
        ItemStack[] out = new ItemStack[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = get(inv, i);
            out[i] = stack.getType().isAir() ? null : stack.clone();
        }
        return out;
    }

    /** Writes all 41 slots at once (null entries empty the slot). */
    public static void apply(PlayerInventory inv, ItemStack[] slots) {
        for (int i = 0; i < SLOTS; i++) {
            set(inv, i, slots != null && i < slots.length && slots[i] != null ? slots[i].clone() : null);
        }
    }

    /** How many items of a material the inventory holds across all slots. */
    public static int count(PlayerInventory inv, Material material) {
        int n = 0;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = get(inv, i);
            if (stack.getType() == material) {
                n += stack.getAmount();
            }
        }
        return n;
    }

    /** The first hotbar/storage slot holding the material, or -1. */
    public static int find(PlayerInventory inv, Material material) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getType() == material) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Puts the stack of {@code from} into the hotbar and selects it, swapping
     * with whatever was there - the way a player presses a number key after
     * dragging an item down.
     *
     * @return the hotbar slot now selected, or -1
     */
    /**
     * Places ONE block by hand, the way a player does.
     *
     * <p>The Null holds the block, swings, {@link BlockPlaceEvent} is fired so a
     * protection plugin gets its say, and only then is the block written. The
     * stack in the hand is really decremented, so the block genuinely comes out
     * of the body's inventory - the rule every build in this plugin obeys.</p>
     *
     * @return true when a block was really placed
     */
    public static boolean placeOne(Player handle, Block block, Material material) {
        if (handle == null || block == null || material == null || !material.isBlock()) {
            return false;
        }
        if (block.getType() == material) {
            return false;
        }
        if (!block.getType().isAir() && !block.isReplaceable()) {
            return false;
        }
        PlayerInventory inv = handle.getInventory();
        int slot = find(inv, material);
        if (slot < 0) {
            return false;
        }
        Block against = null;
        for (BlockFace face : new BlockFace[] {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH,
                BlockFace.EAST, BlockFace.WEST, BlockFace.UP}) {
            Block neighbour = block.getRelative(face);
            if (neighbour.getType().isSolid()) {
                against = neighbour;
                break;
            }
        }
        if (against == null) {
            return false; // nothing to place it against: a player could not either
        }
        // hold() may move the stack into the hotbar: the slot it ends up in is
        // the one that has to be charged, not the one it was found in.
        int held = hold(handle, slot);
        ItemStack inHand = inv.getItem(held < 0 ? slot : held);
        BlockState replaced = block.getState();
        handle.swingMainHand();
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, against, inHand, handle, true,
                EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            return false;
        }
        block.setType(material, true);
        try {
            block.getWorld().playSound(block.getLocation().add(0.5D, 0.5D, 0.5D),
                    block.getBlockData().getSoundGroup().getPlaceSound(), 1.0F, 0.8F);
        } catch (Throwable ignored) {
            block.getWorld().playSound(block.getLocation().add(0.5D, 0.5D, 0.5D),
                    Sound.BLOCK_STONE_PLACE, 1.0F, 0.8F);
        }
        // Bukkit hands out a COPY of the stack, so the block has to be paid
        // for by writing it back - otherwise a Null places forever.
        if (inHand.getAmount() <= 1) {
            inv.setItem(held < 0 ? slot : held, null);
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
            inv.setItem(held < 0 ? slot : held, inHand);
        }
        return true;
    }

    /**
     * Breaks ONE block by hand.
     *
     * <p>{@link BlockBreakEvent} is fired first, so griefing stays the owner's
     * decision and a protection plugin can refuse it; nothing is ever
     * bulk-removed and nothing is ever set to air behind the event's back.</p>
     *
     * @return true when the block was really broken
     */
    public static boolean breakOne(Player handle, Block block) {
        if (handle == null || block == null || block.getType().isAir()) {
            return false;
        }
        BlockBreakEvent event = new BlockBreakEvent(block, handle);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        handle.swingMainHand();
        block.breakNaturally(handle.getInventory().getItemInMainHand());
        return true;
    }

    public static int hold(Player handle, int from) {
        if (handle == null || from < 0 || from >= 36) {
            return -1;
        }
        PlayerInventory inv = handle.getInventory();
        int target = from;
        if (from >= 9) {
            target = inv.getHeldItemSlot();
            ItemStack moving = inv.getItem(from);
            ItemStack held = inv.getItem(target);
            inv.setItem(target, moving);
            inv.setItem(from, held);
        }
        inv.setHeldItemSlot(target);
        return target;
    }
}
