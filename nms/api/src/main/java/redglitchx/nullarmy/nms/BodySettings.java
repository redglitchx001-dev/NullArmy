package redglitchx.nullarmy.nms;

/**
 * Rules the version adapter applies inside every Null's own tick.
 *
 * <p>The plugin owns the configuration; the adapter owns the entity. This is
 * the small, immutable bridge between the two, pushed on enable and on every
 * {@code /null reload}. Everything here is a server rule that has to be
 * answered <i>inside</i> vanilla's damage and movement code (for example
 * {@code Player#canHarmPlayer}), which is why it cannot simply live in the
 * plugin.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BodySettings {

    /** The shipped defaults: Nulls are ordinary, hittable, falling bodies. */
    public static final BodySettings DEFAULTS = new BodySettings(true, true, true, true, true);

    private final boolean playersCanHitNulls;
    private final boolean nullsCanHitNulls;
    private final boolean fallDamage;
    private final boolean pickupItems;
    private final boolean collisions;

    public BodySettings(boolean playersCanHitNulls, boolean nullsCanHitNulls, boolean fallDamage,
                        boolean pickupItems, boolean collisions) {
        this.playersCanHitNulls = playersCanHitNulls;
        this.nullsCanHitNulls = nullsCanHitNulls;
        this.fallDamage = fallDamage;
        this.pickupItems = pickupItems;
        this.collisions = collisions;
    }

    /** {@code combat.players-can-hit-nulls}: a real player's melee and arrows hurt a Null. */
    public boolean playersCanHitNulls() { return playersCanHitNulls; }

    /** One Null may hurt another (ordered fights, retaliation, the self test). */
    public boolean nullsCanHitNulls() { return nullsCanHitNulls; }

    /** {@code combat.fall-damage}: vanilla fall damage, including Feather Falling. */
    public boolean fallDamage() { return fallDamage; }

    /** Nulls pick up item entities they walk over, like players do. */
    public boolean pickupItems() { return pickupItems; }

    /** Nulls push and are pushed by other bodies (vanilla entity collision). */
    public boolean collisions() { return collisions; }

    @Override
    public String toString() {
        return "players-can-hit-nulls=" + playersCanHitNulls + ", nulls-can-hit-nulls=" + nullsCanHitNulls
                + ", fall-damage=" + fallDamage + ", pickup=" + pickupItems + ", collisions=" + collisions;
    }
}
