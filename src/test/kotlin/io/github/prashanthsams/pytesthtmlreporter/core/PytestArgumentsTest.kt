package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PytestArgumentsTest {

    private fun args(vararg tokens: String) = PytestArguments(tokens.toList())

    @Test
    fun `reads both option spellings and keeps the last one`() {
        assertEquals("a", args("--html-report=a").reportPath)
        assertEquals("b dir/x.html", args("--html-report", "b dir/x.html").reportPath)
        assertEquals("second", args("--html-report=first", "-q", "--html-report", "second").reportPath)
        assertEquals("none", args("--report-open=none").reportOpen)
        assertEquals("ci", args("--report-profile", "ci").reportProfile)
        assertNull(args("-q").reportPath)
    }

    @Test
    fun `ignores everything after a bare double dash`() {
        assertNull(args("--", "--html-report=x").reportPath)
        assertNull(args("tests", "--", "--collect-only").collectOnlyFlag)
    }

    @Test
    fun `does not confuse similarly named options`() {
        assertNull(args("--html-reporter=x").reportPath)
        assertNull(args("--html=x").reportPath)
        assertEquals("--html=x", args("--html=x").pytestHtmlFlag)
    }

    @Test
    fun `detects the reporter being disabled in every -p spelling`() {
        assertEquals("-p no:reporter", args("-p", "no:reporter").reporterDisabledBy)
        assertEquals("-p no:reporter", args("-pno:reporter").reporterDisabledBy)
        assertEquals("-p no:pytest_html_reporter.plugin", args("-p", "no:pytest_html_reporter.plugin").reporterDisabledBy)
        assertNull(args("-p", "no:cacheprovider").reporterDisabledBy)
        assertTrue(args("-p", "pytest_html_reporter.plugin").loadsReporterExplicitly)
        assertFalse(args("-p", "xdist").loadsReporterExplicitly)
    }

    @Test
    fun `detects runs that write no report`() {
        assertEquals("--co", args("--co").collectOnlyFlag)
        assertEquals("--collect-only", args("-q", "--collect-only").collectOnlyFlag)
        assertEquals("--report-shard", args("--report-shard=1/4").shardWithoutMerge)
        assertNull(args("--report-shard=1/4", "--report-shard-merge").shardWithoutMerge)
    }
}
