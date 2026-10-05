package redglitchx.nullarmy.core.naming;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Readable Null handles.
 *
 * <p><b>P-03.</b> The old generator produced 16 random alphanumerics, so the
 * server list, the tab list and every nameplate read like
 * {@code c1b12d32d3dc74c4} - an unreadable hex fragment. A Null is something a
 * player has to shout at, so its name has to be pronounceable.</p>
 *
 * <p>Names are a themed word, optionally with a small number
 * ({@code Voidwalker}, {@code Grimjaw_12}), never longer than the 16-character
 * username limit, and never a raw UUID or hex fragment. {@link #isReadable} is
 * the rule the self test enforces on every live Null.</p>
 *
 * <p>Pure and dependency-free, so the word list and the readability rule are
 * unit tested.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullNames {

    /** Minecraft's username limit. */
    public static final int MAX_LENGTH = 16;

    /** Names must look like this: a letter first, then letters, digits and _. */
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,15}");

    /** Eight or more bare hex digits is the gibberish shape we are replacing. */
    private static final Pattern HEX_BLOB = Pattern.compile("[0-9a-fA-F]{8,}");

    private static final String[] WORDS = {
            "Voidwalker", "Grimjaw", "Nullborne", "Dreadmask", "Ashfang", "Ironveil",
            "Blackmarch", "Duskrend", "Riftborn", "Nightmire", "Sable", "Wraith",
            "Cinderhollow", "Gloomwright", "Ravenshade", "Embervault", "Hollowguard",
            "Palewatcher", "Stormhowl", "Gravemark", "Vantablack", "Silentruin",
            "Doomwhisper", "Frostmourn", "Obsidian", "Shatterveil", "Umbralfang",
            "Wane", "Dirge", "Mournhollow", "Netherbane", "Soulrend"
    };

    private NullNames() {
    }

    /** How many distinct word stems exist (used by the uniqueness test). */
    public static int wordCount() {
        return WORDS.length;
    }

    /** The word at {@code index}, wrapped into range. */
    public static String word(int index) {
        return WORDS[Math.floorMod(index, WORDS.length)];
    }

    /**
     * A readable, unique handle.
     *
     * <p>Tries the bare word first, then {@code Word_2} .. {@code Word_99}, then
     * {@code Word_100} .. - so a full server still gets legal, readable, unique
     * names and never a hex fragment.</p>
     *
     * @param seed  any long; the same seed gives the same name
     * @param taken names already in use (may be null)
     */
    public static String next(long seed, Set<String> taken) {
        int start = Math.floorMod((int) (seed ^ (seed >>> 32)), WORDS.length);
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < WORDS.length; i++) {
                String word = WORDS[Math.floorMod(start + i, WORDS.length)];
                for (int suffix = 0; suffix <= 104; suffix++) {
                    String candidate = suffix == 0 ? word : word + "_" + suffix;
                    if (candidate.length() > MAX_LENGTH) {
                        continue;
                    }
                    if (taken == null || !taken.contains(candidate)) {
                        return candidate;
                    }
                }
            }
            // Everything from this word is taken: fall through and allow a
            // numeric suffix beyond 99 rather than emitting a UUID.
        }
        return shorten(WORDS[Math.floorMod(start, WORDS.length)]
                + "_" + Math.floorMod(seed, 100000L));
    }

    private static String shorten(String name) {
        return name.length() <= MAX_LENGTH ? name : name.substring(0, MAX_LENGTH);
    }

    /**
     * True when a name is a readable handle: legal Minecraft username shape, at
     * most 16 characters, containing a vowel, and not a hex blob.
     */
    public static boolean isReadable(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) {
            return false;
        }
        if (!SHAPE.matcher(name).matches()) {
            return false;
        }
        if (HEX_BLOB.matcher(name).find()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        boolean vowel = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y') {
                vowel = true;
                break;
            }
        }
        return vowel;
    }
}
