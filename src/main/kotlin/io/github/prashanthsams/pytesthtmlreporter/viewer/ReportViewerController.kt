package io.github.prashanthsams.pytesthtmlreporter.viewer

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import kotlinx.coroutines.CoroutineScope
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The project's entry point to the viewer: actions, runs and the run-configuration listener ask it to show a report,
 * whether or not the tool window has been opened yet. The selection is remembered per project.
 */
@Service(Service.Level.PROJECT)
class ReportViewerController(private val project: Project, internal val scope: CoroutineScope) {

    private data class PendingOpen(val report: Path, val forceReload: Boolean)

    private var panel: ReportViewerPanel? = null
    private var pending: PendingOpen? = null

    internal fun attach(panel: ReportViewerPanel): Pair<Path, Boolean>? {
        this.panel = panel
        val request = pending
        pending = null
        return request?.let { it.report to it.forceReload }
    }

    /** The tool window panel, once the tool window has been opened. */
    val viewerPanel: ReportViewerPanel? get() = panel

    internal fun detach(panel: ReportViewerPanel) {
        if (this.panel === panel) this.panel = null
    }

    /** The report on screen, or the remembered selection when the tool window has not been opened. */
    val currentReport: Path?
        get() = panel?.currentReport ?: rememberedReport()

    fun rememberedReport(): Path? = ReporterWorkspaceState.getInstance(project).state.selectedReport
        ?.takeIf(String::isNotBlank)
        ?.let { try { Path.of(it) } catch (_: InvalidPathException) { null } }

    /** Shows [report] (call on the EDT). [forceReload] re-reads it even when it is already on screen. */
    fun open(report: Path, activate: Boolean = true, forceReload: Boolean = false) {
        ReporterWorkspaceState.getInstance(project).state.selectedReport = report.toString()
        val target = panel
        if (target != null) target.show(report, forceReload) else pending = PendingOpen(report, forceReload)
        if (activate) toolWindow()?.activate(null, false)
    }

    fun refresh() {
        panel?.refresh()
    }

    fun toolWindow(): ToolWindow? = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)

    companion object {
        const val TOOL_WINDOW_ID = "Pytest HTML Reporter"

        fun getInstance(project: Project): ReportViewerController = project.service()
    }
}
