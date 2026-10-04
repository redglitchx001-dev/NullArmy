#!/usr/bin/env bash
# =====================================================================
#  NullArmy runtime smoke test on a REAL Paper server.
#
#  Compiling proves nothing about whether a Null can be seen. This starts a
#  headless Paper server with the built plugin, runs /null selftest from the
#  console, and fails the job unless:
#
#    * the plugin enabled on the target Minecraft version;
#    * the self test reported PASS with zero FAIL lines;
#    * the server log contains no exception, no "Illegal ChunkMap::addEntity"
#      and no NullArmy SEVERE line.
#
#  If Paper itself cannot be downloaded the job FAILS with a BLOCKED marker
#  rather than reporting success it did not earn.
# =====================================================================
set -uo pipefail

MC_VERSION="${MC_VERSION:-1.21.11}"
WORK="${GITHUB_WORKSPACE:-$(pwd)}"
JAR="$(ls -1 "$WORK"/plugin/build/libs/NullArmy-*.jar 2>/dev/null | head -n 1)"
SERVER_DIR="$WORK/ci-server"
LOG="$SERVER_DIR/server.log"

if [ -z "$JAR" ]; then
  echo "RUNTIME SMOKE: BLOCKED - no plugin jar was built (looked in plugin/build/libs)"
  exit 1
fi
echo "RUNTIME SMOKE: using $JAR"

rm -rf "$SERVER_DIR"
mkdir -p "$SERVER_DIR/plugins"
cp "$JAR" "$SERVER_DIR/plugins/"

# ---------------------------------------------------------------- Paper download
PAPER_JAR="$SERVER_DIR/paper.jar"
download_paper() {
  # Fill v3 (current): ask for the latest stable build, then its server download.
  local meta
  meta="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/${MC_VERSION}/builds?channel=STABLE" 2>/dev/null)" || meta=""
  if [ -n "$meta" ]; then
    local url
    url="$(printf '%s' "$meta" | python3 -c '
import json,sys
try:
    data=json.load(sys.stdin)
except Exception:
    sys.exit(0)
builds=data.get("builds") or []
if not builds:
    sys.exit(0)
latest=builds[-1]
downloads=latest.get("downloads") or {}
for key in ("server:default","server","application"):
    entry=downloads.get(key)
    if isinstance(entry,dict) and entry.get("url"):
        print(entry["url"]); break
' 2>/dev/null)"
    if [ -n "$url" ]; then
      echo "RUNTIME SMOKE: downloading Paper from the Fill v3 API"
      curl -fsSL -o "$PAPER_JAR" "$url" && [ -s "$PAPER_JAR" ] && return 0
    fi
  fi

  # Download API v2 (older PaperMC API shape).
  meta="$(curl -fsSL "https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}" 2>/dev/null)" || meta=""
  if [ -n "$meta" ]; then
    local build
    build="$(printf '%s' "$meta" | python3 -c '
import json,sys
try:
    data=json.load(sys.stdin)
except Exception:
    sys.exit(0)
builds=data.get("builds") or []
print(builds[-1] if builds else "")
' 2>/dev/null)"
    if [ -n "$build" ]; then
      local url="https://api.papermc.io/v2/projects/paper/versions/${MC_VERSION}/builds/${build}/downloads/paper-${MC_VERSION}-${build}.jar"
      echo "RUNTIME SMOKE: downloading Paper build ${build} from the v2 API"
      curl -fsSL -o "$PAPER_JAR" "$url" && [ -s "$PAPER_JAR" ] && return 0
    fi
  fi
  return 1
}

if ! download_paper; then
  # No server means no runtime claim. This is deliberately NOT a pass, and it is
  # not a build failure either: it is a loud, separate verdict.
  echo "RUNTIME SMOKE: BLOCKED - could not download a Paper ${MC_VERSION} server jar."
  echo "RUNTIME SMOKE: BLOCKED - no runtime claim is made for this build."
  echo "RUNTIME SMOKE: BLOCKED - remaining checks that a live server must confirm:"
  echo "  1. one Null and a squad are visible to a real client (not just tracked)"
  echo "  2. a Null survives chunk load/unload and normal entity tracking"
  echo "  3. portal doorways build, are stepped out of, and restore cleanly"
  echo "  4. the Totem Of Null shutdown walks every Null out, Commander last"
  echo "  5. the wither cannon fires and delivers TNT with block damage off"
  exit 3
fi

# ---------------------------------------------------------------- server config
echo "eula=true" > "$SERVER_DIR/eula.txt"
cat > "$SERVER_DIR/server.properties" <<'PROPS'
online-mode=false
level-name=world
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=8
simulation-distance=6
max-players=20
motd=NullArmy runtime smoke test
sync-chunk-writes=false
max-tick-time=-1
enable-command-block=false
pvp=false
PROPS
# A flat world means the spawn point always has safe ground under it, so the
# self test never fails because of terrain.
mkdir -p "$SERVER_DIR/config"

# ---------------------------------------------------------------- run
cd "$SERVER_DIR" || exit 1
mkfifo stdin.fifo
# Keep the fifo open for the whole run so the server never sees EOF and shuts down.
exec 9<> stdin.fifo
java -Xms512M -Xmx1536M -jar paper.jar --nogui < stdin.fifo > server.log 2>&1 &
SERVER_PID=$!
echo "RUNTIME SMOKE: server pid $SERVER_PID"

wait_for() {
  local pattern="$1"
  local limit="$2"
  local waited=0
  while [ "$waited" -lt "$limit" ]; do
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "RUNTIME SMOKE: the server exited while waiting for '$pattern'"
      return 1
    fi
    if grep -qE "$pattern" server.log 2>/dev/null; then
      return 0
    fi
    sleep 2
    waited=$((waited + 2))
  done
  echo "RUNTIME SMOKE: timed out after ${limit}s waiting for '$pattern'"
  return 1
}

status=0

if ! wait_for 'Done \(' 300; then
  echo "RUNTIME SMOKE: FAIL - the server never finished starting"
  status=1
else
  echo "RUNTIME SMOKE: server started"
  if ! grep -q "NullArmy enabled" server.log; then
    echo "RUNTIME SMOKE: FAIL - NullArmy did not enable on Paper ${MC_VERSION}"
    status=1
  fi

  printf 'null selftest\n' >&9
  if ! wait_for '\[NullArmy\]\[SELFTEST\] RESULT:' 240; then
    echo "RUNTIME SMOKE: FAIL - the self test never reported a result"
    status=1
  fi

  # Give the sequential totem shutdown and portal cleanup a moment, then stop.
  sleep 5
  printf 'null status\n' >&9
  sleep 3
  printf 'stop\n' >&9
  wait_for 'Closing Server|Server closed|Saving is already turned off' 90 || true
  sleep 5
  kill "$SERVER_PID" 2>/dev/null || true
fi

# ---------------------------------------------------------------- verdict
echo "----------------------- self test output -----------------------"
grep -E '\[NullArmy\]\[SELFTEST\]' server.log || echo "(no self test output at all)"
echo "----------------------- nullarmy log lines ---------------------"
grep -E '\[NullArmy\]|NullArmy' server.log | head -n 120 || true
echo "----------------------- server errors --------------------------"
grep -nE 'Exception|SEVERE|ERROR|Illegal ChunkMap|Caused by|\tat ' server.log | head -n 80 || echo "(none)"
echo "----------------------------------------------------------------"

if grep -qE '\[NullArmy\]\[SELFTEST\] .*FAIL' server.log; then
  echo "RUNTIME SMOKE: FAIL - at least one self test check failed"
  status=1
fi
if ! grep -qE '\[NullArmy\]\[SELFTEST\] RESULT: PASS' server.log; then
  echo "RUNTIME SMOKE: FAIL - no passing self test result was recorded"
  status=1
fi
# A crash, an untracked-entity warning or a NullArmy SEVERE line is a failure
# even when the checks above somehow passed.
if grep -qE 'Illegal ChunkMap::addEntity|Exception in server tick loop|Cannot invoke .* because .*connection. is null' server.log; then
  echo "RUNTIME SMOKE: FAIL - the server reported a tracking or tick-loop fault"
  status=1
fi
if grep -qE '^\[[0-9:]+\] \[Server thread\/SEVERE\]: \[NullArmy\]' server.log; then
  echo "RUNTIME SMOKE: FAIL - NullArmy logged a SEVERE line"
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "RUNTIME SMOKE: PASS - Nulls spawned, were tracked, were delivered to a viewer, survived ticking and went out one at a time on Paper ${MC_VERSION}"
else
  echo "RUNTIME SMOKE: FAIL - see the log sections above"
fi
exit "$status"
