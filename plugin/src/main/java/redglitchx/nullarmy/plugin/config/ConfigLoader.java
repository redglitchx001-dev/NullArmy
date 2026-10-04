package redglitchx.nullarmy.plugin.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import redglitchx.nullarmy.core.config.YamlProblem;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Reads {@code config.yml} without ever losing a working configuration.
 *
 * <h2>What went wrong before</h2>
 * <p>Bukkit's {@code YamlConfiguration.loadConfiguration} catches a parse error,
 * prints a SnakeYAML stack trace and returns an <b>empty</b> configuration. The
 * plugin then quietly ran on built-in defaults, {@code /null reload} said it had
 * reloaded, and the config migration - seeing every key "missing" - appended
 * the whole shipped file to the broken one, every restart.</p>
 *
 * <p>Here the text is parsed once, strictly; a failure becomes a
 * {@link YamlProblem} (file, line, column, the offending lines with a caret)
 * that the caller reports to the console <b>and</b> to whoever ran the command,
 * while the last configuration that parsed stays in use.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ConfigLoader {

    /** The result of one load. */
    public static final class Outcome {
        private final YamlConfiguration config;
        private final YamlProblem problem;
        private final File file;

        Outcome(YamlConfiguration config, YamlProblem problem, File file) {
            this.config = config;
            this.problem = problem;
            this.file = file;
        }

        /** The parsed configuration (with the shipped defaults attached); null on failure. */
        public YamlConfiguration config() { return config; }

        /** What is wrong with the file; null when it parsed. */
        public YamlProblem problem() { return problem; }

        public File file() { return file; }

        public boolean ok() { return problem == null && config != null; }
    }

    private ConfigLoader() {
    }

    /** Parses a file; a missing file yields the shipped defaults. */
    public static Outcome load(JavaPlugin plugin, File file) {
        YamlConfiguration defaults = shipped(plugin);
        if (file == null || !file.isFile()) {
            YamlConfiguration empty = new YamlConfiguration();
            empty.setDefaults(defaults);
            return new Outcome(empty, null, file);
        }
        String text;
        try {
            text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return new Outcome(null, YamlProblem.locate(file.getName(),
                    "the file could not be read: " + t.getMessage(), null), file);
        }
        return parse(file.getName(), text, defaults, file);
    }

    /** Parses YAML text the same way (used by the migration's re-check and the self test). */
    public static Outcome parse(String fileName, String text, YamlConfiguration defaults, File file) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text == null ? "" : text);
        } catch (InvalidConfigurationException bad) {
            return new Outcome(null, YamlProblem.locate(fileName, messageOf(bad), text), file);
        } catch (Throwable t) {
            return new Outcome(null, YamlProblem.locate(fileName, messageOf(t), text), file);
        }
        if (defaults != null) {
            config.setDefaults(defaults);
        }
        return new Outcome(config, null, file);
    }

    /** The shipped config.yml from the jar, or an empty configuration. */
    public static YamlConfiguration shipped(JavaPlugin plugin) {
        YamlConfiguration out = new YamlConfiguration();
        if (plugin == null) {
            return out;
        }
        try (InputStream in = plugin.getResource(ConfigBootstrap.FILE_NAME)) {
            if (in != null) {
                out.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
            // An unreadable bundled file means "no defaults", never a crash.
        }
        return out;
    }

    /** The parser's own message, including the cause's (where SnakeYAML puts the marks). */
    static String messageOf(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable cursor = t;
        int depth = 0;
        while (cursor != null && depth < 4) {
            if (cursor.getMessage() != null && sb.indexOf(cursor.getMessage()) < 0) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(cursor.getMessage());
            }
            cursor = cursor.getCause();
            depth++;
        }
        return sb.length() == 0 ? t.getClass().getSimpleName() : sb.toString();
    }
}
