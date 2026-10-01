plugins {
    // Lets Gradle provision the JDK 21 toolchain on machines (and CI runners) that do not have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pytest-html-reporter-jetbrains"

// Test-only plugin that drives the real plugin in a sandbox IDE (./gradlew runSmokeTest); never distributed.
include(":smoke-test")
