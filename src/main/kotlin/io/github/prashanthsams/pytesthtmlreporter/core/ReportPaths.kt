package io.github.prashanthsams.pytesthtmlreporter.core

import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Where pytest-html-reporter writes a report for a given `--html-report` value.
 *
 * This mirrors upstream behaviour line for line (`util.report_path`, `util.expand_time` and
 * `HTMLReporter.report_path` in pytest-html-reporter 0.4.3), oddities included, because a prediction that names a
 * different folder than the reporter would point the viewer at a stale or missing file:
 *
 * - an empty value means `"."`, i.e. the working directory;
 * - a value containing `.html` *anywhere* is a file path, split on `/` (not the platform separator);
 * - any other value is a folder, and the file is `pytest_html_report.html` inside it.
 *
 * Note that upstream's README describes the default as `report/pytest_html_reporter.html`; the code (and every real
 * run checked while building this plugin) writes `./pytest_html_report.html`.
 */
object ReportPaths {
    const val DEFAULT_FILE_NAME = "pytest_html_report.html"
    const val OUTPUT_JSON = "output.json"
    const val SCREENSHOTS_DIR = "pytest_screenshots"
    const val ARCHIVE_DIR = "archive"
    private const val DEFAULT_PATH = "."

    /** The strftime directives upstream expands (`util.TIME_DIRECTIVES`); `%` followed by anything else is kept. */
    private const val TIME_DIRECTIVES = "aAbBcdfGHIjmMpSuUVwWxXyYzZ"
    private val DIRECTIVE = Regex("%(.)", RegexOption.DOT_MATCHES_ALL)

    /** A report location as folder + file name, both still relative to the pytest working directory. */
    data class Split(val folder: String, val fileName: String)

    /** Mirrors `HTMLReporter.report_path`, after time expansion. */
    fun split(value: String): Split {
        val path = value.trim().ifEmpty { DEFAULT_PATH }
        if (".html" !in path) return Split(path, DEFAULT_FILE_NAME)
        val head = if ('/' in path) path.substringBeforeLast('/') else path
        var folder = if (".html" in head) DEFAULT_PATH else head
        if (folder.isEmpty()) folder = DEFAULT_PATH
        return Split(folder, path.substringAfterLast('/'))
    }

    /**
     * The report file a (time-expanded) value produces when pytest runs in [workingDirectory] with [environment]
     * (upstream applies `os.path.expanduser(os.path.expandvars(...))` to the folder).
     */
    fun resolve(value: String, workingDirectory: Path, environment: Map<String, String> = System.getenv()): Path {
        val split = split(value)
        val base = workingDirectory.resolve(expandFolder(split.folder, environment)).normalize()
        return base.resolve(split.fileName).normalize()
    }

    fun hasTimeDirectives(value: String): Boolean =
        DIRECTIVE.findAll(value).any { it.groupValues[1][0] in TIME_DIRECTIVES }

    /** `%` doubled, so that the reporter's own expansion turns an already-expanded path back into itself. */
    fun escapePercent(value: String): String = value.replace("%", "%%")

    /**
     * Mirrors `util.expand_time`: strftime on the known directives, `%%` to a literal `%`, everything else kept.
     * Upstream formats a naive local `datetime.now()`, so `%z` and `%Z` expand to nothing; names use the C locale.
     */
    fun expandTime(value: String, now: LocalDateTime): String {
        if ('%' !in value) return value
        return DIRECTIVE.replace(value) { match ->
            val directive = match.groupValues[1][0]
            when {
                directive == '%' -> "%"
                directive !in TIME_DIRECTIVES -> match.value
                else -> format(directive, now)
            }
        }
    }

    /**
     * A regex matching every file a value *with* time placeholders could name, for finding the report a run wrote
     * when the path came from pytest configuration rather than from this plugin.
     */
    fun patternFor(value: String, workingDirectory: Path, environment: Map<String, String> = System.getenv()): Regex {
        val split = split(value)
        val raw = workingDirectory.resolve(expandFolder(split.folder, environment)).resolve(split.fileName).normalize().toString()
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 1 < raw.length) {
                val directive = raw[i + 1]
                when {
                    directive == '%' -> out.append(Regex.escape("%"))
                    directive in TIME_DIRECTIVES -> out.append(if (directive == 'x') ".*?" else NO_SEPARATOR)
                    else -> out.append(Regex.escape(raw.substring(i, i + 2)))
                }
                i += 2
                continue
            }
            out.append(Regex.escape(c.toString()))
            i++
        }
        return Regex(out.toString())
    }

    /**
     * The deepest folder of [value] that has no time placeholder, resolved against [workingDirectory]: where to start
     * looking for the files [patternFor] matches.
     */
    fun staticRootFor(value: String, workingDirectory: Path, environment: Map<String, String> = System.getenv()): Path {
        val folder = expandFolder(split(value).folder, environment)
        val segments = folder.split('/', '\\')
        val fixedCount = segments.indexOfFirst { hasTimeDirectives(it) }.let { if (it < 0) segments.size else it }
        val prefix = segments.take(fixedCount).joinToString("/")
        return when {
            prefix.isEmpty() && (folder.startsWith("/") || folder.startsWith("\\")) -> workingDirectory.root ?: workingDirectory
            prefix.isEmpty() -> workingDirectory
            else -> workingDirectory.resolve(expandTime(prefix, LocalDateTime.MIN)).normalize()
        }
    }

    /** `os.path.expanduser(os.path.expandvars(folder))` for `~`, `$NAME` and `${NAME}`. */
    internal fun expandFolder(folder: String, environment: Map<String, String>): String {
        val vars = ENV_VAR.replace(folder) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            environment[name] ?: match.value
        }
        if (vars == "~" || vars.startsWith("~/") || vars.startsWith("~\\")) return System.getProperty("user.home") + vars.substring(1)
        return vars
    }

    private val ENV_VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)}|\$([A-Za-z_][A-Za-z0-9_]*)""")
    private const val NO_SEPARATOR = """[^/\\]*?"""

    private fun format(directive: Char, now: LocalDateTime): String {
        fun pad(value: Int, width: Int = 2) = value.toString().padStart(width, '0')
        val hour12 = (now.hour % 12).let { if (it == 0) 12 else it }
        return when (directive) {
            'a' -> now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            'A' -> now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            'b' -> now.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
            'B' -> now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            'c' -> "${format('a', now)} ${format('b', now)} ${now.dayOfMonth.toString().padStart(2, ' ')} " +
                "${pad(now.hour)}:${pad(now.minute)}:${pad(now.second)} ${now.year}"
            'd' -> pad(now.dayOfMonth)
            'f' -> pad(now.get(ChronoField.MICRO_OF_SECOND), 6)
            'G' -> now.get(IsoFields.WEEK_BASED_YEAR).toString()
            'H' -> pad(now.hour)
            'I' -> pad(hour12)
            'j' -> pad(now.dayOfYear, 3)
            'm' -> pad(now.monthValue)
            'M' -> pad(now.minute)
            'p' -> if (now.hour < 12) "AM" else "PM"
            'S' -> pad(now.second)
            'u' -> now.dayOfWeek.value.toString()
            'U' -> pad(weekOfYear(now, firstDay = java.time.DayOfWeek.SUNDAY))
            'V' -> pad(now.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            'w' -> (now.dayOfWeek.value % 7).toString()
            'W' -> pad(weekOfYear(now, firstDay = java.time.DayOfWeek.MONDAY))
            'x' -> "${pad(now.monthValue)}/${pad(now.dayOfMonth)}/${pad(now.year % 100)}"
            'X' -> "${pad(now.hour)}:${pad(now.minute)}:${pad(now.second)}"
            'y' -> pad(now.year % 100)
            'Y' -> now.year.toString()
            'z', 'Z' -> ""
            else -> "%$directive"
        }
    }

    /** C's %U / %W: weeks counted from the first Sunday / Monday of the year; days before it are week 0. */
    private fun weekOfYear(now: LocalDateTime, firstDay: java.time.DayOfWeek): Int {
        val weekFields = WeekFields.of(firstDay, 7)
        return now.get(weekFields.weekOfYear())
    }
}
