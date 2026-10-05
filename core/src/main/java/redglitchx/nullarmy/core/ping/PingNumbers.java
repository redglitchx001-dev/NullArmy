package redglitchx.nullarmy.core.ping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the multiplayer server list shows.
 *
 * <p><b>P-11.</b> The Nulls are server-driven bodies, not sessions, so the
 * vanilla ping answered {@code 0/2026} while two hundred of them stood in front
 * of the castle. The count line and the hover sample are assembled here, where
 * they can be tested without a server.</p>
 *
 * <p>Pure: strings and lists only.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PingNumbers {

    /** Shipped {@code motd.max-players}. */
    public static final int DEFAULT_MAX = 2026;

    /** Shipped {@code motd.format}. */
    public static final String DEFAULT_FORMAT = "NULL ARMY - {nulls} strong";

    /** How many names the vanilla hover sample ever shows. */
    public static final int SAMPLE_LIMIT = 20;

    private PingNumbers() {
    }

    /** The count line: {@code <real + nulls>/<max>}. */
    public static String num(int realPlayers, int nulls, int max) {
        int shown = Math.max(0, realPlayers) + Math.max(0, nulls);
        int cap = max <= 0 ? DEFAULT_MAX : max;
        return shown + "/" + cap;
    }

    /**
     * The hover sample: the army first, then the real players, capped.
     *
     * @param nullNames Null handles, already readable (see
     *                  {@code redglitchx.nullarmy.core.naming.NullNames})
     * @param players   real player names
     * @param limit     how many lines at most (0 or less = {@link #SAMPLE_LIMIT})
     */
    public static List<String> sample(List<String> nullNames, List<String> players, int limit) {
        int cap = limit <= 0 ? SAMPLE_LIMIT : limit;
        List<String> out = new ArrayList<>();
        addAll(out, nullNames, cap);
        addAll(out, players, cap);
        return out;
    }

    /** The sample joined the way the self test prints it. */
    public static String sampleJoined(List<String> nullNames, List<String> players, int limit) {
        return String.join(", ", sample(nullNames, players, limit));
    }

    private static void addAll(List<String> out, List<String> from, int cap) {
        if (from == null) {
            return;
        }
        for (String name : from) {
            if (out.size() >= cap) {
                return;
            }
            if (name != null && !name.isEmpty()) {
                out.add(name);
            }
        }
    }

    /**
     * The MOTD line, with {@code {nulls}} and {@code {players}} substituted.
     *
     * @param format the configured format; null or empty means no line at all
     */
    public static String motd(String format, int nulls, int realPlayers) {
        if (format == null || format.trim().isEmpty()) {
            return "";
        }
        String out = format.replace("{nulls}", Integer.toString(Math.max(0, nulls)))
                .replace("{players}", Integer.toString(Math.max(0, realPlayers)))
                .replace("{total}", Integer.toString(Math.max(0, nulls) + Math.max(0, realPlayers)));
        return out.trim();
    }

    /** The default line for {@code nulls} Nulls. */
    public static String defaultMotd(int nulls) {
        return motd(DEFAULT_FORMAT, nulls, 0);
    }

    /** A safe max player count: never below the live head count, never absurd. */
    public static int clampMax(int configured, int liveCount) {
        int max = configured <= 0 ? DEFAULT_MAX : configured;
        return Math.max(max, Math.max(0, liveCount));
    }

    /** Lower-cased, for case-insensitive comparison in tests and logs. */
    public static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }
}
