package redglitchx.nullarmy.plugin.util;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small, boring safety helpers.
 *
 * <p>The rule this class exists to enforce: <b>no exception may escape
 * {@code onEnable}, {@code onDisable}, a command, a listener or a tick.</b> A
 * thrown NPE inside a listener is survivable; the same NPE thrown from an
 * entity tick or the server loop takes the whole server down with it. So every
 * risky call site goes through here: the failure is logged with the real reason
 * (never swallowed silently), the caller gets a boolean, and the server keeps
 * running.</p>
 *
 * <p>{@link Breaker} is a latched circuit breaker for the one path that is
 * genuinely dangerous: creating a fake {@code ServerPlayer} through NMS. Each
 * NPC gets a listener that drops outbound sends so server packet broadcasts
 * cannot dereference a null connection or queue packets for a client that does
 * not exist. If the spawn path still fails, it stays off until the
 * owner reloads or restarts, and the reason is visible in {@code /null status}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class Guard {

    /** Maximum characters of a throwable description shown to a player. */
    private static final int MAX_REASON = 220;

    private Guard() {
    }

    /** A unit of work that may fail. */
    public interface Risk {
        void run() throws Throwable;
    }

    /**
     * Runs {@code risk}, catching everything and logging the full stack trace
     * with a {@code [NullArmy]} prefix.
     *
     * <p>This is the <b>only</b> overload on purpose: a lambda body matches both
     * a {@code Runnable} and this type, so having both would make every call
     * site ambiguous and fail to compile. Because {@link Risk#run()} may throw,
     * this form also covers the calls that used to need a {@code Runnable}.</p>
     *
     * @return true when it completed without throwing
     */
    public static boolean attempt(Logger logger, String what, Risk risk) {
        if (risk == null) {
            return false;
        }
        try {
            risk.run();
            return true;
        } catch (Throwable t) {
            if (logger != null) {
                logger.log(Level.SEVERE, "[NullArmy] " + what + " failed: " + describe(t), t);
            }
            return false;
        }
    }

    /**
     * A one-line, player-safe description of a failure: type, message and the
     * root cause when they differ. Never null, never longer than
     * {@link #MAX_REASON}.
     */
    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(t.getClass().getSimpleName());
        String message = t.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            sb.append(": ").append(message.trim());
        }
        Throwable root = rootCause(t);
        if (root != t && root != null && root.getMessage() != null
                && !root.getMessage().trim().isEmpty()) {
            sb.append(" (caused by ").append(root.getClass().getSimpleName())
                    .append(": ").append(root.getMessage().trim()).append(')');
        }
        String out = sb.toString().replace('\n', ' ').replace('\r', ' ');
        if (out.length() > MAX_REASON) {
            out = out.substring(0, MAX_REASON - 3) + "...";
        }
        return out;
    }

    /** The deepest cause that still has its own cause chain end. */
    public static Throwable rootCause(Throwable t) {
        Throwable current = t;
        int guard = 0;
        while (current.getCause() != null && current.getCause() != current && guard++ < 32) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * A latched guard for a dangerous capability.
     *
     * <p>Once tripped it reports itself as open until {@link #reset()} is
     * called, so a failing feature degrades into a clear message instead of
     * being retried on every command.</p>
     */
    public static final class Breaker {

        private final String capability;
        private volatile boolean open;
        private volatile String reason = "";
        private volatile long trippedAtMillis;
        private volatile int tripCount;

        public Breaker(String capability) {
            this.capability = capability == null ? "capability" : capability;
        }

        public boolean isOpen() { return open; }

        /** Human-readable reason for the last trip, or "". */
        public String reason() { return reason; }

        public long trippedAtMillis() { return trippedAtMillis; }

        public int tripCount() { return tripCount; }

        public String capability() { return capability; }

        /**
         * Trips the breaker and logs it once. Safe to call repeatedly: the
         * first reason wins so the root cause is not overwritten by a
         * follow-up failure.
         */
        public void trip(String why, Throwable cause, Logger logger, String advice) {
            String text = (why == null || why.trim().isEmpty()) ? "unknown reason" : why.trim();
            if (!open) {
                reason = text;
                trippedAtMillis = System.currentTimeMillis();
            }
            open = true;
            tripCount++;
            if (logger != null) {
                logger.severe("[NullArmy] " + capability + " has been disabled for this session: "
                        + text
                        + (advice == null || advice.isEmpty() ? "" : " " + advice)
                        + (cause == null ? "" : " (" + describe(cause) + ")"));
            }
        }

        public void reset() {
            open = false;
            reason = "";
            tripCount = 0;
        }

        /** Short status line for {@code /null status} and {@code /null debug}. */
        public String statusLine() {
            if (!open) {
                return capability + ": armed";
            }
            return capability + ": DISABLED - " + reason;
        }
    }
}
