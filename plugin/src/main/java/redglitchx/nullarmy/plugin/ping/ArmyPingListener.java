package redglitchx.nullarmy.plugin.ping;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;

import redglitchx.nullarmy.core.ping.PingNumbers;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The server-list ping: the army shows up in the multiplayer screen.
 *
 * <h2>P-11</h2>
 * <ul>
 *   <li>{@code numPlayers} is the <b>real online players plus the live
 *       Nulls</b> - the army counts, because it is really there;</li>
 *   <li>{@code maxPlayers} is {@code motd.max-players} (2026);</li>
 *   <li>the MOTD is {@code motd.format}, {@code NULL ARMY - {nulls} strong};</li>
 *   <li>the hover sample lists the Nulls first, then the real players.</li>
 * </ul>
 *
 * <p>All of it is off when {@code motd.show-army} is false, and none of it can
 * ever claim more people than are really on the server.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ArmyPingListener implements Listener {

    private final NullArmyPlugin plugin;

    /** How many pings were answered with the army in them (self test). */
    private int answered;
    /** The last numbers that were published, for {@code /null status}. */
    private String lastLine = "";

    public ArmyPingListener(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Paper's extended ping is the one that owns the player count and the hover
     * sample, so it does the whole job; the plain Bukkit event below only
     * catches servers that fire the base event.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onPing(PaperServerListPingEvent event) {
        Guard.attempt(plugin.getLogger(), "server list ping", () -> handle(event, true));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlainPing(ServerListPingEvent event) {
        if (event instanceof PaperServerListPingEvent) {
            return; // already handled, with the count and the sample
        }
        Guard.attempt(plugin.getLogger(), "server list ping", () -> handle(event, false));
    }

    private void handle(ServerListPingEvent event, boolean full) {
        if (event == null) {
            return;
        }
        PluginConfig config = plugin.pluginConfig();
        V3Settings settings = config == null ? null : config.v3();
        if (settings != null && !settings.motdShowArmy()) {
            return; // opt-out: the vanilla list is left exactly alone
        }
        int nulls = 0;
        List<String> nullNames = new ArrayList<>();
        if (plugin.squads() != null) {
            for (NullBody body : plugin.squads().allMembers()) {
                try {
                    if (body == null || !body.isAlive()) {
                        continue;
                    }
                    nulls++;
                    String name = body.profileName();
                    if (name != null && !name.isEmpty()) {
                        nullNames.add(name);
                    }
                } catch (Throwable ignored) {
                    // A body that cannot be read is not counted.
                }
            }
        }
        List<String> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player == null || plugin.adapter() == null
                    || plugin.adapter().isNullEntity(player.getUniqueId())) {
                continue; // a Null is not a second, real player
            }
            players.add(player.getName());
        }
        int real = players.size();
        int max = PingNumbers.clampMax(settings == null ? PingNumbers.DEFAULT_MAX : settings.motdMaxPlayers(),
                real + nulls);

        try {
            event.setMaxPlayers(max);
        } catch (Throwable ignored) {
            // Some proxies own this number; the MOTD still carries the truth.
        }
        String format = settings == null ? PingNumbers.DEFAULT_FORMAT : settings.motdFormat();
        String motd = PingNumbers.motd(format, nulls, real);
        if (!motd.isEmpty()) {
            try {
                event.setMotd(motd);
            } catch (Throwable ignored) {
                // A MOTD that cannot be set is not worth a stack trace.
            }
        }
        if (full && event instanceof PaperServerListPingEvent) {
            try {
                ((PaperServerListPingEvent) event).setNumPlayers(real + nulls);
                List<PaperServerListPingEvent.ListedPlayerInfo> listed =
                        ((PaperServerListPingEvent) event).getListedPlayers();
                listed.clear();
                for (String name : PingNumbers.sample(nullNames, players, PingNumbers.SAMPLE_LIMIT)) {
                    listed.add(new PaperServerListPingEvent.ListedPlayerInfo(name, NIL_UUID));
                }
            } catch (Throwable ignored) {
                // The sample is a nicety; the count above is the promise.
            }
        }
        answered++;
        lastLine = PingNumbers.num(real, nulls, max) + " - " + motd;
    }

    /** The hover sample carries names only, so the id is never a real player's. */
    private static final UUID NIL_UUID = new UUID(0L, 0L);

    /** How many pings have been answered with the army in them. */
    public int answered() {
        return answered;
    }

    /** The last count/MOTD line that was published, for {@code /null status}. */
    public String lastLine() {
        return lastLine;
    }
}
