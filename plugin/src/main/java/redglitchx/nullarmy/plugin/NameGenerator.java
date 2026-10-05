package redglitchx.nullarmy.plugin;

import redglitchx.nullarmy.core.naming.NullNames;

import java.security.SecureRandom;
import java.util.Set;

/**
 * Generates Null profile names.
 *
 * <p>Spec 3: "Assign a unique random alphanumeric profile/display name similar
 * to {@code uH3WR2v0ti0uTHJ}."</p>
 *
 * <p><b>v4 (P-03): the old 16 random alphanumerics were the bug.</b> A server
 * list, a tab list and a nameplate full of {@code c1b12d32d3dc74c4} is
 * unreadable, and the owner could not shout an order at a Null whose name is a
 * hex fragment. Names now come from the themed, readable generator in
 * {@link NullNames} ({@code Voidwalker}, {@code Null_07}, {@code Grimjaw_12}),
 * still unique and still inside the 16-character username limit.
 * {@code names.style: "codes"} restores the old behaviour.</p>
 *
 * <p>Uses {@link SecureRandom} rather than {@link java.util.Random} so a player
 * cannot predict the next Null's name from observing previous ones.</p>
 *
 * <p>Uniqueness is enforced by the caller against both live and persisted NPCs
 * - a generator cannot know about those.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NameGenerator {

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 16;
    private static final int USERNAME_LIMIT = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Set while {@code names.style} is "codes" so a legacy server keeps its look. */
    private static volatile boolean codes;

    /** Called on (re)load. {@code true} restores the pre-v4 random strings. */
    public static void setStyle(String style) {
        codes = "codes".equalsIgnoreCase(style);
    }

    public static boolean readable() {
        return !codes;
    }

    private NameGenerator() {
    }

    static {
        if (LENGTH > USERNAME_LIMIT) {
            throw new IllegalStateException("generated names would exceed the username limit");
        }
    }

    /** @return a readable, legal name (or a 16-character code in codes mode) */
    public static String next() {
        return next(new java.util.HashSet<>());
    }

    /**
     * @param taken names already in use; the result is not one of them
     * @return a readable, legal name (or a 16-character code in codes mode)
     */
    public static String next(Set<String> taken) {
        if (!codes) {
            return NullNames.next(RANDOM.nextLong(), taken);
        }
        for (int attempt = 0; attempt < 64; attempt++) {
            String candidate = randomCode();
            if (taken == null || !taken.contains(candidate.toLowerCase(java.util.Locale.ROOT))) {
                return candidate;
            }
        }
        return randomCode();
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** @return true if {@code name} is a legal Minecraft username shape */
    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.length() > USERNAME_LIMIT) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (ALPHABET.indexOf(name.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }
}
