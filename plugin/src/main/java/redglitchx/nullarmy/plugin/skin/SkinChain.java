package redglitchx.nullarmy.plugin.skin;

import org.bukkit.Bukkit;

import redglitchx.nullarmy.core.json.Json;
import redglitchx.nullarmy.core.skin.SkinPayload;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which skin the Nulls wear, in a fixed order of trust.
 *
 * <ol>
 *   <li>{@code skins.value} + {@code skins.signature} from config.yml - an owner
 *       who pasted a signed texture gets exactly that, with no network;</li>
 *   <li>{@code plugins/NullArmy/skins/null.png}, uploaded to MineSkin v2 and
 *       cached as a signed texture (requires {@code skins.mineskin.api-key});</li>
 *   <li>{@code skins.proxy-url} - any service that returns
 *       {@code {"value","signature"}} JSON (MineSkin and Mojang shapes too) or raw
 *       base64; a failure logs the HTTP status and the first 80 characters of the
 *       body so the owner can see what the proxy said;</li>
 *   <li>Mojang by account name ({@code skins.nulls} / {@code skins.commander}),
 *       through the existing cache and resolver.</li>
 * </ol>
 *
 * <p>The chain runs off the main thread. When it settles, every live Null and
 * the Commander are re-skinned live ({@link #reapplyAll}): the adapter withdraws
 * and re-announces the player-info entry and re-pairs the entity, so viewers see
 * the new skin without a relog.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SkinChain implements Reloadable {

    private static final URI MINESKIN_QUEUE_URI = URI.create("https://api.mineskin.org/v2/queue");
    private static final String MINESKIN_BASE_URL = "https://api.mineskin.org";
    private static final String MINESKIN_USER_AGENT = "NullArmy/0.1.0 (Paper plugin; skin signing)";
    private static final int MAX_CUSTOM_PNG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MINESKIN_POLLS = 60;
    private static final int MAX_MINESKIN_RESPONSE_CHARS = 256 * 1024;

    /** One resolved skin and where it came from. */
    public static final class Resolution {
        private final SkinData skin;
        private final String source;
        private final List<String> attempts;

        Resolution(SkinData skin, String source, List<String> attempts) {
            this.skin = skin;
            this.source = source;
            this.attempts = Collections.unmodifiableList(new ArrayList<>(attempts));
        }

        /** A resolution from a known texture (the self test's stub skins). */
        public static Resolution of(SkinData skin, String source) {
            return new Resolution(skin, source, Collections.singletonList(source));
        }

        public SkinData skin() { return skin; }
        public String source() { return source; }
        public List<String> attempts() { return attempts; }
        public boolean complete() { return skin != null && skin.complete(); }
    }

    private final NullArmyPlugin plugin;
    private final HttpClient http;
    /** MineSkin requests do not follow redirects, preventing the bearer key from ever being forwarded. */
    private final HttpClient mineSkinHttp;
    private volatile Resolution nulls = new Resolution(null, "not resolved yet", Collections.emptyList());
    private volatile Resolution commander = new Resolution(null, "not resolved yet", Collections.emptyList());
    private final Map<String, String> applied = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String lastProxyStatus = "";

    public SkinChain(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        this.mineSkinHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            Files.createDirectories(plugin.getDataFolder().toPath().resolve("skins"));
        } catch (IOException | SecurityException failure) {
            plugin.getLogger().warning("[NullArmy] could not prepare plugins/NullArmy/skins ("
                    + failure.getClass().getSimpleName() + "); custom PNG skins may be unavailable.");
        }
    }

    @Override
    public void onConfigReloaded(PluginConfig config) {
        refreshAsync(true);
    }

    /** The current skin for Nulls (or the Commander), possibly null. */
    public SkinData current(boolean forCommander) {
        Resolution r = forCommander ? commander : nulls;
        if (forCommander && (r == null || !r.complete())) {
            r = nulls;
        }
        return r == null ? null : r.skin();
    }

    public Resolution resolution(boolean forCommander) {
        return forCommander ? commander : nulls;
    }

    public String lastProxyStatus() { return lastProxyStatus; }

    /** Resolves the chain off-thread; on success, optionally re-skins every live body. */
    public void refreshAsync(boolean reapply) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Resolution forNulls = resolve(false);
                Resolution forCommander = commanderSharesNullSkin() ? forNulls : resolve(true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    nulls = forNulls;
                    commander = forCommander;
                    plugin.getLogger().info("[NullArmy] skin for Nulls: " + forNulls.source()
                            + (forNulls.complete() ? "" : " (default skin)"));
                    if (reapply && settings() != null && settings().skinLiveReapply()) {
                        int done = reapplyAll();
                        if (done > 0) {
                            plugin.getLogger().info("[NullArmy] re-skinned " + done + " live Null(s).");
                        }
                    }
                });
            });
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] skin refresh not scheduled: " + Guard.describe(t));
        }
    }

    private V3Settings settings() {
        return plugin.pluginConfig() == null ? null : plugin.pluginConfig().v3();
    }

    /**
     * Runs the chain now (blocking - call off the main thread for the network
     * steps).
     */
    public Resolution resolve(boolean forCommander) {
        List<String> attempts = new ArrayList<>();
        V3Settings v3 = settings();
        // 1. Pasted value + signature.
        if (v3 != null && !v3.skinValue().isEmpty()) {
            if (!v3.skinSignature().isEmpty()) {
                attempts.add("config value+signature: used");
                return new Resolution(new SkinData(v3.skinValue(), v3.skinSignature(), SkinData.Source.CONFIG),
                        "config.yml skins.value + skins.signature", attempts);
            }
            attempts.add("config value without skins.signature: skipped (clients need the signature)");
        } else {
            attempts.add("config value+signature: not set");
        }
        // 2. A local PNG must be signed before Minecraft clients can use it.
        if (forCommander && !commanderSharesNullSkin()) {
            attempts.add("skins/null.png: skipped because the Commander has a separate skin account");
        } else {
            SkinPayload customPng = signCustomPng(v3);
            if (customPng != null) {
                if (customPng.complete()) {
                    attempts.add("skins/null.png via MineSkin: signed texture used");
                    return new Resolution(new SkinData(customPng.value(), customPng.signature(),
                            SkinData.Source.MINESKIN), "MineSkin signed plugins/NullArmy/skins/null.png", attempts);
                }
                attempts.add("skins/null.png via MineSkin: " + customPng.error());
            } else {
                attempts.add("skins/null.png: not present");
            }
        }
        // 3. Proxy.
        if (v3 != null && !v3.skinProxyUrl().isEmpty()) {
            SkinPayload payload = fetchProxy(v3.skinProxyUrl());
            if (payload.complete()) {
                attempts.add("proxy " + v3.skinProxyUrl() + ": used");
                return new Resolution(new SkinData(payload.value(), payload.signature(), SkinData.Source.PROXY),
                        "skins.proxy-url " + v3.skinProxyUrl(), attempts);
            }
            attempts.add("proxy " + v3.skinProxyUrl() + ": " + payload.error());
        } else {
            attempts.add("proxy: not set");
        }
        // 4. Mojang by name.
        String name = account(forCommander);
        if (name != null && !name.isEmpty() && plugin.skins() != null) {
            SkinData cached = plugin.skins().resolveCached(name);
            if (cached != null && cached.complete()) {
                attempts.add("Mojang account " + name + ": " + cached.source().name().toLowerCase(java.util.Locale.ROOT));
                return new Resolution(cached, "Mojang account " + name + " (" + cached.source().name()
                        .toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")", attempts);
            }
            try {
                SkinData fetched = new MojangSkinClient().fetch(name);
                if (fetched != null && fetched.complete()) {
                    attempts.add("Mojang account " + name + ": fetched");
                    return new Resolution(fetched, "Mojang account " + name + " (fetched)", attempts);
                }
                attempts.add("Mojang account " + name + ": no signed texture returned");
            } catch (Throwable t) {
                attempts.add("Mojang account " + name + ": " + Guard.describe(t));
            }
        } else {
            attempts.add("Mojang account: not set");
        }
        // 5. Legacy root-level skin.png is not signed, so Minecraft cannot display it.
        File legacyPng = new File(plugin.getDataFolder(), "skin.png");
        boolean legacyPngPresent = legacyPng.isFile() || plugin.getResource("skin.png") != null;
        attempts.add(legacyPngPresent ? "legacy skin.png: present but unsigned; use skins/null.png with"
                + " skins.mineskin.api-key, skins.proxy-url, or a signed skins.value + skins.signature"
                : "legacy skin.png: none");
        return new Resolution(null, legacyPngPresent ? "legacy skin.png (unsigned, not displayable) - default skin shown"
                : "nothing resolved - default skin shown", attempts);
    }

    private boolean commanderSharesNullSkin() {
        PluginConfig config = plugin.pluginConfig();
        return config == null || config.commanderSkinName().equalsIgnoreCase(config.nullSkinName());
    }

    /**
     * Signs the administrator-provided PNG and reuses a disk cache until its
     * content changes. A null result means no custom file is present.
     */
    private synchronized SkinPayload signCustomPng(V3Settings v3) {
        Path image = plugin.getDataFolder().toPath().resolve("skins").resolve("null.png");
        if (!Files.exists(image)) {
            return null;
        }
        if (!Files.isRegularFile(image)) {
            return SkinPayload.failure("skins/null.png is not a regular file");
        }

        final byte[] png;
        try {
            long size = Files.size(image);
            if (size < 8 || size > MAX_CUSTOM_PNG_BYTES) {
                return SkinPayload.failure("skins/null.png must be a PNG no larger than 2 MiB");
            }
            png = Files.readAllBytes(image);
        } catch (IOException | SecurityException failure) {
            return SkinPayload.failure("could not read skins/null.png (" + failure.getClass().getSimpleName() + ")");
        }
        if (!hasPngSignature(png)) {
            return SkinPayload.failure("skins/null.png does not have a PNG file signature");
        }

        final String digest;
        try {
            digest = sha256(png);
        } catch (NoSuchAlgorithmException impossible) {
            return SkinPayload.failure("SHA-256 is unavailable on this Java runtime");
        }
        SkinPayload cached = readMineSkinCache(digest);
        if (cached != null && cached.complete()) {
            return cached;
        }

        String apiKey = v3 == null ? "" : v3.skinMineSkinApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            return SkinPayload.failure("MineSkin API key is not available; set skins.mineskin.api-key"
                    + " (an env:NAME reference is supported)");
        }
        if (apiKey.length() > 4096 || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            return SkinPayload.failure("MineSkin API key is not a valid HTTP header value");
        }

        SkinPayload signed = uploadToMineSkin(png, apiKey);
        if (signed.complete()) {
            writeMineSkinCache(digest, signed);
        }
        return signed;
    }

    private SkinPayload uploadToMineSkin(byte[] png, String apiKey) {
        String boundary = "----NullArmy" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = mineSkinMultipart(png, boundary);
        try {
            HttpRequest request = HttpRequest.newBuilder(MINESKIN_QUEUE_URI)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", mineSkinUserAgent())
                    .header("Accept", "application/json")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> response = mineSkinHttp.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body() == null ? "" : response.body();
            if (responseBody.length() > MAX_MINESKIN_RESPONSE_CHARS) {
                return SkinPayload.failure("MineSkin returned an oversized response");
            }
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                return SkinPayload.failure("MineSkin queue returned HTTP " + response.statusCode());
            }

            SkinPayload immediate = SkinPayload.parse(responseBody);
            if (immediate.complete()) {
                return immediate;
            }
            String jobId = mineSkinJobId(responseBody);
            if (jobId == null) {
                return SkinPayload.failure("MineSkin queue response had no signed texture or job id");
            }
            return pollMineSkinJob(jobId, apiKey);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SkinPayload.failure("MineSkin request was interrupted");
        } catch (Exception failure) {
            // Never include request details here: the Authorization header is a secret.
            return SkinPayload.failure("MineSkin request failed (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private SkinPayload pollMineSkinJob(String jobId, String apiKey) throws IOException, InterruptedException {
        if (!jobId.matches("[A-Za-z0-9._~-]{1,128}")) {
            return SkinPayload.failure("MineSkin returned an invalid job id");
        }
        URI statusUri = URI.create(MINESKIN_BASE_URL + "/v2/queue/" + jobId);
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        long lastPollNanos = 0L;
        for (int poll = 0; poll < MAX_MINESKIN_POLLS && System.nanoTime() < deadline; poll++) {
            if (lastPollNanos != 0L) {
                long remaining = Duration.ofSeconds(1).toNanos() - (System.nanoTime() - lastPollNanos);
                if (remaining > 0L) {
                    java.util.concurrent.TimeUnit.NANOSECONDS.sleep(remaining);
                }
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
            lastPollNanos = System.nanoTime();
            HttpRequest request = HttpRequest.newBuilder(statusUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("User-Agent", mineSkinUserAgent())
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = mineSkinHttp.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            if (body.length() > MAX_MINESKIN_RESPONSE_CHARS) {
                return SkinPayload.failure("MineSkin returned an oversized job response");
            }
            if (response.statusCode() != 200) {
                return SkinPayload.failure("MineSkin job status returned HTTP " + response.statusCode());
            }
            SkinPayload signed = SkinPayload.parse(body);
            if (signed.complete()) {
                return signed;
            }
            String status = mineSkinJobStatus(body);
            if ("failed".equals(status)) {
                return SkinPayload.failure("MineSkin could not sign skins/null.png");
            }
            if ("completed".equals(status)) {
                return SkinPayload.failure("MineSkin completed without signed texture data");
            }
            if (status == null || !("waiting".equals(status) || "active".equals(status)
                    || "unknown".equals(status))) {
                return SkinPayload.failure("MineSkin returned an unrecognized job status");
            }
        }
        return SkinPayload.failure("MineSkin signing timed out while waiting for the queue");
    }

    private String mineSkinUserAgent() {
        String version = plugin.getDescription().getVersion();
        if (version == null || version.trim().isEmpty()) {
            version = "unknown";
        }
        version = version.replaceAll("[^A-Za-z0-9._+-]", "_");
        return "NullArmy/" + version + " (Paper plugin; skin signing)";
    }

    private static byte[] mineSkinMultipart(byte[] png, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
        writeUtf8(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"null.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n");
        out.write(png, 0, png.length);
        writeUtf8(out, "\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"name\"\r\n\r\n"
                + "NullArmy Null\r\n--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"visibility\"\r\n\r\n"
                + "unlisted\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void writeUtf8(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }

    private static boolean hasPngSignature(byte[] bytes) {
        byte[] signature = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes == null || bytes.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = hex[value >>> 4];
            result[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(result);
    }

    private SkinPayload readMineSkinCache(String digest) {
        Path cache = mineSkinCachePath();
        try {
            if (!Files.isRegularFile(cache) || Files.size(cache) > MAX_MINESKIN_RESPONSE_CHARS) {
                return null;
            }
            List<String> lines = Files.readAllLines(cache, StandardCharsets.UTF_8);
            if (lines.size() < 3 || !digest.equals(lines.get(0).trim())) {
                return null;
            }
            SkinPayload payload = SkinPayload.of(lines.get(1).trim(), lines.get(2).trim());
            return payload.complete() ? payload : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private void writeMineSkinCache(String digest, SkinPayload payload) {
        Path cache = mineSkinCachePath();
        Path temporary = cache.resolveSibling(cache.getFileName() + ".tmp");
        try {
            Files.createDirectories(cache.getParent());
            Files.write(temporary, List.of(digest, payload.value(), payload.signature()), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | SecurityException failure) {
            plugin.getLogger().fine("[NullArmy] could not persist the MineSkin result ("
                    + failure.getClass().getSimpleName() + ")");
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The cache is only an optimization; a stale temp file is harmless.
            }
        }
    }

    private Path mineSkinCachePath() {
        return plugin.getDataFolder().toPath().resolve("skins").resolve("null.mineskin.skin");
    }

    private static String mineSkinJobId(String body) {
        Map<String, Object> job = mineSkinJob(body);
        Object id = job == null ? null : job.get("id");
        return id instanceof String ? (String) id : null;
    }

    private static String mineSkinJobStatus(String body) {
        Map<String, Object> job = mineSkinJob(body);
        Object status = job == null ? null : job.get("status");
        return status instanceof String ? (String) status : null;
    }

    private static Map<String, Object> mineSkinJob(String body) {
        try {
            if (body == null || body.length() > MAX_MINESKIN_RESPONSE_CHARS) {
                return null;
            }
            Map<String, Object> root = Json.asObject(Json.parse(body, MAX_MINESKIN_RESPONSE_CHARS));
            Object job = root.get("job");
            if (job instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) job;
                return result;
            }
        } catch (RuntimeException ignored) {
            // The caller will report a generic missing/invalid MineSkin response.
        }
        return null;
    }

    private String account(boolean forCommander) {
        PluginConfig config = plugin.pluginConfig();
        if (config == null) {
            return null;
        }
        try {
            if (forCommander && plugin.commander() != null) {
                String commanderSkin = plugin.commander().skinName();
                if (commanderSkin != null && !commanderSkin.isEmpty()) {
                    return commanderSkin;
                }
            }
            return config.nullSkinName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** GET the proxy and read a texture out of whatever it returned. */
    public SkinPayload fetchProxy(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json, text/plain")
                    .GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body();
            if (response.statusCode() / 100 != 2) {
                lastProxyStatus = "HTTP " + response.statusCode() + ": " + SkinPayload.preview(body, 80);
                plugin.getLogger().warning("[NullArmy] skins.proxy-url answered " + lastProxyStatus);
                return SkinPayload.failure(lastProxyStatus);
            }
            SkinPayload payload = SkinPayload.parse(body);
            lastProxyStatus = "HTTP " + response.statusCode() + (payload.complete() ? " (texture read)"
                    : ": " + payload.error() + " - body: " + SkinPayload.preview(body, 80));
            if (!payload.complete()) {
                plugin.getLogger().warning("[NullArmy] skins.proxy-url " + lastProxyStatus);
            }
            return payload;
        } catch (Throwable t) {
            lastProxyStatus = "request failed: " + Guard.describe(t);
            plugin.getLogger().warning("[NullArmy] skins.proxy-url " + lastProxyStatus);
            return SkinPayload.failure(lastProxyStatus);
        }
    }

    /** Installs a resolution directly (used by the self test after a blocking resolve). */
    public void install(Resolution forNulls) {
        if (forNulls != null) {
            nulls = forNulls;
        }
    }

    /** Re-skins every live Null and the Commander with the current resolution. */
    public int reapplyAll() {
        int done = 0;
        List<NullBody> bodies = new ArrayList<>();
        if (plugin.squads() != null) {
            bodies.addAll(plugin.squads().allMembers());
        }
        NullBody commanderBody = plugin.commander() == null ? null : plugin.commander().body();
        if (commanderBody != null) {
            bodies.add(commanderBody);
        }
        for (NullBody body : bodies) {
            if (reapply(body, body == commanderBody)) {
                done++;
            }
        }
        return done;
    }

    /** Re-skins one body live. */
    public boolean reapply(NullBody body, boolean isCommander) {
        if (body == null || plugin.adapter() == null) {
            return false;
        }
        Resolution r = isCommander && commander.complete() ? commander : nulls;
        SkinData skin = r == null ? null : r.skin();
        String value = skin != null && skin.complete() ? skin.value() : "";
        String signature = skin != null && skin.complete() ? skin.signature() : "";
        boolean ok = false;
        try {
            ok = plugin.adapter().reapplySkin(body, value, signature);
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] live skin re-apply failed: " + Guard.describe(t));
        }
        if (ok) {
            applied.put(body.profileName(), r == null ? "default" : r.source());
        }
        return ok;
    }

    /** Records the source a body was spawned with. */
    public void noteSpawned(NullBody body, boolean isCommander) {
        if (body == null) {
            return;
        }
        Resolution r = isCommander && commander.complete() ? commander : nulls;
        applied.put(body.profileName(), r == null || !r.complete() ? "default skin" : r.source());
    }

    /** One line per live Null: name, source, and whether the profile carries a signed texture. */
    public List<String> report() {
        List<String> out = new ArrayList<>();
        List<NullBody> bodies = new ArrayList<>();
        if (plugin.squads() != null) {
            bodies.addAll(plugin.squads().allMembers());
        }
        if (plugin.commander() != null && plugin.commander().body() != null) {
            bodies.add(plugin.commander().body());
        }
        for (NullBody body : bodies) {
            String[] texture = plugin.adapter() == null ? null : plugin.adapter().skinOf(body);
            String source = applied.getOrDefault(body.profileName(), "unknown");
            out.add(body.profileName() + ": " + source + " - profile "
                    + (texture == null ? "has no texture (default skin)"
                    : "carries a texture" + (texture[1] == null || texture[1].isEmpty() ? " WITHOUT a signature"
                    : " with signature (" + texture[1].length() + " chars)")));
        }
        return out;
    }
}
