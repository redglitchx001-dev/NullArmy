package redglitchx.nullarmy.core.orders;

import java.util.Locale;
import java.util.Set;

/**
 * Turns a normal chat sentence into an order.
 *
 * <p><b>P-09.</b> The owner types <i>"Commander build me a throne"</i>,
 * <i>"null bridge in front of me"</i>, <i>"null attack them"</i> or
 * <i>"null destroy"</i> and expects the army to understand. The old chat
 * director matched a fixed wake word and then looked for one of a handful of
 * hard-coded verbs, so most sentences simply did nothing.</p>
 *
 * <p>This parser is pure and total: it never throws and it never guesses. It
 * decides (a) whether the line was aimed at us at all, and (b) which verb, if
 * any, the sentence asks for. Everything else - who is allowed to give the
 * order, what the target list is, whether griefing is permitted - is decided by
 * the caller, which can be tested and configured.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class OrderParser {

    /** What a sentence asks the army to do. */
    public enum Verb {
        BUILD, BRIDGE, ATTACK, DESTROY, FOLLOW, COME, STOP, GUARD, MARCH, DRILL, PATROL,
        DEFEND, SALUTE, REGROUP,
        /** Addressed to the Commander, but no order in it - conversation. */
        CHAT
    }

    /** How the speaker got our attention. */
    public enum Address { MENTION, NAME, WAKE_WORD, NONE }

    /** The wake words that work even when the Commander has been renamed. */
    public static final Set<String> WAKE_WORDS = Set.of("null", "commander", "nulls", "nullarmy");

    /** A parsed line. */
    public static final class Order {

        private final Address address;
        private final Verb verb;
        private final String argument;
        private final String text;
        private final boolean addressed;

        Order(Address address, Verb verb, String argument, String text) {
            this.address = address;
            this.verb = verb;
            this.argument = argument == null ? "" : argument.trim();
            this.text = text == null ? "" : text.trim();
            this.addressed = address != Address.NONE;
        }

        /** Whether the line was aimed at the Commander / the army at all. */
        public boolean addressed() {
            return addressed;
        }

        public Address address() {
            return address;
        }

        public Verb verb() {
            return verb;
        }

        /** What the verb applies to: {@code "a throne"}, {@code "them"}, {@code "Steve"}. */
        public String argument() {
            return argument;
        }

        /** The sentence with the address stripped. */
        public String text() {
            return text;
        }

        /** True when this verb actually commands the army (as opposed to chatting). */
        public boolean isOrder() {
            return verb != Verb.CHAT;
        }

        @Override
        public String toString() {
            return verb + "(" + argument + ")";
        }
    }

    private OrderParser() {
    }

    /**
     * Parses a chat line.
     *
     * @param raw           the raw message
     * @param commanderName the configured {@code commander.name}; may be null
     * @param mentionPrefix the configured {@code chat.mention-prefix} ("@")
     * @return the order, or null when the line was not aimed at us
     */
    public static Order parse(String raw, String commanderName, String mentionPrefix) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty()) {
            return null;
        }
        String prefix = (mentionPrefix == null || mentionPrefix.isEmpty()) ? "@" : mentionPrefix;
        String name = commanderName == null ? "" : commanderName.trim();
        String lower = line.toLowerCase(Locale.ROOT);

        // 1. @Name ...
        if (!name.isEmpty()) {
            String mention = (prefix + name).toLowerCase(Locale.ROOT);
            int at = lower.indexOf(mention);
            if (at >= 0) {
                String rest = stripLeadingPunctuation(line.substring(0, at) + line.substring(at + mention.length()));
                Verb verb = verbOf(rest);
                return new Order(Address.MENTION, verb, argumentOf(verb, rest), rest.trim());
            }
        }
        // 2. A line that opens with the Commander's name.
        if (!name.isEmpty()) {
            String named = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith(named)) {
                String after = line.substring(name.length());
                char next = after.isEmpty() ? ' ' : after.charAt(0);
                if (!Character.isLetterOrDigit(next)) {
                    String rest = stripLeadingPunctuation(after);
                    Verb verb = verbOf(rest);
                    return new Order(Address.NAME, verb, argumentOf(verb, rest), rest.trim());
                }
            }
        }
        // 3. A wake word at the start of the line.
        String[] head = firstWord(lower);
        String word = head[0];
        if (WAKE_WORDS.contains(word)) {
            String rest = line.substring(Integer.parseInt(head[1]));
            Verb verb = verbOf(rest);
            return new Order(Address.WAKE_WORD, verb, argumentOf(verb, rest), rest.trim());
        }
        return null;
    }

    /**
     * True when a line is aimed at the Commander and contains no order - a pure
     * conversation opener such as {@code "@Vex hello"}.
     */
    public static boolean isConversation(String raw, String commanderName, String mentionPrefix) {
        Order order = parse(raw, commanderName, mentionPrefix);
        return order != null && !order.isOrder();
    }

    /** Which verb a sentence asks for; {@link Verb#CHAT} when none. */
    public static Verb verbOf(String sentence) {
        String s = sentence == null ? "" : sentence.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return Verb.CHAT;
        }
        s = stripLeadingPunctuation(s);
        for (String filler : new String[] {"please", "hey", "hi", "hello", "ok", "okay", "now", "then"}) {
            if (s.startsWith(filler + " ") && s.length() > filler.length() + 1) {
                s = s.substring(filler.length() + 1).trim();
            }
        }
        if (starts(s, "bridge")) {
            return Verb.BRIDGE;
        }
        if (starts(s, "build") || starts(s, "make") || starts(s, "construct") || starts(s, "raise")) {
            return Verb.BUILD;
        }
        if (starts(s, "destroy") || starts(s, "raze") || starts(s, "grief") || starts(s, "teardown")
                || starts(s, "tear down") || starts(s, "demolish")) {
            return Verb.DESTROY;
        }
        if (starts(s, "attack") || starts(s, "kill") || starts(s, "hunt") || starts(s, "fight")
                || starts(s, "engage")) {
            return Verb.ATTACK;
        }
        if (starts(s, "follow") || starts(s, "come") || starts(s, "withdraw") || starts(s, "recall")) {
            return Verb.FOLLOW;
        }
        if (starts(s, "stop") || starts(s, "halt") || starts(s, "freeze") || starts(s, "hold")) {
            return Verb.STOP;
        }
        if (starts(s, "guard")) {
            return Verb.GUARD;
        }
        if (starts(s, "defend")) {
            return Verb.DEFEND;
        }
        if (starts(s, "march")) {
            return Verb.MARCH;
        }
        if (starts(s, "drill")) {
            return Verb.DRILL;
        }
        if (starts(s, "patrol")) {
            return Verb.PATROL;
        }
        if (starts(s, "salute")) {
            return Verb.SALUTE;
        }
        if (starts(s, "regroup") || starts(s, "rally") || starts(s, "reform")) {
            return Verb.REGROUP;
        }
        return Verb.CHAT;
    }

    /** What the verb applies to, with the filler words a player would use removed. */
    public static String argumentOf(Verb verb, String sentence) {
        String s = sentence == null ? "" : sentence.trim();
        if (s.isEmpty()) {
            return "";
        }
        s = stripLeadingPunctuation(s);
        for (String filler : new String[] {"please", "hey", "hi", "hello", "ok", "okay", "now", "then"}) {
            if (s.toLowerCase(Locale.ROOT).startsWith(filler + " ") && s.length() > filler.length() + 1) {
                s = s.substring(filler.length() + 1).trim();
            }
        }
        String[] verbs = verbsFor(verb);
        if (verbs.length == 0) {
            return "";
        }
        String lower = s.toLowerCase(Locale.ROOT);
        for (String v : verbs) {
            if (lower.startsWith(v)) {
                s = s.substring(v.length()).trim();
                break;
            }
        }
        // Drop the polite filler between the verb and the object.
        String[] words = s.split("\\s+");
        int from = 0;
        while (from < words.length && from < 3 && isFiller(words[from])) {
            from++;
        }
        StringBuilder out = new StringBuilder();
        for (int i = from; i < words.length; i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(words[i]);
        }
        return out.toString().trim();
    }

    private static boolean isFiller(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.equals("me") || w.equals("us") || w.equals("a") || w.equals("an") || w.equals("the")
                || w.equals("my") || w.equals("some") || w.equals("up") || w.equals("out")
                || w.equals("this") || w.equals("that") || w.equals("here") || w.equals("there");
    }

    private static String[] verbsFor(Verb verb) {
        switch (verb) {
            case BUILD: return new String[] {"build", "make", "construct", "raise"};
            case BRIDGE: return new String[] {"bridge"};
            case ATTACK: return new String[] {"attack", "kill", "hunt", "fight", "engage"};
            case DESTROY: return new String[] {"destroy", "raze", "grief", "teardown", "tear down", "demolish"};
            case FOLLOW: return new String[] {"follow", "come", "withdraw", "recall"};
            case STOP: return new String[] {"stop", "halt", "freeze", "hold"};
            case GUARD: return new String[] {"guard"};
            case DEFEND: return new String[] {"defend"};
            case MARCH: return new String[] {"march"};
            case DRILL: return new String[] {"drill"};
            case PATROL: return new String[] {"patrol"};
            case SALUTE: return new String[] {"salute"};
            case REGROUP: return new String[] {"regroup", "rally", "reform"};
            default: return new String[0];
        }
    }

    private static boolean starts(String sentence, String word) {
        if (sentence.equals(word)) {
            return true;
        }
        if (sentence.startsWith(word + " ")) {
            return true;
        }
        return sentence.startsWith(word) && sentence.length() > word.length()
                && !Character.isLetterOrDigit(sentence.charAt(word.length()));
    }

    /** The first word of a line and, as a string, the index just past it. */
    private static String[] firstWord(String line) {
        int i = 0;
        while (i < line.length() && !Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        int start = i;
        while (i < line.length() && Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        return new String[] {line.substring(start, i).toLowerCase(Locale.ROOT), Integer.toString(i)};
    }

    private static String stripLeadingPunctuation(String line) {
        int i = 0;
        while (i < line.length() && !Character.isLetterOrDigit(line.charAt(i))) {
            i++;
        }
        return line.substring(i);
    }
}
