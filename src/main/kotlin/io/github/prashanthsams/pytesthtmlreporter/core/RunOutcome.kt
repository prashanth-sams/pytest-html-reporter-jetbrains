package io.github.prashanthsams.pytesthtmlreporter.core

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** pytest's documented exit codes (`pytest.ExitCode`). */
enum class PytestExitCode(val code: Int) {
    OK(0),
    TESTS_FAILED(1),
    INTERRUPTED(2),
    INTERNAL_ERROR(3),
    USAGE_ERROR(4),
    NO_TESTS_COLLECTED(5),
    ;

    companion object {
        fun of(code: Int): PytestExitCode? = entries.firstOrNull { it.code == code }
    }
}

/** The outcome counts of one build, read from the `output.json` the reporter writes beside the report. */
data class RunSummary(val counts: Map<String, Int>, val total: Int?, val status: String?) {

    /** E.g. "2 passed, 1 failed, 1 error, 1 skipped, 1 xfailed" - zero counts left out. */
    fun describe(): String = ORDER.mapNotNull { (key, label) ->
        counts[key]?.takeIf { it > 0 }?.let { "$it $label" }
    }.joinToString(", ")

    companion object {
        private val ORDER = listOf(
            "pass" to "passed", "fail" to "failed", "error" to "error", "skip" to "skipped",
            "xfail" to "xfailed", "xpass" to "xpassed", "rerun" to "rerun",
        )

        fun parse(json: String): RunSummary? {
            val root = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
            val statusList = root.objectValue("status_list") ?: return null
            val counts = statusList.entries.mapNotNull { (key, value) ->
                val count = when (value) {
                    is Number -> value.toInt()
                    is String -> value.trim().toIntOrNull()
                    else -> null
                }
                if (key is String && count != null) key to count else null
            }.toMap()
            val total = when (val value = root["total_tests"]) {
                is Number -> value.toInt()
                is String -> value.trim().toIntOrNull()
                else -> null
            }
            return RunSummary(counts, total, root.stringValue("status"))
        }

        /** Reads `output.json` beside [report], or null when it is missing or not a reporter document. */
        fun forReport(report: Path): RunSummary? {
            val json = report.resolveSibling(ReportPaths.OUTPUT_JSON)
            return try {
                if (Files.size(json) > MAX_OUTPUT_JSON_BYTES) null
                else parse(String(Files.readAllBytes(json), StandardCharsets.UTF_8))
            } catch (_: IOException) {
                null
            }
        }

        private const val MAX_OUTPUT_JSON_BYTES = 64L * 1024 * 1024
    }
}
