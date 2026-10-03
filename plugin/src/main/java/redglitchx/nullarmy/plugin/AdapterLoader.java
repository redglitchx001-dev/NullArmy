package redglitchx.nullarmy.plugin;

import redglitchx.nullarmy.nms.VersionAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Selects the {@link VersionAdapter} matching the running server.
 *
 * <p>Adapters are compiled against their own dev bundle, so they must be
 * resolved by name rather than linked directly - otherwise a plugin built for
 * 1.21.11 would fail to class-load on 1.21.4.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class AdapterLoader {

    /** Adapter class names, newest first. Reflection keeps hard links out. */
    private static final String[] ADAPTERS = {
            "redglitchx.nullarmy.nms.v1_21_11.V1_21_11Adapter"
    };

    private AdapterLoader() {
    }

    /** @return a matching adapter, or null if this server is unsupported */
    static VersionAdapter load(String serverVersion) {
        for (String className : ADAPTERS) {
            try {
                Class<?> clazz = Class.forName(className);
                VersionAdapter adapter = (VersionAdapter) clazz.getDeclaredConstructor().newInstance();
                if (adapter.supports(serverVersion)) {
                    return adapter;
                }
            } catch (ClassNotFoundException e) {
                // Adapter for another server version not present on the classpath.
                // Expected when the plugin is built for a subset of versions.
            } catch (ReflectiveOperationException | ClassCastException e) {
                // A broken adapter is a real problem: report it, do not silently skip.
                throw new IllegalStateException("Failed to load version adapter " + className, e);
            }
        }
        return null;
    }

    static List<String> supportedVersions() {
        List<String> out = new ArrayList<>();
        for (String className : ADAPTERS) {
            try {
                Class<?> clazz = Class.forName(className);
                VersionAdapter adapter = (VersionAdapter) clazz.getDeclaredConstructor().newInstance();
                out.add(adapter.minecraftVersion());
            } catch (ReflectiveOperationException | ClassCastException e) {
                // ignored: see load()
            } catch (ClassNotFoundException e) {
                // ignored: adapter not shipped in this build
            }
        }
        return Collections.unmodifiableList(out);
    }
}
