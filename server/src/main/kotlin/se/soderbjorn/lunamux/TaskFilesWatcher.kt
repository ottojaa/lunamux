/**
 * Reads the task files other tools leave in a folder and turns them into [TaskFile]s for the
 * [TaskIndex].
 *
 * This file contains [TaskFilesWatcher], which polls the folder and parses each `<id>.json`, the
 * parsed model ([TaskFile], [TaskOpen]) and the private model of the JSON itself ([TaskFileJson]
 * and [OpenJson]). The format is described in `docs/tasks.md`. A file only declares a task: its id,
 * its title, its repository and how to start a session for it. Who is in the task is not written
 * in the file; it is the sessions that carry the task's id. Lunamux only ever reads these files.
 *
 * @see TaskIndex
 * @see AgentTask
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * One task file as written by the tool that owns it.
 *
 * Everything is nullable or defaulted so a file with a missing field fails the explicit checks
 * in [TaskFilesWatcher] (one warning, task skipped) instead of failing JSON decoding with a less
 * helpful message. Unknown fields are ignored, which is how a file written for an older version,
 * with a member list, a `lead` action or a `version`, still parses: those keys say nothing now.
 *
 * @property id the task's id, also what [WindowCommand.OpenTask] names.
 * @property title the task's name.
 * @property repo repository name shown beside the title.
 * @property open how and where to start the session the header's open action starts.
 */
@Serializable
internal data class TaskFileJson(
    val id: String? = null,
    val title: String? = null,
    val repo: String? = null,
    val open: OpenJson? = null,
)

/**
 * The `open` action of a task file.
 *
 * @property command the line typed into a new tab's shell to start the session.
 * @property folder absolute folder the session runs in (a leading `~/` is expanded).
 */
@Serializable
internal data class OpenJson(val command: String? = null, val folder: String? = null)

/**
 * The `open` action of a parsed task: the command the server types and where.
 *
 * @property command the line typed into a new tab's shell; never leaves the server.
 * @property folder canonical absolute folder the tab opens in.
 */
data class TaskOpen(val command: String, val folder: String)

/**
 * A parsed, validated task file: what [TaskFilesWatcher] keeps per file between polls.
 *
 * The folder of the `open` action is already expanded and canonical.
 *
 * @property id the task's id.
 * @property title the task's name.
 * @property repo repository name shown beside the title, or null.
 * @property open the `open` action, or null when the file gives none.
 * @property modified the file's modification time in epoch millis; the earliest sign of when the
 *   task existed, used to place a task that has no session yet.
 */
data class TaskFile(
    val id: String,
    val title: String,
    val repo: String?,
    val open: TaskOpen?,
    val modified: Long,
)

/** What the watcher remembers about one file: enough to see that it changed, and what it parsed to. */
private class Seen(val modified: Long, val length: Long, val parsed: TaskFile?)

/**
 * Watches folders of task files and publishes the tasks they declare.
 *
 * The watcher re-reads a file only when its modified time or size changes ([reload]). The result
 * is [files]. A file that is not valid JSON, or lacks `id` or `title`, or has an `open` action
 * without a command or an absolute folder, is skipped with a single warning until it changes
 * again; nothing here throws.
 *
 * Created once by `main()`; the [TaskIndex] collects [files] and answers every question about a
 * task, including which `open` command the open-task route runs.
 *
 * @param dir the folder holding `<id>.json` files; it may not exist. When two files share an id
 *   the newer file wins.
 * @param warn receives each warning about a file that was skipped; logs by default.
 * @see TaskIndex
 */
class TaskFilesWatcher(
    private val dir: File,
    private val warn: (String) -> Unit = { LoggerFactory.getLogger(TaskFilesWatcher::class.java).warn(it) },
) {
    private val lock = Any()
    private val seen = HashMap<String, Seen>()
    private val _files = MutableStateFlow<List<TaskFile>>(emptyList())

    /** The parsed task files, sorted by id. Collected by the [TaskIndex]. */
    val files: StateFlow<List<TaskFile>> = _files.asStateFlow()

    /**
     * Start watching: poll the folder every 3 seconds.
     *
     * Called once from `main()` with the server's background scope.
     *
     * @param scope the scope the loop runs in; cancelling it stops the watcher.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { reload() }.onFailure { warn("Task folder ${dir.path} could not be read: ${it.message}") }
                delay(POLL_MS)
            }
        }
    }

    /**
     * Re-read the folder: parse files that are new or changed, forget removed ones, then publish.
     *
     * Called by the polling loop; tests call it directly.
     */
    fun reload() {
        val found = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }
            ?.sortedBy { it.name }.orEmpty()
        synchronized(lock) {
            // A duplicate id is reported only when the set of parsed files changed in this
            // reload; otherwise the same warning would repeat on every poll.
            var changed = seen.keys.retainAll(found.map { it.path }.toSet())
            for (f in found) {
                val modified = f.lastModified()
                val length = f.length()
                val before = seen[f.path]
                if (before != null && before.modified == modified && before.length == length) continue
                seen[f.path] = Seen(modified, length, parseFile(f, modified))
                changed = true
            }
            val byId = LinkedHashMap<String, Pair<String, TaskFile>>()
            // Oldest file first so that, for a repeated id, the newest file replaces it.
            for ((path, s) in seen.entries.sortedBy { it.value.modified }) {
                val p = s.parsed ?: continue
                val earlier = byId[p.id]
                if (earlier != null && changed) {
                    warn("Task file ${File(path).name} repeats the id of ${File(earlier.first).name}; using ${File(path).name}")
                }
                byId[p.id] = path to p
            }
            _files.value = byId.values.map { it.second }.sortedBy { it.id }
        }
    }

    private fun parseFile(f: File, modified: Long): TaskFile? {
        val file = try {
            fileJson.decodeFromString(TaskFileJson.serializer(), f.readText())
        } catch (e: Exception) {
            warn("Skipping task file ${f.name}: not a readable task file (${e.message?.lineSequence()?.firstOrNull()})")
            return null
        }
        fun bad(why: String): TaskFile? {
            warn("Skipping task file ${f.name}: $why")
            return null
        }
        val id = file.id?.takeIf { it.isNotBlank() } ?: return bad("missing id")
        val title = file.title?.takeIf { it.isNotBlank() } ?: return bad("missing title")
        val open = file.open?.let {
            val command = it.command?.takeIf { c -> c.isNotBlank() } ?: return bad("missing open.command")
            val folder = absolute(it.folder) ?: return bad("open.folder must be an absolute path")
            TaskOpen(command, folder)
        }
        return TaskFile(id, title, file.repo?.takeIf { it.isNotBlank() }, open, modified)
    }

    private fun absolute(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expanded = if (raw == "~" || raw.startsWith("~/")) System.getProperty("user.home") + raw.drop(1) else raw
        return if (expanded.startsWith("/")) canonical(expanded) else null
    }

    private companion object {
        const val POLL_MS = 3_000L
        val fileJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * The canonical form of a path, or its absolute form when the file system cannot resolve it.
 *
 * Used by the watcher for the folder of a task file's `open` action, so that a symlinked folder
 * compares equal to its target.
 *
 * @param path the path to resolve.
 * @return the canonical path.
 */
internal fun canonical(path: String): String =
    try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }
