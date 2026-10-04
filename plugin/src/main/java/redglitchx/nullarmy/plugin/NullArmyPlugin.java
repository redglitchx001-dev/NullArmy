package redglitchx.nullarmy.plugin;

import org.bukkit.command.PluginCommand;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.util.TickBudget;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.commander.CommanderManager;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.ConfigBootstrap;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.menu.MenuManager;
import redglitchx.nullarmy.plugin.skin.SkinResolver;
import redglitchx.nullarmy.plugin.spectacle.Airdrop;
import redglitchx.nullarmy.plugin.spectacle.EntityRegistry;
import redglitchx.nullarmy.plugin.spectacle.WitherCannon;
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
 * listener or a tick. A single connectionless {@code ServerPlayer} that NPEs
 * inside the server's entity tick does not throw a nice chat message - it takes
 * the whole server down and writes a crash report. So:</p>
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

    private TickBudget pathBudget;
    private TickBudget blockInspectionBudget;

    private final List<Reloadable> reloadables = new ArrayList<>();

    /** Latched guard around the NMS spawn path. See {@link Guard.Breaker}. */
    private final Guard.Breaker spawnBreaker = new Guard.Breaker("NMS Null spawning");

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

        this.squads = new SquadManager(this, adapter, caps, pluginConfig);
        this.summonFlow = new SummonFlow(this, pluginConfig, squads);
        this.skinResolver = new SkinResolver(this);
        this.commander = new CommanderManager(this, skinResolver);
        Guard.attempt(getLogger(), "loading the Commander file", () -> commander.load());
        this.entityRegistry = new EntityRegistry(this);
        this.witherCannon = new WitherCannon(this, entityRegistry);
        this.airdrop = new Airdrop(this, entityRegistry);

        this.command = new NullCommand(this, squads, pluginConfig);
        this.menuManager = new MenuManager(this, command);

        // 4. Listeners. registerEvents throws if a listener is malformed, so
        //    each registration is isolated.
        registerListener(summonFlow, "summon flow");
        registerListener(commander, "Commander GUI");
        registerListener(menuManager, "menu GUI");
        registerListener(entityRegistry, "entity registry");

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

        // 6. Warm the skin cache in the background. Cosmetic, never fatal.
        Guard.attempt(getLogger(), "warming the skin cache", () -> commander.preloadSkin());

        // 7. The tick loop. Its own try/catch sits one level above the
        //    per-subsystem guards in onTick().
        try {
            this.tickTask = getServer().getScheduler().runTaskTimer(this, this::onTickGuarded, 1L, 1L);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] Could not start the tick loop: "
                    + Guard.describe(t), t);
        }

        this.reloadables.add(squads);
        this.reloadables.add(summonFlow);
        this.reloadables.add(commander);
        this.reloadables.add(entityRegistry);
        this.reloadables.add(witherCannon);
        this.reloadables.add(airdrop);
        this.reloadables.add(command);
        this.reloadables.add(menuManager);

        this.fullyEnabled = true;
        getLogger().info("NullArmy enabled on " + serverVersion
                + " using adapter " + adapter.minecraftVersion());
        getLogger().info("[NullArmy] config: " + (configFile == null ? "unavailable" : configFile.getAbsolutePath()));
        getLogger().info("[NullArmy] caps: " + caps.maxLiveNpcs() + " live Nulls, summon cap "
                + caps.summonHardCap() + ", " + caps.portalEffectsPerSummon() + " portal effects");
        getLogger().info("[NullArmy] type /null help, or open the menu with /null menu");
    }

    @Override
    public void onDisable() {
        try {
            if (tickTask != null) {
                Guard.attempt(getLogger(), "stopping the tick loop", () -> tickTask.cancel());
                tickTask = null;
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
            if (witherCannon != null) {
                Guard.attempt(getLogger(), "stopping pending cannon shots", () -> witherCannon.stopAll());
            }
            if (airdrop != null) {
                Guard.attempt(getLogger(), "stopping pending airdrops", () -> airdrop.stopAll());
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
        try {
            return new PluginConfig(getConfig(), getLogger());
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] config.yml could not be parsed ("
                    + Guard.describe(t) + "). Continuing with built-in defaults.");
            try {
                return new PluginConfig(new org.bukkit.configuration.file.YamlConfiguration(), getLogger());
            } catch (Throwable fatal) {
                // Should be impossible: an empty YamlConfiguration always parses.
                getLogger().log(Level.SEVERE, "[NullArmy] even the default config failed", fatal);
                return null;
            }
        }
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
            PluginConfig fresh = buildConfig();
            if (fresh == null) {
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
            // An explicit reload is also the way to re-arm the NMS breaker after
            // a fix; the plugin says so in chat when it fires.
            spawnBreaker.reset();
            return true;
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[NullArmy] /null reload failed: " + Guard.describe(t), t);
            return false;
        }
    }

    // ------------------------------------------------------------------ accessors

    public PluginConfig pluginConfig() { return pluginConfig; }
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
    public TickBudget pathBudget() { return pathBudget; }
    public TickBudget blockInspectionBudget() { return blockInspectionBudget; }
    public long currentTick() { return tickCounter; }

    /** True when the plugin finished enabling (adapter present, managers wired). */
    public boolean fullyEnabled() { return fullyEnabled; }

    /** The latched guard around NMS spawning, for {@code /null status} and {@code /null debug}. */
    public Guard.Breaker spawnBreaker() { return spawnBreaker; }

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
}
