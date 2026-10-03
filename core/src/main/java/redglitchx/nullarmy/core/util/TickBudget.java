package redglitchx.nullarmy.core.util;

/**
 * A per-tick work budget.
 *
 * <p>Every costly subsystem (path searches, block inspections, packet sends,
 * endpoint calls) draws from a budget so no system can do unbounded work in a
 * single tick. Spec section 1.4: "no unbounded work".</p>
 *
 * <p>Not thread-safe: budgets are consumed on the main thread only.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class TickBudget {

    private final String name;
    private final int perTick;
    private int remaining;

    public TickBudget(String name, int perTick) {
        if (perTick <= 0) {
            throw new IllegalArgumentException("perTick must be > 0");
        }
        this.name = name;
        this.perTick = perTick;
        this.remaining = perTick;
    }

    public String name() { return name; }

    /** Call once per server tick. */
    public void reset() { remaining = perTick; }

    /**
     * Attempts to consume {@code amount} units.
     *
     * @return true if the work may proceed, false if the budget is exhausted
     */
    public boolean tryConsume(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be > 0");
        }
        if (remaining < amount) {
            return false;
        }
        remaining -= amount;
        return true;
    }

    public int remaining() { return remaining; }
    public int perTick() { return perTick; }

    /** Fraction of the budget still free this tick, in [0, 1]. */
    public double remainingFraction() { return (double) remaining / (double) perTick; }
}
