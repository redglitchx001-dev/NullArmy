package redglitchx.nullarmy.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import redglitchx.nullarmy.core.config.Caps;

/**
 * Typed view over {@code config.yml}.
 *
 * <p>Every value has a documented default, so a missing key never means
 * "undefined behaviour".</p>
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

    public PluginConfig(FileConfiguration config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        this.caps = new Caps()
                .withMaxLiveNpcs(config.getInt("limits.max-live-npcs", 64))
                .withSummonHardCap(config.getInt("limits.summon-hard-cap", 24))
                .withPortalEffectsPerSummon(config.getInt("visuals.portal-effects-per-summon", 16))
                .withPathMaxExpansions(config.getInt("limits.path-max-expansions", 4096))
                .withMaxAiJsonBytes(config.getInt("ai.max-json-bytes", 8192))
                .withEndpointTimeoutMillis(config.getLong("ai.timeout-millis", 3000L));

        // Spec 8: all of these default to FALSE. Destructive behaviour is opt-in.
        this.griefingEnabled = config.getBoolean("policy.griefing-enabled", false);
        this.explosivesEnabled = config.getBoolean("policy.explosives-enabled", false);
        this.witherEnabled = config.getBoolean("policy.wither-enabled", false);
        this.moderationIntegrationEnabled =
                config.getBoolean("policy.moderation-integration-enabled", false);

        this.summonPromptTimeoutTicks = config.getLong("summoning.prompt-timeout-ticks", 300L);
        this.chatEnabledByDefault = config.getBoolean("chat.enabled-by-default", false);
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
}
