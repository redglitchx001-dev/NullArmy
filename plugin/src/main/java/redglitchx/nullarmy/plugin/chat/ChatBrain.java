package redglitchx.nullarmy.plugin.chat;

import org.bukkit.Bukkit;

import redglitchx.nullarmy.core.agent.AgentRole;
import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.json.Json;
import redglitchx.nullarmy.core.util.RateLimiter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.util.Guard;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one place the plugin talks to a language model.
 *
 * <p>It is a thin, defensive wrapper around the endpoint configuration that
 * already exists under {@code ai.endpoints} / {@code ai.agents}: the same keys,
 * the same roles, the same rate limits. No second config format, and no new
 * dependency - the JDK's own {@link HttpClient} and the project's own
 * {@link Json} do the work, exactly like the skin lookup does.</p>
 *
 * <h2>Rules this class exists to keep</h2>
 * <ul>
 *   <li><b>AI is never required.</b> With no endpoint configured,
 *       {@link #available()} is false and every caller gets an honest reason
 *       instead of a hang or an exception. The plugin stays fully usable.</li>
 *   <li><b>Network work is async, everything else is not.</b> The HTTP call
 *       happens off the server thread; the reply is handed back
 *       <b>on the main thread</b> through the Bukkit scheduler.</li>
 *   <li><b>Nothing escapes.</b> Bad JSON, a dead endpoint, a key missing from
 *       the environment, a reply that is 40 KB long - all of it ends in a
 *       {@code [NullArmy]} log line and a short reply, never in a stack trace
 *       inside a chat event.</li>
 * </ul>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class ChatBrain {

    /** One message in the conversation, in the shape every API accepts. */
    public static final class Turn {
        private final String role;
        private final String content;

        public Turn(String role, String content) {
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
        }

        public String role() { return role; }
        public String content() { return content; }
    }

    /** At most one result method is called; callbacks use the server thread while it is available. */
    public interface Reply {
        void ok(String text);
        void failed(String reason);
    }

    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ROLE_SYSTEM = "system";

    /** Hard cap on outgoing prompt size, whatever the config says. */
    private static final int MAX_PROMPT_CHARS = 8_000;

    private final NullArmyPlugin plugin;
    private final HttpClient http;
    private final RateLimiter limiter;
    private final Map<String, EndpointBudget> endpointBudgets = new ConcurrentHashMap<>();

    private static final int MAX_ENDPOINT_RETRIES = 5;

    private static final class EndpointBudget {
        private final int callsPerMinute;
        private final RateLimiter limiter;

        EndpointBudget(int callsPerMinute) {
            this.callsPerMinute = callsPerMinute;
            this.limiter = RateLimiter.perMinute(callsPerMinute);
        }
    }

    /** Guards every completion path and prevents a throwing callback from being retried as a failure. */
    private final class OnceReply implements Reply {
        private final Reply delegate;
        private final AtomicBoolean completed = new AtomicBoolean();

        private OnceReply(Reply delegate) {
            this.delegate = delegate;
        }

        @Override
        public void ok(String text) {
            complete(() -> delegate.ok(text));
        }

        @Override
        public void failed(String reason) {
            complete(() -> delegate.failed(reason));
        }

        private void complete(Runnable callback) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            onMainThread(() -> {
                try {
                    callback.run();
                } catch (Throwable t) {
                    plugin.getLogger().warning("[NullArmy] chat callback failed: " + Guard.describe(t));
                }
            });
        }
    }

    private static final class ResponseTooLargeException extends RuntimeException {
        private final int maxBytes;

        ResponseTooLargeException(int maxBytes) {
            super("response exceeded max-json-bytes (" + maxBytes + ")");
            this.maxBytes = maxBytes;
        }
    }

    public ChatBrain(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Do not follow a redirect with an Authorization header. A
                // provider/gateway should expose its final OpenAI-compatible URL.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        // Conservative default; the per-endpoint limit is applied too.
        this.limiter = RateLimiter.perMinute(20);
    }

    // --------------------------------------------------------------- availability

    /** True when at least one role endpoint has a valid URL and resolved key. */
    public boolean available() {
        return !chain().isEmpty();
    }

    /**
     * Why AI is not available, in words a server owner can act on. Never null.
     */
    public String unavailableReason() {
        PluginConfig config = config();
        if (config == null) {
            return "the plugin config is not loaded";
        }
        if (!config.aiEnabled()) {
            return "ai.enabled is false in config.yml";
        }
        if (config.endpoints().isEmpty()) {
            return config.endpointProblems().isEmpty()
                    ? "no endpoints are defined under ai.endpoints in config.yml"
                    : "no valid endpoint was registered; inspect /null ai endpoints and fix the skipped entries";
        }
        if (chain().isEmpty()) {
            return "no enabled endpoint for the ChatCommander role resolves -"
                    + " check ai.default-endpoint, ai.endpoints and the API key environment variable";
        }
        return "no AI endpoint is usable right now";
    }

    /** Ordered endpoints for the chat role, filtered to the usable ones. */
    private List<EndpointConfig> chain() {
        PluginConfig config = config();
        if (config == null || !config.aiEnabled()) {
            return List.of();
        }
        List<EndpointConfig> out = new ArrayList<>();
        for (EndpointConfig endpoint : config.chainFor(AgentRole.CHAT_COMMANDER)) {
            if (endpoint != null && endpoint.isUsable()) {
                out.add(endpoint);
            }
        }
        return out;
    }

    private PluginConfig config() {
        try {
            return plugin.pluginConfig();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------------- asking

    /**
     * Asks the model what a Null or the Commander should say.
     *
     * <p>Returns immediately. For a non-null callback, exactly one result is
     * delivered on the main thread whenever the server is available. If the
     * plugin has stopped or main-thread scheduling is rejected, the callback is
     * dropped rather than touching Bukkit from the HTTP worker thread.</p>
     *
     * @param persona  the system prompt describing who is speaking
     * @param history  previous turns, oldest first (trimmed by the caller)
     * @param userText what the player just said
     */
    public void ask(String persona, List<Turn> history, String userText, Reply reply) {
        if (reply == null) {
            return;
        }
        OnceReply once = new OnceReply(reply);
        try {
            List<EndpointConfig> chain = chain();
            if (chain.isEmpty()) {
                once.failed(unavailableReason());
                return;
            }
            if (!limiter.tryAcquire()) {
                once.failed("the chat rate limit is reached; try again in "
                        + (limiter.millisUntilNextToken() / 1000L + 1L) + "s");
                return;
            }
            // Try every configured endpoint in role order. A provider error on
            // the first model should not make a healthy custom fallback appear
            // unregistered or leave the player waiting for local fallback.
            tryEndpoint(chain, 0, persona, history, userText, once, new ArrayList<>());
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] chat request failed to start ("
                    + t.getClass().getSimpleName() + ")");
            once.failed("the AI request could not be started");
        }
    }

    private void tryEndpoint(List<EndpointConfig> chain, int index, String persona,
                             List<Turn> history, String userText, Reply reply, List<String> failures) {
        if (index >= chain.size()) {
            String reason = failures.isEmpty() ? "no enabled endpoint could be used"
                    : "all configured endpoints failed (" + String.join("; ", failures) + ")";
            fail(reply, reason);
            return;
        }
        EndpointConfig endpoint = chain.get(index);
        String body = requestBody(endpoint, persona, history, userText);
        if (body == null) {
            failures.add(safeEndpointId(endpoint.id()) + ": request could not be built");
            tryEndpoint(chain, index + 1, persona, history, userText, reply, failures);
            return;
        }
        sendAttempt(chain, index, endpoint, body, 0, persona, history, userText, reply, failures);
    }

    /** Sends one request attempt, applying the endpoint's budget and bounded transient retries. */
    private void sendAttempt(List<EndpointConfig> chain, int index, EndpointConfig endpoint, String body,
                             int retriesUsed, String persona, List<Turn> history, String userText,
                             Reply reply, List<String> failures) {
        try {
            RateLimiter endpointLimiter = endpointBudget(endpoint).limiter;
            if (!endpointLimiter.tryAcquire()) {
                failures.add(safeEndpointId(endpoint.id()) + ": calls-per-minute limit reached");
                fallbackToNext(chain, index, persona, history, userText, reply, failures);
                return;
            }

            HttpRequest request = buildRequest(endpoint, body);
            http.sendAsync(request, limitedBodyHandler(Math.max(1, endpoint.maxJsonBytes())))
                    .whenComplete((response, error) -> handleAttemptResponse(chain, index, endpoint, body,
                            retriesUsed, persona, history, userText, reply, failures, response, error));
        } catch (Throwable t) {
            // Includes malformed custom URLs and local setup failures. Never log
            // the raw URL: it may contain credentials in a query string.
            failures.add(safeEndpointId(endpoint.id()) + ": invalid request configuration");
            fallbackToNext(chain, index, persona, history, userText, reply, failures);
        }
    }

    private void handleAttemptResponse(List<EndpointConfig> chain, int index, EndpointConfig endpoint, String body,
                                       int retriesUsed, String persona, List<Turn> history, String userText,
                                       Reply reply, List<String> failures, HttpResponse<String> response,
                                       Throwable error) {
        try {
            if (error != null) {
                Throwable root = rootCause(error);
                if (root instanceof ResponseTooLargeException) {
                    ResponseTooLargeException tooLarge = (ResponseTooLargeException) root;
                    failures.add(safeEndpointId(endpoint.id()) + ": response exceeded max-json-bytes ("
                            + tooLarge.maxBytes + ")");
                    fallbackToNext(chain, index, persona, history, userText, reply, failures);
                    return;
                }
                if (retriesUsed < retryLimit(endpoint)) {
                    retryLater(() -> sendAttempt(chain, index, endpoint, body, retriesUsed + 1,
                            persona, history, userText, reply, failures), retriesUsed + 1);
                    return;
                }
                failures.add(safeEndpointId(endpoint.id()) + ": request failed ("
                        + root.getClass().getSimpleName() + ")");
                fallbackToNext(chain, index, persona, history, userText, reply, failures);
                return;
            }
            if (response == null) {
                failures.add(safeEndpointId(endpoint.id()) + ": empty HTTP response");
                fallbackToNext(chain, index, persona, history, userText, reply, failures);
                return;
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                if (retryableStatus(status) && retriesUsed < retryLimit(endpoint)) {
                    retryLater(() -> sendAttempt(chain, index, endpoint, body, retriesUsed + 1,
                            persona, history, userText, reply, failures), retriesUsed + 1);
                    return;
                }
                failures.add(safeEndpointId(endpoint.id()) + ": HTTP " + status);
                fallbackToNext(chain, index, persona, history, userText, reply, failures);
                return;
            }
            String text = extract(response.body());
            if (text == null || text.trim().isEmpty()) {
                failures.add(safeEndpointId(endpoint.id()) + ": response did not contain chat text");
                fallbackToNext(chain, index, persona, history, userText, reply, failures);
                return;
            }
            reply.ok(clean(text));
        } catch (Throwable t) {
            plugin.getLogger().fine("[NullArmy] AI response processing failed for "
                    + safeEndpointId(endpoint.id()) + " (" + t.getClass().getSimpleName() + ")");
            failures.add(safeEndpointId(endpoint.id()) + ": response could not be processed");
            fallbackToNext(chain, index, persona, history, userText, reply, failures);
        }
    }

    /** Starts the next fallback without letting an exceptional config entry strand the callback. */
    private void fallbackToNext(List<EndpointConfig> chain, int index, String persona,
                                List<Turn> history, String userText, Reply reply, List<String> failures) {
        try {
            tryEndpoint(chain, index + 1, persona, history, userText, reply, failures);
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] could not continue the AI endpoint fallback chain ("
                    + t.getClass().getSimpleName() + ")");
            fail(reply, "the configured AI endpoint chain could not be started");
        }
    }

    /** Builds the exact JSON POST used by both model requests and connectivity probes. */
    public static HttpRequest buildRequest(EndpointConfig endpoint, String body) {
        if (endpoint == null) {
            throw new IllegalArgumentException("endpoint must not be null");
        }
        return buildRequest(endpoint.chatCompletionsUri(), endpoint.resolveApiKey(),
                endpoint.timeoutMillis(), body);
    }

    /** Shared URL/auth builder so diagnostics probes and chat use identical wire semantics. */
    public static HttpRequest buildRequest(java.net.URI uri, String apiKey, long timeoutMillis, String body) {
        if (uri == null || body == null) {
            throw new IllegalArgumentException("request URI and body must not be null");
        }
        String scheme = uri.getScheme();
        int port = uri.getPort();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null || uri.getHost().isEmpty() || uri.getUserInfo() != null
                || port < -1 || port == 0 || port > 65_535) {
            throw new IllegalArgumentException("request URI must be an absolute credential-free HTTP URL");
        }
        if (body.length() > MAX_PROMPT_CHARS * 4) {
            throw new IllegalArgumentException("request body exceeds the prompt size limit");
        }
        String key = apiKey == null ? "" : apiKey.trim();
        if (!EndpointConfig.isValidApiKeyValue(key)) {
            throw new IllegalArgumentException("API key is too long or contains invalid HTTP header characters");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofMillis(Math.max(1L, Math.min(120_000L, timeoutMillis))))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (!key.isEmpty()) {
            request.header("Authorization", "Bearer " + key);
        }
        return request.build();
    }

    private EndpointBudget endpointBudget(EndpointConfig endpoint) {
        return endpointBudgets.compute(endpoint.id(), (id, current) ->
                current == null || current.callsPerMinute != endpoint.callsPerMinute()
                        ? new EndpointBudget(endpoint.callsPerMinute()) : current);
    }

    private static int retryLimit(EndpointConfig endpoint) {
        return Math.min(MAX_ENDPOINT_RETRIES, Math.max(0, endpoint.maxRetries()));
    }

    /** Bounds and strips control/format codes from config-provided endpoint IDs in output. */
    private static String safeEndpointId(String id) {
        if (id == null || id.isEmpty()) {
            return "endpoint";
        }
        StringBuilder clean = new StringBuilder(Math.min(80, id.length()));
        for (int i = 0; i < id.length() && clean.length() < 80; i++) {
            char c = id.charAt(i);
            clean.append(c >= 0x20 && c != 0x7f && c != '\u00a7' ? c : ' ');
        }
        String label = clean.toString().trim();
        return id.length() > clean.length() ? label + "…" : (label.isEmpty() ? "endpoint" : label);
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static void retryLater(Runnable task, int retryNumber) {
        long delayMillis = Math.min(2000L, 150L << Math.min(4, Math.max(0, retryNumber - 1)));
        CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        int depth = 0;
        while (current.getCause() != null && current.getCause() != current && depth++ < 32) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Reads at most {@code maxBytes} from the provider. A standard {@code ofString}
     * body handler would allocate an oversized response before rejecting it.
     */
    public static HttpResponse.BodyHandler<String> limitedBodyHandler(int maxBytes) {
        final int byteLimit = Math.max(1, maxBytes);
        return responseInfo -> new HttpResponse.BodySubscriber<String>() {
            private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(byteLimit, 8192));
            private final CompletableFuture<String> result = new CompletableFuture<>();
            private Flow.Subscription subscription;

            @Override
            public CompletionStage<String> getBody() {
                return result;
            }

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                if (this.subscription != null) {
                    subscription.cancel();
                    return;
                }
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(List<ByteBuffer> buffers) {
                try {
                    for (ByteBuffer buffer : buffers) {
                        int remaining = buffer.remaining();
                        if (remaining > byteLimit - bytes.size()) {
                            subscription.cancel();
                            result.completeExceptionally(new ResponseTooLargeException(byteLimit));
                            return;
                        }
                        byte[] chunk = new byte[remaining];
                        buffer.get(chunk);
                        bytes.write(chunk, 0, chunk.length);
                    }
                    subscription.request(1);
                } catch (Throwable failure) {
                    subscription.cancel();
                    result.completeExceptionally(failure);
                }
            }

            @Override
            public void onError(Throwable failure) {
                result.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                result.complete(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            }
        };
    }

    /** Reports a failure from whichever thread the HTTP client finished on. */
    private void fail(Reply reply, String reason) {
        plugin.getLogger().warning("[NullArmy] AI chat unavailable: " + reason);
        reply.failed(reason);
    }

    /** Hops to the server thread; never runs a Bukkit callback on an HTTP worker as fallback. */
    private void onMainThread(Runnable task) {
        Runnable guarded = () -> {
            try {
                task.run();
            } catch (Throwable t) {
                plugin.getLogger().warning("[NullArmy] chat callback failed: " + Guard.describe(t));
            }
        };
        boolean mainThread;
        try {
            mainThread = Bukkit.isPrimaryThread();
        } catch (Throwable unavailable) {
            mainThread = false;
        }
        if (mainThread) {
            guarded.run();
            return;
        }
        if (!plugin.isEnabled()) {
            plugin.getLogger().fine("[NullArmy] chat callback was dropped after plugin shutdown.");
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, guarded);
        } catch (Throwable schedulingFailure) {
            plugin.getLogger().fine("[NullArmy] chat callback was dropped because main-thread scheduling failed ("
                    + schedulingFailure.getClass().getSimpleName() + ").");
        }
    }

    // ------------------------------------------------------------------ JSON shapes

    /** Builds the chat-completions request. Returns null when it cannot be built. */
    private String requestBody(EndpointConfig endpoint, String persona,
                              List<Turn> history, String userText) {
        try {
            List<Object> messages = new ArrayList<>();
            messages.add(message(ROLE_SYSTEM, persona == null ? "" : persona));
            if (history != null) {
                for (Turn turn : history) {
                    if (turn != null && !turn.content().isEmpty()) {
                        messages.add(message(turn.role(), turn.content()));
                    }
                }
            }
            messages.add(message(ROLE_USER, userText == null ? "" : userText));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", endpoint.modelId());
            body.put("messages", messages);
            body.put("temperature", 0.8);
            body.put("max_tokens", 220);
            String json = Json.write(body);
            if (json.length() > MAX_PROMPT_CHARS * 4) {
                return null;
            }
            return json;
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] could not build the AI request ("
                    + t.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("role", role);
        map.put("content", clip(content, MAX_PROMPT_CHARS / 4));
        return map;
    }

    /**
     * Pulls the assistant text out of a chat-completions response.
     *
     * <p>Both the OpenAI shape ({@code choices[0].message.content}) and the
     * simpler {@code {"reply": "..."}} shape are accepted, because an owner
     * pointing this at a local model should not have to fake the former.</p>
     */
    public static String extract(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            Object parsed = Json.parse(body);
            Map<String, Object> root = Json.asObject(parsed);
            if (root == null) {
                return null;
            }
            Object reply = root.get("reply");
            if (reply instanceof String) {
                return (String) reply;
            }
            List<Object> choices = Json.asArray(root.get("choices"));
            if (choices == null || choices.isEmpty()) {
                return null;
            }
            Map<String, Object> choice = Json.asObject(choices.get(0));
            if (choice == null) {
                return null;
            }
            Map<String, Object> msg = Json.asObject(choice.get("message"));
            if (msg != null && msg.get("content") instanceof String) {
                return (String) msg.get("content");
            }
            if (choice.get("text") instanceof String) {
                return (String) choice.get("text");
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    // -------------------------------------------------------------------- cleaning

    /**
     * Makes a model reply safe to print in Minecraft chat: one line, no colour
     * codes, no control characters, bounded length.
     */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r", " ").replace("\n", " ").replaceAll("\\s{2,}", " ");
        // Strip legacy formatting so a model cannot inject colour or §k obfuscation.
        text = text.replace('\u00a7', ' ');
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        return out.toString().trim();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

}
