package redglitchx.nullarmy.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import redglitchx.nullarmy.core.formation.FormationMatrix;
import redglitchx.nullarmy.core.zone.SummonZone;
import redglitchx.nullarmy.nms.BodySettings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Every setting added in v3, read once per (re)load.
 *
 * <p>New booleans default to {@code true}; the only exceptions are the ones that
 * would let the plugin change the world or start fights on its own:
 * {@code ai.builder.gather-outside-zone} and {@code combat.initiate}. Numbers
 * are clamped to safe ranges with a console note, never rejected.</p>
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

    // combat
    private final boolean combatEnabled;
    private final boolean playersCanHitNulls;
    private final boolean nullsCanHitNulls;
    private final boolean crits;
    private final boolean shields;
    private final boolean bows;
    private final boolean retaliate;
    private final boolean initiate;
    private final boolean fallDamage;
    private final int shieldDisableTicks;

    // bodies
    private final boolean noDeathDrops;
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
    private final boolean skinLiveReapply;

    // chat
    private final boolean commanderPublicReplies;
    private final boolean commanderNameTrigger;

    // AI builder
    private final boolean builderEnabled;
    private final String builderEndpoint;
    private final String builderModel;
    private final String builderApiKey;
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

        this.combatEnabled = config.getBoolean("combat.enabled", true);
        this.playersCanHitNulls = config.getBoolean("combat.players-can-hit-nulls", true);
        this.nullsCanHitNulls = config.getBoolean("combat.nulls-can-hit-nulls", true);
        this.crits = config.getBoolean("combat.crits", true);
        this.shields = config.getBoolean("combat.shields", true);
        this.bows = config.getBoolean("combat.bows", true);
        this.retaliate = config.getBoolean("combat.retaliate", true);
        this.initiate = config.getBoolean("combat.initiate", false);
        this.fallDamage = config.getBoolean("combat.fall-damage", true);
        this.shieldDisableTicks = clamp(config.getInt("combat.shield-disable-ticks", 30), 0, 200,
                "combat.shield-disable-ticks", logger);

        this.noDeathDrops = config.getBoolean("nulls.no-death-drops", true);
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
        this.skinLiveReapply = config.getBoolean("skins.live-reapply", true);

        this.commanderPublicReplies = config.getBoolean("chat.commander-public-replies", true);
        this.commanderNameTrigger = config.getBoolean("chat.commander-name-trigger", true);

        this.builderEnabled = config.getBoolean("ai.builder.enabled", true);
        this.builderEndpoint = trimmed(config.getString("ai.builder.endpoint", ""));
        this.builderModel = trimmed(config.getString("ai.builder.model", ""));
        this.builderApiKey = resolveKey(config);
        this.builderTimeoutMs = clamp(config.getInt("ai.builder.timeout-ms", 20000), 500, 120000,
                "ai.builder.timeout-ms", logger);
        this.builderMaxSteps = clamp(config.getInt("ai.builder.max-steps", 400), 1, 4000,
                "ai.builder.max-steps", logger);
        this.builderPlaceRateTicks = clamp(config.getInt("ai.builder.place-rate-ticks", 10), 1, 200,
                "ai.builder.place-rate-ticks", logger);
        this.builderGatherOutsideZone = config.getBoolean("ai.builder.gather-outside-zone", false);
    }

    private static String resolveKey(FileConfiguration config) {
        for (String path : new String[] {"ai.builder.api-key", "ai.api-key"}) {
            String raw = trimmed(config.getString(path, ""));
            String value = fromEnvReference(raw);
            if (!value.isEmpty()) {
                return value;
            }
        }
        String env = System.getenv(AI_KEY_ENV);
        return env == null ? "" : env.trim();
    }

    private static String fromEnvReference(String raw) {
        if (raw.startsWith("env:")) {
            String env = System.getenv(raw.substring(4).trim());
            return env == null ? "" : env.trim();
        }
        return raw;
    }

    private static String trimmed(String s) {
        return s == null ? "" : s.trim();
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

    public boolean combatEnabled() { return combatEnabled; }
    public boolean playersCanHitNulls() { return playersCanHitNulls; }
    public boolean nullsCanHitNulls() { return nullsCanHitNulls; }
    public boolean crits() { return crits; }
    public boolean shields() { return shields; }
    public boolean bows() { return bows; }
    public boolean retaliate() { return retaliate; }
    public boolean initiate() { return initiate; }
    public boolean fallDamage() { return fallDamage; }
    public int shieldDisableTicks() { return shieldDisableTicks; }

    public boolean noDeathDrops() { return noDeathDrops; }
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
    public boolean skinLiveReapply() { return skinLiveReapply; }

    public boolean commanderPublicReplies() { return commanderPublicReplies; }
    public boolean commanderNameTrigger() { return commanderNameTrigger; }

    public boolean builderEnabled() { return builderEnabled; }
    public String builderEndpoint() { return builderEndpoint; }
    public String builderModel() { return builderModel; }
    public String builderApiKey() { return builderApiKey; }
    public boolean builderHasKey() { return !builderApiKey.isEmpty(); }
    public int builderTimeoutMs() { return builderTimeoutMs; }
    public int builderMaxSteps() { return builderMaxSteps; }
    public int builderPlaceRateTicks() { return builderPlaceRateTicks; }
    public boolean builderGatherOutsideZone() { return builderGatherOutsideZone; }

    /** Effective values for {@code /null config}; secrets are masked. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summon.zone-size", zoneSize);
        out.put("portals.lifetime-s", portalLifetimeTicks / 20);
        out.put("portals.air-height-min", airHeightMin);
        out.put("portals.air-height-max", airHeightMax);
        out.put("portals.floating-chance", floatingChance);
        out.put("combat.enabled", combatEnabled);
        out.put("combat.players-can-hit-nulls", playersCanHitNulls);
        out.put("combat.crits", crits);
        out.put("combat.shields", shields);
        out.put("combat.bows", bows);
        out.put("combat.retaliate", retaliate);
        out.put("combat.initiate", initiate);
        out.put("combat.fall-damage", fallDamage);
        out.put("nulls.no-death-drops", noDeathDrops);
        out.put("nulls.separation-radius", separationRadius);
        out.put("formations.spacing", formationSpacing);
        out.put("skins.value", skinValue.isEmpty() ? "(unset)" : "(set, " + skinValue.length() + " chars)");
        out.put("skins.signature", skinSignature.isEmpty() ? "(unset)" : "(set)");
        out.put("skins.proxy-url", skinProxyUrl.isEmpty() ? "(unset)" : skinProxyUrl);
        out.put("ai.builder.enabled", builderEnabled);
        out.put("ai.builder.endpoint", builderEndpoint.isEmpty() ? "(unset - offline planner)" : builderEndpoint);
        out.put("ai.builder.model", builderModel.isEmpty() ? "(unset)" : builderModel);
        out.put("ai.builder.api-key", builderApiKey.isEmpty() ? "(unset)" : "(set, hidden)");
        out.put("ai.builder.timeout-ms", builderTimeoutMs);
        out.put("ai.builder.max-steps", builderMaxSteps);
        out.put("ai.builder.place-rate-ticks", builderPlaceRateTicks);
        out.put("ai.builder.gather-outside-zone", builderGatherOutsideZone);
        return out;
    }
}
