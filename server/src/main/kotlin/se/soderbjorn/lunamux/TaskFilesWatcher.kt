/**
 * Reads the task files other tools leave in a folder and turns them into [TaskFile]s for the
 * [TaskIndex].
 *
 * This file contains [TaskFilesWatcher], which polls the folder(s) and parses each `<id>.json`,
 * the parsed model ([TaskFile], [TaskOpen], [TaskFileMember]) and the private model of the JSON
 * itself ([TaskFileJson] and its parts). The format is described in `docs/tasks.md`. Lunamux only
 * ever reads these files. Matching a file's members to session records is the task index's job,
 * not this file's: the watcher only says what the files declare.
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
import java.time.Instant

/**
 * One task file as written by the tool that owns it.
 *
 * Everything is nullable or defaulted so a file with a missing field fails the explicit checks
 * in [TaskFilesWatcher] (one warning, task skipped) instead of failing JSON decoding with a less
 * helpful message. Unknown fields are ignored, which is how `version`, `tag`, `link` and
 * `updatedAt` are accepted without being used.
 *
 * @property id the task's id, also what [WindowCommand.OpenTask] names.
 * @property title the task's name.
 * @property repo repository name shown beside the title.
 * @property open how and where to start the session the header's open action starts.
 * @property lead the same thing under its older name; read as [open] when [open] is absent.
 * @property members the members the file lists.
 */
@Serializable
internal data class TaskFileJson(
    val id: String? = null,
    val title: String? = null,
    val repo: String? = null,
    val open: OpenJson? = null,
    val lead: OpenJson? = null,
    val members: List<MemberJson> = emptyList(),
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
 * How a task file ties a member to a session Lunamux hosts.
 *
 * @property agentSessionId the agent's own session id (Claude's `session_id`).
 * @property folder absolute folder; an open session running in it or below it matches.
 */
@Serializable
internal data class MatchJson(val agentSessionId: String? = null, val folder: String? = null)

/**
 * One member of a task file.
 *
 * @property title the member's name.
 * @property links ticket keys or other short labels.
 * @property match how to find the member's session, if Lunamux hosts it.
 * @property state `needs_you`, `working`, `idle` or `ended`.
 * @property label a state word for the summary line, such as "in review".
 * @property line one line about what the member is doing.
 * @property since ISO-8601 time the member entered [state].
 * @property sessionId the id `claude attach` takes for the member's background session; only the id
 *   is read from the file, the command is always built by the server.
 */
@Serializable
internal data class MemberJson(
    val title: String? = null,
    val links: List<String> = emptyList(),
    val match: MatchJson? = null,
    val state: String? = null,
    val label: String? = null,
    val line: String? = null,
    val since: String? = null,
    val sessionId: String? = null,
)

/**
 * The `open` action of a parsed task: the command the server types and where.
 *
 * @property command the line typed into a new tab's shell; never leaves the server.
 * @property folder canonical absolute folder the tab opens in.
 */
data class TaskOpen(val command: String, val folder: String)

/**
 * One member of a parsed task file, with the match it asks for.
 *
 * @property member the member as the clients get it, state and line still the file's own.
 * @property matchSessionId the agent session id the member asks to be matched by, or null.
 * @property matchFolder canonical folder an open session may sit in or below, or null.
 */
data class TaskFileMember(
    val member: AgentTaskMember,
    val matchSessionId: String?,
    val matchFolder: String?,
)

/**
 * A parsed, validated task file: what [TaskFilesWatcher] keeps per file between polls.
 *
 * Paths are already expanded and canonical, so matching is a plain string comparison.
 *
 * @property id the task's id.
 * @property title the task's name.
 * @property repo repository name shown beside the title, or null.
 * @property open the `open` action, or null when the file gives none.
 * @property members the file's members in order.
 * @property modified the file's modification time in epoch millis.
 */
data class TaskFile(
    val id: String,
    val title: String,
    val repo: String?,
    val open: TaskOpen?,
    val members: List<TaskFileMember>,
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
 * task, including which `open` command the open-task routes run.
 *
 * @param dirs the folders holding `<id>.json` files, in the order they are read; any of them may
 *   not exist. When two files share an id the newer file wins, wherever it lies.
 * @param warn receives each warning about a file that was skipped; logs by default.
 * @see TaskIndex
 */
class TaskFilesWatcher(
    private val dirs: List<File>,
    private val warn: (String) -> Unit = { LoggerFactory.getLogger(TaskFilesWatcher::class.java).warn(it) },
) {
    private val lock = Any()
    private val seen = HashMap<String, Seen>()
    private val _files = MutableStateFlow<List<TaskFile>>(emptyList())

    /** The parsed task files, sorted by id. Collected by the [TaskIndex]. */
    val files: StateFlow<List<TaskFile>> = _files.asStateFlow()

    /**
     * Start watching: poll the folders every 3 seconds.
     *
     * Called once from `main()` with the server's background scope.
     *
     * @param scope the scope the loop runs in; cancelling it stops the watcher.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                runCatching { reload() }.onFailure { warn("Task folders ${dirs.joinToString()} could not be read: ${it.message}") }
                delay(POLL_MS)
            }
        }
    }

    /**
     * Re-read the folders: parse files that are new or changed, forget removed ones, then publish.
     *
     * Called by the polling loop; tests call it directly.
     */
    fun reload() {
        val found = dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }
                ?.sortedBy { it.name }.orEmpty()
        }
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
        val openJson = file.open ?: file.lead
        val open = openJson?.let {
            val command = it.command?.takeIf { c -> c.isNotBlank() } ?: return bad("missing open.command")
            val folder = absolute(it.folder) ?: return bad("open.folder must be an absolute path")
            TaskOpen(command, folder)
        }
        val members = ArrayList<TaskFileMember>()
        val usedKeys = HashSet<String>()
        for ((i, m) in file.members.withIndex()) {
            val state = m.state
            if (state !in STATES) return bad("member ${i + 1} has state '$state'; use needs_you, working, idle or ended")
            val mTitle = m.title?.takeIf { it.isNotBlank() } ?: return bad("member ${i + 1} has no title")
            val folder = m.match?.folder?.let { absolute(it) ?: return bad("member ${i + 1} match.folder must be an absolute path") }
            val sessionId = m.sessionId?.trim()?.takeIf { it.isNotEmpty() }
            // A session id is the most stable name for a member, but two members may carry the same
            // one; the second gets its place in the file appended so that no two rows share a key.
            // '#' is not allowed in a session id, so a numbered key never equals a real one.
            val key = when {
                sessionId != null && usedKeys.add(sessionId) -> sessionId
                else -> "${sessionId.orEmpty()}#${i + 1}".also { usedKeys += it }
            }
            members += TaskFileMember(
                member = AgentTaskMember(
                    key = key, title = mTitle, links = m.links, state = state!!,
                    label = m.label?.takeIf { it.isNotBlank() }, line = m.line?.takeIf { it.isNotBlank() },
                    sinceMillis = instant(m.since) ?: modified, sessionId = sessionId,
                ),
                matchSessionId = m.match?.agentSessionId?.takeIf { it.isNotBlank() },
                matchFolder = folder,
            )
        }
        return TaskFile(id, title, file.repo?.takeIf { it.isNotBlank() }, open, members, modified)
    }

    private fun absolute(path: String?): String? {
        val raw = path?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val expanded = if (raw == "~" || raw.startsWith("~/")) System.getProperty("user.home") + raw.drop(1) else raw
        return if (expanded.startsWith("/")) canonical(expanded) else null
    }

    private fun instant(text: String?): Long? =
        text?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private companion object {
        const val POLL_MS = 3_000L
        val STATES = setOf("needs_you", "working", "idle", "ended")
        val fileJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * The canonical form of a path, or its absolute form when the file system cannot resolve it.
 *
 * Used by the watcher for the folders in task files and by [TaskIndex] for the folders of session
 * records, so that a symlinked folder compares equal to its target.
 *
 * @param path the path to resolve.
 * @return the canonical path.
 */
internal fun canonical(path: String): String =
    try { File(path).canonicalPath } catch (_: Exception) { File(path).absolutePath }
