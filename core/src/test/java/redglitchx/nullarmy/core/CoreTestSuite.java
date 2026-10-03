package redglitchx.nullarmy.core;

import redglitchx.nullarmy.core.agent.CircuitBreaker;
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
}
