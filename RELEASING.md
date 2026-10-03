# Tutorial — Making a Public Release

How to turn your source code into a GitHub release that other people can download, install, and trust.

This guide assumes you have already produced a working jar — see **[`BUILD_TUTORIAL.md`](BUILD_TUTORIAL.md)**.

---

## Table of Contents

- [The short version](#the-short-version)
- [Part 1 — Before your first release](#part-1--before-your-first-release)
- [Part 2 — Pick a version number](#part-2--pick-a-version-number)
- [Part 3 — Set the version in code](#part-3--set-the-version-in-code)
- [Part 4 — Automate the build (recommended)](#part-4--automate-the-build-recommended)
- [Part 5 — Build the release jar](#part-5--build-the-release-jar)
- [Part 6 — Write the release notes](#part-6--write-the-release-notes)
- [Part 7 — Publish the release](#part-7--publish-the-release)
- [Part 8 — After the release](#part-8--after-the-release)
- [Release checklist](#release-checklist)

---

## The short version

If you have a working jar in hand:

```bash
# 1. commit everything
git add -A && git commit -m "Release v0.1.0"
git push origin arena/01a10183-nullarmy

# 2. tag it
git tag -a v0.1.0 -m "v0.1.0 — first public release"
git push origin v0.1.0

# 3. upload the jar
gh release create v0.1.0 \
  --title "NullArmy v0.1.0" \
  --notes-file RELEASE_NOTES.md \
  plugin/build/libs/NullArmy-0.1.0-dev.jar#NullArmy-0.1.0.jar \
  plugin/build/libs/NullArmy-0.1.0-dev.jar.sha256
```

---

## Part 1 — Before your first release

Do these once. They protect you and your users.

| # | Item | Why |
| --- | --- | --- |
| 1 | **Build it at least once, yourself** ([BUILD_TUTORIAL.md](BUILD_TUTORIAL.md)) | Never ship a file you have never produced |
| 2 | **Run the smoke test** ([BUILD_TUTORIAL.md](BUILD_TUTORIAL.md#step-7--install-the-jar-on-your-server)) | `Call Horn` summon, `/null status`, no `playerdata` created |
| 3 | Add a `LICENSE` file | Without one, nobody legally has permission to use it. See **[§ Licence](#licence)** |
| 4 | Fill in your `plugin.yml` metadata | `authors`, `description`, `website` |
| 5 | Update `README.md` | What it does, how to install, supported versions |
| 6 | Delete or reset any real API keys | Even as env-var *names* in example config — make sure no real secret is committed |
| 7 | Push your branch | `git push origin arena/01a10183-nullarmy` |

> ### ⚠️ Be honest in the release notes
> This project has **never been compiled**. Until you complete step 1 above, the honest label for
> the first release is **pre-release / experimental**. Mark it as such with
> `gh release create ... --prerelease`. That is not a weakness — it tells users to expect rough
> edges and invites exactly the bug reports the project needs.

---

## Part 2 — Pick a version number

This project uses **[Semantic Versioning](https://semver.org)**: `MAJOR.MINOR.PATCH`.

| Change | Bump | Example |
| --- | --- | --- |
| Crashes, fixes a bug | PATCH | `0.1.0` → `0.1.1` |
| Adds a feature, backwards-compatible | MINOR | `0.1.1` → `0.2.0` |
| Breaks config format or API | MAJOR | `0.2.0` → `1.0.0` |
| Still unproven / untested on a real server | add a suffix | `0.1.0-dev`, `0.1.0-rc1` |

**Below `1.0.0` the project is unstable by convention** — expect breaking changes between minors.
That is the right label here given nothing has been built yet.

Suggested first version: **`v0.1.0-dev`**.

---

## Part 3 — Set the version in code

The version lives in **`gradle.properties`**:

```properties
group=redglitchx.nullarmy
version=0.1.0-dev
```

Change it, commit, and the jar name follows automatically:
`plugin/build/libs/NullArmy-0.1.0-dev.jar`.

> Keep `plugin.yml` `version:` in sync. Better: use Gradle resource filtering so there is
> exactly one source of truth — see [Part 4](#part-4--automate-the-build-recommended).

---

## Part 4 — Automate the build (recommended)

Hand-building works once. A CI workflow builds the same jar every time, cleanly, and gives
users reproducible downloads.

Create **`.github/workflows/build.yml`** in your repo:

```yaml
name: Build

on:
  push:
    branches: [ "**" ]
    tags: [ "v*" ]
  pull_request:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: Build
        run: ./gradlew build --no-daemon --stacktrace

      - name: Locate plugin jar
        id: jar
        run: |
          JAR=$(ls plugin/build/libs/*.jar | head -n 1)
          echo "path=$JAR"          >> "$GITHUB_OUTPUT"
          echo "name=$(basename $JAR)" >> "$GITHUB_OUTPUT"
          cd plugin/build/libs && sha256sum *.jar > sha256.txt

      - name: Upload jar artifact
        uses: actions/upload-artifact@v4
        with:
          name: NullArmy
          path: |
            plugin/build/libs/*.jar
            plugin/build/libs/sha256.txt
          if-no-files-found: error

      - name: Attach jar to release
        if: startsWith(github.ref, 'refs/tags/v')
        uses: softprops/action-gh-release@v2
        with:
          files: |
            plugin/build/libs/*.jar
            plugin/build/libs/sha256.txt
          generate_release_notes: true
```

### Then generate the wrapper (CI needs it)

```bash
gradle wrapper --gradle-version 8.14
git add gradlew gradlew.bat gradle/wrapper
git commit -m "Add Gradle wrapper"
```

> ### ⚠️ The first CI run will probably fail
> The Paper dev-bundle coordinate in `nms/v1_21_11/build.gradle.kts` is **an unverified guess**.
> CI is actually the best place to discover this: it does the whole thing on a clean machine with
> unrestricted network. Click the failing job, read the error, fix the coordinate, push again.
> Keep iterating until it's green — **that green checkmark is your first proof the project builds.**

---

## Part 5 — Build the release jar

```bash
./gradlew clean build
```

Verify what you're about to ship:

```bash
ls -la plugin/build/libs/
sha256sum plugin/build/libs/*.jar | tee plugin/build/libs/sha256.txt
```

### Pre-upload sanity checks

```bash
# Does it contain the plugin descriptor?
unzip -l plugin/build/libs/NullArmy-0.1.0-dev.jar | grep -E "paper-plugin.yml|plugin.yml"

# Does it contain your classes?
unzip -l plugin/build/libs/NullArmy-0.1.0-dev.jar | grep "redglitchx/nullarmy"

# Are all the modules bundled (no missing runtime deps)?
unzip -l plugin/build/libs/NullArmy-0.1.0-dev.jar | grep -c "\.class"
```

> **No API keys inside the jar.** Run this before every release:
> ```bash
> unzip -p plugin/build/libs/*.jar | grep -iE "sk-|api[_-]?key" && echo "LEAK!" || echo "clean"
> ```

---

## Part 6 — Write the release notes

Create `RELEASE_NOTES.md` (or let `generate_release_notes: true` do a first draft):

```markdown
## NullArmy v0.1.0

Minecraft plugin that summons an army of Minecraft-style "Null" NPCs
triggered only by blowing a goat horn renamed to "Call Horn".

### Requirements
- Paper **1.21.11**
- Java **21+**

### Install
1. Download `NullArmy-0.1.0.jar`
2. Drop it into your server's `plugins/` folder
3. Restart the server

### Usage
Rename a Goat Horn to `Call Horn` at an anvil, hold it, right-click.
Type a number in chat to choose how many Nulls to summon.

### AI (optional)
NullArmy works fully offline. To add an AI endpoint, see
[ENDPOINTS.md](https://github.com/redglitchx001-dev/NullArmy/blob/main/ENDPOINTS.md).

### ⚠️ Experimental
This is the first public build. Please report bugs at
<https://github.com/redglitchx001-dev/NullArmy/issues>.

### Checksums
See `sha256.txt`.
```

**Rules for good notes:** lead with what the user gets, state requirements first, link the
install steps, and always say plainly what is experimental.

---

## Part 7 — Publish the release

### Option A — Command line (`gh`)

```bash
# tag
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0

# release (attach files)
gh release create v0.1.0 \
  --title "NullArmy v0.1.0" \
  --notes-file RELEASE_NOTES.md \
  --prerelease \
  plugin/build/libs/NullArmy-0.1.0-dev.jar#NullArmy-0.1.0.jar \
  plugin/build/libs/sha256.txt
```

> `path#display-name` renames the uploaded file — the jar keeps its Gradle name on disk but shows
> up nicely for users.

Mark it a pre-release with `--prerelease` until you have run the smoke test on a real server.

### Option B — GitHub website

1. Go to **your repo → Releases → Draft a new release**
2. **Choose a tag** → type `v0.1.0` → *Create new tag*
3. **Release title:** `NullArmy v0.1.0`
4. Paste your notes
5. Tick **Set as a pre-release** if it's still experimental
6. **Drag the jar and `sha256.txt` into the Attach binaries box**
7. **Publish release**

### Option C — Let CI do it

If you added the workflow in [Part 4](#part-4--automate-the-build-recommended), pushing a tag is
enough:

```bash
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

The workflow builds, attaches the jar and checksums, and generates notes from your commits.

---

## Part 8 — After the release

| Step | Command / action |
| --- | --- |
| Verify the download works | Click the release asset, unzip it, confirm `paper-plugin.yml` is inside |
| Install it on a clean test server | Fresh Paper 1.21.11, no other plugins |
| Run the smoke test | [BUILD_TUTORIAL.md §Part 7](BUILD_TUTORIAL.md#step-7--install-the-jar-on-your-server) |
| Watch the issues | Fix fast, cut a PATCH release |
| Bump the version | `gradle.properties` → `0.1.1` |

### Cutting a patch release

```bash
# fix the bug, then:
# gradle.properties -> version=0.1.1
git add -A && git commit -m "Fix <bug> (v0.1.1)"
git push origin arena/01a10183-nullarmy
git tag -a v0.1.1 -m "v0.1.1"
git push origin v0.1.1
# CI attaches the jar automatically
```

---

## Release checklist

Copy this into every release issue:

- [ ] Project **builds successfully** (`./gradlew clean build`)
- [ ] Smoke test passed on a real Paper 1.21.11 server
- [ ] `gradle.properties` version bumped
- [ ] `plugin.yml` version in sync
- [ ] `README.md` accurate (features, install, requirements)
- [ ] `LICENSE` file present
- [ ] No API keys or secrets anywhere (`unzip -p ... \| grep -iE "sk-\|api[_-]?key"` → clean)
- [ ] Default `config.yml` has all endpoints **disabled**
- [ ] No `playerdata` pollution confirmed
- [ ] `sha256.txt` generated
- [ ] Tag pushed: `git push origin v0.X.Y`
- [ ] Release published with notes + attached jar
- [ ] Downloaded the release asset and verified it unzips
- [ ] Experimental status stated honestly in the notes

---

## Licence

The source headers say **"Copyright (c) RedGlitchX. All rights reserved."** — that is *not* a
licence and, on its own, it means nobody may legally use, copy, or distribute your plugin.

To make this genuinely public, add a `LICENSE` file. Quick guide:

| Licence | Effect | Good for |
| --- | --- | --- |
| **MIT** | Anyone can do almost anything, just keep the notice | Maximum adoption, simplest |
| **Apache-2.0** | MIT + explicit patent grant | Larger projects |
| **GPL-3.0** | Derivatives must also be open source | Keeping it free |
| **PolyForm Shield** | Free use, no competing commercial resale | Protecting a paid plugin |

Pick one at <https://choosealicense.com>, save it as `LICENSE`, and mention it in the README and
release notes. **This is your call as the owner** — I have not chosen one for you.

---

## Quick reference

```bash
# version
gradle.properties: version=0.1.0-dev

# build
./gradlew clean build
ls plugin/build/libs/

# checksum
sha256sum plugin/build/libs/*.jar > plugin/build/libs/sha256.txt

# secret scan
unzip -p plugin/build/libs/*.jar | grep -iE "sk-|api[_-]?key"

# tag + release
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
gh release create v0.1.0 --notes-file RELEASE_NOTES.md --prerelease \
  plugin/build/libs/NullArmy-0.1.0-dev.jar#NullArmy-0.1.0.jar \
  plugin/build/libs/sha256.txt
```

---

**Copyright (c) RedGlitchX. All rights reserved.**
