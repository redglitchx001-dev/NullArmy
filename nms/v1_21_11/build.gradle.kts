/*
 * nms/v1_21_11 - the Paper 1.21.11 adapter.
 *
 * paperweight-userdev is the supported way to access Paper NMS. This adapter
 * is compiled in Mojang mappings and is only intended to run on Paper.
 */
plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation(project(":nms:api"))
    implementation(project(":core"))
}

// The final plugin jar is Mojang-mapped and targets Paper, not Spigot.
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
