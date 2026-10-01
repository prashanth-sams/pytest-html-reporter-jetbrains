package io.github.prashanthsams.pytesthtmlreporter.platform

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.util.execution.ParametersListUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import com.intellij.testFramework.PlatformTestUtil
import io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles
import io.github.prashanthsams.pytesthtmlreporter.core.ReporterVersion
import io.github.prashanthsams.pytesthtmlreporter.core.RunSummary
import io.github.prashanthsams.pytesthtmlreporter.core.TestData
import io.github.prashanthsams.pytesthtmlreporter.run.PytestRunService
import io.github.prashanthsams.pytesthtmlreporter.run.ReporterProbe
import io.github.prashanthsams.pytesthtmlreporter.run.RunEnvironment
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Runs pytest for real through [PytestRunService] - probe, command, process, completion - against the fixture suite
 * (one failing test, so pytest exits with 1). Needs an interpreter with pytest-html-reporter installed:
 * `PYTEST_HTML_REPORTER_PYTHON=/path/to/venv/bin/python ./gradlew test`. Without it the tests return early.
 */
class PytestRunServiceTest : ReporterTestCase() {

    private val python: String? = System.getenv("PYTEST_HTML_REPORTER_PYTHON")?.takeIf(String::isNotBlank)
    private lateinit var projectDirectory: Path
    private val notifications = CopyOnWriteArrayList<Notification>()

    override fun setUp() {
        super.setUp()
        projectDirectory = Files.createTempDirectory("run proj ü")
        Path.of("scripts", "fixture-project").toFile().copyRecursively(projectDirectory.toFile(), overwrite = true)
        ReporterSettings.getInstance(project).state.apply {
            workingDirectory = projectDirectory.toString()
            outputPath = "out dir ü/my report ü.html"
            pytestArguments = ""
        }
        ReporterWorkspaceState.getInstance(project).state.interpreterPath = python.orEmpty()
        if (!TrustedProjects.isProjectTrusted(project)) TrustedProjects.setProjectTrusted(project, true)
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications += notification
            }
        })
    }

    override fun tearDown() {
        try {
            PytestRunService.getInstance(project).stop()
            projectDirectory.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun skipWithoutPython(): Boolean {
        if (python == null) LOG.warn("PYTEST_HTML_REPORTER_PYTHON is not set; skipping ${name}")
        return python == null
    }

    private fun awaitNotification(text: String) {
        PlatformTestUtil.waitWithEventsDispatching(
            { "no notification containing '$text'; got: ${notifications.map { it.content }}" },
            { notifications.any { it.content.contains(text) } },
            RUN_TIMEOUT_SECONDS,
        )
    }

    fun testFailedRunShowsTheReportItWrote() {
        if (skipWithoutPython()) return
        val expected = projectDirectory.resolve("out dir ü/my report ü.html")
        // A report left by an earlier run must be replaced, never reused.
        TestData.copyReport("0.3.0", expected.parent, expected.fileName.toString())
        val stale = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        Files.setLastModifiedTime(expected, FileTime.fromMillis(stale))

        PytestRunService.getInstance(project).run()
        awaitNotification("pytest finished")

        assertEquals(expected.toString(), ReporterWorkspaceState.getInstance(project).state.selectedReport)
        assertTrue(Files.getLastModifiedTime(expected).toMillis() > stale)
        assertTrue(ReportFiles.isComplete(expected))
        assertEquals("2 passed, 1 failed, 1 error, 1 skipped, 1 xfailed", RunSummary.forReport(expected)?.describe())
        // attach() raises AttributeError in 0.3.0 (fixed in later 0.3.x releases), so only newer ones store the image.
        if (reporterVersion() >= ReporterVersion.parse("0.3.6")!!) {
            Files.list(expected.resolveSibling("pytest_screenshots")).use { assertTrue(it.findAny().isPresent) }
        }
        val finished = notifications.first { it.content.contains("pytest finished") }
        assertTrue(finished.content, finished.content.contains("some tests failed"))
        assertFalse(PytestRunService.getInstance(project).isRunning)
    }

    fun testRunWithoutResultsDoesNotPresentAnOlderReport() {
        if (skipWithoutPython()) return
        val previous = TestData.copyReport("0.4.3", projectDirectory.resolve("out dir ü"), "my report ü.html")
        val stamp = FileTime.fromMillis(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1))
        Files.setLastModifiedTime(previous, stamp)
        ReporterWorkspaceState.getInstance(project).state.selectedReport = "/elsewhere/old.html"
        ReporterSettings.getInstance(project).state.pytestArguments = "-k no_test_matches_this"

        PytestRunService.getInstance(project).run()
        awaitNotification("did not write a new report")

        assertTrue(notifications.any { it.content.contains("no tests were collected") })
        assertEquals("/elsewhere/old.html", ReporterWorkspaceState.getInstance(project).state.selectedReport)
        assertEquals(stamp, Files.getLastModifiedTime(previous))
    }

    fun testSecondRunIsRefusedWhileOneIsActive() {
        if (skipWithoutPython()) return
        val service = PytestRunService.getInstance(project)
        service.run()
        service.run()
        assertTrue(notifications.any { it.content.contains("already running") })
        awaitNotification("pytest finished")
    }

    fun testPassingRunReceivesTheEnvironmentAndReportsSuccess() {
        if (skipWithoutPython()) return
        useTestProject("environment")
        ReporterSettings.getInstance(project).state.apply {
            environmentVariables = mutableMapOf("PHR_TEST_VALUE" to "ü 名 with spaces")
            targets = "tests/test_environment.py"
        }

        PytestRunService.getInstance(project).run()
        awaitNotification("pytest finished")

        val finished = notifications.first { it.content.contains("pytest finished") }
        assertTrue(finished.content, finished.content.contains("all tests passed") && finished.content.contains("1 passed"))
        assertEquals(projectDirectory.resolve("out dir ü/my report ü.html").toString(), ReporterWorkspaceState.getInstance(project).state.selectedReport)
    }

    fun testStopInterruptsPytestAndStillShowsWhatRan() {
        if (skipWithoutPython()) return
        useTestProject("slow")
        ReporterSettings.getInstance(project).state.targets = "tests/test_slow.py"
        val service = PytestRunService.getInstance(project)
        service.run()
        // Give pytest time to finish test_quick and settle into test_slow.
        val started = System.currentTimeMillis()
        PlatformTestUtil.waitWithEventsDispatching("run did not start", { service.isRunning && System.currentTimeMillis() - started > 6_000 }, 30)
        service.stop()
        awaitNotification("pytest finished")

        val finished = notifications.first { it.content.contains("pytest finished") }
        assertTrue(finished.content, finished.content.contains("interrupted") && finished.content.contains("1 passed"))
        assertFalse(service.isRunning)
    }

    fun testStopKillsPytestWhenTheInterruptIsIgnored() {
        if (skipWithoutPython()) return
        useTestProject("slow")
        ReporterSettings.getInstance(project).state.apply {
            targets = "tests/test_slow.py"
            environmentVariables = mutableMapOf("PHR_IGNORE_SIGINT" to "1")
        }
        val service = PytestRunService.getInstance(project)
        service.run()
        val started = System.currentTimeMillis()
        PlatformTestUtil.waitWithEventsDispatching("run did not start", { service.isRunning && System.currentTimeMillis() - started > 6_000 }, 30)
        service.stop()
        // Interrupt ignored -> killed after the grace period; a killed run writes no report.
        awaitNotification("did not write a new report")
        assertFalse(service.isRunning)
        assertTrue("killed well before test_slow's 120 s", System.currentTimeMillis() - started < 60_000)
    }

    fun testMissingReporterOffersTheInstallCommand() {
        val withoutReporter = System.getenv("PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER")?.takeIf(String::isNotBlank)
        if (withoutReporter == null) {
            LOG.warn("PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER is not set; skipping $name")
            return
        }
        ReporterWorkspaceState.getInstance(project).state.interpreterPath = withoutReporter
        val previous = TestDialogManager.setTestDialog(TestDialog { 1 }) // "Copy Command"
        try {
            CopyPasteManager.getInstance().setContents(StringSelection(""))
            PytestRunService.getInstance(project).run()
            PlatformTestUtil.waitWithEventsDispatching("install command not copied", { clipboard().contains("pip install") }, 60)
            assertEquals(ParametersListUtil.join(listOf(withoutReporter, "-m", "pip", "install", "pytest-html-reporter")), clipboard())
            assertFalse(PytestRunService.getInstance(project).isRunning)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }

    private fun clipboard(): String = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty()

    /** Replaces the fixture suite with one of the small projects under testData/projects. */
    private fun useTestProject(name: String) {
        projectDirectory.resolve("tests").toFile().deleteRecursively()
        TestData.root.resolve("projects").resolve(name).toFile().copyRecursively(projectDirectory.toFile(), overwrite = true)
    }

    private fun reporterVersion(): ReporterVersion {
        val probe = runBlocking {
            ReporterProbe.run(Path.of(python!!), projectDirectory, RunEnvironment.create(Path.of(python), emptyMap(), true), null)
        }
        return probe.parsedReporterVersion!!
    }

    private companion object {
        private val LOG = logger<PytestRunServiceTest>()
        private const val RUN_TIMEOUT_SECONDS = 240
    }
}
