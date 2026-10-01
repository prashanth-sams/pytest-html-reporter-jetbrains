package io.github.prashanthsams.pytesthtmlreporter.viewer

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.jcef.JBCefBrowser
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.ReportNavigationPolicy
import io.github.prashanthsams.pytesthtmlreporter.core.ReportNavigationPolicy.Action
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.ui.ReporterNotifications
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefBeforeDownloadCallback
import org.cef.callback.CefDownloadItem
import org.cef.callback.CefDownloadItemCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefDownloadHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.handler.CefResourceRequestHandler
import org.cef.handler.CefResourceRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/**
 * The embedded Chromium view of one report at a time.
 *
 * The report's own interface is used unchanged: its scripts, charts, tables, themes and `#hash` routes all run as
 * written. What this class adds is containment - see [ReportNavigationPolicy] - and two conveniences: following the
 * IDE theme until the report's own theme switch is used, and saving the report's CSV/Excel/PDF exports.
 * No JavaScript bridge is installed; nothing in the page can call into the IDE.
 */
internal class JcefReportBrowser(
    private val project: Project,
    parentDisposable: Disposable,
    private val listener: Listener,
    /** Tests run headless: off-screen rendering, and the native browser created without waiting to be shown. */
    headless: Boolean = false,
) : Disposable {

    interface Listener {
        fun loadStarted()
        fun loadFinished()
        fun loadFailed(errorText: String, url: String)
    }

    private val browser: JBCefBrowser = JBCefBrowser.createBuilder()
        .setEnableOpenDevToolsMenuItem(false)
        .apply { if (headless) setOffScreenRendering(true).setCreateImmediately(true) }
        .build()

    /** For tests: the underlying browser, to observe what the page did. */
    @get:org.jetbrains.annotations.TestOnly
    internal val jbCefBrowser: JBCefBrowser get() = browser

    @Volatile
    private var policy: ReportNavigationPolicy? = null

    @Volatile
    private var reportFile: Path? = null

    /** The main frame's address, which the report keeps in step with its current tab (`history.replaceState`). */
    @Volatile
    var currentUrl: String? = null
        private set

    private val pendingDownloads = ConcurrentHashMap<Int, String>()

    val component: JComponent get() = browser.component

    init {
        Disposer.register(parentDisposable, this)
        Disposer.register(this, browser)
        val client = browser.jbCefClient
        val cefBrowser = browser.cefBrowser
        client.addRequestHandler(RequestHandler(), cefBrowser)
        client.addLifeSpanHandler(PopupHandler(), cefBrowser)
        client.addLoadHandler(LoadHandler(), cefBrowser)
        client.addDisplayHandler(AddressHandler(), cefBrowser)
        client.addDownloadHandler(DownloadHandler(), cefBrowser)
    }

    /** Loads [report] from the start. */
    fun load(report: Path) {
        reportFile = report
        policy = createPolicy(report)
        browser.loadURL(ReportNavigationPolicy.fileUrl(report))
    }

    /** Reloads the current report from disk, keeping the page the user is on (the URL fragment) and the scroll position. */
    fun reload() {
        val report = reportFile ?: return
        policy = createPolicy(report)
        if (currentUrl?.let { policy?.isReportDocument(it) } == true) browser.cefBrowser.reloadIgnoreCache() else load(report)
    }

    fun isShowing(report: Path): Boolean = reportFile == report

    /** Re-reads settings that affect containment (remote content). */
    fun settingsChanged() {
        reportFile?.let { policy = createPolicy(it) }
        applyTheme()
    }

    /** Applies the IDE theme unless the user picked one with the report's own switch (stored in localStorage). */
    fun applyTheme() {
        if (!ReporterSettings.getInstance(project).state.followIdeTheme) return
        val theme = if (JBColor.isBright()) "light" else "dark"
        val script = """
            (function () {
              try { if (window.localStorage && window.localStorage.getItem('pytestHtmlReporterTheme')) { return; } } catch (e) {}
              if (document.documentElement) { document.documentElement.setAttribute('data-theme', '$theme'); }
            })();
        """.trimIndent()
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url ?: "", 0)
    }

    private fun createPolicy(report: Path): ReportNavigationPolicy = ReportNavigationPolicy(
        reportFile = report,
        projectRoot = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() },
        blockRemoteContent = ReporterSettings.getInstance(project).state.blockRemoteContent,
    )

    override fun dispose() {
        pendingDownloads.clear()
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) action() }, ModalityState.any())
    }

    private fun handle(decision: ReportNavigationPolicy.Decision, url: String) {
        when (decision.action) {
            Action.ALLOW -> Unit
            Action.OPEN_IN_BROWSER -> onEdt { BrowserUtil.browse(url) }
            Action.OPEN_LOCAL_FILE -> decision.localFile?.let { file -> onEdt { openLocalFile(file) } }
            Action.BLOCK -> onEdt { ReporterNotifications.notify(project, message("viewer.blocked", url)) }
        }
    }

    /** HTML opens in the system browser (it may be another report or a coverage page); anything else in the IDE. */
    private fun openLocalFile(file: Path) {
        if (file.fileName.toString().endsWith(".html", ignoreCase = true) || file.fileName.toString().endsWith(".htm", ignoreCase = true)) {
            BrowserUtil.browse(file)
            return
        }
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
        if (virtualFile != null) FileEditorManager.getInstance(project).openFile(virtualFile, true) else BrowserUtil.browse(file)
    }

    private inner class RequestHandler : CefRequestHandlerAdapter() {
        private val resources = ResourceHandler()

        override fun onBeforeBrowse(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean): Boolean {
            val url = request?.url ?: return true
            val policy = policy ?: return true
            if (frame != null && !frame.isMain) return !policy.allowsResource(url)
            val decision = policy.navigation(url)
            if (decision.action == Action.ALLOW) return false
            handle(decision, url)
            return true
        }

        override fun getResourceRequestHandler(
            browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, isNavigation: Boolean, isDownload: Boolean,
            requestInitiator: String?, disableDefaultHandling: BoolRef?,
        ): CefResourceRequestHandler = resources
    }

    private inner class ResourceHandler : CefResourceRequestHandlerAdapter() {
        override fun onBeforeResourceLoad(browser: CefBrowser?, frame: CefFrame?, request: CefRequest?): Boolean {
            val url = request?.url ?: return true
            val allowed = policy?.allowsResource(url) ?: false
            if (!allowed) LOG.debug { "Blocked report subresource: $url" }
            return !allowed
        }
    }

    /** The viewer never opens windows: `target="_blank"` links are routed like navigations. */
    private inner class PopupHandler : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean {
            val url = targetUrl.orEmpty()
            val decision = policy?.popup(url) ?: return true
            if (decision.action == Action.ALLOW) onEdt { this@JcefReportBrowser.browser.loadURL(url) } else handle(decision, url)
            return true
        }
    }

    private inner class LoadHandler : CefLoadHandlerAdapter() {
        override fun onLoadingStateChange(browser: CefBrowser?, isLoading: Boolean, canGoBack: Boolean, canGoForward: Boolean) {
            onEdt { if (isLoading) listener.loadStarted() else listener.loadFinished() }
        }

        override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
            if (frame?.isMain == true) applyTheme()
        }

        override fun onLoadError(browser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?) {
            // ERR_ABORTED is what a navigation cancelled by this class looks like; it is not a failure of the report.
            if (frame?.isMain != true || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
            onEdt { listener.loadFailed(errorText ?: errorCode?.name.orEmpty(), failedUrl.orEmpty()) }
        }
    }

    private inner class AddressHandler : CefDisplayHandlerAdapter() {
        override fun onAddressChange(browser: CefBrowser?, frame: CefFrame?, url: String?) {
            if (frame?.isMain != false) currentUrl = url
        }

        override fun onConsoleMessage(browser: CefBrowser?, level: org.cef.CefSettings.LogSeverity?, message: String?, source: String?, line: Int): Boolean {
            LOG.debug { "report console [$level] $message ($source:$line)" }
            return false
        }
    }

    /**
     * Exports (CSV, Excel, PDF) are generated in the page and arrive as downloads. They are written to a private temp
     * folder first and then offered to the user, so no Chromium callback is ever left waiting on a dialog.
     */
    private inner class DownloadHandler : CefDownloadHandlerAdapter() {
        override fun onBeforeDownload(browser: CefBrowser?, item: CefDownloadItem?, suggestedName: String?, callback: CefBeforeDownloadCallback?) {
            if (item == null || callback == null) return
            val name = (suggestedName?.takeIf(String::isNotBlank) ?: item.suggestedFileName ?: "export").replace(Regex("""[\\/:*?"<>|]"""), "_")
            val directory = Path.of(PathManager.getTempPath(), "pytest-html-reporter", "downloads", item.id.toString())
            try {
                Files.createDirectories(directory)
            } catch (e: IOException) {
                LOG.warn("Cannot create download folder $directory", e)
                return
            }
            pendingDownloads[item.id] = name
            callback.Continue(directory.resolve(name).toString(), false)
        }

        override fun onDownloadUpdated(browser: CefBrowser?, item: CefDownloadItem?, callback: CefDownloadItemCallback?) {
            if (item == null || !item.isComplete) return
            val name = pendingDownloads.remove(item.id) ?: return
            val temporary = Path.of(item.fullPath ?: return)
            onEdt { offerDownload(temporary, name) }
        }
    }

    private fun offerDownload(temporary: Path, name: String) {
        val descriptor = FileSaverDescriptor(message("download.title"), message("download.description"))
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
        val target = dialog.save(reportFile?.parent, name)?.file?.toPath()
        try {
            if (target != null) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                ReporterNotifications.notify(
                    project, message("download.saved", target.fileName.toString()),
                    actions = listOf(ReporterNotifications.action(message("action.reveal.text")) { com.intellij.ide.actions.RevealFileAction.openFile(target) }),
                )
            }
        } catch (e: IOException) {
            ReporterNotifications.notify(project, message("download.failed", e.message.orEmpty()), com.intellij.notification.NotificationType.ERROR)
        } finally {
            runCatching { Files.deleteIfExists(temporary) }
            runCatching { Files.deleteIfExists(temporary.parent) }
        }
    }

    private companion object {
        private val LOG = logger<JcefReportBrowser>()
    }
}
