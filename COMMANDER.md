# The Null Commander

One named Null that spawns from a portal, wears one configured skin, carries a loadout you edit
in a GUI, and fights with a real mace/elytra technique library.

> ### ⚠️ Status: written, not run
> **None of this has ever been compiled.** It was authored in a sandbox with no JDK and no route
> to Mojang's API, so the skin lookup, the GUI and the spawn have **never executed**. The
> technique library in `core` is pure logic and is covered by unit tests, but those tests have
> never been *run* either. Treat everything here as "authored, awaiting a first build".

---

## Commands

| Command | Permission | What it does |
| --- | --- | --- |
| `/null commander` | `nullarmy.commander` | Summons the Commander out of a portal where you stand |
| `/null loadout` | `nullarmy.gui` | Opens the loadout editor |
| `/null skin` | `nullarmy.admin` | Shows skin resolution state (never prints the texture blob) |
| `/null status` | `nullarmy.admin` | Adapter, live Null count, policy flags |

All default to `op`.

---

## Spawning: it comes out of a portal

```
/null commander
```

1. The plugin looks for a **collision-safe** spot near you — a small outward spiral, up to two
   blocks up. If it cannot find one, it says so and spawns nothing. **No teleporting out of bad
   positions** (spec 3).
2. The body is created.
3. **Portal effects play at the spawn point**, at least 15 per spec 3 — the Commander is
   *stepping out* of them, not blinking into existence.
4. The saved loadout is applied.

If the Commander is already alive, the command tells you to `/null dismiss` first instead of
silently doing nothing.

---

## The skin: configurable, for Nulls and the Commander

Every Null and the Commander wear the skin of a Minecraft account **you choose**. Set it in
`config.yml`:

```yaml
skins:
  # Skin for ordinary Nulls.
  nulls: "uH3WR2v0ti0uTHJ"

  # Skin for the Commander. Empty = same as the Null skin above.
  commander: ""
```

Two independent values. Leave `commander` empty and it inherits `nulls`, so setting one line
changes every NPC.

You can also override without editing any file, which is handy when the name turns out to be
wrong and the server is already running:

```
-Dnullarmy.skin.null=SomeName
-Dnullarmy.skin.commander=SomeOtherName
```

`/null skin` shows what is configured, what inherited from what, and whether each resolved.

> ### ⚠️ If the name isn't a real account, nothing breaks — you just won't see that skin
> `uH3WR2v0ti0uTHJ` first appears in `NullArmy_Master_Prompt.md` §3 as an **example of the random
> name format** Nulls are given, not as a verified Minecraft account. If no such account exists,
> the lookup returns nothing and those NPCs keep the default Steve/Alex skin. The plugin logs a
> warning naming exactly which value failed and how to change it. **Cosmetic only — a missing
> skin never blocks a spawn.**

### How the skin is resolved

`SkinResolver` tries four sources, cheapest first:

| # | Source | Where | Notes |
| --- | --- | --- | --- |
| 1 | **Memory** | in-process | Already resolved this session |
| 2 | **Disk cache** | `plugins/NullArmy/skins/<name>.skin` | Written after the first successful fetch. Server restarts are instant and work offline. |
| 3 | **Bundled** | `skins/<name>.skin` inside the jar | Lets you ship the skin permanently — **zero network, ever**. This is why the jar may be large. |
| 4 | **Network** | Mojang's API | **Async only.** Never on the main thread. |

The network path is exactly the two calls every skin plugin makes:

```
GET https://api.mojang.com/users/profiles/minecraft/<name>            -> UUID
GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid> -> textures value + signature
```

**Both halves are required.** A texture value without Mojang's signature is rejected by the
client, so if either is missing the plugin leaves the profile alone rather than pretending it
applied a skin (`IMPLEMENTATION_PLAN.md` A-05).

### Changing the skin owner

Either edit `DEFAULT_SKIN_OWNER`, or — without touching code — start the server with:

```
-Dnullarmy.skin.owner=SomeOtherName
```

### Baking the skin in (never touch the network)

If Mojang is blocked on your host, or you simply want zero first-run delay:

1. Fetch the skin once (`/null skin` after a successful lookup, or any Mojang profile tool).
2. Copy `<name>.skin` from `plugins/NullArmy/skins/` into the jar as `skins/<name>.skin`.
3. Rebuild.

The bundled copy is found before any network call.

### If the lookup fails

You get a warning, and Nulls use the default skin. **The plugin keeps working** — a missing skin
is cosmetic, never fatal, and never blocks a spawn.

---

## The loadout GUI

```
/null loadout
```

A plain 6-row chest. No GUI library, nothing to install.

```
row 0 : [helmet] [chestplate] [leggings] [boots] [offhand]
row 1 : storage
row 2 : storage
row 3 : storage
row 4 : hotbar
row 5 : [Clear]  [Save and close]  [Cancel]  [info]
```

- Put the items in that you want the Commander to carry.
- **Save and close** applies it to a live Commander immediately *and* writes it to
  `commander.yml`.
- **Cancel**, or just closing the chest, **discards the edit** — a mis-click can never silently
  overwrite a loadout you were happy with.
- **Clear** empties every slot (still needs Save to stick).

It is a **blueprint editor, never a duplicator**: the items come from your own inventory, and
nothing is created out of nothing.

### Known limitation, stated honestly

The loadout crosses into the version-neutral NMS layer as `{slot, material name, count}`
(`nms:api` has no dependency on the server, so it cannot carry Bukkit `ItemStack`s). That means
**material and count survive; enchantments, custom names and NBT do not.** Adding NBT support
means either giving `nms:api` a server dependency or serialising item NBT as a string — both are
reasonable, neither is done.

---

## Inventory: the same 41 slots a real player has

The Commander's inventory is **exactly** a real player's — 41 slots, same indices, same meaning:

| Slots | What |
| --- | --- |
| `0-8` | hotbar |
| `9-35` | main storage (3 rows of 9) |
| `36` | boots |
| `37` | leggings |
| `38` | chestplate |
| `39` | helmet |
| `40` | offhand |

The GUI mirrors that layout, so what you see is what the Commander actually carries.

> **Why this is called out:** Bukkit's `PlayerInventory` exposes only slots `0-35` through
> `setItem()`. The armour and offhand slots are separate methods (`setBoots`, `setLeggings`,
> `setChestplate`, `setHelmet`, `setItemInOffHand`). Writing indices `36-40` with `setItem()`
> **silently does nothing** — the classic way a loadout "just doesn't work". The adapter maps
> them explicitly, and a test pins the layout.

---

## Combat: mace and elytra

The Commander picks techniques from `PvpArsenal` — **25 techniques** across three disciplines.
The selector is **pure and deterministic**: same situation, same choice, every time. No
randomness, no I/O, no Bukkit — so it is unit tested with nothing but a JRE.

### Mace (12)

| Technique | When |
| --- | --- |
| `FULL_SMASH` | Fall from full height onto a target the smash will kill |
| `COMBO_DOUBLE_SMASH` | Wind Burst bounces you up for a second smash on a healthy target |
| `WIND_BURST_RECOVERY` | Use Wind Burst to bounce up instead of eating the fall |
| `DENSITY_BURST` | Density adds damage per block — take the longest fall available |
| `BREACH_SHIELD_BREAK` | Breach cuts armour; hit a shielded target with the mace, not a sword |
| `HOTBAR_SWAP_SMASH` | Fall holding a sword, swap to the mace last moment so they can't pre-shield |
| `PEARL_SMASH` | Pearl straight up, then smash down |
| `WIND_CHARGE_LAUNCH` | Wind charge downward for height, convert into a smash |
| `ELYTRA_DIVE_SMASH` | Dive on elytra to build speed, switch to the mace for the hit |
| `AERIAL_JUKE` | Wind charge sideways mid-fall to dodge an arrow or a counter-smash |
| `MLG_WATER_RESET` | Smash missed — water bucket to cancel fall damage and reset |
| `NO_COMMIT` | **Refuse to jump**: the fall isn't lethal, so committing just gives away height |

### Elytra (11)

| Technique | When |
| --- | --- |
| `ROCKET_CHAIN` | Chain fireworks to hold speed in a long chase |
| `FIREWORK_CONSERVE` | Stop boosting and glide — fireworks are nearly gone |
| `STRAFE_RUN` | Strafe so incoming shots can't lead you |
| `DRIVE_BY_BOW` | Hold the draw through the pass, release at the closest point |
| `CROSSBOW_PUNISH` | Close fast and burst at point-blank |
| `SWOOP_SWORD` | Dive through with a sword for a crit (when no mace) |
| `WIND_CHARGE_BOOST` | Wind charge instead of a firework — boost that costs no fireworks |
| `RIPTIDE_LAUNCH` | Riptide in rain/water to regain height for free |
| `PEARL_CHAIN` | Pearl behind the target and reset the engagement |
| `LANDING_CANCEL` | Retract the elytra to drop fast instead of overshooting |
| `RETREAT_CLIMB` | Low health — climb away and live to re-engage |

### Movement (2)

`SHIELD_TURTLE` (raise the shield and close the distance), `DISENGAGE` (the universal fallback —
it applies to every situation, so the selector can never return null).

### The safety rule built into the selector

**The Commander refuses to commit to a smash that cannot kill.** `NO_COMMIT` exists precisely so
it doesn't throw away its height for a hit that leaves it stranded next to a full-health target.
A test asserts this.

---

## The Commander's username

In `config.yml`:

```yaml
commander:
  name: "NullCommander"     # player names cannot contain spaces
  spawn-with-portal: true
```

Note the distinction:

- **`commander.name`** — the *display name* the Commander shows.
- **`SkinResolver.DEFAULT_SKIN_OWNER`** (`uH3WR2v0ti0uTHJ`) — the account whose **skin** every
  Null wears.

They are independent, and both are changeable.

---

## No dependencies

Everything here is built in-repo:

| Need | How it's done | Dependency |
| --- | --- | --- |
| Skin lookup | `java.net.http.HttpClient` (in the JDK since Java 11) | none |
| JSON | ~40-line scanner in `MojangSkinClient.extractString` | none (no Gson) |
| Loadout GUI | Plain Bukkit chest inventory + click handler | none |
| Persistence | Bukkit's own `YamlConfiguration` | none |
| Technique library | Pure Java in `core` | none |
| PvP tactics | Deterministic selector in `core` | none |

The only thing the plugin needs is **Paper itself**. You were explicit that the jar may exceed
50 MB to achieve that — the only thing that would make it big is bundling skin textures, which is
optional.

---

## What isn't done

Stated plainly so it isn't mistaken for working code:

- ❌ **Nothing has been compiled.** Not once.
- ❌ The GUI has never been opened; the click-handler slot maths is only checked by a small unit
  test in `core`.
- ❌ The skin has never been fetched — the sandbox has no route to `api.mojang.com`.
- ❌ The Commander does not yet *call* the technique selector during real combat; `plan()` is
  wired in but no AI tick drives it yet.
- ❌ NBT/enchantments are not preserved in the loadout (see above).

See [`BUILD_TUTORIAL.md`](BUILD_TUTORIAL.md) for how to produce the first real jar, and
[`STATUS.md`](STATUS.md) for the feature-by-feature picture.

---

**Copyright (c) RedGlitchX. All rights reserved.**
