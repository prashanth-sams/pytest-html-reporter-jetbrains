package io.github.prashanthsams.pytesthtmlreporter

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object PytestHtmlReporterIcons {
    /** 13x13 classic-UI tool window icon; the new UI uses `icons/expui/toolWindow.svg` via the icon mapping file. */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/toolWindow.svg", PytestHtmlReporterIcons::class.java)

    @JvmField
    val Reporter: Icon = IconLoader.getIcon("/icons/reporter.svg", PytestHtmlReporterIcons::class.java)
}
