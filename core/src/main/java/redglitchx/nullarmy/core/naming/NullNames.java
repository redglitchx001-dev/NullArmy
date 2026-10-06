package redglitchx.nullarmy.core.naming;

import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic alphanumeric profile-name generation for pure core checks.
 *
 * <p>Live Nulls use the plugin's {@code SecureRandom}-backed generator. This
 * dependency-free helper mirrors its username shape so core tests can verify
 * legality and uniqueness without Bukkit.</p>
 *
 * <p>Each name is exactly 16 ASCII letters and digits, begins with a letter,
 * and contains at least one digit, matching the requested Null username format.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullNames {

    /** Minecraft's profile-name limit and the generated code length. */
    public static final int MAX_LENGTH = 16;

    private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String ALPHABET = LETTERS + DIGITS;

    private NullNames() {
    }

    /**
     * Returns a deterministic, legal alphanumeric username that is not in
     * {@code taken} (comparison is case-insensitive).
     *
     * @param seed  any long; the same seed and taken set give the same result
     * @param taken names already in use (may be null)
     */
    public static String next(long seed, Set<String> taken) {
        Random random = new Random(seed);
        while (true) {
            String candidate = randomCode(random);
            if (!isTaken(candidate, taken)) {
                return candidate;
            }
        }
    }

    private static String randomCode(Random random) {
        StringBuilder result = new StringBuilder(MAX_LENGTH);
        result.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        boolean hasDigit = false;
        for (int i = 1; i < MAX_LENGTH; i++) {
            char next = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
            result.append(next);
            hasDigit |= DIGITS.indexOf(next) >= 0;
        }
        if (!hasDigit) {
            int slot = 1 + random.nextInt(MAX_LENGTH - 1);
            result.setCharAt(slot, DIGITS.charAt(random.nextInt(DIGITS.length())));
        }
        return result.toString();
    }

    private static boolean isTaken(String candidate, Set<String> taken) {
        if (taken == null || taken.isEmpty()) {
            return false;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        for (String name : taken) {
            if (name != null && lower.equals(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True for the exact legal 16-character alphanumeric profile-name shape. */
    public static boolean isValid(String name) {
        if (name == null || name.length() != MAX_LENGTH || LETTERS.indexOf(name.charAt(0)) < 0) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (LETTERS.indexOf(c) >= 0) {
                hasLetter = true;
            } else if (DIGITS.indexOf(c) >= 0) {
                hasDigit = true;
            } else {
                return false;
            }
        }
        return hasLetter && hasDigit;
    }
}
