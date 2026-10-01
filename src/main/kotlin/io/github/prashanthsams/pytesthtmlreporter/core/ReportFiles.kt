package io.github.prashanthsams.pytesthtmlreporter.core

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A report found on disk. */
data class ReportCandidate(
    val file: Path,
    val lastModifiedMillis: Long,
    val sizeBytes: Long,
    /** `output.json` beside it - present for every pytest-html-reporter run since 0.3.0. */
    val hasOutputJson: Boolean,
)

/** Recognising pytest-html-reporter output without parsing whole files. */
object ReportFiles {
    /** Bytes read from the start of an HTML file; the title sits within the first 3 KB in 0.3.0 through 0.4.3. */
    private const val HEAD_BYTES = 64 * 1024

    /** Bytes read from the end of a file to decide whether it has been completely written. */
    private const val TAIL_BYTES = 512

    private const val TITLE = "<title>Pytest HTML Reporter</title>"

    /** The `localStorage` key the report's theme switch uses (0.3.x and 0.4.x). */
    private const val THEME_KEY = "pytestHtmlReporterTheme"

    /**
     * A path string for comparisons: `/` separators and Unicode NFC. macOS can report the same file name decomposed
     * (NFD) in file-system events while Java paths are composed, so `ü` would otherwise not equal itself.
     */
    fun comparablePath(path: String): String =
        java.text.Normalizer.normalize(path.replace('\\', '/'), java.text.Normalizer.Form.NFC)

    fun isReportHead(head: String): Boolean = head.contains(TITLE, ignoreCase = true) || head.contains(THEME_KEY)

    /** Whether [file] is an HTML file written by pytest-html-reporter. */
    fun isReport(file: Path): Boolean {
        if (!file.fileName.toString().endsWith(".html", ignoreCase = true)) return false
        val head = readHead(file) ?: return false
        return isReportHead(head)
    }

    /**
     * Whether [file] looks completely written. The reporter writes the whole page in one `open(path, 'w')` pass,
     * so a watcher can observe a truncated or partial file; a complete page ends with `</html>`.
     */
    fun isComplete(file: Path): Boolean {
        val tail = readTail(file) ?: return false
        return tail.trimEnd().endsWith("</html>", ignoreCase = true)
    }

    fun candidate(file: Path): ReportCandidate? {
        val attributes = attributes(file) ?: return null
        if (!attributes.isRegularFile) return null
        return ReportCandidate(
            file = file,
            lastModifiedMillis = attributes.lastModifiedTime().toMillis(),
            sizeBytes = attributes.size(),
            hasOutputJson = Files.isRegularFile(file.resolveSibling(ReportPaths.OUTPUT_JSON)),
        )
    }

    fun attributes(file: Path): BasicFileAttributes? = try {
        Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readHead(file: Path): String? = try {
        Files.newInputStream(file).use { input ->
            val bytes = input.readNBytes(HEAD_BYTES)
            String(bytes, StandardCharsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun readTail(file: Path): String? = try {
        RandomAccessFile(file.toFile(), "r").use { raf ->
            val length = raf.length()
            if (length == 0L) return null
            val start = maxOf(0L, length - TAIL_BYTES)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            String(bytes, StandardCharsets.UTF_8)
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
