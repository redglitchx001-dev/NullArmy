<div align="center">

```
███╗   ██╗██╗   ██╗██╗     ██╗      █████╗ ██████╗ ███╗   ███╗██╗   ██╗
████╗  ██║██║   ██║██║     ██║     ██╔══██╗██╔══██╗████╗ ████║╚██╗ ██╔╝
██╔██╗ ██║██║   ██║██║     ██║     ███████║██████╔╝██╔████╔██║ ╚████╔╝ 
██║╚██╗██║██║   ██║██║     ██║     ██╔══██║██╔══██╗██║╚██╔╝██║  ╚██╔╝  
██║ ╚████║╚██████╔╝███████╗███████╗██║  ██║██║  ██║██║ ╚═╝ ██║   ██║   
╚═╝  ╚═══╝ ╚═════╝ ╚══════╝╚══════╝╚═╝  ╚═╝╚═╝  ╚═╝╚═╝     ╚═╝   ╚═╝   
```

# NullArmy

**A Paper/Spigot plugin that spawns Nulls — physically simulated, player-like NPCs that fight, build, and survive using nothing but real vanilla mechanics.**

<br>

![Status](https://img.shields.io/badge/status-specification%20%2F%20pre--alpha-blue)
![Runtime Dependencies](https://img.shields.io/badge/runtime_dependencies-0-brightgreen)
![Minecraft](https://img.shields.io/badge/minecraft-1.21.x%20%E2%86%92%201.21.11-3a7d3a?logo=minecraft&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Paper%20%2F%20Spigot-ff6b00)
![Copyright](https://img.shields.io/badge/%C2%A9-RedGlitchX-lightgrey)
![License](https://img.shields.io/badge/license-none%20declared%20yet-red)

</div>

---

## What is NullArmy?

You blow a **Call Horn** (or trigger a **Totem Of Null**). The plugin asks how many. Fifteen-plus portal effects flare open across the ground — and out of them *walk* Nulls: black-skinned, random-named, inventory-carrying entities that behave like a coordinated squad of skilled survival players.

They are **not** invulnerable mobs. They are **not** client-side illusions. Every Null:

- walks everywhere — **no teleporting, ever**, under any circumstance
- owns a real inventory, and every arrow fired, block placed, potion drunk, and tool swung is subtracted from it
- takes real damage, gets hungry, burns, drowns, freezes, and dies permanently
- respects attack cooldowns, shields, line of sight, enchantment rules, and block hardness
- keeps personal space — Nulls queue at doorways instead of stacking inside each other
- can be built to do 471 specific, individually testable vanilla things (see [the catalogue](#the-471))

The design philosophy is blunt: **if a real survival player can't do it, a Null can't do it either.** When a requested idea is impossible in vanilla, NullArmy says so out loud and implements the nearest honest alternative. It never fakes success.

---

## Table of Contents

- [Project Status](#project-status)
- [The Non-Negotiables](#the-non-negotiables)
- [The Vanilla-Reality Gate](#the-vanilla-reality-gate)
- [How Summoning Works](#how-summoning-works)
- [Commands](#commands)
- [Formations](#formations)
- [The 471](#the-471)
- [Architecture](#architecture)
- [Version Support](#version-support)
- [AI Endpoints](#ai-endpoints)
- [Safety & Griefing Policy](#safety--griefing-policy)
- [Performance Budget](#performance-budget)
- [Testing & Acceptance](#testing--acceptance)
- [Roadmap](#roadmap)
- [Building](#building)
- [Contributing](#contributing)
- [License & Copyright](#license--copyright)

---

## Project Status

**Read this before you get excited.** NullArmy is currently a **specification, not a plugin.**

| | |
| --- | --- |
| **Source code** | **33 Java files, 4,423 lines** across 4 Gradle modules |
| **Build system** | Gradle (Kotlin DSL), multi-module |
| **Compiled?** | ❌ **Never.** No JDK or dev bundle available in the authoring environment |
| **Lines of Java syntax-verified** | 4,423 / 4,423 (parser check only — not a compile) |
| **Mechanics implemented (of 471)** | **0** — nothing counts as implemented until it is tested on a declared version |
| **Current state** | Phase 0 complete · Phase 1 + partial Phase 2/3 **source authored but unverified** |
| **Docs** | Master prompt + [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) · [`STATUS.md`](STATUS.md) · [`TRACEABILITY.md`](TRACEABILITY.md) · [`BUILD.md`](BUILD.md) |

This README is the **public contract**: it describes what NullArmy will be, the invariants it will never break, and the bar it must clear before anything gets called "done." It is written from the master prompt so that the goalposts are visible before a single class is compiled.

Everything below is a **commitment**, not a boast. As phases land, the traceability tables get filled in — and per the project's own rules, anything not yet implemented stays marked as such. **Nothing gets labelled "complete" that isn't.**

### Phase 0 documents

The audit is done. These are its outputs:

| Document | Contents |
| --- | --- |
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | Environment audit, exact version compatibility table, NMS/packet feasibility notes, architecture diagram, module plan, ADRs, vanilla-impossibility register, documented assumptions, risk register |
| [`STATUS.md`](STATUS.md) | Phase state, core-feature traceability, acceptance-criteria scoreboard, Phase 0 handoff |
| [`TRACEABILITY.md`](TRACEABILITY.md) | All **471** mechanics (221 original + 250 added), extracted from the spec — every item marked `not started` |
| [`BUILD.md`](BUILD.md) | Exact build and verification commands, and what is/isn't proven |

> **Two blockers surfaced in Phase 0 and both need an owner decision:** the entire 1.21.x target range is **end-of-life** (Paper 1.21.11 support ended 2026-06-15), and this build environment has **no JDK, no Gradle, and no access to `repo.papermc.io`**. See the plan's [§8 Owner decisions](IMPLEMENTATION_PLAN.md#8-owner-decisions-required).

### ⚠️ Verification status of the code

Source has been written for the build skeleton, the version adapter SPI, the 1.21.11 adapter, the plugin bootstrap, and the core logic (item ledger, boids, pathfinding, planner, block-plan validator, JSON codec, circuit breaker).

| Proven | Not proven |
| --- | --- |
| All 33 files are syntactically valid Java | ❌ Compilation / type-checking |
| Package layout matches directories | ❌ NMS signatures (every one is a **hypothesis**) |
| No self-recursive methods | ❌ Gradle dependency resolution |
| `core` is dependency-free pure Java | ❌ Any runtime behaviour at all |

**The NMS adapter has never touched a Minecraft server.** Treat it as a starting point to verify, not as working code. [`BUILD.md`](BUILD.md) lists the five checks (V-01…V-05) that settle it.

---

## The Non-Negotiables

Six rules that override every other feature request. A feature that breaks one of these does not ship.

### 1. Zero runtime dependencies

No Citizens. No ProtocolLib. No WorldEdit. No pathfinding library. No AI SDK. No shaded third-party JSON parser downloading itself at runtime.

NullArmy talks to **Paper/Spigot APIs plus its own NMS and packet code**, and nothing else. The JDK HTTP client handles external AI calls. JSON is handled by a small, strictly bounded in-project codec. There is no hidden download, no runtime library install, no telemetry phone-home.

NMS code lives behind version adapters. **No single NMS package, mapping set, packet shape, or constructor is assumed to work across every 1.21 patch version** — each claimed version is built and tested separately.

### 2. Pure vanilla mechanics + real resource accounting

Every Null action must correspond to something a real survival-mode Java player could do under the server's active rules. Normal range, line of sight, movement, cooldowns, collision, durability, hunger, status effects, ammunition, block placement, and dimension limits all apply.

Forbidden: magical teleports, invisible movement, wall phasing, instant construction, giant fusions, fabricated loot, free ammunition, infinite durability, AI-generated items.

The **NPC inventory is authoritative**, backed by an auditable item ledger. The `/null gui` loadout screen is a *blueprint*, not a duplicator — gear must come from items donated by the summoner, legitimate drops, real crafting, honest trades, or explicitly configured storage.

### 3. No clumping, stacking, or clipping

Formation-aware Boids/local avoidance with **hard collision constraints**. Separation, alignment, cohesion, formation-slot attraction, obstacle avoidance, and target pursuit are blended as bounded steering forces — and formation goals never override hitboxes or collision.

If a route is too narrow, Nulls **queue, spread out, reroute, wait, or abandon the maneuver.** They never share coordinates, walk through solid blocks, or teleport to fix bad spacing. Avoidance covers other Nulls, players, mobs, vehicles, and world geometry. Tested at doors, bridges, corners, boats, and combat chokepoints.

### 4. "Zero lag" as an engineering target, not a marketing word

No software can honestly guarantee mathematically zero cost, so NullArmy doesn't claim it. The target is defined as: **no unbounded work, no main-thread network calls, no runaway entity/packet work, and no noticeable TPS degradation at documented scale.**

Every costly system gets configurable caps, a per-tick budget, cancellation, and a low-cost fallback. Profiling results and measured limits get published — including the numbers that look bad.

### 5. The vanilla-reality gate

Impossible ideas are refused with an explanation and the closest genuine alternative. See the next section.

### 6. Server authority

Gameplay state lives on the server. **Packets are not proof** that an NPC hit something, picked something up, placed a block, or consumed a resource — every action is validated against server state before it counts.

---

## The Vanilla-Reality Gate

The most important table in this document. These are ideas that sound great and don't work.

| Requested idea | Reality | What NullArmy does instead |
| --- | :-: | --- |
| **TNT mines obsidian** | ❌ | Nulls use a diamond/netherite pickaxe and real mining time. TNT is never presented as an obsidian shortcut. Bedrock and unbreakables stay unbreakable. |
| **Throw a Wither Skull as a projectile** | ❌ | A Wither Skeleton Skull is a *placeable block item*, not a throwable. If Wither content is explicitly enabled, Nulls physically assemble the real soul sand/soul soil + 3 skulls structure from owned items. Off by default. |
| **Wither cannon** | ⚠️ | Only a real, physically built, inventory-funded contraption. Failure is reported honestly — a failed launch is never silently replaced with a scripted projectile. |
| **Removing armor makes you invisible** | ❌ | Reduces visible armor/glint and *also removes protection*. Invisibility requires an actual owned invisibility potion, with all its normal telltales (particles, held items, armor). |
| **Wind Charges = flight** | ❌ | A Wind Charge gives a genuine one-off impulse. Sustained gliding requires a real equipped Elytra plus real firework rockets, with durability, launch, collision, and landing handled normally. |
| **Packet-only fake player** | ❌ | A packet-only entity can't reliably fight, collide, place blocks, hold an inventory, or obey world physics. NullArmy uses a **server-authoritative NMS-backed entity** for anything that affects the world; packets are for appearance, profile/list presentation, and animation only. |
| **Summon portals teleport** | ❌ | Purely cosmetic particles/sound. Requested Nulls physically *walk out* from safe spawn points; surplus effects close empty. Never extra NPCs to satisfy the visual count. |
| **Potion combining / mixing** | ❌ | Vanilla has no potion-mixing action. Legal brewing and tactical *sequencing* of separate potions only. |
| **Ender pearls / chorus fruit to reposition** | ❌ | A Null never teleports. Not by pearl, not by fruit, not by command, not by portal, not to "fix" a stuck path. |

> **Because a Null cannot teleport, it can get genuinely stuck.** That's accepted. A stuck Null diagnoses, replans, and escalates — it does not vanish and reappear.

---

## How Summoning Works

1. **Trigger.** Use a real **Goat Horn** configured/named `Call Horn`, or a real **Totem of Undying** configured/named/tagged `Totem Of Null`. These are vanilla items — a usable summon item is only ever created by an explicit owner/admin action or a documented recipe/config. Never a spontaneous grant.
2. **Ask.** The plugin prompts the *authorized summoner* for the desired Null count in chat. The pending request is bound to that player, expires after a configurable timeout, validates the answer, supports cancel/help, and **ignores chat from any other player**.
3. **Enforce.** Minimum two Nulls if the squad needs two commanders. A configurable hard cap and resource/performance budget apply. Excessive counts are rejected with a clear message — **never** a partial surprise army.
4. **Verify.** World permission, loaded/safe ground, nearby hazards, owner limits, and spawn spacing are checked before anything commits. If no safe location exists, NullArmy explains the failure rather than spawning through a wall.
5. **Emerge.** **≥15 visual portal effects** fire (when visuals are enabled) — effects only. The actual Nulls walk out from collision-safe spawn points.

### Identity

| Property | Behaviour |
| --- | --- |
| **Skin** | Pure black player skin from a configured valid texture/profile or a documented bundled/owner-supplied asset. If the target client/profile mechanism can't render it, NullArmy says exactly what setup is required rather than promising it. Real players' skins are never touched. |
| **Name** | Unique random alphanumeric profile/display name, e.g. `uH3WR2v0ti0uTHJ`. Respects the target version's username/profile length and character constraints; no duplicates across online *and* persisted NPCs. |
| **Commanders** | Exactly **two** designated Commanders for any squad of two or more. Roles are stored — not randomly reassigned each tick or restart — with orderly succession if one is permanently lost. |
| **Body** | Realistic health, armor, inventory, equipment, hitboxes, animations, sounds, and damage. No hidden invulnerability, no fake health. |

---

## Commands

All commands are permission-checked with tab completion, clear feedback, and audit logs for destructive/admin actions. Offline and ambiguous targets are handled safely.

| Command | Permission | What it does |
| --- | --- | --- |
| `/null gui` | `nullarmy.gui` | Opens the inventory/loadout **planning** GUI. Selects equipment priorities and quantities, shows an explicit supply source and every deficit. **Never duplicates a displayed item.** |
| `/null chat [on\|off]` | `nullarmy.chat` | Toggles Null chat and ChatCommander output. |
| `/null attack <player>` | `nullarmy.attack` | Sets a physical pursuit/combat objective. The target is **not** instantly damaged or moved. |
| `/null attackx <player>` | `nullarmy.attackx` | Adaptive extreme-combat profile: faster tactical reassessment, tighter coordination, careful resource use, stronger counterplay. **No cheats, impossible reaction times, hidden information, bonus damage, or free items** — just a better-behaved squad. |
| `/null follow me` | `nullarmy.follow` | Follows the issuing owner using a formation and personal-space rules. **Never teleports to catch up.** |
| `/null build a <structure>` | `nullarmy.build` | Triggers the building system (see below). |
| `/null status` | `nullarmy.admin` | Diagnostics: active NPCs, states, squads, endpoint health, task backlog, performance counters. Never exposes secrets. |
| `/null stop` | `nullarmy.admin` | Emergency stop / kill switch for all active squads. |
| `Null ban <player>` / `/null ban <player>` | `nullarmy.moderation` | ⚠️ **Not an AI action.** Explicit, separately permission-gated moderation with confirmation and audit logging. Cannot be invoked by ChatCommander or any endpoint. Returns a clear disabled message unless the owner enables moderation integration. A "ban" never silently means an instant combat kill. |
| `Null kill <player>` / `/null kill <player>` | `nullarmy.attack` | A **lethal-combat objective only** — the squad still has to fight normally, and the target can escape, defend, or survive. Operator cleanup/dismissal is a separate, explicitly confirmed action that cannot accidentally delete real players. |

Natural-language equivalents are accepted when enabled, but destructive, moderation, and expensive actions require high confidence **and** confirmation.

### Formations

`line` · `encircle` · `square` · `shield-wall / Turtle`

Configurable spacing, orientation, leader/commander anchors, terrain-aware offsets, and orderly transitions. **A command changes a goal — it never overrides collision, inventory, pathfinding, or server protections.**

### Building

Triggered by `Null build a <structure>` or an equivalent authorized command.

1. Check the plugin-owned `/schematics` folder first — a documented, bounded plugin JSON format, plus an optional vanilla structure format if it can be done safely without WorldEdit. **WorldEdit is never required.**
2. If no schematic matches, **BuilderAgent** may propose a strict JSON block plan. Dimensions, palette, block states, rotations, material costs, support rules, world bounds, protection, and **every single placement** are validated locally before approval.
3. Nulls then **physically walk** to each location, select the correct block, orient it, swing, place it through authoritative vanilla-like placement rules, consume the real block, and wait out the cooldown.

No instant paste. No mass `setType`. No invisible worker. If supplies run out, they pause, request supply, or gather/craft through legal actions only. Player builds are preserved unless the owner explicitly enables the relevant destructive permission.

---

## The 471

The spec catalogues **471 vanilla mechanics** across twelve groups: the original 221 in `NullArmy_Master_Prompt.md` §6, plus 250 more in [`MECHANICS_EXPANSION.md`](MECHANICS_EXPANSION.md). This is a feature *catalogue*, not permission to break the core rules — every item requires genuine inventory, legal perception, and authoritative server validation.

| Group | # | Range | Summary |
| --- | :-: | :-: | --- |
| **A. Combat & equipment tactics** | 40 | 1–40 | Role-aware weapon choice, real attack cooldowns, reach/hit validation, true crit conditions, combo strafing, sprint-reset knockback, shield timing (note: Java swords have **no** legacy sword-blocking — no fake block-hitting), axe shield pressure, durability management, bow draw discipline, projectile lead, cover-aware aim, arrow conservation, tipped-arrow gating, crossbow loading and enchantments, firework crossbows, fishing-rod interruption, trident throws with Loyalty/Riptide/Channeling restrictions, mace smash attacks, Wind Burst handling, sweep awareness, debuff selection, milk cleanse, golden-apple timing, totem use, lava-bucket combat, water-bucket counterplay, flint-and-steel, End-crystal and respawn-anchor PvP, TNT combat, anti-air interception, disengage logic |
| **B. Squad tactics & coordination** | 25 | 41–65 | Commander hierarchy, squad roles, focus fire, threat scoring, synchronized volleys, crossfire lanes, flanking, pincer timing, shield rotation, all four formations, owner escort, rear guard, reserve squad, frontline rotation, peeling, casualty contingency, rally points, target splitting, support protection, friendly-fire lane checks, formation-aware separation, nonverbal signals |
| **C. Mobility & traversal** | 45 | 66–110 | Incremental A*, local steering, Boids neighbourhood, hard NPC separation, chunk-aware routes, legal step-up, jump timing, edge sensing, stairs, ladders, vines/scaffolding, **real** bridging/scaffolding (one block at a time, never an instant bridge), doors, fence gates, trapdoors, low-ceiling posture, gap-jump evaluation, controlled drops, route mining, hazard costs, fall prediction, **water/cobweb/hay/slime/powder-snow clutches**, Wind-charge impulse, swimming, water currents, bubble columns, boats, chest boats, ice boating, minecarts, rail construction, horses, saddles/armor, camels, striders, Elytra, rocket-assisted glide, flight and landing planning |
| **D. Survival, inventory & SMP life** | 50 | 111–160 | Hunger monitoring, food selection, raw-vs-cooked, real cooking, health triage, potion inventory, real brewing, self/splash/ally potion timing, debuff safety, effect sequencing, fire resistance, water breathing, milk removal, drowning/freezing/fire/lava responses, water supply, light awareness, torch placement, shelter seeking, beds, armor choice/wear, shield and tool wear, repairing, enchantments, offhand policy, inventory sorting, **stack conservation**, loot pickup and priorities, dead-ally recovery, arrow/potion sharing, equipment handoff, commander resupply, summoner delivery, storage use, trapped-container caution, villager trading, crop planting/harvesting, fishing, wolf taming, animal care, breeding |
| **E. Mining, construction, redstone & traps** | 40 | 161–200 | Correct mining tool, obsidian mining, bedrock/unbreakables (**never** bypassed), visible-resource mining (**no x-ray ore search**), staircase mining, tunneling, gravity-block awareness, torch markers, placement physics, material cost planning, temporary scaffold, defensive walls, trenches, water control, lava casting, Frost Walker, firebreaks, TNT placement/ignition, cannon assembly/calibration, misfire handling, blast-resistance awareness, Wither gates, skull-item correctness, **visible-only** redstone reconnaissance, tripwire disarming, shears, pressure plates, buttons/levers, redstone-dust tracing, repeater timing, comparator logic, observer awareness, piston hazards, dispensers/droppers, hopper logistics, doors, breach choices |
| **F. Stealth, deception & lifelike behaviour** | 21 | 201–221 | Crouch approach, tall-grass concealment, darkness discipline, armor-removal tradeoff, potion invisibility, honest identity tells (**no packet hacks to hide name tags or particles**), sound discipline, line-of-sight breaking, cover scouting, light discipline, armor-stand decoys, banner/sign signaling, campfire smoke, feigned retreat, bait discipline, terrain ambush, watch rotation, shift-signal vocabulary, natural gaze/posture, chat psychology, organic idle loop |
| **G. Advanced combat, damage & equipment depth** | 50 | 222–271 | Enchantment matchups (Smite/Bane/Impaling/Density/Breach), armour-value targeting, Thorns recoil, curse handling, Spectral Arrow marking, Lingering clouds, Slow Falling, Turtle Master, **Spears + Lunge (1.21.11)**, Warden withdrawal, hostile-projectile dodging |
| **H. Squad command, coordination & logistics** | 40 | 272–311 | Bounding overwatch, sentry rotation, chokepoint control, buddy pairs, medic/ammo/engineer roles, fall-back staging, time-of-day & weather planning, cargo triage, dead-drop caching, pursuit abort |
| **I. Mounts, traversal & mobility** | 45 | 312–356 | **Nautilus + Zombie Nautilus + Nautilus Armour (1.21.11)**, **Zombie Horse & Camel Husk (1.21.11)**, mounted water crossing, Soul Speed, Swift Sneak, honey-block sliding, ice friction, kelp elevators, rail switching, minecart spacing |
| **J. Survival, crafting, economy & SMP life** | 50 | 357–406 | XP/Mending allocation, anvil prior-work cost, grindstone, netherite smithing, armour trims, Bundles, Shulker Boxes, **Crafter**, **Shelf / Copper Chest / Copper Golem (1.21.9)**, fuel economy, full farm & food chains |
| **K. Mining, building, redstone & automation** | 40 | 407–446 | Ancient debris, Piglin aggro on nether gold, **Sculk Sensor noise discipline**, Shrieker avoidance, trial chambers & vaults, **Copper Bulb (1.21.9)**, cobble/basalt generators, dripstone lava farms, sorting arrays, item lifts, spawn-proofing |
| **L. Stealth, perception, scouting & lifelike behaviour** | 25 | 447–471 | Vibration-aware movement, wool-dampened routes, sound-cue interpretation, spyglass scouting, cartography, dead reckoning, counter-scouting, particle/glint tells, signal fires, rest rotation |

### Idle behaviour

Nulls never stand motionless without reason — but "lifelike" randomness never overrides danger checks or commanded objectives. Bounded, non-spammy idle behaviours: look around, adjust facing, briefly crouch, inspect surroundings, jump only when safe and useful, regroup, signal nearby allies.

No endless shift-spam. No collision-causing jumps. No pointless item swings. No chat spam.

---

## Architecture

Planned module layout (Phase 0/1 deliverable). NMS is quarantined behind version adapters so no single mapping set is assumed across 1.21 patches.

```
nullarmy/
├── api/            # public plugin API surface
├── version/        # version adapters — one per supported server build
│   ├── v1_21_R1/ … v1_21_Rn/
│   └── VersionAdapter  (SPI boundary)
├── entity/         # authoritative NPC entity, profile, black skin, naming
├── inventory/      # authoritative inventory + auditable item ledger
├── command/        # commands, permissions, tab completion, audit logging
├── perception/     # raycasts, LOS, audible events, confidence decay
├── navigation/     # incremental A*, local steering, terrain cost
├── formation/      # boids/flocking, spacing, line/square/encircle/turtle
├── combat/         # weapons, cooldowns, shields, projectiles, potions
├── build/          # schematics parser, BuilderAgent plan validation
├── redstone/       # visible-only circuit reasoning, traps, TNT
├── persistence/    # identity, owner, squad, inventory, objectives, timers
├── agent/          # OpenAI-compatible clients, circuit breaker, schemas
├── config/         # configuration, per-world policy, caps
├── telemetry/      # diagnostics, debug rejection reasons, perf counters
└── test/           # pure-logic tests + Paper server test procedures
```

**Threading rules, non-optional:**

- World reads and **all** world mutations happen on the correct server thread.
- Async work may only process immutable snapshots or pure calculations — and every result is revalidated on the server thread before acting.
- No chunk force-loading. No unbounded region scans. No pathing through unloaded chunks unless the server loaded them naturally and policy permits.
- Persistence must survive chunk unload, plugin disable, server restart, player disconnect, and endpoint timeout **without duplicating drops**.

---

## Version Support

Target: **Minecraft 1.21.x → 1.21.11** (Paper/Spigot).

| Version | Adapter | Status |
| --- | :-: | --- |
| 1.21.x | `v1_21_R1` | ⬜ Not started |
| … | … | ⬜ Not started |
| 1.21.11 | `v1_21_Rn` | ⬜ Not started |

**Verify each exact server build before claiming support.** NullArmy does **not** promise every patch version on the strength of one successful compile — each version is built and tested separately, and the mappings/builds actually tested are recorded here.

Unsupported builds must **fail clearly**, not silently limp along.

---

## AI Endpoints

Four isolated, **optional**, OpenAI-compatible agent roles. All network work is async, rate-limited, time-bounded, and cancellable.

> **A missing or unreachable endpoint must never stall the server or stop basic Null behaviour.**

| Agent | May do | May **never** do |
| --- | --- | :-: |
| **ChatCommander** | Produce short chat text | Issue commands, change targets, alter inventories, ban players, authorize actions |
| **CombatTactician** | Recommend a high-level intent from a strict enum (`hold`, `approach`, `flank`, `retreat`, `shield`, `ranged volley`, `resupply`, `regroup`) | Deal damage directly, bypass the local combat validator |
| **BuilderAgent** | Return a bounded block-plan JSON using an allowed palette and finite dimensions | Write blocks; skip inventory/support/protection/cost checks |
| **PathfinderCore** | Suggest a destination/route preference from a **sanitized** snapshot | Move the NPC; supply hidden-world or through-wall data |

### Endpoint safety

1. Base URL, model, **auth-key environment-variable name**, timeout, retry count, rate limits, token/response size caps, and enabled state are configured independently per role.
2. **No API keys in commands, prompts, chat, debug logs, exception traces, or persisted gameplay data.** Coarse summaries are sent instead of full inventories or unrelated player data.
3. Player chat, books, signs, entity names, and endpoint responses are **untrusted input**. Prompt-injection defenses are mandatory; agents never receive secrets or any interface that can execute code or console commands.
4. Strict JSON/schema validation on every action-bearing response. Malformed, oversized, stale, out-of-range, unknown-target, unsafe, or impossible actions are rejected. All numerics clamped; all identifiers validated locally.
5. Every recommendation carries an expiry, request ID, NPC/squad scope, confidence, and reason. **Stale replies are never executed.**
6. Circuit breaker, bounded retries with backoff, request coalescing/caching where safe, deterministic local fallbacks. **Never an HTTP request on the tick thread.**
7. **The local safety validator sits above every agent decision.** The endpoint advises; the server owns inventory, movement, damage, blocks, permissions, and final calls.
8. Aggregate latency, errors, rejection reasons, and token/request counts are logged — without credentials or sensitive player content. Opt-in privacy controls and retention limits.

---

## Safety & Griefing Policy

NullArmy may run on an SMP, so destructive behaviour must be explicit, predictable, and **off until turned on**.

**Defaults:** no explosive block damage · no Wither spawning · no hostile block breaking · no fire spread · **no interaction inside protected or claimed areas.** The server owner opts in per world and per command permission.

- **Show, then confirm.** Before a costly or destructive objective, the owner sees the intended area, item cost, expected risk, and whether block damage is enabled. Wither creation, large TNT operations, and anything capable of substantial terrain damage require explicit confirmation.
- **Obey the world.** Difficulty, PvP, `mobGriefing`, fire-tick, explosion, claim, and protection rules are respected — never bypassed with direct block writes.
- **Failure is closed.** If NullArmy cannot determine whether an area is protected, it fails closed and asks the owner.
- **Always available:** target allow/deny lists, owner and allied-player protection, safe-zone checks, emergency stop, `/null stop`, and a kill switch for all active squads.
- **Hard limit:** no external endpoint may ban, kick, mute, op, execute commands for, or moderate a player. Ever.

### Base breaching (guarded)

If a target is behind a wall, Nulls first assess legal entrances, doors, visible weak points, mining time/tool, team safety, and server protection. They can mine with the correct tool at ordinary block-breaking speed.

TNT + Flint and Steel requires all of: the Null actually **carries both**, the world permits griefing, **and** the owner enabled explosive tactics. Placement, fuse, blast damage, block destruction, ally danger, and retreat are all real.

A dispenser/redstone/TNT cannon is built only from carried or legitimately acquired parts, physically placed piece by piece. It may fail — and failure is reported, not faked.

---

## Performance Budget

Every one of these gets a configurable cap, a per-tick budget, cancellation, and a low-cost fallback:

| Resource | Capped | Resource | Capped |
| --- | :-: | --- | :-: |
| Live NPCs | ✅ | Packet sends | ✅ |
| Squads | ✅ | Particle effects | ✅ |
| Simultaneous path searches | ✅ | Endpoint calls | ✅ |
| Block inspections | ✅ | Active builders | ✅ |
| AI JSON size | ✅ | Schematic dimensions | ✅ |

**Operating rules:**

- NPC decision updates are **staggered** — no full expensive brain tick for every Null on every server tick.
- Spatial hashing for crowd checks; safe immutable snapshots are reused.
- Bounded queues and back-pressure throughout.
- When overloaded, NullArmy sacrifices optional emotes, endpoint calls, and long-range replanning **first** — collision safety and tick health last.

> When the plugin ships, real tick/CPU/packet measurements get published here. **Including the numbers that look bad.** No unqualified "zero lag" claims.

---

## Testing & Acceptance

Automated tests for pure logic, plus a reproducible Paper-server checklist for NMS/world interactions. Nothing is called done until these pass:

1. ⬜ Each declared server version compiles and starts using its matching adapter; unsupported builds fail clearly.
2. ⬜ Summoning requires valid item, permission, count, safe positions, and configured cap; chat request ownership/timeout/cancel works.
3. ⬜ A summon creates **≥15 visual effects** when enabled but **never** more actual Nulls than requested.
4. ⬜ Two commanders are stable for every squad of at least two.
5. ⬜ Skin/name/profile limits and collision-safe spawn points are handled correctly.
6. ⬜ `/null gui` never duplicates inventory; all loadout deficits are visible.
7. ⬜ Every placed block, fired arrow/rocket, used potion, dropped stack, repair, trade, and pickup has correct item accounting **across save/restart**.
8. ⬜ No NPC teleports, clips into another NPC, phases through a block, exceeds legal acceleration, or force-loads a chunk.
9. ⬜ Formation changes work at doors, stairs, bridges, boats, combat crowds, and mixed terrain without stacking.
10. ⬜ Combat respects cooldown, line of sight, shields, ammunition, effects, durability, allies, and PvP/world rules.
11. ⬜ Water/cobweb/hay/slime/powder-snow clutches are attempted only with a real item and legal timing; **failed clutches still have normal consequences.**
12. ⬜ Elytra flight consumes real rockets and respects durability, collision, takeoff, and landing. Wind Charges never create sustained flight.
13. ⬜ TNT does not break obsidian; obsidian mining uses a valid pickaxe and real time; bedrock is never bypassed.
14. ⬜ Wither skulls are never thrown; Wither spawning is off by default and requires real ingredients + permission + confirmation.
15. ⬜ Trap/redstone reasoning uses only visible information; no hidden blocks or player inventories are exposed to the AI.
16. ⬜ Endpoint timeout, malformed JSON, prompt injection, rate limiting, DNS/TLS failure, and full outage leave the server responsive and fall back locally.
17. ⬜ Restart, chunk unload, owner disconnect, NPC death, dropped gear, and plugin disable never duplicate items or orphan tasks.
18. ⬜ Load testing at the documented NPC cap meets the published tick/CPU/packet budget — **with measurements, not "zero lag" claims.**

---

## Roadmap

Implementation runs in gated phases. Each phase ends with a handoff stating files changed, behaviour now working, exact build/test results, known limitations, remaining traceability items, and the next phase.

| Phase | Focus | Status |
| --- | :-: | --- |
| **0** | Repository & feasibility audit — architecture diagram, module plan, exact version compatibility table, NMS/packet feasibility notes, list of vanilla impossibilities, resolved assumptions | ✅ **Complete** |
| **1** | Build skeleton & version adapters — build, plugin metadata, config, permissions, adapter boundaries, lifecycle. Compiles on the first declared target | ⬜ **Gated** — needs owner decisions D-1 + D-2 |
| **2** | Authoritative NPC identity & lifecycle — server-side gameplay entity, packet/profile/skin, unique names, persistence, two commanders, health/equipment/inventory, death/drops, safe spawn validation | ⬜ |
| **3** | Commands, summoning & visuals — Call Horn/Totem validation, chat amount flow, count caps, 15 portal effects, physical emergence, permissions, `/null gui` blueprint | ⬜ |
| **4** | Perception, movement, collision & formations — legal perception, incremental pathing, Boids separation, no-clumping, follow, all four formations, door/terrain traversal, stuck recovery | ⬜ |
| **5** | Survival inventory & combat — resource ledger, equipment priorities, food/potions, ranged and melee combat, shields, crossbows, tridents, anti-air, allied support, loot, death recovery | ⬜ |
| **6** | Mobility extensions — MLG attempts, bridging/scaffolding, boats, minecarts, mounts, Elytra/rockets, landing | ⬜ |
| **7** | Builder, mining, redstone & destructive systems — `/schematics` parser, BuilderAgent JSON schema, per-block physical placement, mining, traps, redstone, guarded TNT/cannons | ⬜ |
| **8** | External AI agents — one role at a time, strict schemas, circuit breaker, privacy controls, deterministic fallback, adversarial tests | ⬜ |
| **9** | Performance, compatibility & release — version matrix, profiling, item-conservation and restart tests, documentation review, traceability audit | ⬜ |

**Definition of Done:** the declared feature set is traceable, item conservation is proven, authoritative movement/combat/building works on every advertised version, the no-teleport and no-clipping invariants pass tests, endpoint failure is harmless, destructive features are opt-in and protected, resource limits are documented, **and the project builds from a clean checkout with no undeclared runtime dependencies.**

---

## Building

**There is nothing to build yet.** Build instructions land with **Phase 1**, which is currently gated on two owner decisions (see [`IMPLEMENTATION_PLAN.md` §8](IMPLEMENTATION_PLAN.md#8-owner-decisions-required)).

Two Phase 0 findings affect anyone about to try:

- **The declared target range is end-of-life.** Paper 1.21.11 support ended 2026-06-15 and 1.21.10 ended 2026-01-17; every version from 1.21 → 1.21.11 is `UNSUPPORTED`. The current Minecraft release is 26.3.
- **The reference sandbox cannot build this project.** No JDK, no Gradle, and network access limited to `github.com` — `repo.papermc.io` (which hosts the mandatory Paper dev bundle), Maven Central, Gradle distributions, and JDK downloads are all unreachable. Build/verify must happen on a properly provisioned machine.

Expected shape once Phase 1 completes:

```bash
git clone https://github.com/redglitchx001-dev/NullArmy.git
cd NullArmy
./gradlew build          # requires JDK 21 (Paper 1.21+ baseline)
# → build/libs/NullArmy-<version>.jar
```

The built JAR must contain **no** third-party runtime dependencies. If it does, that's a bug.

---

## Contributing

Contributions are welcome once Phase 1 lands and there is code to contribute to. Until then, the most useful contributions are **on the spec** in [`NullArmy_Master_Prompt.md`](NullArmy_Master_Prompt.md):

- finding a mechanic that vanilla cannot actually do (→ it belongs in the [vanilla-reality gate](#the-vanilla-reality-gate))
- finding an item-conservation hole — a path where an item could be duplicated or deleted
- finding a teleport cheat — any code path that moves a Null without walking
- tightening an acceptance criterion so it becomes genuinely testable

**Ground rules for any contribution:**

- No new runtime dependencies. Ever.
- No teleport. Not for pathfinding recovery, not for "unsticking," not for convenience.
- No feature that works only via packets if it visibly affects the world.
- Every feature ships with its traceability state: `implemented` · `partial` · `experimental` · `blocked by vanilla` · `not started`.
- **Never label an experimental or blocked feature "complete."** Never fake success.

---

## License & Copyright

**Copyright © RedGlitchX.** All rights reserved.

No license file has been added yet, which means the default position applies: **all rights reserved** — no permission is granted to use, copy, modify, or distribute this work. If you are RedGlitchX and want contributors, add a `LICENSE` file and this section will be updated.

The design brief lives in [`NullArmy_Master_Prompt.md`](NullArmy_Master_Prompt.md).

---

<div align="center">

**NullArmy · Copyright RedGlitchX**

*When vanilla physics, a Paper/NMS version, or packet behaviour makes a requested feature impossible, NullArmy explains exactly why, marks it `blocked by vanilla` or `version-specific`, and offers the nearest honest alternative.*

**Never fake success.**

</div>


