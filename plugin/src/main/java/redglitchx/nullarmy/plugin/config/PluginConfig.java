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
import java.util.Locale;
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
    private final boolean portalParticlesEnabled;

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
    private final int witherCannonShots;
    private final int witherCannonPerShot;
    private final String witherCannonPattern;
    private final int witherCannonFuseTicks;
    private final int witherCannonShotDelayTicks;
    private final int witherCannonRange;
    private final double witherCannonRadius;
    private final double witherCannonHeight;

    private final boolean airdropEnabled;
    private final String airdropPermission;
    private final int airdropPortals;
    private final boolean airdropDropNullsFromSky;
    private final int airdropTntPerDrop;
    private final int airdropHeight;

    private final boolean portalTravelEnabled;
    private final int chatCommandsPerMinute;
    private final List<String> chatWakeWords;
    private final int chatMaxReplyChars;
    private final int chatSessionTimeoutTicks;
    private final String personaNull;
    private final String personaCommander;

    // ------------------------------------------------------------- portals
    private final boolean portalsEnabled;
    private final int portalsMaxPerSummon;
    private final int portalsMaxPerPortal;
    private final int portalsMaxActive;
    private final long portalsLifetimeTicks;
    private final boolean portalsPersistUntilClear;
    private final int portalSearchRadius;
    private final boolean portalTravelAllowed;

    // --------------------------------------------------------------- totem
    private final boolean totemShutdownEnabled;
    private final int totemShutdownDelayTicks;
    private final boolean totemAnnounceProgress;
    private final boolean totemDespawnTriggersShutdown;

    // ------------------------------------------------------------- loadout
    private final List<String> defaultKitLines;
    private final boolean kitAppliesToNulls;
    private final boolean kitAppliesToCommander;

    // ------------------------------------------------------------ missions
    private final boolean missionsEnabled;
    private final boolean commanderSpawnWithPortal;

    // ---------------------------------------------------------------- chat
    private final boolean commanderOnlyConversation;
    private final boolean nullsOrdersOnly;

    // --------------------------------------------------------------- nulls
    private final boolean nullsInTabList;
    private final boolean plainNames;

    // ------------------------------------------------------------ self test
    private final int selfTestSquadSize;

    // ------------------------------------------------------------------ ai
    private final boolean aiSquadCoordination;
    private final boolean aiAutoCoordinate;
    private final int aiCoordinateIntervalTicks;

    private final boolean aiEnabled;
    private final Map<String, EndpointConfig> endpoints = new LinkedHashMap<>();
    private final Map<String, String> endpointProblems = new LinkedHashMap<>();

    /** Every v3 setting (combat, zone, portals, skins, builder, bodies). */
    private final V3Settings v3;
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
        // v3: every count is capped at 100 - a server cannot be talked into a
        // thousand ServerPlayer bodies by a typo.
        int maxLive = clamp(config.getInt("limits.max-live-npcs", 64), 1, 100,
                "limits.max-live-npcs", logger);
        int hardCap = clamp(config.getInt("limits.summon-hard-cap", 24), 1, 100,
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
        this.portalParticlesEnabled = config.getBoolean("visuals.portal-particles-enabled", true);
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
        // v4 (P-08): the orbital barrage. skulls-per-shot is the new name;
        // minecarts-per-shot is accepted so an older config still works.
        this.witherCannonShots = clamp(config.getInt("wither-cannon.shots", 3),
                1, 64, "wither-cannon.shots", logger);
        int perShot = config.isSet("wither-cannon.skulls-per-shot")
                ? config.getInt("wither-cannon.skulls-per-shot", 24)
                : config.getInt("wither-cannon.minecarts-per-shot", 24);
        this.witherCannonPerShot = clamp(perShot, 1, 100, "wither-cannon.skulls-per-shot", logger);
        this.witherCannonPattern = nonEmpty(config.getString("wither-cannon.pattern", "sphere"),
                "sphere").trim();
        this.witherCannonFuseTicks = clamp(config.getInt("wither-cannon.fuse-ticks", 60),
                1, 1200, "wither-cannon.fuse-ticks", logger);
        this.witherCannonShotDelayTicks = clamp(config.getInt("wither-cannon.shot-delay-ticks", 10),
                0, 1200, "wither-cannon.shot-delay-ticks", logger);
        this.witherCannonRange = clamp(config.getInt("wither-cannon.range", 120),
                8, 256, "wither-cannon.range", logger);
        this.witherCannonRadius = Math.max(0.5D, config.getDouble("wither-cannon.radius", 8.0D));
        this.witherCannonHeight = Math.max(4.0D, config.getDouble("wither-cannon.height", 40.0D));

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

        // ------------------------------------------------------------ mechanics
        this.portalTravelEnabled = config.getBoolean("mechanics.portal-travel", true);

        // ------------------------------------------------------------ chat + AI chat
        this.chatCommandsPerMinute = clamp(config.getInt("chat.commands-per-minute", 20),
                1, 600, "chat.commands-per-minute", logger);
        this.chatWakeWords = readWakeWords(config);
        this.chatMaxReplyChars = clamp(config.getInt("chat.max-reply-chars", 400),
                40, 2000, "chat.max-reply-chars", logger);
        this.chatSessionTimeoutTicks = clamp(config.getInt("chat.session-timeout-seconds", 600),
                10, 86400, "chat.session-timeout-seconds", logger) * 20;
        this.personaNull = config.getString("chat.personas.null", "");
        this.personaCommander = config.getString("chat.personas.commander", "");

        // ------------------------------------------------------------- portals
        // Real, temporary doorways the Nulls walk out of. On by default: an
        // arrival made of particles only is not an arrival.
        this.portalsEnabled = config.getBoolean("portals.enabled", true);
        this.portalsMaxPerSummon = clamp(config.getInt("portals.max-per-summon", 4),
                1, redglitchx.nullarmy.core.portal.PortalPlan.HARD_PORTAL_CEILING,
                "portals.max-per-summon", logger);
        this.portalsMaxPerPortal = clamp(config.getInt("portals.max-per-portal", 2),
                1, 2, "portals.max-per-portal", logger);
        this.portalsMaxActive = clamp(config.getInt("portals.max-active", 32),
                1, 512, "portals.max-active", logger);
        this.portalsLifetimeTicks = clamp((int) config.getLong("portals.lifetime-ticks", 600L),
                20, 72_000, "portals.lifetime-ticks", logger);
        this.portalsPersistUntilClear = config.getBoolean("portals.persist-until-clear", false);
        this.portalSearchRadius = clamp(config.getInt("portals.search-radius", 6),
                1, 24, "portals.search-radius", logger);
        this.portalTravelAllowed = config.getBoolean("portals.allow-travel", false);

        // --------------------------------------------------------------- totem
        this.totemShutdownEnabled = config.getBoolean("totem.shutdown-enabled", true);
        this.totemShutdownDelayTicks = clamp(config.getInt("totem.shutdown-delay-ticks", 10),
                1, 200, "totem.shutdown-delay-ticks", logger);
        this.totemAnnounceProgress = config.getBoolean("totem.announce-progress", true);
        this.totemDespawnTriggersShutdown =
                config.getBoolean("totem.despawn-triggers-shutdown", true);

        // ------------------------------------------------------------- loadout
        this.defaultKitLines = readKit(config);
        this.kitAppliesToNulls = config.getBoolean("loadout.apply-to-nulls", true);
        this.kitAppliesToCommander = config.getBoolean("loadout.apply-to-commander", true);

        // ------------------------------------------------------------ missions
        this.missionsEnabled = config.getBoolean("missions.enabled", true);
        this.commanderSpawnWithPortal = config.getBoolean("commander.spawn-with-portal", true);

        // ---------------------------------------------------------------- chat
        this.commanderOnlyConversation =
                config.getBoolean("chat.commander-only-conversation", true);
        this.nullsOrdersOnly = config.getBoolean("chat.nulls-orders-only", true);

        // --------------------------------------------------------------- nulls
        this.nullsInTabList = config.getBoolean("nulls.show-in-tab-list", true);
        this.plainNames = config.getBoolean("nulls.plain-names", true);

        // ------------------------------------------------------------ self test
        this.selfTestSquadSize = clamp(config.getInt("selftest.squad-size", 5),
                1, 32, "selftest.squad-size", logger);

        // ------------------------------------------------------------------ ai
        this.aiSquadCoordination = config.getBoolean("ai.squad-coordination", true);
        this.aiAutoCoordinate = config.getBoolean("ai.auto-coordinate", true);
        this.aiCoordinateIntervalTicks = clamp(config.getInt("ai.coordinate-interval-ticks", 400),
                100, 72_000, "ai.coordinate-interval-ticks", logger);

        // ------------------------------------------------------------ endpoints
        this.aiEnabled = config.getBoolean("ai.enabled", false);
        this.v3 = new V3Settings(config, logger);

        ConfigurationSection epSection = config.getConfigurationSection("ai.endpoints");
        if (epSection != null) {
            for (String id : epSection.getKeys(false)) {
                ConfigurationSection s = epSection.getConfigurationSection(id);
                if (s == null) {
                    endpointProblems.put(id, "expected a mapping with endpoint and model-id fields");
                    if (logger != null) {
                        logger.warning("[NullArmy] AI endpoint '" + id
                                + "' was not registered: expected a mapping with endpoint and model-id fields.");
                    }
                    continue;
                }
                // Accepted spellings: the current three-field form, plus the
                // older base-url/model/auth-key-env names so existing configs
                // keep working. Legacy *-key-env fields contain an environment
                // variable name, so translate them to the explicit env: form.
                String url = firstNonEmpty(s, "endpoint", "base-url");
                String model = firstNonEmpty(s, "model-id", "model");
                String key = firstNonEmpty(s, "api-key");
                if (key.isEmpty()) {
                    String envName = firstNonEmpty(s, "api-key-env", "auth-key-env");
                    key = envName.isEmpty() || envName.startsWith("env:") ? envName : "env:" + envName;
                }

                try {
                    EndpointConfig ep = EndpointConfig.builder(id, url, model)
                            .withApiKey(key)
                            .timeoutMillis(clampLong(s.getLong("timeout-millis",
                                    config.getLong("ai.defaults.timeout-millis", 3000L)),
                                    500L, 120_000L, "ai.endpoints." + id + ".timeout-millis", logger))
                            .callsPerMinute(s.getInt("calls-per-minute",
                                    config.getInt("ai.defaults.calls-per-minute", 20)))
                            .maxJsonBytes(clamp(s.getInt("max-json-bytes",
                                    config.getInt("ai.defaults.max-json-bytes", 8192)),
                                    256, 10_000_000, "ai.endpoints." + id + ".max-json-bytes", logger))
                            .maxRetries(clamp(s.getInt("max-retries", 2), 0, 5,
                                    "ai.endpoints." + id + ".max-retries", logger))
                            // Shipped providers set enabled:false explicitly. A
                            // new custom entry with the four required fields is
                            // usable immediately unless its owner opts it out.
                            .enabled(s.getBoolean("enabled", true))
                            .build();
                    endpoints.put(id, ep);
                    if (logger != null && ep.hasInlineKey()) {
                        logger.warning("[NullArmy] Endpoint '" + id
                                + "' has its API key written inline in config.yml. It is plain text on"
                                + " disk and can leak through a paste, a backup or a git commit."
                                + " Prefer  api-key: \"env:YOUR_VAR_NAME\"  instead.");
                    }
                } catch (RuntimeException invalidEndpoint) {
                    // One malformed custom endpoint must not discard every
                    // other setting or leave the plugin on a stale config.
                    endpointProblems.put(id, "invalid URL/model or limit; check endpoint, model-id and limits");
                    if (logger != null) {
                        logger.warning("[NullArmy] AI endpoint '" + id
                                + "' was not registered: invalid URL/model or limit."
                                + " Other settings remain loaded; fix this entry and run /null reload.");
                    }
                }
            }
        }

        // --------------------------------------------------------------- agents
        // The ai.agents: section is OPTIONAL. When absent, every role simply
        // uses the default endpoint, so an owner who only cares about adding
        // models never has to think about roles at all.
        String defaultEndpointId = resolveDefaultEndpointId(config, logger);
        boolean autoEnableRoles = aiEnabled && !defaultEndpointId.isEmpty();

        for (AgentRole role : AgentRole.values()) {
            AgentBinding.Builder defaultBinding = AgentBinding.builder(role)
                    .primaryEndpointId(defaultEndpointId)
                    .enabled(autoEnableRoles);
            // A simple endpoint list is useful as a fallback list by itself:
            // use the selected default first, then every other enabled entry
            // in YAML order. Explicit role bindings below replace this chain.
            for (EndpointConfig endpoint : endpoints.values()) {
                if (endpoint.enabled() && !endpoint.id().equals(defaultEndpointId)) {
                    defaultBinding.addFallback(endpoint.id());
                }
            }
            bindings.put(role, defaultBinding.build());
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
    private String resolveDefaultEndpointId(FileConfiguration config, Logger logger) {
        String configured = config.getString("ai.default-endpoint", "");
        if (configured != null && !configured.trim().isEmpty()) {
            String id = configured.trim();
            EndpointConfig selected = endpoints.get(id);
            if (selected != null && selected.enabled()) {
                return selected.id();
            }
            if (logger != null) {
                logger.warning("[NullArmy] ai.default-endpoint '" + id
                        + "' is missing or disabled; selecting the first enabled endpoint instead.");
            }
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

    // ------------------------------------------------------------- portals API

    /** Whether optional portal particles are enabled; physical frames are unaffected. */
    public boolean portalParticlesEnabled() { return portalParticlesEnabled; }

    /** Whether arrivals are built as real, temporary portal doorways. */
    public boolean portalsEnabled() { return portalsEnabled; }

    /** Hard maximum of doorways one summon may open. Always at least 1. */
    public int portalsMaxPerSummon() { return portalsMaxPerSummon; }

    /** How many Nulls may share one doorway. */
    public int portalsMaxPerPortal() { return portalsMaxPerPortal; }

    /** How many doorways may stand in the world at once. */
    public int portalsMaxActive() { return portalsMaxActive; }

    /** How long a doorway stays before its blocks are restored. */
    public long portalsLifetimeTicks() { return portalsLifetimeTicks; }

    /** Whether arrival frames remain until /null portals clear or plugin shutdown. */
    public boolean portalsPersistUntilClear() { return portalsPersistUntilClear; }

    /** How far from the summoner a site is searched for. */
    public int portalSearchRadius() { return portalSearchRadius; }

    /**
     * Whether standing in one of our doorways may send a player to the Nether.
     *
     * <p>Off by default and deliberately so: the plugin must not be the reason a
     * Nether portal gets generated on somebody's map.</p>
     */
    public boolean portalTravelAllowed() { return portalTravelAllowed; }

    // --------------------------------------------------------------- totem API

    public boolean totemShutdownEnabled() { return totemShutdownEnabled; }
    public int totemShutdownDelayTicks() { return totemShutdownDelayTicks; }
    public boolean totemAnnounceProgress() { return totemAnnounceProgress; }
    public boolean totemDespawnTriggersShutdown() { return totemDespawnTriggersShutdown; }

    // ------------------------------------------------------------- loadout API

    /** The default kit in config form: {@code slot:MATERIAL:count}. */
    public List<String> defaultKitLines() { return defaultKitLines; }

    public boolean kitAppliesToNulls() { return kitAppliesToNulls; }
    public boolean kitAppliesToCommander() { return kitAppliesToCommander; }

    // ------------------------------------------------------------ missions API

    public boolean missionsEnabled() { return missionsEnabled; }

    /** The Commander arrives through a real temporary doorway, not just effects. */
    public boolean commanderSpawnWithPortal() { return commanderSpawnWithPortal; }

    // ---------------------------------------------------------------- chat API

    /** Only the Commander holds a conversation; Nulls take orders and stay quiet. */
    public boolean commanderOnlyConversation() { return commanderOnlyConversation; }

    /** Ordinary Nulls answer orders only, never chat. */
    public boolean nullsOrdersOnly() { return nullsOrdersOnly; }


    // --------------------------------------------------------------- nulls API

    /** Nulls appear in the client's tab list, with a plain name. */
    public boolean nullsInTabList() { return nullsInTabList; }

    /** Names carry no colours or symbols, exactly like a normal player. */
    public boolean plainNames() { return plainNames; }

    // ------------------------------------------------------------------ ai API

    /** How many Nulls the runtime smoke test summons as a squad. */
    public int selfTestSquadSize() { return selfTestSquadSize; }

    /** The AI may coordinate the squad through typed, allowlisted actions. */
    public boolean aiSquadCoordination() { return aiSquadCoordination; }

    /** The local coordinator may take safe steps on its own, on an interval. */
    public boolean aiAutoCoordinate() { return aiAutoCoordinate; }

    /** How often the local coordinator may act by itself. */
    public int aiCoordinateIntervalTicks() { return aiCoordinateIntervalTicks; }

    /** Reads the default kit, falling back to the shipped one. */
    private static List<String> readKit(FileConfiguration config) {
        try {
            List<String> raw = config.getStringList("loadout.default-kit");
            List<String> out = new ArrayList<>();
            if (raw != null) {
                for (String line : raw) {
                    if (line != null && !line.trim().isEmpty()) {
                        out.add(line.trim());
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    /** The v3 settings. Never null. */
    public V3Settings v3() { return v3; }

    /** The parsed file this configuration was built from. */
    public FileConfiguration file() { return config; }

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
    public int witherCannonShots() { return witherCannonShots; }
    public int witherCannonPerShot() { return witherCannonPerShot; }
    public String witherCannonPattern() { return witherCannonPattern; }
    public int witherCannonFuseTicks() { return witherCannonFuseTicks; }
    public int witherCannonShotDelayTicks() { return witherCannonShotDelayTicks; }
    public int witherCannonRange() { return witherCannonRange; }
    public double witherCannonRadius() { return witherCannonRadius; }
    public double witherCannonHeight() { return witherCannonHeight; }

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

    /**
     * Whether {@code /null portal} may relocate a body.
     *
     * <p>On by default because it is an owner-run command with a visible
     * portal at both ends; it is still the one deliberate exception to the
     * no-teleport rule, so an owner who wants the strict rule can switch it off
     * and every Null will refuse to make the crossing.</p>
     */
    public boolean portalTravelEnabled() { return portalTravelEnabled; }

    /** Orders a single player may give per minute through chat. */
    public int chatCommandsPerMinute() { return chatCommandsPerMinute; }

    /** Words that turn a chat line into an order. Never empty. */
    public List<String> chatWakeWords() { return chatWakeWords; }

    /** Longest reply the plugin will print, in characters. */
    public int chatMaxReplyChars() { return chatMaxReplyChars; }

    /** How long a private channel stays open without activity. */
    public int chatSessionTimeoutTicks() { return chatSessionTimeoutTicks; }

    /** System prompt for an ordinary Null; "" means "use the built-in one". */
    public String personaNull() { return personaNull; }

    /** System prompt for the Commander; "" means "use the built-in one". */
    public String personaCommander() { return personaCommander; }

    private static long clampLong(long value, long min, long max, String key, Logger logger) {
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

    /**
     * Reads the wake words, always returning something usable.
     *
     * <p>An empty or malformed list would silently disable the whole chat
     * interface, so the built-in words are the fallback, not an error.</p>
     */
    private static List<String> readWakeWords(FileConfiguration config) {
        List<String> fallback = List.of("null", "nulls", "commander");
        try {
            // getStringList() silently drops null elements, and `- null` unquoted
            // in YAML IS a null element - the owner meant the word "null".
            List<String> raw = new ArrayList<>();
            List<?> list = config.getList("chat.wake-words");
            if (list != null) {
                for (Object item : list) {
                    raw.add(item == null ? "null" : String.valueOf(item));
                }
            }
            if (raw == null || raw.isEmpty()) {
                return fallback;
            }
            List<String> out = new ArrayList<>();
            for (String word : raw) {
                if (word != null && !word.trim().isEmpty()) {
                    out.add(word.trim().toLowerCase(Locale.ROOT));
                }
            }
            return out.isEmpty() ? fallback : out;
        } catch (Throwable t) {
            return fallback;
        }
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

    /** Malformed endpoint entries skipped without rejecting the rest of config.yml. */
    public Map<String, String> endpointProblems() {
        return Collections.unmodifiableMap(endpointProblems);
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
