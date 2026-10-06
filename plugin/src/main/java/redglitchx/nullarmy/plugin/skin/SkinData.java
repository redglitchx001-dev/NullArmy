package redglitchx.nullarmy.plugin.skin;

import java.util.Objects;

/**
 * A resolved Minecraft skin: the base64 texture blob Mojang returns plus the
 * signature that proves it came from Mojang.
 *
 * <p>Both are required. A value without a valid signature is rejected by the
 * client and the NPC renders with a default skin (IMPLEMENTATION_PLAN.md A-05).</p>
 *
 * <p><b>STATUS: UNVERIFIED</b> - never compiled (BUILD.md blocker B-1).</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class SkinData {

    /** Where the skin came from, for diagnostics. */
    public enum Source {
        /** Shipped inside the plugin jar under skins/. */
        BUNDLED,
        /** Cached on disk under plugins/NullArmy/skins/. */
        DISK_CACHE,
        /** Fetched live from Mojang this session. */
        NETWORK,
        /** In-memory only (already resolved earlier this session). */
        MEMORY,
        /** Pasted into config.yml as skins.value + skins.signature. */
        CONFIG,
        /** Returned by skins.proxy-url. */
        PROXY,
        /** Signed by MineSkin from the local skins/null.png image. */
        MINESKIN
    }

    private final String value;
    private final String signature;
    private final Source source;

    public SkinData(String value, String signature, Source source) {
        this.value = value;
        this.signature = signature;
        this.source = source == null ? Source.MEMORY : source;
    }

    public String value() { return value; }
    public String signature() { return signature; }
    public Source source() { return source; }

    /** True only when both halves are present - a value alone is useless. */
    public boolean complete() {
        return value != null && !value.isEmpty()
                && signature != null && !signature.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SkinData)) return false;
        SkinData other = (SkinData) o;
        return Objects.equals(value, other.value) && Objects.equals(signature, other.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, signature);
    }

    @Override
    public String toString() {
        return "SkinData{source=" + source + ", valueLength="
                + (value == null ? 0 : value.length()) + "}";
    }
}
