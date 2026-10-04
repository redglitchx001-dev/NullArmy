package redglitchx.nullarmy.plugin.shutdown;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.totem.ShutdownSequence;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The controlled, sequential shutdown a Totem Of Null starts.
 *
 * <p>When the tagged totem pops or is truly destroyed, every Null on the server -
 * including the Commander - goes out <b>one at a time</b> with a visible delay
 * between them. While it runs:</p>
 * <ul>
 *   <li>queued summon prompts are cancelled;</li>
 *   <li>no new Null may be created ({@link SquadManager} refuses);</li>
 *   <li>objectives are cleared and any running mission is stopped;</li>
 *   <li>spectacle shots in flight are stopped so nothing is left behind;</li>
 *   <li>when the last body is gone, squads, timers and tracking are dropped.</li>
 * </ul>
 *
 * <p>It is idempotent: a second totem popping mid-sequence changes nothing, and a
 * shutdown with no Nulls to remove completes immediately and says so.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ShutdownDirector implements Reloadable {

    private static final String PREFIX = PluginText.PREFIX;

    private final NullArmyPlugin plugin;
    private final List<ShutdownSequence.Step> steps = new ArrayList<>();
    private final List<NullBody> targets = new ArrayList<>();

    private PluginConfig config;
    private long startTick = -1L;
    private long endTick = -1L;
    private int nextStep;
    private String reason = "";
    private boolean running;
    private boolean completed;

    public ShutdownDirector(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    public boolean isRunning() { return running; }

    public String reason() { return reason; }

    /** How many bodies are still waiting to go out. */
    public int remaining() {
        return running ? Math.max(0, targets.size() - nextStep) : 0;
    }

    /** One line for {@code /null status}. */
    public String describe() {
        if (!running) {
            return completed ? "finished (" + reason + ")" : "idle";
        }
        return "running: " + nextStep + "/" + targets.size() + " gone, "
                + ShutdownSequence.describe(steps, delayTicks()) + " (" + reason + ")";
    }

    private int delayTicks() {
        return config == null ? ShutdownSequence.DEFAULT_DELAY_TICKS : config.totemShutdownDelayTicks();
    }

    /**
     * Starts the sequence.
     *
     * @param why what happened to the totem, in words the owner will recognise
     * @return true when this call started it; false when one is already running
     *     or there was nothing to shut down
     */
    public boolean start(String why) {
        if (running) {
            return false;
        }
        reason = why == null || why.trim().isEmpty() ? "the Totem Of Null was destroyed" : why.trim();

        // Nothing may be created while the army is going out.
        cancelQueuedSummons();
        stopSpectacles();
        stopMissions();

        List<NullBody> bodies = collectTargets();
        if (bodies.isEmpty()) {
            completed = true;
            announce(PREFIX + reason + " - but there were no Nulls left to send out.");
            plugin.getLogger().info("[NullArmy] Totem Of Null shutdown: nothing to do.");
            return false;
        }

        List<ShutdownSequence.Entry> entries = new ArrayList<>();
        for (NullBody body : bodies) {
            entries.add(new ShutdownSequence.Entry(labelOf(body), isCommander(body)));
        }
        long now = plugin.currentTick();
        steps.clear();
        steps.addAll(ShutdownSequence.schedule(entries, now, delayTicks()));
        targets.clear();
        targets.addAll(bodies);
        nextStep = 0;
        startTick = now;
        endTick = ShutdownSequence.endTick(steps, now);
        running = true;
        completed = false;

        // Clear objectives first: a Null that is walking somewhere should stop
        // before it goes out, not mid-stride two chunks away. This is a
        // stand-down, NOT the plugin's permanent safe-shutdown latch - the horn
        // has to answer again once the last Null has gone.
        Guard.attempt(plugin.getLogger(), "standing the squad down for shutdown",
                () -> plugin.squads().standDown());

        plugin.getLogger().info("[NullArmy] " + reason + ": shutting down " + bodies.size()
                + " Null(s) one at a time, " + delayTicks() + " tick(s) apart.");
        announce(PREFIX + reason + ".");
        announce(PREFIX + "The NullArmy is going out: " + bodies.size() + " Null(s), one at a time,"
                + " Commander last. No new Nulls will answer the horn until it is over.");
        return true;
    }

    /** Per-tick driver: runs whatever is due, then finishes the sequence. */
    public void tick(long tickCounter) {
        if (!running) {
            return;
        }
        while (nextStep < steps.size() && steps.get(nextStep).atTick() <= tickCounter) {
            int index = nextStep;
            nextStep++;
            Guard.attempt(plugin.getLogger(), "shutting down a Null", () -> execute(index));
        }
        if (nextStep >= steps.size()) {
            finish();
        }
    }

    /** Sends one body out, visibly. */
    private void execute(int index) {
        if (index < 0 || index >= targets.size()) {
            return;
        }
        NullBody body = targets.get(index);
        targets.set(index, null);
        if (body == null) {
            return;
        }
        playDeparture(body);
        if (isCommander(body)) {
            Guard.attempt(plugin.getLogger(), "despawning the Commander",
                    () -> plugin.commander().despawn());
        }
        Guard.attempt(plugin.getLogger(), "removing a Null for shutdown", body::destroy);
        Guard.attempt(plugin.getLogger(), "forgetting a Null for shutdown",
                () -> plugin.squads().forget(body));

        boolean announceProgress = config == null || config.totemAnnounceProgress();
        if (announceProgress) {
            int total = steps.size();
            int gone = index + 1;
            // Every tenth body, plus the last one: enough to see it happening
            // without flooding chat for a squad of a hundred.
            if (total <= 10 || gone == total || gone % Math.max(1, total / 10) == 0) {
                announce(PREFIX + gone + "/" + total + " gone - " + labelOf(body) + " steps out.");
            }
        }
    }

    /** The visible part of one departure: light going back into the doorway. */
    private void playDeparture(NullBody body) {
        try {
            Vec3d at = body.bodyPosition();
            String worldName = body instanceof NullBody ? worldOf(body) : null;
            World world = worldName == null ? null : Bukkit.getWorld(worldName);
            if (world == null || at == null) {
                return;
            }
            Location location = new Location(world, at.x(), at.y() + 1.0, at.z());
            world.spawnParticle(Particle.REVERSE_PORTAL, location, 60, 0.4, 0.9, 0.4, 0.05);
            world.spawnParticle(Particle.SOUL, location, 12, 0.3, 0.7, 0.3, 0.01);
            world.playSound(location, Sound.BLOCK_PORTAL_TRIGGER, 0.7f, 0.7f);
            world.playSound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.6f);
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] departure effects skipped: " + Guard.describe(t));
        }
    }

    private String worldOf(NullBody body) {
        for (SquadManager.Squad squad : plugin.squads().allSquads()) {
            if (squad.members().contains(body)) {
                return squad.worldName();
            }
        }
        return null;
    }

    private void finish() {
        running = false;
        completed = true;
        int total = steps.size();
        // Cleanup: squads, tracking and anything the sequence left behind.
        Guard.attempt(plugin.getLogger(), "cleaning up after the shutdown", () -> {
            plugin.squads().forgetAllEmpty();
            plugin.registry().sweep();
            if (plugin.portals() != null) {
                plugin.portals().restoreAll();
            }
        });
        steps.clear();
        targets.clear();
        nextStep = 0;
        plugin.getLogger().info("[NullArmy] Totem Of Null shutdown complete: " + total
                + " Null(s) went out, the Commander last. The plugin is accepting summons again.");
        announce(PREFIX + "The NullArmy is gone: " + total + " Null(s) stepped out, the Commander"
                + " last. The horn will answer again.");
    }

    /** Stops the sequence immediately, removing whatever is left at once. */
    public void abort(String why) {
        if (!running) {
            return;
        }
        for (int i = nextStep; i < targets.size(); i++) {
            NullBody body = targets.get(i);
            if (body != null) {
                Guard.attempt(plugin.getLogger(), "removing a Null during an aborted shutdown",
                        body::destroy);
            }
        }
        finish();
        reason = (why == null || why.isEmpty()) ? reason : why;
    }

    // ------------------------------------------------------------------- helpers

    private List<NullBody> collectTargets() {
        List<NullBody> out = new ArrayList<>();
        // Ordinary Nulls first, in squad order, so the Commander is last.
        for (NullBody body : plugin.squads().allMembers()) {
            if (body != null && body.isAlive() && !isCommander(body)) {
                out.add(body);
            }
        }
        if (plugin.commander() != null && plugin.commander().isSpawned()) {
            NullBody commander = plugin.commander().body();
            if (commander != null && commander.isAlive() && !out.contains(commander)) {
                out.add(commander);
            }
        }
        // Any body the adapter still knows about that the squads do not.
        for (NullBody body : plugin.squads().orphanedBodies()) {
            if (body != null && body.isAlive() && !out.contains(body)) {
                out.add(body);
            }
        }
        return out;
    }

    private boolean isCommander(NullBody body) {
        return plugin.commander() != null && plugin.commander().body() == body;
    }

    private String labelOf(NullBody body) {
        if (isCommander(body)) {
            return plugin.commander().commanderName();
        }
        try {
            String name = body.profileName();
            return name == null || name.isEmpty() ? ("#" + body.id()) : name;
        } catch (Throwable t) {
            return "a Null";
        }
    }

    private void cancelQueuedSummons() {
        if (plugin.summonFlow() == null) {
            return;
        }
        Guard.attempt(plugin.getLogger(), "cancelling queued summons", () -> {
            int cancelled = plugin.summonFlow().cancelAll(
                    "a Totem Of Null shutdown is running");
            if (cancelled > 0) {
                plugin.getLogger().info("[NullArmy] cancelled " + cancelled
                        + " queued summon prompt(s).");
            }
        });
    }

    private void stopSpectacles() {
        Guard.attempt(plugin.getLogger(), "stopping cannon shots", () -> {
            if (plugin.witherCannon() != null) {
                plugin.witherCannon().stopAll();
            }
        });
        Guard.attempt(plugin.getLogger(), "stopping air drops", () -> {
            if (plugin.airdrop() != null) {
                plugin.airdrop().stopAll();
            }
        });
    }

    private void stopMissions() {
        Guard.attempt(plugin.getLogger(), "stopping missions", () -> {
            if (plugin.missions() != null) {
                plugin.missions().stop("the Totem Of Null was destroyed");
            }
        });
    }

    private void announce(String message) {
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] shutdown announcement skipped: "
                    + Guard.describe(t));
        }
    }

    /** The owner of a Null, for the report. Null when it is not known. */
    public UUID ownerOf(NullBody body) {
        return plugin.squads() == null ? null : plugin.squads().ownerOf(body);
    }

    /** How long the sequence has been running, in ticks. */
    public long elapsed(long now) {
        return running ? Math.max(0L, now - startTick) : 0L;
    }
}
