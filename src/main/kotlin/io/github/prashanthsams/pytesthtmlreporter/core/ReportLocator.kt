package io.github.prashanthsams.pytesthtmlreporter.core

import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence

/**
 * Ties a finished run to the report *it* wrote. A file only counts when it was modified after the run started, so
 * a run that wrote nothing (no tests collected, `--collect-only`, a crash) is never shown an older report as its own.
 */
object ReportLocator {
    /** File systems with coarse timestamps (FAT, some network mounts) round to 2 seconds. */
    const val MTIME_TOLERANCE_MILLIS = 2_000L

    fun find(expected: ExpectedReport, startedAtMillis: Long): ReportCandidate? = when (expected) {
        is ExpectedReport.Exact -> fresh(expected.file, startedAtMillis)
        is ExpectedReport.Matching -> matching(expected, startedAtMillis)
    }

    fun isFresh(candidate: ReportCandidate, startedAtMillis: Long): Boolean =
        candidate.lastModifiedMillis >= startedAtMillis - MTIME_TOLERANCE_MILLIS

    private fun fresh(file: Path, startedAtMillis: Long): ReportCandidate? {
        val candidate = ReportFiles.candidate(file) ?: return null
        return candidate.takeIf { isFresh(it, startedAtMillis) && ReportFiles.isReport(file) }
    }

    private fun matching(expected: ExpectedReport.Matching, startedAtMillis: Long): ReportCandidate? {
        if (!Files.isDirectory(expected.root)) return null
        return try {
            Files.walk(expected.root, expected.maxDepth).use { stream ->
                stream.asSequence()
                    .filter { expected.pattern.matches(it.toString()) }
                    .mapNotNull { fresh(it, startedAtMillis) }
                    .maxByOrNull { it.lastModifiedMillis }
            }
        } catch (_: IOException) {
            null
        } catch (_: java.io.UncheckedIOException) {
            null
        }
    }

    /**
     * Waits until [file] is completely written: it ends with `</html>` and its size and timestamp hold still between
     * two checks. Returns false if that does not happen within [timeoutMillis].
     */
    suspend fun awaitComplete(file: Path, timeoutMillis: Long = 10_000, pollMillis: Long = 200): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var previous: Pair<Long, Long>? = null
        while (System.currentTimeMillis() < deadline) {
            val attributes = ReportFiles.attributes(file)
            if (attributes != null && attributes.isRegularFile) {
                val stamp = attributes.size() to attributes.lastModifiedTime().toMillis()
                if (stamp == previous && ReportFiles.isComplete(file)) return true
                previous = stamp
            } else {
                previous = null
            }
            delay(pollMillis)
        }
        return false
    }
}
