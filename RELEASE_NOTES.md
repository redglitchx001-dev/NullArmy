# NullArmy release notes

## v4 — "an army, not a crowd" (branch `arena/01a10c41-nullarmy`)

Everything in the v4 dossier (P-01 … P-13, L-01 … L-08) is implemented **in place** on top of v3
and ships with a live regression check (`S-85` … `S-117`) that runs on a real Paper 1.21.11 server
inside `./gradlew build`. The v3 checks (S-27 … S-84), the original 26 and the core suite still run
and still pass.

**Verification status — read this before the table.** Green end to end on CI run **37322972345**.
The core suite is green (**92/92**) and the live Paper 1.21.11 selftest reports
`RESULT: PASS 118 passed, 0 failed`. `./gradlew build` also verified the distributable jar at
`plugin/build/libs/NullArmy-0.1.0-dev.jar` and ended with
`RUNTIME SMOKE: PASS - verified on a live Paper server.`

**Follow-up (same day, branch `arena/c18d2228-nullarmy`).** Five of the v4 live checks were failing
on `main` and are repaired: S-78 (wall sampler read un-rotated cells), S-85/S-87/S-88 (a Null's
`isOnline()` is false by construction, so the ordered hunt ended on its first tick), S-102
(`autoBridge` could place from mid-jump), S-115 (the loot stack was dropped at spawn and left behind),
plus S-69's containment, which had never actually been exercised. The `/null tp` cannon now has its
own runtime check, **S-117**. The last run, CI **37432208719** (commit `d0200c1`), reports
**`RESULT: PASS 120 passed, 0 failed`** and
`RUNTIME SMOKE: PASS - verified on a live Paper server.` Every root cause and the exact evidence
line per check are in [`STATUS.md`](STATUS.md#follow-up-pass-the-five-failing-smoke-checks-and-what-each-repair-was).

The last timing-sensitive v4 smoke failures are fixed here:

* **S-81** — the falling-critical sample now waits for a real developed fall (`fallDistance >= 0.5`)
  while keeping a full attack meter, so vanilla's own critical-hit predicate agrees with the sample.
* **S-86** — the forced-reach check now measures the same fresh attacker-eye to victim-target-point
  distance that `ReachGate` uses, instead of comparing stale cached centre points.
* **S-82/S-104** — the live smoke also accepts Paper's fully shield-stopped no-damage path and scopes
  the idle-camp hit window to the current camp, removing the remaining intermittent measurement noise.
* **Skins** — Mojang profile fetches now request `?unsigned=false`, so live account skins include the
  signed `textures` property required by clients.

Two standing laws were kept throughout: **the verification law** (a fix is only claimed when its
check passes in the live runtime smoke; a check that cannot run headless prints
`BLOCKED: <reason>` and then asserts the closest measurable thing — BLOCKED is never a pass) and
**physical honesty** (no fly, no noclip, no invulnerability, no schematic paste; every block is
walked to, swung at, placed or broken by hand, one per swing).

### The promises, and how each one is measured

| Promise | What it means | Live check |
| --- | --- | --- |
| **P-01** | Melee reach is exactly vanilla: ≤ 3.0 eye-to-target **with** line of sight, never through a wall or a corner. | S-85 damage really lands inside reach; S-86 zero damage with the reach forced below the fighting distance, and the gate refuses a strike through a real wall |
| **P-02** | Swing at the cooldown (≥ ~5 swings in 3 s), crits every 1-2 swings while falling. | S-87, S-88 |
| **P-03** | Unique random 16-character alphanumeric profile names (first character is a letter), never a UUID; staggered portal emergence, swirl at the mouth only, real obsidian + purple frame. | S-89, S-90 |
| **P-04** | The totem/horn holder is the sole commander; Nulls never hit the owner, the Commander or their mates; an order that is not the owner's moves nothing. | S-91, S-92 |
| **P-05** | No friendly fire — a `NullArmy-<owner8>` team with friendly fire off **and** an event filter behind it. Imperfect aim (`combat.aim-skill`, 0-1, default 0.65): ±4-10° of error, lead error, 0.3-0.8 s of reaction time, full misses beyond 12 blocks. Perfect aim is banned. | S-93, S-94 |
| **P-06** | Nulls share the same soldier kit; the Commander additionally gets an Elytra and a white trim on his chestplate. Ordinary Nulls receive no Elytra or trims. Owner-edited loadouts remain intact. | S-95 |
| **P-07** | Deterministic local construction only. Owner-relative goals and known structures are planned locally; `ai.builder.endpoint` is used for connectivity tests only and never receives construction goals or plans. | S-96, S-97, S-98 |
| **P-08** | The Orbital Wither Cannon fires **wither-blue skulls** instead of TNT minecarts. Rod aiming (`/null cannon aim` → rod `NullAim`; stand = launch column, look = raycast to `range`, right-click locks the target with a particle marker + coordinates). Opt-in (`nullarmy.admin`, `enabled: false`), a confirm step, `blocks-damage: false`, and it never hurts the owner or his Nulls. | S-99, S-100 |
| **P-09** | Natural chat orders through a **pure, core, testable** `OrderParser`. Wake words `null`/`commander` **or** `@<commander.name>`/name prefix. An attack persists until the target is dead or gone; the owner, mates and `policy.protected` are never targeted; `destroy` needs `policy.griefing-enabled` **and** a confirm, otherwise **one console refusal line** and nothing said in chat. A non-owner's sentence is ignored in silence. | S-106 … S-109 |
| **P-10** | Nulls drop armour, hands and inventory on death by default (`drops.enabled: true`, `drops.chance: 1.0`). Only explicit `drops.enabled: false` suppresses loot; the legacy `nulls.no-death-drops` key is ignored. Vanilla kill/death messages remain visible. | S-110 |
| **P-11** | Server-list values are left to the server: NullArmy does not alter the MOTD, player counts, max-player count, or sample list. | — |
| **P-12** | `commander.name` (default `NullCommander`) with a live rename `/null name <new>`. Anybody may talk to him by name; public replies render exactly `NAME: MESSAGE`, private sessions stay private, and only the owner is obeyed. | S-113 |
| **L-01** | March and drill on **one shared cadence**, locked formation, cycling line → wedge → phalanx. | S-101 |
| **L-02** | Auto-bridge gaps shallower than 4 blocks out of the body's own pack, sneaking at the edge. | S-102 |
| **L-03** | `patrol <a> <b>` for ever; `guard here` and salute when the owner returns within 8 blocks. No ambient head sweeps. | S-103 |
| **L-04** | Camp life — ring round the light, spar in pairs with **zero** damage, eat when hurt, haul blocks to a builder in need (`behaviour.camp-life: true`). | S-104 |
| **L-05** | One scoreboard team per squad, friendly fire off, shared trim. | S-93 |
| **L-06** | Sneak + horn is the **recall** — the squad comes home in formation on the march cadence, never a new summon prompt. | S-105 |
| **L-07** | Hunt to the end: the order persists, at most two chasers, the rest hold, everyone regroups when the target is gone. | S-114 |
| **P-13** | `/null tp` is one owner-triggered, same-world Ender Pearl barrage: the cast hands out a tagged, nearly broken, one-use rod; an ordinary fishing rod is refused and left alone; a missing pearl refuses the cast without spending anything; each live Null (and the Commander when he is the owner's) spends **its own** real pearl from a plan of varied drop heights (12-30 blocks) onto spaced landings (≥ 1.25 blocks) in loaded, collision-safe ground; the summoning doorways are untouched. | S-117 |
| **L-08** | Loot discipline — pick up the drops of the players they defeat. | S-115 |

### New commands

| Command | What it does |
| --- | --- |
| `/null name <new>` | Renames the Commander live and writes `commander.yml` (P-12). |
| `/null cannon aim` | Hands you the `NullAim` rod: stand = launch column, look = raycast to `range`, right-click locks the target. |
| `/null cannon fire [shots]` | Opens a confirm. Nothing is created until `/null cannon confirm`. `/null cannon cancel` stands down. |
| `/null ai test [id]` | One real HTTP call: prints the endpoint, the **HTTP status**, the **model** and the round trip (P-07). |
| `/null ai endpoints` | Every configured endpoint, what resolves, and a warning for any inline API key. |
| `/null order … march\|drill\|patrol\|bridge\|salute\|regroup\|hunt\|destroy` | The L-01…L-08 and P-09 orders. `patrol here to <x> <y> <z>`, `bridge [blocks]`, `hunt <player> [chasers]`. |

### New settings (appended to an existing config.yml automatically, with a backup)

`commander.name` · `chat.mention-prefix: "@"`, `chat.silence-units` · `policy.protected: []` ·
`combat.aim-skill: 0.65`, `combat.melee-reach: 3.0` ·
`drops.enabled: true`, `drops.chance: 1.0` · `behaviour.camp-life|auto-bridge|march-cadence: true` ·
`names.style: "words"` · `wither-cannon.shots: 3`, `.minecarts-per-shot: 24`, `.pattern: "sphere"`,
`.fuse-ticks: 60`, `.shot-delay-ticks: 10`, `.range: 120`, `.enabled: false`, `.blocks-damage: false` ·
`ai.builder.endpoint: ""`, `ai.builder.gather-outside-zone: false`.

Every count is capped at 100. **Every new boolean defaults to true except**
`wither-cannon.enabled` (false), `ai.builder.gather-outside-zone` (false)
and `policy.griefing-enabled` (false) — nothing destructive or expensive is on by default.

### The one change you asked for by name

> Instead of TNT minecarts, use wither blue skulls.

The barrage no longer creates a TNT minecart or a TNT entity anywhere. The payload count
(`wither-cannon.minecarts-per-shot`, 24) is kept and now means *skulls per shot*; each skull is a
real `WitherSkull` with `setCharged(true)` — charged is what makes a wither skull **blue**. S-100
asserts that **not one TNT entity exists in the world** for the barrage.

---

## v3 — "bodies that behave like players" (branch `arena/01a10811-nullarmy`)

Every bug in the v3 dossier (B-01 … B-17) is fixed **at its root** and ships with a regression
check that runs on a **live Paper 1.21.11 server** inside `./gradlew build` (`runtimeSmoke`,
`/null selftest`, checks `S-27` … `S-84`). The original 66 core tests and 26 self-test checks
still run and still pass. Where a check needs a real player (nobody can join the headless CI
server) it prints `BLOCKED: <reason>` and then asserts the closest thing that can be measured —
BLOCKED is never counted as a pass.

### What was actually wrong (root causes)

| Bug | Root cause found | Fix |
| --- | --- | --- |
| B-01 spawn refused, summoning dead for the session | (a) In Paper 1.21.11's authlib, `GameProfile`'s property map is **immutable**; writing the skin into it threw `UnsupportedOperationException` as soon as a skin was cached, the plugin read that as a broken NMS path and latched the spawn breaker. (b) A cancelled `addFreshEntity` (protection plugin) was treated as an adapter failure too. | Profiles use Paper's `MutablePropertyMap`; spot problems are *refusals* that retry a ring of neighbouring cells and never latch the breaker. |
| B-02 broken config.yml = silent defaults | Bukkit returns an **empty** config on a YAML error; the plugin ran on defaults, `/null reload` said "reloaded", and the migration — seeing every key "missing" — appended the whole shipped file to the broken one on every start. | Strict parse with file / line / column / snippet to console **and** sender; the last good config stays; migration refuses to touch a file that does not parse and rolls back a block that does not re-parse. |
| B-03 skins never applied | Same immutable profile map; skin only read from system properties; applied at spawn only. | Priority chain: signed `skins.value`+`signature` → `skins/null.png` signed by MineSkin v2 → `skins.proxy-url` → Mojang; MineSkin results are cached by PNG digest; live re-apply (withdraw, re-announce, re-pair viewers); `/null skin` report. MineSkin keys accept `env:NAME` and are masked from diagnostics. |
| B-04 Nulls stack / float / slide | Custom steering kept old velocity, no friction, no entity push, gravity only when "not on ground". | Bodies now move through vanilla `travel()` (friction, gravity, step-up, water, ladders), push like players, separation steering, a pile detector that walks extras out, real fall damage. |
| B-05 Nulls cannot be hit / never die | Since 1.21.4 `ServerPlayer` is **invulnerable until its client reports "loaded"** — the timeout runs in `ServerPlayer.tick()`, which a Null overrides. Also `canHarmPlayer` used the world PvP flag. Death messages broadcast through vanilla. | Client marked loaded; `combat.players-can-hit-nulls`; real death animation, full drops by default, vanilla kill/death messages retained, advancement messages suppressed. |
| B-06 frozen heads | Only `yRot` was ever set, never `yHeadRot`. | Head and body share one yaw for intentional attention and explicit combat/formation looks; random idle glances and scans are removed. |
| B-07 "square" was a circle | Formation slots were circles / a diagonal. | Fixed cell matrices (line, rank, column, square, wedge, phalanx, arrow, encircle, turtle), spacing ≥ 1.1, rotated by the anchor's facing, optimal (non-crossing) cell assignment, no jitter when held. |
| B-08 chat spam, "destroyed s Totem Of Null" | Unit announcements leaked into chat; death and advancement broadcasts needed distinct handling. | Unit/status events go to console and `/null status`; vanilla Null death messages are retained, advancements are suppressed, and Totem Of Null pops plus sequential shutdown are silent. Event lines come from checked templates (core placeholder scan). |
| B-09 Commander only answered privately | Legacy chat event, wake word only, private replies. The shipped wake word `- null` is YAML's **null value**, so "null" never worked. | `AsyncChatEvent`; per-player OFF/PRIVATE/PUBLIC modes, addressed public replies formatted `Name: message`, and private Commander sessions. OFF still processes orders; PUBLIC keeps addressed lines visible. |
| B-10 | No per-Null loadout editing. | One themed loadout editor for the Commander and every Null; templates; persisted in `nulls.yml`; duplication-proof. |
| B-11 | Kits carried material + count only; Commander's enchanted gear arrived plain. | Shared Null/Commander soldier kit with real enchantments and potions; Commander receives Elytra and a white chestplate trim; regular Nulls get neither. Untouched old defaults upgrade without replacing owner edits. |
| B-12 | No idle life. | Per-Null speed (±10 %), arm swings, shield raise, bow draw, eating below 60 % health, crouch-rest after 60 s idle, sneak at ledges, swim, sprint over 8 blocks. |
| B-13 | Doorway was a 4x4 frame of real portal blocks. | Complete 4x5 obsidian frame with six real `NETHER_PORTAL` blocks in the 2x3 opening; portal-travel events for owned blocks are cancelled. Ground or floating, never cuts terrain, closes after the last Null steps out or after `portals.lifetime-s`. |
| B-14 | No zone. | `summon.zone-size` (default 100) around the horn user; everything stays inside; `/null zone` outline. |
| B-15 | No direct orders. | `/null order <name|all> <walk|run|sprint|jump|stop|follow|hold|gather|build|attack|defend>`, same words in chat; vanilla physics only. |
| B-16 | No builder. | `/null ai build <goal>` uses deterministic local plans only; AI is never asked to plan construction. Nulls place blocks by hand, one swing at a time, paced and from their own inventory. |
| B-17 | No real PvP. | Vanilla `Player#attack` with cooldown, jump crits (×1.5), sprint knockback, ordered-only pursuit/strafe, shields, Wind Charges and bow lead + arc; no autonomous hostile acquisition. |

Also found while testing: Bukkit reports **every right-click into the air as a cancelled
interact event**, so the horn only worked when used on a block. Fixed.

### New commands

| Command | What it does |
| --- | --- |
| `/null config [page|filter]` | Effective value of every setting; secrets hidden. |
| `/null skin` | Which skin source won, every attempt, and what each live Null's profile carries. |
| `/null zone` | Shows your summon zone's border for 5 s; coordinates go to the console. |
| `/null order <name|all|commander> <verb> [args]` | Direct orders (see B-15). `hold square` holds a formation where you stand. |
| `/null loadout [null]` / `/null loadout template <name> [save <from>|apply <to>]` | Loadout editor and templates. |
| `/null ai build <goal>` / `/null ai stop` | The builder. |
| `/null reload` | Appends missing shipped keys with a backup, re-reads settings, reapplies live skins, and reports exactly what changed; malformed files leave the last good settings active. |

### New settings (appended to an existing config.yml automatically, with a backup)

The shipped `config.yml` is the current setting reference, including `drops.enabled` and
`drops.chance`. Legacy `combat.initiate` and
`nulls.no-death-drops` entries may remain in older files but are ignored: combat pursuit requires
an explicit order and loot is enabled unless `drops.enabled: false` is set. `ai.builder.endpoint`
is retained for connectivity tests only; local deterministic construction never sends build goals
or plans to AI.

### Test it in game (10 minutes)

1. **Config safety** — break `plugins/NullArmy/config.yml` (e.g. indent one line wrongly), run
   `/null reload`: you get `config.yml line N, column M`, the offending lines with a caret, and
   "the last good configuration is still in use". Fix it, reload, `/null config combat` shows
   the values.
2. **Summon** — use the horn **in the air**. Answer `5`. Watch obsidian frames (some floating)
   appear inside your zone; Nulls step out (from a floating one they drop and take real fall
   damage). `/null zone` shows the border. Press the horn twice: no extra chat line.
3. **Bodies** — walk into them: you push them and they push you. Summon 10 in a corridor: they
   spread out, never stack. Hit one: it takes damage, can die (tips over, no drops), nothing
   appears in chat. Stand still near them: heads turn to you for a few seconds.
4. **Orders** — `/null order all walk here`, `sprint here`, `jump`, `hold square`,
   `follow`, `stop`. In chat: `NullCommander, follow me` / `null hold wedge`. Each Null swings
   and nods. Say `hey nullcommander, how are you?` — the Commander answers in public chat.
5. **Loadouts** — `/null loadout <NullName>`, put a diamond helmet in the helmet slot, close:
   the Null wears it, and it survives `/null reload`. `/null loadout template tank save <NullName>`
   then `/null loadout template tank apply all`.
6. **Kit** — `/null kit`: netherite, Protection IV, potions, building blocks.
7. **Combat** — `/null order all attack <friend>` on a server with PvP on: cooled swings,
   jump crits (crit particles), shields raised between swings, bows at range. Hit a Null with an
   axe while it blocks: its shield drops for 1.5 s.
8. **Builder** — `/null ai build a small hut`: a deterministic local planner lays out the
   structure; Nulls walk, swing and place blocks one at a time from their own inventory.
   Building works without an AI endpoint, and no build plan is sent to a model; `/null ai stop`
   stops them. `ai.builder.endpoint` is used only by `/null ai test` for connectivity.
9. **Skins** — place a valid PNG at `plugins/NullArmy/skins/null.png`, set
   `skins.mineskin.api-key` to a MineSkin key (prefer `env:MINESKIN_API_KEY`), then run
   `/null reload`: the plugin uploads the PNG to MineSkin v2, caches its signed texture by
   file digest, and live-reapplies it. Alternatively paste a signed value/signature or use
   `skins.proxy-url`; `/null skin` reports the selected source.
10. **Shutdown** — destroy the Totem Of Null: Nulls leave one by one; progress is in the console
    and `/null status`, not in chat.

### Honest limitations

* Minecraft clients cannot use unsigned PNG bytes as a skin. The configured
  `plugins/NullArmy/skins/null.png` is sent to MineSkin v2 for signing; the signed result is
  cached locally. If no MineSkin key is available, use `skins.proxy-url` or paste a signed
  value+signature. The legacy root-level `skin.png` remains unsigned and is not applied.
* Headless checks stand in for a real player in three places (marked BLOCKED in the log): a
  ServerPlayer probe strikes for "a player hits a Null", a watcher point stands in for "a player
  walks by", and a pig is thrown through a doorway for "a thrown player is not teleported".
* While a Null's loadout editor is open, items the Null uses in that moment (an apple it eats)
  are restored when the editor closes — close the editor before sending that Null into a fight.

---

## Previous release notes


NullArmy now has a **passing runtime smoke test on a live Paper 1.21.11 server**: `./gradlew build`
ends with the `runtimeSmoke` task, which starts a headless Paper server with the built jar, runs
`/null selftest` from the console, and fails the build unless every check passed and the server log
is clean. A green build therefore proves compilation, the 66-check core test suite, and that a Null
and a squad really spawn, get tracked, are delivered to a viewer's connection as the two packets a
client needs, emerge from real temporary portal doorways, survive ticking and go out one at a time
when a Totem Of Null is destroyed.

It still does **not** prove what a GPU drew. Before calling this a stable v1.0.0, a human has to
join a real server and confirm the remaining checks listed in `STATUS.md` ("What a headless server
cannot prove"): skin rendering, nameplate/tab appearance, how the doorway looks against the owner's
reference screenshot, the cannon's arc when a player aims it, and a chunk unload/reload cycle.

Run it yourself with `./gradlew runtimeSmoke` (needs network access to download Paper). If Paper
cannot be downloaded the task reports `RUNTIME SMOKE: BLOCKED` and prints the checks that are still
outstanding — it never reports success it did not earn.
