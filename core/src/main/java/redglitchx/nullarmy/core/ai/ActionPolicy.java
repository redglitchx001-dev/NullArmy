package redglitchx.nullarmy.core.ai;

import redglitchx.nullarmy.core.formation.FormationMatrix;

/**
 * The gate every AI-proposed action has to pass before it is executed.
 *
 * <p>The checks are the same ones a typed {@code /null} order passes, and they
 * are evaluated <b>here</b>, on values the plugin read from the server, never on
 * anything the model asserted. A model cannot widen a cap, grant a permission,
 * open a policy gate or claim that a cooldown has expired.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ActionPolicy {

    /** What the plugin knows right now, read from the server and the config. */
    public interface View {
        /** AI master switch plus at least one usable endpoint. */
        boolean aiUsable();
        /** The requesting player holds this permission. */
        boolean hasPermission(String permission);
        int liveNulls();
        int maxLiveNulls();
        /** The owner has a squad at all. */
        boolean hasSquad();
        boolean portalTravelEnabled();
        boolean airdropUsable();
        boolean cannonUsable();
        boolean missionsEnabled();
        boolean missionRunning();
        /** A shutdown sequence (Totem Of Null) is currently in progress. */
        boolean shutdownRunning();
        /** True while the server is stopping the plugin. */
        boolean pluginStopping();
    }

    /** The outcome of one gate check. */
    public static final class Decision {
        private final boolean allowed;
        private final String reason;
        private final boolean needsConfirmation;

        private Decision(boolean allowed, String reason, boolean needsConfirmation) {
            this.allowed = allowed;
            this.reason = reason == null ? "" : reason;
            this.needsConfirmation = needsConfirmation;
        }

        public boolean allowed() { return allowed; }
        public String reason() { return reason; }
        /** True when a human has to say yes before this runs. */
        public boolean needsConfirmation() { return needsConfirmation; }
    }

    private ActionPolicy() {
    }

    /**
     * Decides whether an action may run.
     *
     * @return a decision that always carries the real reason
     */
    public static Decision check(SquadAction action, View view) {
        if (action == null || view == null) {
            return deny("nothing to check");
        }
        if (view.pluginStopping()) {
            return deny("the plugin is shutting down");
        }
        if (view.shutdownRunning()) {
            return deny("a Totem Of Null shutdown is running; no Null may be created or ordered");
        }
        if (action.isRefusal()) {
            return deny(action.reason().isEmpty() ? "the model declined" : action.reason());
        }
        if (!view.aiUsable() && action.kind() != SquadAction.Kind.REPORT) {
            // The deterministic fallback may still report; it must not pretend a
            // model asked for anything.
            return deny("no AI endpoint is configured, so only the local report runs");
        }
        if (!view.hasSquad() && action.kind() != SquadAction.Kind.REPORT) {
            return deny("there is no squad to coordinate");
        }
        switch (action.kind()) {
            case REPORT:
                return allow();
            case FOLLOW:
            case COME:
                return permission("nullarmy.follow", view);
            case GUARD:
            case ROLES:
                return permission("nullarmy.follow", view);
            case FORMATION:
                if (!isFormation(action.argument())) {
                    return deny("'" + action.argument() + "' is not a formation"
                            + " (line, wall, rank, column, square, wedge, phalanx, arrow, encircle, turtle)");
                }
                return permission("nullarmy.follow", view);
            case TACTICS:
                if (!isTactics(action.argument())) {
                    return deny("'" + action.argument() + "' is not a tactics style"
                            + " (aggressive, balanced, defensive)");
                }
                return permission("nullarmy.attack", view);
            case HEAL:
                return permission("nullarmy.admin", view);
            case PORTAL:
                if (!view.portalTravelEnabled()) {
                    return deny("mechanics.portal-travel is false in config.yml");
                }
                return permission("nullarmy.admin", view);
            case MISSION_START:
                if (!view.missionsEnabled()) {
                    return deny("missions.enabled is false in config.yml");
                }
                if (view.missionRunning()) {
                    return deny("a mission is already running; stop it first");
                }
                return permission("nullarmy.mission", view);
            case MISSION_STOP:
                if (!view.missionsEnabled()) {
                    return deny("missions.enabled is false in config.yml");
                }
                return permission("nullarmy.mission", view);
            case AIRDROP:
                if (!view.airdropUsable()) {
                    return deny("the air drop is off: airdrop.enabled and its policy"
                            + " gates have to be true in config.yml");
                }
                return confirm(permission("nullarmy.admin", view));
            case CANNON:
                if (!view.cannonUsable()) {
                    return deny("the wither cannon is off: wither-cannon.enabled,"
                            + " policy.explosives-enabled and policy.wither-enabled"
                            + " all have to be true in config.yml");
                }
                return confirm(permission("nullarmy.admin", view));
            case DISMISS:
                return confirm(permission("nullarmy.admin", view));
            case REFUSE:
            default:
                return deny("that action is not allowlisted");
        }
    }

    private static boolean isFormation(String value) {
        return FormationMatrix.isKnown(value);
    }

    private static boolean isTactics(String value) {
        return "aggressive".equals(value) || "balanced".equals(value) || "defensive".equals(value);
    }

    /**
     * Orders on an existing squad are permission-gated, not cap-gated: only
     * <i>creating</i> a Null consumes the live cap, and the plugin checks that
     * again at the spawn site. A cap that is already reached must never be the
     * reason a guard order is refused.
     */
    private static Decision permission(String permission, View view) {
        if (!view.hasPermission(permission)) {
            return deny("the owner does not have " + permission);
        }
        return allow();
    }

    private static Decision allow() {
        return new Decision(true, "", false);
    }

    private static Decision confirm(Decision inner) {
        if (!inner.allowed()) {
            return inner;
        }
        return new Decision(true, "needs the owner's confirmation before it runs", true);
    }

    private static Decision deny(String reason) {
        return new Decision(false, reason, false);
    }
}
