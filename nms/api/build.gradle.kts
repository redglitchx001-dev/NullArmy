/*
 * nms/api - the VersionAdapter SPI. Interfaces and version-neutral value
 * types only. No server dependency, so core and plugin can compile against it.
 */
plugins {
    java
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
