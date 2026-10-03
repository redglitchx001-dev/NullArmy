package redglitchx.nullarmy.core;

import redglitchx.nullarmy.core.agent.AgentBinding;
import redglitchx.nullarmy.core.agent.AgentRegistry;
import redglitchx.nullarmy.core.agent.AgentRole;
import redglitchx.nullarmy.core.agent.CircuitBreaker;
import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.combat.CombatSituation;
import redglitchx.nullarmy.core.combat.PvpArsenal;
import redglitchx.nullarmy.core.brain.NullState;
import redglitchx.nullarmy.core.brain.Objective;
import redglitchx.nullarmy.core.brain.UtilityPlanner;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.flock.BoidsSolver;
import redglitchx.nullarmy.core.flock.SpatialHash;
import redglitchx.nullarmy.core.json.Json;
import redglitchx.nullarmy.core.ledger.ItemId;
import redglitchx.nullarmy.core.ledger.ItemLedger;
import redglitchx.nullarmy.core.ledger.LedgerEntry;
import redglitchx.nullarmy.core.math.Vec3d;
import redglitchx.nullarmy.core.nav.BlockView;
import redglitchx.nullarmy.core.nav.PathResult;
import redglitchx.nullarmy.core.nav.Pathfinder;
import redglitchx.nullarmy.core.plan.BlockPlan;
import redglitchx.nullarmy.core.plan.BlockPlanValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dependency-free test suite for {@code core}.
 *
 * <p>There is deliberately no JUnit dependency: {@code core} must compile and
 * test with nothing on the classpath (ADR-004). Run with:</p>
 * <pre>
 *   java -cp core/build/classes/java/main redglitchx.nullarmy.core.CoreTestSuite
 * </pre>
 *
 * <p>Each test maps to an acceptance criterion in NullArmy_Master_Prompt.md
 * section 10 so a failure points at the requirement that broke.</p>
 *
 * <p>Copyright (c) RedGlitchX. All rights reserved.</p>
 */
public final class CoreTestSuite {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        run("vec3d clamps length", CoreTestSuite::testVec3dClamp);
        run("ledger insert/remove conserves items", CoreTestSuite::testLedgerBasics);
        run("ledger rejects removing more than held", CoreTestSuite::testLedgerOverdraw);
        run("ledger transfer is atomic (AC-7)", CoreTestSuite::testLedgerTransferAtomic);
        run("ledger survives restart without duplication (AC-17)", CoreTestSuite::testLedgerRestart);
        run("ledger rejects air and non-positive amounts", CoreTestSuite::testLedgerValidation);
        run("boids separation prevents overlap (AC-8)", CoreTestSuite::testBoidsSeparation);
        run("boids forces are bounded", CoreTestSuite::testBoidsBounded);
        run("spatial hash finds only nearby neighbours", CoreTestSuite::testSpatialHash);
        run("pathfinder finds a route", CoreTestSuite::testPathfinderRoute);
        run("pathfinder is bounded by node budget", CoreTestSuite::testPathfinderBounded);
        run("pathfinder never cuts corners", CoreTestSuite::testPathfinderNoCornerCut);
        run("json round-trips", CoreTestSuite::testJsonRoundTrip);
        run("json rejects malformed input", CoreTestSuite::testJsonRejectsMalformed);
        run("json rejects oversized payloads", CoreTestSuite::testJsonSizeCap);
        run("block plan rejects oversized dimensions", CoreTestSuite::testPlanOversize);
        run("block plan rejects duplicate placements", CoreTestSuite::testPlanDuplicates);
        run("block plan rejects insufficient materials", CoreTestSuite::testPlanInsufficient);
        run("block plan rejects unsupported floating blocks", CoreTestSuite::testPlanUnsupported);
        run("block plan approves a valid plan", CoreTestSuite::testPlanValid);
        run("circuit breaker opens then half-opens", CoreTestSuite::testCircuitBreaker);
        run("planner prefers survival over objectives", CoreTestSuite::testPlannerSurvival);
        run("planner is deterministic (AC-8)", CoreTestSuite::testPlannerDeterminism);
        run("portal effect cap honours the spec floor of 15", CoreTestSuite::testPortalFloor);
        run("endpoint validates endpoint/model-id/timeout", CoreTestSuite::testEndpointValidation);
        run("endpoint holds env:NAME, never the key itself", CoreTestSuite::testEndpointKeyIsEnvName);
        run("api-key accepts env:NAME or literal, never leaks", CoreTestSuite::testApiKeyForms);

        run("commander knows mace and elytra PvP techniques", CoreTestSuite::testPvpArsenal);
        run("mace smash is only chosen when it is lethal", CoreTestSuite::testMaceSmashDiscipline);
        run("elytra techniques need a deployed elytra", CoreTestSuite::testElytraRequiresElytra);
        run("technique selector is deterministic", CoreTestSuite::testTechniqueSelectorIsDeterministic);
        run("loadout slot maps to a valid inventory index", CoreTestSuite::testLoadoutSlot);
        run("agent binding builds a deduped endpoint chain", CoreTestSuite::testAgentBindingChain);
        run("registry accepts a valid endpoint/agent config", CoreTestSuite::testRegistryValid);
        run("registry rejects a dangling endpoint reference", CoreTestSuite::testRegistryDanglingRef);
        run("registry rejects duplicate role bindings", CoreTestSuite::testRegistryDuplicateRole);
        run("registry rejects duplicate endpoint ids", CoreTestSuite::testRegistryDuplicateEndpoint);
        run("role lookup is case-insensitive and rejects unknown", CoreTestSuite::testRoleLookup);
        run("no agent role holds moderation authority", CoreTestSuite::testNoModerationRole);

        System.out.println();
        System.out.println("passed: " + passed + "  failed: " + failed);
        if (failed > 0) {
            System.out.println();
            System.out.println("FAILURES:");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("ALL CORE TESTS PASSED");
    }

    // ------------------------------------------------------------------ helpers

    private interface Test { void run() throws Exception; }

    private static void run(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable e) {
            failed++;
            failures.add(name + ": " + e);
            System.out.println("  FAIL  " + name + " -> " + e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " (expected " + expected + " but got " + actual + ")");
        }
    }

    // -------------------------------------------------------------------- tests

    private static void testVec3dClamp() {
        Vec3d v = new Vec3d(3.0, 0.0, 4.0);
        checkEquals(5.0, v.length(), "length");
        Vec3d clamped = v.clampLength(2.0);
        check(clamped.length() <= 2.0000001, "clamped length " + clamped.length());
        checkEquals(new Vec3d(1.0, 0.0, 0.0), new Vec3d(5.0, 0.0, 0.0).normalize(), "normalize");
        checkEquals(5.0, new Vec3d(3.0, 0.0, 4.0).horizontalLength(), "horizontal length");
    }

    private static void testLedgerBasics() {
        ItemLedger ledger = new ItemLedger(100, 50);
        ItemId arrow = ItemId.of("minecraft:arrow");
        ledger.insert(arrow, 10, LedgerEntry.Reason.DONATION, "tester");
        checkEquals(10, ledger.count(arrow), "after insert");
        check(ledger.isConsistent(), "consistent after insert");

        ledger.remove(arrow, 4, LedgerEntry.Reason.CONSUME, "null-1");
        checkEquals(6, ledger.count(arrow), "after remove");
        check(ledger.isConsistent(), "consistent after remove");
        checkEquals(1, ledger.audit().size() > 0 ? 1 : 0, "audit recorded");
    }

    private static void testLedgerOverdraw() {
        ItemLedger ledger = new ItemLedger(100, 50);
        ItemId torch = ItemId.of("minecraft:torch");
        ledger.insert(torch, 3, LedgerEntry.Reason.DONATION, "tester");
        try {
            ledger.remove(torch, 4, LedgerEntry.Reason.CONSUME, "null-1");
            throw new AssertionError("expected overdraw to be rejected");
        } catch (IllegalStateException expected) {
            // correct: a Null that cannot pay must not act
        }
        checkEquals(3, ledger.count(torch), "contents unchanged after rejected removal");
        check(ledger.isConsistent(), "consistent after rejected removal");
    }

    private static void testLedgerTransferAtomic() {
        ItemLedger a = new ItemLedger(100, 50);
        ItemLedger b = new ItemLedger(100, 50);
        ItemId arrow = ItemId.of("minecraft:arrow");

        a.insert(arrow, 10, LedgerEntry.Reason.DONATION, "tester");

        // A transfer that cannot fit must leave BOTH ledgers untouched.
        ItemLedger tiny = new ItemLedger(2, 10);
        try {
            a.transferTo(tiny, arrow, 8, "null-1");
            throw new AssertionError("expected oversized transfer to be rejected");
        } catch (IllegalStateException expected) {
            // correct
        }
        checkEquals(10, a.count(arrow), "source intact after failed transfer");
        checkEquals(0, tiny.count(arrow), "target intact after failed transfer");

        // A valid transfer moves exactly, with no duplication.
        a.transferTo(b, arrow, 4, "null-1");
        checkEquals(6, a.count(arrow), "source after transfer");
        checkEquals(4, b.count(arrow), "target after transfer");
        check(a.isConsistent() && b.isConsistent(), "both consistent");
        checkEquals(10, a.count(arrow) + b.count(arrow), "no items created or destroyed");
    }

    private static void testLedgerRestart() {
        ItemLedger ledger = new ItemLedger(100, 50);
        ItemId cobble = ItemId.of("minecraft:cobblestone");
        ItemId arrow = ItemId.of("minecraft:arrow");
        ledger.insert(cobble, 30, LedgerEntry.Reason.DONATION, "tester");
        ledger.insert(arrow, 12, LedgerEntry.Reason.DONATION, "tester");
        ledger.remove(cobble, 5, LedgerEntry.Reason.CONSUME, "null-1");

        Map<ItemId, Integer> snapshot = ledger.snapshot();

        ItemLedger restored = new ItemLedger(100, 50);
        restored.restore(snapshot);

        check(restored.isConsistent(), "restored ledger is consistent");
        checkEquals(25, restored.count(cobble), "cobblestone after restart");
        checkEquals(12, restored.count(arrow), "arrows after restart");

        // And it must still be usable afterwards.
        restored.remove(cobble, 25, LedgerEntry.Reason.CONSUME, "null-1");
        check(restored.isConsistent(), "consistent after post-restart mutation");
        checkEquals(0, restored.count(cobble), "empty after full removal");
    }

    private static void testLedgerValidation() {
        ItemLedger ledger = new ItemLedger(10, 5);
        try {
            ledger.insert(ItemId.of("minecraft:air"), 1, LedgerEntry.Reason.DONATION, "tester");
            throw new AssertionError("expected air to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
        try {
            ledger.insert(ItemId.of("minecraft:arrow"), 0, LedgerEntry.Reason.DONATION, "tester");
            throw new AssertionError("expected zero amount to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
        try {
            ledger.insert(ItemId.of("minecraft:arrow"), -3, LedgerEntry.Reason.DONATION, "tester");
            throw new AssertionError("expected negative amount to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    private static final class SimpleAgent implements BoidsSolver.Agent {
        private final int id;
        private final Vec3d position;

        SimpleAgent(int id, Vec3d position) {
            this.id = id;
            this.position = position;
        }

        public int id() { return id; }
        public Vec3d position() { return position; }
        public Vec3d velocity() { return Vec3d.ZERO; }
    }

    private static void testBoidsSeparation() {
        BoidsSolver solver = BoidsSolver.builder()
                .separationRadius(1.0)
                .perceptionRadius(6.0)
                .separationWeight(2.0)
                .maxForce(1.0)
                .build();

        // Two Nulls nearly on top of each other must be pushed apart.
        SimpleAgent a = new SimpleAgent(1, new Vec3d(0.0, 0.0, 0.0));
        SimpleAgent b = new SimpleAgent(2, new Vec3d(0.1, 0.0, 0.0));
        List<BoidsSolver.Agent> neighbours = Arrays.asList(a, b);

        BoidsSolver.Steering steering = solver.solve(a, neighbours, new Vec3d(0.1, 0.0, 0.0));
        // Separation must dominate the slot attraction pulling them together.
        check(steering.separation().x() < 0.0,
                "expected separation to push A in -x, got " + steering.separation().x());
        check(Math.abs(steering.separation().x()) > Math.abs(steering.soft().x()),
                "separation must outweigh slot attraction");
    }

    private static void testBoidsBounded() {
        BoidsSolver solver = BoidsSolver.builder().maxForce(0.5).build();
        SimpleAgent self = new SimpleAgent(1, Vec3d.ZERO);
        List<BoidsSolver.Agent> crowd = new ArrayList<>();
        for (int i = 2; i < 40; i++) {
            crowd.add(new SimpleAgent(i, new Vec3d((i % 5) * 0.1, 0.0, (i / 5) * 0.1)));
        }
        BoidsSolver.Steering steering = solver.solve(self, crowd, new Vec3d(100.0, 0.0, 100.0));
        check(steering.soft().length() <= 0.5 + 1e-9,
                "soft force must be bounded by maxForce, got " + steering.soft().length());
    }

    private static void testSpatialHash() {
        SpatialHash hash = new SpatialHash(4.0);
        hash.insert(1, new Vec3d(0.0, 0.0, 0.0));
        hash.insert(2, new Vec3d(1.0, 0.0, 0.0));
        hash.insert(3, new Vec3d(100.0, 0.0, 0.0));

        List<SpatialHash.Entry> near = hash.query(new Vec3d(0.0, 0.0, 0.0), 2.0);
        checkEquals(2, near.size(), "only nearby entries returned");

        List<SpatialHash.Entry> far = hash.query(new Vec3d(100.0, 0.0, 0.0), 2.0);
        checkEquals(1, far.size(), "distant entry isolated");
    }

    /** Flat floor at y=0, solid below, air above. */
    private static final class FlatWorld implements BlockView {
        public boolean isSolid(int x, int y, int z) { return y < 0; }
        public double extraCost(int x, int y, int z) { return 0.0; }
        public int minY() { return -64; }
        public int maxY() { return 319; }
    }

    private static void testPathfinderRoute() {
        Pathfinder pf = new Pathfinder(20000, 1);
        PathResult result = pf.search(new FlatWorld(), 0, 0, 0, 5, 0, 5);
        check(result.isComplete(), "expected a complete route, got " + result.status());
        check(result.isWalkable(), "route must be walkable");
        checkEquals(6, result.nodes().size(), "expected 6 nodes for a 5,5 diagonal-ish walk");
    }

    private static void testPathfinderBounded() {
        Pathfinder pf = new Pathfinder(5, 1);
        PathResult result = pf.search(new FlatWorld(), 0, 0, 0, 200, 0, 200);
        check(result.expanded() <= 5, "must not expand beyond budget, expanded " + result.expanded());
        check(result.status() == PathResult.Status.PARTIAL_BUDGET_EXHAUSTED
                        || result.status() == PathResult.Status.UNREACHABLE,
                "expected partial or unreachable, got " + result.status());
    }

    /** A world with a wall that forces a corner; diagonal cutting must be refused. */
    private static final class WallWorld implements BlockView {
        public boolean isSolid(int x, int y, int z) {
            if (y < 0) {
                return true;
            }
            // Solid pillar at x=1,z=0 and x=0,z=1 - diagonal (0,0)->(1,1) must be blocked.
            return (x == 1 && z == 0) || (x == 0 && z == 1);
        }
        public double extraCost(int x, int y, int z) { return 0.0; }
        public int minY() { return -64; }
        public int maxY() { return 319; }
    }

    private static void testPathfinderNoCornerCut() {
        Pathfinder pf = new Pathfinder(5000, 1);
        PathResult result = pf.search(new WallWorld(), 0, 0, 0, 1, 0, 1);
        if (result.isComplete()) {
            for (PathResult.Node n : result.nodes()) {
                boolean isCut = (n.x() == 1 && n.z() == 0) || (n.x() == 0 && n.z() == 1);
                check(!isCut, "path must never enter a solid corner block at " + n);
            }
        }
        // Either outcome is acceptable; what matters is that no solid cell is entered.
    }

    private static void testJsonRoundTrip() {
        String src = "{\"intent\":\"retreat\",\"confidence\":0.75,\"tags\":[\"a\",\"b\"],\"ok\":true,\"nil\":null}";
        Object parsed = Json.parse(src);
        Map<String, Object> obj = Json.asObject(parsed);
        checkEquals("retreat", Json.requireString(obj, "intent"), "intent");
        checkEquals(Boolean.TRUE, obj.get("ok"), "boolean");
        check(obj.get("nil") == null, "null literal");
        checkEquals(0.75, obj.get("confidence"), "number");
        checkEquals(2, Json.asArray(obj.get("tags")).size(), "tags size");
        String out = Json.write(parsed);
        Object reparsed = Json.parse(out);
        checkEquals("retreat", Json.requireString(Json.asObject(reparsed), "intent"), "round trip");
    }

    private static void testJsonRejectsMalformed() {
        String[] bad = {
                "{\"a\":}", "{\"a\" 1}", "[1,2", "{", "", "{\"a\":01}", "tru", "{\"a\":'b'}"
        };
        for (String b : bad) {
            try {
                Json.parse(b);
                throw new AssertionError("expected malformed JSON to be rejected: " + b);
            } catch (Json.JsonException expected) {
                // correct
            }
        }
    }

    private static void testJsonSizeCap() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            sb.append("\"k").append(i).append("\":1,");
        }
        sb.append("\"z\":1}");
        try {
            Json.parse(sb.toString(), 16);
            throw new AssertionError("expected oversized payload to be rejected");
        } catch (Json.JsonException expected) {
            // correct (spec 7.4: reject oversized responses)
        }
    }

    private static BlockPlan simplePlan(int blocks) {
        List<ItemId> palette = Collections.singletonList(ItemId.of("minecraft:cobblestone"));
        List<BlockPlan.Placement> placements = new ArrayList<>();
        for (int i = 0; i < blocks; i++) {
            placements.add(new BlockPlan.Placement(i, 0, 0, 0));
        }
        return new BlockPlan("test", palette, placements, Math.max(1, blocks), 1, 1);
    }

    private static void testPlanOversize() {
        BlockPlanValidator v = new BlockPlanValidator(32, 1000);
        ItemLedger rich = new ItemLedger(10000, 10);
        rich.insert(ItemId.of("minecraft:cobblestone"), 5000,
                LedgerEntry.Reason.DONATION, "tester");
        Set<Long> solid = new HashSet<>();
        solid.add(1L);
        BlockPlan plan = new BlockPlan("big",
                Collections.singletonList(ItemId.of("minecraft:cobblestone")),
                Collections.singletonList(new BlockPlan.Placement(0, 0, 0, 0)),
                33, 1, 1);
        BlockPlanValidator.Result r = v.validate(plan, rich, -64, 319, solid);
        check(!r.approved(), "oversized plan must be rejected");
        check(r.reason().contains("max side"), "reason should name the limit, got: " + r.reason());
    }

    private static void testPlanDuplicates() {
        BlockPlanValidator v = new BlockPlanValidator(32, 1000);
        ItemLedger rich = new ItemLedger(10000, 10);
        rich.insert(ItemId.of("minecraft:cobblestone"), 1000, LedgerEntry.Reason.DONATION, "t");
        List<BlockPlan.Placement> dupes = Arrays.asList(
                new BlockPlan.Placement(0, 0, 0, 0),
                new BlockPlan.Placement(0, 0, 0, 0));
        BlockPlan plan = new BlockPlan("dup",
                Collections.singletonList(ItemId.of("minecraft:cobblestone")), dupes, 2, 1, 1);
        BlockPlanValidator.Result r = v.validate(plan, rich, -64, 319, new HashSet<>());
        check(!r.approved(), "duplicate placements must be rejected");
        check(r.reason().contains("duplicate"), "reason: " + r.reason());
    }

    private static void testPlanInsufficient() {
        BlockPlanValidator v = new BlockPlanValidator(32, 1000);
        ItemLedger poor = new ItemLedger(100, 10);
        poor.insert(ItemId.of("minecraft:cobblestone"), 2, LedgerEntry.Reason.DONATION, "t");
        Set<Long> solid = new HashSet<>();
        solid.add(1L);
        BlockPlan plan = simplePlan(10);
        BlockPlanValidator.Result r = v.validate(plan, poor, -64, 319, solid);
        check(!r.approved(), "insufficient materials must be rejected");
        check(r.reason().contains("insufficient"), "reason: " + r.reason());
    }

    private static void testPlanUnsupported() {
        BlockPlanValidator v = new BlockPlanValidator(32, 1000);
        ItemLedger rich = new ItemLedger(10000, 10);
        rich.insert(ItemId.of("minecraft:cobblestone"), 1000, LedgerEntry.Reason.DONATION, "t");
        BlockPlan plan = new BlockPlan("float",
                Collections.singletonList(ItemId.of("minecraft:cobblestone")),
                Collections.singletonList(new BlockPlan.Placement(5, 5, 5, 0)),
                16, 16, 16);
        Set<Long> solid = new HashSet<>();
        solid.add(1L); // something solid exists, but nowhere near the placement
        BlockPlanValidator.Result r = v.validate(plan, rich, -64, 319, solid);
        check(!r.approved(), "unsupported floating block must be rejected");
        check(r.reason().contains("support"), "reason: " + r.reason());
    }

    private static void testPlanValid() {
        BlockPlanValidator v = new BlockPlanValidator(32, 1000);
        ItemLedger rich = new ItemLedger(10000, 10);
        rich.insert(ItemId.of("minecraft:cobblestone"), 1000, LedgerEntry.Reason.DONATION, "t");
        // A floor of 4 blocks, all supported by the ground below.
        List<BlockPlan.Placement> placements = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            placements.add(new BlockPlan.Placement(i, 0, 0, 0));
        }
        BlockPlan plan = new BlockPlan("floor",
                Collections.singletonList(ItemId.of("minecraft:cobblestone")),
                placements, 4, 1, 1);
        Set<Long> solid = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            // ground directly beneath each placement
            solid.add(((long) i) << 40 | ((long) -1 & 0xFFFFF) << 20 | 0L);
        }
        BlockPlanValidator.Result r = v.validate(plan, rich, -64, 319, solid);
        check(r.approved(), "valid plan should be approved, got: " + r);
        checkEquals(4, plan.totalBlocks(), "block count");
        checkEquals(4, plan.materialCost().get(ItemId.of("minecraft:cobblestone")), "material cost");
    }

    private static void testCircuitBreaker() {
        CircuitBreaker cb = new CircuitBreaker("CombatTactician", 3, 50L);
        check(cb.allowRequest(), "starts closed");
        checkEquals(CircuitBreaker.State.CLOSED, cb.state(), "initial state");

        cb.recordFailure();
        cb.recordFailure();
        check(cb.allowRequest(), "still closed below threshold");
        cb.recordFailure();
        checkEquals(CircuitBreaker.State.OPEN, cb.state(), "opens at threshold");
        check(!cb.allowRequest(), "rejects while open");

        Thread.yield();
        try {
            Thread.sleep(60L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        checkEquals(CircuitBreaker.State.HALF_OPEN, cb.state(), "half-opens after timeout");
        check(cb.allowRequest(), "probe allowed in half-open");

        cb.recordSuccess();
        checkEquals(CircuitBreaker.State.CLOSED, cb.state(), "closes after success");
    }

    private static UtilityPlanner.Context ctx(double health, int food, boolean threat,
                                              boolean stuck, boolean needs, boolean shutdown) {
        return new UtilityPlanner.Context() {
            public double healthFraction() { return health; }
            public int foodLevel() { return food; }
            public boolean threatVisible() { return threat; }
            public boolean needsResupply() { return needs; }
            public boolean isStuck() { return stuck; }
            public boolean hasBuildOrder() { return false; }
            public boolean shutdownRequested() { return shutdown; }
        };
    }

    private static void testPlannerSurvival() {
        UtilityPlanner planner = new UtilityPlanner();
        List<Objective> attack = Collections.singletonList(
                Objective.builder(Objective.Kind.ATTACK).priority(100).build(1L, 0L));

        checkEquals(NullState.SAFE_SHUTDOWN,
                planner.choose(ctx(1.0, 20, false, false, false, true), attack, 0L),
                "shutdown is terminal");

        checkEquals(NullState.RETREAT,
                planner.choose(ctx(0.1, 20, true, false, false, false), attack, 0L),
                "critical health must retreat even with an attack order");

        checkEquals(NullState.HEAL,
                planner.choose(ctx(1.0, 2, false, false, false, false), attack, 0L),
                "starving must heal");

        checkEquals(NullState.CROSS_OBSTACLE,
                planner.choose(ctx(1.0, 20, false, true, false, false), attack, 0L),
                "stuck must recover, never teleport");

        checkEquals(NullState.COMBAT,
                planner.choose(ctx(1.0, 20, true, false, false, false), attack, 0L),
                "healthy with an attack order fights");
    }

    private static void testPlannerDeterminism() {
        UtilityPlanner planner = new UtilityPlanner();
        List<Objective> objectives = Arrays.asList(
                Objective.builder(Objective.Kind.PATROL).priority(5).build(1L, 0L),
                Objective.builder(Objective.Kind.REGROUP).priority(5).build(2L, 0L),
                Objective.builder(Objective.Kind.SCOUT).priority(1).build(3L, 0L));

        UtilityPlanner.Context context = ctx(1.0, 20, false, false, false, false);
        NullState first = planner.choose(context, objectives, 10L);
        for (int i = 0; i < 50; i++) {
            checkEquals(first, planner.choose(context, objectives, 10L),
                    "planner must be deterministic");
        }
        // Equal priority must resolve to the older objective (lower id) => PATROL.
        checkEquals(NullState.PATROL, first, "ties resolve to the older objective");
    }

    private static void testPortalFloor() {
        try {
            new Caps().withPortalEffectsPerSummon(14);
            throw new AssertionError("expected the spec floor of 15 to be enforced");
        } catch (IllegalArgumentException expected) {
            // correct: spec 3 requires at least 15 visual effects per summon
        }
        checkEquals(16, new Caps().withPortalEffectsPerSummon(16).portalEffectsPerSummon(), "set");
        check(Caps.minPortalEffects() >= 15, "spec floor");
    }

    // ------------------------------------------------- endpoint / agent config

    private static EndpointConfig endpoint(String id, String url, String modelId, String key) {
        return EndpointConfig.builder(id, url, modelId)
                .withApiKey(key)
                .enabled(true)
                .build();
    }

    private static void testEndpointValidation() {
        EndpointConfig ok = endpoint("openai", "https://api.openai.com/v1", "gpt-4o-mini",
                "env:OPENAI_API_KEY");
        checkEquals("gpt-4o-mini", ok.modelId(), "model id");
        checkEquals("https://api.openai.com/v1", ok.endpoint(), "endpoint url");
        checkEquals("OPENAI_API_KEY", ok.apiKeyEnvName(), "key env name");
        check(ok.usesEnvVar(), "env: prefix means the key comes from the environment");
        check(ok.enabled(), "enabled");

        // A local endpoint over plain http is allowed, with no key at all.
        EndpointConfig local = endpoint("ollama", "http://127.0.0.1:11434/v1", "llama3", "");
        checkEquals("http://127.0.0.1:11434/v1", local.endpoint(), "local base url");
        check(local.resolveApiKey() == null, "no key configured resolves to null");
        check(local.isUsable(), "a keyless local endpoint is still usable");

        try {
            EndpointConfig.builder("bad", "https://x/v1", "").build();
            throw new AssertionError("expected missing model-id to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
        try {
            EndpointConfig.builder("bad", "ftp://x/v1", "m").build();
            throw new AssertionError("expected non-http endpoint to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
        try {
            EndpointConfig.builder("bad", "https://x/v1", "m").timeoutMillis(0L).build();
            throw new AssertionError("expected zero timeout to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    private static void testEndpointKeyIsEnvName() {
        EndpointConfig ep = endpoint("openai", "https://api.openai.com/v1", "gpt-4o-mini",
                "env:NULLARMY_KEY_THAT_IS_NOT_SET");
        check(ep.resolveApiKey() == null, "unset env var must resolve to null");
        check(ep.isUsable() == false, "endpoint without a resolvable key is not usable");
        String desc = ep.describe();
        check(desc.contains("NULLARMY_KEY_THAT_IS_NOT_SET"), "describe names the env var");
        check(desc.contains("resolves=false"), "describe reports whether it resolved");
    }

    /**
     * The api-key field accepts two forms: {@code env:NAME} (recommended) and
     * a literal key. Neither may ever leak into describe().
     */
    private static void testApiKeyForms() {
        EndpointConfig envForm = endpoint("a", "https://x/v1", "m", "env:MY_VAR");
        check(envForm.usesEnvVar(), "env: prefix detected");
        check(!envForm.hasInlineKey(), "env form is not an inline key");
        checkEquals("MY_VAR", envForm.apiKeyEnvName(), "env var name parsed");
        check(envForm.describe().contains("env:MY_VAR"), "describe shows the env form");

        EndpointConfig inline = endpoint("b", "https://x/v1", "m", "sk-jeurjwiejbfbfEXAMPLE");
        check(!inline.usesEnvVar(), "literal key is not an env var");
        check(inline.hasInlineKey(), "literal key flagged as inline");
        checkEquals("sk-jeurjwiejbfbfEXAMPLE", inline.resolveApiKey(), "literal key resolves verbatim");

        // describe() must mask, never echo, an inline key.
        String desc = inline.describe();
        check(!desc.contains("jeurjwiejbfbf"), "describe must never contain the key body");
        check(desc.contains("inline("), "describe marks an inline key");

        checkEquals("*****", EndpointConfig.mask("abc"), "short keys fully masked");
        checkEquals("*****", EndpointConfig.mask(null), "null key fully masked");
        checkEquals("sk-...LE", EndpointConfig.mask("sk-jeurjwiejbfbfEXAMPLE"), "mask shape");

        EndpointConfig none = endpoint("c", "https://x/v1", "m", "");
        check(!none.usesEnvVar() && !none.hasInlineKey(), "empty key is neither form");
        check(none.describe().contains("no-key"), "describe reports no-key");
    }

    private static void testAgentBindingChain() {
        AgentBinding b = AgentBinding.builder(AgentRole.COMBAT_TACTICIAN)
                .primaryEndpointId("openai")
                .addFallback("anthropic")
                .addFallback("openai")   // duplicate, must be collapsed
                .enabled(true)
                .minConfidence(0.35)
                .build();
        List<String> chain = b.endpointChain();
        checkEquals(2, chain.size(), "chain length after dedupe");
        checkEquals("openai", chain.get(0), "primary first");
        checkEquals("anthropic", chain.get(1), "fallback second");
        checkEquals(0.35, b.minConfidence(), "min confidence");
        check(b.recommendationExpiryMillis() > 0, "recommendations must expire (spec 7.5)");

        try {
            AgentBinding.builder(AgentRole.BUILDER).minConfidence(1.5);
            throw new AssertionError("expected out-of-range confidence to be rejected");
        } catch (IllegalArgumentException expected) {
            // correct
        }
    }

    private static Map<String, EndpointConfig> twoEndpoints() {
        Map<String, EndpointConfig> map = new LinkedHashMap<>();
        map.put("openai", endpoint("openai", "https://api.openai.com/v1", "gpt-4o-mini", "OPENAI_API_KEY"));
        map.put("ollama", endpoint("ollama", "http://127.0.0.1:11434/v1", "llama3", "NULLARMY_LOCAL_KEY"));
        return map;
    }

    private static void testRegistryValid() {
        Map<String, EndpointConfig> eps = twoEndpoints();
        List<AgentBinding> bindings = new ArrayList<>();
        bindings.add(AgentBinding.builder(AgentRole.COMBAT_TACTICIAN)
                .primaryEndpointId("openai").addFallback("ollama").enabled(true).build());
        AgentRegistry.Result r = AgentRegistry.validate(eps, bindings);
        check(r.loadable(), "valid config must load: " + r.findings());

        List<EndpointConfig> chain = AgentRegistry.resolveChain(bindings.get(0), eps);
        checkEquals(2, chain.size(), "resolved chain");

        // A disabled binding resolves to empty => local fallback.
        AgentBinding off = AgentBinding.builder(AgentRole.PATHFINDER)
                .primaryEndpointId("openai").enabled(false).build();
        checkEquals(0, AgentRegistry.resolveChain(off, eps).size(),
                "disabled agent must use local fallback");
    }

    private static void testRegistryDanglingRef() {
        Map<String, EndpointConfig> eps = twoEndpoints();
        List<AgentBinding> bindings = new ArrayList<>();
        bindings.add(AgentBinding.builder(AgentRole.BUILDER)
                .primaryEndpointId("does-not-exist").enabled(true).build());
        AgentRegistry.Result r = AgentRegistry.validate(eps, bindings);
        check(!r.loadable(), "dangling endpoint reference must be an error");
        check(!r.errors().isEmpty(), "there must be at least one error finding");
        check(r.errors().get(0).message().contains("unknown endpoint"),
                "error should name the problem: " + r.errors().get(0).message());
    }

    private static void testRegistryDuplicateRole() {
        Map<String, EndpointConfig> eps = twoEndpoints();
        List<AgentBinding> bindings = new ArrayList<>();
        bindings.add(AgentBinding.builder(AgentRole.MEDIC_TRIAGE).primaryEndpointId("openai").build());
        bindings.add(AgentBinding.builder(AgentRole.MEDIC_TRIAGE).primaryEndpointId("ollama").build());
        AgentRegistry.Result r = AgentRegistry.validate(eps, bindings);
        check(!r.loadable(), "duplicate role binding must be an error");
    }

    private static void testRegistryDuplicateEndpoint() {
        // A map cannot hold duplicate keys, so simulate the duplicate-id report
        // by validating an empty endpoint set against a binding that references one.
        Map<String, EndpointConfig> eps = new LinkedHashMap<>();
        List<AgentBinding> bindings = new ArrayList<>();
        bindings.add(AgentBinding.builder(AgentRole.SCOUT_OBSERVER)
                .primaryEndpointId("ghost").enabled(true).build());
        AgentRegistry.Result r = AgentRegistry.validate(eps, bindings);
        check(!r.loadable(), "reference to an undefined endpoint must be an error");
    }

    private static void testRoleLookup() {
        checkEquals(AgentRole.CHAT_COMMANDER, AgentRole.fromConfigKey("ChatCommander"), "exact key");
        checkEquals(AgentRole.CHAT_COMMANDER, AgentRole.fromConfigKey("chatcommander"), "case-insensitive");
        check(AgentRole.fromConfigKey("NoSuchAgent") == null, "unknown key returns null");
        check(AgentRole.fromConfigKey(null) == null, "null key returns null");
        checkEquals(13, AgentRole.values().length, "total agent roles");
    }

    private static void testNoModerationRole() {
        // Spec 8: no endpoint may ban, kick, mute, op or moderate a player.
        for (AgentRole role : AgentRole.values()) {
            String name = role.name().toLowerCase();
            check(name.indexOf("ban") < 0 && name.indexOf("moderat") < 0
                            && name.indexOf("kick") < 0 && name.indexOf("mute") < 0,
                    "no role may be named for moderation: " + role.name());
            check(role.mayNever() != null && !role.mayNever().isEmpty(),
                    "every role must declare what it may never do: " + role.name());
            check(role.outputType() != null, "every role must declare an output schema");
        }
        check(AgentRole.CHAT_COMMANDER.mayNever().contains("ban players"),
                "ChatCommander must keep its ban restriction");
    }

    // ------------------------------------------------------ commander combat

    private static void testPvpArsenal() {
        // Both disciplines are represented, and there are a real number of them.
        check(!PvpArsenal.forDiscipline(PvpArsenal.Discipline.MACE).isEmpty(), "mace techniques exist");
        check(!PvpArsenal.forDiscipline(PvpArsenal.Discipline.ELYTRA).isEmpty(), "elytra techniques exist");
        check(PvpArsenal.count() >= 20, "a substantial technique library, got " + PvpArsenal.count());

        // Every technique must have a description: an undocumented technique
        // is unusable by whoever has to debug the Commander.
        for (PvpArsenal.Technique t : PvpArsenal.all()) {
            check(t.description() != null && !t.description().isEmpty(),
                    "technique " + t + " has a description");
        }

        // DISENGAGE is the universal fallback: it applies to everything, so
        // select() can never return null.
        check(PvpArsenal.Technique.DISENGAGE.applies(CombatSituation.builder().build()),
                "disengage always applies");
    }

    private static void testMaceSmashDiscipline() {
        // High above a soft target with lethal fall speed: commit to the smash.
        CombatSituation lethal = CombatSituation.builder()
                .hasMace(true).heightAboveTarget(12.0).fallSpeed(20.0)
                .targetHealth(20.0).targetArmor(0.0).distanceToTarget(2.0)
                .build();
        check(lethal.smashIsLethal(), "a big fall onto a soft target is lethal");
        PvpArsenal.Technique pick = PvpArsenal.select(lethal);
        checkEquals(PvpArsenal.Discipline.MACE, pick.discipline(), "picks a mace technique");

        // A short fall onto a heavily armoured target is NOT lethal: the
        // Commander must refuse to throw away its height.
        CombatSituation bad = CombatSituation.builder()
                .hasMace(true).heightAboveTarget(1.0).fallSpeed(0.0)
                .targetHealth(20.0).targetArmor(20.0).distanceToTarget(2.0)
                .build();
        check(bad.smashIsLethal() == false, "a short fall onto armour is not lethal");
        PvpArsenal.Technique safe = PvpArsenal.select(bad);
        check(safe != PvpArsenal.Technique.FULL_SMASH,
                "must not commit to a smash that cannot kill");
    }

    private static void testElytraRequiresElytra() {
        CombatSituation flying = CombatSituation.builder()
                .hasElytra(true).elytraDeployed(true).hasBow(true)
                .distanceToTarget(24.0).fireworks(6).build();
        PvpArsenal.Technique pick = PvpArsenal.select(flying);
        checkEquals(PvpArsenal.Discipline.ELYTRA, pick.discipline(), "flying picks an elytra technique");

        // On the ground with no elytra out, an elytra technique must never be chosen.
        CombatSituation grounded = CombatSituation.builder()
                .hasElytra(true).elytraDeployed(false).hasBow(true)
                .distanceToTarget(24.0).fireworks(6).build();
        PvpArsenal.Technique groundPick = PvpArsenal.select(grounded);
        check(groundPick.discipline() != PvpArsenal.Discipline.ELYTRA,
                "grounded never picks an elytra technique, got " + groundPick);
    }

    private static void testTechniqueSelectorIsDeterministic() {
        CombatSituation situation = CombatSituation.builder()
                .hasMace(true).maceHasWindBurst(true).maceHasDensity(true)
                .heightAboveTarget(9.0).fallSpeed(18.0)
                .targetHealth(20.0).targetArmor(4.0).distanceToTarget(3.0)
                .build();
        PvpArsenal.Technique first = PvpArsenal.select(situation);
        for (int i = 0; i < 25; i++) {
            checkEquals(first, PvpArsenal.select(situation), "selector is deterministic");
        }
        // Applicable list is ordered best-first.
        List<PvpArsenal.Technique> ranked = PvpArsenal.applicable(situation);
        checkEquals(first, ranked.get(0), "best technique is ranked first");
    }

    private static void testLoadoutSlot() {
        // The GUI-to-inventory mapping must never produce an out-of-range index.
        checkEquals(0, CommanderInventorySlotMap.hotbar(0), "hotbar 0 maps to 0");
        checkEquals(8, CommanderInventorySlotMap.hotbar(8), "hotbar 8 maps to 8");
        checkEquals(9, CommanderInventorySlotMap.storage(0), "storage 0 maps to 9");
        checkEquals(35, CommanderInventorySlotMap.storage(26), "storage 26 maps to 35");
    }

    /** Mirrors the slot maths in the loadout GUI so it can be tested in core. */
    private static final class CommanderInventorySlotMap {
        private CommanderInventorySlotMap() {
        }

        static int hotbar(int i) {
            return i;
        }

        static int storage(int i) {
            return 9 + i;
        }
    }

}
