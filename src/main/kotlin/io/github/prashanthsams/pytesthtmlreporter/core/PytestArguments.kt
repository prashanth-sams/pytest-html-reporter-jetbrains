package io.github.prashanthsams.pytesthtmlreporter.core

/**
 * Reporter-relevant facts read from the user's own pytest arguments (already split into tokens).
 *
 * Only tokens before a bare `--` are options; pytest treats everything after it as file or directory arguments.
 * For repeated `store` options the last occurrence wins, as it does in pytest's argparse.
 */
class PytestArguments(val tokens: List<String>) {

    private val optionTokens: List<String> =
        tokens.indexOf(END_OF_OPTIONS).let { if (it < 0) tokens else tokens.subList(0, it) }

    /** The value of the last `--flag=value` or `--flag value`, or null when the flag is absent. */
    fun valueOf(flag: String): String? {
        var value: String? = null
        var i = 0
        while (i < optionTokens.size) {
            val token = optionTokens[i]
            when {
                token == flag && i + 1 < optionTokens.size -> {
                    value = optionTokens[i + 1]
                    i += 2
                    continue
                }
                token.startsWith("$flag=") -> value = token.substring(flag.length + 1)
            }
            i++
        }
        return value
    }

    fun has(flag: String): Boolean = optionTokens.any { it == flag || it.startsWith("$flag=") }

    val reportPath: String? get() = valueOf(ProbeResult.HTML_REPORT)
    val reportOpen: String? get() = valueOf(ProbeResult.REPORT_OPEN)
    val reportProfile: String? get() = valueOf(ProbeResult.REPORT_PROFILE)

    /** Every `-p` plugin argument, in the three spellings argparse accepts (`-p x`, `-px`, `-p=x`). */
    private val pluginArguments: List<String>
        get() = buildList {
            var i = 0
            while (i < optionTokens.size) {
                val token = optionTokens[i]
                when {
                    token == "-p" && i + 1 < optionTokens.size -> {
                        add(optionTokens[i + 1])
                        i += 2
                        continue
                    }
                    token.startsWith("-p") && !token.startsWith("--") && token.length > 2 ->
                        add(token.substring(2).removePrefix("="))
                }
                i++
            }
        }

    /** `-p no:reporter` (the entry point name upstream registers) or a block naming the module. */
    val reporterDisabledBy: String?
        get() = pluginArguments.firstOrNull { arg ->
            arg.startsWith("no:") && arg.removePrefix("no:").let { it == ENTRY_POINT_NAME || it.startsWith("pytest_html_reporter") }
        }?.let { "-p $it" }

    /** Whether the reporter is loaded explicitly, which matters when plugin autoloading is disabled. */
    val loadsReporterExplicitly: Boolean
        get() = pluginArguments.any { it.startsWith("pytest_html_reporter") }

    val collectOnlyFlag: String?
        get() = optionTokens.firstOrNull { it == "--collect-only" || it == "--co" || it == "--collectonly" }

    val shardWithoutMerge: String?
        get() = if (has("--report-shard") && !has("--report-shard-merge")) "--report-shard" else null

    /** `--html` belongs to the separate pytest-html plugin; an easy slip for `--html-report`. */
    val pytestHtmlFlag: String?
        get() = optionTokens.firstOrNull { it == "--html" || it.startsWith("--html=") }

    companion object {
        const val END_OF_OPTIONS = "--"

        /** `pytest11` entry point name in upstream's setup.py: `reporter = pytest_html_reporter.plugin`. */
        const val ENTRY_POINT_NAME = "reporter"
    }
}
