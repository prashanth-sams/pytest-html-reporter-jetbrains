package io.github.prashanthsams.pytesthtmlreporter.python

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.jetbrains.python.sdk.PythonSdkUtil
import io.github.prashanthsams.pytesthtmlreporter.PytestHtmlReporterBundle.message
import io.github.prashanthsams.pytesthtmlreporter.run.InterpreterResolution
import io.github.prashanthsams.pytesthtmlreporter.run.PythonInterpreterProvider
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The project's Python interpreter, from the Python plugin's SDK model. Registered only when Python support is
 * installed (PyCharm, or IntelliJ IDEA with the Python plugin) - see `pytest-html-reporter-python.xml`.
 */
class PythonSdkInterpreterProvider : PythonInterpreterProvider {

    override fun findInterpreter(project: Project, workingDirectory: Path?): InterpreterResolution? {
        val sdk = moduleSdk(project, workingDirectory)
            ?: ProjectRootManager.getInstance(project).projectSdk?.takeIf(PythonSdkUtil::isPythonSdk)
            ?: ModuleManager.getInstance(project).modules.firstNotNullOfOrNull(PythonSdkUtil::findPythonSdk)
            ?: return null
        return resolve(sdk)
    }

    /** The interpreter of the module that contains the working directory, as PyCharm would pick for a run there. */
    private fun moduleSdk(project: Project, workingDirectory: Path?): Sdk? {
        val directory = workingDirectory?.let { LocalFileSystem.getInstance().findFileByNioFile(it) } ?: return null
        val module = ProjectFileIndex.getInstance(project).getModuleForFile(directory, false) ?: return null
        return PythonSdkUtil.findPythonSdk(module)
    }

    private fun resolve(sdk: Sdk): InterpreterResolution {
        // SSH, Docker, Docker Compose, WSL and other targets: Python runs elsewhere, so pytest cannot be launched here.
        if (PythonSdkUtil.isRemote(sdk)) return InterpreterResolution.Remote(sdk.name)
        val home = sdk.homePath?.takeIf(String::isNotBlank)
            ?: return InterpreterResolution.Unavailable(message("interpreter.unavailable.sdkWithoutHome", sdk.name))
        val executable = try {
            Path.of(home)
        } catch (_: InvalidPathException) {
            return InterpreterResolution.Remote(sdk.name)
        }
        if (!Files.isRegularFile(executable)) {
            return InterpreterResolution.Unavailable(message("interpreter.unavailable.sdkMissing", sdk.name, home))
        }
        return InterpreterResolution.Local(executable, message("interpreter.description.project", sdk.name))
    }
}
