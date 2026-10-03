package redglitchx.nullarmy.plugin.config;

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
                EndpointConfig ep = EndpointConfig.builder(id)
                        .baseUrl(s.getString("base-url", ""))
                        .model(s.getString("model", ""))
                        .authKeyEnv(s.getString("auth-key-env", ""))
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
            }
        }

        // --------------------------------------------------------------- agents
        // Start from every known role, disabled, then apply overrides.
        for (AgentRole role : AgentRole.values()) {
            bindings.put(role, AgentBinding.builder(role).enabled(false).build());
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
                        + " — ignored. Valid keys: " + validRoleKeys());
            }
            if (aiEnabled) {
                logger.info("[NullArmy] AI enabled with " + endpoints.size()
                        + " endpoint(s) across " + AgentRole.values().length + " agent role(s).");
            }
        }
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
