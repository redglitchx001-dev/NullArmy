/*
 * nms/api - the VersionAdapter SPI. Interfaces and version-neutral value
 * types only. No server dependency, so core and plugin can compile against it.
 */
plugins {
    `java-library`
}

dependencies {
    /*
     * The SPI signatures expose core value types (Vec3d, BlockView,
     * ItemLedger), so :core has to be on this module's compile classpath.
     * `api` rather than `implementation` because those types are part of this
     * module's exported contract: anything that compiles against
     * VersionAdapter/NullBody needs them too.
     */
    api(project(":core"))
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
