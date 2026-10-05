package redglitchx.nullarmy.plugin.mission;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.mission.Mission;
import redglitchx.nullarmy.core.mission.MissionBoard;
import redglitchx.nullarmy.core.mission.MissionKind;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.List;
import java.util.UUID;

/**
 * Drives one original NullArmy mission at a time.
 *
 * <p>A mission is how the Commander and every active Null pull in the same
 * direction: it stores one objective, gives the squad a real movement order to
 * work on, reports progress as it goes, and stops safely on command, on timeout,
 * or when the squad is gone.</p>
 *
 * <h2>What it never does</h2>
 * No mission destroys a block, spawns an explosive, or attacks a player. The
 * objectives are movement, formation and reporting - the same validated orders
 * {@code /null follow} and {@code /null guard} already run - so the whole system
 * is safe with every policy switch left off.
 *
 * <p>The mission content itself is original to NullArmy: named operations written
 * for this plugin, with no characters, items, dialogue or plots from any other
 * work.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MissionRunner implements Reloadable {

    private static final String PREFIX = PluginText.PREFIX;

    /** How often a running mission checks itself and reports, in ticks. */
    private static final int REPORT_INTERVAL_TICKS = 200;

    private final NullArmyPlugin plugin;
    private final MissionBoard board = new MissionBoard();

    private PluginConfig config;
    private long lastReportTick = -1L;
    private UUID owner;

    public MissionRunner(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    public boolean isRunning() { return board.isRunning(); }

    /** One line for {@code /null status} and for the Commander's snapshot. */
    public String oneLine() {
        Mission mission = board.active();
        return mission == null ? "none running" : mission.report();
    }

    /** The full report, for {@code /null mission}. */
    public List<String> describe() { return board.describe(); }

    /** Every mission key, for tab completion. */
    public static List<String> kinds() { return MissionBoard.kinds(); }

    /**
     * Starts a mission for one owner's squad.
     *
     * @return a message ready to print, explaining what happened
     */
    public String start(UUID ownerId, String kindKey) {
        if (config != null && !config.missionsEnabled()) {
            return "missions are off: set missions.enabled to true in config.yml.";
        }
        Player player = ownerId == null ? null : Bukkit.getPlayer(ownerId);
        if (player == null || !player.isOnline()) {
            return "only an online player can start a mission.";
        }
        MissionKind kind = MissionKind.parse(kindKey);
        if (kind == null) {
            return "'" + kindKey + "' is not a mission. Try one of: "
                    + String.join(", ", MissionBoard.kinds()) + ".";
        }
        SquadManager squads = plugin.squads();
        List<NullBody> members = squads == null ? null : squads.membersOf(ownerId);
        if (members == null || members.isEmpty()) {
            return "you have no Nulls to send. Sound the horn first.";
        }
        Mission mission = board.start(kind, plugin.currentTick(), members.size(), null);
        if (mission == null) {
            return "the mission could not be started.";
        }
        owner = ownerId;
        lastReportTick = plugin.currentTick();

        // The squad gets a real order to work on: hold for a defensive objective,
        // walk to the owner for a moving one. Both are the plugin's own validated
        // objectives, so nothing here can bypass a gate.
        Guard.attempt(plugin.getLogger(), "giving the mission its objective", () -> {
            if (kind == MissionKind.BANNER_HOLD || kind == MissionKind.GATE_VIGIL) {
                squads.guard(ownerId);
            } else {
                squads.follow(ownerId, ownerId, "mission " + kind.key());
            }
        });

        plugin.getLogger().info("[NullArmy] mission started: " + kind.title()
                + " with " + members.size() + " Null(s) for " + player.getName());
        return kind.title() + " is under way with " + members.size() + " Null(s). "
                + kind.briefing() + " Progress: 0/" + kind.goal() + " " + kind.unit() + ".";
    }

    /** Stops the running mission safely. */
    public String stop(String reason) {
        if (!board.isRunning()) {
            return "no mission is running.";
        }
        Mission mission = board.active();
        String title = mission == null ? "the mission" : mission.kind().title();
        board.stop(plugin.currentTick(), reason);
        Guard.attempt(plugin.getLogger(), "clearing the mission objective", () -> {
            if (owner != null && plugin.squads() != null) {
                plugin.squads().clearObjectives(owner);
            }
        });
        plugin.getLogger().info("[NullArmy] mission stopped: " + title + " (" + reason + ")");
        return title + " stopped (" + reason + "). The squad is standing down, nothing was removed.";
    }

    /** Per-tick driver: reports progress, times out, and notices a lost squad. */
    public void tick(long tickCounter) {
        Mission mission = board.active();
        if (mission == null) {
            return;
        }
        if (owner == null) {
            board.stop(tickCounter, "the owner is unknown");
            return;
        }
        List<NullBody> members = plugin.squads() == null
                ? null : plugin.squads().membersOf(owner);
        if (members == null || members.isEmpty()) {
            String title = mission.kind().title();
            board.stop(tickCounter, "the squad is gone");
            announce(owner, PREFIX + title + " called off: there are no Nulls left to run it.");
            return;
        }
        if (board.tick(tickCounter)) {
            announce(owner, PREFIX + mission.report());
            Guard.attempt(plugin.getLogger(), "clearing a timed-out objective",
                    () -> plugin.squads().clearObjectives(owner));
            return;
        }
        if (tickCounter - lastReportTick < REPORT_INTERVAL_TICKS) {
            return;
        }
        lastReportTick = tickCounter;
        // Progress is earned by a squad that is still alive and still working:
        // one unit per report interval, up to the kind's goal.
        boolean finished = board.advance(1, tickCounter);
        announce(owner, PREFIX + (finished
                ? mission.kind().title() + " is complete: " + mission.report()
                : mission.report()));
        if (finished) {
            Guard.attempt(plugin.getLogger(), "clearing a completed objective",
                    () -> plugin.squads().clearObjectives(owner));
            owner = null;
        }
    }

    private void announce(UUID ownerId, String message) {
        try {
            // Chat silence: mission progress is an event - console and
            // /null status - never a chat line.
            if (plugin.chatGate() != null) {
                Player player = ownerId == null ? null : Bukkit.getPlayer(ownerId);
                plugin.chatGate().eventRaw("mission (" + (player == null ? "owner offline" : player.getName())
                        + "): " + redglitchx.nullarmy.plugin.util.PluginText.plain(message));
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] mission announcement skipped: " + Guard.describe(t));
        }
    }

    /** Who owns the running mission, for {@code /null mission}. */
    public UUID owner() { return owner; }
}
