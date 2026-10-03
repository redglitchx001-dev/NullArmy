# Building & Verifying NullArmy

**Copyright (c) RedGlitchX. All rights reserved.**

---

## ⚠️ Read this before you trust anything

**No part of this project has ever been compiled.**

The reference sandbox this code was written in has:

- no JDK (`javac`),
- no Gradle,
- and network access limited to `github.com` — `repo.papermc.io`, Maven Central, Gradle distributions and JDK downloads are all unreachable.

The Paper **dev bundle** is a hard requirement of `paperweight-userdev`, and it could not be fetched. So:

| Status | Meaning |
| --- | --- |
| ✅ **Verified** | All 33 Java files parse as syntactically valid Java (checked with the `javalang` parser). Zero package/directory mismatches. Zero self-recursive methods. |
| ❌ **NOT verified** | Compilation, type-checking, NMS signatures, Gradle resolution, and every runtime behaviour. |

Per spec §1.5 — *"Never fake success"* — this document states plainly what has and has not been proven. **Treat the NMS adapter as a hypothesis** until you run the checks in [Verification](#verification) below.

---

## Prerequisites

| Requirement | Why |
| --- | --- |
| **JDK 21+** | Required by every 1.21.x Paper build (verified via PaperMC Fill API; Java 21 is the minimum). |
| **Gradle 8.x** | `paperweight-userdev` is the only supported NMS path on Paper, and it is a Gradle plugin. Maven is explicitly unsupported for NMS. |
| **Network access to `repo.papermc.io` + Maven Central** | Fetches the dev bundle (~100 MB+) and dependencies. |
| **A Paper 1.21.11 server** | To run the runtime verification checks. |

---

## Build

```bash
git clone https://github.com/redglitchx001-dev/NullArmy.git
cd NullArmy
git checkout arena/01a10183-nullarmy

# Full build
./gradlew build

# Or, without a wrapper:
gradle build
```

Expected outputs:

| Module | Artifact |
| --- | --- |
| `core` | `core/build/libs/nullarmy-core-<version>.jar` |
| `nms/v1_21_11` | `nms/v1_21_11/build/libs/…` |
| `plugin` | `plugin/build/libs/NullArmy-<version>.jar` |

If you have no wrapper yet, generate one:

```bash
gradle wrapper --gradle-version 8.14
```

---

## Run the core tests

`core` has **zero dependencies** by design (ADR-004), so its tests run with nothing but a JRE:

```bash
# Build test classes
gradle :core:testClasses

# Run the suite (no JUnit needed)
java -cp core/build/classes/java/main:core/build/classes/java/test \
     redglitchx.nullarmy.core.CoreTestSuite
```

Exit code `0` = all tests passed. The suite prints `passed: N  failed: 0`.

Tests currently cover:

| Test | Acceptance criterion |
| --- | --- |
| Ledger insert/remove conserves items | §1.2 item accounting |
| Ledger transfer is atomic | **AC-7** (accounting across save/restart) |
| Ledger survives restart without duplication | **AC-17** (no dupes on restart) |
| Boids separation prevents overlap | **AC-8** (no clipping/stacking) |
| Pathfinder is bounded by node budget | §5, §9 |
| Pathfinder never cuts solid corners | §5 |
| JSON rejects malformed / oversized input | §7.4 strict validation |
| Block plan rejects oversize, dupes, shortages, unsupported blocks | §4 |
| Circuit breaker opens / half-opens / closes | §7.6, **AC-16** |
| Planner prefers survival; is deterministic | §5 |
| Portal effects honour the spec floor of 15 | §3 |

---

## Verification

These are the open items from `IMPLEMENTATION_PLAN.md`. **Do not consider Phase 1 done until all five pass.**

| # | Item | How to check |
| --- | --- | --- |
| **V-01** | Fill the blank Spigot NMS revision cells (1.21.3 – 1.21.11) | Spigot BuildTools / SpigotMC NMS version wiki |
| **V-02** | **Mojang-mapped vs reobfuscated artifact** — Paper 1.21.11 build 17060+ removed runtime plugin remapping, so a `reobfJar` artifact may not load | Load the plugin on a 1.21.11 build **≥17060** *and* on an older 1.21.x build. See A-14. |
| **V-03** | Confirm no `playerdata` file is written for a spawned Null | Spawn one, then `ls world/playerdata/` — no new `<uuid>.dat` should appear (risk R-01) |
| **V-04** | Confirm the packet sequence renders a Null | Spawn one and confirm it is visible to a real client |
| **V-05** | Confirm dev-bundle coordinates resolve | The build must complete — this is the first gate |

### Pin these before release

`nms/v1_21_11/build.gradle.kts` currently uses:

```
id("io.papermc.paperweight.userdev")
paperweight.paperDevBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-SNAPSHOT")
```

**These coordinates are unverified.** Replace them with the real published pair for your target build, and record what you used in `IMPLEMENTATION_PLAN.md`.

---

## Runtime smoke test

```bash
# 1. Drop the jar into plugins/ and start the server.
#    Expect:  "NullArmy enabled on 1.21.11 using adapter 1.21.11"
#    If unsupported you should instead see a clear failure (spec: fail clearly).

# 2. Rename a Goat Horn to exactly "Call Horn" (anvil), hold it, right-click.
#    Expect:  "How many Nulls do you want to summon?"

# 3. Type 3 in chat.
#    Expect:  "Summoned 3 Nulls."
#             >= 15 portal particle effects
#             3 Nulls walk out - they must NOT appear at the same coordinates
#             and must NOT be inside blocks

# 4. Check for playerdata pollution (V-03):
ls world/playerdata/

# 5. Diagnostics:
/null status
```

---

## Known-incomplete areas

These are **not** bugs — they are phases that have not been written yet, and they are reported honestly rather than stubbed silently:

| Area | Phase | Current behaviour |
| --- | --- | --- |
| Loadout GUI (`/null gui`) | 3 | Prints "not implemented yet" |
| Building (`/null build`) | 7 | Prints "not implemented yet" |
| Terrain danger costs (lava, fire, cactus…) | 4 | `BlockView.extraCost()` returns 0 — one `TODO(phase-4)` |
| Combat, formations, perception wiring | 4–5 | Not started |
| AI endpoint clients | 8 | Not started |
| Persistence of squads/inventories | 2 | `ItemLedger.restore()` exists and is tested, but no file IO yet |

See [`TRACEABILITY.md`](TRACEABILITY.md) for the status of all 471 mechanics and [`STATUS.md`](STATUS.md) for phase state.
