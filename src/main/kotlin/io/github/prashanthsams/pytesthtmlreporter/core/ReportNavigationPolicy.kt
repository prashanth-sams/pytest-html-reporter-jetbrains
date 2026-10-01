package io.github.prashanthsams.pytesthtmlreporter.core

import java.net.URI
import java.nio.file.Path

/**
 * What the embedded viewer may load and where links go. The report is treated as untrusted content:
 *
 * - the report document itself may be reloaded and navigated by fragment (its tabs and deep links are `#hash` routes);
 * - its subresources may come from its own folder (`pytest_screenshots/…`), `data:` or `blob:` URLs; remote
 *   subresources are refused unless explicitly allowed - a pytest-html-reporter report inlines everything it needs;
 * - `http(s)` and `mailto` links open in the system browser;
 * - links to other files open outside the viewer, and only when they are inside the report folder or the project;
 * - everything else (other local files, `javascript:`, unknown schemes) is blocked.
 */
class ReportNavigationPolicy(
    reportFile: Path,
    projectRoot: Path?,
    private val blockRemoteContent: Boolean,
) {
    enum class Action { ALLOW, OPEN_IN_BROWSER, OPEN_LOCAL_FILE, BLOCK }

    data class Decision(val action: Action, val localFile: Path? = null)

    private val report: Path = reportFile.toAbsolutePath().normalize()
    private val reportDirectory: Path = report.parent ?: report
    private val project: Path? = projectRoot?.toAbsolutePath()?.normalize()

    /** A navigation of the viewer's own frame. */
    fun navigation(url: String): Decision {
        val uri = parse(url) ?: return BLOCKED
        return when (uri.scheme?.lowercase()) {
            "file" -> classifyLocal(uri)
            "http", "https", "mailto" -> Decision(Action.OPEN_IN_BROWSER)
            "about" -> if (url.equals("about:blank", ignoreCase = true)) Decision(Action.ALLOW) else BLOCKED
            else -> BLOCKED
        }
    }

    /** A link asking for a new window (`target="_blank"`); the viewer never opens browser windows of its own. */
    fun popup(url: String): Decision {
        if (url.isBlank() || url.equals("about:blank", ignoreCase = true)) return BLOCKED
        return navigation(url)
    }

    /** Whether a subresource (image, script, frame…) may be fetched. */
    fun allowsResource(url: String): Boolean {
        val uri = parse(url) ?: return false
        return when (uri.scheme?.lowercase()) {
            "file" -> localPath(uri)?.let { isInside(it, reportDirectory) } ?: false
            "data", "blob", "about", "devtools" -> true
            "http", "https", "ws", "wss" -> !blockRemoteContent
            else -> false
        }
    }

    /** Whether [url] is the report document itself, ignoring fragment and query. */
    fun isReportDocument(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("file", ignoreCase = true) && localPath(uri) == report
    }

    private fun classifyLocal(uri: URI): Decision {
        val path = localPath(uri) ?: return BLOCKED
        return when {
            path == report -> Decision(Action.ALLOW)
            isInside(path, reportDirectory) -> Decision(Action.OPEN_LOCAL_FILE, path)
            project != null && isInside(path, project) -> Decision(Action.OPEN_LOCAL_FILE, path)
            else -> BLOCKED
        }
    }

    private fun isInside(path: Path, directory: Path): Boolean = path.startsWith(directory)

    companion object {
        private val BLOCKED = Decision(Action.BLOCK)

        private fun parse(url: String): URI? = try {
            URI(url.trim())
        } catch (_: Exception) {
            null
        }

        /** The local path a `file:` URI names, without its query and fragment. */
        fun localPath(uri: URI): Path? = try {
            if (!uri.scheme.equals("file", ignoreCase = true)) null
            else Path.of(URI("file", uri.authority?.takeIf { it.isNotEmpty() }, uri.path, null, null))
                .toAbsolutePath().normalize()
        } catch (_: Exception) {
            null
        }

        /** The `file:` URL to load for [file]: percent-encoded, so spaces and non-ASCII names survive. */
        fun fileUrl(file: Path): String = file.toAbsolutePath().normalize().toUri().toASCIIString()
    }
}
