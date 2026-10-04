package redglitchx.nullarmy.plugin.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Guarantees that {@code plugins/NullArmy/config.yml} exists and is loaded.
 *
 * <h2>Why this is not just {@code saveDefaultConfig()}</h2>
 * <p>{@code JavaPlugin#saveDefaultConfig()} delegates to
 * {@code saveResource("config.yml", false)}, which throws
 * {@link IllegalArgumentException} when the jar has no such entry - and it
 * throws <b>before</b> it creates the data folder. A jar that lost
 * {@code config.yml} therefore produced exactly this on a real server:</p>
 * <pre>
 *   [ERROR]: Error occurred while enabling NullArmy
 *   java.lang.IllegalArgumentException: The embedded resource 'config.yml' cannot be found
 * </pre>
 * <p>...no {@code plugins/NullArmy/} folder, no commands, no Commander. So the
 * bootstrap below does four things in order, and none of them can abort
 * startup:</p>
 * <ol>
 *   <li>creates the data folder explicitly;</li>
 *   <li>writes the shipped {@code config.yml} only when the file is absent
 *       (an existing file is <b>never</b> overwritten);</li>
 *   <li>if the jar really is missing the resource, writes a short starter
 *       config and says so loudly instead of throwing;</li>
 *   <li>reloads the configuration and logs the absolute path once.</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ConfigBootstrap {

    /** The file name inside the data folder, and the resource path in the jar. */
    public static final String FILE_NAME = "config.yml";

    private ConfigBootstrap() {
    }

    /**
     * Ensures the data folder and {@code config.yml} exist, then reloads.
     *
     * @return the config file on disk (which may not exist if the folder could
     *     not be created - callers must cope, defaults are always valid)
     */
    public static File prepare(JavaPlugin plugin) {
        if (plugin == null) {
            return null;
        }
        Logger log = plugin.getLogger();
        File folder = plugin.getDataFolder();
        File file = folder == null ? null : new File(folder, FILE_NAME);

        try {
            if (folder != null && !folder.isDirectory()) {
                boolean made = folder.mkdirs();
                if (!made && !folder.isDirectory()) {
                    log.severe("[NullArmy] Could not create the data folder "
                            + folder.getAbsolutePath()
                            + " - continuing with built-in defaults. Check file permissions.");
                }
            }
            if (file != null && !file.isFile()) {
                try {
                    // replace=false: never overwrite an existing file.
                    plugin.saveResource(FILE_NAME, false);
                } catch (IllegalArgumentException missing) {
                    log.severe("[NullArmy] " + missing.getMessage());
                    log.severe("[NullArmy] The plugin jar is missing " + FILE_NAME
                            + " - writing a minimal starter config so the plugin can run."
                            + " Rebuild the jar to get the full commented defaults.");
                    writeStarter(file);
                }
            } else if (file != null) {
                log.info("[NullArmy] Keeping the existing configuration (never overwritten).");
            }
            plugin.reloadConfig();
        } catch (Throwable t) {
            // Nothing here may stop the plugin from enabling: every value in
            // PluginConfig has a documented default, so an unreadable file
            // degrades to "defaults" rather than "no plugin".
            log.log(Level.SEVERE, "[NullArmy] Config bootstrap problem: " + t, t);
        }

        log.info("[NullArmy] config: " + (file == null ? "(no data folder)" : file.getAbsolutePath()));
        return file;
    }

    /**
     * Writes a short, valid starter config. Used only when the jar lost its
     * own copy - every key here also has a default in {@link PluginConfig}, so
     * this file is a convenience, never a requirement.
     */
    private static void writeStarter(File file) {
        String yaml = String.join("\n",
                "# NullArmy starter configuration (generated fallback).",
                "# The shipped jar normally provides a fully commented config.yml.",
                "",
                "limits:",
                "  max-live-npcs: 64",
                "  summon-hard-cap: 24",
                "  path-max-expansions: 4096",
                "  max-tracked-entities: 256",
                "",
                "visuals:",
                "  # Spec 3 requires at least 15 portal effects per summon.",
                "  portal-effects-per-summon: 16",
                "",
                "summoning:",
                "  prompt-timeout-ticks: 300",
                "  spawn-search-radius: 6",
                "",
                "menu:",
                "  title: \"NullArmy - Command Center\"",
                "",
                "chat:",
                "  enabled-by-default: false",
                "",
                "ai:",
                "  # The plugin is fully functional with AI off.",
                "  enabled: false",
                "  default-endpoint: \"\"",
                "  endpoints: {}",
                "",
                "skins:",
                "  nulls: \"uH3WR2v0ti0uTHJ\"",
                "  commander: \"\"",
                "",
                "commander:",
                "  name: \"NullCommander\"",
                "  spawn-with-portal: true",
                "",
                "policy:",
                "  griefing-enabled: false",
                "  explosives-enabled: false",
                "  wither-enabled: false",
                "  moderation-integration-enabled: false",
                "",
                "wither-cannon:",
                "  enabled: false",
                "  require-permission: nullarmy.admin",
                "  max-charge: 3",
                "  tnt-per-shot: 3",
                "  cooldown-seconds: 20",
                "  blocks-damage: false",
                "",
                "airdrop:",
                "  enabled: false",
                "  require-permission: nullarmy.admin",
                "  portals: 16",
                "  drop-nulls-from-sky: false",
                "  height: 12",
                "  tnt-per-drop: 2",
                "");
        try {
            Files.write(file.toPath(), yaml.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // Last resort: the plugin still runs from the built-in defaults.
            file.getParentFile().mkdirs();
        }
    }
}
