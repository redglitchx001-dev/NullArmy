package redglitchx.nullarmy.plugin.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code /null menu} screen: a real inventory GUI, not a chat menu.
 *
 * <p>It follows the pattern already proven by
 * {@code CommanderInventoryGui}: a plain chest inventory owned by an
 * {@link InventoryHolder}, read-only buttons, and clicks handled by a listener
 * that cancels everything. No GUI library, no ProtocolLib, nothing to
 * download.</p>
 *
 * <p>Two properties matter more than the layout:</p>
 * <ul>
 *   <li><b>Nothing can be taken.</b> Every slot is filled (glass panes as
 *       filler) and every click and drag is cancelled, so the menu can never
 *       become an item duplicator or a way to lose items.</li>
 *   <li><b>A button is never a bypass.</b> Each button dispatches the same
 *       command through the same executor, so the permission check, the policy
 *       gates and the safety wrappers are identical to typing it.</li>
 * </ul>
 *
 * <p>Buttons the player may not use - and the gated Wither Cannon and Airdrop
 * entries while their config switches are off - are hidden rather than shown
 * and refused, so the screen always matches reality.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MenuGui implements InventoryHolder {

    /** A double chest: a status header, 36 action slots and one navigation row. */
    public static final int SIZE = 54;

    /** The header row is reserved for live status cards; commands fill rows 2-5. */
    public static final int CONTENT_START = 9;

    /** Buttons per page. The bottom row is reserved for navigation. */
    public static final int PAGE_SIZE = 36;

    public static final int BUTTON_BACK = 45;
    public static final int BUTTON_CLOSE = 49;
    public static final int BUTTON_PAGE = 52;
    public static final int BUTTON_NEXT = 53;

    /** One clickable entry. {@code action} is the {@code /null ...} argument list. */
    public static final class Button {
        private final int slot;
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final String[] action;
        private final String permission;

        Button(int slot, Material material, String name, List<String> lore,
               String permission, String... action) {
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.action = action;
            this.permission = permission;
        }

        public int slot() { return slot; }
        public Material material() { return material; }
        public String name() { return name; }
        public List<String> lore() { return lore; }
        public String[] action() { return action; }
        public String permission() { return permission; }
    }

    private final NullArmyPlugin plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final List<Button> buttons = new ArrayList<>();
    private int page;

    /**
     * Builds the screen for one viewer.
     *
     * <p>It is one instance per open, which is what makes permission filtering
     * possible: a player never sees a button they cannot use, so the GUI cannot
     * advertise a command that will refuse them.</p>
     */
    public MenuGui(NullArmyPlugin plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        Component title = PluginText.gradientComponent(titleText(plugin));
        // Server#createInventory(InventoryHolder, int, Component) is the
        // documented 1.21.11 signature; the String-title overload is deprecated.
        this.inventory = plugin.getServer().createInventory(this, SIZE, title);
        buildButtons();
        paint();
    }

    /** The player this screen was built for. */
    public Player viewer() { return viewer; }

    private static String titleText(NullArmyPlugin plugin) {
        try {
            PluginConfig config = plugin.pluginConfig();
            if (config != null && config.menuTitle() != null && !config.menuTitle().isEmpty()) {
                return config.menuTitle();
            }
        } catch (Throwable ignored) {
            // Fall through to the default title.
        }
        return "NullArmy - Command Center";
    }

    /**
     * Every entry, in the order it should appear.
     *
     * <p>Permissions mirror the typed commands exactly. A button with a null
     * permission is always shown (its command still checks for itself).</p>
     */
    private void buildButtons() {
        buttons.clear();

        add(Material.GOAT_HORN, "Call Horn · Null", "Get the Call Goat Horn named Null.",
                "nullarmy.summon", "horn");
        add(Material.TOTEM_OF_UNDYING, "Totem Of Null", "Get the totem trigger item named Null.",
                "nullarmy.summon", "totem");
        add(Material.PLAYER_HEAD, "Commander", "Summon the Null Commander out of a portal.",
                "nullarmy.commander", "commander");
        add(Material.CHEST, "Loadout", "Edit the Commander's 41-slot loadout.",
                "nullarmy.gui", "loadout");
        add(Material.LEATHER_BOOTS, "Follow", "Your Nulls walk to you (never teleport).",
                "nullarmy.follow", "follow");
        add(Material.DIAMOND_SWORD, "Attack", "Set a physical pursuit objective.",
                "nullarmy.attack", "attack");
        add(Material.NETHERITE_SWORD, "AttackX", "Extreme-combat profile for the squad.",
                "nullarmy.attackx", "attackx");
        add(Material.BRICKS, "Build", "Bounded, inventory-funded building.",
                "nullarmy.build", "build");
        add(Material.SHIELD, "Guard", "Hold position and watch.",
                "nullarmy.follow", "guard");
        add(Material.ARROW, "Formation", "Line, square, encircle or turtle.",
                "nullarmy.follow", "formation");
        add(Material.RED_BED, "Stop", "Stop every Null where it stands.",
                "nullarmy.admin", "stop");
        add(Material.BARRIER, "Dismiss", "Remove all Nulls immediately.",
                "nullarmy.admin", "dismiss");
        add(Material.COMPASS, "Status", "Live squads, caps, adapter, config path.",
                "nullarmy.admin", "status");
        add(Material.BOOK, "Features", "What works, and what needs an AI model.",
                "nullarmy.admin", "features");
        add(Material.NAME_TAG, "Skins", "Configured and resolved skins.",
                "nullarmy.admin", "skin");
        add(Material.PLAYER_HEAD, "List Nulls", "Every live Null with health and position.",
                "nullarmy.admin", "list");
        add(Material.GOLDEN_APPLE, "Heal", "Top up your Nulls' health.",
                "nullarmy.admin", "heal");
        add(Material.DIAMOND_CHESTPLATE, "Equip", "Give your held item to your Nulls.",
                "nullarmy.admin", "equip");
        add(Material.HOPPER, "Drop", "Empty your Nulls' inventories into the world.",
                "nullarmy.admin", "drop");
        add(Material.BLAZE_ROD, "Build Wand", "Region-select tool for /null build.",
                "nullarmy.build", "wand");
        add(Material.ENDER_PEARL, "Portals", "Play the portal visual where you stand.",
                "nullarmy.admin", "portals");
        add(Material.CLOCK, "Reload", "Re-read config.yml without a restart.",
                "nullarmy.admin", "reload");
        add(Material.MILK_BUCKET, "Clear Skins", "Forget cached skins and re-resolve.",
                "nullarmy.admin", "clearskins");
        add(Material.WRITABLE_BOOK, "Version", "Plugin, adapter and server version.",
                null, "version");
        add(Material.REDSTONE_TORCH, "Debug", "Diagnostics, guard state, entity list.",
                "nullarmy.admin", "debug");
        add(Material.PAPER, "Help", "Every subcommand with its usage line.",
                null, "help");

        // Gated entries are only present when the feature is really available.
        PluginConfig config = plugin.pluginConfig();
        if (config != null && config.witherCannonUsable()) {
            add(Material.TNT_MINECART, "Wither Cannon", "Fire a TNT minecart into a sky portal.",
                    config.witherCannonPermission(), "withercannon");
        }
        if (config != null && config.airdropEnabled()) {
            add(Material.FIREWORK_ROCKET, "Air Drop", "Sky portals deliver a squad.",
                    config.airdropPermission(), "airdrop");
        }
    }

    private void add(Material material, String name, String lore, String permission, String... action) {
        if (permission != null && viewer != null && !viewer.hasPermission(permission)) {
            // Hidden, not shown-and-refused: the screen must match reality.
            return;
        }
        int slot = CONTENT_START + (buttons.size() % PAGE_SIZE);
        List<String> lines = new ArrayList<>();
        lines.add(lore);
        lines.add(" ");
        lines.add("Click to run /null " + String.join(" ", action));
        buttons.add(new Button(slot, material, name, lines, permission, action));
    }

    /** Repaints the whole inventory for the current page. */
    public void paint() {
        inventory.clear();

        // Every slot is protected by a decorative pane before real content is
        // laid out; the menu can never become a place to put or lose items.
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler(slot));
        }

        // A dashboard header makes the screen useful at a glance, not just a
        // wall of commands. These cards are informational and read-only.
        inventory.setItem(1, liveSquadCard());
        inventory.setItem(4, brandCard());
        inventory.setItem(7, systemCard());

        int start = page * PAGE_SIZE;
        int end = Math.min(buttons.size(), start + PAGE_SIZE);
        int slot = CONTENT_START;
        for (int i = start; i < end; i++) {
            Button button = buttons.get(i);
            inventory.setItem(slot++, item(button.material(), button.name(), button.lore()));
        }

        if (page > 0) {
            inventory.setItem(BUTTON_BACK, item(Material.ARROW, "Previous page",
                    List.of("Page " + page + " of " + pageCount())));
        }
        if (end < buttons.size()) {
            inventory.setItem(BUTTON_NEXT, item(Material.SPECTRAL_ARROW, "Next page",
                    List.of("Page " + (page + 2) + " of " + pageCount())));
        }
        inventory.setItem(BUTTON_CLOSE, item(Material.BARRIER, "Close",
                List.of("Close the command center.")));
        inventory.setItem(BUTTON_PAGE, item(Material.CLOCK,
                "Page " + (page + 1) + " of " + pageCount(),
                List.of(buttons.size() + " available actions")));
    }

    private ItemStack liveSquadCard() {
        int live = plugin.squads() == null ? 0 : plugin.squads().liveCount();
        int max = plugin.pluginConfig() == null ? 0 : plugin.pluginConfig().caps().maxLiveNpcs();
        int pending = plugin.summonFlow() == null ? 0 : plugin.summonFlow().pendingCount();
        return item(Material.WITHER_SKELETON_SKULL,
                Component.text("LIVE SQUAD").color(TextColor.color(0x5EEAD4))
                        .decoration(TextDecoration.BOLD, true),
                List.of(loreLine("Nulls deployed: " + live + " / " + max, NamedTextColor.WHITE),
                        loreLine("Open call prompts: " + pending, NamedTextColor.GRAY)));
    }

    private ItemStack brandCard() {
        return item(Material.NETHER_STAR, PluginText.gradientComponent("NULLARMY"),
                List.of(loreLine("COMMAND CENTER", TextColor.color(0xD8B4FE)),
                        loreLine("Choose an action below.", NamedTextColor.GRAY)));
    }

    private ItemStack systemCard() {
        boolean paused = plugin.spawnBreaker() != null && plugin.spawnBreaker().isOpen();
        String state = paused ? "SPAWN GUARD: PAUSED" : "SPAWN GUARD: READY";
        Material icon = paused ? Material.BARRIER : Material.SHIELD;
        TextColor stateColor = paused ? NamedTextColor.RED : NamedTextColor.GREEN;
        return item(icon,
                Component.text("SYSTEM STATUS").color(stateColor)
                        .decoration(TextDecoration.BOLD, true),
                List.of(loreLine(state, stateColor),
                        loreLine("Destructive features remain opt-in.", NamedTextColor.GRAY)));
    }

    private static Component loreLine(String text, TextColor color) {
        return Component.text(text).color(color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack filler(int slot) {
        int row = slot / 9;
        int column = slot % 9;
        Material material;
        if (row == 0 || row == 5) {
            material = Material.PURPLE_STAINED_GLASS_PANE;
        } else if (column == 0 || column == 8) {
            material = Material.MAGENTA_STAINED_GLASS_PANE;
        } else {
            material = Material.BLACK_STAINED_GLASS_PANE;
        }
        return item(material, " ", List.of());
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (line.startsWith("Click to run ")) {
                lines.add(PluginText.gradientComponent(line)
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lines.add(loreLine(line, NamedTextColor.GRAY));
            }
        }
        Component displayName = Component.text(name)
                .color(NamedTextColor.WHITE)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false);
        return item(material, displayName, lines);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.customName(name.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The button in a content slot on the current page, or null. */
    public Button buttonAt(int rawSlot) {
        if (rawSlot < CONTENT_START || rawSlot >= CONTENT_START + PAGE_SIZE) {
            return null;
        }
        int index = page * PAGE_SIZE + (rawSlot - CONTENT_START);
        if (index < 0 || index >= buttons.size()) {
            return null;
        }
        return buttons.get(index);
    }

    public int page() { return page; }

    public int pageCount() {
        return Math.max(1, (buttons.size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    /** Moves one page in either direction. Returns true when the page changed. */
    public boolean turnPage(int delta) {
        int target = page + delta;
        if (target < 0 || target >= pageCount()) {
            return false;
        }
        page = target;
        paint();
        return true;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Opens the screen for a player. */
    public void open(Player viewer) {
        viewer.openInventory(inventory);
    }
}
