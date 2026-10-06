package redglitchx.nullarmy.plugin.body;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.util.PluginText;

import java.util.Locale;

/**
 * Lets the vanilla {@code /kill <NullName>} command target Null player bodies
 * without claiming or changing {@code /kill} for ordinary players.
 */
public final class NullKillBridge implements Listener {

    private final NullArmyPlugin plugin;

    public NullKillBridge(NullArmyPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Guard.attempt(plugin.getLogger(), "resolving /kill for a Null", () ->
                intercept(event.getPlayer(), event.getMessage(), event::setCancelled));
    }

    /** Also supports an operator issuing {@code kill <NullName>} from console. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        Guard.attempt(plugin.getLogger(), "resolving console kill for a Null", () ->
                intercept(event.getSender(), event.getCommand(), event::setCancelled));
    }

    private void intercept(CommandSender sender, String rawCommand, java.util.function.Consumer<Boolean> cancel) {
        if (sender == null || rawCommand == null) {
            return;
        }
        String command = rawCommand.trim();
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        String[] words = command.isEmpty() ? new String[0] : command.split("\\s+");
        if (words.length != 2 || !(words[0].equalsIgnoreCase("kill")
                || words[0].equalsIgnoreCase("minecraft:kill"))) {
            return;
        }
        // Real players retain vanilla's exact /kill behavior, even if a Null
        // happens to have an old duplicate profile name.
        if (isRealPlayerName(words[1])) {
            return;
        }
        NullBody target = findNull(words[1]);
        if (target == null) {
            return;
        }
        if (!sender.hasPermission("minecraft.command.kill") && !sender.isOp()) {
            return; // mirror vanilla /kill's permission gate for the Null-only bridge
        }
        cancel.accept(true);
        Player body = Bodies.player(target);
        if (body == null || !body.isValid() || body.isDead()) {
            sender.sendMessage(PluginText.PREFIX + "That Null is no longer alive.");
            return;
        }
        String name = target.profileName();
        body.damage(1.0E6D);
        if (body.isValid() && !body.isDead() && body.getHealth() > 0.0D) {
            sender.sendMessage(PluginText.PREFIX + "The kill did not reach " + name
                    + "; another plugin cancelled its damage.");
        } else {
            sender.sendMessage(PluginText.PREFIX + "Killed Null " + name + ".");
        }
    }

    private boolean isRealPlayerName(String wanted) {
        if (wanted == null) {
            return false;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online == null || online.getName() == null || !online.getName().equalsIgnoreCase(wanted)) {
                continue;
            }
            if (plugin.adapter() == null || !plugin.adapter().isNullEntity(online.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    private NullBody findNull(String wanted) {
        if (wanted == null || plugin.squads() == null) {
            return null;
        }
        String key = wanted.toLowerCase(Locale.ROOT);
        for (NullBody body : plugin.squads().allMembers()) {
            if (body != null && body.profileName() != null
                    && body.profileName().toLowerCase(Locale.ROOT).equals(key) && body.isAlive()) {
                return body;
            }
        }
        NullBody commander = plugin.commander() == null ? null : plugin.commander().body();
        if (commander != null && commander.profileName() != null
                && commander.profileName().toLowerCase(Locale.ROOT).equals(key) && commander.isAlive()) {
            return commander;
        }
        return null;
    }
}
