package redglitchx.nullarmy.plugin.ai;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.ai.ActionPolicy;
import redglitchx.nullarmy.core.ai.SquadAction;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.squad.RoleAssignment;
import redglitchx.nullarmy.core.squad.SquadRole;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.chat.ChatBrain;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Commander's view of its own army, and the only way a model may act on it.
 *
 * <h2>Two halves</h2>
 * <ol>
 *   <li><b>A shared, current picture.</b> {@link #snapshot(UUID)} renders what the
 *       squad is doing right now - every Null's health, position and stored role,
 *       the objective, formation and tactics, kit status, how the last arrival
 *       came in, whether the cannon and the air drop are usable, and the mission.
 *       That is what the Commander reasons over, and what {@code /null ai} shows
 *       when it is asked what the AI can see.</li>
 *   <li><b>Typed actions only.</b> A model answers with one allowlisted
 *       {@link SquadAction}. It is parsed, checked against {@link ActionPolicy}
 *       with values read from the server, and then executed through the <b>same</b>
 *       {@code /null} executor a player's typed order uses - so permissions, caps,
 *       thread rules and policy gates are identical. A model cannot run a console
 *       command, cannot widen a cap, cannot enable griefing, cannot ban anybody,
 *       and cannot fire the cannon or dismiss the squad without the owner saying
 *       yes first.</li>
 * </ol>
 *
 * <h2>No model, still useful</h2>
 * With no endpoint configured, an explicit {@code /null coordinate} order may
 * run {@link #deterministicStep(UUID)} through local rules. Periodic ticks never
 * perform group actions: healing, role changes and objectives only happen after
 * a player order. Everything destructive remains outside the local path.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SquadCoordinator implements Reloadable {

    private static final String PREFIX = PluginText.PREFIX;

    /** How long a snapshot may be before it is clipped, in characters. */
    private static final int SNAPSHOT_LIMIT = 2400;
    /** A destructive model proposal cannot remain executable indefinitely. */
    private static final long CONFIRMATION_TTL_TICKS = 20L * 30L;

    private static final class PendingConfirmation {
        private final SquadAction action;
        private final long expiresAtTick;

        PendingConfirmation(SquadAction action, long expiresAtTick) {
            this.action = action;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private final NullArmyPlugin plugin;
    private final Map<UUID, PendingConfirmation> awaitingConfirmation = new LinkedHashMap<>();
    private final List<String> recentDecisions = new ArrayList<>();

    private PluginConfig config;

    public SquadCoordinator(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
        awaitingConfirmation.clear();
    }

    // ------------------------------------------------------------------- snapshot

    /**
     * The Commander's current picture of one owner's army.
     *
     * @return a compact, honest text block; never null
     */
    public String snapshot(UUID owner) {
        StringBuilder sb = new StringBuilder();
        try {
            SquadManager squads = plugin.squads();
            List<NullBody> members = squads == null ? new ArrayList<>() : squads.membersOf(owner);
            sb.append("SQUAD: ").append(members.size()).append(" live Null(s), ")
                    .append(squads == null ? "n/a" : squads.liveCount()).append(" server-wide, cap ")
                    .append(config == null ? "?" : String.valueOf(config.caps().maxLiveNpcs()))
                    .append('\n');
            if (squads != null) {
                SquadManager.Squad squad = squads.find(owner);
                if (squad != null) {
                    sb.append("OBJECTIVE: ").append(squad.objective().name().toLowerCase())
                            .append(" formation=").append(squad.formation())
                            .append(" tactics=").append(squad.tactics())
                            .append('\n');
                    sb.append("ROLES: ").append(RoleAssignment.describe(squad.roles())).append('\n');
                    if (!squad.arrivalNote().isEmpty()) {
                        sb.append("ARRIVAL: ").append(squad.arrivalNote()).append('\n');
                    }
                    if (!squad.spawnFailures().isEmpty()) {
                        sb.append("FAILURES: ").append(squad.spawnFailures().size())
                                .append(" - ").append(squad.spawnFailures().get(0)).append('\n');
                    }
                }
            }
            int shown = 0;
            for (NullBody body : members) {
                if (shown >= 12) {
                    sb.append("  ... and ").append(members.size() - shown).append(" more\n");
                    break;
                }
                sb.append("  ").append(describe(body, squads, owner, shown)).append('\n');
                shown++;
            }
            sb.append("KIT: ").append(plugin.kits() == null ? "unavailable" : plugin.kits().describe())
                    .append('\n');
            sb.append("PORTALS: ").append(plugin.portals() == null ? "unavailable"
                    : plugin.portals().describe()).append('\n');
            sb.append("CANNON: ").append(cannonLine()).append('\n');
            sb.append("AIRDROP: ").append(plugin.pluginConfig() == null ? "unknown"
                    : (plugin.pluginConfig().airdropEnabled() ? "enabled"
                            : "off (airdrop.enabled is false)")).append('\n');
            sb.append("MISSION: ").append(plugin.missions() == null ? "unavailable"
                    : plugin.missions().oneLine()).append('\n');
            sb.append("SHUTDOWN: ").append(plugin.shutdown() == null ? "unavailable"
                    : plugin.shutdown().describe());
        } catch (Throwable t) {
            sb.append("\nsnapshot incomplete: ").append(Guard.describe(t));
        }
        String text = sb.toString();
        return text.length() > SNAPSHOT_LIMIT ? text.substring(0, SNAPSHOT_LIMIT) + "..." : text;
    }

    private String describe(NullBody body, SquadManager squads, UUID owner, int index) {
        try {
            Vec3d at = body.bodyPosition();
            SquadManager.Squad squad = squads == null ? null : squads.find(owner);
            SquadRole role = squad == null ? null : squad.roleOf(index);
            String kit = plugin.kits() == null ? "?" : kitState(body);
            return (role == null ? "null" : role.key()) + " " + body.profileName()
                    + " hp=" + Math.round(body.health())
                    + " pos=" + (int) Math.floor(at.x()) + "," + (int) Math.floor(at.y())
                    + "," + (int) Math.floor(at.z())
                    + " tracked=" + plugin.adapter().isTracked(body)
                    + " kit=" + kit;
        } catch (Throwable t) {
            return "unreadable Null (" + Guard.describe(t) + ")";
        }
    }

    private String kitState(NullBody body) {
        if (plugin.kits() == null) {
            return "unknown";
        }
        return plugin.kits().verify(body) == null ? "ok" : "incomplete";
    }

    private String cannonLine() {
        PluginConfig current = config;
        if (current == null) {
            return "unknown";
        }
        if (!current.witherCannonEnabled()) {
            return "off (wither-cannon.enabled is false)";
        }
        if (!current.explosivesEnabled()) {
            return "blocked (policy.explosives-enabled is false)";
        }
        if (!current.witherEnabled()) {
            return "blocked (policy.wither-enabled is false)";
        }
        return "ready (block damage " + (current.witherCannonBlocksDamage() ? "ON" : "off") + ")";
    }

    // -------------------------------------------------------------------- policy

    /** The live policy view the allowlist is checked against. */
    private ActionPolicy.View view(final UUID owner) {
        final Player player = Bukkit.getPlayer(owner);
        final PluginConfig current = config;
        return new ActionPolicy.View() {
            @Override public boolean aiUsable() {
                return current != null && current.aiSquadCoordination() && brainUsable();
            }
            @Override public boolean hasPermission(String permission) {
                return player != null && player.hasPermission(permission);
            }
            @Override public int liveNulls() {
                return plugin.squads() == null ? 0 : plugin.squads().liveCount();
            }
            @Override public int maxLiveNulls() {
                return current == null ? 0 : current.caps().maxLiveNpcs();
            }
            @Override public boolean hasSquad() {
                return plugin.squads() != null && !plugin.squads().membersOf(owner).isEmpty();
            }
            @Override public boolean portalTravelEnabled() {
                return current != null && current.portalTravelEnabled();
            }
            @Override public boolean airdropUsable() {
                return current != null && current.airdropEnabled();
            }
            @Override public boolean cannonUsable() {
                return current != null && current.witherCannonUsable();
            }
            @Override public boolean missionsEnabled() {
                return current != null && current.missionsEnabled();
            }
            @Override public boolean missionRunning() {
                return plugin.missions() != null && plugin.missions().isRunning();
            }
            @Override public boolean shutdownRunning() {
                return plugin.shutdown() != null && plugin.shutdown().isRunning();
            }
            @Override public boolean pluginStopping() {
                return !plugin.fullyEnabled() || plugin.squads() == null
                        || plugin.squads().isShutdownRequested();
            }
        };
    }

    private boolean brainUsable() {
        ChatBrain brain = plugin.chat() == null ? null : plugin.chat().brain();
        return brain != null && brain.available();
    }

    // ------------------------------------------------------------------ execution

    /**
     * Runs one action for one owner.
     *
     * @return what happened, ready to print
     */
    public String execute(UUID owner, SquadAction action) {
        if (owner == null || action == null) {
            return "nothing to do";
        }
        ActionPolicy.Decision decision = ActionPolicy.check(action, view(owner));
        if (!decision.allowed()) {
            remember("refused " + action.kind().key() + ": " + decision.reason());
            return "refused: " + decision.reason();
        }
        if (decision.needsConfirmation()) {
            long expiresAt = plugin.currentTick() + CONFIRMATION_TTL_TICKS;
            awaitingConfirmation.put(owner, new PendingConfirmation(action, expiresAt));
            remember("held for confirmation: " + action.kind().key());
            return action.kind().key() + " needs your confirmation within 30 seconds: run /null confirm"
                    + " to execute it, or /null confirm no to drop it.";
        }
        return run(owner, action);
    }

    /** Turns a validated action into the equivalent typed order. */
    private String run(UUID owner, SquadAction action) {
        Player player = Bukkit.getPlayer(owner);
        if (player == null || !player.isOnline()) {
            return "the owner is offline, so nothing ran";
        }
        String[] args = commandArgs(action);
        if (args == null) {
            return "that action has no command form";
        }
        final String[] copy = args;
        boolean ran = Guard.attempt(plugin.getLogger(), "AI action " + action.kind().key(),
                () -> plugin.command().dispatch(player, copy));
        remember((ran ? "ran " : "failed ") + action.kind().key()
                + (action.argument().isEmpty() ? "" : " " + action.argument()));
        return (ran ? "" : "could not run ") + "/null " + String.join(" ", args);
    }

    /** The {@code /null} form of an action. Null when there is none. */
    private String[] commandArgs(SquadAction action) {
        String argument = action.argument();
        switch (action.kind()) {
            case REPORT:
                return new String[] {"status"};
            case FOLLOW:
                return new String[] {"follow"};
            case COME:
                return new String[] {"come"};
            case GUARD:
                return new String[] {"guard"};
            case FORMATION:
                return new String[] {"formation", argument};
            case TACTICS:
                return new String[] {"tactics", argument};
            case HEAL:
                return new String[] {"heal"};
            case ROLES:
                return new String[] {"roles"};
            case PORTAL:
                return argument.isEmpty() ? new String[] {"portal"}
                        : new String[] {"portal", argument};
            case MISSION_START:
                return new String[] {"mission", "start", argument};
            case MISSION_STOP:
                return new String[] {"mission", "stop"};
            case AIRDROP:
                return new String[] {"airdrop"};
            case CANNON:
                return new String[] {"withercannon"};
            case DISMISS:
                return new String[] {"dismiss"};
            case REFUSE:
            default:
                return null;
        }
    }

    /** Executes a held action after the owner said yes. */
    public String confirm(UUID owner, boolean yes) {
        PendingConfirmation pending = awaitingConfirmation.remove(owner);
        if (pending == null) {
            return "nothing was waiting for your confirmation.";
        }
        if (plugin.currentTick() >= pending.expiresAtTick) {
            remember("confirmation expired: " + pending.action.kind().key());
            return "that confirmation expired. Nothing ran; ask the Commander again if you still want it.";
        }
        SquadAction action = pending.action;
        if (!yes) {
            remember("owner dropped " + action.kind().key());
            return "dropped " + action.kind().key() + ". Nothing ran.";
        }
        // A confirmation is not a permission or policy snapshot. The owner may
        // have lost a permission, a config gate may have changed, or shutdown
        // may have started while the action was waiting, so run every live gate
        // again immediately before dispatching the typed command.
        ActionPolicy.Decision decision = ActionPolicy.check(action, view(owner));
        if (!decision.allowed()) {
            remember("confirmation refused " + action.kind().key() + ": " + decision.reason());
            return "refused: " + decision.reason() + ". Nothing ran.";
        }
        return run(owner, action);
    }

    /** True while an unexpired action is waiting for this owner's yes. */
    public boolean hasPendingConfirmation(UUID owner) {
        if (owner == null) {
            return false;
        }
        PendingConfirmation pending = awaitingConfirmation.get(owner);
        if (pending == null) {
            return false;
        }
        if (plugin.currentTick() >= pending.expiresAtTick) {
            awaitingConfirmation.remove(owner, pending);
            return false;
        }
        return true;
    }

    /** Drops any outstanding proposal when its owner leaves the server. */
    public void clearPendingConfirmation(UUID owner) {
        if (owner != null) {
            awaitingConfirmation.remove(owner);
        }
    }

    // ------------------------------------------------------- deterministic local

    /**
     * One coordination step with no model involved.
     *
     * <p>Only safe, reversible actions are chosen here, and only when the squad
     * actually needs them: heal a hurt Null, give an idle squad something to hold,
     * store roles that were never assigned. Nothing destructive is reachable from
     * this path at all.</p>
     *
     * @return what it decided, ready to print
     */
    public String deterministicStep(UUID owner) {
        if (owner == null || plugin.squads() == null) {
            return "there is no squad to coordinate";
        }
        List<NullBody> members = plugin.squads().membersOf(owner);
        if (members.isEmpty()) {
            return "there is no squad to coordinate";
        }
        int hurt = 0;
        for (NullBody body : members) {
            try {
                if (body.health() < 20.0) {
                    hurt++;
                }
            } catch (Throwable ignored) {
                // An unreadable body does not decide the step.
            }
        }
        if (hurt > 0) {
            return executeLocal(owner, SquadAction.of(SquadAction.Kind.HEAL, "",
                    hurt + " Null(s) are hurt"));
        }
        SquadManager.Squad squad = plugin.squads().find(owner);
        if (squad != null && squad.roles().isEmpty()) {
            return executeLocal(owner, SquadAction.of(SquadAction.Kind.ROLES, "",
                    "no roles are stored for this squad"));
        }
        if (squad != null && squad.objective() == SquadManager.Objective.NONE) {
            return executeLocal(owner, SquadAction.of(SquadAction.Kind.GUARD, "",
                    "the squad is idle, so it holds position"));
        }
        return executeLocal(owner, SquadAction.of(SquadAction.Kind.REPORT, "", "nothing needs changing"));
    }

    /** Executes the narrow local allowlist; only explicit owner-order callers may reach it. */
    private String executeLocal(UUID owner, SquadAction action) {
        if (owner == null || action == null) {
            return "nothing to do";
        }
        ActionPolicy.Decision decision = ActionPolicy.checkLocal(action, view(owner));
        if (!decision.allowed()) {
            remember("refused local " + action.kind().key() + ": " + decision.reason());
            return "refused: " + decision.reason();
        }
        return run(owner, action);
    }

    // ------------------------------------------------------------------- the model

    /**
     * Asks the model what the squad should do next, then validates the answer.
     *
     * <p>Runs off the server thread (the brain is async), and the action it
     * produces is executed back on the main thread through the same validated
     * command path a typed order uses. The tick is never blocked on a model.</p>
     */
    public void askModel(UUID owner, String hint, java.util.function.Consumer<String> onDone) {
        ChatBrain brain = plugin.chat() == null ? null : plugin.chat().brain();
        if (brain == null || !brain.available()) {
            String reason = brain == null ? "the chat brain is not wired" : brain.unavailableReason();
            String fallback = deterministicStep(owner);
            if (onDone != null) {
                onDone.accept("No model is configured (" + reason + "), so the local"
                        + " coordinator ran instead: " + fallback);
            }
            return;
        }
        String prompt = "You are the NullArmy Commander. Here is your squad right now:\n"
                + snapshot(owner)
                + "\nAllowed actions, reply with exactly one, nothing else:\n"
                + String.join(", ", SquadAction.allowlist())
                + "\nReply in the form: action argument\nOwner's note: "
                + (hint == null || hint.isEmpty() ? "(none)" : hint);
        brain.ask(prompt, null, hint == null || hint.isEmpty() ? "coordinate the squad" : hint,
                new ChatBrain.Reply() {
                    @Override
                    public void ok(String text) {
                        SquadAction action = SquadAction.parse(text);
                        executeOnMainThread(owner, action, result -> {
                            if (onDone != null) {
                                onDone.accept(result);
                            }
                        });
                    }

                    @Override
                    public void failed(String reason) {
                        executeOnMainThread(owner, SquadAction.refuse("the model could not answer"), ignored -> {
                            remember("model failed");
                            if (onDone != null) {
                                onDone.accept("The model did not answer (" + reason + "). No action ran.");
                            }
                        });
                    }
                });
    }

    /** Executes on the server thread and delivers the result only after it exists. */
    private void executeOnMainThread(UUID owner, SquadAction action,
                                    java.util.function.Consumer<String> onDone) {
        Runnable task = () -> {
            final String result;
            try {
                result = execute(owner, action);
            } catch (Throwable failure) {
                plugin.getLogger().warning("[NullArmy] coordination action failed ("
                        + Guard.describe(failure) + ")");
                if (onDone != null) {
                    onDone.accept("coordination action failed; nothing further was run");
                }
                return;
            }
            if (onDone != null) {
                onDone.accept(result);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable schedulingFailure) {
            // The callback often sends a Bukkit chat message: never run it on
            // this asynchronous model thread as a fallback.
            plugin.getLogger().warning("[NullArmy] could not schedule a coordination action on the server thread ("
                    + schedulingFailure.getClass().getSimpleName() + "); nothing ran.");
        }
    }

    // ------------------------------------------------------------------ automatic

    /**
     * Compatibility tick hook. Group actions are intentionally never automated:
     * healing, role changes and objectives only happen after an explicit owner
     * order, even if an older config still has {@code ai.auto-coordinate: true}.
     */
    public void tick(long tickCounter) {
        // Confirmation cleanup is bounded and runs once a second; stale proposals
        // cannot stay resident just because their owner never types /null confirm.
        if (tickCounter % 20L == 0L) {
            awaitingConfirmation.entrySet().removeIf(entry -> {
                if (tickCounter >= entry.getValue().expiresAtTick) {
                    remember("confirmation expired: " + entry.getValue().action.kind().key());
                    return true;
                }
                return false;
            });
        }
    }

    /** The last few decisions, for {@code /null ai}. */
    public List<String> recentDecisions() {
        return new ArrayList<>(recentDecisions);
    }

    private void remember(String line) {
        recentDecisions.add(0, line);
        while (recentDecisions.size() > 8) {
            recentDecisions.remove(recentDecisions.size() - 1);
        }
    }

    /** Where the owner is, for the report. */
    public String ownerPosition(UUID owner) {
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player == null) {
            return "offline";
        }
        Location at = player.getLocation();
        return at == null || at.getWorld() == null ? "unknown"
                : at.getWorld().getName() + " " + (int) Math.floor(at.getX()) + ","
                        + (int) Math.floor(at.getY()) + "," + (int) Math.floor(at.getZ());
    }
}
