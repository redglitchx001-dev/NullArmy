package redglitchx.nullarmy.core.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates and resolves the endpoint/agent configuration.
 *
 * <p>Pure logic, no I/O, so it is fully unit-testable without a server. It
 * answers the operational question "is this configuration safe to load?"
 * before a single packet is sent.</p>
 *
 * <p>Validation is intentionally strict: a misconfigured agent is reported
 * clearly and downgraded to local behaviour rather than silently doing
 * something surprising.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class AgentRegistry {

    /** Severity of a validation finding. */
    public enum Severity { ERROR, WARNING }

    /** One validation finding. */
    public static final class Finding {
        private final Severity severity;
        private final String message;

        Finding(Severity severity, String message) {
            this.severity = severity;
            this.message = message;
        }

        public Severity severity() { return severity; }
        public String message() { return message; }

        @Override
        public String toString() { return severity + ": " + message; }
    }

    /** Result of validating a configuration. */
    public static final class Result {
        private final boolean loadable;
        private final List<Finding> findings;

        Result(boolean loadable, List<Finding> findings) {
            this.loadable = loadable;
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
        }

        /** False when an ERROR finding exists - the config must not be used as-is. */
        public boolean loadable() { return loadable; }

        public List<Finding> findings() { return findings; }

        public boolean hasErrors() { return loadable == false; }

        public List<Finding> errors() {
            List<Finding> out = new ArrayList<>();
            for (Finding f : findings) {
                if (f.severity == Severity.ERROR) {
                    out.add(f);
                }
            }
            return out;
        }
    }

    private AgentRegistry() {
    }

    /**
     * Validates endpoints and bindings.
     *
     * @param endpoints the defined endpoints, keyed by id
     * @param bindings  the defined agent bindings
     */
    public static Result validate(Map<String, EndpointConfig> endpoints,
                                  Collection<AgentBinding> bindings) {
        List<Finding> findings = new ArrayList<>();

        if (endpoints == null) {
            endpoints = Collections.emptyMap();
        }
        if (bindings == null) {
            bindings = Collections.emptyList();
        }

        // Duplicate endpoint ids.
        if (endpoints.size() != distinctCount(endpoints.keySet())) {
            findings.add(new Finding(Severity.ERROR, "duplicate endpoint ids in configuration"));
        }

        // Duplicate role bindings.
        List<AgentRole> seenRoles = new ArrayList<>();
        for (AgentBinding b : bindings) {
            if (seenRoles.contains(b.role())) {
                findings.add(new Finding(Severity.ERROR,
                        "agent '" + b.role().configKey() + "' is bound more than once"));
            } else {
                seenRoles.add(b.role());
            }
        }

        for (AgentBinding b : bindings) {
            String key = b.role().configKey();

            // Endpoint references must resolve.
            for (String id : b.endpointChain()) {
                if (!endpoints.containsKey(id)) {
                    findings.add(new Finding(Severity.ERROR,
                            "agent '" + key + "' references unknown endpoint '" + id + "'"));
                }
            }

            // An enabled agent pointing at a disabled endpoint still works, but
            // the operator should know it will run on local fallback only.
            if (b.enabled()) {
                boolean anyUsable = false;
                for (String id : b.endpointChain()) {
                    EndpointConfig ep = endpoints.get(id);
                    if (ep != null && ep.enabled()) {
                        anyUsable = true;
                        break;
                    }
                }
                if (!b.endpointChain().isEmpty() && !anyUsable) {
                    findings.add(new Finding(Severity.WARNING,
                            "agent '" + key + "' is enabled but all its endpoints are disabled"
                                    + " - it will use local fallback only"));
                }
                if (b.endpointChain().isEmpty()) {
                    findings.add(new Finding(Severity.WARNING,
                            "agent '" + key + "' has no endpoint - local fallback only"));
                }
            }
        }

        // Hard invariant: no role may hold moderation authority. This is
        // enforced by the enum itself; this assertion keeps it true forever.
        for (AgentRole role : AgentRole.values()) {
            String never = role.mayNever().toLowerCase();
            if (never.contains("ban players") == false && role == AgentRole.CHAT_COMMANDER) {
                findings.add(new Finding(Severity.ERROR,
                        "ChatCommander lost its ban restriction - spec 7 violation"));
            }
        }

        boolean loadable = true;
        for (Finding f : findings) {
            if (f.severity == Severity.ERROR) {
                loadable = false;
                break;
            }
        }
        return new Result(loadable, findings);
    }

    /**
     * Resolves the ordered endpoint chain for a role, skipping disabled ones.
     *
     * @return endpoints to try, in order; empty means "use local fallback"
     */
    public static List<EndpointConfig> resolveChain(AgentBinding binding,
                                                    Map<String, EndpointConfig> endpoints) {
        List<EndpointConfig> out = new ArrayList<>();
        if (binding == null || !binding.enabled()) {
            return out;
        }
        for (String id : binding.endpointChain()) {
            EndpointConfig ep = endpoints.get(id);
            if (ep != null && ep.enabled()) {
                out.add(ep);
            }
        }
        return out;
    }

    /** Convenience: builds the default all-disabled binding set for every role. */
    public static Map<AgentRole, AgentBinding> defaultBindings() {
        Map<AgentRole, AgentBinding> out = new LinkedHashMap<>();
        for (AgentRole role : AgentRole.values()) {
            out.put(role, AgentBinding.builder(role).enabled(false).build());
        }
        return out;
    }

    private static int distinctCount(Collection<String> values) {
        List<String> copy = new ArrayList<>();
        for (String v : values) {
            if (!copy.contains(v)) {
                copy.add(v);
            }
        }
        return copy.size();
    }
}
