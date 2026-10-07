package redglitchx.nullarmy.plugin.ai.builder;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import redglitchx.nullarmy.core.construct.BuildStep;
import redglitchx.nullarmy.core.construct.FallbackPlanner;
import redglitchx.nullarmy.core.json.Json;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.nms.NullBody;
import redglitchx.nullarmy.plugin.NullArmyPlugin;
import redglitchx.nullarmy.plugin.body.Bodies;
import redglitchx.nullarmy.plugin.body.Mind;
import redglitchx.nullarmy.plugin.body.NullBrain;
import redglitchx.nullarmy.plugin.config.PluginConfig;
import redglitchx.nullarmy.plugin.config.Reloadable;
import redglitchx.nullarmy.plugin.config.V3Settings;
import redglitchx.nullarmy.plugin.util.Guard;
import redglitchx.nullarmy.plugin.zone.ZoneService;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A deterministic local builder: a goal becomes blocks placed by hand without AI.
 *
 * <h2>Planning</h2>
 * <p>{@link FallbackPlanner} creates a repeatable local plan (hut, bridge, wall,
 * tower, platform, throne, or gather) from the goal, zone, and available
 * inventory. AI endpoints are never asked to produce construction plans.</p>
 *
 * <h2>Building</h2>
 * <p>Every step is physical. A Null walks within reach, looks at the block,
 * swings, and: breaks it over the tool-correct break time (with the crack
 * animation, through {@code Player#breakBlock}, so drops and protection plugins
 * behave as for a player); places one block per swing from its own inventory
 * through a real {@link BlockPlaceEvent}, never faster than
 * {@code ai.builder.place-rate-ticks}; picks up the drops by walking over them;
 * chops a tree and makes planks when it runs out of wood. There is no paste and
 * no bulk block setting anywhere.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class BuilderService implements Reloadable {

    /** One build in progress. */
    public static final class Job {
        final UUID owner;
        final String world;
        final ZoneService.Record zone;
        final String goal;
        final List<BuildStep> steps;
        final Map<UUID, Deque<BuildStep>> queues = new LinkedHashMap<>();
        final Map<UUID, Exec> exec = new HashMap<>();
        final List<Long> placeTicks = new ArrayList<>();
        final String source;
        final long startedTick;
        long lastPlaceTick = Long.MIN_VALUE / 2;
        int placed;
        int broken;
        int skipped;
        int logsGathered;
        boolean active = true;
        String note = "";
        final List<String> skips = new ArrayList<>();

        Job(UUID owner, String world, ZoneService.Record zone, String goal, List<BuildStep> steps, String source,
            long startedTick) {
            this.owner = owner;
            this.world = world;
            this.zone = zone;
            this.goal = goal;
            this.steps = steps;
            this.source = source;
            this.startedTick = startedTick;
        }

        public UUID owner() { return owner; }
        public String goal() { return goal; }
        public List<BuildStep> steps() { return steps; }
        public String source() { return source; }
        public int placed() { return placed; }
        public int broken() { return broken; }
        public int skipped() { return skipped; }
        public boolean active() { return active; }
        public List<Long> placeTicks() { return new ArrayList<>(placeTicks); }
        public List<String> skips() { return new ArrayList<>(skips); }
        public ZoneService.Record zone() { return zone; }

        public String describe() {
            int left = 0;
            for (Deque<BuildStep> q : queues.values()) {
                left += q.size();
            }
            return "\"" + goal + "\" (" + source + "): " + placed + " placed, " + broken + " broken, "
                    + skipped + " skipped, " + left + " steps left" + (active ? "" : " - finished");
        }
    }

    /** Per-body execution state for the current step. */
    static final class Exec {
        BuildStep step;
        long stepStart;
        float breakProgress;
        long lastSwing;
        long waitUntil = -1L;
    }

    private static final double REACH = 4.2D;
    private static final int MAX_LOGS_PER_JOB = 24;

    private final NullArmyPlugin plugin;
    private final HttpClient http;
    private final Map<UUID, Job> jobsByOwner = new HashMap<>();
    private final Map<UUID, Job> jobByBody = new HashMap<>();
    private V3Settings v3;
    private Job lastJob;
    private volatile String lastAiStatus = "no request yet";

    public BuilderService(NullArmyPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.v3 = config == null ? null : config.v3();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void onConfigReloaded(PluginConfig fresh) {
        if (fresh != null) {
            this.v3 = fresh.v3();
        }
    }

    public Job lastJob() { return lastJob; }
    public Job jobOf(UUID owner) { return owner == null ? null : jobsByOwner.get(owner); }
    public String lastAiStatus() { return lastAiStatus; }

    // ------------------------------------------------------------------ start

    /**
     * Starts a build for {@code owner}'s Nulls.
     *
     * @param report receives the plan's outcome (on the main thread) - the issuer's answer
     * @return the immediate answer
     */
    public String start(UUID owner, String world, Vec3d standAt, float yaw, String goal, Consumer<String> report) {
        return start(owner, world, standAt, yaw, goal, report, null, null, null);
    }

    /**
     * P-07: resolves {@code ai.builder.endpoint}.
     *
     * <p>Two spellings are accepted:</p>
     * <ul>
     *   <li>a plain base URL - any OpenAI-compatible server
     *       ({@code http://localhost:1234/v1}, {@code https://openrouter.ai/api/v1}, ...);</li>
     *   <li><code>id:&lt;name&gt;</code> - a named endpoint from the
     *       {@code ai.endpoints} map, which also supplies its model and key.</li>
     * </ul>
     *
     * @return the resolved triple (endpoint, model, key); the key is used only
     *     for the outbound request and is never included in diagnostics
     */
    public Endpoint resolveEndpoint() {
        String configured = v3 == null ? "" : v3.builderEndpoint();
        if (!configured.isEmpty()) {
            return resolve(configured);
        }

        // The connectivity command should test the same effective service that
        // ChatCommander will use when no builder-specific override is present.
        // Construction itself remains fully local and never reads this target.
        redglitchx.nullarmy.plugin.config.PluginConfig current = plugin.pluginConfig();
        List<redglitchx.nullarmy.core.agent.EndpointConfig> chain = current == null
                ? List.of() : current.chainFor(redglitchx.nullarmy.core.agent.AgentRole.CHAT_COMMANDER);
        redglitchx.nullarmy.core.agent.EndpointConfig firstConfigured = null;
        for (redglitchx.nullarmy.core.agent.EndpointConfig endpoint : chain) {
            if (firstConfigured == null) {
                firstConfigured = endpoint;
            }
            if (endpoint.isUsable()) {
                return resolve("id:" + endpoint.id());
            }
        }
        if (firstConfigured != null) {
            return resolve("id:" + firstConfigured.id());
        }
        long timeout = v3 == null ? 20000L : v3.builderTimeoutMs();
        int maxBytes = current == null ? 8192 : current.caps().maxAiJsonBytes();
        return new Endpoint("", "", "", "", maxBytes, timeout,
                "no enabled endpoint is configured for the ChatCommander role");
    }

    /** Resolves one configured endpoint string. Never throws, never returns null. */
    public Endpoint resolve(String configured) {
        String spec = configured == null ? "" : configured.trim();
        long defaultTimeout = v3 == null ? 20000L : v3.builderTimeoutMs();
        if (spec.isEmpty()) {
            return new Endpoint("", v3 == null ? "" : v3.builderModel(),
                    "", "", 8192, defaultTimeout, "");
        }
        if (spec.regionMatches(true, 0, "id:", 0, 3)) {
            String id = spec.substring(3).trim();
            redglitchx.nullarmy.plugin.config.PluginConfig current = plugin.pluginConfig();
            redglitchx.nullarmy.core.agent.EndpointConfig ep =
                    current == null ? null : current.endpoints().get(id);
            if (ep == null) {
                String skipped = current == null ? null : current.endpointProblems().get(id);
                String problem = skipped == null ? "unknown endpoint id '" + safeLabel(id) + "'"
                        : "endpoint entry was skipped: " + skipped;
                return new Endpoint("", "", "", id, 8192, defaultTimeout, problem);
            }
            if (!ep.enabled()) {
                return new Endpoint("", ep.modelId(), "", ep.id(), ep.maxJsonBytes(), ep.timeoutMillis(),
                        "endpoint is disabled");
            }
            String key = ep.resolveApiKey();
            if (ep.usesEnvVar() && key == null) {
                String name = ep.apiKeyEnvName();
                return new Endpoint("", ep.modelId(), "", ep.id(), ep.maxJsonBytes(), ep.timeoutMillis(),
                        "API-key environment variable '" + safeLabel(name) + "' is missing or invalid");
            }
            return new Endpoint(ep.baseUrl(), ep.modelId(), key, ep.id(), ep.maxJsonBytes(), ep.timeoutMillis(), "");
        }
        redglitchx.nullarmy.plugin.config.PluginConfig current = plugin.pluginConfig();
        int maxBytes = current == null ? 8192 : current.caps().maxAiJsonBytes();
        String model = v3 == null ? "" : v3.builderModel();
        String key = v3 == null ? "" : v3.builderApiKey();
        try {
            redglitchx.nullarmy.core.agent.EndpointConfig.chatCompletionsUri(spec);
        } catch (IllegalArgumentException invalidUrl) {
            return new Endpoint("", model, "", "", maxBytes, defaultTimeout,
                    "invalid HTTP URL; use an absolute http:// or https:// endpoint");
        }
        String keyProblem = v3 == null ? "" : v3.builderApiKeyProblem();
        if (!keyProblem.isEmpty()) {
            return new Endpoint("", model, "", "", maxBytes, defaultTimeout, keyProblem);
        }
        return new Endpoint(spec, model, key, "", maxBytes, defaultTimeout, "");
    }

    /** A resolved endpoint: where to POST, which model, which key, and where it came from. */
    public static final class Endpoint {
        private final String url;
        private final String model;
        private final String key;
        private final String id;
        private final int maxJsonBytes;
        private final long timeoutMillis;
        private final String problem;

        Endpoint(String url, String model, String key, String id, int maxJsonBytes,
                 long timeoutMillis, String problem) {
            this.url = url == null ? "" : url;
            this.model = model == null ? "" : model;
            this.key = key == null ? "" : key;
            this.id = id == null ? "" : id;
            this.maxJsonBytes = Math.max(256, Math.min(10_000_000, maxJsonBytes));
            this.timeoutMillis = Math.max(1L, Math.min(120_000L, timeoutMillis));
            this.problem = problem == null ? "" : problem;
        }

        public String url() { return url; }
        public String model() { return model; }
        public String key() { return key; }
        public String id() { return id; }
        public int maxJsonBytes() { return maxJsonBytes; }
        public long timeoutMillis() { return timeoutMillis; }
        public String problem() { return problem; }
        public boolean usable() { return !url.isEmpty() && problem.isEmpty(); }
    }

    /**
     * P-07: {@code /null ai test [id]} - one real HTTP call, and the honest
     * answer printed: the HTTP status and the configured model. Response bodies
     * are never inspected for diagnostic text, so a provider cannot echo a key.
     */
    public void testEndpoint(String id, Consumer<String> report) {
        if (report == null) {
            return;
        }
        Endpoint endpoint = id == null || id.isEmpty() ? resolveEndpoint() : resolve("id:" + id);
        if (!endpoint.usable()) {
            String problem = endpoint.problem();
            if (!problem.isEmpty()) {
                reportEndpointTest(report, "Endpoint '" + (endpoint.id().isEmpty() ? "custom" : safeLabel(endpoint.id()))
                        + "' cannot be tested: " + problem + ".");
            } else {
                reportEndpointTest(report, id == null || id.isEmpty()
                        ? "No endpoint is configured: set ai.builder.endpoint to a base URL or id:<name>."
                        : "No endpoint with the id '" + safeLabel(id) + "' is configured.");
            }
            return;
        }
        final java.net.URI requestUri;
        try {
            requestUri = redglitchx.nullarmy.core.agent.EndpointConfig.chatCompletionsUri(endpoint.url());
        } catch (IllegalArgumentException invalid) {
            reportEndpointTest(report, "endpoint '" + (endpoint.id().isEmpty() ? "custom" : safeLabel(endpoint.id()))
                    + "' has an invalid HTTP URL; check ai.endpoints in config.yml");
            return;
        }
        String url = safeEndpoint(endpoint.url());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "Reply with the single word: ready");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model().isEmpty() ? "default" : endpoint.model());
        body.put("max_tokens", 8);
        body.put("messages", List.of(message));
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest request = redglitchx.nullarmy.plugin.chat.ChatBrain.buildRequest(requestUri,
                    endpoint.key(), endpoint.timeoutMillis(), Json.write(body));
            int responseLimit = endpoint.maxJsonBytes();
            http.sendAsync(request,
                    redglitchx.nullarmy.plugin.chat.ChatBrain.limitedBodyHandler(responseLimit))
                    .whenComplete((response, error) -> {
                        long millis = System.currentTimeMillis() - startedAt;
                        String line;
                        if (error != null) {
                            Throwable root = error;
                            int depth = 0;
                            while (root.getCause() != null && root.getCause() != root && depth++ < 32) {
                                root = root.getCause();
                            }
                            line = root.getClass().getSimpleName().equals("ResponseTooLargeException")
                                    ? "endpoint " + url + " -> response exceeded max-json-bytes ("
                                            + responseLimit + ") in " + millis + "ms"
                                    : "endpoint " + url + " -> request failed ("
                                            + root.getClass().getSimpleName() + ") in " + millis + "ms";
                        } else if (response == null) {
                            line = "endpoint " + url + " -> no HTTP response";
                        } else {
                            // Response bodies are untrusted and may echo request headers or
                            // credentials. Diagnostics only show the configured model.
                            String model = safeModel(endpoint.model());
                            line = "endpoint " + url + " -> HTTP " + response.statusCode()
                                    + ", configured model " + (model.isEmpty() ? "(default)" : model)
                                    + " in " + millis + "ms";
                        }
                        reportEndpointTest(report, line);
                    });
        } catch (Throwable t) {
            reportEndpointTest(report, "endpoint " + (endpoint.id().isEmpty() ? "custom" : safeLabel(endpoint.id()))
                    + " -> the request could not be sent (" + t.getClass().getSimpleName() + ")");
        }
    }

    private void reportEndpointTest(Consumer<String> report, String line) {
        Runnable task = () -> Guard.attempt(plugin.getLogger(),
                "reporting an endpoint test", () -> report.accept(line));
        boolean mainThread;
        try {
            mainThread = Bukkit.isPrimaryThread();
        } catch (Throwable unavailable) {
            mainThread = false;
        }
        if (mainThread) {
            task.run();
            return;
        }
        if (!plugin.isEnabled()) {
            plugin.getLogger().fine("[NullArmy] endpoint-test result was dropped after plugin shutdown.");
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, task);
        } catch (Throwable schedulingFailure) {
            // The callback may send a Bukkit message; never run it inline on the HTTP thread.
            plugin.getLogger().fine("[NullArmy] endpoint-test result was dropped because main-thread"
                    + " scheduling failed (" + schedulingFailure.getClass().getSimpleName() + ").");
        }
    }

    private static String safeModel(String raw) {
        return safeLabel(raw);
    }

    /** Bounds and strips control/format characters from user-controlled labels in chat output. */
    private static String safeLabel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(Math.min(80, raw.length()));
        for (int i = 0; i < raw.length() && clean.length() < 80; i++) {
            char c = raw.charAt(i);
            clean.append(c >= 0x20 && c != 0x7f && c != '\u00a7' ? c : ' ');
        }
        String label = clean.toString().trim();
        return raw.length() > clean.length() ? label + "…" : label;
    }

    /** {@code /null ai endpoints}: every configured endpoint, registration state and role chain. */
    public String describeEndpoints() {
        StringBuilder out = new StringBuilder();
        out.append("build planner: deterministic local (AI is never asked to build)\n");
        out.append("AI connectivity test: ");
        String configured = v3 == null ? "" : v3.builderEndpoint();
        String safeConfigured = configured.isEmpty() ? "(ChatCommander effective chain; local construction stays offline)"
                : configured.regionMatches(true, 0, "id:", 0, 3)
                        ? "id:" + safeLabel(configured.substring(3).trim()) : safeEndpoint(configured);
        out.append(safeConfigured);
        Endpoint resolved = resolveEndpoint();
        out.append("\n  effective test target: ")
                .append(resolved.usable() ? safeEndpoint(resolved.url()) : "(nothing to test)");
        if (!resolved.id().isEmpty()) {
            out.append(" id:").append(safeLabel(resolved.id()));
        }
        out.append(" model ").append(resolved.model().isEmpty() ? "(default)" : safeModel(resolved.model()))
                .append(" auth=").append(resolved.key().isEmpty() ? "none" : "configured");
        if (!resolved.problem().isEmpty()) {
            out.append(" [").append(resolved.problem()).append(']');
        }

        redglitchx.nullarmy.plugin.config.PluginConfig current = plugin.pluginConfig();
        if (current == null) {
            out.append("\n  AI config: not loaded");
            return out.toString();
        }
        out.append("\n  ai.enabled: ").append(current.aiEnabled());
        Map<String, redglitchx.nullarmy.core.agent.EndpointConfig> known = current.endpoints();
        if (known.isEmpty() && current.endpointProblems().isEmpty()) {
            out.append("\n  ai.endpoints: none configured");
        } else {
            out.append("\n  registered ai.endpoints: ").append(known.size());
            for (redglitchx.nullarmy.core.agent.EndpointConfig ep : known.values()) {
                String keyState;
                if (ep.usesEnvVar()) {
                    keyState = "key env:" + safeLabel(ep.apiKeyEnvName())
                            + (ep.resolveApiKey() == null ? " missing" : " resolved");
                } else if (ep.hasInlineKey()) {
                    keyState = "inline key set";
                } else {
                    keyState = "no key";
                }
                out.append("\n    ").append(safeLabel(ep.id())).append(" -> ").append(safeEndpoint(ep.baseUrl()))
                        .append(" model=").append(safeModel(ep.modelId()))
                        .append(" enabled=").append(ep.enabled())
                        .append(" usable=").append(ep.isUsable())
                        .append(" (").append(keyState).append(')');
            }
            for (Map.Entry<String, String> problem : current.endpointProblems().entrySet()) {
                out.append("\n    ").append(safeLabel(problem.getKey())).append(" -> NOT REGISTERED: ")
                        .append(problem.getValue());
            }
        }

        List<redglitchx.nullarmy.core.agent.EndpointConfig> chatChain =
                current.chainFor(redglitchx.nullarmy.core.agent.AgentRole.CHAT_COMMANDER);
        out.append("\n  ChatCommander chain: ");
        if (!current.aiEnabled()) {
            out.append("off (ai.enabled is false)");
        } else if (chatChain.isEmpty()) {
            out.append("none (enable an endpoint and bind it under ai.agents.ChatCommander,"
                    + " or set ai.default-endpoint)");
        } else {
            boolean first = true;
            for (redglitchx.nullarmy.core.agent.EndpointConfig ep : chatChain) {
                if (!ep.isUsable()) {
                    continue;
                }
                if (!first) {
                    out.append(" -> ");
                }
                out.append(safeLabel(ep.id()));
                first = false;
            }
            if (first) {
                out.append("none (API key did not resolve)");
            }
        }
        if (plugin.chat() != null && plugin.chat().brain() != null
                && !plugin.chat().brain().available()) {
            out.append("\n  chat status: ").append(plugin.chat().brain().unavailableReason());
        }
        return out.toString();
    }

    /** Hides user-info, all path segments, query values and fragments in diagnostics. */
    private static String safeEndpoint(String raw) {
        return redglitchx.nullarmy.core.agent.EndpointConfig.safeEndpointForDisplay(raw);
    }

    /**
     * Starts a deterministic local build. The endpoint parameters are retained
     * for source compatibility with older callers, but are intentionally never
     * used to create or modify a build plan.
     */
    public String start(UUID owner, String world, Vec3d standAt, float yaw, String goal, Consumer<String> report,
                        String endpointOverride, String modelOverride, String keyOverride) {
        if (v3 != null && !v3.builderEnabled()) {
            return "the builder is switched off (ai.builder.enabled: false)";
        }
        List<NullBody> bodies = liveBodies(owner);
        if (bodies.isEmpty()) {
            return "you have no Nulls to build with - summon some first";
        }
        if (goal == null || goal.trim().isEmpty()) {
            return "say what to build, e.g. /null ai build a small hut";
        }
        stop(owner, "replaced by a new build");
        ZoneService.Record zone = plugin.zones() == null ? null : plugin.zones().existing(owner);
        if (zone == null || !zone.world().equals(world) || !zone.zone().contains(standAt.x(), standAt.z())) {
            zone = plugin.zones().open(owner, world, standAt);
        }
        int facing = facingOf(yaw);
        int[] anchor = relative(zone, standAt);
        Map<String, Integer> stock = stock(bodies);
        lastAiStatus = "build plan not sent to AI; using deterministic local planner";
        Job job = fallback(owner, world, zone, goal, anchor, facing, stock, "deterministic local planner");
        if (job == null) {
            return "the deterministic local planner does not know how to build \"" + goal
                    + "\" (it knows hut, bridge, wall, tower, platform, throne)";
        }
        return "building " + job.describe() + " with deterministic local logic; no AI request was made";
    }

    private Job fallback(UUID owner, String world, ZoneService.Record zone, String goal, int[] anchor, int facing,
                         Map<String, Integer> stock, String why) {
        FallbackPlanner.Plan plan = FallbackPlanner.plan(goal, anchor[0], anchor[1], anchor[2], facing, stock);
        if (plan == null) {
            return null;
        }
        Job job = launch(owner, world, zone, goal, plan.steps(), "offline planner (" + why + ")");
        if (plan.shortfall() > 0) {
            job.note = plan.shortfall() + " blocks short - the Nulls will chop wood for planks";
        }
        return job;
    }

    private Job launch(UUID owner, String world, ZoneService.Record zone, String goal, List<BuildStep> steps,
                       String source) {
        Job job = new Job(owner, world, zone, goal, new ArrayList<>(steps), source, plugin.currentTick());
        List<NullBody> bodies = liveBodies(owner);
        assign(job, bodies);
        jobsByOwner.put(owner, job);
        for (UUID id : job.queues.keySet()) {
            jobByBody.put(id, job);
        }
        lastJob = job;
        if (plugin.chatGate() != null) {
            plugin.chatGate().event("build.started", "goal", goal, "owner", ownerName(owner),
                    "plan", steps.size() + " steps by " + source);
        }
        return job;
    }

    /**
     * Steps go to the Null they name; unnamed steps are split into segments at
     * every MOVE and handed out round-robin, so one Null walks to a spot and
     * places the blocks that belong to it.
     */
    private void assign(Job job, List<NullBody> bodies) {
        Map<String, NullBody> byName = new HashMap<>();
        for (NullBody body : bodies) {
            byName.put(body.profileName().toLowerCase(Locale.ROOT), body);
            job.queues.put(body.uuid(), new ArrayDeque<>());
        }
        int next = 0;
        NullBody current = bodies.get(0);
        for (BuildStep step : job.steps) {
            NullBody target;
            if (!step.nullName().isEmpty() && byName.containsKey(step.nullName().toLowerCase(Locale.ROOT))) {
                target = byName.get(step.nullName().toLowerCase(Locale.ROOT));
            } else {
                if (step.action() == BuildStep.Action.MOVE) {
                    current = bodies.get(next++ % bodies.size());
                }
                target = current;
            }
            job.queues.get(target.uuid()).addLast(step);
        }
        job.queues.values().removeIf(Deque::isEmpty);
    }

    // ------------------------------------------------------------------- stop

    public String stop(UUID owner, String why) {
        Job job = owner == null ? null : jobsByOwner.remove(owner);
        if (job == null) {
            return "no build is running";
        }
        finish(job, why == null ? "stopped" : why);
        return "build stopped: " + job.describe();
    }

    public void stopAll(String why) {
        for (UUID owner : new ArrayList<>(jobsByOwner.keySet())) {
            stop(owner, why);
        }
    }

    private void finish(Job job, String why) {
        job.active = false;
        jobByBody.values().removeIf(j -> j == job);
        jobsByOwner.values().removeIf(j -> j == job);
        if (plugin.chatGate() != null) {
            if (why == null) {
                plugin.chatGate().event("build.finished", "goal", job.goal, "placed", job.placed, "broken", job.broken);
            } else {
                plugin.chatGate().event("build.stopped", "goal", job.goal, "reason", why);
            }
        }
    }

    /** The body is no longer part of its build (ordered elsewhere, died). */
    public void release(NullBody body) {
        if (body == null || body.uuid() == null) {
            return;
        }
        Job job = jobByBody.remove(body.uuid());
        if (job != null) {
            Deque<BuildStep> left = job.queues.remove(body.uuid());
            if (left != null && !left.isEmpty()) {
                // Hand the rest to a teammate rather than dropping it.
                for (Deque<BuildStep> other : job.queues.values()) {
                    other.addAll(left);
                    break;
                }
            }
        }
    }

    public boolean drives(NullBody body) {
        Job job = body == null || body.uuid() == null ? null : jobByBody.get(body.uuid());
        return job != null && job.active;
    }

    /** Housekeeping: finish jobs whose every queue is empty. */
    public void tick(long now) {
        for (Job job : new ArrayList<>(jobsByOwner.values())) {
            boolean empty = true;
            for (Deque<BuildStep> q : job.queues.values()) {
                if (!q.isEmpty()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                jobsByOwner.remove(job.owner);
                finish(job, null);
            } else if (now - job.startedTick > 20L * 60L * 20L) {
                stop(job.owner, "took longer than 20 minutes");
            }
        }
    }

    // ---------------------------------------------------------------- execute

    /** One tick of one Null's current step. */
    public NullBrain.Intent tickBody(NullBody body, Mind mind, Player handle, String world, Vec3d pos, long now) {
        Job job = jobByBody.get(body.uuid());
        NullBrain.Intent intent = NullBrain.Intent.stop();
        if (job == null || handle == null) {
            return intent;
        }
        Deque<BuildStep> queue = job.queues.get(body.uuid());
        if (queue == null || queue.isEmpty()) {
            jobByBody.remove(body.uuid());
            return intent;
        }
        Exec exec = job.exec.computeIfAbsent(body.uuid(), k -> new Exec());
        BuildStep step = queue.peekFirst();
        if (exec.step != step) {
            exec.step = step;
            exec.stepStart = now;
            exec.breakProgress = 0.0F;
            exec.waitUntil = -1L;
        }
        if (now - exec.stepStart > 20L * 45L) {
            skip(job, queue, step, "took longer than 45 seconds");
            return intent;
        }
        World w = Bukkit.getWorld(job.world);
        if (w == null || !job.world.equals(world)) {
            skip(job, queue, step, "the Null is not in the build's world");
            return intent;
        }
        int bx = job.zone.originX() + step.x();
        int by = job.zone.originY() + step.y();
        int bz = job.zone.originZ() + step.z();
        Vec3d centre = new Vec3d(bx + 0.5D, by, bz + 0.5D);
        switch (step.action()) {
            case MOVE: {
                NullBrain.Intent walk = steer(body, mind, world, pos, centre, 0.7D);
                if (walk == null) {
                    queue.pollFirst();
                    return intent;
                }
                return walk;
            }
            case WAIT: {
                if (exec.waitUntil < 0) {
                    exec.waitUntil = now + step.ticks();
                }
                if (now >= exec.waitUntil) {
                    queue.pollFirst();
                }
                return intent;
            }
            case PICKUP: {
                NullBrain.Intent walk = steer(body, mind, world, pos, centre, 0.5D);
                if (walk != null) {
                    return walk;
                }
                boolean itemsLeft = false;
                for (Entity near : w.getNearbyEntities(new Location(w, centre.x(), centre.y(), centre.z()), 1.5, 1.5, 1.5)) {
                    if (near instanceof Item && !near.isDead()) {
                        itemsLeft = true;
                        break;
                    }
                }
                if (!itemsLeft || now - exec.stepStart > 40) {
                    queue.pollFirst();
                    craftPlanksIfNeeded(handle, queue);
                }
                return intent;
            }
            case BREAK:
                return breakStep(job, queue, exec, body, mind, handle, w, world, pos, bx, by, bz, now);
            case PLACE:
            default:
                return placeStep(job, queue, exec, body, mind, handle, w, world, pos, bx, by, bz, step, now);
        }
    }

    private NullBrain.Intent steer(NullBody body, Mind mind, String world, Vec3d pos, Vec3d target, double arrive) {
        double dist = Math.hypot(target.x() - pos.x(), target.z() - pos.z());
        if (dist <= arrive) {
            return null;
        }
        NullBrain.Intent intent = new NullBrain.Intent();
        intent.dx = (target.x() - pos.x()) / dist * Math.min(1.0D, Math.max(0.3D, dist / 1.2D));
        intent.dz = (target.z() - pos.z()) / dist * Math.min(1.0D, Math.max(0.3D, dist / 1.2D));
        intent.gait = dist > 6.0D ? NullBody.GAIT_RUN : NullBody.GAIT_WALK;
        if (target.y() > pos.y() + 0.6D && dist < 2.0D && body.onGround()) {
            intent.jump = true;
        }
        if (body.horizontalCollision() && body.onGround()) {
            intent.jump = true;
        }
        intent.sneak = target.y() < pos.y() - 0.5D;
        return intent;
    }

    private NullBrain.Intent approach(NullBody body, Mind mind, String world, Vec3d pos, int bx, int by, int bz) {
        Vec3d eye = new Vec3d(pos.x(), pos.y() + 1.62D, pos.z());
        Vec3d centre = new Vec3d(bx + 0.5D, by + 0.5D, bz + 0.5D);
        if (eye.distanceTo(centre) <= REACH) {
            return null;
        }
        NullBrain.Intent walk = steer(body, mind, world, pos, new Vec3d(bx + 0.5D, by, bz + 0.5D), 2.5D);
        if (walk == null) {
            walk = NullBrain.Intent.stop();
        }
        walk.look = centre;
        return walk;
    }

    private NullBrain.Intent placeStep(Job job, Deque<BuildStep> queue, Exec exec, NullBody body, Mind mind,
                                       Player handle, World w, String world, Vec3d pos, int bx, int by, int bz,
                                       BuildStep step, long now) {
        Block block = w.getBlockAt(bx, by, bz);
        Material material = Material.matchMaterial(step.block());
        if (material == null || !material.isBlock()) {
            skip(job, queue, step, step.block() + " is not a block");
            return NullBrain.Intent.stop();
        }
        if (block.getType() == material) {
            queue.pollFirst();
            return NullBrain.Intent.stop();
        }
        if (!block.getType().isAir() && !block.isReplaceable()) {
            skip(job, queue, step, "the spot is taken by " + block.getType().name().toLowerCase(Locale.ROOT));
            return NullBrain.Intent.stop();
        }
        PlayerInventory inv = handle.getInventory();
        int slot = Bodies.find(inv, material);
        if (slot < 0 && material.name().endsWith("_PLANKS")) {
            slot = anyPlanks(inv);
            if (slot >= 0) {
                material = inv.getItem(slot).getType();
            } else if (gatherWood(job, queue, body, handle, w, pos)) {
                return NullBrain.Intent.stop();
            }
        }
        if (slot < 0) {
            skip(job, queue, step, "no " + material.name().toLowerCase(Locale.ROOT) + " left in the inventory");
            return NullBrain.Intent.stop();
        }
        NullBrain.Intent walk = approach(body, mind, world, pos, bx, by, bz);
        if (walk != null) {
            return walk;
        }
        NullBrain.Intent intent = NullBrain.Intent.stop();
        intent.look = new Vec3d(bx + 0.5D, by + 0.5D, bz + 0.5D);
        intent.sneak = pos.y() > by + 0.5D; // at an edge, sneak like a player bridging
        int rate = v3 == null ? 10 : v3.builderPlaceRateTicks();
        if (now - job.lastPlaceTick < rate || now - exec.stepStart < 2) {
            return intent;
        }
        for (Entity occupant : w.getNearbyEntities(org.bukkit.util.BoundingBox.of(block).expand(-0.01D))) {
            if (!(occupant instanceof Item) && !occupant.isDead()) {
                return intent; // somebody stands there: wait, never place a block into a body
            }
        }
        Block against = null;
        for (BlockFace face : new BlockFace[] {BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
                BlockFace.WEST, BlockFace.UP}) {
            Block neighbour = block.getRelative(face);
            if (neighbour.getType().isSolid()) {
                against = neighbour;
                break;
            }
        }
        if (against == null) {
            skip(job, queue, step, "nothing to place it against");
            return intent;
        }
        Bodies.hold(handle, slot);
        ItemStack inHand = inv.getItemInMainHand();
        BlockState replaced = block.getState();
        handle.swingMainHand();
        BlockPlaceEvent event = new BlockPlaceEvent(block, replaced, against, inHand, handle, true, EquipmentSlot.HAND);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !event.canBuild()) {
            skip(job, queue, step, "the server refused the placement (protection)");
            return intent;
        }
        block.setType(material, true);
        try {
            w.playSound(block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData().getSoundGroup().getPlaceSound(),
                    1.0F, 0.8F);
        } catch (Throwable ignored) {
            w.playSound(block.getLocation().add(0.5, 0.5, 0.5), Sound.BLOCK_STONE_PLACE, 1.0F, 0.8F);
        }
        if (inHand.getAmount() <= 1) {
            inv.setItemInMainHand(null);
        } else {
            inHand.setAmount(inHand.getAmount() - 1);
        }
        job.placed++;
        job.lastPlaceTick = now;
        job.placeTicks.add(now);
        queue.pollFirst();
        return intent;
    }

    private NullBrain.Intent breakStep(Job job, Deque<BuildStep> queue, Exec exec, NullBody body, Mind mind,
                                       Player handle, World w, String world, Vec3d pos, int bx, int by, int bz, long now) {
        Block block = w.getBlockAt(bx, by, bz);
        if (block.getType().isAir()) {
            queue.pollFirst();
            return NullBrain.Intent.stop();
        }
        String deny = denyBreak(block);
        if (deny != null) {
            skip(job, queue, exec.step, deny);
            return NullBrain.Intent.stop();
        }
        NullBrain.Intent walk = approach(body, mind, world, pos, bx, by, bz);
        if (walk != null) {
            return walk;
        }
        holdBestTool(handle, block);
        NullBrain.Intent intent = NullBrain.Intent.stop();
        intent.look = new Vec3d(bx + 0.5D, by + 0.5D, bz + 0.5D);
        float speed = 0.0F;
        try {
            speed = block.getBreakSpeed(handle);
        } catch (Throwable ignored) {
            // fall through: treated as unbreakable
        }
        if (speed <= 0.0F) {
            if (now - exec.stepStart > 40) {
                skip(job, queue, exec.step, block.getType().name().toLowerCase(Locale.ROOT) + " cannot be broken");
            }
            return intent;
        }
        exec.breakProgress += speed;
        if (now - exec.lastSwing >= 4) {
            exec.lastSwing = now;
            handle.swingMainHand();
            try {
                w.playSound(block.getLocation().add(0.5, 0.5, 0.5), block.getBlockData().getSoundGroup().getHitSound(),
                        0.5F, 0.6F);
            } catch (Throwable ignored) {
                // cosmetic
            }
        }
        float shown = Math.min(1.0F, exec.breakProgress);
        for (Player viewer : w.getPlayers()) {
            if (viewer.getLocation().distanceSquared(block.getLocation()) < 32 * 32
                    && (plugin.adapter() == null || !plugin.adapter().isNullEntity(viewer.getUniqueId()))) {
                viewer.sendBlockDamage(block.getLocation(), exec.breakProgress >= 1.0F ? 0.0F : shown, handle);
            }
        }
        if (exec.breakProgress >= 1.0F) {
            boolean broke = handle.breakBlock(block);
            if (broke) {
                job.broken++;
                if (block.getType().isAir() && exec.step != null) {
                    queue.pollFirst();
                }
            } else {
                skip(job, queue, exec.step, "the server refused the break (protection)");
            }
        }
        return intent;
    }

    /** Blocks a builder never breaks, whatever the plan says. */
    private String denyBreak(Block block) {
        Material type = block.getType();
        if (type == Material.BEDROCK || type == Material.BARRIER || type == Material.END_PORTAL_FRAME) {
            return type.name().toLowerCase(Locale.ROOT) + " is never broken";
        }
        try {
            if (block.getState() instanceof InventoryHolder) {
                return "containers are never broken by the builder";
            }
        } catch (Throwable ignored) {
            // treat as allowed
        }
        if (plugin.portals() != null) {
            for (redglitchx.nullarmy.plugin.portal.PortalBuilder.BuiltPortal portal : plugin.portals().standing()) {
                if (portal.owns(block.getLocation())) {
                    return "that block belongs to an arrival doorway";
                }
            }
        }
        return null;
    }

    private void holdBestTool(Player handle, Block block) {
        PlayerInventory inv = handle.getInventory();
        ItemStack held = inv.getItemInMainHand();
        if (held != null && !held.getType().isAir() && block.isPreferredTool(held)) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && !stack.getType().isAir() && block.isPreferredTool(stack)
                    && stack.getType().name().matches(".*_(PICKAXE|AXE|SHOVEL|HOE)$")) {
                Bodies.hold(handle, i);
                return;
            }
        }
    }

    private static int anyPlanks(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack != null && stack.getType().name().endsWith("_PLANKS")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * No wood left: find the nearest log (inside the zone unless
     * {@code ai.builder.gather-outside-zone}), queue chopping and picking up the
     * drop in front of the current step.
     */
    private boolean gatherWood(Job job, Deque<BuildStep> queue, NullBody body, Player handle, World w, Vec3d pos) {
        if (job.logsGathered >= MAX_LOGS_PER_JOB) {
            return false;
        }
        boolean outside = v3 != null && v3.builderGatherOutsideZone();
        int px = (int) Math.floor(pos.x());
        int py = (int) Math.floor(pos.y());
        int pz = (int) Math.floor(pos.z());
        Block best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx++) {
            for (int dz = -16; dz <= 16; dz++) {
                if (!outside && !job.zone.zone().containsBlock(px + dx, pz + dz)) {
                    continue;
                }
                for (int dy = -4; dy <= 8; dy++) {
                    Block candidate = w.getBlockAt(px + dx, py + dy, pz + dz);
                    String name = candidate.getType().name();
                    if (name.endsWith("_LOG") || name.endsWith("_STEM")) {
                        double d = dx * dx + dy * dy * 2 + dz * dz;
                        if (d < bestDist) {
                            bestDist = d;
                            best = candidate;
                        }
                    }
                }
            }
        }
        if (best == null) {
            return false;
        }
        int rx = best.getX() - job.zone.originX();
        int ry = best.getY() - job.zone.originY();
        int rz = best.getZ() - job.zone.originZ();
        queue.addFirst(BuildStep.pickup(rx, Math.max(ry, (int) Math.floor(pos.y()) - job.zone.originY()), rz));
        queue.addFirst(BuildStep.breakAt(rx, ry, rz));
        job.logsGathered++;
        return true;
    }

    /** One log in the inventory becomes four planks of its kind, as in the 2x2 grid. */
    private void craftPlanksIfNeeded(Player handle, Deque<BuildStep> queue) {
        BuildStep next = queue.peekFirst();
        if (next == null || next.action() != BuildStep.Action.PLACE || !next.block().endsWith("_PLANKS")) {
            return;
        }
        PlayerInventory inv = handle.getInventory();
        if (anyPlanks(inv) >= 0) {
            return;
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null) {
                continue;
            }
            String name = stack.getType().name().replace("STRIPPED_", "");
            if (name.endsWith("_LOG") || name.endsWith("_STEM")) {
                Material planks = Material.matchMaterial(name.replace("_LOG", "_PLANKS").replace("_STEM", "_PLANKS"));
                if (planks == null) {
                    continue;
                }
                stack.setAmount(stack.getAmount() - 1);
                inv.setItem(i, stack.getAmount() <= 0 ? null : stack);
                inv.addItem(new ItemStack(planks, 4));
                return;
            }
        }
    }

    private void skip(Job job, Deque<BuildStep> queue, BuildStep step, String why) {
        if (queue.peekFirst() == step) {
            queue.pollFirst();
        }
        job.skipped++;
        if (job.skips.size() < 10) {
            job.skips.add(step + ": " + why);
        }
        plugin.getLogger().fine("[NullArmy] build step skipped: " + step + " - " + why);
    }

    // ----------------------------------------------------------------- helpers

    private List<NullBody> liveBodies(UUID owner) {
        List<NullBody> out = new ArrayList<>();
        if (plugin.squads() == null || owner == null) {
            return out;
        }
        for (NullBody body : plugin.squads().membersOf(owner)) {
            try {
                if (body.isAlive() && body.uuid() != null) {
                    out.add(body);
                }
            } catch (Throwable ignored) {
                // skip unreadable bodies
            }
        }
        return out;
    }

    /** Placeable blocks the squad carries. */
    private static Map<String, Integer> stock(List<NullBody> bodies) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (NullBody body : bodies) {
            Player handle = Bodies.player(body);
            if (handle == null) {
                continue;
            }
            for (ItemStack stack : handle.getInventory().getStorageContents()) {
                if (stack != null && stack.getType().isBlock() && stack.getType().isSolid()
                        && !stack.getType().name().endsWith("_SHULKER_BOX")) {
                    out.merge(stack.getType().name(), stack.getAmount(), Integer::sum);
                }
            }
        }
        return out;
    }

    private static int[] relative(ZoneService.Record zone, Vec3d at) {
        return new int[] {(int) Math.floor(at.x()) - zone.originX(), (int) Math.floor(at.y()) - zone.originY(),
                (int) Math.floor(at.z()) - zone.originZ()};
    }

    /** Minecraft yaw to the planner's cardinal facing (0 south, 1 west, 2 north, 3 east). */
    static int facingOf(float yaw) {
        return Math.floorMod(Math.round(yaw / 90.0F), 4);
    }

    private static String ownerName(UUID owner) {
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        return player != null ? player.getName() : (owner == null ? "?" : owner.toString().substring(0, 8));
    }
}
