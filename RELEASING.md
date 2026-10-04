# NullArmy Release Readiness

**Copyright (c) RedGlitchX. All rights reserved.**

---

## Current decision: no stable v1.0.0 yet

There is no GitHub Release or version tag yet. The current project version remains
`0.1.0-dev`. The user requested a stable v1.0.0, but selected **wait until the build and a real
Paper-server test are validated**. Do not tag or publish v1.0.0 until the gates below pass.

A green GitHub Actions build proves that Gradle compiled the source and that the automated,
dependency-free core test suite passed. It does **not** prove NMS runtime behavior or that the
incomplete gameplay described in the README is implemented. Current project status and feature
coverage are tracked in [`STATUS.md`](STATUS.md) and [`TRACEABILITY.md`](TRACEABILITY.md).

## Gates for a stable v1.0.0

- [ ] The clean `./gradlew clean build` GitHub Actions run succeeds on the release commit.
- [ ] The produced `NullArmy-1.0.0.jar` contains the plugin descriptor, bootstrap class, core
      classes, and the 1.21.11 adapter; it contains no Paper/server classes.
- [ ] Install and startup are verified on a disposable Paper 1.21.11 server running Java 21.
- [ ] Command registration, permission checks, supported-version handling, NPC lifecycle, client
      visibility, player-data behavior, and shutdown are smoke-tested and documented with the exact
      Paper build number.
- [ ] `README.md`, `STATUS.md`, and `TRACEABILITY.md` accurately describe what is implemented and
      what is not. A successful boot must not be treated as feature completion.
- [ ] A `LICENSE` file and redistribution terms have been chosen by the project owner.
- [ ] Release notes describe limitations and installation requirements; checksum is attached.

At the time this checklist was added, `STATUS.md` says 0 of 47 core features and 0 of 471
catalogue mechanics are implemented. That is incompatible with presenting the advertised NullArmy
gameplay as a finished stable v1. Either complete the declared v1 scope, or explicitly revise the
product scope and acceptance criteria before calling a release stable.

## Build and inspect the release candidate

The Gradle wrapper pins Gradle 9.8.0 and its distribution checksum. Use JDK 21 and run:

```bash
./gradlew clean build --no-daemon
```

The plugin artifact path is `plugin/build/libs/NullArmy-<version>.jar`. Before a future release,
set the Gradle version to the chosen version (for example `1.0.0`) and check the built artifact:

```bash
VERSION=1.0.0
JAR="plugin/build/libs/NullArmy-${VERSION}.jar"
jar tf "$JAR" | grep -E '^(plugin.yml|redglitchx/nullarmy/)'
unzip -p "$JAR" META-INF/MANIFEST.MF | grep -i '^paperweight-mappings-namespace: mojang$'
sha256sum "$JAR" > "${JAR}.sha256"
```

The GitHub workflow runs on branch pushes, pull requests, and `v*` tags. It builds, verifies the
JAR contents, and uploads a `NullArmy-JAR` artifact with a SHA-256 file for 14 days. It deliberately
does **not** publish GitHub Releases automatically; the live-server checks are a manual release
gate.

## Publish after all gates pass

1. Merge the fully tested release commit to the repository's default branch.
2. Update `gradle.properties` to `version=1.0.0`; update README/status/release notes and run the
   full clean build again.
3. Create and push the annotated tag from the verified default-branch commit:

   ```bash
   git tag -a v1.0.0 -m "NullArmy v1.0.0"
   git push origin v1.0.0
   ```

4. Wait for the tag's GitHub Actions build to pass. Download its `NullArmy-JAR` artifact from the
   successful workflow run and confirm the checksum.
5. Publish the GitHub Release with the verified JAR and checksum attached:

   ```bash
   gh release create v1.0.0 \
     --title "NullArmy v1.0.0" \
     --generate-notes \
     release-assets/NullArmy-1.0.0.jar \
     release-assets/NullArmy-1.0.0.jar.sha256
   ```

6. Download the published asset once more and verify its SHA-256 against the attached checksum.

For prerelease builds after readiness improves but before stable acceptance, use a SemVer prerelease
suffix such as `1.0.0-rc.1`, label the GitHub Release as a prerelease, and state the limitations
prominently. Do not use a stable `v1.0.0` tag for an unverified build.
