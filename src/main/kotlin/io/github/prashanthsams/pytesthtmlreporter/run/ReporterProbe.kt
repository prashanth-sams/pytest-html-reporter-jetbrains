package io.github.prashanthsams.pytesthtmlreporter.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.logger
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.core.MiniJson
import io.github.prashanthsams.pytesthtmlreporter.core.ProbeResult
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Runs `python/pytest_html_reporter_probe.py` with the chosen interpreter. The script only reads: versions, the
 * reporter's registered flags, and where its configuration puts the report.
 */
internal object ReporterProbe {
    private val LOG = logger<ReporterProbe>()
    private const val RESOURCE = "/python/pytest_html_reporter_probe.py"
    private const val TIMEOUT_MILLIS = 60_000

    class ProbeException(message: String, val details: String? = null) : Exception(message)

    suspend fun run(interpreter: Path, workingDirectory: Path, environment: RunEnvironment, profile: String?): ProbeResult {
        val script = scriptFile()
        val command = GeneralCommandLine(interpreter.toString(), script.toString(), workingDirectory.toString())
            .withWorkingDirectory(workingDirectory)
        if (!profile.isNullOrBlank()) command.addParameter(profile)
        environment.applyTo(command)
        // Never leave bytecode caches behind in the temp folder or the project.
        command.withEnvironment("PYTHONDONTWRITEBYTECODE", "1")

        val handler = try {
            CapturingProcessHandler(command)
        } catch (e: ExecutionException) {
            throw ProbeException(message("probe.error.start", interpreter, e.message.orEmpty()))
        }
        val output = try {
            runInterruptible { handler.runProcess(TIMEOUT_MILLIS) }
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        if (output.isTimeout) throw ProbeException(message("probe.error.timeout", interpreter, TIMEOUT_MILLIS / 1000))
        val json = output.stdoutLines.lastOrNull { it.trimStart().startsWith("{") }
        if (output.exitCode != 0 || json == null) {
            throw ProbeException(message("probe.error.failed", interpreter, output.exitCode), output.stderr.ifBlank { output.stdout })
        }
        return try {
            ProbeResult.parse(json)
        } catch (e: MiniJson.JsonException) {
            LOG.warn("Unreadable probe output: $json", e)
            throw ProbeException(message("probe.error.unreadable", interpreter), json)
        }
    }

    /** Copies the script out of the plugin JAR once per content version (Python cannot import from inside a JAR). */
    @Synchronized
    private fun scriptFile(): Path {
        val bytes = ReporterProbe::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: throw ProbeException("Probe script $RESOURCE is missing from the plugin")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val target = Path.of(PathManager.getTempPath(), "pytest-html-reporter", "probe-$digest.py")
        try {
            if (!Files.isRegularFile(target)) {
                Files.createDirectories(target.parent)
                val temporary = Files.createTempFile(target.parent, "probe", ".tmp")
                Files.write(temporary, bytes)
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        } catch (e: IOException) {
            throw ProbeException(message("probe.error.script", e.message.orEmpty()))
        }
        return target
    }
}
