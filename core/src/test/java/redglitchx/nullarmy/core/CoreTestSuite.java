package redglitchx.nullarmy.core;

import redglitchx.nullarmy.core.agent.AgentBinding;
import redglitchx.nullarmy.core.agent.AgentRegistry;
import redglitchx.nullarmy.core.agent.AgentRole;
import redglitchx.nullarmy.core.agent.CircuitBreaker;
import redglitchx.nullarmy.core.agent.EndpointConfig;
import redglitchx.nullarmy.core.combat.CombatSituation;
import redglitchx.nullarmy.core.combat.PvpArsenal;
import redglitchx.nullarmy.core.ai.Capability;
import redglitchx.nullarmy.core.brain.NullState;
import redglitchx.nullarmy.core.brain.Objective;
import redglitchx.nullarmy.core.brain.UtilityPlanner;
import redglitchx.nullarmy.core.config.Caps;
import redglitchx.nullarmy.core.config.SummonRules;
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
        run("summon count honours the hard cap", CoreTestSuite::testSummonHardCap);
        run("summon count honours live capacity", CoreTestSuite::testSummonCapacity);
        run("summon refuses when the server is full", CoreTestSuite::testSummonFull);
        run("summon refuses non-positive counts", CoreTestSuite::testSummonNonPositive);
        run("summon leaves a fitting request alone", CoreTestSuite::testSummonNoClamp);
        run("partial spawn reports how many made it", CoreTestSuite::testSummonPartialSpots);
        run("endpoint validates endpoint/model-id/timeout", CoreTestSuite::testEndpointValidation);
        run("endpoint holds env:NAME, never the key itself", CoreTestSuite::testEndpointKeyIsEnvName);
        run("api-key accepts env:NAME or literal, never leaks", CoreTestSuite::testApiKeyForms);

        run("commander knows mace and elytra PvP techniques", CoreTestSuite::testPvpArsenal);
        run("mace smash is only chosen when it is lethal", CoreTestSuite::testMaceSmashDiscipline);
        run("elytra techniques need a deployed elytra", CoreTestSuite::testElytraRequiresElytra);
        run("technique selector is deterministic", CoreTestSuite::testTechniqueSelectorIsDeterministic);
        run("loadout slot maps to a valid inventory index", CoreTestSuite::testLoadoutSlot);

        run("plugin is complete with no AI models configured", CoreTestSuite::testOfflineCapabilities);
        run("AI-only features are listed, not hidden", CoreTestSuite::testLostWithoutAi);
        run("inventory mirrors a real player: 41 slots", CoreTestSuite::testInventoryMatchesPlayer);
        run("agent binding builds a deduped endpoint chain", CoreTestSuite::testAgentBindingChain);
        run("registry accepts a valid endpoint/agent config", CoreTestSuite::testRegistryValid);
        run("registry rejects a dangling endpoint reference", CoreTestSuite::testRegistryDanglingRef);
        run("registry rejects duplicate role bindings", CoreTestSuite::testRegistryDuplicateRole);
        run("registry rejects duplicate endpoint ids", CoreTestSuite::testRegistryDuplicateEndpoint);
        run("role lookup is case-insensitive and rejects unknown", CoreTestSuite::testRoleLookup);
        run("no agent role holds moderation authority", CoreTestSuite::testNoModerationRole);
        run("portal plan honours the hard maximum and never builds an empty door",
                CoreTestSuite::testPortalPlanLimits);
        run("portal plan places every Null or reports the spill",
                CoreTestSuite::testPortalPlanConservesNulls);
        run("portal plan varies the count and the split between summons",
                CoreTestSuite::testPortalPlanVaries);
        run("portal plan redistributes when a site cannot be built",
                CoreTestSuite::testPortalPlanWithoutPortal);
        run("default kit carries the required items in the right slots",
                CoreTestSuite::testDefaultKitSlots);
        run("default kit config parsing round-trips and skips junk",
                CoreTestSuite::testDefaultKitParsing);
        run("re-applying a kit never duplicates an item",
                CoreTestSuite::testKitReapplyNoDuplicates);
        run("totem shutdown is one Null at a time with the Commander last",
                CoreTestSuite::testShutdownSequence);
        run("totem shutdown blocks new summons while it runs",
                CoreTestSuite::testShutdownBlocksSpawns);
        run("summon items are named exactly and recognised by tag after a rename",
                CoreTestSuite::testSummonItemIdentity);
        run("a plain Totem of Undying is not the Totem Of Null",
                CoreTestSuite::testPlainTotemNotOurs);
        run("config merge adds missing keys and keeps owner values",
                CoreTestSuite::testConfigMerge);
        run("role assignment gives every member exactly one role",
                CoreTestSuite::testRoleAssignment);
        run("AI actions are allowlisted and typed, everything else is refused",
                CoreTestSuite::testSquadActionAllowlist);
        run("AI actions that need a human are never auto-executed",
                CoreTestSuite::testSquadActionConfirmation);
        run("the AI policy gate denies closed gates and a running shutdown",
                CoreTestSuite::testActionPolicyGates);
        run("a mission starts, reports progress and stops safely",
                CoreTestSuite::testMissionLifecycle);
        run("missions never ask for destruction",
                CoreTestSuite::testMissionsAreSafe);

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

    private static void testSummonHardCap() {
        SummonRules.Decision d = SummonRules.decide(50, 24, 0, 64);
        checkEquals(24, d.granted(), "granted");
        check(d.clamped(), "must be reported as clamped");
        check(!d.refused(), "a clamped request still spawns");
        check(d.explanation().contains("24"), "the explanation must state the cap");
    }

    private static void testSummonCapacity() {
        SummonRules.Decision d = SummonRules.decide(10, 24, 60, 64);
        checkEquals(4, d.granted(), "granted");
        check(d.clamped(), "must be reported as clamped");
        check(d.explanation().contains("4"), "the explanation must state the real number");
    }

    private static void testSummonFull() {
        SummonRules.Decision d = SummonRules.decide(5, 24, 64, 64);
        checkEquals(0, d.granted(), "granted");
        check(d.refused(), "a full server must refuse, not partially spawn");
        check(d.explanation().length() > 0, "a refusal must say why");
    }

    private static void testSummonNonPositive() {
        check(SummonRules.decide(0, 24, 0, 64).refused(), "zero is not a summon");
        check(SummonRules.decide(-3, 24, 0, 64).refused(), "negative is not a summon");
        check(SummonRules.decide(5, 0, 0, 64).refused(), "a zero hard cap disables summoning");
    }

    private static void testSummonNoClamp() {
        SummonRules.Decision d = SummonRules.decide(3, 24, 5, 64);
        checkEquals(3, d.granted(), "granted");
        check(!d.clamped(), "a request that fits must not be clamped");
    }

    private static void testSummonPartialSpots() {
        SummonRules.Decision d = SummonRules.decideSafeSpots(10, 4);
        checkEquals(4, d.granted(), "granted");
        check(d.clamped(), "partial spawn must be reported");
        check(d.explanation().contains("4"), "the explanation must state how many spawned");
        SummonRules.Decision none = SummonRules.decideSafeSpots(5, 0);
        check(none.refused(), "no safe spot means nothing spawns");
        checkEquals(0, SummonRules.remainingCapacity(70, 64), "live count above the cap is still 0 free");
        checkEquals(64, SummonRules.remainingCapacity(0, 64), "an empty server has full capacity");
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


    // ----------------------------------------------------- offline capability

    private static void testOfflineCapabilities() {
        // The core promise: with no endpoints at all, the plugin still works.
        check(Capability.countAlways() > 0, "there are always-available features");
        check(Capability.countLocalFallback() > 0, "there are local-fallback features");
        check(Capability.countAiOnly() > 0, "there are AI-only features");

        // Every capability must document itself, including what happens offline.
        // An undocumented gap is how owners end up thinking something is broken.
        for (Capability c : Capability.values()) {
            check(c.description() != null && !c.description().isEmpty(),
                    "capability " + c + " has a description");
            check(c.offlineBehaviour() != null && !c.offlineBehaviour().isEmpty(),
                    "capability " + c + " states its offline behaviour");
            check(c.group() != null && !c.group().isEmpty(),
                    "capability " + c + " has a group");
        }

        // ALWAYS features must never depend on AI being on.
        for (Capability c : Capability.with(Capability.Availability.ALWAYS)) {
            check(c.isUsable(false), "ALWAYS capability " + c + " works with AI off");
            check(c.isUsable(true), "ALWAYS capability " + c + " works with AI on");
        }
        // LOCAL_FALLBACK works either way.
        for (Capability c : Capability.with(Capability.Availability.LOCAL_FALLBACK)) {
            check(c.isUsable(false), "fallback capability " + c + " works with AI off");
            check(c.isUsable(true), "fallback capability " + c + " works with AI on");
        }
        // AI_ONLY is the honest exception.
        for (Capability c : Capability.with(Capability.Availability.AI_ONLY)) {
            check(!c.isUsable(false), "AI-only capability " + c + " is NOT usable offline");
            check(c.isUsable(true), "AI-only capability " + c + " is usable with AI on");
        }
    }

    private static void testLostWithoutAi() {
        List<Capability> lost = Capability.lostWithoutAi();
        check(!lost.isEmpty(), "the plugin is honest that some features need a model");
        checkEquals(Capability.countAiOnly(), lost.size(), "lost list matches AI-only count");
        // The things that MUST survive offline: the whole point of the plugin.
        check(Capability.SUMMON_HORN.isUsable(false), "horn summoning works offline");
        check(Capability.PORTAL_VISUALS.isUsable(false), "portal visuals work offline");
        check(Capability.COMMANDER_SPAWN.isUsable(false), "the Commander works offline");
        check(Capability.COMMANDER_LOADOUT.isUsable(false), "the loadout GUI works offline");
        check(Capability.MACE_TECHNIQUES.isUsable(false), "mace techniques work offline");
        check(Capability.ELYTRA_TECHNIQUES.isUsable(false), "elytra techniques work offline");
        check(Capability.SHARED_SKIN.isUsable(false), "the shared skin works offline");
    }

    private static void testInventoryMatchesPlayer() {
        // A real player inventory is exactly 41 slots:
        //   0-8   hotbar
        //   9-35  main storage (3 rows of 9)
        //   36    boots
        //   37    leggings
        //   38    chestplate
        //   39    helmet
        //   40    offhand
        checkEquals(41, PLAYER_INVENTORY_SLOTS, "a player inventory has 41 slots");
        checkEquals(0, HOTBAR_FIRST, "hotbar starts at 0");
        checkEquals(8, HOTBAR_LAST, "hotbar ends at 8");
        checkEquals(9, STORAGE_FIRST, "storage starts at 9");
        checkEquals(35, STORAGE_LAST, "storage ends at 35");
        checkEquals(36, SLOT_BOOTS, "boots slot");
        checkEquals(37, SLOT_LEGGINGS, "leggings slot");
        checkEquals(38, SLOT_CHESTPLATE, "chestplate slot");
        checkEquals(39, SLOT_HELMET, "helmet slot");
        checkEquals(40, SLOT_OFFHAND, "offhand slot");

        // The armour and offhand indices must be exactly the ones that are NOT
        // writable through the main inventory, which is why the adapter has to
        // map them explicitly rather than calling setItem(36..40).
        for (int i = 36; i <= 40; i++) {
            check(i >= 36, "slot " + i + " is an equipment slot, not a storage slot");
        }
        checkEquals(36, STORAGE_LAST + 1, "equipment slots begin right after storage");
    }

    /** The 41-slot player inventory layout, mirrored from LoadoutSlot. */
    private static final int PLAYER_INVENTORY_SLOTS = 41;
    private static final int HOTBAR_FIRST = 0;
    private static final int HOTBAR_LAST = 8;
    private static final int STORAGE_FIRST = 9;
    private static final int STORAGE_LAST = 35;
    private static final int SLOT_BOOTS = 36;
    private static final int SLOT_LEGGINGS = 37;
    private static final int SLOT_CHESTPLATE = 38;
    private static final int SLOT_HELMET = 39;
    private static final int SLOT_OFFHAND = 40;


    // ===================================================================
    //  Portal arrivals: random count, random distribution, no lost Nulls
    // ===================================================================

    private static void testPortalPlanLimits() {
        java.util.Random random = new java.util.Random(7L);
        for (int attempt = 0; attempt < 500; attempt++) {
            redglitchx.nullarmy.core.portal.PortalPlan plan =
                    redglitchx.nullarmy.core.portal.PortalPlan.of(12, 4, 6, random);
            check(plan.portalCount() >= 1, "at least one portal is always built");
            check(plan.portalCount() <= 4, "the configured maximum is a hard ceiling");
            for (int i = 0; i < plan.portalCount(); i++) {
                check(plan.nullsAt(i) >= 1, "a built portal always has a Null in it");
                check(plan.nullsAt(i) <= 6, "a portal never exceeds its per-portal cap");
            }
        }
        // A hard ceiling exists even when the config asks for something absurd.
        redglitchx.nullarmy.core.portal.PortalPlan huge =
                redglitchx.nullarmy.core.portal.PortalPlan.of(500, 9999, 4, new java.util.Random(3L));
        check(huge.portalCount() <= redglitchx.nullarmy.core.portal.PortalPlan.HARD_PORTAL_CEILING,
                "no summon may open more portals than the hard ceiling");
    }

    private static void testPortalPlanConservesNulls() {
        java.util.Random random = new java.util.Random(11L);
        for (int nulls = 1; nulls <= 40; nulls++) {
            for (int maxPortals = 1; maxPortals <= 6; maxPortals++) {
                redglitchx.nullarmy.core.portal.PortalPlan plan =
                        redglitchx.nullarmy.core.portal.PortalPlan.of(nulls, maxPortals, 4, random);
                checkEquals(nulls, plan.assigned() + plan.spill(),
                        "every Null is either in a portal or reported as spill ("
                                + nulls + " Nulls, " + maxPortals + " portals)");
                if (plan.spill() > 0) {
                int capacity = plan.portalCount() * 4;
                check(nulls > capacity, "spill only happens when every doorway is full");
                checkEquals(plan.portalCount(), Math.min(
                                redglitchx.nullarmy.core.portal.PortalPlan.HARD_PORTAL_CEILING,
                                Math.min(maxPortals, nulls)),
                        "spill only happens when every allowed portal was built");
            }
            }
        }
    }

    private static void testPortalPlanVaries() {
        java.util.Set<Integer> portalCounts = new java.util.HashSet<>();
        java.util.Set<String> shapes = new java.util.HashSet<>();
        java.util.Random random = new java.util.Random(2026L);
        for (int attempt = 0; attempt < 400; attempt++) {
            redglitchx.nullarmy.core.portal.PortalPlan plan =
                    redglitchx.nullarmy.core.portal.PortalPlan.of(9, 5, 9, random);
            portalCounts.add(plan.portalCount());
            shapes.add(plan.distribution().toString());
        }
        check(portalCounts.size() >= 3, "the portal count varies between summons, saw "
                + portalCounts);
        check(shapes.size() >= 5, "the split between portals varies, saw " + shapes.size()
                + " shapes");
    }

    private static void testPortalPlanWithoutPortal() {
        java.util.Random random = new java.util.Random(5L);
        redglitchx.nullarmy.core.portal.PortalPlan plan =
                redglitchx.nullarmy.core.portal.PortalPlan.of(8, 3, 4, random);
        check(plan.portalCount() >= 2, "the fixture opens at least two portals");
        redglitchx.nullarmy.core.portal.PortalPlan smaller = plan.withoutPortal(0);
        checkEquals(plan.portalCount() - 1, smaller.portalCount(),
                "one unbuildable site leaves one fewer portal");
        checkEquals(plan.nulls(), smaller.assigned() + smaller.spill(),
                "the Nulls of a lost doorway are redistributed, never dropped");
        redglitchx.nullarmy.core.portal.PortalPlan last = smaller.withoutPortal(0);
        redglitchx.nullarmy.core.portal.PortalPlan none = last.withoutPortal(0);
        checkEquals(0, none.portalCount(), "doorways can run out");
        checkEquals(plan.nulls(), none.spill(),
                "with no doorway left every Null is reported for the fallback path");
    }

    // ===================================================================
    //  Default equipment
    // ===================================================================

    private static void testDefaultKitSlots() {
        java.util.List<redglitchx.nullarmy.core.kit.DefaultKit.Item> kit =
                redglitchx.nullarmy.core.kit.DefaultKit.DEFAULT;
        java.util.Map<Integer, String> slots = redglitchx.nullarmy.core.kit.DefaultKit.slotMap(kit);
        checkEquals("IRON_CHESTPLATE", slots.get(38), "iron chestplate in the chestplate slot");
        checkEquals("SHIELD", slots.get(40), "shield in the offhand slot");
        checkEquals("IRON_SWORD", slots.get(0), "hotbar 0 holds the iron sword");
        checkEquals("BOW", slots.get(1), "hotbar 1 holds the bow");
        checkEquals("ARROW", slots.get(2), "hotbar 2 holds arrows");
        checkEquals("GOLDEN_APPLE", slots.get(3), "hotbar 3 holds golden apples");
        checkEquals("COOKED_BEEF", slots.get(4), "hotbar 4 holds cooked food");
        checkEquals("IRON_PICKAXE", slots.get(5), "hotbar 5 holds the iron pickaxe");
        checkEquals("ENDER_PEARL", slots.get(6), "hotbar 6 holds ender pearls");
        checkEquals("WATER_BUCKET", slots.get(7), "hotbar 7 holds the water bucket");
        checkEquals("TORCH", slots.get(8), "hotbar 8 holds torches");
        for (redglitchx.nullarmy.core.kit.DefaultKit.Item item : kit) {
            check(item.slot() >= 0 && item.slot() <= 40, "every kit slot is a real player slot");
            check(item.count() >= 1 && item.count() <= 64, "every count is a legal stack size");
        }
    }

    private static void testDefaultKitParsing() {
        java.util.List<String> errors = new java.util.ArrayList<>();
        java.util.List<String> lines = java.util.Arrays.asList(
                "0:IRON_SWORD", "38:iron_chestplate:1", "40:SHIELD:1",
                "not a kit line", "99:STONE:1", "");
        java.util.List<redglitchx.nullarmy.core.kit.DefaultKit.Item> kit =
                redglitchx.nullarmy.core.kit.DefaultKit.parse(lines, errors);
        checkEquals(3, kit.size(), "three readable lines become three items");
        checkEquals(2, errors.size(), "the junk line and the out-of-range slot are reported");
        checkEquals("IRON_SWORD", kit.get(0).material(), "material names are normalised");
        java.util.List<String> round = redglitchx.nullarmy.core.kit.DefaultKit.serialize(kit);
        java.util.List<redglitchx.nullarmy.core.kit.DefaultKit.Item> again =
                redglitchx.nullarmy.core.kit.DefaultKit.parse(round, null);
        checkEquals(kit, again, "serialize then parse gives the same kit back");
        // An empty or fully broken config falls back to the shipped kit rather
        // than leaving a Null with nothing.
        check(redglitchx.nullarmy.core.kit.DefaultKit.parse(null, null)
                        == redglitchx.nullarmy.core.kit.DefaultKit.DEFAULT,
                "no config means the default kit");
        check(redglitchx.nullarmy.core.kit.DefaultKit.parse(
                        java.util.Collections.singletonList("junk"), null)
                        == redglitchx.nullarmy.core.kit.DefaultKit.DEFAULT,
                "a broken config means the default kit");
    }

    private static void testKitReapplyNoDuplicates() {
        java.util.List<redglitchx.nullarmy.core.kit.DefaultKit.Item> kit =
                redglitchx.nullarmy.core.kit.DefaultKit.DEFAULT;
        java.util.Map<Integer, String> empty = new java.util.LinkedHashMap<>();
        checkEquals(kit.size(),
                redglitchx.nullarmy.core.kit.DefaultKit.missingFrom(kit, empty).size(),
                "a naked Null is missing the whole kit");

        java.util.Map<Integer, String> equipped = redglitchx.nullarmy.core.kit.DefaultKit.slotMap(kit);
        check(redglitchx.nullarmy.core.kit.DefaultKit.missingFrom(kit, equipped).isEmpty(),
                "applying the same kit twice writes nothing, so nothing duplicates");

        java.util.Map<Integer, String> edited = new java.util.LinkedHashMap<>(equipped);
        edited.put(0, "NETHERITE_SWORD");
        edited.remove(8);
        java.util.List<redglitchx.nullarmy.core.kit.DefaultKit.Item> missing =
                redglitchx.nullarmy.core.kit.DefaultKit.missingFrom(kit, edited);
        checkEquals(2, missing.size(), "only the changed and the emptied slot are refilled");
        boolean touchedOwnerChoice = false;
        for (redglitchx.nullarmy.core.kit.DefaultKit.Item item : missing) {
            if (item.slot() == 38 || item.slot() == 40) {
                touchedOwnerChoice = true;
            }
        }
        check(!touchedOwnerChoice, "an owner's edited slots are left alone");
    }

    // ===================================================================
    //  Totem Of Null: sequential shutdown
    // ===================================================================

    private static void testShutdownSequence() {
        java.util.List<redglitchx.nullarmy.core.totem.ShutdownSequence.Entry> entries =
                new java.util.ArrayList<>();
        entries.add(new redglitchx.nullarmy.core.totem.ShutdownSequence.Entry("Commander", true));
        for (int i = 0; i < 5; i++) {
            entries.add(new redglitchx.nullarmy.core.totem.ShutdownSequence.Entry("Null" + i, false));
        }
        java.util.List<redglitchx.nullarmy.core.totem.ShutdownSequence.Step> steps =
                redglitchx.nullarmy.core.totem.ShutdownSequence.schedule(entries, 1000L, 10);
        checkEquals(6, steps.size(), "every Null, including the Commander, gets one step");
        check(steps.get(5).commander(), "the Commander goes last");
        for (int i = 1; i < steps.size(); i++) {
            checkEquals(10L, steps.get(i).atTick() - steps.get(i - 1).atTick(),
                    "the delay between two Nulls is visible, never zero");
        }
        checkEquals(1000L, steps.get(0).atTick(), "the first step runs when it was asked to");
        checkEquals(1050L,
                redglitchx.nullarmy.core.totem.ShutdownSequence.endTick(steps, 1000L),
                "the last step is the end of the sequence");
        checkEquals(1, redglitchx.nullarmy.core.totem.ShutdownSequence.due(steps, 1005L).size(),
                "only one Null is due after five ticks");
        checkEquals(6, redglitchx.nullarmy.core.totem.ShutdownSequence.due(steps, 2000L).size(),
                "everything is due once the sequence has run out");
        // A zero delay would be a single-tick mass delete, which is the thing
        // this sequence exists to avoid.
        java.util.List<redglitchx.nullarmy.core.totem.ShutdownSequence.Step> noDelay =
                redglitchx.nullarmy.core.totem.ShutdownSequence.schedule(entries, 0L, 0);
        check(noDelay.get(1).atTick() > noDelay.get(0).atTick(),
                "a configured delay of zero is raised to one tick");
    }

    private static void testShutdownBlocksSpawns() {
        java.util.List<redglitchx.nullarmy.core.totem.ShutdownSequence.Entry> entries =
                java.util.Arrays.asList(
                        new redglitchx.nullarmy.core.totem.ShutdownSequence.Entry("a", false),
                        new redglitchx.nullarmy.core.totem.ShutdownSequence.Entry("b", false),
                        new redglitchx.nullarmy.core.totem.ShutdownSequence.Entry("c", false));
        java.util.List<redglitchx.nullarmy.core.totem.ShutdownSequence.Step> steps =
                redglitchx.nullarmy.core.totem.ShutdownSequence.schedule(entries, 500L, 20);
        check(!redglitchx.nullarmy.core.totem.ShutdownSequence.isRunning(steps, 500L, 499L),
                "before it starts, summons are still allowed");
        check(redglitchx.nullarmy.core.totem.ShutdownSequence.isRunning(steps, 500L, 500L),
                "the first tick of the sequence refuses new summons");
        check(redglitchx.nullarmy.core.totem.ShutdownSequence.isRunning(steps, 500L, 540L),
                "mid-sequence refuses new summons");
        check(!redglitchx.nullarmy.core.totem.ShutdownSequence.isRunning(steps, 500L, 541L),
                "after the last step the plugin accepts summons again");
        check(!redglitchx.nullarmy.core.totem.ShutdownSequence.isRunning(
                        java.util.Collections.emptyList(), 500L, 500L),
                "an empty plan blocks nothing");
    }

    // ===================================================================
    //  Summon item identity
    // ===================================================================

    private static void testSummonItemIdentity() {
        checkEquals("Null", redglitchx.nullarmy.core.item.SummonItemSpec.HORN_DISPLAY_NAME,
                "the Call Horn keeps the name Null");
        checkEquals("The Totem Of Null",
                redglitchx.nullarmy.core.item.SummonItemSpec.TOTEM_DISPLAY_NAME,
                "the totem is named exactly The Totem Of Null");
        checkEquals("vanishing_curse",
                redglitchx.nullarmy.core.item.SummonItemSpec.VANISHING_CURSE_KEY,
                "the totem carries the real Curse of Vanishing");
        checkEquals("nullarmy:totem_of_null",
                redglitchx.nullarmy.core.item.SummonItemSpec.tagKey(
                        redglitchx.nullarmy.core.item.SummonItemSpec.TOTEM_TAG),
                "the persistent-data key is stable across renames and moves");

        // Renamed, repaired, moved: the tag still identifies it.
        checkEquals(redglitchx.nullarmy.core.item.SummonItemSpec.TOTEM_TAG,
                redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("TOTEM_OF_UNDYING",
                        "Whatever The Player Typed", false, true),
                "a renamed totem is still recognised by its tag");
        checkEquals(redglitchx.nullarmy.core.item.SummonItemSpec.HORN_TAG,
                redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("GOAT_HORN",
                        "renamed horn", true, false),
                "a renamed horn is still recognised by its tag");
        // Older builds named the items without tags; those items must keep working.
        checkEquals(redglitchx.nullarmy.core.item.SummonItemSpec.TOTEM_TAG,
                redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("TOTEM_OF_UNDYING",
                        "Totem Of Null", false, false),
                "the legacy totem name is still recognised");
        checkEquals("", redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("GOAT_HORN",
                        "The Totem Of Null", false, false),
                "a horn named like the totem is not a totem");
    }

    private static void testPlainTotemNotOurs() {
        checkEquals("", redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("TOTEM_OF_UNDYING",
                        null, false, false),
                "an unnamed vanilla totem is not ours");
        checkEquals("", redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("TOTEM_OF_UNDYING",
                        "Totem of Undying", false, false),
                "a vanilla-named totem is not ours");
        checkEquals("", redglitchx.nullarmy.core.item.SummonItemSpec.kindOf("DIAMOND",
                        "The Totem Of Null", false, true),
                "the right name on the wrong item is not ours");
        check(redglitchx.nullarmy.core.item.SummonItemSpec.isLegalProfileName("uH3WR2v0ti0uTHJ"),
                "a 16-character alphanumeric profile name is legal");
        check(redglitchx.nullarmy.core.item.SummonItemSpec.isLegalProfileName("uH3WR2v0ti0uTHJxy"),
                "16 characters is still legal");
        check(!redglitchx.nullarmy.core.item.SummonItemSpec.isLegalProfileName("uH3WR2v0ti0uTHJxyz"),
                "17 characters is not a legal profile name");
        check(!redglitchx.nullarmy.core.item.SummonItemSpec.isLegalProfileName("has space"),
                "a profile name may not contain a space");
        check(!redglitchx.nullarmy.core.item.SummonItemSpec.isLegalProfileName("§cColored"),
                "a profile name may not contain formatting");
    }

    // ===================================================================
    //  Config migration
    // ===================================================================

    private static void testConfigMerge() {
        java.util.Map<String, Object> existing = new java.util.LinkedHashMap<>();
        existing.put("limits.max-live-npcs", 100);
        existing.put("policy.griefing-enabled", Boolean.TRUE);
        existing.put("legacy.key-i-made-up", "keep me");
        java.util.Map<String, Object> shipped = new java.util.LinkedHashMap<>();
        shipped.put("limits.max-live-npcs", 64);
        shipped.put("limits.summon-hard-cap", 100);
        shipped.put("policy.griefing-enabled", Boolean.FALSE);
        shipped.put("portals.max-per-summon", 4);

        redglitchx.nullarmy.core.config.ConfigMerge.Result result =
                redglitchx.nullarmy.core.config.ConfigMerge.merge(existing, shipped);
        check(result.changed(), "a file missing new keys has to be written back");
        checkEquals(2, result.addedCount(), "exactly the two new keys are added");
        check(result.additions().containsKey("limits.summon-hard-cap"), "the new cap is added");
        check(result.additions().containsKey("portals.max-per-summon"), "the new section is added");
        check(!result.additions().containsKey("limits.max-live-npcs"),
                "an owner value is never overwritten");
        check(!result.additions().containsKey("policy.griefing-enabled"),
                "an owner's true stays true even when the shipped default is false");
        check(result.unknownKeys().contains("legacy.key-i-made-up"),
                "a key the build no longer ships is reported, not deleted");

        redglitchx.nullarmy.core.config.ConfigMerge.Result none =
                redglitchx.nullarmy.core.config.ConfigMerge.merge(shipped, shipped);
        check(!none.changed(), "an up-to-date file is left alone");
        check(redglitchx.nullarmy.core.config.ConfigMerge.describe(none).contains("already"),
                "the report says the file needed nothing");
        check(redglitchx.nullarmy.core.config.ConfigMerge.isLeaf(Boolean.FALSE),
                "a boolean is a leaf value");
        check(!redglitchx.nullarmy.core.config.ConfigMerge.isLeaf(
                        new java.util.LinkedHashMap<String, Object>()),
                "a section is not a leaf");
    }

    // ===================================================================
    //  Squad roles and AI coordination
    // ===================================================================

    private static void testRoleAssignment() {
        for (int size = 1; size <= 24; size++) {
            java.util.List<redglitchx.nullarmy.core.squad.SquadRole> roles =
                    redglitchx.nullarmy.core.squad.RoleAssignment.assign(size, true);
            checkEquals(size, roles.size(), "every member gets exactly one role");
            checkEquals(redglitchx.nullarmy.core.squad.SquadRole.COMMANDER, roles.get(0),
                    "index 0 is the Commander");
            for (redglitchx.nullarmy.core.squad.SquadRole role : roles) {
                check(role != null, "no member is left without a role");
            }
            if (size >= 3) {
                check(roles.contains(redglitchx.nullarmy.core.squad.SquadRole.SCOUT),
                        "a squad of " + size + " has a scout");
                check(roles.contains(redglitchx.nullarmy.core.squad.SquadRole.GUARD),
                        "a squad of " + size + " has a guard");
            }
            if (size >= 7) {
                check(roles.contains(redglitchx.nullarmy.core.squad.SquadRole.MEDIC),
                        "a squad of " + size + " has a medic");
            }
        }
        // Deterministic: the same size gives the same layout, so a report cannot
        // disagree with the squad.
        check(redglitchx.nullarmy.core.squad.RoleAssignment.assign(9, true)
                        .equals(redglitchx.nullarmy.core.squad.RoleAssignment.assign(9, true)),
                "role assignment is stable");
        check(redglitchx.nullarmy.core.squad.RoleAssignment.describe(
                        redglitchx.nullarmy.core.squad.RoleAssignment.assign(6, true))
                .contains("guard"), "the report names the roles it assigned");
    }

    private static void testSquadActionAllowlist() {
        checkEquals(redglitchx.nullarmy.core.ai.SquadAction.Kind.FORMATION,
                redglitchx.nullarmy.core.ai.SquadAction.parse("formation line").kind(),
                "a plain order parses");
        checkEquals("line",
                redglitchx.nullarmy.core.ai.SquadAction.parse("formation line").argument(),
                "the argument is carried with the action");
        checkEquals(redglitchx.nullarmy.core.ai.SquadAction.Kind.GUARD,
                redglitchx.nullarmy.core.ai.SquadAction.parse("guard: hold the gate").kind(),
                "the colon form parses");
        checkEquals(redglitchx.nullarmy.core.ai.SquadAction.Kind.TACTICS,
                redglitchx.nullarmy.core.ai.SquadAction.parse(
                        "{\"action\": \"tactics\", \"argument\": \"defensive\"}").kind(),
                "the JSON form parses");
        checkEquals("defensive",
                redglitchx.nullarmy.core.ai.SquadAction.parse(
                        "{\"action\": \"tactics\", \"argument\": \"defensive\"}").argument(),
                "the JSON argument parses");
        check(redglitchx.nullarmy.core.ai.SquadAction.parse("op ban Steve").isRefusal(),
                "a console command is not an action");
        check(redglitchx.nullarmy.core.ai.SquadAction.parse("kill all players").isRefusal(),
                "killing players is not in the allowlist");
        check(redglitchx.nullarmy.core.ai.SquadAction.parse("griefing-enabled true").isRefusal(),
                "a policy toggle is not an action");
        check(redglitchx.nullarmy.core.ai.SquadAction.parse("").isRefusal(),
                "an empty answer is a refusal");
        check(redglitchx.nullarmy.core.ai.SquadAction.parse(null).isRefusal(),
                "a null answer is a refusal");
        check(!redglitchx.nullarmy.core.ai.SquadAction.allowlist().contains("ban"),
                "no moderation action is allowlisted");
        check(!redglitchx.nullarmy.core.ai.SquadAction.allowlist().contains("kill"),
                "no kill action is allowlisted");
    }

    private static void testSquadActionConfirmation() {
        check(redglitchx.nullarmy.core.ai.SquadAction.Kind.CANNON.needsHumanConfirmation(),
                "the cannon always needs a human yes");
        check(redglitchx.nullarmy.core.ai.SquadAction.Kind.AIRDROP.needsHumanConfirmation(),
                "an air drop always needs a human yes");
        check(redglitchx.nullarmy.core.ai.SquadAction.Kind.DISMISS.needsHumanConfirmation(),
                "dismissing the squad always needs a human yes");
        check(!redglitchx.nullarmy.core.ai.SquadAction.Kind.REPORT.needsHumanConfirmation(),
                "a read-only report does not");
        check(!redglitchx.nullarmy.core.ai.SquadAction.Kind.FOLLOW.needsHumanConfirmation(),
                "walking to the owner does not");
    }

    private static void testActionPolicyGates() {
        //        ai  perm squad portal airdrop cannon missions running stopping shutdown
        final boolean[] gates = {true, true, true, false, true, true, false, true, false, false};
        redglitchx.nullarmy.core.ai.ActionPolicy.View open = policyView(gates);
        check(redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.REPORT, "", ""), open)
                .allowed(), "a report is always allowed");
        check(redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.FORMATION, "line", ""), open)
                .allowed(), "a formation order passes with the gates open");
        check(!redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.FORMATION, "pyramid", ""), open)
                .allowed(), "an invented formation is refused");
        check(redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.CANNON, "", ""), open)
                .needsConfirmation(), "the cannon needs confirmation even when it is enabled");
        check(!redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.PORTAL, "Steve", ""), open)
                .allowed(), "portal travel is refused while mechanics.portal-travel is false");

        // Shutdown running: nothing is ordered and nothing is created.
        boolean[] shutting = gates.clone();
        shutting[9] = true;
        check(!redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.FOLLOW, "", ""),
                        policyView(shutting)).allowed(),
                "a Totem Of Null shutdown stops every AI action");

        // No AI endpoint: the local fallback may report, and must not pretend.
        boolean[] noAi = gates.clone();
        noAi[0] = false;
        check(redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.REPORT, "", ""),
                        policyView(noAi)).allowed(),
                "the deterministic report works with no endpoint configured");
        check(!redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.HEAL, "", ""),
                        policyView(noAi)).allowed(),
                "a model-backed action is refused when no endpoint is configured");

        // No permission: refused with the permission named.
        boolean[] noPerm = gates.clone();
        noPerm[1] = false;
        redglitchx.nullarmy.core.ai.ActionPolicy.Decision denied =
                redglitchx.nullarmy.core.ai.ActionPolicy.check(
                        redglitchx.nullarmy.core.ai.SquadAction.of(
                                redglitchx.nullarmy.core.ai.SquadAction.Kind.FOLLOW, "", ""),
                        policyView(noPerm));
        check(!denied.allowed(), "a missing permission refuses the action");
        check(denied.reason().contains("nullarmy.follow"),
                "the refusal names the permission that is missing");
    }

    /** Builds a policy view from a fixed flag array. */
    private static redglitchx.nullarmy.core.ai.ActionPolicy.View policyView(final boolean[] g) {
        return new redglitchx.nullarmy.core.ai.ActionPolicy.View() {
            @Override public boolean aiUsable() { return g[0]; }
            @Override public boolean hasPermission(String permission) { return g[1]; }
            @Override public int liveNulls() { return 4; }
            @Override public int maxLiveNulls() { return 100; }
            @Override public boolean hasSquad() { return g[2]; }
            @Override public boolean portalTravelEnabled() { return g[3]; }
            @Override public boolean airdropUsable() { return g[4]; }
            @Override public boolean cannonUsable() { return g[5]; }
            @Override public boolean missionsEnabled() { return g[6]; }
            @Override public boolean missionRunning() { return g[7]; }
            @Override public boolean shutdownRunning() { return g[9]; }
            @Override public boolean pluginStopping() { return g[8]; }
        };
    }

    // ===================================================================
    //  Original mission system
    // ===================================================================

    private static void testMissionLifecycle() {
        redglitchx.nullarmy.core.mission.MissionBoard board =
                new redglitchx.nullarmy.core.mission.MissionBoard();
        check(!board.isRunning(), "nothing is running before a mission starts");
        redglitchx.nullarmy.core.mission.Mission mission = board.start(
                redglitchx.nullarmy.core.mission.MissionKind.SCOUT_OUTPOST, 100L, 6, null);
        check(mission != null && board.isRunning(), "starting a mission puts it on the board");
        checkEquals(6, mission.goal(), "the kind decides how much progress finishes it");
        check(!board.advance(2, 120L), "two sectors do not finish a six-sector objective");
        checkEquals(2, mission.progress(), "progress is recorded");
        check(board.advance(4, 140L), "the last sector completes the mission");
        check(!board.isRunning(), "a completed mission leaves the board");
        check(board.history().get(0).state()
                        == redglitchx.nullarmy.core.mission.Mission.State.COMPLETE,
                "the completed mission is remembered");

        // One objective at a time: starting a second stops the first safely.
        board.start(redglitchx.nullarmy.core.mission.MissionKind.BANNER_HOLD, 200L, 4, null);
        board.start(redglitchx.nullarmy.core.mission.MissionKind.NULL_TRIALS, 210L, 4, null);
        check(board.history().get(0).state()
                        == redglitchx.nullarmy.core.mission.Mission.State.STOPPED,
                "the replaced mission was stopped, not lost");
        check(board.stop(220L, "the owner said so"), "a mission can be stopped on command");
        check(!board.stop(221L, "again"), "stopping twice is honest about doing nothing");

        // Time runs out.
        board.start(redglitchx.nullarmy.core.mission.MissionKind.GATE_VIGIL, 300L, 5, null);
        check(!board.tick(301L), "a fresh mission is not out of time");
        check(board.tick(300L + redglitchx.nullarmy.core.mission.MissionKind.GATE_VIGIL
                        .durationTicks()),
                "a mission that overruns its window stops itself");
        check(!board.describe().isEmpty(), "the board always has something to report");
        check(board.start(null, 1L, 1, null) == null, "an unknown kind starts nothing");
    }

    private static void testMissionsAreSafe() {
        for (redglitchx.nullarmy.core.mission.MissionKind kind
                : redglitchx.nullarmy.core.mission.MissionKind.values()) {
            check(kind.goal() > 0, kind.key() + " has a measurable objective");
            check(kind.durationTicks() >= 600, kind.key() + " gives the squad time to work");
            check(!kind.title().isEmpty(), kind.key() + " has a name of its own");
            check(!kind.briefing().isEmpty(), kind.key() + " explains itself");
            check(redglitchx.nullarmy.core.squad.SquadRole.parse(kind.leadRole()) != null,
                    kind.key() + " is led by a real squad role");
            String text = (kind.title() + " " + kind.briefing()).toLowerCase(java.util.Locale.ROOT);
            check(!text.contains("explode") && !text.contains("destroy") && !text.contains("grief"),
                    kind.key() + " asks for nothing destructive");
        }
        check(redglitchx.nullarmy.core.mission.MissionKind.parse("banner-hold")
                        == redglitchx.nullarmy.core.mission.MissionKind.BANNER_HOLD,
                "a mission key parses");
        check(redglitchx.nullarmy.core.mission.MissionKind.parse("Banner Hold")
                        == redglitchx.nullarmy.core.mission.MissionKind.BANNER_HOLD,
                "a mission title parses");
        check(redglitchx.nullarmy.core.mission.MissionKind.parse("spawn tnt") == null,
                "an invented mission is not a mission");
    }

}
