package redglitchx.nullarmy.plugin.chat;

import org.bukkit.Bukkit;

import redglitchx.nullarmy.core.agent.AgentRole;
import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.json.Json;
import redglitchx.nullarmy.core.util.RateLimiter;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.util.Guard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Result callback. Exactly one of the two methods is called, once. */
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

    public ChatBrain(NullArmyPlugin plugin) {
        this.plugin = plugin;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        // Conservative default; the per-endpoint limit is applied too.
        this.limiter = RateLimiter.perMinute(20);
    }

    // --------------------------------------------------------------- availability

    /** True when a model can actually be reached with the current config. */
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
            return "no endpoints are defined under ai.endpoints in config.yml";
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
     * <p>Returns immediately. {@code reply} is always called exactly once, on
     * the main thread, whatever happens.</p>
     *
     * @param persona  the system prompt describing who is speaking
     * @param history  previous turns, oldest first (trimmed by the caller)
     * @param userText what the player just said
     */
    public void ask(String persona, List<Turn> history, String userText, Reply reply) {
        if (reply == null) {
            return;
        }
        try {
            List<EndpointConfig> chain = chain();
            if (chain.isEmpty()) {
                reply.failed(unavailableReason());
                return;
            }
            if (!limiter.tryAcquire()) {
                reply.failed("the chat rate limit is reached; try again in "
                        + (limiter.millisUntilNextToken() / 1000L + 1L) + "s");
                return;
            }
            EndpointConfig endpoint = chain.get(0);
            String body = requestBody(endpoint, persona, history, userText);
            if (body == null) {
                reply.failed("the request could not be built");
                return;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint.baseUrl()))
                    .timeout(Duration.ofMillis(Math.max(1000L, endpoint.timeoutMillis())))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + endpoint.resolveApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            fail(reply, "the endpoint could not be reached: " + brief(error));
                            return;
                        }
                        if (response == null) {
                            fail(reply, "the endpoint returned nothing");
                            return;
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            fail(reply, "the endpoint answered HTTP " + response.statusCode()
                                    + ": " + clip(response.body(), 160));
                            return;
                        }
                        String text = extract(response.body());
                        if (text == null || text.trim().isEmpty()) {
                            fail(reply, "the model returned no text");
                            return;
                        }
                        String clean = clean(text);
                        onMainThread(() -> {
                            try {
                                reply.ok(clean);
                            } catch (Throwable t) {
                                plugin.getLogger().warning("[NullArmy] chat reply handler failed: "
                                        + Guard.describe(t));
                            }
                        });
                    });
        } catch (Throwable t) {
            // A malformed endpoint URL lands here, for example.
            plugin.getLogger().warning("[NullArmy] chat request failed to start: " + Guard.describe(t));
            reply.failed(Guard.describe(t));
        }
    }

    /** Reports a failure from whichever thread the HTTP client finished on. */
    private void fail(Reply reply, String reason) {
        plugin.getLogger().warning("[NullArmy] AI chat unavailable: " + reason);
        onMainThread(() -> {
            try {
                reply.failed(reason);
            } catch (Throwable t) {
                plugin.getLogger().warning("[NullArmy] chat failure handler failed: "
                        + Guard.describe(t));
            }
        });
    }

    /** Hops to the server thread; falls back to the caller when the server is gone. */
    private void onMainThread(Runnable task) {
        try {
            if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
                return;
            }
        } catch (Throwable ignored) {
            // Shutting down: run inline below.
        }
        try {
            task.run();
        } catch (Throwable t) {
            plugin.getLogger().warning("[NullArmy] chat callback failed: " + Guard.describe(t));
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
            plugin.getLogger().warning("[NullArmy] could not build the AI request: " + Guard.describe(t));
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

    private static String brief(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return clip(message, 120);
    }
}
