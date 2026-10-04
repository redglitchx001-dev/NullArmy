package redglitchx.nullarmy.nms.v1_21_11;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import io.papermc.paper.profile.MutablePropertyMap;

import java.util.Collection;
import java.util.UUID;

/**
 * Puts a signed skin texture on a Null's {@link GameProfile}.
 *
 * <h2>The bug this class used to cause</h2>
 * <p>In the authlib that Paper 1.21.11 ships, {@code GameProfile} is a record and
 * the {@code PropertyMap} created by {@code new GameProfile(id, name)} is
 * <b>immutable</b>. The old code called {@code profile.properties().put(...)} on
 * it, which throws {@code UnsupportedOperationException} the moment a skin is
 * actually known. On a live server the skin cache is warmed at enable, so every
 * summon after that threw inside the adapter, the plugin read it as a broken NMS
 * path and latched summoning off for the whole session - while CI, which never
 * had a skin cached at summon time, stayed green.</p>
 *
 * <p>Every profile is now created with Paper's own {@link MutablePropertyMap}
 * (the same thing {@code CraftPlayerProfile} does), so the texture can be set at
 * spawn and replaced live by {@code /null clearskins} or {@code /null reload}.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
final class SkinApplicator {

    static final String TEXTURES = "textures";

    private SkinApplicator() {
    }

    /** A fresh profile whose property map can be written to. */
    static GameProfile mutableProfile(UUID id, String name) {
        return new GameProfile(id, name, new MutablePropertyMap());
    }

    /** Attaches the configured skin, if any. */
    static boolean apply(GameProfile profile) {
        return apply(profile, "", "");
    }

    /**
     * Attaches the requested skin, falling back to the system-property skin.
     *
     * <p>Both halves are required: a value without a signature is rejected by
     * the client, so the plugin would only be pretending to have set a skin.</p>
     *
     * @return true when the profile now carries a texture
     */
    static boolean apply(GameProfile profile, String requestedValue, String requestedSignature) {
        String value = requestedValue;
        String signature = requestedSignature;
        if (isBlank(value) || isBlank(signature)) {
            value = SkinConfig.textureValue();
            signature = SkinConfig.textureSignature();
        }
        if (isBlank(value) || isBlank(signature)) {
            // No skin available. Keep the default rather than pretending.
            return false;
        }
        return replace(profile, value, signature);
    }

    /**
     * Replaces the texture property. Blank value or signature clears it.
     *
     * @return true when the profile ends up carrying exactly the requested
     *     texture (or none, when clearing)
     */
    static boolean replace(GameProfile profile, String value, String signature) {
        if (profile == null) {
            return false;
        }
        try {
            profile.properties().removeAll(TEXTURES);
            if (!isBlank(value) && !isBlank(signature)) {
                profile.properties().put(TEXTURES, new Property(TEXTURES, value, signature));
            }
        } catch (UnsupportedOperationException immutable) {
            // A profile that was not created through mutableProfile(). Report it
            // honestly instead of throwing into a spawn.
            return false;
        }
        String[] now = read(profile);
        if (isBlank(value) || isBlank(signature)) {
            return now == null;
        }
        return now != null && value.equals(now[0]) && signature.equals(now[1]);
    }

    /** {value, signature} of the profile's texture, or null when it has none. */
    static String[] read(GameProfile profile) {
        if (profile == null) {
            return null;
        }
        try {
            Collection<Property> textures = profile.properties().get(TEXTURES);
            if (textures == null) {
                return null;
            }
            for (Property property : textures) {
                if (property != null) {
                    return new String[] {property.value(), property.signature() == null ? "" : property.signature()};
                }
            }
        } catch (Throwable ignored) {
            // Unreadable means "no texture" for every caller.
        }
        return null;
    }

    private static boolean isBlank(String text) {
        return text == null || text.isEmpty();
    }
}
