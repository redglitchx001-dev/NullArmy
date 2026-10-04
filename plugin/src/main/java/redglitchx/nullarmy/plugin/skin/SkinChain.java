package redglitchx.nullarmy.plugin.skin;

import org.bukkit.Bukkit;

import redglitchx.nullarmy.core.skin.SkinPayload;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which skin the Nulls wear, in a fixed order of trust.
 *
 * <ol>
 *   <li>{@code skins.value} + {@code skins.signature} from config.yml - an owner
 *       who pasted a signed texture gets exactly that, with no network;</li>
 *   <li>{@code skins.proxy-url} - any service that returns
 *       {@code {"value","signature"}} JSON (MineSkin and Mojang shapes too) or raw
 *       base64; a failure logs the HTTP status and the first 80 characters of the
 *       body so the owner can see what the proxy said;</li>
 *   <li>Mojang by account name ({@code skins.nulls} / {@code skins.commander}),
 *       through the existing cache and resolver;</li>
 *   <li>a bundled {@code skin.png} - reported honestly: a PNG cannot be shown by
 *       clients until it is signed, so the Nulls keep the default skin and
 *       {@code /null skin} says why.</li>
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

        public SkinData skin() { return skin; }
        public String source() { return source; }
        public List<String> attempts() { return attempts; }
        public boolean complete() { return skin != null && skin.complete(); }
    }

    private final NullArmyPlugin plugin;
    private final HttpClient http;
    private volatile Resolution nulls = new Resolution(null, "not resolved yet", Collections.emptyList());
    private volatile Resolution commander = new Resolution(null, "not resolved yet", Collections.emptyList());
    private final Map<String, String> applied = Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String lastProxyStatus = "";

    public SkinChain(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
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
                Resolution forCommander = resolve(true);
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
        // 2. Proxy.
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
        // 3. Mojang by name.
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
        // 4. Bundled skin.png - honest about what it can and cannot do.
        File png = new File(plugin.getDataFolder(), "skin.png");
        boolean bundled = png.isFile() || plugin.getResource("skin.png") != null;
        attempts.add(bundled ? "skin.png: present, but unsigned - clients will not display it; use"
                + " skins.proxy-url (a signing service) or paste skins.value + skins.signature"
                : "skin.png: none");
        return new Resolution(null, bundled ? "skin.png (unsigned, not displayable) - default skin shown"
                : "nothing resolved - default skin shown", attempts);
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
