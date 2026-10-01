package io.github.prashanthsams.pytesthtmlreporter.core

import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque

/**
 * Bounded, breadth-first search for pytest-html-reporter reports.
 *
 * Only two kinds of HTML file are opened: `pytest_html_report.html` (the reporter's default name) and `.html` files
 * in a folder holding `output.json` (which the reporter writes beside every report, whatever the HTML is called).
 * Their first bytes are then checked for the report's signature, so unrelated HTML is never read in full.
 *
 * Virtual environments are skipped by marker (`pyvenv.cfg`, `conda-meta`) as well as by name, so a venv called
 * anything at all is not walked; dependency folders and build output are skipped by name; symbolic links are never
 * followed. Depth, directory count and time are all capped.
 */
class ReportScanner(private val limits: Limits = Limits()) {

    data class Limits(
        val maxDepth: Int = 6,
        val maxDirectories: Int = 20_000,
        val maxReports: Int = 200,
        val timeBudgetMillis: Long = 5_000,
    )

    data class Result(val reports: List<ReportCandidate>, val truncated: Boolean, val visitedDirectories: Int)

    fun scan(roots: Collection<Path>, excludedNames: Set<String>, isCancelled: () -> Boolean = { false }): Result {
        val deadline = System.currentTimeMillis() + limits.timeBudgetMillis
        val found = LinkedHashMap<Path, ReportCandidate>()
        val seenDirectories = HashSet<Path>()
        val queue = ArrayDeque<Pair<Path, Int>>()
        roots.map { it.toAbsolutePath().normalize() }.distinct().forEach { queue.add(it to 0) }
        var visited = 0
        var truncated = false

        while (queue.isNotEmpty()) {
            if (isCancelled() || visited >= limits.maxDirectories || found.size >= limits.maxReports ||
                System.currentTimeMillis() > deadline
            ) {
                truncated = queue.isNotEmpty()
                break
            }
            val (directory, depth) = queue.poll()
            if (!seenDirectories.add(directory)) continue
            visited++

            val listing = list(directory) ?: continue
            if (listing.isEnvironment) continue

            for (file in listing.htmlFiles) {
                val name = file.fileName.toString()
                if ((listing.hasOutputJson || name == ReportPaths.DEFAULT_FILE_NAME) && ReportFiles.isReport(file)) {
                    ReportFiles.candidate(file)?.let { found[file] = it }
                }
            }
            if (depth < limits.maxDepth) {
                for (child in listing.directories) {
                    if (!isExcluded(child.fileName.toString(), excludedNames)) queue.add(child to depth + 1)
                }
            }
        }

        val reports = found.values.sortedByDescending { it.lastModifiedMillis }
        return Result(reports, truncated, visited)
    }

    /** Checks a single folder (no recursion) - for locations the plugin knows about, such as a configured output. */
    fun inspectDirectory(directory: Path): List<ReportCandidate> {
        val listing = list(directory) ?: return emptyList()
        return listing.htmlFiles
            .filter { listing.hasOutputJson || it.fileName.toString() == ReportPaths.DEFAULT_FILE_NAME }
            .filter(ReportFiles::isReport)
            .mapNotNull(ReportFiles::candidate)
    }

    private class Listing(
        val directories: List<Path>,
        val htmlFiles: List<Path>,
        val hasOutputJson: Boolean,
        val isEnvironment: Boolean,
    )

    private fun list(directory: Path): Listing? {
        val directories = ArrayList<Path>()
        val htmlFiles = ArrayList<Path>()
        var hasOutputJson = false
        var isEnvironment = false
        try {
            Files.newDirectoryStream(directory).use { stream ->
                for (entry in stream) {
                    val attributes = ReportFiles.attributes(entry) ?: continue
                    val name = entry.fileName.toString()
                    when {
                        attributes.isSymbolicLink -> Unit
                        attributes.isDirectory -> {
                            if (name == CONDA_MARKER) isEnvironment = true
                            directories.add(entry)
                        }
                        attributes.isRegularFile -> when {
                            name == VENV_MARKER -> isEnvironment = true
                            name == ReportPaths.OUTPUT_JSON -> hasOutputJson = true
                            name.endsWith(".html", ignoreCase = true) -> htmlFiles.add(entry)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            return null
        } catch (_: DirectoryIteratorException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return Listing(directories, htmlFiles, hasOutputJson, isEnvironment)
    }

    companion object {
        private const val VENV_MARKER = "pyvenv.cfg"
        private const val CONDA_MARKER = "conda-meta"

        /** Folders that never hold a report: VCS data, environments, caches, dependencies and build output. */
        val DEFAULT_EXCLUDED_NAMES: Set<String> = linkedSetOf(
            ".git", ".hg", ".svn", ".idea", ".vscode", ".fleet",
            ".venv", "venv", "env", "virtualenv", ".virtualenv", ".conda", "site-packages", "node_modules",
            ".tox", ".nox", ".eggs", "__pycache__", ".pytest_cache", ".mypy_cache", ".ruff_cache", ".hypothesis",
            ".cache", ".gradle", ".kotlin", "build", "dist", "out", "target", "htmlcov",
            ReportPaths.SCREENSHOTS_DIR, ReportPaths.ARCHIVE_DIR, "shards",
        )

        fun isExcluded(name: String, excludedNames: Set<String>): Boolean =
            name in excludedNames || name.endsWith(".egg-info") || name.endsWith(".dist-info")

        /** Parses the comma/newline separated list kept in settings. */
        fun parseExcludedNames(text: String): Set<String> =
            text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).toCollection(linkedSetOf())
    }
}
