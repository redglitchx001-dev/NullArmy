package redglitchx.nullarmy.plugin.commander;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * The Commander's loadout screen - a plain Bukkit chest inventory.
 *
 * <p><b>No GUI library, no ProtocolLib, no dependencies.</b> This is a normal
 * double chest with a normal {@link InventoryClickEvent} handler, so it works
 * on any Paper build and adds nothing to the jar.</p>
 *
 * <h2>Slot layout</h2>
 * <pre>
 *   row 0 : [0]=helmet [1]=chestplate [2]=leggings [3]=boots [4]=offhand
 *   row 1 : [ 9..17] storage
 *   row 2 : [18..26] storage
 *   row 3 : [27..35] storage
 *   row 4 : [36..44] hotbar
 *   row 5 : [47]=clear  [49]=save  [51]=cancel   [53]=info
 * </pre>
 *
 * <p>Editing this inventory edits the Commander's loadout directly. It is a
 * <b>blueprint editor, never a duplicator</b>: whatever the player puts in is
 * what the Commander spawns with, taken from the player's own items - nothing
 * is created out of nothing.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CommanderInventoryGui implements InventoryHolder {

    public static final int SIZE = 54;

    // Equipment row.
    public static final int SLOT_HELMET = 0;
    public static final int SLOT_CHESTPLATE = 1;
    public static final int SLOT_LEGGINGS = 2;
    public static final int SLOT_BOOTS = 3;
    public static final int SLOT_OFFHAND = 4;

    // Storage: GUI slot 9..35 maps to player inventory index 9..35.
    public static final int STORAGE_START = 9;
    public static final int STORAGE_END = 35;

    // Hotbar: GUI slot 36..44 maps to player inventory index 0..8.
    public static final int HOTBAR_START = 36;
    public static final int HOTBAR_END = 44;

    public static final int BUTTON_CLEAR = 47;
    public static final int BUTTON_SAVE = 49;
    public static final int BUTTON_CANCEL = 51;
    public static final int BUTTON_INFO = 53;

    private final Inventory inventory;
    private final ItemStack[] working;

    public CommanderInventoryGui(String title, ItemStack[] currentLoadout) {
        this.working = new ItemStack[CommanderManager.LOADOUT_SLOTS];
        for (int i = 0; i < CommanderManager.LOADOUT_SLOTS && i < currentLoadout.length; i++) {
            this.working[i] = currentLoadout[i];
        }
        this.inventory = Bukkit.createInventory(this, SIZE, title);
        paint();
    }

    /** Draws the equipment, storage, hotbar and buttons. */
    private void paint() {
        inventory.clear();

        // Equipment: 40=helmet 39=chest 38=legs 37=boots 41=offhand (player indices)
        setTo(SLOT_HELMET, working[39]);
        setTo(SLOT_CHESTPLATE, working[38]);
        setTo(SLOT_LEGGINGS, working[37]);
        setTo(SLOT_BOOTS, working[36]);
        setTo(SLOT_OFFHAND, working[40]);

        for (int p = 9; p <= 35; p++) {
            setTo(STORAGE_START + (p - 9), working[p]);
        }
        for (int p = 0; p <= 8; p++) {
            setTo(HOTBAR_START + p, working[p]);
        }

        inventory.setItem(BUTTON_CLEAR, button(Material.RED_STAINED_GLASS_PANE,
                "Clear loadout", "Empty every slot."));
        inventory.setItem(BUTTON_SAVE, button(Material.LIME_STAINED_GLASS_PANE,
                "Save and close", "Give the Commander this loadout."));
        inventory.setItem(BUTTON_CANCEL, button(Material.GRAY_STAINED_GLASS_PANE,
                "Cancel", "Close without saving."));
        inventory.setItem(BUTTON_INFO, button(Material.BOOK,
                "Commander loadout",
                "Helmet / chest / legs / boots / offhand on the top row.",
                "Storage below, hotbar on row 5.",
                "Items you place here are what the Commander spawns with."));
    }

    private void setTo(int guiSlot, ItemStack stack) {
        inventory.setItem(guiSlot, stack == null ? null : stack.clone());
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(List.of(lore));
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** True when a slot is one of the read-only control buttons. */
    public static boolean isButton(int slot) {
        return slot == BUTTON_CLEAR || slot == BUTTON_SAVE
                || slot == BUTTON_CANCEL || slot == BUTTON_INFO;
    }

    /** True when a GUI slot is one the player may put items into. */
    public static boolean isEditable(int slot) {
        if (isButton(slot)) {
            return false;
        }
        if (slot >= SLOT_HELMET && slot <= SLOT_OFFHAND) {
            return true;
        }
        return (slot >= STORAGE_START && slot <= STORAGE_END)
                || (slot >= HOTBAR_START && slot <= HOTBAR_END);
    }

    /**
     * Translates a GUI slot into the player-inventory index it edits, or -1.
     *
     * <p>Keeping this in one place is what stops the classic off-by-one that
     * silently saves the loadout into the wrong slots.</p>
     */
    public static int toLoadoutIndex(int guiSlot) {
        switch (guiSlot) {
            case SLOT_HELMET: return 39;
            case SLOT_CHESTPLATE: return 38;
            case SLOT_LEGGINGS: return 37;
            case SLOT_BOOTS: return 36;
            case SLOT_OFFHAND: return 40;
            default: break;
        }
        if (guiSlot >= STORAGE_START && guiSlot <= STORAGE_END) {
            return guiSlot; // 9..35 maps straight through
        }
        if (guiSlot >= HOTBAR_START && guiSlot <= HOTBAR_END) {
            return guiSlot - HOTBAR_START; // 36..44 -> 0..8
        }
        return -1;
    }

    /** Writes what the player placed into the working loadout. */
    void commitFromView() {
        for (int slot = 0; slot < SIZE; slot++) {
            if (isButton(slot)) {
                continue;
            }
            int index = toLoadoutIndex(slot);
            if (index < 0 || index >= CommanderManager.LOADOUT_SLOTS) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            working[index] = (stack == null || stack.getType().isAir()) ? null : stack.clone();
        }
    }

    void clearWorking() {
        for (int i = 0; i < working.length; i++) {
            working[i] = null;
        }
        paint();
    }

    ItemStack[] working() {
        return working;
    }

    /** Opens the screen for a player. */
    public void open(Player viewer) {
        viewer.openInventory(inventory);
    }
}
