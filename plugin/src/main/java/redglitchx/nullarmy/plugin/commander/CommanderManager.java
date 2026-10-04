package redglitchx.nullarmy.plugin.commander;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;

import redglitchx.nullarmy.core.combat.CombatSituation;
import redglitchx.nullarmy.core.combat.PvpArsenal;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.nms.VersionAdapter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.skin.SkinData;
import redglitchx.nullarmy.plugin.skin.SkinResolver;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * The Null Commander: one named Null that spawns from a portal, wears the
 * configured skin, carries an owner-edited loadout, and fights using the
 * mace/elytra technique library in {@link PvpArsenal}.
 *
 * <p><b>No dependencies.</b> The GUI is a plain chest inventory, persistence is
 * Bukkit's own {@link YamlConfiguration}, and the skin lookup uses the JDK's
 * HTTP client. Nothing is downloaded at build time and no other plugin is
 * required at runtime.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CommanderManager implements Listener, Reloadable {

    /** 36 storage + 4 armour + 1 offhand, matching a player inventory. */
    public static final int LOADOUT_SLOTS = 41;

    private static final String FILE_NAME = "commander.yml";
    private static final String DEFAULT_NAME = "NullCommander";

    /** Every message starts with the shared gradient brand. */
    private static final String PREFIX = PluginText.PREFIX;

    private final NullArmyPlugin plugin;
    private final SkinResolver skins;
    private final ItemStack[] loadout = new ItemStack[LOADOUT_SLOTS];
    private final File file;

    private NullBody commander;
    private SkinData skin;
    private String name = DEFAULT_NAME;

    public CommanderManager(NullArmyPlugin plugin, SkinResolver skins) {
        this.plugin = plugin;
        this.skins = skins;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** Reads the saved name and loadout. Safe to call when the file is absent. */
    public void load() {
        name = plugin.getConfig().getString("commander.name", DEFAULT_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = DEFAULT_NAME;
        }
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (int i = 0; i < LOADOUT_SLOTS; i++) {
            Object raw = yaml.get("loadout." + i);
            if (raw instanceof ItemStack) {
                loadout[i] = (ItemStack) raw;
            }
        }
    }

    /**
     * Warms the skin cache in the background so the first spawn already has it.
     *
     * <p>Everything else works even if this never completes - a Null without a
     * skin is still a Null.</p>
     */
    public void preloadSkin() {
        // Warm both skins: the Commander's, and the one ordinary Nulls share.
        // A failure is cosmetic and is reported, never fatal.
        preloadOne(plugin.pluginConfig().commanderSkinName(), true);
        String nullSkin = plugin.pluginConfig().nullSkinName();
        if (!nullSkin.equalsIgnoreCase(plugin.pluginConfig().commanderSkinName())) {
            preloadOne(nullSkin, false);
        }
    }

    private void preloadOne(String username, boolean forCommander) {
        if (username == null || username.trim().isEmpty()) {
            return;
        }
        skins.resolveAsync(username, data -> {
            if (data != null && data.complete()) {
                if (forCommander) {
                    this.skin = data;
                }
                plugin.getLogger().info("[NullArmy] Skin ready for '" + username
                        + "' from " + data.source() + ".");
            } else {
                plugin.getLogger().warning("[NullArmy] Could not resolve the skin for '"
                        + username + "'. Those NPCs will use the default skin."
                        + " Set skins.nulls / skins.commander in config.yml, or pass"
                        + " -Dnullarmy.skin.null=Name. The plugin still works -"
                        + " a missing skin is cosmetic only.");
            }
        });
    }

    /** The username whose skin the Commander uses, after config resolution. */
    public String skinName() {
        return plugin.pluginConfig().commanderSkinName();
    }

    public SkinData skin() { return skin; }
    public String commanderName() { return name; }
    public ItemStack[] loadout() { return loadout; }

    /**
     * A config reload does not change anything the Commander is already
     * wearing: the loadout lives in {@code commander.yml} and is edited through
     * the GUI, never in {@code config.yml}. The hook exists so {@code /null
     * reload} can tell every subsystem in one pass without special cases.
     */
    @Override
    public void onConfigReloaded(PluginConfig config) {
        // Intentionally empty: see the note above.
    }

    public boolean isSpawned() {
        return commander != null && commander.isAlive();
    }

    /**
     * Spawns the Commander in front of the player, out of a portal.
     *
     * <p>Returns false and explains why when it cannot, rather than failing
     * silently.</p>
     */
    public boolean spawn(Player owner) {
        try {
            return spawnChecked(owner);
        } catch (Throwable t) {
            // The Commander is created through NMS, so this is the one place in
            // the class that really can take a server down: nothing may leave here.
            plugin.getLogger().severe("[NullArmy] Commander spawn failed: " + Guard.describe(t));
            if (owner != null) {
                owner.sendMessage(PREFIX + "The Commander could not be summoned: " + Guard.describe(t));
            }
            return false;
        }
    }

    /** The real spawn sequence, isolated so the failure can be contained. */
    private boolean spawnChecked(Player owner) {
        if (owner == null) {
            return false;
        }
        // Creating an NPC is main-thread work only (spec 2.4); refuse politely
        // rather than corrupting the world from another thread.
        if (!Bukkit.isPrimaryThread()) {
            owner.sendMessage(PREFIX + "The Commander must be summoned from the server thread.");
            return false;
        }
        VersionAdapter adapter = plugin.adapter();
        if (adapter == null) {
            owner.sendMessage(PREFIX + "The Commander needs a version adapter; none is loaded.");
            return false;
        }
        if (isSpawned()) {
            owner.sendMessage(PREFIX + "The Commander is already here. Use /null dismiss first.");
            return false;
        }
        // Same latched guard the squads use: if NMS spawning has already failed
        // once this session, do not try it again with a live server on the line.
        if (plugin.spawnBreaker().isOpen()) {
            owner.sendMessage(PREFIX + "Null creation is disabled for this session: "
                    + plugin.spawnBreaker().reason());
            owner.sendMessage(PREFIX + "Fix the cause, then run /null reload to re-arm it.");
            return false;
        }

        Location origin = owner.getLocation();
        String world = origin == null || origin.getWorld() == null ? null : origin.getWorld().getName();
        if (world == null) {
            owner.sendMessage(PREFIX + "Could not determine your world.");
            return false;
        }

        Vec3d spot = findSafeSpot(adapter, world, origin);
        if (spot == null) {
            owner.sendMessage(PREFIX + "No safe ground nearby for the Commander to step onto -"
                    + " move to open ground and try again.");
            return false;
        }

        String value = (skin != null && skin.complete()) ? skin.value() : "";
        String signature = (skin != null && skin.complete()) ? skin.signature() : "";

        try {
            commander = adapter.spawnNull(new VersionAdapter.SpawnRequest(
                    owner.getUniqueId(), name, world, spot, 36 * 64, value, signature));
        } catch (Throwable t) {
            String reason = Guard.describe(t);
            plugin.spawnBreaker().trip(reason, t, plugin.getLogger(),
                    "Null creation is now latched off; the server is unaffected."
                            + " Restart or run /null reload to try again after fixing the cause.");
            owner.sendMessage(PREFIX + "The Commander could not be summoned: " + reason);
            return false;
        }
        if (commander == null) {
            owner.sendMessage(PREFIX + "The adapter returned no entity - nothing was summoned.");
            return false;
        }

        // The portal is the whole point of the entrance. It is cosmetic, so it
        // may fail without losing the Commander that already exists.
        Guard.attempt(plugin.getLogger(), "Commander portal effects", () -> {
            int effects = Math.max(Caps.minPortalEffects(),
                    plugin.pluginConfig() == null ? Caps.minPortalEffects()
                            : plugin.pluginConfig().caps().portalEffectsPerSummon());
            adapter.playPortalEffects(world, spot, effects);
        });

        Guard.attempt(plugin.getLogger(), "Commander loadout", this::applyLoadout);
        owner.sendMessage(PREFIX + "The Commander steps out of the portal."
                + " Use /null loadout to equip it.");
        return true;
    }

    /** Sends the saved loadout to the spawned Commander. */
    private void applyLoadout() {
        if (commander == null) {
            return;
        }
        List<LoadoutSlot> slots = new ArrayList<>();
        for (int i = 0; i < loadout.length; i++) {
            ItemStack stack = loadout[i];
            if (stack == null || stack.getType() == null || stack.getType().isAir()) {
                continue;
            }
            String material = stack.getType().name();
            slots.add(new LoadoutSlot(i, material, stack.getAmount()));
        }
        commander.setLoadout(slots);
    }

    public boolean despawn() {
        NullBody body = commander;
        commander = null;
        if (body == null) {
            return false;
        }
        // The reference is dropped first so a failure cannot leave the manager
        // thinking a body still exists.
        return Guard.attempt(plugin.getLogger(), "despawning the Commander", body::destroy);
    }

    /** Opens the loadout editor for a player. */
    public void openLoadout(Player viewer) {
        if (viewer == null) {
            return;
        }
        Guard.attempt(plugin.getLogger(), "opening the Commander loadout", () -> {
            CommanderInventoryGui gui = new CommanderInventoryGui("Commander loadout", loadout);
            gui.open(viewer);
        });
    }

    /** Persists the current loadout to commander.yml. */
    public void save() throws IOException {
        save(loadout);
    }

    /** Writes a candidate snapshot without changing the live in-memory loadout. */
    private void save(ItemStack[] state) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < LOADOUT_SLOTS && i < state.length; i++) {
            if (state[i] != null) {
                yaml.set("loadout." + i, state[i]);
            }
        }
        if (!plugin.getDataFolder().isDirectory()) {
            plugin.getDataFolder().mkdirs();
        }
        yaml.save(file);
    }

    private static ItemStack[] cloneLoadout(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[LOADOUT_SLOTS];
        for (int i = 0; i < copy.length && i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    /** Picks the technique the Commander uses for a situation. */
    public PvpArsenal.Technique plan(CombatSituation situation) {
        return PvpArsenal.select(situation);
    }

    /**
     * Finds a collision-safe spot near the player. Tries a small outward
     * spiral rather than teleporting the NPC out of a bad position later -
     * spec 3 forbids spawning through terrain.
     */
    private static Vec3d findSafeSpot(VersionAdapter adapter, String world, Location origin) {
        double baseX = origin.getX();
        double baseY = Math.floor(origin.getY());
        double baseZ = origin.getZ();
        int[][] offsets = {
                {1, 0}, {0, 1}, {-1, 0}, {0, -1},
                {2, 0}, {0, 2}, {-2, 0}, {0, -2},
                {2, 2}, {-2, 2}, {2, -2}, {-2, -2},
                {0, 0},
        };
        for (int[] offset : offsets) {
            for (int dy = 0; dy <= 2; dy++) {
                Vec3d candidate = new Vec3d(baseX + offset[0], baseY + dy, baseZ + offset[1]);
                if (adapter.isSpawnSafe(world, candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ GUI events

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        // A listener is one of the four places an exception must never escape
        // (spec 2.1): this GUI edits real items, so it is exactly where a
        // surprise is most expensive.
        Guard.attempt(plugin.getLogger(), "Commander loadout click", () -> handleClick(event));
    }

    private void handleClick(InventoryClickEvent event) {
        if (event == null || event.getInventory() == null) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof CommanderInventoryGui)) {
            return;
        }
        // Dropping an editor item would let a user duplicate a saved loadout
        // entry by closing without saving (the editor is transactional).
        if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
            event.setCancelled(true);
            return;
        }
        int slot = event.getRawSlot();

        // Clicks in the player's own inventory must behave normally.
        if (slot >= CommanderInventoryGui.SIZE) {
            return;
        }

        if (CommanderInventoryGui.isButton(slot)) {
            event.setCancelled(true);
            CommanderInventoryGui gui = (CommanderInventoryGui) event.getInventory().getHolder();
            if (!(event.getWhoClicked() instanceof Player)) {
                return;
            }
            Player player = (Player) event.getWhoClicked();
            if (slot == CommanderInventoryGui.BUTTON_CLEAR) {
                gui.clearWorking();
                player.sendMessage(PREFIX + "Loadout cleared. Press Save to keep it empty.");
            } else if (slot == CommanderInventoryGui.BUTTON_CANCEL) {
                player.closeInventory();
            } else if (slot == CommanderInventoryGui.BUTTON_SAVE) {
                // Save a detached candidate first. A disk failure must not
                // change the in-memory loadout or equip an unsaved version.
                final ItemStack[][] candidate = new ItemStack[1][];
                boolean copied = Guard.attempt(plugin.getLogger(), "reading the loadout editor", () -> {
                    gui.commitFromView();
                    candidate[0] = cloneLoadout(gui.working());
                });
                if (!copied || candidate[0] == null) {
                    player.sendMessage(PREFIX + "Could not read the loadout editor; it is still open.");
                    return;
                }
                boolean saved = Guard.attempt(plugin.getLogger(), "saving the Commander loadout",
                        () -> save(candidate[0]));
                if (!saved) {
                    player.sendMessage(PREFIX + "Save failed. Your current loadout is unchanged; "
                            + "the editor is still open so you can retry or cancel.");
                    return;
                }
                System.arraycopy(candidate[0], 0, loadout, 0, LOADOUT_SLOTS);
                gui.markSaved();
                boolean applied = Guard.attempt(plugin.getLogger(), "applying the Commander loadout",
                        this::applyLoadout);
                player.sendMessage(PREFIX + "Commander loadout saved.");
                if (!applied) {
                    player.sendMessage(PREFIX + "Saved, but the live Commander could not be re-equipped yet.");
                }
                player.closeInventory();
            }
            return;
        }

        if (!CommanderInventoryGui.isEditable(slot)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Guard.attempt(plugin.getLogger(), "Commander loadout drag", () -> {
            if (event == null || event.getView() == null
                    || !(event.getView().getTopInventory().getHolder() instanceof CommanderInventoryGui)) {
                return;
            }
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < CommanderInventoryGui.SIZE && !CommanderInventoryGui.isEditable(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        });
    }

    @EventHandler
    public void onItemDrop(PlayerDropItemEvent event) {
        Guard.attempt(plugin.getLogger(), "Commander loadout item drop", () -> {
            if (event != null && event.getPlayer() != null
                    && event.getPlayer().getOpenInventory().getTopInventory().getHolder()
                            instanceof CommanderInventoryGui) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Guard.attempt(plugin.getLogger(), "Commander loadout close", () -> {
            if (event == null || event.getInventory() == null) {
                return;
            }
            if (!(event.getInventory().getHolder() instanceof CommanderInventoryGui)) {
                return;
            }
            // Closing without pressing Save discards the edit and restores the
            // player's inventory snapshot. A successful Save also closes the
            // screen, so never report that path as discarded.
            CommanderInventoryGui gui = (CommanderInventoryGui) event.getInventory().getHolder();
            if (!gui.wasSaved() && event.getPlayer() instanceof Player) {
                Player player = (Player) event.getPlayer();
                boolean restored = Guard.attempt(plugin.getLogger(), "restoring the pre-edit inventory",
                        () -> gui.restorePlayerInventory(player));
                player.sendMessage(PREFIX + (restored
                        ? "Loadout editor closed without saving; your inventory was restored."
                        : "Loadout editor closed, but your inventory could not be restored; check the server log."));
            }
        });
    }

    /** True when the material is one we refuse to store (purely defensive). */
    static boolean isBanned(ItemStack stack) {
        return stack != null && stack.getType() == Material.AIR;
    }
}
