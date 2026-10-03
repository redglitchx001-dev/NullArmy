package redglitchx.nullarmy.core.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Binds one {@link AgentRole} to one primary endpoint plus an optional ordered
 * fallback chain.
 *
 * <p>If the primary endpoint is missing, unreachable, rate-limited or its
 * circuit breaker is open, NullArmy walks the fallback chain and finally falls
 * back to deterministic local logic. Spec 7: "A missing or unreachable endpoint
 * must never stall the server or stop basic Null behaviour."</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class AgentBinding {

    /** Below this confidence a recommendation is discarded rather than executed. */
    private static final double DEFAULT_MIN_CONFIDENCE = 0.0;

    private final AgentRole role;
    private final String primaryEndpointId;
    private final List<String> fallbackEndpointIds;
    private final boolean enabled;
    private final double minConfidence;
    private final long recommendationExpiryMillis;

    private AgentBinding(Builder b) {
        this.role = b.role;
        this.primaryEndpointId = b.primaryEndpointId;
        this.fallbackEndpointIds = Collections.unmodifiableList(new ArrayList<>(b.fallbacks));
        this.enabled = b.enabled;
        this.minConfidence = b.minConfidence;
        this.recommendationExpiryMillis = b.recommendationExpiryMillis;
    }

    public static Builder builder(AgentRole role) { return new Builder(role); }

    public AgentRole role() { return role; }
    public String primaryEndpointId() { return primaryEndpointId; }
    public List<String> fallbackEndpointIds() { return fallbackEndpointIds; }
    public boolean enabled() { return enabled; }
    public double minConfidence() { return minConfidence; }

    /** Spec 7.5: every recommendation expires so a stale reply is never executed. */
    public long recommendationExpiryMillis() { return recommendationExpiryMillis; }

    /** Primary first, then fallbacks in order. */
    public List<String> endpointChain() {
        List<String> chain = new ArrayList<>();
        if (primaryEndpointId != null && !primaryEndpointId.isEmpty()) {
            chain.add(primaryEndpointId);
        }
        for (String id : fallbackEndpointIds) {
            if (!chain.contains(id)) {
                chain.add(id);
            }
        }
        return chain;
    }

    public static final class Builder {
        private final AgentRole role;
        private String primaryEndpointId = "";
        private final List<String> fallbacks = new ArrayList<>();
        private boolean enabled = false;
        private double minConfidence = DEFAULT_MIN_CONFIDENCE;
        private long recommendationExpiryMillis = 5000L;

        Builder(AgentRole role) {
            if (role == null) {
                throw new IllegalArgumentException("role must not be null");
            }
            this.role = role;
        }

        public Builder primaryEndpointId(String v) {
            primaryEndpointId = v == null ? "" : v.trim();
            return this;
        }

        public Builder addFallback(String v) {
            if (v != null && !v.trim().isEmpty()) {
                fallbacks.add(v.trim());
            }
            return this;
        }

        public Builder enabled(boolean v) { enabled = v; return this; }

        public Builder minConfidence(double v) {
            if (v < 0.0 || v > 1.0) {
                throw new IllegalArgumentException("min-confidence must be within 0..1");
            }
            minConfidence = v;
            return this;
        }

        public Builder recommendationExpiryMillis(long v) {
            if (v <= 0) {
                throw new IllegalArgumentException("expiry must be > 0");
            }
            recommendationExpiryMillis = v;
            return this;
        }

        public AgentBinding build() {
            return new AgentBinding(this);
        }
    }
}
