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

    /** Kept so the skin getters can read their keys lazily, at call time. */
    private final FileConfiguration config;

    private final Caps caps;

    private final boolean griefingEnabled;
    private final boolean explosivesEnabled;
    private final boolean witherEnabled;
    private final long summonPromptTimeoutTicks;
    private final boolean chatEnabledByDefault;
    private final boolean moderationIntegrationEnabled;

    private final int spawnSearchRadius;
    private final int maxTrackedEntities;
    private final String menuTitle;

    private final boolean witherCannonEnabled;
    private final String witherCannonPermission;
    private final int witherCannonMaxCharge;
    private final int witherCannonTntPerShot;
    private final int witherCannonCooldownSeconds;
    private final boolean witherCannonBlocksDamage;

    private final boolean airdropEnabled;
    private final String airdropPermission;
    private final int airdropPortals;
    private final boolean airdropDropNullsFromSky;
    private final int airdropTntPerDrop;
    private final int airdropHeight;

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
        this.config = config;

        /*
         * Every limit is CLAMPED into a legal range and reported, never thrown
         * on. A one-character typo in config.yml must not be able to stop the
         * plugin from enabling - that was blocker-shaped behaviour in the first
         * build, and spec 9 wants visible caps, not a dead plugin.
         */
        int maxLive = clamp(config.getInt("limits.max-live-npcs", 64), 1, 4096,
                "limits.max-live-npcs", logger);
        int hardCap = clamp(config.getInt("limits.summon-hard-cap", 24), 1, 4096,
                "limits.summon-hard-cap", logger);
        if (hardCap > maxLive) {
            if (logger != null) {
                logger.warning("[NullArmy] limits.summon-hard-cap (" + hardCap
                        + ") is above limits.max-live-npcs (" + maxLive
                        + ") - using " + maxLive + " so the two caps agree.");
            }
            hardCap = maxLive;
        }
        int portals = clamp(config.getInt("visuals.portal-effects-per-summon", 16),
                Caps.minPortalEffects(), 512, "visuals.portal-effects-per-summon", logger);
        int pathExpansions = clamp(config.getInt("limits.path-max-expansions", 4096),
                1, 1_000_000, "limits.path-max-expansions", logger);

        this.caps = new Caps()
                .withMaxLiveNpcs(maxLive)
                .withSummonHardCap(hardCap)
                .withPortalEffectsPerSummon(portals)
                .withPathMaxExpansions(pathExpansions)
                .withMaxAiJsonBytes(clamp(config.getInt("ai.defaults.max-json-bytes", 8192),
                        256, 10_000_000, "ai.defaults.max-json-bytes", logger))
                .withEndpointTimeoutMillis(Math.max(1L, config.getLong("ai.defaults.timeout-millis", 3000L)));

        // Spec 8: all destructive options default to FALSE.
        this.griefingEnabled = config.getBoolean("policy.griefing-enabled", false);
        this.explosivesEnabled = config.getBoolean("policy.explosives-enabled", false);
        this.witherEnabled = config.getBoolean("policy.wither-enabled", false);
        this.moderationIntegrationEnabled =
                config.getBoolean("policy.moderation-integration-enabled", false);

        this.summonPromptTimeoutTicks =
                Math.max(20L, config.getLong("summoning.prompt-timeout-ticks", 300L));
        this.chatEnabledByDefault = config.getBoolean("chat.enabled-by-default", false);

        this.spawnSearchRadius = clamp(config.getInt("summoning.spawn-search-radius", 6),
                1, 24, "summoning.spawn-search-radius", logger);
        this.maxTrackedEntities = clamp(config.getInt("limits.max-tracked-entities", 256),
                8, 100_000, "limits.max-tracked-entities", logger);
        String title = config.getString("menu.title", "");
        this.menuTitle = (title == null || title.trim().isEmpty())
                ? "NullArmy - Command Center" : title.trim();

        // ---------------------------------------------------- wither cannon / airdrop
        // Spec 8: everything destructive is OFF unless the owner says otherwise.
        this.witherCannonEnabled = config.getBoolean("wither-cannon.enabled", false);
        this.witherCannonPermission = nonEmpty(
                config.getString("wither-cannon.require-permission", "nullarmy.admin"),
                "nullarmy.admin");
        this.witherCannonMaxCharge = clamp(config.getInt("wither-cannon.max-charge", 3),
                1, 64, "wither-cannon.max-charge", logger);
        this.witherCannonTntPerShot = clamp(config.getInt("wither-cannon.tnt-per-shot", 3),
                1, 64, "wither-cannon.tnt-per-shot", logger);
        this.witherCannonCooldownSeconds = clamp(config.getInt("wither-cannon.cooldown-seconds", 20),
                0, 3600, "wither-cannon.cooldown-seconds", logger);
        this.witherCannonBlocksDamage = config.getBoolean("wither-cannon.blocks-damage", false);

        this.airdropEnabled = config.getBoolean("airdrop.enabled", false);
        this.airdropPermission = nonEmpty(
                config.getString("airdrop.require-permission", "nullarmy.admin"), "nullarmy.admin");
        this.airdropPortals = clamp(config.getInt("airdrop.portals", 16),
                Caps.minPortalEffects(), 512, "airdrop.portals", logger);
        this.airdropDropNullsFromSky = config.getBoolean("airdrop.drop-nulls-from-sky", false);
        this.airdropTntPerDrop = clamp(config.getInt("airdrop.tnt-per-drop", 2),
                0, 64, "airdrop.tnt-per-drop", logger);
        this.airdropHeight = clamp(config.getInt("airdrop.height", 12),
                3, 60, "airdrop.height", logger);

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

    /** How far from the summoner the plugin searches for safe ground. */
    public int spawnSearchRadius() { return spawnSearchRadius; }

    /** Ceiling on entities NullArmy tracks and cleans up itself. */
    public int maxTrackedEntities() { return maxTrackedEntities; }

    /** Title used for the /null menu inventory. */
    public String menuTitle() { return menuTitle; }

    public boolean witherCannonEnabled() { return witherCannonEnabled; }
    public String witherCannonPermission() { return witherCannonPermission; }
    public int witherCannonMaxCharge() { return witherCannonMaxCharge; }
    public int witherCannonTntPerShot() { return witherCannonTntPerShot; }
    public int witherCannonCooldownSeconds() { return witherCannonCooldownSeconds; }

    /**
     * True when the cannon may destroy blocks. Explosives, wither content AND
     * griefing all have to be on as well; spec 8 makes that an explicit,
     * triple opt-in rather than a side effect of one flag.
     */
    public boolean witherCannonBlocksDamage() {
        return witherCannonBlocksDamage && witherEnabled && explosivesEnabled && griefingEnabled;
    }

    /** True when the cannon is switched on <i>and</i> its policy gates are open. */
    public boolean witherCannonUsable() {
        return witherCannonEnabled && explosivesEnabled && witherEnabled;
    }

    /** Why the cannon is not usable, for a one-line player-facing message. */
    public String witherCannonBlockedReason() {
        if (!witherCannonEnabled) {
            return "wither-cannon.enabled is false in config.yml";
        }
        if (!explosivesEnabled) {
            return "policy.explosives-enabled is false in config.yml";
        }
        if (!witherEnabled) {
            return "policy.wither-enabled is false in config.yml";
        }
        if (!witherCannonBlocksDamage && !griefingEnabled) {
            // Still allowed: the shot is visual only. This text is only used
            // when something else is missing, so it is never shown.
            return "";
        }
        return "";
    }

    public boolean airdropEnabled() { return airdropEnabled; }
    public String airdropPermission() { return airdropPermission; }
    public int airdropPortals() { return airdropPortals; }
    public boolean airdropDropNullsFromSky() { return airdropDropNullsFromSky; }
    public int airdropTntPerDrop() { return airdropTntPerDrop; }

    /** How far above the summoner the sky portal opens, in blocks (3..60). */
    public int airdropHeight() { return airdropHeight; }

    private static int clamp(int value, int min, int max, String key, Logger logger) {
        if (value < min) {
            if (logger != null) {
                logger.warning("[NullArmy] " + key + " is " + value + "; using the minimum "
                        + min + " instead of refusing to start.");
            }
            return min;
        }
        if (value > max) {
            if (logger != null) {
                logger.warning("[NullArmy] " + key + " is " + value + "; using the maximum "
                        + max + " instead.");
            }
            return max;
        }
        return value;
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

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
