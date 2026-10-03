package redglitchx.nullarmy.plugin;

import java.security.SecureRandom;

/**
 * Generates Null profile names.
 *
 * <p>Spec 3: "Assign a unique random alphanumeric profile/display name similar
 * to {@code uH3WR2v0ti0uTHJ}."</p>
 *
 * <p>Uses {@link SecureRandom} rather than {@link java.util.Random} so a
 * player cannot predict the next Null's name from observing previous ones.
 * Names are 16 characters, comfortably inside the 16-character username limit.</p>
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

    private NameGenerator() {
    }

    static {
        if (LENGTH > USERNAME_LIMIT) {
            throw new IllegalStateException("generated names would exceed the username limit");
        }
    }

    /** @return a 16-character alphanumeric name */
    public static String next() {
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
