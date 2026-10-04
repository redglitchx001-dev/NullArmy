package redglitchx.nullarmy.core.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One thing the Commander AI is allowed to ask for.
 *
 * <p>The AI never runs commands and never touches the server directly. It
 * produces a typed action from a closed allowlist; the plugin executes it through
 * the same validated APIs a player's {@code /null} order goes through, after the
 * same permission, cap, thread and policy checks. Anything the allowlist does not
 * contain - a console command, a ban, a griefing toggle, an arbitrary string -
 * cannot be expressed at all, so it cannot be executed.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SquadAction {

    /** The closed allowlist. Adding a kind is a code change, not a config change. */
    public enum Kind {
        /** Read-only report of the squad. Always allowed. */
        REPORT("report", false, false),
        /** Walk the squad to the owner. */
        FOLLOW("follow", false, false),
        /** Walk the squad to the owner's exact position. */
        COME("come", false, false),
        /** Hold position and watch. */
        GUARD("guard", false, false),
        /** Take a formation. Argument: line|square|encircle|turtle. */
        FORMATION("formation", false, false),
        /** Change how the squad fights. Argument: aggressive|balanced|defensive. */
        TACTICS("tactics", false, false),
        /** Top the squad's health up. */
        HEAL("heal", false, false),
        /** Re-store roles for the squad. */
        ROLES("roles", false, false),
        /** Move the squad through a portal to a player. */
        PORTAL("portal", false, false),
        /** Start an original NullArmy mission. Argument: mission kind key. */
        MISSION_START("mission-start", false, false),
        /** Stop the running mission safely. */
        MISSION_STOP("mission-stop", false, false),
        /** Sky delivery. Needs its config gate and a human in the loop. */
        AIRDROP("airdrop", true, true),
        /** The wither cannon. Opt-in, explosive, and never fired by a model alone. */
        CANNON("cannon", true, true),
        /** Remove the squad. Reversible only by summoning again. */
        DISMISS("dismiss", true, true),
        /** The model declined, or asked for something outside the allowlist. */
        REFUSE("refuse", false, false);

        private final String key;
        private final boolean needsOwnerPresent;
        private final boolean needsHumanConfirmation;

        Kind(String key, boolean needsOwnerPresent, boolean needsHumanConfirmation) {
            this.key = key;
            this.needsOwnerPresent = needsOwnerPresent;
            this.needsHumanConfirmation = needsHumanConfirmation;
        }

        /** The spelling a model is told to use. */
        public String key() { return key; }

        /** True when only the owner may be the target of this action. */
        public boolean needsOwnerPresent() { return needsOwnerPresent; }

        /**
         * True when a human has to confirm before anything happens.
         *
         * <p>No model output ever executes one of these on its own.</p>
         */
        public boolean needsHumanConfirmation() { return needsHumanConfirmation; }

        /** Parses a key; null when it is not in the allowlist. */
        public static Kind parse(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
            for (Kind kind : values()) {
                if (kind.key.equals(value) || kind.name().toLowerCase(Locale.ROOT).equals(value)) {
                    return kind;
                }
            }
            return null;
        }
    }

    private final Kind kind;
    private final String argument;
    private final String reason;

    private SquadAction(Kind kind, String argument, String reason) {
        this.kind = kind;
        this.argument = argument == null ? "" : argument.trim();
        this.reason = reason == null ? "" : reason.trim();
    }

    public static SquadAction of(Kind kind, String argument, String reason) {
        return new SquadAction(kind == null ? Kind.REFUSE : kind, argument, reason);
    }

    /** A refusal that carries the reason it was refused. */
    public static SquadAction refuse(String reason) {
        return new SquadAction(Kind.REFUSE, "", reason);
    }

    public Kind kind() { return kind; }
    public String argument() { return argument; }
    public String reason() { return reason; }
    public boolean isRefusal() { return kind == Kind.REFUSE; }
    public boolean needsHumanConfirmation() { return kind.needsHumanConfirmation(); }

    /** Every key a model may answer with, for the prompt and for {@code /null ai}. */
    public static List<String> allowlist() {
        List<String> out = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            if (kind != Kind.REFUSE) {
                out.add(kind.key());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Parses one line of model output.
     *
     * <p>Accepted forms, in order: {@code kind}, {@code kind argument},
     * {@code kind: argument}, {@code {"action":"kind","argument":"x"}}. The JSON
     * form is matched with plain string scanning on purpose - {@code core} has no
     * dependencies, and a model's answer is never trusted beyond this parse.</p>
     *
     * @return a refusal action when nothing in the allowlist matches
     */
    public static SquadAction parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return refuse("the model returned nothing");
        }
        String text = raw.trim();
        String kindPart = null;
        String argumentPart = null;

        int brace = text.indexOf('{');
        if (brace >= 0) {
            kindPart = jsonField(text, "action");
            if (kindPart == null) {
                kindPart = jsonField(text, "kind");
            }
            argumentPart = jsonField(text, "argument");
            if (argumentPart == null) {
                argumentPart = jsonField(text, "target");
            }
        } else {
            int colon = text.indexOf(':');
            String head = colon >= 0 ? text.substring(0, colon) : text;
            String tail = colon >= 0 ? text.substring(colon + 1) : "";
            String[] words = head.trim().split("\\s+", 2);
            kindPart = words.length > 0 ? words[0] : "";
            argumentPart = words.length > 1 ? words[1] : tail;
            if (argumentPart == null || argumentPart.trim().isEmpty()) {
                // "formation line" style: the argument followed the kind directly.
                argumentPart = words.length > 1 ? words[1] : "";
            }
        }

        Kind kind = Kind.parse(kindPart);
        if (kind == null) {
            return refuse("'" + trimForMessage(kindPart) + "' is not an allowlisted action");
        }
        return new SquadAction(kind, argumentPart, "");
    }

    /** A very small, dependency-free string field reader for model output. */
    private static String jsonField(String json, String field) {
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length()) {
            return null;
        }
        if (json.charAt(start) == '"') {
            int end = json.indexOf('"', start + 1);
            return end < 0 ? null : json.substring(start + 1, end);
        }
        int end = start;
        while (end < json.length() && ",}]".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private static String trimForMessage(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 40 ? trimmed.substring(0, 40) + "..." : trimmed;
    }

    @Override
    public String toString() {
        return "SquadAction{" + kind.key() + (argument.isEmpty() ? "" : " " + argument) + "}";
    }
}
