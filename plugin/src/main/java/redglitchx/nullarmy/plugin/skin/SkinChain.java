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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Which skin the Nulls wear, in a fixed order of trust.
 *
 * <ol>
 *   <li>{@code skins.value} + {@code skins.signature} from config.yml - an owner
 *       who pasted a signed texture gets exactly that, with no network;</li>
 *   <li>The configured relative PNG under {@code plugins/NullArmy/skins/},
 *       uploaded to MineSkin v2 and cached as a signed texture (requires
 *       {@code skins.mineskin.api-key});</li>
 *   <li>{@code skins.proxy-url} - any service that returns
 *       {@code {"value","signature"}} JSON (MineSkin and Mojang shapes too) or raw
 *       base64; failures are reported with bounded, credential-redacted diagnostics
 *       and never include the response body;</li>
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
    private static final int MAX_CUSTOM_PNG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MINESKIN_POLLS = 60;
    private static final int MAX_MINESKIN_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_PROXY_RESPONSE_BYTES = 256 * 1024;

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
    private final AtomicLong refreshGeneration = new AtomicLong();

    public SkinChain(NullArmyPlugin plugin) {
        this.plugin = plugin;
        // A proxy URL may carry credentials in its query/path; never forward it
        // automatically to a redirect target.
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
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
        long generation = refreshGeneration.incrementAndGet();
        lastProxyStatus = "";
        PluginConfig config = plugin.pluginConfig();
        V3Settings v3 = config == null ? null : config.v3();
        boolean sharesSkin = commanderSharesNullSkin(config);
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Resolution forNulls = resolve(false, config, v3, sharesSkin, generation);
                Resolution forCommander = sharesSkin ? forNulls
                        : resolve(true, config, v3, false, generation);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // /null reload may have started another resolution while this
                    // network request was still running. Never let the older config win.
                    if (generation != refreshGeneration.get()) {
                        return;
                    }
                    nulls = forNulls;
                    commander = forCommander;
                    plugin.getLogger().info("[NullArmy] skin for Nulls: " + forNulls.source()
                            + (forNulls.complete() ? "" : " (default skin)"));
                    if (reapply && v3 != null && v3.skinLiveReapply()) {
                        int done = reapplyAll();
                        if (done > 0) {
                            plugin.getLogger().info("[NullArmy] re-skinned " + done + " live Null(s).");
                        }
                    }
                });
            });
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] skin refresh not scheduled: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Runs the chain now (blocking - call off the main thread for the network
     * steps).
     */
    public Resolution resolve(boolean forCommander) {
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("skin-chain network resolution must run asynchronously");
        }
        PluginConfig config = plugin.pluginConfig();
        V3Settings v3 = config == null ? null : config.v3();
        return resolve(forCommander, config, v3, commanderSharesNullSkin(config), -1L);
    }

    private Resolution resolve(boolean forCommander, PluginConfig config, V3Settings v3,
                               boolean sharesSkin, long generation) {
        List<String> attempts = new ArrayList<>();
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
        if (forCommander && !sharesSkin) {
            attempts.add("configured PNG: skipped because the Commander has a separate skin account");
        } else {
            SkinPayload customPng = signCustomPng(v3);
            if (customPng != null) {
                if (customPng.complete()) {
                    attempts.add("configured PNG via MineSkin: signed texture used");
                    return new Resolution(new SkinData(customPng.value(), customPng.signature(),
                            SkinData.Source.MINESKIN), "MineSkin signed configured PNG", attempts);
                }
                attempts.add("configured PNG via MineSkin: " + customPng.error());
            } else {
                attempts.add("configured PNG: not present or disabled");
            }
        }
        // 3. Proxy. Diagnostics intentionally omit URL path, query and response body.
        if (v3 != null && !v3.skinProxyUrl().isEmpty()) {
            String safeProxy = redglitchx.nullarmy.core.agent.EndpointConfig
                    .safeEndpointForDisplay(v3.skinProxyUrl());
            SkinPayload payload = fetchProxy(v3.skinProxyUrl(), generation);
            if (payload.complete()) {
                attempts.add("proxy " + safeProxy + ": used");
                return new Resolution(new SkinData(payload.value(), payload.signature(), SkinData.Source.PROXY),
                        "skins.proxy-url " + safeProxy, attempts);
            }
            attempts.add("proxy " + safeProxy + ": " + payload.error());
        } else {
            attempts.add("proxy: not set");
        }
        // 4. Mojang by an explicitly configured account name only.
        String name = account(forCommander, config);
        if (name != null && !name.isEmpty() && plugin.skins() != null) {
            if (!SkinResolver.isValidAccountName(name)) {
                attempts.add("configured Mojang account name is invalid; lookup skipped");
            } else {
                SkinData cached = plugin.skins().resolveCached(name);
                if (cached != null && cached.complete()) {
                    attempts.add("Mojang account " + name + ": "
                            + cached.source().name().toLowerCase(java.util.Locale.ROOT));
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
                    attempts.add("Mojang account " + name + ": lookup failed ("
                            + t.getClass().getSimpleName() + ")");
                }
            }
        } else {
            attempts.add("Mojang account: not configured");
        }
        // 5. Legacy root-level skin.png is unsigned, so Minecraft cannot display it.
        File legacyPng = new File(plugin.getDataFolder(), "skin.png");
        boolean legacyPngPresent = legacyPng.isFile() || plugin.getResource("skin.png") != null;
        attempts.add(legacyPngPresent ? "legacy skin.png: present but unsigned; use skins.png-path with"
                + " MineSkin, skins.proxy-url, or a signed skins.value + skins.signature"
                : "legacy skin.png: none");
        return new Resolution(null, legacyPngPresent ? "legacy skin.png (unsigned, not displayable) - default skin shown"
                : "nothing resolved - default skin shown", attempts);
    }

    private boolean commanderSharesNullSkin(PluginConfig config) {
        return config == null || config.commanderSkinName().equalsIgnoreCase(config.nullSkinName());
    }

    /**
     * Resolves the configured PNG only inside the dedicated skins directory.
     * Absolute paths, traversal, backslashes and symbolic links are refused.
     * A null result means the source is disabled.
     */
    public static Path resolveConfiguredPng(Path dataFolder, String configured) throws IOException {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) {
            return null;
        }
        if (dataFolder == null || raw.length() > 512 || raw.indexOf('\\') >= 0
                || raw.indexOf('\0') >= 0 || raw.matches(".*[\\r\\n\\t].*")) {
            throw new IllegalArgumentException("PNG path must be a short relative path under skins/");
        }
        Path relative = Path.of(raw);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("absolute PNG paths are not allowed");
        }
        for (Path part : relative) {
            String name = part.toString();
            if ("..".equals(name) || name.contains(":")) {
                throw new IllegalArgumentException("PNG path traversal is not allowed");
            }
        }

        Path root = dataFolder.toRealPath();
        Path allowedRoot = root.resolve("skins").normalize();
        if (Files.isSymbolicLink(allowedRoot)
                || !Files.isDirectory(allowedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("the skins directory must be a real directory");
        }
        Path image = root.resolve(relative).normalize();
        if (!image.startsWith(allowedRoot) || image.equals(allowedRoot)) {
            throw new IllegalArgumentException("PNG path must stay under skins/");
        }

        Path cursor = allowedRoot;
        Path beneathSkins = allowedRoot.relativize(image);
        int index = 0;
        for (Path part : beneathSkins) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException("symbolic links are not allowed in the PNG path");
            }
            index++;
            if (index < beneathSkins.getNameCount() && Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("PNG parent path is not a directory");
            }
        }
        if (!Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
            return image;
        }
        if (!Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("configured PNG is not a regular file");
        }
        Path realImage = image.toRealPath();
        if (!realImage.startsWith(allowedRoot)) {
            throw new IllegalArgumentException("configured PNG resolves outside skins/");
        }
        return realImage;
    }

    /**
     * Signs the administrator-provided PNG and reuses a disk cache until its
     * content changes. File bytes and remote responses are bounded.
     */
    private synchronized SkinPayload signCustomPng(V3Settings v3) {
        final Path image;
        try {
            image = resolveConfiguredPng(plugin.getDataFolder().toPath(),
                    v3 == null ? "skins/null.png" : v3.skinPngPath());
        } catch (IOException | RuntimeException unsafePath) {
            return SkinPayload.failure("configured PNG path is invalid or unsafe");
        }
        if (image == null || !Files.exists(image, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        if (Files.isSymbolicLink(image) || !Files.isRegularFile(image, LinkOption.NOFOLLOW_LINKS)) {
            return SkinPayload.failure("configured PNG is not a regular file");
        }

        final byte[] png;
        try {
            long size = Files.size(image);
            if (size < 24L || size > MAX_CUSTOM_PNG_BYTES) {
                return SkinPayload.failure("configured PNG must be a skin-sized PNG no larger than 2 MiB");
            }
            try (InputStream in = Files.newInputStream(image, LinkOption.NOFOLLOW_LINKS)) {
                png = readBounded(in, MAX_CUSTOM_PNG_BYTES);
            }
            if (png == null) {
                return SkinPayload.failure("configured PNG exceeds the 2 MiB limit");
            }
        } catch (IOException | SecurityException failure) {
            return SkinPayload.failure("configured PNG could not be read ("
                    + failure.getClass().getSimpleName() + ")");
        }
        if (!isSupportedSkinPng(png)) {
            return SkinPayload.failure("configured PNG must be a 64x64 or 64x32 skin image");
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
        if (apiKey.length() > 4096
                || !redglitchx.nullarmy.core.agent.EndpointConfig.isValidApiKeyValue(apiKey)) {
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
            HttpResponse<InputStream> response = mineSkinHttp.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            InputStream responseStream = response.body();
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                if (responseStream != null) {
                    try (InputStream ignored = responseStream) {
                        // Do not read or retain provider error bodies; they may contain credentials.
                    }
                }
                return SkinPayload.failure("MineSkin queue returned HTTP " + response.statusCode());
            }
            if (responseStream == null) {
                return SkinPayload.failure("MineSkin queue returned an empty response");
            }
            String responseBody;
            try (InputStream bodyStream = responseStream) {
                responseBody = readBoundedUtf8(bodyStream, MAX_MINESKIN_RESPONSE_BYTES);
            }
            if (responseBody == null) {
                return SkinPayload.failure("MineSkin returned an oversized response");
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
            HttpResponse<InputStream> response = mineSkinHttp.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            InputStream responseStream = response.body();
            if (response.statusCode() != 200) {
                if (responseStream != null) {
                    try (InputStream ignored = responseStream) {
                        // Provider error bodies are intentionally suppressed.
                    }
                }
                return SkinPayload.failure("MineSkin job status returned HTTP " + response.statusCode());
            }
            if (responseStream == null) {
                return SkinPayload.failure("MineSkin job status returned an empty response");
            }
            String body;
            try (InputStream bodyStream = responseStream) {
                body = readBoundedUtf8(bodyStream, MAX_MINESKIN_RESPONSE_BYTES);
            }
            if (body == null) {
                return SkinPayload.failure("MineSkin returned an oversized job response");
            }
            SkinPayload signed = SkinPayload.parse(body);
            if (signed.complete()) {
                return signed;
            }
            String status = mineSkinJobStatus(body);
            if ("failed".equals(status)) {
                return SkinPayload.failure("MineSkin could not sign the configured PNG");
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

    /**
     * Validates a bounded PNG skin without relying on AWT/ImageIO. Checks chunk
     * order and CRCs, supported skin dimensions, legal colour metadata, a full
     * zlib raster with valid filter bytes, and a terminal IEND chunk.
     */
    public static boolean isSupportedSkinPng(byte[] bytes) {
        if (!hasPngSignature(bytes) || bytes.length < 45 || bytes.length > MAX_CUSTOM_PNG_BYTES) {
            return false;
        }
        int offset = 8;
        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = -1;
        int interlace = -1;
        boolean ihdr = false;
        boolean palette = false;
        boolean idat = false;
        boolean idatEnded = false;
        boolean iend = false;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(Math.min(bytes.length, 32 * 1024));
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) {
                return false;
            }
            int dataLength = readInt(bytes, offset);
            if (dataLength < 0 || dataLength > bytes.length - offset - 12) {
                return false;
            }
            int typeOffset = offset + 4;
            if (!validChunkType(bytes, typeOffset)) {
                return false;
            }
            String type = new String(bytes, typeOffset, 4, StandardCharsets.US_ASCII);
            int dataOffset = offset + 8;
            int crcOffset = dataOffset + dataLength;
            CRC32 crc = new CRC32();
            crc.update(bytes, typeOffset, dataLength + 4);
            if ((int) crc.getValue() != readInt(bytes, crcOffset)) {
                return false;
            }

            if (!ihdr && !"IHDR".equals(type)) {
                return false;
            }
            if ("IHDR".equals(type)) {
                if (ihdr || offset != 8 || dataLength != 13) {
                    return false;
                }
                width = readInt(bytes, dataOffset);
                height = readInt(bytes, dataOffset + 4);
                bitDepth = bytes[dataOffset + 8] & 0xff;
                colorType = bytes[dataOffset + 9] & 0xff;
                interlace = bytes[dataOffset + 12] & 0xff;
                if (width != 64 || (height != 64 && height != 32)
                        || !validPngFormat(bitDepth, colorType)
                        || bytes[dataOffset + 10] != 0 || bytes[dataOffset + 11] != 0
                        || (interlace != 0 && interlace != 1)) {
                    return false;
                }
                ihdr = true;
            } else if ("PLTE".equals(type)) {
                if (palette || idat || colorType == 0 || colorType == 4
                        || dataLength < 3 || dataLength > 768 || dataLength % 3 != 0
                        || colorType == 3 && dataLength / 3 > (1 << bitDepth)) {
                    return false;
                }
                palette = true;
            } else if ("IDAT".equals(type)) {
                if (idatEnded || (colorType == 3 && !palette)) {
                    return false;
                }
                idat = true;
                compressed.write(bytes, dataOffset, dataLength);
            } else if ("IEND".equals(type)) {
                if (!idat || dataLength != 0 || colorType == 3 && !palette) {
                    return false;
                }
                iend = true;
                offset += dataLength + 12;
                if (offset != bytes.length) {
                    return false;
                }
                break;
            } else {
                if (idat) {
                    idatEnded = true;
                }
                // PNG critical chunks have an uppercase first byte. Unknown
                // critical chunks cannot be safely interpreted by this reader.
                if (bytes[typeOffset] >= 'A' && bytes[typeOffset] <= 'Z') {
                    return false;
                }
                if ("PLTE".equals(type)) {
                    return false;
                }
            }
            if (idat && !"IDAT".equals(type) && !"IEND".equals(type)) {
                idatEnded = true;
            }
            offset += dataLength + 12;
        }
        if (!ihdr || !idat || !iend || compressed.size() == 0 || colorType == 3 && !palette) {
            return false;
        }
        return validPngRaster(compressed.toByteArray(), width, height, bitDepth, colorType, interlace);
    }

    private static boolean validChunkType(byte[] bytes, int offset) {
        for (int i = 0; i < 4; i++) {
            int c = bytes[offset + i] & 0xff;
            if (!(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z')) {
                return false;
            }
        }
        // The third character's reserved bit must be zero (uppercase).
        return bytes[offset + 2] >= 'A' && bytes[offset + 2] <= 'Z';
    }

    private static boolean validPngFormat(int bitDepth, int colorType) {
        switch (colorType) {
            case 0: return bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 2: return bitDepth == 8 || bitDepth == 16;
            case 3: return bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 4: return bitDepth == 8 || bitDepth == 16;
            case 6: return bitDepth == 8 || bitDepth == 16;
            default: return false;
        }
    }

    private static boolean validPngRaster(byte[] compressed, int width, int height,
                                          int bitDepth, int colorType, int interlace) {
        int channels;
        switch (colorType) {
            case 0: case 3: channels = 1; break;
            case 2: channels = 3; break;
            case 4: channels = 2; break;
            case 6: channels = 4; break;
            default: return false;
        }
        int bitsPerPixel = channels * bitDepth;
        long expected = expectedRasterBytes(width, height, bitsPerPixel, interlace);
        if (expected <= 0L || expected > 65_536L) {
            return false;
        }
        byte[] raster = new byte[(int) expected + 1];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            int total = 0;
            while (!inflater.finished() && total < raster.length) {
                int count = inflater.inflate(raster, total, raster.length - total);
                if (count == 0) {
                    if (inflater.finished()) {
                        break;
                    }
                    return false;
                }
                total += count;
            }
            if (!inflater.finished() || total != expected || inflater.getRemaining() != 0) {
                return false;
            }
            return validFilterBytes(raster, total, width, height, bitsPerPixel, interlace);
        } catch (DataFormatException invalidZlib) {
            return false;
        } finally {
            inflater.end();
        }
    }

    private static long expectedRasterBytes(int width, int height, int bitsPerPixel, int interlace) {
        if (interlace == 0) {
            long rowBytes = ((long) width * bitsPerPixel + 7L) / 8L;
            return (rowBytes + 1L) * height;
        }
        int[] startX = {0, 4, 0, 2, 0, 1, 0};
        int[] startY = {0, 0, 4, 0, 2, 0, 1};
        int[] stepX = {8, 8, 4, 4, 2, 2, 1};
        int[] stepY = {8, 8, 8, 4, 4, 2, 2};
        long total = 0L;
        for (int pass = 0; pass < 7; pass++) {
            int passWidth = width <= startX[pass] ? 0
                    : (width - startX[pass] + stepX[pass] - 1) / stepX[pass];
            int passHeight = height <= startY[pass] ? 0
                    : (height - startY[pass] + stepY[pass] - 1) / stepY[pass];
            if (passWidth > 0 && passHeight > 0) {
                long rowBytes = ((long) passWidth * bitsPerPixel + 7L) / 8L;
                total += (rowBytes + 1L) * passHeight;
            }
        }
        return total;
    }

    private static boolean validFilterBytes(byte[] raster, int rasterLength, int width, int height,
                                            int bitsPerPixel, int interlace) {
        int[] startX = {0, 4, 0, 2, 0, 1, 0};
        int[] startY = {0, 0, 4, 0, 2, 0, 1};
        int[] stepX = {8, 8, 4, 4, 2, 2, 1};
        int[] stepY = {8, 8, 8, 4, 4, 2, 2};
        int passes = interlace == 0 ? 1 : 7;
        int offset = 0;
        for (int pass = 0; pass < passes; pass++) {
            int passWidth = interlace == 0 ? width : width <= startX[pass] ? 0
                    : (width - startX[pass] + stepX[pass] - 1) / stepX[pass];
            int passHeight = interlace == 0 ? height : height <= startY[pass] ? 0
                    : (height - startY[pass] + stepY[pass] - 1) / stepY[pass];
            if (passWidth == 0 || passHeight == 0) {
                continue;
            }
            int rowBytes = (passWidth * bitsPerPixel + 7) / 8;
            for (int row = 0; row < passHeight; row++) {
                int filter = raster[offset] & 0xff;
                if (filter > 4) {
                    return false;
                }
                offset += rowBytes + 1;
            }
        }
        return offset == rasterLength;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
    }

    /** Reads bounded UTF-8, returning null when the byte limit is exceeded. */
    private static String readBoundedUtf8(InputStream in, int limit) throws IOException {
        byte[] bytes = readBounded(in, limit);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads at most {@code limit} bytes, returning null if one more byte exists. */
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
                // InputStream implementations should make progress for a non-empty
                // buffer, but a custom stream may return zero. Fall back to one byte
                // so a broken response cannot spin the skin worker forever.
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
            Path directory = cache.getParent();
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(cache)
                    || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(cache) > MAX_MINESKIN_RESPONSE_BYTES) {
                return null;
            }
            final byte[] bytes;
            try (InputStream in = Files.newInputStream(cache, LinkOption.NOFOLLOW_LINKS)) {
                bytes = readBounded(in, MAX_MINESKIN_RESPONSE_BYTES);
            }
            if (bytes == null) {
                return null;
            }
            String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\\R", -1);
            if (lines.length < 3 || !digest.equals(lines[0].trim())) {
                return null;
            }
            SkinPayload payload = SkinPayload.of(lines[1].trim(), lines[2].trim());
            return payload.complete() ? payload : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    private void writeMineSkinCache(String digest, SkinPayload payload) {
        Path cache = mineSkinCachePath();
        Path directory = cache.getParent();
        Path temporary = null;
        try {
            if (payload == null || !payload.complete() || digest == null || !digest.matches("[0-9a-f]{64}")) {
                return;
            }
            if (Files.isSymbolicLink(directory)) {
                return;
            }
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(cache)) {
                return;
            }
            // A unique, newly-created sibling avoids following a stale or
            // attacker-planted fixed .tmp symlink from an earlier run.
            temporary = Files.createTempFile(directory, "null-mineskin-", ".tmp");
            Files.write(temporary, List.of(digest, payload.value(), payload.signature()), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | SecurityException failure) {
            plugin.getLogger().fine("[NullArmy] could not persist the MineSkin result ("
                    + failure.getClass().getSimpleName() + ")");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The cache is only an optimization; a stale temp file is harmless.
                }
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
            if (body == null || body.length() > MAX_MINESKIN_RESPONSE_BYTES) {
                return null;
            }
            Map<String, Object> root = Json.asObject(Json.parse(body, MAX_MINESKIN_RESPONSE_BYTES));
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

    private String account(boolean forCommander, PluginConfig config) {
        if (config == null) {
            return "";
        }
        try {
            return forCommander ? config.commanderSkinName() : config.nullSkinName();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** GET the configured proxy; never retain or report its response body on failure. */
    public SkinPayload fetchProxy(String url) {
        return fetchProxy(url, -1L);
    }

    private SkinPayload fetchProxy(String url, long generation) {
        String status;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json, text/plain")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream ignored = response.body()) {
                    // Do not parse, retain or log an error body; it may contain credentials.
                }
                status = "HTTP " + response.statusCode() + " (response body suppressed)";
                setProxyStatus(generation, status);
                plugin.getLogger().warning("[NullArmy] skins.proxy-url " + status);
                return SkinPayload.failure("proxy returned HTTP " + response.statusCode());
            }
            final byte[] bytes;
            try (InputStream body = response.body()) {
                bytes = readBounded(body, MAX_PROXY_RESPONSE_BYTES);
            }
            if (bytes == null) {
                status = "HTTP " + response.statusCode() + " (response exceeded the size limit)";
                setProxyStatus(generation, status);
                plugin.getLogger().warning("[NullArmy] skins.proxy-url " + status);
                return SkinPayload.failure("proxy response exceeded the size limit");
            }
            SkinPayload payload = SkinPayload.parse(new String(bytes, StandardCharsets.UTF_8));
            status = "HTTP " + response.statusCode() + (payload.complete()
                    ? " (texture read)" : " (no usable signed texture)");
            setProxyStatus(generation, status);
            if (!payload.complete()) {
                plugin.getLogger().warning("[NullArmy] skins.proxy-url " + status);
            }
            return payload;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            status = "request interrupted";
        } catch (Throwable failure) {
            status = "request failed (" + failure.getClass().getSimpleName() + ")";
        }
        setProxyStatus(generation, status);
        plugin.getLogger().warning("[NullArmy] skins.proxy-url " + status);
        return SkinPayload.failure(status);
    }

    private void setProxyStatus(long generation, String status) {
        if (generation < 0L || generation == refreshGeneration.get()) {
            lastProxyStatus = status == null ? "" : status;
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
