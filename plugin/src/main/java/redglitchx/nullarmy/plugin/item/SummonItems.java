package redglitchx.nullarmy.plugin.item;

import org.bukkit.Material;
import org.bukkit.MusicInstrument;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import redglitchx.nullarmy.core.item.SummonItemSpec;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.ArrayList;
import java.util.List;

/**
 * The two legal summon items: the <b>Call Horn</b> and the <b>Totem Of Null</b>.
 *
 * <p>The Call Horn is named exactly {@code Null} and keeps the vanilla "Call"
 * goat-horn instrument, so right-clicking it plays the real horn sound. The totem
 * is named exactly {@code The Totem Of Null} and carries the <b>real Curse of
 * Vanishing</b>, so it disappears instead of being left on the ground when its
 * owner dies.</p>
 *
 * <p>Spec 3 allows a real Goat Horn or a real Totem of Undying as the trigger,
 * but the item may only ever come into existence through an explicit owner
 * action - never a drop, never a spontaneous grant, never a recipe that quietly
 * hands one out. {@code /null horn} and {@code /null totem} are that action.</p>
 *
 * <h2>How the item is recognised</h2>
 * <p>A {@link PersistentDataContainer} tag ({@code nullarmy:call_horn} /
 * {@code nullarmy:totem_of_null}) is the primary key, so the item still works
 * after the player renames it, repairs it or runs it through a crafting table.
 * The display name is only a fallback for items made by an older build.</p>
 *
 * <h2>Enchantment</h2>
 * <p>The example item carries a <b>real</b> enchantment ({@code Unbreaking I})
 * with {@link ItemFlag#HIDE_ENCHANTS}, which is what makes it glint without
 * printing an enchantment line. No fake, illegal or out-of-range enchantment is
 * ever written.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SummonItems {

    /** The Call Horn's name, exactly (spec 3: the item is named {@code Null}). */
    public static final String DISPLAY_NAME = SummonItemSpec.HORN_DISPLAY_NAME;

    /** The totem's name, exactly. Not "Totem Of Null", not "Null". */
    public static final String TOTEM_DISPLAY_NAME = SummonItemSpec.TOTEM_DISPLAY_NAME;

    /** Persistent-data key suffixes, stored as {@code nullarmy:<suffix>}. */
    public static final String HORN_KEY = "call_horn";
    public static final String TOTEM_KEY = "totem_of_null";

    /** Display names an older build of this plugin used. */
    private static final String LEGACY_HORN_NAME = "Call Horn";
    private static final String LEGACY_TOTEM_NAME = "Totem Of Null";

    /** Enchantment level on the summon items. Level 1, a legal value. */
    private static final int GLINT_LEVEL = 1;

    private SummonItems() {
    }

    /** The Call Horn: a real Goat Horn, named, enchanted, glinting and tagged. */
    public static ItemStack callHorn(Plugin plugin) {
        return build(plugin, Material.GOAT_HORN, HORN_KEY, DISPLAY_NAME, LEGACY_HORN_NAME, false,
                "Call Goat Horn: right-click to sound the call.",
                "Then type how many Nulls should come in chat.",
                "They walk out of real, temporary portal doorways.",
                "Owner-issued item. Never granted spontaneously.");
    }

    /**
     * The Totem Of Null: a real Totem of Undying, named exactly, cursed with the
     * real Curse of Vanishing, and tagged so a rename cannot break recognition.
     */
    public static ItemStack totemOfNull(Plugin plugin) {
        return build(plugin, Material.TOTEM_OF_UNDYING, TOTEM_KEY, TOTEM_DISPLAY_NAME,
                LEGACY_TOTEM_NAME, true,
                "The Totem Of Null: the army's heart.",
                "Right-click to call the Nulls, then type how many come.",
                "If it pops or is destroyed, every Null - Commander last -",
                "steps out one at a time and the army is gone.",
                "Owner-issued item. Never dropped, never granted.");
    }

    /**
     * Builds one summon item.
     *
     * <p>Never throws: if the meta cannot be written the plain item is returned
     * rather than failing the command that asked for it.</p>
     */
    private static ItemStack build(Plugin plugin, Material material, String keySuffix,
                                   String displayName, String legacyName, boolean vanish,
                                   String... loreLines) {
        ItemStack item = new ItemStack(material);
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return item;
            }
            // customName(Component) is the current API on 1.21.11; the older
            // displayName(Component) is marked obsolete since 1.21.4 and now
            // means the same thing.
            meta.customName(Component.text(displayName)
                    .color(NamedTextColor.DARK_PURPLE)
                    .decoration(TextDecoration.ITALIC, false));

            // The summon horn is specifically the vanilla "Call" instrument,
            // not whichever goat-horn instrument happened to be on the item.
            if (material == Material.GOAT_HORN && meta instanceof MusicInstrumentMeta) {
                ((MusicInstrumentMeta) meta).setInstrument(MusicInstrument.CALL_GOAT_HORN);
            }

            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(Component.text(line)
                        .color(NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);

            if (vanish) {
                // The real Curse of Vanishing, not a look-alike: the totem must not
                // survive its owner as a loose item anyone can pick up.
                Enchantment curse = Enchantment.VANISHING_CURSE;
                if (curse != null) {
                    meta.addEnchant(curse, 1, true);
                }
                // Deliberately NOT hidden: an owner should be able to read why the
                // totem vanished. The glint comes with the enchantment anyway.
            } else {
                // A real, legal enchantment - that is what makes the horn glint.
                meta.addEnchant(Enchantment.UNBREAKING, GLINT_LEVEL, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }

            meta.getPersistentDataContainer().set(
                    key(plugin, keySuffix), PersistentDataType.STRING, "1");
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // A cosmetic failure must not stop the command from handing the item over.
        }
        return item;
    }

    /** True when the stack is a summon item of either kind. Never throws. */
    public static boolean isSummonItem(ItemStack item) {
        return !kindOf(item).isEmpty();
    }

    /**
     * @return {@link #HORN_KEY}, {@link #TOTEM_KEY} or {@code ""} when the stack
     *     is not a summon item
     */
    public static String kindOf(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) {
            return "";
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return "";
            }
            Material type = item.getType();
            PersistentDataContainer data = meta.getPersistentDataContainer();

            if (type == Material.GOAT_HORN) {
                if (hasTag(data, HORN_KEY)) {
                    return HORN_KEY;
                }
                if (nameIs(meta, DISPLAY_NAME) || nameIs(meta, LEGACY_HORN_NAME)) {
                    return HORN_KEY;
                }
                return "";
            }
            if (type == Material.TOTEM_OF_UNDYING) {
                if (hasTag(data, TOTEM_KEY)) {
                    return TOTEM_KEY;
                }
                // The exact name, or a name an older build used. A plain vanilla
                // totem - and one renamed to anything else - is NOT ours.
                if (nameIs(meta, TOTEM_DISPLAY_NAME) || nameIs(meta, LEGACY_TOTEM_NAME)) {
                    return TOTEM_KEY;
                }
                return "";
            }
            return "";
        } catch (Throwable ignored) {
            // Anything unreadable is simply "not a summon item".
            return "";
        }
    }

    /** True when this exact stack is a Call Horn. */
    public static boolean isCallHorn(ItemStack item) {
        return HORN_KEY.equals(kindOf(item));
    }

    /** The exact vanilla sound belonging to the Call Goat Horn instrument. */
    public static Sound callHornSound() {
        return MusicInstrument.CALL_GOAT_HORN.getSound();
    }

    /** True when the stack carries the real Curse of Vanishing. */
    public static boolean hasVanishingCurse(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        try {
            ItemMeta meta = item.getItemMeta();
            return meta != null && Enchantment.VANISHING_CURSE != null
                    && meta.hasEnchant(Enchantment.VANISHING_CURSE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The plain-text name of a stack, or "" when it has none. */
    public static String plainName(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return "";
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null || meta.customName() == null) {
                return "";
            }
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(meta.customName());
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** The identity rules, for diagnostics and tests. */
    public static String identitySummary() {
        return "horn='" + SummonItemSpec.HORN_DISPLAY_NAME + "' tagged "
                + SummonItemSpec.tagKey(SummonItemSpec.HORN_TAG) + "; totem='"
                + SummonItemSpec.TOTEM_DISPLAY_NAME + "' tagged "
                + SummonItemSpec.tagKey(SummonItemSpec.TOTEM_TAG) + " with "
                + SummonItemSpec.VANISHING_CURSE_KEY;
    }

    /** True when this exact stack is a Totem Of Null. */
    public static boolean isTotemOfNull(ItemStack item) {
        return TOTEM_KEY.equals(kindOf(item));
    }

    /** The persistent-data key for a summon item; safe with a null plugin. */
    private static NamespacedKey key(Plugin plugin, String suffix) {
        if (plugin == null) {
            return NamespacedKey.minecraft("nullarmy_" + suffix);
        }
        return new NamespacedKey(plugin, suffix);
    }

    private static boolean hasTag(PersistentDataContainer data, String suffix) {
        if (data == null) {
            return false;
        }
        // Two probes: the plugin-scoped key, and the older hand-written one.
        if (data.has(NamespacedKey.minecraft("nullarmy_" + suffix), PersistentDataType.STRING)) {
            return true;
        }
        return data.has(rawKey(suffix), PersistentDataType.STRING);
    }

    /**
     * The plugin-scoped key. Constructed without a Plugin instance because the
     * namespace for a Bukkit plugin is always its lower-cased name.
     */
    private static NamespacedKey rawKey(String suffix) {
        return new NamespacedKey("nullarmy", suffix);
    }

    /**
     * Compares the item's name with an expected plain string.
     *
     * <p>Only the plain-text accessor is used. Component equality would compare
     * styling as well, so an item this plugin created (dark purple, italic off)
     * would never equal a bare component - a false negative is worse than no
     * fallback at all. The persistent-data tag remains the real key; this is
     * only for items made by an older build.</p>
     */
    private static boolean nameIs(ItemMeta meta, String expected) {
        if (meta == null || expected == null) {
            return false;
        }
        // Preferred: the Adventure component this plugin writes, as plain text.
        // A renamed item keeps its tag, so this path only matters for items an
        // older build made before tags existed.
        try {
            Component custom = meta.customName();
            if (custom != null) {
                String plain = net.kyori.adventure.text.serializer.plain
                        .PlainTextComponentSerializer.plainText().serialize(custom);
                if (plain != null && expected.equals(plain.trim())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the legacy accessor below.
        }
        try {
            if (!meta.hasDisplayName()) {
                return false;
            }
            return expected.equals(meta.getDisplayName());
        } catch (Throwable ignored) {
            // A missing legacy accessor is not worth failing an interaction over.
            return false;
        }
    }
}
