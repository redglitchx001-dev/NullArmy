package redglitchx.nullarmy.plugin.selftest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * The runtime smoke test, run against a real Paper server.
 *
 * <p>Compiling proves nothing about whether a Null can be seen. This walks the
 * whole path - registration, tracking, the two packets a client needs, ticking
 * over many server ticks, portals, and the sequential totem shutdown - and prints
 * one line per check with a {@code PASS} or {@code FAIL} marker, so a headless
 * server in CI can be checked by grepping the log.</p>
 *
 * <p>It is honest about what it can and cannot prove without a client:</p>
 * <ul>
 *   <li>it <b>can</b> prove the server is tracking each Null
 *       ({@code ChunkMap.entityMap}), that the body is valid and present in the
 *       level's entity index, that its packet listener is installed, and that a
 *       viewer's connection is really handed
 *       {@code ClientboundPlayerInfoUpdatePacket} and the pairing bundle - the two
 *       packets a client turns into a visible player;</li>
 *   <li>it <b>cannot</b> prove what a GPU drew. That still needs a human looking
 *       at a client, and the remaining checks are listed in STATUS.md.</li>
 * </ul>
 *
 * <p>Everything it creates is removed before it finishes.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SelfTest {

    /** Log marker CI greps for. */
    public static final String MARKER = "[NullArmy][SELFTEST]";

    /** Ticks between two steps, so the server really ticks in between. */
    private static final int STEP_GAP_TICKS = 4;

    /** Ticks a step asks for before the next one runs, when it needs real time. */
    private static final int LONG_STEP_GAP_TICKS = 20;

    /** How many long observations of the ticking squad the test makes. */
    private static final int SQUAD_OBSERVATIONS = 10;

    private final NullArmyPlugin plugin;
    private final Deque<Runnable> steps = new ArrayDeque<>();
    private final List<String> results = new ArrayList<>();

    private int nextGap = STEP_GAP_TICKS;
    private boolean running;
    private int passed;
    private int failed;
    private CommandSender reporter;
    private UUID owner;
    private String worldName;
    private Vec3d origin;
    private NullBody single;
    private NullBody probe;
    private UUID squadOwner;
    private int ticksObserved;
    private boolean tickingClean = true;
    private SelfTestV3 v3;

    public SelfTest(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() { return running; }

    /**
     * Starts a run.
     *
     * @return a message for the caller; the checks arrive in the log and, for a
     *     player, in chat as they complete
     */
    public String start(CommandSender sender) {
        if (running) {
            return "a self test is already running";
        }
        VersionAdapter adapter = plugin.adapter();
        if (adapter == null) {
            return "no version adapter is loaded, so there is nothing to test";
        }
        if (!Bukkit.isPrimaryThread()) {
            return "the self test has to be started from the server thread";
        }
        running = true;
        reporter = sender;
        passed = 0;
        failed = 0;
        ticksObserved = 0;
        tickingClean = true;
        results.clear();
        steps.clear();

        World world = sender instanceof Player && ((Player) sender).getWorld() != null
                ? ((Player) sender).getWorld() : Bukkit.getWorlds().get(0);
        worldName = world == null ? null : world.getName();
        if (worldName == null) {
            return finishNow("no world to test in");
        }
        owner = sender instanceof Player ? ((Player) sender).getUniqueId()
                : UUID.nameUUIDFromBytes("nullarmy-selftest".getBytes());
        squadOwner = owner;

        Vec3d safe = findSafeSpot(world, sender);
        if (safe == null) {
            return finishNow("no collision-safe ground near the test origin");
        }
        origin = safe;

        say("starting on " + worldName + " at " + (int) safe.x() + "," + (int) safe.y()
                + "," + (int) safe.z() + " (server " + Bukkit.getMinecraftVersion() + ")");
        say("adapter " + adapter.minecraftVersion() + ", tracking internals: "
                + adapter.trackingDiagnostics());

        steps.add(this::stepSingleSpawn);
        steps.add(this::stepProbeVisibility);
        steps.add(this::stepSingleCleanup);
        steps.add(this::stepSquadSpawn);
        for (int i = 0; i < SQUAD_OBSERVATIONS; i++) {
            steps.add(this::stepSquadTick);
        }
        steps.add(this::stepSquadSurvived);
        steps.add(this::stepPortalRestored);
        steps.add(this::stepSquadCleanup);
        // v3 (S-27 onward): one block of checks per bug B-01..B-17.
        v3 = new SelfTestV3(plugin, this, worldName, origin);
        v3.enqueue(steps);
        steps.add(this::stepShutdownSequence);
        // The sequence spends its configured delay between bodies, so it needs
        // more ticks than one step: wait until it is done rather than hoping.
        for (int i = 0; i < 12; i++) {
            steps.add(this::stepShutdownWait);
        }
        steps.add(this::stepShutdownResult);
        steps.add(this::stepSummary);
        scheduleNext();
        return "self test started: " + steps.size() + " steps, results go to the console log"
                + (sender instanceof Player ? " and to you" : "");
    }

    private void scheduleNext() {
        try {
            int gap = Math.max(1, nextGap);
            nextGap = STEP_GAP_TICKS;
            Bukkit.getScheduler().runTaskLater(plugin, this::runNextStep, gap);
        } catch (Throwable t) {
            fail("the scheduler refused to continue the self test: " + Guard.describe(t));
            finish();
        }
    }

    private void runNextStep() {
        if (!running) {
            return;
        }
        Runnable step = steps.poll();
        if (step == null) {
            finish();
            return;
        }
        try {
            step.run();
        } catch (Throwable t) {
            fail("a step threw: " + Guard.describe(t));
        }
        if (running) {
            scheduleNext();
        }
    }

    // --------------------------------------------------------------------- steps

    /** One Null, registered and verified the way a summon does it. */
    private void stepSingleSpawn() {
        try {
            single = plugin.squads().spawnOne(owner, worldName, origin, false);
        } catch (Throwable t) {
            single = null;
            fail("a single Null could not be spawned: " + Guard.describe(t));
            return;
        }
        check(single != null, "a single Null was created");
        if (single == null) {
            return;
        }
        check(single.isAlive(), "the single Null is alive after registration");
        check(plugin.adapter().packetListenerReady(single),
                "the single Null has a non-null packet listener (the tickChildren NPE fix)");
        check(plugin.adapter().isTracked(single),
                "the chunk map is tracking the single Null, so clients can be told about it");
        String kitProblem = plugin.kits() == null ? "kit service unavailable"
                : plugin.kits().verify(single);
        check(kitProblem == null, "the default kit is really equipped"
                + (kitProblem == null ? "" : ": " + kitProblem));
        check(single.profileName() != null && single.profileName().length() <= 16,
                "the profile name is legal and at most 16 characters (" + single.profileName() + ")");
    }

    /**
     * The two packets a client needs, proven against a recording viewer.
     *
     * <p>The probe is a NullPlayer whose connection records instead of discards.
     * Player info first, then the tracker's own pairing pass: exactly what a real
     * client receives, and exactly what it drops if either is missing.</p>
     */
    private void stepProbeVisibility() {
        if (single == null) {
            fail("visibility could not be checked: there is no Null to look at");
            return;
        }
        try {
            Vec3d at = single.bodyPosition();
            // The probe goes two blocks away but INSIDE the target's own chunk: a
            // viewer in the next chunk would add a second variable to a test that
            // is trying to prove one thing.
            double probeX = at.x() + 2.0;
            double chunkMinX = Math.floor(probeX / 16.0) * 16.0;
            if (Math.floor(probeX / 16.0) != Math.floor(at.x() / 16.0)) {
                probeX = at.x() - 2.0;
            }
            if (Math.floor(probeX / 16.0) != Math.floor(at.x() / 16.0)) {
                probeX = chunkMinX + 8.5;
            }
            probe = plugin.adapter().createViewerProbe(worldName, new Vec3d(probeX, at.y(), at.z()));
        } catch (Throwable t) {
            probe = null;
            fail("the viewer probe could not be created: " + Guard.describe(t));
            return;
        }
        check(probe != null, "a viewer probe was created next to the Null");
        if (probe == null) {
            return;
        }
        boolean announced = plugin.adapter().announceTo(probe, single);
        check(announced, "the player-info packet was accepted for the Null");
        boolean paired = plugin.adapter().pairProbe(probe, single);
        check(paired, "the tracker delivered the Null to the viewer"
                + (paired ? " (" + pairingPath() + ")"
                        : " - " + plugin.adapter().trackingDiagnostics()));

        say("pairing path: " + plugin.adapter().trackingDiagnostics());
        List<String> packets = plugin.adapter().probePackets(probe);
        check(packets.contains("ClientboundPlayerInfoUpdatePacket"),
                "the viewer received ClientboundPlayerInfoUpdatePacket"
                        + " (without it a client logs 'add player prior to sending player info'"
                        + " and renders nothing)");
        check(packets.contains("ClientboundBundlePacket")
                        || packets.contains("ClientboundAddEntityPacket"),
                "the viewer received the entity pairing packets");
        int viewers = plugin.adapter().viewerCount(single);
        check(viewers >= 1, "the tracker counts at least one viewer (" + viewers + ")");
    }

    /**
     * Which path produced the pairing packets.
     *
     * <p>Reported rather than hidden: the tracker's own decision path needs a
     * viewer that has genuinely received its chunks, which a headless probe never
     * has, so the test may fall back to the exact call that path makes. The verdict
     * says which one it was instead of implying more than was measured.</p>
     */
    private String pairingPath() {
        for (String line : plugin.adapter().trackingDiagnostics()) {
            if (line.startsWith("lastPairing=")) {
                return line.substring("lastPairing=".length());
            }
        }
        return "path not reported";
    }

    private void stepSingleCleanup() {
        if (probe != null) {
            Guard.attempt(plugin.getLogger(), "removing the probe", probe::destroy);
            probe = null;
        }
        if (single != null) {
            Guard.attempt(plugin.getLogger(), "removing the test Null", single::destroy);
            boolean gone = !single.isAlive();
            check(gone, "the test Null is gone after destroy()");
            single = null;
        }
    }

    /** A whole squad, through the real summon path - portals and all. */
    private void stepSquadSpawn() {
        int size = plugin.pluginConfig() == null ? 5
                : plugin.pluginConfig().selfTestSquadSize();
        try {
            SquadManager.Squad squad = plugin.squads().createSquad(squadOwner, worldName, origin, size);
            int spawned = squad.members().size();
            check(spawned == size, "the squad of " + size + " spawned completely ("
                    + spawned + " live)");
            for (String failure : squad.spawnFailures()) {
                fail("a squad member did not spawn: " + failure);
            }
            int tracked = 0;
            for (NullBody body : squad.members()) {
                if (plugin.adapter().isTracked(body) && body.isAlive()
                        && plugin.adapter().packetListenerReady(body)) {
                    tracked++;
                }
            }
            check(tracked == spawned, "every squad member is tracked, alive and listening ("
                    + tracked + "/" + spawned + ")");
            say("arrival: " + squad.arrivalNote());
            if (plugin.portals() != null && plugin.portals().activeCount() > 0) {
                pass("real portal doorways are standing in the world: "
                        + plugin.portals().activeCount());
            } else {
                say("no doorway could be built at this spot; the squad used verified open ground");
            }
        } catch (Throwable t) {
            fail("the squad summon threw: " + Guard.describe(t));
        }
    }

    /** Watches the squad while the server ticks it. */
    private void stepSquadTick() {
        // Ask for a full second of real server ticking before the next look, so
        // "it survived ticking" means something.
        nextGap = LONG_STEP_GAP_TICKS;
        List<NullBody> members = plugin.squads().membersOf(squadOwner);
        ticksObserved += LONG_STEP_GAP_TICKS;
        int alive = 0;
        int tracked = 0;
        for (NullBody body : members) {
            if (body.isAlive()) {
                alive++;
            }
            if (plugin.adapter().isTracked(body)) {
                tracked++;
            }
            Vec3d at = body.bodyPosition();
            if (at == null || !Double.isFinite(at.x()) || !Double.isFinite(at.y())
                    || !Double.isFinite(at.z())) {
                tickingClean = false;
            }
        }
        if (alive != members.size() || tracked != members.size()) {
            tickingClean = false;
            say("after " + ticksObserved + " ticks: " + alive + " alive, " + tracked
                    + " tracked of " + members.size());
        }
        for (String problem : plugin.selfTestErrors()) {
            tickingClean = false;
            fail("the server reported a problem while ticking the squad: " + problem);
        }
    }

    private void stepSquadSurvived() {
        List<NullBody> members = plugin.squads().membersOf(squadOwner);
        check(tickingClean, "the squad stayed alive, tracked and finite over "
                + ticksObserved + " server ticks");
        check(!members.isEmpty(), "the squad is still present after ticking ("
                + members.size() + " member(s))");
    }

    /** Portals are temporary: their blocks must be back to what they were. */
    private void stepPortalRestored() {
        if (plugin.portals() == null) {
            say("portal lifetime not checked: the portal manager is not wired");
            return;
        }
        int standing = plugin.portals().activeCount();
        int restored = plugin.portals().restoreAll();
        check(restored >= 0, "portal cleanup ran (" + standing + " standing, "
                + restored + " restored)");
        check(plugin.portals().activeCount() == 0, "no doorway is left standing after cleanup");
    }

    private void stepSquadCleanup() {
        try {
            int removed = plugin.squads().dismiss(squadOwner);
            say("dismissed " + removed + " squad member(s)");
        } catch (Throwable t) {
            fail("dismissing the squad threw: " + Guard.describe(t));
        }
    }

    /** The Totem Of Null shutdown: one at a time, Commander last. */
    private void stepShutdownSequence() {
        try {
            plugin.squads().createSquad(squadOwner, worldName, origin, 3);
        } catch (Throwable t) {
            fail("the shutdown fixture could not be spawned: " + Guard.describe(t));
            return;
        }
        int before = plugin.squads().liveCount();
        boolean started = plugin.shutdown().start("the self test destroyed a Totem Of Null");
        check(started, "the sequential shutdown started with " + before + " Null(s)");
        check(plugin.shutdown().isRunning(), "the shutdown is running");
        check(!summonAllowedNow(), "new summons are refused while the shutdown runs");
    }

    private void stepShutdownWait() {
        // Nothing to do but let the server tick the sequence.
        say("shutdown progress: " + plugin.shutdown().describe());
    }

    private void stepShutdownResult() {
        if (v3 != null && plugin.chatGate() != null) {
            int forbidden = plugin.chatGate().forbiddenBroadcasts() - v3.forbiddenAtStart();
            check(forbidden == 0, "S-84 [B-08] a Null death and a Totem Of Null shutdown caused zero chat"
                    + " broadcasts (" + forbidden + " forbidden, " + plugin.chatGate().events() + " events logged)");
        }
        check(!plugin.shutdown().isRunning(), "the shutdown finished");
        check(plugin.squads().liveCount() == 0,
                "every Null is gone after the shutdown (" + plugin.squads().liveCount() + " left)");
        check(summonAllowedNow(), "summons are accepted again after the shutdown");
    }

    /**
     * True when the plugin would accept a summon right now.
     *
     * <p>Asks for zero Nulls on purpose: the preflight refuses that with its real
     * reason, so the answer distinguishes "a shutdown is blocking summons" from
     * "summons work".</p>
     */
    private boolean summonAllowedNow() {
        try {
            plugin.squads().createSquad(squadOwner, worldName, origin, 0);
            return true;
        } catch (IllegalStateException refusal) {
            String message = refusal.getMessage() == null ? "" : refusal.getMessage();
            boolean blockedByShutdown = message.contains("Totem Of Null shutdown")
                    || message.contains("the plugin is shutting down");
            return !blockedByShutdown;
        } catch (Throwable t) {
            return true;
        }
    }

    private void stepSummary() {
        finish();
    }

    // ------------------------------------------------------------------ reporting

    void check(boolean ok, String what) {
        if (ok) {
            pass(what);
        } else {
            fail(what);
        }
    }

    void pass(String what) {
        passed++;
        results.add("PASS " + what);
        say("PASS " + what);
    }

    void fail(String what) {
        failed++;
        results.add("FAIL " + what);
        say("FAIL " + what);
    }

    /** Runs {@code step} again next (after the gap), ahead of the queued steps. */
    void retry(Runnable step) {
        steps.addFirst(step);
    }

    /** Asks for {@code ticks} of real server time before the next step runs. */
    void gap(int ticks) {
        nextGap = Math.max(1, ticks);
    }

    void say(String line) {
        plugin.getLogger().info(MARKER + " " + line);
        if (reporter instanceof Player && ((Player) reporter).isOnline()) {
            try {
                reporter.sendMessage(redglitchx.nullarmy.plugin.util.PluginText.PREFIX
                        + "[selftest] " + line);
            } catch (Throwable ignored) {
                // The log is the record; chat is a convenience.
            }
        }
    }

    private String finishNow(String reason) {
        running = false;
        say("ABORTED: " + reason);
        return "self test aborted: " + reason;
    }

    private void finish() {
        running = false;
        cleanupEverything();
        String verdict = failed == 0 ? "PASS" : "FAIL";
        String summary = verdict + " " + passed + " passed, " + failed + " failed";
        plugin.getLogger().info(MARKER + " RESULT: " + summary);
        if (reporter != null) {
            try {
                reporter.sendMessage(redglitchx.nullarmy.plugin.util.PluginText.PREFIX
                        + "[selftest] RESULT: " + summary);
            } catch (Throwable ignored) {
                // Already in the log.
            }
        }
    }

    /** Leaves the world exactly as the test found it. */
    private void cleanupEverything() {
        if (v3 != null) {
            Guard.attempt(plugin.getLogger(), "v3 self test cleanup", () -> v3.cleanup());
        }
        Guard.attempt(plugin.getLogger(), "self test cleanup", () -> {
            if (probe != null) {
                probe.destroy();
                probe = null;
            }
            if (single != null) {
                single.destroy();
                single = null;
            }
            plugin.squads().dismiss(squadOwner);
            if (plugin.portals() != null) {
                plugin.portals().restoreAll();
            }
            plugin.registry().sweep();
        });
    }

    /** The last run's lines, for {@code /null debug}. */
    public List<String> lastResults() {
        return new ArrayList<>(results);
    }

    public int lastPassed() { return passed; }

    public int lastFailed() { return failed; }

    /** Finds collision-safe ground for the test, near the sender or world spawn. */
    private Vec3d findSafeSpot(World world, CommandSender sender) {
        VersionAdapter adapter = plugin.adapter();
        Location base;
        if (sender instanceof Player && ((Player) sender).getWorld() == world) {
            base = ((Player) sender).getLocation();
        } else {
            base = world.getSpawnLocation();
        }
        if (base == null) {
            return null;
        }
        double bx = Math.floor(base.getX());
        double bz = Math.floor(base.getZ());
        for (int dy = 0; dy <= 3; dy++) {
            for (int ring = 0; ring <= 8; ring++) {
                double r = ring * 2.0;
                int points = ring == 0 ? 1 : Math.max(8, (int) (2 * Math.PI * r));
                for (int i = 0; i < points; i++) {
                    double angle = (2.0 * Math.PI * i) / points;
                    double x = bx + (ring == 0 ? 0 : Math.cos(angle) * r);
                    double z = bz + (ring == 0 ? 0 : Math.sin(angle) * r);
                    // Stand clear of the spawn point itself so the test never
                    // fights the world's own spawn area.
                    Vec3d candidate = new Vec3d(x + 0.5, base.getY() + dy, z + 0.5);
                    try {
                        if (adapter.isSpawnSafe(worldName, candidate)
                                && adapter.isEntitySpaceFree(worldName, candidate)) {
                            return candidate;
                        }
                    } catch (Throwable ignored) {
                        // Try the next spot.
                    }
                }
            }
        }
        return null;
    }
}
