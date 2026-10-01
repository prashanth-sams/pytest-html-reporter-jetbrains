// Test-only IDE plugin that drives pytest-html-reporter inside a sandbox IDE (see scripts/smoke-test.sh).
// It is never part of the distributed plugin ZIP.
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

version = "1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        apiVersion = KotlinVersion.KOTLIN_2_1
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        pycharmCommunity(rootProject.providers.gradleProperty("platformVersion")) { useInstaller = false }
        localPlugin(project(":"))
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

// Test-only plugin: never verified or published. Without this, `verifyPlugin` would also run here and fetch the
// "recommended" IDE set for a plugin nobody installs.
tasks {
    verifyPlugin { enabled = false }
    publishPlugin { enabled = false }
    signPlugin { enabled = false }
}
