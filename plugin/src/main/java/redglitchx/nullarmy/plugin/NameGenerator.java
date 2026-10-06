package redglitchx.nullarmy.plugin;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;

/**
 * Generates random, unique Null profile names using Minecraft-safe letters and
 * digits, matching the requested 16-character username shape.
 *
 * <p>The first character is a letter and each name includes at least one digit;
 * the remaining characters are sampled from upper/lower-case letters and
 * digits. Uniqueness is checked against the caller's live roster.</p>
 *
 * <p>Uses {@link SecureRandom} rather than {@link java.util.Random} so names
 * cannot be predicted from a previous summon.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NameGenerator {

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String ALPHABET = LETTERS + DIGITS;
    private static final int LENGTH = 16;
    private static final int USERNAME_LIMIT = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private NameGenerator() {
    }

    /** Kept for config compatibility; NullArmy now always uses random codes. */
    public static void setStyle(String style) {
        // The prior "words" default was superseded by the requested random names.
    }

    /** This generator intentionally does not use themed word names. */
    public static boolean readable() {
        return false;
    }

    static {
        if (LENGTH > USERNAME_LIMIT) {
            throw new IllegalStateException("generated names would exceed the username limit");
        }
    }

    /** @return a random legal 16-character name */
    public static String next() {
        return next(null);
    }

    /**
     * @param taken names already in use (case-insensitive); may be null
     * @return a random alphanumeric name not present in {@code taken}
     */
    public static String next(Set<String> taken) {
        while (true) {
            String candidate = randomCode();
            if (!isTaken(taken, candidate)) {
                return candidate;
            }
        }
    }

    private static boolean isTaken(Set<String> taken, String candidate) {
        if (taken == null || taken.isEmpty()) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        return taken.contains(lower) || taken.contains(candidate)
                || taken.stream().anyMatch(name -> name != null && lower.equals(name.toLowerCase(Locale.ROOT)));
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        sb.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        boolean hasDigit = false;
        for (int i = 1; i < LENGTH; i++) {
            char next = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
            sb.append(next);
            hasDigit |= Character.isDigit(next);
        }
        if (!hasDigit) {
            int slot = 1 + RANDOM.nextInt(LENGTH - 1);
            sb.setCharAt(slot, DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return sb.toString();
    }

    /** @return true if {@code name} contains only letters and digits and fits the username limit */
    public static boolean isValid(String name) {
        if (name == null || name.length() != LENGTH || name.length() > USERNAME_LIMIT
                || LETTERS.indexOf(name.charAt(0)) < 0) {
            return false;
        }
        boolean letter = false;
        boolean digit = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (LETTERS.indexOf(c) >= 0) {
                letter = true;
            } else if (DIGITS.indexOf(c) >= 0) {
                digit = true;
            } else {
                return false;
            }
        }
        return letter && digit;
    }
}
