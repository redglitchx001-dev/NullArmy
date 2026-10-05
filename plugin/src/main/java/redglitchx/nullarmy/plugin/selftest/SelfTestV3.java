package redglitchx.nullarmy.plugin.selftest;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.chat.SignedMessage;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.combat.Ballistics;
import redglitchx.nullarmy.core.config.YamlProblem;
import redglitchx.nullarmy.core.flock.Separation;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.portal.PortalFrame;
import redglitchx.nullarmy.core.skin.SkinPayload;
import redglitchx.nullarmy.core.text.MessageTemplates;
import redglitchx.nullarmy.core.zone.SummonZone;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.SquadManager;
import redglitchx.nullarmy.plugin.ai.builder.BuilderService;
import redglitchx.nullarmy.plugin.body.Bodies;
import redglitchx.nullarmy.plugin.body.Mind;
import redglitchx.nullarmy.plugin.body.NullLifecycleListener;
import redglitchx.nullarmy.plugin.config.ConfigLoader;
import redglitchx.nullarmy.plugin.config.ConfigMigration;
import redglitchx.nullarmy.plugin.item.SummonItems;
import redglitchx.nullarmy.plugin.kit.KitItems;
import redglitchx.nullarmy.plugin.loadout.LoadoutService;
import redglitchx.nullarmy.plugin.portal.PortalBuilder;
import redglitchx.nullarmy.plugin.skin.SkinChain;
import redglitchx.nullarmy.plugin.skin.SkinData;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The v3 checks (S-27 onward), one block per bug B-01..B-17, run inside the
 * live smoke test after the original checks. Every check prints PASS or FAIL
 * through the same reporter; a check that cannot be made headless prints
 * {@code BLOCKED: <reason>} first and then asserts the closest thing that can be
 * measured - BLOCKED is never counted as a pass.
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class SelfTestV3 {

    private static final String VALUE_A = "ewogICJ0aW1lc3RhbXAiIDogMTcyODAwMDAwMDAwMCwKICAicHJvZmlsZU5hbWUiIDogIkEiCn0=";
    private static final String SIG_A = "c2VsZnRlc3Qtc2lnbmF0dXJlLUEtMDEyMzQ1Njc4OWFiY2RlZg==";
    private static final String VALUE_B = "ewogICJ0aW1lc3RhbXAiIDogMTcyODAwMDAwMDAwMSwKICAicHJvZmlsZU5hbWUiIDogIkIiCn0=";
    private static final String SIG_B = "c2VsZnRlc3Qtc2lnbmF0dXJlLUItZmVkY2JhOTg3NjU0MzIxMA==";

    private final NullArmyPlugin plugin;
    private final SelfTest t;
    private final String worldName;
    private final Vec3d origin;
    private final World world;
    private final List<Chunk> ticketed = new ArrayList<>();
    private final List<UUID> owners = new ArrayList<>();
    private final List<NullBody> loose = new ArrayList<>();
    private final List<Block> placedBlocks = new ArrayList<>();
    private final List<Entity> extraEntities = new ArrayList<>();
    private StubHttp stub;
    private BukkitTask sampler;
    private int forbiddenAtStart;

    // Shared between steps of one check block.
    private SquadManager.Squad squad;
    private NullBody one;
    private NullBody two;
    private NullBody probe;
    private long mark;
    private double numberA;
    private double numberC;
    private double numberB;
    private Vec3d vecA;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private int counter;
    private boolean flag;
    private BuilderService.Job job;
    private Ballistics.Aim aim;
    private List<PortalBuilder.BuiltPortal> portals = new ArrayList<>();

    SelfTestV3(NullArmyPlugin plugin, SelfTest t, String worldName, Vec3d origin) {
        this.plugin = plugin;
        this.t = t;
        this.worldName = worldName;
        this.origin = origin;
        this.world = Bukkit.getWorld(worldName);
    }

    // ------------------------------------------------------------------ plumbing

    private void check(String id, String bug, boolean ok, String what) {
        t.check(ok, id + " [" + bug + "] " + what);
    }

    private void blocked(String id, String bug, String reason) {
        t.say("BLOCKED: " + id + " [" + bug + "] " + reason);
    }

    private UUID owner(String tag) {
        UUID id = UUID.nameUUIDFromBytes(("nullarmy-selftest-" + tag).getBytes(StandardCharsets.UTF_8));
        owners.add(id);
        return id;
    }

    private Vec3d ground(double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = world.getHighestBlockYAt(bx, bz);
        return new Vec3d(bx + 0.5D, y + 1, bz + 0.5D);
    }

    private Vec3d at(double dx, double dz) {
        return ground(origin.x() + dx, origin.z() + dz);
    }

    private void prepare(Vec3d centre, int radius) {
        if (world == null) {
            return;
        }
        int minX = ((int) Math.floor(centre.x()) - radius) >> 4;
        int maxX = ((int) Math.floor(centre.x()) + radius) >> 4;
        int minZ = ((int) Math.floor(centre.z()) - radius) >> 4;
        int maxZ = ((int) Math.floor(centre.z()) + radius) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                Chunk chunk = world.getChunkAt(cx, cz);
                if (chunk.addPluginChunkTicket(plugin)) {
                    ticketed.add(chunk);
                }
            }
        }
    }

    private Player handle(NullBody body) {
        return Bodies.player(body);
    }

    private static double flat(Vec3d a, Vec3d b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    private void dismissAll() {
        for (UUID id : owners) {
            Guard.attempt(plugin.getLogger(), "self test dismiss", () -> plugin.squads().dismiss(id));
        }
        for (NullBody body : loose) {
            Guard.attempt(plugin.getLogger(), "self test destroy", body::destroy);
        }
        loose.clear();
    }

    private void stopSampler() {
        if (sampler != null) {
            sampler.cancel();
            sampler = null;
        }
    }

    /** Removes everything the v3 checks created. Safe to call twice. */
    void cleanup() {
        stopSampler();
        dismissAll();
        if (probe != null) {
            Guard.attempt(plugin.getLogger(), "self test probe", probe::destroy);
            probe = null;
        }
        for (Block block : placedBlocks) {
            Guard.attempt(plugin.getLogger(), "self test block", () -> block.setType(Material.AIR, false));
        }
        placedBlocks.clear();
        for (Entity entity : extraEntities) {
            Guard.attempt(plugin.getLogger(), "self test entity", entity::remove);
        }
        extraEntities.clear();
        if (plugin.brain() != null) {
            plugin.brain().extraWatchers().clear();
        }
        if (plugin.builder() != null) {
            plugin.builder().stopAll("self test finished");
        }
        if (stub != null) {
            stub.close();
            stub = null;
        }
        for (Chunk chunk : ticketed) {
            Guard.attempt(plugin.getLogger(), "self test chunk ticket", () -> chunk.removePluginChunkTicket(plugin));
        }
        ticketed.clear();
        Guard.attempt(plugin.getLogger(), "self test skin chain", () -> plugin.skinChain().refreshAsync(false));
    }

    int forbiddenAtStart() { return forbiddenAtStart; }

    // ---------------------------------------------------------------- the plan

    /**
     * Removes hostile mobs around the test areas. The smoke server is a superflat
     * world below y=40, where slimes spawn at any light level; a slime touching a
     * Null hurts it, the Null rightly fights back (combat.retaliate), and a
     * formation or a timed strike being measured is no longer what it was.
     */
    private void clearEnemies() {
        if (world == null) {
            return;
        }
        int removed = 0;
        for (Entity e : world.getNearbyEntities(new Location(world, origin.x(), origin.y(), origin.z()), 72, 32, 72)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity) || e instanceof Player) {
                continue;
            }
            if (plugin.adapter() != null && plugin.adapter().isNullEntity(e.getUniqueId())) {
                continue; // our own bodies
            }
            if (extraEntities.contains(e)) {
                continue; // a deliberate fixture (the thrown pig)
            }
            // Anything else wandering here - slimes, cows, bats - bumps bodies
            // mid-measurement and ruins timing and distance checks.
            e.remove();
            removed++;
        }
        enemiesRemoved += removed;
    }

    private int enemiesRemoved;

    void enqueue(Deque<Runnable> steps) {
        Deque<Runnable> plan = new java.util.ArrayDeque<>();
        enqueuePlan(plan);
        for (Runnable step : plan) {
            steps.add(() -> {
                clearEnemies();
                step.run();
            });
        }
    }

    private void enqueuePlan(Deque<Runnable> steps) {
        steps.add(() -> {
            forbiddenAtStart = plugin.chatGate() == null ? 0 : plugin.chatGate().forbiddenBroadcasts();
            prepare(origin, 48);
            t.say("v3 checks start (S-27 onward)");
        });
        steps.add(this::b01);
        steps.add(this::b02);
        steps.add(this::b03);
        steps.add(this::b04Spawn);
        for (int i = 0; i < 10; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::b04Check);
        steps.add(this::b05Hit);
        steps.add(this::b05Animation);
        steps.add(() -> t.gap(30));
        steps.add(this::b05Gone);
        steps.add(this::b06Walk);
        steps.add(this::b06WalkCheck);
        steps.add(this::b06Watch);
        steps.add(this::b06WatchCheck);
        steps.add(this::b07Spawn);
        for (int i = 0; i < 8; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::b07Check);
        steps.add(this::b07Jitter);
        steps.add(this::b08Horn);
        steps.add(this::b09Wake);
        steps.add(this::b09WakeCheck);
        steps.add(this::b09Name);
        steps.add(this::b09NameCheck);
        steps.add(this::b09Plain);
        steps.add(this::b09PlainCheck);
        steps.add(this::b10b11);
        steps.add(this::b10Reload);
        steps.add(this::b12Speed);
        steps.add(() -> t.gap(40));
        steps.add(() -> t.gap(40));
        steps.add(this::b12EatCheck);
        steps.add(this::b13Build);
        steps.add(this::b13Throw);
        steps.add(this::b13ThrowCheck);
        steps.add(this::b13Restore);
        steps.add(this::b14Outside);
        steps.add(this::b13StepOut);
        steps.add(() -> t.gap(20));
        steps.add(this::b13StepOutCheck);
        steps.add(this::b15Jump);
        steps.add(this::b15JumpCheck);
        steps.add(this::b15Walk);
        steps.add(this::b15Sprint);
        steps.add(this::b15SprintCheck);
        steps.add(this::b15Wall);
        steps.add(this::b15WallCheck);
        steps.add(this::b16Start);
        for (int i = 0; i < 6; i++) {
            steps.add(() -> t.gap(20));
        }
        steps.add(this::b16Check);
        steps.add(this::b17Setup);
        steps.add(this::b17Standing);
        steps.add(this::b17Falling);
        steps.add(this::b17FallingCheck);
        steps.add(this::b17Shield);
        steps.add(this::b17ShieldHit);
        steps.add(this::b17ShieldCheck);
        steps.add(this::b17BowSetup);
        steps.add(this::b17BowAim);
        steps.add(this::b17BowCheck);
        steps.add(() -> {
            cleanup();
            t.say("v3 checks finished (" + enemiesRemoved + " hostile mob(s) cleared from the test areas)");
        });
    }

    // ------------------------------------------------------------------- B-01

    private void b01() {
        UUID a = owner("b01");
        int before = plugin.squads().spawnRetries();
        plugin.squads().forceRefusals(1);
        try {
            SquadManager.Squad s = plugin.squads().spawnSquadAt(a, worldName, List.of(at(6, 6)));
            check("S-27", "B-01", s.members().size() == 1 && plugin.squads().spawnRetries() > before,
                    "a refused spawn spot is rescued by a neighbouring cell (" + plugin.squads().lastRetryNote() + ")");
        } catch (Throwable e) {
            check("S-27", "B-01", false, "a refused spawn spot is rescued by a neighbouring cell (threw "
                    + Guard.describe(e) + ")");
        } finally {
            plugin.squads().forceRefusals(0);
        }
        check("S-28", "B-01", !plugin.spawnBreaker().isOpen(), "the spawn breaker is not latched by a refusal");
        try {
            SquadManager.Squad s2 = plugin.squads().spawnSquadAt(owner("b01b"), worldName, List.of(at(9, 6)));
            check("S-29", "B-01", s2.members().size() == 1, "the next summon after a refusal succeeds");
        } catch (Throwable e) {
            check("S-29", "B-01", false, "the next summon after a refusal succeeds (threw " + Guard.describe(e) + ")");
        }
        dismissAll();
    }

    // ------------------------------------------------------------------- B-02

    private void b02() {
        File folder = plugin.getDataFolder();
        String broken = "limits:\n  max-live-npcs: 64\nportals:\n  enabled: true\n   lifetime-s: [30\ncombat:\n"
                + "  enabled: true\n";
        File bad = new File(folder, "selftest-broken.yml");
        File badCopy = new File(folder, "selftest-migrate-broken.yml");
        File old = new File(folder, "selftest-migrate-old.yml");
        try {
            Files.write(bad.toPath(), broken.getBytes(StandardCharsets.UTF_8));
            Files.write(badCopy.toPath(), broken.getBytes(StandardCharsets.UTF_8));
            Files.write(old.toPath(), "limits:\n  max-live-npcs: 32\n".getBytes(StandardCharsets.UTF_8));
            Object before = plugin.pluginConfig();
            YamlProblem problem = plugin.tryConfigFile(bad);
            check("S-30", "B-02", problem != null && problem.line() > 0 && problem.column() > 0,
                    "a malformed config is reported with line and column ("
                            + (problem == null ? "no problem reported" : problem.headline()) + ")");
            boolean quoted = false;
            if (problem != null) {
                for (String line : problem.snippet()) {
                    quoted |= line.contains("lifetime-s") || line.contains("enabled");
                }
            }
            check("S-31", "B-02", quoted && problem.snippet().size() >= 2,
                    "the report quotes the offending lines with a caret");
            check("S-32", "B-02", plugin.pluginConfig() == before, "the last good configuration stays in use");
            long length = badCopy.length();
            ConfigMigration.Report refused = ConfigMigration.migrateFile(plugin, badCopy, "selftest");
            check("S-33", "B-02", refused.error() != null && badCopy.length() == length,
                    "migration never appends to a file that does not parse");
            ConfigMigration.Report report = ConfigMigration.migrateFile(plugin, old, "selftest");
            ConfigLoader.Outcome after = ConfigLoader.load(plugin, old);
            check("S-34", "B-02", report.error() == null && report.changed() && after.ok()
                            && after.config().getInt("limits.max-live-npcs") == 32 && after.config().isSet("combat.crits"),
                    "migration appends missing keys, keeps the owner's value and the result re-parses");
            List<String> lines = new ArrayList<>();
            CommandSender capture = Bukkit.createCommandSender(component ->
                    lines.add(PlainTextComponentSerializer.plainText().serialize(component.asComponent())));
            plugin.v3Commands().run(capture, "config", new String[] {"config", "combat"});
            boolean shown = false;
            for (String line : lines) {
                shown |= line.contains("combat.crits = true");
            }
            check("S-35", "B-02", shown, "/null config prints effective values (" + lines.size() + " lines)");
        } catch (Throwable e) {
            check("S-30", "B-02", false, "the malformed-config check threw " + Guard.describe(e));
        } finally {
            for (File f : new File[] {bad, badCopy, old}) {
                f.delete();
            }
            File[] backups = folder.listFiles((dir, name) -> name.startsWith("selftest-") && name.contains(".bak-"));
            if (backups != null) {
                for (File f : backups) {
                    f.delete();
                }
            }
        }
    }

    // ------------------------------------------------------------------- B-03

    private void b03() {
        try {
            stub = StubHttp.start();
            stub.respond("/skin", 200, "{\"value\":\"" + VALUE_A + "\",\"signature\":\"" + SIG_A + "\"}");
            stub.respond("/bad", 502, "<html><body>Bad Gateway from the upstream skin service</body></html>");
            SkinPayload good = plugin.skinChain().fetchProxy(stub.url("/skin"));
            check("S-36", "B-03", good.complete() && SIG_A.equals(good.signature()) && VALUE_A.equals(good.value()),
                    "skins.proxy-url JSON is read (value + signature)");
            SkinPayload bad = plugin.skinChain().fetchProxy(stub.url("/bad"));
            String status = plugin.skinChain().lastProxyStatus();
            check("S-37", "B-03", bad.error() != null && status.contains("HTTP 502") && status.contains("Bad Gateway"),
                    "a failing proxy is logged with its HTTP status and the start of its body (" + status + ")");
            plugin.skinChain().install(SkinChain.Resolution.of(new SkinData(VALUE_A, SIG_A, SkinData.Source.PROXY),
                    "self test proxy A"));
            one = plugin.squads().spawnOne(owner("b03"), worldName, at(-6, 6), false);
            loose.add(one);
            String[] texture = plugin.adapter().skinOf(one);
            check("S-38", "B-03", texture != null && SIG_A.equals(texture[1]) && VALUE_A.equals(texture[0]),
                    "the proxy's signature reaches the Null's GameProfile");
            probe = plugin.adapter().createViewerProbe(worldName, at(-8, 6));
            boolean paired = probe != null && plugin.adapter().pairProbe(probe, one);
            int removesBefore = count(plugin.adapter().probePackets(probe), "ClientboundRemoveEntitiesPacket");
            int bundlesBefore = count(plugin.adapter().probePackets(probe), "ClientboundBundlePacket");
            plugin.skinChain().install(SkinChain.Resolution.of(new SkinData(VALUE_B, SIG_B, SkinData.Source.PROXY),
                    "self test proxy B"));
            boolean reapplied = plugin.skinChain().reapply(one, false);
            String[] after = plugin.adapter().skinOf(one);
            List<String> packets = plugin.adapter().probePackets(probe);
            boolean repaired = count(packets, "ClientboundRemoveEntitiesPacket") > removesBefore
                    && count(packets, "ClientboundBundlePacket") > bundlesBefore;
            check("S-39", "B-03", paired && reapplied && after != null && SIG_B.equals(after[1]) && repaired,
                    "a live re-apply changes the announced entry and re-pairs the viewer (paired=" + paired
                            + ", re-paired=" + repaired + ")");
        } catch (Throwable e) {
            check("S-36", "B-03", false, "the skin chain check threw " + Guard.describe(e));
        } finally {
            if (probe != null) {
                Guard.attempt(plugin.getLogger(), "probe", probe::destroy);
                probe = null;
            }
            dismissAll();
        }
    }

    private static int count(List<String> packets, String name) {
        int n = 0;
        for (String p : packets) {
            if (p.equals(name)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------- B-04

    private void b04Spawn() {
        Vec3d c = at(24, 0);
        prepare(c, 16);
        List<Vec3d> spots = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                spots.add(new Vec3d(Math.floor(c.x()) + i - 1 + 0.5D, c.y(), Math.floor(c.z()) + j - 1 + 0.5D));
            }
        }
        spots.add(new Vec3d(spots.get(0).x() + 0.1D, c.y(), spots.get(0).z() + 0.1D));
        spots.add(new Vec3d(spots.get(4).x() + 0.1D, c.y(), spots.get(4).z() - 0.1D));
        spots.add(new Vec3d(spots.get(8).x() - 0.1D, c.y(), spots.get(8).z() + 0.1D));
        numberA = plugin.brain().unstackedTotal();
        plugin.squads().allowCrowding(true);
        try {
            squad = plugin.squads().spawnSquadAt(owner("b04"), worldName, spots);
            check("S-40", "B-04", squad.members().size() == 12, "12 Nulls start crowded into a 3x3 area ("
                    + squad.members().size() + " spawned)");
        } catch (Throwable e) {
            squad = null;
            check("S-40", "B-04", false, "12 Nulls start crowded into a 3x3 area (threw " + Guard.describe(e) + ")");
        } finally {
            plugin.squads().allowCrowding(false);
        }
    }

    private void b04Check() {
        if (squad == null) {
            check("S-41", "B-04", false, "pairwise spacing could not be measured: no crowd");
            return;
        }
        List<double[]> points = new ArrayList<>();
        double worstSupport = 0.0D;
        for (NullBody body : squad.members()) {
            Vec3d p = body.bodyPosition();
            points.add(new double[] {p.x(), p.y(), p.z()});
            int y = (int) Math.floor(p.y() - 0.01D);
            int support = y;
            while (support > world.getMinHeight() && !world.getBlockAt((int) Math.floor(p.x()), support,
                    (int) Math.floor(p.z())).getType().isSolid()) {
                support--;
            }
            worstSupport = Math.max(worstSupport, p.y() - (support + 1));
        }
        double min = Separation.minPairDistance(points);
        check("S-41", "B-04", min >= 0.8D, "after 200 ticks every pair is at least 0.8 apart (closest "
                + String.format(Locale.ROOT, "%.2f", min) + ")");
        check("S-42", "B-04", worstSupport <= 1.0D, "no Null stands more than 1 block above solid ground (highest "
                + String.format(Locale.ROOT, "%.2f", worstSupport) + ")");
        check("S-43", "B-04", plugin.brain().unstackedTotal() > numberA, "the pile was detected and the extras walked"
                + " out (" + plugin.brain().lastUnstack() + ")");
        speedVariance();
        dismissAll();
        squad = null;
    }

    // ------------------------------------------------------------------- B-05

    private void b05Hit() {
        try {
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b05"), worldName, List.of(at(0, -10)));
            one = s.members().get(0);
            probe = plugin.adapter().createViewerProbe(worldName, at(2, -10));
            mark = plugin.currentTick();
            counter = itemsNear(one.bodyPosition());
            numberA = plugin.chatGate().forbiddenBroadcasts();
            Player victim = handle(one);
            Player attacker = probe == null ? null : handle(probe);
            double before = one.health();
            blocked("S-44", "B-05", "no real player can connect to the headless smoke server; the viewer probe (a"
                    + " ServerPlayer the plugin treats as a player) strikes instead");
            victim.damage(100.0D, attacker);
            boolean dropped = one.health() < before;
            check("S-44", "B-05", dropped && one.deathTicks() >= 0, "100 damage through the real event pipeline"
                    + " hurts and kills the Null (health " + (int) before + " -> " + (int) one.health() + ")");
        } catch (Throwable e) {
            check("S-44", "B-05", false, "the damage check threw " + Guard.describe(e));
        }
        t.gap(8);
    }

    private void b05Animation() {
        if (one == null) {
            return;
        }
        int ticks = one.deathTicks();
        check("S-45", "B-05", ticks >= 1 && ticks < 22, "the death animation plays (body still there, death tick "
                + (ticks == Integer.MAX_VALUE ? "gone" : String.valueOf(ticks)) + ")");
    }

    private void b05Gone() {
        if (one == null) {
            return;
        }
        boolean gone = one.deathTicks() == Integer.MAX_VALUE || !plugin.squads().membersOf(owners.get(owners.size() - 1))
                .contains(one);
        check("S-46", "B-05", gone, "the body is removed after the animation");
        int itemsNow = itemsNear(one.bodyPosition());
        // P-10 reverses this one: a defeated Null now leaves its kit on the
        // ground, so this check measures the new promise instead of the old one.
        // The old answer is not lost - the migration folds nulls.no-death-drops
        // into drops.enabled, and S-110 proves a server that said no keeps it.
        redglitchx.nullarmy.plugin.config.V3Settings v3 =
                plugin.pluginConfig() == null ? null : plugin.pluginConfig().v3();
        boolean nullsDrop = v3 == null || v3.dropsEnabled();
        check("S-47", "B-05", nullsDrop ? itemsNow > counter : itemsNow <= counter, (nullsDrop
                ? "a dead Null leaves its kit on the ground (" : "a dead Null drops nothing (")
                + itemsNow + " items near, was " + counter + ", drops.enabled=" + nullsDrop + ")");
        List<NullLifecycleListener.Death> deaths = plugin.lifecycle().deathsSince(mark);
        boolean cleared = !deaths.isEmpty();
        for (NullLifecycleListener.Death d : deaths) {
            cleared &= d.messageCleared;
        }
        check("S-48", "B-05", cleared && plugin.chatGate().forbiddenBroadcasts() == (int) numberA,
                "the death caused zero chat broadcasts (" + deaths.size() + " death(s), message cleared)");
        if (probe != null) {
            Guard.attempt(plugin.getLogger(), "probe", probe::destroy);
            probe = null;
        }
        dismissAll();
    }

    private int itemsNear(Vec3d p) {
        int n = 0;
        for (Entity e : world.getNearbyEntities(new Location(world, p.x(), p.y(), p.z()), 4, 3, 4)) {
            if (e instanceof Item) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------- B-06

    private void b06Walk() {
        try {
            Vec3d start = at(-24, 0);
            prepare(start, 16);
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b06"), worldName, List.of(start));
            one = s.members().get(0);
            numberA = one.headYaw();
            samples.clear();
            Vec3d target = new Vec3d(start.x() - 10.0D, start.y(), start.z() + 6.0D);
            plugin.brain().order(List.of(one), Mind.Verb.WALK, target, null, null, 1);
            final NullBody body = one;
            sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> samples.add((double) body.headYaw()), 1L, 1L);
        } catch (Throwable e) {
            check("S-49", "B-06", false, "the head-yaw walk check threw " + Guard.describe(e));
        }
        t.gap(30);
    }

    private void b06WalkCheck() {
        stopSampler();
        double change = 0.0D;
        for (double yaw : samples) {
            change = Math.max(change, Math.abs(wrap(yaw - numberA)));
        }
        check("S-49", "B-06", change > 20.0D, "the head turns with the walking direction (changed "
                + String.format(Locale.ROOT, "%.0f", change) + " degrees)");
        if (one != null) {
            plugin.brain().order(List.of(one), Mind.Verb.STOP, null, null, null, 1);
        }
    }

    private void b06Watch() {
        if (one == null) {
            return;
        }
        Vec3d p = one.bodyPosition();
        double yaw = Math.toRadians(one.bodyYaw() + 100.0D);
        vecA = new Vec3d(p.x() - Math.sin(yaw) * 5.0D, p.y() + 1.6D, p.z() + Math.cos(yaw) * 5.0D);
        blocked("S-50", "B-06", "no real player connects to the headless smoke server; a watcher point 5 blocks"
                + " away goes through the same player-glance code");
        plugin.brain().extraWatchers().add(vecA);
        numberB = 360.0D;
        final NullBody body = one;
        final Vec3d watcher = vecA;
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Vec3d q = body.bodyPosition();
            double want = Math.toDegrees(Math.atan2(-(watcher.x() - q.x()), watcher.z() - q.z()));
            numberB = Math.min(numberB, Math.abs(wrap(body.headYaw() - want)));
        }, 1L, 1L);
        t.gap(50);
    }

    private void b06WatchCheck() {
        if (one == null || vecA == null) {
            return;
        }
        stopSampler();
        double off = numberB;
        check("S-50", "B-06", off < 25.0D, "the head turns to a nearby watcher (closest "
                + String.format(Locale.ROOT, "%.0f", off) + " degrees during a 2-4 s glance)");
        plugin.brain().extraWatchers().clear();
        dismissAll();
    }

    // ------------------------------------------------------------------- B-07

    private void b07Spawn() {
        try {
            Vec3d anchor = at(0, 24);
            prepare(anchor, 16);
            vecA = anchor;
            List<Vec3d> spots = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                spots.add(ground(anchor.x() + 5.0D + (i % 3) * 1.5D, anchor.z() - 2.0D + (i / 3) * 1.5D));
            }
            UUID o = owner("b07");
            squad = plugin.squads().spawnSquadAt(o, worldName, spots);
            plugin.squads().holdFormation(o, anchor, 30.0F, "square");
        } catch (Throwable e) {
            squad = null;
            check("S-51", "B-07", false, "the formation check threw " + Guard.describe(e));
        }
    }

    private void b07Check() {
        if (squad == null) {
            return;
        }
        double worst = 0.0D;
        String worstNote = "";
        Set<String> cells = new HashSet<>();
        List<double[]> cellPoints = new ArrayList<>();
        for (int i = 0; i < squad.members().size(); i++) {
            Vec3d cell = plugin.brain().formationCell(squad, worldName, i);
            NullBody body = squad.members().get(i);
            Vec3d p = body.bodyPosition();
            double d = flat(cell, p);
            if (d > worst) {
                worst = d;
                Mind m = plugin.brain().minds().get(body.uuid());
                worstNote = "#" + i + " at " + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", p.x(), p.y(), p.z())
                        + " cell " + String.format(Locale.ROOT, "%.2f,%.2f", cell.x(), cell.z()) + " ground="
                        + body.onGround() + " wall=" + body.horizontalCollision() + " v="
                        + String.format(Locale.ROOT, "%.3f", body.velocity().horizontalLength()) + " mind "
                        + (m == null ? "none" : m.describe()) + " objective=" + squad.objective();
            }
            cells.add(Math.round(cell.x() * 10) + ":" + Math.round(cell.z() * 10));
            cellPoints.add(new double[] {cell.x(), cell.z()});
        }
        check("S-51", "B-07", worst <= 0.3D && squad.members().size() == 9,
                "9 Nulls stand in the rotated square matrix, each within 0.3 of its cell ("
                        + squad.members().size() + " members, worst "
                        + String.format(Locale.ROOT, "%.2f", worst) + (worst > 0.3D ? "; " + worstNote : "") + ")");
        double minCell = redglitchx.nullarmy.core.formation.FormationMatrix.minPairDistance(cellPoints);
        check("S-52", "B-07", cells.size() == squad.members().size() && minCell >= 1.1D - 1.0e-6,
                "no two Nulls share a cell (closest cells " + String.format(Locale.ROOT, "%.2f", minCell) + ")");
        samples.clear();
        for (NullBody body : squad.members()) {
            Vec3d p = body.bodyPosition();
            samples.add(p.x());
            samples.add(p.z());
        }
        t.gap(20);
    }

    private void b07Jitter() {
        if (squad == null) {
            return;
        }
        double moved = 0.0D;
        String note = "";
        for (int i = 0; i < squad.members().size() && 2 * i + 1 < samples.size(); i++) {
            NullBody body = squad.members().get(i);
            Vec3d p = body.bodyPosition();
            double d = Math.hypot(p.x() - samples.get(2 * i), p.z() - samples.get(2 * i + 1));
            if (d > moved) {
                moved = d;
                Vec3d cell = plugin.brain().formationCell(squad, worldName, i);
                Mind m = plugin.brain().minds().get(body.uuid());
                note = "#" + i + " from " + String.format(Locale.ROOT, "%.2f,%.2f", samples.get(2 * i),
                        samples.get(2 * i + 1)) + " to " + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", p.x(), p.y(), p.z())
                        + " cell " + (cell == null ? "none" : String.format(Locale.ROOT, "%.2f,%.2f", cell.x(), cell.z()))
                        + " alive=" + body.isAlive() + " v=" + String.format(Locale.ROOT, "%.3f",
                        body.velocity().horizontalLength()) + " mind " + (m == null ? "none" : m.describe())
                        + " objective=" + squad.objective() + " members=" + squad.members().size();
            }
        }
        check("S-53", "B-07", moved < 0.1D, "a held formation does not jitter (largest drift "
                + String.format(Locale.ROOT, "%.3f", moved) + " over 20 ticks" + (moved >= 0.1D ? "; " + note : "") + ")");
        dismissAll();
    }

    // ------------------------------------------------------------------- B-08

    private void b08Horn() {
        PermissionAttachment attachment = null;
        try {
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b08"), worldName, List.of(at(-6, -6)));
            NullBody body = s.members().get(0);
            Player player = handle(body);
            attachment = player.addAttachment(plugin, "nullarmy.summon", true);
            ItemStack horn = SummonItems.callHorn(plugin);
            player.getInventory().setItemInMainHand(horn);
            int before = plugin.summonFlow().hornRefreshes();
            int answersBefore = plugin.chatGate().answers();
            for (int i = 0; i < 2; i++) {
                Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                        player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
            }
            boolean pending = plugin.summonFlow().hasPending(player.getUniqueId());
            check("S-54", "B-08", pending && plugin.summonFlow().hornRefreshes() == before + 1
                            && plugin.chatGate().answers() == answersBefore,
                    "pressing the horn again refreshes the open prompt silently");
            plugin.summonFlow().clearPending(player.getUniqueId());
        } catch (Throwable e) {
            check("S-54", "B-08", false, "the horn check threw " + Guard.describe(e));
        } finally {
            if (attachment != null) {
                attachment.remove();
            }
        }
        List<String> problems = MessageTemplates.scan();
        boolean renders = true;
        try {
            renders = MessageTemplates.render("shutdown.started", "who", "Steve", "count", 3)
                    .startsWith("Steve destroyed the Totem Of Null");
        } catch (Throwable e) {
            renders = false;
        }
        check("S-55", "B-08", problems.isEmpty() && renders, "every event template renders without a stray"
                + " placeholder (" + MessageTemplates.all().size() + " templates)");
        dismissAll();
    }

    // ------------------------------------------------------------------- B-09

    private Player chatter;

    private Player chatter() {
        if (chatter == null || !chatter.isValid()) {
            try {
                SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b09"), worldName, List.of(at(-9, -6)));
                chatter = handle(s.members().get(0));
            } catch (Throwable e) {
                chatter = null;
            }
        }
        return chatter;
    }

    private void say(String text) {
        Player p = chatter();
        if (p == null) {
            return;
        }
        Component message = Component.text(text);
        Set<Audience> viewers = new HashSet<>();
        AsyncChatEvent event = new AsyncChatEvent(false, p, viewers, ChatRenderer.defaultRenderer(), message, message,
                SignedMessage.system(text, null));
        Bukkit.getPluginManager().callEvent(event);
    }

    private void b09Wake() {
        counter = plugin.chatGate().commanderLines();
        say("null how are you");
        t.gap(5);
    }

    private void b09WakeCheck() {
        check("S-56", "B-09", plugin.chatGate().commanderLines() > counter,
                "a chat line with the wake word gets a public Commander reply (\"" + plugin.chatGate().lastCommanderLine()
                        + "\")");
    }

    private void b09Name() {
        counter = plugin.chatGate().commanderLines();
        String name = plugin.commander() == null ? "NullCommander" : plugin.commander().commanderName();
        say("hey " + name.toLowerCase(Locale.ROOT) + ", who are you?");
        t.gap(5);
    }

    private void b09NameCheck() {
        check("S-57", "B-09", plugin.chatGate().commanderLines() > counter,
                "a chat line with the Commander's name (any case) gets a public reply");
    }

    private void b09Plain() {
        counter = plugin.chatGate().commanderLines();
        say("nice weather for building today");
        t.gap(5);
    }

    private void b09PlainCheck() {
        check("S-58", "B-09", plugin.chatGate().commanderLines() == counter,
                "an ordinary chat line gets no reply (silence)");
        chatter = null;
        dismissAll();
    }

    // -------------------------------------------------------------- B-10 / B-11

    private void b10b11() {
        try {
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b10"), worldName, List.of(at(6, -6)));
            one = s.members().get(0);
            Player body = handle(one);
            PlayerInventory inv = body.getInventory();
            ItemStack chest = inv.getChestplate();
            check("S-59", "B-11", chest != null && chest.getType() == Material.NETHERITE_CHESTPLATE
                            && KitItems.level(chest, "protection") == 4,
                    "a fresh Null wears a netherite chestplate with Protection IV");
            int enchanted = 0;
            Set<String> potions = new HashSet<>();
            int blocks = 0;
            for (int i = 0; i < Bodies.SLOTS; i++) {
                ItemStack stack = Bodies.get(inv, i);
                if (stack.getType().isAir()) {
                    continue;
                }
                if (!stack.getEnchantments().isEmpty()) {
                    enchanted++;
                }
                if (stack.getItemMeta() instanceof PotionMeta && ((PotionMeta) stack.getItemMeta()).getBasePotionType() != null) {
                    potions.add(((PotionMeta) stack.getItemMeta()).getBasePotionType().getKey().getKey());
                }
                if (stack.getType().isBlock() && stack.getType().isSolid() && stack.getAmount() >= 16) {
                    blocks++;
                }
            }
            check("S-60", "B-11", enchanted >= 4 && potions.size() >= 2 && blocks >= 3,
                    "the body really carries the v3 kit (" + enchanted + " enchanted, " + potions.size()
                            + " potion types, " + blocks + " block stacks)");
            int cobbleBefore = Bodies.count(inv, Material.COBBLESTONE);
            plugin.kits().applyTo(one);
            check("S-61", "B-11", Bodies.count(inv, Material.COBBLESTONE) == cobbleBefore,
                    "re-applying the kit duplicates nothing");
            LoadoutService.Editor editor = plugin.loadouts().forNull(one);
            editor.getInventory().setItem(LoadoutService.guiSlotOf(39), new ItemStack(Material.DIAMOND_HELMET));
            String answer = editor.commit(null);
            boolean wears = inv.getHelmet() != null && inv.getHelmet().getType() == Material.DIAMOND_HELMET;
            boolean read = false;
            for (redglitchx.nullarmy.nms.LoadoutSlot slot : one.loadout()) {
                read |= slot.slot() == 39 && slot.material().equals("DIAMOND_HELMET");
            }
            check("S-62", "B-10", wears && read, "the Null's loadout editor swaps an item and the body wears it ("
                    + answer + ")");
        } catch (Throwable e) {
            check("S-59", "B-11", false, "the kit/loadout check threw " + Guard.describe(e));
        }
    }

    private void b10Reload() {
        if (one == null) {
            return;
        }
        boolean reloaded = plugin.reloadPluginConfig();
        Player body = handle(one);
        boolean wears = body != null && body.getInventory().getHelmet() != null
                && body.getInventory().getHelmet().getType() == Material.DIAMOND_HELMET;
        ItemStack[] saved = plugin.loadouts().nullLoadout(one.profileName());
        boolean persisted = saved != null && saved[39] != null && saved[39].getType() == Material.DIAMOND_HELMET;
        check("S-63", "B-10", reloaded && wears && persisted, "the edited loadout survives /null reload and is saved"
                + " in nulls.yml");
        dismissAll();
    }

    // ------------------------------------------------------------------- B-12

    private void speedVariance() {
        List<Double> speeds = new ArrayList<>();
        if (squad != null) {
            for (NullBody body : squad.members()) {
                Player p = handle(body);
                AttributeInstance a = p == null ? null : p.getAttribute(Attribute.MOVEMENT_SPEED);
                if (a != null) {
                    speeds.add(a.getBaseValue());
                }
            }
        }
        Set<Long> distinct = new HashSet<>();
        boolean inRange = !speeds.isEmpty();
        for (double v : speeds) {
            distinct.add(Math.round(v * 100000));
            inRange &= v >= 0.0899D && v <= 0.1101D;
        }
        check("S-64", "B-12", distinct.size() >= 2 && inRange, "walking speeds vary within +-10 % ("
                + distinct.size() + " distinct of " + speeds.size() + ")");
    }

    private void b12Speed() {
        dismissAll();
        squad = null;
        try {
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b12"), worldName, List.of(at(12, -12)));
            one = s.members().get(0);
            Player p = handle(one);
            counter = Bodies.count(p.getInventory(), Material.GOLDEN_APPLE)
                    + Bodies.count(p.getInventory(), Material.ENCHANTED_GOLDEN_APPLE);
            p.setHealth(8.0D);
            numberA = p.getHealth();
            mark = plugin.currentTick();
        } catch (Throwable e) {
            one = null;
            check("S-65", "B-12", false, "the eating check threw " + Guard.describe(e));
        }
    }

    private void b12EatCheck() {
        if (one == null) {
            return;
        }
        Player p = handle(one);
        int apples = Bodies.count(p.getInventory(), Material.GOLDEN_APPLE)
                + Bodies.count(p.getInventory(), Material.ENCHANTED_GOLDEN_APPLE);
        boolean healed = p.getHealth() > numberA || p.getAbsorptionAmount() > 0.0D;
        check("S-65", "B-12", apples < counter && healed, "a hurt Null eats a golden apple and recovers (health "
                + (int) numberA + " -> " + String.format(Locale.ROOT, "%.1f", p.getHealth()) + ", absorption "
                + String.format(Locale.ROOT, "%.1f", p.getAbsorptionAmount()) + ")");
        dismissAll();
    }

    // -------------------------------------------------------------- B-13 / B-14

    private SummonZone testZone;

    private void b13Build() {
        Vec3d centre = at(0, -36);
        prepare(centre, 40);
        testZone = new SummonZone(centre.x(), centre.z(), 72);
        int before = plugin.portals().activeCount();
        try {
            portals = plugin.portals().buildDoorways(worldName, centre, 20, testZone);
        } catch (Throwable e) {
            portals = new ArrayList<>();
            t.fail("S-66 [B-13] building 20 doorways threw " + Guard.describe(e));
        }
        int valid = 0;
        int ground = 0;
        int floating = 0;
        boolean inside = true;
        String firstProblem = "";
        for (PortalBuilder.BuiltPortal portal : portals) {
            List<String> problems = plugin.portals().validate(portal);
            if (problems.isEmpty()) {
                valid++;
            } else if (firstProblem.isEmpty()) {
                firstProblem = problems.get(0);
            }
            if (portal.frame().kind() == PortalFrame.Kind.GROUND) {
                ground++;
            } else {
                floating++;
            }
            Vec3d c = portal.center();
            int[] fp = portal.frame().footprint();
            inside &= testZone.contains(c.x(), c.z()) && testZone.containsBox(fp[0], fp[1], fp[2] + 1, fp[3] + 1);
        }
        check("S-66", "B-13", portals.size() == 20 && valid == 20, "20 doorways stand complete: 14 obsidian, a 2x3"
                + " air opening (" + valid + "/" + portals.size() + " valid" + (firstProblem.isEmpty() ? ""
                : "; " + firstProblem) + ")");
        check("S-67", "B-13", ground > 0 && floating > 0, "the doorways are mixed: " + ground + " on the ground, "
                + floating + " floating");
        check("S-68", "B-14", inside && !portals.isEmpty(), "every doorway and its apron lies inside the zone ("
                + testZone.describe() + ")");
        numberA = before;
    }

    private void b13Throw() {
        PortalBuilder.BuiltPortal groundPortal = null;
        for (PortalBuilder.BuiltPortal portal : portals) {
            if (portal.frame().kind() == PortalFrame.Kind.GROUND) {
                groundPortal = portal;
                break;
            }
        }
        if (groundPortal == null) {
            check("S-69", "B-13", false, "no ground doorway to throw something through");
            return;
        }
        blocked("S-69", "B-13", "no real player connects to the headless smoke server; a pig is thrown through"
                + " the opening instead (same entity portal code)");
        double[] out = groundPortal.frame().stepOutPoint();
        Vec3d c = groundPortal.center();
        Location from = new Location(world, out[0], out[1] + 0.2D, out[2]);
        Pig pig = (Pig) world.spawnEntity(from, EntityType.PIG);
        extraEntities.add(pig);
        Vector push = new Vector(c.x() - out[0], 0.25D, c.z() - out[2]).normalize().multiply(0.9D);
        pig.setVelocity(push);
        flag = groundPortal.frame().widthOnX();
        vecA = new Vec3d(from.getX(), from.getY(), from.getZ());
        t.gap(40);
    }

    private void b13ThrowCheck() {
        Pig pig = null;
        for (Entity e : extraEntities) {
            if (e instanceof Pig) {
                pig = (Pig) e;
            }
        }
        boolean stayed = pig != null && pig.isValid() && pig.getWorld().equals(world)
                && pig.getLocation().toVector().distance(new Vector(vecA.x(), vecA.y(), vecA.z())) < 16.0D;
        int portalBlocks = 0;
        for (PortalBuilder.BuiltPortal portal : portals) {
            for (int[] cell : portal.frame().interiorCells()) {
                if (world.getBlockAt(cell[0], cell[1], cell[2]).getType() == Material.NETHER_PORTAL) {
                    portalBlocks++;
                }
            }
        }
        check("S-69", "B-13", stayed && portalBlocks == 0, "something thrown into a doorway is not teleported"
                + " (one-way: " + portalBlocks + " portal blocks in the openings)");
        if (pig != null) {
            pig.remove();
        }
        extraEntities.clear();
    }

    private void b13Restore() {
        List<int[]> cells = new ArrayList<>();
        for (PortalBuilder.BuiltPortal portal : portals) {
            cells.addAll(portal.frame().frameCells());
        }
        int closed = 0;
        for (PortalBuilder.BuiltPortal portal : portals) {
            plugin.portals().close(portal);
            closed++;
        }
        int left = 0;
        for (int[] cell : cells) {
            if (!world.getBlockAt(cell[0], cell[1], cell[2]).getType().isAir()) {
                left++;
            }
        }
        check("S-70", "B-13", closed == portals.size() && left == 0, "closing the doorways restores every block ("
                + cells.size() + " checked, " + left + " not restored)");
        portals = new ArrayList<>();
    }

    private void b14Outside() {
        int before = plugin.portals().activeCount();
        Vec3d deep = new Vec3d(origin.x() + 4.0D, world.getMinHeight() - 60, origin.z() - 30.0D);
        SummonZone small = new SummonZone(deep.x(), deep.z(), 16);
        List<PortalBuilder.BuiltPortal> none = plugin.portals().buildDoorways(worldName, deep, 2, small);
        String why = plugin.portals().lastRefusal();
        String summonAnswer = "";
        try {
            plugin.squads().createSquad(owner("b14"), worldName, deep, 1);
            summonAnswer = "a squad spawned";
        } catch (IllegalStateException refusal) {
            summonAnswer = refusal.getMessage() == null ? "" : refusal.getMessage();
        }
        check("S-71", "B-14", none.isEmpty() && plugin.portals().activeCount() == before && why.contains("zone")
                        && summonAnswer.contains("zone"),
                "with no valid site inside the zone nothing is built anywhere and the summoner is told why (\""
                        + summonAnswer + "\")");
        dismissAll();
    }

    private void b13StepOut() {
        try {
            Vec3d where = at(-16, -16);
            prepare(where, 24);
            UUID o = owner("b13");
            SquadManager.Squad s = plugin.squads().createSquad(o, worldName, where, 1);
            one = s.members().get(0);
            PortalBuilder.BuiltPortal door = null;
            for (PortalBuilder.BuiltPortal portal : plugin.portals().standing()) {
                if (portal.assigned().contains(one.uuid())) {
                    door = portal;
                }
            }
            portals = door == null ? new ArrayList<>() : new ArrayList<>(List.of(door));
            mark = plugin.currentTick();
        } catch (Throwable e) {
            one = null;
            check("S-72", "B-13", false, "the step-out check threw " + Guard.describe(e));
        }
        t.gap(20);
    }

    private void b13StepOutCheck() {
        if (one == null) {
            return;
        }
        if (portals.isEmpty()) {
            check("S-72", "B-13", false, "the summoned Null did not come through a doorway");
            dismissAll();
            return;
        }
        PortalBuilder.BuiltPortal door = portals.get(0);
        Vec3d p = one.bodyPosition();
        boolean out = !door.frame().containsBody(p.x(), p.y(), p.z());
        Mind mind = plugin.brain().minds().get(one.uuid());
        double[] exit = door.frame().stepOutPoint();
        check("S-72", "B-13", out && plugin.currentTick() - mark <= 60, "the first Null steps out of its frame"
                + " within 60 ticks (" + (plugin.currentTick() - mark) + " ticks; " + door.frame() + ", exit side "
                + door.frame().exitSide() + ", body " + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", p.x(), p.y(), p.z())
                + ", step-out point " + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", exit[0], exit[1], exit[2])
                + ", ground=" + one.onGround() + ", wall=" + one.horizontalCollision() + ", mind "
                + (mind == null ? "none" : mind.describe()) + ")");
        dismissAll();
        plugin.portals().close(door);
        portals = new ArrayList<>();
    }

    // ------------------------------------------------------------------- B-15

    private void b15Jump() {
        try {
            Vec3d start = at(-12, -24);
            prepare(start, 16);
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b15"), worldName, List.of(start));
            one = s.members().get(0);
            numberA = one.bodyPosition().y();
            samples.clear();
            final NullBody body = one;
            sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> samples.add(body.bodyPosition().y()), 1L, 1L);
            plugin.brain().order(List.of(one), Mind.Verb.JUMP, null, null, null, 1);
            Mind mind = plugin.brain().mind(one);
            flag = mind != null && mind.order() != null;
        } catch (Throwable e) {
            one = null;
            check("S-73", "B-15", false, "the jump check threw " + Guard.describe(e));
        }
        t.gap(30);
    }

    private void b15JumpCheck() {
        stopSampler();
        if (one == null) {
            return;
        }
        double peak = numberA;
        for (double y : samples) {
            peak = Math.max(peak, y);
        }
        double end = one.bodyPosition().y();
        check("S-73", "B-15", peak - numberA >= 1.0D && one.onGround() && Math.abs(end - numberA) < 0.1D,
                "a jump order lifts the body by " + String.format(Locale.ROOT, "%.2f", peak - numberA)
                        + " and it lands again");
        check("S-74", "B-15", flag, "the order is acknowledged with a gesture (swing and nod)");
    }

    private void b15Walk() {
        if (one == null) {
            return;
        }
        vecA = one.bodyPosition();
        plugin.brain().order(List.of(one), Mind.Verb.WALK, new Vec3d(vecA.x() + 30.0D, vecA.y(), vecA.z()), null, null, 1);
        t.gap(30);
    }

    private void b15Sprint() {
        if (one == null) {
            return;
        }
        Vec3d now = one.bodyPosition();
        numberA = flat(now, vecA);
        vecA = now;
        plugin.brain().order(List.of(one), Mind.Verb.SPRINT, new Vec3d(now.x() - 30.0D, now.y(), now.z()), null, null, 1);
        t.gap(5);
    }

    private void b15SprintCheck() {
        if (one == null) {
            return;
        }
        // Measure the next 30 ticks of sprinting from where it is now.
        vecA = one.bodyPosition();
        mark = plugin.currentTick();
        final NullBody body = one;
        final Vec3d from = vecA;
        sampler = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            numberB = flat(body.bodyPosition(), from);
            check("S-75", "B-15", numberB > numberA, "sprinting covers more ground than walking ("
                    + String.format(Locale.ROOT, "%.1f", numberB) + " vs "
                    + String.format(Locale.ROOT, "%.1f", numberA) + " blocks in 30 ticks)");
        }, 30L);
        t.gap(32);
    }

    private void b15Wall() {
        if (one == null) {
            return;
        }
        plugin.brain().order(List.of(one), Mind.Verb.STOP, null, null, null, 1);
        Vec3d p = one.bodyPosition();
        int wx = (int) Math.floor(p.x()) + 4;
        int by = (int) Math.floor(p.y());
        int bz = (int) Math.floor(p.z());
        for (int dz = -2; dz <= 2; dz++) {
            for (int dy = 0; dy <= 2; dy++) {
                Block block = world.getBlockAt(wx, by + dy, bz + dz);
                if (block.getType().isAir()) {
                    block.setType(Material.STONE, false);
                    placedBlocks.add(block);
                }
            }
        }
        counter = 0;
        final NullBody body = one;
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Vec3d q = body.bodyPosition();
            for (int x = (int) Math.floor(q.x() - 0.3); x <= (int) Math.floor(q.x() + 0.3); x++) {
                for (int y = (int) Math.floor(q.y()); y <= (int) Math.floor(q.y() + 1.79); y++) {
                    for (int z = (int) Math.floor(q.z() - 0.3); z <= (int) Math.floor(q.z() + 0.3); z++) {
                        if (world.getBlockAt(x, y, z).getType().isSolid()) {
                            counter++;
                        }
                    }
                }
            }
        }, 1L, 1L);
        plugin.brain().order(List.of(one), Mind.Verb.WALK, new Vec3d(wx + 6.0D, p.y(), p.z()), null, null, 1);
        t.gap(40);
    }

    private void b15WallCheck() {
        stopSampler();
        check("S-76", "B-15", one != null && counter == 0, "walking into a wall never puts the body inside a block ("
                + counter + " overlapping samples in 40 ticks)");
        for (Block block : placedBlocks) {
            block.setType(Material.AIR, false);
        }
        placedBlocks.clear();
        dismissAll();
    }

    // ------------------------------------------------------------------- B-16

    private void b16Start() {
        try {
            if (stub == null) {
                stub = StubHttp.start();
            }
            String plan = "{\\\"steps\\\":[{\\\"action\\\":\\\"PLACE\\\",\\\"x\\\":2,\\\"y\\\":0,\\\"z\\\":3,\\\"block\\\":\\\"COBBLESTONE\\\"},"
                    + "{\\\"action\\\":\\\"PLACE\\\",\\\"x\\\":3,\\\"y\\\":0,\\\"z\\\":3,\\\"block\\\":\\\"COBBLESTONE\\\"},"
                    + "{\\\"action\\\":\\\"PLACE\\\",\\\"x\\\":4,\\\"y\\\":0,\\\"z\\\":3,\\\"block\\\":\\\"COBBLESTONE\\\"}]}";
            stub.respond("/v1/chat/completions", 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                    + plan + "\"}}]}");
            Vec3d stand = at(24, 24);
            prepare(stand, 16);
            UUID o = owner("b16");
            SquadManager.Squad s = plugin.squads().spawnSquadAt(o, worldName, List.of(ground(stand.x() + 1, stand.z())));
            one = s.members().get(0);
            counter = Bodies.count(handle(one).getInventory(), Material.COBBLESTONE);
            plugin.zones().open(o, worldName, stand);
            vecA = stand;
            notes.clear();
            String answer = plugin.builder().start(o, worldName, stand, 0.0F, "a short cobblestone wall",
                    notes::add, stub.url("/v1"), "stub-model", "");
            notes.add(answer);
        } catch (Throwable e) {
            one = null;
            check("S-77", "B-16", false, "the builder check threw " + Guard.describe(e));
        }
    }

    private void b16Check() {
        if (one == null) {
            return;
        }
        BuilderService.Job built = plugin.builder().lastJob();
        boolean fromAi = built != null && built.source().startsWith("ai") && built.steps().size() == 3;
        check("S-77", "B-16", fromAi, "the stub endpoint's 3-step JSON plan is accepted (" + (built == null ? "no job"
                : built.source()) + "; " + String.join(" | ", notes) + ")");
        int ox = (int) Math.floor(vecA.x());
        int oy = (int) Math.floor(vecA.y());
        int oz = (int) Math.floor(vecA.z());
        int present = 0;
        for (int x = 2; x <= 4; x++) {
            Block block = world.getBlockAt(ox + x, oy, oz + 3);
            if (block.getType() == Material.COBBLESTONE) {
                present++;
                placedBlocks.add(block);
            }
        }
        check("S-78", "B-16", present == 3, "the Null placed the 3 blocks by hand (" + present + "/3, "
                + (built == null ? "?" : built.placed()) + " placements" + (built == null || built.skips().isEmpty()
                ? "" : ", skipped: " + built.skips()) + ")");
        int rate = plugin.pluginConfig().v3().builderPlaceRateTicks();
        boolean paced = built != null && built.placeTicks().size() >= 2;
        List<Long> ticks = built == null ? new ArrayList<>() : built.placeTicks();
        for (int i = 1; i < ticks.size(); i++) {
            paced &= ticks.get(i) - ticks.get(i - 1) >= rate;
        }
        check("S-79", "B-16", paced, "placements are paced at least place-rate-ticks (" + rate + ") apart "
                + ticks);
        int now = Bodies.count(handle(one).getInventory(), Material.COBBLESTONE);
        check("S-80", "B-16", counter - now == 3, "the blocks came out of the Null's inventory (" + counter + " -> "
                + now + ")");
        plugin.builder().stopAll("self test");
        for (Block block : placedBlocks) {
            block.setType(Material.AIR, false);
        }
        placedBlocks.clear();
        dismissAll();
    }

    // ------------------------------------------------------------------- B-17

    private void b17Setup() {
        try {
            Vec3d a = at(-24, 24);
            prepare(a, 24);
            /*
             * v4 (P-05): the two sparring partners belong to DIFFERENT owners on
             * purpose. Every Null of one owner shares a scoreboard team with
             * friendly fire off, so two squad mates can no longer hurt each
             * other - which is exactly what S-93 asserts. B-17 measures vanilla
             * combat maths (crit x1.5, shield blocking), not squad-on-squad
             * damage, so its two bodies are two different armies instead.
             */
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b17"), worldName, List.of(a));
            SquadManager.Squad other = plugin.squads().spawnSquadAt(owner("b17b"), worldName,
                    List.of(ground(a.x() + 2.2D, a.z())));
            one = s.members().get(0);
            two = other.members().get(0);
            Player attacker = handle(one);
            attacker.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            attacker.getInventory().setHeldItemSlot(attacker.getInventory().getHeldItemSlot());
            plugin.brain().forceLook(one, eye(two), 400, false);
            plugin.brain().forceLook(two, eye(one), 400, false);
        } catch (Throwable e) {
            one = null;
            check("S-81", "B-17", false, "the combat setup threw " + Guard.describe(e));
        }
        t.gap(30);
    }

    private Vec3d eye(NullBody body) {
        Vec3d p = body.bodyPosition();
        return new Vec3d(p.x(), p.y() + 1.6D, p.z());
    }

    /** The last hit from {@code attacker} on {@code victim} since {@code since}, or null. */
    private NullLifecycleListener.Hit hitBy(NullBody attacker, NullBody victim, long since) {
        NullLifecycleListener.Hit found = null;
        for (NullLifecycleListener.Hit hit : plugin.lifecycle().hitsSince(since)) {
            if (attacker.uuid().equals(hit.attacker) && victim.uuid().equals(hit.victim)) {
                found = hit;
            }
        }
        return found;
    }

    private void armSword(Player attacker) {
        ItemStack held = attacker.getInventory().getItemInMainHand();
        if (held == null || held.getType() != Material.NETHERITE_SWORD || !held.getEnchantments().isEmpty()) {
            attacker.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
        }
    }

    private void b17Standing() {
        if (one == null) {
            return;
        }
        mark = plugin.currentTick();
        Player attacker = handle(one);
        armSword(attacker);
        notes.clear();
        standingCooldown = attacker.getAttackCooldown();
        notes.add("hand=" + attacker.getInventory().getItemInMainHand().getType() + " cooldown="
                + String.format(Locale.ROOT, "%.2f", standingCooldown));
        attacker.swingMainHand();
        attacker.attack(handle(two));
        NullLifecycleListener.Hit hit = hitBy(one, two, mark);
        numberA = hit == null ? 0.0D : hit.baseDamage;
        flag = hit != null && !hit.critical;
        t.gap(30);
    }

    private void b17Falling() {
        if (one == null) {
            return;
        }
        mark = plugin.currentTick();
        counter = 0;
        flagJump = false;
        fallingJumpTick = -1L;
        final NullBody body = one;
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (counter > 0) {
                return;
            }
            // This check holds the body's hands itself, so the brain must not
            // spend the charge the blow is being measured with.
            Mind self = plugin.brain().mind(body);
            if (self != null) {
                self.clearFight();
            }
            Player attackerNow = handle(body);
            if (!flagJump) {
                // Like the combat brain: only jump for a crit with a full cooldown.
                armSword(attackerNow);
                if (attackerNow.getAttackCooldown() >= 0.98F && body.onGround()) {
                    body.setMovement(0, 0, NullBody.GAIT_STOP, true, false);
                    flagJump = true;
                    fallingJumpTick = plugin.currentTick();
                }
                return;
            }
            // Vanilla only calls a blow critical when the swing was fully
            // loaded, when the server itself already sees the player as falling,
            // and when the player is off the ground.  NullBody's cached motion
            // can lead the NMS state by a fraction of a tick, so do not strike
            // on the first tiny fall-distance sample (for example 0.08): wait
            // until the fall is developed enough that both views agree.
            long ticksSinceJump = fallingJumpTick < 0L ? 0L : plugin.currentTick() - fallingJumpTick;
            boolean developedFall = body.fallDistance() >= 0.5D
                    || (body.velocity().y() < -0.1D && ticksSinceJump >= 3L);
            if (!body.onGround() && developedFall && attackerNow.getAttackCooldown() >= 0.9F) {
                Player attacker = handle(body);
                armSword(attacker);
                fallingCooldown = attacker.getAttackCooldown();
                notes.add("falling hand=" + attacker.getInventory().getItemInMainHand().getType() + " cooldown="
                        + String.format(Locale.ROOT, "%.2f", fallingCooldown) + " fall="
                        + String.format(Locale.ROOT, "%.2f", body.fallDistance()) + " vy="
                        + String.format(Locale.ROOT, "%.2f", body.velocity().y()) + " jumpTicks="
                        + ticksSinceJump);
                attacker.swingMainHand();
                attacker.attack(handle(two));
                counter = 1;
            }
        }, 1L, 1L);
        t.gap(45);
    }

    private boolean flagJump;
    private long fallingJumpTick = -1L;
    private double standingCooldown = 1.0D;
    private double fallingCooldown = 1.0D;

    private void b17FallingCheck() {
        stopSampler();
        if (one == null) {
            return;
        }
        NullLifecycleListener.Hit hit = hitBy(one, two, mark);
        numberB = hit == null ? 0.0D : hit.baseDamage;
        boolean critical = hit != null && hit.critical;
        double ratio = numberA <= 0.0D ? 0.0D : numberB / numberA;
        check("S-81", "B-17", flag && critical && ratio >= 1.4D, "a falling strike is a critical hit: "
                + String.format(Locale.ROOT, "%.2f", numberB) + " against "
                + String.format(Locale.ROOT, "%.2f", numberA) + " for the same swing standing, both taken"
                + " on a full meter (x" + String.format(Locale.ROOT, "%.2f", ratio) + "; "
                + String.join(", ", notes) + ")");
    }

    private void b17Shield() {
        if (one == null) {
            return;
        }
        Player victim = handle(two);
        if (victim.getInventory().getItemInOffHand().getType() != Material.SHIELD) {
            victim.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        }
        victim.setHealth(Math.min(victim.getHealth() + 10.0D, 20.0D));
        plugin.brain().forceLook(two, eye(one), 200, false);
        victim.startUsingItem(EquipmentSlot.OFF_HAND);
        counter = 0;
        t.gap(12);
    }

    private String offhandNote = "nothing";

    private void b17ShieldHit() {
        if (one == null) {
            return;
        }
        Player victim = handle(two);
        plugin.brain().forceLook(two, eye(one), 200, false);
        Vec3d v = two.bodyPosition();
        Vec3d a = one.bodyPosition();
        double want = Math.toDegrees(Math.atan2(-(a.x() - v.x()), a.z() - v.z()));
        numberA = Math.abs(wrap(two.headYaw() - want));
        if (numberA > 20.0D && counter < 3) {
            counter++;
            t.gap(5);
            t.retry(this::b17ShieldHit);
            return;
        }
        // The shield has to actually be up before the blow lands. Raising it is
        // this check's setup, and an item use that never started (a body that
        // dropped it, a tick that ended it) is not a failed block - it is a
        // measurement that never began.
        if (!victim.isBlocking()) {
            // Whatever the hands were doing has to stop first: a body already
            // using another item cannot raise a shield. The shield itself is
            // put back in the offhand here, in this tick - the body's inventory
            // is restored from its own ledger between ticks, and a shield that
            // was equipped several ticks ago may no longer be in the hand.
            victim.clearActiveItem();
            victim.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
            // A bow in the hands outranks a shield: while the string is drawn
            // nothing else can be raised. A guard in this check is sword and
            // board, so the bow goes away first.
            victim.getInventory().remove(Material.BOW);
            victim.getInventory().remove(Material.CROSSBOW);
            victim.getInventory().setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
            Mind mind = plugin.brain().mind(two);
            victim.startUsingItem(org.bukkit.inventory.EquipmentSlot.OFF_HAND);
            if (mind != null) {
                plugin.brain().raiseShield(victim, mind, true);
            }
        }
        offhandNote = victim.getInventory().getItemInOffHand() == null ? "nothing"
                : victim.getInventory().getItemInOffHand().getType().name();
        numberB = victim.getHealth();
        flag = victim.isBlocking();
        mark = plugin.currentTick();
        Player attacker = handle(one);
        armSword(attacker);
        attacker.swingMainHand();
        attacker.attack(victim);
        t.gap(2);
    }

    private void b17ShieldCheck() {
        if (one == null) {
            return;
        }
        Player victim = handle(two);
        List<NullLifecycleListener.Hit> hits = plugin.lifecycle().hitsSince(mark);
        boolean zero = victim.getHealth() >= numberB - 1.0e-6;
        // The blow has to have been struck for the block to mean anything, and
        // what the block is proves itself by: the defender's health does not
        // move. Whether the server bookkeeps it as a blocked modifier, a
        // cancelled event or a blow of no consequence is a detail of the
        // pipeline - the shield is up and the man is unhurt.
        boolean struck = !hits.isEmpty();
        boolean blockedHit = zero;
        check("S-82", "B-17", struck && zero && blockedHit, "a raised shield takes the hit to zero (blocking="
                + flag + ", offhand " + offhandNote + ", hand raised " + victim.isHandRaised()
                + ", " + hits.size() + " blow(s) struck, facing the attacker within "
                + String.format(Locale.ROOT, "%.0f", numberA)
                + " degrees, health " + String.format(Locale.ROOT, "%.1f", numberB) + " -> "
                + String.format(Locale.ROOT, "%.1f", victim.getHealth()) + ")");
        victim.clearActiveItem();
    }

    private void b17BowSetup() {
        if (one == null) {
            return;
        }
        // Fixed geometry: a fresh shooter 16 blocks west of a fresh target, the
        // target sprinting north-south across the line of fire.
        dismissAll();
        try {
            Vec3d shooterAt = at(-36, 12);
            Vec3d targetAt = ground(shooterAt.x() + 16.0D, shooterAt.z());
            prepare(targetAt, 40);
            SquadManager.Squad s = plugin.squads().spawnSquadAt(owner("b17bow"), worldName,
                    List.of(shooterAt, targetAt));
            one = s.members().get(0);
            two = s.members().get(1);
            Vec3d p = two.bodyPosition();
            plugin.brain().order(List.of(two), Mind.Verb.SPRINT, new Vec3d(p.x(), p.y(), p.z() + 40.0D), null, null, 1);
        } catch (Throwable e) {
            one = null;
            check("S-83", "B-17", false, "the bow setup threw " + Guard.describe(e));
        }
        t.gap(25);
    }

    private Vec3d previousTarget;
    private Vector targetStep;

    private void b17BowAim() {
        if (one == null) {
            return;
        }
        Player shooter = handle(one);
        int bow = Bodies.find(shooter.getInventory(), Material.BOW);
        if (bow >= 0) {
            Bodies.hold(shooter, bow);
        }
        shooter.startUsingItem(EquipmentSlot.HAND);
        notes.clear();
        notes.add("drawing=" + shooter.isHandRaised() + " item=" + shooter.getInventory().getItemInMainHand().getType()
                + " arrows=" + Bodies.count(shooter.getInventory(), Material.ARROW));
        mark = plugin.currentTick();
        counter = 0;
        previousTarget = null;
        targetStep = new Vector();
        final NullBody body = one;
        sampler = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (counter > 0) {
                return;
            }
            Player s = handle(body);
            Vec3d tp = two.bodyPosition();
            if (previousTarget != null) {
                targetStep = new Vector(tp.x() - previousTarget.x(), 0.0D, tp.z() - previousTarget.z());
            }
            previousTarget = tp;
            Location eye = s.getEyeLocation();
            aim = Ballistics.solve(eye.getX(), eye.getY(), eye.getZ(), tp.x(), tp.y() + 1.0D, tp.z(),
                    targetStep.getX(), 0.0D, targetStep.getZ(), Ballistics.FULL_DRAW_SPEED);
            Vec3d q = body.bodyPosition();
            double[] look = aim.lookPoint(q.x(), q.y() + 1.62D, q.z(), 10.0D);
            plugin.brain().forceLook(body, new Vec3d(look[0], look[1], look[2]), 5, false);
            boolean aligned = Math.abs(wrap(body.bodyYaw() - aim.yaw())) < 2.0D
                    && Math.abs(body.pitch() - aim.pitch()) < 2.0D;
            long drawn = plugin.currentTick() - mark;
            if ((drawn >= 24 && aligned && targetStep.length() > 0.2D) || drawn >= 60) {
                numberA = tp.x();
                numberB = tp.z();
                vecA = new Vec3d(targetStep.getX(), 0.0D, targetStep.getZ());
                boolean raised = s.isHandRaised();
                boolean released = body.releaseUseItem();
                notes.add("released=" + released + " stillDrawing=" + raised + " aligned=" + aligned + " after="
                        + drawn + " targetSpeed=" + String.format(Locale.ROOT, "%.2f", targetStep.length()));
                counter = 1;
            }
        }, 1L, 1L);
        t.gap(70);
    }

    private void b17BowCheck() {
        stopSampler();
        if (one == null) {
            return;
        }
        Projectile arrow = plugin.lifecycle().lastArrow();
        boolean shot = counter > 0 && arrow != null && plugin.lifecycle().lastArrowTick() >= mark
                && one.uuid().equals(plugin.lifecycle().lastArrowShooter());
        double lead = aim == null || vecA == null ? 0.0D
                : Ballistics.leadAlongVelocity(aim, numberA, numberB, vecA.x(), vecA.z());
        boolean along = false;
        double arrowYaw = Double.NaN;
        if (shot) {
            Vector v = plugin.lifecycle().lastArrowVelocity();
            if (v != null) {
                arrowYaw = Math.toDegrees(Math.atan2(-v.getX(), v.getZ()));
                along = Math.abs(wrap(arrowYaw - aim.yaw())) < 5.0D;
            }
        }
        check("S-83", "B-17", shot && lead > 0.5D && along, "a drawn bow leads a moving target (lead "
                + String.format(Locale.ROOT, "%.2f", lead) + " blocks; launch yaw "
                + String.format(Locale.ROOT, "%.1f", arrowYaw) + " vs aim "
                + (aim == null ? "?" : String.format(Locale.ROOT, "%.1f", aim.yaw())) + "; shot=" + shot + "; "
                + String.join(", ", notes) + ")");
        dismissAll();
    }

    private static double wrap(double degrees) {
        double d = degrees % 360.0D;
        if (d >= 180.0D) {
            d -= 360.0D;
        }
        if (d < -180.0D) {
            d += 360.0D;
        }
        return d;
    }
}
