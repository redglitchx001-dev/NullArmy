package redglitchx.nullarmy.plugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.config.SummonRules;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.item.SummonItems;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Summoning: trigger item -&gt; chat count -&gt; validated spawn.
 *
 * <p>Spec 3: after a valid trigger the plugin asks the <b>authorized
 * summoner</b> for the desired count in chat; the request is bound to that
 * player, expires after a timeout, validates the answer, supports cancel, and
 * must ignore chat from anyone else.</p>
 *
 * <p>The count arithmetic itself lives in {@link SummonRules} so the same
 * clamping is used by the chat flow, by {@code /null horn} and by tests - a
 * count can never sneak past the caps through a second code path.</p>
 *
 * <p><b>Threading</b> (spec 2.4): {@link AsyncPlayerChatEvent} is async, so
 * every world-touching operation is scheduled back onto the main thread. No
 * world mutation ever happens off-thread, and nothing thrown here can reach the
 * chat thread.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SummonFlow implements Listener, Reloadable {

    /** Every message this flow sends starts with this, as the spec requires. */
    private static final String PREFIX = "[NullArmy] ";

    /** The exact question from spec 3. */
    private static final String QUESTION = "How many Nulls should come?";

    private final NullArmyPlugin plugin;
    private final SquadManager squads;
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    private PluginConfig config;

    /** A summon request waiting for its owner to answer in chat. */
    private static final class Pending {
        private final UUID player;
        private final long createdTick;
        private final String worldName;
        private final Vec3d origin;
        private final String itemKind;

        Pending(UUID player, long createdTick, String worldName, Vec3d origin, String itemKind) {
            this.player = player;
            this.createdTick = createdTick;
            this.worldName = worldName;
            this.origin = origin;
            this.itemKind = itemKind;
        }
    }

    public SummonFlow(NullArmyPlugin plugin, PluginConfig config, SquadManager squads) {
        this.plugin = plugin;
        this.config = config;
        this.squads = squads;
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.config = fresh;
        }
    }

    // ------------------------------------------------------------------ triggers

    /** Player clicks with a summon item: opened from a listener, so it is wrapped. */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Guard.attempt(plugin.getLogger(), "summon-item interaction", () -> handleInteract(event));
    }

    private void handleInteract(PlayerInteractEvent event) {
        if (event == null) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // Both hands fire an event; only the main hand should start a prompt.
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        String kind = SummonItems.kindOf(item);
        if (kind.isEmpty()) {
            return;
        }

        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        // The horn is not a musical instrument here.
        event.setCancelled(true);

        if (!player.hasPermission("nullarmy.summon")) {
            player.sendMessage(PREFIX + "You do not have permission to summon Nulls (nullarmy.summon).");
            return;
        }
        if (plugin.spawnBreaker().isOpen()) {
            player.sendMessage(PREFIX + "Null creation is disabled this session: "
                    + plugin.spawnBreaker().reason());
            player.sendMessage(PREFIX + "Fix the cause, then run /null reload to re-arm it.");
            return;
        }

        Caps caps = caps();
        int free = SummonRules.remainingCapacity(squads.liveCount(), caps.maxLiveNpcs());
        if (free <= 0) {
            player.sendMessage(PREFIX + "The server Null limit is reached ("
                    + squads.liveCount() + "/" + caps.maxLiveNpcs()
                    + "). Use /null dismiss or /null stop first.");
            return;
        }

        Pending existing = pending.get(player.getUniqueId());
        if (existing != null) {
            player.sendMessage(PREFIX + "You already have a summon request open. Answer it or type 'cancel'.");
            return;
        }

        Location loc = player.getLocation();
        if (loc == null || loc.getWorld() == null) {
            player.sendMessage(PREFIX + "Could not read your position, so nothing was summoned.");
            return;
        }

        pending.put(player.getUniqueId(), new Pending(
                player.getUniqueId(),
                plugin.currentTick(),
                loc.getWorld().getName(),
                new Vec3d(loc.getX(), Math.floor(loc.getY()), loc.getZ()),
                kind));

        player.sendMessage(PREFIX + QUESTION);
        player.sendMessage(PREFIX + "Type a number from 1 to " + caps.summonHardCap()
                + " in chat. Type 'cancel' to stop.");
    }

    // ------------------------------------------------------------------- chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Guard.attempt(plugin.getLogger(), "summon prompt chat", () -> handleChat(event));
    }

    private void handleChat(AsyncPlayerChatEvent event) {
        if (event == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        UUID id = player.getUniqueId();
        Pending request = pending.get(id);
        if (request == null) {
            return;
        }

        // This player has a pending request, so their chat is consumed by it and
        // must never reach other players as ordinary chat.
        event.setCancelled(true);

        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        if (raw.equalsIgnoreCase("cancel") || raw.equalsIgnoreCase("stop")) {
            pending.remove(id);
            player.sendMessage(PREFIX + "Summon cancelled. Nothing was spawned.");
            return;
        }
        if (raw.equalsIgnoreCase("help")) {
            player.sendMessage(PREFIX + "Reply with a number 1-"
                    + caps().summonHardCap() + ", or 'cancel'.");
            return;
        }

        int count;
        try {
            count = Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            player.sendMessage(PREFIX + "'" + raw + "' is not a number. Reply with a count or 'cancel'.");
            return;
        }

        final int finalCount = count;
        // Back to the main thread before touching the world (spec 2.4).
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                pending.remove(id);
                Guard.attempt(plugin.getLogger(), "executing a summon",
                        () -> executeSummon(player, request, finalCount));
            });
        } catch (Throwable t) {
            pending.remove(id);
            player.sendMessage(PREFIX + "Could not schedule the summon: " + Guard.describe(t));
        }
    }

    /**
     * Applies the caps and spawns. Runs on the main thread, always wrapped.
     */
    private void executeSummon(Player player, Pending request, int requested) {
        if (player == null || !player.isOnline()) {
            plugin.getLogger().info("[NullArmy] summon for " + request.player
                    + " skipped: the player is offline.");
            return;
        }
        Caps caps = caps();
        SummonRules.Decision decision = SummonRules.decide(
                requested, caps.summonHardCap(), squads.liveCount(), caps.maxLiveNpcs());
        if (decision.refused()) {
            player.sendMessage(PREFIX + decision.explanation());
            return;
        }
        if (decision.clamped()) {
            player.sendMessage(PREFIX + decision.explanation());
        }

        try {
            SquadManager.Squad squad = squads.createSquad(
                    request.player, request.worldName, request.origin, decision.granted());
            int spawned = squad.members().size();
            if (spawned < decision.granted()) {
                player.sendMessage(PREFIX + "Only " + spawned + " of " + decision.granted()
                        + " Nulls found collision-safe ground; the rest were not spawned"
                        + " rather than clipping into terrain.");
            }
            player.sendMessage(PREFIX + "Summoned " + spawned
                    + (spawned == 1 ? " Null." : " Nulls.")
                    + " The portals open where you stood.");
        } catch (IllegalStateException refusal) {
            // Clear, honest failure - no partial army (spec 3).
            player.sendMessage(PREFIX + "Summon failed: " + refusal.getMessage());
        } catch (Throwable t) {
            player.sendMessage(PREFIX + "Summon failed: " + Guard.describe(t));
            plugin.getLogger().warning("[NullArmy] summon failed: " + Guard.describe(t));
        }
    }

    // ------------------------------------------------------------------- housekeeping

    /** Expires stale requests. Runs on the main thread, never throws. */
    public void tick(long tickCounter) {
        if (pending.isEmpty()) {
            return;
        }
        long timeout = config == null ? 300L : config.summonPromptTimeoutTicks();
        List<UUID> expired = new ArrayList<>();
        for (Map.Entry<UUID, Pending> entry : pending.entrySet()) {
            if (tickCounter - entry.getValue().createdTick >= timeout) {
                expired.add(entry.getKey());
            }
        }
        for (UUID id : expired) {
            pending.remove(id);
            try {
                Player player = Bukkit.getPlayer(id);
                if (player != null && player.isOnline()) {
                    player.sendMessage(PREFIX + "The summon request timed out. Use the item again when ready.");
                }
            } catch (Throwable ignored) {
                // An unreachable player simply does not get the notice.
            }
        }
    }

    /** True when this player owes an answer to the prompt. */
    public boolean hasPending(UUID player) {
        return player != null && pending.containsKey(player);
    }

    /** Number of open prompts, for {@code /null status}. */
    public int pendingCount() {
        return pending.size();
    }

    /** Drops a pending prompt (used by {@code /null stop}). */
    public boolean cancelPending(UUID player) {
        return player != null && pending.remove(player) != null;
    }

    private Caps caps() {
        return config == null ? plugin.pluginConfig().caps() : config.caps();
    }
}
