package io.github.prashanthsams.pytesthtmlreporter.ui

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.project.Project

/**
 * Project trust gate. In an untrusted project the plugin neither runs Python (probe, pip, pytest) nor loads a report's
 * JavaScript automatically; reading files to discover reports is still allowed.
 */
internal object ProjectTrust {
    fun isTrusted(project: Project): Boolean = TrustedProjects.isProjectTrusted(project)
}
