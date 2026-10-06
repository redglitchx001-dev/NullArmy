package redglitchx.nullarmy.plugin.spectacle;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.body.Bodies;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The explicit, owner-triggered Ender Pearl teleport cannon.
 *
 * <p>{@code /null tp} gives the owner a tagged, nearly-broken fishing rod.
 * Casting it into a block and reeling the embedded hook arms one volley: one
 * real Ender Pearl is consumed from each live Null (and the owner's Commander,
 * if present). Each pearl is a vanilla projectile, falling from a different
 * height and a spaced position over a loaded, collision-safe landing area.
 * Nulls that lack a pearl are never supplied with a fabricated one.</p>
 *
 * <p>This is intentionally separate from summoning and ordinary movement. The
 * summon doorway remains physical; this class does not expose a generic
 * teleport operation to pathfinding or an AI agent.</p>
 */
public final class TeleportCannon implements Listener {

    private static final String PERMISSION = "nullarmy.admin";
    private static final int MAX_TARGET_RANGE = 64;
    private static final int MAX_SCATTER_RADIUS = 9;
    private static final int MIN_DROP_HEIGHT = 12;
    private static final int MAX_DROP_HEIGHT = 30;
    private static final double MIN_LANDING_SPACING = 1.25D;
    private static final double DOWNWARD_SPEED = 1.15D;

    private final NullArmyPlugin plugin;
    private final NamespacedKey rodIdKey;
    private final Map<UUID, ArmedHook> armedHooks = new HashMap<>();
    private final Map<UUID, PendingPearl> pendingPearls = new HashMap<>();
    private final Map<UUID, PendingPearl> pendingByBody = new HashMap<>();
    private final Set<UUID> activeVolleyOwners = new HashSet<>();

    private static final class PendingPearl {
        private final Shot shot;
        private final World world;
        private boolean teleportWasDenied;

        PendingPearl(Shot shot, World world) {
            this.shot = shot;
            this.world = world;
        }
    }

    private static final class ArmedHook {
        private final UUID owner;
        private final String rodId;

        ArmedHook(UUID owner, String rodId) {
            this.owner = owner;
            this.rodId = rodId;
        }
    }

    private static final class Shot {
        private final NullBody body;
        private final Player handle;
        private final Vec3d landing;
        private final int height;

        Shot(NullBody body, Player handle, Vec3d landing, int height) {
            this.body = body;
            this.handle = handle;
            this.landing = landing;
            this.height = height;
        }
    }

    private static final class VolleyPlan {
        private final World world;
        private final List<Shot> shots;
        private final String failure;

        private VolleyPlan(World world, List<Shot> shots, String failure) {
            this.world = world;
            this.shots = shots;
            this.failure = failure;
        }

        static VolleyPlan ready(World world, List<Shot> shots) {
            return new VolleyPlan(world, shots, null);
        }

        static VolleyPlan failed(String reason) {
            return new VolleyPlan(null, java.util.Collections.emptyList(), reason);
        }

        boolean ready() {
            return failure == null && !shots.isEmpty();
        }
    }

    /**
     * What one cast did, in the words the angler is told and one flag: whether
     * the rod was spent. The event handler and the self test both go through
     * {@link #castVolley}, so a test measures the same path a real reel takes.
     */
    public static final class CastResult {
        /** True when a volley was scheduled and the rod was consumed. */
        public final boolean fired;
        /** The one line the angler is sent. */
        public final String message;

        CastResult(boolean fired, String message) {
            this.fired = fired;
            this.message = message == null ? "" : message;
        }
    }

    public TeleportCannon(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.rodIdKey = new NamespacedKey(plugin, "teleport_cannon_rod_id");
    }

    /** Gives the one-use aiming rod, replacing any previous unspent copy. */
    public String giveRod(Player player) {
        if (!isLive(player)) {
            return "Only a player who is really in the world can receive the teleport-cannon rod.";
        }
        if (!player.hasPermission(PERMISSION)) {
            return "You need " + PERMISSION + " to use the teleport cannon.";
        }

        clearArmedHooks(player.getUniqueId());
        removeTaggedRods(player);

        ItemStack rod = new ItemStack(Material.FISHING_ROD, 1);
        ItemMeta meta = rod.getItemMeta();
        if (meta == null) {
            return "The fishing rod could not be prepared; nothing was added.";
        }
        meta.setDisplayName(ChatColor.DARK_PURPLE + "Null Teleport Cannon");
        meta.setLore(java.util.Arrays.asList(
                ChatColor.GRAY + "One-use Ender Pearl barrage",
                ChatColor.GRAY + "Cast at an open, loaded block and reel in.",
                ChatColor.GRAY + "Each live Null spends its own Ender Pearl."));
        if (meta instanceof Damageable) {
            ((Damageable) meta).setDamage(Math.max(0, Material.FISHING_ROD.getMaxDurability() - 1));
        }
        String rodId = UUID.randomUUID().toString();
        meta.getPersistentDataContainer().set(rodIdKey, PersistentDataType.STRING, rodId);
        rod.setItemMeta(meta);

        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(rod);
        for (ItemStack left : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }
        return "A nearly-broken Null Teleport Cannon rod was given to you. Cast into a clear, loaded "
                + "ground area and reel when the hook sticks; each live Null must carry a real Ender Pearl.";
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event == null || event.getPlayer() == null || event.getHook() == null || event.getState() == null) {
            return;
        }
        Player player = event.getPlayer();
        FishHook hook = event.getHook();
        UUID hookId = hook.getUniqueId();
        try {
            if (event.getState() == PlayerFishEvent.State.FISHING) {
                String rodId = rodId(itemInHand(event));
                if (rodId != null) {
                    clearArmedHooks(player.getUniqueId());
                    armedHooks.put(hookId, new ArmedHook(player.getUniqueId(), rodId));
                }
                return;
            }

            if (event.getState() == PlayerFishEvent.State.IN_GROUND) {
                ArmedHook armed = armedHooks.remove(hookId);
                String rodId = armed != null && player.getUniqueId().equals(armed.owner)
                        ? armed.rodId : rodId(itemInHand(event));
                if (rodId == null) {
                    return; // an ordinary fishing rod: nothing to do, nothing touched
                }
                CastResult result = castVolley(player, hook.getLocation(), rodId);
                player.sendMessage(PluginText.PREFIX + result.message);
                if (result.fired) {
                    hook.remove();
                }
                return;
            }

            // A failed cast, catch, or ordinary reel must not leave an armed hook
            // which could fire later with a different fishing rod.
            armedHooks.remove(hookId);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] teleport cannon event failed safely: " + Guard.describe(t));
            player.sendMessage(PluginText.PREFIX + "The teleport cannon failed safely; no items were fabricated.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (event == null || event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || event.getPlayer() == null || !event.isCancelled()) {
            return;
        }
        PendingPearl pending = pendingByBody.get(event.getPlayer().getUniqueId());
        if (pending != null) {
            // Never bypass another plugin's explicit teleport veto with the
            // disconnected-client fallback below.
            pending.teleportWasDenied = true;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPearlImpact(ProjectileHitEvent event) {
        if (event == null || !(event.getEntity() instanceof EnderPearl)) {
            return;
        }
        UUID pearlId = event.getEntity().getUniqueId();
        PendingPearl pending = pendingPearls.remove(pearlId);
        if (pending == null) {
            return;
        }
        Block hit = event.getHitBlock();
        if (hit == null || event.getHitEntity() != null
                || hit.getX() != (int) Math.floor(pending.shot.landing.x())
                || hit.getY() != (int) Math.floor(pending.shot.landing.y()) - 1
                || hit.getZ() != (int) Math.floor(pending.shot.landing.z())) {
            clearPendingBody(pending);
            return;
        }
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> completePearlImpact(pending), 1L);
        } catch (Throwable t) {
            clearPendingBody(pending);
            plugin.getLogger().warning("[NullArmy] could not schedule Ender Pearl impact check: "
                    + Guard.describe(t));
        }
    }

    private void completePearlImpact(PendingPearl pending) {
        try {
            Shot shot = pending.shot;
            if (pending.teleportWasDenied || shot == null || shot.body == null || !shot.body.isAlive()
                    || shot.handle == null || !shot.handle.isValid() || !pending.world.equals(shot.handle.getWorld())) {
                return;
            }
            Location now = shot.handle.getLocation();
            double dx = now.getX() - shot.landing.x();
            double dz = now.getZ() - shot.landing.z();
            if (dx * dx + dz * dz <= 4.0D && Math.abs(now.getY() - shot.landing.y()) <= 3.0D) {
                return; // vanilla already applied the Ender Pearl teleport
            }

            int blockX = (int) Math.floor(shot.landing.x());
            int blockZ = (int) Math.floor(shot.landing.z());
            if (!pending.world.isChunkLoaded(blockX >> 4, blockZ >> 4)
                    || !plugin.adapter().isSpawnSafe(pending.world.getName(), shot.landing)
                    || !plugin.adapter().isEntitySpaceFree(pending.world.getName(), shot.landing)) {
                return;
            }
            if (!plugin.adapter().enderPearlTeleport(pending.world.getName(), shot.body, shot.landing)) {
                plugin.getLogger().warning("[NullArmy] vanilla Ender Pearl impact could not move Null "
                        + shot.body.profileName() + "; it remained at its current position.");
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] Ender Pearl impact fallback failed safely: "
                    + Guard.describe(t));
        } finally {
            clearPendingBody(pending);
        }
    }

    private void clearPendingBody(PendingPearl pending) {
        if (pending == null || pending.shot == null || pending.shot.body == null
                || pending.shot.body.uuid() == null) {
            return;
        }
        pendingByBody.remove(pending.shot.body.uuid(), pending);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (event != null && event.getPlayer() != null) {
            clearArmedHooks(event.getPlayer().getUniqueId());
        }
    }

    /**
     * The cast behind a stuck hook: permission, one-volley-at-a-time, the plan,
     * the schedule and the one-use rod. This is the whole decision, so the event
     * handler and the self test cannot drift apart - a real reel and the test
     * both come through here.
     *
     * <p>Nothing is spent before the plan is ready: a cast that is refused
     * leaves every Null's pearls and the rod exactly where they were.</p>
     *
     * @param player the angler
     * @param target where the hook stuck
     * @param rodId  the tagged rod's id, or null for a rod that is not a cannon rod
     * @return what the angler is told, and whether the volley was scheduled
     */
    public CastResult castVolley(Player player, Location target, String rodId) {
        if (player == null) {
            return new CastResult(false, "Only a player can fire the teleport cannon.");
        }
        if (rodId == null || rodId.isEmpty()) {
            return new CastResult(false, "That is an ordinary fishing rod, not a Null Teleport"
                    + " Cannon rod; it is left exactly as it was.");
        }
        if (!player.hasPermission(PERMISSION)) {
            return new CastResult(false, "You need " + PERMISSION + " to use the teleport cannon.");
        }
        if (activeVolleyOwners.contains(player.getUniqueId())) {
            return new CastResult(false, "Your previous pearl volley is still in progress.");
        }
        VolleyPlan plan = prepare(player, target);
        if (!plan.ready()) {
            return new CastResult(false, plan.failure);
        }
        if (!scheduleVolley(player, plan)) {
            return new CastResult(false, "The pearl barrage could not be scheduled;"
                    + " no Null inventory was changed.");
        }
        activeVolleyOwners.add(player.getUniqueId());
        consumeRod(player, rodId);
        return new CastResult(true, "The line snaps — " + plan.shots.size()
                + " real Ender Pearl(s) are falling from varied heights and positions. "
                + "Summoning portals remain physical and unchanged.");
    }

    /** The cannon-rod id in that player's held item, or null for any other rod. */
    public String heldRodId(Player player) {
        if (player == null || player.getInventory() == null) {
            return null;
        }
        return rodId(player.getInventory().getItemInMainHand());
    }

    /**
     * True while that player is really in the world. A real player who quit is
     * removed from the level, and a Null body is a live server-side player with
     * no client attached - {@code isOnline()} answers "no" for a Null by
     * construction, which must not read as "the owner logged out".
     */
    private static boolean isLive(Player player) {
        return player != null && player.isValid() && !player.isDead();
    }

    private VolleyPlan prepare(Player owner, Location target) {
        if (owner == null || target == null || target.getWorld() == null
                || !owner.getWorld().equals(target.getWorld())) {
            return VolleyPlan.failed("The teleport cannon only works in the same world.");
        }
        if (owner.getLocation().distanceSquared(target) > (double) MAX_TARGET_RANGE * MAX_TARGET_RANGE) {
            return VolleyPlan.failed("The hook is too far away; keep the target within " + MAX_TARGET_RANGE + " blocks.");
        }

        List<NullBody> bodies = new ArrayList<>(plugin.squads().membersOf(owner.getUniqueId()));
        if (plugin.commander() != null && owner.getUniqueId().equals(plugin.commander().owner())) {
            NullBody commander = plugin.commander().body();
            if (commander != null && commander.isAlive()) {
                boolean alreadyAdded = false;
                for (NullBody body : bodies) {
                    if (body != null && body.uuid() != null && body.uuid().equals(commander.uuid())) {
                        alreadyAdded = true;
                        break;
                    }
                }
                if (!alreadyAdded) {
                    bodies.add(commander);
                }
            }
        }

        List<NullBody> live = new ArrayList<>();
        List<Player> handles = new ArrayList<>();
        for (NullBody body : bodies) {
            if (body == null || !body.isAlive()) {
                continue;
            }
            Player handle = Bodies.player(body);
            if (handle == null || !handle.isValid()) {
                return VolleyPlan.failed("A live Null could not be resolved; no pearls were spent.");
            }
            if (!target.getWorld().equals(handle.getWorld())) {
                return VolleyPlan.failed("All live Nulls must already be in the target's world. "
                        + "The cannon does not cross dimensions.");
            }
            if (body.uuid() != null && pendingByBody.containsKey(body.uuid())) {
                return VolleyPlan.failed(body.profileName() + " already has a cannon pearl in flight.");
            }
            if (findPearlSlot(handle.getInventory()) < 0) {
                return VolleyPlan.failed(body.profileName() + " has no Ender Pearl. "
                        + "Give every live Null one first; the cannon never invents ammunition.");
            }
            live.add(body);
            handles.add(handle);
        }
        if (live.isEmpty()) {
            return VolleyPlan.failed("You have no live Nulls in this world to fire through the cannon.");
        }

        List<Vec3d> landingSpots = findSafeLandings(target.getWorld(), target, live.size());
        if (landingSpots.size() < live.size()) {
            return VolleyPlan.failed("Not enough clear, loaded, collision-safe landing spots were found. "
                    + "Aim at an open area; no chunks will be force-loaded.");
        }

        List<Shot> shots = new ArrayList<>();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int maxHeight = Math.min(MAX_DROP_HEIGHT,
                target.getWorld().getMaxHeight() - 2 - (int) Math.ceil(landingSpots.get(0).y()));
        if (maxHeight < 4) {
            return VolleyPlan.failed("There is not enough safe sky above the target area for a pearl drop.");
        }
        int minHeight = Math.min(MIN_DROP_HEIGHT, maxHeight);
        int previousHeight = -1;
        for (int i = 0; i < live.size(); i++) {
            Vec3d landing = landingSpots.get(i);
            int localMax = Math.min(MAX_DROP_HEIGHT,
                    target.getWorld().getMaxHeight() - 2 - (int) Math.ceil(landing.y()));
            int localMin = Math.min(minHeight, localMax);
            if (localMax < 4) {
                return VolleyPlan.failed("A landing spot has too little sky clearance; no pearls were spent.");
            }
            int height = localMin + random.nextInt(Math.max(1, localMax - localMin + 1));
            if (localMax > localMin && height == previousHeight) {
                height = height == localMax ? localMin : height + 1;
            }
            shots.add(new Shot(live.get(i), handles.get(i), landing, height));
            previousHeight = height;
        }
        return VolleyPlan.ready(target.getWorld(), shots);
    }

    /**
     * Plans safe ground spots in a compact, randomized spiral. Every candidate
     * is inside an already-loaded chunk, has a clear sky column, and is checked
     * by the active version adapter for collision safety.
     */
    private List<Vec3d> findSafeLandings(World world, Location center, int count) {
        List<Vec3d> out = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        if (world == null || center == null || count <= 0) {
            return out;
        }
        double angleOffset = ThreadLocalRandom.current().nextDouble(0.0D, Math.PI * 2.0D);
        for (int ring = 0; ring <= MAX_SCATTER_RADIUS / 1.5D && out.size() < count; ring++) {
            double radius = ring * 1.5D;
            int points = ring == 0 ? 1 : Math.max(8, ring * 8);
            for (int point = 0; point < points && out.size() < count; point++) {
                double angle = angleOffset + (Math.PI * 2.0D * point / points);
                int blockX = (int) Math.floor(center.getX() + Math.cos(angle) * radius);
                int blockZ = (int) Math.floor(center.getZ() + Math.sin(angle) * radius);
                long key = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL);
                if (!visited.add(key) || !world.isChunkLoaded(blockX >> 4, blockZ >> 4)) {
                    continue;
                }

                int groundY = world.getHighestBlockYAt(blockX, blockZ);
                if (groundY < world.getMinHeight() || groundY + MAX_DROP_HEIGHT + 2 >= world.getMaxHeight()) {
                    continue;
                }
                Block ground = world.getBlockAt(blockX, groundY, blockZ);
                if (!ground.getType().isSolid() || ground.isLiquid()) {
                    continue;
                }
                Vec3d feet = new Vec3d(blockX + 0.5D, groundY + 1.0D, blockZ + 0.5D);
                if (!clearDropColumn(world, blockX, blockZ, groundY + 1, MAX_DROP_HEIGHT)
                        || !plugin.adapter().isSpawnSafe(world.getName(), feet)
                        || !plugin.adapter().isEntitySpaceFree(world.getName(), feet)
                        || tooClose(out, feet)) {
                    continue;
                }
                out.add(feet);
            }
        }
        return out;
    }

    private boolean clearDropColumn(World world, int x, int z, int firstY, int height) {
        int lastY = Math.min(world.getMaxHeight() - 1, firstY + height);
        for (int y = firstY; y <= lastY; y++) {
            if (!world.getBlockAt(x, y, z).getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private static boolean tooClose(List<Vec3d> spots, Vec3d candidate) {
        double limit = MIN_LANDING_SPACING * MIN_LANDING_SPACING;
        for (Vec3d spot : spots) {
            double dx = spot.x() - candidate.x();
            double dz = spot.z() - candidate.z();
            if (dx * dx + dz * dz < limit) {
                return true;
            }
        }
        return false;
    }

    private boolean scheduleVolley(Player owner, VolleyPlan plan) {
        try {
            new BukkitRunnable() {
                private int index;
                private int launched;
                private int skipped;

                @Override
                public void run() {
                    try {
                        if (!isLive(owner) || !owner.getWorld().equals(plan.world)) {
                            skipped += plan.shots.size() - index;
                            finish();
                            return;
                        }
                        if (index >= plan.shots.size()) {
                            finish();
                            return;
                        }
                        Shot shot = plan.shots.get(index++);
                        if (launchOne(plan.world, shot)) {
                            launched++;
                        } else {
                            skipped++;
                        }
                        if (index >= plan.shots.size()) {
                            finish();
                        }
                    } catch (Throwable t) {
                        skipped += Math.max(0, plan.shots.size() - index + (index > launched + skipped ? 1 : 0));
                        plugin.getLogger().warning("[NullArmy] pearl-rain tick failed safely: "
                                + Guard.describe(t));
                        finish();
                    }
                }

                private void finish() {
                    cancel();
                    activeVolleyOwners.remove(owner.getUniqueId());
                    if (owner.isOnline()) {
                        owner.sendMessage(PluginText.PREFIX + "Pearl rain complete: " + launched + "/"
                                + plan.shots.size() + " Null(s) launched; " + skipped
                                + " did not fire because their body, pearl, or safe drop lane changed.");
                    }
                }
            }.runTaskTimer(plugin, 1L, 1L);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] could not schedule pearl rain: " + Guard.describe(t));
            return false;
        }
    }

    private boolean launchOne(World world, Shot shot) {
        if (shot == null || shot.body == null || !shot.body.isAlive() || shot.handle == null
                || !shot.handle.isValid() || !world.equals(shot.handle.getWorld())) {
            return false;
        }
        PlayerInventory inventory = shot.handle.getInventory();
        int pearlSlot = findPearlSlot(inventory);
        if (pearlSlot < 0) {
            return false;
        }

        int x = (int) Math.floor(shot.landing.x());
        int z = (int) Math.floor(shot.landing.z());
        if (!world.isChunkLoaded(x >> 4, z >> 4)
                || !plugin.adapter().isSpawnSafe(world.getName(), shot.landing)
                || !plugin.adapter().isEntitySpaceFree(world.getName(), shot.landing)
                || !clearDropColumn(world, x, z, (int) Math.floor(shot.landing.y()), shot.height)) {
            return false;
        }
        Location launchAt = new Location(world, shot.landing.x(), shot.landing.y() + shot.height,
                shot.landing.z());
        if (launchAt.getY() >= world.getMaxHeight() - 1) {
            return false;
        }

        EnderPearl pearl = null;
        try {
            // Use the Null as the genuine projectile source. Only the projectile
            // is moved to the sky; the Null remains where it was until the
            // vanilla Ender Pearl hits a block and applies its normal teleport.
            pearl = shot.handle.launchProjectile(EnderPearl.class);
            if (pearl == null || !pearl.isValid()) {
                return false;
            }
            pearl.setShooter(shot.handle);
            if (!pearl.teleport(launchAt)) {
                pearl.remove();
                return false;
            }
            pearl.setVelocity(new Vector(0.0D, -DOWNWARD_SPEED, 0.0D));
        } catch (Throwable t) {
            if (pearl != null && pearl.isValid()) {
                pearl.remove();
            }
            plugin.getLogger().warning("[NullArmy] Ender Pearl launch failed: " + Guard.describe(t));
            return false;
        }

        consumePearl(inventory, pearlSlot);
        PendingPearl pending = new PendingPearl(shot, world);
        UUID pearlId = pearl.getUniqueId();
        pendingPearls.put(pearlId, pending);
        if (shot.body.uuid() != null) {
            pendingByBody.put(shot.body.uuid(), pending);
        }
        try {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                pendingPearls.remove(pearlId, pending);
                clearPendingBody(pending);
            }, 20L * 40L);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] could not schedule projectile cleanup: " + Guard.describe(t));
        }
        return true;
    }

    private static int findPearlSlot(PlayerInventory inventory) {
        if (inventory == null) {
            return -1;
        }
        for (int slot = 0; slot < Bodies.SLOTS; slot++) {
            ItemStack stack = Bodies.get(inventory, slot);
            if (stack.getType() == Material.ENDER_PEARL && stack.getAmount() > 0) {
                return slot;
            }
        }
        return -1;
    }

    private static void consumePearl(PlayerInventory inventory, int slot) {
        ItemStack stack = Bodies.get(inventory, slot);
        if (stack.getType() != Material.ENDER_PEARL || stack.getAmount() <= 0) {
            return;
        }
        if (stack.getAmount() == 1) {
            Bodies.set(inventory, slot, null);
        } else {
            stack.setAmount(stack.getAmount() - 1);
            Bodies.set(inventory, slot, stack);
        }
    }

    private static ItemStack itemInHand(PlayerFishEvent event) {
        if (event == null || event.getPlayer() == null) {
            return null;
        }
        if (event.getHand() == EquipmentSlot.OFF_HAND) {
            return event.getPlayer().getInventory().getItemInOffHand();
        }
        return event.getPlayer().getInventory().getItemInMainHand();
    }

    private String rodId(ItemStack stack) {
        if (stack == null || stack.getType() != Material.FISHING_ROD || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(rodIdKey, PersistentDataType.STRING);
    }

    private void consumeRod(Player player, String rodId) {
        if (player == null || rodId == null) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (rodId.equals(rodId(inventory.getItem(slot)))) {
                inventory.setItem(slot, null);
                return;
            }
        }
        if (rodId.equals(rodId(inventory.getItemInOffHand()))) {
            inventory.setItemInOffHand(null);
        }
    }

    private void removeTaggedRods(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < 36; slot++) {
            if (rodId(inventory.getItem(slot)) != null) {
                inventory.setItem(slot, null);
            }
        }
        if (rodId(inventory.getItemInOffHand()) != null) {
            inventory.setItemInOffHand(null);
        }
    }

    private void clearArmedHooks(UUID player) {
        if (player == null) {
            return;
        }
        armedHooks.entrySet().removeIf(entry -> player.equals(entry.getValue().owner));
    }
}
