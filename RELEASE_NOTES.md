# NullArmy release notes

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
