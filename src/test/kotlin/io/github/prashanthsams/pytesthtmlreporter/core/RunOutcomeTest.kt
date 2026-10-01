package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class RunOutcomeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `summarises every outcome of the fixture suite`() {
        for (version in listOf("0.4.3", "0.3.0")) {
            val summary = RunSummary.forReport(TestData.report(version).resolve(ReportPaths.DEFAULT_FILE_NAME))!!
            assertEquals(version, "2 passed, 1 failed, 1 error, 1 skipped, 1 xfailed", summary.describe())
            assertEquals(6, summary.total)
            assertEquals("FAIL", summary.status)
        }
    }

    @Test
    fun `ignores missing and unrelated output json`() {
        val folder = temporary.newFolder().toPath()
        assertNull(RunSummary.forReport(folder.resolve("pytest_html_report.html")))
        Files.writeString(folder.resolve("output.json"), """{"name": "some other tool"}""")
        assertNull(RunSummary.forReport(folder.resolve("pytest_html_report.html")))
        Files.writeString(folder.resolve("output.json"), "{ not json")
        assertNull(RunSummary.forReport(folder.resolve("pytest_html_report.html")))
    }

    @Test
    fun `maps pytest exit codes`() {
        assertEquals(PytestExitCode.TESTS_FAILED, PytestExitCode.of(1))
        assertEquals(PytestExitCode.NO_TESTS_COLLECTED, PytestExitCode.of(5))
        assertNull(PytestExitCode.of(137))
    }
}
