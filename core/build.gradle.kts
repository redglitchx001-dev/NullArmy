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
