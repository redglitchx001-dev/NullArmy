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
import redglitchx.nullarmy.plugin.kit.KitItems;
import redglitchx.nullarmy.plugin.skin.SkinData;
import redglitchx.nullarmy.plugin.skin.SkinResolver;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

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

    /** Private Commander status messages use the shared plugin prefix. */
    private static final String PREFIX = PluginText.PREFIX;

    private final NullArmyPlugin plugin;
    private final SkinResolver skins;
    private final ItemStack[] loadout = new ItemStack[LOADOUT_SLOTS];
    private final File file;

    private NullBody commander;
    private String name = DEFAULT_NAME;
    /** P-04/P-09/P-12: the one player the Commander obeys. */
    private UUID ownerId;

    public CommanderManager(NullArmyPlugin plugin, SkinResolver skins) {
        this.plugin = plugin;
        this.skins = skins;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /** Reads the configured name and saved loadout. Safe to call when the loadout file is absent. */
    public void load() {
        name = normalizedName(plugin.getConfig().getString("commander.name", DEFAULT_NAME));
        if (name == null) {
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

    /** A plain Minecraft profile name, or null when the input has no valid characters. */
    private static String normalizedName(String wanted) {
        String clean = wanted == null ? "" : wanted.replaceAll("[^A-Za-z0-9_]", "");
        if (clean.isEmpty()) {
            return null;
        }
        return clean.length() > 16 ? clean.substring(0, 16) : clean;
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
        preloadOne(plugin.pluginConfig().commanderSkinName());
        String nullSkin = plugin.pluginConfig().nullSkinName();
        if (!nullSkin.equalsIgnoreCase(plugin.pluginConfig().commanderSkinName())) {
            preloadOne(nullSkin);
        }
    }

    private void preloadOne(String username) {
        if (username == null || username.trim().isEmpty()) {
            return;
        }
        skins.resolveAsync(username, data -> {
            if (data != null && data.complete()) {
                plugin.getLogger().info("[NullArmy] Skin ready for '" + username
                        + "' from " + data.source() + ".");
            } else {
                plugin.getLogger().warning("[NullArmy] Could not resolve the skin for '"
                        + username + "'. Those NPCs will use the default skin."
                        + " Set skins.nulls / skins.commander in config.yml, or use the matching"
                        + " -Dnullarmy.skin.null=Name / -Dnullarmy.skin.commander=Name override."
                        + " The plugin still works -"
                        + " a missing skin is cosmetic only.");
            }
        });
    }

    /** The username whose skin the Commander uses, after config resolution. */
    public String skinName() {
        return plugin.pluginConfig().commanderSkinName();
    }

    public SkinData skin() {
        return plugin.skinChain() == null ? null : plugin.skinChain().current(true);
    }
    public String commanderName() { return name; }

    /**
     * P-12: renames the Commander live without asking Bukkit to rewrite the
     * owner's entire config file. The single name scalar is changed only after
     * strict validation and a restorable backup; malformed or ambiguous YAML
     * leaves both the file and the running name untouched.
     */
    public String rename(String wanted) {
        String clean = normalizedName(wanted);
        if (clean == null) {
            return "That name has no letters or numbers a Minecraft name can hold.";
        }
        String previous = name;
        if (clean.equalsIgnoreCase(previous)) {
            return "The Commander is already called " + clean + ".";
        }
        if (!redglitchx.nullarmy.plugin.config.ConfigMigration.updateCommanderName(
                plugin, plugin.configFile(), clean)) {
            return "The Commander was not renamed: config.yml could not be updated safely. "
                    + "No settings were changed.";
        }
        // Rebuild the typed runtime settings so /null reload and a rename share
        // the same path and every registered config hook sees the new value.
        plugin.reloadPluginConfig();
        name = clean;
        plugin.getLogger().info("[NullArmy] the Commander is now called " + clean + " (was " + previous + ")");
        if (plugin.chatGate() != null) {
            plugin.chatGate().event("commander.renamed", "from", previous, "to", clean);
        }
        return "The Commander answers to " + clean + " now" + (commander == null ? "." : ". His name tag"
                + " changes the next time he is summoned.");
    }

    /** P-04: the player the Commander obeys, or null before he is summoned. */
    public UUID owner() { return ownerId; }

    /** True when this player is the one the Commander obeys. */
    public boolean isOwner(Player player) {
        return player != null && ownerId != null && ownerId.equals(player.getUniqueId());
    }
    public ItemStack[] loadout() { return loadout; }

    /** The live Commander body, or null when it is not here. */
    public NullBody body() { return commander; }

    /**
     * True when a Commander loadout is already saved.
     *
     * <p>This is what decides whether the default kit is installed: a fresh
     * install (or a deleted {@code commander.yml}) gets the shipped kit, an
     * owner-edited loadout is left exactly as it was saved.</p>
     */
    public boolean hasSavedLoadout() {
        for (ItemStack stack : loadout) {
            if (stack != null && stack.getType() != null && !stack.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    /** The kit verification line for {@code /null status}, or null when it is right. */
    public String kitProblem() {
        if (commander == null || plugin.kits() == null) {
            return null;
        }
        return plugin.kits().verify(commander);
    }

    /**
     * Applies the runtime Commander name. The current body keeps its existing
     * profile name until the next summon; chat addressing changes immediately.
     * The owner-edited loadout remains isolated in {@code commander.yml}.
     */
    @Override
    public void onConfigReloaded(PluginConfig config) {
        if (config == null || config.file() == null) {
            return;
        }
        String freshName = normalizedName(config.file().getString("commander.name", DEFAULT_NAME));
        name = freshName == null ? DEFAULT_NAME : freshName;
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
        // Creating or cleaning an NPC is main-thread work only (spec 2.4); do
        // not mutate the active owner's identity before this guard.
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
        // A naturally dead Commander can remain referenced until the next
        // command. Remove that stale ServerPlayer before registering a replacement.
        if (commander != null) {
            NullBody stale = commander;
            if (!Guard.attempt(plugin.getLogger(), "cleaning up the dead Commander", stale::destroy)) {
                owner.sendMessage(PREFIX + "The previous Commander could not be cleaned up safely;"
                        + " check the server log before trying again.");
                return false;
            }
            if (commander == stale) {
                commander = null;
            }
            ownerId = null;
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

        // A real doorway first: the Commander should walk out of the same kind of
        // temporary portal the squad uses, not out of a particle effect. If no
        // site can be built, verified open ground is used and the message says so.
        Vec3d spot = null;
        boolean doorway = false;
        if (plugin.portals() != null && plugin.pluginConfig() != null
                && plugin.pluginConfig().commanderSpawnWithPortal()) {
            java.util.List<redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal> built =
                    plugin.portals().buildDoorways(world,
                            new Vec3d(origin.getX(), Math.floor(origin.getY()), origin.getZ()), 1);
            if (!built.isEmpty()) {
                spot = plugin.portals().takeExit(built.get(0), 0);
                doorway = spot != null;
            }
        }
        if (spot == null) {
            spot = findSafeSpot(adapter, world, origin);
        }
        if (spot == null) {
            owner.sendMessage(PREFIX + "No safe ground nearby for the Commander to step onto -"
                    + " move to open ground and try again.");
            return false;
        }
        final boolean arrivedThroughDoorway = doorway;

        SkinData resolvedSkin = plugin.skinChain() == null ? null : plugin.skinChain().current(true);
        String value = (resolvedSkin != null && resolvedSkin.complete()) ? resolvedSkin.value() : "";
        String signature = (resolvedSkin != null && resolvedSkin.complete()) ? resolvedSkin.signature() : "";

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
        // A returned object is not a spawn: the Commander has to be alive, have a
        // packet listener, and be tracked by the server, or nobody would see it.
        String problem = null;
        if (!adapter.packetListenerReady(commander)) {
            problem = "it has no packet listener, so it was removed instead of registered";
        } else if (!commander.isAlive()) {
            problem = "it was not alive after registration";
        } else if (!adapter.isTracked(commander)) {
            problem = "the server is not tracking it, so no client could see it";
        }
        if (problem != null) {
            final redglitchx.nullarmy.nms.NullBody broken = commander;
            commander = null;
            Guard.attempt(plugin.getLogger(), "removing an unusable Commander", broken::destroy);
            owner.sendMessage(PREFIX + "The Commander could not be summoned: " + problem + ".");
            plugin.getLogger().severe("[NullArmy] Commander registration failed: " + problem);
            return false;
        }

        // Ownership becomes live only after the adapter has fully registered,
        // tracked and validated the body.
        ownerId = owner.getUniqueId();

        // The portal effects are the last, cosmetic part of the entrance: they may
        // fail without losing the Commander that already exists and was verified.
        final Vec3d arrivalSpot = spot;
        Guard.attempt(plugin.getLogger(), "Commander portal effects", () -> {
            PluginConfig current = plugin.pluginConfig();
            int effects = current == null || current.portalParticlesEnabled()
                    ? Math.max(Caps.minPortalEffects(), current == null ? Caps.minPortalEffects()
                            : current.caps().portalEffectsPerSummon()) : 0;
            adapter.playPortalEffects(world, arrivalSpot, effects);
        });

        Guard.attempt(plugin.getLogger(), "Commander loadout", this::applyLoadout);
        owner.sendMessage(PREFIX + (arrivedThroughDoorway
                ? "The Commander steps out of a real doorway; it closes on its own shortly."
                : "No doorway site was clear, so the Commander stepped onto verified open"
                        + " ground with portal effects instead."));
        String kitProblem = plugin.kits() == null ? null : plugin.kits().verify(commander);
        owner.sendMessage(PREFIX + (kitProblem == null
                ? "Loadout verified on the body. Use /null loadout to edit it."
                : "Loadout is incomplete: " + kitProblem + ". Use /null loadout to edit it."));
        return true;
    }

    /** Sends the saved loadout to the spawned Commander. */
    private void applyLoadout() {
        if (commander == null) {
            return;
        }
        // The owner's saved loadout is applied as full items - enchantments,
        // potions, names and all - straight into the body's inventory. The old
        // LoadoutSlot path carried only material and count, which silently turned
        // an owner's enchanted gear into plain items.
        org.bukkit.entity.Player handle = redglitchx.nullarmy.plugin.body.Bodies.player(commander);
        if (handle != null) {
            org.bukkit.inventory.PlayerInventory inv = handle.getInventory();
            ItemStack[] equipped = new ItemStack[redglitchx.nullarmy.plugin.body.Bodies.SLOTS];
            for (int i = 0; i < loadout.length && i < equipped.length; i++) {
                ItemStack stack = loadout[i];
                if (stack == null || stack.getType() == null || stack.getType().isAir()) {
                    continue;
                }
                ItemStack item = stack.clone();
                KitItems.withoutArmorTrim(item);
                if (i == 38) {
                    KitItems.withCommanderChestplateTrim(item);
                }
                equipped[i] = item;
            }
            redglitchx.nullarmy.plugin.body.Bodies.apply(inv, equipped);
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
        if (body != null && plugin.brain() != null) {
            plugin.brain().combat().stopFlight(body, redglitchx.nullarmy.plugin.body.Bodies.player(body));
        }
        commander = null;
        ownerId = null;
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

    /** Picks a supported, vanilla-executed melee technique for the Commander. */
    public PvpArsenal.Technique plan(CombatSituation situation) {
        return PvpArsenal.selectSupportedMelee(situation);
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
