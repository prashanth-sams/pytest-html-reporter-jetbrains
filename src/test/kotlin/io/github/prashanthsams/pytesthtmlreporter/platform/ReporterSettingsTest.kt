package io.github.prashanthsams.pytesthtmlreporter.platform

import com.intellij.openapi.components.State
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.util.xmlb.XmlSerializer
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState

class ReporterSettingsTest : ReporterTestCase() {

    fun testDefaults() {
        val state = ReporterSettings.SettingsState()
        assertEquals("report", state.outputPath)
        assertTrue(state.respectPytestConfiguration)
        assertTrue(state.openReportAfterRun)
        assertTrue(state.autoRefresh)
        assertTrue(state.blockRemoteContent)
        assertFalse(state.refreshAfterRunConfigurations)
        assertEquals(6, state.discoveryDepth)
        assertEquals(ReportScanner.DEFAULT_EXCLUDED_NAMES, ReportScanner.parseExcludedNames(state.excludedDirectories.orEmpty()))
    }

    fun testSharedSettingsRoundTripThroughXml() {
        val state = ReporterSettings.getInstance(project).state
        state.outputPath = "out dir ü/my report ü.html"
        state.respectPytestConfiguration = false
        state.targets = "tests/unit \"tests/with space\""
        state.pytestArguments = "-m \"not slow\" -k login"
        state.workingDirectory = "backend"
        state.environmentVariables = mutableMapOf("API_URL" to "http://localhost:8000", "UNICODE" to "ü名")
        state.passParentEnvironment = false
        state.autoRefresh = false
        state.refreshAfterRunConfigurations = true
        state.discoveryDepth = 3
        state.additionalLocations = "../shared-reports"

        val element = XmlSerializer.serialize(state)
        val restored = ReporterSettings()
        restored.loadState(XmlSerializer.deserialize(element, ReporterSettings.SettingsState::class.java))

        assertEquals(state, restored.state)
        assertEquals("out dir ü/my report ü.html", restored.state.outputPath)
        assertEquals(mapOf("API_URL" to "http://localhost:8000", "UNICODE" to "ü名"), restored.state.environmentVariables)
        assertFalse(restored.state.passParentEnvironment)
        assertEquals(3, restored.state.discoveryDepth)
    }

    fun testOnlyChangedValuesAreWritten() {
        val element = XmlSerializer.serialize(ReporterSettings.SettingsState())
        assertTrue("defaults should not be serialised: ${element.children.map { it.getAttributeValue("name") }}", element.children.isEmpty())
    }

    fun testWorkspaceStateRoundTripAndStorage() {
        val state = ReporterWorkspaceState.getInstance(project).state
        state.interpreterPath = "/venvs/py env ü/bin/python"
        state.selectedReport = "/work/project/report/pytest_html_report.html"
        val restored = ReporterWorkspaceState()
        restored.loadState(XmlSerializer.deserialize(XmlSerializer.serialize(state), ReporterWorkspaceState.WorkspaceState::class.java))
        assertEquals(state, restored.state)

        // Machine-specific values stay out of version control; shared settings go to .idea/pytestHtmlReporter.xml.
        val workspaceStorage = ReporterWorkspaceState::class.java.getAnnotation(State::class.java).storages.single().value
        assertEquals(StoragePathMacros.WORKSPACE_FILE, workspaceStorage)
        val sharedStorage = ReporterSettings::class.java.getAnnotation(State::class.java).storages.single().value
        assertEquals("pytestHtmlReporter.xml", sharedStorage)
    }

    fun testServicesAreProjectScoped() {
        assertSame(ReporterSettings.getInstance(project), ReporterSettings.getInstance(project))
        assertSame(ReporterWorkspaceState.getInstance(project), ReporterWorkspaceState.getInstance(project))
    }
}
