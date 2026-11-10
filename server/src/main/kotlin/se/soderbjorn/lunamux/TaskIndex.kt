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
 * that cannot be changed that way.
 */
class TaskException(message: String) : IllegalArgumentException(message)

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

/** Decides each session's task and carries the operations that change it. */
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
    private val _state = MutableStateFlow<List<AgentTask>>(emptyList())

    /** The tasks to show: files, and agent-made tasks that hold a session. Collected per window socket by `WindowRoutes`. */
    val state: StateFlow<List<AgentTask>> = _state.asStateFlow()

    init {
        recompute()
    }

    /** Keep [state] current: recompute whenever the session records or the task files change. */
    fun start(scope: CoroutineScope) {
        scope.launch { combine(tracker.summaries, files) { _, _ -> }.collect { recompute() } }
    }

    /** Look up a task file by id, for the route that runs its `open` action. */
    fun fileTask(taskId: String): TaskFile? = files.value.firstOrNull { it.id == taskId }

    /** The name of a task, whether or not any session of it is in memory. */
    fun titleOf(taskId: String): String? = synchronized(lock) {
        files.value.firstOrNull { it.id == taskId }?.title ?: tasks[taskId]?.title
    }

    /** The ids of the tasks whose title contains [text], ignoring case. */
    fun taskIdsTitled(text: String): Set<String> = synchronized(lock) {
        (files.value.map { it.id } + agentMade().map { it.id }).toSet()
            .filter { titleOf(it)?.contains(text, ignoreCase = true) == true }
            .toSet()
    }

    /** The task a record belongs to: the one it names, if that task still exists. */
    fun taskIdOf(record: AgentSessionRecord): String? = record.taskId?.takeIf { titleOf(it) != null }

    /**
     * Rebuild [state] from the files, the task rows and the records. Cheap enough to run on every
     * record change; called by [start]'s collector and by every mutating method.
     */
    fun recompute() {
        synchronized(lock) {
            val records = tracker.snapshot()
            val listed = resolve(records, files.value, tasks)
            // A task's start is fixed the first time it is seen, so its place in the overview
            // never moves when its sessions come and go afterwards.
            for (t in listed) if (tasks[t.id]?.startedAt == null) t.startedAtMillis?.let { stampStart(t.id, it) }
            _state.value = listed
        }
    }

    /** Make a new task. */
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

    /** Name the conversation that speaks for a task, from the pane it runs in. */
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
     * Set or clear a task's one-line status. [questionWaits] goes with it (a later call that omits it
     * takes it back): the overview keeps a closed main conversation's row in view while it is set.
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
        stampStart(taskId, _state.value.firstOrNull { it.id == taskId }?.startedAtMillis ?: clock())
    }

    /** Store the task's start, making its row if it has none, and remember it; call with [lock] held. */
    private fun stampStart(taskId: String, at: Long) {
        repo.stampTaskStart(taskId, at)
        val row = tasks[taskId] ?: TaskRow(taskId)
        tasks = tasks + (taskId to row.copy(startedAt = row.startedAt ?: at))
    }

    private fun isKnownPane(sessionId: String): Boolean =
        tracker.openRecordOf(sessionId) != null || leafOfSession(sessionId) != null

    /** Move sessions into a task, out of whatever task they were in. */
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
        return sessionIds.associateWith { sid -> tracker.openRecordOf(sid)?.let(::taskIdOf) }
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

    /** Every task there is, with what is in it. */
    fun listing(): List<TaskListing> {
        val records = tracker.snapshot()
        val byTask = records.groupBy { taskIdOf(it) }
        fun sessionsOf(taskId: String): List<TaskListSession> =
            byTask[taskId].orEmpty().map { r ->
                TaskListSession(
                    sessionId = r.terminalSessionId.takeIf { r.state != ENDED },
                    title = r.title ?: r.firstPrompt?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                        ?: "${r.agent} session",
                    state = r.state,
                )
            }
        fun listing(id: String, title: String, source: String): TaskListing {
            val row = synchronized(lock) { tasks[id] }
            return TaskListing(id, title, source, sessionsOf(id), mainConversationOf(id), ownSessionOf(id), row?.status, row?.questionWaits == true)
        }
        val fileIds = files.value.map { it.id }.toSet()
        val agentMade = synchronized(lock) { agentMade() }.filter { it.id !in fileIds }
        return files.value.sortedBy { it.id }.map { listing(it.id, it.title, "file") } +
            agentMade.sortedBy { it.id }.map { listing(it.id, it.title.orEmpty(), "agents") }
    }

    /** The terminal session id of the pane where a task's main conversation runs now. */
    fun ownSessionOf(taskId: String): String? = synchronized(lock) {
        val conversation = mainConversationOf(taskId) ?: return@synchronized null
        tracker.snapshot().firstOrNull { it.conversationId == conversation && it.state != ENDED }?.terminalSessionId
    }

    /** The conversation that speaks for a task (see [speakerOf]), or null when none was named. */
    fun mainConversationOf(taskId: String): String? = synchronized(lock) {
        val stored = tasks[taskId]?.mainConversation ?: return@synchronized null
        speakerOf(taskId, stored, tracker.snapshot())
    }

    /** The `open` action a task file declares for the task whose main conversation is [record]. */
    fun openOfMain(record: AgentSessionRecord): TaskOpen? = synchronized(lock) {
        _state.value.filter { it.mainConversation == record.conversationId }.sortedBy { it.id }
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

        /**
         * The conversation that speaks for task [taskId], given the [stored] name. That is the stored
         * conversation, unless it is a helper (see [isHelper]) or has no transcript left to resume
         * (see [transcriptMissing]): then the task's real conversation speaks, one that was still
         * open when the stored one began (a real conversation that closed before it, say before a
         * /clear, is not what the person named). A helper with a transcript is answered from its own
         * pane, the latest there. One with no transcript can't be the conversation the person
         * means, and reopening the real conversation in another pane moves its record there, so
         * every pane is looked at: the stored one's pane first, then a foreground one, then the
         * latest. A main that really closed keeps its name, and with no real conversation to take
         * over the stored name stays.
         */
        internal fun speakerOf(taskId: String, stored: String, records: List<AgentSessionRecord>): String {
            val named = records.firstOrNull { it.conversationId == stored || it.id == stored } ?: return stored
            val sound = !named.transcriptMissing() && (named.leafId == null || !isHelper(named, taskId, records))
            if (sound) return named.conversationId
            val anywhere = named.transcriptMissing()
            val real = records.filter {
                it.taskId == taskId && it.conversationId != named.conversationId && !it.transcriptMissing() &&
                    (anywhere || it.leafId == named.leafId) &&
                    !isHelper(it, taskId, records) && (it.endedAt ?: Long.MAX_VALUE) > named.createdAt
            }
            val preferred = real.filter { it.leafId == named.leafId }.ifEmpty { real.filter { !it.background } }.ifEmpty { real }
            return preferred.maxByOrNull { it.createdAt }?.conversationId ?: named.conversationId
        }

        /**
         * Whether [r] is a helper conversation of task [taskId]: one that is not tagged with the task
         * (the tracker used to leave helpers untagged, and so did the stored records of that time),
         * or that was started while another conversation in its pane was still open (which is how the
         * tracker makes a helper). Record age alone proves nothing: reopening a conversation makes a
         * newer record than its helper's.
         */
        private fun isHelper(r: AgentSessionRecord, taskId: String, records: List<AgentSessionRecord>): Boolean =
            r.taskId != taskId || records.any {
                it.leafId == r.leafId && it.createdAt < r.createdAt && (it.state != ENDED || (it.endedAt ?: 0L) > r.createdAt)
            }

        private fun isOpen(r: AgentSessionRecord) = r.state != ENDED && r.leafId != null

        /**
         * Work out the tasks to show from the three inputs. A record belongs to the task it names
         * if that task exists. Pure, so tests and [recompute] share it.
         */
        fun resolve(
            records: List<AgentSessionRecord>,
            files: List<TaskFile>,
            tasks: Map<String, TaskRow> = emptyMap(),
        ): List<AgentTask> {
            val fileIds = files.map { it.id }.toSet()
            val rows = tasks.values.filter { it.title != null }
            val known = fileIds + rows.map { it.id }
            val recordsByTask = records.filter { it.taskId in known }.groupBy { it.taskId }

            val fileTasks = files.sortedBy { it.id }.map { f ->
                AgentTask(id = f.id, title = f.title, repo = f.repo, source = "file", hasOpen = f.open != null)
            }
            val agentTasks = rows.filter { it.id !in fileIds && it.id in recordsByTask }
                .map { AgentTask(id = it.id, title = it.title.orEmpty(), source = "agents") }
            val listed = (fileTasks + agentTasks).map { t ->
                val m = tasks[t.id]
                val firstSeen = recordsByTask[t.id].orEmpty().map { it.createdAt }.filter { it > 0 }.minOrNull()
                    ?: files.firstOrNull { it.id == t.id }?.modified
                    ?: m?.createdAt
                t.copy(
                    mainConversation = m?.mainConversation?.let { speakerOf(t.id, it, records) },
                    status = m?.status,
                    statusAtMillis = m?.statusAt,
                    questionWaits = m?.questionWaits == true && m.status != null,
                    startedAtMillis = m?.startedAt ?: firstSeen,
                )
            }
            return listed.sortedBy { it.id }
        }
    }
}
