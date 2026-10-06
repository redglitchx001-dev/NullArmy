package redglitchx.nullarmy.plugin.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import redglitchx.nullarmy.core.config.ConfigMerge;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brings an owner's existing {@code config.yml} up to date without touching it.
 *
 * <h2>The bug this replaces</h2>
 * The plugin used to refuse to write to an existing {@code config.yml} at all, so
 * after an update the new settings simply did not exist on disk and
 * {@code /null reload} appeared to do nothing. Not overwriting an owner's values
 * is correct; never telling them about a new setting is not.
 *
 * <h2>How it migrates</h2>
 * <ol>
 *   <li>Reads the {@code config.yml} inside the jar and the one on disk, both as
 *       flat dotted keys.</li>
 *   <li>{@link ConfigMerge} decides what the file is missing. An existing value
 *       always wins, whatever its type, so nothing the owner set can change.</li>
 *   <li>The missing keys are <b>appended</b> to the file as a clearly labelled
 *       block. Appending instead of rewriting is the point: every comment, every
 *       blank line and every value the owner wrote stays exactly where it was,
 *       because Bukkit's own {@code save()} would drop all of it.</li>
 *   <li>A timestamped backup is written first, so an owner who disagrees with a
 *       new default can put the old file back.</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ConfigMigration {

    /** What one migration did. */
    public static final class Report {
        private final boolean changed;
        private final List<String> addedKeys;
        private final List<String> unknownKeys;
        private final File backup;
        private final String error;

        Report(boolean changed, List<String> addedKeys, List<String> unknownKeys,
               File backup, String error) {
            this.changed = changed;
            this.addedKeys = addedKeys;
            this.unknownKeys = unknownKeys;
            this.backup = backup;
            this.error = error;
        }

        static Report untouched(List<String> unknown) {
            return new Report(false, new ArrayList<>(), unknown, null, null);
        }

        static Report failed(String error) {
            return new Report(false, new ArrayList<>(), new ArrayList<>(), null, error);
        }

        /** True when the file on disk was written to. */
        public boolean changed() { return changed; }
        public List<String> addedKeys() { return addedKeys; }
        public List<String> unknownKeys() { return unknownKeys; }
        public File backup() { return backup; }
        public String error() { return error; }

        /** One line for chat and the log. */
        public String describe() {
            if (error != null) {
                return "config.yml could not be updated: " + error;
            }
            if (!changed) {
                return "config.yml already had every setting this build ships";
            }
            return "added " + addedKeys.size() + " missing setting(s) to config.yml"
                    + (backup == null ? "" : " (backup: " + backup.getName() + ")");
        }
    }

    private ConfigMigration() {
    }

    /**
     * Migrates {@code plugins/NullArmy/config.yml} in place.
     *
     * @param plugin  the plugin, used to find the data folder and the jar resource
     * @param version the running build's version, written into the appended header
     */
    public static Report migrate(JavaPlugin plugin, String version) {
        if (plugin == null) {
            return Report.failed("no plugin");
        }
        return migrateFile(plugin, new File(plugin.getDataFolder(), ConfigBootstrap.FILE_NAME), version);
    }

    /** Migrates any config file the same way (the self test uses copies). */
    public static Report migrateFile(JavaPlugin plugin, File file, String version) {
        if (plugin == null || file == null) {
            return Report.failed("no plugin or file");
        }
        if (!file.isFile()) {
            // Nothing to migrate: the bootstrap writes the full shipped file.
            return Report.untouched(new ArrayList<>());
        }
        try {
            Map<String, Object> shipped = leaves(loadResource(plugin));
            // Parse the owner's file strictly. Bukkit's loadConfiguration would
            // return an EMPTY configuration for a file with a syntax error, every
            // shipped key would look "missing", and the whole default file would
            // be appended to the broken one - on every start. A file that does not
            // parse is never touched.
            ConfigLoader.Outcome current = ConfigLoader.load(plugin, file);
            if (!current.ok()) {
                return Report.failed("config.yml has a syntax error (" + current.problem().headline()
                        + "); nothing was appended - fix it, then run /null reload");
            }
            Map<String, Object> existing = leaves(current.config());
            ConfigMerge.Result result = ConfigMerge.merge(existing, shipped);
            if (!result.changed()) {
                return Report.untouched(result.unknownKeys());
            }

            File backup = backup(file);
            // Explicit current keys always win in ConfigMerge.merge. Legacy
            // death-suppression keys are inert: Null deaths now use vanilla
            // drops by default, while an existing drops.enabled value remains.
            Map<String, Object> additions = result.additions();
            String block = renderBlock(additions, version, backup);
            Files.write(file.toPath(), block.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.APPEND, StandardOpenOption.CREATE);

            // Re-parse what was written before anyone uses it; a block that does
            // not parse is rolled back to the backup, never left behind.
            ConfigLoader.Outcome after = ConfigLoader.load(plugin, file);
            if (!after.ok()) {
                if (backup != null) {
                    Files.copy(backup.toPath(), file.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return Report.failed("the appended settings did not parse (" + after.problem().headline()
                        + "); config.yml was rolled back" + (backup == null ? "" : " to " + backup.getName()));
            }
            return new Report(true, new ArrayList<>(result.additions().keySet()),
                    result.unknownKeys(), backup, null);
        } catch (Throwable t) {
            return Report.failed(Guard.describe(t));
        }
    }

    /** The shipped {@code config.yml}, or an empty configuration when the jar lost it. */
    private static YamlConfiguration loadResource(JavaPlugin plugin) {
        YamlConfiguration empty = new YamlConfiguration();
        try (InputStream in = plugin.getResource(ConfigBootstrap.FILE_NAME)) {
            if (in == null) {
                return empty;
            }
            YamlConfiguration loaded = new YamlConfiguration();
            loaded.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return loaded;
        } catch (Throwable t) {
            return empty;
        }
    }

    /** Flat dotted keys with leaf values only: sections merge key by key. */
    private static Map<String, Object> leaves(YamlConfiguration config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (config == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : config.getValues(true).entrySet()) {
            if (ConfigMerge.isLeaf(entry.getValue())) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** A timestamped copy, so an owner can always go back. */
    private static File backup(File file) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backup = new File(file.getParentFile(), file.getName() + ".bak-" + stamp);
            Files.copy(file.toPath(), backup.toPath());
            return backup;
        } catch (Throwable t) {
            // No backup is not a reason to skip the migration, but it is logged.
            return null;
        }
    }

    /** The appended block: a header, then one {@code dotted.key: value} per line. */
    private static String renderBlock(Map<String, Object> additions, String version, File backup) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append("# ---------------------------------------------------------------------\n");
        sb.append("#  Added by NullArmy ").append(version == null ? "" : version).append(' ')
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date())).append('\n');
        sb.append("#\n");
        sb.append("#  These settings did not exist in your file yet, so they were appended\n");
        sb.append("#  with their shipped values. Nothing above was read, rewritten or\n");
        sb.append("#  replaced: your own values and comments are untouched.\n");
        if (backup != null) {
            sb.append("#  A copy of the file before this block is ").append(backup.getName()).append(".\n");
        }
        sb.append("#  Dotted keys are exactly the same setting as the nested form; move\n");
        sb.append("#  them into their section by hand if you prefer it tidy.\n");
        sb.append("# ---------------------------------------------------------------------\n");
        for (Map.Entry<String, Object> entry : additions.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(yaml(entry.getValue())).append('\n');
        }
        return sb.toString();
    }

    /** Renders one value as YAML. Strings are quoted so a colon cannot break it. */
    private static String yaml(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof List<?>) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (List<?>) value) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(yaml(item));
            }
            return sb.append(']').toString();
        }
        String text = String.valueOf(value);
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
