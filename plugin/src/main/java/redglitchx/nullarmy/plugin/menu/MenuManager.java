package redglitchx.nullarmy.plugin.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.command.NullCommand;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.Arrays;

/**
 * Click handling for {@link MenuGui}.
 *
 * <p>Three guarantees, in order of importance:</p>
 * <ol>
 *   <li><b>Nothing can be taken.</b> Every click and every drag is cancelled,
 *       including shift-clicks and clicks inside the player's own inventory
 *       while the menu is open, so the screen cannot duplicate, leak or
 *       swallow an item.</li>
 *   <li><b>Nothing can throw.</b> The whole handler is wrapped, and each
 *       dispatched action is wrapped separately, so a failing command leaves
 *       the menu usable and the server untouched.</li>
 *   <li><b>Buttons are never a bypass.</b> Clicks dispatch the same
 *       {@code /null ...} arguments through the same executor as typing them,
 *       so permissions and policy gates apply identically.</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MenuManager implements Listener, Reloadable {

    private final NullArmyPlugin plugin;
    private final NullCommand command;

    public MenuManager(NullArmyPlugin plugin, NullCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    @Override
    public void onConfigReloaded(PluginConfig config) {
        // Nothing cached: the menu is rebuilt per open, so a reload is picked
        // up automatically the next time a player opens it.
    }

    /** Opens the menu for a player. */
    public void open(Player player) {
        if (player == null) {
            return;
        }
        Guard.attempt(plugin.getLogger(), "opening the menu", () ->
                new MenuGui(plugin, player).open(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        Guard.attempt(plugin.getLogger(), "menu click", () -> handleClick(event));
    }

    private void handleClick(InventoryClickEvent event) {
        InventoryHolder holder = holderOf(event);
        if (!(holder instanceof MenuGui)) {
            return;
        }
        // 1. The menu is read-only, always.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        MenuGui menu = (MenuGui) holder;
        int slot = event.getRawSlot();

        // Clicks in the player's own inventory are cancelled too (nothing may
        // move while the menu is open) but do nothing else.
        if (slot < 0 || slot >= MenuGui.SIZE) {
            return;
        }

        if (slot == MenuGui.BUTTON_CLOSE) {
            player.closeInventory();
            return;
        }
        if (slot == MenuGui.BUTTON_BACK) {
            menu.turnPage(-1);
            return;
        }
        if (slot == MenuGui.BUTTON_NEXT) {
            menu.turnPage(1);
            return;
        }
        if (slot == MenuGui.BUTTON_PAGE) {
            return;
        }

        MenuGui.Button button = menu.buttonAt(slot);
        if (button == null) {
            return;
        }
        dispatch(player, button);
    }

    /** Runs the button's command with the same checks a typed command gets. */
    private void dispatch(Player player, MenuGui.Button button) {
        String[] action = button.action();
        if (action == null || action.length == 0) {
            return;
        }
        final String[] args = Arrays.copyOf(action, action.length);
        // A "menu" button just closes the screen: reopening it would close the
        // one the player is standing in.
        if (args.length == 1 && args[0].equalsIgnoreCase("menu")) {
            player.closeInventory();
            return;
        }
        Guard.attempt(plugin.getLogger(), "menu action /null " + String.join(" ", args), () -> {
            if (command == null) {
                player.sendMessage(PluginText.PREFIX + "That action is unavailable: the command is not wired.");
                return;
            }
            command.dispatch(player, args);
            // Refresh so state changes (a dismissed squad, a reloaded config)
            // are visible without closing and reopening the screen.
            refresh(player);
        });
    }

    /** Repaints the open screen in place. */
    private void refresh(Player player) {
        try {
            InventoryHolder holder = player.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof MenuGui) {
                ((MenuGui) holder).paint();
            }
        } catch (Throwable ignored) {
            // A closed or replaced view needs nothing.
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        Guard.attempt(plugin.getLogger(), "menu drag", () -> {
            if (holderOf(event.getView() == null ? null : event.getView().getTopInventory())
                    instanceof MenuGui) {
                event.setCancelled(true);
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Closing is always safe: the working state lives in the inventory
        // itself, so there is nothing to commit and nothing to lose.
        Guard.attempt(plugin.getLogger(), "menu close", () -> {
            // Intentionally empty.
        });
    }

    private static InventoryHolder holderOf(InventoryClickEvent event) {
        if (event == null) {
            return null;
        }
        try {
            if (event.getInventory() == null) {
                return null;
            }
            return event.getInventory().getHolder();
        } catch (Throwable t) {
            return null;
        }
    }

    private static InventoryHolder holderOf(org.bukkit.inventory.Inventory inventory) {
        return inventory == null ? null : inventory.getHolder();
    }
}
