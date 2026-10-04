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

    /*
     * State the resources explicitly.
     *
     * plugin.yml and config.yml are what make the plugin loadable and able to
     * create plugins/NullArmy/ on a cold start. Relying on the default
     * source-set wiring is how a jar can end up with plugin.yml but no
     * config.yml - which loads, then fails inside onEnable and leaves no data
     * folder at all. Naming the resource directory here removes that whole
     * class of surprise. (Duplicates are excluded above, so this cannot double
     * an entry.)
     */
    from(sourceSets.getByName("main").resources)

    manifest {
        attributes(mapOf("paperweight-mappings-namespace" to "mojang"))
    }

    /*
     * Packaging guard.
     *
     * A jar without config.yml still loads, but JavaPlugin#saveResource then
     * throws IllegalArgumentException inside onEnable, the data folder is never
     * created, and every /null command is left without the plugin behind it.
     * That failure is invisible to a build that only checks for plugin.yml, so
     * the build now fails loudly here instead of on the server.
     *
     * This runs as part of `./gradlew build`, so CI turns a packaging
     * regression into a red build with the real reason in the log.
     */
    val requiredResources = listOf("plugin.yml", "config.yml")
    val jarTask = this
    doLast {
        val jarFile = jarTask.archiveFile.get().asFile
        val present = java.util.zip.ZipFile(jarFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        val missing = requiredResources.filterNot { present.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "NullArmy jar is missing required resource(s): $missing. " +
                    "Check plugin/src/main/resources and the processResources/jar configuration. " +
                    "Entries actually packaged: " + present.sorted().joinToString(", ")
            )
        }
        println("NullArmy jar check OK: " + requiredResources.joinToString(", ") +
            " packaged in " + jarFile.name + " (" + present.size + " entries)")
    }
}
