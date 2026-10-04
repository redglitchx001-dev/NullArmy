package redglitchx.nullarmy.plugin.chat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import redglitchx.nullarmy.core.util.RateLimiter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Everything the player can say in chat instead of typing a command.
 *
 * <p>Three jobs, in increasing order of ambition:</p>
 * <ol>
 *   <li><b>Orders.</b> {@code null attack Steve}, {@code null kill Steve},
 *       {@code null come}, {@code null stop} - the wake word plus a subcommand
 *       is dispatched through the same {@link NullCommand} executor as typing
 *       {@code /null …}, so permissions, caps and policy gates are identical.
 *       The order is not broadcast to the server.</li>
 *   <li><b>Conversation.</b> {@code null chat commander} opens a private line
 *       to the Commander (or to a Null); the player's next messages go to that
 *       character and nobody else sees them. With no AI endpoint configured the
 *       character still answers with short, honest local lines.</li>
 *   <li><b>Falling back.</b> {@code null who are you} - not an order, not a
 *       session - is answered by the model in the Commander's voice, or by a
 *       plain "AI is off" line with the reason.</li>
 * </ol>
 *
 * <p><b>Threading:</b> chat arrives on an async thread. Nothing here touches the
 * world; anything that does is scheduled onto the main thread first. Nothing
 * escapes: the handler is wrapped, and each dispatch is wrapped separately.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ChatDirector implements Listener, Reloadable {

    private static final String PREFIX = PluginText.PREFIX;

    /** Who a session is with. */
    public enum Speaker {
        NONE, NULL, COMMANDER;

        public static Speaker parse(String raw) {
            if (raw == null) {
                return null;
            }
            switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "null":
                case "nulls":
                case "soldier":
                    return NULL;
                case "commander":
                case "commando":
                case "co":
                    return COMMANDER;
                case "off":
                case "none":
                case "end":
                    return NONE;
                default:
                    return null;
            }
        }

        public String displayName() {
            return this == COMMANDER ? "Commander" : "Null";
        }
    }

    /** One open private line. */
    private static final class Session {
        private final Speaker speaker;
        private final Deque<ChatBrain.Turn> history = new ArrayDeque<>();
        private long lastActivityTick;
        private boolean awaitingReply;

        Session(Speaker speaker, long now) {
            this.speaker = speaker;
            this.lastActivityTick = now;
        }
    }

    /** A two-word phrase mapped onto a subcommand, tried before the single words. */
    private static final Map<String, String> PHRASES = new LinkedHashMap<>();
    /** A single word that is a natural way to say a subcommand. */
    private static final Map<String, String> WORDS = new HashMap<>();
    /** Words that end a conversation. */
    private static final List<String> EXIT_WORDS = List.of("exit", "bye", "goodbye", "end chat", "stop talking");

    static {
        PHRASES.put("come here", "come");
        PHRASES.put("come to me", "come");
        PHRASES.put("follow me", "follow");
        PHRASES.put("go away", "dismiss");
        PHRASES.put("stop it", "stop");
        PHRASES.put("heal up", "heal");
        PHRASES.put("get over here", "come");
        PHRASES.put("tell me", "status");

        // "eliminate", "banish" and friends are how a human says kill/ban.
        WORDS.put("eliminate", "kill");
        WORDS.put("banish", "ban");
        WORDS.put("remove", "dismiss");
        WORDS.put("summon", "horn");
        WORDS.put("call", "horn");
        WORDS.put("rest", "guard");
        WORDS.put("wait", "guard");
        WORDS.put("halt", "stop");
        WORDS.put("freeze", "stop");
        WORDS.put("report", "status");
        WORDS.put("sitrep", "status");
        WORDS.put("inventory", "inv");
        WORDS.put("gear", "inv");
        WORDS.put("menu", "menu");
        WORDS.put("gui", "menu");
        WORDS.put("help", "help");
    }

    /** Short local answers so a character still feels alive with AI switched off. */
    private static final Map<String, String[]> LOCAL_LINES = new LinkedHashMap<>();

    static {
        LOCAL_LINES.put("hello", new String[] {
                "Commander on deck. Say the word and the Nulls move.",
                "I am here. What do you need?",
                "Reporting. The squad is ready." });
        LOCAL_LINES.put("hi", LOCAL_LINES.get("hello"));
        LOCAL_LINES.put("hey", LOCAL_LINES.get("hello"));
        LOCAL_LINES.put("thanks", new String[] {
                "That is what we are for.",
                "Noted. Anything else?" });
        LOCAL_LINES.put("thank you", LOCAL_LINES.get("thanks"));
        LOCAL_LINES.put("who are you", new String[] {
                "NullCommander. I lead them; you lead me.",
                "The Commander. These Nulls answer to me - and I answer to you." });
        LOCAL_LINES.put("what are you", LOCAL_LINES.get("who are you"));
        LOCAL_LINES.put("how are you", new String[] {
                "Standing, watching, waiting for the order.",
                "Ready. Bored, even." });
        LOCAL_LINES.put("good job", new String[] {
                "We aim to please.",
                "Noted in the log." });
    }

    private final NullArmyPlugin plugin;
    private final NullCommand command;
    private final ChatBrain brain;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Map<UUID, RateLimiter> orderLimits = new HashMap<>();

    private PluginConfig config;

    public ChatDirector(NullArmyPlugin plugin, NullCommand command, ChatBrain brain) {
        this.plugin = plugin;
        this.command = command;
        this.brain = brain;
        this.config = plugin.pluginConfig();
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
        // Sessions are cheap and live in memory only: a reload should not
        // silently end a conversation someone is in the middle of.
    }

    public ChatBrain brain() { return brain; }

    /** True when conversation is reserved for the Commander. */
    private boolean commanderOnlyConversation() {
        return config == null || config.commanderOnlyConversation();
    }

    /**
     * How a speaker is named in chat.
     *
     * <p>The Commander is shown by its configured name with no colours and no
     * symbols, exactly like a normal player; the brand prefix in front of the line
     * is the plugin's own gradient.</p>
     */
    private String label(Speaker speaker) {
        if (speaker == Speaker.COMMANDER && plugin.commander() != null) {
            String name = plugin.commander().commanderName();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
        }
        return speaker == null ? "Commander" : speaker.displayName();
    }

    /** True when this player is talking to a character. */
    public boolean isInSession(UUID player) {
        return player != null && sessions.containsKey(player);
    }

    public int sessionCount() {
        return sessions.size();
    }

    // -------------------------------------------------------------- session control

    /**
     * Opens a private line. Called from {@code /null chat} and from chat itself.
     *
     * @return true when a session is now open
     */
    public boolean startSession(Player player, Speaker speaker) {
        if (player == null || speaker == null || speaker == Speaker.NONE) {
            return false;
        }
        if (speaker == Speaker.NULL && commanderOnlyConversation()) {
            player.sendMessage(PREFIX + "Only the Commander talks. The Nulls take orders and"
                    + " nothing else - try 'null guard', 'null follow', 'null formation square'.");
            player.sendMessage(PREFIX + "Use /null chat commander to open the Commander's channel.");
            return false;
        }
        if (!player.hasPermission("nullarmy.chat")) {
            player.sendMessage(PREFIX + "You do not have permission to talk to the Nulls (nullarmy.chat).");
            return false;
        }
        sessions.put(player.getUniqueId(), new Session(speaker, plugin.currentTick()));
        player.sendMessage(PREFIX + "Private channel open with the " + speaker.displayName() + ".");
        player.sendMessage(PREFIX + "Type your messages normally - only the " + speaker.displayName()
                + " hears them. Say 'exit' or run /null chat off to end it.");
        if (!brain.available()) {
            String reason = speaker.displayName() + " can still answer briefly, but the"
                    + " full conversation needs a model: " + brain.unavailableReason() + ".";
            player.sendMessage(PREFIX + reason);
        }
        return true;
    }

    /** Ends a session. Returns false when there was none. */
    public boolean endSession(Player player, String why) {
        if (player == null) {
            return false;
        }
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return false;
        }
        player.sendMessage(PREFIX + "The " + session.speaker.displayName() + " signs off."
                + (why == null || why.isEmpty() ? "" : " (" + why + ")"));
        return true;
    }

    /** One-line status for {@code /null chat}. */
    public String describe(UUID player) {
        Session session = player == null ? null : sessions.get(player);
        if (session == null) {
            return "No private channel open. Use /null chat commander or /null chat null.";
        }
        return "Talking to the " + session.speaker.displayName() + " -"
                + " " + session.history.size() + " message(s) in context.";
    }

    // ------------------------------------------------------------------- chat hook

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Guard.attempt(plugin.getLogger(), "chat handling", () -> handle(event));
    }

    private void handle(AsyncPlayerChatEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        // A player answering a summon prompt belongs to SummonFlow: leave the
        // message alone so a count is never mistaken for conversation.
        try {
            if (plugin.summonFlow() != null && plugin.summonFlow().hasPending(player.getUniqueId())) {
                return;
            }
        } catch (Throwable ignored) {
            // If the flow cannot be asked, the wake-word check below still applies.
        }

        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        if (raw.isEmpty()) {
            return;
        }

        // 1. An open session takes the message, whatever it says.
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            if (isExit(raw)) {
                event.setCancelled(true);
                endSession(player, "you said so");
                return;
            }
            event.setCancelled(true);
            converse(player, session, raw);
            return;
        }

        // 2. A wake word turns the rest into an order or a question.
        String rest = stripWakeWord(raw);
        if (rest == null) {
            return;
        }
        if (rest.isEmpty()) {
            event.setCancelled(true);
            player.sendMessage(PREFIX + "Yes? Try 'null help', 'null attack <player>',"
                    + " or 'null chat commander' to talk.");
            return;
        }

        String talkTarget = talkCommand(rest);
        if (talkTarget != null) {
            event.setCancelled(true);
            if (talkTarget.isEmpty()) {
                player.sendMessage(PREFIX + "Usage: 'null chat <null|commander|off>'.");
                return;
            }
            Speaker speaker = Speaker.parse(talkTarget);
            if (speaker == null) {
                player.sendMessage(PREFIX + "'" + talkTarget + "' is neither a Null nor the Commander."
                        + " Use 'null chat commander', 'null chat null' or 'null chat off'.");
                return;
            }
            if (speaker == Speaker.NONE) {
                if (!endSession(player, null)) {
                    player.sendMessage(PREFIX + "No private channel was open.");
                }
                return;
            }
            startSession(player, speaker);
            return;
        }

        String[] args = asCommand(rest);
        if (args == null) {
            // Not an order: it is a question for the model (or an honest refusal).
            event.setCancelled(true);
            ask(player, Speaker.COMMANDER, raw, null);
            return;
        }

        event.setCancelled(true);
        if (!allowOrder(player)) {
            player.sendMessage(PREFIX + "Slow down - the Nulls can only take so many orders a minute.");
            return;
        }
        dispatch(player, args);
    }

    /** True when the player still has order budget this minute. */
    private boolean allowOrder(Player player) {
        int perMinute = config == null ? 20 : config.chatCommandsPerMinute();
        RateLimiter limiter = orderLimits.computeIfAbsent(player.getUniqueId(),
                id -> RateLimiter.perMinute(perMinute));
        return limiter.tryAcquire();
    }

    /** Runs the order on the server thread through the real command executor. */
    private void dispatch(Player player, String[] args) {
        final String[] copy = args.clone();
        Guard.attempt(plugin.getLogger(), "chat order null " + String.join(" ", copy), () -> {
            if (command == null) {
                player.sendMessage(PREFIX + "Orders are unavailable: the command is not wired.");
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> Guard.attempt(plugin.getLogger(),
                        "executing a chat order", () -> command.dispatch(player, copy)));
            } catch (Throwable t) {
                // Already on the main thread, or the scheduler is closing: run inline.
                command.dispatch(player, copy);
            }
        });
    }

    // ------------------------------------------------------------------ conversation

    private void converse(Player player, Session session, String message) {
        session.lastActivityTick = plugin.currentTick();
        push(session.history, new ChatBrain.Turn("user", message));

        if (!brain.available()) {
            String local = localLine(message);
            if (local == null) {
                player.sendMessage(PREFIX + label(session.speaker) + ": the channel is"
                        + " not connected to a model, so I only know a few lines."
                        + " (" + brain.unavailableReason() + ")");
                return;
            }
            player.sendMessage(PREFIX + label(session.speaker) + ": " + local);
            push(session.history, new ChatBrain.Turn("assistant", local));
            return;
        }
        if (session.awaitingReply) {
            player.sendMessage(PREFIX + label(session.speaker) + ": one moment...");
            return;
        }
        session.awaitingReply = true;
        ask(player, session.speaker, message, reply -> {
            session.awaitingReply = false;
            if (reply != null) {
                push(session.history, new ChatBrain.Turn("assistant", reply));
            }
        });
    }

    /**
     * Sends one message to the model and prints the answer.
     *
     * @param onDone optional notification that a reply arrived (may be null)
     */
    private void ask(Player player, Speaker speaker, String message,
                     java.util.function.Consumer<String> onDone) {
        List<ChatBrain.Turn> history = null;
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.speaker == speaker) {
            history = new ArrayList<>(session.history);
        }

        // Greetings first: they are free, instant and never wrong.
        if (history == null || history.isEmpty()) {
            String local = localLine(message);
            if (local != null && !brain.available()) {
                player.sendMessage(PREFIX + label(speaker) + ": " + local);
                if (onDone != null) {
                    onDone.accept(local);
                }
                return;
            }
        }

        final String persona = persona(speaker, player);
        brain.ask(persona, history, message, new ChatBrain.Reply() {
            @Override
            public void ok(String text) {
                String clipped = clip(text, config == null ? 400 : config.chatMaxReplyChars());
                player.sendMessage(PREFIX + label(speaker) + ": " + clipped);
                if (onDone != null) {
                    onDone.accept(clipped);
                }
            }

            @Override
            public void failed(String reason) {
                String local = localLine(message);
                if (local != null) {
                    player.sendMessage(PREFIX + label(speaker) + ": " + local);
                    if (onDone != null) {
                        onDone.accept(local);
                    }
                    return;
                }
                player.sendMessage(PREFIX + "The " + label(speaker)
                        + " cannot answer that right now: " + reason + ".");
            }
        });
    }

    /**
     * The system prompt that makes the reply sound like the character.
     *
     * <p>The Commander also gets its squad: a live snapshot of every Null's
     * health, position and role, the objective, kit state, portal arrivals and
     * what the cannon and air drop are allowed to do. That is what turns chat
     * answers into coordination instead of small talk.</p>
     */
    private String persona(Speaker speaker, Player player) {
        String name = player == null ? "the operator" : player.getName();
        String base = render(config == null ? null : config.personaCommander(), name);
        if (speaker == Speaker.NULL && !commanderOnlyConversation()) {
            base = render(config == null ? null : config.personaNull(), name);
        }
        if (player != null && plugin.coordinator() != null) {
            try {
                base = base + "\n\nYour squad right now:\n"
                        + plugin.coordinator().snapshot(player.getUniqueId())
                        + "\nYou may only ask for allowlisted actions: "
                        + String.join(", ", redglitchx.nullarmy.core.ai.SquadAction.allowlist())
                        + ". Never invent console commands, never ban or kill a player,"
                        + " never enable griefing.";
            } catch (Throwable t) {
                // A squad the snapshot cannot read is not a reason to stop talking.
                plugin.getLogger().fine("[NullArmy] squad snapshot skipped: " + Guard.describe(t));
            }
        }
        return base;
    }

    private static String render(String template, String playerName) {
        String base = template == null || template.trim().isEmpty()
                ? DEFAULT_PERSONA
                : template;
        return base.replace("{player}", playerName);
    }

    private static final String DEFAULT_PERSONA =
            "You are the NullCommander, a single grey Null NPC leading a small squad of silent"
            + " soldiers in a Minecraft world. You are talking privately to {player} in Minecraft"
            + " chat. Speak like a soldier: short, calm, dry-witted, loyal. Answer in one or two"
            + " sentences, under 200 characters, plain text only - no markdown, no emoji, no"
            + " roleplay actions in asterisks. If asked to do something you cannot do, say so"
            + " plainly and name the /null command that would do it.";

    /** Short canned lines so the character is alive even with no model. */
    private static String localLine(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT).trim();
        text = text.replace("?", "").replace("!", "").replaceAll("\\s{2,}", " ");
        String[] lines = LOCAL_LINES.get(text);
        if (lines == null) {
            for (Map.Entry<String, String[]> entry : LOCAL_LINES.entrySet()) {
                if (text.startsWith(entry.getKey())) {
                    lines = entry.getValue();
                    break;
                }
            }
        }
        if (lines == null || lines.length == 0) {
            return null;
        }
        return lines[(int) (Math.random() * lines.length) % lines.length];
    }

    // ---------------------------------------------------------------------- parsing

    /** True when the message ends the conversation. */
    static boolean isExit(String raw) {
        String text = raw.toLowerCase(Locale.ROOT).trim();
        for (String word : EXIT_WORDS) {
            if (text.equals(word) || text.startsWith(word + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a wake word from the front of the message.
     *
     * @return the remainder, or null when the message was not addressed to a Null
     */
    String stripWakeWord(String raw) {
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> wakes = config == null ? List.of("null", "nulls", "commander")
                : config.chatWakeWords();
        for (String wake : wakes) {
            String word = wake.toLowerCase(Locale.ROOT);
            if (lower.startsWith(word)) {
                String rest = text.substring(wake.length()).trim();
                // "nulls" inside "nullships" must not trigger anything.
                if (!rest.isEmpty() && !Character.isWhitespace(text.charAt(wake.length()))
                        && !rest.startsWith(":") && !rest.startsWith(",")) {
                    return null;
                }
                return rest.startsWith(":") || rest.startsWith(",")
                        ? rest.substring(1).trim()
                        : rest;
            }
        }
        return null;
    }

    /** True when the message asks to open or close a private line. */
    private static String talkCommand(String rest) {
        String lower = rest.toLowerCase(Locale.ROOT).trim();
        for (String verb : new String[] { "chat", "talk", "speak" }) {
            if (lower.equals(verb)) {
                return "";
            }
            if (lower.startsWith(verb + " ")) {
                return lower.substring(verb.length() + 1).trim();
            }
        }
        return null;
    }

    /**
     * Turns the words after the wake word into a subcommand plus arguments.
     *
     * @return null when this is not a command the plugin knows
     */
    String[] asCommand(String rest) {
        String lower = rest.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty()) {
            return null;
        }
        // Two-word natural phrases win over single words ("come here" != "come here").
        for (Map.Entry<String, String> phrase : PHRASES.entrySet()) {
            if (lower.startsWith(phrase.getKey())) {
                String tail = rest.substring(phrase.getKey().length()).trim();
                return tail.isEmpty()
                        ? new String[] { phrase.getValue() }
                        : new String[] { phrase.getValue(), tail };
            }
        }
        String[] parts = rest.split("\\s+");
        String head = parts[0].toLowerCase(Locale.ROOT);
        String mapped = WORDS.getOrDefault(head, head);
        if (!isKnown(mapped)) {
            return null;
        }
        parts[0] = mapped;
        return parts;
    }

    /** True when the word is a subcommand this plugin actually implements. */
    private boolean isKnown(String word) {
        if (command != null && command.isSubcommand(word)) {
            return true;
        }
        return WORDS.containsValue(word)
                || word.equals("kill") || word.equals("ban") || word.equals("inv");
    }

    private static void push(Deque<ChatBrain.Turn> history, ChatBrain.Turn turn) {
        int max = 12;
        history.addLast(turn);
        while (history.size() > max) {
            history.removeFirst();
        }
    }

    private static String clip(String text, int max) {
        int limit = max <= 0 ? 400 : max;
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "\u2026";
    }
}
