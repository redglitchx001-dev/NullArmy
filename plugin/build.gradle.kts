/*
 * plugin - Paper bootstrap. Commands, configuration, persistence, lifecycle.
 *
 * Paper is provided by the server. The project modules are compiled into the
 * final plugin jar; Paper API and NMS/server classes are never bundled.
 */
import org.gradle.api.file.DuplicatesStrategy

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
    inputs.property("version", project.version.toString())
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version.toString())
    }
}

// The plugin loader does not resolve Gradle project dependencies from its
// plugins/ directory. Merge only our own module classes; never shade Paper/NMS.
val bundledModules = listOf(":core", ":nms:api", ":nms:v1_21_11")

tasks.jar {
    archiveBaseName.set("NullArmy")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(bundledModules.map { "$it:classes" })
    from(bundledModules.map { modulePath ->
        project(modulePath).layout.buildDirectory.dir("classes/java/main")
    })
    manifest {
        attributes(mapOf("paperweight-mappings-namespace" to "mojang"))
    }
}
