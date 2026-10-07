package redglitchx.nullarmy.plugin.chat;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.util.RateLimiter;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.body.Mind;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.config.V3Settings;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything the player can say in chat instead of typing a command.
 *
 * <p>Three jobs, in increasing order of ambition:</p>
 * <ol>
 *   <li><b>Orders.</b> {@code null attack Steve}, {@code null kill Steve},
 *       {@code null come}, {@code null stop} - the wake word plus a subcommand
 *       is dispatched through the same {@link NullCommand} executor as typing
 *       {@code /null …}, so permissions, caps and policy gates are identical.
 *       OFF hides recognized order lines; PRIVATE consumes its conversation
 *       privately; PUBLIC leaves recognized orders visible.</li>
 *   <li><b>Conversation.</b> A per-player {@link ChatMode} selects OFF, PRIVATE,
 *       or PUBLIC independently of {@link Session} history. {@code /null chat
 *       private} opens a private line; {@code /null chat public} enables public
 *       name/wake-word replies; {@code /null chat off} closes routing. Private
 *       messages are intercepted; public replies use {@code Name: message}.</li>
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

    /** Conversation visibility selected by one player, independent of history/session state. */
    public enum ChatMode { OFF, PRIVATE, PUBLIC }

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
                case "private":
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

    /** One short-lived, block-specific owner confirmation for a protected teardown. */
    private static final class PendingDestroy {
        private final String phrase;
        private final String world;
        private final int x;
        private final int y;
        private final int z;
        private final Material material;
        private final long expiresAtTick;

        PendingDestroy(String phrase, Block block, long expiresAtTick) {
            this.phrase = phrase;
            this.world = block.getWorld().getName();
            this.x = block.getX();
            this.y = block.getY();
            this.z = block.getZ();
            this.material = block.getType();
            this.expiresAtTick = expiresAtTick;
        }

        boolean matches(String requestedPhrase, Block block, long now) {
            return block != null && now <= expiresAtTick
                    && phrase.equals(requestedPhrase)
                    && world.equals(block.getWorld().getName())
                    && x == block.getX() && y == block.getY() && z == block.getZ()
                    && material == block.getType();
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
        PHRASES.put("stop it", "stop");
        PHRASES.put("heal up", "heal");
        PHRASES.put("get over here", "come");
        PHRASES.put("tell me", "status");

        // "eliminate", "banish" and friends are how a human says kill/ban.
        WORDS.put("eliminate", "kill");
        WORDS.put("banish", "ban");
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
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ChatMode> chatModes = new ConcurrentHashMap<>();
    private final Map<UUID, RateLimiter> orderLimits = new ConcurrentHashMap<>();

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
        // silently end a conversation someone is in the middle of. A destructive
        // confirmation is policy-bound and must be restarted after any reload.
        pendingDestroy.clear();
    }

    public ChatBrain brain() { return brain; }

    /** Whether private Commander dialogue currently shows the optional plugin tag. */
    public boolean pluginPrefixEnabled() { return !prefix().isEmpty(); }

    /** True when conversation is reserved for the Commander. */
    private boolean commanderOnlyConversation() {
        return config == null || config.commanderOnlyConversation();
    }

    /** The chat-line prefix is opt-in; by default the Commander speaks without a plugin tag. */
    private String prefix() {
        V3Settings settings = config == null ? null : config.v3();
        return settings != null && settings.chatPluginPrefix() ? PREFIX : "";
    }

    /**
     * How a speaker is named in chat.
     *
     * <p>The Commander is shown by its configured name, exactly like a normal
     * player; public messages use {@code Name: message} formatting.</p>
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

    /** The selected channel visibility; defaults to the server's public-reply setting. */
    public ChatMode chatMode(UUID player) {
        if (player == null) {
            return ChatMode.OFF;
        }
        ChatMode selected = chatModes.get(player);
        return selected == null ? (publicRepliesEnabled() ? ChatMode.PUBLIC : ChatMode.OFF) : selected;
    }

    private boolean publicRepliesEnabled() {
        return config == null || config.v3().commanderPublicReplies();
    }

    private boolean explicitlyOff(UUID player) {
        return player != null && chatModes.get(player) == ChatMode.OFF;
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
            player.sendMessage(prefix() + "Only the Commander talks. The Nulls take orders and"
                    + " nothing else - try 'null guard', 'null follow', 'null formation square'.");
            player.sendMessage(prefix() + "Use /null chat commander to open the Commander's channel.");
            return false;
        }
        if (!player.hasPermission("nullarmy.chat")) {
            player.sendMessage(prefix() + "You do not have permission to talk to the Nulls (nullarmy.chat).");
            return false;
        }
        sessions.put(player.getUniqueId(), new Session(speaker, plugin.currentTick()));
        chatModes.put(player.getUniqueId(), ChatMode.PRIVATE);
        player.sendMessage(prefix() + "Private channel open with the " + speaker.displayName() + ".");
        player.sendMessage(prefix() + "Type your messages normally - only the " + speaker.displayName()
                + " hears them. Say 'exit' or run /null chat off to end it.");
        if (!brain.available()) {
            String reason = speaker.displayName() + " can still answer briefly, but the"
                    + " full conversation needs a model: " + brain.unavailableReason() + ".";
            player.sendMessage(prefix() + reason);
        }
        return true;
    }

    /** Selects public Commander replies for this player, without opening a history session. */
    public boolean setPublicMode(Player player) {
        if (player == null) {
            return false;
        }
        if (!player.hasPermission("nullarmy.chat")) {
            player.sendMessage(prefix() + "You do not have permission to talk to the Commander (nullarmy.chat).");
            return false;
        }
        boolean closedPrivate = sessions.remove(player.getUniqueId()) != null;
        chatModes.put(player.getUniqueId(), ChatMode.PUBLIC);
        player.sendMessage(prefix() + (closedPrivate ? "Private channel closed. " : "")
                + "Public Commander chat is on. Address him by name or wake word; replies appear as Name: message.");
        return true;
    }

    /** Turns off persistent Commander routing; explicit army orders remain available. */
    public boolean setOffMode(Player player) {
        if (player == null) {
            return false;
        }
        boolean closedPrivate = sessions.remove(player.getUniqueId()) != null;
        chatModes.put(player.getUniqueId(), ChatMode.OFF);
        player.sendMessage(prefix() + (closedPrivate ? "Private channel closed. " : "")
                + "Commander chat mode is off. Addressed conversation lines will not be routed; orders still work.");
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
        chatModes.put(player.getUniqueId(), ChatMode.OFF);
        player.sendMessage(prefix() + "The " + session.speaker.displayName() + " signs off."
                + (why == null || why.isEmpty() ? "" : " (" + why + ")"));
        return true;
    }

    /** One-line status for {@code /null chat}. */
    public String describe(UUID player) {
        Session session = player == null ? null : sessions.get(player);
        if (session != null) {
            return "Private channel with the " + session.speaker.displayName() + " is open - "
                    + session.history.size() + " message(s) in context.";
        }
        ChatMode mode = chatMode(player);
        if (mode == ChatMode.PUBLIC) {
            return "Public Commander chat is on. Address him by name or wake word; use /null chat off to stop.";
        }
        if (explicitlyOff(player)) {
            return "Commander chat mode is OFF. Use /null chat public or /null chat private to choose a channel.";
        }
        return "No private channel open. Server default is private replies. Use /null chat public,"
                + " /null chat private, or /null chat off.";
    }

    // ------------------------------------------------------------------- chat hook

    /**
     * Paper's modern chat event. Public mode leaves addressed lines and order
     * text visible, replying as {@code Name: message}; private mode consumes
     * conversation into the player's session, and OFF disables conversation
     * routing while still processing recognized orders. Unrelated OFF-mode
     * lines are left alone.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent event) {
        Guard.attempt(plugin.getLogger(), "chat handling", () -> handle(event));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (event == null || event.getPlayer() == null) {
            return;
        }
        UUID player = event.getPlayer().getUniqueId();
        sessions.remove(player);
        chatModes.remove(player);
        orderLimits.remove(player);
        pendingDestroy.remove(player);
        if (plugin.coordinator() != null) {
            plugin.coordinator().clearPendingConfirmation(player);
        }
    }

    private void handle(io.papermc.paper.event.player.AsyncChatEvent event) {
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
            // If the flow cannot be asked, the trigger check below still applies.
        }

        String raw = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(event.message()).trim();
        if (raw.isEmpty()) {
            return;
        }

        // 1. An open private session takes the message, whatever it says.
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            if (isExit(raw)) {
                endSession(player, "you said so");
                return;
            }
            converse(player, session, raw);
            return;
        }

        // 2. Addressed to the Commander? Wake word or the Commander's name.
        String rest = stripTrigger(raw);
        if (rest == null) {
            return; // silence: not for us
        }
        triggered++;
        final UUID speaker = player.getUniqueId();
        later(() -> {
            if (plugin.brain() != null) {
                plugin.brain().noteSpeaker(speaker, speaker);
            }
        });

        ChatMode mode = chatMode(player.getUniqueId());
        boolean offExplicitly = explicitlyOff(player.getUniqueId());
        String talkTarget = talkCommand(rest);
        if (talkTarget != null) {
            event.setCancelled(true);
            if (talkTarget.isEmpty()) {
                player.sendMessage(prefix() + "Usage: 'null chat <public|private|null|commander|off>'.");
                return;
            }
            if (talkTarget.equalsIgnoreCase("public")) {
                setPublicMode(player);
                return;
            }
            if (talkTarget.equalsIgnoreCase("off")) {
                setOffMode(player);
                return;
            }
            Speaker target = Speaker.parse(talkTarget);
            if (target == null) {
                player.sendMessage(prefix() + "'" + talkTarget + "' is neither a Null nor a chat mode."
                        + " Use 'null chat public', 'null chat private', 'null chat null' or 'null chat off'.");
                return;
            }
            if (target == Speaker.NONE) {
                setOffMode(player);
                return;
            }
            startSession(player, target);
            return;
        }

        boolean publicReplies = mode == ChatMode.PUBLIC;
        if (rest.isEmpty()) {
            if (publicReplies) {
                commanderReply("Yes, " + player.getName() + "? Give the order, or ask me something.");
            } else if (!offExplicitly) {
                event.setCancelled(true);
                player.sendMessage(prefix() + "Yes? Try 'null help', 'null attack <player>',"
                        + " or 'null chat commander' to talk.");
            }
            return;
        }

        String[] args = orderWords(rest);
        if (args == null) {
            args = asCommand(rest);
        }
        if (args == null) {
            // P-09: a natural sentence. The pure core parser decides whether it
            // is an order at all; anything the word-matching path understood
            // above is untouched, so no old sentence changes meaning.
            if (naturalOrder(player, raw)) {
                // OFF disables conversation, not army orders. Recognized orders
                // are hidden outside PUBLIC mode and remain visible in PUBLIC mode.
                if (mode != ChatMode.PUBLIC) {
                    event.setCancelled(true);
                }
                return;
            }
            if (offExplicitly) {
                return; // explicitly disabled chat leaves ordinary public chat untouched
            }
            // A question or a remark: the Commander answers.
            if (publicReplies) {
                answerPublicly(player, rest);
            } else {
                event.setCancelled(true);
                ask(player, Speaker.COMMANDER, raw, null);
            }
            return;
        }

        if (!publicReplies) {
            event.setCancelled(true);
        }
        if (!allowOrder(player)) {
            player.sendMessage(prefix() + "Slow down - the Nulls can only take so many orders a minute.");
            return;
        }
        final String verb = args[0].equals("order") && args.length > 2 ? args[2] : args[0];
        if (!dispatch(player, args)) {
            return;
        }
        if (publicReplies) {
            commanderReply(acknowledgement(verb, player.getName()));
        }
    }

    // ------------------------------------------------------- natural orders (P-09)

    /** A destroy order waiting for its confirm: owner -> exact target and expiry. */
    private final Map<UUID, PendingDestroy> pendingDestroy = new ConcurrentHashMap<>();
    private static final long DESTROY_CONFIRM_WINDOW_TICKS = 200L;

    /** How many natural-language orders were obeyed (self test). */
    private int naturalOrders;
    /** How many destroy orders were refused in one console line (self test). */
    private int destroyRefusals;
    /** How many orders were ignored because the speaker is not the owner (self test). */
    private int ignoredNonOwner;

    /** How many natural-language orders were obeyed this session. */
    public int naturalOrders() { return naturalOrders; }
    /** How many destroy refusals were written to the console. */
    public int destroyRefusals() { return destroyRefusals; }
    /** How many addressed orders were ignored because the speaker is not the owner. */
    public int ignoredNonOwner() { return ignoredNonOwner; }

    /** Self test: clears the P-09 counters before a measured window. */
    public void resetOrderCounters() {
        naturalOrders = 0;
        destroyRefusals = 0;
        ignoredNonOwner = 0;
        pendingDestroy.clear();
    }

    /**
     * Handles a natural sentence.
     *
     * <p>Only the owner is obeyed. A non-owner's order is ignored in silence -
     * not a word in chat, not a Null moved - while a non-owner's conversation is
     * left for the Commander to answer like any other player would.</p>
     *
     * @return true when the line was an order and has been dealt with
     */
    private boolean naturalOrder(Player player, String raw) {
        if (player == null || plugin.pluginConfig() == null) {
            return false;
        }
        V3Settings settings = plugin.pluginConfig().v3();
        redglitchx.nullarmy.core.orders.OrderParser.Order order =
                redglitchx.nullarmy.core.orders.OrderParser.parse(raw, commanderName(),
                        settings == null ? "@" : settings.mentionPrefix());
        if (order == null || !order.addressed() || !order.isOrder()) {
            return false;
        }
        // AsyncChatEvent may run off-thread. Parsing is pure; keep the event's
        // player handle, but do not query or mutate it until the main-thread task.
        later(() -> executeNaturalOrder(player, order));
        return true;
    }

    /** Executes one parsed natural-language order on the server thread. */
    private void executeNaturalOrder(Player player,
                                     redglitchx.nullarmy.core.orders.OrderParser.Order order) {
        if (player == null || !player.isValid() || order == null) {
            return;
        }
        if (plugin.commander() != null && plugin.commander().owner() != null
                && !plugin.commander().isOwner(player)) {
            // P-09: a non-owner's order is ignored silently. No chat, no movement.
            ignoredNonOwner++;
            plugin.getLogger().info("[NullArmy] " + player.getName() + " ordered the army without being"
                    + " its owner; the order was ignored in silence.");
            return;
        }
        if (!allowOrder(player)) {
            player.sendMessage(prefix() + "Slow down - the Nulls can only take so many orders a minute.");
            return;
        }
        if (order.verb() == redglitchx.nullarmy.core.orders.OrderParser.Verb.DESTROY) {
            destroyOrder(player, order);
            return;
        }
        String permission = naturalOrderPermission(order.verb());
        if (permission != null && !player.hasPermission(permission)) {
            player.sendMessage(prefix() + "That order needs " + permission + ". Nothing was changed.");
            return;
        }
        // A valid, authorized non-destroy order cancels a pending confirmation:
        // "stop" after "destroy". An unauthorized line does not consume it.
        pendingDestroy.remove(player.getUniqueId());
        if (!obey(player, order)) {
            return;
        }
        naturalOrders++;
        replyByMode(player, acknowledgement(order.verb().name(), player.getName()));
    }

    /** Maps natural orders to the same action-level permission as their typed command. */
    private static String naturalOrderPermission(redglitchx.nullarmy.core.orders.OrderParser.Verb verb) {
        if (verb == null) {
            return null;
        }
        switch (verb) {
            case BUILD:
            case BRIDGE:
                return "nullarmy.build";
            case ATTACK:
                return "nullarmy.attack";
            case STOP:
                return "nullarmy.admin";
            case FOLLOW:
            case COME:
            case GUARD:
            case MARCH:
            case DRILL:
            case PATROL:
            case DEFEND:
            case SALUTE:
            case REGROUP:
                return "nullarmy.follow";
            case DESTROY:
                return "nullarmy.admin";
            case CHAT:
            default:
                return null;
        }
    }

    /** Carries out one parsed order. @return false when the verb is not ours. */
    private boolean obey(Player player, redglitchx.nullarmy.core.orders.OrderParser.Order order) {
        if (plugin.brain() == null || plugin.squads() == null) {
            return false;
        }
        UUID owner = player.getUniqueId();
        List<NullBody> targets = new ArrayList<>(plugin.squads().membersOf(owner));
        if (targets.isEmpty()) {
            player.sendMessage(prefix() + "You have no Nulls to order - summon some first.");
            return true;
        }
        V3Settings settings = plugin.pluginConfig() == null ? null : plugin.pluginConfig().v3();
        switch (order.verb()) {
            case BUILD: {
                if (plugin.builder() == null) {
                    return false;
                }
                String goal = order.argument().isEmpty() ? "a small hut" : order.argument();
                Location at = player.getLocation();
                String answer = plugin.builder().start(owner, at.getWorld().getName(),
                        new Vec3d(at.getX(), at.getY(), at.getZ()), at.getYaw(), goal,
                        line -> player.sendMessage(prefix() + line));
                player.sendMessage(prefix() + answer);
                return true;
            }
            case BRIDGE: {
                // L-02: the whole squad bridges forward out of its own inventory.
                Location at = player.getLocation();
                double yaw = Math.toRadians(at.getYaw());
                Vec3d ahead = new Vec3d(at.getX() - Math.sin(yaw) * 10.0D, at.getY(),
                        at.getZ() + Math.cos(yaw) * 10.0D);
                player.sendMessage(prefix() + plugin.brain().bridge(targets, ahead, owner));
                return true;
            }
            case ATTACK: {
                Entity target = attackTarget(player, order.argument());
                if (target == null) {
                    player.sendMessage(prefix() + "Attack whom? Name an online player, or say 'them' while"
                            + " looking directly at a living target. I will not guess a target.");
                    return true;
                }
                if (target.getUniqueId().equals(owner)) {
                    player.sendMessage(prefix() + "Never. The army does not touch its own owner.");
                    return true;
                }
                if (settings != null && settings.isProtected(target.getName(),
                        target.getUniqueId())) {
                    player.sendMessage(prefix() + target.getName() + " is protected"
                            + " (policy.protected) - the army will not touch them.");
                    return true;
                }
                // L-07: hunt to the end - two chasers, the rest hold the line.
                player.sendMessage(prefix() + plugin.brain().hunt(targets, target.getUniqueId(), owner, 2));
                return true;
            }
            case FOLLOW:
            case COME:
                player.sendMessage(prefix() + plugin.brain().order(targets, Mind.Verb.FOLLOW, null,
                        owner, owner, 1));
                return true;
            case STOP:
                if (plugin.builder() != null) {
                    plugin.builder().stop(owner, "stopped by order");
                }
                player.sendMessage(prefix() + plugin.brain().order(targets, Mind.Verb.STOP, null, null,
                        owner, 1));
                return true;
            case GUARD:
            case DEFEND:
                player.sendMessage(prefix() + plugin.brain().order(targets, Mind.Verb.DEFEND, null,
                        owner, owner, 1));
                return true;
            case MARCH:
            case DRILL: {
                Location at = player.getLocation();
                player.sendMessage(prefix() + plugin.brain().order(targets,
                        order.verb() == redglitchx.nullarmy.core.orders.OrderParser.Verb.MARCH
                                ? Mind.Verb.MARCH : Mind.Verb.DRILL,
                        new Vec3d(at.getX(), at.getY(), at.getZ()), null, owner, 1));
                return true;
            }
            case PATROL: {
                Location at = player.getLocation();
                double yaw = Math.toRadians(at.getYaw());
                Vec3d b = new Vec3d(at.getX() - Math.sin(yaw) * 16.0D, at.getY(),
                        at.getZ() + Math.cos(yaw) * 16.0D);
                player.sendMessage(prefix() + plugin.brain().patrol(targets,
                        new Vec3d(at.getX(), at.getY(), at.getZ()), b, owner));
                return true;
            }
            case SALUTE:
                player.sendMessage(prefix() + plugin.brain().salute(targets, owner));
                return true;
            case REGROUP:
                player.sendMessage(prefix() + plugin.brain().order(targets, Mind.Verb.REGROUP, null,
                        owner, owner, 1));
                return true;
            default:
                return false;
        }
    }

    /** Resolves the target of an attack: 'them' (what the owner is looking at) or a name. */
    private Entity attackTarget(Player player, String argument) {
        String arg = argument == null ? "" : argument.trim();
        if (arg.isEmpty()) {
            return null; // an unqualified "attack" or "kill" never picks a target for the owner
        }
        if (arg.equalsIgnoreCase("them") || arg.equalsIgnoreCase("him")
                || arg.equalsIgnoreCase("her") || arg.equalsIgnoreCase("it")
                || arg.equalsIgnoreCase("that")) {
            org.bukkit.util.RayTraceResult hit = player.rayTraceEntities(48);
            Entity target = hit == null ? null : hit.getHitEntity();
            if (!(target instanceof org.bukkit.entity.LivingEntity) || target.isDead()
                    || target.getUniqueId().equals(player.getUniqueId())
                    || (plugin.adapter() != null && plugin.adapter().isNullEntity(target.getUniqueId()))) {
                return null; // pronouns only resolve the living entity the owner actually aimed at
            }
            return target;
        }
        return Bukkit.getPlayerExact(arg);
    }

    /**
     * P-09: destroy.
     *
     * <p>Refused unless {@code policy.griefing-enabled} is on, and even then only
     * after the owner confirms. A refusal is <b>one line in the console</b> - the
     * army says nothing, because a refusal in chat from a dozen Nulls would be a
     * shout, not an answer.</p>
     */
    private boolean destroyOrder(Player player, redglitchx.nullarmy.core.orders.OrderParser.Order order) {
        UUID owner = player.getUniqueId();
        boolean griefing = plugin.pluginConfig() != null && plugin.pluginConfig().griefingEnabled();
        if (!griefing) {
            pendingDestroy.remove(owner);
            destroyRefusals++;
            plugin.getLogger().info("[NullArmy] destroy refused for " + player.getName()
                    + ": policy.griefing-enabled is false.");
            return true;
        }
        if (!player.hasPermission("nullarmy.admin")) {
            pendingDestroy.remove(owner);
            player.sendMessage(prefix() + "A directed teardown needs nullarmy.admin. Nothing was changed.");
            return true;
        }

        String phrase = order.argument() == null ? "" : order.argument().trim().toLowerCase(Locale.ROOT);
        if (phrase.isEmpty()) {
            pendingDestroy.remove(owner);
            player.sendMessage(prefix() + "Name what to destroy and look directly at the block."
                    + " The target is limited to a small patch around that block.");
            return true;
        }
        org.bukkit.util.RayTraceResult trace = player.rayTraceBlocks(6.0D);
        Block target = trace == null ? null : trace.getHitBlock();
        if (target == null || target.getType().isAir()) {
            pendingDestroy.remove(owner);
            player.sendMessage(prefix() + "No block is targeted. Look at the exact block and repeat"
                    + " 'null destroy <target>'. Nothing was changed.");
            return true;
        }
        redglitchx.nullarmy.plugin.SquadManager.Squad squad = plugin.squads() == null
                ? null : plugin.squads().find(owner);
        if (squad == null || !squad.worldName().equals(target.getWorld().getName())) {
            pendingDestroy.remove(owner);
            player.sendMessage(prefix() + "The target block must be in the same world as your live squad."
                    + " Nothing was changed.");
            return true;
        }
        List<NullBody> members = plugin.squads().membersOf(owner);
        if (members.isEmpty()) {
            pendingDestroy.remove(owner);
            player.sendMessage(prefix() + "You have no Nulls to order - summon some first.");
            return true;
        }

        long now = plugin.currentTick();
        PendingDestroy pending = pendingDestroy.get(owner);
        if (pending == null || !pending.matches(phrase, target, now)) {
            pendingDestroy.put(owner, new PendingDestroy(phrase, target,
                    now + DESTROY_CONFIRM_WINDOW_TICKS));
            player.sendMessage(prefix() + "This will remove at most 9 blocks in a small patch centered on "
                    + target.getType().name().toLowerCase(Locale.ROOT) + " at "
                    + target.getX() + "," + target.getY() + "," + target.getZ()
                    + ". Repeat the same directed order while aiming at this block within 10 seconds to confirm.");
            return true;
        }
        pendingDestroy.remove(owner);
        if (plugin.brain() == null || plugin.squads() == null) {
            return true;
        }
        // One worker, one horizontal 3x3 layer, and a hard 9-block cap keep an
        // explicitly enabled teardown bounded. The owner confirms the exact
        // world/block again; it never defaults to a broad area around the player.
        NullBody worker = members.get(0);
        Vec3d centre = new Vec3d(target.getX() + 0.5D, target.getY() - 1.0D,
                target.getZ() + 0.5D);
        player.sendMessage(prefix() + plugin.brain().destroy(java.util.Collections.singletonList(worker),
                centre, 1, owner, target.getWorld().getName()));
        naturalOrders++;
        replyByMode(player, "As you say. It comes down.");
        return true;
    }

    private int triggered;

    /** How many chat lines were addressed to the Commander this session (self test). */
    public int triggeredCount() { return triggered; }

    /** Runs on the main thread a tick later - after the player's own line is shown. */
    private void later(Runnable action) {
        boolean mainThread = Bukkit.isPrimaryThread();
        try {
            Bukkit.getScheduler().runTaskLater(plugin, () -> Guard.attempt(plugin.getLogger(),
                    "Commander reply", action::run), 1L);
        } catch (Throwable t) {
            if (mainThread) {
                action.run();
            } else {
                // Never fall back to touching Bukkit/world state from AsyncChatEvent.
                plugin.getLogger().fine("[NullArmy] deferred chat work was not scheduled ("
                        + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private void commanderReply(String text) {
        final String name = commanderName();
        later(() -> {
            if (plugin.chatGate() != null) {
                plugin.chatGate().commanderSay(name, text);
            }
        });
    }

    private void replyByMode(Player player, String text) {
        if (player == null || text == null) {
            return;
        }
        ChatMode mode = chatMode(player.getUniqueId());
        if (mode == ChatMode.PUBLIC) {
            commanderReply(text);
        } else if (!explicitlyOff(player.getUniqueId())) {
            player.sendMessage(prefix() + text);
        }
    }

    private String commanderName() {
        return plugin.commander() == null || plugin.commander().commanderName() == null
                ? "NullCommander" : plugin.commander().commanderName();
    }

    private static String acknowledgement(String verb, String player) {
        String v = verb == null ? "" : verb.toLowerCase(Locale.ROOT);
        switch (v) {
            case "attack": case "kill": return "Understood, " + player + ". Moving on the target.";
            case "follow": case "come": return "On our way, " + player + ".";
            case "stop": case "hold": case "guard": return "Holding.";
            case "build": return "Builders, to work.";
            case "gather": return "Gathering wood.";
            case "defend": return "We cover you, " + player + ".";
            case "jump": return "Hup.";
            default: return "Understood - " + v + ".";
        }
    }

    /** "walk here", "sprint to Steve", "hold square"... the v3 order verbs, for every Null. */
    static String[] orderWords(String rest) {
        String[] parts = rest.trim().split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        String verb = parts[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        java.util.Set<String> verbs = java.util.Set.of("walk", "run", "sprint", "jump", "stop", "follow", "hold",
                "gather", "build", "attack", "defend");
        if (!verbs.contains(verb)) {
            return null;
        }
        List<String> args = new ArrayList<>();
        args.add("order");
        args.add("all");
        args.add(verb);
        for (int i = 1; i < parts.length; i++) {
            String word = parts[i];
            if (i == 1 && (word.equalsIgnoreCase("to") || word.equalsIgnoreCase("me"))) {
                if (word.equalsIgnoreCase("me")) {
                    args.add("here");
                }
                continue;
            }
            args.add(word);
        }
        return args.toArray(new String[0]);
    }

    /**
     * Strips a wake word, or the Commander's name, from the front of a line.
     *
     * @return the rest of the line, "" when nothing followed, or null when the
     *     line is not addressed to the Commander at all
     */
    String stripTrigger(String raw) {
        String rest = stripWakeWord(raw);
        if (rest != null) {
            return rest;
        }
        if (config != null && !config.v3().commanderNameTrigger()) {
            return null;
        }
        String name = commanderName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        int at = -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|[^a-z0-9_])" + java.util.regex.Pattern.quote(n)
                + "($|[^a-z0-9_])").matcher(lower);
        if (m.find()) {
            at = m.start() + m.group(1).length();
        }
        if (at < 0) {
            return null;
        }
        String before = text.substring(0, at).trim();
        String after = text.substring(at + name.length()).trim();
        if (after.startsWith(",") || after.startsWith(":")) {
            after = after.substring(1).trim();
        }
        if (before.endsWith(",")) {
            before = before.substring(0, before.length() - 1).trim();
        }
        String joined = (before + " " + after).trim();
        // "hey NullCommander" is a greeting with nothing else in it.
        if (joined.equalsIgnoreCase("hey") || joined.equalsIgnoreCase("hi") || joined.equalsIgnoreCase("hello")) {
            return joined;
        }
        return joined;
    }

    /** The Commander answers in public chat: a local line, the model, or an honest fallback. */
    private void answerPublicly(Player player, String message) {
        String local = localLine(message);
        if (brain == null || !brain.available()) {
            commanderReply(local != null ? local
                    : "I hear you, " + player.getName() + ". Give an order - walk, follow, hold, attack, build -"
                    + " and the Nulls move.");
            return;
        }
        if (local != null) {
            commanderReply(local);
            return;
        }
        final String persona = persona(Speaker.COMMANDER, player);
        brain.ask(persona, null, message, new ChatBrain.Reply() {
            @Override
            public void ok(String text) {
                commanderReply(clip(text, config == null ? 400 : config.chatMaxReplyChars()));
            }

            @Override
            public void failed(String reason) {
                commanderReply("I cannot think that through right now (" + reason + "). Orders still work.");
            }
        });
    }

    private boolean allowOrder(Player player) {
        int perMinute = config == null ? 20 : config.chatCommandsPerMinute();
        RateLimiter limiter = orderLimits.computeIfAbsent(player.getUniqueId(),
                id -> RateLimiter.perMinute(perMinute));
        return limiter.tryAcquire();
    }

    /** Runs the order on the server thread through the real command executor. */
    private boolean dispatch(Player player, String[] args) {
        if (player == null || args == null || command == null) {
            plugin.getLogger().warning("[NullArmy] A chat order was dropped because the command is unavailable.");
            return false;
        }
        final String[] copy = args.clone();
        Runnable execute = () -> Guard.attempt(plugin.getLogger(), "executing a chat order",
                () -> command.dispatch(player, copy));
        if (Bukkit.isPrimaryThread()) {
            execute.run();
            return true;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, execute);
            return true;
        } catch (Throwable t) {
            // A failed async dispatch is dropped, never run against Bukkit off-thread.
            plugin.getLogger().warning("[NullArmy] A chat order was dropped because main-thread scheduling failed ("
                    + t.getClass().getSimpleName() + ").");
            return false;
        }
    }

    // ------------------------------------------------------------------ conversation

    private void converse(Player player, Session session, String message) {
        session.lastActivityTick = plugin.currentTick();
        push(session.history, new ChatBrain.Turn("user", message));

        if (!brain.available()) {
            String local = localLine(message);
            if (local == null) {
                player.sendMessage(prefix() + label(session.speaker) + ": the channel is"
                        + " not connected to a model, so I only know a few lines."
                        + " (" + brain.unavailableReason() + ")");
                return;
            }
            player.sendMessage(prefix() + label(session.speaker) + ": " + local);
            push(session.history, new ChatBrain.Turn("assistant", local));
            return;
        }
        if (session.awaitingReply) {
            player.sendMessage(prefix() + label(session.speaker) + ": one moment...");
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
        } else {
            session = null;
        }
        final Session expectedSession = session;

        // Greetings first: they are free, instant and never wrong.
        if (history == null || history.isEmpty()) {
            String local = localLine(message);
            if (local != null && !brain.available()) {
                player.sendMessage(prefix() + label(speaker) + ": " + local);
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
                if (expectedSession != null && sessions.get(player.getUniqueId()) != expectedSession) {
                    return; // the player switched channels while this reply was in flight
                }
                String clipped = clip(text, config == null ? 400 : config.chatMaxReplyChars());
                player.sendMessage(prefix() + label(speaker) + ": " + clipped);
                if (onDone != null) {
                    onDone.accept(clipped);
                }
            }

            @Override
            public void failed(String reason) {
                if (expectedSession != null && sessions.get(player.getUniqueId()) != expectedSession) {
                    return; // do not answer into a channel the player already left
                }
                String local = localLine(message);
                if (local != null) {
                    player.sendMessage(prefix() + label(speaker) + ": " + local);
                    if (onDone != null) {
                        onDone.accept(local);
                    }
                    return;
                }
                player.sendMessage(prefix() + "The " + label(speaker)
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
    /** Pure command-name resolution, exposed for diagnostics and runtime smoke tests. */
    public String[] asCommand(String rest) {
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
