# NullArmy — Status

**Last updated:** 2026-10-03 · **Current state:** Phase 0 complete · Phase 1 source authored **but UNVERIFIED** (blocked B-1) · Partial Phase 2/3 source authored, also unverified

Companion documents: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (audit + architecture) · [`TRACEABILITY.md`](TRACEABILITY.md) (471-item register) · [`MECHANICS_EXPANSION.md`](MECHANICS_EXPANSION.md) (250 added mechanics) · [`BUILD.md`](BUILD.md) (build & verify commands)

---

## Phase state

| Phase | Focus | Status |
| --- | :---: | --- |
| **0** | Repository & feasibility audit | ✅ **COMPLETE** |
| 1 | Build skeleton & version adapters | 🟠 **Source authored — UNVERIFIED** (blocked B-1) |
| 2 | Authoritative NPC identity & lifecycle | 🟠 Partial source authored — UNVERIFIED |
| 3 | Commands, summoning & visuals | 🟠 Partial source authored — UNVERIFIED |
| 4 | Perception, movement, collision & formations | ⬜ Not started |
| 5 | Survival inventory & combat | ⬜ Not started |
| 6 | Mobility extensions | ⬜ Not started |
| 7 | Builder, mining, redstone & destructive systems | ⬜ Not started |
| 8 | External AI agents | ⬜ Not started |
| 9 | Performance, compatibility & release | ⬜ Not started |

---

## 🔴 Blockers

### B-1 — Build environment unavailable
No JDK, no Gradle, and network access limited to `github.com`. `repo.papermc.io` (Paper dev bundle), Maven Central, Gradle distributions, and JDK downloads are all unreachable. **No Phase can be compiled or verified in this sandbox.** → Owner decision **D-2** in the plan.

> **What this does and does not mean for the code that now exists.**
> 33 Java files (4,423 lines) have been authored across all four modules. They are
> **syntactically valid** (verified with the `javalang` Java parser: 33/33 parse, 0
> package/directory mismatches, 0 self-recursive methods). They have **never been
> compiled**, so type-checking, NMS signatures and every runtime behaviour remain
> unproven. Every NMS class, method and constructor in `nms/v1_21_11` is a
> **hypothesis**, and is labelled `STATUS: UNVERIFIED` in its Javadoc.
> See [`BUILD.md`](BUILD.md) for the exact commands to prove or disprove it.

### B-2 — Target version range is end-of-life
Paper 1.21.11 support ended **2026-06-15**; 1.21.10 ended **2026-01-17**. Every version from 1.21 → 1.21.11 is `UNSUPPORTED`. Current MC release is 26.3 (needs Java 25). → Owner decision **D-1** in the plan.

---

## Core feature traceability

Status vocabulary per spec: `implemented` · `partial` · `experimental` · `blocked by vanilla` · `version-specific` · `not started`

| # | Feature | Spec § | Status | Phase |
| ---: | --- | :---: | :---: | :---: |
| 1 | `Call Horn` (Goat Horn) summon trigger | 3 | `not started` | 3 |
| 2 | `Totem Of Null` (Totem of Undying) trigger | 3 | `not started` | 3 |
| 3 | Chat count request, owner binding, timeout, cancel | 3 | `not started` | 3 |
| 4 | ≥15 portal visual effects per summon | 3 | `not started` | 3 |
| 5 | Collision-safe physical emergence (no teleport) | 3 | `not started` | 3 |
| 6 | Pure black skin | 3 | `not started` ⚠️ | 2 |
| 7 | Unique random alphanumeric profile names | 3 | `not started` | 2 |
| 8 | Exactly 2 commanders + stable succession | 3 | `not started` | 2 |
| 9 | Authoritative `ServerPlayer` entity | 2, 5 | `not started` | 2 |
| 10 | Item ledger + conservation invariant | 1.2 | `not started` | 5 |
| 11 | `/null gui` (planning blueprint, no duplication) | 4 | `not started` | 3 |
| 12 | `/null chat [on\|off]` | 4 | `not started` | 3 |
| 13 | `/null attack <player>` | 4 | `not started` | 5 |
| 14 | `/null attackx <player>` | 4 | `not started` | 5 |
| 15 | `/null follow me` | 4 | `not started` | 4 |
| 16 | `/null build a <structure>` | 4 | `not started` | 7 |
| 17 | `/null ban <player>` (moderation-gated, audited) | 4 | `not started` | 3 |
| 18 | `/null kill <player>` (lethal-combat objective only) | 4 | `not started` | 5 |
| 19 | `/null status` diagnostics | 9 | `not started` | 1 |
| 20 | `/null stop` emergency kill switch | 8 | `not started` | 1 |
| 21 | Formations: line, encircle, square, turtle | 4 | `not started` | 4 |
| 22 | Boids separation / no clumping | 1.3 | `not started` | 4 |
| 23 | Fair perception (LOS only, no x-ray) | 5 | `not started` | 4 |
| 24 | Incremental bounded A* | 5 | `not started` | 4 |
| 25 | Persistence (restart-safe, no item duplication) | 2.6 | `not started` | 2 |
| 26 | ChatCommander | 7 | `not started` | 8 |
| 27 | CombatTactician | 7 | `not started` | 8 |
| 28 | BuilderAgent | 7 | `not started` | 8 |
| 29 | PathfinderCore | 7 | `not started` | 8 |
| 29a | ScoutObserver | 7 | `not started` | 8 |
| 29b | ThreatAnalyst | 7 | `not started` | 8 |
| 29c | LogisticsQuartermaster | 7 | `not started` | 8 |
| 29d | MedicTriage | 7 | `not started` | 8 |
| 29e | FormationTactician | 7 | `not started` | 8 |
| 29f | RedstoneAnalyst | 7 | `not started` | 8 |
| 29g | MiningForeman | 7 | `not started` | 8 |
| 29h | IdleBehaviourDirector | 7 | `not started` | 8 |
| 29i | GuardianAuditor | 7 | `not started` | 8 |
| 30 | Circuit breaker + deterministic fallback | 7 | `not started` | 8 |
| 31 | Griefing/explosives **off by default** | 8 | `not started` | 7 |
| 32 | `/schematics` parser | 4 | `not started` | 7 |
| 33 | Debug rejection-reason reporting | 9 | `not started` | 1 |

**0 of 42 core features implemented. 0 of 471 catalogue mechanics implemented.**

> **Why nothing is marked `implemented` yet.** The spec defines `implemented` as
> *"complete and tested on a declared target version"*. Source now exists for a
> number of these, but **none has been compiled or executed**, so none qualifies.
> Unverified source has been written for: **1, 2, 3, 4, 5** (summon triggers, chat
> count flow, portal effects), **7** (name generation), **8** (commander
> assignment + succession), **9** (`NullPlayer` entity), **10** (`ItemLedger`),
> **11, 12, 13, 14, 15, 17, 18, 19, 20** (command tree), **21** (spawn ring
> spacing), **22** (`BoidsSolver`, `SpatialHash`), **24** (`Pathfinder`).
> Items **6** (skin), **16** and **21** (formations) are *not* functional yet in
> any sense — the skin needs a real Mojang texture (A-05) and formations need
> Phase 4.

---

## Acceptance criteria (§10)

| # | Criterion | Status |
| ---: | --- | :---: |
| 1 | Each declared version compiles and starts; unsupported builds fail clearly | ⬜ |
| 2 | Summoning validates item, permission, count, safe positions, cap | ⬜ |
| 3 | ≥15 visual effects, never more Nulls than requested | ⬜ |
| 4 | Two commanders stable for every squad ≥2 | ⬜ |
| 5 | Skin/name/profile limits + collision-safe spawns | ⬜ |
| 6 | `/null gui` never duplicates; deficits visible | ⬜ |
| 7 | Item accounting correct across save/restart | ⬜ |
| 8 | No teleport / clip / phase / illegal accel / chunk force-load | ⬜ |
| 9 | Formations hold at doors, stairs, bridges, boats, crowds | ⬜ |
| 10 | Combat respects cooldown, LOS, shields, ammo, durability, allies | ⬜ |
| 11 | Clutches need a real item; failed clutches have consequences | ⬜ |
| 12 | Elytra consumes real rockets; Wind Charges ≠ flight | ⬜ |
| 13 | TNT doesn't break obsidian; bedrock never bypassed | ⬜ |
| 14 | Wither skulls never thrown; Wither off by default | ⬜ |
| 15 | Redstone reasoning uses only visible info | ⬜ |
| 16 | Endpoint failures leave the server responsive | ⬜ |
| 17 | Restart/unload/disconnect/death never duplicate items | ⬜ |
| 18 | Load test meets published budget **with measurements** | ⬜ |

**0 of 18 passing.**

---

## Phase 0 handoff

Per §11 *"Required handoff at every phase"* — all six items:

### (a) Files changed
| File | Change |
| --- | --- |
| `IMPLEMENTATION_PLAN.md` | **new** — audit, version table, NMS feasibility, architecture, ADRs, impossibilities, assumptions, risk register |
| `STATUS.md` | **new** — this file |
| `TRACEABILITY.md` | **new** — all 471 mechanics (221 original + 250 added), generated from spec |
| `README.md` | updated — links to the three new docs |

### (b) Behaviour now working
**None.** Phase 0 is audit and planning only; it produces no runtime behaviour by design.

### (c) Exact build/test results
```
$ java -version        → not found
$ gradle -version      → not found
$ mvn -version         → not found
$ curl repo.papermc.io → 000 (blocked)
$ curl repo1.maven.org → 000 (blocked)
```
**No compilation was attempted and none succeeded.** Zero build artefacts exist. Reported as zero rather than implied.

### (d) Known limitations
- The whole 1.21.x range is end-of-life (B-2).
- Cannot build or test here (B-1).
- `ServerPlayer`-as-NPC carries unproven side-effect risks R-01…R-04 (playerdata writes, phantom join events, anti-cheat detection).
- Mojang-mapped vs reobfuscated artifact choice is unproven on 1.21.11 (V-02).
- Spigot NMS revision cells for 1.21.3–1.21.11 unverified — left blank rather than guessed.

### (e) Remaining traceability items
42 core features + 471 mechanics, **all `not started`**. Statuses move only when implemented and tested on a declared target version.

### (f) Next phase
**Phase 1 — Build skeleton and version adapters**, gated on owner decisions **D-1** (version range) and **D-2** (build environment).

Per §11, work stops here. Awaiting `CONTINUE`.
