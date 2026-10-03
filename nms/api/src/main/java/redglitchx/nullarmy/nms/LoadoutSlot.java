package redglitchx.nullarmy.nms;

import java.util.Objects;

/**
 * One occupied inventory slot of a Null, in a version-neutral form.
 *
 * <p>{@code nms:api} deliberately has <b>no dependency on the server</b>, so a
 * loadout cannot travel through it as Bukkit {@code ItemStack}s. Instead each
 * slot is described by a material <i>name</i> and a count, and the version
 * adapter resolves the name against whatever server it is running on. An
 * unknown material is skipped rather than throwing - a missing sword must
 * never stop a summon.</p>
 *
 * <p>Slot indices follow the player inventory: 0-8 hotbar, 9-35 storage,
 * 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand.</p>
 *
 * <p><b>Limitation, stated honestly:</b> only the material and the count
 * survive. Enchantments, custom names and NBT are not carried across this
 * boundary. That is a deliberate trade to keep the SPI dependency-free.</p>
 *
 * <p>Note: written as a plain final class rather than a record so it stays
 * parseable by the syntax checker used in this repository.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class LoadoutSlot {

    public static final int SLOT_BOOTS = 36;
    public static final int SLOT_LEGGINGS = 37;
    public static final int SLOT_CHESTPLATE = 38;
    public static final int SLOT_HELMET = 39;
    public static final int SLOT_OFFHAND = 40;
    public static final int MAX_SLOT = 40;

    private final int slot;
    private final String material;
    private final int count;

    public LoadoutSlot(int slot, String material, int count) {
        if (slot < 0 || slot > MAX_SLOT) {
            throw new IllegalArgumentException("slot out of range 0.." + MAX_SLOT + ": " + slot);
        }
        if (material == null || material.trim().isEmpty()) {
            throw new IllegalArgumentException("material must not be blank");
        }
        this.slot = slot;
        this.material = material.trim();
        this.count = Math.max(1, Math.min(64, count));
    }

    public static LoadoutSlot of(int slot, String material, int count) {
        return new LoadoutSlot(slot, material, count);
    }

    public int slot() { return slot; }
    public String material() { return material; }
    public int count() { return count; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LoadoutSlot)) return false;
        LoadoutSlot other = (LoadoutSlot) o;
        return slot == other.slot && count == other.count && Objects.equals(material, other.material);
    }

    @Override
    public int hashCode() {
        return Objects.hash(slot, material, count);
    }

    @Override
    public String toString() {
        return "LoadoutSlot{" + slot + "," + material + "x" + count + "}";
    }
}
