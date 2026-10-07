package redglitchx.nullarmy.core.agent;

import java.net.URI;
import java.util.Locale;
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
        this.endpoint = b.endpoint.trim();
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

    /**
     * Resolves this OpenAI-compatible base URL to its chat-completions route.
     *
     * <p>Configuration accepts either a base such as {@code https://host/v1}
     * or the full {@code /chat/completions} URL. This method handles both,
     * preserves any base-path and query, and never appends the route twice.
     * URL fragments are discarded because they are client-side only.</p>
     */
    public URI chatCompletionsUri() {
        return chatCompletionsUri(endpoint);
    }

    /** Static form used by connectivity checks and dependency-free tests. */
    public static URI chatCompletionsUri(String baseUrl) {
        String raw = baseUrl == null ? "" : baseUrl.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("endpoint URL is empty");
        }
        final URI base;
        try {
            base = URI.create(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("endpoint URL is malformed", invalid);
        }
        String scheme = base.getScheme();
        String host = base.getHost();
        int port = base.getPort();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || host == null || host.isEmpty() || port < -1 || port == 0 || port > 65_535) {
            throw new IllegalArgumentException("endpoint URL must be an absolute http:// or https:// URL");
        }
        String path = base.getRawPath();
        path = path == null ? "" : path;
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            path += "/chat/completions";
        }
        // User-info is accepted for diagnostics/migration compatibility but is
        // deliberately stripped from the request URI. Credentials belong in the
        // api-key field, where they can be sent as an Authorization header.
        String authorityHost = host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]" : host;
        StringBuilder resolved = new StringBuilder(scheme.toLowerCase(Locale.ROOT))
                .append("://").append(authorityHost);
        if (port > 0) {
            resolved.append(':').append(port);
        }
        resolved.append(path);
        if (base.getRawQuery() != null) {
            resolved.append('?').append(base.getRawQuery());
        }
        try {
            return URI.create(resolved.toString());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("could not resolve endpoint chat-completions URL", invalid);
        }
    }

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
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                return null;
            }
            try {
                String value = System.getenv(name);
                if (value == null || value.trim().isEmpty()) {
                    return null;
                }
                value = value.trim();
                return isValidApiKeyValue(value) ? value : null;
            } catch (IllegalArgumentException | SecurityException invalidEnvironmentAccess) {
                return null;
            }
        }
        return apiKeyRaw;
    }

    /**
     * Checks the size and character set accepted by JDK HTTP header values.
     * This is also applied to keys resolved from the process environment.
     */
    public static boolean isValidApiKeyValue(String value) {
        if (value == null) {
            return true;
        }
        if (value.length() > 8192) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || c > 0xff) {
                return false;
            }
        }
        return true;
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
     * Redacts user-info, every path segment, query values and fragments before a
     * URL is shown to an operator. Custom deployments sometimes put credentials
     * in any of those locations, so diagnostics intentionally show only the
     * HTTP origin and whether a path/query was present.
     */
    public static String safeEndpointForDisplay(String raw) {
        if (raw != null && raw.regionMatches(true, 0, "id:", 0, 3)) {
            return "id:" + safeLabel(raw.substring(3));
        }
        try {
            URI uri = URI.create(raw == null ? "" : raw.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || host == null || host.isEmpty() || port < -1 || port == 0 || port > 65_535) {
                return "(invalid URL)";
            }
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            StringBuilder out = new StringBuilder(scheme.toLowerCase(Locale.ROOT))
                    .append("://").append(host);
            if (port > 0) {
                out.append(':').append(port);
            }
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                out.append(" [path redacted]");
            }
            if (uri.getRawQuery() != null) {
                out.append(" [query redacted]");
            }
            return out.toString();
        } catch (IllegalArgumentException invalid) {
            return "(invalid URL)";
        }
    }

    private static String safeLabel(String raw) {
        String text = raw == null ? "" : raw.trim();
        StringBuilder clean = new StringBuilder(Math.min(80, text.length()));
        for (int i = 0; i < text.length() && clean.length() < 80; i++) {
            char c = text.charAt(i);
            clean.append(c >= 0x20 && c != 0x7f && c != '\u00a7' ? c : ' ');
        }
        String label = clean.toString().trim();
        return text.length() > clean.length() ? label + "…" : label;
    }

    /** Diagnostic line. Never contains the API key or any URL path/query values. */
    public String describe() {
        String keyPart;
        if (apiKeyRaw == null || apiKeyRaw.isEmpty()) {
            keyPart = "no-key";
        } else if (usesEnvVar()) {
            keyPart = ENV_PREFIX + safeLabel(apiKeyEnvName()) + " resolves=" + (resolveApiKey() != null);
        } else {
            keyPart = "inline(hidden)";
        }
        return safeLabel(id) + " [" + safeLabel(modelId) + "] "
                + safeEndpointForDisplay(endpoint) + " " + keyPart;
    }

    /** Returns a fixed redaction marker for any non-empty literal key. */
    public static String mask(String key) {
        return "*****";
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
            try {
                chatCompletionsUri(url);
            } catch (IllegalArgumentException invalidUrl) {
                throw new IllegalArgumentException("endpoint must be an absolute http:// or https:// URL on endpoint '"
                        + safeLabel(id) + "'", invalidUrl);
            }
            if (apiKeyRaw != null && !apiKeyRaw.isEmpty() && !apiKeyRaw.startsWith(ENV_PREFIX)
                    && !isValidApiKeyValue(apiKeyRaw)) {
                throw new IllegalArgumentException("api-key is too long or contains invalid HTTP header characters"
                        + " on endpoint '" + safeLabel(id) + "'");
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
