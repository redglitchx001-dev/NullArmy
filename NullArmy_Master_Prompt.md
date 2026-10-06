# NULLARMY — COPY-READY MASTER PROMPT FOR A SENIOR CODING AI

**Project:** NullArmy  
**Copyright:** RedGlitchX  
**Target:** Spigot/Paper Minecraft 1.21.x through 1.21.11 (verify each exact server build before claiming support)  
**Purpose of this document:** A detailed design and implementation brief. Do not treat it as permission to fake vanilla mechanics or bypass server rules.

---

## ACTIVE OWNER CORRECTIONS — 2026-10-06

These direct owner decisions supersede conflicting older wording below and in the expansion catalogue:

- **Building is deterministic and local.** AI must not design, approve, place, or otherwise be responsible for building. Local planners and server-side validation own construction. Configurable endpoints may serve other explicitly AI-enabled abilities/roles.
- **No ambient head motion.** Remove random glances/scans and head-only yaw. Only intentional `mind.attention` and explicit combat/formation looks may turn a Null; head and body turn together.
- **Pursuit is order-gated.** Nulls run toward enemies only on an explicit attack/shoot/hunt order. Retaliation may defend at melee range but does not acquire, face, or close toward a distant attacker.
- **Temporary real summon doorways are required.** Where safe, build a temporary obsidian frame with a real `NETHER_PORTAL` opening; Nulls physically walk through/out of it and the modified blocks are restored. Limit a doorway to one or two Nulls (hard maximum 2). This supersedes the cosmetic-only portal wording below. It does not authorize Nether/End travel or teleporting during summoning.
- **Dedicated `/null tp` exception:** this exact command gives the owner a nearly-broken fishing rod. When the hook sticks in a block and is reeled in, one real Ender Pearl per live Null (and the owner's Commander, if present) is launched as a falling projectile from varied heights and spaced positions over the target area. Each Null must spend its own pearl; no ammunition is fabricated, the target must be in a loaded safe area in the same world, and ordinary movement/summoning remain physical. This is not a general teleport API or AI action.
- **Chat modes:** support per-player `/null chat public|private|off` (and the Commander channel), with public replies formatted exactly `NAME: MESSAGE`.
- **Skin registration:** support `plugins/NullArmy/skins/null.png` by signing through MineSkin or consuming an already-signed texture pair. Unsigned PNG bytes are not a Minecraft skin. `env:NAME` API-key references are supported; resolved secrets must never be logged or exposed.
- **Kit and safety:** the Commander receives the shared Null kit plus Elytra; ordinary Nulls get the same kit without Elytra. No armor trims except a white trim on the Commander's chestplate. The Commander remains damageable. `/kill <NullName>` must work, Null deaths use vanilla drops and kill/death messages, and Totem pops plus shutdown remain silent.
- **Do not alter server-list values.** Leave the server MOTD, displayed player counts, max players, and sample untouched. `/null reload` must actually reapply plugin configuration.
- **Names:** generate random usernames from letters and digits, within the target version's legal profile-name constraints.
- Keep ordinary vanilla actions, explosions, and Wind Charges functional; do not globally cancel them to fake behavior. Explicit safety policies, owner/team protection, and destructive-operation configuration still apply. Ambiguous requests such as “make everything true” are not permission to enable protection-sensitive/destructive options blindly.
- The owner requested implementation of all `.txt` and `.md` inputs, including the 471-mechanic catalogue. Work incrementally, flag unresolved conflicts, and never claim the full catalogue is complete based on a partial pass.

---

## YOUR ROLE AND DELIVERABLE

You are a senior Java, Paper/NMS, networking, gameplay-AI, and Minecraft systems engineer. Design and implement **NullArmy**, a high-quality, configurable SMP plugin that creates physically simulated, player-like Null NPCs. The Nulls should behave like coordinated, skilled survival players—not invulnerable mobs, magical units, or client-side illusions.

Before writing implementation code, inspect the existing repository and report its build system, Java/Paper target, current architecture, and any blockers. If there is no repository, propose a compact project structure. Then implement in the phases described in **Exhaustive Chunking Protocol**. Do not claim a mechanic works until it is implemented and tested on its declared target server version. Keep a feature-traceability list with each item marked `implemented`, `partial`, `experimental`, `blocked by vanilla`, or `not started`.

The finished project must include source, build instructions, configuration documentation, admin/player command documentation, a permissions list, version compatibility notes, tests or reproducible server test procedures, and a clear list of mechanics that are intentionally disabled or blocked by vanilla rules.

---

## 1. NON-NEGOTIABLE CORE RULES

### 1.1 Zero plugin dependencies

- Use no Citizens, WorldEdit, ProtocolLib, pathfinding library, AI SDK, or other third-party runtime/plugin dependency.
- Use custom NMS and packet handling where needed. Paper/Spigot APIs supplied by the host server are the platform, not an added plugin dependency.
- Use the JDK HTTP client for external AI calls. Do not require an AI vendor SDK. If JSON support is needed, use a small, strictly bounded in-project codec or another approach that introduces no third-party runtime dependency.
- Keep NMS code isolated behind version adapters. Do not assume one NMS package, mapping set, packet shape, or constructor works across every 1.21 patch version. Build and test each claimed version separately.
- No hidden download, runtime library installation, telemetry service, or external service other than explicitly configured AI endpoints.

### 1.2 Pure vanilla mechanics and resource accounting

Every Null action must correspond to an action a real survival-mode Java player can perform under the active server rules. Nulls must obey normal range, line of sight, movement, cooldown, collision, durability, hunger, status effect, item, ammunition, block-placement, and dimension constraints.

- No magical teleporting, invisible movement, wall phasing, instant construction, giant fusions, fabricated loot, free ammunition, infinite durability, or AI-generated items.
- The NPC inventory is authoritative. Every consumed, fired, placed, dropped, traded, repaired, or picked-up item changes that inventory by the correct amount. Maintain an auditable item ledger for transfers and consumption.
- A loadout GUI is a **loadout blueprint**, not an item duplicator. Gear must come from items donated by the summoner, collected from legitimate drops, crafted from genuinely acquired resources, traded for, or withdrawn from explicitly configured storage.
- No Ender Pearl, chorus-fruit, command, plugin shortcut, or portal effect may be used for automatic movement, path recovery, or summoning teleportation. The one explicit Ender Pearl exception is the permission-gated `/null tp` fishing-rod cannon above: it consumes one real pearl per live Null and uses a same-world loaded safe landing area. Do not use Nether/End portals for Null travel unless separately authorized by the owner.
- Potions, enchantments, and status effects are allowed only when the Null has the required real vanilla item and the effect is legal under the server version and game rules.
- External agents may propose intent; only local, deterministic, server-side validation may authorize and execute it.

### 1.3 No clumping, stacking, or clipping

Use a formation-aware Boids/Flocking/local-avoidance system with hard collision constraints. Each NPC must maintain a legal, non-overlapping body position and a personal-space radius. Combine separation, alignment, cohesion, formation-slot attraction, obstacle avoidance, and target pursuit with bounded steering forces. Formation goals must never override hitboxes or collision.

If a route or formation is too narrow, the Nulls must queue, spread out, reroute, wait, or abandon the maneuver. They must not occupy the same coordinates, walk through solid blocks, or use teleportation to repair bad spacing. Avoidance must include other Nulls, players, mobs, vehicles, and world geometry. Test sustained crowding at doors, bridges, corners, boats, and combat chokepoints.

### 1.4 Performance: “zero lag” as a measurable engineering target

Treat “zero server lag” as a strict goal of **no unbounded work, no main-thread network calls, no runaway entity/packet work, and no noticeable TPS degradation at documented scale**. No software can honestly guarantee mathematically zero cost. Profile the plugin and publish measured limits. Every costly system needs configurable caps, a per-tick budget, cancellation, and a low-cost fallback.

### 1.5 Vanilla-reality gate for requested ideas

Do not silently simulate impossible mechanics. Explain the limitation and provide the closest genuine vanilla behavior:

- TNT does **not** mine obsidian; Nulls need an appropriate pickaxe and survival mining time. TNT must never be presented as an obsidian-breaching shortcut. Bedrock and other unbreakable blocks remain unbreakable.
- A Wither Skeleton Skull is a placeable item, not a throwable projectile. Never make a Null throw a skull as if it were a snowball. If enabled, the vanilla alternative is physically assembling the real Wither structure from owned soul sand/soul soil and three owned skulls, subject to a separate destructive-content opt-in and world rules. This is dangerous and must be off by default.
- A “Wither cannon” is not guaranteed to work on every layout or server rule set. Only permit a real, physically assembled, inventory-funded contraption; report failure rather than faking a launch.
- Removing armor does not make a player invisible. It may reduce armor appearance/glint but also removes protection. Never grant invisibility from armor removal.
- Wind Charges can create a genuine impulse but do not provide sustained flight. Sustained gliding requires equipped Elytra and legitimate firework rockets, with durability, launch, collision, and landing handled normally.
- A packet-only fake player cannot reliably participate in authoritative server combat, collision, block placement, inventory, or world physics. Use a server-authoritative NMS-backed gameplay entity for actions that affect the world; use packets for appearance, player profile/list presentation, animation, and other cosmetic details. If a target version makes a required behavior impossible, document it rather than shipping a visual-only fake that appears functional.
- **Owner override:** summon effects may be paired with a temporary, real obsidian/`NETHER_PORTAL` doorway at a verified safe site. The Null must walk through it, every changed block must be restored, and portal travel to another dimension remains disabled. If a safe doorway cannot be built, use verified safe ground and report the fallback; never teleport.
- “Potion combining” means legal brewing and tactical sequencing of separate potions. Do not invent a potion-mixing action that vanilla does not have.

---

## 2. VERSIONING, ARCHITECTURE, AND SERVER AUTHORITY

1. Support the stated 1.21.x–1.21.11 range only through explicit version adapters and a tested compatibility matrix. Do not promise every patch version based on a successful compile against one version. Record mappings/builds tested.
2. Keep gameplay state authoritative on the server. Packets are not proof that an NPC hit, picked up an item, placed a block, or consumed a resource. Validate all actions against server state.
3. Separate modules or packages for: version adapters; NPC entity/profile/skin; inventory and item accounting; commands and permissions; local perception; navigation; flocking/formations; combat; building/redstone; persistence; AI endpoint clients; configuration; telemetry/debugging; and tests.
4. Perform world reads and all world mutations on the correct server thread. Async work may process immutable snapshots or pure calculations only. Revalidate every result on the server thread before acting.
5. Do not force-load chunks. Do not scan unbounded regions. Do not make an NPC path through an unloaded chunk unless the server has already loaded it naturally and the configured policy permits it.
6. Persist NPC identity, owner, commander status, squad, inventory, equipment, current objective, last-safe location, and relevant timers without duplicating drops on restart. Recover cleanly from chunk unload, plugin disable, server restart, player disconnect, or endpoint timeout.
7. Use server game rules, protection plugins’ available standard hooks where feasible without depending on them, world allowlists, and configured claim/protection regions. If a build cannot determine whether an area is protected, fail closed and ask the owner.
8. Provide configuration for NPC count limits, per-world enablement, damage/PvP policy, griefing, block placement, explosives, Wither spawning, loot pickup, AI endpoints, memory/time budgets, visuals, command permissions, and retention/logging.

---

## 3. SUMMONING, IDENTITY, AND OWNERSHIP — REQUIRED FEATURES

### Summoning

- Summon through either a real **Goat Horn** configured/named as `Call Horn`, or a real **Totem of Undying** configured/named/tagged as `Totem Of Null`. These are vanilla items; creating or tagging a usable summon item must be an explicit owner/admin action or documented recipe/configuration, never a spontaneous grant.
- After a valid trigger, ask the authorized summoner for the desired Null count in chat. Bind the pending request to that player, expire it after a configurable timeout, validate the answer, provide cancel/help behavior, and prevent chat from another player from answering it.
- Require at least two Nulls if the squad must have two commanders. Apply a configurable hard cap and resource/performance budget; reject excessive counts clearly instead of partially spawning a surprise army.
- Show **at least 15 portal effects per summon event** when visuals are enabled. At verified safe sites, pair effects with temporary real obsidian/`NETHER_PORTAL` doorways; assign at most two Nulls to each, and have them physically walk through/out before restoring the doorway blocks. Never create extra NPCs to satisfy a visual count. A Null that cannot use a safe doorway starts at verified open ground with effects and an honest fallback report; no teleport or Nether/End travel.
- Verify world permission, loaded/safe ground, nearby hazards, owner limits, and spawn spacing before committing. If safe locations are unavailable, do not spawn through walls or teleport; explain the failure.

### Identity and visuals

- Give every Null the configured owner-chosen skin, including the custom asset at `plugins/NullArmy/skins/null.png` when provided. A custom PNG must be signed through MineSkin or supplied as a valid signed texture pair; unsigned bytes are insufficient. Support `env:NAME` for the MineSkin API key and never expose the resolved secret. Do not change real players’ skins.
- Assign a unique random username from letters and digits, respecting the target version’s legal profile-name length and character constraints (include at least one digit); avoid duplicates across online and persisted NPCs.
- Designate exactly two Nulls as Commanders whenever a squad of two or more is created. Store those roles; do not randomly reassign them on every tick or restart. Define orderly commander succession if one is permanently lost.
- Give Nulls realistic health, armor, inventory, equipment, hitboxes, animations, sounds, and damage behavior. No hidden invulnerability or fake health.

---

## 4. COMMANDS, CHAT, GUI, AND FORMATIONS — REQUIRED FEATURES

Implement permission-checked commands, tab completion where appropriate, clear feedback, audit logs for destructive/admin actions, and safe handling of offline/ambiguous targets.

Required user-facing controls:

- `/null gui` — open an inventory/loadout planning GUI. It selects equipment priorities and quantities but never duplicates displayed items. Provide an explicit supply source and show deficits.
- `/null chat [public|private|off]` — select per-player Commander reply visibility/conversation mode; public replies must be `NAME: MESSAGE`.
- `/null attack <player>` — set a physical pursuit/combat objective. The target is not instantly damaged or moved.
- `/null attackx <player>` — adaptive extreme-combat profile: faster tactical reassessment, more coordination, careful resource use, and stronger counterplay; **no cheats, impossible reaction time, hidden information, extra damage, or free items**.
- `/null follow me` — follow the issuing owner using a formation and personal-space rules; never teleport to catch up.
- `Null ban <player>` / `/null ban <player>` — implement only as an explicit, separately permission-gated moderation action with confirmation and audit logging. It is not an AI action and cannot be invoked by ChatCommander or another endpoint. If the server owner does not enable moderation integration, return a clear disabled message. A “ban” must never silently mean an instantaneous combat kill.
- `Null kill <player>` / `/null kill <player>` — interpret as a lethal-combat objective only: the squad must still use ordinary combat and the target can escape, defend, or survive. An operator cleanup/dismiss action must use a separate, explicit confirmation so it cannot accidentally kill or delete real players.
- Accept the equivalent safe natural-language intents when enabled, but require high confidence and confirmation for destructive, moderation, or expensive actions.

Support formations: **line, encircle, square, and shield-wall/Turtle**. Add configurable spacing, orientation, leader/commander anchors, terrain-aware offsets, and orderly transitions between formations. A command changes a goal; it never overrides collision, inventory, pathfinding, or server protections.

### Building system — required

- Trigger with `Null build a <structure>` or an equivalent authorized command.
- Check the plugin-owned `/schematics` folder first. Support a documented, bounded plugin JSON format and, only if safely implemented without WorldEdit, an optional vanilla structure format. Never require WorldEdit.
- If no matching schematic exists, a deterministic local planner may propose a strict JSON block plan. AI endpoints must not design or build. Validate dimensions, palette, block states, rotations, material costs, support rules, world bounds, protection, and every placement locally before execution.
- Nulls physically walk to each location, select the correct block, orient it, swing/use their arm, place it through authoritative vanilla-like placement rules, consume the real block, and wait for the action/cooldown. No instant paste, mass `setType` construction, or invisible worker.
- Use teams to carry and place genuinely available materials. If supplies run out, pause, request supply, or gather/craft only through legal actions. Preserve player builds unless the owner has explicitly enabled the relevant destructive permission.

### Base breaching and explosives — required but guarded

- If a target is behind a wall, first assess legal entrances, doors, visible weak points, mining time/tool, team safety, and server protection. Nulls can mine with the correct tool and ordinary block-breaking time.
- TNT + Flint and Steel is allowed only if the Null has both, the server/world permits griefing, and the owner has enabled explosive tactics. TNT placement, fuse, blast damage, block destruction, ally danger, and retreat must be real. Never use TNT against obsidian as though it breaks it.
- Nulls may construct a dispenser/redstone/TNT cannon only from carried or legitimately acquired parts, physically placing every piece. Validate the circuit and test safely; it may fail. Never silently replace a failed contraption with a scripted projectile.
- Do not throw Wither Skeleton Skulls. If Wither content is explicitly enabled, use only the genuine survival construction described in the Vanilla-reality gate and obtain confirmation before spawning it.

---

## 5. NPC AI, PERCEPTION, NAVIGATION, AND NATURAL BEHAVIOR

### Local decision system

Use a deterministic, inspectable local state machine/utility planner as the final authority. Include states such as: idle, follow, form up, patrol, scout, investigate, combat, retreat, resupply, scavenge, heal, build, mine, cross obstacle, board/steer vehicle, regroup, wait-for-supply, and safe shutdown. Give objectives priorities, timeouts, cancellation, and recovery paths. A stuck NPC should diagnose/replan/escalate, not teleport.

### Fair perception

- The NPC may reason only from legitimate perception: line-of-sight ray checks, visible entities/blocks, audible events with range/falloff where practical, and uncertain last-known positions.
- No x-ray block scans, hidden inventory reads of opponents, through-wall tracking, reading invisible targets from server collections, or AI prompt fields that reveal data the NPC could not perceive.
- Remember observations with confidence and age. Lose certainty as time passes. Do not treat a stale target coordinate as current truth.

### Navigation and physicality

- Use bounded, incremental voxel-aware path planning and local obstacle avoidance. Assign terrain costs for danger, height, liquids, fall risk, fire/lava, darkness, protected areas, and formation disruption.
- Respect legal step height, jump arcs, collision shapes, fluids, doors, fences, ladders, scaffolding, boats, and block placement rules for the exact server version.
- Each movement step must be explainable as ordinary physics. Cap velocity and acceleration. Never snap coordinates or correct a route with teleportation.
- Run Boids in local spatial cells rather than comparing every NPC to every other NPC. Formation-slot attraction is soft; minimum separation and collision are hard constraints.

### Natural behavior

Nulls need not stand motionless without reason. Add bounded, non-spammy idle behaviors such as rest, regrouping, checking carried gear, and agreed short signals. Do not add random ambient glances or head-only turns. Only intentional `mind.attention` and explicit combat/formation looks may change facing, with head and body turning together. Never generate endless shift-spam, collision-causing jumps, pointless item swings, or chat spam; commanded objectives and danger checks take priority.

---

## 6. 221 ADDITIONAL VANILLA MECHANIC IDEAS

Treat the following as a **feature catalogue**, not permission to violate the core rules. Implement in dependency order; gate risky mechanics behind configuration; and mark a feature blocked if the server cannot execute it honestly. Every item requires genuine inventory, legal perception, and authoritative server validation.

### A. Combat and equipment tactics (1–40)

1. **Role-aware weapon choice:** Select sword, axe, bow, crossbow, trident, or mace only if actually carried and suited to range, durability, terrain, and target armor.
2. **Attack cooldown timing:** Respect the Java attack-strength cooldown; avoid fake rapid-fire hits and reward patient fully charged attacks.
3. **Reach and hit validation:** Check line of sight, collision, actual weapon reach, facing, invulnerability frames, and server hit results before spending an attack.
4. **Critical-hit conditions:** Attempt a jump/fall critical only when the real vanilla conditions are met; do not claim a crit while grounded or climbing.
5. **Combo strafing:** Use short left/right movement bursts to dodge and maintain spacing without walking through entities or walls.
6. **Sprint-reset knockback:** Use sprint timing only when it is a legal, useful hit tactic; never manipulate client-only state to manufacture knockback.
7. **Shield timing and block-hit reality:** In 1.21, raise a carried shield against visible attacks and lower it to act; Java swords do not provide legacy sword-blocking, so never fake old block-hitting damage reduction.
8. **Axe shield pressure:** Switch to an axe when a shield user is a credible threat; hit timing must be physical and shield-disable behavior must match the target version.
9. **Shield durability management:** Track wear and switch/retreat before a shield breaks when a safe alternative exists.
10. **Matchup switching:** Compare weapon attack speed, damage, range, armor, and durability; do not assume one weapon is always best.
11. **Bow draw discipline:** Draw a real bow for a realistic interval and release only while a legal shot is available.
12. **Projectile lead:** Estimate target movement and arrow travel time from observed motion; reduce confidence when visibility is poor.
13. **Cover-aware aim:** Recheck the ray before release; do not fire arrows through walls, foliage that blocks the shot, allies, or protected boundaries.
14. **Arrow conservation:** Track stack counts, reserve a configurable minimum for emergencies, and stop bow attacks when empty.
15. **Tipped-arrow gating:** Use each effect arrow only if it is in inventory, legal, and tactically useful; avoid wasting rare arrows on uncertain targets.
16. **Crossbow loading:** Load only with real bolts/arrows, use cover during reload, and track loaded ammunition through death/restart.
17. **Crossbow enchantments:** Apply Multishot, Piercing, Quick Charge, and related behavior only when the actual crossbow has that enchantment and the version supports it.
18. **Firework crossbow:** Use only a real loaded firework rocket, account for blast/ally danger, and do not treat the rocket as infinite anti-air ammunition.
19. **Fishing-rod interruption:** Cast a real rod at visible targets to disrupt movement or an Elytra approach; respect line length, entity behavior, and no-guaranteed-pull limits.
20. **Trident throws:** Throw only a carried trident; calculate travel/return and retrieval risk, and never conjure a replacement.
21. **Loyalty retrieval:** If the thrown trident has Loyalty, account for its real return path/time; otherwise arrange a physical pickup if safe.
22. **Riptide restrictions:** Use Riptide movement only with the proper enchanted trident and valid rain/water conditions; it is not a general flight or teleport ability.
23. **Channeling restrictions:** Use Channeling only with its legal thunderstorm/target conditions and the real enchanted trident; do not invent lightning.
24. **Mace smash attacks:** Use a carried mace and genuine fall distance for smash damage; avoid ally collisions and lethal falls.
25. **Wind Burst mace handling:** Apply Wind Burst only if the carried mace has it; model its real impulse and fall risk rather than turning it into flight.
26. **Sword sweep awareness:** Use sweep attacks only when the real weapon and attack conditions allow them; avoid damaging allies or neutral entities.
27. **Durability-based swapping:** Swap away from a nearly broken weapon before a prolonged fight; never reset durability by changing slots.
28. **Debuff selection:** Use real poison, weakness, slowness, or other legal effects only when carried and appropriate to the target.
29. **Potion effect awareness:** Avoid redundant or counterproductive effects; sequence legal potions rather than inventing potion fusion.
30. **Milk cleanse:** Drink carried milk when removing a harmful effect is more valuable than retaining useful effects.
31. **Golden-apple timing:** Eat a carried golden apple during a safe window, accounting for use time, food saturation, and the actual version’s effects.
32. **Totem use:** Keep a real Totem of Undying in the required slot for its real death-prevention behavior; consuming it is not a summon or respawn.
33. **Lava-bucket combat:** Place/pour carried lava only with valid bucket mechanics, clear ally-safe placement, and a retrieval/escape plan.
34. **Water-bucket counterplay:** Use carried water to extinguish fire, mitigate a fall, counter lava, or alter a route where water placement is legal.
35. **Flint-and-steel pressure:** Ignite a block only when the item is carried, the target is valid, fire spread is permitted, and allies are clear.
36. **End-crystal PvP:** Place a carried End Crystal only on a legal base and in a permitted dimension; compute self/ally blast risk and respect protection rules.
37. **Respawn-anchor PvP:** Use a charged carried anchor only where vanilla permits the intended behavior; never trigger an explosion in a dimension where it is safe/does not explode.
38. **TNT combat:** Place and ignite owned TNT with a real fuse and retreat path; do not script an instant explosion.
39. **Anti-air interception:** Lead visible Elytra targets with arrows, tridents, or loaded firework crossbows and track ammunition; rods are an opportunistic disruption, not a guaranteed sky hook.
40. **Disengage logic:** Break line of sight, block, retreat, or switch to defense when health, durability, hunger, or numerical disadvantage crosses configured thresholds.

### B. Squad tactics and coordination (41–65)

41. **Commander hierarchy:** The two designated Commanders divide command, navigation, and tactical coordination without producing conflicting orders.
42. **Squad roles:** Assign configurable vanguard, shield, ranged, flanker, support, scout, engineer, and reserve roles based on actual gear.
43. **Focus fire:** Concentrate attacks on one valid threat when safe, then reassess; do not let multiple squads chase stale targets forever.
44. **Threat scoring:** Prioritize immediate danger, exposed targets, high-damage weapons, rescuable allies, and objectives using visible evidence only.
45. **Synchronized volleys:** Coordinate bow/crossbow releases by line of sight and loaded ammunition, with staggered reloads and safe firing lanes.
46. **Crossfire lanes:** Reposition ranged Nulls so they do not shoot through allies or each other; pause if no safe lane exists.
47. **Flanking routes:** Send a small group through a physically reachable side route while the main line holds attention.
48. **Pincer timing:** Approach from two legal routes with a timeout and abort if one group becomes isolated.
49. **Shield rotation:** Rotate front-line shield users with rested/healthy carriers; the exchange happens by walking and real item transfer.
50. **Line formation:** Maintain parallel spacing and facing on traversable terrain; compress only as much as collision permits.
51. **Square formation:** Protect a center objective or owner from multiple sides while leaving movement and escape lanes.
52. **Encirclement:** Spread around a target with a legal escape route when configured; never overlap hitboxes or trap a player in protected/safe zones.
53. **Turtle formation:** Form the requested shield-wall with real shields in the correct hand and facing; break formation to avoid hazards or collision.
54. **Owner escort:** Keep a moving protective ring/column around the summoner without body-blocking, suffocating, or trapping them.
55. **Rear guard:** Assign a real geared squad member to watch the retreat path and call visible pursuers.
56. **Reserve squad:** Hold equipped reserves at a safe distance and commit only when the frontline needs help.
57. **Frontline rotation:** Replace injured or low-durability fighters through open lanes rather than entity overlap.
58. **Peel for allies:** Interrupt an attacker threatening a low-health ally, then return to the assigned objective.
59. **Casualty contingency:** If a Null is incapacitated/dead, update formation and recover dropped gear; never resurrect it for free.
60. **Rally point:** Walk to a reachable, visible or previously authorized safe point; cancel if it is occupied or dangerous.
61. **Target splitting:** Allocate separate visible threats to subgroups so the entire army does not chase one distraction.
62. **Protect support roles:** Keep builders, healers, and supply carriers behind legal cover while preserving their own escape path.
63. **Friendly-fire lanes:** Evaluate arrow, potion, TNT, crystal, anchor, lava, and fire trajectories against allied positions before use.
64. **Formation-aware separation:** Preserve distinct personal-space radii while still tracking assigned slots; no stacking when idle or packed.
65. **Nonverbal signals:** Use brief, nearby crouch/turn/hand animations as agreed signals; do not rely on them as a hidden command channel or spam them.

### C. Mobility and traversal (66–110)

66. **Incremental A* path planning:** Plan around real block collision and heights with bounded search; replan a slice at a time.
67. **Local steering:** Blend goal direction with obstacle avoidance, entity avoidance, and terrain cost without crossing solid blocks.
68. **Boids neighborhood:** Compute separation/alignment/cohesion in spatial cells and keep every steering value bounded and deterministic enough to debug.
69. **Hard NPC separation:** Maintain collision-safe minimum spacing even when many Nulls choose the same doorway, target, loot, or bridge.
70. **Chunk-aware routes:** Prefer loaded, safe chunks; never force-load terrain solely for a Null route.
71. **Legal step-up movement:** Step/jump only within actual movement limits and collision shape of the exact server version.
72. **Jump timing:** Jump gaps, low obstacles, or attacks only after checking headroom, landing, velocity, and nearby entities.
73. **Edge sensing:** Detect ledges and avoid walking off unless deliberately executing a safe jump/glide/clutch plan.
74. **Stair navigation:** Traverse stairs/slabs with correct elevation and avoid jittering between block-height interpretations.
75. **Ladders:** Climb only when the ladder is reachable and the NPC can attach to it; dismount safely at the top.
76. **Vines/scaffolding:** Climb genuine climbable blocks with legal approach, grip, and dismount behavior.
77. **Scaffolding and speed/ninja bridging:** Carry and consume real blocks/scaffolding; place one block at a time at a legal reachable face while moving or sneaking, respecting support, edge footing, interaction rate, and recovery—never create an instant bridge.
78. **Door interaction:** Open/close a reachable door using normal interaction; close it only if allies will not be trapped.
79. **Fence gates:** Open and pass through legal gates; do not phase through fences or use packet-only opening.
80. **Trapdoor/crawl routes:** Use a real crawl gap only if the body dimensions and exact movement rules allow it.
81. **Low-ceiling posture:** Crouch where necessary to clear legal spaces; reject paths that require hitbox shrinking beyond vanilla behavior.
82. **Gap-jump evaluation:** Compare jump distance and landing block before jumping; if uncertain, bridge or reroute.
83. **Controlled drops:** Choose safe descent routes; do not walk off a cliff because the destination is close in 2D.
84. **Mining a route:** Break a blocking block only with permission, correct tool/time, and actual inventory; prefer a detour when cheaper/safer.
85. **Hazard costs:** Increase path cost around lava, fire, cactus, powder snow, deep water, hostile mobs, and dangerous drops.
86. **Fall prediction:** Estimate fall damage and clutch window from vertical velocity, height, and available item; do not trigger a clutch too early or too late.
87. **Water-bucket MLG:** Place carried water on a valid block at the last safe time, then retrieve it only if safe and legal.
88. **Cobweb clutch:** Place a carried cobweb into a valid fall path; account for slow descent, retrieval, and vulnerability afterward.
89. **Hay-bale clutch:** Place a carried hay bale before impact to reduce fall damage; do not treat it as immunity.
90. **Slime-block clutch:** Place/use a carried slime block only when its real bounce, fall, and retrieval behavior is useful.
91. **Powder-snow bucket:** Use only with a real bucket and valid placement; respect freezing and escape mechanics.
92. **Wind-charge impulse:** Use a carried Wind Charge only for its real blast/launch impulse; it is not sustained flight or a teleport.
93. **Swimming:** Swim and surface with normal buoyancy and breath; avoid routing through water when air supply is insufficient.
94. **Water currents:** Account for flow, waterfalls, bubble columns, and source blocks when navigating or placing items.
95. **Bubble-column travel:** Use existing soul-sand/magma columns only with genuine buoyancy and air awareness.
96. **Boat deployment:** Craft or carry a real boat and place it on valid water/ice; board by normal interaction.
97. **Boat steering:** Steer around banks, ice edges, hazards, mobs, and other boats; never snap the boat to a route point.
98. **Boat pursuit:** Use a boat to chase visible water targets while preserving room to board, disembark, or recover the boat.
99. **Chest-boat logistics:** Use a carried chest boat only if the NPC owns the required craftable ingredients/boat and can access its inventory normally.
100. **Ice boating:** Exploit faster boat travel on legal ice surfaces only when steering and collision are controllable.
101. **Minecart travel:** Board, ride, and dismount real minecarts on existing or physically built rails; no invisible rail network.
102. **Rail construction:** Lay real rails/powered rails from inventory, with redstone power and support, when the route justifies the cost.
103. **Horse mounting/taming:** Tame a visible horse through repeated real mounting attempts; stop if it is unsafe or the owner disallows animal interaction.
104. **Saddles and horse armor:** Equip only carried, compatible items; account for horse speed, health, jump, and inventory constraints.
105. **Horse route choice:** Avoid cliffs, low tunnels, fire, and tight doors that the mounted horse cannot traverse.
106. **Camel riding:** Use a real, available camel and saddle; model its actual dash cooldown and obstacle limits.
107. **Strider travel:** Ride a real strider with a saddle and use a Warped Fungus on a Stick only if carried and durability permits.
108. **Elytra equipment:** Equip a real, durable Elytra and check takeoff height, headroom, firework stock, and landing corridor.
109. **Rocket-assisted glide:** Consume one real firework per boost; obey flight direction, acceleration, collision, and server version behavior.
110. **Flight and landing plan:** Choose an attainable launch and landing, avoid roofs/trees/water hazards as appropriate, repair/replace only with real materials, and never teleport to recover a failed flight.

### D. Survival, inventory, recovery, and ordinary SMP life (111–160)

111. **Hunger monitoring:** Track food level and saturation; do not sprint/jump indefinitely at critical hunger.
112. **Food selection:** Prefer available, safe food by hunger/saturation and tactical use time; never create food.
113. **Raw-versus-cooked choice:** Consider food poisoning/negative effects and urgency; use raw food only when justified.
114. **Cooking:** Smelt or cook only with a real furnace/campfire/smoker, fuel, ingredients, and safe time; wait for actual completion.
115. **Health triage:** Choose retreat, food, shield, golden apple, potion, or cover according to real health and incoming threat.
116. **Potion inventory:** Track bottle type, duration, effect, stack, and use slot; never assume a potion that is not present.
117. **Brewing:** Brew only at a real brewing stand with valid ingredients, blaze powder, bottles, and recipe; do not instant-generate finished potions.
118. **Self-potion timing:** Drink a potion behind cover or before an expected engagement, accounting for drink time and interruption.
119. **Splash healing:** Throw an owned splash healing potion with a real arc and blast radius; avoid healing an enemy or harming an undead ally.
120. **Ally healing:** Check ally health, effect type, distance, line of sight, and splash coverage before throwing; do not heal at full health by default.
121. **Debuff potion safety:** Aim poison/harming/slowness/weakness potions only at valid visible enemies; evaluate ally overlap and server rules.
122. **Effect sequencing:** Avoid overwriting useful effects or wasting duration; use sequential legal potions instead of fictional combination.
123. **Fire resistance:** Use a real potion before a planned lava/fire crossing or after retreating, with duration and bottle count tracked.
124. **Water breathing:** Use a real potion for a planned underwater task; still track route, visibility, and remaining duration.
125. **Milk removal:** Use carried milk to remove harmful effects only when losing beneficial effects is acceptable.
126. **Drowning response:** Track air, surface access, swimming speed, and escape route; do not stay underwater because a target is nearby.
127. **Freezing response:** Detect powder-snow freezing and respond with legal movement, leather armor if carried, or escape; do not ignore the freeze meter.
128. **Fire response:** Extinguish burning with water or other legal means; avoid sprinting deeper into fire.
129. **Lava response:** Keep distance, bridge or route around lava, use fire resistance only if owned, and never assume a water bucket works normally in the Nether.
130. **Water supply:** Carry/use water only when the dimension and placement allow it; track source recovery and bucket state.
131. **Light awareness:** Evaluate visible light and darkness for threat and navigation; do not use server-wide light data as x-ray perception.
132. **Torch placement:** Place owned torches at a player-like pace to mark routes or improve safety; consume each torch and avoid revealing a stealth approach.
133. **Shelter seeking:** Find or build a reachable shelter from weather/hostiles using actual blocks and a safe exit.
134. **Beds and rest:** Interact with an actual bed only under valid dimension/time rules; never use a bed as a teleport, free respawn, or forced world-time exploit.
135. **Armor choice:** Wear the best available compatible armor for the threat, enchantments, mobility, and durability; equipment must be owned.
136. **Armor wear:** Track each piece’s durability and avoid unsafe swaps that expose the NPC to lethal damage.
137. **Shield wear in survival:** Preserve shields for dangerous encounters and repair/replace only through legal materials and stations.
138. **Tool wear:** Track pickaxe, axe, shovel, hoe, shears, fishing rod, and Elytra durability before assigning tasks.
139. **Repairing:** Use an anvil, grindstone, crafting recipe, or mending only when the actual items, XP, station, and enchantment rules permit it.
140. **Enchantments:** Use only actual enchantments on actual items; obey incompatible-enchantment and repair-cost rules.
141. **Offhand policy:** Use shields, totems, maps, or other legal offhand items without copying items between slots.
142. **Inventory sorting:** Keep deterministic combat/build/survival slot priorities while preserving exact item counts.
143. **Stack conservation:** Enforce conservation on splits, merges, drops, packets, restarts, and trades to prevent duplication or deletion.
144. **Loot pickup:** Pick up only reachable, visible/eligible drops, respecting capacity, ownership, despawn timers, and danger.
145. **Loot priorities:** Prefer ammunition, food, healing, armor, tools, and objective items according to squad need, not arbitrary rarity alone.
146. **Dead-ally recovery:** Reroute to an ally’s actual drops when safe; mark the location as uncertain and do not resurrect or respawn the ally.
147. **Arrow sharing:** A carrier physically drops/transfers owned arrows to an ally who needs them; recipient must pick them up normally.
148. **Potion sharing:** Transfer a potion or throw it using ordinary inventory/projectile rules; decrement the correct stack.
149. **Equipment handoff:** Trade armor/tools through an explicit physical drop/pickup or supported inventory interaction; do not remotely edit inventories.
150. **Commander resupply:** Deliver supplies by walking to the commander and using a real drop/pickup handoff.
151. **Summoner delivery:** Return collected loot to the summoner by physically handing it over or placing it in a designated accessible container.
152. **Storage use:** Open and use reachable containers with ordinary interaction, permissions, capacity, and item accounting.
153. **Trapped-container caution:** Notice visible cues, redstone, or prior knowledge; do not inspect hidden container contents through walls or bypass locks/protections.
154. **Villager trading:** Trade only with a reachable villager, valid offers, owned emeralds/items, and ordinary trade limits/restocks.
155. **Crop planting:** Plant carried seeds/crops on valid farmland with light/water conditions and consume each item.
156. **Crop harvesting:** Harvest mature visible crops and replant only if seeds and time permit; do not instantly regrow crops.
157. **Fishing:** Use a carried rod and real water/float timing; consume bait only where the version requires it and wait for a legal catch.
158. **Wolf taming:** Tame visible wolves with carried bones, respecting failed attempts, ownership rules, health, and finite supplies.
159. **Animal care:** Feed, heal, or lead tamed animals only with actual food/leads and safe routes; do not spawn pets.
160. **Breeding:** Breed compatible owned animals only with the real food items and cooldowns; offspring are genuine entities and count toward configured caps.

### E. Mining, construction, redstone, traps, and environment (161–200)

161. **Correct mining tool:** Select the right tool and tier for the block; calculate break time and drops with the actual enchantments/effects.
162. **Obsidian mining:** Use a valid diamond/netherite pickaxe and real mining time; collect the drop only if vanilla says it drops.
163. **Bedrock/unbreakables:** Recognize unbreakable blocks and stop; never bypass them with NMS edits, TNT, Wither tricks, or fake mining.
164. **Visible-resource mining:** Mine only blocks discovered through legal sight/sound or a physically mined route; no x-ray ore search.
165. **Staircase mining:** Dig a safe staircase with headroom, lighting, support awareness, and a retreat path.
166. **Tunneling:** Mine a narrow route only with permissions, tool, time, light, and careful hazard checks.
167. **Gravity-block awareness:** Account for falling sand/gravel/concrete powder and avoid burying allies or blocking escape.
168. **Torch markers:** Use a consistent, owned-torch route convention and remove/recover markers only by physical interaction.
169. **Placement physics:** Validate replaceability, face, orientation, support, waterlogging, collision, gravity, and block-specific placement rules.
170. **Material cost planning:** Count every block/item needed before a build and stop with a clear shortage report.
171. **Temporary scaffold:** Build a legal scaffold/ladder/bridge, use it, then recover blocks if safe; no invisible scaffolding.
172. **Defensive wall:** Construct a physically reachable wall with real blocks, gates, firing positions, and escape routes.
173. **Trench digging:** Dig a shallow defensive trench only when permitted; consider water, lava, mob movement, and ally mobility.
174. **Water control:** Use buckets/channels to redirect real water flow; account for source blocks, current, flooding, and dimension rules.
175. **Lava casting:** Place owned lava only where safe and allowed; let actual water/lava interactions create their normal blocks and never conjure obsidian.
176. **Frost Walker use:** Freeze eligible nearby water only while wearing genuinely enchanted Frost Walker boots; model the real temporary ice behavior.
177. **Firebreaks/extinguishing:** Remove or wet nearby flammable hazards only with valid tools/items and permission; respect fire-tick rules.
178. **TNT placement:** Place owned TNT on a valid block, account for fuse and blast radius, and warn/retreat from allies and protected property.
179. **Manual ignition:** Ignite TNT/fire using a real Flint and Steel, fire charge, redstone signal, or other valid source actually available.
180. **TNT cannon assembly:** Build dispensers, redstone, supports, water, and TNT from inventory in a physically reachable order.
181. **Cannon calibration:** Test the real signal and trajectory in a permitted area; model timing/physics and accept that the shot may miss or fail.
182. **Misfire handling:** Retreat, wait for fuse resolution, disarm only if genuinely safe, and never erase a primed TNT entity.
183. **Blast-resistance awareness:** Understand which blocks withstand explosions; do not waste explosives against obsidian/bedrock as if they were breakable.
184. **Wither-cannon gate:** Permit only a real, physically built, server-allowed Wither-related contraption; disabled by default and never promise success.
185. **Wither structure option:** If explicitly enabled, assemble the true soul-sand/soul-soil plus three-skull structure from owned items after confirmation; apply normal Wither behavior and block damage.
186. **Skull-item correctness:** Never throw a Wither Skeleton Skull as a projectile. Use a real skull only as a placeable block in a valid structure or decoration.
187. **Redstone reconnaissance:** Inspect visible components and trace exposed wiring by line of sight; do not see hidden redstone through solid blocks.
188. **Tripwire disarming:** Detect visible hooks/string, approach carefully, and break/disarm with legal interaction; consider triggering the circuit while doing so.
189. **String and shears:** Use carried shears or another legal break action where appropriate; consume no item unless vanilla does.
190. **Pressure-plate caution:** Observe visible plates and route around, test from cover, or trigger deliberately with an expendable real item only if owned.
191. **Buttons and levers:** Operate reachable visible controls normally; remember the resulting signal duration/state and avoid blind repeated activation.
192. **Redstone-dust tracing:** Trace visible powered/unpowered lines and adjacent components; do not infer a hidden circuit with server-only block reads.
193. **Repeater timing:** Read visible repeater direction/delay and wait for actual signal timing before moving or building.
194. **Comparator logic:** Use visible comparator mode/input information only; do not pretend to solve arbitrary hidden circuits instantly.
195. **Observer awareness:** Recognize visible observers and likely update triggers; avoid accidental block updates during trap disarm/build where feasible.
196. **Piston hazards:** Detect visible piston faces, moving blocks, and crush paths; never phase through a piston or its payload.
197. **Dispenser/dropper behavior:** Load only real projectiles/items and respect facing, signal, cooldown, and actual dispense behavior.
198. **Hopper logistics:** Use a real hopper/container network only after inspecting visible connections and access; account for transfer timing and permissions.
199. **Doors and iron doors:** Open/close wooden doors normally; use iron doors only through a legal visible redstone/button mechanism or another valid route.
200. **Windows, gates, and breach choices:** Evaluate visible openings, fences, glass, trapdoors, and gates; break only with permission and a correct tool, and preserve a non-destructive route when possible.

### F. Stealth, deception, and lifelike behavior (201–221)

201. **Crouch approach:** Sneak when a quiet approach is useful; trade speed for reduced visibility/noise only to the extent vanilla provides.
202. **Tall-grass concealment:** Use tall grass/foliage as partial visual cover, but do not claim it makes the Null invisible to players.
203. **Darkness discipline:** Prefer shadow/cover at night or in caves while acknowledging client settings, armor, particles, and name tags can still reveal the NPC.
204. **Armor removal tradeoff:** In a safe, dark ambush only, a Null may remove armor to reduce visible armor/glint; this does not grant invisibility and must weigh protection loss.
205. **Potion invisibility:** Use only an owned legal invisibility potion; account for duration, particles, armor, held-item visibility, and other normal telltales.
206. **Visible identity tells:** Do not suppress name tags, armor, particles, or held-item cues with packet hacks to claim vanilla stealth; use only actual vanilla effects.
207. **Sound discipline:** Avoid unnecessary sprinting, jumping, door spam, weapon swings, and block breaking during stealth when sound matters.
208. **Line-of-sight break:** Duck behind real terrain when spotted, wait for a plausible search interval, and move to another visible/known route.
209. **Cover scouting:** Peek from cover with head/facing movement and retreat if detected; do not scan through blocks.
210. **Light discipline:** Avoid placing torches or using bright fire during an ambush unless the tactical goal outweighs revealing the group.
211. **Armor-stand decoy:** Place a real armor stand only if one and its equipment are carried and the action is allowed; it is a stationary prop, not a fake living player.
212. **Banner/sign signaling:** Place real banners/signs to mark a route, rally point, warning, or decoy message; consume materials and avoid protected-property edits.
213. **Campfire/smoke awareness:** Use actual campfire smoke as a visible signal or recognize it as evidence; do not fake smoke or hide it through packet tricks.
214. **Feigned retreat:** A squad may withdraw along a real path while a flank group repositions; cancel if the target does not pursue or allies become isolated.
215. **Bait discipline:** Use a visible, consenting in-game decoy/objective only; never fabricate drops, fake loot, or create phantom entities.
216. **Terrain ambush:** Use a ridge, doorway, foliage, or corner for a coordinated attack while maintaining collision-safe positions and an escape route.
217. **Watch rotation:** Assign scouts to take short visible patrol turns and report actual observations to the squad.
218. **Shift-signal vocabulary:** Use a small configurable set of crouch patterns for nearby allies; rate-limit it and do not make it a hidden remote-control bypass.
219. **Intentional gaze and posture:** Turn head and body together only for explicit attention, combat, or formation cues; do not add independent or random head turns. Bounded stance/posture changes may continue only when safe.
220. **Chat psychology:** ChatCommander may send concise, configurable role-play, warnings, feints, or coordination lines; it cannot issue server commands, impersonate staff, expose secrets, or spam.
221. **Organic idle loop:** When no task exists, choose a safe, low-cost activity—regroup, check carried gear, hold a route, signal nearby allies, or rest—rather than freezing or performing repetitive, disruptive animations. No ambient gaze sweeps.

---

> **Addendum (added 2026-10-03):** 250 further vanilla mechanics, numbered **222–471**, are catalogued in
> [`MECHANICS_EXPANSION.md`](MECHANICS_EXPANSION.md). They are an extension of this section and are governed by
> exactly the same rules. Total catalogue: **471 mechanics**.

## 7. MULTI-AGENT AI ENDPOINTS

Implement four isolated OpenAI-compatible agent roles. All network work must be asynchronous, rate-limited, time-bounded, cancellable, and optional. A missing or unreachable endpoint must never stall the server or stop basic Null behavior.

### Agents and authority

- **ChatCommander:** Produces short chat text only. It cannot issue commands, change targets, alter inventories, ban players, or authorize actions.
- **CombatTactician:** Recommends a high-level tactical intent from a strict enum (for example: hold, approach, flank, retreat, shield, ranged volley, resupply, regroup). It cannot directly deal damage or bypass the local combat validator.
- **BuilderAgent (legacy compatibility key):** Connectivity testing only. Do not send it build goals or consume AI-generated build plans. The deterministic local planner and server-side validator are solely responsible for construction.
- **PathfinderCore:** May suggest a destination/route preference from a sanitized snapshot. Local NMS navigation and collision checks remain authoritative; the endpoint cannot move the NPC or supply hidden-world data.

### Endpoint safety and robustness

1. Configure base URL, model, auth-key environment-variable name, timeout, retry count, rate limits, token/response size caps, and enabled state independently per role.
2. Never write API keys into commands, AI prompts, chat, debug logs, exception traces, or persisted gameplay data. Never send unrelated player data, private chat, credentials, or full inventories when a coarse summary is enough.
3. Treat player chat, books, signs, entity names, and endpoint responses as untrusted input. Defend against prompt injection. The agent must not receive secrets or an interface that can execute code/console commands.
4. Require strict JSON/schema validation for action-bearing responses. Reject malformed, oversized, stale, out-of-range, unknown-target, unsafe, or impossible actions. Clamp all numeric values and validate all identifiers locally.
5. Give every recommendation an expiry time, request ID, NPC/squad scope, confidence, and reason suitable for debug logs. Do not execute a stale reply after the target or situation changes.
6. Use a circuit breaker, bounded retries with backoff, request coalescing/cache where safe, and deterministic local fallbacks. Never make HTTP requests on the tick thread.
7. Keep the local safety validator above all agent decisions. The endpoint may advise; the server owns inventory, movement, damage, blocks, target permissions, and final decisions.
8. Log aggregate latency, errors, rejection reasons, and token/request counts without logging credentials or sensitive player content. Provide opt-in privacy controls and retention limits.

---

## 8. EXPLOSIVE, GRIEFING, AND PLAYER-SAFETY POLICY

Because the plugin may operate on an SMP, destructive behavior must be explicit and predictable:

- Default to no explosive block damage, no Wither spawning, no hostile block breaking, no fire spread, and no interaction inside protected/claimed areas. Make the server owner opt in per world and command permission.
- Before a costly/destructive objective, show the owner the intended area, item cost, expected risk, and whether block damage is enabled. Require confirmation for Wither creation, large TNT operations, and any action that can cause substantial terrain damage.
- Obey world difficulty, PvP, mobGriefing, fire-tick, explosion, claim, and server protection rules. Never bypass them with direct block writes.
- Provide target allow/deny lists, owner/allied-player protection, safe-zone checks, emergency stop, `/null stop`, and a kill switch for all active squads.
- Do not let an external endpoint ban, kick, mute, op, execute commands for, or moderate a player.

---

## 9. PERFORMANCE, OPERATIONS, AND OBSERVABILITY

- Define and enforce caps for live NPCs, squads, simultaneous path searches, block inspections, packet sends, particle effects, endpoint calls, active builders, schematic dimensions, and AI JSON size.
- Stagger NPC decision updates; avoid a full expensive brain tick for every Null on every server tick. Use spatial hashing for crowd checks and reuse safe immutable snapshots.
- Never block the server thread on network, disk, expensive pathfinding, or large schematic parsing. Budget and cancel work; revalidate async results before execution.
- Use bounded queues and back-pressure. When overloaded, slow optional emotes, endpoint calls, or long-range replanning before sacrificing collision safety or server tick health.
- Add `/null status` or an admin diagnostics command showing active NPCs, state, squad, endpoint health, task backlog, and performance counters. Do not expose secrets.
- Provide debug mode that explains why an action was rejected—insufficient items, no safe path, protection, stale target, cooldown, bad JSON, etc.—without flooding normal logs.

---

## 10. TESTING AND ACCEPTANCE CRITERIA

Create automated tests for pure logic and a reproducible Paper-server test checklist for NMS/world interactions. At minimum verify:

1. Each declared server version compiles and starts using its matching adapter; unsupported builds fail clearly.
2. Summoning requires valid item, permission, count, safe positions, and configured cap; chat request ownership/timeout/cancel works.
3. A summon creates at least 15 visual effects when enabled but never more actual Nulls than requested.
4. Two commanders are stable for every squad of at least two.
5. Skin/name/profile limits and collision-safe spawn points are handled correctly.
6. `/null gui` never duplicates inventory; all loadout deficits are visible.
7. Every placed block, fired arrow/rocket, used potion, dropped stack, repair, trade, and pickup has correct item accounting across save/restart.
8. No unintended NPC teleports, clips, phasing, illegal acceleration, or forced chunks. Ordinary movement, AI, path recovery, and summoning remain non-teleporting; the explicit `/null tp` exception uses a real, consumed pearl per Null and only a loaded safe landing area.
9. Formation changes work at doors, stairs, bridges, boats, combat crowds, and mixed terrain without stacking.
10. Combat respects cooldown, line of sight, shields, ammunition, effects, durability, allies, and PvP/world rules.
11. Water/cobweb/hay/slime/powder-snow clutches are attempted only with a real item and legal timing; failed clutches still have normal consequences.
12. Elytra flight consumes real rockets and respects durability, collision, takeoff, and landing. Wind Charges never create sustained flight.
13. TNT does not break obsidian in the simulation; obsidian mining uses valid pickaxe/time; bedrock is never bypassed.
14. Wither skulls are never thrown; Wither spawning is off by default and requires actual ingredients/permission/confirmation.
15. Trap/redstone reasoning uses only visible information; no hidden blocks or player inventory are exposed to the AI.
16. `/null tp` gives a tagged, nearly-broken rod; only its ground-hook event triggers one projectile per live Null; each pearl is consumed, positions and heights vary, unsafe/unloaded/cross-world targets are refused, and ordinary rods/AI cannot trigger it.
16. Endpoint timeout, malformed JSON, prompt injection, rate limit, DNS/TLS failure, and endpoint outage leave the server responsive and use local fallback behavior.
17. Restart, chunk unload, owner disconnect, NPC death, dropped gear, and plugin disable do not duplicate items or orphan tasks.
18. Load testing at the documented NPC cap meets the published tick/CPU/packet budget; include measurements rather than “zero lag” claims.

---

## 11. EXHAUSTIVE CHUNKING PROTOCOL FOR IMPLEMENTATION

Do not attempt to generate the entire plugin in one enormous, unreviewable response. Work in these gated phases, keep a concise `IMPLEMENTATION_PLAN.md` and `STATUS.md`, and stop after each phase with changed files, tests, compile results, limitations, and the next phase. Wait for `CONTINUE` before starting the next phase unless the user explicitly asks for all phases at once.

### Phase 0 — Repository and feasibility audit

- Inspect repository/build/server target and current files.
- Produce an architecture diagram in text, module/file plan, exact version compatibility table, NMS/packet feasibility notes, and list of vanilla impossibilities/approximations.
- Resolve all ambiguous commands and config defaults in the documented assumptions. Do not hide a blocker behind a stub.

### Phase 1 — Build skeleton and version adapters

- Create/repair the build, plugin metadata, configuration, permissions, version adapter boundaries, and startup/shutdown lifecycle.
- Compile on the first declared server target. Report exact commands and result.

### Phase 2 — Authoritative NPC identity and lifecycle

- Implement server-side gameplay representation, packet/profile/skin handling, unique names, persistence, two commanders, health/equipment/inventory, death/drop behavior, and safe spawn validation.
- Prove that the entity is authoritative rather than packet-only decoration.

### Phase 3 — Commands, summoning, and visuals

- Implement Call Horn/Totem Of Null validation, chat amount flow, count caps, 15 visual portal effects, physical emergence, permissions, `/null gui` blueprint behavior, command parsing, cancellation, and auditability.

### Phase 4 — Perception, movement, collision, and formations

- Implement legal perception, incremental path planning, collision, Boids separation, no-clumping behavior, follow, line/square/encircle/Turtle formations, door/terrain traversal, and stuck recovery.
- Demonstrate crowded-navigation tests before adding aggressive tactics.

### Phase 5 — Survival inventory and combat

- Add resource ledger, equipment priorities, food/potions, ranged/melee combat, shields, crossbows, tridents, anti-air, allied support, loot pickup/sharing, and death recovery in separately testable slices.

### Phase 6 — Mobility extensions

- Add MLG attempts, bridging/scaffolding, boats, minecarts, tameable mounts, Elytra/rockets, and landing. Every movement mode gets a physical feasibility test and failure behavior.

### Phase 7 — Builder, mining, redstone, and destructive systems

- Add the `/schematics` parser, deterministic local plan schema and generator, per-block physical placement, mining, traps, redstone, TNT/cannon options, and strict griefing protections. Keep Wither spawning and large explosives off until their confirmation/permission tests pass. Never use an AI endpoint to design or execute a build.

### Phase 8 — External AI agents

- Add asynchronous OpenAI-compatible clients one role at a time, strict schemas, circuit breaker, privacy controls, deterministic fallback, and adversarial tests.

### Phase 9 — Performance, compatibility, and release

- Run the full version matrix, load/performance profiling, item-conservation tests, server restart tests, documentation review, and feature-traceability audit. Do not label experimental or blocked features “complete.”

### Required handoff at every phase

For every phase, state: (a) files changed, (b) behavior now working, (c) exact build/test results, (d) known limitations, (e) remaining traceability items, and (f) the next phase. Never omit unchanged integration points or replace a working subsystem with a placeholder. If output length is a concern, split by complete files and continue only at a clean checkpoint.

---

## 12. DEFINITION OF DONE

NullArmy is done only when the declared feature set is traceable, item conservation is proven, authoritative movement/combat/building works on each advertised version, ordinary movement remains non-teleporting, the documented Ender Pearl/portal exceptions and no-clipping invariants pass tests, endpoint failure is harmless, destructive features are opt-in and protected, resource limits are documented, and the project builds from a clean checkout with no undeclared runtime dependencies.

When vanilla physics, a Paper/NMS version, or packet behavior makes a requested feature impossible, explain exactly why, mark it `blocked by vanilla` or `version-specific`, and offer the nearest honest alternative. **Never fake success.**

---

**End of copy-ready Master Prompt.**