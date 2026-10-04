/*
 * core - version-independent plugin logic.
 *
 * HARD RULE (see IMPLEMENTATION_PLAN.md ADR-004): this module depends on
 * NOTHING. No Paper, no Bukkit, no NMS, no third-party library.
 * That is what makes it testable without a Minecraft server.
 */
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
