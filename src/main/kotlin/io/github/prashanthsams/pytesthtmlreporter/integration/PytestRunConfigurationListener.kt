package io.github.prashanthsams.pytesthtmlreporter.integration

import com.intellij.execution.ExecutionListener
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import io.github.prashanthsams.pytesthtmlreporter.core.ReportLocator
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.viewer.ReportViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt-in integration with existing pytest run configurations (Settings: "Refresh the viewer when pytest run
 * configurations finish"). Built only on the platform's execution events, so it does not depend on Python plugin
 * internals: when a Python test configuration using the pytest runner finishes, the newest report written during the
 * run is found and shown. The run configuration itself is not modified - its reporter options stay in pytest
 * configuration (`html_report`, `report_open = none`, profiles).
 */
class PytestRunConfigurationListener(private val project: Project) : ExecutionListener {

    private val startedAt = ConcurrentHashMap<ProcessHandler, Long>()

    override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (isPytestConfiguration(env)) startedAt[handler] = System.currentTimeMillis()
    }

    override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
        val started = startedAt.remove(handler) ?: return
        val settings = ReporterSettings.getInstance(project).state
        if (!settings.refreshAfterRunConfigurations || project.isDisposed) return
        val controller = ReportViewerController.getInstance(project)
        controller.scope.launch(Dispatchers.IO) {
            val discovery = ReportDiscoveryService.getInstance(project)
            val roots = listOfNotNull(project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }) + discovery.knownLocations()
            val scanner = ReportScanner(ReportScanner.Limits(maxDepth = settings.discoveryDepth.coerceIn(1, 12)))
            val fresh = scanner.scan(roots.distinct(), ReportScanner.parseExcludedNames(settings.excludedDirectories.orEmpty()))
                .reports.firstOrNull { ReportLocator.isFresh(it, started) } ?: return@launch
            if (!ReportLocator.awaitComplete(fresh.file)) return@launch
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(fresh.file)
            discovery.register(fresh)
            withContext(Dispatchers.EDT) {
                controller.open(fresh.file, activate = settings.openReportAfterRun, forceReload = true)
            }
        }
    }

    companion object {
        /** Python's test configuration type, and the factory ID of its pytest runner (PythonCore). */
        private const val PYTHON_TESTS_TYPE_ID = "tests"
        private val PYTEST_FACTORY_IDS = setOf("py.test", "pytest")

        fun isPytestConfiguration(env: ExecutionEnvironment): Boolean {
            val configuration = env.runProfile as? RunConfiguration ?: return false
            return configuration.type.id == PYTHON_TESTS_TYPE_ID && configuration.factory?.id in PYTEST_FACTORY_IDS
        }
    }
}
