package io.github.prashanthsams.pytesthtmlreporter.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner

/**
 * Project settings that are safe to share through version control (`.idea/pytestHtmlReporter.xml`).
 * Report-generation options themselves stay in pytest configuration (pytest.ini, pyproject.toml, profiles).
 */
@Service(Service.Level.PROJECT)
@State(
    name = "PytestHtmlReporterSettings",
    storages = [Storage("pytestHtmlReporter.xml")],
    category = SettingsCategory.TOOLS,
)
class ReporterSettings : SimplePersistentStateComponent<ReporterSettings.SettingsState>(SettingsState()) {

    class SettingsState : BaseState() {
        /** `--html-report` value: a folder or an `.html` file, relative to the working directory. Blank: let pytest decide. */
        var outputPath by string(DEFAULT_OUTPUT_PATH)

        /** Keep a report path that pytest configuration (addopts, profile, environment, html_report) already names. */
        var respectPytestConfiguration by property(true)

        /** Test targets (paths or node IDs), space-separated with shell-style quoting. Blank: pytest's own testpaths. */
        var targets by string("")

        /** Extra pytest arguments, e.g. `-m "not slow" -k login`. */
        var pytestArguments by string("")

        /** Blank: the project root. */
        var workingDirectory by string("")

        var environmentVariables by map<String, String>()
        var passParentEnvironment by property(true)

        var openReportAfterRun by property(true)
        var autoRefresh by property(true)
        var followIdeTheme by property(true)
        var blockRemoteContent by property(true)

        /** Opt-in: refresh the viewer after a PyCharm pytest run configuration finishes. */
        var refreshAfterRunConfigurations by property(false)

        var discoveryDepth by property(DEFAULT_DISCOVERY_DEPTH)
        var excludedDirectories by string(ReportScanner.DEFAULT_EXCLUDED_NAMES.joinToString(", "))

        /** Extra folders to search, relative to the project root or absolute; searched even when excluded by name. */
        var additionalLocations by string("")
    }

    companion object {
        const val DEFAULT_OUTPUT_PATH = "report"
        const val DEFAULT_DISCOVERY_DEPTH = 6

        fun getInstance(project: Project): ReporterSettings = project.service()
    }
}

/** Machine-specific state kept out of version control (`workspace.xml`). */
@Service(Service.Level.PROJECT)
@State(name = "PytestHtmlReporterWorkspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ReporterWorkspaceState : SimplePersistentStateComponent<ReporterWorkspaceState.WorkspaceState>(WorkspaceState()) {

    class WorkspaceState : BaseState() {
        /** Absolute path of a Python executable. Blank: the project's Python interpreter. */
        var interpreterPath by string("")

        /** The report last shown in the viewer, remembered per project. */
        var selectedReport by string("")
    }

    companion object {
        fun getInstance(project: Project): ReporterWorkspaceState = project.service()
    }
}

/** Published on the project message bus after the settings page is applied. */
fun interface ReporterSettingsListener {
    fun settingsChanged()

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ReporterSettingsListener> = Topic(ReporterSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
