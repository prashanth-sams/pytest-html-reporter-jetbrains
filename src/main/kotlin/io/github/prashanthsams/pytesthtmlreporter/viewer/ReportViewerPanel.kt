package io.github.prashanthsams.pytesthtmlreporter.viewer

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLoadingPanel
import com.intellij.ui.components.JBPanelWithEmptyText
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles
import io.github.prashanthsams.pytesthtmlreporter.core.ReportLocator
import io.github.prashanthsams.pytesthtmlreporter.discovery.DiscoveryState
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryListener
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.run.PytestRunService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettingsListener
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import io.github.prashanthsams.pytesthtmlreporter.ui.ProjectTrust
import kotlinx.coroutines.Dispatchers
import org.jetbrains.annotations.ApiStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.CardLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JPanel

/**
 * The Pytest HTML Reporter tool window: a toolbar, and either the embedded report or a status page (looking for
 * reports, none found, file missing, project untrusted, embedded browser unavailable, or a load error).
 */
class ReportViewerPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private sealed interface Status {
        data object Scanning : Status
        data object Empty : Status
        data class Missing(val report: Path) : Status
        data class Untrusted(val report: Path) : Status
        data class Unsupported(val report: Path) : Status
        data class Failed(val report: Path, val error: String) : Status
    }

    private val controller = ReportViewerController.getInstance(project)
    private val discovery = ReportDiscoveryService.getInstance(project)
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private val statusPanel = JBPanelWithEmptyText()
    private val loadingPanel = JBLoadingPanel(BorderLayout(), this, LOADING_DELAY_MILLIS)

    private var browser: JcefReportBrowser? = null
    @Volatile
    private var disposed = false
    private var watchRequest: LocalFileSystem.WatchRequest? = null

    /** Size and timestamp of the report version last loaded into the browser. */
    @Volatile
    private var loadedStamp: Pair<Long, Long>? = null

    @Volatile
    private var onScreen = false
    @Volatile
    private var watchedFile: VirtualFile? = null
    private var reloadJob: Job? = null
    private var loadWatchdog: Job? = null

    /** Reports from this session the user chose to open although the project is not trusted. */
    private val untrustedAllowed = HashSet<Path>()

    var currentReport: Path? = null
        private set

    /** Diagnostics (used by the smoke test): the status page on screen, or null while the report itself is shown. */
    @get:ApiStatus.Internal
    @Volatile
    var displayedStatus: String? = "Scanning"
        private set

    /** Diagnostics (used by the smoke test): how many times the embedded browser finished loading. */
    @get:ApiStatus.Internal
    @Volatile
    var completedLoads: Int = 0
        private set

    init {
        val group = ActionManager.getInstance().getAction(TOOLBAR_GROUP_ID) as ActionGroup
        val actionToolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, group, true)
        actionToolbar.targetComponent = this
        toolbar = actionToolbar.component

        cardPanel.add(loadingPanel, BROWSER_CARD)
        cardPanel.add(statusPanel, STATUS_CARD)
        setContent(cardPanel)

        val connection = project.messageBus.connect(this)
        connection.subscribe(ReportDiscoveryListener.TOPIC, ReportDiscoveryListener { state -> onEdt { discoveryChanged(state) } })
        connection.subscribe(ReporterSettingsListener.TOPIC, ReporterSettingsListener { onEdt { browser?.settingsChanged() } })
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = fileEvents(events)
        })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { browser?.applyTheme() })

        addHierarchyListener { event ->
            if (event.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) onScreen = isShowing
        }
        startChangePolling()

        val requested = controller.attach(this)
        val initial = requested?.first ?: controller.rememberedReport()?.takeIf(Files::isRegularFile)
        if (initial != null) show(initial, forceReload = requested?.second ?: false) else showStatus(Status.Scanning)
        discovery.ensureScanned()
        discoveryChanged(discovery.state)
    }

    /** Shows [report]; [forceReload] re-reads it even when it is already displayed. */
    fun show(report: Path, forceReload: Boolean = false) {
        val same = report == currentReport
        currentReport = report
        ReporterWorkspaceState.getInstance(project).state.selectedReport = report.toString()
        watch(report)

        when {
            !Files.isRegularFile(report) -> return showStatus(Status.Missing(report))
            !ProjectTrust.isTrusted(project) && report !in untrustedAllowed -> return showStatus(Status.Untrusted(report))
            !JcefSupport.isSupported() -> return showStatus(Status.Unsupported(report))
        }
        val view = browser ?: createBrowser(report) ?: return
        cards.show(cardPanel, BROWSER_CARD)
        displayedStatus = null
        loadedStamp = stampOf(report)
        when {
            !view.isShowing(report) -> view.load(report).also { watchLoad(report) }
            forceReload || !same -> view.reload().also { watchLoad(report) }
        }
    }

    /**
     * If the embedded browser has not finished loading after a while - Chromium failed to start, or hangs - say so
     * and offer the system browser, instead of leaving a spinner on screen.
     */
    private fun watchLoad(report: Path) {
        val loadsBefore = completedLoads
        loadWatchdog?.cancel()
        loadWatchdog = controller.scope.launch {
            delay(LOAD_TIMEOUT_MILLIS)
            withContext(Dispatchers.EDT) {
                if (!disposed && currentReport == report && displayedStatus == null && completedLoads == loadsBefore) {
                    LOG.warn("The embedded browser did not finish loading $report within ${LOAD_TIMEOUT_MILLIS / 1000} s")
                    showStatus(Status.Failed(report, message("viewer.error.timeout", LOAD_TIMEOUT_MILLIS / 1000)))
                }
            }
        }
    }

    /** Reloads the current report, keeping the report page the user is on; rescans when there is none. */
    fun refresh() {
        val report = currentReport
        if (report == null) {
            discovery.rescan()
            return
        }
        if (!Files.isRegularFile(report)) {
            showStatus(Status.Missing(report))
            return
        }
        show(report, forceReload = true)
    }

    private fun createBrowser(report: Path): JcefReportBrowser? = try {
        JcefReportBrowser(project, this, object : JcefReportBrowser.Listener {
            override fun loadStarted() = loadingPanel.startLoading()

            override fun loadFinished() {
                completedLoads++
                loadWatchdog?.cancel()
                loadingPanel.stopLoading()
                if (currentReport != null && browser != null) cards.show(cardPanel, BROWSER_CARD)
            }

            override fun loadFailed(errorText: String, url: String) {
                loadWatchdog?.cancel()
                loadingPanel.stopLoading()
                currentReport?.let { showStatus(Status.Failed(it, errorText)) }
            }
        }).also {
            browser = it
            loadingPanel.add(it.component, BorderLayout.CENTER)
        }
    } catch (e: Throwable) {
        if (e is ControlFlowException) throw e
        LOG.warn("Could not create the embedded browser", e)
        showStatus(Status.Failed(report, e.message ?: e.javaClass.simpleName))
        null
    }

    private fun discoveryChanged(state: DiscoveryState) {
        val report = currentReport
        when {
            report == null && state.reports.isNotEmpty() -> show(state.reports.first().file)
            report == null && state.scanning -> showStatus(Status.Scanning)
            report == null -> showStatus(Status.Empty)
        }
    }

    /** Keeps the current report's folder watched so external writes reach the VFS and [fileEvents]. */
    private fun watch(report: Path) {
        val directory = report.parent?.toString() ?: return
        val fileSystem = LocalFileSystem.getInstance()
        watchRequest = watchRequest?.let { fileSystem.replaceWatchedRoot(it, directory, false) } ?: fileSystem.addRootToWatch(directory, false)
        watchedFile = null
        controller.scope.launch(Dispatchers.IO) {
            // Loading the file into the VFS is what makes change events for it arrive.
            val file = fileSystem.refreshAndFindFileByNioFile(report)
            if (currentReport == report) watchedFile = file
        }
    }

    private fun fileEvents(events: List<VFileEvent>) {
        val report = currentReport ?: return
        val path = ReportFiles.comparablePath(report.toString())
        val watched = watchedFile
        if (events.none { (watched != null && it.file == watched) || ReportFiles.comparablePath(it.path) == path }) return
        scheduleReload(report)
    }

    /** Reloads [report] once it has been completely written, if auto-refresh is on and it is still the one shown. */
    private fun scheduleReload(report: Path) {
        reloadJob?.cancel()
        reloadJob = controller.scope.launch(Dispatchers.IO) {
            delay(RELOAD_DEBOUNCE_MILLIS)
            if (!Files.isRegularFile(report)) {
                withContext(Dispatchers.EDT) { if (currentReport == report) showStatus(Status.Missing(report)) }
                return@launch
            }
            // Never load a half-written report: wait until it ends with </html> and stops changing.
            if (!ReportLocator.awaitComplete(report)) return@launch
            if (!ReporterSettings.getInstance(project).state.autoRefresh) return@launch
            withContext(Dispatchers.EDT) {
                if (currentReport == report && !disposed) show(report, forceReload = true)
            }
        }
    }

    /**
     * Fallback for file watchers that miss a change - observed on macOS for folders with non-ASCII names, where the
     * IDE's native watcher delivers no event, and possible on network drives: while the viewer is on screen with
     * auto-refresh on, the one displayed file's size and timestamp are compared every few seconds.
     */
    private fun startChangePolling() {
        controller.scope.launch(Dispatchers.IO) {
            while (!disposed) {
                delay(POLL_INTERVAL_MILLIS)
                val report = currentReport ?: continue
                val shown = loadedStamp ?: continue
                if (!onScreen || reloadJob?.isActive == true || !ReporterSettings.getInstance(project).state.autoRefresh) continue
                val stamp = stampOf(report)
                if (stamp != null && stamp != shown && currentReport == report) scheduleReload(report)
            }
        }
    }

    private fun stampOf(report: Path): Pair<Long, Long>? =
        ReportFiles.attributes(report)?.let { it.size() to it.lastModifiedTime().toMillis() }

    private fun showStatus(status: Status) {
        displayedStatus = status.javaClass.simpleName
        loadingPanel.stopLoading()
        val text = statusPanel.emptyText
        text.clear()
        val link = SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
        val grey = SimpleTextAttributes.GRAYED_ATTRIBUTES
        when (status) {
            Status.Scanning -> text.appendLine(message("viewer.status.scanning"))
            Status.Empty -> {
                text.appendLine(message("viewer.status.empty"))
                text.appendLine(message("action.run.text"), link) { PytestRunService.getInstance(project).run() }
                text.appendLine(message("action.openReport.text"), link) { invokeAction("PytestHtmlReporter.OpenReport") }
                text.appendLine(message("action.rescan.text"), link) { discovery.rescan() }
            }
            is Status.Missing -> {
                text.appendLine(message("viewer.status.missing"))
                text.appendLine(status.report.toString(), grey, null)
                text.appendLine(message("action.run.text"), link) { PytestRunService.getInstance(project).run() }
                text.appendLine(message("action.rescan.text"), link) { forgetAndRescan() }
            }
            is Status.Untrusted -> {
                text.appendLine(message("viewer.status.untrusted"))
                text.appendLine(message("viewer.status.untrusted.detail"), grey, null)
                text.appendLine(message("viewer.action.openAnyway"), link) {
                    untrustedAllowed.add(status.report)
                    show(status.report, forceReload = true)
                }
                text.appendLine(message("action.openInBrowser.text"), link) { BrowserUtil.browse(status.report) }
            }
            is Status.Unsupported -> {
                text.appendLine(message("viewer.status.unsupported"))
                text.appendLine(message("viewer.status.unsupported.detail"), grey, null)
                text.appendLine(message("action.openInBrowser.text"), link) { BrowserUtil.browse(status.report) }
            }
            is Status.Failed -> {
                text.appendLine(message("viewer.status.failed"))
                text.appendLine(status.error, grey, null)
                text.appendLine(message("viewer.action.retry"), link) { show(status.report, forceReload = true) }
                text.appendLine(message("action.openInBrowser.text"), link) { BrowserUtil.browse(status.report) }
            }
        }
        cards.show(cardPanel, STATUS_CARD)
        statusPanel.repaint()
    }

    private fun forgetAndRescan() {
        currentReport = null
        ReporterWorkspaceState.getInstance(project).state.selectedReport = ""
        showStatus(Status.Scanning)
        discovery.rescan()
    }

    private fun invokeAction(id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        ActionManager.getInstance().tryToExecute(action, null, this, TOOLBAR_PLACE, true)
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ action() }, ModalityState.any()) { disposed }
    }

    override fun dispose() {
        disposed = true
        reloadJob?.cancel()
        loadWatchdog?.cancel()
        watchRequest?.let(LocalFileSystem.getInstance()::removeWatchedRoot)
        watchRequest = null
        controller.detach(this)
        browser = null
    }

    private companion object {
        private val LOG = logger<ReportViewerPanel>()
        private const val TOOLBAR_GROUP_ID = "PytestHtmlReporter.ViewerToolbar"
        private const val TOOLBAR_PLACE = "PytestHtmlReporterToolbar"
        private const val BROWSER_CARD = "browser"
        private const val STATUS_CARD = "status"
        private const val LOADING_DELAY_MILLIS = 150
        private const val RELOAD_DEBOUNCE_MILLIS = 400L
        private const val POLL_INTERVAL_MILLIS = 2_000L
        private const val LOAD_TIMEOUT_MILLIS = 30_000L
    }
}

private typealias ControlFlowException = com.intellij.openapi.diagnostic.ControlFlowException
