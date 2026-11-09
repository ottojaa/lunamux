/**
 * The one place that decides which task each agent session belongs to.
 *
 * This file contains [TaskIndex], which combines the tasks agents made through the MCP tools (rows
 * of `tasks` that carry a title), the tasks that task files declare ([TaskFile]) and the session
 * records, and publishes the result as [AgentTask]s plus an assignment of every record to at most
 * one task. A task's members are the sessions whose record carries the task's id, and nothing else.
 * It also holds the operations behind the MCP task tools, so that the tools and the overview agree
 * on every rule.
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
import se.soderbjorn.lunamux.persistence.SettingsRepository.TaskRow
import java.util.UUID

/**
 * Thrown by [TaskIndex] when a request names a task or a session that does not exist, or a task
 * that cannot be changed that way. The message names the offender and says what to do instead; the
 * MCP tools hand it to the agent as the tool's error.
 */
class TaskException(message: String) : IllegalArgumentException(message)

/** What [TaskIndex] last decided. */
data class TaskSnapshot(
    val tasks: List<AgentTask> = emptyList(),
    val assignment: Map<String, String> = emptyMap(),
)

/** One line of a task listing: a session that carries the task's id. */
data class TaskListSession(val sessionId: String?, val title: String, val state: String)

/** One task as the `list_tasks` MCP tool reports it. */
data class TaskListing(
    val id: String,
    val title: String,
    val source: String,
    val sessions: List<TaskListSession>,
    val mainConversation: String? = null,
    val ownSessionId: String? = null,
    val status: String? = null,
    val questionWaits: Boolean = false,
)

/**
 * Decides each session's task and carries the operations that change it.
 *
 * A session's task is the task its record names (an agent put it there through `add_to_task` or
 * `create_window`), or none. Where a session runs, and what a task file lists, never places it. A
 * task file and an agent-made task with one id are one task; the file's title wins. A task named by
 * no file and no row is ignored, so removing a task file does not leave sessions pointing at a
 * ghost.
 *
 * Created once by `main()`. [start] keeps [state] current as files and records change; the mutating
 * methods publish immediately so a tool's answer already reflects its own change.
 */
class TaskIndex(
    private val repo: SettingsRepository,
    private val tracker: AgentSessionTracker,
    private val files: StateFlow<List<TaskFile>>,
    private val leafOfSession: (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newSuffix: () -> String = { UUID.randomUUID().toString().take(SUFFIX_LENGTH) },
) {
    private val lock = Any()
    private var tasks: Map<String, TaskRow> =
        runCatching { repo.loadTasks() }.getOrDefault(emptyList()).associateBy { it.id }

    /** The tasks an agent made: the stored rows that carry a title. Call with [lock] held. */
    private fun agentMade(): List<TaskRow> = tasks.values.filter { it.title != null }
    private val _state = MutableStateFlow(TaskSnapshot())

    /** The current tasks and assignment; collected per window socket by `WindowRoutes`. */
    val state: StateFlow<TaskSnapshot> = _state.asStateFlow()

    init {
        recompute()
    }

    /** Keep [state] current: recompute whenever the session records or the task files change. */
    fun start(scope: CoroutineScope) {
        scope.launch { combine(tracker.summaries, files) { _, _ -> }.collect { recompute() } }
    }

    /** Look up a task file by id, for the route that runs its `open` action. */
    fun fileTask(taskId: String): TaskFile? = files.value.firstOrNull { it.id == taskId }

    /**
     * The name of a task, whether or not any session of it is in memory.
     *
     * Unlike [state], which lists only tasks that hold a session in memory, this knows every task
     * file and every agent-made task row. A task file's title wins over the row with the same id.
     */
    fun titleOf(taskId: String): String? = synchronized(lock) {
        files.value.firstOrNull { it.id == taskId }?.title ?: tasks[taskId]?.title
    }

    /** The ids of the tasks whose title contains [text], ignoring case. */
    fun taskIdsTitled(text: String): Set<String> = synchronized(lock) {
        (files.value.map { it.id } + agentMade().map { it.id }).toSet()
            .filter { titleOf(it)?.contains(text, ignoreCase = true) == true }
            .toSet()
    }

    /**
     * The task a record belongs to, as the overview shows it.
     *
     * A session in memory takes the assignment [state] made; one that is only in the database falls
     * back to the task id it was stored with, if that task still exists.
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
            val snapshot = resolve(records, files.value, tasks)
            // A task's start is fixed the first time it is seen, so its place in the overview
            // never moves when its sessions come and go afterwards.
            for (t in snapshot.tasks) if (tasks[t.id]?.startedAt == null) t.startedAtMillis?.let { stampStart(t.id, it) }
            _state.value = snapshot
        }
    }

    /**
     * Make a new task.
     *
     * The task's main conversation is the one running in the caller's pane when the caller is a
     * known pane, else the one in the first of [sessionIds], else none. The pane is turned into its
     * conversation once, here; what the task stores is the conversation.
     */
    fun createTask(title: String, sessionIds: List<String>, callerSessionId: String? = null): String {
        val clean = cleanTitle(title)
        val targets = sessionIds.map(::targetOf)
        val own = callerSessionId?.takeIf(::isKnownPane) ?: sessionIds.firstOrNull()
        val id = synchronized(lock) {
            val known = tasks.keys + files.value.map { it.id }
            var candidate: String
            do { candidate = "${slug(clean)}-${newSuffix()}" } while (candidate in known)
            val now = clock()
            repo.insertTask(candidate, clean, now)
            tasks = tasks + (candidate to TaskRow(candidate, title = clean, createdAt = now, updatedAt = now))
            candidate
        }
        recompute()
        targets.forEach { apply(it, id) }
        recompute()
        if (own != null) conversationOf(own)?.let { storeMainConversation(id, it) }
        return id
    }

    /**
     * Name the conversation that speaks for a task, from the pane it runs in.
     *
     * The pane is looked up the way `add_to_task` looks up its panes, so the same mistake gets the
     * same error, and is turned into the conversation running in it right now; the task stores that
     * conversation and forgets the pane. It is not moved into the task.
     */
    fun setOwnSession(taskId: String, sessionId: String) {
        requireTask(taskId)
        targetOf(sessionId)
        val conversation = conversationOf(sessionId)
            ?: throw TaskException("No agent runs in session '$sessionId', so there is no conversation to name. Start the agent there first, or pass a conversationId.")
        storeMainConversation(taskId, conversation)
    }

    /** Name the conversation that speaks for a task, directly. */
    fun setMainConversation(taskId: String, conversationId: String) {
        requireTask(taskId)
        val known = tracker.find(conversationId)
            ?: throw TaskException("Unknown conversation '$conversationId'. Use the conversationId list_tasks or list_sessions shows.")
        storeMainConversation(taskId, known.conversationId)
    }

    /**
     * Set or clear a task's one-line status.
     *
     * Line breaks become single spaces, the ends are trimmed and the text is cut at [STATUS_MAX]
     * characters; nothing left clears the status.
     *
     * [questionWaits] belongs to the status: every call sets both, so a later status that does not
     * repeat the flag takes it back, and clearing the status clears it too. The overview reads it
     * to keep the row of a task whose main conversation is closed where the person looks, since
     * resuming that conversation is what asks the question.
     */
    fun setStatus(taskId: String, text: String, questionWaits: Boolean = false): String? {
        requireTask(taskId)
        val clean = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim().take(STATUS_MAX).trim().ifEmpty { null }
        synchronized(lock) {
            val now = clock()
            ensureStarted(taskId)
            repo.setTaskStatus(taskId, clean, now, questionWaits)
            tasks = tasks + (taskId to tasks.getValue(taskId).copy(
                status = clean, statusAt = clean?.let { now }, questionWaits = clean != null && questionWaits,
            ))
        }
        recompute()
        return clean
    }

    /**
     * The conversation running in a pane right now: its open record's
     * [AgentSessionRecord.conversationId], or null when no agent runs there.
     */
    private fun conversationOf(sessionId: String): String? = tracker.openRecordOf(sessionId)?.conversationId

    /** Store the conversation that speaks for a task, then publish. */
    private fun storeMainConversation(taskId: String, conversationId: String) {
        synchronized(lock) {
            ensureStarted(taskId)
            repo.setTaskMainConversation(taskId, conversationId)
            tasks = tasks + (taskId to tasks.getValue(taskId).copy(mainConversation = conversationId))
        }
        recompute()
    }

    /** Make sure the task has a stored row with a start to write to; call with [lock] held. */
    private fun ensureStarted(taskId: String) {
        if (tasks[taskId]?.startedAt != null) return
        stampStart(taskId, _state.value.tasks.firstOrNull { it.id == taskId }?.startedAtMillis ?: clock())
    }

    /** Store the task's start, making its row if it has none, and remember it; call with [lock] held. */
    private fun stampStart(taskId: String, at: Long) {
        repo.stampTaskStart(taskId, at)
        val row = tasks[taskId] ?: TaskRow(taskId)
        tasks = tasks + (taskId to row.copy(startedAt = row.startedAt ?: at))
    }

    private fun isKnownPane(sessionId: String): Boolean =
        tracker.openRecordOf(sessionId) != null || leafOfSession(sessionId) != null

    /**
     * Move sessions into a task, out of whatever task they were in.
     *
     * A pane with no agent yet is promised the task for the next session that starts in it.
     */
    fun addToTask(taskId: String, sessionIds: List<String>) {
        requireTask(taskId)
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, taskId) }
        recompute()
    }

    /** Take sessions out of the task an agent put them in, so each is a task of its own again. */
    fun removeFromTask(sessionIds: List<String>): Map<String, String?> {
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, null) }
        recompute()
        val snapshot = _state.value
        return sessionIds.associateWith { sid -> tracker.openRecordOf(sid)?.let { snapshot.assignment[it.id] } }
    }

    /** Rename an agent-made task. */
    fun renameTask(taskId: String, title: String) {
        val clean = cleanTitle(title)
        synchronized(lock) {
            if (files.value.any { it.id == taskId }) {
                throw TaskException("Task '$taskId' is declared by a task file, which owns its title. Edit the file's title instead.")
            }
            val row = tasks[taskId]?.takeIf { it.title != null } ?: throw unknownTask(taskId)
            val now = clock()
            repo.renameTask(taskId, clean, now)
            tasks = tasks + (taskId to row.copy(title = clean, updatedAt = now))
        }
        recompute()
    }

    /**
     * Every task there is, with what is in it.
     *
     * Includes agent-made tasks that hold no session yet.
     */
    fun listing(): List<TaskListing> {
        val records = tracker.snapshot()
        val snapshot = _state.value
        val byTask = records.filter { it.id in snapshot.assignment }.groupBy { snapshot.assignment.getValue(it.id) }
        fun sessionsOf(taskId: String): List<TaskListSession> =
            byTask[taskId].orEmpty().map { r ->
                TaskListSession(
                    sessionId = r.terminalSessionId.takeIf { r.state != ENDED },
                    title = r.title ?: r.firstPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                        ?: "${r.agent} session",
                    state = r.state,
                )
            }
        val fileListings = files.value.sortedBy { it.id }.map { f ->
            TaskListing(f.id, f.title, "file", sessionsOf(f.id), mainConversationOf(f.id), ownSessionOf(f.id), statusOf(f.id), questionWaitsOf(f.id))
        }
        val fileIds = files.value.map { it.id }.toSet()
        val agentListings = synchronized(lock) { agentMade() }.filter { it.id !in fileIds }.sortedBy { it.id }
            .map { TaskListing(it.id, it.title.orEmpty(), "agents", sessionsOf(it.id), mainConversationOf(it.id), ownSessionOf(it.id), statusOf(it.id), questionWaitsOf(it.id)) }
        return fileListings + agentListings
    }

    /**
     * The terminal session id of the pane where a task's main conversation runs now.
     *
     * Looked up from the conversation's open record each time, because pane ids are handed out
     * again on every start and only the conversation is stored.
     */
    fun ownSessionOf(taskId: String): String? = synchronized(lock) {
        val conversation = tasks[taskId]?.mainConversation ?: return@synchronized null
        tracker.snapshot().firstOrNull { (it.conversationId == conversation || it.id == conversation) && it.state != ENDED }
            ?.terminalSessionId
    }

    /** The conversation that speaks for a task, as stored, or null when none was named. */
    fun mainConversationOf(taskId: String): String? = synchronized(lock) {
        val stored = tasks[taskId]?.mainConversation ?: return@synchronized null
        tracker.snapshot().firstOrNull { it.conversationId == stored || it.id == stored }?.conversationId ?: stored
    }

    private fun statusOf(taskId: String): String? = synchronized(lock) { tasks[taskId]?.status }

    private fun questionWaitsOf(taskId: String): Boolean = synchronized(lock) { tasks[taskId]?.questionWaits == true }

    /**
     * The `open` action a task file declares for the task whose main conversation is [record].
     *
     * The match is by conversation id, so every run of the conversation counts as the main one.
     * When several tasks name the conversation, the first by id that declares an `open` action
     * wins.
     */
    fun openOfMain(record: AgentSessionRecord): TaskOpen? = synchronized(lock) {
        _state.value.tasks.filter { it.mainConversation == record.conversationId }.sortedBy { it.id }
            .firstNotNullOfOrNull { t -> files.value.firstOrNull { it.id == t.id }?.open }
    }

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
     * A session that has ended no longer counts, because its pane is gone or only holds a shell.
     */
    fun openLeavesOf(taskId: String): List<String> {
        val sessions = tracker.snapshot()
            .filter { isOpen(it) && taskIdOf(it) == taskId }
            .mapNotNull { it.leafId }
        return sessions + tracker.pendingLeavesOf(taskId)
    }

    /** Check that a task exists, before something is created for it. */
    fun requireTask(taskId: String) {
        val known = synchronized(lock) { tasks[taskId]?.title != null } || files.value.any { it.id == taskId }
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

        private fun isOpen(r: AgentSessionRecord) = r.state != ENDED && r.leafId != null

        /**
         * Work out the tasks and every record's task from the three inputs. A record belongs to the
         * task it names if that task exists, and to none otherwise. Pure, so tests and [recompute]
         * share it.
         */
        fun resolve(
            records: List<AgentSessionRecord>,
            files: List<TaskFile>,
            tasks: Map<String, TaskRow> = emptyMap(),
        ): TaskSnapshot {
            val fileIds = files.map { it.id }.toSet()
            val rows = tasks.values.filter { it.title != null }
            val known = fileIds + rows.map { it.id }
            val assignment = LinkedHashMap<String, String>()
            for (r in records) r.taskId?.takeIf { it in known }?.let { assignment[r.id] = it }

            val fileTasks = files.sortedBy { it.id }.map { f ->
                AgentTask(id = f.id, title = f.title, repo = f.repo, source = "file", hasOpen = f.open != null)
            }
            val used = assignment.values.toSet()
            val agentTasks = rows.filter { it.id !in fileIds && it.id in used }
                .map { AgentTask(id = it.id, title = it.title.orEmpty(), source = "agents") }
            val recordsByTask = records.filter { it.id in assignment }.groupBy { assignment.getValue(it.id) }
            val listed = (fileTasks + agentTasks).map { t ->
                val m = tasks[t.id]
                val firstSeen = recordsByTask[t.id].orEmpty().map { it.createdAt }.filter { it > 0 }.minOrNull()
                    ?: files.firstOrNull { it.id == t.id }?.modified
                    ?: m?.createdAt
                t.copy(
                    mainConversation = m?.mainConversation?.let { stored ->
                        records.firstOrNull { it.conversationId == stored || it.id == stored }?.conversationId ?: stored
                    },
                    status = m?.status,
                    statusAtMillis = m?.statusAt,
                    questionWaits = m?.questionWaits == true && m.status != null,
                    startedAtMillis = m?.startedAt ?: firstSeen,
                )
            }
            return TaskSnapshot(listed.sortedBy { it.id }, assignment)
        }
    }
}
