package redglitchx.nullarmy.core.ledger;

/**
 * One auditable movement of items.
 *
 * <p>Spec 1.2: "Maintain an auditable item ledger for transfers and
 * consumption." Spec 10.7 requires every transfer to have correct accounting
 * across save and restart, so the reason field is mandatory - an unexplained
 * delta is a bug.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class LedgerEntry {

    /** Why items moved. Never null. */
    public enum Reason {
        /** Donated by the summoner into a Null's inventory. */
        DONATION,
        /** Picked up from the ground. */
        PICKUP,
        /** Dropped onto the ground. */
        DROP,
        /** Consumed by use (arrow fired, potion drunk, block placed). */
        CONSUME,
        /** Spent by crafting or smelting. */
        CRAFT,
        /** Moved between two Nulls by physical handoff. */
        TRANSFER,
        /** Lost on death. */
        DEATH_DROP,
        /** Durability wear, recorded for repair accounting. */
        WEAR,
        /** Explicitly withdrawn by an operator. Requires an actor. */
        ADMIN
    }

    private final long sequence;
    private final Reason reason;
    private final ItemId item;
    private final int delta;
    private final String actor;
    private final long tick;

    LedgerEntry(long sequence, Reason reason, ItemId item, int delta, String actor, long tick) {
        this.sequence = sequence;
        this.reason = reason;
        this.item = item;
        this.delta = delta;
        this.actor = actor;
        this.tick = tick;
    }

    public long sequence() { return sequence; }
    public Reason reason() { return reason; }
    public ItemId item() { return item; }

    /** Positive when items entered this ledger, negative when they left. */
    public int delta() { return delta; }

    /** Who caused it: a player name, a Null id, or "system". Never null. */
    public String actor() { return actor; }

    public long tick() { return tick; }

    @Override
    public String toString() {
        return "#" + sequence + " " + reason + " " + item + " " + (delta >= 0 ? "+" : "") + delta
                + " by " + actor + " @tick " + tick;
    }
}
