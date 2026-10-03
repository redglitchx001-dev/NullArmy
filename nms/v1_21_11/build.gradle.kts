/*
 * nms/v1_21_11 - the Paper 1.21.11 adapter.
 *
 * paperweight-userdev is the ONLY supported way to use NMS on Paper
 * (see IMPLEMENTATION_PLAN.md section 3.1), which is why Gradle is mandatory.
 *
 * VERIFY BEFORE RELYING ON THIS (IMPLEMENTATION_PLAN.md V-05, V-02):
 *   - paperweight plugin version and dev-bundle coordinates must be pinned to
 *     a real published pair for 1.21.11.
 *   - Per A-14 we ship Mojang-mapped, NOT reobfuscated, because Paper 1.21.11
 *     build 17060+ removed runtime plugin remapping.
 */
plugins {
    java
    id("io.papermc.paperweight.userdev")
}

dependencies {
    paperweight.paperDevBundle("io.papermc.paper:dev-bundle:1.21.11-R0.1-SNAPSHOT")
    implementation(project(":nms:api"))
    implementation(project(":core"))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
