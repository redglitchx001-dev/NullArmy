package redglitchx.nullarmy.plugin.skin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import redglitchx.nullarmy.plugin.NullArmyPlugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <h2>Explicit identity only</h2>
 * <p>Account skins are optional and must be configured by the server owner.
 * With no configured account, no other player's skin is fetched and the
 * generated profile uses Minecraft's normal default skin.</p>
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

    private static final String CACHE_DIR = "skins";
    private static final long MAX_CACHE_ENTRIES = 64;
    private static final int MAX_CACHE_FILE_BYTES = 64 * 1024;

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

    /** Explicitly configured account whose skin is used, or an empty string. */
    public String skinOwner() {
        String override = System.getProperty("nullarmy.skin.null");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        if (plugin instanceof NullArmyPlugin) {
            redglitchx.nullarmy.plugin.config.PluginConfig config =
                    ((NullArmyPlugin) plugin).pluginConfig();
            return config == null ? "" : config.nullSkinName();
        }
        return "";
    }

    /**
     * Returns a cached skin if one is already known, without touching the
     * network. Returns {@code null} when only a network fetch could answer.
     *
     * <p>Safe to call on the main thread.</p>
     */
    public SkinData resolveCached(String username) {
        String key = key(username);
        if (key.isEmpty()) {
            return null;
        }
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
        if (callback == null) {
            return;
        }
        String key = key(username);
        if (key.isEmpty()) {
            deliver(callback, null);
            return;
        }
        SkinData known = resolveCached(key);
        if (known != null) {
            deliver(callback, known);
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            SkinData fetched = null;
            try {
                fetched = client.fetch(key);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING,
                        "[NullArmy] Skin lookup failed (" + e.getClass().getSimpleName() + ")");
            }
            if (fetched != null && fetched.complete()) {
                remember(key, fetched);
                writeDiskCache(key, fetched);
            }
            deliver(callback, fetched);
        });
    }

    private void deliver(Consumer<SkinData> callback, SkinData data) {
        Runnable task = () -> callback.accept(data);
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
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
            if (!Files.isSymbolicLink(cacheDir)
                    && Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)) {
                try (java.util.stream.Stream<Path> files = Files.list(cacheDir)) {
                    for (Path file : files.toList()) {
                        if (Files.isSymbolicLink(file)
                                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
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
                    "[NullArmy] Could not clear the skin cache directory ("
                            + e.getClass().getSimpleName() + ")");
        }
        return cleared;
    }

    /** Human-readable lookup trail, for {@code /null status}. Never contains keys. */
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>();
        String owner = skinOwner();
        out.add("skin owner: " + (owner.isEmpty() ? "(not configured; default Minecraft skin)" : owner));
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
        try {
            if (Files.isSymbolicLink(cacheDir) || Files.isSymbolicLink(file)
                    || !Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_CACHE_FILE_BYTES) {
                return null;
            }
            final byte[] bytes;
            try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                bytes = readBounded(in, MAX_CACHE_FILE_BYTES);
            }
            if (bytes == null) {
                return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            SkinData data = parseTwoLines(List.of(text.split("\\R", -1)), SkinData.Source.DISK_CACHE);
            return (data != null && data.complete()) ? data : null;
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    private void writeDiskCache(String key, SkinData data) {
        if (!isValidAccountName(key) || data == null || !data.complete()) {
            return;
        }
        Path file = cacheFile(key);
        Path temporary = null;
        try {
            if (Files.isSymbolicLink(cacheDir)) {
                return;
            }
            Files.createDirectories(cacheDir);
            if (Files.isSymbolicLink(cacheDir)
                    || !Files.isDirectory(cacheDir, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(file)) {
                return;
            }
            byte[] body = (data.value() + "\n" + data.signature() + "\n").getBytes(StandardCharsets.UTF_8);
            if (body.length > MAX_CACHE_FILE_BYTES) {
                return;
            }
            temporary = Files.createTempFile(cacheDir, "skin-", ".tmp");
            Files.write(temporary, body);
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | SecurityException e) {
            plugin.getLogger().log(Level.FINE,
                    "[NullArmy] Could not persist a skin cache entry (" + e.getClass().getSimpleName() + ")");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The cache is only an optimization.
                }
            }
        }
    }

    /**
     * Reads {@code skins/<name>.skin} from inside the jar. This is how an owner
     * ships the skin permanently - no network, no first-run delay.
     */
    private SkinData readBundled(String key) {
        if (!isValidAccountName(key)) {
            return null;
        }
        String resource = CACHE_DIR + "/" + key + ".skin";
        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return null;
            }
            byte[] bytes = readBounded(in, MAX_CACHE_FILE_BYTES);
            if (bytes == null) {
                return null;
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            SkinData data = parseTwoLines(List.of(text.split("\\R", -1)), SkinData.Source.BUNDLED);
            return (data != null && data.complete()) ? data : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Mojang account names are path components only after strict validation;
     * rejecting invalid values here prevents cache and jar-resource traversal.
     */
    public static boolean isValidAccountName(String username) {
        return username != null && username.matches("[A-Za-z0-9_]{1,16}");
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
        String name = username == null ? "" : username.trim();
        return isValidAccountName(name) ? name.toLowerCase(Locale.ROOT) : "";
    }

    /** Reads at most {@code limit} bytes and handles streams that return zero. */
    private static byte[] readBounded(InputStream in, int limit) throws IOException {
        if (in == null || limit < 0) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        while (true) {
            int read = in.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int single = in.read();
                if (single < 0) {
                    break;
                }
                if (total >= limit) {
                    return null;
                }
                out.write(single);
                total++;
                continue;
            }
            if (read > limit - total) {
                return null;
            }
            out.write(buffer, 0, read);
            total += read;
        }
        return out.toByteArray();
    }

    /** Snapshot of the cache, for tests and diagnostics. */
    Map<String, SkinData> snapshot() {
        return new LinkedHashMap<>(cache);
    }
}
