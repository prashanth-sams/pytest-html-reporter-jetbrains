package io.github.prashanthsams.pytesthtmlreporter.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.execution.ParametersListUtil
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.CommandNote
import io.github.prashanthsams.pytesthtmlreporter.core.ExpectedReport
import io.github.prashanthsams.pytesthtmlreporter.core.ProbeResult
import io.github.prashanthsams.pytesthtmlreporter.core.PytestArguments
import io.github.prashanthsams.pytesthtmlreporter.core.PytestCommandBuilder
import io.github.prashanthsams.pytesthtmlreporter.core.PytestExitCode
import io.github.prashanthsams.pytesthtmlreporter.core.ReportCandidate
import io.github.prashanthsams.pytesthtmlreporter.core.ReportLocator
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner
import io.github.prashanthsams.pytesthtmlreporter.core.ReporterVersion
import io.github.prashanthsams.pytesthtmlreporter.core.RunSummary
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterConfigurable
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.ui.ProjectTrust
import io.github.prashanthsams.pytesthtmlreporter.ui.ReporterNotifications
import io.github.prashanthsams.pytesthtmlreporter.viewer.ReportViewerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * "Run pytest with HTML Report": `<python> -m pytest <targets> <arguments> --html-report=<output> --report-open=none`
 * in the Run tool window, then the report that run wrote - and only that one - in the viewer.
 */
@Service(Service.Level.PROJECT)
class PytestRunService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val preparing = AtomicBoolean(false)
    private val activeProcess = AtomicReference<ProcessHandler?>(null)

    val isRunning: Boolean get() = preparing.get() || activeProcess.get()?.isProcessTerminated == false

    /** Starts a run; [targets] replaces the configured targets (e.g. files selected in the Project view). */
    fun run(targets: List<String>? = null) {
        if (!ProjectTrust.isTrusted(project)) {
            ReporterNotifications.notify(project, message("run.untrusted"), NotificationType.WARNING)
            return
        }
        if (isRunning || !preparing.compareAndSet(false, true)) {
            ReporterNotifications.notify(
                project, message("run.alreadyRunning"), NotificationType.WARNING,
                actions = listOf(ReporterNotifications.action(message("run.action.stop")) { stop() }),
            )
            return
        }
        scope.launch {
            try {
                prepareAndStart(targets, installAttempted = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("pytest-html-reporter run failed to start", e)
                ReporterNotifications.notify(project, message("run.error.unexpected", e.message ?: e.javaClass.simpleName), NotificationType.ERROR)
            } finally {
                preparing.set(false)
            }
        }
    }

    /**
     * Interrupts pytest (SIGINT, so it can still finish its session and write the report), and kills it if it is
     * still running after a grace period - for example when SIGINT is ignored because the IDE was started in the
     * background, which child processes inherit.
     */
    fun stop() {
        val handler = activeProcess.get()?.takeUnless { it.isProcessTerminated } ?: return
        handler.destroyProcess()
        if (handler is KillableProcessHandler) {
            scope.launch {
                delay(STOP_GRACE_MILLIS)
                if (!handler.isProcessTerminated) {
                    LOG.info("pytest did not stop after an interrupt; killing it")
                    handler.killProcess()
                }
            }
        }
    }

    private suspend fun prepareAndStart(targets: List<String>?, installAttempted: Boolean) {
        val settings = ReporterSettings.getInstance(project).state
        val workingDirectory = workingDirectory() ?: return
        val interpreter = when (val resolution = InterpreterResolver.resolve(project, workingDirectory)) {
            is InterpreterResolution.Local -> resolution
            is InterpreterResolution.Remote -> return notifyWithSettings(message("interpreter.remote", resolution.name), NotificationType.WARNING)
            is InterpreterResolution.Unavailable -> return notifyWithSettings(resolution.reason, NotificationType.WARNING)
        }
        val userArguments = ParametersListUtil.parse(settings.pytestArguments.orEmpty())
        val runTargets = targets ?: ParametersListUtil.parse(settings.targets.orEmpty())
        val environment = RunEnvironment.create(interpreter.executable, settings.environmentVariables, settings.passParentEnvironment)

        val probe = try {
            withBackgroundProgress(project, message("progress.probe")) {
                ReporterProbe.run(interpreter.executable, workingDirectory, environment, PytestArguments(userArguments).reportProfile)
            }
        } catch (e: ReporterProbe.ProbeException) {
            LOG.info("Probe failed: ${e.message}\n${e.details.orEmpty()}")
            ReporterNotifications.notify(project, listOfNotNull(e.message, e.details?.take(MAX_DETAILS)).joinToString("\n"), NotificationType.ERROR)
            return
        }

        if (!probe.hasPytest || !probe.hasReporter) {
            if (installAttempted) {
                ReporterNotifications.notify(project, message("install.stillMissing", interpreter.executable), NotificationType.ERROR)
                return
            }
            val packages = if (probe.hasPytest) listOf(PACKAGE) else listOf("pytest", PACKAGE)
            if (askToInstall(interpreter.executable, probe, packages, upgrade = false)) {
                install(interpreter.executable, environment, workingDirectory, packages) { prepareAndStart(targets, installAttempted = true) }
            }
            return
        }
        if (!probe.meetsMinimumVersion) {
            when (askAboutOldVersion(probe)) {
                OldVersionChoice.UPGRADE -> {
                    install(interpreter.executable, environment, workingDirectory, listOf(PACKAGE)) { prepareAndStart(targets, installAttempted = true) }
                    return
                }
                OldVersionChoice.RUN_ANYWAY -> Unit
                OldVersionChoice.CANCEL -> return
            }
        }

        val build = PytestCommandBuilder().build(
            PytestCommandBuilder.Request(
                interpreter = interpreter.executable.toString(),
                workingDirectory = workingDirectory,
                targets = runTargets,
                userArguments = userArguments,
                outputSetting = settings.outputPath.orEmpty(),
                respectPytestConfiguration = settings.respectPytestConfiguration,
                environment = environment.effective,
            ),
            probe,
        )
        val blocking = build.notes.filter { it.severity == CommandNote.Severity.ERROR }
        if (blocking.isNotEmpty() && !confirmAnyway(blocking)) return

        withContext(Dispatchers.EDT) { start(build, interpreter, probe, environment, workingDirectory, targets) }
    }

    private fun start(
        build: PytestCommandBuilder.Result,
        interpreter: InterpreterResolution.Local,
        probe: ProbeResult,
        environment: RunEnvironment,
        workingDirectory: Path,
        targets: List<String>?,
    ) {
        val command = environment.applyTo(GeneralCommandLine(build.command).withWorkingDirectory(workingDirectory))
        val handler = try {
            KillableColoredProcessHandler(command)
        } catch (e: ExecutionException) {
            ReporterNotifications.notify(project, message("run.error.start", e.message.orEmpty()), NotificationType.ERROR)
            return
        }
        // Stop sends SIGINT first, so pytest finishes its session and the reporter still writes the report.
        handler.setShouldKillProcessSoftly(true)
        ProcessTerminatedListener.attach(handler, project)
        val startedAt = System.currentTimeMillis()
        handler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) {
                consoleHeader(interpreter, probe, build, command, workingDirectory).forEach { line ->
                    handler.notifyTextAvailable(line + "\n", ProcessOutputTypes.SYSTEM)
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                activeProcess.compareAndSet(handler, null)
                scope.launch { completed(build.expectedReport, event.exitCode, startedAt, workingDirectory) }
            }
        })
        activeProcess.set(handler)
        RunContentExecutor(project, handler)
            .withTitle(message("run.console.title"))
            .withRerun { run(targets) }
            .withActivateToolWindow(true)
            .withFocusToolWindow(false)
            .run()
    }

    /** Finds the report the finished run wrote and shows it; never presents an older report as this run's result. */
    private suspend fun completed(expected: ExpectedReport, exitCode: Int, startedAt: Long, workingDirectory: Path) {
        val report = withContext(Dispatchers.IO) {
            ReportLocator.find(expected, startedAt) ?: findFreshReport(workingDirectory, startedAt)
        }
        val outcome = exitDescription(exitCode)
        if (report == null) {
            ReporterNotifications.notify(project, message("run.noReport", outcome), NotificationType.WARNING)
            return
        }
        ReportLocator.awaitComplete(report.file)
        val candidate = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(report.file)
            io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles.candidate(report.file) ?: report
        }
        ReportDiscoveryService.getInstance(project).register(candidate)
        val summary = withContext(Dispatchers.IO) { RunSummary.forReport(candidate.file) }?.describe()?.takeIf(String::isNotEmpty)
        withContext(Dispatchers.EDT) {
            val open = ReporterSettings.getInstance(project).state.openReportAfterRun
            ReportViewerController.getInstance(project).open(candidate.file, activate = open, forceReload = true)
            val type = if (exitCode == 0) NotificationType.INFORMATION else NotificationType.WARNING
            val text = if (summary != null) message("run.finished.summary", outcome, summary) else message("run.finished", outcome)
            ReporterNotifications.notify(
                project, text, type,
                actions = listOf(
                    ReporterNotifications.action(message("run.action.showReport")) {
                        ReportViewerController.getInstance(project).open(candidate.file, activate = true)
                    },
                    ReporterNotifications.action(message("action.openInBrowser.text")) { BrowserUtil.browse(candidate.file) },
                ),
            )
        }
    }

    /** Fallback when the expected location had nothing new: any report under the project written since the run started. */
    private fun findFreshReport(workingDirectory: Path, startedAt: Long): ReportCandidate? {
        val discovery = ReportDiscoveryService.getInstance(project)
        val roots = (listOf(workingDirectory) + discovery.knownLocations()).distinct()
        val settings = ReporterSettings.getInstance(project).state
        val result = ReportScanner(ReportScanner.Limits(maxDepth = settings.discoveryDepth.coerceIn(1, 12)))
            .scan(roots, ReportScanner.parseExcludedNames(settings.excludedDirectories.orEmpty()))
        return result.reports.firstOrNull { ReportLocator.isFresh(it, startedAt) }
    }

    private fun install(interpreter: Path, environment: RunEnvironment, workingDirectory: Path, packages: List<String>, then: suspend () -> Unit) {
        scope.launch(Dispatchers.EDT) {
            val command = environment.applyTo(
                GeneralCommandLine(listOf(interpreter.toString(), "-m", "pip", "install", "--upgrade") + packages)
                    .withWorkingDirectory(workingDirectory),
            )
            val handler = try {
                KillableColoredProcessHandler(command)
            } catch (e: ExecutionException) {
                ReporterNotifications.notify(project, message("install.error.start", e.message.orEmpty()), NotificationType.ERROR)
                return@launch
            }
            ProcessTerminatedListener.attach(handler, project)
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    if (event.exitCode == 0) {
                        scope.launch {
                            preparing.set(true)
                            try {
                                then()
                            } finally {
                                preparing.set(false)
                            }
                        }
                    } else {
                        ReporterNotifications.notify(project, message("install.failed", event.exitCode), NotificationType.ERROR)
                    }
                }
            })
            RunContentExecutor(project, handler)
                .withTitle(message("install.console.title"))
                .withActivateToolWindow(true)
                .run()
        }
    }

    private suspend fun askToInstall(interpreter: Path, probe: ProbeResult, packages: List<String>, upgrade: Boolean): Boolean {
        val commandText = ParametersListUtil.join(listOf(interpreter.toString(), "-m", "pip", "install") + (if (upgrade) listOf("--upgrade") else emptyList()) + packages)
        val missing = packages.joinToString(", ")
        val python = probe.pythonVersion?.let { "Python $it" } ?: interpreter.toString()
        return withContext(Dispatchers.EDT) {
            if (!probe.pipAvailable) {
                Messages.showWarningDialog(project, message("install.noPip", missing, python, interpreter, commandText), message("install.title"))
                return@withContext false
            }
            val choice = Messages.showDialog(
                project,
                message("install.prompt", missing, python, interpreter, commandText),
                message("install.title"),
                arrayOf(message("install.button.install"), message("install.button.copy"), Messages.getCancelButton()),
                0,
                Messages.getWarningIcon(),
            )
            if (choice == 1) CopyPasteManager.getInstance().setContents(StringSelection(commandText))
            choice == 0
        }
    }

    private enum class OldVersionChoice { UPGRADE, RUN_ANYWAY, CANCEL }

    private suspend fun askAboutOldVersion(probe: ProbeResult): OldVersionChoice = withContext(Dispatchers.EDT) {
        val choice = Messages.showDialog(
            project,
            message("version.tooOld", probe.reporterVersion.orEmpty(), ReporterVersion.MINIMUM_SUPPORTED.toString()),
            message("version.tooOld.title"),
            arrayOf(message("version.button.upgrade"), message("version.button.runAnyway"), Messages.getCancelButton()),
            0,
            Messages.getWarningIcon(),
        )
        when (choice) {
            0 -> OldVersionChoice.UPGRADE
            1 -> OldVersionChoice.RUN_ANYWAY
            else -> OldVersionChoice.CANCEL
        }
    }

    private suspend fun confirmAnyway(notes: List<CommandNote>): Boolean = withContext(Dispatchers.EDT) {
        val details = notes.joinToString("\n") { CommandNotes.describe(it) }
        Messages.showOkCancelDialog(
            project, message("run.confirm.blocking", details), message("run.confirm.title"),
            message("version.button.runAnyway"), Messages.getCancelButton(), Messages.getWarningIcon(),
        ) == Messages.OK
    }

    private fun workingDirectory(): Path? {
        val base = project.basePath ?: run {
            ReporterNotifications.notify(project, message("run.error.noProjectDirectory"), NotificationType.ERROR)
            return null
        }
        val configured = ReporterSettings.getInstance(project).state.workingDirectory?.trim().orEmpty()
        val directory = try {
            if (configured.isEmpty()) Path.of(base) else Path.of(base).resolve(configured).normalize()
        } catch (_: InvalidPathException) {
            null
        }
        if (directory == null || !Files.isDirectory(directory)) {
            notifyWithSettings(message("run.error.workingDirectory", configured), NotificationType.ERROR)
            return null
        }
        return directory
    }

    private fun notifyWithSettings(text: String, type: NotificationType) {
        ReporterNotifications.notify(
            project, text, type,
            actions = listOf(ReporterNotifications.action(message("action.settings.text")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ReporterConfigurable::class.java)
            }),
        )
    }

    private fun consoleHeader(
        interpreter: InterpreterResolution.Local,
        probe: ProbeResult,
        build: PytestCommandBuilder.Result,
        command: GeneralCommandLine,
        workingDirectory: Path,
    ): List<String> = buildList {
        add(message("console.interpreter", interpreter.executable, probe.pythonVersion.orEmpty(), interpreter.description))
        add(message("console.versions", probe.pytestVersion.orEmpty(), probe.reporterVersion.orEmpty()))
        add(message("console.workingDirectory", workingDirectory))
        build.notes.forEach { add(CommandNotes.prefix(it) + CommandNotes.describe(it)) }
        add(message("console.expected", CommandNotes.describe(build.expectedReport)))
        add("$ " + command.commandLineString)
        add("")
    }

    override fun dispose() {
        activeProcess.get()?.takeUnless { it.isProcessTerminated }?.destroyProcess()
    }

    companion object {
        private val LOG = logger<PytestRunService>()
        private const val PACKAGE = "pytest-html-reporter"
        private const val MAX_DETAILS = 1500
        private const val STOP_GRACE_MILLIS = 10_000L

        fun getInstance(project: Project): PytestRunService = project.service()

        fun exitDescription(exitCode: Int): String = when (PytestExitCode.of(exitCode)) {
            PytestExitCode.OK -> message("exit.ok")
            PytestExitCode.TESTS_FAILED -> message("exit.failed")
            PytestExitCode.INTERRUPTED -> message("exit.interrupted")
            PytestExitCode.INTERNAL_ERROR -> message("exit.internalError")
            PytestExitCode.USAGE_ERROR -> message("exit.usageError")
            PytestExitCode.NO_TESTS_COLLECTED -> message("exit.noTests")
            null -> message("exit.other", exitCode)
        }
    }
}
