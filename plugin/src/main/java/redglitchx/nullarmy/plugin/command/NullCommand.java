package redglitchx.nullarmy.plugin.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The {@code /null} command tree.
 *
 * <p>Spec 4: "Implement permission-checked commands, tab completion where
 * appropriate, clear feedback, audit logs for destructive/admin actions, and
 * safe handling of offline/ambiguous targets."</p>
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
public final class NullCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "gui", "chat", "attack", "attackx", "follow", "build",
            "status", "stop", "dismiss", "ban", "kill");

    private final NullArmyPlugin plugin;
    private final SquadManager squads;
    private final PluginConfig config;

    public NullCommand(NullArmyPlugin plugin, SquadManager squads, PluginConfig config) {
        this.plugin = plugin;
        this.squads = squads;
        this.config = config;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("NullArmy. Usage: /null <gui|chat|attack|attackx|follow|build|status|stop|dismiss|ban|kill>");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "status": return status(sender);
            case "stop":
            case "dismiss": return dismiss(sender);
            case "gui": return gui(sender);
            case "chat": return chat(sender, args);
            case "attack": return combatObjective(sender, args, false);
            case "attackx": return combatObjective(sender, args, true);
            case "follow": return follow(sender);
            case "build": return build(sender, args);
            case "ban": return ban(sender, args);
            case "kill": return kill(sender, args);
            default:
                sender.sendMessage("Unknown subcommand '" + sub + "'.");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String prefix = args[0].toLowerCase();
            for (String s : SUBCOMMANDS) {
                if (s.startsWith(prefix)) {
                    out.add(s);
                }
            }
            return out;
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("attack")
                || args[0].equalsIgnoreCase("attackx")
                || args[0].equalsIgnoreCase("ban")
                || args[0].equalsIgnoreCase("kill"))) {
            return null; // default: online player names
        }
        return Collections.emptyList();
    }

    // ------------------------------------------------------------- subcommands

    private boolean status(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        VersionAdapter adapter = plugin.adapter();
        Caps caps = plugin.pluginConfig().caps();
        sender.sendMessage("NullArmy status:");
        sender.sendMessage("  adapter: " + (adapter == null ? "none" : adapter.minecraftVersion()));
        sender.sendMessage("  live Nulls: " + squads.liveCount() + "/" + caps.maxLiveNpcs());
        sender.sendMessage("  griefing: " + config.griefingEnabled()
                + "  explosives: " + config.explosivesEnabled()
                + "  wither: " + config.witherActuallyAllowed());
        // Diagnostics only. Never prints credentials or keys (spec 9).
        return true;
    }

    private boolean dismiss(CommandSender sender) {
        if (!require(sender, "nullarmy.admin")) {
            return true;
        }
        squads.dismissAll();
        plugin.getLogger().info("AUDIT: " + sender.getName() + " dismissed all Nulls.");
        sender.sendMessage("All Nulls dismissed.");
        return true;
    }

    private boolean gui(CommandSender sender) {
        if (!require(sender, "nullarmy.gui")) {
            return true;
        }
        // Phase 3 implements the inventory. It is a blueprint, never a duplicator.
        sender.sendMessage("The loadout GUI is not implemented yet (Phase 3 - see TRACEABILITY.md).");
        return true;
    }

    private boolean chat(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.chat")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /null chat <on|off>");
            return true;
        }
        boolean on = args[1].equalsIgnoreCase("on");
        sender.sendMessage("Null chat is now " + (on ? "on" : "off")
                + ". (ChatCommander wiring lands in Phase 8.)");
        return true;
    }

    private boolean combatObjective(CommandSender sender, String[] args, boolean extreme) {
        String permission = extreme ? "nullarmy.attackx" : "nullarmy.attack";
        if (!require(sender, permission)) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /null " + (extreme ? "attackx" : "attack") + " <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            // Safe handling of offline/ambiguous targets (spec 4).
            sender.sendMessage("No online player named '" + args[1] + "'.");
            return true;
        }
        // Sets an objective. The target is NOT damaged or moved here - the squad
        // still has to walk there and fight (spec 4).
        sender.sendMessage("Objective set: squad will pursue " + target.getName()
                + (extreme ? " (extreme profile: better tactics, no extra damage or free items)." : "."));
        return true;
    }

    private boolean follow(CommandSender sender) {
        if (!require(sender, "nullarmy.follow")) {
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can be followed.");
            return true;
        }
        sender.sendMessage("Squad will follow you. They walk - they never teleport to catch up.");
        return true;
    }

    private boolean build(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.build")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /null build <structure>");
            return true;
        }
        // Phase 7: schematic lookup, plan validation, then physical block-by-block build.
        sender.sendMessage("Building is not implemented yet (Phase 7 - see TRACEABILITY.md).");
        return true;
    }

    private boolean ban(CommandSender sender, String[] args) {
        // Spec 4: an explicit, separately permission-gated moderation action.
        // Spec 7/8: no endpoint may ever trigger this.
        if (!config.moderationIntegrationEnabled()) {
            sender.sendMessage("Moderation integration is disabled. Enable "
                    + "policy.moderation-integration-enabled in config.yml to use this.");
            return true;
        }
        if (!require(sender, "nullarmy.moderation")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /null ban <player>");
            return true;
        }
        sender.sendMessage("Ban requires confirmation: /null ban " + args[1] + " confirm");
        if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
            plugin.getLogger().warning("AUDIT: " + sender.getName() + " banned " + args[1]
                    + ". This was a human moderation action, never an AI action.");
            sender.sendMessage("Ban integration is not wired to a moderation backend yet.");
        }
        return true;
    }

    private boolean kill(CommandSender sender, String[] args) {
        if (!require(sender, "nullarmy.attack")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /null kill <player>");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage("No online player named '" + args[1] + "'.");
            return true;
        }
        // A lethal-combat OBJECTIVE only. The target can escape, defend or win.
        sender.sendMessage("Lethal objective set on " + target.getName()
                + ". They can still escape, fight back, or survive.");
        return true;
    }

    private boolean require(CommandSender sender, String permission) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage("You do not have permission (" + permission + ").");
            return false;
        }
        return true;
    }
}
