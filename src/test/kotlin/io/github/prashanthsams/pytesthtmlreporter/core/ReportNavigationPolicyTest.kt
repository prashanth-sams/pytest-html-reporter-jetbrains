package io.github.prashanthsams.pytesthtmlreporter.core

import io.github.prashanthsams.pytesthtmlreporter.core.ReportNavigationPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class ReportNavigationPolicyTest {

    private val project: Path = Path.of("/work/sample proj ü").toAbsolutePath()
    private val report: Path = project.resolve("out dir ü/my report ü.html")
    private val policy = ReportNavigationPolicy(report, project, blockRemoteContent = true)
    private val reportUrl = ReportNavigationPolicy.fileUrl(report)

    @Test
    fun `encodes spaces and unicode in the report url`() {
        assertTrue(reportUrl.startsWith("file:"))
        assertTrue(reportUrl.contains("%20"))
        assertFalse(reportUrl.contains(" "))
        assertTrue(reportUrl.all { it.code < 128 })
        assertTrue(policy.isReportDocument(reportUrl))
    }

    @Test
    fun `allows the report and its hash routes`() {
        assertEquals(Action.ALLOW, policy.navigation(reportUrl).action)
        assertEquals(Action.ALLOW, policy.navigation("$reportUrl#test-metrics?status=FAIL").action)
        assertTrue(policy.isReportDocument("$reportUrl#dashboard"))
    }

    @Test
    fun `sends web links to the system browser`() {
        assertEquals(Action.OPEN_IN_BROWSER, policy.navigation("https://github.com/prashanth-sams/pytest-html-reporter").action)
        assertEquals(Action.OPEN_IN_BROWSER, policy.popup("http://ci.example.com/job/42").action)
        assertEquals(Action.OPEN_IN_BROWSER, policy.navigation("mailto:team@example.com").action)
    }

    @Test
    fun `opens files beside the report or in the project outside the viewer`() {
        val screenshot = report.resolveSibling("pytest_screenshots/1.png")
        val coverage = project.resolve("htmlcov/index.html")
        assertEquals(Action.OPEN_LOCAL_FILE, policy.navigation(ReportNavigationPolicy.fileUrl(screenshot)).action)
        assertEquals(screenshot, policy.navigation(ReportNavigationPolicy.fileUrl(screenshot)).localFile)
        assertEquals(Action.OPEN_LOCAL_FILE, policy.popup(ReportNavigationPolicy.fileUrl(coverage)).action)
    }

    @Test
    fun `blocks everything else`() {
        assertEquals(Action.BLOCK, policy.navigation("file:///etc/passwd").action)
        assertEquals(Action.BLOCK, policy.navigation("javascript:alert(1)").action)
        assertEquals(Action.BLOCK, policy.navigation("data:text/html,<script>1</script>").action)
        assertEquals(Action.BLOCK, policy.navigation("chrome://settings").action)
        assertEquals(Action.BLOCK, policy.popup("about:blank").action)
        assertEquals(Action.BLOCK, policy.popup("").action)
        assertEquals(Action.BLOCK, policy.navigation("not a url").action)
    }

    @Test
    fun `restricts subresources to the report folder`() {
        assertTrue(policy.allowsResource(reportUrl))
        assertTrue(policy.allowsResource(ReportNavigationPolicy.fileUrl(report.resolveSibling("pytest_screenshots/1.png"))))
        assertTrue(policy.allowsResource("data:image/png;base64,AAAA"))
        assertTrue(policy.allowsResource("blob:file:///1234"))
        assertFalse(policy.allowsResource(ReportNavigationPolicy.fileUrl(project.resolve("secrets.txt"))))
        assertFalse(policy.allowsResource("file:///etc/passwd"))
        assertFalse(policy.allowsResource("https://cdn.example.com/x.js"))
        assertFalse(policy.allowsResource("ftp://example.com/x"))
    }

    @Test
    fun `can allow remote subresources when asked`() {
        val open = ReportNavigationPolicy(report, project, blockRemoteContent = false)
        assertTrue(open.allowsResource("https://cdn.example.com/x.js"))
        assertFalse(open.allowsResource("file:///etc/passwd"))
    }
}
