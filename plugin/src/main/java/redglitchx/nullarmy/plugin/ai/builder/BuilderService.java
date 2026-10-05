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

import redglitchx.nullarmy.core.construct.BuildPlanParser;
import redglitchx.nullarmy.core.construct.BuildPlanValidator;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The AI builder: a goal in words becomes blocks placed by hand.
 *
 * <h2>Planning</h2>
 * <p>With {@code ai.builder.endpoint} set, the goal, the zone, the Nulls' names
 * and what they carry go to an OpenAI-compatible {@code /chat/completions}
 * endpoint, which must answer with strict JSON steps
 * ({@code {null, action: MOVE|BREAK|PLACE|PICKUP|WAIT, x, y, z, block?}},
 * coordinates relative to the zone origin). The answer is parsed and validated
 * ({@link BuildPlanValidator}); a bad plan goes back once with the reasons, and a
 * second bad plan - or no endpoint, a timeout, an HTTP error - hands over to the
 * offline {@link FallbackPlanner} (hut, bridge, wall, tower, platform).</p>
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
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
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
     * @return the resolved triple (endpoint, model, key); the key is never a
     *     literal - either it is an {@code env:NAME} reference or empty
     */
    public Endpoint resolveEndpoint() {
        String configured = v3 == null ? "" : v3.builderEndpoint();
        return resolve(configured);
    }

    /** Resolves one configured endpoint string. Never throws, never returns null. */
    public Endpoint resolve(String configured) {
        String spec = configured == null ? "" : configured.trim();
        if (spec.isEmpty()) {
            return new Endpoint("", v3 == null ? "" : v3.builderModel(), v3 == null ? "" : v3.builderApiKey(), "");
        }
        if (spec.toLowerCase(Locale.ROOT).startsWith("id:")) {
            String id = spec.substring(3).trim();
            redglitchx.nullarmy.core.agent.EndpointConfig ep =
                    plugin.pluginConfig() == null ? null : plugin.pluginConfig().endpoints().get(id);
            if (ep == null) {
                return new Endpoint("", "", "", "unknown endpoint id '" + id + "'");
            }
            return new Endpoint(ep.baseUrl(), ep.modelId(), ep.resolveApiKey(), ep.id());
        }
        return new Endpoint(spec, v3 == null ? "" : v3.builderModel(), v3 == null ? "" : v3.builderApiKey(), "");
    }

    /** A resolved endpoint: where to POST, which model, which key, and where it came from. */
    public static final class Endpoint {
        private final String url;
        private final String model;
        private final String key;
        private final String id;

        Endpoint(String url, String model, String key, String id) {
            this.url = url == null ? "" : url;
            this.model = model == null ? "" : model;
            this.key = key == null ? "" : key;
            this.id = id == null ? "" : id;
        }

        public String url() { return url; }
        public String model() { return model; }
        public String key() { return key; }
        public String id() { return id; }
        public boolean usable() { return !url.isEmpty(); }
    }

    /**
     * P-07: {@code /null ai test [id]} - one real HTTP call, and the honest
     * answer printed: the HTTP status and the model that answered.
     */
    public void testEndpoint(String id, Consumer<String> report) {
        Endpoint endpoint = id == null || id.isEmpty() ? resolveEndpoint() : resolve("id:" + id);
        if (!endpoint.usable()) {
            report.accept(id == null || id.isEmpty()
                    ? "No endpoint is configured: set ai.builder.endpoint to a base URL or id:<name>."
                    : "No endpoint with the id '" + id + "' is configured.");
            return;
        }
        String url = endpoint.url().endsWith("/chat/completions") ? endpoint.url()
                : endpoint.url().replaceAll("/+$", "") + "/chat/completions";
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "Reply with the single word: ready");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", endpoint.model().isEmpty() ? "default" : endpoint.model());
        body.put("max_tokens", 8);
        body.put("messages", List.of(message));
        long startedAt = System.currentTimeMillis();
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(v3 == null ? 20000 : v3.builderTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (!endpoint.key().isEmpty()) {
                request.header("Authorization", "Bearer " + endpoint.key());
            }
            http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                long millis = System.currentTimeMillis() - startedAt;
                String line;
                if (error != null) {
                    line = "endpoint " + url + " -> request failed: " + Guard.describe(error);
                } else {
                    String model = contentModel(response.body());
                    line = "endpoint " + url + " -> HTTP " + response.statusCode()
                            + ", model " + (model.isEmpty() ? "(none reported)" : model)
                            + " in " + millis + "ms";
                }
                Bukkit.getScheduler().runTask(plugin, () -> Guard.attempt(plugin.getLogger(),
                        "reporting an endpoint test", () -> report.accept(line)));
            });
        } catch (Throwable t) {
            report.accept("endpoint " + url + " -> the request could not be sent: " + Guard.describe(t));
        }
    }

    /** {@code /null ai endpoints}: every configured endpoint and what resolves. */
    public String describeEndpoints() {
        StringBuilder out = new StringBuilder();
        out.append("builder endpoint: ");
        String configured = v3 == null ? "" : v3.builderEndpoint();
        out.append(configured.isEmpty() ? "(none - the offline planner is used)" : configured);
        Endpoint resolved = resolveEndpoint();
        out.append("\n  resolved: ").append(resolved.usable() ? resolved.url() : "(nothing to resolve)")
                .append(" model ").append(resolved.model().isEmpty() ? "(default)" : resolved.model());
        Map<String, redglitchx.nullarmy.core.agent.EndpointConfig> known =
                plugin.pluginConfig() == null ? Map.of() : plugin.pluginConfig().endpoints();
        if (known.isEmpty()) {
            out.append("\n  ai.endpoints: none configured");
            return out.toString();
        }
        out.append("\n  ai.endpoints:");
        for (redglitchx.nullarmy.core.agent.EndpointConfig ep : known.values()) {
            out.append("\n    ").append(ep.id()).append(" -> ").append(ep.baseUrl())
                    .append(" model ").append(ep.modelId())
                    .append(ep.enabled() ? "" : " (disabled)")
                    .append(ep.hasInlineKey() ? " WARNING: inline API key" : "");
        }
        return out.toString();
    }

    /** The model name an OpenAI-style reply reports, or empty. */
    private static String contentModel(String body) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(body, 1024 * 1024));
            Object model = root.get("model");
            return model instanceof String ? (String) model : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** As {@link #start}, with an explicit endpoint (the self test's local stub). */
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
        ZoneService.Record zone = plugin.zones() == null ? null
                : plugin.zones().existing(owner);
        if (zone == null || !zone.world().equals(world) || !zone.zone().contains(standAt.x(), standAt.z())) {
            zone = plugin.zones().open(owner, world, standAt);
        }
        final ZoneService.Record useZone = zone;
        int facing = facingOf(yaw);
        int[] anchor = relative(useZone, standAt);
        Endpoint resolved = endpointOverride != null ? new Endpoint(endpointOverride, modelOverride, keyOverride, "")
                : resolveEndpoint();
        String endpoint = resolved.url();
        String model = resolved.model();
        String key = resolved.key();
        Map<String, Integer> stock = stock(bodies);
        if (endpoint.isEmpty()) {
            Job job = fallback(owner, world, useZone, goal, anchor, facing, stock, "no ai.builder.endpoint configured");
            return job == null ? "the offline planner does not know how to build \"" + goal
                    + "\" (it knows hut, bridge, wall, tower, platform)" : "building " + job.describe();
        }
        String system = systemPrompt(useZone, v3 == null ? 400 : v3.builderMaxSteps());
        String user = userPrompt(goal, bodies, stock, anchor, facing);
        requestPlan(endpoint, model, key, system, user, null, plan -> {
            List<String> problems = plan.ok() ? validate(plan.steps(), useZone, bodies) : List.of(plan.error());
            if (problems.isEmpty()) {
                Job job = launch(owner, world, useZone, goal, plan.steps(), "ai " + (model.isEmpty() ? "model" : model));
                report.accept("the AI planned " + plan.steps().size() + " steps; building " + job.describe());
                return;
            }
            String retryNote = "Your previous plan was rejected: " + String.join("; ", problems)
                    + ". Return ONLY corrected JSON in the same format.";
            requestPlan(endpoint, model, key, system, user, retryNote, second -> {
                List<String> again = second.ok() ? validate(second.steps(), useZone, bodies) : List.of(second.error());
                if (again.isEmpty()) {
                    Job job = launch(owner, world, useZone, goal, second.steps(), "ai " + model + " (2nd try)");
                    report.accept("the AI's second plan passed; building " + job.describe());
                    return;
                }
                Job job = fallback(owner, world, useZone, goal, anchor, facing, stock,
                        "the AI plan was rejected twice (" + again.get(0) + ")");
                report.accept(job == null ? "the AI plan was rejected twice and the offline planner does not know \""
                        + goal + "\"" : "the AI plan was rejected twice; the offline planner builds " + job.describe());
            });
        });
        return "asking the AI for a plan (" + endpoint + ") - the Nulls start as soon as it answers";
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
        walk.lookHeadOnly = true;
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
        intent.lookHeadOnly = false;
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
        intent.lookHeadOnly = false;
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

    // ---------------------------------------------------------------- planning

    private List<String> validate(List<BuildStep> steps, ZoneService.Record zone, List<NullBody> bodies) {
        Set<String> names = new HashSet<>();
        for (NullBody body : bodies) {
            names.add(body.profileName());
        }
        int half = (int) Math.floor(zone.zone().half());
        return BuildPlanValidator.validate(steps, half, -16, 32, v3 == null ? 400 : v3.builderMaxSteps(), names,
                name -> {
                    Material m = Material.matchMaterial(name);
                    return m != null && m.isBlock() && m.isItem() && !m.isAir();
                });
    }

    /** POSTs to /chat/completions; the callback runs on the main thread. */
    private void requestPlan(String endpoint, String model, String key, String system, String user, String retryNote,
                             Consumer<BuildPlanParser.Result> callback) {
        try {
            String url = endpoint.endsWith("/chat/completions") ? endpoint
                    : endpoint.replaceAll("/+$", "") + "/chat/completions";
            List<Object> messages = new ArrayList<>();
            messages.add(message("system", system));
            messages.add(message("user", user));
            if (retryNote != null) {
                messages.add(message("user", retryNote));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model.isEmpty() ? "default" : model);
            body.put("temperature", 0.2D);
            body.put("messages", messages);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(v3 == null ? 20000 : v3.builderTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (key != null && !key.isEmpty()) {
                request.header("Authorization", "Bearer " + key);
            }
            http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> {
                BuildPlanParser.Result result;
                if (error != null) {
                    lastAiStatus = "request failed: " + error.getClass().getSimpleName();
                    result = failure("the AI request failed: " + Guard.describe(error));
                } else if (response.statusCode() / 100 != 2) {
                    lastAiStatus = "HTTP " + response.statusCode();
                    result = failure("the AI endpoint answered HTTP " + response.statusCode() + ": "
                            + redglitchx.nullarmy.core.skin.SkinPayload.preview(response.body(), 80));
                } else {
                    lastAiStatus = "HTTP " + response.statusCode();
                    result = BuildPlanParser.parse(contentOf(response.body()), v3 == null ? 400 : v3.builderMaxSteps());
                }
                final BuildPlanParser.Result done = result;
                Bukkit.getScheduler().runTask(plugin, () -> Guard.attempt(plugin.getLogger(),
                        "handling the AI plan", () -> callback.accept(done)));
            });
        } catch (Throwable t) {
            lastAiStatus = "request not sent: " + Guard.describe(t);
            callback.accept(failure("the AI request could not be sent: " + Guard.describe(t)));
        }
    }

    private static BuildPlanParser.Result failure(String why) {
        return BuildPlanParser.failure(why);
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** choices[0].message.content of an OpenAI-style reply (or the body itself). */
    static String contentOf(String body) {
        try {
            Map<String, Object> root = Json.asObject(Json.parse(body, 1024 * 1024));
            Object choices = root.get("choices");
            if (choices instanceof List && !((List<?>) choices).isEmpty()) {
                Object first = ((List<?>) choices).get(0);
                if (first instanceof Map) {
                    Object message = ((Map<?, ?>) first).get("message");
                    if (message instanceof Map && ((Map<?, ?>) message).get("content") instanceof String) {
                        return (String) ((Map<?, ?>) message).get("content");
                    }
                }
            }
        } catch (Throwable ignored) {
            // not an OpenAI envelope: maybe the plan itself
        }
        return body;
    }

    private static String systemPrompt(ZoneService.Record zone, int maxSteps) {
        int half = (int) Math.floor(zone.zone().half());
        return "You plan Minecraft builds for a squad of NPC players who place blocks by hand. "
                + "Answer with ONLY a JSON object: {\"steps\":[{\"null\":\"<name or any>\",\"action\":\"MOVE|BREAK|"
                + "PLACE|PICKUP|WAIT\",\"x\":int,\"y\":int,\"z\":int,\"block\":\"<MATERIAL for PLACE>\"}]}. "
                + "Coordinates are block offsets from the zone origin (0,0,0 is the block the owner stood on; "
                + "y=0 is their feet level, y=-1 the ground). Stay within |x|,|z| <= " + half + " and -16 <= y <= 32. "
                + "Order PLACE steps bottom-up so every block touches one placed before it or the ground. "
                + "Insert MOVE steps so a builder stands within 4 blocks of each block it places. "
                + "Use only blocks the squad carries. At most " + maxSteps + " steps. No prose.";
    }

    private static String userPrompt(String goal, List<NullBody> bodies, Map<String, Integer> stock, int[] anchor,
                                     int facing) {
        List<String> names = new ArrayList<>();
        for (NullBody body : bodies) {
            names.add(body.profileName());
        }
        String[] dirs = {"+z (south)", "-x (west)", "-z (north)", "+x (east)"};
        return "Goal: " + goal + ". Builders: " + names + ". Blocks carried: " + stock + ". The owner stands at "
                + anchor[0] + "," + anchor[1] + "," + anchor[2] + " facing " + dirs[Math.floorMod(facing, 4)]
                + "; build in front of them.";
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
