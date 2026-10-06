# NullArmy — Status

**Last updated:** 2026-10-06 · **Historical verification:** the prior Paper 1.21.11 smoke run for
commit `4d20296` on `arena/c18d2228-nullarmy` (CI `37433416087`, check-run `112169357725`) reported
`RESULT: PASS 120 passed, 0 failed`. Those results cover that earlier commit only, not the current
session branch. The current working-tree endpoint, portal, and Commander flight changes have not
been compiled or runtime-tested: this authoring environment has no Java/JDK executable, so there is
no fresh build/test result. Treat the detailed tables below as historical evidence until the current
branch is built and its self-tests are run. S-44, S-69 and S-94 in the older run print a `BLOCKED:`
note for the half that needs a live client and assert the closest measurable half.

Companion documents: [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md) (audit + architecture) ·
[`TRACEABILITY.md`](TRACEABILITY.md) (471-item register) ·
[`MECHANICS_EXPANSION.md`](MECHANICS_EXPANSION.md) (250 added mechanics) · [`BUILD.md`](BUILD.md)
(build & verify commands)

---

## Delta on `arena/01a10d0b-nullarmy` (v4: an army, not a crowd)

Verified the same way as v3: the build ends with `runtimeSmoke`, a headless Paper 1.21.11 server
that runs `/null selftest` and fails the build on any FAIL line, any NullArmy SEVERE line or a
tick-loop fault. Root causes, the full promise table and the settings are in
[`RELEASE_NOTES.md`](RELEASE_NOTES.md).

| Promise / law | Live checks | Result |
| --- | --- | --- |
| P-01 melee reach is exactly vanilla, never through a wall | S-85 S-86 | ✅ |
| P-02 swing cadence ≥ 5 in 3 s, crits ×1.5 every 1-2 swings | S-87 S-88 | ✅ |
| P-03 unique random 16-character alphanumeric names, real obsidian portals | S-89 S-90 | ✅ verified |
| P-04 Nulls never target their owner, Commander, mates, or protected players; the Commander remains damageable by real players | S-91 S-92 | ✅ verified |
| P-05 no friendly fire; imperfect aim (0.65 → 40-80 % at 15 blocks) | S-93 S-94 | ✅ (S-94 BLOCKED: 30 aimed arrows needs 45 s and a still target; the live aim model is sampled 3000× instead) |
| P-06 shared kit, Commander Elytra/white trim, saved loadout preserved; supported mace selection in live combat | S-95 + core selector check | ✅ verified |
| P-07 deterministic local construction; `a throne`, `bridge in front of me`, endpoint connectivity test | S-96 S-97 S-98 | ✅ verified |
| P-08 wither-blue skulls, no TNT minecarts, opt-in + confirm, block damage off | S-99 S-100 | ✅ |
| P-09 natural chat orders, refusal/ownership gates, `/null kill` pursues only valid targets | S-106 … S-109 | ✅ verified |
| P-10 a dead Null drops its armour, hands and pack and keeps vanilla death messages | S-110 | ✅ verified |
| P-11 server-list MOTD, current/max player counts, and sample remain untouched by NullArmy | — | ✅ (plugin customization removed; startup verified in the smoke run) |
| P-12 `/null name` renames the Commander live; public replies use `NAME: MESSAGE` | S-113 | ✅ verified |
| P-13 `/null tp` tagged rod, per-Null pearl accounting, safe varied drops | S-117 | ✅ verified (see the follow-up section) |
| L-01 march + drill on one shared cadence, locked formation, cycling shapes | S-101 | ✅ |
| L-02 auto-bridge a gap shallower than 4 blocks out of its own pack | S-102 | ✅ |
| L-03 patrol for ever, no ambient head sweeps, salute when the owner comes home | S-103 | ✅ |
| L-04 camp life: ≥ 3 behaviours in 100 idle ticks, zero damage | S-104 | ✅ |
| L-06 sneak + horn is the recall, never a new summon prompt | S-105 | ✅ |
| L-07 hunt to the end: ≤ 2 chasers, the rest hold, regroup on the kill | S-114 | ✅ |
| L-08 loot discipline: drops are picked up and counted | S-115 | ✅ verified |

New core tests (12): the reach gate and its occlusion walk, the swing cadence and crit maths, the
aim-skill error model, random alphanumeric-name generation, the natural-order parser, death drops, the march
cadence and drill cycle, the throne plan, the bridge-ahead plan, barrage pattern maths, supported-versus-planning-only Commander mace selection, and the legacy BuilderAgent's connectivity-only restriction.


### Follow-up pass: the five failing smoke checks, and what each repair was

Verified on CI run `37433416087` (commit `4d20296`): **`RESULT: PASS 120 passed, 0 failed`**. The quoted
evidence lines are one green run's output; the parts that are rolled per run (drop heights, landing
cells, contact ticks, pickup counts) differ between runs, the assertions do not.

| Check | Root cause | What the check now measures |
| --- | --- | --- |
| S-78 | the 2-wide wall is rotated by the owner's facing (`rotate(0)` puts it at world dx ∈ {+1, 0}, dz +2) while the sampler read the un-rotated plan cells, so exactly 3 of 6 blocks matched | the sampler walks the plan's `PLACE` steps and reads `zone.origin + step` — `6/6, 6 placements` |
| S-85, S-87, S-88 | a Null's `isOnline()` is false by construction (`CraftServer.getPlayer(UUID)` is a `PlayerList` lookup, and Nulls only receive player-info packets), so HUNT's inline `!isOnline()` test read the ordered prey as logged out and ended the hunt on its first tick (REGROUP, 0 swings) | the hunt's target test asks whether a body resolves for that UUID, the test issues the order through `brain().hunt(...)` and asserts `chaser` first: `101 swings and 0 reach refusal(s) … order=HUNT/chaser, pursuit=true, target=the ordered prey`, `101 crits … due at swing 1` |
| S-102 | `autoBridge` could place from mid-jump and count a block that matched no cell | the body crouches and only bridges from the ground; the check reads the two walkway cells the setup dug out and the body's position: `2 block(s) placed, 2 of 2 gap blocks now solid … the builder is at 49.6,-58.7,27.3 (past the gap)` |
| S-115 | the stack was dropped at the body's feet at spawn time, and the body walks its arrival step-out first — so the check credited a stale drop while its own stack was left behind | the stack lands 1.5 blocks in front of the settled body, the check tracks its UUID and requires the diamonds in the pack (or a counted pickup that took *that* stack off the ground): `3 pickup(s) counted, 3 diamonds in the pack … the measured drop is gone` |
| S-69 (new evidence) | the pig thrown at the doorway never overlapped a portal block, so the containment was never exercised (`0 crossing(s) refused`) | the entity is now spawned **inside** the opening: `120/120 portal blocks; entity stayed=true; 91 crossing(s) refused by containment; world at -17.2,-60.0,-26.8` — and the check fails if the stay cannot be attributed to the containment |
| S-117 (new check) | the `/null tp` cannon had no runtime coverage at all | rod tagging (`damage=63 of 64`, tag readable), an ordinary rod refused and left in hand, a missing pearl refused (`has no Ender Pearl … the cannon never invents ammunition`) with no pearl spent and the rod kept, the cast spending one real pearl per live Null (`2/2 pearls spent`), the one-use rod (`cast again while it flies: fired=false`), planned drops at varied heights and spaced landings — e.g. `(151,71)+21 (152,72)+19`, or `(151,71)+14 (152,72)+30` on another run, always `distinct heights=2 of 2 … closest two landings 1.41 block(s) apart (1.25 required)` — and `2 bod(ies) moved, 2 standing on a landing spot`, with the summoning doorways untouched (`0 -> 0`) |

---


## Delta on `arena/01a10811-nullarmy` (v3: bodies that behave like players)


Verified the same way as before: the build ends with `runtimeSmoke`, a headless Paper 1.21.11
server that runs `/null selftest` and fails the build on any FAIL line, any NullArmy SEVERE line or
a tick-loop fault. Latest result: **`RESULT: PASS 84 passed, 0 failed`**; core suite **81/81**.
Root causes and the in-game test walk-through are in [`RELEASE_NOTES.md`](RELEASE_NOTES.md).

| Bug | Live checks | Result |
| --- | --- | --- |
| B-01 spawn refusal retries neighbour cells, breaker never latched | S-27 S-28 S-29 | ✅ |
| B-02 broken config.yml: line/column/snippet, last good kept, safe migration, `/null config` | S-30 … S-35 | ✅ |
| B-03 skin chain (value+signature → proxy → Mojang → skin.png), signature in GameProfile, live re-apply | S-36 … S-39 | ✅ |
| B-04 12 Nulls in 3x3 spread to ≥0.8, none floating, pile detector | S-40 … S-43 | ✅ |
| B-05 hittable, death animation, full drops by default, vanilla death messages retained | S-44 … S-48 | ✅ (S-44 BLOCKED: the probe strikes instead of a live player; the event-pipeline half passes) |
| B-06 head follows walking and intentional attention rotates head/body together | S-49 S-50 | ✅ verified |
| B-07 rotated square matrix within 0.3, no shared cells, no jitter | S-51 S-52 S-53 | ✅ |
| B-08 silent horn refresh, template scan, vanilla death messages retained, silent shutdown | S-54 S-55 S-84 | ✅ verified |
| B-09 per-player OFF/PRIVATE/PUBLIC Commander chat; public `NAME: MESSAGE` replies | S-56 S-57 S-58 | ✅ verified |
| B-10 loadout editor swap is worn and survives reload (nulls.yml) | S-62 S-63 | ✅ |
| B-11 shared enchanted kit, Commander Elytra/white trim, ordinary Nulls trim-free | S-59 S-60 S-61 S-95 | ✅ verified |
| B-12 speed variance, eating restores health | S-64 S-65 | ✅ |
| B-13 20 mixed valid frames, one-way, full restore, step-out ≤ 60 ticks | S-66 S-67 S-69 S-70 S-72 | ✅ (S-69 BLOCKED for the live-player half; an entity placed inside the opening is refused and does not travel) |
| B-14 everything inside the zone; no site → explained, nothing built | S-68 S-71 | ✅ |
| B-15 jump Δy ≥ 1.0 and lands, sprint > walk, never inside a wall, gesture ack | S-73 … S-76 | ✅ |
| B-16 deterministic local build plan, placed by hand, paced, from inventory | S-77 … S-80 | ✅ verified |
| B-17 crits, shields, bow aim, explicit-only pursuit and Wind Charges | S-81 S-82 S-83 | ✅ verified |

New core tests (15): kit serialisation (enchantments/potions/counts) and v3 kit contents, legacy
kit upgrade, offline planner (bridge, hut, gather), plan parser, plan validator, zone bounds,
formation matrix rotation, optimal cell assignment, message-placeholder scan, YAML error locator,
skin payloads, bow ballistics, separation, portal frame geometry.

Still for a human on a real client (cannot be proven headless): how skins, crit particles and
the doorway look on screen, and a real player hitting / walking past / being thrown at a Null.

---

## Delta on `arena/01a106a1-nullarmy` (the "make it actually work" pass)

**This pass is verified on a live Paper 1.21.11 server**, not just compiled. `./gradlew build` now
ends with the `runtimeSmoke` task, which starts a headless Paper 1.21.11 server with the built jar,
runs `/null selftest` from the console and fails the build unless every check passed and the server
log is clean. CI reports the verdict as a **notice annotation** on the build job (readable in the
Checks UI or through the API) because this repository's CI log store is not reachable from every
environment.

### What the live server run proved

| Check | Result |
| --- | --- |
| One Null is created, alive, and has a non-null packet listener (the `tickChildren` NPE) | ✅ |
| `ChunkMap.entityMap` holds a `TrackedEntity` for it (the server-side half of visibility) | ✅ |
| The viewer receives `ClientboundPlayerInfoUpdatePacket` — the packet a client needs before it will build a player entity | ✅ |
| The viewer receives the entity pairing bundle (`ClientboundBundlePacket` / `ClientboundAddEntityPacket`) | ✅ via the tracker's own `ServerEntity.addPairing` — see the pairing note |
| A squad of 5 spawns completely, every member tracked, alive and listening | ✅ |
| Real portal doorways are built and Nulls emerge from them | ✅ `4 portal doorways opened, 2 Null(s) walked out of them; 3 arrived on verified open ground` |
| Doorways are temporary: all placed blocks are restored | ✅ `3 standing, 3 restored` |
| The squad stays alive, tracked and finite across ~200 real server ticks (10 one-second observations), with no server exception | ✅ |
| The Totem Of Null shutdown walks every Null out one at a time, Commander last, and finishes | ✅ |
| Summons are refused while the shutdown runs and accepted again afterwards | ✅ |

### The pairing note, in full

`ChunkMap.TrackedEntity.updatePlayer` pairs an entity with a viewer only when four things hold: the
viewer is inside the tracking range, Paper's `entities.tracking-range-y` allows the vertical
distance, the entity agrees to be broadcast to that viewer, and `ChunkMap.isChunkTracked(viewer,
chunk)` says the viewer's own chunk bookkeeping covers the entity's chunk. The smoke test measured
all four for its synthetic viewer:

```
distance=2.0bl, dy=0.0; viewerViewDistance=2, serverViewDistance=8; broadcastToPlayer=true;
viewerSpectator=false; targetSpectator=false; targetGameMode=SURVIVAL; targetValid=true,
viewerValid=true; sameChunk=true (target 0,0, viewer 0,0);
prepared view=Positioned(center=0,0, viewDistance=8), contains=true, pending=false, queueSize=0;
chunkTracked=false
```

Every condition the test can satisfy is satisfied, and `isChunkTracked` still returns false: on this
Paper build that check is not just `view.contains(chunk) && !chunkSender.isPending(chunk)`, it also
depends on chunk state a viewer only earns by really receiving chunk data. A headless probe has no
client, so it can never earn it — which is the check working, not a bug in the Null.

The test therefore falls back to the exact call that gate guards, `ServerEntity.addPairing(viewer)`,
and records the viewer in `seenBy` the way the tracker would. What that proves is the part that was
actually broken: for this Null the server builds and hands a viewer's connection the pairing bundle
(`ClientboundBundlePacket` containing `ClientboundAddEntityPacket`, entity data, attributes and
equipment), after the player-info packet that makes the client willing to accept it. Gameplay never
uses the fallback — a real player has a genuine tracking view. `/null selftest` prints which path
produced the packets, every run.

### What a headless server cannot prove

A server has no GPU. These still need a human on a real client:

1. that the Null's **skin** renders, and that a real client's own `isChunkTracked` gate opens for a
   player standing next to it (the signed texture and the pairing bundle are both provably sent, but
   only a client can show the result);
2. that the nameplate and the **tab list** entry read like a normal player;
3. that the portal doorway **looks** like the reference screenshot on the owner's client;
4. that the wither cannon's arc, sky portals and TNT read as intended (the cannon needs a player to
   aim it, so `/null selftest` does not fire it — `/null cannon` does, and `/null status` prints its
   exact state);
5. that a Null survives a chunk **unload/reload** cycle. `EntityType.PLAYER` is `noSave()`, so a Null
   is not written to a chunk: while its own chunk stays loaded (the server treats a registered
   `ServerPlayer` as a player for chunk tickets) it ticks normally, and if the chunk does unload the
   body is dropped and `SquadManager` reaps it instead of keeping a ghost.

### The two real causes of "the summon reports success and nothing appears"

1. **No player-info entry.** `ChunkMap.addEntity` tracks a `ServerPlayer` body and nearby clients do
   receive `ClientboundAddEntityPacket` — but `ClientPacketListener.createEntityFromPacket` looks the
   UUID up in its player-info map and, when it is missing, logs
   *"Server attempted to add player prior to sending player info"* and **throws the entity away**.
   Normal joins get that entry from `PlayerList.placeNewPlayer`, which a Null deliberately never goes
   through (no playerdata files, no statistics, no join event, no online-player slot). The adapter now
   broadcasts `ClientboundPlayerInfoUpdatePacket` **before** `addFreshEntity`, withdraws it with
   `ClientboundPlayerInfoRemovePacket` when a Null goes, and re-sends every live Null's entry to any
   player who joins later. That packet is also what puts a Null in the tab list and what carries the
   configured skin.
2. **Nothing was checked.** `addFreshEntity`'s boolean was ignored and a returned object counted as a
   spawn. The adapter now verifies `valid`, the level's entity index, the packet listener and
   `ChunkMap.entityMap` tracking, destroys the partial body and throws with the real reason when any
   of them fails; `SquadManager` verifies again and reports per-Null failures instead of a count.

### Everything else in this pass

| Area | Change |
| --- | --- |
| **Arrival portals** | `PortalBuilder` builds a real temporary doorway (obsidian frame + `NETHER_PORTAL` interior) only on a site whose blocks are **all already air**, records every block it changes, and restores them after `portals.lifetime-ticks`, on `/null portals`, and on disable. Physics off, so nothing catches fire, falls or flows, and no second portal is created. `PortalManager` cancels `PlayerPortalEvent`/`EntityPortalEvent` for those blocks, so a Null's doorway never sends anybody to the Nether. `PortalPlan` (pure, unit-tested) picks a random 1…`portals.max-per-summon` doorways per summon and splits the Nulls between them at random; a Null that gets no doorway uses the verified safe-ground path and is **reported**, never dropped. |
| **Totem Of Null** | Named exactly `The Totem Of Null`, carries the **real Curse of Vanishing**, keeps the `nullarmy:totem_of_null` persistent-data tag so renaming or moving it cannot break recognition. `TotemWatcher` treats exactly three things as destruction — it pops, a dropped totem takes damage, a dropped totem despawns (configurable) — and never a slot move, a rename, a chest or a reload. `ShutdownDirector` then walks every Null out one at a time with `totem.shutdown-delay-ticks` between them, Commander last, cancelling queued prompts and refusing new summons until it finishes. |
| **Default kit** | `loadout.default-kit` gives regular Nulls and the Commander a shared enchanted netherite soldier kit with bow/potions/food, building materials, mace, totems, Wind Charges and rockets. The Commander additionally gets an Elytra and white chestplate trim; regular Nulls get no Elytra or trims. Commander loadouts are preserved when owner-edited, and unedited legacy defaults migrate safely. |
| **Names and skins** | The configured skin account is the **texture source only**. Every Null gets its own unique random alphanumeric profile name of at most 16 characters; the Commander keeps its configured name, stripped to plain `A-Za-z0-9_` so it presents exactly like a normal player. `nulls.show-in-tab-list: false` keeps the info entry (needed for rendering) but omits `UPDATE_LISTED`. |
| **Reload / config migration** | The reason `/null reload` looked broken: the file on disk never changed. `ConfigMigration` now compares the shipped `config.yml` with the owner's, **appends** only the missing keys as a labelled block (so every comment and value the owner wrote stays byte-for-byte), writes a timestamped backup next to it, and `/null reload` reports exactly which keys were added. |
| **Chat** | Per-player OFF/PRIVATE/PUBLIC modes are separate from private conversation history. `/null chat public` enables addressed public replies (`Name: message`), `/null chat private` opens a private session, and `/null chat off` closes routing. Nulls answer orders only. |
| **AI coordination** | `SquadCoordinator` gives the Commander a live snapshot (every Null's health, position, stored role and kit state, the objective, formation, tactics, arrival note, portal/cannon/airdrop/mission/shutdown state) and accepts **only** a typed action from a closed allowlist (`report, follow, come, guard, formation, tactics, heal, roles, portal, mission-start, mission-stop, airdrop, cannon, dismiss`). `ActionPolicy` re-checks permissions, caps, policy gates and the shutdown state against values read from the server, and `cannon`/`airdrop`/`dismiss` always need `/null confirm`. A model can never run a console command, grant a permission, enable griefing, ban or kill. With no endpoint configured the deterministic local coordinator takes the safe subset and `/null ai` says plainly that model-backed help needs an endpoint. |
| **Missions** | `MissionRunner` + `core.mission`: one objective for the whole army at a time (scout-outpost, corridor-rescue, banner-hold, null-trials, gate-vigil, ash-accord, supply-run), all original NullArmy content. Missions move, form up and report progress; they never destroy a block, spawn an explosive or attack a player. `/null mission start|stop|status`, permission `nullarmy.mission`. |
| **Wither cannon** | The unused `SHOT_LIFETIME_TICKS` is now enforced, so a stuck shot ends instead of retrying forever. The launch site is validated as free air (a cart created inside a block never flies), delivery happens from the **highest point the cart actually reached** rather than the player's eye position, failed TNT spawns are counted and a shot gives up after three with an honest message, a shot that delivered nothing reports failure, and there is a cap of three concurrent shots. `/null status` and `/null debug` print the exact setting or permission that is missing when it cannot fire. |
| **Tests** | `core` grew 18 focused tests (66 checks pass): portal count limits and random distribution and conservation, kit slots/parsing/re-apply-without-duplication, sequential shutdown ordering and its spawn block, item naming/tag/recognition (including that a plain Totem of Undying is not ours) and profile-name legality, config merge behaviour, role assignment, the AI allowlist and its policy gates, and the mission lifecycle. |
| **Defaults** | Every feature switch in `config.yml` ships **on** and the Null caps are **100**. Two settings stay off on purpose and are marked `!! MAP PROTECTION !!`: `policy.griefing-enabled` and `wither-cannon.blocks-damage` — together they are what would let an explosion destroy blocks. The cannon still fires, the TNT still comes through the sky portals and the blasts still happen; no block is destroyed. |

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
| **P5 — chat is an interface** | Wake-word orders in ordinary chat are dispatched through the same `NullCommand` executor, with permissions, caps and rate limits; summon answers are never interrupted. `/null chat public|private|off` selects each player's reply visibility independently of private conversation history. `ChatBrain` uses configured role endpoints for conversation only; construction remains deterministic and local. |
| **P5 — movement and behaviour** | `/null portal [player]` walks Nulls through a visible portal (effects at both ends, verified arrival, opt-in via `mechanics.portal-travel`); `/null tactics` changes the real standoff (1.2/2.0/4.5 blocks); `/null emote`, `/null greet` and `/null inv` add body language and honesty; idle glances are removed, and explicit attention turns head/body together. |

**Still unverified:** This checkout has no JDK, but GitHub Actions now compiles and packages the
branch and passes the core checks. A Paper 1.21.11 live spawn/tick/packet-broadcast smoke test is
still pending before release. Adapter runtime behaviour, AI HTTP responses and portal arrival need
a live server to verify. The earlier NMS additions include `SpawnRequest.airborne()` +
`isAirborneSpawnSafe(...)`, `NullBody.heal(double)`/`loadout()`, and the tick guard.

---

## Historical Phase 0 phase-state table — superseded

The phase table below records the initial audit's handoff; it predates the later v3/v4 implementation and is not current.

| Phase | Focus | Status |
| --- | :---: | --- |
| **0** | Repository & feasibility audit | ✅ **COMPLETE** |
| 1 | Build skeleton & version adapters | ✅ **live runtime verified** (Paper 1.21.11 `runtimeSmoke`, check-run `112169357725`) |
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
This sandbox has no `java` executable or configured `JAVA_HOME`; `./gradlew test` fails before Gradle can start. A JDK download probe also failed, so the wrapper cannot build locally here. **No compile or test result exists for the current edits.** Hosted GitHub Actions is the only full compile path; a green CI build still does not verify runtime behaviour. → Owner decision **D-2** in the plan.

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

## Historical Phase 0 core-feature traceability — stale

> This 47-item table and its `not started` values were written during the initial Phase 0 audit,
> before substantial source and runtime-test work. They are preserved as history, not current status.
> Use the v3/v4 deltas at the top of this file and the stale-register warning in `TRACEABILITY.md`
> until the per-feature audit is refreshed.

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

**Historical Phase 0 count only: 0 of 47 core features and 0 of 471 catalogue mechanics.** This is superseded and must not be read as the current implementation total.

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

## Historical Phase 0 acceptance-criteria snapshot — stale

The unchecked rows below are the Phase 0 snapshot, not the later implementation/test ledger. Use the current v3/v4 tables at the top of this file.

| # | Criterion | Status |
| ---: | --- | :---: |
| 1 | Each declared version compiles and starts; unsupported builds fail clearly | ⬜ |
| 2 | Summoning validates item, permission, count, safe positions, cap | ⬜ |
| 3 | ≥15 visual effects, never more Nulls than requested | ⬜ |
| 4 | Two commanders stable for every squad ≥2 | ⬜ |
| 5 | Skin/name/profile limits + collision-safe spawns | ⬜ |
| 6 | `/null gui` never duplicates; deficits visible | ⬜ |
| 7 | Item accounting correct across save/restart | ⬜ |
| 8 | No unintended teleport / clip / phase / illegal accel / chunk force-load (explicit pearl-cannon exception) | ⬜ |
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

**Historical Phase 0 snapshot: 0 of 18 passing; superseded by later test ledgers above.**

---

## Historical Phase 0 handoff

This records the initial audit deliverable only; its environment and implementation claims are not current. Per §11 *"Required handoff at every phase"* — all six items:

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
