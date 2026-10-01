package io.github.prashanthsams.pytesthtmlreporter.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import io.github.prashanthsams.pytesthtmlreporter.core.PythonActivation
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** The environment pytest and the probe run in: the IDE's console environment, activation, then the user's variables. */
internal class RunEnvironment private constructor(
    private val variables: Map<String, String>,
    private val passParentEnvironment: Boolean,
    /** Everything the process will see, for predictions that depend on the environment (expandvars, autoload). */
    val effective: Map<String, String>,
) {
    fun applyTo(command: GeneralCommandLine): GeneralCommandLine = command
        .withParentEnvironmentType(
            if (passParentEnvironment) GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else GeneralCommandLine.ParentEnvironmentType.NONE,
        )
        .withEnvironment(variables)
        .withCharset(StandardCharsets.UTF_8)

    companion object {
        fun create(interpreter: Path, userVariables: Map<String, String>, passParentEnvironment: Boolean): RunEnvironment {
            val parent = if (passParentEnvironment) EnvironmentUtil.getEnvironmentMap() else emptyMap()
            val pathKey = parent.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: "PATH"
            val activation = PythonActivation.variables(interpreter, parent[pathKey], SystemInfo.isWindows)
                .mapKeys { (key, _) -> if (key == "PATH") pathKey else key }

            val variables = LinkedHashMap<String, String>()
            variables.putAll(activation)
            // Unbuffered UTF-8 output keeps the console live and readable whatever the system locale is.
            variables["PYTHONUNBUFFERED"] = "1"
            variables["PYTHONIOENCODING"] = "utf-8"
            variables.putAll(userVariables)

            val effective = LinkedHashMap(parent)
            effective.putAll(variables)
            return RunEnvironment(variables, passParentEnvironment, effective)
        }
    }
}
