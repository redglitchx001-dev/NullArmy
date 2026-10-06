package redglitchx.nullarmy.plugin.loadout;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.body.Bodies;
import redglitchx.nullarmy.plugin.kit.KitItems;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One themed loadout editor for the Commander and for every Null, plus saved
 * templates, persisted in {@code nulls.yml}.
 *
 * <h2>Layout (54 slots)</h2>
 * <pre>
 *   row 0: helmet chest legs boots offhand  .  .  [save] [info]
 *   rows 1-3: storage 9-35
 *   row 4: hotbar 0-8
 *   row 5: frame, title in the middle
 * </pre>
 *
 * <h2>Transactional, and duplication-proof</h2>
 * <p>A Null's (or the Commander's) items are <b>moved</b>, never copied: what the
 * editor shows is what the body carries, and what is left in the editor when it
 * closes - by the save button or by closing it - is written back to the body in
 * one go. If the body is gone by then, everything in the editor is handed back
 * to the editor's user, so nothing is lost and nothing can be duplicated.
 * Templates are saved from, and applied to, live loadouts; viewing one is
 * read-only for the same reason.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class LoadoutService implements Listener {

    /** GUI slot of each player slot 0..40. */
    static final int[] GUI_OF = new int[41];
    /** Player slot of each GUI slot, or -1. */
    static final int[] SLOT_OF = new int[54];

    static final int SAVE_BUTTON = 7;

    /** The editor slot that shows player slot {@code slot} (0-40). */
    public static int guiSlotOf(int slot) {
        return slot < 0 || slot > 40 ? -1 : GUI_OF[slot];
    }
    static final int INFO_ITEM = 8;

    static {
        java.util.Arrays.fill(SLOT_OF, -1);
        int[] top = {39, 38, 37, 36, 40};
        for (int i = 0; i < top.length; i++) {
            GUI_OF[top[i]] = i;
        }
        for (int slot = 9; slot <= 35; slot++) {
            GUI_OF[slot] = slot;
        }
        for (int slot = 0; slot <= 8; slot++) {
            GUI_OF[slot] = 36 + slot;
        }
        for (int slot = 0; slot <= 40; slot++) {
            SLOT_OF[GUI_OF[slot]] = slot;
        }
    }

    /** What an editor edits. */
    public interface Target {
        String label();

        /** The current 41 slots (copies), or null when the target is gone. */
        ItemStack[] read();

        /** Writes all 41 slots; false when the target is gone. */
        boolean write(ItemStack[] slots);

        /** True when the editor may move items in and out (false: read-only preview). */
        default boolean editable() { return true; }
    }

    /** One open editor. */
    public final class Editor implements InventoryHolder {
        private final Target target;
        private final Inventory inventory;
        private boolean committed;

        Editor(Target target) {
            this.target = target;
            this.inventory = Bukkit.createInventory(this, 54,
                    PluginText.gradientComponent("Loadout").append(Component.text(" - " + target.label(),
                            NamedTextColor.GRAY)));
            paint();
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }

        public Target target() { return target; }

        private void paint() {
            ItemStack frame = named(Material.PURPLE_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < 54; i++) {
                if (SLOT_OF[i] < 0) {
                    inventory.setItem(i, frame);
                }
            }
            inventory.setItem(SAVE_BUTTON, target.editable()
                    ? named(Material.LIME_CONCRETE, "Save and close", "Writes this loadout to " + target.label())
                    : named(Material.GRAY_CONCRETE, "Read-only template", "Use /null loadout template <name> apply"));
            inventory.setItem(INFO_ITEM, named(Material.BOOK, target.label(),
                    "Top row: helmet, chestplate, leggings, boots, offhand",
                    "Rows 2-4: storage, row 5: hotbar"));
            inventory.setItem(49, named(Material.NETHERITE_SWORD, "NullArmy", "Changes apply when you close"));
            ItemStack[] slots = target.read();
            for (int slot = 0; slot <= 40; slot++) {
                ItemStack stack = slots == null ? null : slots[slot];
                inventory.setItem(GUI_OF[slot], stack == null ? null : stack.clone());
            }
        }

        /** The 41 slots as laid out in the editor right now. */
        public ItemStack[] working() {
            ItemStack[] out = new ItemStack[41];
            for (int slot = 0; slot <= 40; slot++) {
                ItemStack stack = inventory.getItem(GUI_OF[slot]);
                out[slot] = stack == null || stack.getType().isAir() ? null : stack.clone();
            }
            return out;
        }

        /**
         * Writes the editor's contents to the target, once. Returns the answer
         * for the user; when the target is gone the items go back to
         * {@code giveBackTo}.
         */
        public String commit(HumanEntity giveBackTo) {
            if (committed) {
                return "already saved";
            }
            committed = true;
            if (!target.editable()) {
                return "templates are read-only here";
            }
            ItemStack[] slots = working();
            sanitiseArmour(slots);
            boolean ok;
            try {
                ok = target.write(slots);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok) {
                if (giveBackTo != null) {
                    for (ItemStack stack : slots) {
                        if (stack != null) {
                            for (ItemStack overflow : giveBackTo.getInventory().addItem(stack).values()) {
                                giveBackTo.getWorld().dropItemNaturally(giveBackTo.getLocation(), overflow);
                            }
                        }
                    }
                }
                inventory.clear();
                return target.label() + " is gone; everything in the editor was handed back to you";
            }
            return "saved " + target.label();
        }
    }

    private final NullArmyPlugin plugin;
    private final File file;
    private final Map<String, ItemStack[]> templates = new HashMap<>();
    private final Map<String, ItemStack[]> nullLoadouts = new HashMap<>();

    public LoadoutService(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "nulls.yml");
        load();
    }

    // ------------------------------------------------------------------ targets

    /** The editor for a live Null. */
    public Editor forNull(NullBody body) {
        return new Editor(nullTarget(body));
    }

    public Target nullTarget(NullBody body) {
        return new Target() {
            @Override
            public String label() {
                return body.profileName();
            }

            @Override
            public ItemStack[] read() {
                Player handle = Bodies.player(body);
                return handle == null ? null : Bodies.snapshot(handle.getInventory());
            }

            @Override
            public boolean write(ItemStack[] slots) {
                Player handle = Bodies.player(body);
                if (handle == null || !body.isAlive()) {
                    return false;
                }
                ItemStack[] soldierLoadout = withoutArmorTrims(slots);
                Bodies.apply(handle.getInventory(), soldierLoadout);
                nullLoadouts.put(body.profileName().toLowerCase(Locale.ROOT), copy(soldierLoadout));
                save();
                return true;
            }
        };
    }

    /** The editor for the Commander's saved loadout (applied to its body when it is out). */
    public Editor forCommander() {
        return new Editor(commanderTarget());
    }

    public Target commanderTarget() {
        return new Target() {
            @Override
            public String label() {
                return plugin.commander() == null ? "Commander" : plugin.commander().commanderName();
            }

            @Override
            public ItemStack[] read() {
                ItemStack[] saved = plugin.commander() == null ? null : plugin.commander().loadout();
                return saved == null ? new ItemStack[41] : copy(saved);
            }

            @Override
            public boolean write(ItemStack[] slots) {
                if (plugin.commander() == null) {
                    return false;
                }
                ItemStack[] commanderLoadout = withCommanderTrim(slots);
                ItemStack[] saved = plugin.commander().loadout();
                for (int i = 0; i < saved.length && i < commanderLoadout.length; i++) {
                    saved[i] = commanderLoadout[i] == null ? null : commanderLoadout[i].clone();
                }
                Guard.attempt(plugin.getLogger(), "saving the Commander loadout", () -> plugin.commander().save());
                NullBody body = plugin.commander().body();
                Player handle = Bodies.player(body);
                if (handle != null) {
                    Bodies.apply(handle.getInventory(), commanderLoadout);
                }
                return true;
            }
        };
    }

    /** A read-only preview of a template. */
    public Editor forTemplate(String name) {
        final ItemStack[] slots = templates.get(key(name));
        return new Editor(new Target() {
            @Override
            public String label() {
                return "template " + name;
            }

            @Override
            public ItemStack[] read() {
                return slots == null ? new ItemStack[41] : copy(slots);
            }

            @Override
            public boolean write(ItemStack[] ignored) {
                return false;
            }

            @Override
            public boolean editable() {
                return false;
            }
        });
    }

    public void open(Player viewer, Editor editor) {
        if (viewer != null && editor != null) {
            viewer.openInventory(editor.getInventory());
        }
    }

    // ---------------------------------------------------------------- templates

    public String saveTemplate(String name, Target from) {
        ItemStack[] slots = from == null ? null : from.read();
        if (slots == null) {
            return "nothing to save: " + (from == null ? "no source" : from.label() + " is gone");
        }
        templates.put(key(name), copy(slots));
        save();
        return "template " + name + " saved from " + from.label();
    }

    public String applyTemplate(String name, Target to) {
        ItemStack[] slots = templates.get(key(name));
        if (slots == null) {
            return "there is no template called " + name + " (" + templateNames() + ")";
        }
        if (to == null || !to.write(copy(slots))) {
            return (to == null ? "no target" : to.label()) + " could not be changed";
        }
        return "template " + name + " applied to " + to.label();
    }

    public List<String> templateNames() {
        return new ArrayList<>(templates.keySet());
    }

    /** The persisted loadout of a Null by name (copies), or null. */
    public ItemStack[] nullLoadout(String name) {
        ItemStack[] slots = name == null ? null : nullLoadouts.get(name.toLowerCase(Locale.ROOT));
        return slots == null ? null : copy(slots);
    }

    // ---------------------------------------------------------------- persistence

    public void load() {
        templates.clear();
        nullLoadouts.clear();
        if (!file.isFile()) {
            return;
        }
        Guard.attempt(plugin.getLogger(), "reading nulls.yml", () -> {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            readSection(yaml, "templates", templates);
            readSection(yaml, "nulls", nullLoadouts);
        });
    }

    private static void readSection(YamlConfiguration yaml, String root, Map<String, ItemStack[]> into) {
        if (yaml.getConfigurationSection(root) == null) {
            return;
        }
        for (String name : yaml.getConfigurationSection(root).getKeys(false)) {
            ItemStack[] slots = new ItemStack[41];
            for (int i = 0; i < 41; i++) {
                Object raw = yaml.get(root + "." + name + "." + i);
                if (raw instanceof ItemStack) {
                    slots[i] = (ItemStack) raw;
                }
            }
            into.put(name, slots);
        }
    }

    public void save() {
        Guard.attempt(plugin.getLogger(), "writing nulls.yml", () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(java.util.Arrays.asList("NullArmy loadouts: templates and the last saved"
                    + " loadout of each Null. Written by the plugin."));
            write(yaml, "templates", templates);
            write(yaml, "nulls", nullLoadouts);
            if (!plugin.getDataFolder().isDirectory()) {
                plugin.getDataFolder().mkdirs();
            }
            yaml.save(file);
        });
    }

    private static void write(YamlConfiguration yaml, String root, Map<String, ItemStack[]> from) {
        for (Map.Entry<String, ItemStack[]> entry : from.entrySet()) {
            for (int i = 0; i < 41 && i < entry.getValue().length; i++) {
                if (entry.getValue()[i] != null) {
                    yaml.set(root + "." + entry.getKey() + "." + i, entry.getValue()[i]);
                }
            }
        }
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Editor)) {
            return;
        }
        Editor editor = (Editor) event.getInventory().getHolder();
        boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < 54;
        if (!editor.target().editable()) {
            event.setCancelled(true);
            return;
        }
        if (top) {
            int raw = event.getRawSlot();
            if (raw == SAVE_BUTTON) {
                event.setCancelled(true);
                HumanEntity who = event.getWhoClicked();
                String answer = editor.commit(who);
                Bukkit.getScheduler().runTask(plugin, () -> who.closeInventory());
                if (plugin.chatGate() != null) {
                    plugin.chatGate().answer(who, answer);
                }
                return;
            }
            if (SLOT_OF[raw] < 0) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof Editor)) {
            return;
        }
        Editor editor = (Editor) event.getInventory().getHolder();
        if (!editor.target().editable()) {
            event.setCancelled(true);
            return;
        }
        for (int raw : event.getRawSlots()) {
            if (raw < 54 && SLOT_OF[raw] < 0) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Editor)) {
            return;
        }
        Editor editor = (Editor) event.getInventory().getHolder();
        if (editor.committed || !editor.target().editable()) {
            return;
        }
        String answer = editor.commit(event.getPlayer());
        if (plugin.chatGate() != null) {
            plugin.chatGate().answer(event.getPlayer(), answer);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Armour slots only hold what can be worn there; anything else moves to storage. */
    static void sanitiseArmour(ItemStack[] slots) {
        String[] suffix = {"_BOOTS", "_LEGGINGS", "_CHESTPLATE", "_HELMET"};
        for (int i = 0; i < 4; i++) {
            int slot = 36 + i;
            ItemStack stack = slots[slot];
            if (stack == null) {
                continue;
            }
            String name = stack.getType().name();
            boolean fits = name.endsWith(suffix[i]) || (slot == 39 && (name.endsWith("_HEAD")
                    || name.endsWith("_SKULL") || name.equals("CARVED_PUMPKIN") || name.equals("TURTLE_HELMET")))
                    || (slot == 38 && name.equals("ELYTRA"));
            if (fits) {
                continue;
            }
            for (int s = 9; s <= 35; s++) {
                if (slots[s] == null) {
                    slots[s] = stack;
                    slots[slot] = null;
                    break;
                }
            }
        }
    }

    private static ItemStack[] withoutArmorTrims(ItemStack[] slots) {
        ItemStack[] clean = copy(slots);
        for (int slot = 0; slot < clean.length; slot++) {
            if (clean[slot] != null) {
                KitItems.withoutArmorTrim(clean[slot]);
            }
        }
        return clean;
    }

    private static ItemStack[] withCommanderTrim(ItemStack[] slots) {
        ItemStack[] clean = withoutArmorTrims(slots);
        if (clean[38] != null) {
            KitItems.withCommanderChestplateTrim(clean[38]);
        }
        return clean;
    }

    private static ItemStack[] copy(ItemStack[] slots) {
        ItemStack[] out = new ItemStack[41];
        for (int i = 0; i < 41 && slots != null && i < slots.length; i++) {
            out[i] = slots[i] == null ? null : slots[i].clone();
        }
        return out;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private static ItemStack named(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name, NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lines);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
