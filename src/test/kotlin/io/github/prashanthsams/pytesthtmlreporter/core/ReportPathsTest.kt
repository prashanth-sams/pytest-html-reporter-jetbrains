package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class ReportPathsTest {

    private val workingDirectory: Path = Path.of("/work/project dir ü").toAbsolutePath()

    /** Every case was computed by pytest-html-reporter 0.4.3's own util.expand_time and HTMLReporter.report_path. */
    @Test
    fun `matches upstream for every recorded case`() {
        val document = MiniJson.parse(Files.readString(TestData.root.resolve("upstream/report_paths.json"))) as Map<*, *>
        val frozen = LocalDateTime.parse(document.stringValue("frozenTime"))
        val cases = document.listValue("cases")!!.map { it as Map<*, *> }
        assertTrue(cases.size >= 15)
        for (case in cases) {
            val input = case.stringValue("input")!!
            val expanded = ReportPaths.expandTime(input.ifEmpty { "." }, frozen)
            assertEquals("expansion of '$input'", case.stringValue("expanded"), expanded)

            val file = ReportPaths.resolve(expanded, workingDirectory, emptyMap())
            val folder = workingDirectory.relativize(file.parent).toString().replace('\\', '/').ifEmpty { "." }
            assertEquals("folder of '$input'", case.stringValue("folder"), folder)
            assertEquals("file of '$input'", case.stringValue("file"), file.fileName.toString())
        }
    }

    @Test
    fun `defaults to pytest_html_report html in the working directory`() {
        assertEquals(workingDirectory.resolve("pytest_html_report.html"), ReportPaths.resolve("", workingDirectory, emptyMap()))
        assertEquals(workingDirectory.resolve("report/pytest_html_report.html"), ReportPaths.resolve("report", workingDirectory, emptyMap()))
    }

    @Test
    fun `keeps absolute paths absolute`() {
        val absolute = Path.of("/tmp/elsewhere").toAbsolutePath()
        assertEquals(absolute.resolve("r.html"), ReportPaths.resolve("${absolute.toString().replace('\\', '/')}/r.html", workingDirectory, emptyMap()))
    }

    @Test
    fun `expands environment variables and the home folder like os path expandvars and expanduser`() {
        val env = mapOf("OUT" to "build-out", "DAY" to "monday")
        assertEquals(workingDirectory.resolve("build-out/monday/r.html"), ReportPaths.resolve("\$OUT/\${DAY}/r.html", workingDirectory, env))
        assertEquals(workingDirectory.resolve("\$MISSING/pytest_html_report.html"), ReportPaths.resolve("\$MISSING", workingDirectory, emptyMap()))
        assertEquals(Path.of(System.getProperty("user.home")).resolve("reports/pytest_html_report.html"), ReportPaths.resolve("~/reports", workingDirectory, emptyMap()))
    }

    @Test
    fun `detects and escapes time placeholders`() {
        assertTrue(ReportPaths.hasTimeDirectives("reports/%Y%m%d"))
        assertFalse(ReportPaths.hasTimeDirectives("100% pass"))
        assertFalse(ReportPaths.hasTimeDirectives("%%Y"))
        assertFalse(ReportPaths.hasTimeDirectives("%q"))
        assertEquals("100%% pass", ReportPaths.escapePercent("100% pass"))
        // Escaping an expanded value makes upstream's expansion a no-op.
        val now = LocalDateTime.of(2026, 1, 2, 3, 4, 5)
        assertEquals("100% pass", ReportPaths.expandTime(ReportPaths.escapePercent("100% pass"), now))
    }

    @Test
    fun `builds a pattern and static root for configured placeholder paths`() {
        val pattern = ReportPaths.patternFor("reports/%Y%m%d/report_%H%M.html", workingDirectory, emptyMap())
        val root = ReportPaths.staticRootFor("reports/%Y%m%d/report_%H%M.html", workingDirectory, emptyMap())
        assertEquals(workingDirectory.resolve("reports"), root)
        assertTrue(pattern.matches(workingDirectory.resolve("reports/20260307/report_0905.html").toString()))
        assertFalse(pattern.matches(workingDirectory.resolve("reports/20260307/other.html").toString()))
        assertFalse(pattern.matches(workingDirectory.resolve("reports/2026/03/report_0905.html").toString()))

        val folderPattern = ReportPaths.patternFor("reports/%Y-%m-%d", workingDirectory, emptyMap())
        assertTrue(folderPattern.matches(workingDirectory.resolve("reports/2026-03-07/pytest_html_report.html").toString()))
        assertEquals(workingDirectory, ReportPaths.staticRootFor("%Y/r.html", workingDirectory, emptyMap()))
    }
}
