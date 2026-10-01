package io.github.prashanthsams.pytesthtmlreporter.core

/**
 * What the probe script (`python/pytest_html_reporter_probe.py`) found in an interpreter.
 *
 * Every optional reporter flag the plugin passes is gated on [reporterOptions] - the flags the installed plugin
 * actually registers - rather than on a version number alone.
 */
data class ProbeResult(
    val pythonVersion: String?,
    val pythonExecutable: String?,
    val pytestVersion: String?,
    val pytestError: String?,
    val reporterVersion: String?,
    val reporterError: String?,
    /** Flags registered by the installed reporter's `pytest_addoption`, or null when they could not be read. */
    val reporterOptions: Set<String>?,
    val pipAvailable: Boolean,
    /**
     * The report path pytest configuration names: what the reporter's own `config --json` resolved (ini key,
     * profile or environment; 0.4.3+), else the `html_report` ini key read by the probe (0.3.4+).
     */
    val configuredPath: ConfiguredValue?,
    val configError: String?,
    /** `--html-report` found in `addopts` or `PYTEST_ADDOPTS`, which `config --json` does not model. */
    val addoptsReportPath: ConfiguredValue?,
) {
    data class ConfiguredValue(val value: String, val source: String)

    val hasPytest: Boolean get() = pytestVersion != null

    val hasReporter: Boolean get() = reporterVersion != null

    val parsedReporterVersion: ReporterVersion? get() = ReporterVersion.parse(reporterVersion)

    val meetsMinimumVersion: Boolean
        get() = parsedReporterVersion?.let { it >= ReporterVersion.MINIMUM_SUPPORTED } ?: false

    fun supportsOption(flag: String): Boolean {
        reporterOptions?.let { return flag in it }
        // Introspection failed: fall back to the release that introduced the flag.
        val version = parsedReporterVersion ?: return false
        return when (flag) {
            HTML_REPORT -> true
            REPORT_OPEN -> version >= ReporterVersion.REPORT_OPEN
            REPORT_PROFILE -> version >= ReporterVersion.CONFIG_COMMAND
            else -> false
        }
    }

    /** Whether `--html-report` values are run through strftime by the installed reporter (0.3.4+). */
    val expandsTimePlaceholders: Boolean
        get() = parsedReporterVersion?.let { it >= ReporterVersion.STRFTIME_PATHS } ?: false

    companion object {
        const val PROBE_PROTOCOL = 1
        const val HTML_REPORT = "--html-report"
        const val REPORT_OPEN = "--report-open"
        const val REPORT_PROFILE = "--report-profile"

        /** Parses the probe's JSON document. */
        fun parse(json: String): ProbeResult {
            val root = MiniJson.parse(json.trim()) as? Map<*, *>
                ?: throw MiniJson.JsonException("probe output is not a JSON object")
            val python = root.objectValue("python")
            val options = root.listValue("reporterOptions")?.filterIsInstance<String>()?.toSet()
            val reporterVersion = root.stringValue("reporterVersion")
            // The html_report ini key only means something to releases that read it (0.3.4+).
            val iniPath = root.objectValue("iniReportPath")?.toConfiguredValue()
                ?.takeIf { ReporterVersion.parse(reporterVersion)?.let { v -> v >= ReporterVersion.STRFTIME_PATHS } == true }
            return ProbeResult(
                pythonVersion = python?.stringValue("version"),
                pythonExecutable = python?.stringValue("executable"),
                pytestVersion = root.stringValue("pytestVersion"),
                pytestError = root.stringValue("pytestError"),
                reporterVersion = reporterVersion,
                reporterError = root.stringValue("reporterError"),
                reporterOptions = options,
                pipAvailable = root.booleanValue("pipAvailable") ?: false,
                configuredPath = root.objectValue("configPath")?.toConfiguredValue() ?: iniPath,
                configError = root.stringValue("configError"),
                addoptsReportPath = root.objectValue("addoptsReportPath")?.toConfiguredValue(),
            )
        }

        private fun Map<*, *>.toConfiguredValue(): ConfiguredValue? {
            val value = stringValue("value")?.takeIf { it.isNotBlank() } ?: return null
            return ConfiguredValue(value, stringValue("source").orEmpty())
        }
    }
}
