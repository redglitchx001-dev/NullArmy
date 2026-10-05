# NullArmy release notes

## v4 — "an army, not a crowd" (branch `arena/01a10afa-nullarmy`)

Everything in the v4 dossier (P-01 … P-12, L-01 … L-08) is implemented **in place** on top of v3
and ships with a live regression check (`S-85` … `S-115`) that runs on a real Paper 1.21.11 server
inside `./gradlew build`. The v3 checks (S-27 … S-84), the original 26 and the core suite still run
and still pass.

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
| **P-03** | Readable unique names ≤ 16 chars (a themed word + a small number: `Voidwalker`, `Null_07`, `Grimjaw_12`), never hex, never a UUID; staggered portal emergence, swirl at the mouth only, real obsidian + purple frame. | S-89, S-90 |
| **P-04** | The totem/horn holder is the sole commander; Nulls never hit the owner, the Commander or their mates; an order that is not the owner's moves nothing. | S-91, S-92 |
| **P-05** | No friendly fire — a `NullArmy-<owner8>` team with friendly fire off **and** an event filter behind it. Imperfect aim (`combat.aim-skill`, 0-1, default 0.65): ±4-10° of error, lead error, 0.3-0.8 s of reaction time, full misses beyond 12 blocks. Perfect aim is banned. | S-93, S-94 |
| **P-06** | Nulls keep the netherite kit; the Commander gets a boss kit (mace, elytra, ×2 totems, ×4 enchanted gapples, wind charges, rockets, netherite sword). Fresh installs get it; a saved `commander.yml` is never overwritten. | S-95 |
| **P-07** | Build v2: owner-relative goals (`bridge in front of me` → ≥ 5 blocks along the facing from 2 ahead; `a throne` → seat, back, armrests, gold/wool accents, facing the owner). `ai.builder.endpoint` accepts any OpenAI-compatible base URL **or** `id:<name>` from `ai.endpoints`. | S-96, S-97, S-98 |
| **P-08** | The Orbital Wither Cannon fires **wither-blue skulls** instead of TNT minecarts. Rod aiming (`/null cannon aim` → rod `NullAim`; stand = launch column, look = raycast to `range`, right-click locks the target with a particle marker + coordinates). Opt-in (`nullarmy.admin`, `enabled: false`), a confirm step, `blocks-damage: false`, and it never hurts the owner or his Nulls. | S-99, S-100 |
| **P-09** | Natural chat orders through a **pure, core, testable** `OrderParser`. Wake words `null`/`commander` **or** `@<commander.name>`/name prefix. An attack persists until the target is dead or gone; the owner, mates and `policy.protected` are never targeted; `destroy` needs `policy.griefing-enabled` **and** a confirm, otherwise **one console refusal line** and nothing said in chat. A non-owner's sentence is ignored in silence. | S-106 … S-109 |
| **P-10** | Nulls drop armour, hands and inventory on death (`drops.enabled: true`, `drops.chance: 1.0`). Player drops stay vanilla; nothing is void-deleted. | S-110 |
| **P-11** | `ServerListPingEvent`: `numPlayers` = real online + live Nulls, `max` = `motd.max-players` (2026), MOTD = `motd.format` (`NULL ARMY - {nulls} strong`), hover sample lists the army first. | S-111, S-112 |
| **P-12** | `commander.name` (default `NullCommander`) with a live rename `/null name <new>`. Anybody may talk to him by name and gets a one-or-two-line in-chat reply; only the owner is obeyed. | S-113 |
| **L-01** | March and drill on **one shared cadence**, locked formation, cycling line → wedge → phalanx. | S-101 |
| **L-02** | Auto-bridge gaps shallower than 4 blocks out of the body's own pack, sneaking at the edge. | S-102 |
| **L-03** | `patrol <a> <b>` for ever; `guard here` with head sweeps and a salute when the owner returns within 8 blocks. | S-103 |
| **L-04** | Camp life — ring round the light, spar in pairs with **zero** damage, eat when hurt, haul blocks to a builder in need (`behaviour.camp-life: true`). | S-104 |
| **L-05** | One scoreboard team per squad, friendly fire off, shared trim. | S-93 |
| **L-06** | Sneak + horn is the **recall** — the squad comes home in formation on the march cadence, never a new summon prompt. | S-105 |
| **L-07** | Hunt to the end: the order persists, at most two chasers, the rest hold, everyone regroups when the target is gone. | S-114 |
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
`combat.aim-skill: 0.65`, `combat.melee-reach: 3.0`, `combat.initiate: false` ·
`drops.enabled: true`, `drops.chance: 1.0` · `motd.show-army: true`, `motd.max-players: 2026`,
`motd.format: "NULL ARMY - {nulls} strong"` · `behaviour.camp-life|auto-bridge|march-cadence: true` ·
`names.style: "words"` · `wither-cannon.shots: 3`, `.minecarts-per-shot: 24`, `.pattern: "sphere"`,
`.fuse-ticks: 60`, `.shot-delay-ticks: 10`, `.range: 120`, `.enabled: false`, `.blocks-damage: false` ·
`ai.builder.endpoint: ""`, `ai.builder.gather-outside-zone: false`.

Every count is capped at 100. **Every new boolean defaults to true except**
`wither-cannon.enabled` (false), `combat.initiate` (false), `ai.builder.gather-outside-zone` (false)
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
| B-03 skins never applied | Same immutable profile map; skin only read from system properties; applied at spawn only. | Priority chain: `skins.value`+`signature` → `skins.proxy-url` → Mojang → `skin.png` (reported honestly); live re-apply (withdraw, re-announce, re-pair viewers); `/null skin` report. |
| B-04 Nulls stack / float / slide | Custom steering kept old velocity, no friction, no entity push, gravity only when "not on ground". | Bodies now move through vanilla `travel()` (friction, gravity, step-up, water, ladders), push like players, separation steering, a pile detector that walks extras out, real fall damage. |
| B-05 Nulls cannot be hit / never die | Since 1.21.4 `ServerPlayer` is **invulnerable until its client reports "loaded"** — the timeout runs in `ServerPlayer.tick()`, which a Null overrides. Also `canHarmPlayer` used the world PvP flag. Death messages broadcast through vanilla. | Client marked loaded; `combat.players-can-hit-nulls`; real death animation, no drops by default, death/advancement messages cleared. |
| B-06 frozen heads | Only `yRot` was ever set, never `yHeadRot`. | Head follows movement, glances at players within 8 blocks for 2-4 s, looks around when idle, turns to the speaker/order-giver, tracks targets. |
| B-07 "square" was a circle | Formation slots were circles / a diagonal. | Fixed cell matrices (line, rank, column, square, wedge, phalanx, arrow, encircle, turtle), spacing ≥ 1.1, rotated by the anchor's facing, optimal (non-crossing) cell assignment, no jitter when held. |
| B-08 chat spam, "destroyed s Totem Of Null" | Deaths, arrivals, shutdown progress, greetings went to chat; vanilla death/advancement broadcasts. | One `ChatGate`: chat only for answers, help, the summon prompt and the Commander; everything else → console + `/null status`. Event lines come from checked templates (core placeholder scan). Repeated horn press refreshes silently. |
| B-09 Commander only answered privately | Legacy chat event, wake word only, private replies. The shipped wake word `- null` is YAML's **null value**, so "null" never worked. | `AsyncChatEvent`; wake word **or the Commander's name** (any case) → public Commander reply; order words obeyed, Nulls gesture and stay silent. |
| B-10 | No per-Null loadout editing. | One themed loadout editor for the Commander and every Null; templates; persisted in `nulls.yml`; duplication-proof. |
| B-11 | Kits carried material + count only; Commander's enchanted gear arrived plain. | New netherite kit with enchantments and potions as real items; old untouched kit upgraded automatically; Commander keeps its saved items exactly. |
| B-12 | No idle life. | Per-Null speed (±10 %), arm swings, shield raise, bow draw, eating below 60 % health, crouch-rest after 60 s idle, sneak at ledges, swim, sprint over 8 blocks. |
| B-13 | Doorway was a 4x4 frame of real portal blocks. | Complete 4x5 obsidian frame, 2x3 **air** opening (one-way by construction), ground or floating 4-12 up, never cuts terrain, closes after the last Null steps out or after `portals.lifetime-s`. |
| B-14 | No zone. | `summon.zone-size` (default 100) around the horn user; everything stays inside; `/null zone` outline. |
| B-15 | No direct orders. | `/null order <name|all> <walk|run|sprint|jump|stop|follow|hold|gather|build|attack|defend>`, same words in chat; vanilla physics only. |
| B-16 | No builder. | `/null ai build <goal>`: OpenAI-compatible strict-JSON plan, validated, retried once, else the offline planner; placed by hand, one block per swing, paced, from the Nulls' own inventory. |
| B-17 | No real PvP. | Vanilla `Player#attack` with full cooldown, jump crits (×1.5), sprint knockback, strafing, shields (axe disables 1.5 s), bow lead + arc, retreat & heal. |

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
| `/null reload` | Now honest: a broken file is reported (line, column, the lines) and not applied. |

### New settings (appended to an existing config.yml automatically, with a backup)

`summon.zone-size` · `portals.lifetime-s`, `restore-after-exit`, `floating-chance`,
`air-height-min/max` · `combat.enabled`, `players-can-hit-nulls`, `nulls-can-hit-nulls`, `crits`,
`shields`, `bows`, `retaliate`, `initiate` (false), `fall-damage`, `shield-disable-ticks` ·
`nulls.no-death-drops`, `pickup-items`, `collisions`, `separation-radius`, `speed-variance`,
`idle-behaviour`, `eat-below-health`, `rest-after-idle-s` · `formations.spacing`, `auto-reform` ·
`orders.gesture-ack` · `skins.value`, `signature`, `proxy-url`, `live-reapply` ·
`chat.commander-public-replies`, `commander-name-trigger` · `ai.api-key` (or env
`NULLARMY_AI_KEY`) · `ai.builder.enabled`, `endpoint`, `api-key`, `model`, `timeout-ms`,
`max-steps`, `place-rate-ticks`, `gather-outside-zone` (false). Every count is capped at 100.

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
8. **Builder** — `/null ai build a small hut` (offline planner without an endpoint): Nulls walk,
   swing and place blocks one at a time from their own inventory; `/null ai stop` stops them.
   With `ai.builder.endpoint` + key set, the plan comes from your model.
9. **Skins** — paste a value + signature into `skins.value`/`skins.signature` (or set
   `skins.proxy-url`), `/null reload`: live Nulls change skin without relogging; `/null skin`
   shows the source per Null.
10. **Shutdown** — destroy the Totem Of Null: Nulls leave one by one; progress is in the console
    and `/null status`, not in chat.

### Honest limitations

* `skin.png` cannot be shown by clients without a signature (Mojang signs textures). It is
  reported, not faked; use `skins.proxy-url` with a signing service or paste value+signature.
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
