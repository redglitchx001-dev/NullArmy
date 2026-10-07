package redglitchx.nullarmy.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.formation.FormationMatrix;
import redglitchx.nullarmy.core.zone.SummonZone;
import redglitchx.nullarmy.nms.BodySettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Every setting added in v3, read once per (re)load.
 *
 * <p>New booleans default to {@code true}, except settings that could expand
 * world changes beyond the owner's zone. Combat pursuit is never autonomous;
 * attack and hunt orders are required. Numbers are clamped to safe ranges with
 * a console note, never rejected.</p>
 *
 * <p>The AI key is read from {@code ai.builder.api-key}, then {@code ai.api-key},
 * then the environment variable {@code NULLARMY_AI_KEY}; {@code env:NAME} in any
 * of the keys reads that variable instead. The key itself is never logged.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class V3Settings {

    public static final String AI_KEY_ENV = "NULLARMY_AI_KEY";

    // summon / portals
    private final int zoneSize;
    private final int portalLifetimeTicks;
    private final int airHeightMin;
    private final int airHeightMax;
    private final double floatingChance;
    private final boolean restoreAfterExit;
    private final boolean portalPersistUntilClear;
    private final boolean portalParticlesEnabled;

    // combat
    private final boolean combatEnabled;
    private final boolean playersCanHitNulls;
    private final boolean nullsCanHitNulls;
    private final boolean crits;
    private final boolean shields;
    private final boolean bows;
    private final boolean retaliate;
    private final boolean fallDamage;
    private final int shieldDisableTicks;

    // bodies
    private final boolean pickupItems;
    private final boolean collisions;
    private final double separationRadius;
    private final boolean speedVariance;
    private final boolean idleBehaviour;
    private final double eatBelowHealth;
    private final int restAfterIdleTicks;
    private final double formationSpacing;
    private final boolean autoReform;
    private final boolean gestureAck;

    // skins
    private final String skinValue;
    private final String skinSignature;
    private final String skinProxyUrl;
    private final String skinPngPath;
    /** Resolved MineSkin key; never include it in diagnostics or logs. */
    private final String skinMineSkinApiKey;
    private final boolean skinLiveReapply;

    // chat
    private final boolean commanderPublicReplies;
    private final boolean commanderNameTrigger;
    private final boolean chatPluginPrefix;
    private final String mentionPrefix;
    private final boolean silenceUnits;
    private final List<String> protectedNames;

    // v4: names, combat feel, drops, behaviour
    private final String namesStyle;
    private final double meleeReach;
    private final float attackThreshold;
    private final int critEverySwings;
    private final double aimSkill;
    private final boolean dropsEnabled;
    /** Self-test only: forces the drops decision so both halves can be measured. */
    private volatile Boolean dropsOverride;
    /** Self test override for {@code combat.melee-reach} (P-01 wiring). */
    private volatile Double meleeReachOverride;
    private volatile Boolean bowsOverride;
    private volatile Boolean retaliateOverride;
    /** Self test override for {@code combat.aim-skill} (P-05 wiring). */
    private volatile Double aimSkillOverride;
    /** Self test override for {@code behaviour.camp-life} (L-04). */
    private volatile Boolean campLifeOverride;
    private final double dropsChance;
    private final boolean marchCadence;
    private final boolean autoBridge;
    private final boolean campLife;
    private final int marchPeriodTicks;
    private final int drillHoldTicks;
    private final int saluteRange;
    private final int huntChasers;

    // AI builder
    private final boolean builderEnabled;
    private final String builderEndpoint;
    private final String builderModel;
    private final String builderApiKey;
    private final String builderApiKeyStatus;
    private final String builderApiKeyProblem;
    private final int builderTimeoutMs;
    private final int builderMaxSteps;
    private final int builderPlaceRateTicks;
    private final boolean builderGatherOutsideZone;

    public V3Settings(FileConfiguration config, Logger logger) {
        this.zoneSize = clamp(config.getInt("summon.zone-size", SummonZone.DEFAULT_SIZE),
                SummonZone.MIN_SIZE, SummonZone.MAX_SIZE, "summon.zone-size", logger);
        int lifetimeSeconds;
        if (config.isSet("portals.lifetime-s")) {
            lifetimeSeconds = config.getInt("portals.lifetime-s", 30);
        } else {
            lifetimeSeconds = (int) (config.getLong("portals.lifetime-ticks", 600L) / 20L);
        }
        this.portalLifetimeTicks = clamp(lifetimeSeconds, 3, 3600, "portals.lifetime-s", logger) * 20;
        int min = clamp(config.getInt("portals.air-height-min", 4), 1, 64, "portals.air-height-min", logger);
        int max = clamp(config.getInt("portals.air-height-max", 12), 1, 64, "portals.air-height-max", logger);
        if (max < min) {
            if (logger != null) {
                logger.warning("[NullArmy] portals.air-height-max (" + max + ") is below air-height-min ("
                        + min + "); using " + min + " for both.");
            }
            max = min;
        }
        this.airHeightMin = min;
        this.airHeightMax = max;
        this.floatingChance = clampD(config.getDouble("portals.floating-chance", 0.35D), 0.0D, 1.0D);
        this.restoreAfterExit = config.getBoolean("portals.restore-after-exit", true);
        this.portalPersistUntilClear = config.getBoolean("portals.persist-until-clear", false);
        this.portalParticlesEnabled = config.getBoolean("visuals.portal-particles-enabled", true);

        this.combatEnabled = config.getBoolean("combat.enabled", true);
        this.playersCanHitNulls = config.getBoolean("combat.players-can-hit-nulls", true);
        this.nullsCanHitNulls = config.getBoolean("combat.nulls-can-hit-nulls", true);
        this.crits = config.getBoolean("combat.crits", true);
        this.shields = config.getBoolean("combat.shields", true);
        this.bows = config.getBoolean("combat.bows", true);
        this.retaliate = config.getBoolean("combat.retaliate", true);
        this.fallDamage = config.getBoolean("combat.fall-damage", true);
        this.shieldDisableTicks = clamp(config.getInt("combat.shield-disable-ticks", 30), 0, 200,
                "combat.shield-disable-ticks", logger);

        this.pickupItems = config.getBoolean("nulls.pickup-items", true);
        this.collisions = config.getBoolean("nulls.collisions", true);
        this.separationRadius = clampD(config.getDouble("nulls.separation-radius", 1.0D), 0.6D, 3.0D);
        this.speedVariance = config.getBoolean("nulls.speed-variance", true);
        this.idleBehaviour = config.getBoolean("nulls.idle-behaviour", true);
        this.eatBelowHealth = clampD(config.getDouble("nulls.eat-below-health", 0.6D), 0.05D, 0.95D);
        this.restAfterIdleTicks = clamp(config.getInt("nulls.rest-after-idle-s", 60), 5, 3600,
                "nulls.rest-after-idle-s", logger) * 20;
        this.formationSpacing = FormationMatrix.spacing(config.getDouble("formations.spacing", 1.5D));
        this.autoReform = config.getBoolean("formations.auto-reform", true);
        this.gestureAck = config.getBoolean("orders.gesture-ack", true);

        this.skinValue = trimmed(config.getString("skins.value", ""));
        this.skinSignature = trimmed(config.getString("skins.signature", ""));
        this.skinProxyUrl = trimmed(config.getString("skins.proxy-url", ""));
        this.skinPngPath = trimmed(config.getString("skins.png-path", "skins/null.png"));
        this.skinMineSkinApiKey = resolveSecret(config.getString("skins.mineskin.api-key", ""));
        this.skinLiveReapply = config.getBoolean("skins.live-reapply", true);

        this.commanderPublicReplies = config.getBoolean("chat.commander-public-replies", true);
        this.commanderNameTrigger = config.getBoolean("chat.commander-name-trigger", true);
        this.chatPluginPrefix = config.getBoolean("chat.plugin-prefix", false);
        this.mentionPrefix = trimmed(config.getString("chat.mention-prefix", "@"));
        this.silenceUnits = config.getBoolean("chat.silence-units", true);
        this.protectedNames = readProtected(config);

        // The user-requested default is a random alphanumeric handle. Legacy
        // config files may still say "words"; it is accepted as an inert old
        // value but never switches live Nulls back to themed names.
        this.namesStyle = "codes";
        this.meleeReach = clampD(config.getDouble("combat.melee-reach", 3.0D), 0.5D, 3.0D);
        this.attackThreshold = (float) clampD(config.getDouble("combat.attack-threshold", 0.55D), 0.1D, 1.0D);
        this.critEverySwings = clamp(config.getInt("combat.crit-every-swings", 2), 1, 5,
                "combat.crit-every-swings", logger);
        this.aimSkill = clampD(config.getDouble("combat.aim-skill", 0.65D), 0.0D, 1.0D);
        this.dropsEnabled = config.getBoolean("drops.enabled", true);
        this.dropsChance = clampD(config.getDouble("drops.chance", 1.0D), 0.0D, 1.0D);
        this.marchCadence = config.getBoolean("behaviour.march-cadence", true);
        this.autoBridge = config.getBoolean("behaviour.auto-bridge", true);
        this.campLife = config.getBoolean("behaviour.camp-life", true);
        this.marchPeriodTicks = clamp(config.getInt("behaviour.march-period-ticks", 8), 2, 40,
                "behaviour.march-period-ticks", logger);
        this.drillHoldTicks = clamp(config.getInt("behaviour.drill-hold-ticks", 80), 20, 1200,
                "behaviour.drill-hold-ticks", logger);
        this.saluteRange = clamp(config.getInt("behaviour.salute-range", 8), 1, 32,
                "behaviour.salute-range", logger);
        this.huntChasers = clamp(config.getInt("behaviour.hunt-chasers", 2), 1, 8,
                "behaviour.hunt-chasers", logger);

        this.builderEnabled = config.getBoolean("ai.builder.enabled", true);
        this.builderEndpoint = trimmed(config.getString("ai.builder.endpoint", ""));
        this.builderModel = trimmed(config.getString("ai.builder.model", ""));
        ResolvedBuilderKey resolvedBuilderKey = resolveKey(config);
        this.builderApiKey = resolvedBuilderKey.value;
        this.builderApiKeyStatus = resolvedBuilderKey.status;
        this.builderApiKeyProblem = resolvedBuilderKey.problem;
        this.builderTimeoutMs = clamp(config.getInt("ai.builder.timeout-ms", 20000), 500, 120000,
                "ai.builder.timeout-ms", logger);
        this.builderMaxSteps = clamp(config.getInt("ai.builder.max-steps", 400), 1, 4000,
                "ai.builder.max-steps", logger);
        this.builderPlaceRateTicks = clamp(config.getInt("ai.builder.place-rate-ticks", 10), 1, 200,
                "ai.builder.place-rate-ticks", logger);
        this.builderGatherOutsideZone = config.getBoolean("ai.builder.gather-outside-zone", false);
    }

    private static final class ResolvedBuilderKey {
        private final String value;
        private final String status;
        private final String problem;

        private ResolvedBuilderKey(String value, String status, String problem) {
            this.value = value;
            this.status = status;
            this.problem = problem;
        }
    }

    private static ResolvedBuilderKey resolveKey(FileConfiguration config) {
        String unresolved = "";
        for (String path : new String[] {"ai.builder.api-key", "ai.api-key"}) {
            String raw = trimmed(config.getString(path, ""));
            if (raw.isEmpty()) {
                continue;
            }
            if (!raw.startsWith("env:")) {
                return checkedBuilderKey(raw, "inline key set");
            }
            String name = raw.substring(4).trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                if (unresolved.isEmpty()) {
                    unresolved = "API-key environment variable name is invalid";
                }
                continue;
            }
            String value = environmentValue(name);
            if (!value.isEmpty()) {
                return checkedBuilderKey(value, "env:" + name + " resolved");
            }
            if (unresolved.isEmpty()) {
                unresolved = "API-key environment variable '" + name + "' is missing or invalid";
            }
        }
        String fallback = environmentValue(AI_KEY_ENV);
        if (!fallback.isEmpty()) {
            String status = "env:" + AI_KEY_ENV + " resolved"
                    + (unresolved.isEmpty() ? "" : " (configured env key unavailable)");
            return checkedBuilderKey(fallback, status);
        }
        if (!unresolved.isEmpty()) {
            return new ResolvedBuilderKey("", unresolved, unresolved);
        }
        return new ResolvedBuilderKey("", "no key configured (env:" + AI_KEY_ENV + " is optional)", "");
    }

    private static ResolvedBuilderKey checkedBuilderKey(String value, String status) {
        if (!EndpointConfig.isValidApiKeyValue(value)) {
            return new ResolvedBuilderKey("", "API-key value invalid (hidden)",
                    "API-key is too long or contains invalid HTTP header characters");
        }
        return new ResolvedBuilderKey(value, status, "");
    }

    private static String environmentValue(String name) {
        try {
            String value = System.getenv(name);
            return value == null ? "" : value.trim();
        } catch (IllegalArgumentException | SecurityException unavailable) {
            return "";
        }
    }

    private static String resolveSecret(String raw) {
        return fromEnvReference(trimmed(raw));
    }

    private static String fromEnvReference(String raw) {
        if (!raw.startsWith("env:")) {
            return raw;
        }
        String name = raw.substring(4).trim();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return "";
        }
        try {
            String env = System.getenv(name);
            return env == null ? "" : env.trim();
        } catch (IllegalArgumentException | SecurityException unavailable) {
            return "";
        }
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
    }

    /** {@code policy.protected}: names or UUIDs the army may never be ordered to hunt. */
    private static List<String> readProtected(FileConfiguration config) {
        List<String> out = new ArrayList<>();
        for (String raw : config.getStringList("policy.protected")) {
            if (raw != null && !raw.trim().isEmpty()) {
                out.add(raw.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static int clamp(int value, int min, int max, String key, Logger logger) {
        if (value < min || value > max) {
            int fixed = Math.max(min, Math.min(max, value));
            if (logger != null) {
                logger.warning("[NullArmy] " + key + " = " + value + " is outside " + min + ".." + max
                        + " - using " + fixed + ".");
            }
            return fixed;
        }
        return value;
    }

    private static double clampD(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /** What the bodies apply inside vanilla code. */
    public BodySettings bodySettings() {
        return new BodySettings(combatEnabled && playersCanHitNulls, combatEnabled && nullsCanHitNulls,
                fallDamage, pickupItems, collisions);
    }

    public int zoneSize() { return zoneSize; }
    public int portalLifetimeTicks() { return portalLifetimeTicks; }
    public int airHeightMin() { return airHeightMin; }
    public int airHeightMax() { return airHeightMax; }
    public double floatingChance() { return floatingChance; }
    public boolean restoreAfterExit() { return restoreAfterExit; }
    public boolean portalPersistUntilClear() { return portalPersistUntilClear; }
    public boolean portalParticlesEnabled() { return portalParticlesEnabled; }

    public boolean combatEnabled() { return combatEnabled; }
    public boolean playersCanHitNulls() { return playersCanHitNulls; }
    public boolean nullsCanHitNulls() { return nullsCanHitNulls; }
    public boolean crits() { return crits; }
    public boolean shields() { return shields; }
    /**
     * Whether Nulls use bows.
     *
     * <p>The override exists because a check about MELEE reach has to be able to
     * take the bow out of the fight: an arrow is not a sword blow, and its range
     * is not governed by {@code combat.melee-reach}.</p>
     */
    public boolean bows() { return bowsOverride == null ? bows : bowsOverride; }

    /** Self test: forces bows on or off without touching the config on disk. */
    public void setBowsOverride(Boolean override) { this.bowsOverride = override; }
    /** Whether a Null hits back. The self test can force it for one check. */
    public boolean retaliate() { return retaliateOverride == null ? retaliate : retaliateOverride; }

    /** Self test: forces retaliation on or off without touching the config. */
    public void setRetaliateOverride(Boolean override) { this.retaliateOverride = override; }
    public boolean fallDamage() { return fallDamage; }
    public int shieldDisableTicks() { return shieldDisableTicks; }

    public boolean pickupItems() { return pickupItems; }
    public boolean collisions() { return collisions; }
    public double separationRadius() { return separationRadius; }
    public boolean speedVariance() { return speedVariance; }
    public boolean idleBehaviour() { return idleBehaviour; }
    public double eatBelowHealth() { return eatBelowHealth; }
    public int restAfterIdleTicks() { return restAfterIdleTicks; }
    public double formationSpacing() { return formationSpacing; }
    public boolean autoReform() { return autoReform; }
    public boolean gestureAck() { return gestureAck; }

    public String skinValue() { return skinValue; }
    public String skinSignature() { return skinSignature; }
    public String skinProxyUrl() { return skinProxyUrl; }
    /** Relative PNG path; SkinChain restricts it to plugins/NullArmy/skins/. */
    public String skinPngPath() { return skinPngPath; }
    /** Resolved key for MineSkin uploads; never log or expose this value. */
    public String skinMineSkinApiKey() { return skinMineSkinApiKey; }
    public boolean skinLiveReapply() { return skinLiveReapply; }

    public boolean commanderPublicReplies() { return commanderPublicReplies; }
    public boolean commanderNameTrigger() { return commanderNameTrigger; }
    public boolean chatPluginPrefix() { return chatPluginPrefix; }
    public String mentionPrefix() { return mentionPrefix.isEmpty() ? "@" : mentionPrefix; }
    public boolean silenceUnits() { return silenceUnits; }
    public List<String> protectedNames() { return protectedNames; }

    /** True when a name or UUID is on {@code policy.protected}. */
    public boolean isProtected(String name, UUID id) {
        if (protectedNames.isEmpty()) {
            return false;
        }
        if (name != null && protectedNames.contains(name.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return id != null && protectedNames.contains(id.toString().toLowerCase(Locale.ROOT));
    }

    public String namesStyle() { return namesStyle; }
    public boolean readableNames() { return "words".equals(namesStyle); }
    public double meleeReach() { return meleeReachOverride == null ? meleeReach : meleeReachOverride; }
    public float attackThreshold() { return attackThreshold; }
    public int critEverySwings() { return critEverySwings; }
    public double aimSkill() { return aimSkillOverride == null ? aimSkill : aimSkillOverride; }
    public boolean dropsEnabled() { return dropsOverride == null ? dropsEnabled : dropsOverride; }
    /** Self-test only: null restores the configured value. */
    public void setDropsOverride(Boolean override) { this.dropsOverride = override; }

    /** Self test: forces {@code combat.melee-reach} without touching config.yml. */
    public void setMeleeReachOverride(Double override) { this.meleeReachOverride = override; }

    /** Self test: forces {@code combat.aim-skill} without touching config.yml. */
    public void setAimSkillOverride(Double override) { this.aimSkillOverride = override; }

    /** Self test: forces {@code behaviour.camp-life} without touching config.yml. */
    public void setCampLifeOverride(Boolean override) { this.campLifeOverride = override; }
    public double dropsChance() { return dropsChance; }
    public boolean marchCadence() { return marchCadence; }
    public boolean autoBridge() { return autoBridge; }
    public boolean campLife() { return campLifeOverride == null ? campLife : campLifeOverride; }
    public int marchPeriodTicks() { return marchPeriodTicks; }
    public int drillHoldTicks() { return drillHoldTicks; }
    public int saluteRange() { return saluteRange; }
    public int huntChasers() { return huntChasers; }

    public boolean builderEnabled() { return builderEnabled; }
    public String builderEndpoint() { return builderEndpoint; }
    public String builderModel() { return builderModel; }
    public String builderApiKey() { return builderApiKey; }
    public String builderApiKeyStatus() { return builderApiKeyStatus; }
    public String builderApiKeyProblem() { return builderApiKeyProblem; }
    public boolean builderHasKey() { return !builderApiKey.isEmpty(); }
    public int builderTimeoutMs() { return builderTimeoutMs; }
    public int builderMaxSteps() { return builderMaxSteps; }
    public int builderPlaceRateTicks() { return builderPlaceRateTicks; }
    public boolean builderGatherOutsideZone() { return builderGatherOutsideZone; }

    /** Hides URL credentials and query values from command diagnostics. */
    private static String safeEndpointForDisplay(String raw) {
        return redglitchx.nullarmy.core.agent.EndpointConfig.safeEndpointForDisplay(raw);
    }

    /** Effective values for {@code /null config}; secrets are masked. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summon.zone-size", zoneSize);
        out.put("portals.lifetime-s", portalLifetimeTicks / 20);
        out.put("portals.persist-until-clear", portalPersistUntilClear);
        out.put("portals.restore-after-exit", restoreAfterExit);
        out.put("visuals.portal-particles-enabled", portalParticlesEnabled);
        out.put("portals.air-height-min", airHeightMin);
        out.put("portals.air-height-max", airHeightMax);
        out.put("portals.floating-chance", floatingChance);
        out.put("combat.enabled", combatEnabled);
        out.put("combat.players-can-hit-nulls", playersCanHitNulls);
        out.put("combat.crits", crits);
        out.put("combat.shields", shields);
        out.put("combat.bows", bows);
        out.put("combat.retaliate", retaliate);
        out.put("combat.fall-damage", fallDamage);
        out.put("nulls.separation-radius", separationRadius);
        out.put("formations.spacing", formationSpacing);
        out.put("skins.value", skinValue.isEmpty() ? "(unset)" : "(set, " + skinValue.length() + " chars)");
        out.put("skins.signature", skinSignature.isEmpty() ? "(unset)" : "(set)");
        out.put("skins.proxy-url", skinProxyUrl.isEmpty() ? "(unset)" : safeEndpointForDisplay(skinProxyUrl));
        out.put("skins.png-path", skinPngPath.isEmpty() ? "(disabled)" : skinPngPath);
        out.put("skins.mineskin.api-key", skinMineSkinApiKey.isEmpty() ? "(unset)" : "(set, hidden)");
        out.put("ai.builder.enabled", builderEnabled);
        out.put("ai.builder.endpoint", builderEndpoint.isEmpty()
                ? "(unset - deterministic local planner)" : safeEndpointForDisplay(builderEndpoint)
                        + " (connectivity test only)");
        out.put("ai.builder.model", builderModel.isEmpty() ? "(unset)" : builderModel);
        out.put("ai.builder.api-key", builderApiKey.isEmpty()
                ? "(unset; " + builderApiKeyStatus + ")"
                : "(set, hidden; " + builderApiKeyStatus + ")");
        out.put("ai.builder.timeout-ms", builderTimeoutMs);
        out.put("ai.builder.max-steps", builderMaxSteps);
        out.put("ai.builder.place-rate-ticks", builderPlaceRateTicks);
        out.put("ai.builder.gather-outside-zone", builderGatherOutsideZone);
        out.put("names.style", namesStyle);
        out.put("combat.melee-reach", meleeReach);
        out.put("combat.attack-threshold", attackThreshold);
        out.put("combat.aim-skill", aimSkill);
        out.put("chat.mention-prefix", mentionPrefix.isEmpty() ? "@" : mentionPrefix);
        out.put("chat.plugin-prefix", chatPluginPrefix);
        out.put("chat.silence-units", silenceUnits);
        out.put("policy.protected", protectedNames.isEmpty() ? "(none)" : String.join(", ", protectedNames));
        out.put("drops.enabled", dropsEnabled);
        out.put("drops.chance", dropsChance);
        out.put("behaviour.march-cadence", marchCadence);
        out.put("behaviour.auto-bridge", autoBridge);
        out.put("behaviour.camp-life", campLife);
        return out;
    }
}
