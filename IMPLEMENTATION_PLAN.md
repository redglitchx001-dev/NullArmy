# NullArmy — Implementation Plan

**Phase 0 deliverable · Repository & feasibility audit**
**Date:** 2026-10-03 · **Status:** Phase 0 complete — awaiting `CONTINUE`
**Source spec:** [`NullArmy_Master_Prompt.md`](NullArmy_Master_Prompt.md)

> Produced per §11 Phase 0: *"Inspect repository/build/server target and current files. Produce an architecture diagram in text, module/file plan, exact version compatibility table, NMS/packet feasibility notes, and list of vanilla impossibilities/approximations. Resolve all ambiguous commands and config defaults in the documented assumptions. Do not hide a blocker behind a stub."*

---

## 0. Executive summary — read this first

Phase 0 is complete. Three findings dominate and **two require an owner decision before any code is written.**

| # | Finding | Severity | Action |
| --- | :---: | --- | --- |
| **F-1** | **The entire declared target range is end-of-life.** Paper 1.21.11 support ended 2026-06-15; 1.21.10 ended 2026-01-17. Every version from 1.21 → 1.21.11 is `UNSUPPORTED`. Current MC release is 26.3. | 🔴 Blocker | **Owner decision D-1** |
| **F-2** | **This sandbox cannot build the project.** No JDK, no Gradle, and only `github.com` is reachable — Maven Central, `repo.papermc.io`, Gradle distributions, and JDK downloads are all blocked. Paper dev bundles are unobtainable. | 🔴 Blocker | **Owner decision D-2** |
| **F-3** | **The spec's "Spigot/Paper" target is effectively Paper-only.** `paperweight-userdev` is the only supported NMS path, and Paper staff state Maven+NMS is unsupported. Reobfuscation is a Paper-tooling concept. | 🟠 Deviation | Documented A-03 |

Everything else in this document is feasible and specified well enough to proceed.

---

## 1. Repository & environment audit

### 1.1 Current repository state

| Item | Finding |
| --- | --- |
| Files tracked in Git | 2 (`NullArmy_Master_Prompt.md`, `README.md`) |
| Total commit history | 2 commits |
| Source code | **None** |
| Build system | **None** — not declared anywhere in the spec |
| Java target | **None declared** |
| Architecture | **None** — nothing to audit |
| Test infrastructure | **None** |
| Blockers pre-existing | No code, no build, no toolchain |

Per §"YOUR ROLE AND DELIVERABLE" — *"If there is no repository, propose a compact project structure"* — a structure is proposed in §4.

### 1.2 Toolchain availability (measured, not assumed)

| Tool | Status | Evidence |
| --- | :---: | --- |
| `java` / `javac` | ❌ Not installed | `command -v java` → not found; no `/usr/lib/jvm` |
| `gradle` | ❌ Not installed | not found |
| `mvn` | ❌ Not installed | not found |
| `git` | ✅ Available | in use |
| Disk free | 20 GB | sufficient |
| Memory | 3.9 GB total | adequate for Gradle |

### 1.3 Network reachability (measured)

| Endpoint | Needed for | Result |
| --- | --- | :---: |
| `github.com` | source, docs | ✅ `200` |
| `codeload.github.com` | git archives | ✅ `200` |
| `repo1.maven.org` | paper-api, JUnit, all deps | ❌ `000` blocked |
| `repo.papermc.io` | **Paper dev bundle (NMS)** | ❌ `000` blocked |
| `services.gradle.org` | Gradle distribution | ❌ `000` blocked |
| `api.adoptium.net` | JDK download | ❌ `000` blocked |
| `download.java.net` | JDK download | ❌ `000` blocked |
| `piston-data.mojang.com` | vanilla jar / version manifest | ❌ `000` blocked |

**Conclusion (F-2):** Phase 1 cannot be compiled or verified in this environment. The Paper dev bundle is a hard requirement of `paperweight-userdev` and cannot be fetched. Source files can still be *authored* here, but **no compile/test result can be honestly reported from this sandbox** — and the spec forbids claiming a mechanism works until it is built and tested.

---

## 2. Version compatibility table (exact)

Source of truth: PaperMC Fill API v3 (`fill.papermc.io/v3/projects/paper`) and the Minecraft Wiki version history. **Nothing here is inferred from memory.**

| MC version | Released | Java | Paper support status | Support ended | Paper builds | Spigot NMS rev | NullArmy adapter |
| --- | --- | --- | --- | ---: | --- | :---: | :---: |
| 1.21 | 2024-06-13 | 21 | 🔴 UNSUPPORTED | — | many | `1_21_R1` ✅ | `v1_21` |
| 1.21.1 | 2024-08-08 | 21 | 🔴 UNSUPPORTED | — | 133 | `1_21_R1` ✅ | `v1_21_1` |
| 1.21.2 | 2024-10-22 | 21 | 🔴 UNSUPPORTED | — | many | `1_21_R2` ✅ | `v1_21_2` |
| 1.21.3 | 2024-10-23 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_3` |
| 1.21.4 | 2024-12-03 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_4` |
| 1.21.5 | 2025-03-25 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_5` |
| 1.21.6 | 2025-06-17 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_6` |
| 1.21.7 | 2025-06-30 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_7` |
| 1.21.8 | 2025-07-17 | 21 | 🔴 UNSUPPORTED | 2025-11-14 | 60 | ⬜ verify P1 | `v1_21_8` |
| 1.21.9 | 2025-09-30 | 21 | 🔴 UNSUPPORTED | — | many | ⬜ verify P1 | `v1_21_9` |
| 1.21.10 | 2025-10-07 | 21 | 🔴 UNSUPPORTED | 2026-01-17 | 130 | ⬜ verify P1 | `v1_21_10` |
| 1.21.11 | 2025-12-09 | 21 | 🔴 UNSUPPORTED | 2026-06-15 | 132 | ⬜ verify P1 | `v1_21_11` |

**Verified directly from `fill.papermc.io`:** 1.21.1, 1.21.8, 1.21.10, 1.21.11 (support status, end date, Java minimum, build list). Java minimum **21** confirmed for every 1.21.x queried.

Cells marked ⬜ were **not** verifiable and are deliberately left blank rather than guessed. The spec demands an *exact* table; an invented cell is worse than an empty one. They are Phase 1 verification items (§9, V-01).

### 2.1 The end-of-life finding (F-1)

The spec targets **1.21.x → 1.21.11** and says *"verify each exact server build before claiming support."* Verification produces an uncomfortable result:

- **1.21.11 — the newest version in the declared range — left Paper support on 2026-06-15**, nearly four months before today's date.
- The 1.21 line is **closed**. Mojang renumbered in 2026: there is no 1.22; the 1.21 line ended at 1.21.11. Current release is **26.3** (2026-09-15), requiring **Java 25**.
- **Every version in the declared range is `UNSUPPORTED`.**

**This needs an owner decision (D-1)** — see §8.

---

## 3. NMS / packet feasibility notes

### 3.1 The only supported NMS path is `paperweight-userdev`

Paper is explicit: *"Userdev is the only supported way of working with NMS in 1.18+. The obfuscated jar is no longer valid to compile against."* Paper staff additionally state *"We don't support using maven with nms internals."*

Implication: **Gradle is mandatory, not a preference** (Assumption A-01). This resolves the spec's silence on build tooling.

### 3.2 Runtime mapping landscape — and why it changed recently

| Era | Paper runtime | CraftBukkit relocated? | Plugin artifact |
| --- | --- | --- | --- |
| ≤ 1.20.4 | Spigot-mapped (obfuscated) | Yes (`v1_20_R3`) | reobf required |
| **1.20.5 – 1.21.11** | **Mojang-mapped** | **No — relocation dropped** | reobf *available*; mojmap loads directly |
| ≥ 26.1 | Mojang-mapped, **no obfuscation at all** | No | reobf **impossible** — Spigot mappings no longer exist |

Two consequences that directly shape the architecture:

1. **Adapters are named by MC version, not by `R`-revision.** Since 1.20.5 CraftBukkit is no longer relocated into `v1_21_R1`-style packages, so the spec's implied adapter naming no longer maps to reality. Use `v1_21_11`, etc. (Assumption A-13).
2. **NMS class/method names are now stable Mojang names across the whole 1.21 line.** This substantially *thins* the version-adapter layer the spec anticipated. Adapters are still required, but for **behavioural and signature drift** rather than wholesale renaming.

### 3.3 ⚠️ Critical: Paper 1.21.11 removed runtime plugin remapping

Paper 1.21.11 **build 17060+ removed the runtime reflection remapping** that translated Spigot-mapped plugins to the Mojang-mapped runtime. This is a live, breaking change — it is what broke ProtocolLib (`NoSuchMethodError: CraftAttribute.minecraftToBukkit`).

**Consequence for NullArmy:** a `reobfJar` (Spigot-mapped) artifact **will not work on recent 1.21.11 builds**, even though the official docs still describe reobfuscation "up to 1.21.11". The safe artifact for the whole 1.21.x range is **Mojang-mapped**, declared as a Paper plugin (`paper-plugin.yml`) so the loader assumes the Mojang namespace.

> **Assumption A-14** — ship Mojang-mapped. This *contradicts* the literal reading of the Paper docs; it is the empirically safer choice given the remapping removal. **Must be verified against a real server in Phase 1** (V-02).

### 3.4 Creating the Null entity

Evidence gathered on spawning player entities under `paperweight` on 1.21.x:

```java
GameProfile profile = new GameProfile(UUID.randomUUID(), name);   // + "textures" Property for skin
ServerPlayer npc    = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
// present to viewers:
conn.send(new ClientboundPlayerInfoUpdatePacket(Action.ADD_PLAYER, npc));
conn.send(new ClientboundAddEntityPacket(npc, null));             // ClientboundAddPlayerPacket no longer exists in 1.21.4+
conn.send(new ClientboundSetEntityDataPacket(npc.getId(), null));
```

**Packet-shape drift is real and this is why adapters stay.** `ClientboundAddPlayerPacket` was removed by 1.21.4; `ClientInformation` has changed constructor shape across patches. The spec's warning — *"Do not assume one NMS package, mapping set, packet shape, or constructor works across every 1.21 patch version"* — is confirmed correct, just for different reasons than obfuscation.

**Trap to avoid:** `server.getPlayerList().placeNewPlayer(...)` appears in community examples, but it drives full player-join semantics. For a headless Null it must **not** be used — see risk R-03.

### 3.5 Feasibility verdict per subsystem

| Subsystem | Verdict | Notes |
| --- | :---: | --- |
| Authoritative player-like entity | 🟢 Feasible | `ServerPlayer` + manual packet presentation |
| Pure black skin | 🟡 Conditional | Requires a **real Mojang-hosted texture + signature**. Cannot be fabricated. See A-05. |
| Custom movement controller | 🟢 Feasible | Required anyway — `ServerPlayer` has no pathfinder goals |
| Combat via real vanilla pipeline | 🟢 Feasible | `ServerPlayer` participates natively |
| Authoritative inventory | 🟢 Feasible | Native `Inventory`/`Container` |
| Physical block placement | 🟢 Feasible | Real use-item / place packets through server validation |
| Boids + collision separation | 🟢 Feasible | Our own steering; no NMS dependency |
| Incremental A* | 🟢 Feasible | Pure computation on immutable chunk snapshots |
| Schematic parsing | 🟢 Feasible | Bounded JSON + optional vanilla structure NBT |
| AI endpoints | 🟢 Feasible | JDK `HttpClient`, async |
| Multi-version single jar | 🟠 Costly | Per-version module + runtime selector (§4.2) |

---

## 4. Architecture

### 4.1 Runtime architecture (text diagram)

```
                         ┌──────────────────────────────┐
   Player / Call Horn ──▶│  Command + Chat Listener      │  main thread
   Totem Of Null ───────▶│  (permission, validation)     │
                         └───────────────┬──────────────┘
                                         │ intent
                         ┌───────────────▼──────────────┐
                         │   Squad Manager              │  main thread
                         │   (2 commanders, roles,      │
                         │    objectives, lifecycle)    │
                         └───────┬──────────────┬───────┘
                                 │              │
              ┌──────────────────▼──┐   ┌───────▼─────────────────┐
              │  Utility Planner    │   │  Null Runtime (per NPC) │
              │  (deterministic,    │   │  ┌────────────────────┐ │
              │   inspectable FSM)  │   │  │ Perception (LOS)   │ │
              └──────────┬──────────┘   │  │ Navigation  (A*)   │ │
                         │              │  │ Steering    (boids)│ │
         ┌───────────────▼───────────┐  │  │ Inventory + ledger │ │
         │  ACTION VALIDATOR         │◀─┼──┤ Combat / Build     │ │
         │  ← server-authoritative   │  │  └─────────┬──────────┘ │
         │  ← rejects illegal acts   │  └────────────┼────────────┘
         │  ← sits ABOVE all agents  │               │
         └───────────────┬───────────┘               │
                         │ authorised                │
              ┌──────────▼──────────┐      ┌─────────▼──────────┐
              │  Version Adapter    │      │  Packet / Visual   │
              │  (NMS + packets)    │      │  (cosmetic only)   │
              └──────────┬──────────┘      └────────────────────┘
                         │                            ▲
              ┌──────────▼──────────┐                 │ never authoritative
              │  NMS ServerPlayer   │─────────────────┘
              │  (authoritative)    │
              └─────────────────────┘

  ASYNC (never touches world state):
    ┌───────────────────────────────────────────────┐
    │ Agent endpoints (ChatCommander, CombatTactician,
    │ BuilderAgent, PathfinderCore)                 │
    │  • circuit breaker, timeouts, strict schemas  │
    │  • ADVISE ONLY — results revalidated on main  │
    └───────────────────────────────────────────────┘
```

**Invariant to preserve:** the arrow from *any* agent into the Null Runtime **never** bypasses the Action Validator. The validator is the only thing that may authorise a world mutation.

### 4.2 Build topology — multi-module Gradle

NMS must be compiled against a **per-version** dev bundle, so a single module cannot cover 1.21 → 1.21.11.

```
NullArmy/
├── settings.gradle.kts
├── build.gradle.kts                 (root: aggregation + shadow)
├── core/                            ← version-independent, NO NMS. Pure logic.
│   └── src/main/java/redglitch/nullarmy/core/…
│       ├── api/          NullArmyAPI, Squad, NullHandle
│       ├── brain/        UtilityPlanner, states, objectives, priorities
│       ├── nav/          AStar, terrain cost, path slices
│       ├── flock/        Boids, spatial hash, separation
│       ├── ledger/       ItemLedger, conservation checks
│       ├── plan/         BlockPlan, schematic codec, cost accounting
│       ├── agent/        endpoint clients, schemas, circuit breaker
│       ├── config/       typed config model + validation
│       └── util/         math, bounded queues, rate limiter
├── nms/
│   ├── api/              ← VersionAdapter SPI (interfaces only)
│   ├── v1_21/            ← paperweight-userdev, dev-bundle 1.21
│   ├── v1_21_1/ … v1_21_11/
└── plugin/               ← Bukkit Plugin bootstrap, commands, config, persistence
                             runtime adapter selection by detected MC version
```

**Why this shape:** `core` is pure Java with zero NMS, so it is **unit-testable without a server** — the only part of this project that can be tested in a sandbox with no Minecraft dependency. That matters enormously given F-2.

### 4.3 Module → spec-section mapping

| Spec § | Module |
| --- | --- |
| §1.2 item accounting | `core/ledger` |
| §1.3 no clumping | `core/flock` + adapter collision |
| §3 summoning/identity | `plugin` + `nms/*` |
| §4 commands | `plugin` |
| §5 AI/perception/nav | `core/brain`, `core/nav` |
| §6 the 221 | distributed across `core/*` + `nms/*` |
| §7 agents | `core/agent` |
| §9 telemetry | `plugin` + `core/util` |

---

## 5. Architectural decisions (ADRs)

### ADR-001 — Null entity representation

**Options considered**

| | Option | Verdict |
| --- | --- | --- |
| **A** | `ServerPlayer` (NMS) + custom movement controller — **one** entity | ✅ **Chosen** |
| **B** | `ServerPlayer` as visual skin + invisible mob as physics/AI body | ❌ Rejected |
| **C** | Mob entity disguised as a player via packets | ❌ Rejected — violates §1.5 |

**Chosen: A.** A single `ServerPlayer` is the authoritative body.

*Why:* one entity means one inventory, one hitbox, one item ledger — which is exactly what §1.2's "the NPC inventory is authoritative" and §2.2's server-authority rule require. Because `ServerPlayer` has **no pathfinder goals** (it is designed to be driven by a client connection), we must supply movement ourselves — and that is *desirable* here: it makes the no-teleport invariant structural rather than aspirational. There is no code path that can teleport a Null, because we control every movement input.

*Why B was rejected:* two entities means two hitboxes and two damage targets, and syncing the player to the mob's position each tick is position-snapping — precisely what §5 forbids (*"Never snap coordinates or correct a route with teleportation"*).

*Cost of A:* we must implement navigation, step height, fluid handling, and climbing ourselves. That is unavoidable regardless, since §5 mandates *"bounded, incremental voxel-aware path planning"* and vanilla pathfinders would not satisfy the "explainable physics" bar anyway.

*Known hazards (risks R-01…R-04, §9):* suppressing `playerdata` writes, advancements, statistics, `PlayerJoinEvent`, tab-list and scoreboard side effects.

### ADR-002 — Movement: custom controller, no pathfinder delegation

Bounded steering forces → vanilla `move()`/`travel()` physics. Acceleration and velocity clamped per §5. Stuck handling = diagnose → replan → escalate → wait. **Never teleport, never snap.**

### ADR-003 — Agents advise; the validator decides

All four endpoints produce *recommendations* consumed through one chokepoint. A missing or unreachable endpoint degrades to deterministic local behaviour and never blocks a tick (§7, §9).

### ADR-004 — Core is NMS-free and server-free

Maximise the surface testable without a Minecraft server. Given F-2, this is the difference between "some tests can run here" and "no tests can run here."

---

## 6. Vanilla impossibilities & approximations register

Per §1.5 — *"Do not silently simulate impossible mechanics."* Status vocabulary is the spec's own: `blocked by vanilla`, `experimental`, `version-specific`, `not started`.

| # | Requested / implied capability | Status | Nearest honest behaviour |
| --- | :---: | --- | --- |
| 1 | TNT mines obsidian | `blocked by vanilla` | Diamond/netherite pickaxe + real mining time |
| 2 | Throw Wither Skeleton Skull as projectile | `blocked by vanilla` | Placeable block item only; real Wither assembly (off by default) |
| 3 | Wither cannon | `experimental` | Real contraption only; failure reported, never faked |
| 4 | Armor removal → invisibility | `blocked by vanilla` | Reduces glint **and** protection; real potion required for invisibility |
| 5 | Wind Charge → sustained flight | `blocked by vanilla` | One-shot impulse; real Elytra + rockets for gliding |
| 6 | Packet-only fake player in combat/collision/inventory | `blocked by vanilla` | Server-authoritative entity for world effects; packets cosmetic only |
| 7 | Summon portals teleport | `blocked by vanilla` | Particles/sound only; Nulls physically walk out |
| 8 | Potion combining / fusion | `blocked by vanilla` | Legal brewing + tactical sequencing |
| 9 | Java sword blocking (legacy block-hit) | `blocked by vanilla` | Java has no sword blocking; shields only |
| 10 | Bypass bedrock / unbreakables | `blocked by vanilla` | Recognise and stop |
| 11 | X-ray ore scan | `blocked by design` | Visible/audible discovery only |
| 12 | Read hidden redstone through blocks | `blocked by design` | Line-of-sight tracing only |
| 13 | Read opponent inventory | `blocked by design` | Not exposed |
| 14 | Hide name tags / armor via packet hacks | `blocked by design` | Real vanilla stealth effects only |
| 15 | Teleport for stuck recovery | `blocked by design` | Diagnose → replan → escalate |
| 16 | Ender pearl / chorus fruit travel | `blocked by design` | Not permitted |
| 17 | Nether/End portal travel | `blocked by design` | Owner may revisit in a future version |
| 18 | Bed as teleport / free respawn | `blocked by vanilla` | Vanilla bed rules only |
| 19 | Instant schematic paste | `blocked by design` | Block-by-block physical placement |
| 20 | Guaranteed fishing-rod pull | `blocked by vanilla` | Opportunistic disruption only |
| 21 | Guaranteed TNT cannon success | `experimental` | May fail; report honestly |
| 22 | Guaranteed MLG clutch | `experimental` | Failed clutch keeps normal consequences |
| 23 | Fake loot / phantom entities | `blocked by design` | Never |
| 24 | **Pure black skin** | `version-specific` | Needs a real Mojang texture + signature (A-05) |
| 25 | Trapdoor / crawl traversal | `version-specific` | Depends on exact hitbox + movement rules |
| 26 | Mount availability (horse/camel/strider) | `version-specific` | World-dependent; graceful degradation |

---

## 7. Documented assumptions

The spec's Phase 0 requires resolving every ambiguity. These are **decisions taken**, not stubs — but several need owner confirmation (marked ⚠️).

| # | Ambiguity | Resolution | Basis |
| --- | --- | --- | --- |
| **A-01** | Build system never named | **Gradle (Kotlin DSL)** | `paperweight-userdev` requires Gradle; Paper does not support Maven+NMS |
| **A-02** | Java version never stated | **Java 21** toolchain | Verified: every 1.21.x queried reports `java.version.minimum: 21` |
| **A-03** ⚠️ | Spec says "Spigot/Paper" | **Paper-only** | NMS access is Paper-tooling-only; Spigot NMS is unsupported. *Deviation from spec — needs owner sign-off* |
| **A-04** | Bare `Null kill <player>` (no `/`) is not a Bukkit command | **AsyncPlayerChatEvent listener** with configurable trigger prefix | Only way to capture unprefixed chat |
| **A-05** ⚠️ | Skin source unspecified | Config supplies `textures` value **+ signature**; optional Mojang session lookup by UUID | A pure black skin **cannot be fabricated** — it must be a real Mojang-hosted texture. Offline-mode servers are a degraded case |
| **A-06** | Config format | **YAML**, `config.yml` + per-world overrides | Bukkit convention |
| **A-07** | Persistence format | **JSON** under `plugins/NullArmy/data/`; main-thread snapshots, async write-behind | Satisfies §2.6 without blocking ticks |
| **A-08** | Test framework | **JUnit 5** for `core/`; manual Paper checklist for NMS | Only `core/` is testable without a server |
| **A-09** | No concrete cap values | Proposed defaults in §7.1 | Spec lists *what* to cap, never values |
| **A-10** | AI auth keys | Per-role env-var names; never in config/logs | §7.2 |
| **A-11** | `/null gui` mechanics | Bukkit Inventory GUI, **planning-only**, read-only on real items | §4 — blueprint, not duplicator |
| **A-12** | Commander count edge cases | squad ≥2 → **2** commanders; squad = 1 → 1; empty → 0; stable succession | §3 |
| **A-13** | Adapter naming | By MC version (`v1_21_11`), **not** `R`-revision | CB relocation dropped in 1.20.5 (§3.2) |
| **A-14** ⚠️ | Which artifact to ship | **Mojang-mapped** (`paper-plugin.yml`) | 1.21.11 b17060+ removed runtime remapping — see §3.3. *Contradicts literal docs; verify in P1* |
| **A-15** | Folia / regionised scheduling | **Out of scope for v1** | Spec assumes a single main thread |

### 7.1 Proposed default caps (A-09)

| Cap | Proposed default | Cap | Proposed default |
| --- | ---: | --- | ---: |
| Live NPCs (server-wide) | 64 | Path slices per tick | 8 |
| NPCs per squad | 24 | Block inspections per tick | 256 |
| Squads per owner | 2 | Packets per NPC per tick | 32 |
| Summon hard cap (per request) | 24 | Concurrent builders | 4 |
| Concurrent path searches | 4 | AI JSON max bytes | 8 KiB |
| Endpoint calls per minute (per role) | 20 | Schematic max dimensions | 32³ |
| Endpoint timeout | 3 s | Portal effects per summon | 16 (spec floor: 15) |

All must be re-tuned by measurement in Phase 9 — these are starting points, not results.

---

## 8. Owner decisions required

### D-1 — Target version range is entirely end-of-life 🔴

Every version from 1.21 → 1.21.11 is `UNSUPPORTED` by Paper.

| Option | Consequence |
| --- | --- |
| **(a)** Build for 1.21 → 1.21.11 **as specified** | Faithful to the spec. No upstream support or security fixes. 12 adapters. |
| **(b)** Retarget to a **supported** version (26.2/26.3) | Upstream support + **no reobf** (simpler). Java 25, substantial rework, diverges from spec. |
| **(c)** Target **1.21.11 only** (last of the line) | 1 adapter, minimal surface. Abandons the 1.21.x range the spec promises. |

**Recommendation: (c) for the first working build, then (b) once the architecture is proven.** Shipping one adapter proves the design cheaply; the multi-version matrix is a Phase 9 concern either way, and (b) is where the ecosystem is going.

### D-2 — Build environment is unavailable 🔴

This sandbox has no JDK, no Gradle, and no access to `repo.papermc.io`.

| Option | Consequence |
| --- | --- |
| **(a)** Author Phase 1 source here, owner builds/verifies locally | Honest. No compile result from me; handoff includes exact commands for the owner to run. |
| **(b)** Owner grants network/JDK access, I build and verify here | Real compile results. |
| **(c)** Owner supplies a dev environment | Same as (b). |

**Recommendation: (a)** — I author the source and hand over exact verification commands, reporting **zero** compile results rather than implying success.

---

## 9. Risk register

| # | Risk | Severity | Mitigation |
| --- | :---: | :---: | --- |
| **R-01** | `ServerPlayer` writes `playerdata` files to disk | 🟠 High | Suppress player IO; verify no `<uuid>.dat` appears (Acceptance item) |
| **R-02** | Phantom `PlayerJoinEvent` / tab-list / scoreboard pollution | 🟠 High | Never use `placeNewPlayer()`; manual packet presentation only |
| **R-03** | Advancements/statistics side effects | 🟡 Medium | Detach or null-out advancement tracking |
| **R-04** | Anti-cheat / protection plugins flag Nulls as players | 🟠 High | Documented; recommend allowlisting. Fails closed where unknown (§2.7) |
| **R-05** | Mojang-mapped vs reobf artifact mismatch (§3.3) | 🟠 High | Verify on a real 1.21.11 build ≥17060 **and** an older build (V-02) |
| **R-06** | 12 adapters × manual verification = large matrix | 🟡 Medium | Mitigated by D-1(c): one adapter first |
| **R-07** | Black skin unobtainable in the owner's setup | 🟡 Medium | Documented fallback + explicit config error (A-05) |
| **R-08** | Item-ledger desync → duplication | 🔴 Critical | Conservation invariant + restart tests (Acceptance 7, 17) |

### Phase 1 verification items

| # | Item |
| --- | --- |
| **V-01** | Fill the ⬜ Spigot NMS revision cells in §2 from real BuildTools/docs |
| **V-02** | Confirm A-14: does the Mojang-mapped artifact load on 1.21.11 b17060+ **and** on older 1.21.x? |
| **V-03** | Confirm no `playerdata` file is written by a spawned Null (R-01) |
| **V-04** | Confirm the exact packet sequence renders a Null on 1.21.11 |
| **V-05** | Confirm `paperweight` dev-bundle availability for the chosen version |

---

## 10. Phase map → first files

| Phase | First files to create |
| --- | --- |
| **1** | `settings.gradle.kts`, root `build.gradle.kts`, `core/build.gradle.kts`, `nms/api/…/VersionAdapter.java`, `nms/v1_21_11/…`, `plugin/src/main/resources/paper-plugin.yml`, config classes, lifecycle |
| **2** | `NullEntity` (adapter-side), profile/skin, `NullRegistry`, persistence, commander selection, safe-spawn validation |
| **3** | `CallHorn` / `TotemOfNull` triggers, chat count flow, portal effects, `/null gui`, command tree, permissions |
| **4** | `Perception`, `AStar`, `Boids`, `FormationController`, stuck recovery |
| **5** | `ItemLedger`, equipment policy, combat actions, potions, loot |
| **6** | Clutches, bridging, boats/minecarts, mounts, Elytra |
| **7** | Schematic codec, `BlockPlanValidator`, mining, redstone, guarded explosives |
| **8** | Endpoint clients, schemas, circuit breaker, fallbacks |
| **9** | Version matrix, profiling, conservation + restart tests, docs |

---

## 11. Sources

All platform facts in this document were verified against primary sources, not recalled:

- **PaperMC Fill API v3** (`fill.papermc.io/v3/projects/paper`, `/versions/{1.21.1,1.21.8,1.21.10,1.21.11}`) — support status, end dates, Java minimum, build lists
- **Minecraft Wiki — Java Edition version history** — release dates for 1.21 → 1.21.11
- **Paper docs — `paperweight-userdev`** — reobfuscation scope, Mojang-mapped runtime from 1.20.5, CB relocation removal, 26.1 changes
- **PaperMC forums / Paper GitHub discussion #10584** — `ServerPlayer` NPCs, absence of pathfinder goals
- **ProtocolLib issue #3608** — Paper 1.21.11 build 17060 removed runtime remapping
- **SpigotMC wiki — Spigot NMS versions** — `1_21_R1` / `1_21_R2` revisions

---

**End of Phase 0. Per §11, work stops here pending `CONTINUE`.**
See [`STATUS.md`](STATUS.md) for phase state and [`TRACEABILITY.md`](TRACEABILITY.md) for the 221-item register.
