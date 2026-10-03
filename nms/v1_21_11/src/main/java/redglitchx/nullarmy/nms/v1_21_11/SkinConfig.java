package redglitchx.nullarmy.nms.v1_21_11;

/**
 * Reads the configured Null skin.
 *
 * <p><b>STATUS: UNVERIFIED</b> - see {@link V1_21_11Adapter}.</p>
 *
 * <p>Backed by system properties so a server owner can supply the texture
 * without editing a file, and so the values never need to live in the plugin
 * jar. Phase 3 wires this to {@code config.yml}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class SkinConfig {

    private SkinConfig() {
    }

    /** Base64 Mojang texture value, or null when unconfigured. */
    static String textureValue() {
        return System.getProperty("nullarmy.skin.value");
    }

    /** Mojang texture signature, or null when unconfigured. */
    static String textureSignature() {
        return System.getProperty("nullarmy.skin.signature");
    }
}
