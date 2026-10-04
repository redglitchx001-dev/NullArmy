package redglitchx.nullarmy.core.agent;

import java.util.Objects;

/**
 * One AI model endpoint.
 *
 * <p>An endpoint is exactly three things you care about:</p>
 * <pre>
 *   my-model:
 *     endpoint: https://api.openai.com/v1   # base URL, http or https
 *     model-id: gpt-4o-mini                 # the model id sent with each request
 *     api-key:  env:OPENAI_API_KEY          # the key, or env:NAME to read it from the environment
 * </pre>
 *
 * <p>You may define <b>as many endpoints as you want</b>. There is no limit and
 * no fixed list - copy the block, give it a new name, change the three fields.</p>
 *
 * <h2>The API key</h2>
 * <p>{@code api-key} accepts either form:</p>
 * <ul>
 *   <li><b>{@code env:OPENAI_API_KEY}</b> - recommended. Only the <i>name</i> of an
 *       environment variable is stored. The real key is read from the server
 *       process environment at call time, so it never sits in config.yml, in the
 *       jar, in git, or in a backup. This is the form the owner selected.</li>
 *   <li><b>{@code sk-...}</b> - the literal key. Works, but it is plain text on
 *       disk: it can leak through a config paste, a screenshot, or a git commit.
 *       The plugin logs a warning when it sees one.</li>
 * </ul>
 *
 * <p>Either way {@link #describe()} prints only the env-var name or a masked
 * literal - never the key value itself.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class EndpointConfig {

    /** Prefix that means "the rest is an environment variable name". */
    public static final String ENV_PREFIX = "env:";

    private final String id;
    private final String endpoint;
    private final String modelId;
    private final String apiKeyRaw;
    private final long timeoutMillis;
    private final int callsPerMinute;
    private final int maxJsonBytes;
    private final int maxRetries;
    private final boolean enabled;

    private EndpointConfig(Builder b) {
        this.id = b.id;
        this.endpoint = b.endpoint;
        this.modelId = b.modelId;
        this.apiKeyRaw = b.apiKeyRaw;
        this.timeoutMillis = b.timeoutMillis;
        this.callsPerMinute = b.callsPerMinute;
        this.maxJsonBytes = b.maxJsonBytes;
        this.maxRetries = b.maxRetries;
        this.enabled = b.enabled;
    }

    public static Builder builder(String id, String endpoint, String modelId) {
        return new Builder(id, endpoint, modelId);
    }

    /** Config key of this endpoint (the map key in {@code ai.endpoints}). */
    public String id() { return id; }

    /** Base URL, always starting with {@code http://} or {@code https://}. */
    public String endpoint() { return endpoint; }

    /** Alias for {@link #endpoint()}. */
    public String baseUrl() { return endpoint; }

    /** The model id sent with each request. */
    public String modelId() { return modelId; }

    /** Alias for {@link #modelId()}. */
    public String model() { return modelId; }

    public long timeoutMillis() { return timeoutMillis; }
    public int callsPerMinute() { return callsPerMinute; }
    public int maxJsonBytes() { return maxJsonBytes; }
    public int maxRetries() { return maxRetries; }
    public boolean enabled() { return enabled; }

    /**
     * True when this endpoint wants its key read from the environment
     * (the {@code api-key} value starts with {@code env:}).
     */
    public boolean usesEnvVar() {
        return apiKeyRaw != null && apiKeyRaw.startsWith(ENV_PREFIX);
    }

    /**
     * The environment variable name to read, or {@code null} when the key is a
     * literal (or absent).
     */
    public String apiKeyEnvName() {
        return usesEnvVar() ? apiKeyRaw.substring(ENV_PREFIX.length()).trim() : null;
    }

    /**
     * True when the key was written inline in config.yml rather than as
     * {@code env:NAME}. Used to warn the owner at startup.
     */
    public boolean hasInlineKey() {
        return apiKeyRaw != null && !apiKeyRaw.isEmpty() && !usesEnvVar();
    }

    /**
     * Resolves the API key: reads the environment variable when configured as
     * {@code env:NAME}, otherwise returns the literal value.
     *
     * <p>Returns {@code null} when there is no key at all - legitimate for
     * local endpoints such as Ollama that need no authentication.</p>
     */
    public String resolveApiKey() {
        if (apiKeyRaw == null || apiKeyRaw.isEmpty()) {
            return null;
        }
        if (usesEnvVar()) {
            String name = apiKeyEnvName();
            if (name.isEmpty()) {
                return null;
            }
            String value = System.getenv(name);
            return (value == null || value.isEmpty()) ? null : value;
        }
        return apiKeyRaw;
    }

    /** True when this endpoint is enabled and its key (if any) actually resolves. */
    public boolean isUsable() {
        if (!enabled) {
            return false;
        }
        if (apiKeyRaw == null || apiKeyRaw.isEmpty()) {
            return true; // no auth configured (e.g. local Ollama)
        }
        return resolveApiKey() != null;
    }

    /**
     * Diagnostic line. <b>Never contains the key value.</b>
     *
     * <p>Example: {@code openai [gpt-4o-mini] https://api.openai.com/v1 env:OPENAI_API_KEY resolves=true}</p>
     */
    public String describe() {
        String keyPart;
        if (apiKeyRaw == null || apiKeyRaw.isEmpty()) {
            keyPart = "no-key";
        } else if (usesEnvVar()) {
            keyPart = ENV_PREFIX + apiKeyEnvName() + " resolves=" + (resolveApiKey() != null);
        } else {
            keyPart = "inline(" + mask(apiKeyRaw) + ")";
        }
        return id + " [" + modelId + "] " + endpoint + " " + keyPart;
    }

    /**
     * Shows only the first 3 and last 2 characters of a literal key.
     *
     * <p>Public so {@code CoreTestSuite} can assert the masking shape directly;
     * it never exposes more of the key than {@link #describe()} does.</p>
     */
    public static String mask(String key) {
        if (key == null || key.length() <= 5) {
            return "*****";
        }
        return key.substring(0, 3) + "..." + key.substring(key.length() - 2);
    }

    @Override
    public String toString() {
        return "EndpointConfig{" + describe() + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointConfig)) return false;
        EndpointConfig other = (EndpointConfig) o;
        return timeoutMillis == other.timeoutMillis
                && callsPerMinute == other.callsPerMinute
                && maxJsonBytes == other.maxJsonBytes
                && maxRetries == other.maxRetries
                && enabled == other.enabled
                && Objects.equals(id, other.id)
                && Objects.equals(endpoint, other.endpoint)
                && Objects.equals(modelId, other.modelId)
                && Objects.equals(apiKeyRaw, other.apiKeyRaw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, endpoint, modelId, apiKeyRaw, timeoutMillis,
                callsPerMinute, maxJsonBytes, maxRetries, enabled);
    }

    /** Fluent builder. Validates on {@link #build()}. */
    public static final class Builder {
        private final String id;
        private final String endpoint;
        private final String modelId;
        private String apiKeyRaw = "";
        private long timeoutMillis = 3000L;
        private int callsPerMinute = 20;
        private int maxJsonBytes = 8192;
        private int maxRetries = 2;
        private boolean enabled = false;

        private Builder(String id, String endpoint, String modelId) {
            this.id = id;
            this.endpoint = endpoint;
            this.modelId = modelId;
        }

        /**
         * Sets the API key field exactly as written in config.yml: either
         * {@code env:NAME} or a literal key. {@code null} means no auth.
         */
        public Builder withApiKey(String raw) {
            this.apiKeyRaw = raw == null ? "" : raw.trim();
            return this;
        }

        /** Convenience: force the {@code env:NAME} form. */
        public Builder withApiKeyEnvName(String envName) {
            this.apiKeyRaw = (envName == null || envName.isEmpty())
                    ? "" : ENV_PREFIX + envName.trim();
            return this;
        }

        /*
         * Property setters follow the naming used by the other builders in
         * core (AgentBinding, Objective, CombatSituation) and by every call
         * site: the plain property name. The "with" prefix is kept only for
         * the two api-key helpers, which are real conversions rather than
         * plain field assignments.
         */
        public Builder timeoutMillis(long v) { this.timeoutMillis = v; return this; }
        public Builder callsPerMinute(int v) { this.callsPerMinute = v; return this; }
        public Builder maxJsonBytes(int v) { this.maxJsonBytes = v; return this; }
        public Builder maxRetries(int v) { this.maxRetries = v; return this; }
        public Builder enabled(boolean v) { this.enabled = v; return this; }

        public EndpointConfig build() {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("endpoint id must not be empty");
            }
            if (modelId == null || modelId.trim().isEmpty()) {
                throw new IllegalArgumentException("model-id must not be empty on endpoint '" + id + "'");
            }
            if (endpoint == null || endpoint.trim().isEmpty()) {
                throw new IllegalArgumentException("endpoint must not be empty on endpoint '" + id + "'");
            }
            String url = endpoint.trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "endpoint must start with http:// or https:// on endpoint '" + id + "': " + url);
            }
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("timeout-millis must be > 0 on endpoint '" + id + "'");
            }
            if (callsPerMinute <= 0) {
                throw new IllegalArgumentException("calls-per-minute must be > 0 on endpoint '" + id + "'");
            }
            if (maxJsonBytes <= 0) {
                throw new IllegalArgumentException("max-json-bytes must be > 0 on endpoint '" + id + "'");
            }
            if (maxRetries < 0) {
                throw new IllegalArgumentException("max-retries must be >= 0 on endpoint '" + id + "'");
            }
            return new EndpointConfig(this);
        }
    }
}
