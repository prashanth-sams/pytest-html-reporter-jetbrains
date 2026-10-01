package io.github.prashanthsams.pytesthtmlreporter.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Fixtures generated from released pytest-html-reporter packages by scripts/generate-fixtures.sh. */
object TestData {
    val root: Path = Path.of("src", "test", "testData").toAbsolutePath()

    fun report(version: String): Path = root.resolve("reports").resolve(version)

    fun probe(name: String): String = Files.readString(root.resolve("probe").resolve(name))

    /** Copies a fixture report folder (HTML, output.json, screenshots) into [target]. */
    fun copyReport(version: String, target: Path, fileName: String = ReportPaths.DEFAULT_FILE_NAME): Path {
        val source = report(version)
        Files.createDirectories(target)
        Files.walk(source).use { stream ->
            stream.forEach { file ->
                val relative = source.relativize(file)
                val destination = if (relative.toString() == ReportPaths.DEFAULT_FILE_NAME) target.resolve(fileName) else target.resolve(relative.toString())
                if (Files.isDirectory(file)) Files.createDirectories(destination)
                else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return target.resolve(fileName)
    }
}
