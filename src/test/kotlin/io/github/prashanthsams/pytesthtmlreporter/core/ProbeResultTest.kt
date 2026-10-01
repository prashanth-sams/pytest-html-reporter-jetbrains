package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses probe output recorded from real interpreters (scripts/generate-fixtures.sh). */
class ProbeResultTest {

    @Test
    fun `reads a current reporter and its flags`() {
        val probe = ProbeResult.parse(TestData.probe("reporter-0.4.3.json"))
        assertEquals("0.4.3", probe.reporterVersion)
        assertTrue(probe.hasPytest)
        assertTrue(probe.meetsMinimumVersion)
        assertTrue(probe.supportsOption(ProbeResult.REPORT_OPEN))
        assertTrue(probe.supportsOption(ProbeResult.REPORT_PROFILE))
        assertTrue(probe.expandsTimePlaceholders)
        assertNull(probe.configuredPath)
        assertNull(probe.addoptsReportPath)
    }

    @Test
    fun `reads an old reporter without --report-open`() {
        val probe = ProbeResult.parse(TestData.probe("reporter-0.3.0.json"))
        assertEquals("0.3.0", probe.reporterVersion)
        assertTrue(probe.meetsMinimumVersion)
        assertFalse(probe.supportsOption(ProbeResult.REPORT_OPEN))
        assertTrue(probe.supportsOption(ProbeResult.HTML_REPORT))
        assertFalse(probe.expandsTimePlaceholders)
    }

    @Test
    fun `reports a missing reporter`() {
        val probe = ProbeResult.parse(TestData.probe("missing-reporter.json"))
        assertTrue(probe.hasPytest)
        assertFalse(probe.hasReporter)
        assertFalse(probe.meetsMinimumVersion)
        assertTrue(probe.pipAvailable)
    }

    @Test
    fun `reads the reporter's resolved configuration and addopts`() {
        val probe = ProbeResult.parse(TestData.probe("configured-0.4.3.json"))
        assertEquals("reports/%Y%m%d/run.html", probe.configuredPath?.value)
        assertEquals("the html_report ini key", probe.configuredPath?.source)
        assertEquals("from-addopts", probe.addoptsReportPath?.value)
        assertTrue(probe.addoptsReportPath!!.source.startsWith("addopts in "))
    }

    @Test
    fun `resolves a named profile`() {
        val probe = ProbeResult.parse(TestData.probe("configured-profile-ci-0.4.3.json"))
        assertEquals("ci-out", probe.configuredPath?.value)
        assertTrue(probe.configuredPath!!.source.startsWith("profile 'ci'"))
    }

    @Test
    fun `reads the html_report ini key for releases without the config command`() {
        val probe = ProbeResult.parse(TestData.probe("configured-0.3.6.json"))
        assertEquals("0.3.6", probe.reporterVersion)
        assertEquals("reports/%Y%m%d/run.html", probe.configuredPath?.value)
        assertTrue(probe.configuredPath!!.source.startsWith("the html_report ini key in "))
        assertEquals("from-addopts", probe.addoptsReportPath?.value)
    }

    @Test
    fun `ignores the html_report ini key for releases that do not read it`() {
        val probe = ProbeResult.parse(TestData.probe("configured-0.3.0.json"))
        assertNull(probe.configuredPath)
        assertEquals("from-addopts", probe.addoptsReportPath?.value)
    }

    @Test
    fun `falls back to version thresholds when flags could not be read`() {
        val probe = ProbeResult.parse("""{"pytestVersion": "8.0.0", "reporterVersion": "0.3.7"}""")
        assertNull(probe.reporterOptions)
        assertTrue(probe.supportsOption(ProbeResult.REPORT_OPEN))
        assertFalse(probe.supportsOption(ProbeResult.REPORT_PROFILE))
    }
}
