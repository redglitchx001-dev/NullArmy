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
