package io.github.prashanthsams.pytesthtmlreporter.platform

import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState

/**
 * Light test projects are shared between test classes, so every test starts from default settings and leaves no Run
 * tool window content (consoles) behind.
 */
abstract class ReporterTestCase : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        resetSettings()
    }

    override fun tearDown() {
        try {
            RunContentManager.getInstance(project).allDescriptors.forEach(Disposer::dispose)
            // Headless, RunContentExecutor has no Run tool window to hand its console to, so nothing disposes it.
            EditorFactory.getInstance().allEditors.filterNot { it.isDisposed }.forEach(EditorFactory.getInstance()::releaseEditor)
            resetSettings()
        } finally {
            super.tearDown()
        }
    }

    private fun resetSettings() {
        ReporterSettings.getInstance(project).loadState(ReporterSettings.SettingsState())
        ReporterWorkspaceState.getInstance(project).loadState(ReporterWorkspaceState.WorkspaceState())
    }
}
