package redglitchx.nullarmy.core.agent;

/**
 * One OpenAI-compatible HTTP endpoint.
 *
 * <p>This is the object the user asked for: an endpoint has a <b>model id</b>,
 * a <b>base URL</b>, and an <b>API key</b> — where the API key is supplied as
 * the <em>name of an environment variable</em>, never as a literal in the
 * config file.</p>
 *
 * <p>Spec 7.2: "Never write API keys into commands, AI prompts, chat, debug
 * logs, exception traces, or persisted gameplay data." Holding only the
 * variable name keeps secrets out of the config, the logs and the jar.</p>
 *
 * <p>You may define as many endpoints as you like. Each {@link AgentBinding}
 * points at one primary endpoint plus an optional ordered fallback chain.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class EndpointConfig {

    private final String id;
    private final String baseUrl;
    private final String model;
    private final String authKeyEnv;
    private final long timeoutMillis;
    private final int callsPerMinute;
    private final int maxJsonBytes;
    private final int maxRetries;
    private final boolean enabled;

    private EndpointConfig(Builder b) {
        this.id = b.id;
        this.baseUrl = b.baseUrl;
        this.model = b.model;
        this.authKeyEnv = b.authKeyEnv;
        this.timeoutMillis = b.timeoutMillis;
        this.callsPerMinute = b.callsPerMinute;
        this.maxJsonBytes = b.maxJsonBytes;
        this.maxRetries = b.maxRetries;
        this.enabled = b.enabled;
    }

    public static Builder builder(String id) { return new Builder(id); }

    /** Unique id used by {@code AgentBinding}s to reference this endpoint. */
    public String id() { return id; }

    /** Base URL of an OpenAI-compatible API, e.g. {@code https://api.openai.com/v1}. */
    public String baseUrl() { return baseUrl; }

    /** The model id sent with each request, e.g. {@code gpt-4o-mini} or {@code llama3}. */
    public String model() { return model; }

    /**
     * Name of the environment variable holding the API key.
     *
     * <p>This is a <b>name</b>, never the key itself.</p>
     */
    public String authKeyEnv() { return authKeyEnv; }

    public long timeoutMillis() { return timeoutMillis; }
    public int callsPerMinute() { return callsPerMinute; }

    /** Hard cap on response size, per spec 7.4 (reject oversized responses). */
    public int maxJsonBytes() { return maxJsonBytes; }

    /** Bounded retries with backoff, per spec 7.6. */
    public int maxRetries() { return maxRetries; }

    public boolean enabled() { return enabled; }

    /**
     * Resolves the API key at call time.
     *
     * <p>Returns null when unset; callers must treat that as a disabled
     * endpoint rather than logging the variable name as an error value.</p>
     */
    public String resolveApiKey() {
        if (authKeyEnv == null || authKeyEnv.isEmpty()) {
            return null;
        }
        String value = System.getenv(authKeyEnv);
        return (value == null || value.isEmpty()) ? null : value;
    }

    /** True when the endpoint can be contacted: enabled, key present, URL set. */
    public boolean isUsable() {
        return enabled && baseUrl != null && !baseUrl.isEmpty()
                && model != null && !model.isEmpty()
                && resolveApiKey() != null;
    }

    /**
     * Diagnostics safe for {@code /null status}.
     *
     * <p>Deliberately prints the env-var <em>name</em> and whether it resolved,
     * never the key value.</p>
     */
    public String describe() {
        return id + " [" + model + " @ " + baseUrl + "] keyEnv=" + authKeyEnv
                + " (resolves=" + (resolveApiKey() != null) + ")"
                + " enabled=" + enabled
                + " rpm=" + callsPerMinute
                + " timeout=" + timeoutMillis + "ms";
    }

    public static final class Builder {
        private final String id;
        private String baseUrl = "";
        private String model = "";
        private String authKeyEnv = "";
        private long timeoutMillis = 3000L;
        private int callsPerMinute = 20;
        private int maxJsonBytes = 8192;
        private int maxRetries = 2;
        private boolean enabled = false;

        Builder(String id) {
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalArgumentException("endpoint id must not be empty");
            }
            this.id = id.trim();
        }

        /** e.g. {@code https://api.openai.com/v1} or {@code http://127.0.0.1:11434/v1} */
        public Builder baseUrl(String v) { baseUrl = v == null ? "" : v.trim(); return this; }

        /** The model id, e.g. {@code gpt-4o-mini}, {@code claude-3-5-sonnet}, {@code llama3}. */
        public Builder model(String v) { model = v == null ? "" : v.trim(); return this; }

        /** Name of the env var holding the key, e.g. {@code OPENAI_API_KEY}. */
        public Builder authKeyEnv(String v) { authKeyEnv = v == null ? "" : v.trim(); return this; }

        public Builder timeoutMillis(long v) { timeoutMillis = v; return this; }
        public Builder callsPerMinute(int v) { callsPerMinute = v; return this; }
        public Builder maxJsonBytes(int v) { maxJsonBytes = v; return this; }
        public Builder maxRetries(int v) { maxRetries = v; return this; }
        public Builder enabled(boolean v) { enabled = v; return this; }

        public EndpointConfig build() {
            if (baseUrl.isEmpty()) {
                throw new IllegalStateException("endpoint '" + id + "' needs a base-url");
            }
            if (model.isEmpty()) {
                throw new IllegalStateException("endpoint '" + id + "' needs a model id");
            }
            if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                throw new IllegalStateException(
                        "endpoint '" + id + "' base-url must start with http:// or https://");
            }
            if (timeoutMillis <= 0) {
                throw new IllegalStateException("endpoint '" + id + "' timeout must be > 0");
            }
            if (callsPerMinute <= 0) {
                throw new IllegalStateException("endpoint '" + id + "' calls-per-minute must be > 0");
            }
            if (maxJsonBytes <= 0) {
                throw new IllegalStateException("endpoint '" + id + "' max-json-bytes must be > 0");
            }
            if (maxRetries < 0) {
                throw new IllegalStateException("endpoint '" + id + "' max-retries must be >= 0");
            }
            return new EndpointConfig(this);
        }
    }
}
