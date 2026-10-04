# NullArmy — Status

**Last updated:** 2026-10-04 · **Current state:** Phase 0 complete · Phase 1/2/3 source authored · **crash-safety, summoning, menu, cannon and airdrop work in flight on `arena/01a10643-nullarmy`** (see the delta below)

Companion documents: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (audit + architecture) · [`TRACEABILITY.md`](TRACEABILITY.md) (471-item register) · [`MECHANICS_EXPANSION.md`](MECHANICS_EXPANSION.md) (250 added mechanics) · [`BUILD.md`](BUILD.md) (build & verify commands)

---

## Delta on `arena/01a10643-nullarmy` (the P0–P4 pass)

This is implementation status, not live-server proof. GitHub Actions now passes a clean
Gradle/Paperweight build and distributable-JAR verification for this branch; the current sandbox
still has no JDK. A Paper 1.21.11 live smoke test remains required before release.

| Area | Change |
| --- | --- |
| **P0 — crashes** | Every entry point is wrapped: `onEnable`, `onDisable`, commands, tab completion, every listener handler and every per-tick subsystem. Per-tick subsystems also have a failure budget: after 100 failures one subsystem switches itself off for the session instead of spamming the log. Chat uses a purple-to-cyan gradient `[NullArmy]` prefix and includes the reason. |
| **P0 — the NMS body** | `NullPlayer.tick()` is wrapped; three consecutive tick failures retire the body (with a stack trace) instead of letting it keep throwing inside the server's entity loop. In response to the reported `MinecraftServer.tickChildren` NPE (`entityplayer.connection == null`), every Null now receives a `ServerGamePacketListenerImpl` that drops outbound sends before world registration: server packet broadcasts have a non-null target and do not queue packets for a client that does not exist. The NMS spawn path remains behind a latched breaker that trips once and then stays off until `/null reload` or a restart. **Needs Paper 1.21.11 live smoke test.** |
| **P0 — data folder** | `ConfigBootstrap` creates `plugins/NullArmy/`, writes the shipped `config.yml` only when absent (never overwrites), falls back to a written starter config if the jar lost the resource, and reloads. The Gradle `jar` task names the resources explicitly (`from(sourceSets.main.resources)`) so both files are packaged, and the workflow's own verification step asserts `plugin.yml` is inside the artifact; the local `check.sh` harness compiles `core`, `nms/api` and `plugin` against Bukkit stubs and runs 48 core tests. |
| **P1 — summoning** | `/null horn` and `/null totem` hand over the real Call Horn / Totem Of Null: both are named `Null`, enchanted (Unbreaking I) with `HIDE_ENCHANTS` for the glint, and tagged with persistent data. The Goat Horn is set to the vanilla `Call` instrument; right-click plays its matching sound, then opens the count prompt. Counts are capped, requests time out and support `cancel`. |
| **P2 — menu** | `/null menu` (`/null m`, `/null gui`) opens a themed 54-slot command center with a purple-to-cyan gradient title, live squad/system cards, permission-filtered actions and pagination. It has its own `InventoryHolder`, cancels every click and drag, and dispatches each action through the same `/null …` command executor path. Nothing in it can be taken. |
| **P3 — spectacle (opt-in)** | `/null withercannon` (`/null cannon`) and `/null airdrop [count]` exist behind their own config blocks, the `policy.*` switches and a permission. Block damage needs a separate `blocks-damage` opt-in; without it the registered explosion handler empties each blast's block list. Every created entity is tracked so `/null stop`, `/null dismiss` and `onDisable` clean up. |
| **P4 — commands** | The full tree is implemented with permission checks, usage lines and tab completion: `menu gui help status version features debug horn totem commander respawn loadout skin follow guard formation attack attackx come tp bring stop dismiss list info name heal equip drop portals clearskins reload wand build chat withercannon cannon airdrop ban kill`. Features that are not implemented say so and change nothing. |
| **P5 — chat is an interface** | Wake-word orders in ordinary chat (`null attack Steve`, `null kill Steve`, `null eliminate Steve`, `null come`, `null stop`, `null heal` …) are stripped out of public chat and dispatched through the same `NullCommand` executor, so permissions, caps and policy gates are identical; per-player rate limit of 20 orders a minute; a player answering a summon prompt is never interrupted. `/null chat <null\|commander>` opens a private channel and `ChatBrain` drives the existing `ai.endpoints`/`ai.agents` config over the JDK HTTP client, off-thread, with the reply delivered on the main thread - and with no model configured the characters answer locally and `/null ai` says why. |
| **P5 — movement and behaviour** | `/null portal [player]` walks Nulls through a visible portal (effects at both ends, verified arrival, opt-in via `mechanics.portal-travel`); `/null tactics` changes the real standoff (1.2/2.0/4.5 blocks); `/null emote`, `/null greet` and `/null inv` add body language and honesty; Nulls glance around and turn to face their owner on their own (`mechanics.idle-gestures`). |

**Still unverified:** This checkout has no JDK, but GitHub Actions now compiles and packages the
branch and passes the core checks. A Paper 1.21.11 live spawn/tick/packet-broadcast smoke test is
still pending before release. Adapter runtime behaviour, AI HTTP responses and portal arrival need
a live server to verify. The earlier NMS additions include `SpawnRequest.airborne()` +
`isAirborneSpawnSafe(...)`, `NullBody.heal(double)`/`loadout()`, and the tick guard.

---

## Phase state

| Phase | Focus | Status |
| --- | :---: | --- |
| **0** | Repository & feasibility audit | ✅ **COMPLETE** |
| 1 | Build skeleton & version adapters | 🟠 **CI-compiled; live runtime unverified** (smoke test pending) |
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

### B-1 — Build environment unavailable (partially mitigated)
No JDK or Gradle in this sandbox, and direct access to `repo.papermc.io` (Paper dev bundle), Maven Central, Gradle distributions and JDK downloads is unavailable. **A full build cannot run locally here.** GitHub Actions can resolve the Paper dev bundle and is now the only full compile; a green CI build still does not verify runtime behaviour. → Owner decision **D-2** in the plan.

> **Partial mitigation (2026-10-04).** A local type-check now exists: the Eclipse batch
> compiler (ECJ, from the VS Code Java language server bundle) running on a bundled JRE,
> with `core`, `nms:api` and `plugin` compiled against checked-in API stubs for the
> Bukkit/Adventure surface they use. This **type-checks the whole plugin module** and
> caught real defects (an ambiguous `Guard.attempt` overload, two operator-precedence
> bugs, missing accessors). It cannot see `nms/v1_21_11`, which needs the real Paper dev
> bundle, so **CI remains the only full build** and the live smoke test the only proof of
> runtime behaviour.

> **What this does and does not mean for the code that now exists.**
> The hosted GitHub Actions build has compiled the full plugin, including
> `nms/v1_21_11`, against Paper 1.21.11 and verified the distributable JAR. This
> validates the referenced compile-time NMS signatures; it does **not** prove
> that a real server can spawn and tick a Null without errors. Both NMS adapter
> Javadocs now say `COMPILED; RUNTIME UNVERIFIED`. A live smoke test is still
> required before release. See [`BUILD.md`](BUILD.md) for the build commands.

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
| 43 | Commander: /null commander portal spawn | 3 | `not started` | 8 |
| 44 | Commander: loadout GUI + persistence | 3 | `not started` | 8 |
| 45 | Commander: shared skin resolution (cached/bundled/network) | 3 | `not started` | 8 |
| 46 | Commander: mace PvP technique library (12) | 3 | `not started` | 8 |
| 47 | Commander: elytra PvP technique library (11) | 3 | `not started` | 8 |
| 30 | Circuit breaker + deterministic fallback | 7 | `not started` | 8 |
| 31 | Griefing/explosives **off by default** | 8 | `not started` | 7 |
| 32 | `/schematics` parser | 4 | `not started` | 7 |
| 33 | Debug rejection-reason reporting | 9 | `not started` | 1 |

**0 of 47 core features implemented. 0 of 471 catalogue mechanics implemented.**

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
47 core features + 471 mechanics, **all `not started`**. Statuses move only when implemented and tested on a declared target version.

### (f) Next phase
**Phase 1 — Build skeleton and version adapters**, gated on owner decisions **D-1** (version range) and **D-2** (build environment).

Per §11, work stops here. Awaiting `CONTINUE`.
