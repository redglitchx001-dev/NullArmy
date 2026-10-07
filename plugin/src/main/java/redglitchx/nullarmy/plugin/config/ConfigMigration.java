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
 *   <li>{@link ConfigMerge} decides what the file is missing. Existing values
 *       win, except for two exact obsolete shipped defaults: the former built-in
 *       skin account is cleared, and the old automatic-coordination default is
 *       turned off. Every other owner-edited value is preserved.</li>
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
        private final List<String> migratedKeys;
        private final List<String> unknownKeys;
        private final File backup;
        private final String error;

        Report(boolean changed, List<String> addedKeys, List<String> migratedKeys, List<String> unknownKeys,
               File backup, String error) {
            this.changed = changed;
            this.addedKeys = addedKeys;
            this.migratedKeys = migratedKeys;
            this.unknownKeys = unknownKeys;
            this.backup = backup;
            this.error = error;
        }

        static Report untouched(List<String> unknown) {
            return new Report(false, new ArrayList<>(), new ArrayList<>(), unknown, null, null);
        }

        static Report failed(String error) {
            return new Report(false, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), null, error);
        }

        /** True when the file on disk was written to. */
        public boolean changed() { return changed; }
        public List<String> addedKeys() { return addedKeys; }
        public List<String> migratedKeys() { return migratedKeys; }
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
            StringBuilder description = new StringBuilder();
            if (!migratedKeys.isEmpty()) {
                description.append("migrated ").append(migratedKeys.size())
                        .append(" legacy setting(s) ").append(migratedKeys);
            }
            if (!addedKeys.isEmpty()) {
                if (description.length() > 0) {
                    description.append(" and ");
                }
                description.append("added ").append(addedKeys.size()).append(" missing setting(s)");
            }
            description.append(" to config.yml");
            if (backup != null) {
                description.append(" (backup: ").append(backup.getName()).append(')');
            }
            return description.toString();
        }
    }

    private static final String LEGACY_DEFAULT_SKIN_OWNER = "uH3WR2v0ti0uTHJ";

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
        if (Files.isSymbolicLink(file.toPath())) {
            return Report.failed("config.yml is a symbolic link; no migration was applied");
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
            String original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<String> migrated = new ArrayList<>();
            String upgraded = migrateKnownLegacyDefaults(original, existing, migrated);
            if (!result.changed() && migrated.isEmpty()) {
                return Report.untouched(result.unknownKeys());
            }
            // A migration that changes an existing value must have a restorable
            // copy. The backup also protects append-only additions from partial
            // writes and is created before touching the owner's file.
            File backup = backup(file);
            Map<String, Object> additions = result.additions();
            String block = additions.isEmpty() ? "" : renderBlock(additions, version, backup);
            writeAtomically(file, upgraded + block);

            // Parse the exact bytes written before anyone uses them. Roll back to
            // the timestamped copy if either a migration or the appended block is
            // not valid YAML.
            ConfigLoader.Outcome after = ConfigLoader.load(plugin, file);
            if (!after.ok()) {
                Files.copy(backup.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                return Report.failed("the migrated/appended settings did not parse ("
                        + after.problem().headline() + "); config.yml was rolled back to " + backup.getName());
            }
            return new Report(true, new ArrayList<>(result.additions().keySet()), migrated,
                    result.unknownKeys(), backup, null);
        } catch (Throwable t) {
            return Report.failed(Guard.describe(t));
        }
    }

    /**
     * Updates only the {@code commander.name} scalar, preserving every other
     * byte, comment and line ending. A strict parse, unique supported key and
     * restorable backup are required; malformed/ambiguous config is untouched.
     */
    public static boolean updateCommanderName(JavaPlugin plugin, File file, String name) {
        if (plugin == null || file == null || name == null || !name.matches("[A-Za-z0-9_]{1,16}")) {
            return false;
        }
        try {
            if (!file.isFile() || Files.isSymbolicLink(file.toPath())) {
                return false;
            }
            ConfigLoader.Outcome before = ConfigLoader.load(plugin, file);
            if (!before.ok()) {
                return false;
            }
            String original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String updated = replaceCommanderName(original, name);
            if (updated == null) {
                return false;
            }
            if (updated.equals(original)) {
                return name.equals(before.config().getString("commander.name", ""));
            }
            File backup = backup(file);
            writeAtomically(file, updated);
            ConfigLoader.Outcome after = ConfigLoader.load(plugin, file);
            if (!after.ok() || !name.equals(after.config().getString("commander.name", ""))) {
                Files.copy(backup.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            // The caller receives a safe generic message; never echo YAML or exception contents.
            return false;
        }
    }

    /** Returns a safely edited source, or null unless exactly one scalar is editable. */
    private static String replaceCommanderName(String source, String name) {
        if (source == null) {
            return null;
        }
        String[] lines = source.split("\n", -1);
        int target = -1;
        boolean inCommander = false;
        int childIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            String body = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            String plain = i == 0 && !body.isEmpty() && body.charAt(0) == 0xFEFF
                    ? body.substring(1) : body;
            String trimmed = plain.trim();
            if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
                continue;
            }
            int indent = indentation(plain);
            int colon = plain.indexOf(':');
            String key = colon < 0 ? "" : plain.substring(indent, colon).trim();
            String value = colon < 0 ? "" : plain.substring(colon + 1).trim();
            if (indent == 0 && key.equals("commander")
                    && (value.isEmpty() || value.charAt(0) == '#')) {
                inCommander = true;
                childIndent = -1;
                continue;
            }
            if (inCommander && indent > 0) {
                if (childIndent < 0) {
                    childIndent = indent;
                }
                if (indent == childIndent && key.equals("name")) {
                    if (target >= 0) {
                        return null;
                    }
                    target = i;
                }
                continue;
            }
            if (inCommander) {
                inCommander = false;
                childIndent = -1;
            }
            if (indent == 0 && key.equals("commander.name")) {
                if (target >= 0) {
                    return null;
                }
                target = i;
            }
        }
        if (target < 0) {
            return null;
        }
        String originalLine = lines[target];
        String ending = originalLine.endsWith("\r") ? "\r" : "";
        String body = ending.isEmpty() ? originalLine
                : originalLine.substring(0, originalLine.length() - ending.length());
        String bom = target == 0 && !body.isEmpty() && body.charAt(0) == 0xFEFF
                ? String.valueOf((char) 0xFEFF) : "";
        String replaced = replaceNameScalar(bom.isEmpty() ? body : body.substring(1), name);
        if (replaced == null) {
            return null;
        }
        lines[target] = bom + replaced + ending;
        return String.join("\n", lines);
    }

    /** Replaces one plain scalar while preserving spacing and any inline comment. */
    private static String replaceNameScalar(String line, String name) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        int valueStart = colon + 1;
        while (valueStart < line.length()
                && (line.charAt(valueStart) == ' ' || line.charAt(valueStart) == 9)) {
            valueStart++;
        }
        int comment = inlineComment(line, valueStart);
        int valueEnd = comment < 0 ? line.length() : comment;
        while (valueEnd > valueStart
                && (line.charAt(valueEnd - 1) == ' ' || line.charAt(valueEnd - 1) == 9)) {
            valueEnd--;
        }
        String oldValue = line.substring(valueStart, valueEnd).trim();
        if (oldValue.startsWith("|") || oldValue.startsWith(">") || oldValue.startsWith("[")
                || oldValue.startsWith("{") || oldValue.startsWith("&") || oldValue.startsWith("*")
                || oldValue.startsWith("!")) {
            return null;
        }
        String prefix = line.substring(0, valueStart);
        if (valueStart == colon + 1) {
            prefix += " ";
        }
        String suffix = line.substring(valueEnd);
        if (suffix.startsWith("#")) {
            suffix = " " + suffix;
        }
        return prefix + (char) 34 + name + (char) 34 + suffix;
    }

    /** Finds an unquoted YAML comment marker; quoted # characters remain part of the scalar. */
    private static int inlineComment(String line, int start) {
        int quote = 0;
        boolean escaped = false;
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == 34) {
                if (escaped) {
                    escaped = false;
                } else if (c == 92) {
                    escaped = true;
                } else if (c == 34) {
                    quote = 0;
                }
            } else if (quote == 39) {
                if (c == 39 && i + 1 < line.length() && line.charAt(i + 1) == 39) {
                    i++;
                } else if (c == 39) {
                    quote = 0;
                }
            } else if (c == 34 || c == 39) {
                quote = c;
            } else if (c == '#' && (i == start || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == 9)) {
                return i;
            }
        }
        return -1;
    }

    private static int indentation(String line) {
        int count = 0;
        while (count < line.length() && (line.charAt(count) == ' ' || line.charAt(count) == 9)) {
            count++;
        }
        return count;
    }

    /**
     * Replaces only defaults that shipped in prior NullArmy versions. Any other
     * explicit owner value is preserved; if its source line cannot be located
     * unambiguously, migration stops rather than rewriting an unknown YAML form.
     */
    private static String migrateKnownLegacyDefaults(String source, Map<String, Object> existing,
                                                     List<String> migrated) throws IOException {
        String upgraded = source;
        Object skin = existing.get("skins.nulls");
        if (LEGACY_DEFAULT_SKIN_OWNER.equals(skin)) {
            String oldSkinScalar = "(?:\"" + LEGACY_DEFAULT_SKIN_OWNER + "\"|'"
                    + LEGACY_DEFAULT_SKIN_OWNER + "'|" + LEGACY_DEFAULT_SKIN_OWNER + ")";
            upgraded = replaceUniqueScalar(upgraded, "nulls", oldSkinScalar, "\"\"");
            migrated.add("skins.nulls");
        }
        if (Boolean.TRUE.equals(existing.get("ai.auto-coordinate"))) {
            upgraded = replaceUniqueScalar(upgraded, "auto-coordinate", "(?i:true)", "false");
            migrated.add("ai.auto-coordinate");
        }
        return upgraded;
    }

    /** Replaces one exact scalar line while preserving indentation, comments and line ending. */
    private static String replaceUniqueScalar(String source, String key, String oldValuePattern,
                                              String replacement) throws IOException {
        String[] lines = source.split("(?<=\\n)", -1);
        java.util.regex.Pattern linePattern = java.util.regex.Pattern.compile(
                "^([ \\t]*" + java.util.regex.Pattern.quote(key) + "[ \\t]*:[ \\t]*)"
                        + "(?:" + oldValuePattern + ")([ \\t]*(?:#.*)?)$");
        int found = -1;
        String changed = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String ending = "";
            if (line.endsWith("\n")) {
                ending = "\n";
                line = line.substring(0, line.length() - 1);
            }
            if (line.endsWith("\r")) {
                ending = "\r" + ending;
                line = line.substring(0, line.length() - 1);
            }
            java.util.regex.Matcher matcher = linePattern.matcher(line);
            if (matcher.matches()) {
                if (found >= 0) {
                    throw new IOException("legacy setting '" + key + "' appears more than once; no change applied");
                }
                found = i;
                changed = matcher.group(1) + replacement + matcher.group(2) + ending;
            }
        }
        if (found < 0) {
            throw new IOException("legacy setting '" + key + "' could not be located safely; no change applied");
        }
        lines[found] = changed;
        return String.join("", lines);
    }

    /** Rewrites a config through a same-directory temporary file and atomic move where available. */
    private static void writeAtomically(File file, String text) throws IOException {
        Path target = file.toPath().toAbsolutePath();
        if (Files.isSymbolicLink(target)) {
            throw new IOException("config.yml is a symbolic link");
        }
        Path parent = target.getParent();
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            Files.copy(target, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            Files.write(temporary, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
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

    /** Creates a unique timestamped copy with the original access attributes. */
    private static File backup(File file) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS").format(new Date());
        Path source = file.toPath();
        Path parent = source.toAbsolutePath().getParent();
        for (int suffix = 0; suffix < 100; suffix++) {
            String candidateName = file.getName() + ".bak-" + stamp + (suffix == 0 ? "" : "-" + suffix);
            Path candidate = parent.resolve(candidateName);
            try {
                Files.copy(source, candidate, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                return candidate.toFile();
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Multiple reloads in the same millisecond still get distinct backups.
            }
        }
        throw new IOException("could not create a unique config.yml backup");
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
        sb.append("#  with their shipped values. Unrelated values and comments are preserved.\n");
        sb.append("#  Only exact obsolete shipped defaults may have been updated above.\n");
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
