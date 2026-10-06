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
import redglitchx.nullarmy.plugin.spectacle.WitherCannon;
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
            "hold", "gather", "build", "attack", "defend", "march", "drill", "patrol", "bridge", "salute",
            "regroup", "hunt");

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
            case "name":
            case "cannon":
                return true;
            case "ai":
                if (args.length < 2) {
                    return false;
                }
                return args[1].equalsIgnoreCase("build") || args[1].equalsIgnoreCase("stop")
                        || args[1].equalsIgnoreCase("test") || args[1].equalsIgnoreCase("endpoints");
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
            case "name":
                return name(sender, args);
            case "cannon":
                return cannon(sender, args);
            case "ai":
                if (args[1].equalsIgnoreCase("build")) {
                    return aiBuild(sender, args);
                }
                if (args[1].equalsIgnoreCase("test")) {
                    return aiTest(sender, args);
                }
                if (args[1].equalsIgnoreCase("endpoints")) {
                    return aiEndpoints(sender);
                }
                return aiStop(sender);
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
        boolean endpointUrl = k.startsWith("ai.endpoints.") && k.endsWith(".endpoint");
        if ((endpointUrl || k.equals("ai.builder.endpoint") || k.equals("skins.proxy-url"))
                && value != null && !String.valueOf(value).isEmpty()) {
            return redglitchx.nullarmy.core.agent.EndpointConfig.safeEndpointForDisplay(String.valueOf(value));
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
            case MARCH: {
                // L-01: march to a point in a locked phalanx, on one cadence.
                Vec3d point = pointOf(sender, player, rest);
                if (point == null) {
                    say(sender, "Where to? 'here' or '<x> <y> <z>'.");
                    return true;
                }
                say(sender, plugin.brain().order(targets, verb, point, null, issuer, 1));
                return true;
            }
            case DRILL: {
                // L-01: cycle line -> wedge -> phalanx where the squad stands.
                Vec3d point = rest.length == 0 && player != null ? here(player) : pointOf(sender, player, rest);
                if (point == null) {
                    say(sender, "Drill needs a player to stand on, or '<x> <y> <z>'.");
                    return true;
                }
                say(sender, plugin.brain().order(targets, verb, point, null, issuer, 1));
                return true;
            }
            case PATROL: {
                // L-03: patrol between two points for ever.
                Vec3d[] points = patrolPoints(player, rest);
                if (points == null) {
                    say(sender, "Patrol where? 'patrol here to <x> <y> <z>' or"
                            + " 'patrol <x1> <y1> <z1> to <x2> <y2> <z2>'.");
                    return true;
                }
                say(sender, plugin.brain().patrol(targets, points[0], points[1], issuer));
                return true;
            }
            case BRIDGE: {
                // L-02: walk forward, bridging the gaps on the way.
                if (player == null) {
                    say(sender, "Bridging needs a player to stand in front of.");
                    return true;
                }
                int blocks = 12;
                if (rest.length > 0) {
                    try {
                        blocks = Math.max(1, Math.min(64, Integer.parseInt(rest[0])));
                    } catch (NumberFormatException ignored) {
                        blocks = 12;
                    }
                }
                say(sender, plugin.brain().bridge(targets, ahead(player, blocks), issuer));
                return true;
            }
            case SALUTE: {
                say(sender, plugin.brain().salute(targets, issuer));
                return true;
            }
            case REGROUP: {
                // L-07: come back to the owner.
                say(sender, plugin.brain().order(targets, verb, null, issuer, issuer, 1));
                return true;
            }
            case HUNT: {
                // L-07: hunt to the end - two chasers, the rest hold the line.
                Entity target = rest.length > 0 ? Bukkit.getPlayerExact(rest[0]) : null;
                if (target == null && player != null && rest.length > 0 && rest[0].equalsIgnoreCase("nearest")) {
                    target = nearestHostile(player);
                }
                if (target == null) {
                    say(sender, "Hunt whom? Name an online player, or 'nearest' for the nearest hostile mob.");
                    return true;
                }
                int chasers = 2;
                if (rest.length > 1) {
                    try {
                        chasers = Math.max(1, Math.min(8, Integer.parseInt(rest[1])));
                    } catch (NumberFormatException ignored) {
                        chasers = 2;
                    }
                }
                say(sender, plugin.brain().hunt(targets, target.getUniqueId(), issuer, chasers));
                return true;
            }
            case DESTROY: {
                // P-09: teardown. Refused here too unless griefing is enabled.
                if (plugin.pluginConfig() == null || !plugin.pluginConfig().griefingEnabled()) {
                    say(sender, "Tearing blocks down is off: set policy.griefing-enabled to true"
                            + " in config.yml first.");
                    plugin.getLogger().info("[NullArmy] destroy refused for " + sender.getName()
                            + ": policy.griefing-enabled is false.");
                    return true;
                }
                if (player == null) {
                    say(sender, "Tearing down needs a player position.");
                    return true;
                }
                int radius = 2;
                if (rest.length > 0) {
                    try {
                        radius = Math.max(1, Math.min(8, Integer.parseInt(rest[0])));
                    } catch (NumberFormatException ignored) {
                        radius = 2;
                    }
                }
                say(sender, plugin.brain().destroy(targets, here(player), radius, issuer));
                return true;
            }
            default:
                say(sender, "That order is not available.");
                return true;
        }
    }

    // ------------------------------------------------------------------- name

    /**
     * {@code /null name <new>}: renames the Commander live (P-12).
     *
     * <p>The name is the one anybody can call him by in chat, and it is saved
     * into {@code commander.yml} so it survives a restart.</p>
     */
    private boolean name(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        if (args.length < 2) {
            say(sender, "The Commander is called " + plugin.commander().commanderName()
                    + ". Usage: /null name <new>");
            return true;
        }
        String wanted = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (wanted.isEmpty()) {
            say(sender, "The Commander needs a name.");
            return true;
        }
        if (wanted.length() > 16) {
            say(sender, "That name is " + wanted.length() + " characters; a player name is at most 16.");
            return true;
        }
        String answer = plugin.commander().rename(wanted);
        say(sender, answer);
        return true;
    }

    // ----------------------------------------------------------------- cannon

    /** {@code /null cannon aim|fire [shots]|confirm|cancel} (P-08). */
    private boolean cannon(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.witherCannon() == null) {
            say(sender, "The cannon is unavailable in this state.");
            return true;
        }
        String what = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "aim";
        Player player = sender instanceof Player ? (Player) sender : null;
        switch (what) {
            case "aim":
                if (player == null) {
                    say(sender, "Aiming needs a player: the rod goes into your hand.");
                    return true;
                }
                say(sender, plugin.witherCannon().aim(player));
                return true;
            case "fire": {
                if (player == null) {
                    say(sender, "Firing needs a player to aim from.");
                    return true;
                }
                int shots = 0;
                if (args.length >= 3) {
                    try {
                        shots = Integer.parseInt(args[2]);
                    } catch (NumberFormatException notANumber) {
                        say(sender, "'" + args[2] + "' is not a number of volleys.");
                        return true;
                    }
                }
                // The confirm step: nothing is created until /null cannon confirm.
                say(sender, plugin.witherCannon().requestFire(player, shots));
                return true;
            }
            case "confirm": {
                if (player == null) {
                    say(sender, "Only a player can fire the cannon.");
                    return true;
                }
                WitherCannon.Result result = plugin.witherCannon().confirm(player);
                say(sender, result.message());
                if (!result.fired()) {
                    say(sender, "The cannon is opt-in: see the wither-cannon: section of config.yml"
                            + " and policy.explosives-enabled / policy.wither-enabled.");
                    say(sender, "Note: the blasts destroy no blocks unless you also enable"
                            + " policy.griefing-enabled and wither-cannon.blocks-damage.");
                }
                return true;
            }
            case "cancel":
                if (player == null) {
                    say(sender, "Only a player can stand the cannon down.");
                    return true;
                }
                say(sender, plugin.witherCannon().cancel(player));
                return true;
            default:
                say(sender, "Usage: /null cannon aim | fire [shots] | confirm | cancel");
                say(sender, plugin.witherCannon().describeState(player));
                return true;
        }
    }

    // ---------------------------------------------------------------------- ai

    /**
     * {@code /null ai test [id]}: one real HTTP call against an endpoint,
     * printing the status code and the model it answered with (P-07).
     */
    private boolean aiTest(CommandSender sender, String[] args) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.builder() == null) {
            say(sender, "The builder is not running.");
            return true;
        }
        String id = args.length >= 3 ? args[2] : "";
        say(sender, "Testing" + (id.isEmpty()
                ? " the configured target (ai.builder.endpoint or ChatCommander chain)"
                : " endpoint " + redglitchx.nullarmy.core.agent.EndpointConfig
                        .safeEndpointForDisplay("id:" + id)) + "...");
        plugin.builder().testEndpoint(id, line -> say(sender, line));
        return true;
    }

    /** {@code /null ai endpoints}: every configured endpoint, resolved or not. */
    private boolean aiEndpoints(CommandSender sender) {
        if (!need(sender, "nullarmy.admin")) {
            return true;
        }
        if (plugin.builder() == null) {
            say(sender, "The builder is not running.");
            return true;
        }
        for (String line : plugin.builder().describeEndpoints().split("\n")) {
            say(sender, line);
        }
        return true;
    }

    /** The player's own feet, as a Vec3d. */
    private Vec3d here(Player player) {
        Location at = player.getLocation();
        return new Vec3d(at.getX(), at.getY(), at.getZ());
    }

    /** A point {@code blocks} ahead of the player, along where he is looking. */
    private Vec3d ahead(Player player, int blocks) {
        Location at = player.getLocation();
        double yaw = Math.toRadians(at.getYaw());
        // Minecraft yaw: 0 is south (+Z), and it grows towards west (-X).
        double dx = -Math.sin(yaw) * blocks;
        double dz = Math.cos(yaw) * blocks;
        return new Vec3d(at.getX() + dx, at.getY(), at.getZ() + dz);
    }

    /** Resolves an order point: 'here' or '<x> <y> <z>' or a player name. */
    private Vec3d pointOf(CommandSender sender, Player player, String[] rest) {
        Object where = place(sender, rest);
        if (where instanceof Entity) {
            Location at = ((Entity) where).getLocation();
            return new Vec3d(at.getX(), at.getY(), at.getZ());
        }
        if (where instanceof Vec3d) {
            return (Vec3d) where;
        }
        return player == null ? null : here(player);
    }

    /** Two patrol points from 'here to <x> <y> <z>' or '<a> to <b>'. */
    private Vec3d[] patrolPoints(Player player, String[] rest) {
        if (player == null || rest.length == 0) {
            return null;
        }
        int split = -1;
        for (int i = 0; i < rest.length; i++) {
            if (rest[i].equalsIgnoreCase("to")) {
                split = i;
                break;
            }
        }
        Vec3d a = here(player);
        Vec3d b = null;
        if (split < 0) {
            b = ahead(player, 16);
        } else {
            String[] first = Arrays.copyOfRange(rest, 0, split);
            String[] second = Arrays.copyOfRange(rest, split + 1, rest.length);
            Object pointA = place(player, first);
            Object pointB = place(player, second);
            if (pointA instanceof Entity) {
                Location at = ((Entity) pointA).getLocation();
                a = new Vec3d(at.getX(), at.getY(), at.getZ());
            } else if (pointA instanceof Vec3d) {
                a = (Vec3d) pointA;
            }
            if (pointB instanceof Entity) {
                Location at = ((Entity) pointB).getLocation();
                b = new Vec3d(at.getX(), at.getY(), at.getZ());
            } else if (pointB instanceof Vec3d) {
                b = (Vec3d) pointB;
            }
        }
        if (b == null) {
            return null;
        }
        return new Vec3d[] {a, b};
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
            return Arrays.asList("build", "stop", "test", "endpoints");
        }
        if (sub.equals("ai") && args.length == 3 && args[1].equalsIgnoreCase("test")) {
            if (plugin.pluginConfig() == null) {
                return Collections.emptyList();
            }
            List<String> ids = new ArrayList<>(plugin.pluginConfig().endpoints().keySet());
            ids.addAll(plugin.pluginConfig().endpointProblems().keySet());
            return ids;
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
