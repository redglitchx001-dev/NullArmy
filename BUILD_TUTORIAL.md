# Building NullArmy into a `.jar`

This guide builds the Paper plugin JAR. **Building is not the same as proving the plugin works**:
NullArmy has no stable v1.0.0 release, and the NMS adapter still needs live-server verification.
See [`BUILD.md`](BUILD.md) for the release gates.

## Requirements

- JDK 21
- Internet access to Gradle, Maven Central, and PaperMC's Maven repository
- A disposable Paper 1.21.11 server for runtime tests

Gradle 9.8.0 is pinned in the checked-in wrapper. You do not need to install Gradle separately.

## 1. Get the source

```bash
git clone https://github.com/redglitchx001-dev/NullArmy.git
cd NullArmy
```

On Windows, run the commands below with `gradlew.bat` instead of `./gradlew`.

## 2. Build and run automated checks

```bash
./gradlew clean build --no-daemon
```

The first build downloads Gradle and the Paper development bundle, so it can take several minutes.
The `build` task runs the dependency-free core test suite as part of `check`.

The installable plugin is:

```text
plugin/build/libs/NullArmy-0.1.0-dev.jar
```

The root `version` in `gradle.properties` controls the filename and the version expanded into
`plugin.yml`. The final JAR includes NullArmy's own core and adapter classes; Paper's API/server
classes are provided by the server and are not bundled.

To run only the pure core test suite:

```bash
./gradlew :core:coreTestSuite --no-daemon
```

## 3. Inspect the artifact

```bash
jar tf plugin/build/libs/NullArmy-0.1.0-dev.jar | grep -E '^(plugin.yml|redglitchx/nullarmy/)'
unzip -p plugin/build/libs/NullArmy-0.1.0-dev.jar META-INF/MANIFEST.MF
sha256sum plugin/build/libs/NullArmy-0.1.0-dev.jar
```

Check that the JAR contains `plugin.yml`, `NullArmyPlugin`, `ItemLedger`, and
`V1_21_11Adapter`. The manifest must state `paperweight-mappings-namespace: mojang`.

## 4. Test on a disposable Paper server

Only test on Paper 1.21.11 / Java 21 for this adapter. Keep the server disposable and back up its
world. Record the exact server build and full startup log.

At minimum, check plugin startup, command registration and permissions, adapter loading, NPC spawn
and removal lifecycle, client visibility, and whether a Null creates an unintended
`world/playerdata/<uuid>.dat`. The features listed as `not started` or `partial` in `STATUS.md` are
not expected to work; a successful startup must not be described as full feature verification.

## GitHub Actions build artifact

The `Build and verify` workflow runs on branch pushes, pull requests, and `v*` tags. A successful
run uploads the JAR and SHA-256 checksum as a `NullArmy-JAR` artifact for 14 days. This is a CI
artifact, **not a published GitHub Release**. Download it from the workflow run's **Artifacts**
section and perform the live-server checks before considering a release.

## Troubleshooting

- **`JAVA_HOME` / Java version errors:** install and select JDK 21, then rerun `java -version`.
- **Gradle distribution checksum error:** do not remove the pinned checksum; verify the download
  and retry with a fresh Gradle user home.
- **Paper dev-bundle resolution failure:** confirm access to `repo.papermc.io` and attach the full
  Gradle error to a report. Do not guess a different bundle version.
- **Compilation or NMS errors:** include the first failing task and full error output. CI compilation
  is an early gate, not proof of runtime compatibility.

**Do not publish a stable v1.0.0 release until the runtime checks and the checklist in
[`RELEASING.md`](RELEASING.md) are complete.**
