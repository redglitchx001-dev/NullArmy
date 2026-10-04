package redglitchx.nullarmy.plugin.config;

/**
 * Implemented by anything that caches values out of {@link PluginConfig}.
 *
 * <p>{@code /null reload} builds a brand-new {@link PluginConfig} (Bukkit
 * replaces the {@code FileConfiguration} object on reload, so a stored
 * reference would go stale) and then hands it to every reloadable component.
 * Without this, a reload would silently keep serving the values read at
 * startup - the classic "I edited the config and nothing changed" bug.</p>
 *
 * <p>Implementations must never throw: a reload has to leave a working plugin
 * behind whatever the new file contains.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public interface Reloadable {

    /** Applies a freshly parsed configuration. */
    void onConfigReloaded(PluginConfig config);
}
