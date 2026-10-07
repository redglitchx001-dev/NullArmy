package redglitchx.nullarmy.plugin.skin;

import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A minimal Mojang skin lookup built on the JDK's own HTTP client.
 *
 * <p><b>No third-party dependencies.</b> {@link java.net.http.HttpClient} has
 * shipped in the JDK since Java 11, so this adds nothing to the jar and pulls
 * in no JSON library - the two fields we need are extracted by a tiny scanner
 * in {@link #extractString}.</p>
 *
 * <p>Two calls, same as any skin plugin makes:</p>
 * <ol>
 *   <li>{@code GET https://api.mojang.com/users/profiles/minecraft/<name>}
 *       returns the account's UUID.</li>
 *   <li>{@code GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid>?unsigned=false}
 *       returns the base64 {@code textures} property with its signature.</li>
 * </ol>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled, and never actually run against
 * Mojang (the sandbox has no network route to api.mojang.com).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class MojangSkinClient {

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final String SIGNED_PROFILE_QUERY = "?unsigned=false";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final HttpClient http;

    public MojangSkinClient() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Resolves the skin for a Minecraft username.
     *
     * <p>Returns {@code null} when the account does not exist, the session
     * server is unreachable, or the response carries no textures property.
     * Callers must treat null as "no skin" and carry on - never crash a spawn
     * because a skin lookup failed.</p>
     *
     * <p><b>Must never be called on the server's main thread.</b></p>
     */
    public SkinData fetch(String username) {
        if (username == null) {
            return null;
        }
        String name = username.trim();
        // fetch() is also a public entry point; never let callers bypass the
        // cache's path-safe Mojang-name check and send arbitrary path segments.
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return null;
        }
        if (Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Mojang skin lookups must not run on the server thread");
        }
        try {
            String uuid = lookupUuid(name);
            if (uuid == null || uuid.isEmpty()) {
                return null;
            }
            return fetchProfile(uuid);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    /** Step 1: username -> UUID. Returns null when unknown or unreachable. */
    String lookupUuid(String username) throws IOException, InterruptedException {
        String body = get(UUID_URL + encode(username));
        if (body == null) {
            return null;
        }
        return extractString(body, "id");
    }

    /** Step 2: UUID -> texture value + signature. */
    SkinData fetchProfile(String uuid) throws IOException, InterruptedException {
        String body = get(PROFILE_URL + encode(uuid.replace("-", "")) + SIGNED_PROFILE_QUERY);
        if (body == null) {
            return null;
        }
        // The textures property lives inside the first entry of "properties".
        String value = extractString(body, "value");
        String signature = extractString(body, "signature");
        if (value == null || signature == null) {
            return null;
        }
        return new SkinData(value, signature, SkinData.Source.NETWORK);
    }

    String get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "NullArmy")
                .GET()
                .build();
        HttpResponse<InputStream> response =
                http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 204 || status == 404) {
                return null; // unknown player / no skin: not an error, just absent
            }
            if (status < 200 || status >= 300) {
                throw new IOException("Mojang returned HTTP " + status + " for " + url);
            }
            long declaredLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declaredLength > MAX_RESPONSE_BYTES) {
                throw new IOException("Mojang response exceeded the size limit");
            }
            return new String(readBounded(body), StandardCharsets.UTF_8);
        }
    }

    /** Reads at most MAX_RESPONSE_BYTES; closing on overflow cancels the rest of the body. */
    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw new IOException("Mojang response exceeded the size limit");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /**
     * Pulls the string value of the first {@code "key":"value"} pair whose key
     * matches exactly. Handles backslash escapes; returns null when absent.
     *
     * <p>This is intentionally tiny - it exists so the plugin needs no JSON
     * library. It is not a general-purpose parser.</p>
     */
    static String extractString(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        while (at >= 0) {
            int colon = json.indexOf(':', at + needle.length());
            if (colon < 0) {
                return null;
            }
            int i = colon + 1;
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
                i++;
            }
            if (i < json.length() && json.charAt(i) == '"') {
                StringBuilder out = new StringBuilder();
                i++;
                while (i < json.length()) {
                    char c = json.charAt(i);
                    if (c == '\\') {
                        i++;
                        if (i < json.length()) {
                            out.append(json.charAt(i));
                            i++;
                        }
                        continue;
                    }
                    if (c == '"') {
                        return out.toString();
                    }
                    out.append(c);
                    i++;
                }
            }
            at = json.indexOf(needle, at + needle.length());
        }
        return null;
    }
}
