/*
 * core - version-independent plugin logic.
 *
 * HARD RULE (see IMPLEMENTATION_PLAN.md ADR-004): this module depends on
 * NOTHING. No Paper, no Bukkit, no NMS, no third-party library.
 * That is what makes it testable without a Minecraft server.
 */
import org.gradle.api.tasks.testing.Test

plugins {
    java
}

dependencies {
    // Intentionally empty. Do not add anything here.
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
}

// CoreTestSuite is deliberately dependency-free and uses a main method instead
// of JUnit. Wire it into `check` so a normal clean build actually executes it.
val coreTestSuite by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the dependency-free core test suite."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("redglitchx.nullarmy.core.CoreTestSuite")
}

tasks.named("check") {
    dependsOn(coreTestSuite)
}

/*
 * The standard `test` task finds no JUnit tests: ADR-004 keeps core
 * dependency-free and the whole suite runs as `coreTestSuite` above. Gradle 9
 * fails a test task that has test sources but discovers no tests, so it is told
 * that discovering none here is expected rather than a misconfiguration.
 */
tasks.named<Test>("test") {
    failOnNoDiscoveredTests.set(false)
}
