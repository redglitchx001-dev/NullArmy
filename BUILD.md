# Building & Verifying NullArmy

**Copyright (c) RedGlitchX. All rights reserved.**

---

## Release status

There is **no stable v1.0.0 release**. The source includes an early Paper 1.21.11 adapter, but it
has not yet been verified on a live server and most of the advertised gameplay is not implemented.
A successful Gradle build proves compilation and the dependency-free core checks only; it does not
prove that the plugin loads or behaves correctly in Minecraft.

The repository now includes a Gradle wrapper and a GitHub Actions build. The wrapper pins Gradle
9.8.0; the workflow uses JDK 21 and uploads a short-lived JAR artifact after a successful build.
This authoring sandbox has no JDK and cannot resolve Paper's Maven repository, so use the hosted
workflow or a properly provisioned machine for the full build.

## Prerequisites

| Requirement | Why |
| --- | --- |
| **JDK 21** | Required by the Paper 1.21.11 target. |
| **Internet access** | Fetches Gradle, the Paper dev bundle, and dependencies. |
| **Paper 1.21.11 server** | Required for runtime verification; compile success is not a smoke test. |

Gradle itself is pinned and downloaded by the checked-in wrapper. Do not replace it with an
unrelated system Gradle version when reporting build failures.

## Build

```bash
git clone https://github.com/redglitchx001-dev/NullArmy.git
cd NullArmy
./gradlew clean build --no-daemon
```

The installable plugin is:

```text
plugin/build/libs/NullArmy-<version>.jar
```

The JAR must include `plugin.yml`, the plugin classes, `core`, and the `nms` adapter classes. It
must **not** include Paper API/server classes. The plugin is Mojang-mapped and Paper-only; Spigot
compatibility is not claimed.

## Core tests

`core` is dependency-free. The plain-Java `CoreTestSuite` is wired into Gradle's `check` task, so a
normal `build` runs it automatically. To run only that suite:

```bash
./gradlew :core:coreTestSuite --no-daemon
```

The suite prints a `passed: N  failed: 0` summary on success.

## GitHub Actions artifact

Every branch push, pull request, and `v*` tag runs `.github/workflows/build.yml`. Once green, the
workflow uploads the verified plugin JAR and its SHA-256 checksum as the **`NullArmy-JAR`** build
artifact for 14 days. This workflow does **not** publish a GitHub Release.

The JAR-artifact check verifies that the descriptor, bootstrap class, core ledger, and 1.21.11
adapter are packaged, and that the manifest declares the Mojang mappings namespace. These checks
still do not substitute for installing the artifact on a real server.

## Runtime verification required before a stable release

Use a disposable Paper 1.21.11 server running Java 21. Keep a copy of the complete startup log and
record the exact Paper build number. At minimum, verify:

| Check | Required evidence |
| --- | --- |
| Plugin loading | Clean startup; `/plugins` shows NullArmy enabled; no class/mapping/linkage errors. |
| Command + permissions | `/null` registers, help/usage is visible, and each permission gate behaves as declared. |
| Adapter | The 1.21.11 adapter loads; unsupported versions fail clearly. |
| NPC lifecycle | Spawn, tick, damage, death, unload, and shutdown have no errors or orphaned entities. |
| Player data | A spawned Null does not create an unintended `world/playerdata/<uuid>.dat`. |
| Client visibility | A real client sees the Null and its movement/animation correctly. |
| Mechanics | Only features actually implemented in `STATUS.md` are tested; do not infer completion from a successful boot. |

The current implementation does not yet satisfy the full feature/acceptance checklist. In
particular, do not present the JAR as a finished NullArmy gameplay release merely because CI builds
it.

See [`STATUS.md`](STATUS.md), [`TRACEABILITY.md`](TRACEABILITY.md), and
[`RELEASING.md`](RELEASING.md) for the remaining work and release gates.
