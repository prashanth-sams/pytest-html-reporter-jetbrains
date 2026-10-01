package io.github.prashanthsams.pytesthtmlreporter.smoke

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Computable
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.scale.JBUIScale
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.run.InterpreterResolution
import io.github.prashanthsams.pytesthtmlreporter.run.PythonInterpreterProvider
import io.github.prashanthsams.pytesthtmlreporter.run.PytestRunService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterConfigurable
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import io.github.prashanthsams.pytesthtmlreporter.viewer.ReportViewerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.awt.Graphics2D
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import java.awt.Component
import java.awt.Container
import java.awt.Frame
import java.awt.Window
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JDialog

/** Starts the smoke scenario when the sandbox IDE is launched with `-Dphr.smoke.output=<folder>`. */
class SmokeTestActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val output = System.getProperty("phr.smoke.output")?.takeIf(String::isNotBlank) ?: return
        SmokeRunner(project, Path.of(output)).run()
    }
}

/**
 * Drives the real plugin the way a user would and records each step's outcome to `results.json`, with screenshots
 * painted from the IDE's own windows (no OS screen-capture permission involved).
 */
internal class SmokeRunner(private val project: Project, private val output: Path) {
    private val steps = mutableListOf<Map<String, Any?>>()
    private val notifications = CopyOnWriteArrayList<String>()
    private val fileEvents = CopyOnWriteArrayList<String>()
    private val geometry = CopyOnWriteArrayList<String>()
    private val mode = System.getProperty("phr.smoke.mode", "full")
    private val projectSdkMode = System.getProperty("phr.smoke.sdk", "project") == "project"
    private var sdkSetups = 0
    @Volatile
    private var closing = false

    suspend fun run() {
        Files.createDirectories(output)
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                events.filter { it.path.endsWith(".html") }.forEach { event ->
                    val decomposed = event.path.any { it.code == 0x0308 }
                    fileEvents += "${event.javaClass.simpleName} ${event.path}${if (decomposed) " (NFD)" else ""}"
                }
            }
        })
        project.messageBus.connect().subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += "${notification.type}: ${notification.title}: ${notification.content}"
            }
        })
        try {
            step("smart mode") { smartReadAction(project) { true }; "indexing finished" }
            step("environment") { environment() }
            prepareWindow()
            when (mode) {
                "fallback" -> fallbackScenario()
                else -> fullScenario()
            }
        } catch (t: Throwable) {
            record("fatal", false, t.stackTraceToString())
        } finally {
            if (!closing) {
                write()
                if (System.getProperty("phr.smoke.exit", "true") == "true") exitIde()
            }
        }
    }

    private suspend fun fullScenario() {
        step("interpreter") { configureInterpreter() }
        step("tool window empty state") {
            openToolWindow()
            val panel = waitFor("viewer panel") { ReportViewerController.getInstance(project).viewerPanel }
            waitUntil("empty state", 30_000) { panel.displayedStatus == "Empty" }
            screenshot("01-empty-state")
            "status=${panel.displayedStatus}"
        }
        step("run pytest with HTML report") {
            val sdkNote = ensureProjectSdk()
            PytestRunService.getInstance(project).run()
            waitUntil("run finished", 240_000) { notifications.any { "pytest finished" in it } }
            val panel = ReportViewerController.getInstance(project).viewerPanel!!
            waitUntil("report displayed", 60_000) { panel.displayedStatus == null && panel.completedLoads >= 1 }
            delay(2_500) // let the report's charts finish animating
            val selected = ReporterWorkspaceState.getInstance(project).state.selectedReport
            check(selected == project.basePath + "/report/pytest_html_report.html") { "unexpected report $selected" }
            screenshot("02-after-run")
            "report=$selected; notification=${notifications.last { "pytest finished" in it }}$sdkNote"
        }
        step("report pages") {
            setMaximized(true)
            delay(1_500)
            screenshot("03-dashboard")
            for ((route, name) in listOf("test-metrics" to "04-test-metrics", "screenshots" to "05-screenshots", "api-logs" to "06-api-logs")) {
                navigate(route)
                screenshot(name)
            }
            navigate("test-metrics")
            "pages captured; url=${browser()?.cefBrowser?.url}"
        }
        step("auto-refresh keeps the page") {
            val panel = ReportViewerController.getInstance(project).viewerPanel!!
            val before = panel.completedLoads
            val report = Path.of(ReporterWorkspaceState.getInstance(project).state.selectedReport!!)
            // Rewrite the report the way the reporter does (truncate, then write), from outside the IDE's VFS.
            val bytes = Files.readAllBytes(report)
            Files.write(report, bytes)
            waitUntil("viewer reloaded", 30_000) { panel.completedLoads > before }
            delay(1_000)
            val url = browser()?.cefBrowser?.url.orEmpty()
            check("#test-metrics" in url) { "page not kept after reload: $url" }
            "reloads ${panel.completedLoads - before}, url=$url"
        }
        step("second report and selector") {
            val other = Path.of(project.basePath!!, "reports", "nightly", "pytest_html_report.html")
            Files.createDirectories(other.parent)
            val current = Path.of(ReporterWorkspaceState.getInstance(project).state.selectedReport!!)
            Files.copy(current, other)
            Files.copy(current.resolveSibling("output.json"), other.resolveSibling("output.json"))
            ReportDiscoveryService.getInstance(project).rescan().join()
            val reports = ReportDiscoveryService.getInstance(project).state.reports.map { it.file.toString() }
            check(reports.size >= 2) { "expected two reports, found $reports" }
            "reports=$reports"
        }
        setMaximized(false)
        step("settings page") {
            val sdkNote = ensureProjectSdk()
            ApplicationManager.getApplication().invokeLater({
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ReporterConfigurable::class.java)
            }, ModalityState.nonModal())
            val dialog = waitFor("settings dialog", 30_000) {
                withContext(UI) { Window.getWindows().firstOrNull { it is JDialog && it.isShowing } }
            }
            // A first-run sandbox opens Settings at its minimum size; use a typical window size for the capture.
            withContext(UI) {
                dialog.setSize(1280, 880)
                dialog.validate()
            }
            delay(1_500)
            withContext(UI) {
                paint(dialog, "07-settings")
                val wrapper = DialogWrapper.findInstance((dialog as JDialog).rootPane) ?: DialogWrapper.findInstance(dialog.contentPane)
                if (wrapper != null) wrapper.close(DialogWrapper.CANCEL_EXIT_CODE) else dialog.dispose()
            }
            waitUntil("settings dialog closed", 10_000) { withContext(UI) { !dialog.isShowing } }
            "captured and closed$sdkNote"
        }
        step("untrusted project") {
            TrustedProjects.setProjectTrusted(project, false)
            try {
                val report = Path.of(ReporterWorkspaceState.getInstance(project).state.selectedReport!!)
                withContext(UI) { ReportViewerController.getInstance(project).open(report, activate = true, forceReload = true) }
                val panel = ReportViewerController.getInstance(project).viewerPanel!!
                waitUntil("untrusted page", 10_000) { panel.displayedStatus == "Untrusted" }
                screenshot("08-untrusted")
                val before = notifications.size
                PytestRunService.getInstance(project).run()
                waitUntil("run refused", 10_000) { notifications.drop(before).any { "untrusted" in it } }
                "viewer=${panel.displayedStatus}; run refused"
            } finally {
                TrustedProjects.setProjectTrusted(project, true)
            }
        }
        closeProjectAndExit()
    }

    /**
     * Closing the project cancels the project-scoped coroutine this scenario runs in, so the last step runs as a plain
     * EDT task in a write-safe (non-modal) context, records its outcome, writes the results and exits.
     */
    private fun closeProjectAndExit() {
        closing = true
        ApplicationManager.getApplication().invokeLater({
            val started = System.currentTimeMillis()
            val closed = runCatching { ProjectManager.getInstance().closeAndDispose(project) }
            record("project close", closed.isSuccess && project.isDisposed,
                "closed=${closed.getOrNull()}, disposed=${project.isDisposed}${closed.exceptionOrNull()?.let { ", error=$it" } ?: ""}",
                System.currentTimeMillis() - started)
            write()
            if (System.getProperty("phr.smoke.exit", "true") == "true") ApplicationManager.getApplication().exit(true, true, false)
        }, ModalityState.nonModal())
    }

    /** The IDE was started with JCEF disabled: the viewer must offer the system browser instead. */
    private suspend fun fallbackScenario() {
        step("fallback") {
            val report = Path.of(project.basePath!!, "report", "pytest_html_report.html")
            check(Files.isRegularFile(report)) { "fallback scenario needs an existing report at $report" }
            openToolWindow()
            withContext(UI) { ReportViewerController.getInstance(project).open(report, activate = true) }
            val panel = waitFor("viewer panel") { ReportViewerController.getInstance(project).viewerPanel }
            waitUntil("fallback page", 30_000) { panel.displayedStatus == "Unsupported" }
            screenshot("09-jcef-unavailable")
            "status=${panel.displayedStatus}"
        }
        closeProjectAndExit()
    }

    private fun environment(): String {
        val info = ApplicationInfo.getInstance()
        val jcefPlugin = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.modules.jcef"))
        val python = PluginManagerCore.getPlugin(PluginId.getId("PythonCore"))
        val ours = PluginManagerCore.getPlugin(PluginId.getId("io.github.prashanthsams.pytesthtmlreporter"))
        // The decisive check for 2026.2+: can the plugin's own class loader see JCEF?
        val jcefClass = runCatching {
            ReportViewerController::class.java.classLoader.loadClass("com.intellij.ui.jcef.JBCefBrowser").classLoader.toString()
        }.getOrElse { "NOT LOADABLE: $it" }
        val jcefSupported = runCatching { com.intellij.ui.jcef.JBCefApp.isSupported() }.getOrElse { "error: $it" }
        return listOf(
            "ide=${info.fullApplicationName} (${info.build.asString()})",
            "plugin=${ours?.version}",
            "pythonPlugin=${python?.version}",
            "jcefPlugin=${jcefPlugin?.let { "${it.pluginId.idString} ${it.version}" } ?: "none (JCEF in core)"}",
            "JBCefBrowser via plugin class loader=$jcefClass",
            "JBCefApp.isSupported=$jcefSupported",
            "trusted=${TrustedProjects.isProjectTrusted(project)}",
        ).joinToString("; ")
    }

    private suspend fun configureInterpreter(): String {
        val python = System.getProperty("phr.smoke.python") ?: error("phr.smoke.python is not set")
        var note = ""
        if (projectSdkMode) {
            // The IDE normally configures the project's .venv on its own, as for a user; wait for that first.
            note = ensureProjectSdk(waitForIdeMillis = 30_000)
            ReporterWorkspaceState.getInstance(project).state.interpreterPath = ""
        } else {
            ReporterWorkspaceState.getInstance(project).state.interpreterPath = python
        }
        val resolved = ApplicationManager.getApplication().runReadAction(Computable {
            PythonInterpreterProvider.EP_NAME.extensionList.map { it.findInterpreter(project, project.basePath?.let(Path::of)) }
        })
        return "providers=$resolved; explicit=${ReporterWorkspaceState.getInstance(project).state.interpreterPath}; output=${ReporterSettings.getInstance(project).state.outputPath}$note"
    }

    private suspend fun projectInterpreterResolves(): Boolean = readAction {
        PythonInterpreterProvider.EP_NAME.extensionList.any {
            it.findInterpreter(project, project.basePath?.let(Path::of)) is InterpreterResolution.Local
        }
    }

    /**
     * Makes sure the project interpreter resolves through the platform SDK model. Waits up to [waitForIdeMillis] for the
     * IDE's own configuration, then creates a bare SDK itself (newer PyCharm versions treat such an SDK as incomplete in
     * their own widgets, but the plugin resolves it). A sandbox can also reload the global SDK table from disk a few
     * seconds after startup, which drops SDKs created before that (seen on 2025.1), so every step that needs the
     * interpreter calls this first. Returns a note for the step's details when the smoke test created the SDK.
     */
    private suspend fun ensureProjectSdk(waitForIdeMillis: Long = 0): String {
        if (!projectSdkMode) return ""
        val python = System.getProperty("phr.smoke.python") ?: error("phr.smoke.python is not set")
        val deadline = System.currentTimeMillis() + waitForIdeMillis
        while (!projectInterpreterResolves()) {
            if (System.currentTimeMillis() >= deadline) break
            delay(500)
        }
        if (projectInterpreterResolves()) return ""
        // PyCharm validates the SDK version string ("Python 3.14.6"), so ask the interpreter for it.
        val version = withContext(Dispatchers.IO) {
            val process = ProcessBuilder(python, "-c", "import platform; print(platform.python_version())").start()
            process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
        }
        edtWriteAction {
            val type = SdkType.findByName("Python SDK") ?: error("no Python SDK type in this IDE")
            val table = ProjectJdkTable.getInstance()
            val sdk = table.findJdk(SDK_NAME) ?: table.createSdk(SDK_NAME, type).also { sdk ->
                sdk.sdkModificator.apply {
                    homePath = python
                    versionString = "Python $version"
                    commitChanges()
                }
                table.addJdk(sdk)
            }
            ProjectRootManager.getInstance(project).projectSdk = sdk
            ModuleManager.getInstance(project).modules.forEach(ModuleRootModificationUtil::setSdkInherited)
        }
        sdkSetups++
        return if (sdkSetups == 1) "; SDK created by the smoke test" else "; SDK created again by the smoke test (the IDE had dropped it)"
    }

    private suspend fun prepareWindow() = withContext(UI) {
        val frame = WindowManager.getInstance().getFrame(project) ?: return@withContext
        frame.extendedState = Frame.NORMAL
        frame.setBounds(40, 40, 1440, 900)
    }

    private suspend fun openToolWindow() = withContext(UI) {
        ToolWindowManager.getInstance(project).getToolWindow(ReportViewerController.TOOL_WINDOW_ID)!!.activate(null)
    }

    private suspend fun setMaximized(maximized: Boolean) = withContext(UI) {
        val manager = ToolWindowManager.getInstance(project)
        manager.getToolWindow(ReportViewerController.TOOL_WINDOW_ID)?.let { manager.setMaximized(it, maximized) }
    }

    private suspend fun navigate(route: String) {
        val browser = browser() ?: error("embedded browser not found")
        browser.cefBrowser.executeJavaScript("window.location.hash = '#$route';", browser.cefBrowser.url, 0)
        delay(2_000)
    }

    /** The embedded browser, found through the client property JCEF puts on its component. */
    private suspend fun browser(): JBCefBrowser? = withContext(UI) {
        val panel = ReportViewerController.getInstance(project).viewerPanel ?: return@withContext null
        find(panel)
    }

    private fun find(component: Component): JBCefBrowser? {
        (component as? JComponent)?.getClientProperty(JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP)?.let { return it as? JBCefBrowser }
        if (component is Container) for (child in component.components) find(child)?.let { return it }
        return null
    }

    /**
     * Paints the IDE frame, then draws the embedded browser's own frame (CefBrowser.createScreenshot) over the
     * browser's area: an off-screen browser draws device pixels for the window it is shown in, which a plain
     * BufferedImage does not reproduce.
     */
    private suspend fun screenshot(name: String) {
        val browser = browser()?.takeIf { withContext(UI) { it.component.isShowing } }
        val frameImage = browser?.let { capture(it, name) }
        withContext(UI) {
            val frame = WindowManager.getInstance().getFrame(project) ?: return@withContext
            paint(frame, name) { root, graphics ->
                if (browser != null && frameImage != null) {
                    val component = browser.component
                    val origin = SwingUtilities.convertPoint(component, 0, 0, root)
                    graphics.drawImage(frameImage, origin.x, origin.y, component.width, component.height, null)
                    val scale = JBUIScale.sysScale(component.graphicsConfiguration)
                    geometry += "$name: browser ${component.width}x${component.height} @${scale}x, frame ${frameImage.width}x${frameImage.height}"
                }
            }
        }
    }

    /** The browser's rendered viewport, as Chromium itself produces it. */
    private fun capture(browser: JBCefBrowser, name: String): BufferedImage? {
        val viaDevTools = runCatching {
            val result = browser.cefBrowser.devToolsClient
                .executeDevToolsMethod("Page.captureScreenshot", "{\"format\":\"png\"}")
                .get(15, TimeUnit.SECONDS)
            val data = Regex("\"data\"\\s*:\\s*\"([^\"]+)\"").find(result)?.groupValues?.get(1) ?: error("no image in $result")
            ImageIO.read(java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data)))
        }
        viaDevTools.exceptionOrNull()?.let { geometry += "$name: DevTools capture failed: $it" }
        return viaDevTools.getOrNull() ?: runCatching { browser.cefBrowser.createScreenshot(true).get(15, TimeUnit.SECONDS) }
            .onFailure { geometry += "$name: createScreenshot failed: $it" }
            .getOrNull()
    }

    private fun paint(window: Window, name: String, overlay: (JComponent, Graphics2D) -> Unit = { _, _ -> }) {
        val root = (window as? javax.swing.RootPaneContainer)?.rootPane ?: return
        val scale = JBUIScale.sysScale(root.graphicsConfiguration).toDouble()
        val image = BufferedImage((root.width * scale).toInt(), (root.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.scale(scale, scale)
        root.paint(graphics)
        overlay(root, graphics)
        graphics.dispose()
        ImageIO.write(image, "png", output.resolve("$name.png").toFile())
    }

    private suspend fun step(name: String, action: suspend () -> String) {
        val started = System.currentTimeMillis()
        try {
            record(name, true, withTimeout(STEP_TIMEOUT_MILLIS) { action() }, System.currentTimeMillis() - started)
        } catch (t: Throwable) {
            record(name, false, t.toString() + "\n" + t.stackTrace.take(8).joinToString("\n"), System.currentTimeMillis() - started)
            runCatching { screenshot("failure-" + name.replace(Regex("[^A-Za-z0-9]+"), "-")) }
        }
    }

    private fun record(name: String, ok: Boolean, details: String, millis: Long = 0) {
        steps += linkedMapOf("step" to name, "ok" to ok, "millis" to millis, "details" to details)
    }

    private suspend fun waitUntil(what: String, timeoutMillis: Long, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) error("timed out waiting for $what; notifications=$notifications")
            delay(250)
        }
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMillis: Long = 30_000, supplier: suspend () -> T?): T {
        var value: T? = null
        waitUntil(what, timeoutMillis) { supplier().also { value = it } != null }
        return value!!
    }

    private fun write() {
        val json = buildString {
            append("{\n  \"mode\": ").append(quote(mode)).append(",\n  \"steps\": [\n")
            steps.forEachIndexed { index, step ->
                append("    {")
                append(step.entries.joinToString(", ") { (key, value) -> "${quote(key)}: ${if (value is String) quote(value) else value}" })
                append("}").append(if (index < steps.lastIndex) ",\n" else "\n")
            }
            append("  ],\n  \"notifications\": [")
            append(notifications.joinToString(", ") { quote(it) })
            append("],\n  \"fileEvents\": [")
            append(fileEvents.joinToString(", ") { quote(it) })
            append("],\n  \"geometry\": [")
            append(geometry.joinToString(", ") { quote(it) })
            append("]\n}\n")
        }
        Files.writeString(output.resolve("results.json"), json)
    }

    private fun quote(text: String) = "\"" + text.flatMap { c ->
        when {
            c == '"' -> listOf('\\', '"')
            c == '\\' -> listOf('\\', '\\')
            c == '\n' -> listOf('\\', 'n')
            c < ' ' -> "\\u%04x".format(c.code).toList()
            else -> listOf(c)
        }
    }.joinToString("") + "\""

    private fun exitIde() {
        ApplicationManager.getApplication().invokeLater({
            ApplicationManager.getApplication().exit(true, true, false)
        }, ModalityState.nonModal())
    }

    private companion object {
        private const val SDK_NAME = "smoke-python"
        private const val STEP_TIMEOUT_MILLIS = 300_000L
        private val UI = Dispatchers.EDT + ModalityState.any().asContextElement()
    }
}
