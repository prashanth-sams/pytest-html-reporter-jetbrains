package io.github.prashanthsams.pytesthtmlreporter.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The environment changes that activating an interpreter's virtual environment or conda environment would make.
 *
 * Running `<venv>/bin/python` directly already imports from the venv; what it does not do is put the venv's scripts
 * on `PATH`, so a test that starts `python`, `pip` or a console script would otherwise get the system one.
 */
object PythonActivation {

    fun variables(interpreter: Path, currentPath: String?, isWindows: Boolean = File.separatorChar == '\\'): Map<String, String> {
        val binDirectory = interpreter.toAbsolutePath().parent ?: return emptyMap()
        val candidates = listOfNotNull(binDirectory, binDirectory.parent)
        val prefix = candidates.firstOrNull { Files.isRegularFile(it.resolve("pyvenv.cfg")) }
        if (prefix != null) {
            val scripts = if (isWindows) prefix.resolve("Scripts") else prefix.resolve("bin")
            return mapOf(
                "VIRTUAL_ENV" to prefix.toString(),
                "PATH" to prepend(listOf(scripts), currentPath, isWindows),
            )
        }
        val conda = candidates.firstOrNull { Files.isDirectory(it.resolve("conda-meta")) } ?: return emptyMap()
        val entries = if (isWindows) {
            listOf(
                conda, conda.resolve("Library").resolve("mingw-w64").resolve("bin"), conda.resolve("Library").resolve("usr").resolve("bin"),
                conda.resolve("Library").resolve("bin"), conda.resolve("Scripts"), conda.resolve("bin"),
            )
        } else {
            listOf(conda.resolve("bin"))
        }
        return mapOf(
            "CONDA_PREFIX" to conda.toString(),
            "PATH" to prepend(entries, currentPath, isWindows),
        )
    }

    private fun prepend(entries: List<Path>, currentPath: String?, isWindows: Boolean): String {
        val separator = if (isWindows) ";" else ":"
        val existing = currentPath.orEmpty().split(separator).filter(String::isNotEmpty)
        val added = entries.map(Path::toString)
        return (added + existing.filterNot { it in added }).joinToString(separator)
    }
}
