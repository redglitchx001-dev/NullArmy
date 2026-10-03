package redglitchx.nullarmy.core.util;

import java.util.concurrent.TimeUnit;

/**
 * A monotonic-clock token bucket.
 *
 * <p>Used to cap outbound AI endpoint calls per minute per role (spec 7) and
 * to rate-limit optional emotes so a Null never spams animations or chat.</p>
 *
 * <p>Uses {@link System#nanoTime}, never wall-clock, so it cannot be fooled
 * by clock changes.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class RateLimiter {

    private final long refillNanosPerToken;
    private final int capacity;
    private double tokens;
    private long lastRefillNanos;

    /**
     * @param permits number of actions allowed per window
     * @param window  the window length
     * @param unit    unit of {@code window}
     */
    public RateLimiter(int permits, long window, TimeUnit unit) {
        if (permits <= 0) {
            throw new IllegalArgumentException("permits must be > 0");
        }
        long windowNanos = unit.toNanos(window);
        if (windowNanos <= 0) {
            throw new IllegalArgumentException("window must be positive");
        }
        this.capacity = permits;
        this.refillNanosPerToken = windowNanos / permits;
        this.tokens = permits;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Convenience factory: {@code permits} per minute. */
    public static RateLimiter perMinute(int permits) {
        return new RateLimiter(permits, 1L, TimeUnit.MINUTES);
    }

    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Milliseconds until the next token is available, or 0 if one is ready. */
    public synchronized long millisUntilNextToken() {
        refill();
        if (tokens >= 1.0) {
            return 0L;
        }
        double deficit = 1.0 - tokens;
        return (long) (deficit * refillNanosPerToken / 1_000_000L);
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + (double) elapsed / (double) refillNanosPerToken);
    }
}
