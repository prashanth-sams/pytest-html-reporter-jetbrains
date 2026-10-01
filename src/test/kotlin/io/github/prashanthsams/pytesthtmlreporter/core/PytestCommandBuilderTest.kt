package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.time.LocalDateTime

class PytestCommandBuilderTest {

    private val workingDirectory: Path = Path.of("/work/sample proj ü").toAbsolutePath()
    private val python = "/venvs/py env ü/bin/python"
    private val now: LocalDateTime = LocalDateTime.of(2026, 3, 7, 9, 5, 4)

    private val current = ProbeResult.parse(TestData.probe("reporter-0.4.3.json"))
    private val oldest = ProbeResult.parse(TestData.probe("reporter-0.3.0.json"))
    private val configured = ProbeResult.parse(TestData.probe("configured-0.4.3.json"))

    private fun build(
        probe: ProbeResult = current,
        output: String = "report",
        respect: Boolean = true,
        targets: List<String> = emptyList(),
        arguments: List<String> = emptyList(),
        environment: Map<String, String> = emptyMap(),
    ) = PytestCommandBuilder().build(
        PytestCommandBuilder.Request(python, workingDirectory, targets, arguments, output, respect, environment, now),
        probe,
    )

    @Test
    fun `builds the documented command with the default output`() {
        val result = build(targets = listOf("tests"), arguments = listOf("-m", "not slow"))
        assertEquals(
            listOf(python, "-m", "pytest", "tests", "-m", "not slow", "--html-report=report", "--report-open=none"),
            result.command,
        )
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("report/pytest_html_report.html")), result.expectedReport)
        assertTrue(result.notes.contains(CommandNote.ReportPathFromSettings("report")))
    }

    @Test
    fun `keeps spaces and unicode inside single arguments`() {
        val result = build(output = "out dir ü/my report ü.html", targets = listOf("tests/test ü.py::test_名前"))
        assertTrue(result.command.contains("--html-report=out dir ü/my report ü.html"))
        assertTrue(result.command.contains("tests/test ü.py::test_名前"))
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("out dir ü/my report ü.html")), result.expectedReport)
    }

    @Test
    fun `respects a report path the user passed`() {
        val result = build(arguments = listOf("--html-report", "mine"))
        assertFalse(result.command.any { it.startsWith("--html-report=") })
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("mine/pytest_html_report.html")), result.expectedReport)
        assertTrue(result.notes.contains(CommandNote.ReportPathFromArguments("mine")))
    }

    @Test
    fun `keeps the path from pytest configuration, addopts first`() {
        val result = build(probe = configured)
        assertFalse(result.command.any { it.startsWith("--html-report") })
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("from-addopts/pytest_html_report.html")), result.expectedReport)
        assertTrue(result.notes.any { it is CommandNote.ReportPathFromConfiguration && it.value == "from-addopts" })
    }

    @Test
    fun `finds configured placeholder paths by pattern`() {
        val probe = configured.copy(addoptsReportPath = null)
        val expected = build(probe = probe).expectedReport as ExpectedReport.Matching
        assertEquals(workingDirectory.resolve("reports"), expected.root)
        assertTrue(expected.pattern.matches(workingDirectory.resolve("reports/20260307/run.html").toString()))
    }

    @Test
    fun `keeps an ini-configured path for releases between 0_3_4 and 0_4_2`() {
        val probe = ProbeResult.parse(TestData.probe("configured-0.3.6.json")).copy(addoptsReportPath = null)
        val result = build(probe = probe)
        assertFalse(result.command.any { it.startsWith("--html-report") })
        assertFalse("0.3.6 has no --report-open", result.command.any { it.startsWith("--report-open") })
        assertTrue(result.notes.any { it is CommandNote.ReportPathFromConfiguration && it.value == "reports/%Y%m%d/run.html" })
        assertTrue(result.expectedReport is ExpectedReport.Matching)
    }

    @Test
    fun `overrides configuration only when asked to`() {
        val result = build(probe = configured, respect = false, output = "ide-report")
        assertTrue(result.command.contains("--html-report=ide-report"))
        assertTrue(result.notes.any { it is CommandNote.ReportPathOverridesConfiguration })
    }

    @Test
    fun `leaves the location to the reporter when the output setting is blank`() {
        val result = build(output = "")
        assertFalse(result.command.any { it.startsWith("--html-report") })
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("pytest_html_report.html")), result.expectedReport)
        assertTrue(result.notes.contains(CommandNote.ReportPathDefault))
    }

    @Test
    fun `expands time placeholders once and escapes the result`() {
        val result = build(output = "reports/%Y%m%d/100% run.html")
        assertTrue(result.command.contains("--html-report=reports/20260307/100%% run.html"))
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("reports/20260307/100% run.html")), result.expectedReport)
    }

    @Test
    fun `passes placeholders verbatim to releases that do not expand them`() {
        val result = build(probe = oldest, output = "reports/%Y")
        assertTrue(result.command.contains("--html-report=reports/%Y"))
        assertEquals(ExpectedReport.Exact(workingDirectory.resolve("reports/%Y/pytest_html_report.html")), result.expectedReport)
    }

    @Test
    fun `gates --report-open on what the installed reporter registers`() {
        assertTrue(build().command.contains("--report-open=none"))
        val old = build(probe = oldest)
        assertFalse(old.command.any { it.startsWith("--report-open") })
        assertTrue(old.notes.contains(CommandNote.ReportOpenUnsupported))
        val userChoice = build(arguments = listOf("--report-open=always"))
        assertFalse(userChoice.command.contains("--report-open=none"))
        assertEquals(CommandNote.Severity.WARNING, userChoice.notes.first { it is CommandNote.ReportOpenFromArguments }.severity)
    }

    @Test
    fun `inserts reporter options before a bare double dash`() {
        val result = build(arguments = listOf("-q", "--", "tests/odd name.py"))
        assertEquals(
            listOf(python, "-m", "pytest", "-q", "--html-report=report", "--report-open=none", "--", "tests/odd name.py"),
            result.command,
        )
    }

    @Test
    fun `passes paths outside the working directory as absolute, with forward slashes`() {
        val outside = workingDirectory.parent.resolve("shared reports").toAbsolutePath()
        val result = build(output = outside.toString())
        assertTrue(result.command.contains("--html-report=" + outside.toString().replace('\\', '/')))
        assertEquals(ExpectedReport.Exact(outside.resolve("pytest_html_report.html")), result.expectedReport)
    }

    @Test
    fun `uses a relative value so a parent folder named like html does not confuse the reporter`() {
        val tricky = Path.of("/work/site.html.d/project").toAbsolutePath()
        val result = PytestCommandBuilder().build(
            PytestCommandBuilder.Request(python, tricky, emptyList(), emptyList(), "report", true, emptyMap(), now),
            current,
        )
        assertTrue(result.command.contains("--html-report=report"))
        assertEquals(ExpectedReport.Exact(tricky.resolve("report/pytest_html_report.html")), result.expectedReport)
    }

    @Test
    fun `flags arguments that prevent a report`() {
        val result = build(arguments = listOf("-p", "no:reporter", "--co", "--report-shard=1/2", "--html=x.html"))
        assertTrue(result.notes.any { it is CommandNote.ReporterDisabled && it.severity == CommandNote.Severity.ERROR })
        assertTrue(result.notes.any { it is CommandNote.NoReportForCollectOnly })
        assertTrue(result.notes.any { it is CommandNote.ShardWithoutMerge })
        assertTrue(result.notes.any { it is CommandNote.PytestHtmlOption })
    }

    @Test
    fun `warns when plugin autoloading is disabled`() {
        val disabled = build(environment = mapOf(PytestCommandBuilder.AUTOLOAD_DISABLED to "1"))
        assertTrue(disabled.notes.contains(CommandNote.ReporterAutoloadDisabled))
        val loaded = build(environment = mapOf(PytestCommandBuilder.AUTOLOAD_DISABLED to "1"), arguments = listOf("-p", "pytest_html_reporter.plugin"))
        assertFalse(loaded.notes.contains(CommandNote.ReporterAutoloadDisabled))
    }
}
