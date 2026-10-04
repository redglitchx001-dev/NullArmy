package redglitchx.nullarmy.plugin.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.text.MessageTemplates;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * The only door between the plugin and in-game chat.
 *
 * <h2>The chat silence law</h2>
 * <p>In-game chat carries exactly four things:</p>
 * <ol>
 *   <li>answers to the player who issued a command ({@link #answer});</li>
 *   <li>{@code /null help};</li>
 *   <li>the summon-count question and its confirm/cancel answers;</li>
 *   <li>the Commander's own conversation lines ({@link #commanderSay}).</li>
 * </ol>
 * <p>Everything else - deaths, arrivals, kit reports, missions, the Totem Of Null
 * shutdown, unstacking, portal clean-up - is an <b>event</b>: it goes to the
 * console and to the {@code /null status} event log ({@link #event}) and never
 * to chat. Vanilla's own death and advancement broadcasts for Nulls are
 * cleared by the lifecycle listener and counted here.</p>
 *
 * <p>The counters exist so the self test can prove the law with numbers rather
 * than by reading code.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ChatGate {

    private static final int EVENT_LOG_SIZE = 60;

    private final Logger logger;
    private final Deque<String> events = new ArrayDeque<>();
    private final AtomicInteger answers = new AtomicInteger();
    private final AtomicInteger broadcasts = new AtomicInteger();
    private final AtomicInteger commanderLines = new AtomicInteger();
    private final AtomicInteger eventCount = new AtomicInteger();
    private final AtomicInteger vanillaSilenced = new AtomicInteger();
    private final AtomicInteger vanillaLeaked = new AtomicInteger();
    private volatile String lastCommanderLine = "";

    public ChatGate(Logger logger) {
        this.logger = logger;
    }

    // ---------------------------------------------------------------- allowed

    /** An answer to the sender of a command (or of a chat order). */
    public void answer(CommandSender to, String message) {
        if (to == null || message == null) {
            return;
        }
        answers.incrementAndGet();
        to.sendMessage(PluginText.PREFIX + message);
    }

    /**
     * A public line spoken by the Commander: gradient brand, the Commander's
     * plain name, the text. The Commander is in the tab list like a player, so
     * this reads as a player talking.
     */
    public void commanderSay(String commanderName, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String name = commanderName == null || commanderName.isEmpty() ? "NullCommander" : commanderName;
        Component line = PluginText.gradientComponent("[NullArmy]")
                .append(Component.text(" "))
                .append(Component.text(name, NamedTextColor.WHITE))
                .append(Component.text(": ", NamedTextColor.GRAY))
                .append(Component.text(text.trim(), NamedTextColor.WHITE));
        commanderLines.incrementAndGet();
        broadcasts.incrementAndGet();
        lastCommanderLine = text.trim();
        try {
            Bukkit.getServer().broadcast(line);
        } catch (Throwable t) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                player.sendMessage(line);
            }
        }
        if (logger != null) {
            logger.info("[NullArmy] <" + name + "> " + text.trim());
        }
    }

    // ------------------------------------------------------------------ events

    /** An event line from a template: console + {@code /null status}, never chat. */
    public void event(String templateKey, Object... nameValuePairs) {
        String line;
        try {
            line = MessageTemplates.render(templateKey, nameValuePairs);
        } catch (IllegalArgumentException broken) {
            line = templateKey + " (event text unavailable: " + broken.getMessage() + ")";
        }
        eventRaw(line);
    }

    /** A free-form event line: console + {@code /null status}, never chat. */
    public void eventRaw(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        eventCount.incrementAndGet();
        synchronized (events) {
            events.addLast(line.trim());
            while (events.size() > EVENT_LOG_SIZE) {
                events.removeFirst();
            }
        }
        if (logger != null) {
            logger.info("[NullArmy] " + line.trim());
        }
    }

    /** The most recent event lines, oldest first. */
    public List<String> recentEvents(int max) {
        synchronized (events) {
            List<String> all = new ArrayList<>(events);
            return all.subList(Math.max(0, all.size() - Math.max(1, max)), all.size());
        }
    }

    // ---------------------------------------------------------- vanilla guard

    /** A vanilla broadcast for a Null (death, advancement) was cleared. */
    public void vanillaSilenced() {
        vanillaSilenced.incrementAndGet();
    }

    /** A vanilla broadcast for a Null got through (checked at MONITOR). */
    public void vanillaLeaked(String what) {
        vanillaLeaked.incrementAndGet();
        if (logger != null) {
            logger.warning("[NullArmy] a vanilla chat broadcast for a Null got through: " + what);
        }
    }

    // ------------------------------------------------------------ measurements

    /** Every line the plugin itself broadcast to all players. */
    public int broadcasts() { return broadcasts.get(); }

    /** Commander conversation lines. */
    public int commanderLines() { return commanderLines.get(); }

    public String lastCommanderLine() { return lastCommanderLine; }

    public int answers() { return answers.get(); }

    public int events() { return eventCount.get(); }

    public int vanillaSilencedCount() { return vanillaSilenced.get(); }

    public int vanillaLeakedCount() { return vanillaLeaked.get(); }

    /**
     * Chat broadcasts that were not Commander lines - always zero, because no
     * other path to all players exists; plus vanilla broadcasts that leaked.
     */
    public int forbiddenBroadcasts() {
        return (broadcasts.get() - commanderLines.get()) + vanillaLeaked.get();
    }
}
