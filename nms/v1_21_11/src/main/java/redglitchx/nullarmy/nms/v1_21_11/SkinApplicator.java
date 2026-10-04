package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

/**
 * Applies the Null skin to a {@link GameProfile}.
 *
 * <p><b>STATUS: UNVERIFIED</b> - see {@link V1_21_11Adapter}.</p>
 *
 * <p>Spec 3 asks for a pure black player skin. That is only achievable with a
 * <b>real Mojang-hosted texture plus its signature</b>; a plugin cannot invent
 * one (IMPLEMENTATION_PLAN.md A-05). If no texture is configured we leave the
 * profile alone rather than faking it - spec 1.5: "Do not promise a pure black
 * skin if the target client/profile mechanism cannot render it; report the
 * exact setup required."</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class SkinApplicator {

    private SkinApplicator() {
    }

    /**
     * Attaches the configured skin, if any.
     *
     * <p>The value/signature pair comes from configuration. Both are required:
     * a value without a valid signature is rejected by the client.</p>
     */
    static void apply(GameProfile profile) {
        apply(profile, "", "");
    }

    /**
     * Attaches the requested skin, falling back to the configured one.
     *
     * <p>Both halves are required. A value without a signature is rejected by
     * the client, so we would only be pretending to have set a skin.</p>
     */
    static void apply(GameProfile profile, String requestedValue, String requestedSignature) {
        String value = requestedValue;
        String signature = requestedSignature;

        if (value == null || value.isEmpty() || signature == null || signature.isEmpty()) {
            value = SkinConfig.textureValue();
            signature = SkinConfig.textureSignature();
        }
        if (value == null || value.isEmpty() || signature == null || signature.isEmpty()) {
            // No skin available. Keep the default rather than pretending.
            return;
        }
        // authlib exposes GameProfile as record-style accessors: properties()
        // (not getProperties()) in the version Paper 1.21.11 ships.
        profile.properties().put("textures", new Property("textures", value, signature));
    }
}
