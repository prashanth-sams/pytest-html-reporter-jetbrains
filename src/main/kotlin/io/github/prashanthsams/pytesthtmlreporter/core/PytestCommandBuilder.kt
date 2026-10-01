package io.github.prashanthsams.pytesthtmlreporter.core

import java.nio.file.Path
import java.time.LocalDateTime

/** Where a run's report will be, as far as can be known before the run starts. */
sealed interface ExpectedReport {
    /** The run writes exactly this file. */
    data class Exact(val file: Path) : ExpectedReport

    /** The path holds time placeholders that pytest expands at run time: some file under [root] matching [pattern]. */
    data class Matching(val root: Path, val pattern: Regex, val maxDepth: Int) : ExpectedReport
}

/** Something worth telling the user about how the command was put together. */
sealed interface CommandNote {
    val severity: Severity

    enum class Severity { INFO, WARNING, ERROR }

    data class ReportPathFromArguments(val value: String) : CommandNote {
        override val severity get() = Severity.INFO
    }

    data class ReportPathFromConfiguration(val value: String, val source: String) : CommandNote {
        override val severity get() = Severity.INFO
    }

    data class ReportPathFromSettings(val value: String) : CommandNote {
        override val severity get() = Severity.INFO
    }

    /** The plugin's path is passed although pytest configuration names another one (respect setting is off). */
    data class ReportPathOverridesConfiguration(val configured: String, val source: String) : CommandNote {
        override val severity get() = Severity.WARNING
    }

    data object ReportPathDefault : CommandNote {
        override val severity get() = Severity.INFO
    }

    data class ReportOpenFromArguments(val value: String) : CommandNote {
        override val severity get() = if (value == "none") Severity.INFO else Severity.WARNING
    }

    /** The installed reporter predates `--report-open` (0.3.7), and does not open a browser either. */
    data object ReportOpenUnsupported : CommandNote {
        override val severity get() = Severity.INFO
    }

    data class ReporterDisabled(val argument: String) : CommandNote {
        override val severity get() = Severity.ERROR
    }

    data object ReporterAutoloadDisabled : CommandNote {
        override val severity get() = Severity.WARNING
    }

    data class NoReportForCollectOnly(val argument: String) : CommandNote {
        override val severity get() = Severity.WARNING
    }

    data class ShardWithoutMerge(val argument: String) : CommandNote {
        override val severity get() = Severity.WARNING
    }

    data class PytestHtmlOption(val argument: String) : CommandNote {
        override val severity get() = Severity.INFO
    }
}

/**
 * Builds `<python> -m pytest <targets> <user arguments> [--html-report=<path>] [--report-open=none]`.
 *
 * The report location is decided in this order, so that pytest configuration stays the source of truth:
 * 1. `--html-report` in the user's own arguments is kept as is;
 * 2. a blank output setting adds nothing, leaving pytest configuration or the reporter default in charge;
 * 3. with "respect pytest configuration" on, a path named by `addopts`, a profile, the environment or the
 *    `html_report` ini key is kept;
 * 4. otherwise the plugin passes its output setting.
 */
class PytestCommandBuilder {

    data class Request(
        val interpreter: String,
        val workingDirectory: Path,
        val targets: List<String>,
        val userArguments: List<String>,
        val outputSetting: String,
        val respectPytestConfiguration: Boolean,
        val environment: Map<String, String>,
        val now: LocalDateTime = LocalDateTime.now(),
    )

    data class Result(val command: List<String>, val expectedReport: ExpectedReport, val notes: List<CommandNote>)

    fun build(request: Request, probe: ProbeResult): Result {
        val user = PytestArguments(request.userArguments)
        val notes = mutableListOf<CommandNote>()
        val reporterArguments = mutableListOf<String>()
        val configured = probe.addoptsReportPath ?: probe.configuredPath

        // The --html-report value that will be in effect, and whether it was already expanded by us.
        val effectiveValue: String
        var expandedByPlugin = false

        val userPath = user.reportPath
        when {
            userPath != null -> {
                notes += CommandNote.ReportPathFromArguments(userPath)
                effectiveValue = userPath
            }
            request.outputSetting.isBlank() || (request.respectPytestConfiguration && configured != null) -> {
                if (configured != null) {
                    notes += CommandNote.ReportPathFromConfiguration(configured.value, configured.source)
                    effectiveValue = configured.value
                } else {
                    notes += CommandNote.ReportPathDefault
                    effectiveValue = ""
                }
            }
            else -> {
                val relative = pathArgument(request.outputSetting, request.workingDirectory, request.environment)
                val expanded = if (probe.expandsTimePlaceholders) ReportPaths.expandTime(relative, request.now) else relative
                val passed = if (probe.expandsTimePlaceholders) ReportPaths.escapePercent(expanded) else expanded
                reporterArguments += "${ProbeResult.HTML_REPORT}=$passed"
                notes += CommandNote.ReportPathFromSettings(expanded)
                if (configured != null) notes += CommandNote.ReportPathOverridesConfiguration(configured.value, configured.source)
                effectiveValue = expanded
                expandedByPlugin = true
            }
        }

        val userOpen = user.reportOpen
        when {
            userOpen != null -> notes += CommandNote.ReportOpenFromArguments(userOpen)
            probe.supportsOption(ProbeResult.REPORT_OPEN) -> reporterArguments += "${ProbeResult.REPORT_OPEN}=none"
            else -> notes += CommandNote.ReportOpenUnsupported
        }

        user.reporterDisabledBy?.let { notes += CommandNote.ReporterDisabled(it) }
        if (!request.environment[AUTOLOAD_DISABLED].isNullOrEmpty() && !user.loadsReporterExplicitly) {
            notes += CommandNote.ReporterAutoloadDisabled
        }
        user.collectOnlyFlag?.let { notes += CommandNote.NoReportForCollectOnly(it) }
        user.shardWithoutMerge?.let { notes += CommandNote.ShardWithoutMerge(it) }
        user.pytestHtmlFlag?.let { notes += CommandNote.PytestHtmlOption(it) }

        val expected = expectedReport(effectiveValue, expandedByPlugin, request, probe)
        return Result(assemble(request, reporterArguments), expected, notes)
    }

    private fun expectedReport(value: String, expandedByPlugin: Boolean, request: Request, probe: ProbeResult): ExpectedReport {
        val dir = request.workingDirectory
        if (!expandedByPlugin && probe.expandsTimePlaceholders && ReportPaths.hasTimeDirectives(value)) {
            return ExpectedReport.Matching(
                root = ReportPaths.staticRootFor(value, dir, request.environment),
                pattern = ReportPaths.patternFor(value, dir, request.environment),
                maxDepth = MATCHING_DEPTH,
            )
        }
        val literal = if (expandedByPlugin || !probe.expandsTimePlaceholders) value else ReportPaths.expandTime(value, request.now)
        return ExpectedReport.Exact(ReportPaths.resolve(literal, dir, request.environment))
    }

    /** Positional targets first, then the user's options, then ours - ahead of a bare `--` if there is one. */
    private fun assemble(request: Request, reporterArguments: List<String>): List<String> {
        val user = request.userArguments
        val split = user.indexOf(PytestArguments.END_OF_OPTIONS).let { if (it < 0) user.size else it }
        return buildList {
            add(request.interpreter)
            add("-m")
            add("pytest")
            addAll(request.targets)
            addAll(user.subList(0, split))
            addAll(reporterArguments)
            addAll(user.subList(split, user.size))
        }
    }

    /**
     * The output setting as an `--html-report` value: relative to the working directory when it lies inside it
     * (upstream treats any path containing `.html` as a file, so a parent folder named `x.html` must not leak into
     * the value), absolute otherwise, and always with `/` separators, which is what upstream splits on.
     */
    private fun pathArgument(setting: String, workingDirectory: Path, environment: Map<String, String>): String {
        val trimmed = setting.trim()
        val folderPart = ReportPaths.expandFolder(trimmed, environment)
        val absolute = workingDirectory.resolve(folderPart).normalize()
        val trailingSlash = trimmed.endsWith('/') || trimmed.endsWith('\\')
        val value = if (absolute.startsWith(workingDirectory)) {
            workingDirectory.relativize(absolute).toString().ifEmpty { "." }
        } else {
            absolute.toString()
        }
        return value.replace('\\', '/') + if (trailingSlash && value.isNotEmpty()) "/" else ""
    }

    companion object {
        const val AUTOLOAD_DISABLED = "PYTEST_DISABLE_PLUGIN_AUTOLOAD"
        private const val MATCHING_DEPTH = 6
    }
}
