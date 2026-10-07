package redglitchx.nullarmy.core.kit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The default equipment every Null starts with, and the kit line format.
 *
 * <h2>The shared soldier kit</h2>
 * Full netherite armour with Protection IV and Unbreaking III (boots also carry
 * Feather Falling IV), a netherite sword (Sharpness V, Unbreaking III), pickaxe
 * (Efficiency V, Fortune III, Unbreaking III), axe (Efficiency V, Unbreaking III)
 * and shovel, a bow with Power V and Infinity plus the one arrow Infinity needs,
 * potions of Healing II (two), Strength II and Regeneration II, four golden
 * apples and four enchanted golden apples, building blocks (cobblestone 64,
 * deepslate 64, obsidian 16), 32 torches, a water bucket, four ender pearls,
 * a mace, two Totems of Undying, 16 Wind Charges, 32 rockets and a shield in
 * the offhand (the shield is what the PvP brain raises). The Commander gets
 * this same kit plus an Elytra; only the Commander's chestplate has a white trim.
 *
 * <h2>Line format</h2>
 * <pre>
 *   slot:MATERIAL[:count][|attribute,attribute...]
 *   0:NETHERITE_SWORD:1|sharpness=5,unbreaking=3
 *   12:POTION:2|potion=strong_healing
 * </pre>
 * Attributes are {@code enchantment=level} (vanilla keys, an optional
 * {@code minecraft:} prefix is accepted) and {@code potion=type}. The old
 * {@code slot:MATERIAL[:count]} lines are still read unchanged. Slot numbers are
 * the real player-inventory numbers: 0-8 hotbar, 9-35 storage, 36 boots,
 * 37 leggings, 38 chestplate, 39 helmet, 40 offhand.
 *
 * <p>Parsing and serialisation live here so the same rules run in the plugin
 * and in the tests.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class DefaultKit {

    /** One kit entry: a slot, a material, a count, enchantments and a potion type. */
    public static final class Item {
        private final int slot;
        private final String material;
        private final int count;
        private final Map<String, Integer> enchants;
        private final String potion;

        public Item(int slot, String material, int count) {
            this(slot, material, count, null, null);
        }

        public Item(int slot, String material, int count, Map<String, Integer> enchants, String potion) {
            if (slot < 0 || slot > 40) {
                throw new IllegalArgumentException("slot out of range 0..40: " + slot);
            }
            if (material == null || material.trim().isEmpty()) {
                throw new IllegalArgumentException("material must not be blank");
            }
            this.slot = slot;
            this.material = material.trim().toUpperCase(Locale.ROOT);
            this.count = Math.max(1, Math.min(64, count));
            Map<String, Integer> copy = new LinkedHashMap<>();
            if (enchants != null) {
                for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
                    String key = normaliseKey(entry.getKey());
                    Integer level = entry.getValue();
                    if (key != null && level != null && level >= 1 && level <= 255) {
                        copy.put(key, level);
                    }
                }
            }
            this.enchants = Collections.unmodifiableMap(copy);
            String p = potion == null ? "" : normaliseKey(potion);
            this.potion = p == null ? "" : p;
        }

        public int slot() { return slot; }
        public String material() { return material; }
        public int count() { return count; }

        /** Enchantment key (lower case, no namespace) to level, in kit order. */
        public Map<String, Integer> enchants() { return enchants; }

        /** Potion type key (lower case, no namespace), or "" for none. */
        public String potion() { return potion; }

        public boolean isEnchanted() { return !enchants.isEmpty(); }

        /** The config form of this entry. */
        public String toConfig() {
            StringBuilder sb = new StringBuilder();
            sb.append(slot).append(':').append(material).append(':').append(count);
            List<String> attributes = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : enchants.entrySet()) {
                attributes.add(entry.getKey() + "=" + entry.getValue());
            }
            if (!potion.isEmpty()) {
                attributes.add("potion=" + potion);
            }
            if (!attributes.isEmpty()) {
                sb.append('|').append(String.join(",", attributes));
            }
            return sb.toString();
        }

        @Override
        public String toString() { return toConfig(); }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Item)) {
                return false;
            }
            Item item = (Item) other;
            return slot == item.slot && count == item.count && material.equals(item.material)
                    && enchants.equals(item.enchants) && potion.equals(item.potion);
        }

        @Override
        public int hashCode() {
            return slot * 31 + material.hashCode() * 7 + count + enchants.hashCode() * 13
                    + potion.hashCode() * 17;
        }
    }

    /** The shipped default kit. Immutable. */
    public static final List<Item> DEFAULT;

    /**
     * The kit lines shipped before v3. A config whose list is exactly this was
     * never edited by its owner, so it is upgraded to {@link #DEFAULT}.
     */
    public static final List<String> LEGACY_V2_LINES = Collections.unmodifiableList(java.util.Arrays.asList(
            "0:IRON_SWORD:1", "1:BOW:1", "2:ARROW:64", "3:GOLDEN_APPLE:4", "4:COOKED_BEEF:16",
            "5:IRON_PICKAXE:1", "6:ENDER_PEARL:8", "7:WATER_BUCKET:1", "8:TORCH:32",
            "38:IRON_CHESTPLATE:1", "40:SHIELD:1"));

    /** The shipped shared kit before the Mace received its live-combat enchantments. */
    public static final List<String> LEGACY_UNENCHANTED_MACE_LINES = Collections.unmodifiableList(
            java.util.Arrays.asList(
            "0:NETHERITE_SWORD:1|sharpness=5,unbreaking=3",
            "1:BOW:1|power=5,infinity=1",
            "2:NETHERITE_AXE:1|efficiency=5,unbreaking=3",
            "3:NETHERITE_PICKAXE:1|efficiency=5,fortune=3,unbreaking=3",
            "4:GOLDEN_APPLE:4", "5:POTION:2|potion=strong_healing", "6:COBBLESTONE:64",
            "7:WATER_BUCKET:1", "8:ENDER_PEARL:4", "9:ARROW:1", "10:NETHERITE_SHOVEL:1",
            "11:POTION:1|potion=strong_strength", "12:POTION:1|potion=strong_regeneration",
            "13:ENCHANTED_GOLDEN_APPLE:4", "14:DEEPSLATE:64", "15:OBSIDIAN:16", "16:TORCH:32",
            "17:MACE:1", "18:TOTEM_OF_UNDYING:2", "19:WIND_CHARGE:16", "20:FIREWORK_ROCKET:32",
            "36:NETHERITE_BOOTS:1|protection=4,unbreaking=3,feather_falling=4",
            "37:NETHERITE_LEGGINGS:1|protection=4,unbreaking=3",
            "38:NETHERITE_CHESTPLATE:1|protection=4,unbreaking=3",
            "39:NETHERITE_HELMET:1|protection=4,unbreaking=3", "40:SHIELD:1|unbreaking=3"));

    /** The previously shipped v3 soldier kit, upgraded without losing custom edits. */
    public static final List<String> LEGACY_V3_LINES = Collections.unmodifiableList(java.util.Arrays.asList(
            "0:NETHERITE_SWORD:1|sharpness=5,unbreaking=3",
            "1:BOW:1|power=5,infinity=1",
            "2:NETHERITE_AXE:1|efficiency=5,unbreaking=3",
            "3:NETHERITE_PICKAXE:1|efficiency=5,fortune=3,unbreaking=3",
            "4:GOLDEN_APPLE:4", "5:POTION:2|potion=strong_healing", "6:COBBLESTONE:64",
            "7:WATER_BUCKET:1", "8:ENDER_PEARL:4", "9:ARROW:1", "10:NETHERITE_SHOVEL:1",
            "11:POTION:1|potion=strong_strength", "12:POTION:1|potion=strong_regeneration",
            "13:ENCHANTED_GOLDEN_APPLE:1", "14:DEEPSLATE:64", "15:OBSIDIAN:16", "16:TORCH:32",
            "36:NETHERITE_BOOTS:1|protection=4,unbreaking=3,feather_falling=4",
            "37:NETHERITE_LEGGINGS:1|protection=4,unbreaking=3",
            "38:NETHERITE_CHESTPLATE:1|protection=4,unbreaking=3",
            "39:NETHERITE_HELMET:1|protection=4,unbreaking=3", "40:SHIELD:1|unbreaking=3"));

    static {
        List<Item> kit = new ArrayList<>();
        kit.add(item(0, "NETHERITE_SWORD", 1, "sharpness=5,unbreaking=3"));
        kit.add(item(1, "BOW", 1, "power=5,infinity=1"));
        kit.add(item(2, "NETHERITE_AXE", 1, "efficiency=5,unbreaking=3"));
        kit.add(item(3, "NETHERITE_PICKAXE", 1, "efficiency=5,fortune=3,unbreaking=3"));
        kit.add(item(4, "GOLDEN_APPLE", 4, ""));
        kit.add(item(5, "POTION", 2, "potion=strong_healing"));
        kit.add(item(6, "COBBLESTONE", 64, ""));
        kit.add(item(7, "WATER_BUCKET", 1, ""));
        kit.add(item(8, "ENDER_PEARL", 4, ""));
        kit.add(item(9, "ARROW", 1, ""));
        kit.add(item(10, "NETHERITE_SHOVEL", 1, ""));
        kit.add(item(11, "POTION", 1, "potion=strong_strength"));
        kit.add(item(12, "POTION", 1, "potion=strong_regeneration"));
        kit.add(item(13, "ENCHANTED_GOLDEN_APPLE", 4, ""));
        kit.add(item(14, "DEEPSLATE", 64, ""));
        kit.add(item(15, "OBSIDIAN", 16, ""));
        kit.add(item(16, "TORCH", 32, ""));
        // Breach and Wind Burst are compatible; Density and Breach are not.
        // Density remains available to an owner's custom kit without creating
        // an illegal all-enchantments-at-once mace.
        kit.add(item(17, "MACE", 1, "breach=4,wind_burst=3"));
        kit.add(item(18, "TOTEM_OF_UNDYING", 2, ""));
        kit.add(item(19, "WIND_CHARGE", 16, ""));
        kit.add(item(20, "FIREWORK_ROCKET", 32, ""));
        kit.add(item(36, "NETHERITE_BOOTS", 1, "protection=4,unbreaking=3,feather_falling=4"));
        kit.add(item(37, "NETHERITE_LEGGINGS", 1, "protection=4,unbreaking=3"));
        kit.add(item(38, "NETHERITE_CHESTPLATE", 1, "protection=4,unbreaking=3"));
        kit.add(item(39, "NETHERITE_HELMET", 1, "protection=4,unbreaking=3"));
        kit.add(item(40, "SHIELD", 1, "unbreaking=3"));
        DEFAULT = Collections.unmodifiableList(kit);
    }

    private DefaultKit() {
    }

    private static Item item(int slot, String material, int count, String attributes) {
        Item parsed = parseOne(slot + ":" + material + ":" + count
                + (attributes.isEmpty() ? "" : "|" + attributes));
        if (parsed == null) {
            throw new IllegalStateException("bad built-in kit entry " + material);
        }
        return parsed;
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
                List<String> attributeErrors = new ArrayList<>();
                Item item = parseOne(line, attributeErrors);
                if (errors != null) {
                    for (String problem : attributeErrors) {
                        errors.add("loadout.default-kit entry '" + line + "': " + problem);
                    }
                }
                if (item == null) {
                    if (errors != null && line != null && !line.trim().isEmpty()
                            && !line.trim().startsWith("#")) {
                        errors.add("loadout.default-kit entry '" + line
                                + "' is not slot:MATERIAL[:count][|enchant=level,potion=type] - ignored");
                    }
                    continue;
                }
                if (bySlot.containsKey(item.slot()) && errors != null) {
                    errors.add("loadout.default-kit slot " + item.slot()
                            + " is listed more than once; the last entry wins");
                }
                bySlot.put(item.slot(), item);
            }
        }
        out.addAll(bySlot.values());
        return out.isEmpty() ? DEFAULT : Collections.unmodifiableList(out);
    }

    /**
     * The kit an owner's config really means.
     *
     * <p>An exact shipped legacy list was never edited, so the current shared
     * kit replaces it (and {@code notes} says so); anything else is the owner's
     * own choice and is used as written.</p>
     */
    public static List<Item> resolveConfigured(List<String> lines, List<String> errors, List<String> notes) {
        if (isLegacyDefault(lines)) {
            if (notes != null) {
                notes.add("loadout.default-kit is the unedited legacy kit; the current shared Null/Commander"
                        + " kit is used instead (edit the list to keep your own)");
            }
            return DEFAULT;
        }
        if (sameLines(lines, LEGACY_UNENCHANTED_MACE_LINES)) {
            if (notes != null) {
                notes.add("loadout.default-kit is the unedited shared kit with a plain Mace; the updated"
                        + " Breach/Wind Burst Mace is used instead (edit the list to keep your own)");
            }
            return DEFAULT;
        }
        if (sameLines(lines, LEGACY_V3_LINES)) {
            if (notes != null) {
                notes.add("loadout.default-kit is the unedited previous NullArmy kit; the updated shared"
                        + " Null/Commander kit is used instead (edit the list to keep your own)");
            }
            return DEFAULT;
        }
        return parse(lines, errors);
    }

    private static boolean sameLines(List<String> lines, List<String> expected) {
        if (lines == null || lines.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim();
            if (!line.equalsIgnoreCase(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** True when the lines are exactly the pre-v3 shipped kit (whitespace and case ignored). */
    public static boolean isLegacyDefault(List<String> lines) {
        if (lines == null || lines.size() != LEGACY_V2_LINES.size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i) == null ? "" : lines.get(i).trim().toUpperCase(Locale.ROOT);
            if (!line.equals(LEGACY_V2_LINES.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** One config line, or null when it cannot be read. */
    public static Item parseOne(String line) {
        return parseOne(line, null);
    }

    /**
     * One config line, or null when its slot/material/count cannot be read.
     * Unreadable attributes are reported in {@code problems} and skipped; the
     * item itself still applies.
     */
    public static Item parseOne(String line, List<String> problems) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return null;
        }
        String head = trimmed;
        String attributes = "";
        int bar = trimmed.indexOf('|');
        if (bar >= 0) {
            head = trimmed.substring(0, bar).trim();
            attributes = trimmed.substring(bar + 1).trim();
        }
        String[] parts = head.split(":");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        int slot;
        int count;
        String material;
        try {
            slot = Integer.parseInt(parts[0].trim());
            material = parts[1].trim();
            count = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 1;
        } catch (RuntimeException bad) {
            return null;
        }
        if (material.isEmpty() || !material.matches("[A-Za-z0-9_]+") || slot < 0 || slot > 40) {
            return null;
        }
        Map<String, Integer> enchants = new LinkedHashMap<>();
        String potion = "";
        if (!attributes.isEmpty()) {
            for (String raw : attributes.split(",")) {
                String attribute = raw.trim();
                if (attribute.isEmpty()) {
                    continue;
                }
                int eq = attribute.indexOf('=');
                if (eq <= 0 || eq == attribute.length() - 1) {
                    report(problems, "attribute '" + attribute + "' is not key=value");
                    continue;
                }
                String key = normaliseKey(attribute.substring(0, eq));
                String value = attribute.substring(eq + 1).trim();
                if (key == null) {
                    report(problems, "attribute key in '" + attribute + "' is not a vanilla key");
                    continue;
                }
                if (key.equals("potion")) {
                    String type = normaliseKey(value);
                    if (type == null) {
                        report(problems, "potion type '" + value + "' is not a vanilla key");
                    } else {
                        potion = type;
                    }
                    continue;
                }
                try {
                    int level = Integer.parseInt(value);
                    if (level < 1 || level > 255) {
                        report(problems, "enchantment level " + level + " for " + key + " is outside 1..255");
                        continue;
                    }
                    enchants.put(key, level);
                } catch (NumberFormatException notANumber) {
                    report(problems, "enchantment level '" + value + "' for " + key + " is not a number");
                }
            }
        }
        try {
            return new Item(slot, material, count, enchants, potion);
        } catch (RuntimeException bad) {
            return null;
        }
    }

    private static void report(List<String> problems, String problem) {
        if (problems != null) {
            problems.add(problem);
        }
    }

    /** lower_case vanilla key without the minecraft: namespace, or null when not one. */
    static String normaliseKey(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        return key.matches("[a-z0-9_./-]+") ? key : null;
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

    /** How many entries carry at least one enchantment. */
    public static int enchantedCount(List<Item> kit) {
        int n = 0;
        if (kit != null) {
            for (Item item : kit) {
                if (item.isEnchanted()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The distinct potion types in a kit. */
    public static Set<String> potionTypes(List<Item> kit) {
        Set<String> out = new LinkedHashSet<>();
        if (kit != null) {
            for (Item item : kit) {
                if (!item.potion().isEmpty()) {
                    out.add(item.potion());
                }
            }
        }
        return out;
    }

    /** Building-block stacks (placeable blocks, at least 16) in a kit. */
    public static int blockStacks(List<Item> kit) {
        Set<String> blocks = new LinkedHashSet<>(java.util.Arrays.asList(
                "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "OBSIDIAN", "STONE", "DIRT",
                "OAK_PLANKS", "SPRUCE_PLANKS", "BIRCH_PLANKS", "STONE_BRICKS", "SANDSTONE"));
        int n = 0;
        if (kit != null) {
            for (Item item : kit) {
                if (blocks.contains(item.material()) && item.count() >= 16) {
                    n++;
                }
            }
        }
        return n;
    }

    /** A readable one-liner: "22 items (17 hotbar/storage, 4 armour, 1 offhand, 11 enchanted)". */
    public static String describe(List<Item> kit) {
        if (kit == null || kit.isEmpty()) {
            return "empty kit";
        }
        int stored = 0;
        int armour = 0;
        int offhand = 0;
        for (Item item : kit) {
            if (item.slot() <= 35) {
                stored++;
            } else if (item.slot() == 40) {
                offhand++;
            } else {
                armour++;
            }
        }
        return kit.size() + " items (" + stored + " hotbar/storage, " + armour + " armour, "
                + offhand + " offhand, " + enchantedCount(kit) + " enchanted, "
                + potionTypes(kit).size() + " potion types)";
    }
}
