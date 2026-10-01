package io.github.prashanthsams.pytesthtmlreporter.viewer

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.jcef.JBCefApp

/**
 * Whether the embedded browser can be used. Calls into JCEF are kept behind this check and inside
 * [JcefReportBrowser], so the plugin still loads - and falls back to the system browser - when the IDE runs on a
 * runtime without JCEF, or (2026.2+) when the separate JCEF plugin is missing or disabled.
 */
internal object JcefSupport {
    private val LOG = logger<JcefSupport>()

    @Volatile
    private var cached: Boolean? = null

    fun isSupported(): Boolean = cached ?: probe().also { cached = it }

    private fun probe(): Boolean = try {
        JBCefApp.isSupported()
    } catch (e: LinkageError) {
        // NoClassDefFoundError when the JCEF module is not on the plugin's classpath.
        LOG.info("JCEF classes are unavailable: ${e.message}")
        false
    } catch (e: Exception) {
        LOG.warn("JCEF support check failed", e)
        false
    }
}
