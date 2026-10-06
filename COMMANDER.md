# The Null Commander

One named Null that spawns from a portal, wears one configured skin, carries a loadout you edit
in a GUI, and fights with a real mace/elytra technique library.

> ### ✅ Status: implemented and verified on a live Paper 1.21.11 server
> CI run `37432208719` (commit `d0200c1`) ends with `RESULT: PASS 120 passed, 0 failed` and
> `RUNTIME SMOKE: PASS - verified on a live Paper server.` The Commander-specific checks are S-95
> (kit, Elytra, white trim, loadout preserved), S-105 (sneak + horn recall), S-113 (live rename,
> `NAME: MESSAGE` replies) and S-117 (the `/null tp` cannon includes the Commander when he belongs
> to the owner who fired it). What still needs a real client is listed at the end of this file.

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

Every Null and the Commander can wear a signed skin from a Minecraft account **you choose**, a
pasted signed texture, or a custom PNG signed through MineSkin. Configure account names and signed
texture data in `config.yml`:

```yaml
skins:
  # Skin for ordinary Nulls.
  nulls: "uH3WR2v0ti0uTHJ"

  # Skin for the Commander. Empty = same as the Null skin above.
  commander: ""
```

Two independent account names. Leave `commander` empty and it inherits the Null skin, so one
setting can cover every NPC.

### Registering a custom skin PNG

Place the image at `plugins/NullArmy/skins/null.png`. A PNG by itself is not a usable Minecraft
player texture: the plugin must obtain a signed texture value and signature. Configure a MineSkin
API key, preferably through an environment variable:

```yaml
skins:
  mineskin:
    api-key: "env:MINESKIN_API_KEY"
```

The plugin uploads and polls asynchronously, then caches MineSkin's signed result. The literal
key may be configured, but `env:NAME` is safer; the resolved secret is hidden from diagnostics and
is never forwarded across redirects. Alternatively, paste an already-signed pair into
`skins.value` and `skins.signature`, or use a trusted `skins.proxy-url`. Unsigned PNG bytes or a
value without a signature are never applied.

You can also override the account names without editing any file, which is handy when a name
turns out to be wrong and the server is already running:

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

`SkinChain` resolves skins in this order:

| # | Source | Where | Notes |
| --- | --- | --- | --- |
| 1 | **Signed config texture** | `skins.value` + `skins.signature` | Both are required; no network. |
| 2 | **Custom PNG** | `plugins/NullArmy/skins/null.png` via MineSkin | Upload/poll is asynchronous; the signed result is cached. |
| 3 | **Trusted proxy** | `skins.proxy-url` | Must return a signed texture pair. |
| 4 | **Account skin** | `skins.nulls` / `skins.commander` via Mojang | Existing in-memory, disk and bundled caches are tried before network. |

Mojang's account lookup uses two calls:

```
GET https://api.mojang.com/users/profiles/minecraft/<name>            -> UUID
GET https://sessionserver.mojang.com/session/minecraft/profile/<uuid> -> textures value + signature
```

**Both halves are required.** A texture value without a valid signature is rejected by the client;
if either is missing the plugin leaves the profile alone rather than pretending it applied a skin.
All network resolution happens off the main thread.

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

### Item fidelity

The GUI and `commander.yml` store Bukkit `ItemStack`s, and the live Paper 1.21.11 path applies
cloned full stacks directly to the Commander's inventory. Enchantments, potion metadata, names and
other serializable item data therefore survive save/load on that path. The version-neutral
`LoadoutSlot` fallback carries only slot, material and count; an adapter that cannot expose the
Bukkit player handle cannot preserve the rest of an item's metadata. Armor trims are normalized on
application: only the Commander's chestplate receives the configured white quartz trim.

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

`PvpArsenal` contains **25 deterministic techniques** across mace, elytra and movement. The
pure selector is covered by dependency-free core tests. In live combat, the Commander builds a
situation from its inventory, target and fall state, then currently executes supported smash,
Wind Burst, Density and elytra-dive mace choices through vanilla `Player#attack`. Shield-break,
pearl, water-placement and flight-controller entries are still planning-library strategies, not
implemented live actions.

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

## Limits and verification

- The current working tree is verified by the live Paper 1.21.11 smoke run recorded in
  `STATUS.md` (CI `37432208719`, `RESULT: PASS 120 passed, 0 failed`). Checks that cannot be driven
  headless print `BLOCKED:` and assert the closest measurable thing instead — S-44, S-69 and S-94
  carry such a note.
- The live Commander integration currently selects supported mace weapon choices only. Elytra
  flight control, pearl movement, water placement, crossbow combos and other library entries are
  not executed yet; they must not be presented as working tactics.
- A real client should still verify the visible skin, chestplate trim, portal entrance and GUI
  appearance after a successful build. Skin tests using local stubs do not prove external MineSkin
  or Mojang availability.

See `BUILD.md` for the verification workflow and `STATUS.md` for the broader implementation
snapshot and remaining blockers.

---

**Copyright (c) RedGlitchX. All rights reserved.**
