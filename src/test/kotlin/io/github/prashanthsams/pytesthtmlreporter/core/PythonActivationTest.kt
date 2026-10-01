package io.github.prashanthsams.pytesthtmlreporter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class PythonActivationTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `activates a virtual environment in a path with spaces`() {
        val venv = temporary.newFolder("py env ü", ".venv").toPath()
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr/bin\n")
        val python = Files.createDirectories(venv.resolve("bin")).resolve("python")
        val variables = PythonActivation.variables(python, "/usr/bin:/bin", isWindows = false)
        assertEquals(venv.toString(), variables["VIRTUAL_ENV"])
        assertEquals("${venv.resolve("bin")}:/usr/bin:/bin", variables["PATH"])
    }

    @Test
    fun `uses Scripts on Windows and does not duplicate PATH entries`() {
        val venv = temporary.newFolder("venv").toPath()
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = C:\\Python312\n")
        val scripts = Files.createDirectories(venv.resolve("Scripts"))
        val variables = PythonActivation.variables(scripts.resolve("python.exe"), "$scripts;C:\\Windows", isWindows = true)
        assertEquals("$scripts;C:\\Windows", variables["PATH"])
    }

    @Test
    fun `activates a conda environment`() {
        val conda = temporary.newFolder("miniconda", "envs", "tests").toPath()
        Files.createDirectories(conda.resolve("conda-meta"))
        val python = Files.createDirectories(conda.resolve("bin")).resolve("python")
        val variables = PythonActivation.variables(python, "/usr/bin", isWindows = false)
        assertEquals(conda.toString(), variables["CONDA_PREFIX"])
        assertTrue(variables["PATH"]!!.startsWith(conda.resolve("bin").toString()))
    }

    @Test
    fun `leaves a system interpreter alone`() {
        val bin = temporary.newFolder("usr", "bin").toPath()
        assertTrue(PythonActivation.variables(bin.resolve("python3"), "/usr/bin", isWindows = false).isEmpty())
    }
}
