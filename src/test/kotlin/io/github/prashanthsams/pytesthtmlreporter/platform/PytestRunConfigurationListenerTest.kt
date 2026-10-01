package io.github.prashanthsams.pytesthtmlreporter.platform

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.diagnostic.logger
import com.intellij.testFramework.PlatformTestUtil
import io.github.prashanthsams.pytesthtmlreporter.core.TestData
import io.github.prashanthsams.pytesthtmlreporter.integration.PytestRunConfigurationListener
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/** The opt-in refresh after Python pytest run configurations, driven through the platform's execution events. */
class PytestRunConfigurationListenerTest : ReporterTestCase() {

    private lateinit var workingDirectory: Path

    override fun setUp() {
        super.setUp()
        workingDirectory = Files.createTempDirectory("run config ü")
        ReporterSettings.getInstance(project).state.workingDirectory = workingDirectory.toString()
    }

    override fun tearDown() {
        try {
            workingDirectory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun pytestEnvironment(): ExecutionEnvironment? {
        val type = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.firstOrNull { it.id == "tests" } ?: return null
        val factory = type.configurationFactories.firstOrNull { it.id == "py.test" } ?: return null
        val settings = RunManager.getInstance(project).createConfiguration("pytest", factory)
        return ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)?.build()
    }

    fun testShowsOnlyTheReportWrittenDuringTheRun() {
        val environment = pytestEnvironment() ?: return LOG.warn("Python test configurations unavailable; skipping")
        assertTrue(PytestRunConfigurationListener.isPytestConfiguration(environment))
        ReporterSettings.getInstance(project).state.refreshAfterRunConfigurations = true

        val stale = TestData.copyReport("0.3.0", workingDirectory.resolve("reports/older"))
        Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000))

        val listener = PytestRunConfigurationListener(project)
        val handler = NopProcessHandler()
        val executorId = DefaultRunExecutor.EXECUTOR_ID
        listener.processStarted(executorId, environment, handler)
        val fresh = TestData.copyReport("0.4.3", workingDirectory.resolve("report"))
        listener.processTerminated(executorId, environment, handler, 1)

        PlatformTestUtil.waitWithEventsDispatching(
            { "fresh report not selected: ${ReporterWorkspaceState.getInstance(project).state.selectedReport}" },
            { ReporterWorkspaceState.getInstance(project).state.selectedReport == fresh.toString() },
            30,
        )
    }

    fun testDoesNothingUnlessEnabled() {
        val environment = pytestEnvironment() ?: return LOG.warn("Python test configurations unavailable; skipping")
        val listener = PytestRunConfigurationListener(project)
        val handler = NopProcessHandler()
        listener.processStarted(DefaultRunExecutor.EXECUTOR_ID, environment, handler)
        TestData.copyReport("0.4.3", workingDirectory.resolve("report"))
        listener.processTerminated(DefaultRunExecutor.EXECUTOR_ID, environment, handler, 0)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        Thread.sleep(1_500)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals("", ReporterWorkspaceState.getInstance(project).state.selectedReport.orEmpty())
    }

    private companion object {
        private val LOG = logger<PytestRunConfigurationListenerTest>()
    }
}
