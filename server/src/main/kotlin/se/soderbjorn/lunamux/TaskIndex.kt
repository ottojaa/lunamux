/**
 * The one place that decides which task each agent session belongs to.
 *
 * This file contains [TaskIndex], which combines the tasks agents made through the MCP tools
 * (rows in `agent_tasks`, sessions that carry a `task_id`), the tasks that task files declare
 * ([TaskFile]) and the session records, and publishes the result as [AgentTask]s plus an
 * assignment of every record to at most one task. It also holds the operations behind the MCP task
 * tools, so that the tools and the overview agree on every rule. The clients never work out
 * membership themselves.
 *
 * @see TaskFilesWatcher
 * @see AgentSessionTracker
 * @see AgentTask
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.AgentTaskRow
import se.soderbjorn.lunamux.persistence.SettingsRepository.TaskMetaRow
import java.util.UUID

/**
 * Thrown by [TaskIndex] when a request names a task or a session that does not exist, or a task
 * that cannot be changed that way. The message names the offender and says what to do instead; the
 * MCP tools hand it to the agent as the tool's error.
 */
class TaskException(message: String) : IllegalArgumentException(message)

/**
 * What [TaskIndex] last decided.
 *
 * @property tasks the tasks the clients get, sorted by id.
 * @property assignment the task id of every record that belongs to one, by record id. A record
 *   missing from it is a task of its own.
 */
data class TaskSnapshot(
    val tasks: List<AgentTask> = emptyList(),
    val assignment: Map<String, String> = emptyMap(),
)

/**
 * One line of a task listing: a session or a file-declared member.
 *
 * @property sessionId the `LUNAMUX_SESSION_ID` of the pane the session runs in, or null when it is not
 *   open (ended, or a member Lunamux does not host).
 * @property title what to call it.
 * @property state `needs_you`, `working`, `idle` or `ended`.
 */
data class TaskListMember(val sessionId: String?, val title: String, val state: String)

/**
 * One task as the `list_tasks` MCP tool reports it.
 *
 * @property id the task's id.
 * @property title the task's name.
 * @property source `file` when a task file declares it, else `agents`.
 * @property members its sessions and file members.
 * @property ownSessionId the terminal session id (`LUNAMUX_SESSION_ID`) the pane whose session speaks for
 *   the task has now, or null when none was named or that pane is gone. The task remembers the pane,
 *   not this id, so the id is current even after a restart gave the pane a new one.
 * @property status the task's one-line status, or null.
 */
data class TaskListing(
    val id: String,
    val title: String,
    val source: String,
    val members: List<TaskListMember>,
    val ownSessionId: String? = null,
    val status: String? = null,
)

/**
 * Decides each session's task and carries the operations that change it.
 *
 * A session's task is, in this order: the task its record names (an agent put it there through
 * `add_to_task` or `create_window`), the task file member whose `match.agentSessionId` equals the
 * session's, the task file member whose `match.folder` holds the newest open session, or none. A
 * task file and an agent-made task with one id are one task; the file's title wins. A task
 * named by no file and no row is ignored, so removing a task file does not leave sessions pointing
 * at a ghost.
 *
 * Created once by `main()`. [start] keeps [state] current as files and records change; the
 * mutating methods publish immediately so a tool's answer already reflects its own change.
 *
 * @param repo the store of agent-made tasks.
 * @param tracker the session records; tasks are written back to it.
 * @param files the parsed task files, usually [TaskFilesWatcher.files].
 * @param leafOfSession maps a pane's `LUNAMUX_SESSION_ID` to its layout leaf id, or null for a pane
 *   that does not exist; replaced in tests.
 * @param sessionOfLeaf the reverse: a layout leaf id to the `LUNAMUX_SESSION_ID` of the terminal in it
 *   now, or null when the pane is gone or has no terminal; replaced in tests.
 * @param clock epoch-millis time source; replaced in tests.
 * @param newSuffix mints the short random suffix of a new task id; replaced in tests.
 * @see TaskSnapshot
 */
class TaskIndex(
    private val repo: SettingsRepository,
    private val tracker: AgentSessionTracker,
    private val files: StateFlow<List<TaskFile>>,
    private val leafOfSession: (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSuffix: () -> String = { UUID.randomUUID().toString().take(SUFFIX_LENGTH) },
    private val sessionOfLeaf: (String) -> String? = { null },
) {
    private val lock = Any()
    private var rows: List<AgentTaskRow> = runCatching { repo.loadAgentTasks() }.getOrDefault(emptyList())
    private var meta: Map<String, TaskMetaRow> =
        runCatching { repo.loadTaskMeta() }.getOrDefault(emptyList()).associateBy { it.taskId }
    private val _state = MutableStateFlow(TaskSnapshot())

    /** The current tasks and assignment; collected per window socket by `WindowRoutes`. */
    val state: StateFlow<TaskSnapshot> = _state.asStateFlow()

    init {
        recompute()
    }

    /**
     * Keep [state] current: recompute whenever the session records or the task files change.
     *
     * Called once from `main()` with the server's background scope.
     *
     * @param scope the scope the collector runs in; cancelling it stops the index.
     */
    fun start(scope: CoroutineScope) {
        scope.launch { combine(tracker.summaries, files) { _, _ -> }.collect { recompute() } }
    }

    /**
     * Look up a task file by id, for the routes that run its `open` action or attach to a member.
     *
     * @param taskId the task's id.
     * @return the parsed file, or null when no file declares the task.
     */
    fun fileTask(taskId: String): TaskFile? = files.value.firstOrNull { it.id == taskId }

    /**
     * The name of a task, whether or not any session of it is in memory.
     *
     * Called by [AgentSessionSearch] to label a match and to find the sessions of a task whose
     * title matches the query. Unlike [state], which lists only tasks that hold a session in
     * memory, this knows every task file and every agent-made task row. A task file's title wins
     * over the row with the same id.
     *
     * @param taskId the task's id.
     * @return the title, or null when no file and no row has that id.
     */
    fun titleOf(taskId: String): String? = synchronized(lock) {
        files.value.firstOrNull { it.id == taskId }?.title ?: rows.firstOrNull { it.id == taskId }?.title
    }

    /**
     * The ids of the tasks whose title contains [text], ignoring case.
     *
     * Called by [AgentSessionSearch] before it queries, so sessions that ended long ago are
     * found through their task even though no field of their own matches.
     *
     * @param text what the user typed, already trimmed.
     * @return task ids from files and agent-made rows, using each task's effective title.
     */
    fun taskIdsTitled(text: String): Set<String> = synchronized(lock) {
        (files.value.map { it.id } + rows.map { it.id }).toSet()
            .filter { titleOf(it)?.contains(text, ignoreCase = true) == true }
            .toSet()
    }

    /**
     * The task a record belongs to, as the overview shows it.
     *
     * Called by [AgentSessionSearch]. A session in memory takes the assignment [state] made; one
     * that is only in the database falls back to the task id it was stored with, if that task
     * still exists.
     *
     * @param record the session record.
     * @return the task id, or null when the session is a task of its own.
     */
    fun taskIdOf(record: AgentSessionRecord): String? =
        _state.value.assignment[record.id] ?: record.taskId?.takeIf { titleOf(it) != null }

    /**
     * Rebuild [state] from the files, the task rows and the records. Cheap enough to run on every
     * record change; called by [start]'s collector and by every mutating method.
     */
    fun recompute() {
        synchronized(lock) {
            val records = tracker.snapshot()
            followOwnSessions(records)
            val snapshot = resolve(records, files.value, rows, meta)
            val fresh = snapshot.tasks.filter { it.id !in meta }
            if (fresh.isNotEmpty()) {
                // A task's start is fixed the first time it is seen, so its place in the overview
                // never moves when its sessions come and go afterwards.
                for (t in fresh) t.startedAtMillis?.let { repo.stampTaskStart(t.id, it) }
                meta = meta + fresh.mapNotNull { t -> t.startedAtMillis?.let { t.id to TaskMetaRow(t.id, it) } }
            }
            _state.value = snapshot
        }
    }

    /**
     * Keep each task's own session where the conversation is: give a row stored the old way a pane
     * to guess, then move a row to the pane its conversation was reopened in.
     *
     * A task named before layout leaf ids were stored remembers its own session only as a terminal
     * session id, which each start hands to whatever pane comes that many places in. When a
     * session that is not ended runs on that id, its pane is taken as the pane, as a best guess:
     * the id may name another pane than it did when the task was named, and the guess is stored, so
     * `set_task_lead` is the way to correct it. An ended session proves nothing (right after a
     * start every record is ended, and the newest on the id may have run in another pane), so it is
     * never used; the task keeps resolving by terminal id until a running session shows up.
     *
     * A resume always opens a new pane, and the tracker then either moves the ended record to that
     * pane's leaf or starts a record there that takes over the conversation id. So that the task
     * does not stay with the old pane (and offer "resume" for a conversation that is running), each
     * row remembers the agent's conversation id of the session last seen in its pane. When nothing
     * runs in the pane any more and that conversation runs in another pane, the row moves there.
     * A new agent that starts in the old pane takes the remembered conversation over instead, so
     * the pane keeps speaking for the task.
     *
     * Called by [recompute] with [lock] held, before it resolves.
     *
     * @param records every record the tracker holds.
     */
    private fun followOwnSessions(records: List<AgentSessionRecord>) {
        for ((taskId, row) in meta.toList()) {
            var current = row
            val terminalId = current.leadSession
            if (current.leadLeaf == null && terminalId != null) {
                val leaf = records.filter { it.terminalSessionId == terminalId && it.state != ENDED }
                    .maxByOrNull { it.createdAt }?.leafId
                if (leaf != null) {
                    repo.setTaskLeadLeaf(taskId, leaf)
                    current = current.copy(leadLeaf = leaf)
                }
            }
            if (current.leadLeaf == null) {
                if (current !== row) meta = meta + (taskId to current)
                continue
            }
            val here = recordBoundTo(records, current)
            val conversation = current.leadAgentSession
            if ((here == null || here.state == ENDED) && conversation != null) {
                val moved = records.filter {
                    it.agentSessionId == conversation && it.state != ENDED && it.leafId != null && it.leafId != current.leadLeaf
                }.maxByOrNull { it.createdAt }
                if (moved != null) {
                    repo.moveTaskLead(taskId, moved.terminalSessionId, moved.leafId!!)
                    meta = meta + (taskId to current.copy(leadSession = moved.terminalSessionId, leadLeaf = moved.leafId))
                    continue
                }
            }
            val seen = here?.agentSessionId
            if (seen != null && seen != conversation) {
                repo.setTaskLeadAgentSession(taskId, seen)
                current = current.copy(leadAgentSession = seen)
            }
            if (current !== row) meta = meta + (taskId to current)
        }
    }

    /**
     * Make a new task.
     *
     * Called by the MCP `create_task` tool.
     *
     * The task's own session is the caller's pane when the caller is a known pane, else the first
     * of [sessionIds], else none. The task remembers the pane by its layout leaf id.
     *
     * @param title the task's name; blank is refused.
     * @param sessionIds panes (`LUNAMUX_SESSION_ID` values) to put in it straight away.
     * @param callerSessionId the pane the calling agent runs in, when the request says so.
     * @return the new task's id.
     * @throws TaskException when the title is blank or a session id names no pane.
     */
    fun createTask(title: String, sessionIds: List<String>, callerSessionId: String? = null): String {
        val clean = cleanTitle(title)
        val targets = sessionIds.map(::targetOf)
        val own = callerSessionId?.takeIf(::isKnownPane) ?: sessionIds.firstOrNull()
        val id = synchronized(lock) {
            val known = rows.map { it.id }.toSet() + files.value.map { it.id }
            var candidate: String
            do { candidate = "${slug(clean)}-${newSuffix()}" } while (candidate in known)
            val now = clock()
            val row = AgentTaskRow(candidate, clean, now, now)
            repo.saveAgentTask(row)
            rows = rows + row
            candidate
        }
        recompute()
        targets.forEach { apply(it, id) }
        recompute()
        if (own != null) storeOwnSession(id, own)
        return id
    }

    /**
     * Name the session that speaks for a task.
     *
     * Called by the MCP `set_task_lead` tool. The pane is looked up the way `add_to_task` looks up
     * its panes, so the same mistake gets the same error. It is not moved into the task. The task
     * remembers the pane by its layout leaf id, so it stays with the pane when a restart gives it
     * another terminal session id.
     *
     * @param taskId an agent-made task or a task file's id.
     * @param sessionId the pane (`LUNAMUX_SESSION_ID` value) whose session speaks for the task.
     * @throws TaskException when the task is unknown or the id names no pane.
     */
    fun setOwnSession(taskId: String, sessionId: String) {
        requireTask(taskId)
        targetOf(sessionId)
        storeOwnSession(taskId, sessionId)
    }

    /**
     * Set or clear a task's one-line status.
     *
     * Called by the MCP `set_task_status` tool. Line breaks become single spaces, the ends are
     * trimmed and the text is cut at [STATUS_MAX] characters; nothing left clears the status.
     *
     * @param taskId an agent-made task or a task file's id.
     * @param text what the work is doing now.
     * @param by the pane that reports it, when the request says so.
     * @return the stored status, or null when it was cleared.
     * @throws TaskException when the task is unknown.
     */
    fun setStatus(taskId: String, text: String, by: String?): String? {
        requireTask(taskId)
        val clean = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim().take(STATUS_MAX).trim().ifEmpty { null }
        synchronized(lock) {
            val now = clock()
            ensureMeta(taskId)
            repo.setTaskStatus(taskId, clean, by, now)
            meta = meta + (taskId to meta.getValue(taskId).copy(
                status = clean, statusBy = clean?.let { by }, statusAt = clean?.let { now },
            ))
        }
        recompute()
        return clean
    }

    /**
     * Store the pane that speaks for a task: its layout leaf id, and the terminal session id it has
     * now. A pane with neither an open record nor a leaf (it cannot be told apart from a pane that
     * is gone) is stored by terminal session id alone and picked up again by [followOwnSessions].
     */
    private fun storeOwnSession(taskId: String, sessionId: String) {
        val leaf = tracker.openRecordOf(sessionId)?.leafId ?: leafOfSession(sessionId)
        synchronized(lock) {
            ensureMeta(taskId)
            repo.setTaskLead(taskId, sessionId, leaf)
            meta = meta + (taskId to meta.getValue(taskId).copy(leadSession = sessionId, leadLeaf = leaf, leadAgentSession = null))
        }
        recompute()
    }

    /** Make sure the task has a stored row to write to; call with [lock] held. */
    private fun ensureMeta(taskId: String) {
        if (taskId in meta) return
        val at = _state.value.tasks.firstOrNull { it.id == taskId }?.startedAtMillis ?: clock()
        repo.stampTaskStart(taskId, at)
        meta = meta + (taskId to TaskMetaRow(taskId, at))
    }

    private fun isKnownPane(sessionId: String): Boolean =
        tracker.openRecordOf(sessionId) != null || leafOfSession(sessionId) != null

    /**
     * Move sessions into a task, out of whatever task they were in.
     *
     * Called by the MCP `add_to_task` tool and by `create_window` with a task. A pane with no agent
     * yet is promised the task for the next session that starts in it.
     *
     * @param taskId an agent-made task or a task file's id.
     * @param sessionIds panes (`LUNAMUX_SESSION_ID` values) to move.
     * @throws TaskException when the task is unknown or a session id names no pane; nothing is
     *   changed in that case.
     */
    fun addToTask(taskId: String, sessionIds: List<String>) {
        requireTask(taskId)
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, taskId) }
        recompute()
    }

    /**
     * Take sessions out of the task an agent put them in, so each is a task of its own again.
     *
     * Called by the MCP `remove_from_task` tool. A session that a task file's member still matches
     * stays in that task: the file, not the agent, placed it there.
     *
     * @param sessionIds panes (`LUNAMUX_SESSION_ID` values) to take out.
     * @return the task each pane is in afterwards (null for none), by pane id.
     * @throws TaskException when a session id names no pane; nothing is changed in that case.
     */
    fun removeFromTask(sessionIds: List<String>): Map<String, String?> {
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, null) }
        recompute()
        val snapshot = _state.value
        return sessionIds.associateWith { sid -> tracker.openRecordOf(sid)?.let { snapshot.assignment[it.id] } }
    }

    /**
     * Rename an agent-made task.
     *
     * Called by the MCP `rename_task` tool.
     *
     * @param taskId the task's id.
     * @param title the new name; blank is refused.
     * @throws TaskException when the task is unknown or a task file declares it (its file owns the
     *   title).
     */
    fun renameTask(taskId: String, title: String) {
        val clean = cleanTitle(title)
        synchronized(lock) {
            if (files.value.any { it.id == taskId }) {
                throw TaskException("Task '$taskId' is declared by a task file, which owns its title. Edit the file's title instead.")
            }
            val row = rows.firstOrNull { it.id == taskId } ?: throw unknownTask(taskId)
            val renamed = row.copy(title = clean, updatedAt = clock())
            repo.saveAgentTask(renamed)
            rows = rows.map { if (it.id == taskId) renamed else it }
        }
        recompute()
    }

    /**
     * Every task there is, with what is in it.
     *
     * Called by the MCP `list_tasks` tool. Includes agent-made tasks that hold no session yet.
     *
     * @return the tasks, task files first, each sorted by id.
     */
    fun listing(): List<TaskListing> {
        val records = tracker.snapshot()
        val snapshot = _state.value
        val byTask = records.filter { it.id in snapshot.assignment }.groupBy { snapshot.assignment.getValue(it.id) }
        fun sessionsOf(taskId: String, claimed: Set<String>): List<TaskListMember> =
            byTask[taskId].orEmpty().filter { it.id !in claimed }.map { r ->
                TaskListMember(
                    sessionId = r.terminalSessionId.takeIf { r.state != ENDED },
                    title = r.title ?: r.firstPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                        ?: "${r.agent} session",
                    state = r.state,
                )
            }
        val fileListings = files.value.sortedBy { it.id }.map { f ->
            val task = snapshot.tasks.firstOrNull { it.id == f.id }
            val claimed = task?.members?.mapNotNull { it.recordId }?.toSet().orEmpty()
            val declared = task?.members.orEmpty().map { m ->
                val rec = m.recordId?.let { id -> records.firstOrNull { it.id == id } }
                TaskListMember(rec?.terminalSessionId?.takeIf { rec.state != ENDED }, m.title, m.state)
            }
            TaskListing(f.id, f.title, "file", declared + sessionsOf(f.id, claimed), ownSessionOf(f.id), statusOf(f.id))
        }
        val fileIds = files.value.map { it.id }.toSet()
        val agentListings = rows.filter { it.id !in fileIds }.sortedBy { it.id }
            .map { TaskListing(it.id, it.title, "agents", sessionsOf(it.id, emptySet()), ownSessionOf(it.id), statusOf(it.id)) }
        return fileListings + agentListings
    }

    /**
     * The terminal session id of the pane that speaks for a task, as it is now.
     *
     * A task that remembers a layout leaf gets the id of the terminal in that leaf, or null when
     * the pane is gone. A task stored the old way has only the terminal session id it was given, and
     * that is what it reports.
     *
     * Called by the task panel's button handler to find where a button's text is typed, and by
     * [listing] for `list_tasks`.
     *
     * @param taskId the task's id.
     * @return the terminal session id of the task's own session, or null when none was named or
     *   its pane is gone.
     */
    fun ownSessionOf(taskId: String): String? = synchronized(lock) {
        val row = meta[taskId] ?: return@synchronized null
        row.leadLeaf?.let(sessionOfLeaf) ?: row.leadSession.takeIf { row.leadLeaf == null }
    }

    private fun statusOf(taskId: String): String? = synchronized(lock) { meta[taskId]?.status }

    /** A pane or a record an operation applies to: the open record when an agent runs, else the pane's leaf. */
    private class Target(val recordId: String?, val leafId: String?)

    private fun targetOf(sessionId: String): Target {
        val rec = tracker.openRecordOf(sessionId)
        val leaf = rec?.leafId ?: leafOfSession(sessionId)
        if (rec == null && leaf == null) {
            throw TaskException("Unknown session '$sessionId'. Use a session id from list_sessions, or \$LUNAMUX_SESSION_ID for your own pane.")
        }
        return Target(rec?.id, leaf)
    }

    private fun apply(target: Target, taskId: String?) {
        if (target.recordId != null) tracker.setTask(target.recordId, taskId)
        else if (target.leafId != null) tracker.setPendingTask(target.leafId, taskId)
    }

    /**
     * The panes a task's sessions are in: the layout leaf of each of its open sessions, and of each
     * pane promised the task whose agent has not started.
     *
     * Called by the MCP `create_window` and `run_command` tools to open a task's next window in the
     * tab the task already uses. A session that has ended no longer counts, because its pane is
     * gone or only holds a shell.
     *
     * @param taskId the task's id.
     * @return layout leaf ids, in no particular order; empty when the task has no pane.
     */
    fun openLeavesOf(taskId: String): List<String> {
        val sessions = tracker.snapshot()
            .filter { isOpen(it) && taskIdOf(it) == taskId }
            .mapNotNull { it.leafId }
        return sessions + tracker.pendingLeavesOf(taskId)
    }

    /**
     * Check that a task exists, before something is created for it.
     *
     * Called by `create_window` so a mistyped task id fails before a pane is opened.
     *
     * @param taskId the task's id.
     * @throws TaskException when no agent-made task and no task file has that id.
     */
    fun requireTask(taskId: String) {
        val known = synchronized(lock) { rows.any { it.id == taskId } } || files.value.any { it.id == taskId }
        if (!known) throw unknownTask(taskId)
    }

    private fun unknownTask(taskId: String) =
        TaskException("Unknown task '$taskId'. Use list_tasks to see the tasks there are, or create_task to make one.")

    private fun cleanTitle(title: String): String {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) throw TaskException("A task needs a title.")
        return clean.take(TITLE_MAX)
    }

    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(SLUG_MAX).trim('-').ifEmpty { "task" }

    internal companion object {
        private const val ENDED = "ended"
        private const val TITLE_MAX = 120
        private const val SLUG_MAX = 40
        private const val SUFFIX_LENGTH = 4
        const val STATUS_MAX = 120

        /**
         * Work out the tasks and every record's task from the three inputs. Pure, so tests and
         * [recompute] share it.
         *
         * @param records the session records in memory.
         * @param files the parsed task files.
         * @param rows the agent-made tasks.
         * @param meta what is stored per task (start, own session, status), by task id.
         * @return the tasks to push and the assignment of records to them.
         */
        fun resolve(
            records: List<AgentSessionRecord>,
            files: List<TaskFile>,
            rows: List<AgentTaskRow>,
            meta: Map<String, TaskMetaRow> = emptyMap(),
        ): TaskSnapshot {
            val fileIds = files.map { it.id }.toSet()
            val known = fileIds + rows.map { it.id }
            val assignment = LinkedHashMap<String, String>()
            for (r in records) r.taskId?.takeIf { it in known }?.let { assignment[r.id] = it }

            val canon = HashMap<String, String>()
            fun cwdOf(r: AgentSessionRecord) = r.cwd?.let { canon.getOrPut(it) { canonical(it) } }
            val newestFirst = records.sortedByDescending { it.createdAt }
            val claimed = HashSet<String>()
            val fileTasks = files.sortedBy { it.id }.map { f ->
                val members = f.members.map { fm ->
                    val m = fm.member
                    val candidates = newestFirst.filter { it.id !in claimed && assignment[it.id].let { t -> t == null || t == f.id } }
                    val rec = fm.matchSessionId?.let { sid -> candidates.firstOrNull { it.agentSessionId == sid } }
                        ?: fm.matchFolder?.let { folder ->
                            candidates.firstOrNull { r ->
                                val cwd = cwdOf(r)
                                isOpen(r) && cwd != null && (cwd == folder || cwd.startsWith(folder.trimEnd('/') + "/"))
                            }
                        }
                    if (rec == null) m else {
                        claimed += rec.id
                        assignment[rec.id] = f.id
                        if (!isOpen(rec)) m.copy(recordId = rec.id) else m.copy(
                            state = rec.state,
                            line = (if (rec.state == "needs_you") rec.question else rec.nowLine) ?: m.line,
                            sinceMillis = rec.stateSince,
                            recordId = rec.id,
                        )
                    }
                }
                AgentTask(
                    id = f.id, title = f.title, repo = f.repo, source = "file", hasOpen = f.open != null,
                    members = members, refreshedAtMillis = f.modified,
                )
            }
            val used = assignment.values.toSet()
            val agentTasks = rows.filter { it.id !in fileIds && it.id in used }
                .map { AgentTask(id = it.id, title = it.title, source = "agents") }
            val recordsByTask = records.filter { it.id in assignment }.groupBy { assignment.getValue(it.id) }
            val tasks = (fileTasks + agentTasks).map { t ->
                val m = meta[t.id]
                val firstSeen = recordsByTask[t.id].orEmpty().map { it.createdAt }.filter { it > 0 }.minOrNull()
                    ?: t.members.map { it.sinceMillis }.filter { it > 0 }.minOrNull()
                    ?: t.refreshedAtMillis
                    ?: rows.firstOrNull { it.id == t.id }?.createdAt
                t.copy(
                    leadRecordId = m?.let { recordBoundTo(records, it)?.id },
                    status = m?.status,
                    statusAtMillis = m?.statusAt,
                    startedAtMillis = m?.startedAt ?: firstSeen,
                )
            }
            return TaskSnapshot(tasks.sortedBy { it.id }, assignment)
        }

        /**
         * The record that speaks for a task, from the pane it remembers.
         *
         * A task remembers its own session as the layout leaf id of its pane, which is stored with
         * the layout and so names the same pane after a restart. The terminal session id does not:
         * it is handed out again from `s1` on every start, so afterwards the same id names another
         * pane. Among the records that ran in that leaf, the one that is not ended is the one
         * running there now (the rule [AgentSessionTracker.openRecordOf] uses); when every one has
         * ended, the newest by start is the task's latest run, which Enter can resume. A pane that
         * has gone leaves only ended records, so the task points at its own latest run and never
         * at whatever pane holds its old terminal id.
         *
         * A task stored before leaf ids were kept has only the terminal session id. It is
         * resolved the same way over the records on that id, until [TaskIndex] has seen a running
         * session on it and stored the pane.
         *
         * Called by [resolve] for each task that has an own session.
         *
         * @param records every record the tracker holds.
         * @param row the task's stored facts.
         * @return the running record in the pane, else the newest record in it, else null (also when
         *   the task has no own session).
         * @see AgentSessionTracker.openRecordOf
         */
        internal fun recordBoundTo(records: List<AgentSessionRecord>, row: TaskMetaRow): AgentSessionRecord? {
            val there = when {
                row.leadLeaf != null -> records.filter { it.leafId == row.leadLeaf }
                row.leadSession != null -> records.filter { it.terminalSessionId == row.leadSession }
                else -> return null
            }
            return there.filter { it.state != ENDED }.maxByOrNull { it.createdAt } ?: there.maxByOrNull { it.createdAt }
        }

        private fun isOpen(r: AgentSessionRecord) = r.state != ENDED && r.leafId != null
    }
}
