# Tutorial — Building NullArmy into a `.jar`

A step-by-step guide from a fresh machine to `NullArmy-0.1.0-dev.jar` running on a Paper server.

> ### ⚠️ Read this first
> **This project has never been compiled.** It was authored in a sandbox with no JDK and no
> access to `repo.papermc.io`, so it could not be built or verified there (`BUILD.md` blocker B-1).
> All 37 Java files are **syntactically valid** (verified with a Java parser) but **no compilation
> has ever succeeded**.
>
> **Expect to hit errors on your first build.** That is normal and not a sign you did anything
> wrong — [§8 Troubleshooting](#step-8--troubleshooting) covers the likely ones. The most probable is
> the Paper dev-bundle version in `nms/v1_21_11/build.gradle.kts`, which is an unverified guess.
>
> If you get it building, please open an issue with the exact command and output — it becomes the
> first real build record for this project.

---

## What you need

| Requirement | Version | Why |
| --- | --- | --- |
| **JDK** | 21 or newer | Required by every Paper 1.21.x build |
| **Gradle** | 8.x | `paperweight-userdev` is a Gradle plugin — Maven is not supported for NMS |
| **Internet** | — | Downloads the Paper dev bundle (~100 MB+) and dependencies |
| **Disk** | ~3 GB free | Gradle caches + dev bundle |
| **A Paper server** | 1.21.11 | To test the jar |

---

## Step 1 — Install JDK 21

Pick the JDK that suits you (Adoptium Temurin is the common choice):

**Windows**
```
winget install EclipseAdoptium.Temurin.21.JDK
```
or download the `.msi` from <https://adoptium.net>.

**macOS**
```bash
brew install --cask temurin@21
```

**Linux (Debian/Ubuntu)**
```bash
sudo apt update && sudo apt install -y openjdk-21-jdk
```

### Verify
```bash
java -version
```
You want a line containing `21`, e.g.:
```
openjdk version "21.0.4" 2024-07-16
```

> If `java` is not found after installing, restart your terminal — the `PATH` changes are
> applied to new shells only.

---

## Step 2 — Install Gradle

**Windows**
```
winget install Gradle.Gradle
```
**macOS**
```bash
brew install gradle
```
**Linux**
```bash
sudo apt install -y gradle      # often older; SDKMAN is preferable:
curl -s "https://get.sdkman.io" | bash
sdk install gradle
```

### Verify
```bash
gradle --version
```
Gradle **8.x** is expected.

> **Java/Gradle mismatch?** Gradle 8.x supports JDK 21. If you see
> `Unsupported class file major version`, your Gradle is too old — upgrade it.

---

## Step 3 — Get the code

```bash
git clone https://github.com/redglitchx001-dev/NullArmy.git
cd NullArmy
git checkout arena/01a10183-nullarmy
```

Confirm you have the source:
```bash
ls
# BUILD.md  IMPLEMENTATION_PLAN.md  MECHANICS_EXPANSION.md  NullArmy_Master_Prompt.md
# README.md  STATUS.md  TRACEABILITY.md  AGENTS.md  BUILD_TUTORIAL.md
# build.gradle.kts  core/  gradle.properties  nms/  plugin/  settings.gradle.kts
```

---

## Step 4 — (Optional) Generate the Gradle wrapper

The repo ships without a wrapper jar. Generate one so builds are reproducible:

```bash
gradle wrapper --gradle-version 8.14
```

This creates `gradlew`, `gradlew.bat` and `gradle/wrapper/`. From then on use `./gradlew`
instead of `gradle`.

*(Skip this step and just use `gradle` if you prefer.)*

---

## Step 5 — Build

```bash
./gradlew build          # or: gradle build
```

**The first build is slow** — it downloads Gradle's own dependencies plus the Paper dev bundle.
Allow several minutes.

### What success looks like
```
BUILD SUCCESSFUL in 3m 12s
```

### Where the jars land

| Module | Output |
| --- | --- |
| `core` | `core/build/libs/nullarmy-core-0.1.0-dev.jar` |
| `nms/v1_21_11` | `nms/v1_21_11/build/libs/nullarmy-nms-v1_21_11-0.1.0-dev.jar` |
| `plugin` | **`plugin/build/libs/NullArmy-0.1.0-dev.jar`** ← this is the one you install |

### Build just the plugin
```bash
./gradlew :plugin:build
```

### Skip the tests (if you hit a test failure you want to look at separately)
```bash
./gradlew build -x test
```

---

## Step 6 — Run the logic tests

The `core` module has zero dependencies, so its tests need nothing but a JRE:

```bash
./gradlew :core:testClasses
java -cp "core/build/classes/java/main:core/build/classes/java/test" \
     redglitchx.nullarmy.core.CoreTestSuite
```

On Windows the classpath separator is `;`:
```cmd
java -cp "core\build\classes\java\main;core\build\classes\java\test" redglitchx.nullarmy.core.CoreTestSuite
```

Exit code `0` and `ALL CORE TESTS PASSED` means the item ledger, boids separation, pathfinding,
block-plan validation, JSON codec, circuit breaker and the new endpoint/agent registry all
behave as specified.

---

## Step 7 — Install the jar on your server

1. Stop the server.
2. Copy `plugin/build/libs/NullArmy-0.1.0-dev.jar` into your server's `plugins/` folder.
3. Start the server.

On startup you should see either:
```
[NullArmy] NullArmy enabled on 1.21.11 using adapter 1.21.11
```
or, if the running version is unsupported, a clear failure explaining that no adapter matches.

### Smoke test

```
1. Rename a Goat Horn to exactly "Call Horn" at an anvil
2. Hold it and right-click  ->  "How many Nulls do you want to summon?"
3. Type 3 in chat           ->  "Summoned 3 Nulls."
   Expect: >=15 portal particles, 3 Nulls that WALK out,
           none sharing coordinates, none inside blocks
4. /null status             ->  adapter, live Null count, policy flags
5. ls world/playerdata/     ->  no new <uuid>.dat (see BUILD.md V-03)
```

---

## Step 8 — Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| `Could not resolve io.papermc.paper:dev-bundle:1.21.11-R0.1-SNAPSHOT` | The dev-bundle coordinate in `nms/v1_21_11/build.gradle.kts` is **an unverified guess** | Find the real coordinate for your Paper build and update it (see `BUILD.md` V-05) |
| `Plugin [id: 'io.papermc.paperweight.userdev'] was not found` | Wrong/absent paperweight version, or the Paper repo is missing from `settings.gradle.kts` | Add Paper's repo to `pluginManagement` and pin a real paperweight version |
| `Unsupported class file major version 68` | Building with a JDK newer than Gradle supports, or vice versa | Use JDK 21 + Gradle 8.x |
| `Cannot find symbol: CraftServer` | CraftBukkit import path | Paper 1.20.5+ **dropped CraftBukkit package relocation** — use `org.bukkit.craftbukkit.CraftServer` with no version segment |
| `NoSuchMethodError` on an NMS call at runtime | Mojang-mapped vs reobfuscated mismatch | See `BUILD.md` V-02 — 1.21.11 b17060+ removed runtime remapping |
| Build hangs / times out downloading | Firewall blocking `repo.papermc.io` | Allow `https://repo.papermc.io` and `https://repo1.maven.org` |
| `error: package org.bukkit does not exist` | `compileOnly` paper-api not resolved | Check the Paper repo is declared in `plugin/build.gradle.kts` |
| Tests fail on `AgentRole` count | You changed the role enum | Update `testRoleLookup` and `config.yml` to match |

### If the build fails, please report it

Open an issue with:
- your OS, `java -version`, `gradle --version`
- the exact command
- the full error output (first 50 lines is plenty)

That turns an unverified guess into a verified coordinate.

---

## Rebuilding after changes

```bash
./gradlew clean build
```

Incremental (faster):
```bash
./gradlew build
```

---

## Quick reference

```bash
# full build
./gradlew build

# plugin jar only
./gradlew :plugin:build

# tests
./gradlew :core:testClasses
java -cp "core/build/classes/java/main:core/build/classes/java/test" \
     redglitchx.nullarmy.core.CoreTestSuite

# where the jar is
ls plugin/build/libs/
```

---

**Copyright (c) RedGlitchX. All rights reserved.**
