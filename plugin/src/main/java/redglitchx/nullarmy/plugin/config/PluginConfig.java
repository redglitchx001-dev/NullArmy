package redglitchx.nullarmy.plugin.config;
import redglitchx.nullarmy.plugin.skin.SkinResolver;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import redglitchx.nullarmy.core.agent.AgentBinding;
import redglitchx.nullarmy.core.agent.AgentRegistry;
import redglitchx.nullarmy.core.agent.AgentRole;
import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.config.Caps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Typed view over {@code config.yml}.
 *
 * <p>Every value has a documented default, so a missing key never means
 * "undefined behaviour".</p>
 *
 * <p>The endpoint/agent model is intentionally simple:</p>
 * <ol>
 *   <li>Define <b>as many endpoints as you want</b> under {@code ai.endpoints}.
 *       Each has a model id, a base URL, and the <em>name</em> of the
 *       environment variable holding its API key.</li>
 *   <li>Bind each agent role to one primary endpoint plus optional fallbacks
 *       under {@code ai.agents}.</li>
 *   <li>Anything disabled, unreachable or rate-limited falls back to
 *       deterministic local logic. Nothing ever blocks the server tick.</li>
 * </ol>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class PluginConfig {

    private final Caps caps;

    private final boolean griefingEnabled;
    private final boolean explosivesEnabled;
    private final boolean witherEnabled;
    private final long summonPromptTimeoutTicks;
    private final boolean chatEnabledByDefault;
    private final boolean moderationIntegrationEnabled;

    private final boolean aiEnabled;
    private final Map<String, EndpointConfig> endpoints = new LinkedHashMap<>();
    private final Map<AgentRole, AgentBinding> bindings = new LinkedHashMap<>();

    public PluginConfig(FileConfiguration config) {
        this(config, null);
    }

    public PluginConfig(FileConfiguration config, Logger logger) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be empty");
        }

        this.caps = new Caps()
                .withMaxLiveNpcs(config.getInt("limits.max-live-npcs", 64))
                .withSummonHardCap(config.getInt("limits.summon-hard-cap", 24))
                .withPortalEffectsPerSummon(config.getInt("visuals.portal-effects-per-summon", 16))
                .withPathMaxExpansions(config.getInt("limits.path-max-expansions", 4096))
                .withMaxAiJsonBytes(config.getInt("ai.defaults.max-json-bytes", 8192))
                .withEndpointTimeoutMillis(config.getLong("ai.defaults.timeout-millis", 3000L));

        // Spec 8: all destructive options default to FALSE.
        this.griefingEnabled = config.getBoolean("policy.griefing-enabled", false);
        this.explosivesEnabled = config.getBoolean("policy.explosives-enabled", false);
        this.witherEnabled = config.getBoolean("policy.wither-enabled", false);
        this.moderationIntegrationEnabled =
                config.getBoolean("policy.moderation-integration-enabled", false);

        this.summonPromptTimeoutTicks = config.getLong("summoning.prompt-timeout-ticks", 300L);
        this.chatEnabledByDefault = config.getBoolean("chat.enabled-by-default", false);

        // ------------------------------------------------------------ endpoints
        this.aiEnabled = config.getBoolean("ai.enabled", false);

        ConfigurationSection epSection = config.getConfigurationSection("ai.endpoints");
        if (epSection != null) {
            for (String id : epSection.getKeys(false)) {
                ConfigurationSection s = epSection.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                // Accepted spellings: the current three-field form, plus the
                // older base-url/model/auth-key-env names so existing configs
                // keep working.
                String url = firstNonEmpty(s, "endpoint", "base-url");
                String model = firstNonEmpty(s, "model-id", "model");
                String key = firstNonEmpty(s, "api-key", "api-key-env", "auth-key-env");

                EndpointConfig ep = EndpointConfig.builder(id, url, model)
                        .withApiKey(key)
                        .timeoutMillis(s.getLong("timeout-millis",
                                config.getLong("ai.defaults.timeout-millis", 3000L)))
                        .callsPerMinute(s.getInt("calls-per-minute",
                                config.getInt("ai.defaults.calls-per-minute", 20)))
                        .maxJsonBytes(s.getInt("max-json-bytes",
                                config.getInt("ai.defaults.max-json-bytes", 8192)))
                        .maxRetries(s.getInt("max-retries", 2))
                        .enabled(s.getBoolean("enabled", false))
                        .build();
                endpoints.put(id, ep);
                if (logger != null && ep.hasInlineKey()) {
                    logger.warning("[NullArmy] Endpoint '" + id
                            + "' has its API key written inline in config.yml. It is plain text on"
                            + " disk and can leak through a paste, a backup or a git commit."
                            + " Prefer  api-key: \"env:YOUR_VAR_NAME\"  instead.");
                }
            }
        }

        // --------------------------------------------------------------- agents
        // The ai.agents: section is OPTIONAL. When absent, every role simply
        // uses the default endpoint, so an owner who only cares about adding
        // models never has to think about roles at all.
        String defaultEndpointId = resolveDefaultEndpointId(config);
        boolean autoEnableRoles = aiEnabled && !defaultEndpointId.isEmpty();

        for (AgentRole role : AgentRole.values()) {
            bindings.put(role, AgentBinding.builder(role)
                    .primaryEndpointId(defaultEndpointId)
                    .enabled(autoEnableRoles)
                    .build());
        }

        ConfigurationSection agSection = config.getConfigurationSection("ai.agents");
        List<String> unknownRoles = new ArrayList<>();
        if (agSection != null) {
            for (String key : agSection.getKeys(false)) {
                AgentRole role = AgentRole.fromConfigKey(key);
                ConfigurationSection s = agSection.getConfigurationSection(key);
                if (role == null) {
                    unknownRoles.add(key);
                    continue;
                }
                if (s == null) {
                    continue;
                }
                AgentBinding.Builder b = AgentBinding.builder(role)
                        .primaryEndpointId(s.getString("endpoint", ""))
                        .enabled(s.getBoolean("enabled", false))
                        .minConfidence(s.getDouble("min-confidence", 0.0))
                        .recommendationExpiryMillis(s.getLong("expiry-millis", 5000L));
                for (String fb : s.getStringList("fallbacks")) {
                    b.addFallback(fb);
                }
                bindings.put(role, b.build());
            }
        }

        // ------------------------------------------------------------ validation
        AgentRegistry.Result result = AgentRegistry.validate(endpoints, bindings.values());
        if (logger != null) {
            for (AgentRegistry.Finding f : result.findings()) {
                if (f.severity() == AgentRegistry.Severity.ERROR) {
                    logger.severe("[NullArmy] AI config: " + f.message());
                } else {
                    logger.warning("[NullArmy] AI config: " + f.message());
                }
            }
            if (!unknownRoles.isEmpty()) {
                logger.warning("[NullArmy] Unknown agent role(s) in config.yml: " + unknownRoles
                        + " - ignored. Valid keys: " + validRoleKeys());
            }
            if (aiEnabled) {
                logger.info("[NullArmy] AI enabled with " + endpoints.size()
                        + " endpoint(s) across " + AgentRole.values().length + " agent role(s).");
            }
        }
    }

    /**
     * Picks the endpoint every role uses when it has no explicit binding:
     * {@code ai.default-endpoint} if set, otherwise the first enabled
     * endpoint, otherwise none (which means local deterministic logic only).
     */
    private String resolveDefaultEndpointId(FileConfiguration config) {
        String configured = config.getString("ai.default-endpoint", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        for (EndpointConfig ep : endpoints.values()) {
            if (ep.enabled()) {
                return ep.id();
            }
        }
        return "";
    }

    /** First non-empty value among the given config keys, or "". */
    private static String firstNonEmpty(ConfigurationSection section, String... keys) {
        for (String key : keys) {
            String value = section.getString(key, "");
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return "";
    }

    public Caps caps() { return caps; }

    public boolean griefingEnabled() { return griefingEnabled; }
    public boolean explosivesEnabled() { return explosivesEnabled; }
    public boolean witherEnabled() { return witherEnabled; }

    /**
     * Wither content requires explosives AND griefing AND its own opt-in.
     * Spec 8: "This is dangerous and must be off by default."
     */
    public boolean witherActuallyAllowed() {
        return witherEnabled && explosivesEnabled && griefingEnabled;
    }

    public boolean moderationIntegrationEnabled() { return moderationIntegrationEnabled; }
    public long summonPromptTimeoutTicks() { return summonPromptTimeoutTicks; }
    public boolean chatEnabledByDefault() { return chatEnabledByDefault; }

    // ------------------------------------------------------------------- AI model

    public boolean aiEnabled() { return aiEnabled; }

    /**
     * True when AI is switched on <i>and</i> at least one configured endpoint
     * actually resolves. Used to decide whether AI-only features are live.
     */
    public boolean aiUsable() {
        if (!aiEnabled) {
            return false;
        }
        for (EndpointConfig ep : endpoints.values()) {
            if (ep.isUsable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Minecraft username whose skin ordinary Nulls wear.
     *
     * <p>Config wins over nothing; the system property wins over config, so an
     * owner can override without editing a file. Falls back to
     * {@link SkinResolver#DEFAULT_SKIN_OWNER}.</p>
     */
    public String nullSkinName() {
        String override = System.getProperty("nullarmy.skin.null");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        // The key is "nulls": an unquoted YAML `null` is the null value, not
        // the word, so using it as a key would silently resolve to nothing.
        String configured = config.getString("skins.nulls", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return SkinResolver.DEFAULT_SKIN_OWNER;
    }

    /**
     * The Minecraft username whose skin the Commander wears.
     *
     * <p>Defaults to whatever {@link #nullSkinName()} is, so setting one
     * value changes every NPC, but the two can be set independently.</p>
     */
    public String commanderSkinName() {
        String override = System.getProperty("nullarmy.skin.commander");
        if (override != null && !override.trim().isEmpty()) {
            return override.trim();
        }
        String configured = config.getString("skins.commander", "");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return nullSkinName();
    }

    public Map<String, EndpointConfig> endpoints() {
        return Collections.unmodifiableMap(endpoints);
    }

    public Map<AgentRole, AgentBinding> bindings() {
        return Collections.unmodifiableMap(bindings);
    }

    /** Ordered endpoints to try for a role, or empty for local fallback only. */
    public List<EndpointConfig> chainFor(AgentRole role) {
        if (!aiEnabled) {
            return Collections.emptyList();
        }
        AgentBinding binding = bindings.get(role);
        return AgentRegistry.resolveChain(binding, endpoints);
    }

    public static List<String> validRoleKeys() {
        List<String> out = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            out.add(role.configKey());
        }
        return out;
    }
}
