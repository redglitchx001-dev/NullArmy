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

**An early-stage Paper plugin for physically simulated, player-like Null NPCs. Significant mechanics are implemented, but the full 471-item catalogue is not complete; the latest working-tree changes have not been rebuilt or verified.**

<br>

![Status](https://img.shields.io/badge/status-implementation%20in%20progress%20%2F%20pre--alpha-blue)
![Runtime Dependencies](https://img.shields.io/badge/runtime_dependencies-0-brightgreen)
![Minecraft](https://img.shields.io/badge/minecraft-1.21.x%20%E2%86%92%201.21.11-3a7d3a?logo=minecraft&logoColor=white)
![Platform](https://img.shields.io/badge/platform-Paper%201.21.11-ff6b00)
![Build](https://github.com/redglitchx001-dev/NullArmy/actions/workflows/build.yml/badge.svg)
![Copyright](https://img.shields.io/badge/%C2%A9-RedGlitchX-lightgrey)
![License](https://img.shields.io/badge/license-none%20declared%20yet-red)

</div>

---

## Verification history and current state

Earlier repository snapshots passed core tests and Paper 1.21.11 `runtimeSmoke` checks (the most
recent historical result is recorded in [`STATUS.md`](STATUS.md)). Those runs predate the current
follow-up edits. This working tree has no Java runtime in `PATH`; its changes have **not** been
rebuilt or run, so historical checks are not a pass for the current diff.

The previous headless smoke run covered:

- one Null and a squad of five are created, alive, with a non-null packet listener, and present in
  `ChunkMap.entityMap` — the server-side half of being visible;
- a viewer's connection really receives `ClientboundPlayerInfoUpdatePacket` **and** the entity
  pairing bundle, which are the two packets a client turns into a visible player (the bundle through
  the tracker's own `ServerEntity.addPairing`; a headless probe can never satisfy the viewer-side
  chunk gate that guards it, and `STATUS.md` shows the measurement);
- Nulls emerge from **real, temporary portal doorways** (`4 portal doorways opened, 3 Null(s) walked
  out of them; 2 arrived on verified open ground instead`), and every block those doorways used is
  put back;
- the squad stays alive, tracked and finite across many server ticks with no server exception;
- a destroyed Totem Of Null walks every Null out one at a time, Commander last, refusing new summons
  until it finishes.

What a headless server cannot prove is listed in [`STATUS.md`](STATUS.md) — skin rendering, the
nameplate and tab appearance, how the doorway looks on your client, the cannon's arc when a player
aims it, and a chunk unload/reload cycle.

## What an owner gets

| | |
| --- | --- |
| **Summoning** | Call Horn named `Null` (real Call goat-horn sound) → "How many Nulls should come?" → a random 1…`portals.max-per-summon` real doorways open near you and the Nulls walk out of them. |
| **Portals** | Temporary obsidian frames with real `NETHER_PORTAL` blocks, built only where the site is clear, with one or two Nulls per doorway (`max-per-portal` is capped at 2); Nulls walk out and blocks are restored without Nether travel. |
| **Equipment** | A shared soldier kit with enchanted netherite armor and weapons, bow/arrows, potions, food, building materials, mace, totems, Wind Charges and rockets. The Commander gets the same kit plus an Elytra and a white chestplate trim; regular Nulls get neither Elytra nor trims. Owner-edited Commander loadouts are preserved. |
| **Names** | Every Null gets a unique random 16-character alphanumeric profile name, beginning with a letter and containing at least one digit. A configured skin account, signed texture pair, or custom `plugins/NullArmy/skins/null.png` supplies the texture only. Commander and Nulls appear in the tab list with plain names. |
| **Totem Of Null** | Named exactly `The Totem Of Null`, real Curse of Vanishing, recognised by persistent data. When it pops or is truly destroyed the whole army goes out one at a time, Commander last. |
| **Chat** | Talk to the Commander only; Nulls take orders. `null guard`, `null follow`, `null formation square`, `null attack Steve` — dispatched through the same validated executor as `/null …`. |
| **AI** | The Commander sees its squad (health, positions, roles, kit, objective, what the cannon and air drop may do) and answers with one typed, allowlisted action that is re-checked against permissions, caps and policy before it runs. Cannon, air drop and dismiss always need `/null confirm`. With no endpoint configured a deterministic local coordinator runs and `/null ai` says so. |
| **Missions** | One original objective for the whole army at a time — scouting, a corridor rescue, holding a banner, trials, a gate vigil, an accord between camps, a supply run. Movement and reporting only: no block damage, no explosives, no PvP. |
| **Reload** | `/null reload` appends any setting this build ships that your `config.yml` lacks, with a timestamped backup, and tells you exactly which keys were added. Your values and comments are never rewritten. |

## What is NullArmy?

You blow a **Call Horn** (or trigger a **Totem Of Null**). The plugin asks how many. Fifteen-plus portal effects flare across the ground; at safe sites, temporary obsidian frames with real `NETHER_PORTAL` openings appear, and Nulls physically walk through and out. The doorway blocks are restored after use. If a safe doorway cannot be built, Nulls emerge on verified open ground with effects and an honest fallback report—never by teleporting or travelling to the Nether. They use the configured black/custom skin when a signed texture is available (otherwise vanilla's default Steve/Alex skin), and have random names and real inventories. They behave like a coordinated survival squad.

They are **not** invulnerable mobs. They are **not** client-side illusions. Every Null:

- walks for ordinary movement; `/null tp` is a separate, owner-triggered Ender Pearl ability, and it does not change how Nulls are summoned
- owns a real inventory, and every arrow fired, block placed, potion drunk, and tool swung is subtracted from it
- takes real damage, gets hungry, burns, drowns, freezes, and dies permanently
- respects attack cooldowns, shields, line of sight, enchantment rules, and block hardness
- keeps personal space — Nulls queue at doorways instead of stacking inside each other
- is being developed against a 471-item vanilla-mechanics catalogue; that catalogue is a roadmap, not a claim that every item is implemented (see [the catalogue](#the-471))

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

**Read this before you get excited.** NullArmy is an **implementation in progress**, not a finished or
release-verified plugin.

| | |
| --- | --- |
| **Source code** | 121 Java source files (including tests) across 4 Gradle modules |
| **Build system** | Gradle (Kotlin DSL), multi-module |
| **Build history** | Earlier snapshots built and passed recorded tests; this working-tree diff has not been rebuilt |
| **Tests** | The prior snapshot recorded 118 checks and a Paper smoke run; current tests are blocked because Java is unavailable |
| **Mechanics** | Many behaviors are implemented; no up-to-date completion count for all 471 catalogue rows is asserted |
| **Current state** | Follow-up bug fixes and mechanics are in progress; see [`STATUS.md`](STATUS.md) |
| **Docs** | Master prompt + [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) · [`STATUS.md`](STATUS.md) · [`TRACEABILITY.md`](TRACEABILITY.md) · [`BUILD.md`](BUILD.md) |

This README describes the project's invariants, currently implemented behaviors, and the broader
471-mechanic target. It is not a claim that the catalogue is complete. Current code status and
historical verification are distinguished explicitly; **nothing in the current diff is labelled
verified until it is rebuilt and tested.**

### Project documentation

These documents are the feature sources, status records, and build references. They do not all have
the same revision date; where claims conflict, `STATUS.md` is the current implementation snapshot
and the explicit user constraints remain the authority for this work:

| Document | Contents |
| --- | --- |
| [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) | Environment audit, exact version compatibility table, NMS/packet feasibility notes, architecture diagram, module plan, ADRs, vanilla-impossibility register, documented assumptions, risk register |
| [`STATUS.md`](STATUS.md) | Historical checks, current branch changes, and verification blockers |
| [`important.txt`](important.txt) | Quick owner setup notes for skins, chat, portals, AI boundaries, and safety defaults |
| [`TRACEABILITY.md`](TRACEABILITY.md) | All **471** mechanics (221 original + 250 added); its Phase 0 `not started` labels are explicitly marked stale pending per-item audit |
| [`BUILD.md`](BUILD.md) | Exact build and verification commands, and what is/isn't proven |
| [`ENDPOINTS.md`](ENDPOINTS.md) | How to add as many AI models as you want: endpoint, model-id, api-key, no limits |
| [`COMMANDER.md`](COMMANDER.md) | The Null Commander: portal spawn, one shared skin, loadout GUI, mace + elytra PvP library |
| [`BUILD_TUTORIAL.md`](BUILD_TUTORIAL.md) | Step-by-step: from a fresh machine to a running `NullArmy.jar` |
| [`RELEASING.md`](RELEASING.md) | How to publish: versioning, CI workflow, checksums, GitHub releases, licence choice |

> **Release warning:** the only adapter in this checkout targets Paper 1.21.11. Earlier snapshots have recorded build and Paper smoke-test results, but the current working-tree edits have not been verified on a live server. This sandbox has no Java runtime, so use the checked-in Gradle wrapper and GitHub Actions build for compilation. A green CI job is not a stable-release approval.

### ⚠️ Verification status of the code

| Historical evidence | Current working-tree status |
| --- | --- |
| Prior CI/build and Paper 1.21.11 smoke checks are recorded in `STATUS.md` | The current edits have not been compiled or executed |
| Core and runtime self-tests exist, including coverage for packet-listener readiness | `./gradlew test` cannot start here: no `java` executable / `JAVA_HOME` |
| The version-specific adapter has been exercised in earlier snapshots | This does not verify the current diff or any future Paper version |
| Skin-chain tests use local stubs for deterministic cases | External MineSkin/Mojang access and visual rendering need a real server/client |

Use [`BUILD.md`](BUILD.md) for the build and verification workflow; use `STATUS.md` for the
specific historical results and the current blockers.

---

## The Non-Negotiables

Six rules that override every other feature request. A feature that breaks one of these does not ship.

### 1. Zero runtime dependencies

No Citizens. No ProtocolLib. No WorldEdit. No pathfinding library. No AI SDK. No shaded third-party JSON parser downloading itself at runtime.

NullArmy currently targets **Paper only**, using the Paper API plus its own NMS and packet code. The JDK HTTP client is reserved for future AI calls. JSON is handled by a small, strictly bounded in-project codec. There is no hidden download, no runtime library install, no telemetry phone-home.

NMS code lives behind version adapters. **No single NMS package, mapping set, packet shape, or constructor is assumed to work across every 1.21 patch version** — each claimed version is built and tested separately.

### 2. Pure vanilla mechanics + real resource accounting

Every Null action must correspond to something a real survival-mode Java player could do under the server's active rules. Normal range, line of sight, movement, cooldowns, collision, durability, hunger, status effects, ammunition, block placement, and dimension limits all apply.

Forbidden: magical teleports, invisible movement, wall phasing, instant construction, giant fusions, fabricated loot, free ammunition, infinite durability, AI-generated items.

The **NPC inventory is authoritative**, backed by an auditable item ledger. The `/null loadout` screen is a *blueprint*, not a duplicator — gear must come from items donated by the summoner, legitimate drops, real crafting, honest trades, or explicitly configured storage. Save persists the candidate before changing the live loadout; Cancel or closing without saving restores the player's inventory to how it was when the editor opened.

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
| **Temporary summon portal doorways** | ✅ | Wherever a safe site exists, Nulls physically walk through temporary obsidian/`NETHER_PORTAL` doorways; modified blocks are restored after use and custom portal travel is cancelled. There is no teleport. If no safe doorway site exists, a Null emerges at verified open ground with effects and the fallback is reported. |
| **Potion combining / mixing** | ❌ | Vanilla has no potion-mixing action. Legal brewing and tactical *sequencing* of separate potions only. |
| **Ender pearls / chorus fruit to reposition** | ⚠️ | The explicit owner-only `/null tp` cannon is the sole Ender Pearl exception: each live Null must spend its own real pearl, and a vanilla projectile falls into a same-world, loaded, collision-safe area. No pearls are created, no AI/path recovery can teleport, and chorus fruit is not used. |

> **Ordinary movement never teleports.** A Null can still get genuinely stuck; it diagnoses, replans, and escalates rather than vanishing and reappearing. The `/null tp` Ender Pearl cannon is a separate, explicit owner command—not path recovery.

---

## Talking To Them

Two ways, and neither needs a slash:

- **Orders.** Start a chat line with a wake word (`null`, `nulls` or `commander` by default -
  `chat.wake-words` in `config.yml`) and the rest is treated as a subcommand:
  `null attack Steve`, `null kill Steve`, `null ban Steve`, `null eliminate Steve`, `null come`,
  `null stop`, `null heal`, `null menu`, `null follow me`, `null go away`. Natural phrases and
  synonyms are mapped and the order goes through the **same** executor as typing `/null` (so
  permissions, caps and policy gates are identical). PUBLIC mode leaves recognized chat orders
  visible; OFF mode executes them silently and leaves unrelated chat alone. An open private
  conversation consumes normal chat until you say `exit`; use slash commands while it is open.
  Each player gets 20 chat orders a minute by default (`chat.commands-per-minute`).
- **Conversation.** `/null chat public` enables public Commander replies to messages addressed
  by name or wake word, formatted `Name: message`. `/null chat private` (or `/null chat commander`)
  opens a private Commander channel; your next messages go only to him, with a rolling context.
  `/null chat null` opens a Null session only when `chat.commander-only-conversation: false`.
  `/null chat off` disables conversation routing for you; army orders still work.

Conversation can use a model. **The plugin never requires one** - with `ai.enabled: false` the
characters still answer a few lines locally("Commander on deck") and `/null ai` states plainly
why the rest is unavailable. To switch a model on, add an endpoint under `ai.endpoints` and point
`ai.default-endpoint` at it; the ChatCommander role uses the same config, key resolution, timeout
and rate limiting as every other AI role. Replies are single-line, colour-code-stripped and length
capped (`chat.max-reply-chars`) so a model can never inject formatting into chat.

## How Summoning Works

1. **Trigger.** Use the plugin-issued Goat Horn named **Null** (its instrument is set to vanilla **Call**) or a Totem of Undying tagged **Totem Of Null**. The horn plays the Call sound when right-clicked; both items then open the same protected summon prompt. Usable summon items only come from an explicit owner/admin action, never a spontaneous grant.
2. **Ask.** The plugin prompts the *authorized summoner* for the desired Null count in chat. The pending request is bound to that player, expires after a configurable timeout, validates the answer, supports cancel/help, and **ignores chat from any other player**.
3. **Enforce.** Minimum two Nulls if the squad needs two commanders. A configurable hard cap and resource/performance budget apply. Excessive counts are rejected with a clear message — **never** a partial surprise army.
4. **Verify.** World permission, loaded/safe ground, nearby hazards, owner limits, and spawn spacing are checked before anything commits. If no safe location exists, NullArmy explains the failure rather than spawning through a wall.
5. **Emerge.** **≥15 visual portal effects** fire when enabled. Where safe, temporary obsidian/`NETHER_PORTAL` doorways are built and Nulls physically walk through and out; changed blocks are restored. If a safe doorway is unavailable, they emerge on verified open ground with an honest fallback report. No teleport or Nether/End travel is used for summoning.

### Identity

| Property | Behaviour |
| --- | --- |
| **Skin** | Pure black player skin from a configured valid texture/profile or a documented bundled/owner-supplied asset. If the target client/profile mechanism can't render it, NullArmy says exactly what setup is required rather than promising it. Real players' skins are never touched. |
| **Name** | Unique random 16-character alphanumeric profile/display name, beginning with a letter and containing at least one digit (for example, `a1B2c3D4e5F6g7H8`). Duplicate checks include the live Nulls and Commander. |
| **Commanders** | Exactly **two** designated Commanders for any squad of two or more. Roles are stored — not randomly reassigned each tick or restart — with orderly succession if one is permanently lost. |
| **Body** | Realistic health, armor, inventory, equipment, hitboxes, animations, sounds, and damage. No hidden invulnerability, no fake health. |

---

## Commands

All commands are permission-checked with tab completion, clear feedback, and audit logs for destructive/admin actions. Offline and ambiguous targets are handled safely.

| Command | Permission | What it does |
| --- | --- | --- |
| `/null menu` (aliases `/null m`, `/null gui`) | `nullarmy.gui` | Opens the NullArmy command center: a themed 54-slot chest GUI with a gradient title, live squad/system cards, permission-filtered actions and pagination. Every click and drag is cancelled, so **nothing in it can be taken, moved or duplicated**. Each action dispatches through the same `/null …` command path as typed commands. |
| `/null horn` | `nullarmy.summon` | Gives you a real Goat Horn named **Null**, set to the vanilla **Call** instrument, enchanted (Unbreaking I) with `HIDE_ENCHANTS` for the glint, and tagged with persistent data. Right-click plays the Call horn sound and asks *"How many Nulls should come?"* in chat. |
| `/null totem` | `nullarmy.summon` | The **Totem Of Null**: a real Totem of Undying made the same way, with the same chat-count flow. |
| `/null reload` | `nullarmy.admin` | Re-reads `config.yml` without a restart, re-arms the NMS spawn breaker and tells every subsystem to re-read its settings. A missing `config.yml` is recreated; an existing one is **never** overwritten. |
| `/null come` (alias `/null bring`) | `nullarmy.follow` | Walks your squad to your position; ordinary movement stays physical and never teleports. |
| `/null tp` | `nullarmy.admin` | Gives a one-use, nearly-broken fishing rod. When the hook sticks in a block and you reel it in, each live Null (and your Commander, if present) spends one real Ender Pearl; vanilla pearls fall from varied heights and spaced positions into a loaded, collision-safe area. Same-world only; no free ammunition or summon teleporting. |
| `/null guard` | `nullarmy.follow` | Holds position and watches. |
| `/null formation <line\\|wall\\|rank\\|column\\|square\\|wedge\\|phalanx\\|arrow\\|encircle\\|turtle>` | `nullarmy.follow` | Uses fixed, non-overlapping cells. `wall` places a wide walking rank in front of the owner; other styles include column, wedge, phalanx and encircle. |
| `/null list` · `/null info <id\|name>` | `nullarmy.admin` | Every live Null with health and position; then one Null in detail. |
| `/null heal` · `/null equip` · `/null drop` | `nullarmy.admin` | Top the squad up; hand your held item to your first Null (the item **leaves your hand**, so this cannot duplicate); empty the squad's inventories into the world as real drops. |
| `/null portals` · `/null clearskins` | `nullarmy.admin` | Play the portal visual where you stand (cosmetic only); forget cached skins and resolve them again. |
| `/null version` · `/null help` · `/null debug` | — / — / `nullarmy.admin` | Plugin, adapter and server version; the full command list; guard state, subsystem failures and tracked entities. |
| `/null withercannon` (alias `/null cannon`) | `nullarmy.admin` | **Opt-in.** Fires a TNT minecart that arcs into the sky, opens portals at the apex and drops TNT. Off unless `wither-cannon.enabled` **and** `policy.explosives-enabled` **and** `policy.wither-enabled` are all true and you hold the configured permission. Block damage needs a fourth opt-in (`policy.griefing-enabled` **and** `wither-cannon.blocks-damage`); without it, explosion and entity effects still occur but blocks are protected. |
| `/null airdrop [count]` | `nullarmy.admin` | **Opt-in.** Sky portals open above you and ground portals around you, TNT drops from the sky, and the squad arrives. With `airdrop.drop-nulls-from-sky: true` the Nulls fall under real gravity and **do** take fall damage; with it false they emerge on ground the adapter verified as safe. |
| `/null chat <public\|private\|null\|commander\|off\|status>` | `nullarmy.chat` | Choose public Commander replies (`Name: message`), open a private channel, or turn conversation routing off. Orders continue to work in every mode. |
| `/null ai` | `nullarmy.admin` | Whether a model is configured, enabled and actually reachable - and, when it is not, the reason in one line. |
| `/null portal [player]` | `nullarmy.admin` | Your Nulls walk **through a portal** to you, or to a named player: effects at both ends, the arrival spot verified collision-safe first, nobody arrives mid-fall. The one deliberate, opt-in exception to the no-teleport rule (`mechanics.portal-travel`). |
| `/null tactics <aggressive\|balanced\|defensive>` | `nullarmy.attack` | Changes the standoff a squad actually keeps: 1.2 / 2.0 / 4.5 blocks. Not cosmetic - the steering uses it. |
| `/null emote <wave\|salute\|nod\|point\|dance\|sit>` · `/null greet [player]` | `nullarmy.admin` / `nullarmy.follow` | Visible body language: your Nulls turn, step and make the sounds a player would hear. A greeting only reaches 24 blocks, because a distant Null waving is a lie. |
| `/null inv` | `nullarmy.admin` | What your Nulls are carrying, read-only - a summary per Null, never an editable inventory. |
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
2. If no schematic matches, a deterministic local planner proposes a strict JSON block plan. No AI endpoint is responsible for designing or building. Dimensions, palette, block states, rotations, material costs, support rules, world bounds, protection, and **every single placement** are validated locally before execution.
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
| **F. Stealth, deception & lifelike behaviour** | 21 | 201–221 | Crouch approach, tall-grass concealment, darkness discipline, armor-removal tradeoff, potion invisibility, honest identity tells (**no packet hacks to hide name tags or particles**), sound discipline, line-of-sight breaking, cover scouting, light discipline, armor-stand decoys, banner/sign signaling, campfire smoke, feigned retreat, bait discipline, terrain ambush, watch rotation, shift-signal vocabulary, intentional attention/posture (head and body together), chat psychology, organic idle loop |
| **G. Advanced combat, damage & equipment depth** | 50 | 222–271 | Enchantment matchups (Smite/Bane/Impaling/Density/Breach), armour-value targeting, Thorns recoil, curse handling, Spectral Arrow marking, Lingering clouds, Slow Falling, Turtle Master, **Spears + Lunge (1.21.11)**, Warden withdrawal, hostile-projectile dodging |
| **H. Squad command, coordination & logistics** | 40 | 272–311 | Bounding overwatch, sentry rotation, chokepoint control, buddy pairs, medic/ammo/engineer roles, fall-back staging, time-of-day & weather planning, cargo triage, dead-drop caching, pursuit abort |
| **I. Mounts, traversal & mobility** | 45 | 312–356 | **Nautilus + Zombie Nautilus + Nautilus Armour (1.21.11)**, **Zombie Horse & Camel Husk (1.21.11)**, mounted water crossing, Soul Speed, Swift Sneak, honey-block sliding, ice friction, kelp elevators, rail switching, minecart spacing |
| **J. Survival, crafting, economy & SMP life** | 50 | 357–406 | XP/Mending allocation, anvil prior-work cost, grindstone, netherite smithing, armour trims, Bundles, Shulker Boxes, **Crafter**, **Shelf / Copper Chest / Copper Golem (1.21.9)**, fuel economy, full farm & food chains |
| **K. Mining, building, redstone & automation** | 40 | 407–446 | Ancient debris, Piglin aggro on nether gold, **Sculk Sensor noise discipline**, Shrieker avoidance, trial chambers & vaults, **Copper Bulb (1.21.9)**, cobble/basalt generators, dripstone lava farms, sorting arrays, item lifts, spawn-proofing |
| **L. Stealth, perception, scouting & lifelike behaviour** | 25 | 447–471 | Vibration-aware movement, wool-dampened routes, sound-cue interpretation, spyglass scouting, cartography, dead reckoning, counter-scouting, particle/glint tells, signal fires, rest rotation |

### Idle behaviour

Nulls may rest, regroup, signal nearby allies or perform another explicit, safe task, but they do not
make random ambient glances or turn their heads independently. Only intentional `mind.attention`
and explicit combat/formation looks may change facing, with head and body yaw kept together.

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
├── build/          # deterministic local plans, schematics and validation
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

This source currently contains one **unverified Paper 1.21.11** adapter. Spigot compatibility and all
other Minecraft versions are unsupported. Paper 1.21.11 is end-of-life.

| Server | Adapter | Status |
| --- | --- | --- |
| Paper 1.21.11 | `v1_21_11` | Source present; compile/runtime validation pending |
| Other Paper versions / Spigot | — | Unsupported |

**Do not infer compatibility from a successful compile.** Each exact server build must be smoke-tested
and recorded here before it is advertised.

---

## AI Endpoints

You can add **as many AI models as you want** — each one is three lines: an `endpoint`
(base URL), a `model-id`, and an `api-key`. There is no limit. Copy the block, rename it, repeat.
See **[`ENDPOINTS.md`](ENDPOINTS.md)**.

All network work is async, rate-limited, time-bounded, and cancellable.

> **A missing or unreachable endpoint must never stall the server or stop basic Null behaviour.**

### Optional: routing different jobs to different models

**You can ignore the table below entirely.** If you just add endpoints, every decision uses your
`default-endpoint`. The table is only for splitting jobs across models — e.g. combat on a fast
local model, chat on a large cloud model.

| Role | May do | May **never** do |
| --- | --- | :-: |
| **ChatCommander** | Produce short chat text | Issue commands, change targets, alter inventories, ban players, authorize actions |
| **CombatTactician** | Recommend a high-level intent from a strict enum (`hold`, `approach`, `flank`, `retreat`, `shield`, `ranged volley`, `resupply`, `regroup`) | Deal damage directly, bypass the local combat validator |
| **BuilderAgent (legacy key)** | Connectivity test only; it receives no build-planning request | Design or execute a build, or return an action-bearing block plan |
| **PathfinderCore** | Suggest a destination/route preference from a **sanitized** snapshot | Move the NPC; supply hidden-world or through-wall data |
| **ScoutObserver** | Summarise what the squad can actually see: contacts, terrain, hazards | Receive hidden entities, inventories, or through-wall data |
| **ThreatAnalyst** | Rank threats from visible evidence (gear, position, numbers) | Read hidden health, inventories, or unobserved targets |
| **LogisticsQuartermaster** | Propose loadout priorities, resupply requests, item allocation | Create, duplicate or delete items; mutate the ledger |
| **MedicTriage** | Propose triage order and treatment type for reported ally state | Heal directly, grant effects, or know health it wasn't told |
| **FormationTactician** | Propose formation type, spacing, orientation, anchor | Override collision, hitboxes, or hard separation |
| **RedstoneAnalyst** | Interpret redstone from line-of-sight evidence only | Read hidden wiring, bypass visible-only perception |
| **MiningForeman** | Propose which visible blocks to mine, in what order, with which tool | X-ray for ore, see through blocks |
| **IdleBehaviourDirector** | Propose bounded idle behaviours so Nulls never freeze | Spam animations, override danger checks |
| **GuardianAuditor** | Review *other agents'* proposals for rule violations | Approve its own output, override the validator |

> **Where do I add endpoints?** `config.yml` → `ai.endpoints:`. Define **as many as you want** —
> each needs an `endpoint` (base URL), a `model-id`, and an `api-key`. Write the key as
> `env:VARNAME` and only the variable *name* is stored; the real key is read from the server
> process at call time. Full guide: **[`ENDPOINTS.md`](ENDPOINTS.md)**.

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

## Works with zero AI models

Core implemented behaviors have deterministic local paths when no endpoints are configured, but
the full 471-item catalogue is not complete. Building always uses local deterministic planning and
server-side validation; no AI endpoint is allowed to design or execute a build. The current
Commander combat integration selects only supported mace choices. Run `/null features` for the
exact list on the server; model-backed conversation and other explicitly enabled roles remain
optional.

Endpoints are an upgrade, not a requirement: **[`ENDPOINTS.md`](ENDPOINTS.md)**.

---

## The Null Commander

`/null commander` summons one named Null that steps through a temporary portal doorway when a
safe site is available. It can use an account skin, signed texture or custom PNG signed through
MineSkin; carries a full Bukkit-item loadout editable in `/null loadout`; and is damageable like
other Nulls. `PvpArsenal` has 25 planning entries, but the current live integration executes only
supported mace weapon choices through vanilla combat. Elytra flight controls, pearl movement,
water placement and other tactics are not yet implemented.

Built with **no dependencies**: the skin lookup uses the JDK's own HTTP client, the GUI is a plain
chest inventory, persistence is Bukkit's YAML, and the technique library is pure Java in `core`.

Full detail: **[`COMMANDER.md`](COMMANDER.md)**.

> **⚠️ Written, not run.** None of this has ever been compiled — see
> [`COMMANDER.md`](COMMANDER.md) for exactly what is and isn't done.

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
| **7** | Builder, mining, redstone & destructive systems — `/schematics` parser, deterministic local plan schema, per-block physical placement, mining, traps, redstone, guarded TNT/cannons | ⬜ |
| **8** | External AI agents — one role at a time, strict schemas, circuit breaker, privacy controls, deterministic fallback, adversarial tests | ⬜ |
| **9** | Performance, compatibility & release — version matrix, profiling, item-conservation and restart tests, documentation review, traceability audit | ⬜ |

**Definition of Done:** the declared feature set is traceable, item conservation is proven, authoritative movement/combat/building works on every advertised version, ordinary movement never teleports, the documented Ender Pearl and portal exceptions are explicitly tested, no-clipping invariants pass, endpoint failure is harmless, destructive features are opt-in and protected, resource limits are documented, **and the project builds from a clean checkout with no undeclared runtime dependencies.**

---

## Building

The repository includes a Gradle wrapper and a GitHub Actions build. With JDK 21 and access to
PaperMC's Maven repository, run:

```bash
./gradlew clean build --no-daemon
# → plugin/build/libs/NullArmy-<version>.jar
```

The build runs the dependency-free core test suite and produces a Paper-only, Mojang-mapped plugin
JAR. GitHub Actions uploads the JAR and SHA-256 checksum as a short-lived workflow artifact; it does
not publish a GitHub Release. See [`BUILD.md`](BUILD.md) for verification steps.

**A built JAR is not a finished plugin release.** The code still needs real-server testing, and most
of the documented gameplay is not implemented. There is no stable v1.0.0 release.

---

## Contributing

Contributions are welcome. The codebase is actively implemented, but the catalogue is incomplete; use [`STATUS.md`](STATUS.md) and [`TRACEABILITY.md`](TRACEABILITY.md) for the current evidence and the spec in [`NullArmy_Master_Prompt.md`](NullArmy_Master_Prompt.md) for remaining requirements.

- finding a mechanic that vanilla cannot actually do (→ it belongs in the [vanilla-reality gate](#the-vanilla-reality-gate))
- finding an item-conservation hole — a path where an item could be duplicated or deleted
- finding an unintended teleport — any relocation outside the documented vanilla Ender Pearl cannon or separately configured portal-travel path
- tightening an acceptance criterion so it becomes genuinely testable

**Ground rules for any contribution:**

- No new runtime dependencies. Ever.
- No arbitrary teleport. Never for pathfinding recovery or "unsticking"; only the documented owner-triggered Ender Pearl cannon and separately configured portal-travel path are exceptions.
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


/div>


