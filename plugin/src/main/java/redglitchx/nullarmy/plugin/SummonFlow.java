package redglitchx.nullarmy.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.plugin.config.PluginConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Summoning: trigger -> chat count -> validated spawn.
 *
 * <p>Spec 3: after a valid trigger the plugin asks the <b>authorized
 * summoner</b> for the desired count in chat; the request is bound to that
 * player, expires after a timeout, validates the answer, supports cancel, and
 * must ignore chat from anyone else.</p>
 *
 * <p><b>Threading</b> (spec 2.4): {@link AsyncPlayerChatEvent} is async, so
 * every world-touching operation is scheduled back onto the main thread. No
 * world mutation ever happens off-thread.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SummonFlow implements Listener {

    /** Configured display names of the two legal summon items. */
    private static final String CALL_HORN_NAME = "Call Horn";
    private static final String TOTEM_OF_NULL_NAME = "Totem Of Null";

    private final NullArmyPlugin plugin;
    private final PluginConfig config;
    private final SquadManager squads;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /** A summon request waiting for its owner to answer in chat. */
    private static final class Pending {
        private final UUID player;
        private final long createdTick;
        private final String worldName;
        private final Vec3d origin;

        Pending(UUID player, long createdTick, String worldName, Vec3d origin) {
            this.player = player;
            this.createdTick = createdTick;
            this.worldName = worldName;
            this.origin = origin;
        }
    }

    public SummonFlow(NullArmyPlugin plugin, PluginConfig config, SquadManager squads) {
        this.plugin = plugin;
        this.config = config;
        this.squads = squads;
    }

    // ------------------------------------------------------------------ triggers

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        boolean isHorn = item.getType() == Material.GOAT_HORN && hasDisplayName(item, CALL_HORN_NAME);
        boolean isTotem = item.getType() == Material.TOTEM_OF_UNDYING
                && hasDisplayName(item, TOTEM_OF_NULL_NAME);
        if (!isHorn && !isTotem) {
            return;
        }

        if (!player.hasPermission("nullarmy.summon")) {
            player.sendMessage("You do not have permission to summon Nulls.");
            return;
        }

        event.setCancelled(true);

        Location loc = player.getLocation();
        Pending existing = pending.get(player.getUniqueId());
        if (existing != null) {
            player.sendMessage("You already have a summon request pending. Answer it or wait for it to expire.");
            return;
        }

        pending.put(player.getUniqueId(), new Pending(
                player.getUniqueId(),
                plugin.currentTick(),
                loc.getWorld().getName(),
                new Vec3d(loc.getX(), loc.getY(), loc.getZ())));

        player.sendMessage("How many Nulls do you want to summon? (1-"
                + plugin.pluginConfig().caps().summonHardCap() + ", or type cancel)");
    }

    // ------------------------------------------------------------------- chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        Pending request = pending.get(id);

        if (request == null) {
            return;
        }

        // This player has a pending request, so their chat is consumed by it and
        // must never reach other players as ordinary chat.
        event.setCancelled(true);

        String raw = event.getMessage().trim();
        if (raw.equalsIgnoreCase("cancel")) {
            pending.remove(id);
            player.sendMessage("Summon cancelled.");
            return;
        }

        int count;
        try {
            count = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            player.sendMessage("That is not a number. Reply with a count or 'cancel'.");
            return;
        }

        final int finalCount = count;
        // Back to the main thread before touching the world (spec 2.4).
        Bukkit.getScheduler().runTask(plugin, () -> {
            pending.remove(id);
            executeSummon(player, request, finalCount);
        });
    }

    private void executeSummon(Player player, Pending request, int count) {
        Caps caps = plugin.pluginConfig().caps();
        if (count <= 0) {
            player.sendMessage("Count must be at least 1.");
            return;
        }
        if (count > caps.summonHardCap()) {
            player.sendMessage("Refusing " + count + " Nulls: the hard cap is "
                    + caps.summonHardCap() + ". Nothing was spawned.");
            return;
        }
        try {
            squads.createSquad(player.getUniqueId(), request.worldName, request.origin, count);
            player.sendMessage("Summoned " + count + " Nulls.");
        } catch (IllegalStateException e) {
            // Clear, honest failure - no partial army (spec 3).
            player.sendMessage("Summon failed: " + e.getMessage());
        }
    }

    /** Expires stale requests. Runs on the main thread. */
    public void tick(long tickCounter) {
        if (pending.isEmpty()) {
            return;
        }
        long timeout = config.summonPromptTimeoutTicks();
        java.util.Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pending> entry = it.next();
            if (tickCounter - entry.getValue().createdTick >= timeout) {
                it.remove();
                Player player = Bukkit.getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.sendMessage("Your summon request expired.");
                }
            }
        }
    }

    private static boolean hasDisplayName(ItemStack item, String expected) {
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && expected.equals(meta.getDisplayName());
    }
}
