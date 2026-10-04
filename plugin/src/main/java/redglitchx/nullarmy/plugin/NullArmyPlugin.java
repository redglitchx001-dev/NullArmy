package redglitchx.nullarmy.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.util.TickBudget;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.ai.SquadCoordinator;
import redglitchx.nullarmy.plugin.commander.CommanderManager;
import redglitchx.nullarmy.plugin.chat.ChatBrain;
import redglitchx.nullarmy.plugin.chat.ChatDirector;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.ConfigBootstrap;
import redglitchx.nullarmy.plugin.config.ConfigMigration;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.kit.KitService;
import redglitchx.nullarmy.plugin.menu.MenuManager;
import redglitchx.nullarmy.plugin.mission.MissionRunner;
import redglitchx.nullarmy.plugin.portal.PortalManager;
import redglitchx.nullarmy.plugin.selftest.SelfTest;
import redglitchx.nullarmy.plugin.shutdown.ShutdownDirector;
import redglitchx.nullarmy.plugin.skin.SkinResolver;
import redglitchx.nullarmy.plugin.spectacle.Airdrop;
import redglitchx.nullarmy.plugin.spectacle.EntityRegistry;
import redglitchx.nullarmy.plugin.spectacle.WitherCannon;
import redglitchx.nullarmy.plugin.totem.TotemWatcher;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * NullArmy bootstrap.
 *
 * <p>Chooses a {@link VersionAdapter} for the running server, wires commands and
 * listeners, and owns the per-tick budgets that keep costly work bounded
 * (spec 9).</p>
 *
 * <h2>The rule this class is built around</h2>
 * <p>Nothing may escape {@code onEnable}, {@code onDisable}, a command, a
 * listener or a tick. A fake {@code ServerPlayer} with a null packet listener
 * can crash the server's own packet-send loop, outside the NPC tick guard. The
 * version adapter installs a non-null listener that discards outgoing sends
 * before registering each Null; the plugin still guards the spawn
 * path and all other risky work. So:</p>
 * <ul>
 *   <li>{@link #onEnable()} is wrapped as a whole, and each step is wrapped
 *       individually, so one broken subsystem cannot stop the rest;</li>
 *   <li>the data folder and {@code config.yml} are created defensively, and a
 *       jar that lost its resource degrades to built-in defaults instead of
 *       aborting startup ({@link ConfigBootstrap});</li>
 *   <li>every per-tick subsystem has its own try/catch with throttled logging,
 *       and a subsystem that keeps failing is switched off for the session
 *       rather than being retried twenty times a second;</li>
 *   <li>the NMS spawn path sits behind a latched breaker
 *       ({@link Guard.Breaker}), so the riskiest thing the plugin does can fail
 *       once and then stay off.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullArmyPlugin extends JavaPlugin {

    /** How often the entity registry is swept, in ticks (1s). */
    private static final int SWEEP_INTERVAL_TICKS = 20;

    /** Failures of one subsystem before it is switched off for this session. */
    private static final int SUBSYSTEM_FAILURE_LIMIT = 100;

    private PluginConfig pluginConfig;
    private VersionAdapter adapter;
    private SquadManager squads;
    private SummonFlow summonFlow;
    private SkinResolver skinResolver;
    private CommanderManager commander;
    private EntityRegistry entityRegistry;
    private WitherCannon witherCannon;
    private Airdrop airdrop;
    private NullCommand command;
    private MenuManager menuManager;
    private ChatDirector chatDirector;
    private ChatBrain chatBrain;
    private PortalManager portalManager;
    private KitService kitService;
    private TotemWatcher totemWatcher;
    private ShutdownDirector shutdownDirector;
    private SquadCoordinator coordinator;
    private MissionRunner missionRunner;
    private SelfTest selfTest;

    // v3
    private redglitchx.nullarmy.plugin.chat.ChatGate chatGate;
    private redglitchx.nullarmy.plugin.body.NullBrain brain;
    private redglitchx.nullarmy.plugin.body.NullLifecycleListener lifecycle;
    private redglitchx.nullarmy.plugin.skin.SkinChain skinChain;
    private redglitchx.nullarmy.plugin.ai.builder.BuilderService builder;
    private redglitchx.nullarmy.plugin.loadout.LoadoutService loadouts;
    private redglitchx.nullarmy.plugin.zone.ZoneService zones;
    private redglitchx.nullarmy.plugin.command.V3Commands v3Commands;

    /** The last config.yml that parsed; what getConfig() returns. */
    private org.bukkit.configuration.file.YamlConfiguration lastGoodConfig;
    /** Why the last load of config.yml was not applied; null when it was. */
    private redglitchx.nullarmy.core.config.YamlProblem lastConfigProblem;

    private TickBudget pathBudget;
    private TickBudget blockInspectionBudget;

    private final List<Reloadable> reloadables = new ArrayList<>();

    /** Latched guard around the NMS spawn path. See {@link Guard.Breaker}. */
    private final Guard.Breaker spawnBreaker = new Guard.Breaker("NMS Null spawning");

    /**
     * Recent failures from the tick loop, drained by the runtime smoke test.
     *
     * <p>The test has to be able to say "the server threw while the squad was
     * ticking" instead of only "the squad is still here", so anything the guards
     * catch is kept here until the test reads it.</p>
     */
    private final java.util.ArrayDeque<String> tickErrors = new java.util.ArrayDeque<>();

    private final Map<String, Integer> subsystemFailures = new HashMap<>();
    private final Set<String> disabledSubsystems = new HashSet<>();

    private BukkitTask tickTask;
    private long tickCounter;
    private boolean fullyEnabled;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        try {
            boot();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] onEnable failed: " + Guard.describe(t), t);
            getLogger().severe("[NullArmy] The server is unaffected; NullArmy stays disabled."
                    + " Run /null status (or read this log) and correct the reason above.");
            Guard.attempt(getLogger(), "disabling after a failed enable",
                    () -> getServer().getPluginManager().disablePlugin(this));
        }
    }

    private void boot() {
        this.chatGate = new redglitchx.nullarmy.plugin.chat.ChatGate(getLogger());
        // 1. Data folder + config, before anything reads a setting.
        final File configFile = ConfigBootstrap.prepare(this);
        this.pluginConfig = buildConfig();

        // 2. Version adapter. Without one there is no entity layer at all, so
        //    this is the only condition that stops NullArmy from enabling.
        String serverVersion = "unknown";
        try {
            serverVersion = getServer().getMinecraftVersion();
        } catch (Throwable t) {
            getLogger().warning("[NullArmy] Could not read the server version: " + Guard.describe(t));
        }
        this.adapter = loadAdapter(serverVersion);
        if (adapter == null) {
            getLogger().severe("No NullArmy version adapter supports server version " + serverVersion
                    + ". Supported: " + AdapterLoader.supportedVersions());
            getLogger().severe("NullArmy will NOT enable. Failing clearly rather than limping along.");
            Guard.attempt(getLogger(), "disabling an unsupported server",
                    () -> getServer().getPluginManager().disablePlugin(this));
            return;
        }

        // 3. Budgets and managers. Each one is created defensively; a manager
        //    that cannot be built leaves a clear log line, never a dead plugin.
        Caps caps = pluginConfig.caps();
        this.pathBudget = new TickBudget("paths", caps.concurrentPathSearches());
        this.blockInspectionBudget = new TickBudget("blockInspections", caps.blockInspectionsPerTick());

        this.kitService = new KitService(this, pluginConfig);
        this.portalManager = new PortalManager(this, pluginConfig);
        this.shutdownDirector = new ShutdownDirector(this, pluginConfig);
        this.missionRunner = new MissionRunner(this, pluginConfig);
        this.squads = new SquadManager(this, adapter, caps, pluginConfig);
        this.summonFlow = new SummonFlow(this, pluginConfig, squads);
        this.skinResolver = new SkinResolver(this);
        this.commander = new CommanderManager(this, skinResolver);
        Guard.attempt(getLogger(), "loading the Commander file", () -> commander.load());
        // A fresh install - or a deleted commander.yml - gets the shipped kit. An
        // owner-edited loadout is left exactly as it was saved.
        Guard.attempt(getLogger(), "installing the Commander's default kit",
                () -> kitService.installCommanderDefault(commander));
        this.zones = new redglitchx.nullarmy.plugin.zone.ZoneService(this);
        this.brain = new redglitchx.nullarmy.plugin.body.NullBrain(this, pluginConfig);
        this.lifecycle = new redglitchx.nullarmy.plugin.body.NullLifecycleListener(this);
        this.skinChain = new redglitchx.nullarmy.plugin.skin.SkinChain(this);
        this.builder = new redglitchx.nullarmy.plugin.ai.builder.BuilderService(this, pluginConfig);
        this.loadouts = new redglitchx.nullarmy.plugin.loadout.LoadoutService(this);
        this.v3Commands = new redglitchx.nullarmy.plugin.command.V3Commands(this);
        Guard.attempt(getLogger(), "installing the body rules",
                () -> adapter.applyBodySettings(pluginConfig.v3().bodySettings()));
        this.entityRegistry = new EntityRegistry(this);
        this.witherCannon = new WitherCannon(this, entityRegistry);
        this.airdrop = new Airdrop(this, entityRegistry);

        this.command = new NullCommand(this, squads, pluginConfig);
        this.menuManager = new MenuManager(this, command);

        // 3b. Chat: orders like "null attack Steve" and private conversations.
        //     The brain is built even with AI switched off, so status output is
        //     honest about *why* a model is unavailable.
        this.chatBrain = new ChatBrain(this);
        this.chatDirector = new ChatDirector(this, command, chatBrain);
        this.coordinator = new SquadCoordinator(this, pluginConfig);
        this.totemWatcher = new TotemWatcher(this, pluginConfig);
        this.selfTest = new SelfTest(this);

        // 4. Listeners. registerEvents throws if a listener is malformed, so
        //    each registration is isolated.
        registerListener(summonFlow, "summon flow");
        registerListener(commander, "Commander GUI");
        registerListener(menuManager, "menu GUI");
        registerListener(entityRegistry, "entity registry");
        // Real, temporary arrival portals: their blocks, their lifetime, and the
        // containment that stops anyone travelling to the Nether through one.
        registerListener(portalManager, "arrival portals");
        // The Totem Of Null: what ends the army when it pops or is destroyed.
        registerListener(totemWatcher, "Totem Of Null");
        // A player who joins after a squad exists needs the Nulls' player-info
        // entries, or the client drops their add-entity packets and sees nobody.
        registerListener(new ViewerListener(this), "viewer refresh");
        // After SummonFlow: a player answering "How many Nulls should come?"
        // must never have that answer read as conversation.
        registerListener(chatDirector, "chat interface");
        registerListener(lifecycle, "Null lifecycle (deaths, hits, silence)");
        registerListener(loadouts, "loadout editor");

        // 5. Commands. A missing command is a warning, not a crash: the rest of
        //    the plugin is still useful through the tick loop and the menu.
        PluginCommand nullCommand = getCommand("null");
        if (nullCommand == null) {
            getLogger().severe("[NullArmy] The 'null' command is missing from plugin.yml."
                    + " Commands and the menu will not be available.");
        } else {
            nullCommand.setExecutor(command);
            nullCommand.setTabCompleter(command);
        }

        // 5b. Tab listing. A Null is always announced with a player-info entry
        //     (a client will not render a player entity without one); this only
        //     decides whether that entry shows in the tab overlay.
        Guard.attempt(getLogger(), "applying the tab-list setting",
                () -> adapter.setTabListing(pluginConfig == null || pluginConfig.nullsInTabList()));

        // 6. Warm the skin cache in the background. Cosmetic, never fatal.
        Guard.attempt(getLogger(), "warming the skin cache", () -> commander.preloadSkin());
        Guard.attempt(getLogger(), "resolving the skin chain", () -> skinChain.refreshAsync(false));

        // 7. The tick loop. Its own try/catch sits one level above the
        //    per-subsystem guards in onTick().
        try {
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::onTickGuarded, 1L, 1L);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] Could not start the tick loop: "
                    + Guard.describe(t), t);
        }

        this.reloadables.add(kitService);
        this.reloadables.add(portalManager);
        this.reloadables.add(shutdownDirector);
        this.reloadables.add(missionRunner);
        this.reloadables.add(coordinator);
        this.reloadables.add(totemWatcher);
        this.reloadables.add(squads);
        this.reloadables.add(summonFlow);
        this.reloadables.add(commander);
        this.reloadables.add(entityRegistry);
        this.reloadables.add(witherCannon);
        this.reloadables.add(airdrop);
        this.reloadables.add(command);
        this.reloadables.add(menuManager);
        this.reloadables.add(chatDirector);
        this.reloadables.add(brain);
        this.reloadables.add(builder);
        this.reloadables.add(skinChain);

        this.fullyEnabled = true;
        getLogger().info("NullArmy enabled on " + serverVersion
                + " using adapter " + adapter.minecraftVersion());
        getLogger().info("[NullArmy] config: " + (configFile == null ? "unavailable" : configFile.getAbsolutePath()));
        getLogger().info("[NullArmy] caps: " + caps.maxLiveNpcs() + " live Nulls, summon cap "
                + caps.summonHardCap() + ", " + caps.portalEffectsPerSummon() + " portal effects");
        getLogger().info("[NullArmy] type /null help, or open the menu with /null menu");
        getLogger().info("[NullArmy] chat: say \"null help\" or \"null attack <player>\";"
                + " AI is " + (chatBrain != null && chatBrain.available() ? "ready" : "off"));
    }

    @Override
    public void onDisable() {
        try {
            if (tickTask != null) {
                Guard.attempt(getLogger(), "stopping the tick loop", () -> tickTask.cancel());
                tickTask = null;
            }
            if (builder != null) {
                Guard.attempt(getLogger(), "stopping builds", () -> builder.stopAll("the plugin is disabling"));
            }
            if (squads != null) {
                Guard.attempt(getLogger(), "requesting safe shutdown",
                        () -> squads.requestSafeShutdown());
            }
            if (commander != null) {
                Guard.attempt(getLogger(), "despawning the Commander", () -> commander.despawn());
            }
            if (squads != null) {
                Guard.attempt(getLogger(), "dismissing live Nulls", () -> squads.dismissAll());
            }
            if (shutdownDirector != null && shutdownDirector.isRunning()) {
                // Finish the sequence now rather than leaving half an army behind.
                Guard.attempt(getLogger(), "completing the totem shutdown",
                        () -> shutdownDirector.abort("the plugin is disabling"));
            }
            if (missionRunner != null) {
                Guard.attempt(getLogger(), "stopping the running mission",
                        () -> missionRunner.stop("the plugin is disabling"));
            }
            if (witherCannon != null) {
                Guard.attempt(getLogger(), "stopping pending cannon shots", () -> witherCannon.stopAll());
            }
            if (airdrop != null) {
                Guard.attempt(getLogger(), "stopping pending airdrops", () -> airdrop.stopAll());
            }
            if (portalManager != null) {
                // Every block the plugin placed goes back, whatever its lifetime
                // had left. A doorway that outlives its summon is world damage.
                int restored = 0;
                try {
                    restored = portalManager.restoreAll();
                } catch (Throwable ignored) {
                    // Reported by restoreAll itself.
                }
                getLogger().info("[NullArmy] restored " + restored + " arrival portal(s).");
            }
            if (entityRegistry != null) {
                int removed = 0;
                try {
                    removed = entityRegistry.sweepAll();
                } catch (Throwable ignored) {
                    // Reported by sweepAll itself.
                }
                getLogger().info("[NullArmy] removed " + removed + " tracked entity/entities.");
            }
            if (brain != null) {
                Guard.attempt(getLogger(), "clearing Null minds", () -> brain.clear());
            }
            getLogger().info("NullArmy disabled (no entity was left behind).");
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] onDisable problem: " + Guard.describe(t), t);
        }
    }

    // ------------------------------------------------------------------ tick loop

    /** Outermost tick guard: nothing may leave this method. */
    private void onTickGuarded() {
        try {
            onTick();
        } catch (Throwable t) {
            rememberTickError("tick loop: " + Guard.describe(t));
            int count = subsystemFailures.merge("tick loop", 1, Integer::sum);
            if (count <= 3 || count % 600 == 0) {
                getLogger().log(Level.SEVERE, "[NullArmy] tick loop failed (" + count
                        + " time(s)): " + Guard.describe(t), t);
            }
        }
    }

    /** Per-tick driver. Resets budgets, then lets each subsystem do bounded work. */
    private void onTick() {
        tickCounter++;
        pathBudget.reset();
        blockInspectionBudget.reset();

        guarded("squad tick", () -> {
            if (squads != null) {
                squads.tick(tickCounter);
            }
        });
        guarded("Null brain", () -> {
            if (brain != null) {
                brain.tick(tickCounter);
            }
        });
        guarded("builder", () -> {
            if (builder != null) {
                builder.tick(tickCounter);
            }
        });
        guarded("summon prompts", () -> {
            if (summonFlow != null) {
                summonFlow.tick(tickCounter);
            }
        });
        guarded("cannon tick", () -> {
            if (witherCannon != null) {
                witherCannon.tick(tickCounter);
            }
        });
        guarded("airdrop tick", () -> {
            if (airdrop != null) {
                airdrop.tick(tickCounter);
            }
        });
        guarded("portal lifetime", () -> {
            if (portalManager != null) {
                portalManager.tick(tickCounter);
            }
        });
        guarded("totem shutdown", () -> {
            if (shutdownDirector != null) {
                shutdownDirector.tick(tickCounter);
            }
        });
        guarded("mission", () -> {
            if (missionRunner != null) {
                missionRunner.tick(tickCounter);
            }
        });
        guarded("squad coordination", () -> {
            if (coordinator != null) {
                coordinator.tick(tickCounter);
            }
        });
        if (tickCounter % SWEEP_INTERVAL_TICKS == 0) {
            guarded("entity sweep", () -> {
                if (entityRegistry != null) {
                    entityRegistry.sweep();
                }
            });
        }
    }

    /**
     * Runs a subsystem for one tick.
     *
     * <p>Logs the first few failures, then every 600 ticks, and switches the
     * subsystem off after {@link #SUBSYSTEM_FAILURE_LIMIT} failures so a broken
     * subsystem cannot spam the log or waste the tick for the rest of the
     * session.</p>
     */
    private void guarded(String what, Runnable action) {
        if (disabledSubsystems.contains(what)) {
            return;
        }
        try {
            action.run();
        } catch (Throwable t) {
            int count = subsystemFailures.merge(what, 1, Integer::sum);
            rememberTickError(what + ": " + Guard.describe(t));
            if (count <= 3 || count % 600 == 0) {
                getLogger().log(Level.SEVERE, "[NullArmy] " + what + " failed (" + count
                        + " time(s)): " + Guard.describe(t), t);
            }
            if (count >= SUBSYSTEM_FAILURE_LIMIT) {
                disabledSubsystems.add(what);
                getLogger().severe("[NullArmy] " + what
                        + " kept failing and has been switched off for this session."
                        + " Restart the server after fixing the reason above.");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private PluginConfig buildConfig() {
        PluginConfig built = loadConfigFrom(new File(getDataFolder(), ConfigBootstrap.FILE_NAME));
        if (built != null) {
            return built;
        }
        // Nothing parsed and there is no previous configuration: the shipped
        // defaults keep the plugin working until the file is fixed.
        try {
            org.bukkit.configuration.file.YamlConfiguration defaults =
                    redglitchx.nullarmy.plugin.config.ConfigLoader.shipped(this);
            org.bukkit.configuration.file.YamlConfiguration empty = new org.bukkit.configuration.file.YamlConfiguration();
            empty.setDefaults(defaults);
            return new PluginConfig(empty, getLogger());
        } catch (Throwable fatal) {
            getLogger().log(Level.WARNING, "[NullArmy] even the default config failed", fatal);
            return null;
        }
    }

    /**
     * Parses a config file. On success it becomes the last good configuration;
     * on a syntax error the problem is reported (file, line, column, the lines
     * themselves) and null is returned - the caller keeps what it had.
     */
    private PluginConfig loadConfigFrom(File file) {
        redglitchx.nullarmy.plugin.config.ConfigLoader.Outcome outcome =
                redglitchx.nullarmy.plugin.config.ConfigLoader.load(this, file);
        if (!outcome.ok()) {
            lastConfigProblem = outcome.problem();
            getLogger().warning("[NullArmy] " + lastConfigProblem.headline());
            for (String line : lastConfigProblem.snippet()) {
                getLogger().warning("[NullArmy]   " + line);
            }
            getLogger().warning("[NullArmy] " + (lastGoodConfig == null
                    ? "no earlier configuration exists, so the shipped defaults are used until it is fixed."
                    : "the last good configuration stays in use until it is fixed."));
            if (chatGate != null) {
                chatGate.event("config.error", "file", lastConfigProblem.file(), "line", lastConfigProblem.line(),
                        "column", lastConfigProblem.column(), "problem", lastConfigProblem.problem());
            }
            return null;
        }
        try {
            PluginConfig parsed = new PluginConfig(outcome.config(), getLogger());
            lastGoodConfig = outcome.config();
            lastConfigProblem = null;
            return parsed;
        } catch (Throwable t) {
            lastConfigProblem = redglitchx.nullarmy.core.config.YamlProblem.locate(file.getName(),
                    "the values could not be read: " + Guard.describe(t), null);
            getLogger().warning("[NullArmy] " + lastConfigProblem.headline());
            return null;
        }
    }

    /** The last configuration that parsed - never Bukkit's empty fallback. */
    @Override
    public org.bukkit.configuration.file.FileConfiguration getConfig() {
        if (lastGoodConfig != null) {
            return lastGoodConfig;
        }
        return super.getConfig();
    }

    /**
     * Bukkit's reload would print a stack trace and return an empty
     * configuration for a broken file. This one keeps the last good one.
     */
    @Override
    public void reloadConfig() {
        loadConfigFrom(new File(getDataFolder(), ConfigBootstrap.FILE_NAME));
    }

    /** Why the last config load was not applied, or null. */
    public redglitchx.nullarmy.core.config.YamlProblem lastConfigProblem() { return lastConfigProblem; }

    /**
     * The self test's malformed-config check: runs a file other than
     * config.yml through exactly the load path and reports the problem,
     * leaving the live configuration alone.
     */
    public redglitchx.nullarmy.core.config.YamlProblem tryConfigFile(File file) {
        org.bukkit.configuration.file.YamlConfiguration goodBefore = lastGoodConfig;
        redglitchx.nullarmy.core.config.YamlProblem problemBefore = lastConfigProblem;
        PluginConfig parsed = loadConfigFrom(file);
        redglitchx.nullarmy.core.config.YamlProblem problem = parsed == null ? lastConfigProblem : null;
        lastGoodConfig = goodBefore;
        lastConfigProblem = problemBefore;
        return problem;
    }

    private VersionAdapter loadAdapter(String serverVersion) {
        try {
            return AdapterLoader.load(serverVersion);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] version adapter failed to load: "
                    + Guard.describe(t), t);
            return null;
        }
    }

    private void registerListener(Listener listener, String what) {
        if (listener == null) {
            getLogger().warning("[NullArmy] " + what + " listener is unavailable - skipping.");
            return;
        }
        Guard.attempt(getLogger(), "registering the " + what + " listener",
                () -> getServer().getPluginManager().registerEvents(listener, this));
    }

    /**
     * Re-reads {@code config.yml} without a restart.
     *
     * @return true when the new configuration was applied (the file is always
     *     recreated if missing, never overwritten)
     */
    public boolean reloadPluginConfig() {
        try {
            ConfigBootstrap.prepare(this);
            PluginConfig fresh = loadConfigFrom(new File(getDataFolder(), ConfigBootstrap.FILE_NAME));
            if (fresh == null) {
                // Reported already; the last good configuration stays in use.
                return false;
            }
            this.pluginConfig = fresh;
            Caps caps = fresh.caps();
            this.pathBudget = new TickBudget("paths", caps.concurrentPathSearches());
            this.blockInspectionBudget = new TickBudget("blockInspections", caps.blockInspectionsPerTick());
            for (Reloadable reloadable : reloadables) {
                Guard.attempt(getLogger(), "reloading " + reloadable.getClass().getSimpleName(),
                        () -> reloadable.onConfigReloaded(fresh));
            }
            if (adapter != null) {
                Guard.attempt(getLogger(), "applying the tab-list setting",
                        () -> adapter.setTabListing(fresh.nullsInTabList()));
                Guard.attempt(getLogger(), "installing the body rules",
                        () -> adapter.applyBodySettings(fresh.v3().bodySettings()));
            }
            spawnBreaker.reset();
            return true;
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[NullArmy] /null reload failed: " + Guard.describe(t), t);
            return false;
        }
    }

    // ------------------------------------------------------------------ accessors

    public PluginConfig pluginConfig() { return pluginConfig; }
    public redglitchx.nullarmy.plugin.chat.ChatGate chatGate() { return chatGate; }
    public redglitchx.nullarmy.plugin.body.NullBrain brain() { return brain; }
    public redglitchx.nullarmy.plugin.body.NullLifecycleListener lifecycle() { return lifecycle; }
    public redglitchx.nullarmy.plugin.skin.SkinChain skinChain() { return skinChain; }
    public redglitchx.nullarmy.plugin.ai.builder.BuilderService builder() { return builder; }
    public redglitchx.nullarmy.plugin.loadout.LoadoutService loadouts() { return loadouts; }
    public redglitchx.nullarmy.plugin.zone.ZoneService zones() { return zones; }
    public redglitchx.nullarmy.plugin.command.V3Commands v3Commands() { return v3Commands; }
    public VersionAdapter adapter() { return adapter; }
    public SquadManager squads() { return squads; }
    public SummonFlow summonFlow() { return summonFlow; }
    public SkinResolver skins() { return skinResolver; }
    public CommanderManager commander() { return commander; }
    public EntityRegistry registry() { return entityRegistry; }
    public WitherCannon witherCannon() { return witherCannon; }
    public Airdrop airdrop() { return airdrop; }
    public NullCommand command() { return command; }
    public MenuManager menu() { return menuManager; }

    /** The chat interface: orders by voice, private channels, AI replies. */
    public ChatDirector chat() { return chatDirector; }

    /** Real, temporary arrival portals: build, lifetime and containment. */
    public PortalManager portals() { return portalManager; }

    /** Default equipment for the Commander and every Null. */
    public KitService kits() { return kitService; }

    /** Watches for the Totem Of Null being consumed or destroyed. */
    public TotemWatcher totems() { return totemWatcher; }

    /** The sequential shutdown a destroyed totem starts. */
    public ShutdownDirector shutdown() { return shutdownDirector; }

    /** The Commander's view of the squad, and the AI action gate. */
    public SquadCoordinator coordinator() { return coordinator; }

    /** The original mission system. */
    public MissionRunner missions() { return missionRunner; }

    /** The Paper runtime smoke test, run from the console or {@code /null selftest}. */
    public SelfTest selfTest() { return selfTest; }

    /**
     * What the last config migration did, for {@code /null reload} and
     * {@code /null debug}.
     *
     * <p>This is the answer to "the reload does not update config.yml": it does
     * now, and this says exactly which keys were appended.</p>
     */
    public ConfigMigration.Report lastMigration() { return ConfigBootstrap.lastReport(); }
    public TickBudget pathBudget() { return pathBudget; }
    public TickBudget blockInspectionBudget() { return blockInspectionBudget; }
    public long currentTick() { return tickCounter; }

    /** True when the plugin finished enabling (adapter present, managers wired). */
    public boolean fullyEnabled() { return fullyEnabled; }

    /** The latched guard around NMS spawning, for {@code /null status} and {@code /null debug}. */
    public Guard.Breaker spawnBreaker() { return spawnBreaker; }

    /** Keeps the last few tick failures for the smoke test, bounded. */
    private void rememberTickError(String line) {
        synchronized (tickErrors) {
            tickErrors.addLast(line);
            while (tickErrors.size() > 16) {
                tickErrors.removeFirst();
            }
        }
    }

    /**
     * Drains the tick failures recorded since the last call.
     *
     * <p>Used by {@code /null selftest} so a server exception during the test is
     * reported as a failed check rather than being logged and forgotten.</p>
     */
    public List<String> selfTestErrors() {
        synchronized (tickErrors) {
            List<String> out = new ArrayList<>(tickErrors);
            tickErrors.clear();
            return out;
        }
    }

    /** Snapshot of subsystem failure counters, for {@code /null debug}. */
    public Map<String, Integer> subsystemFailures() {
        return new HashMap<>(subsystemFailures);
    }

    /** Subsystems switched off after repeated failures, for {@code /null debug}. */
    public Set<String> disabledSubsystems() {
        return new HashSet<>(disabledSubsystems);
    }

    /** The config file on disk, for {@code /null status}. */
    public File configFile() {
        File folder = getDataFolder();
        return folder == null ? null : new File(folder, ConfigBootstrap.FILE_NAME);
    }

    /**
     * Keeps clients able to see Nulls that already existed when they joined.
     *
     * <p>A client drops the add-entity packet for a player UUID it has no
     * player-info entry for, and the entries are broadcast when a Null spawns -
     * so anybody who joins afterwards would see an empty world. This sends them
     * the entries for every live Null once they are in.</p>
     */
    static final class ViewerListener implements org.bukkit.event.Listener {

        private final NullArmyPlugin plugin;

        ViewerListener(NullArmyPlugin plugin) {
            this.plugin = plugin;
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
            Guard.attempt(plugin.getLogger(), "announcing Nulls to a joining player", () -> {
                if (event == null || event.getPlayer() == null) {
                    return;
                }
                final java.util.UUID id = event.getPlayer().getUniqueId();
                // One tick later: the client is still receiving its own join
                // packets, and player-info sent now can be dropped by the client's
                // login sequence.
                org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, () ->
                        Guard.attempt(plugin.getLogger(), "refreshing a viewer", () -> {
                            if (plugin.adapter() == null) {
                                return;
                            }
                            int announced = plugin.adapter().refreshViewer(id);
                            if (announced > 0) {
                                plugin.getLogger().fine("[NullArmy] announced " + announced
                                        + " Null(s) to a joining player.");
                            }
                        }), 5L);
            });
        }
    }
}
