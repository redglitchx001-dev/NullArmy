package redglitchx.nullarmy.plugin.portal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEnterEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.util.BoundingBox;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.portal.PortalPlan;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Owns every temporary arrival portal in the world.
 *
 * <p>Responsibilities, in the order they matter:</p>
 * <ol>
 *   <li><b>Plan.</b> For each summon, pick a random number of doorways from 1 up
 *       to the configured hard maximum and split the Nulls between them, so both
 *       numbers vary between summons.</li>
 *   <li><b>Build.</b> Hand each doorway to {@link PortalBuilder}, which refuses
 *       any site that would overwrite a block or that has no verified spot to
 *       step out onto.</li>
 *   <li><b>Expire.</b> Restore every block once the configured lifetime is up, on
 *       {@code /null portals clear}, and on plugin disable. A doorway that outlives
 *       its summon is world damage by another name.</li>
 *   <li><b>Contain.</b> Cancel portal travel through these blocks, so a Null's
 *       doorway can never send anybody to the Nether.</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PortalManager implements Listener, Reloadable {

    private static final String PREFIX = PluginText.PREFIX;

    private final NullArmyPlugin plugin;
    private final PortalBuilder builder;
    private final List<PortalBuilder.BuiltPortal> active = new ArrayList<>();
    private final Random random = new Random();
    /** How many times an entity's crossing into one of our doorways was refused. */
    private int refusals;

    private PluginConfig config;

    public PortalManager(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.builder = new PortalBuilder(plugin.getLogger());
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    // ------------------------------------------------------------------ planning

    /**
     * Plans one summon's arrivals.
     *
     * @param nulls how many Nulls the summon granted
     * @return a plan with a random doorway count and a random split
     */
    public PortalPlan plan(int nulls) {
        PluginConfig current = config;
        if (current == null || !current.portalsEnabled() || nulls <= 0) {
            return PortalPlan.none(nulls);
        }
        return PortalPlan.of(nulls, current.portalsMaxPerSummon(),
                current.portalsMaxPerPortal(), random);
    }

    // ------------------------------------------------------------------- building

    /**
     * Builds up to {@code count} doorways near {@code origin}.
     *
     * <p>A doorway that cannot be built is not a failure of the summon: the
     * caller redistributes its Nulls over the doorways that did get built, and
     * anything still left uses the ordinary safe-ground path. Nothing is silently
     * dropped.</p>
     *
     * @return the doorways that were really built, never null
     */
    public List<PortalBuilder.BuiltPortal> buildDoorways(String worldName, Vec3d origin, int count) {
        PluginConfig current = config;
        int size = current == null ? redglitchx.nullarmy.core.zone.SummonZone.DEFAULT_SIZE : current.v3().zoneSize();
        return buildDoorways(worldName, origin, count,
                new redglitchx.nullarmy.core.zone.SummonZone(origin.x(), origin.z(), size));
    }

    /**
     * Builds up to {@code count} doorways, every one of them inside {@code zone}.
     * When none fits, {@link #lastRefusal()} says why in words the owner can act on.
     */
    public List<PortalBuilder.BuiltPortal> buildDoorways(String worldName, Vec3d origin, int count,
                                                         redglitchx.nullarmy.core.zone.SummonZone zone) {
        List<PortalBuilder.BuiltPortal> built = new ArrayList<>();
        lastRefusal = "";
        if (count <= 0 || origin == null || worldName == null || zone == null) {
            return built;
        }
        PluginConfig current = config;
        if (current == null || !current.portalsEnabled()) {
            lastRefusal = "portals are switched off (portals.enabled)";
            return built;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return built;
        }
        int room = current.portalsMaxActive() - active.size();
        if (room <= 0) {
            lastRefusal = "the portal ceiling is reached (" + active.size() + "/" + current.portalsMaxActive()
                    + " doorways standing)";
            plugin.getLogger().info("[NullArmy] " + lastRefusal + "; this summon uses safe ground instead.");
            return built;
        }
        int allowed = Math.min(Math.min(count, room), redglitchx.nullarmy.core.portal.PortalPlan.HARD_PORTAL_CEILING * 2);
        long now = plugin.currentTick();
        redglitchx.nullarmy.plugin.config.V3Settings v3 = current.v3();
        for (int i = 0; i < allowed; i++) {
            // Keep the configured expiry on each doorway even while persistence
            // is enabled. The tick sweep suppresses it while the opt-in is on;
            // if an owner reloads it off, overdue frames can then restore safely.
            PortalBuilder.Request request = new PortalBuilder.Request(zone, (int) Math.floor(origin.y()),
                    v3.floatingChance(), v3.airHeightMin(), v3.airHeightMax(), v3.portalLifetimeTicks(), active,
                    current.portalParticlesEnabled());
            PortalBuilder.Result result = builder.build(plugin.adapter(), world, origin, request, now);
            if (result.succeeded()) {
                active.add(result.portal());
                built.add(result.portal());
                continue;
            }
            lastRefusal = result.refusal() == null ? "unknown reason" : result.refusal().reason();
            plugin.getLogger().fine("[NullArmy] portal not built: " + lastRefusal);
            break;
        }
        return built;
    }

    private String lastRefusal = "";

    /** Why the last build found no (or too few) sites; "" when every doorway was built. */
    public String lastRefusal() { return lastRefusal; }

    /** Every doorway standing right now. */
    public List<PortalBuilder.BuiltPortal> standing() {
        return new ArrayList<>(active);
    }

    /** Problems with a standing doorway; empty when it is a complete one-way frame. */
    public List<String> validate(PortalBuilder.BuiltPortal portal) {
        World world = portal == null ? null : Bukkit.getWorld(portal.worldName());
        PluginConfig current = config;
        int min = current == null ? 4 : current.v3().airHeightMin();
        int max = current == null ? 12 : current.v3().airHeightMax();
        return builder.validate(world, portal, min, max);
    }

    /**
     * Closes one doorway now and restores its blocks.
     *
     * @return number of blocks restored in this pass; an incomplete doorway
     *         remains tracked and is retried by the tick sweep
     */
    public int close(PortalBuilder.BuiltPortal portal) {
        if (portal == null) {
            return 0;
        }
        portal.requestRestore();
        int blocks = restore(portal);
        if (portal.isRestored()) {
            active.remove(portal);
        }
        return blocks;
    }

    public Vec3d takeExit(PortalBuilder.BuiltPortal portal, int wanted) {
        if (portal == null) {
            return null;
        }
        List<Vec3d> exits = portal.exits();
        if (wanted < 0 || wanted >= exits.size()) {
            return null;
        }
        Vec3d spot = exits.get(wanted);
        VersionAdapter adapter = plugin.adapter();
        if (adapter == null || spot == null) {
            return null;
        }
        try {
            boolean insideMouth = portal.frame().containsBody(spot.x(), spot.y(), spot.z());
            boolean safe = insideMouth
                    ? adapter.isPortalSpawnSafe(portal.worldName(), spot)
                    : adapter.isSpawnSafe(portal.worldName(), spot);
            if (!safe) {
                return null;
            }
            if (!adapter.isEntitySpaceFree(portal.worldName(), spot)) {
                return null;
            }
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] portal exit re-check failed: " + Guard.describe(t));
            return null; // fail closed: never spawn into an unverified spot
        }
        return spot;
    }

    // ------------------------------------------------------------------- lifetime

    /** Per-tick expiry sweep. Restores whatever has run out of time. */
    public void tick(long tickCounter) {
        if (active.isEmpty()) {
            return;
        }
        PluginConfig current = config;
        boolean persistent = current != null && current.portalsPersistUntilClear();
        boolean afterExit = current == null || current.v3().restoreAfterExit();
        Iterator<PortalBuilder.BuiltPortal> it = active.iterator();
        while (it.hasNext()) {
            PortalBuilder.BuiltPortal portal = it.next();
            boolean expired = !persistent && portal.expiresAtTick() <= tickCounter;
            boolean allOut = !persistent && afterExit && !portal.assigned().isEmpty()
                    && tickCounter - portal.builtTick() > 60 && everyoneOut(portal);
            if (expired || allOut || portal.restorationRequested()) {
                portal.requestRestore();
                restore(portal);
                if (portal.isRestored()) {
                    it.remove();
                    if (plugin.chatGate() != null) {
                        Vec3d c = portal.center();
                        plugin.chatGate().event("portal.restored", "where",
                                Math.round(c.x()) + "," + Math.round(c.y()) + "," + Math.round(c.z()),
                                "blocks", portal.blockCount());
                    }
                } else {
                    // Keep the portal tracked and retry next tick. Counting an
                    // attempted but incomplete restore as closed would hide the
                    // remaining world edits from the owner and from disable().
                    plugin.getLogger().fine("[NullArmy] portal restoration is incomplete; "
                            + portal.pendingRestoreCount() + " block(s) remain at " + portal.center());
                }
                continue;
            }
            if (tickCounter % 10 == 0) {
                World world = Bukkit.getWorld(portal.worldName());
                if (world != null) {
                    PortalBuilder.effects(world, portal, tickCounter % 80 == 0,
                            current == null || current.portalParticlesEnabled());
                }
            }
        }
    }

    /** True when no Null that came through the doorway is still inside its frame. */
    private boolean everyoneOut(PortalBuilder.BuiltPortal portal) {
        for (java.util.UUID id : portal.assigned()) {
            org.bukkit.entity.Entity entity = Bukkit.getEntity(id);
            if (entity == null || entity.isDead()) {
                continue;
            }
            Location at = entity.getLocation();
            if (portal.frame().containsBody(at.getX(), at.getY(), at.getZ())) {
                return false;
            }
        }
        return true;
    }

    private int restore(PortalBuilder.BuiltPortal portal) {
        if (portal == null) {
            return 0;
        }
        World world = Bukkit.getWorld(portal.worldName());
        if (world == null) {
            return 0;
        }
        final int[] done = new int[1];
        Guard.attempt(plugin.getLogger(), "restoring a portal",
                () -> done[0] = portal.restore(world, plugin.getLogger()));
        return done[0];
    }

    /**
     * Restores every active doorway now.
     *
     * @return how many doorways were completely restored; incomplete ones remain
     *         tracked so another cleanup pass can retry them
     */
    public int restoreAll() {
        int count = 0;
        for (PortalBuilder.BuiltPortal portal : new ArrayList<>(active)) {
            portal.requestRestore();
            if (!portal.isRestored()) {
                restore(portal);
            }
            if (portal.isRestored()) {
                active.remove(portal);
                count++;
            }
        }
        return count;
    }

    /** How many doorways are standing right now. */
    public int activeCount() { return active.size(); }

    /** One line for {@code /null status}. */
    public String describe() {
        PluginConfig current = config;
        int max = current == null ? 0 : current.portalsMaxPerSummon();
        long life = current == null ? 0L : current.v3().portalLifetimeTicks();
        boolean enabled = current != null && current.portalsEnabled();
        boolean persistent = current != null && current.portalsPersistUntilClear();
        boolean particles = current == null || current.portalParticlesEnabled();
        String lifetime = persistent ? "persistent until /null portals clear or plugin shutdown"
                : "lifetime " + (life / 20) + "s";
        return (enabled ? "on" : "off (portals.enabled)") + ", " + active.size()
                + " standing, up to " + max + " per summon, " + lifetime
                + ", particles " + (particles ? "on" : "off");
    }

    /** Self test / diagnostics: crossings into our doorways refused so far. */
    public int refusals() { return refusals; }

    /** Self test: start counting refusals from zero. */
    public void resetRefusals() { refusals = 0; }

    // ---------------------------------------------------------------- containment

    /**
     * Contact containment: the block an entity touches is one of ours.
     *
     * <p>This is the reliable half of the lock, and it is checked first because
     * it carries the fact the travel events do not: Paper fires this event with
     * the exact portal block the body overlaps, and cancelling it stops vanilla
     * from starting the crossing at all. The travel events below remain the
     * second line of defence, for an entity that was already inside a doorway
     * when it went up.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortalEnter(EntityPortalEnterEvent event) {
        Guard.attempt(plugin.getLogger(), "portal contact containment", () -> {
            if (event == null || active.isEmpty()) {
                return;
            }
            if (ours(event.getLocation())) {
                event.setCancelled(true);
                refusals++;
            }
        });
    }

    /**
     * Nobody travels through a Null's doorway.
     *
     * <p>The portal blocks are real, so vanilla would start a player's crossing
     * countdown the moment they stood in one. Nulls cannot start it (their portal
     * handling is neutralised in the adapter), but a curious player can - and the
     * plugin must not be the reason somebody ends up in the Nether with a portal
     * generated there.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        Guard.attempt(plugin.getLogger(), "portal containment", () -> {
            if (event == null || active.isEmpty()) {
                return;
            }
            if (ours(event.getFrom()) || isInOurPortal(event.getPlayer())) {
                event.setCancelled(true);
                refusals++;
                Player player = event.getPlayer();
                if (player != null && config != null && !config.portalTravelAllowed()) {
                    player.sendMessage(PREFIX + "That is a Null arrival doorway, not a Nether"
                            + " portal. It closes on its own in a moment.");
                }
            }
        });
    }

    /** The same containment for every other entity type. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        Guard.attempt(plugin.getLogger(), "entity portal containment", () -> {
            if (event == null || active.isEmpty()) {
                return;
            }
            if (ours(event.getFrom()) || isInOurPortal(event.getEntity())) {
                event.setCancelled(true);
                refusals++;
            }
        });
    }

    /** True when that location is one of the blocks we placed. */
    private boolean ours(Location location) {
        if (location == null) {
            return false;
        }
        for (PortalBuilder.BuiltPortal portal : active) {
            if (portal.owns(location)) {
                return true;
            }
        }
        return false;
    }

    /** True when any part of that entity's body overlaps one of our portal blocks. */
    public boolean isInOurPortal(Entity entity) {
        if (entity == null || active.isEmpty()) {
            return false;
        }
        World world = entity.getWorld();
        if (world == null) {
            return false;
        }
        BoundingBox body;
        try {
            body = entity.getBoundingBox();
        } catch (Throwable t) {
            body = null;
        }
        if (body == null) {
            return ours(entity.getLocation());
        }
        for (PortalBuilder.BuiltPortal portal : active) {
            if (!world.getName().equals(portal.worldName())) {
                continue;
            }
            for (int[] cell : portal.frame().interiorCells()) {
                if (body.overlaps(new BoundingBox(cell[0], cell[1], cell[2],
                        cell[0] + 1.0D, cell[1] + 1.0D, cell[2] + 1.0D))) {
                    return true;
                }
            }
        }
        return false;
    }
}
