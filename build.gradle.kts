import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The oldest supported IDE (2025.1) bundles Kotlin stdlib 2.1.10, so newer stdlib API must not be used.
        // Kotlin 2.4 reports API version 2.1 as deprecated; that is expected for as long as 2025.1 is supported.
        apiVersion = KotlinVersion.KOTLIN_2_1
        freeCompilerArgs.add("-Xsuppress-version-warnings")
        // Rely on the JVM default methods of platform interfaces instead of generating DefaultImpls bridges, which the
        // Plugin Verifier would report as overrides of deprecated or experimental methods (e.g. ToolWindowFactory.manage).
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
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    intellijPlatform {
        // Compile against the oldest supported SDK. PythonCore is only needed by the optional Python integration
        // (META-INF/pytest-html-reporter-python.xml); report viewing does not depend on it.
        // Multi-OS archive from the IntelliJ Maven repository: the same artifact on macOS, Linux and Windows CI.
        pycharmCommunity(providers.gradleProperty("platformVersion")) { useInstaller = false }
        bundledPlugin("PythonCore")
        // The archive carries no runtime; tests and runIde use the JetBrains Runtime (with JCEF) matching the SDK.
        jetbrainsRuntime()

        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
        zipSigner()
    }
}

intellijPlatform {
    // Names the distribution ZIP and the folder the IDE installs the plugin into.
    projectName = providers.gradleProperty("pluginName")
    // The settings page is reachable by name; skipping the searchable-options index keeps builds headless and fast.
    buildSearchableOptions = false
    // Kotlin-only sources: no Java forms or @NotNull instrumentation needed.
    instrumentCode = false

    pluginConfiguration {
        id = providers.gradleProperty("pluginId")
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Single source of truth: the Marketplace description is the marked section of README.md.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }

        vendor {
            name = "Prashanth Sams"
            url = "https://github.com/prashanth-sams"
        }
    }

    // Credentials come only from environment variables (GitHub Actions secrets); nothing is stored in the repository.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 1.2.0 goes to the default (stable) channel; 1.2.0-beta.1 goes to the "beta" channel.
        channels = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            listOf(pluginVersion.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            // Multi-OS archives by default (the same artifact on every CI runner); -PverifyUseInstaller=true reuses
            // an installer already downloaded for a sandbox run.
            val installer = providers.gradleProperty("verifyUseInstaller").map(String::toBoolean).getOrElse(false)
            providers.gradleProperty("verifyIdes").get()
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .forEach { entry ->
                    val (type, ideVersion) = entry.split(':', limit = 2)
                    create(IntelliJPlatformType.valueOf(type), ideVersion) { useInstaller = installer }
                }
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    processResources {
        // The licence and the upstream attribution travel with the plugin JAR.
        from(files("LICENSE", "NOTICE.md")) { into("META-INF") }
    }

    test {
        // End-to-end run tests use real interpreters when PYTEST_HTML_REPORTER_PYTHON (with the reporter) and
        // PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER name them; changing either must re-run the tests rather than
        // reuse a cached result.
        inputs.property("testPython", providers.environmentVariable("PYTEST_HTML_REPORTER_PYTHON").orElse(""))
        inputs.property(
            "testPythonWithoutReporter",
            providers.environmentVariable("PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER").orElse(""),
        )
        // Platform tests run headless; JCEF rendering tests opt in to off-screen rendering explicitly.
        systemProperty("ide.browser.jcef.headless.enabled", "true")
        systemProperty("java.awt.headless", "true")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

// Development-IDE smoke test: scripts/smoke-test.sh prepares a sample project and runs one of these tasks.
// The smoke-test plugin drives this plugin inside the sandbox IDE and writes results.json and screenshots.
val smokeProject = providers.gradleProperty("smokeProject")
val smokeOutput = providers.gradleProperty("smokeOutput")
val smokePython = providers.gradleProperty("smokePython")
val smokeMode = providers.gradleProperty("smokeMode").orElse("full")

fun RunIdeTask.configureSmokeTest() {
    // Local copies, so the providers below do not capture the build script (configuration cache).
    val project = smokeProject
    val output = smokeOutput
    val python = smokePython
    val mode = smokeMode
    argumentProviders.add(CommandLineArgumentProvider { listOfNotNull(project.orNull) })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(
            output.orNull?.let { "-Dphr.smoke.output=$it" },
            python.orNull?.let { "-Dphr.smoke.python=$it" },
            "-Dphr.smoke.mode=${mode.get()}",
            // First-run prompts that would otherwise wait for a click.
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Didea.initially.ask.config=never",
            // The fallback scenario starts the IDE without its embedded browser.
            if (mode.get() == "fallback") "-Dide.browser.jcef.enabled=false" else null,
        )
    })
}

/** Trusts only the folder holding the smoke project, so the IDE's project-trust check stays on. */
fun PrepareSandboxTask.trustSmokeProjectFolder() {
    val configDirectory = sandboxConfigDirectory
    val trusted = smokeProject.map { File(it).parent }
    doLast {
        val folder = trusted.orNull ?: return@doLast
        val options = configDirectory.get().asFile.resolve("options").apply { mkdirs() }
        options.resolve("trusted-paths.xml").writeText(
            """
            <application>
              <component name="Trusted.Paths.Settings">
                <option name="TRUSTED_PATHS">
                  <list>
                    <option value="$folder" />
                  </list>
                </option>
              </component>
            </application>
            """.trimIndent(),
        )
    }
}

val smokeTestPlugin = layout.projectDirectory.file("smoke-test/build/distributions/smoke-test-1.0.zip").asFile

intellijPlatformTesting {
    runIde {
        register("runSmokeTest") {
            plugins { localPlugin(smokeTestPlugin) }
            task { configureSmokeTest() }
            prepareSandboxTask {
                dependsOn(":smoke-test:buildPlugin")
                trustSmokeProjectFolder()
            }
        }
        register("runSmokeTest262") {
            type = IntelliJPlatformType.PyCharm
            version = "2026.2.3"
            // The installer, not the multi-OS archive: since 2026.2 the JCEF plugin loads Chromium from
            // plugins/jcef-plugin/jcef, which only the OS-specific installers contain.
            useInstaller = true
            plugins { localPlugin(smokeTestPlugin) }
            task {
                configureSmokeTest()
                // Chromium's sandbox needs the signed app bundle of an installed IDE; development runs only.
                jvmArgs("-Dide.browser.jcef.sandbox.enable=false")
            }
            prepareSandboxTask {
                dependsOn(":smoke-test:buildPlugin")
                trustSmokeProjectFolder()
            }
        }
    }
}
