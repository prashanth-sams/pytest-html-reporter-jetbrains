package io.github.prashanthsams.pytesthtmlreporter.run

import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterWorkspaceState
import org.jetbrains.annotations.Nls
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Which Python runs pytest. */
sealed interface InterpreterResolution {
    /** A Python executable on this machine. */
    data class Local(val executable: Path, @Nls val description: String) : InterpreterResolution

    /** The project interpreter runs elsewhere (SSH, Docker, WSL or another target), which this plugin cannot launch. */
    data class Remote(@Nls val name: String) : InterpreterResolution

    data class Unavailable(@Nls val reason: String) : InterpreterResolution
}

/**
 * Supplies the project's Python interpreter. Implemented by the optional Python integration
 * (`META-INF/pytest-html-reporter-python.xml`), so IDEs without Python support still load the plugin and use the
 * interpreter set in settings.
 */
interface PythonInterpreterProvider {
    /** The interpreter for [workingDirectory] in [project], or null when there is none. Called inside a read action. */
    fun findInterpreter(project: Project, workingDirectory: Path?): InterpreterResolution?

    companion object {
        val EP_NAME: ExtensionPointName<PythonInterpreterProvider> =
            ExtensionPointName.create("io.github.prashanthsams.pytesthtmlreporter.interpreterProvider")
    }
}

internal object InterpreterResolver {

    /** The interpreter set explicitly in settings wins; otherwise the project interpreter, when Python support is present. */
    suspend fun resolve(project: Project, workingDirectory: Path?): InterpreterResolution {
        explicitInterpreter(project)?.let { return it }
        val fromProject = readAction {
            PythonInterpreterProvider.EP_NAME.extensionList.firstNotNullOfOrNull { it.findInterpreter(project, workingDirectory) }
        }
        return fromProject ?: InterpreterResolution.Unavailable(
            if (PythonInterpreterProvider.EP_NAME.extensionList.isEmpty()) message("interpreter.unavailable.noPythonSupport")
            else message("interpreter.unavailable.noProjectInterpreter"),
        )
    }

    fun explicitInterpreter(project: Project): InterpreterResolution? {
        val configured = ReporterWorkspaceState.getInstance(project).state.interpreterPath?.trim().orEmpty()
        if (configured.isEmpty()) return null
        val path = try {
            Path.of(configured)
        } catch (_: InvalidPathException) {
            return InterpreterResolution.Unavailable(message("interpreter.unavailable.invalidPath", configured))
        }
        return if (Files.isRegularFile(path) && Files.isExecutable(path)) {
            InterpreterResolution.Local(path, message("interpreter.description.explicit"))
        } else {
            InterpreterResolution.Unavailable(message("interpreter.unavailable.missingFile", configured))
        }
    }
}
