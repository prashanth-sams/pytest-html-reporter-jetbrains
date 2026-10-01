package io.github.prashanthsams.pytesthtmlreporter.core

/**
 * A pytest-html-reporter release number, compared the way PEP 440 orders the versions this project has published:
 * numeric release segments, with any pre-release or development suffix sorting before the plain release.
 */
data class ReporterVersion(val release: List<Int>, val suffix: String = "") : Comparable<ReporterVersion> {

    private val isPreRelease: Boolean
        get() = suffix.isNotEmpty() && !suffix.startsWith("post")

    override fun compareTo(other: ReporterVersion): Int {
        val length = maxOf(release.size, other.release.size)
        for (i in 0 until length) {
            val diff = release.getOrElse(i) { 0 } - other.release.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return when {
            isPreRelease == other.isPreRelease -> suffix.compareTo(other.suffix)
            isPreRelease -> -1
            else -> 1
        }
    }

    override fun toString(): String = release.joinToString(".") + suffix

    companion object {
        // Declared before the constants below, which are parsed while the companion object initialises.
        private val PATTERN = Regex("""^\s*v?(\d+(?:\.\d+)*)([A-Za-z0-9.+_-]*)\s*$""")

        /** 0.2.x releases fail on current pytest (INTERNALERROR, no report); 0.3.0 is the first one that works. */
        val MINIMUM_SUPPORTED: ReporterVersion = parse("0.3.0")!!

        /** `--html-report` values are expanded with strftime placeholders, and `html_report` is read from ini files, from this release on. */
        val STRFTIME_PATHS: ReporterVersion = parse("0.3.4")!!

        /** `--report-open` (and the browser opening it controls) first shipped in this release. */
        val REPORT_OPEN: ReporterVersion = parse("0.3.7")!!

        /** Named profiles and the `pytest-html-reporter config --json` command first shipped in this release. */
        val CONFIG_COMMAND: ReporterVersion = parse("0.4.3")!!

        fun parse(text: String?): ReporterVersion? {
            val match = PATTERN.matchEntire(text ?: return null) ?: return null
            val release = match.groupValues[1].split('.').map { it.toIntOrNull() ?: return null }
            val suffix = match.groupValues[2].trimStart('.', '-', '_').lowercase()
            return ReporterVersion(release, suffix)
        }
    }
}
