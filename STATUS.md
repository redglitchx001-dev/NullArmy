# NullArmy — Status

**Last updated:** 2026-10-03 · **Current phase:** Phase 0 — COMPLETE · **Gate:** awaiting `CONTINUE`

Companion documents: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (audit + architecture) · [`TRACEABILITY.md`](TRACEABILITY.md) (221-item register)

---

## Phase state

| Phase | Focus | Status |
| --- | :---: | --- |
| **0** | Repository & feasibility audit | ✅ **COMPLETE** |
| 1 | Build skeleton & version adapters | ⬜ Not started — **blocked** (see below) |
| 2 | Authoritative NPC identity & lifecycle | ⬜ Not started |
| 3 | Commands, summoning & visuals | ⬜ Not started |
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
| 30 | Circuit breaker + deterministic fallback | 7 | `not started` | 8 |
| 31 | Griefing/explosives **off by default** | 8 | `not started` | 7 |
| 32 | `/schematics` parser | 4 | `not started` | 7 |
| 33 | Debug rejection-reason reporting | 9 | `not started` | 1 |

**0 of 33 core features implemented. 0 of 221 catalogue mechanics implemented.**

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
| `TRACEABILITY.md` | **new** — all 221 mechanics, generated from spec |
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
33 core features + 221 mechanics, **all `not started`**. Statuses move only when implemented and tested on a declared target version.

### (f) Next phase
**Phase 1 — Build skeleton and version adapters**, gated on owner decisions **D-1** (version range) and **D-2** (build environment).

Per §11, work stops here. Awaiting `CONTINUE`.
