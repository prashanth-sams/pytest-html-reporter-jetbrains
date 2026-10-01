package io.github.prashanthsams.pytesthtmlreporter.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.text.DateFormatUtil
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.ReportCandidate
import io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles
import io.github.prashanthsams.pytesthtmlreporter.core.ReportPaths
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.run.PytestRunService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterConfigurable
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettingsListener
import io.github.prashanthsams.pytesthtmlreporter.viewer.ReportViewerController
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

/** Base for actions that need a project and only cheap checks in `update`. */
abstract class ReporterAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    protected fun currentReport(e: AnActionEvent): Path? =
        e.project?.let { ReportViewerController.getInstance(it).currentReport }?.takeIf(Files::isRegularFile)
}

class RunPytestWithHtmlReportAction : ReporterAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        if (e.place == ActionPlaces.PROJECT_VIEW_POPUP) {
            e.presentation.isEnabledAndVisible = project != null && selectedTargets(e, project) != null
            return
        }
        e.presentation.isEnabled = project != null && !PytestRunService.getInstance(project).isRunning
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targets = if (e.place == ActionPlaces.PROJECT_VIEW_POPUP) selectedTargets(e, project) else null
        PytestRunService.getInstance(project).run(targets)
    }

    /** Selected Python files and folders as pytest targets, relative to the working directory when inside it. */
    private fun selectedTargets(e: AnActionEvent, project: Project): List<String>? {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.takeIf { it.isNotEmpty() } ?: return null
        if (!files.all { it.isInLocalFileSystem && (it.isDirectory || it.extension == "py") }) return null
        val base = project.basePath ?: return null
        val configured = ReporterSettings.getInstance(project).state.workingDirectory.orEmpty()
        val workingDirectory = if (configured.isBlank()) base else FileUtil.toSystemIndependentName(Path.of(base).resolve(configured).normalize().toString())
        return files.map { file ->
            FileUtil.getRelativePath(workingDirectory, file.path, '/')?.takeUnless { it.startsWith("..") }?.ifEmpty { "." } ?: file.path
        }
    }
}

class OpenReportAction : ReporterAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withExtensionFilter("html")
            .withTitle(message("action.openReport.chooser.title"))
            .withDescription(message("action.openReport.chooser.description"))
        val start = ReportViewerController.getInstance(project).currentReport?.parent
            ?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }
            ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        FileChooser.chooseFile(descriptor, project, start) { chosen -> open(project, chosen) }
    }

    private fun open(project: Project, chosen: VirtualFile) {
        val path = chosen.toNioPath()
        if (!ReportFiles.isReport(path) && Messages.showYesNoDialog(
                project, message("action.openReport.notReport", chosen.name), message("action.openReport.text"), Messages.getQuestionIcon(),
            ) != Messages.YES
        ) {
            return
        }
        ReportFiles.candidate(path)?.let(ReportDiscoveryService.getInstance(project)::register)
        ReportViewerController.getInstance(project).open(path, activate = true)
    }
}

class RefreshReportAction : ReporterAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReportViewerController.getInstance(project).refresh()
    }
}

class ToggleAutoRefreshAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        e.project?.let { ReporterSettings.getInstance(it).state.autoRefresh } ?: false

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val project = e.project ?: return
        ReporterSettings.getInstance(project).state.autoRefresh = state
        project.messageBus.syncPublisher(ReporterSettingsListener.TOPIC).settingsChanged()
        if (state) ReportViewerController.getInstance(project).refresh()
    }
}

class OpenInExternalBrowserAction : ReporterAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = currentReport(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        currentReport(e)?.let { BrowserUtil.browse(it) }
    }
}

class RevealReportFileAction : ReporterAction() {
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = currentReport(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        currentReport(e)?.let(RevealFileAction::openFile)
    }
}

class RescanReportsAction : ReporterAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReportDiscoveryService.getInstance(project).rescan()
    }
}

class OpenReporterSettingsAction : ReporterAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, ReporterConfigurable::class.java)
    }
}

/** "Open in Pytest HTML Reporter" on HTML files the reporter wrote (Project view and editor tab menus). */
class OpenInReporterViewerAction : ReporterAction() {
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && isReportFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val path = file.toNioPath()
        ReportFiles.candidate(path)?.let(ReportDiscoveryService.getInstance(project)::register)
        ReportViewerController.getInstance(project).open(path, activate = true, forceReload = true)
    }

    companion object {
        /** Cheap, VFS-only test: the reporter's default name, or an HTML file with `output.json` beside it. */
        fun isReportFile(file: VirtualFile): Boolean =
            !file.isDirectory && file.isInLocalFileSystem && file.extension.equals("html", ignoreCase = true) &&
                (file.name == ReportPaths.DEFAULT_FILE_NAME || file.parent?.findChild(ReportPaths.OUTPUT_JSON) != null)
    }
}

/** Toolbar combo listing the reports found in the project, newest first, with relative path and modification time. */
class SelectReportAction : ComboBoxAction(), DumbAware {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val current = ReportViewerController.getInstance(project).currentReport
        e.presentation.setText(current?.let { label(project, it) } ?: message("selector.none"), false)
        e.presentation.description = current?.toString() ?: message("selector.description")
        e.presentation.icon = AllIcons.FileTypes.Html
        e.presentation.isEnabled = true
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val project = dataContext.getData(CommonDataKeys.PROJECT) ?: return group
        val reports = ReportDiscoveryService.getInstance(project).state.reports
        if (reports.isEmpty()) {
            group.add(NoReports())
        } else {
            reports.forEach { group.add(ChooseReport(project, it)) }
        }
        group.addSeparator()
        ActionManager.getInstance().getAction("PytestHtmlReporter.OpenReport")?.let(group::add)
        ActionManager.getInstance().getAction("PytestHtmlReporter.Rescan")?.let(group::add)
        return group
    }

    private class ChooseReport(private val project: Project, private val candidate: ReportCandidate) : DumbAwareAction() {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            val time = DateFormatUtil.formatPrettyDateTime(candidate.lastModifiedMillis)
            e.presentation.setText(message("selector.item", label(project, candidate.file), time), false)
            e.presentation.description = candidate.file.toString()
        }

        override fun actionPerformed(e: AnActionEvent) {
            ReportViewerController.getInstance(project).open(candidate.file, activate = true)
        }
    }

    private class NoReports : DumbAwareAction(message("selector.empty")) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    companion object {
        /** The report's path relative to the project, or its absolute path when it lives elsewhere. */
        fun label(project: Project, report: Path): String {
            val base = project.basePath ?: return report.toString()
            return FileUtil.getRelativePath(base, FileUtil.toSystemIndependentName(report.toString()), '/')
                ?.takeUnless { it.startsWith("..") }
                ?: report.toString()
        }
    }
}
