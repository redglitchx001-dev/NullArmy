# NullArmy release notes

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
