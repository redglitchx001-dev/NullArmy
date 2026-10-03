package redglitchx.nullarmy.core.agent;

/**
 * Circuit breaker for outbound AI endpoint calls.
 *
 * <p>Spec 7.6: "Use a circuit breaker, bounded retries with backoff ...
 * deterministic local fallbacks. Never make HTTP requests on the tick thread."
 * Spec 10.16: an endpoint outage must leave the server responsive.</p>
 *
 * <p>Three states, standard: CLOSED (normal), OPEN (failing; calls rejected
 * immediately without touching the network), HALF_OPEN (one probe allowed
 * after the reset timeout).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long resetTimeoutMillis;
    private final long clockSourceOffset;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAtMillis = -1L;
    private long lastProbeAtMillis = -1L;

    public CircuitBreaker(String name, int failureThreshold, long resetTimeoutMillis) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be > 0");
        }
        if (resetTimeoutMillis <= 0) {
            throw new IllegalArgumentException("resetTimeoutMillis must be > 0");
        }
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMillis = resetTimeoutMillis;
        this.clockSourceOffset = System.currentTimeMillis();
    }

    public String name() { return name; }
    public State state() { return evaluate(System.currentTimeMillis()); }

    /** @return true if a call may be attempted right now */
    public boolean allowRequest() {
        State s = evaluate(System.currentTimeMillis());
        return s == State.CLOSED || s == State.HALF_OPEN;
    }

    public void recordSuccess() {
        long now = System.currentTimeMillis();
        evaluate(now);
        consecutiveFailures = 0;
        openedAtMillis = -1L;
        lastProbeAtMillis = -1L;
        state = State.CLOSED;
    }

    public void recordFailure() {
        long now = System.currentTimeMillis();
        State s = evaluate(now);
        if (s == State.HALF_OPEN) {
            // A probe failed: go straight back to OPEN and restart the timer.
            state = State.OPEN;
            openedAtMillis = now;
            consecutiveFailures = failureThreshold;
            return;
        }
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAtMillis = now;
        }
    }

    /** Milliseconds until a probe is permitted, or 0 if one is allowed now. */
    public long millisUntilProbe() {
        long now = System.currentTimeMillis();
        State s = evaluate(now);
        if (s != State.OPEN) {
            return 0L;
        }
        long elapsed = now - openedAtMillis;
        return Math.max(0L, resetTimeoutMillis - elapsed);
    }

    private State evaluate(long now) {
        if (state == State.OPEN) {
            if (now - openedAtMillis >= resetTimeoutMillis) {
                state = State.HALF_OPEN;
                lastProbeAtMillis = now;
            }
        }
        return state;
    }

    /** Diagnostics for {@code /null status}. Never includes credentials. */
    public String describe() {
        return name + "=" + state() + " failures=" + consecutiveFailures
                + " probeIn=" + millisUntilProbe() + "ms";
    }
}
