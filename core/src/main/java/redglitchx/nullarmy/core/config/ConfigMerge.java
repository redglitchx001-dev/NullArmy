package redglitchx.nullarmy.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Merges newly shipped config keys into an owner's existing file.
 *
 * <p>The complaint this fixes: an update adds settings, the owner runs
 * {@code /null reload}, and nothing changes on disk because the plugin refuses to
 * touch an existing {@code config.yml}. Refusing to <b>overwrite</b> an owner's
 * values is right; refusing to <b>add</b> a key they have never seen is not.</p>
 *
 * <p>Rules, all of them deliberate:</p>
 * <ul>
 *   <li>an existing value always wins, whatever its type;</li>
 *   <li>a key present in the shipped defaults but absent from the file is added
 *       with the shipped value;</li>
 *   <li>a key present in the file but not in the defaults is kept and reported -
 *       it may be an older spelling another part of the plugin still reads;</li>
 *   <li>sections are compared as flat dotted paths, which is how Bukkit's
 *       {@code getValues(true)} hands them over, so nothing has to know the
 *       shape of the file.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ConfigMerge {

    /** The outcome of one merge. */
    public static final class Result {
        private final Map<String, Object> additions;
        private final List<String> unknownKeys;
        private final boolean changed;

        Result(Map<String, Object> additions, List<String> unknownKeys) {
            this.additions = Collections.unmodifiableMap(new LinkedHashMap<>(additions));
            this.unknownKeys = Collections.unmodifiableList(new ArrayList<>(unknownKeys));
            this.changed = !additions.isEmpty();
        }

        /** Dotted key -&gt; shipped default value, for keys the file was missing. */
        public Map<String, Object> additions() { return additions; }

        /** Keys in the file that the shipped defaults no longer mention. */
        public List<String> unknownKeys() { return unknownKeys; }

        /** True when the file has to be written back. */
        public boolean changed() { return changed; }

        /** How many keys were added. */
        public int addedCount() { return additions.size(); }
    }

    private ConfigMerge() {
    }

    /**
     * Computes what the owner's file is missing.
     *
     * @param existing flat dotted keys already in {@code config.yml}
     * @param shipped  flat dotted keys of the {@code config.yml} inside the jar
     */
    public static Result merge(Map<String, Object> existing, Map<String, Object> shipped) {
        Map<String, Object> additions = new TreeMap<>();
        List<String> unknown = new ArrayList<>();
        Map<String, Object> have = existing == null ? Collections.emptyMap() : existing;
        Map<String, Object> want = shipped == null ? Collections.emptyMap() : shipped;

        for (Map.Entry<String, Object> entry : want.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            if (!have.containsKey(key)) {
                additions.put(key, entry.getValue());
            }
        }
        for (String key : have.keySet()) {
            if (key != null && !key.isEmpty() && !want.containsKey(key)) {
                unknown.add(key);
            }
        }
        Collections.sort(unknown);
        return new Result(additions, unknown);
    }

    /** True when a value is a leaf, not a section: sections merge key by key. */
    public static boolean isLeaf(Object value) {
        return !(value instanceof Map);
    }

    /** A readable summary of what changed, for the reload message and the log. */
    public static String describe(Result result) {
        if (result == null || !result.changed()) {
            return "config.yml already had every key this build ships";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("added ").append(result.addedCount())
                .append(result.addedCount() == 1 ? " missing key: " : " missing keys: ");
        int shown = 0;
        for (String key : result.additions().keySet()) {
            if (shown > 0) {
                sb.append(", ");
            }
            if (shown >= 8) {
                sb.append("...");
                break;
            }
            sb.append(key);
            shown++;
        }
        return sb.toString();
    }
}
