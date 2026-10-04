package redglitchx.nullarmy.plugin.kit;

import org.bukkit.inventory.ItemStack;

import redglitchx.nullarmy.core.kit.DefaultKit;
import redglitchx.nullarmy.nms.LoadoutSlot;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.commander.CommanderManager;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default equipment for the Commander and for every Null.
 *
 * <p>Iron chestplate, shield in the offhand, and a hotbar of iron sword, bow,
 * arrows, golden apples, cooked food, iron pickaxe, ender pearls, water bucket
 * and torches. Slot numbers are the real player-inventory numbers, so armour and
 * the offhand go to the slots Bukkit will not write through {@code setItem}.</p>
 *
 * <h2>Three rules this class exists to keep</h2>
 * <ul>
 *   <li><b>Verified, not assumed.</b> After writing a kit the body is read back
 *       through the adapter's own loadout view. A menu that shows a chestplate is
 *       not a Null wearing one; {@link #verify} is what says so.</li>
 *   <li><b>Never duplicated.</b> Only the slots that are empty or hold something
 *       else are written, so re-applying a kit cannot stack a second sword into a
 *       slot that already has one, and cannot wipe what an owner arranged.</li>
 *   <li><b>The Commander keeps an owner's edit.</b> The default kit is installed
 *       into {@code commander.yml} only when no Commander loadout is saved yet -
 *       a fresh install, or a file that was deleted. An edited loadout is left
 *       exactly as it was saved.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class KitService implements Reloadable {

    private final NullArmyPlugin plugin;
    private List<DefaultKit.Item> kit = DefaultKit.DEFAULT;
    private final List<String> configErrors = new ArrayList<>();

    public KitService(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        apply(config);
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        apply(fresh);
    }

    private void apply(PluginConfig config) {
        configErrors.clear();
        if (config == null) {
            kit = DefaultKit.DEFAULT;
            return;
        }
        List<String> lines = config.defaultKitLines();
        List<String> notes = new ArrayList<>();
        kit = DefaultKit.resolveConfigured(lines, configErrors, notes);
        for (String error : configErrors) {
            plugin.getLogger().warning("[NullArmy] " + error);
        }
        for (String note : notes) {
            plugin.getLogger().info("[NullArmy] " + note);
        }
    }

    /** The kit as version-neutral loadout slots. Never null. */
    public List<LoadoutSlot> slots() {
        List<LoadoutSlot> out = new ArrayList<>();
        for (DefaultKit.Item item : kit) {
            try {
                out.add(LoadoutSlot.of(item.slot(), item.material(), item.count()));
            } catch (Throwable t) {
                // An impossible slot or a blank material is skipped, never fatal.
                plugin.getLogger().fine("[NullArmy] kit entry skipped: " + Guard.describe(t));
            }
        }
        return out;
    }

    /** The kit in config form, for {@code /null kit} and the docs. */
    public List<String> configLines() {
        return DefaultKit.serialize(kit);
    }

    /** Config problems found while parsing, for {@code /null debug}. */
    public List<String> configErrors() {
        return new ArrayList<>(configErrors);
    }

    public String describe() {
        return DefaultKit.describe(kit);
    }

    /**
     * Puts the missing parts of the kit on a body.
     *
     * @return true when every kit slot now holds the right material
     */
    public boolean applyTo(NullBody body) {
        if (body == null) {
            return false;
        }
        try {
            Map<Integer, String> occupied = occupiedSlots(body);
            List<DefaultKit.Item> missing = DefaultKit.missingFrom(kit, occupied);
            if (missing.isEmpty()) {
                // Already equipped: writing nothing is how a kit stops duplicating.
                return verify(body) == null;
            }
            org.bukkit.entity.Player handle = redglitchx.nullarmy.plugin.body.Bodies.player(body);
            if (handle != null) {
                // Full items - enchantments, potion types, counts - straight into
                // the body's real inventory.
                List<String> problems = new ArrayList<>();
                org.bukkit.inventory.PlayerInventory inv = handle.getInventory();
                for (DefaultKit.Item item : missing) {
                    ItemStack stack = KitItems.toStack(item, problems);
                    if (stack != null) {
                        redglitchx.nullarmy.plugin.body.Bodies.set(inv, item.slot(), stack);
                    }
                }
                for (String problem : problems) {
                    plugin.getLogger().fine("[NullArmy] kit: " + problem);
                }
                return verify(body) == null;
            }
            List<LoadoutSlot> slots = new ArrayList<>();
            for (DefaultKit.Item item : missing) {
                slots.add(LoadoutSlot.of(item.slot(), item.material(), item.count()));
            }
            body.setLoadout(slots);
            return verify(body) == null;
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] kit application failed: " + Guard.describe(t));
            return false;
        }
    }

    /** The kit entries, for checks and the GUI. */
    public List<DefaultKit.Item> items() {
        return kit;
    }

    /**
     * Reads the body back and says what is wrong with it.
     *
     * @return null when the kit is really equipped, otherwise the reason
     */
    public String verify(NullBody body) {
        if (body == null) {
            return "no body";
        }
        Map<Integer, String> occupied;
        try {
            occupied = occupiedSlots(body);
        } catch (Throwable t) {
            return "the inventory could not be read (" + Guard.describe(t) + ")";
        }
        List<String> problems = new ArrayList<>();
        for (DefaultKit.Item item : kit) {
            String held = occupied.get(item.slot());
            if (held == null) {
                problems.add("slot " + item.slot() + " is empty (expected " + item.material() + ")");
            } else if (!held.equalsIgnoreCase(item.material())) {
                problems.add("slot " + item.slot() + " holds " + held
                        + " (expected " + item.material() + ")");
            }
        }
        if (problems.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < problems.size() && i < 3; i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(problems.get(i));
        }
        if (problems.size() > 3) {
            sb.append("; +").append(problems.size() - 3).append(" more");
        }
        return sb.toString();
    }

    /** Slot -&gt; material name, read from the real NMS-backed inventory. */
    private Map<Integer, String> occupiedSlots(NullBody body) {
        Map<Integer, String> out = new LinkedHashMap<>();
        List<LoadoutSlot> loadout = body.loadout();
        if (loadout == null) {
            return out;
        }
        for (LoadoutSlot slot : loadout) {
            if (slot != null) {
                out.put(slot.slot(), slot.material());
            }
        }
        return out;
    }

    /**
     * Installs the default kit as the Commander's saved loadout.
     *
     * <p>Only when the Commander has nothing saved: a fresh install, or an owner
     * who deleted {@code commander.yml}. An edited loadout is never touched.</p>
     *
     * @return true when the Commander's loadout was written
     */
    public boolean installCommanderDefault(CommanderManager commander) {
        if (commander == null || commander.hasSavedLoadout()) {
            return false;
        }
        ItemStack[] loadout = commander.loadout();
        if (loadout == null) {
            return false;
        }
        for (DefaultKit.Item item : kit) {
            if (item.slot() < 0 || item.slot() >= loadout.length) {
                continue;
            }
            List<String> problems = new ArrayList<>();
            ItemStack stack = KitItems.toStack(item, problems);
            if (stack == null) {
                plugin.getLogger().warning("[NullArmy] default kit entry '" + item.toConfig()
                        + "' could not be built on this server - skipped (" + problems + ").");
                continue;
            }
            loadout[item.slot()] = stack;
        }
        try {
            commander.save();
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] the Commander's default kit could not be"
                    + " saved to commander.yml: " + Guard.describe(t));
            return false;
        }
        plugin.getLogger().info("[NullArmy] no Commander loadout was saved, so the default kit ("
                + describe() + ") was installed into commander.yml.");
        return true;
    }
}
