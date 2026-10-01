package io.github.prashanthsams.pytesthtmlreporter.run

import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.CommandNote
import io.github.prashanthsams.pytesthtmlreporter.core.ExpectedReport
import org.jetbrains.annotations.Nls

/** Human-readable text for the decisions [io.github.prashanthsams.pytesthtmlreporter.core.PytestCommandBuilder] made. */
internal object CommandNotes {

    fun prefix(note: CommandNote): String = when (note.severity) {
        CommandNote.Severity.INFO -> ""
        CommandNote.Severity.WARNING -> message("console.prefix.warning")
        CommandNote.Severity.ERROR -> message("console.prefix.error")
    }

    @Nls
    fun describe(note: CommandNote): String = when (note) {
        is CommandNote.ReportPathFromArguments -> message("note.path.arguments", note.value)
        is CommandNote.ReportPathFromConfiguration -> message("note.path.configuration", note.value, note.source)
        is CommandNote.ReportPathFromSettings -> message("note.path.settings", note.value)
        is CommandNote.ReportPathOverridesConfiguration -> message("note.path.overrides", note.configured, note.source)
        CommandNote.ReportPathDefault -> message("note.path.default")
        is CommandNote.ReportOpenFromArguments -> message("note.open.arguments", note.value)
        CommandNote.ReportOpenUnsupported -> message("note.open.unsupported")
        is CommandNote.ReporterDisabled -> message("note.disabled", note.argument)
        CommandNote.ReporterAutoloadDisabled -> message("note.autoload")
        is CommandNote.NoReportForCollectOnly -> message("note.collectOnly", note.argument)
        is CommandNote.ShardWithoutMerge -> message("note.shard", note.argument)
        is CommandNote.PytestHtmlOption -> message("note.pytestHtml", note.argument)
    }

    @Nls
    fun describe(expected: ExpectedReport): String = when (expected) {
        is ExpectedReport.Exact -> expected.file.toString()
        is ExpectedReport.Matching -> message("console.expected.matching", expected.root)
    }
}
