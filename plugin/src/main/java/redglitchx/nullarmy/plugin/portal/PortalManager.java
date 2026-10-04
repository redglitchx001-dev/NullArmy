package redglitchx.nullarmy.plugin.portal;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;

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
        List<PortalBuilder.BuiltPortal> built = new ArrayList<>();
        if (count <= 0 || origin == null || worldName == null) {
            return built;
        }
        PluginConfig current = config;
        if (current == null || !current.portalsEnabled()) {
            return built;
        }
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return built;
        }
        int allowed = Math.min(count, current.portalsMaxPerSummon());
        int room = current.portalsMaxActive() - active.size();
        if (room <= 0) {
            plugin.getLogger().info("[NullArmy] portal ceiling reached ("
                    + active.size() + "/" + current.portalsMaxActive()
                    + " active doorways); this summon uses safe ground instead.");
            return built;
        }
        allowed = Math.min(allowed, room);
        long now = plugin.currentTick();
        for (int i = 0; i < allowed; i++) {
            PortalBuilder.Result result = builder.build(plugin.adapter(), world, origin,
                    current.portalSearchRadius(), current.portalsLifetimeTicks(), now);
            if (result.succeeded()) {
                active.add(result.portal());
                built.add(result.portal());
                continue;
            }
            // A refusal is worth one line in the log, never an exception.
            plugin.getLogger().fine("[NullArmy] portal not built: "
                    + (result.refusal() == null ? "unknown reason" : result.refusal().reason()));
            break; // the search already swept every nearby site; retrying is noise
        }
        return built;
    }

    /**
     * The exit spot a Null should be spawned at.
     *
     * <p>Spots are consumed in order and each one is re-checked, so two Nulls
     * never share a block and a spot that has since become unsafe is skipped.</p>
     *
     * @return a validated spot, or null when this doorway is full
     */
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
            if (!adapter.isSpawnSafe(portal.worldName(), spot)) {
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
        Iterator<PortalBuilder.BuiltPortal> it = active.iterator();
        while (it.hasNext()) {
            PortalBuilder.BuiltPortal portal = it.next();
            if (portal.expiresAtTick() <= tickCounter) {
                it.remove();
                restore(portal);
            }
        }
    }

    /** Puts one doorway's blocks back. */
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
     * @return how many doorways were undone
     */
    public int restoreAll() {
        int count = 0;
        for (PortalBuilder.BuiltPortal portal : new ArrayList<>(active)) {
            if (restore(portal) >= 0) {
                count++;
            }
        }
        active.clear();
        return count;
    }

    /** How many doorways are standing right now. */
    public int activeCount() { return active.size(); }

    /** One line for {@code /null status}. */
    public String describe() {
        PluginConfig current = config;
        int max = current == null ? 0 : current.portalsMaxPerSummon();
        long life = current == null ? 0 : current.portalsLifetimeTicks();
        boolean enabled = current != null && current.portalsEnabled();
        return (enabled ? "on" : "off (portals.enabled)") + ", " + active.size()
                + " standing, up to " + max + " per summon, lifetime " + (life / 20) + "s";
    }

    // ---------------------------------------------------------------- containment

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
            if (ours(event.getFrom())) {
                event.setCancelled(true);
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
            if (ours(event.getFrom())) {
                event.setCancelled(true);
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

    /** True when any entity near this location is standing in one of our blocks. */
    public boolean isInOurPortal(Entity entity) {
        return entity != null && ours(entity.getLocation());
    }
}
