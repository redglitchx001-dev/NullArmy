package redglitchx.nullarmy.core.item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The identity rules of the two summon items, free of any server dependency.
 *
 * <p>Keeping the exact strings and the recognition logic here means a test can
 * prove - without Bukkit - that the Call Horn is named {@code Null}, that the
 * totem is named exactly {@code The Totem Of Null}, that recognition survives a
 * rename because it is the persistent-data tag that counts, and that a plain
 * vanilla Totem of Undying is <b>not</b> recognised.</p>
 *
 * <p>Copyright (c) RedGlitchx. All rights reserved.</p>
 */
public final class SummonItemSpec {

    /** The Call Horn keeps the name the owner asked for: exactly "Null". */
    public static final String HORN_DISPLAY_NAME = "Null";

    /** The totem's exact name. Not "Totem Of Null", not "Null". */
    public static final String TOTEM_DISPLAY_NAME = "The Totem Of Null";

    /** Persistent-data namespace. A Bukkit plugin's namespace is its lower name. */
    public static final String NAMESPACE = "nullarmy";

    /** Persistent-data key suffixes. */
    public static final String HORN_TAG = "call_horn";
    public static final String TOTEM_TAG = "totem_of_null";

    /** Older builds wrote the key by hand instead of through the plugin. */
    public static final String LEGACY_TAG_PREFIX = "nullarmy_";

    /** Materials, as Bukkit names them. */
    public static final String HORN_MATERIAL = "GOAT_HORN";
    public static final String TOTEM_MATERIAL = "TOTEM_OF_UNDYING";

    /** The horn must be the vanilla "Call" instrument, not any goat horn. */
    public static final String HORN_INSTRUMENT = "CALL_GOAT_HORN";

    /** The real Curse of Vanishing, by registry key. */
    public static final String VANISHING_CURSE_KEY = "vanishing_curse";

    /** The glint enchantment on the horn: a real, legal enchantment. */
    public static final String GLINT_ENCHANT_KEY = "unbreaking";

    /** Names older builds used, still recognised so nobody loses an item. */
    public static final List<String> LEGACY_NAMES;

    static {
        List<String> names = new ArrayList<>();
        names.add("Call Horn");
        names.add("Totem Of Null");
        LEGACY_NAMES = Collections.unmodifiableList(names);
    }

    private SummonItemSpec() {
    }

    /** The full persistent-data key for a tag suffix, e.g. {@code nullarmy:totem_of_null}. */
    public static String tagKey(String suffix) {
        return NAMESPACE + ":" + suffix;
    }

    /** The legacy hand-written key, e.g. {@code nullarmy:nullarmy_totem_of_null}. */
    public static String legacyTagKey(String suffix) {
        return NAMESPACE + ":" + LEGACY_TAG_PREFIX + suffix;
    }

    /**
     * Which summon item this is.
     *
     * <p>The tag is the primary key, so renaming, repairing or moving the item
     * cannot break recognition. The display name is only a fallback for items an
     * older build made before tags existed.</p>
     *
     * @param material      Bukkit material name of the stack
     * @param plainName     the item's name as plain text, or null
     * @param hasHornTag    the {@code call_horn} tag is present
     * @param hasTotemTag   the {@code totem_of_null} tag is present
     * @return {@link #HORN_TAG}, {@link #TOTEM_TAG} or {@code ""}
     */
    public static String kindOf(String material, String plainName,
                                boolean hasHornTag, boolean hasTotemTag) {
        if (material == null) {
            return "";
        }
        String type = material.trim().toUpperCase(Locale.ROOT);
        if (type.equals(HORN_MATERIAL)) {
            if (hasHornTag) {
                return HORN_TAG;
            }
            return nameMatches(plainName, HORN_DISPLAY_NAME) ? HORN_TAG : "";
        }
        if (type.equals(TOTEM_MATERIAL)) {
            if (hasTotemTag) {
                return TOTEM_TAG;
            }
            // A plain Totem of Undying is NOT ours, and neither is one renamed to
            // the horn's name: only the exact totem name or the tag counts.
            return nameMatches(plainName, TOTEM_DISPLAY_NAME) ? TOTEM_TAG : "";
        }
        return "";
    }

    /** Exact plain-text match, ignoring surrounding whitespace only. */
    private static boolean nameMatches(String plainName, String expected) {
        if (plainName == null) {
            return false;
        }
        String trimmed = plainName.trim();
        if (expected.equals(trimmed)) {
            return true;
        }
        for (String legacy : LEGACY_NAMES) {
            if (legacy.equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code name} is a legal Minecraft profile name shape. */
    public static boolean isLegalProfileName(String name) {
        if (name == null || name.isEmpty() || name.length() > 16) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
