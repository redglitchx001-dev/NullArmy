package redglitchx.nullarmy.plugin.skin;

import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Resolves the skin every Null and the Commander must wear.
 *
 * <h2>Why one username for everything</h2>
 * <p>The owner asked that <b>every</b> skin come from a single Minecraft
 * account. That is what {@link #DEFAULT_SKIN_OWNER} is: one lookup, cached
 * once, reused for every NPC - so a hundred Nulls cost one HTTP request, not
 * a hundred, and they all look identical.</p>
 *
 * <h2>Lookup order</h2>
 * <ol>
 *   <li><b>Memory</b> - already resolved this session.</li>
 *   <li><b>Disk cache</b> - {@code plugins/NullArmy/skins/<name>.skin}. Written
 *       after a successful fetch, so the server starts instantly and works
 *       even when Mojang is unreachable.</li>
 *   <li><b>Bundled resource</b> - {@code skins/<name>.skin} inside the jar. Lets
 *       an owner bake the skin in permanently, so the plugin never touches the
 *       network at all. This is why the jar may be large: bundling is a
 *       deliberate trade, not bloat.</li>
 *   <li><b>Network</b> - Mojang, but <i>only asynchronously</i>
 *       ({@link #resolveAsync}). Never on the main thread.</li>
 * </ol>
 *
 * <p>A failed lookup is never fatal. Spawns continue with whatever skin is
 * available; a Null without a skin is still a Null.</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SkinResolver {

    /**
     * The Minecraft username whose skin every Null and the Commander wears.
     * Change it here, or set {@code nullarmy.skin.owner} as a system property.
     */
    public static final String DEFAULT_SKIN_OWNER = "uH3WR2v0ti0uTHJ";

    private static final String CACHE_DIR = "skins";
    private static final long MAX_CACHE_ENTRIES = 64;

    private final Plugin plugin;
    private final MojangSkinClient client;
    private final Path cacheDir;
    private final Map<String, SkinData> cache = new ConcurrentHashMap<>();
    private final List<String> lastDiagnostics = new ArrayList<>();

    public SkinResolver(Plugin plugin) {
        this(plugin, new MojangSkinClient());
    }

    public SkinResolver(Plugin plugin, MojangSkinClient client) {
        this.plugin = plugin;
        this.client = client;
        this.cacheDir = plugin.getDataFolder().toPath().resolve(CACHE_DIR);
    }

    /** The username whose skin is used for every NPC. */
    public String skinOwner() {
        String override = System.getProperty("nullarmy.skin.owner");
        return (override == null || override.trim().isEmpty())
                ? DEFAULT_SKIN_OWNER : override.trim();
    }

    /**
     * Returns a cached skin if one is already known, without touching the
     * network. Returns {@code null} when only a network fetch could answer.
     *
     * <p>Safe to call on the main thread.</p>
     */
    public SkinData resolveCached(String username) {
        String key = key(username);
        SkinData hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        SkinData disk = readDiskCache(key);
        if (disk != null) {
            remember(key, disk);
            return disk;
        }
        SkinData bundled = readBundled(key);
        if (bundled != null) {
            remember(key, bundled);
            return bundled;
        }
        return null;
    }

    /**
     * Resolves a skin, hitting the network only if every cache missed.
     *
     * <p>{@code callback} always runs on the server's main thread, exactly
     * once, possibly with {@code null}.</p>
     */
    public void resolveAsync(String username, Consumer<SkinData> callback) {
        String key = key(username);
        SkinData known = resolveCached(key);
        if (known != null) {
            callback.accept(known);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            SkinData fetched = null;
            try {
                fetched = client.fetch(key);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[NullArmy] Skin lookup failed for '" + key + "': " + e.getMessage());
            }
            if (fetched != null && fetched.complete()) {
                remember(key, fetched);
                writeDiskCache(key, fetched);
            }
            SkinData result = fetched;
            plugin.getServer().getScheduler().runTask(plugin, () -> callback.accept(result));
        });
    }

    /**
     * Forgets every cached skin - memory and disk - so the next lookup fetches
     * fresh data. Used by {@code /null clearskins} after the owner changes the
     * skin account.
     *
     * <p>Only generated {@code *.skin} cache files are deleted. Administrator
     * assets such as {@code skins/null.png} and bundled jar resources are kept;
     * a file that cannot be deleted is simply left in place.</p>
     *
     * @return how many cached entries (memory + disk files) were cleared
     */
    public int clearCache() {
        int cleared = cache.size();
        cache.clear();
        try {
            if (Files.isDirectory(cacheDir)) {
                try (java.util.stream.Stream<Path> files = Files.list(cacheDir)) {
                    for (Path file : files.toList()) {
                        if (!Files.isRegularFile(file)
                                || !file.getFileName().toString().endsWith(".skin")) {
                            continue;
                        }
                        try {
                            if (Files.deleteIfExists(file)) {
                                cleared++;
                            }
                        } catch (IOException ignored) {
                            // A locked file is simply not cleared this time.
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[NullArmy] Could not clear the skin cache directory: " + e.getMessage());
        }
        return cleared;
    }

    /** Human-readable lookup trail, for {@code /null status}. Never contains keys. */
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        out.add("skin owner: " + skinOwner());
        out.add("cached skins: " + cache.size());
        for (Map.Entry<String, SkinData> e : cache.entrySet()) {
            out.add("  " + e.getKey() + " from " + e.getValue().source()
                    + " complete=" + e.getValue().complete());
        }
        return out;
    }

    // ------------------------------------------------------------- internals

    private void remember(String key, SkinData data) {
        synchronized (cache) {
            if (cache.size() >= MAX_CACHE_ENTRIES && !cache.containsKey(key)) {
                // LinkedHashMap-free eviction: drop the first non-matching entry.
                String first = cache.keySet().iterator().next();
                cache.remove(first);
            }
        }
        cache.put(key, data);
    }

    private Path cacheFile(String key) {
        return cacheDir.resolve(key + ".skin");
    }

    private SkinData readDiskCache(String key) {
        Path file = cacheFile(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            SkinData data = parseTwoLines(lines, SkinData.Source.DISK_CACHE);
            return (data != null && data.complete()) ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDiskCache(String key, SkinData data) {
        try {
            Files.createDirectories(cacheDir);
            String body = data.value() + "\n" + data.signature() + "\n";
            Files.write(cacheFile(key), body.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[NullArmy] Could not cache the skin for '" + key + "': " + e.getMessage());
        }
    }

    /**
     * Reads {@code skins/<name>.skin} from inside the jar. This is how an owner
     * ships the skin permanently - no network, no first-run delay.
     */
    private SkinData readBundled(String key) {
        String resource = CACHE_DIR + "/" + key + ".skin";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = in.readAllBytes();
            String text = new String(bytes, StandardCharsets.UTF_8);
            SkinData data = parseTwoLines(List.of(text.split("\\R")), SkinData.Source.BUNDLED);
            return (data != null && data.complete()) ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Two lines: the base64 value, then the signature. */
    static SkinData parseTwoLines(List<String> lines, SkinData.Source source) {
        if (lines == null || lines.size() < 2) {
            return null;
        }
        String value = lines.get(0).trim();
        String signature = lines.get(1).trim();
        if (value.isEmpty() || signature.isEmpty()) {
            return null;
        }
        return new SkinData(value, signature, source);
    }

    static String key(String username) {
        return username == null ? "" : username.toLowerCase(Locale.ROOT);
    }

    /** Snapshot of the cache, for tests and diagnostics. */
    Map<String, SkinData> snapshot() {
        return new LinkedHashMap<>(cache);
    }
}
