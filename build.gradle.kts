/*
 * NullArmy root build. Copyright (c) RedGlitchX. All rights reserved.
 *
 * NOTE ON ZIP INCLUSION (IMPLEMENTATION_PLAN.md V-05):
 * Only :nms:v1_21_11 is wired in by default, per owner decision D-1(c) - one
 * adapter first. To ship the full 1.21.x matrix, add the remaining
 * :nms:v1_21_* modules to settings.gradle.kts and to the list below.
 */
plugins {
    java
}

allprojects {
    group = findProperty("group") as String
    version = findProperty("version") as String
}

subprojects {
    apply(plugin = "java")

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:all,-serial,-processing")
    }
}

/*
 * The adapter modules that get merged into the final plugin jar.
 * Each produces a version-bound artifact; the plugin selects one at runtime.
 */
val adapterProjects = listOf(":nms:v1_21_11")

tasks.register("printAdapterSet") {
    doLast { println("adapters=" + adapterProjects.joinToString(",")) }
}

/*
 * Runtime smoke test on a REAL Paper server.
 *
 * Compiling is not verification: the failure this project had was a Null that
 * was created, tracked, reported as summoned - and invisible, because the client
 * drops an add-entity packet for a player UUID it has no player-info entry for.
 * Only a running server can prove that path, so `ci/runtime-smoke.sh` starts a
 * headless Paper server with the built jar, runs `/null selftest` from the
 * console and checks the log.
 *
 * Verdicts, all of them printed with the marker "RUNTIME SMOKE:":
 *   PASS    - every check passed and the server log is clean
 *   FAIL    - a check failed; the build fails
 *   BLOCKED - no Paper jar could be downloaded, so NO runtime claim is made.
 *             The build does not fail on a missing download, but it does not
 *             pretend to have verified anything either: the script prints the
 *             exact checks that still need a live server.
 */
val runtimeSmoke by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs /null selftest against a headless Paper server."
    dependsOn(":plugin:jar")
    workingDir = rootDir
    commandLine("bash", "ci/runtime-smoke.sh")
    environment("MC_VERSION", "1.21.11")
    // The script distinguishes BLOCKED (3) from FAIL (1) itself.
    isIgnoreExitValue = true
    doLast {
        val code = executionResult.get().exitValue
        when (code) {
            0 -> println("RUNTIME SMOKE: PASS - verified on a live Paper server.")
            3 -> {
                logger.warn("RUNTIME SMOKE: BLOCKED - no Paper server could be started," +
                        " so nothing was verified at runtime. See the checklist above.")
            }
            else -> throw GradleException(
                    "RUNTIME SMOKE: FAIL - the live Paper server test failed (exit $code).")
        }
    }
}

tasks.named("build") {
    dependsOn(runtimeSmoke)
}
