# NullArmy release notes

NullArmy is **not ready for a stable v1.0.0 release**. The repository currently contains
unverified source, and there is no passing Paper-server smoke test. Do not publish or install a
stable release until the build, runtime checks, and release checklist in `RELEASING.md` pass.

The GitHub Actions build workflow can produce a JAR artifact for validation. A green build proves
compilation and the automated core test suite only; it does not prove that the plugin loads or works
on a real Paper server.
