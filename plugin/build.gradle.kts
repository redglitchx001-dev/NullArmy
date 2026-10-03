/*
 * plugin - Bukkit bootstrap. Commands, configuration, persistence, lifecycle.
 *
 * Depends on the Paper API (provided by the server at runtime - NOT a plugin
 * dependency, so the "zero runtime dependencies" rule is preserved).
 */
plugins {
    java
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation(project(":core"))
    implementation(project(":nms:api"))

    // Version adapters are loaded reflectively at runtime by AdapterLoader.
    implementation(project(":nms:v1_21_11"))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version.toString())
    }
}
