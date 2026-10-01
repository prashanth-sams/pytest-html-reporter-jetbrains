package io.github.prashanthsams.pytesthtmlreporter.discovery

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.util.messages.Topic
import io.github.prashanthsams.pytesthtmlreporter.core.ReportCandidate
import io.github.prashanthsams.pytesthtmlreporter.core.ReportFiles
import io.github.prashanthsams.pytesthtmlreporter.core.ReportPaths
import io.github.prashanthsams.pytesthtmlreporter.core.ReportScanner
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettings
import io.github.prashanthsams.pytesthtmlreporter.settings.ReporterSettingsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** The reports known for a project, newest first. */
data class DiscoveryState(
    val reports: List<ReportCandidate>,
    val scanning: Boolean,
    val truncated: Boolean,
    val scannedAtMillis: Long?,
)

fun interface ReportDiscoveryListener {
    fun discoveryChanged(state: DiscoveryState)

    companion object {
        @Topic.ProjectLevel
        val TOPIC: Topic<ReportDiscoveryListener> = Topic(ReportDiscoveryListener::class.java, Topic.BroadcastDirection.NONE)
    }
}

/**
 * Finds reports without slowing the IDE: one bounded background scan when first needed (and on an explicit rescan or a
 * change of discovery settings); after that, only the folders named by VFS events for `.html` or `output.json` files
 * are re-inspected, in debounced batches. The project tree is never walked again just because something changed.
 */
@Service(Service.Level.PROJECT)
class ReportDiscoveryService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    @Volatile
    var state: DiscoveryState = DiscoveryState(emptyList(), scanning = false, truncated = false, scannedAtMillis = null)
        private set

    private var scanJob: Job? = null
    private val pendingDirectories: MutableSet<Path> = ConcurrentHashMap.newKeySet()
    private val flushScheduled = AtomicBoolean(false)

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) = onFileEvents(events)
        })
        connection.subscribe(ReporterSettingsListener.TOPIC, ReporterSettingsListener { rescan() })
    }

    /** Starts the first scan if none has run yet. */
    fun ensureScanned() {
        if (state.scannedAtMillis == null && scanJob?.isActive != true) rescan()
    }

    /** A full bounded scan, replacing any scan in progress. */
    @Synchronized
    fun rescan(): Job {
        scanJob?.cancel()
        publish(state.copy(scanning = true))
        val job = scope.launch(Dispatchers.IO) {
            val roots = scanRoots()
            val settings = ReporterSettings.getInstance(project).state
            val scanner = ReportScanner(ReportScanner.Limits(maxDepth = settings.discoveryDepth.coerceIn(1, MAX_DEPTH)))
            val excluded = ReportScanner.parseExcludedNames(settings.excludedDirectories.orEmpty())
            val result = scanner.scan(roots, excluded) { !isActive }
            val explicit = knownLocations().flatMap(scanner::inspectDirectory)
            if (!isActive) return@launch
            val merged = (result.reports + explicit).associateBy { it.file }.values.sortedByDescending { it.lastModifiedMillis }
            LOG.debug { "Report scan of $roots: ${merged.size} report(s), ${result.visitedDirectories} folder(s), truncated=${result.truncated}" }
            publish(DiscoveryState(merged, scanning = false, truncated = result.truncated, scannedAtMillis = System.currentTimeMillis()))
        }
        scanJob = job
        return job
    }

    /** Adds or updates one report, e.g. the one a run just wrote. */
    fun register(candidate: ReportCandidate) {
        val reports = (state.reports.filterNot { it.file == candidate.file } + candidate).sortedByDescending { it.lastModifiedMillis }
        publish(state.copy(reports = reports))
    }

    /** Re-reads the reports in [directories] (no recursion), e.g. after a run outside the IDE's control. */
    fun refreshDirectories(directories: Collection<Path>) {
        pendingDirectories += directories
        scheduleFlush()
    }

    /** Folders worth checking directly whatever the exclusions say: the configured output and the usual names. */
    fun knownLocations(): List<Path> {
        val base = projectRoot() ?: return emptyList()
        val settings = ReporterSettings.getInstance(project).state
        val workingDirectory = settings.workingDirectory?.takeIf(String::isNotBlank)?.let { base.resolveSafely(it) } ?: base
        val output = settings.outputPath?.takeIf(String::isNotBlank)?.let {
            ReportPaths.resolve(it, workingDirectory).parent
        }
        return listOfNotNull(output, workingDirectory, workingDirectory.resolve("report"), workingDirectory.resolve("reports"))
            .distinct()
    }

    private suspend fun scanRoots(): List<Path> {
        val base = projectRoot()
        val contentRoots = readAction {
            ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.fileSystem.getNioPath(it) }
        }
        val additional = ReporterSettings.getInstance(project).state.additionalLocations.orEmpty()
            .split(',', '\n').map(String::trim).filter(String::isNotEmpty)
            .mapNotNull { location -> (base ?: return@mapNotNull null).resolveSafely(location) }
        val roots = (listOfNotNull(base) + contentRoots.filter { root -> base == null || !root.startsWith(base) } + additional)
        return roots.map { it.toAbsolutePath().normalize() }.distinct()
    }

    private fun projectRoot(): Path? = project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }

    private fun onFileEvents(events: List<VFileEvent>) {
        // Compare in one form: '/'-separated and NFC (macOS may report decomposed names in file events).
        val base = ReportFiles.comparablePath(project.basePath ?: return)
        val known = state.reports.map { ReportFiles.comparablePath(it.file.toString()) to it }
        for (event in events) {
            val path = ReportFiles.comparablePath(event.path)
            val name = path.substringAfterLast('/')
            val relevant = name.endsWith(".html", ignoreCase = true) || name == ReportPaths.OUTPUT_JSON
            if (relevant && (FileUtil.isAncestor(base, path, false) || known.any { (file, _) -> parentOf(file) == parentOf(path) })) {
                parentOf(path).toPathOrNull()?.let(pendingDirectories::add)
            }
            if (event is VFileDeleteEvent || event is VFileMoveEvent) {
                // A deleted or moved folder takes every report under it along.
                known.filter { (file, _) -> FileUtil.isAncestor(path, file, true) }.forEach { (_, report) -> pendingDirectories.add(report.file.parent) }
            }
        }
        if (pendingDirectories.isNotEmpty()) scheduleFlush()
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MILLIS)
            flushScheduled.set(false)
            flush()
        }
    }

    private suspend fun flush() {
        val directories = pendingDirectories.toList()
        pendingDirectories.removeAll(directories.toSet())
        if (directories.isEmpty()) return
        val scanner = ReportScanner()
        val updated = withContext(Dispatchers.IO) {
            val kept = state.reports.filterNot { it.file.parent in directories }
            val inspected = directories.flatMap(scanner::inspectDirectory)
            (kept + inspected).filter { ReportFiles.attributes(it.file) != null }
        }
        publish(state.copy(reports = updated.associateBy { it.file }.values.sortedByDescending { it.lastModifiedMillis }))
    }

    private fun publish(newState: DiscoveryState) {
        state = newState
        if (!project.isDisposed) project.messageBus.syncPublisher(ReportDiscoveryListener.TOPIC).discoveryChanged(newState)
    }

    override fun dispose() = Unit

    companion object {
        private val LOG = logger<ReportDiscoveryService>()
        private const val DEBOUNCE_MILLIS = 500L
        private const val MAX_DEPTH = 12

        fun getInstance(project: Project): ReportDiscoveryService = project.service()

        private fun parentOf(path: String): String = path.substringBeforeLast('/', path)

        private fun String.toPathOrNull(): Path? = try {
            Path.of(this)
        } catch (_: InvalidPathException) {
            null
        }

        internal fun Path.resolveSafely(other: String): Path? = try {
            resolve(other).normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }
}
