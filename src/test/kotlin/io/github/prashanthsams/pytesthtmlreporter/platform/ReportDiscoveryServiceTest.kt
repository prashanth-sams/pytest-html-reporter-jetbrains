package io.github.prashanthsams.pytesthtmlreporter.platform

import com.intellij.testFramework.PlatformTestUtil
import io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles
import io.github.prashanthsams.pytesthtmlreporter.core.TestData
import io.github.prashanthsams.pytesthtmlreporter.discovery.DiscoveryState
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryListener
import io.github.prashanthsams.pytesthtmlreporter.discovery.ReportDiscoveryService
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class ReportDiscoveryServiceTest : ReporterTestCase() {

    private lateinit var location: Path

    override fun setUp() {
        super.setUp()
        location = Files.createTempDirectory("reports dir ü")
        ReporterSettings.getInstance(project).state.additionalLocations = location.toString()
    }

    override fun tearDown() {
        try {
            location.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun awaitScan(service: ReportDiscoveryService) {
        PlatformTestUtil.waitWithEventsDispatching("report scan did not finish", { !service.state.scanning && service.state.scannedAtMillis != null }, 30)
    }

    fun testScanFindsReportsAndPublishesState() {
        val report = TestData.copyReport("0.4.3", location.resolve("nested/results"), "custom name.html")
        val events = CopyOnWriteArrayList<DiscoveryState>()
        project.messageBus.connect(testRootDisposable).subscribe(ReportDiscoveryListener.TOPIC, ReportDiscoveryListener { events += it })

        val service = ReportDiscoveryService.getInstance(project)
        service.rescan()
        awaitScan(service)

        assertTrue("$report not in ${service.state}", service.state.reports.any { it.file == report })
        assertTrue("a scanning state and a finished state are published", events.size >= 2)
        assertTrue(events.first().scanning)
        assertFalse(events.last().scanning)
    }

    fun testRegisterAndRefreshKeepTheListCurrent() {
        val service = ReportDiscoveryService.getInstance(project)
        service.rescan()
        awaitScan(service)

        val report = TestData.copyReport("0.3.0", location.resolve("later"))
        service.register(ReportFiles.candidate(report)!!)
        assertEquals(report, service.state.reports.first().file)

        Files.delete(report)
        service.refreshDirectories(listOf(report.parent))
        PlatformTestUtil.waitWithEventsDispatching("deleted report still listed", { service.state.reports.none { it.file == report } }, 10)
    }

    fun testKnownLocationsFollowTheOutputSetting() {
        val settings = ReporterSettings.getInstance(project).state
        settings.outputPath = "custom-out/r.html"
        val locations = ReportDiscoveryService.getInstance(project).knownLocations()
        val base = Path.of(project.basePath!!)
        assertEquals(base.resolve("custom-out"), locations.first())
        assertTrue(locations.contains(base.resolve("report")))
    }
}
