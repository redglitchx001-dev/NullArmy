package redglitchx.nullarmy.core.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The default equipment every Null and the Commander start with.
 *
 * <p>Iron chestplate, shield in the offhand, and a hotbar of iron sword, bow,
 * arrows, golden apples, cooked food, iron pickaxe, ender pearls, a water bucket
 * and torches. Slot numbers are the real player-inventory numbers, so armour and
 * the offhand land in the slots Bukkit will not write through {@code setItem}:
 * 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 offhand.</p>
 *
 * <p>Config form is one string per item: {@code slot:MATERIAL[:count]}. Parsing
 * lives here so the same rules run in the plugin and in the tests.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class DefaultKit {

    /** One kit entry: a slot, a material name and a count. */
    public static final class Item {
        private final int slot;
        private final String material;
        private final int count;

        public Item(int slot, String material, int count) {
            if (slot < 0 || slot > 40) {
                throw new IllegalArgumentException("slot out of range 0..40: " + slot);
            }
            if (material == null || material.trim().isEmpty()) {
                throw new IllegalArgumentException("material must not be blank");
            }
            this.slot = slot;
            this.material = material.trim().toUpperCase(java.util.Locale.ROOT);
            this.count = Math.max(1, Math.min(64, count));
        }

        public int slot() { return slot; }
        public String material() { return material; }
        public int count() { return count; }

        /** The config form of this entry. */
        public String toConfig() { return slot + ":" + material + ":" + count; }

        @Override
        public String toString() { return toConfig(); }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Item)) {
                return false;
            }
            Item item = (Item) other;
            return slot == item.slot && count == item.count && material.equals(item.material);
        }

        @Override
        public int hashCode() { return slot * 31 + material.hashCode() * 7 + count; }
    }

    /** The shipped default kit. Immutable. */
    public static final List<Item> DEFAULT;

    static {
        List<Item> kit = new ArrayList<>();
        kit.add(new Item(0, "IRON_SWORD", 1));
        kit.add(new Item(1, "BOW", 1));
        kit.add(new Item(2, "ARROW", 64));
        kit.add(new Item(3, "GOLDEN_APPLE", 4));
        kit.add(new Item(4, "COOKED_BEEF", 16));
        kit.add(new Item(5, "IRON_PICKAXE", 1));
        kit.add(new Item(6, "ENDER_PEARL", 8));
        kit.add(new Item(7, "WATER_BUCKET", 1));
        kit.add(new Item(8, "TORCH", 32));
        kit.add(new Item(38, "IRON_CHESTPLATE", 1));
        kit.add(new Item(40, "SHIELD", 1));
        DEFAULT = Collections.unmodifiableList(kit);
    }

    private DefaultKit() {
    }

    /**
     * Parses config lines, skipping anything unreadable.
     *
     * <p>A typo must never stop a summon or leave a Null naked, so bad lines are
     * collected into {@code errors} (which may be null) and the rest still
     * applies. An empty or fully broken list falls back to {@link #DEFAULT}.</p>
     *
     * @return the parsed kit, never null and never empty
     */
    public static List<Item> parse(List<String> lines, List<String> errors) {
        List<Item> out = new ArrayList<>();
        Map<Integer, Item> bySlot = new LinkedHashMap<>();
        if (lines != null) {
            for (String line : lines) {
                Item item = parseOne(line);
                if (item == null) {
                    if (errors != null && line != null && !line.trim().isEmpty()) {
                        errors.add("loadout.default-kit entry '" + line
                                + "' is not slot:MATERIAL[:count] - ignored");
                    }
                    continue;
                }
                bySlot.put(item.slot(), item);
            }
        }
        out.addAll(bySlot.values());
        return out.isEmpty() ? DEFAULT : Collections.unmodifiableList(out);
    }

    /** One config line, or null when it cannot be read. */
    public static Item parseOne(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String[] parts = trimmed.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            int slot = Integer.parseInt(parts[0].trim());
            String material = parts[1].trim();
            int count = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
            if (material.isEmpty()) {
                return null;
            }
            return new Item(slot, material, count);
        } catch (RuntimeException bad) {
            return null;
        }
    }

    /** The config lines for a kit. */
    public static List<String> serialize(List<Item> kit) {
        List<String> out = new ArrayList<>();
        if (kit != null) {
            for (Item item : kit) {
                out.add(item.toConfig());
            }
        }
        return out;
    }

    /**
     * Which kit entries a body is actually missing.
     *
     * <p>Applying a kit twice must not duplicate anything and must not wipe what
     * the owner arranged, so only slots that are empty or hold a different
     * material are (re)written.</p>
     *
     * @param kit      what the body should carry
     * @param occupied slot -&gt; material name currently in that slot
     */
    public static List<Item> missingFrom(List<Item> kit, Map<Integer, String> occupied) {
        List<Item> out = new ArrayList<>();
        if (kit == null) {
            return out;
        }
        Map<Integer, String> current = occupied == null ? Collections.emptyMap() : occupied;
        for (Item item : kit) {
            String held = current.get(item.slot());
            if (held == null || held.trim().isEmpty()
                    || !held.trim().equalsIgnoreCase(item.material())) {
                out.add(item);
            }
        }
        return out;
    }

    /** Slot -&gt; material of a kit, for the comparison above. */
    public static Map<Integer, String> slotMap(List<Item> kit) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (kit != null) {
            for (Item item : kit) {
                out.put(item.slot(), item.material());
            }
        }
        return out;
    }

    /** A readable one-liner: "11 items (hotbar 0-8, chestplate, offhand)". */
    public static String describe(List<Item> kit) {
        if (kit == null || kit.isEmpty()) {
            return "empty kit";
        }
        int hotbar = 0;
        int armour = 0;
        int offhand = 0;
        for (Item item : kit) {
            if (item.slot() <= 8) {
                hotbar++;
            } else if (item.slot() == 40) {
                offhand++;
            } else {
                armour++;
            }
        }
        return kit.size() + " items (" + hotbar + " hotbar, " + armour + " armour, "
                + offhand + " offhand)";
    }
}
