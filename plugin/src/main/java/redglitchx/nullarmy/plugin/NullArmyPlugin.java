package redglitchx.nullarmy.plugin;

import org.bukkit.plugin.java.JavaPlugin;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.util.TickBudget;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.PluginConfig;

import java.util.Objects;

/**
 * NullArmy bootstrap.
 *
 * <p>Chooses a {@link VersionAdapter} for the running server, wires commands,
 * and owns the per-tick budgets that keep costly work bounded (spec 9).</p>
 *
 * <p><b>STATUS: UNVERIFIED.</b> Never compiled or run (blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullArmyPlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private VersionAdapter adapter;
    private SquadManager squads;
    private SummonFlow summonFlow;

    private TickBudget pathBudget;
    private TickBudget blockInspectionBudget;

    private long tickCounter;
    private boolean shutdownClean;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(getConfig());

        String serverVersion = getServer().getMinecraftVersion();
        this.adapter = AdapterLoader.load(serverVersion);
        if (adapter == null) {
            getLogger().severe("No NullArmy version adapter supports server version " + serverVersion
                    + ". Supported: " + AdapterLoader.supportedVersions());
            getLogger().severe("NullArmy will NOT enable. Failing clearly rather than limping along.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Caps caps = pluginConfig.caps();
        this.pathBudget = new TickBudget("paths", caps.concurrentPathSearches());
        this.blockInspectionBudget = new TickBudget("blockInspections", caps.blockInspectionsPerTick());

        this.squads = new SquadManager(this, adapter, caps, pluginConfig);
        this.summonFlow = new SummonFlow(this, pluginConfig, squads);

        NullCommand command = new NullCommand(this, squads, pluginConfig);
        Objects.requireNonNull(getCommand("null"), "command 'null' missing from paper-plugin.yml")
                .setExecutor(command);
        Objects.requireNonNull(getCommand("null")).setTabCompleter(command);

        getServer().getPluginManager().registerEvents(summonFlow, this);
        getServer().getScheduler().runTaskTimer(this, this::onTick, 1L, 1L);

        getLogger().info("NullArmy enabled on " + serverVersion
                + " using adapter " + adapter.minecraftVersion());
    }

    @Override
    public void onDisable() {
        this.shutdownClean = true;
        if (squads != null) {
            // Spec 5: SAFE_SHUTDOWN is a real state, not an instant delete.
            squads.requestSafeShutdown();
        }
        getLogger().info(shutdownClean
                ? "NullArmy disabled (safe shutdown requested)"
                : "NullArmy disabled");
    }

    /** Per-tick driver. Resets budgets, then lets squads do bounded work. */
    private void onTick() {
        tickCounter++;
        pathBudget.reset();
        blockInspectionBudget.reset();

        if (squads != null) {
            squads.tick(tickCounter);
        }
        if (summonFlow != null) {
            summonFlow.tick(tickCounter);
        }
    }

    public PluginConfig pluginConfig() { return pluginConfig; }
    public VersionAdapter adapter() { return adapter; }
    public SquadManager squads() { return squads; }
    public SummonFlow summonFlow() { return summonFlow; }
    public TickBudget pathBudget() { return pathBudget; }
    public TickBudget blockInspectionBudget() { return blockInspectionBudget; }
    public long currentTick() { return tickCounter; }
}
