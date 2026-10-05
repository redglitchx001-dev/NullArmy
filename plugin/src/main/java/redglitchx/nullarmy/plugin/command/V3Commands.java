package redglitchx.nullarmy.plugin.command;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import redglitchx.nullarmy.core.config.YamlProblem;
import redglitchx.nullarmy.core.formation.FormationMatrix;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.body.Mind;
import redglitchx.nullarmy.plugin.loadout.LoadoutService;
import redglitchx.nullarmy.plugin.zone.ZoneService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The v3 subcommands: {@code config, skin, zone, order, loadout, ai build,
 * ai stop} and an honest {@code reload}.
 *
 * <p>Every reply goes to the issuer only ({@link redglitchx.nullarmy.plugin.chat.ChatGate#answer}).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class V3Commands {

    public static final List<String> SUBCOMMANDS = Arrays.asList("config", "zone", "order");
    public static final List<String> VERBS = Arrays.asList("walk", "run", "sprint", "jump", "stop", "follow",
            "hold", "gather", "build", "attack", "defend");

    private final NullArmyPlugin plugin;

    public V3Commands(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    /** True when this class answers {@code sub} with these arguments. */
    public boolean handles(String sub, String[] args) {
        switch (sub) {
            case "config":
            case "zone":
            case "order":
            case "skin":
            case "loadout":
            case "reload":
                return true;
            case "ai":
                return args.length >= 2 && (args[1].equalsIgnoreCase("build") || args[1].equalsIgnoreCase("stop"));
            default:
                return false;
        }
    }

    public boolean run(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "config":
                return config(sender, args);
            case "zone":
                return zone(sender);
            case "order":
                return order(sender, args);
            case "skin":
                return skin(sender);
            case "loadout":
                return loadout(sender, args);
            case "reload":
                return reload(sender);
            case "ai":
                return args[1].equalsIgnoreCase("build") ? aiBuild(sender, args) : aiStop(sender);
            default:
                return false;
        }
    }

    private void say(CommandSender to, String line) {
        if (plugin.chatGate() != null) {
            plugin.chatGate().answer(to, line);
        } else {
            to.sendMessage(line);
        }
    }

    private boolean need(CommandSender sender, String permission) {
        if (permission == null || permission.isEmpty() || sender.hasPermission(permission)) {
            return true;
        }
        say(sender, "You need " + permission + " for that.");
        return false;
    }

    // ----------------------------------------------------------------- config

    /** {@code /null config [page|key-prefix]}: the effective value of every setting. */
    private boolean config(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        YamlProblem problem = plugin.lastConfigProblem();
        say(sender, "config: " + (plugin.configFile() == null ? "(no file)" : plugin.configFile().getAbsolutePath())
                + (problem == null ? " - parsed OK" : " - NOT applied: " + problem.headline()));
        List<String> lines = new ArrayList<>();
        FileConfiguration file = plugin.pluginConfig() == null ? null : plugin.pluginConfig().file();
        if (file != null) {
            java.util.Set<String> keys = new java.util.TreeSet<>();
            if (file.getDefaults() != null) {
                keys.addAll(file.getDefaults().getKeys(true));
            }
            keys.addAll(file.getKeys(true));
            for (String key : keys) {
                Object value = file.get(key);
                if (value instanceof org.bukkit.configuration.ConfigurationSection) {
                    continue;
                }
                lines.add(key + " = " + mask(key, value));
            }
        }
        for (Map.Entry<String, Object> entry : plugin.pluginConfig().v3().describe().entrySet()) {
            lines.add("(effective) " + entry.getKey() + " = " + entry.getValue());
        }
        int page = 1;
        String filter = null;
        if (args.length >= 2) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException notANumber) {
                filter = args[1].toLowerCase(Locale.ROOT);
            }
        }
        if (filter != null) {
            List<String> filtered = new ArrayList<>();
            for (String line : lines) {
                if (line.toLowerCase(Locale.ROOT).contains(filter)) {
                    filtered.add(line);
                }
            }
            lines = filtered;
        }
        int perPage = 18;
        int pages = Math.max(1, (lines.size() + perPage - 1) / perPage);
        page = Math.min(page, pages);
        for (int i = (page - 1) * perPage; i < Math.min(lines.size(), page * perPage); i++) {
            say(sender, "  " + lines.get(i));
        }
        say(sender, "page " + page + "/" + pages + " (" + lines.size() + " values) - /null config <page|filter>");
        if (sender instanceof Player) {
            plugin.getLogger().info("[NullArmy] " + sender.getName() + " viewed the effective configuration.");
        }
        return true;
    }

    private static Object mask(String key, Object value) {
        String k = key.toLowerCase(Locale.ROOT);
        if ((k.contains("api-key") || k.contains("token") || k.contains("secret") || k.endsWith("signature")
                || k.equals("skins.value")) && value != null && !String.valueOf(value).isEmpty()) {
            return String.valueOf(value).startsWith("env:") ? value : "(set, hidden)";
        }
        return value;
    }

    // ----------------------------------------------------------------- reload

    private boolean reload(CommandSender sender) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        boolean ok = plugin.reloadPluginConfig();
        YamlProblem problem = plugin.lastConfigProblem();
        if (problem != null) {
            say(sender, "config.yml was NOT applied - " + problem.headline());
            for (String line : problem.snippet()) {
                say(sender, line);
            }
            say(sender, "The last good configuration is still in use. Fix the line above and run /null reload.");
            return true;
        }
        if (!ok) {
            say(sender, "Reload failed - see the console. The previous configuration is still in use.");
            return true;
        }
        say(sender, "Reloaded config.yml: caps " + plugin.pluginConfig().caps().maxLiveNpcs() + " live / "
                + plugin.pluginConfig().caps().summonHardCap() + " per summon, zone "
                + plugin.pluginConfig().v3().zoneSize() + ", combat " + (plugin.pluginConfig().v3().combatEnabled()
                ? "on" : "off") + ". Skins re-resolve and re-apply live.");
        if (plugin.lastMigration() != null && plugin.lastMigration().changed()) {
            say(sender, plugin.lastMigration().describe());
        }
        return true;
    }

    // ------------------------------------------------------------------- skin

    private boolean skin(CommandSender sender) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.skinChain() == null) {
            say(sender, "The skin chain is not running.");
            return true;
        }
        say(sender, "Nulls: " + plugin.skinChain().resolution(false).source());
        for (String attempt : plugin.skinChain().resolution(false).attempts()) {
            say(sender, "  - " + attempt);
        }
        if (!plugin.skinChain().lastProxyStatus().isEmpty()) {
            say(sender, "  proxy: " + plugin.skinChain().lastProxyStatus());
        }
        say(sender, "Commander: " + plugin.skinChain().resolution(true).source());
        List<String> report = plugin.skinChain().report();
        if (report.isEmpty()) {
            say(sender, "No Null is out right now.");
        }
        for (String line : report.subList(0, Math.min(20, report.size()))) {
            say(sender, "  " + line);
        }
        if (plugin.skins() != null) {
            for (String line : plugin.skins().diagnostics()) {
                say(sender, "  cache: " + line);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------- zone

    private boolean zone(CommandSender sender) {
        if (!(sender instanceof Player)) {
            say(sender, "/null zone shows your summon zone around you - run it in game.");
            return true;
        }
        if (!need(sender, "nullarmy.summon")) {
            return true;
        }
        Player player = (Player) sender;
        ZoneService.Record record = plugin.zones().zoneOf(player);
        plugin.zones().showOutline(player, record, 5);
        say(sender, "Your summon zone: " + record.describe() + ". The border glows for 5 seconds.");
        return true;
    }

    // ------------------------------------------------------------------ order

    /** {@code /null order <target> <verb> [args]}. */
    private boolean order(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.follow")) {
            return true;
        }
        if (args.length < 3) {
            say(sender, "Usage: /null order <name|all|commander> <" + String.join("|", VERBS) + "> [args]");
            return true;
        }
        List<NullBody> targets = targets(sender, args[1]);
        if (targets.isEmpty()) {
            say(sender, "No Null matches '" + args[1] + "'.");
            return true;
        }
        String verbWord = args[2].toLowerCase(Locale.ROOT);
        Mind.Verb verb;
        try {
            verb = Mind.Verb.valueOf(verbWord.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            say(sender, "Unknown order '" + args[2] + "'. Orders: " + String.join(", ", VERBS));
            return true;
        }
        String[] rest = Arrays.copyOfRange(args, 3, args.length);
        UUID issuer = sender instanceof Player ? ((Player) sender).getUniqueId() : null;
        Player player = sender instanceof Player ? (Player) sender : null;
        switch (verb) {
            case WALK:
            case RUN:
            case SPRINT: {
                Object where = place(sender, rest);
                if (where == null) {
                    say(sender, "Where to? 'here', '<x> <y> <z>' or a player name.");
                    return true;
                }
                if (where instanceof Entity) {
                    say(sender, plugin.brain().order(targets, verb, null, ((Entity) where).getUniqueId(), issuer, 1));
                } else {
                    say(sender, plugin.brain().order(targets, verb, (Vec3d) where, null, issuer, 1));
                }
                return true;
            }
            case JUMP: {
                int count = 1;
                if (rest.length > 0) {
                    try {
                        count = Math.max(1, Math.min(20, Integer.parseInt(rest[0])));
                    } catch (NumberFormatException ignored) {
                        count = 1;
                    }
                }
                say(sender, plugin.brain().order(targets, verb, null, null, issuer, count));
                return true;
            }
            case STOP:
                if (plugin.builder() != null && issuer != null) {
                    plugin.builder().stop(issuer, "stopped by order");
                }
                say(sender, plugin.brain().order(targets, verb, null, null, issuer, 1));
                return true;
            case FOLLOW:
            case DEFEND: {
                Entity whom = rest.length > 0 ? Bukkit.getPlayerExact(rest[0]) : player;
                if (whom == null) {
                    say(sender, "Who? Name an online player.");
                    return true;
                }
                say(sender, plugin.brain().order(targets, verb, null, whom.getUniqueId(), issuer, 1));
                return true;
            }
            case HOLD: {
                if (rest.length > 0 && FormationMatrix.isKnown(rest[0]) && player != null) {
                    Location at = player.getLocation();
                    int n = plugin.squads().holdFormation(player.getUniqueId(),
                            new Vec3d(at.getX(), at.getY(), at.getZ()), at.getYaw(), rest[0].toLowerCase(Locale.ROOT));
                    plugin.brain().clearOrders(targets);
                    for (NullBody body : targets) {
                        plugin.brain().acknowledge(body, plugin.brain().mind(body));
                    }
                    say(sender, n + " Null(s) hold a " + rest[0].toLowerCase(Locale.ROOT) + " where you stand,"
                            + " facing your way.");
                    return true;
                }
                say(sender, plugin.brain().order(targets, verb, null, null, issuer, 1));
                return true;
            }
            case GATHER:
            case BUILD: {
                if (player == null) {
                    say(sender, "Building needs a player position - run it in game.");
                    return true;
                }
                String goal = verb == Mind.Verb.GATHER ? "gather wood" : String.join(" ", rest);
                return startBuild(player, goal);
            }
            case ATTACK: {
                Entity target = rest.length > 0 ? Bukkit.getPlayerExact(rest[0]) : null;
                if (target == null && player != null && rest.length > 0 && rest[0].equalsIgnoreCase("nearest")) {
                    target = nearestHostile(player);
                }
                if (target == null) {
                    say(sender, "Attack whom? Name an online player, or 'nearest' for the nearest hostile mob.");
                    return true;
                }
                say(sender, plugin.brain().order(targets, verb, null, target.getUniqueId(), issuer, 1));
                return true;
            }
            default:
                say(sender, "That order is not available.");
                return true;
        }
    }

    private Entity nearestHostile(Player player) {
        Entity best = null;
        double bestDist = 24.0D * 24.0D;
        for (Entity near : player.getNearbyEntities(24, 8, 24)) {
            if (near instanceof org.bukkit.entity.Monster && !near.isDead()) {
                double d = near.getLocation().distanceSquared(player.getLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = near;
                }
            }
        }
        return best;
    }

    /** 'here', 'x y z', or a player. */
    private Object place(CommandSender sender, String[] rest) {
        if (rest.length == 0 || rest[0].equalsIgnoreCase("here") || rest[0].equalsIgnoreCase("me")) {
            if (sender instanceof Player) {
                Location at = ((Player) sender).getLocation();
                return new Vec3d(at.getX(), at.getY(), at.getZ());
            }
            return null;
        }
        if (rest.length >= 3) {
            try {
                return new Vec3d(Double.parseDouble(rest[0]), Double.parseDouble(rest[1]), Double.parseDouble(rest[2]));
            } catch (NumberFormatException ignored) {
                // maybe a name
            }
        }
        return Bukkit.getPlayerExact(rest[0]);
    }

    /** "all" (yours, or everybody's from the console), "commander", or one Null by name/id. */
    List<NullBody> targets(CommandSender sender, String token) {
        List<NullBody> out = new ArrayList<>();
        if (token == null || plugin.squads() == null) {
            return out;
        }
        if (token.equalsIgnoreCase("all") || token.equalsIgnoreCase("squad") || token.equalsIgnoreCase("everyone")) {
            if (sender instanceof Player) {
                out.addAll(plugin.squads().membersOf(((Player) sender).getUniqueId()));
            }
            if (out.isEmpty() && (!(sender instanceof Player) || sender.hasPermission("nullarmy.admin"))) {
                out.addAll(plugin.squads().allMembers());
            }
            return out;
        }
        if (token.equalsIgnoreCase("commander")) {
            if (plugin.commander() != null && plugin.commander().body() != null) {
                out.add(plugin.commander().body());
            }
            return out;
        }
        NullBody one = plugin.squads().find(token);
        if (one != null) {
            out.add(one);
        }
        return out;
    }

    // ---------------------------------------------------------------- loadout

    /** {@code /null loadout [nullName | template <name> [save <from> | apply <to>]]}. */
    private boolean loadout(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.gui")) {
            return true;
        }
        LoadoutService loadouts = plugin.loadouts();
        if (args.length >= 2 && args[1].equalsIgnoreCase("template")) {
            if (args.length < 3) {
                say(sender, "Templates: " + (loadouts.templateNames().isEmpty() ? "none yet"
                        : String.join(", ", loadouts.templateNames()))
                        + ". /null loadout template <name> [save <null|commander> | apply <null|all|commander>]");
                return true;
            }
            String name = args[2];
            if (args.length >= 5 && args[3].equalsIgnoreCase("save")) {
                if (!need(sender, "nullarmy.admin")) {
                    return true;
                }
                LoadoutService.Target from = args[4].equalsIgnoreCase("commander") ? loadouts.commanderTarget()
                        : nullTarget(args[4]);
                say(sender, from == null ? "No Null called " + args[4] + "." : loadouts.saveTemplate(name, from));
                return true;
            }
            if (args.length >= 5 && args[3].equalsIgnoreCase("apply")) {
                if (!need(sender, "nullarmy.admin")) {
                    return true;
                }
                if (args[4].equalsIgnoreCase("commander")) {
                    say(sender, loadouts.applyTemplate(name, loadouts.commanderTarget()));
                    return true;
                }
                List<NullBody> bodies = targets(sender, args[4]);
                if (bodies.isEmpty()) {
                    say(sender, "No Null matches '" + args[4] + "'.");
                    return true;
                }
                for (NullBody body : bodies) {
                    say(sender, loadouts.applyTemplate(name, loadouts.nullTarget(body)));
                }
                return true;
            }
            if (!(sender instanceof Player)) {
                say(sender, "Viewing a template needs a player.");
                return true;
            }
            loadouts.open((Player) sender, loadouts.forTemplate(name));
            return true;
        }
        if (!(sender instanceof Player)) {
            say(sender, "The loadout editor needs a player.");
            return true;
        }
        Player player = (Player) sender;
        if (args.length >= 2) {
            NullBody body = plugin.squads().find(args[1]);
            if (body == null) {
                say(sender, "No Null called '" + args[1] + "'. /null list shows their names.");
                return true;
            }
            loadouts.open(player, loadouts.forNull(body));
            say(sender, "Editing " + body.profileName() + "'s loadout - changes apply when you close it.");
            return true;
        }
        loadouts.open(player, loadouts.forCommander());
        say(sender, "Editing the Commander's loadout - changes apply when you close it.");
        return true;
    }

    private LoadoutService.Target nullTarget(String name) {
        NullBody body = plugin.squads() == null ? null : plugin.squads().find(name);
        return body == null ? null : plugin.loadouts().nullTarget(body);
    }

    // --------------------------------------------------------------------- ai

    private boolean aiBuild(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.build")) {
            return true;
        }
        if (!(sender instanceof Player)) {
            say(sender, "Building needs a player position - run it in game.");
            return true;
        }
        String goal = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
        return startBuild((Player) sender, goal);
    }

    private boolean startBuild(Player player, String goal) {
        if (plugin.builder() == null) {
            say(player, "The builder is not running.");
            return true;
        }
        Location at = player.getLocation();
        String answer = plugin.builder().start(player.getUniqueId(), at.getWorld().getName(),
                new Vec3d(at.getX(), at.getY(), at.getZ()), at.getYaw(), goal,
                result -> say(player, result));
        say(player, answer);
        return true;
    }

    private boolean aiStop(CommandSender sender) {
        if (!need(sender, "nullarmy.build")) {
            return true;
        }
        if (!(sender instanceof Player)) {
            plugin.builder().stopAll("stopped from the console");
            say(sender, "Every build was stopped.");
            return true;
        }
        say(sender, plugin.builder().stop(((Player) sender).getUniqueId(), "stopped by its owner"));
        return true;
    }

    /** Tab completion for the v3 subcommands. */
    public List<String> complete(String sub, String[] args) {
        if (sub.equals("order")) {
            if (args.length == 2) {
                List<String> out = new ArrayList<>(Arrays.asList("all", "commander"));
                if (plugin.squads() != null) {
                    for (NullBody body : plugin.squads().allMembers()) {
                        out.add(body.profileName());
                    }
                }
                return out;
            }
            if (args.length == 3) {
                return VERBS;
            }
            if (args.length == 4 && args[2].equalsIgnoreCase("hold")) {
                return FormationMatrix.kinds();
            }
        }
        if (sub.equals("ai") && args.length == 2) {
            return Arrays.asList("build", "stop");
        }
        if (sub.equals("loadout") && args.length == 2) {
            List<String> out = new ArrayList<>(Collections.singletonList("template"));
            if (plugin.squads() != null) {
                for (NullBody body : plugin.squads().allMembers()) {
                    out.add(body.profileName());
                }
            }
            return out;
        }
        return Collections.emptyList();
    }
}
