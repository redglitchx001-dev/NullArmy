package redglitchx.nullarmy.plugin.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import redglitchx.nullarmy.core.ai.Capability;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.chat.ChatBrain;
import redglitchx.nullarmy.plugin.chat.ChatDirector;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.item.SummonItems;
import redglitchx.nullarmy.plugin.spectacle.Airdrop;
import redglitchx.nullarmy.plugin.spectacle.WitherCannon;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /null} command tree.
 *
 * <p>Spec 4: "Implement permission-checked commands, tab completion where
 * appropriate, clear feedback, audit logs for destructive/admin actions, and
 * safe handling of offline/ambiguous targets."</p>
 *
 * <h2>Rules this class follows without exception</h2>
 * <ul>
 *   <li><b>Nothing throws out of a command.</b> {@link #onCommand} is wrapped as
 *       a whole and every subcommand is wrapped again, so a broken subsystem
 *       produces one prefixed line in chat and a stack trace in the log - never
 *       an error in the player's face and never a half-executed command.</li>
 *   <li><b>Every line is prefixed {@code [NullArmy]}.</b> A player must always be
 *       able to tell what spoke and why.</li>
 *   <li><b>Every subcommand checks its own permission</b> before it touches
 *       anything, and the menu dispatches through this same path, so a button
 *       can never be a way around a permission.</li>
 *   <li><b>Honest stubs.</b> Features that are not implemented say so, name the
 *       phase, and do nothing. Nothing pretends to work.</li>
 * </ul>
 *
 * <p>Two commands are deliberately awkward to run:</p>
 * <ul>
 *   <li>{@code ban} requires {@code nullarmy.moderation}, is off by default,
 *       and can never be invoked by an AI agent (spec 7).</li>
 *   <li>{@code kill} sets a lethal-combat objective. It does <b>not</b> instantly
 *       kill, and operator cleanup is a separate confirmed action.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class NullCommand implements CommandExecutor, TabCompleter, Reloadable {

    /** Every chat line the plugin sends starts with the shared gradient brand. */
    private static final String PREFIX = PluginText.PREFIX;

    /** Subcommands in help order. Aliases are resolved before this list is used. */
    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "menu", "gui", "help", "status", "version", "features", "debug", "ai",
            "horn", "totem", "commander", "respawn", "loadout", "skin",
            "follow", "guard", "formation", "tactics", "attack", "attackx",
            "come", "tp", "bring", "portal",
            "stop", "dismiss", "list", "info", "name", "heal", "equip", "drop", "inv",
            "portals", "clearskins", "reload", "wand", "build", "chat", "emote", "greet",
            "withercannon", "cannon", "airdrop", "ban", "kill",
            "kit", "roles", "mission", "missions", "coordinate", "confirm", "selftest",
            "shutdown");

    /** Combat temperaments understood by {@link SquadManager}. */
    private static final List<String> TACTICS = Arrays.asList("aggressive", "balanced", "defensive");

    /** Gestures a Null can perform. */
    private static final List<String> EMOTES = Arrays.asList("wave", "salute", "nod", "point", "dance", "sit");

    /** Formation styles understood by {@link SquadManager}. */
    private static final List<String> FORMATIONS = Arrays.asList("line", "square", "encircle", "turtle");

    /** One help line per subcommand: usage, permission, description. */
    private static final List<String[]> HELP = Arrays.asList(
            new String[]{"menu [page]", "nullarmy.gui", "Open the NullArmy command menu (a real GUI)."},
            new String[]{"help", "", "List every subcommand."},
            new String[]{"status", "nullarmy.admin", "Live squads, caps, adapter, config path."},
            new String[]{"version", "", "Plugin, adapter and server version."},
            new String[]{"features", "nullarmy.admin", "What works, and what needs an AI model."},
            new String[]{"debug", "nullarmy.admin", "Guard state, subsystem failures, tracked entities."},
            new String[]{"horn", "nullarmy.summon", "Give yourself the item named Null (Call Goat Horn)."},
            new String[]{"totem", "nullarmy.summon", "Give yourself the Totem Of Null (same flow)."},
            new String[]{"commander", "nullarmy.commander", "Summon the Null Commander out of a portal."},
            new String[]{"respawn", "nullarmy.commander", "Bring the Commander back if it is gone."},
            new String[]{"loadout", "nullarmy.gui", "Edit the Commander's loadout."},
            new String[]{"skin", "nullarmy.admin", "Which skins are configured and resolved."},
            new String[]{"follow", "nullarmy.follow", "Your Nulls walk to you. They never teleport."},
            new String[]{"guard", "nullarmy.follow", "Hold position and watch."},
            new String[]{"formation <style>", "nullarmy.follow", "line, square, encircle or turtle."},
            new String[]{"attack <player>", "nullarmy.attack", "Set a physical pursuit objective."},
            new String[]{"attackx <player>", "nullarmy.attackx", "Extreme-combat profile for the squad."},
            new String[]{"come", "nullarmy.follow", "Walk your squad to your position (never a teleport)."},
            new String[]{"stop", "nullarmy.admin", "Stop every Null of yours exactly where it stands."},
            new String[]{"dismiss", "nullarmy.admin", "Remove your Nulls immediately (kill switch)."},
            new String[]{"list", "nullarmy.admin", "Every live Null with health and position."},
            new String[]{"info <id|name>", "nullarmy.admin", "Details for one Null."},
            new String[]{"name <id|name> <new>", "nullarmy.admin", "Rename a Null (not supported by the adapter yet)."},
            new String[]{"heal", "nullarmy.admin", "Top your Nulls back to full health."},
            new String[]{"equip", "nullarmy.admin", "Hand your held item to your first Null."},
            new String[]{"drop", "nullarmy.admin", "Empty your Nulls' inventories into the world."},
            new String[]{"portals", "nullarmy.admin", "Play the portal visual where you stand."},
            new String[]{"clearskins", "nullarmy.admin", "Forget cached skins and resolve them again."},
            new String[]{"reload", "nullarmy.admin", "Re-read config.yml without a restart."},
            new String[]{"wand", "nullarmy.build", "Region-select tool for building (Phase 7)."},
            new String[]{"build <structure>", "nullarmy.build", "Bounded, inventory-funded building (Phase 7)."},
            new String[]{"chat [null|commander|off]", "nullarmy.chat", "Private chat with a Null or the Commander."},
            new String[]{"ai", "nullarmy.admin", "Whether an AI model is configured and reachable."},
            new String[]{"portal [player]", "nullarmy.admin", "Walk your Nulls through a portal to you or a player."},
            new String[]{"emote <wave|salute|nod|point|dance|sit>", "nullarmy.admin", "A visible human gesture from your Nulls."},
            new String[]{"greet [player]", "nullarmy.follow", "Your Nulls face and greet someone."},
            new String[]{"inv", "nullarmy.admin", "What your Nulls are carrying (read-only)."},
            new String[]{"tactics <aggressive|balanced|defensive>", "nullarmy.attack", "How your Nulls fight."},
            new String[]{"withercannon", "nullarmy.admin", "Fire the opt-in TNT-minecart sky cannon."},
            new String[]{"airdrop [count]", "nullarmy.admin", "Portals above and below deliver a squad."},
            new String[]{"ban <player>", "nullarmy.moderation", "Moderation action; never an AI action."},
            new String[]{"kill <player>", "nullarmy.attack", "Set a lethal-combat objective (they can still win)."},
            new String[]{"kit", "nullarmy.admin", "The default kit, and whether every Null really wears it."},
            new String[]{"roles", "nullarmy.follow", "Store squad roles: scout, guard, escort, ranged, medic."},
            new String[]{"mission <start|stop|status> [kind]", "nullarmy.mission", "One objective for the whole army."},
            new String[]{"coordinate [note]", "nullarmy.admin", "The Commander coordinates the squad (typed actions only)."},
            new String[]{"confirm [yes|no]", "nullarmy.admin", "Confirm or drop an action the Commander is holding."},
            new String[]{"shutdown", "nullarmy.admin", "Run the Totem Of Null shutdown now, on purpose."},
            new String[]{"selftest", "nullarmy.admin", "Runtime smoke test: spawn, tracking, packets, portals."});

    private final NullArmyPlugin plugin;
    private final SquadManager squads;
    private PluginConfig config;

    public NullCommand(NullArmyPlugin plugin, SquadManager squads, PluginConfig config) {
        this.plugin = plugin;
        this.squads = squads;
        this.config = config;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    // ------------------------------------------------------------------ entry points

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            return run(sender, args);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] /null failed: " + Guard.describe(t));
            sender.sendMessage(PREFIX + "That command failed: " + Guard.describe(t));
            return true;
        }
    }

    /**
     * Runs a subcommand for a player exactly as if they had typed it.
     *
     * <p>The menu uses this path on purpose: permission checks, policy gates and
     * the safety wrappers are the ones the typed command gets, so a GUI button
     * can never bypass anything.</p>
     */
    public void dispatch(Player player, String[] args) {
        if (player == null) {
            return;
        }
        Guard.attempt(plugin.getLogger(), "menu action", () -> {
            if (args == null || args.length == 0) {
                showHelp(player);
                return;
            }
            run(player, args);
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            return complete(sender, args);
        } catch (Throwable t) {
            // A throwing tab completer desyncs the client's command preview and
            // spams the log; an empty list is always safe.
            return Collections.emptyList();
        }
    }

    private List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            return out;
        }
        String sub = canonical(args[0]);
        if (args.length == 2) {
            switch (sub) {
                case "attack":
                case "attackx":
                case "kill":
                case "ban":
                    return onlinePlayerNames(args[1]);
                case "formation":
                    return startingWith(FORMATIONS, args[1]);
                case "tactics":
                    return startingWith(TACTICS, args[1]);
                case "emote":
                    return startingWith(EMOTES, args[1]);
                case "chat":
                    return startingWith(Arrays.asList("commander", "null", "off", "status"), args[1]);
                case "portal":
                case "greet":
                    return onlinePlayerNames(args[1]);
                case "info":
                case "name":
                    return nullSubcommandTargets(args[1]);
                case "mission":
                    return missionCompletion(args[1]);
                case "confirm":
                    return startingWith(Arrays.asList("yes", "no"), args[1]);
                case "airdrop":
                case "menu":
                    return Collections.emptyList();
                default:
                    break;
            }
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------------ dispatch

    /** Handles one invocation. Never throws to the caller: onCommand wraps it. */
    private boolean run(CommandSender sender, String[] args) {
        if (args == null || args.length == 0) {
            showHelp(sender);
            return true;
        }
        String sub = canonical(args[0]);
        switch (sub) {
            case "help":
                return showHelp(sender);
            case "menu":
                return menu(sender);
            case "status":
                return status(sender);
            case "version":
                return version(sender);
            case "features":
                return features(sender);
            case "debug":
                return debug(sender);
            case "horn":
                return summonItem(sender, true);
            case "totem":
                return summonItem(sender, false);
            case "commander":
                return commander(sender);
            case "respawn":
                return respawn(sender);
            case "loadout":
                return loadoutGui(sender);
            case "skin":
                return skinStatus(sender);
            case "follow":
                return follow(sender);
            case "guard":
                return guard(sender);
            case "formation":
                return formation(sender, args);
            case "attack":
                return combatObjective(sender, args, false);
            case "attackx":
                return combatObjective(sender, args, true);
            case "come":
                return come(sender);
            case "stop":
                return stop(sender);
            case "dismiss":
                return dismiss(sender);
            case "list":
                return list(sender);
            case "info":
                return info(sender, args);
            case "name":
                return name(sender, args);
            case "heal":
                return heal(sender);
            case "equip":
                return equip(sender);
            case "drop":
                return drop(sender);
            case "portals":
                return portals(sender);
            case "clearskins":
                return clearSkins(sender);
            case "reload":
                return reload(sender);
            case "wand":
                return notYet(sender, "The build wand", "Phase 7 (building)");
            case "build":
                return notYet(sender, "Building structures", "Phase 7 (building)");
            case "chat":
                return chat(sender, args);
            case "ai":
                return ai(sender);
            case "portal":
                return portal(sender, args);
            case "emote":
                return emote(sender, args);
            case "greet":
                return greet(sender, args);
            case "inv":
                return inv(sender);
            case "tactics":
                return tactics(sender, args);
            case "withercannon":
                return witherCannon(sender);
            case "airdrop":
                return airdrop(sender, args);
            case "ban":
                return ban(sender, args);
            case "kill":
                return kill(sender, args);
            case "kit":
                return kit(sender);
            case "roles":
                return roles(sender);
            case "mission":
            case "missions":
                return mission(sender, args);
            case "coordinate":
                return coordinate(sender, args);
            case "confirm":
                return confirm(sender, args);
            case "shutdown":
                return shutdown(sender);
            case "selftest":
                return selftest(sender);
            default:
                sender.sendMessage(PREFIX + "Unknown subcommand '" + args[0] + "'.");
                sender.sendMessage(PREFIX + "Run /null help for the full list.");
                return true;
        }
    }

    /**
     * True when {@code raw} is a subcommand this plugin actually implements.
     *
     * <p>Used by the chat interface: an order like "null attack Steve" must be
     * recognised as a command, while "null who are you" must not be.</p>
     */
    public boolean isSubcommand(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        String name = canonical(raw);
        return SUBCOMMANDS.contains(name)
                || name.equals("kill") || name.equals("ban")
                || name.equals("inv") || name.equals("tactics") || name.equals("portal")
                || name.equals("emote") || name.equals("greet") || name.equals("ai")
                || name.equals("kit") || name.equals("roles") || name.equals("mission")
                || name.equals("coordinate") || name.equals("confirm")
                || name.equals("shutdown") || name.equals("selftest");
    }

    /** Maps every alias to its canonical subcommand name. */
    private static String canonical(String raw) {
        String sub = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        switch (sub) {
            case "m":
            case "gui":
                return "menu";
            case "cannon":
            case "wc":
                return "withercannon";
            case "tp":
            case "bring":
                return "come";
            case "skins":
                return "skin";
            default:
                return sub;
        }
    }

    // ------------------------------------------------------------------ subcommands

    private boolean showHelp(CommandSender sender) {
        sender.sendMessage(PREFIX + "NullArmy commands (usage - what it does):");
        for (String[] line : HELP) {
            if (line[1] != null && !line[1].isEmpty() && !sender.hasPermission(line[1])) {
                continue; // do not advertise what the sender may not run
            }
            sender.sendMessage(PREFIX + "  /null " + line[0] + " - " + line[2]);
        }
        sender.sendMessage(PREFIX + "Nulls walk; they never teleport. Destructive features are off"
                + " until you enable them in config.yml.");
        return true;
    }

    private boolean menu(CommandSender sender) {
        if (!require(sender, "nullarmy.gui")) {
            return true;
        }
        Player player = asPlayer(sender, "The menu is a GUI, so it needs a player.");
        if (player == null) {
            return true;
        }
        if (plugin.menu() == null) {
            sender.sendMessage(PREFIX + "The menu is unavailable in this state - use /null help.");
            return true;
        }
        plugin.menu().open(player);
        return true;
    }

    private boolean status(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        VersionAdapter adapter = plugin.adapter();
        Caps caps = config.caps();
        Guard.Breaker breaker = plugin.spawnBreaker();
        sender.sendMessage(PREFIX + "Status:");
        sender.sendMessage(PREFIX + "  adapter: " + (adapter == null ? "none" : adapter.minecraftVersion()));
        sender.sendMessage(PREFIX + "  live Nulls: " + squads.liveCount() + "/" + caps.maxLiveNpcs()
                + " (summon cap " + caps.summonHardCap() + " per request)");
        sender.sendMessage(PREFIX + "  activity: " + squads.objectiveSummary());
        sender.sendMessage(PREFIX + "  open summon prompts: " + (plugin.summonFlow() == null
                ? "n/a" : String.valueOf(plugin.summonFlow().pendingCount())));
        sender.sendMessage(PREFIX + "  spawn path: " + breaker.statusLine());
        sender.sendMessage(PREFIX + "  tracked entities: " + (plugin.registry() == null
                ? "n/a" : plugin.registry().size() + "/" + plugin.registry().maxTracked()));
        sender.sendMessage(PREFIX + "  policy: griefing=" + config.griefingEnabled()
                + " explosives=" + config.explosivesEnabled()
                + " wither=" + config.witherEnabled()
                + " (wither fully usable=" + config.witherActuallyAllowed() + ")");
        sender.sendMessage(PREFIX + "  wither cannon: " + (plugin.witherCannon() == null
                ? gateLine(config.witherCannonUsable(), config.witherCannonEnabled(),
                        "wither-cannon.enabled")
                : plugin.witherCannon().describeState(sender instanceof Player ? (Player) sender : null)));
        sender.sendMessage(PREFIX + "  arrival portals: " + (plugin.portals() == null
                ? "unavailable" : plugin.portals().describe()));
        sender.sendMessage(PREFIX + "  default kit: " + (plugin.kits() == null
                ? "unavailable" : plugin.kits().describe() + ", verified="
                        + kitVerified()));
        sender.sendMessage(PREFIX + "  mission: " + (plugin.missions() == null
                ? "unavailable" : plugin.missions().oneLine()));
        sender.sendMessage(PREFIX + "  totem shutdown: " + (plugin.shutdown() == null
                ? "unavailable" : plugin.shutdown().describe()));
        sender.sendMessage(PREFIX + "  commander: " + commanderLine());
        sender.sendMessage(PREFIX + "  air drop: " + gateLine(config.airdropEnabled(),
                config.airdropEnabled(), "airdrop.enabled"));
        sender.sendMessage(PREFIX + "  config: " + plugin.configFile());
        return true;
    }

    private static String gateLine(boolean usable, boolean enabled, String key) {
        if (usable) {
            return "ENABLED";
        }
        return enabled ? "configured but blocked by policy" : "off (" + key + " is false)";
    }

    private boolean version(CommandSender sender) {
        String pluginVersion = "unknown";
        try {
            // Deprecated but still present; wrapped because /null version must
            // never be the thing that throws.
            pluginVersion = plugin.getDescription().getVersion();
        } catch (Throwable ignored) {
            // Keep the fallback.
        }
        String server = "unknown";
        try {
            server = Bukkit.getMinecraftVersion();
        } catch (Throwable ignored) {
            // Keep the fallback.
        }
        VersionAdapter adapter = plugin.adapter();
        sender.sendMessage(PREFIX + "NullArmy " + pluginVersion
                + " | adapter " + (adapter == null ? "none" : adapter.minecraftVersion())
                + " | server " + server);
        sender.sendMessage(PREFIX + "Nulls are real server-side entities: one hitbox, one inventory,"
                + " no teleporting, no free items.");
        return true;
    }

    /**
     * Tells the owner exactly what works right now and what an AI model would
     * add. Deliberately honest: with no endpoints the plugin is complete, and
     * the few things that need a model are listed rather than glossed over.
     */
    private boolean features(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        boolean ai = config.aiUsable();
        sender.sendMessage(PREFIX + "Features - AI " + (ai ? "ENABLED" : "OFF (running fully offline)"));
        sender.sendMessage(PREFIX + "  always available:   " + Capability.countAlways());
        sender.sendMessage(PREFIX + "  local fallback:     " + Capability.countLocalFallback()
                + " (work offline; a model only refines them)");
        sender.sendMessage(PREFIX + "  need an AI model:   " + Capability.countAiOnly());

        if (!ai) {
            sender.sendMessage(PREFIX + "");
            sender.sendMessage(PREFIX + "Without an AI endpoint these are unavailable:");
            for (Capability c : Capability.lostWithoutAi()) {
                sender.sendMessage(PREFIX + "  - " + c.description());
                sender.sendMessage(PREFIX + "      offline: " + c.offlineBehaviour());
            }
            sender.sendMessage(PREFIX + "");
            sender.sendMessage(PREFIX + "Everything else works. To add a model see ENDPOINTS.md.");
        }
        return true;
    }

    private boolean debug(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        sender.sendMessage(PREFIX + "Debug:");
        sender.sendMessage(PREFIX + "  " + plugin.spawnBreaker().statusLine());
        if (plugin.witherCannon() != null) {
            sender.sendMessage(PREFIX + "  wither cannon shots in flight: "
                    + plugin.witherCannon().shotsInFlight());
        }
        if (plugin.airdrop() != null) {
            sender.sendMessage(PREFIX + "  air drop pending: " + plugin.airdrop().hasPending());
        }
        sender.sendMessage(PREFIX + "  squads: " + squads.allSquads().size()
                + ", members: " + squads.allMembers().size()
                + ", objective: " + squads.objectiveSummary());
        var failures = plugin.subsystemFailures();
        if (failures.isEmpty()) {
            sender.sendMessage(PREFIX + "  subsystem failures: none");
        } else {
            sender.sendMessage(PREFIX + "  subsystem failures: " + failures);
        }
        var disabled = plugin.disabledSubsystems();
        if (!disabled.isEmpty()) {
            sender.sendMessage(PREFIX + "  switched off for this session: " + disabled
                    + " (restart after fixing the log)");
        }
        if (plugin.registry() != null) {
            sender.sendMessage(PREFIX + "  tracked entities:");
            for (String line : plugin.registry().describe()) {
                sender.sendMessage(PREFIX + "    " + line);
            }
        }
        if (plugin.adapter() != null) {
            sender.sendMessage(PREFIX + "  tracking internals: "
                    + plugin.adapter().trackingDiagnostics());
        }
        if (plugin.kits() != null && !plugin.kits().configErrors().isEmpty()) {
            sender.sendMessage(PREFIX + "  kit config problems: " + plugin.kits().configErrors());
        }
        if (plugin.coordinator() != null) {
            sender.sendMessage(PREFIX + "  coordinator decisions:");
            for (String line : plugin.coordinator().recentDecisions()) {
                sender.sendMessage(PREFIX + "    " + line);
            }
        }
        if (plugin.totems() != null) {
            sender.sendMessage(PREFIX + "  totem shutdowns triggered: "
                    + plugin.totems().triggeredCount());
            sender.sendMessage(PREFIX + "  summon items: "
                    + redglitchx.nullarmy.plugin.item.SummonItems.identitySummary());
        }
        if (plugin.lastMigration() != null) {
            sender.sendMessage(PREFIX + "  last config migration: "
                    + plugin.lastMigration().describe());
        }
        if (plugin.selfTest() != null && !plugin.selfTest().lastResults().isEmpty()) {
            sender.sendMessage(PREFIX + "  last self test: " + plugin.selfTest().lastPassed()
                    + " passed, " + plugin.selfTest().lastFailed() + " failed");
            for (String line : plugin.selfTest().lastResults()) {
                sender.sendMessage(PREFIX + "    " + line);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ new commands

    /** The default kit, and proof that the live Nulls are actually wearing it. */
    private boolean kit(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.kits() == null) {
            sender.sendMessage(PREFIX + "The kit service is not available.");
            return true;
        }
        sender.sendMessage(PREFIX + "Default kit: " + plugin.kits().describe());
        for (String line : plugin.kits().configLines()) {
            sender.sendMessage(PREFIX + "  " + line);
        }
        sender.sendMessage(PREFIX + "Applied to Nulls: " + config.kitAppliesToNulls()
                + ", to the Commander on a fresh install: " + config.kitAppliesToCommander());
        sender.sendMessage(PREFIX + "Live check: " + kitVerified());
        if (sender instanceof Player && config.kitAppliesToNulls()) {
            int touched = 0;
            for (redglitchx.nullarmy.nms.NullBody body : squads.membersOf(((Player) sender).getUniqueId())) {
                if (plugin.kits().applyTo(body)) {
                    touched++;
                }
            }
            if (touched > 0) {
                sender.sendMessage(PREFIX + "Re-checked " + touched
                        + " of your Nulls; missing slots were filled, nothing was duplicated.");
            }
        }
        return true;
    }

    /** One line saying whether the kit is really on the bodies. */
    private String kitVerified() {
        if (plugin.kits() == null) {
            return "unavailable";
        }
        int checked = 0;
        int wrong = 0;
        String firstProblem = null;
        for (redglitchx.nullarmy.nms.NullBody body : squads.allMembers()) {
            checked++;
            String problem = plugin.kits().verify(body);
            if (problem != null) {
                wrong++;
                if (firstProblem == null) {
                    firstProblem = problem;
                }
            }
        }
        if (plugin.commander() != null && plugin.commander().isSpawned()) {
            checked++;
            String problem = plugin.commander().kitProblem();
            if (problem != null) {
                wrong++;
                if (firstProblem == null) {
                    firstProblem = problem;
                }
            }
        }
        if (checked == 0) {
            return "no Nulls to check";
        }
        return wrong == 0 ? checked + " body/bodies all wear it"
                : wrong + "/" + checked + " incomplete (" + firstProblem + ")";
    }

    /** Stores and reports squad roles. */
    private boolean roles(CommandSender sender) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player has a squad to organise.");
        if (player == null) {
            return true;
        }
        int assigned = squads.assignRoles(player.getUniqueId());
        if (assigned <= 0) {
            sender.sendMessage(PREFIX + "You have no Nulls to organise. Sound the horn first.");
            return true;
        }
        sender.sendMessage(PREFIX + "Roles stored for " + assigned + " Null(s): "
                + squads.roleSummary(player.getUniqueId()));
        for (String duty : squads.roleDuties(player.getUniqueId())) {
            sender.sendMessage(PREFIX + "  " + duty);
        }
        return true;
    }

    /** One objective for the whole army. */
    private boolean mission(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.mission")) {
            return true;
        }
        if (plugin.missions() == null) {
            sender.sendMessage(PREFIX + "The mission system is not available.");
            return true;
        }
        String sub = args.length < 2 ? "status" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start": {
                Player player = asPlayer(sender, "Only a player can lead a mission.");
                if (player == null) {
                    return true;
                }
                String kind = args.length < 3 ? "" : args[2];
                if (kind.isEmpty()) {
                    sender.sendMessage(PREFIX + "Which mission? Try one of: "
                            + String.join(", ", redglitchx.nullarmy.plugin.mission.MissionRunner.kinds()));
                    return true;
                }
                sender.sendMessage(PREFIX + plugin.missions().start(player.getUniqueId(), kind));
                return true;
            }
            case "stop":
                sender.sendMessage(PREFIX + plugin.missions().stop("the owner stopped it"));
                return true;
            case "kinds":
            case "list":
                sender.sendMessage(PREFIX + "Missions (all original NullArmy content):");
                for (redglitchx.nullarmy.core.mission.MissionKind kind
                        : redglitchx.nullarmy.core.mission.MissionKind.values()) {
                    sender.sendMessage(PREFIX + "  " + kind.key() + " - " + kind.title()
                            + ": " + kind.briefing());
                }
                return true;
            case "status":
            default:
                for (String line : plugin.missions().describe()) {
                    sender.sendMessage(PREFIX + line);
                }
                return true;
        }
    }

    private List<String> missionCompletion(String partial) {
        List<String> words = new ArrayList<>(
                redglitchx.nullarmy.plugin.mission.MissionRunner.kinds());
        words.add("start");
        words.add("stop");
        words.add("status");
        return startingWith(words, partial);
    }

    /**
     * The Commander coordinates its squad.
     *
     * <p>With a model configured the answer is parsed into one typed, allowlisted
     * action and validated before anything runs. Without one the local coordinator
     * takes a safe step instead - and says which of the two it was.</p>
     */
    private boolean coordinate(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Coordination needs an owner in the world.");
        if (player == null) {
            return true;
        }
        if (plugin.coordinator() == null) {
            sender.sendMessage(PREFIX + "The squad coordinator is not available.");
            return true;
        }
        String note = args.length < 2 ? "" : String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        boolean model = plugin.chat() != null && plugin.chat().brain() != null
                && plugin.chat().brain().available() && config.aiSquadCoordination();
        if (model) {
            sender.sendMessage(PREFIX + "The Commander is reading the squad and will answer"
                    + " with one allowlisted action.");
            plugin.coordinator().askModel(player.getUniqueId(), note,
                    line -> player.sendMessage(PREFIX + "Commander: " + line));
            return true;
        }
        sender.sendMessage(PREFIX + "No AI endpoint is configured, so the local coordinator"
                + " ran instead (this is honest, not a model reply).");
        sender.sendMessage(PREFIX + "Commander: " + plugin.coordinator().deterministicStep(
                player.getUniqueId()));
        return true;
    }

    /** Confirms or drops an action the Commander is holding. */
    private boolean confirm(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Only the owner can confirm an action.");
        if (player == null) {
            return true;
        }
        if (plugin.coordinator() == null) {
            sender.sendMessage(PREFIX + "The squad coordinator is not available.");
            return true;
        }
        boolean yes = args.length < 2 || !args[1].equalsIgnoreCase("no");
        sender.sendMessage(PREFIX + plugin.coordinator().confirm(player.getUniqueId(), yes));
        return true;
    }

    /** Runs the totem shutdown deliberately, for testing and for an owner who means it. */
    private boolean shutdown(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.shutdown() == null) {
            sender.sendMessage(PREFIX + "The shutdown director is not available.");
            return true;
        }
        if (plugin.shutdown().isRunning()) {
            sender.sendMessage(PREFIX + "A shutdown is already running: "
                    + plugin.shutdown().describe());
            return true;
        }
        if (squads.liveCount() == 0) {
            sender.sendMessage(PREFIX + "There are no Nulls to send out.");
            return true;
        }
        sender.sendMessage(PREFIX + "Starting the sequential shutdown of " + squads.liveCount()
                + " Null(s), one at a time, Commander last.");
        plugin.shutdown().start("an operator ran /null shutdown");
        return true;
    }

    /** The runtime smoke test. */
    private boolean selftest(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.selfTest() == null) {
            sender.sendMessage(PREFIX + "The self test is not available.");
            return true;
        }
        sender.sendMessage(PREFIX + plugin.selfTest().start(sender));
        return true;
    }

    /** The Commander, in one line. */
    private String commanderLine() {
        if (plugin.commander() == null) {
            return "unavailable";
        }
        return (plugin.commander().isSpawned() ? "here" : "not spawned")
                + ", name '" + plugin.commander().commanderName() + "'"
                + ", saved loadout=" + plugin.commander().hasSavedLoadout()
                + (plugin.commander().kitProblem() == null ? ""
                        : ", kit: " + plugin.commander().kitProblem());
    }

    /** Gives the Call Horn or the Totem Of Null, the trigger items from spec 3. */
    private boolean summonItem(CommandSender sender, boolean horn) {
        if (!require(sender, "nullarmy.summon")) {
            return true;
        }
        Player player = asPlayer(sender, "You need to be a player to hold the item.");
        if (player == null) {
            return true;
        }
        ItemStack item = horn ? SummonItems.callHorn(plugin) : SummonItems.totemOfNull(plugin);
        if (item == null) {
            sender.sendMessage(PREFIX + "Could not build the item - see the server log.");
            return true;
        }
        java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        String name = horn ? "Null (Call Goat Horn)" : "Totem Of Null";
        sender.sendMessage(PREFIX + "Given: " + name + " (right-click it to summon Nulls).");
        if (horn) {
            sender.sendMessage(PREFIX + "The Call horn sound plays, then you can enter a count in chat or type 'cancel'.");
        } else {
            sender.sendMessage(PREFIX + "Right-click the totem, then enter a count in chat or type 'cancel'.");
        }
        return true;
    }

    /**
     * Spawns the Null Commander out of a portal.
     *
     * <p>The entrance is deliberately the portal: the Commander steps out of
     * it rather than blinking into existence.</p>
     */
    private boolean commander(CommandSender sender) {
        if (!require(sender, "nullarmy.commander")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can summon the Commander - it spawns where you stand.");
        if (player == null) {
            return true;
        }
        if (plugin.commander() == null) {
            sender.sendMessage(PREFIX + "The Commander is unavailable in this state.");
            return true;
        }
        if (plugin.commander().isSpawned()) {
            sender.sendMessage(PREFIX + "The Commander is already here. Use /null respawn if it is gone.");
            return true;
        }
        boolean ok = plugin.commander().spawn(player);
        sender.sendMessage(PREFIX + (ok
                ? "The Commander has arrived. Use /null loadout to equip it."
                : "The Commander could not be summoned - the reason is in the server log ([NullArmy])."));
        return true;
    }

    private boolean respawn(CommandSender sender) {
        if (!require(sender, "nullarmy.commander")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can respawn the Commander.");
        if (player == null) {
            return true;
        }
        if (plugin.commander() == null) {
            sender.sendMessage(PREFIX + "The Commander is unavailable in this state.");
            return true;
        }
        if (plugin.commander().isSpawned()) {
            sender.sendMessage(PREFIX + "The Commander is alive; nothing to respawn.");
            return true;
        }
        boolean ok = plugin.commander().spawn(player);
        sender.sendMessage(PREFIX + (ok ? "The Commander is back."
                : "The Commander could not be summoned - see the log."));
        return true;
    }

    /** Opens the Commander loadout editor. */
    private boolean loadoutGui(CommandSender sender) {
        if (!require(sender, "nullarmy.gui")) {
            return true;
        }
        Player player = asPlayer(sender, "The loadout editor is a GUI, so it needs a player.");
        if (player == null) {
            return true;
        }
        plugin.commander().openLoadout(player);
        return true;
    }

    /** Reports which skins are configured and whether they resolved. */
    private boolean skinStatus(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        sender.sendMessage(PREFIX + "Null skin:      " + config.nullSkinName());
        sender.sendMessage(PREFIX + "Commander skin: " + config.commanderSkinName()
                + (config.commanderSkinName().equalsIgnoreCase(config.nullSkinName())
                   ? " (inherited)" : ""));
        sender.sendMessage(PREFIX + "Change them under  skins:  in config.yml, or start the"
                + " server with -Dnullarmy.skin.null=Name");
        if (plugin.skins() != null) {
            for (String line : plugin.skins().diagnostics()) {
                sender.sendMessage(PREFIX + "  " + line);
            }
        }
        return true;
    }

    private boolean follow(CommandSender sender) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can be followed.");
        if (player == null) {
            return true;
        }
        int count = squads.follow(player.getUniqueId(), player.getUniqueId(), player.getName());
        answer(sender, count, "will follow you. They walk - they never teleport to catch up.",
                "You have no live Nulls to follow you.");
        return true;
    }

    private boolean guard(CommandSender sender) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can give the order.");
        if (player == null) {
            return true;
        }
        int count = squads.guard(player.getUniqueId());
        answer(sender, count, "are holding position and watching.", "You have no live Nulls to guard.");
        return true;
    }

    private boolean formation(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can command a formation.");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null formation <" + String.join("|", FORMATIONS) + ">");
            return true;
        }
        String style = args[1].toLowerCase(Locale.ROOT);
        if (!FORMATIONS.contains(style)) {
            sender.sendMessage(PREFIX + "Unknown formation '" + args[1] + "'. Try "
                    + String.join(", ", FORMATIONS) + ".");
            return true;
        }
        int count = squads.formation(player.getUniqueId(), player.getUniqueId(),
                player.getName(), style);
        answer(sender, count, "are forming up around you (" + style + ").",
                "You have no live Nulls to arrange.");
        return true;
    }

    private boolean combatObjective(CommandSender sender, String[] args, boolean extreme) {
        String permission = extreme ? "nullarmy.attackx" : "nullarmy.attack";
        if (!require(sender, permission)) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can give the order.");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null " + (extreme ? "attackx" : "attack") + " <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // Safe handling of offline/ambiguous targets (spec 4).
            sender.sendMessage(PREFIX + "No online player named '" + args[1] + "'.");
            return true;
        }
        int count = squads.attack(player.getUniqueId(), target.getUniqueId(), target.getName());
        if (count <= 0) {
            sender.sendMessage(PREFIX + "You have no live Nulls to send.");
            return true;
        }
        sender.sendMessage(PREFIX + count + " Null(s) will pursue " + target.getName()
                + (extreme ? " on the extreme profile (better tactics - never extra damage or free items)." : "."));
        sender.sendMessage(PREFIX + "They have to walk there. Nothing was teleported and"
                + " " + target.getName() + " was not touched.");
        return true;
    }

    /**
     * Walks the squad to the player.
     *
     * <p>Deliberately <b>not</b> a teleport: spec 5 forbids teleporting Nulls,
     * including as recovery. This is the same physical objective {@code follow}
     * uses, so the squad arrives by walking.</p>
     */
    private boolean come(CommandSender sender) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player has a position to come to.");
        if (player == null) {
            return true;
        }
        Location loc = player.getLocation();
        if (loc == null || loc.getWorld() == null) {
            sender.sendMessage(PREFIX + "Could not read your position.");
            return true;
        }
        int count = squads.destination(player.getUniqueId(),
                new Vec3d(loc.getX(), loc.getY(), loc.getZ()), "your position");
        answer(sender, count, "are walking to you (physical travel, no teleport).",
                "You have no live Nulls to call.");
        return true;
    }

    private boolean stop(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (!(sender instanceof Player)) {
            squads.dismissAll();
            sender.sendMessage(PREFIX + "All Nulls on the server have been stopped and dismissed.");
            return true;
        }
        Player player = (Player) sender;
        int count = squads.clearObjectives(player.getUniqueId());
        plugin.getLogger().info("AUDIT: " + sender.getName() + " stopped " + count + " Null(s).");
        answer(sender, count, "have stopped where they stand.", "You have no live Nulls.");
        return true;
    }

    private boolean dismiss(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = sender instanceof Player ? (Player) sender : null;
        if (player == null) {
            squads.dismissAll();
            sender.sendMessage(PREFIX + "Every Null on the server has been dismissed.");
            return true;
        }
        int removed = squads.dismiss(player.getUniqueId());
        if (plugin.summonFlow() != null) {
            plugin.summonFlow().cancelPending(player.getUniqueId());
        }
        plugin.getLogger().info("AUDIT: " + sender.getName() + " dismissed " + removed + " Null(s).");
        sender.sendMessage(PREFIX + (removed == 0
                ? "You had no Nulls to dismiss."
                : "Dismissed " + removed + " Null(s)."));
        return true;
    }

    private boolean list(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        List<String> lines = squads.describeMembers();
        if (lines.isEmpty()) {
            sender.sendMessage(PREFIX + "No live Nulls.");
            return true;
        }
        sender.sendMessage(PREFIX + "Live Nulls (" + lines.size() + "):");
        for (String line : lines) {
            sender.sendMessage(PREFIX + "  " + line);
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null info <id|name>   (see /null list)");
            return true;
        }
        NullBody body = squads.find(args[1]);
        if (body == null) {
            sender.sendMessage(PREFIX + "No live Null matches '" + args[1] + "'.");
            return true;
        }
        SquadManager.Squad squad = squadOf(body);
        sender.sendMessage(PREFIX + "Null " + (squad == null ? "" : "in " + squad.worldName() + ": "));
        sender.sendMessage(PREFIX + "  " + squads.describe(body, squad));
        try {
            var inventory = body.inventory();
            sender.sendMessage(PREFIX + "  inventory: " + (inventory == null ? "unavailable"
                    : inventory.total() + "/" + inventory.capacity() + " items, consistent="
                            + inventory.isConsistent()));
        } catch (Throwable t) {
            sender.sendMessage(PREFIX + "  inventory: unreadable (" + Guard.describe(t) + ")");
        }
        return true;
    }

    private boolean name(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "Usage: /null name <id|name> <new name>");
            return true;
        }
        sender.sendMessage(PREFIX + "Renaming a live Null is not supported yet: the adapter builds"
                + " the profile from NameGenerator and the SPI has no rename call.");
        sender.sendMessage(PREFIX + "Nothing changed. See /null info " + args[1] + " for the current name.");
        return true;
    }

    private boolean heal(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player owns Nulls to heal.");
        if (player == null) {
            return true;
        }
        int healed = squads.healAll(player.getUniqueId(), 1024.0);
        answer(sender, healed, "have been topped up to full health.", "You have no live Nulls to heal.");
        return true;
    }

    /**
     * Hands the held item to the first Null.
     *
     * <p>The item is <b>taken</b> from the player's hand, so this cannot be used
     * to duplicate anything: what the player loses, the Null gains.</p>
     */
    private boolean equip(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player can hand over an item.");
        if (player == null) {
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            sender.sendMessage(PREFIX + "Hold the item you want your Null to carry, then run /null equip.");
            return true;
        }
        List<NullBody> members = squads.membersOf(player.getUniqueId());
        if (members.isEmpty()) {
            sender.sendMessage(PREFIX + "You have no live Nulls to equip. Nothing was taken.");
            return true;
        }
        NullBody body = members.get(0);
        Material type = held.getType();
        int amount = held.getAmount();
        int slot = slotFor(type);
        try {
            body.setLoadout(Collections.singletonList(LoadoutSlot.of(slot, type.name(), amount)));
        } catch (Throwable t) {
            sender.sendMessage(PREFIX + "The Null would not take the item: " + Guard.describe(t));
            return true;
        }
        // Only removed after the Null accepted it, so nothing is ever lost.
        player.getInventory().setItemInMainHand(null);
        sender.sendMessage(PREFIX + "Handed " + amount + "x " + type.name()
                + " to Null #" + body.id() + " (slot " + slot + ").");
        sender.sendMessage(PREFIX + "The item left your hand, so nothing was duplicated.");
        return true;
    }

    /** Where an item belongs: armour slots by name, everything else in hand. */
    private static int slotFor(Material material) {
        String name = material.name();
        if (name.endsWith("_HELMET") || name.equals("TURTLE_HELMET") || name.equals("CARVED_PUMPKIN")) {
            return LoadoutSlot.SLOT_HELMET;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return LoadoutSlot.SLOT_CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return LoadoutSlot.SLOT_LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return LoadoutSlot.SLOT_BOOTS;
        }
        if (name.equals("SHIELD")) {
            return LoadoutSlot.SLOT_OFFHAND;
        }
        return 0; // first hotbar slot: "in hand"
    }

    /**
     * Empties every Null's inventory into the world as real drops.
     *
     * <p>Each slot is removed from the Null first and only then dropped, so the
     * server can never end up with both.</p>
     */
    private boolean drop(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Only a player owns Nulls to empty.");
        if (player == null) {
            return true;
        }
        List<NullBody> members = squads.membersOf(player.getUniqueId());
        if (members.isEmpty()) {
            sender.sendMessage(PREFIX + "You have no live Nulls.");
            return true;
        }
        int dropped = 0;
        List<String> problems = new ArrayList<>();
        for (NullBody body : members) {
            List<LoadoutSlot> slots;
            try {
                slots = body.loadout();
            } catch (Throwable t) {
                problems.add("#" + body.id() + ": " + Guard.describe(t));
                continue;
            }
            if (slots == null || slots.isEmpty()) {
                continue;
            }
            World world = bodyWorld(body);
            Location at = bodyLocation(body, world);
            for (LoadoutSlot slot : slots) {
                Material material = Material.matchMaterial(slot.material());
                if (material == null || material.isAir() || world == null || at == null) {
                    problems.add(slot.material() + " (no material/world match)");
                    continue;
                }
                try {
                    world.dropItemNaturally(at, new ItemStack(material, slot.count()));
                    dropped++;
                } catch (Throwable t) {
                    problems.add(material.name() + ": " + Guard.describe(t));
                }
            }
            try {
                body.setLoadout(Collections.emptyList());
            } catch (Throwable t) {
                problems.add("#" + body.id() + " could not be emptied: " + Guard.describe(t));
            }
        }
        sender.sendMessage(PREFIX + (dropped == 0
                ? "Your Nulls were carrying nothing to drop."
                : "Dropped " + dropped + " stack(s) from your Nulls at their feet."));
        for (String problem : problems) {
            sender.sendMessage(PREFIX + "  skipped: " + problem);
        }
        return true;
    }

    private World bodyWorld(NullBody body) {
        SquadManager.Squad squad = squadOf(body);
        if (squad == null) {
            return null;
        }
        return Bukkit.getWorld(squad.worldName());
    }

    private Location bodyLocation(NullBody body, World world) {
        if (world == null) {
            return null;
        }
        try {
            Vec3d pos = body.bodyPosition();
            return new Location(world, pos.x(), pos.y(), pos.z());
        } catch (Throwable t) {
            return null;
        }
    }

    private SquadManager.Squad squadOf(NullBody body) {
        for (SquadManager.Squad squad : squads.allSquads()) {
            try {
                if (squad.members().contains(body)) {
                    return squad;
                }
            } catch (Throwable ignored) {
                // A broken squad is simply not the owner.
            }
        }
        return null;
    }

    private boolean portals(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.portals() != null) {
            sender.sendMessage(PREFIX + "Arrival portals: " + plugin.portals().describe());
        }
        Player player = asPlayer(sender, "Only a player has a position for the portal.");
        if (player == null) {
            return true;
        }
        Location loc = player.getLocation();
        if (loc == null || loc.getWorld() == null) {
            sender.sendMessage(PREFIX + "Could not read your position.");
            return true;
        }
        VersionAdapter adapter = plugin.adapter();
        if (adapter == null) {
            sender.sendMessage(PREFIX + "No adapter is loaded, so no effects can be played.");
            return true;
        }
        int effects = Math.max(Caps.minPortalEffects(), config.caps().portalEffectsPerSummon());
        String world = loc.getWorld().getName();
        Vec3d at = new Vec3d(loc.getX(), loc.getY(), loc.getZ());
        boolean ok = Guard.attempt(plugin.getLogger(), "manual portal effects",
                () -> adapter.playPortalEffects(world, at, effects));
        sender.sendMessage(PREFIX + (ok
                ? "Played " + effects + " portal effects where you stand (cosmetic only)."
                : "The portal effect failed - see the log."));
        return true;
    }

    private boolean clearSkins(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.skins() == null) {
            sender.sendMessage(PREFIX + "The skin resolver is unavailable.");
            return true;
        }
        int cleared = plugin.skins().clearCache();
        sender.sendMessage(PREFIX + "Cleared " + cleared + " cached skin entr(ies).");
        sender.sendMessage(PREFIX + "The next summon resolves the skin again (asynchronously, so"
                + " nothing stalls the server).");
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        boolean ok = plugin.reloadPluginConfig();
        if (ok) {
            plugin.getLogger().info("AUDIT: " + sender.getName() + " reloaded config.yml.");
            sender.sendMessage(PREFIX + "config.yml reloaded. The spawn path was re-armed and"
                    + " every subsystem re-read its settings.");
            // The reason a reload used to look broken: the file on disk never
            // changed, so nothing new appeared. Say exactly what it did.
            redglitchx.nullarmy.plugin.config.ConfigMigration.Report migration =
                    plugin.lastMigration();
            if (migration == null) {
                sender.sendMessage(PREFIX + "  no config migration report is available.");
            } else {
                sender.sendMessage(PREFIX + "  " + migration.describe());
                if (migration.changed()) {
                    sender.sendMessage(PREFIX + "  added: " + migration.addedKeys());
                    sender.sendMessage(PREFIX + "  your own values and comments were left"
                            + " untouched; the new keys are appended at the bottom of "
                            + plugin.configFile());
                }
                if (!migration.unknownKeys().isEmpty()) {
                    sender.sendMessage(PREFIX + "  keys this build does not ship (kept as they"
                            + " are): " + migration.unknownKeys().size());
                }
            }
        } else {
            sender.sendMessage(PREFIX + "Reload failed - the old settings are still in use."
                    + " The reason is in the server log.");
        }
        return true;
    }

    private boolean notYet(CommandSender sender, String feature, String phase) {
        sender.sendMessage(PREFIX + feature + " is not implemented yet (" + phase
                + " - see TRACEABILITY.md). Nothing was changed.");
        return true;
    }

    /** {@code /null chat [null|commander|off|status]} - the private channel. */
    private boolean chat(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.chat")) {
            return true;
        }
        Player player = asPlayer(sender, "Chat needs a player on one end of it.");
        if (player == null) {
            return true;
        }
        if (plugin.chat() == null) {
            sender.sendMessage(PREFIX + "The chat director is unavailable in this state.");
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(PREFIX + plugin.chat().describe(player.getUniqueId()));
            sender.sendMessage(PREFIX + "Usage: /null chat <null|commander|off>"
                    + " - then just type normally in chat.");
            if (!plugin.chat().brain().available()) {
                sender.sendMessage(PREFIX + "AI: off (" + plugin.chat().brain().unavailableReason() + ").");
            } else {
                sender.sendMessage(PREFIX + "AI: ready. The Commander will answer in character.");
            }
            return true;
        }
        ChatDirector.Speaker speaker = ChatDirector.Speaker.parse(args[1]);
        if (speaker == null) {
            sender.sendMessage(PREFIX + "'" + args[1] + "' is neither a Null nor the Commander."
                    + " Use /null chat <null|commander|off>.");
            return true;
        }
        if (speaker == ChatDirector.Speaker.NONE) {
            if (!plugin.chat().endSession(player, null)) {
                sender.sendMessage(PREFIX + "No private channel was open.");
            }
            return true;
        }
        plugin.chat().startSession(player, speaker);
        return true;
    }

    /** {@code /null ai} - honest status of the model connection. */
    private boolean ai(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        ChatBrain brain = plugin.chat() == null ? null : plugin.chat().brain();
        sender.sendMessage(PREFIX + "AI endpoints: " + config.endpoints().size()
                + ", enabled: " + config.aiEnabled() + ", usable: " + config.aiUsable() + ".");
        if (brain == null || !brain.available()) {
            sender.sendMessage(PREFIX + "AI is not usable: "
                    + (brain == null ? "the chat director is unavailable" : brain.unavailableReason()) + ".");
            sender.sendMessage(PREFIX + "The plugin is fully functional without it -"
                    + " AI only adds conversation and the AI-only roles.");
            describeCoordination(sender);
            return true;
        }
        sender.sendMessage(PREFIX + "Chat model is ready for the ChatCommander role.");
        PlanOutcome outcome = aiOutcome();
        if (outcome != null) {
            sender.sendMessage(PREFIX + outcome.message);
        }
        describeCoordination(sender);
        return true;
    }

    /**
     * What the AI is and is not allowed to do to the squad.
     *
     * <p>Honest about the split: a model may only answer with one typed action
     * from a closed allowlist, which is then checked against permissions, caps
     * and policy before it runs through the same executor a typed order uses.
     * Without an endpoint, the local coordinator does the safe subset - and this
     * says so instead of implying a model is involved.</p>
     */
    private void describeCoordination(CommandSender sender) {
        sender.sendMessage(PREFIX + "Squad coordination: " + (config.aiSquadCoordination()
                ? "on" : "off (ai.squad-coordination is false)")
                + ", automatic local steps: " + (config.aiAutoCoordinate() ? "on" : "off")
                + " every " + (config.aiCoordinateIntervalTicks() / 20) + "s.");
        sender.sendMessage(PREFIX + "  allowlisted actions: "
                + String.join(", ", redglitchx.nullarmy.core.ai.SquadAction.allowlist()));
        sender.sendMessage(PREFIX + "  a model can never run a console command, grant a"
                + " permission, enable griefing, ban or kill a player; cannon, airdrop and"
                + " dismiss always need /null confirm.");
        if (plugin.chat() == null || plugin.chat().brain() == null
                || !plugin.chat().brain().available()) {
            sender.sendMessage(PREFIX + "  no model is configured, so only the local"
                    + " deterministic coordinator runs: /null coordinate takes one safe step"
                    + " (heal a hurt squad, hold position when idle, store missing roles).");
        } else {
            sender.sendMessage(PREFIX + "  a model IS configured: /null coordinate asks it to"
                    + " read the squad and answer with one allowlisted action.");
        }
        if (plugin.coordinator() != null) {
            for (String line : plugin.coordinator().recentDecisions()) {
                sender.sendMessage(PREFIX + "  last: " + line);
            }
        }
        if (sender instanceof Player && plugin.coordinator() != null
                && plugin.coordinator().hasPendingConfirmation(((Player) sender).getUniqueId())) {
            sender.sendMessage(PREFIX + "  an action is waiting for your /null confirm.");
        }
    }

    /** Small holder so the two AI-info lines stay readable. */
    private static final class PlanOutcome {
        private final String message;
        PlanOutcome(String message) { this.message = message; }
    }

    private PlanOutcome aiOutcome() {
        int sessions = plugin.chat() == null ? 0 : plugin.chat().sessionCount();
        return new PlanOutcome("Open private channels: " + sessions
                + ". Try /null chat commander, or say 'null hello' in chat.");
    }

    /** {@code /null portal [player]} - the visible way to move Nulls. */
    private boolean portal(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "The portal needs a player to open next to.");
        if (player == null) {
            return true;
        }
        if (!config.portalTravelEnabled()) {
            sender.sendMessage(PREFIX + "Portal travel is switched off:"
                    + " set mechanics.portal-travel to true in config.yml.");
            return true;
        }
        Location target = player.getLocation();
        Player destination = null;
        if (args.length >= 2) {
            destination = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (destination == null) {
                sender.sendMessage(PREFIX + "'" + args[1] + "' is not online.");
                return true;
            }
            target = destination.getLocation();
        }
        if (target == null || target.getWorld() == null) {
            sender.sendMessage(PREFIX + "Could not read the destination position.");
            return true;
        }
        Vec3d where = new Vec3d(target.getX(), Math.floor(target.getY()), target.getZ());
        int moved = squads.portalAll(player.getUniqueId(), target.getWorld().getName(), where);
        if (moved <= 0) {
            sender.sendMessage(PREFIX + "No Null of yours could make the crossing."
                    + " They stay where they are rather than risk a bad arrival.");
            return true;
        }
        sender.sendMessage(PREFIX + "Opened the way: " + moved
                + (moved == 1 ? " Null walked" : " Nulls walked")
                + " through the portal to " + (destination == null ? "you" : destination.getName()) + ".");
        return true;
    }

    /** {@code /null emote <gesture>} - visible body language. */
    private boolean emote(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "Pick a gesture from in game.");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null emote <" + String.join("|", EMOTES) + ">");
            return true;
        }
        String gesture = args[1].toLowerCase(Locale.ROOT);
        if (!EMOTES.contains(gesture)) {
            sender.sendMessage(PREFIX + "Unknown gesture '" + args[1] + "'. Try "
                    + String.join(", ", EMOTES) + ".");
            return true;
        }
        int done = squads.emote(player.getUniqueId(), gesture, player.getLocation());
        if (done <= 0) {
            sender.sendMessage(PREFIX + "You have no Nulls nearby to " + gesture + ".");
            return true;
        }
        sender.sendMessage(PREFIX + done + (done == 1 ? " Null answers" : " Nulls answer")
                + " you: " + gesture + ".");
        return true;
    }

    /** {@code /null greet [player]} - the human touch. */
    private boolean greet(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        Player player = asPlayer(sender, "Greetings happen in person.");
        if (player == null) {
            return true;
        }
        Player target = player;
        if (args.length >= 2) {
            target = org.bukkit.Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(PREFIX + "'" + args[1] + "' is not online.");
                return true;
            }
        }
        int done = squads.greet(player.getUniqueId(), target);
        if (done <= 0) {
            sender.sendMessage(PREFIX + "You have no Nulls nearby to greet"
                    + (target == player ? " you" : " " + target.getName()) + ".");
            return true;
        }
        sender.sendMessage(PREFIX + done + (done == 1 ? " Null raises" : " Nulls raise")
                + " a hand to " + target.getName() + ".");
        return true;
    }

    /** {@code /null inv} - what the Nulls are carrying, read-only. */
    private boolean inv(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.registry() == null) {
            sender.sendMessage(PREFIX + "The entity registry is unavailable in this state.");
            return true;
        }
        List<String> lines = squads.describeInventories(sender instanceof Player
                ? ((Player) sender).getUniqueId() : null);
        if (lines.isEmpty()) {
            sender.sendMessage(PREFIX + "No Null is carrying anything.");
            return true;
        }
        sender.sendMessage(PREFIX + "Carried by your Nulls (" + lines.size() + " stack(s)):");
        for (String line : lines) {
            sender.sendMessage(PREFIX + "  " + line);
        }
        return true;
    }

    /** {@code /null tactics <aggressive|balanced|defensive>} - how they fight. */
    private boolean tactics(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.attack")) {
            return true;
        }
        Player player = asPlayer(sender, "Tactics apply to the Nulls of one player.");
        if (player == null) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Your Nulls are fighting " + squads.tactics(player.getUniqueId())
                    + ". Usage: /null tactics <" + String.join("|", TACTICS) + ">");
            return true;
        }
        String style = args[1].toLowerCase(Locale.ROOT);
        if (!TACTICS.contains(style)) {
            sender.sendMessage(PREFIX + "Unknown style '" + args[1] + "'. Try "
                    + String.join(", ", TACTICS) + ".");
            return true;
        }
        if (!squads.setTactics(player.getUniqueId(), style)) {
            sender.sendMessage(PREFIX + "You have no Nulls to give orders to.");
            return true;
        }
        sender.sendMessage(PREFIX + "Tactics set to " + style + ".");
        return true;
    }

    private boolean witherCannon(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "The cannon has to be aimed by a player.");
        if (player == null) {
            return true;
        }
        if (plugin.witherCannon() == null) {
            sender.sendMessage(PREFIX + "The wither cannon is unavailable in this state.");
            return true;
        }
        WitherCannon.Result result = plugin.witherCannon().fire(player);
        sender.sendMessage(PREFIX + result.message());
        if (!result.fired()) {
            // Say exactly what to change, so nobody has to read the source.
            sender.sendMessage(PREFIX + "The cannon is opt-in: see the wither-cannon: section of config.yml"
                    + " and policy.explosives-enabled / policy.wither-enabled.");
            sender.sendMessage(PREFIX + "Note: the blasts destroy no blocks unless you also enable"
                    + " policy.griefing-enabled and wither-cannon.blocks-damage.");
        }
        return true;
    }

    private boolean airdrop(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        Player player = asPlayer(sender, "An air drop is called by a player and lands around them.");
        if (player == null) {
            return true;
        }
        if (plugin.airdrop() == null) {
            sender.sendMessage(PREFIX + "Air drops are unavailable in this state.");
            return true;
        }
        int requested = 0;
        if (args.length >= 2) {
            try {
                requested = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                sender.sendMessage(PREFIX + "'" + args[1] + "' is not a number."
                        + " Use /null airdrop [count].");
                return true;
            }
        }
        Airdrop.Result result = plugin.airdrop().drop(player, requested);
        sender.sendMessage(PREFIX + result.message());
        if (!result.dropped()) {
            sender.sendMessage(PREFIX + "Air drops are opt-in: see the airdrop: section of config.yml.");
        }
        return true;
    }

    private boolean ban(CommandSender sender, String[] args) {
        // Spec 4: an explicit, separately permission-gated moderation action.
        // Spec 7/8: no endpoint may ever trigger this.
        if (!config.moderationIntegrationEnabled()) {
            sender.sendMessage(PREFIX + "Moderation integration is disabled. Enable "
                    + "policy.moderation-integration-enabled in config.yml to use this.");
            return true;
        }
        if (!require(sender, "nullarmy.moderation")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null ban <player>");
            return true;
        }
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            plugin.getLogger().warning("AUDIT: " + sender.getName() + " banned " + args[1]
                    + ". This was a human moderation action, never an AI action.");
            sender.sendMessage(PREFIX + "Ban integration is not wired to a moderation backend yet."
                    + " Nothing was banned.");
            return true;
        }
        sender.sendMessage(PREFIX + "Ban requires confirmation: /null ban " + args[1] + " confirm");
        return true;
    }

    private boolean kill(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.attack")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "Usage: /null kill <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + "No online player named '" + args[1] + "'.");
            return true;
        }
        // A lethal-combat OBJECTIVE only. The target can escape, defend or win.
        sender.sendMessage(PREFIX + "Lethal objective set on " + target.getName()
                + ". They can still escape, fight back, or survive.");
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** Prints either the count line or the "nothing to do" line. */
    private void answer(CommandSender sender, int count, String success, String nothing) {
        if (count <= 0) {
            sender.sendMessage(PREFIX + nothing);
            return;
        }
        sender.sendMessage(PREFIX + count + (count == 1 ? " Null " : " Nulls ") + success);
    }

    private Player asPlayer(CommandSender sender, String why) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        sender.sendMessage(PREFIX + why);
        return null;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender == null) {
            return false;
        }
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(PREFIX + "You do not have permission (" + permission + ").");
            return false;
        }
        return true;
    }

    private static List<String> onlinePlayerNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online != null && online.getName().toLowerCase(Locale.ROOT)
                    .startsWith(prefix.toLowerCase(Locale.ROOT))) {
                out.add(online.getName());
            }
        }
        return out;
    }

    private static List<String> startingWith(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String needle = prefix.toLowerCase(Locale.ROOT);
        for (String option : options) {
            if (option.startsWith(needle)) {
                out.add(option);
            }
        }
        return out;
    }

    private List<String> nullSubcommandTargets(String prefix) {
        List<String> out = new ArrayList<>();
        String needle = prefix.toLowerCase(Locale.ROOT);
        for (NullBody body : squads.allMembers()) {
            try {
                String id = String.valueOf(body.id());
                String name = body.profileName();
                if (id.startsWith(needle)) {
                    out.add(id);
                }
                if (name != null && name.toLowerCase(Locale.ROOT).startsWith(needle)) {
                    out.add(name);
                }
            } catch (Throwable ignored) {
                // A misbehaving body is simply not offered.
            }
        }
        return out;
    }
}
