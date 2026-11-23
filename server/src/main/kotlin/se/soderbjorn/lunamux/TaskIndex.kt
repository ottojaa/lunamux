/**
 * The one owner of tasks: which tasks there are, what each says about itself, and the operations
 * behind the MCP task tools.
 *
 * This file contains [TaskIndex], which combines the tasks agents made through the MCP tools (rows
 * of the `tasks` table that carry a title), the tasks that task files declare ([TaskFile]) and the
 * conversation summaries the [AgentSessionTracker] publishes, and publishes the result as one
 * [TaskState]. It also holds [TaskState] (what is published), [TaskListing] and [TaskListSession]
 * (what `list_tasks` reports).
 *
 * The rules, in one place so that the tools and the overview cannot disagree:
 *  - A conversation belongs to the task whose id its record carries, and to nothing else. A record
 *    keeps the id even when the task is gone; consumers read [TaskState.known] to ignore such an id.
 *  - A task file's task is always published. An agent-made task is published while at least one
 *    conversation carries it. A stored row without a title (made only to hold the start time or the
 *    status of a file task) is not a task.
 *  - A task's main conversation is the one stored for it, unless that conversation sits in another
 *    task that names it too: a conversation speaks for the task it is in.
 *  - A task's start is stamped the first time it is published and never moves.
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.TaskRow
import java.util.UUID

/**
 * Everything the overview needs to know about tasks, published as one value.
 *
 * @property sessions the conversation rows the tasks were derived from, one snapshot of the tracker's
 *   summaries. A row whose task does not exist has its [AgentSessionSummary.taskId] cleared, so every
 *   `taskId` here is the id of a task in [tasks] and every [AgentTask.main] is a row in here.
 * @property tasks the tasks to show, sorted by id.
 * @property known the id of every task that exists, shown or not. A conversation whose
 *   [AgentSessionSummary.taskId] is not in here belongs to no task.
 */
data class TaskState(val sessions: List<AgentSessionSummary>, val tasks: List<AgentTask>, val known: Set<String>)

/**
 * One line of a task listing: a conversation that carries the task's id.
 *
 * @property sessionId the terminal session it runs in; null once it has ended.
 * @property title the conversation's title by the one naming rule the overview uses ([AgentSessionSummary.title]).
 * @property state the conversation's state word.
 */
data class TaskListSession(val sessionId: String?, val title: String, val state: String)

/**
 * One task as the `list_tasks` MCP tool reports it.
 *
 * @property id the task's id.
 * @property title the task's name.
 * @property source `file` for a task file's task, `agents` for one an agent made.
 * @property sessions the conversations in it.
 * @property mainConversation the conversation that speaks for it, or null.
 * @property mainSessionId the terminal session [mainConversation] runs in; null while it is closed.
 * @property status the task's status line as stored (a count-only one included), or null.
 * @property questionWaits whether the status says a question waits.
 * @property paused whether the work is paused on purpose.
 * @property startedAt when the task was first published, epoch millis.
 */
data class TaskListing(
    val id: String,
    val title: String,
    val source: String,
    val sessions: List<TaskListSession>,
    val mainConversation: String? = null,
    val mainSessionId: String? = null,
    val status: String? = null,
    val questionWaits: Boolean = false,
    val paused: Boolean = false,
    val startedAt: Long? = null,
)

/**
 * Decides which tasks exist and what they say, and carries the operations that change them. Built
 * once in `main`; the overview reads [state], the MCP task tools call the operations.
 *
 * @param repo where agent-made tasks are stored; read once at construction and written through.
 * @param tracker the conversation records and their published summaries.
 * @param files the tasks declared by task files, from [TaskFilesWatcher.files].
 * @param leafOfSession maps a terminal session id to the layout pane it shows, or null when there is none.
 * @param clock the time source, replaceable in tests.
 * @param newSuffix the random tail of a new task's id, replaceable in tests.
 * @see TaskState
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

    /** Every stored task row by id: a memory mirror of the table, loaded once and written through. */
    private var tasks: Map<String, TaskRow> =
        runCatching { repo.loadTasks() }.getOrDefault(emptyList()).associateBy { it.id }

    private val _state = MutableStateFlow(TaskState(emptyList(), emptyList(), emptySet()))

    /** The tasks to show, the ids of all that exist, and the conversation rows they came from. Read by [AgentsFeed]. */
    val state: StateFlow<TaskState> = _state.asStateFlow()

    init {
        recompute()
    }

    /**
     * Keep [state] current: recompute whenever the conversation summaries or the task files change.
     *
     * @param scope the scope the collector runs in.
     */
    fun start(scope: CoroutineScope) {
        scope.launch { combine(tracker.summaries, files) { _, _ -> }.collect { recompute() } }
    }

    /**
     * Look up a task file by id.
     *
     * @param taskId the task's id.
     * @return the file's task, or null when no file declares it.
     */
    fun fileTask(taskId: String): TaskFile? = files.value.firstOrNull { it.id == taskId }

    /**
     * The name of a task, whether or not any conversation of it exists. A file's title wins over a stored one.
     *
     * @param taskId the task's id.
     * @return its title, or null when there is no such task.
     */
    fun titleOf(taskId: String): String? = synchronized(lock) {
        files.value.firstOrNull { it.id == taskId }?.title ?: tasks[taskId]?.title
    }

    /**
     * The `open` action a resume of [record] may use: the one of the first task (by id) whose
     * published main conversation is this one and whose file declares an action. Called by resume
     * and by the closed row's note, so both agree on the folder.
     *
     * @param record a conversation record.
     * @return the action, or null when the record speaks for no task that has one.
     * @see planResume
     */
    fun openOfMain(record: AgentSessionRecord): TaskOpen? = synchronized(lock) {
        val conversation = record.conversationId
        _state.value.tasks.sortedBy { it.id }
            .firstOrNull { it.main == conversation && fileTask(it.id)?.open != null }
            ?.let { fileTask(it.id)?.open }
    }

    /**
     * The ids of the tasks whose title contains [text], ignoring case. Called by search.
     *
     * @param text the words to look for.
     * @return the matching task ids.
     */
    fun taskIdsTitled(text: String): Set<String> = synchronized(lock) {
        knownIds().filter { titleOf(it)?.contains(text, ignoreCase = true) == true }.toSet()
    }

    /**
     * The task a record belongs to: the one it names, if that task exists.
     *
     * @param record a conversation record.
     * @return the task id, or null.
     */
    fun taskIdOf(record: AgentSessionRecord): String? = record.taskId?.takeIf { titleOf(it) != null }

    /** The id of every task that exists. Call with [lock] held. */
    private fun knownIds(): Set<String> =
        files.value.map { it.id }.toSet() + tasks.values.filter { it.title != null }.map { it.id }

    /**
     * Rebuild [state] from the files, the stored rows and the conversation summaries, in one pass,
     * and stamp the start of every task published for the first time. Called by [start]'s collector
     * and by every operation that changes something.
     */
    fun recompute() {
        synchronized(lock) {
            val summaries = tracker.summaries.value
            val fileList = files.value
            val fileIds = fileList.map { it.id }.toSet()
            val known = knownIds()
            val carried = summaries.filter { it.taskId in known }.groupBy { it.taskId }

            val shown = fileList.map { it.id } +
                tasks.values.filter { it.title != null && it.id !in fileIds && it.id in carried }.map { it.id }
            val published = shown.distinct().sorted().map { id ->
                val file = fileList.firstOrNull { it.id == id }
                val row = tasks[id]
                val startedAt = row?.startedAt
                    ?: carried[id].orEmpty().map { it.startedAt }.filter { it > 0 }.minOrNull()
                    ?: file?.modified
                    ?: row?.createdAt
                // A task's start is fixed the first time it is published, so its place in the
                // overview never moves when its conversations come and go afterwards.
                if (row?.startedAt == null && startedAt != null) stampStart(id, startedAt)
                val status = row?.status?.takeUnless { isCountsOnly(it) }
                AgentTask(
                    id = id,
                    title = file?.title ?: row?.title.orEmpty(),
                    repo = file?.repo,
                    main = mainOf(id, summaries),
                    status = status,
                    statusAt = status?.let { row?.statusAt },
                    questionWaits = row?.questionWaits == true && row.status != null,
                    pausedAt = row?.pausedAt,
                    startedAt = startedAt,
                    canOpen = file?.open != null,
                )
            }
            val publishedIds = published.mapTo(HashSet()) { it.id }
            val sessions = summaries.map { if (it.taskId != null && it.taskId !in publishedIds) it.copy(taskId = null) else it }
            _state.value = TaskState(sessions, published, known)
        }
    }

    /**
     * The conversation that speaks for a task, from the summaries: the stored one, unless it sits in
     * another task that names it too (see [spokenForElsewhere]). Call with [lock] held.
     */
    private fun mainOf(taskId: String, summaries: List<AgentSessionSummary>): String? {
        val stored = tasks[taskId]?.mainConversation ?: return null
        return stored.takeUnless { spokenForElsewhere(taskId, it, summaries, tasks) }
    }

    /**
     * Make a task.
     *
     * Every session is checked first, so a refusal changes nothing. Each session then joins the task
     * (a pane with no agent yet is promised it). The conversation that speaks for the task is the
     * caller's own: the pane [callerSessionId] names when it is a known pane, else the first of
     * [sessionIds], and only when an agent runs there now.
     *
     * @param title the task's name; trimmed, whitespace collapsed, cut to 120 characters.
     * @param sessionIds the terminal sessions to put in it.
     * @param callerSessionId the pane the request came from, from the request header, or null.
     * @return the new task's id, `<slug>-<4 hex>`.
     * @throws TaskException for an empty title or an unknown session.
     */
    fun createTask(title: String, sessionIds: List<String>, callerSessionId: String? = null): String {
        val clean = cleanTitle(title)
        val targets = sessionIds.map(::targetOf)
        val own = callerSessionId?.takeIf(::isKnownPane) ?: sessionIds.firstOrNull()
        val id = synchronized(lock) {
            val taken = tasks.keys + files.value.map { it.id }
            var candidate: String
            do { candidate = "${slug(clean)}-${newSuffix()}" } while (candidate in taken)
            val now = clock()
            repo.insertTask(candidate, clean, now)
            tasks = tasks + (candidate to TaskRow(candidate, title = clean, createdAt = now, updatedAt = now))
            candidate
        }
        targets.forEach { apply(it, id) }
        recompute()
        if (own != null) conversationOf(own)?.let { storeMainConversation(id, it) }
        return id
    }

    /**
     * Name the conversation running in a pane as the one that speaks for a task.
     *
     * @param taskId the task.
     * @param sessionId the pane's terminal session id.
     * @throws TaskException for an unknown task or session, or when no agent runs in the pane.
     */
    fun setOwnSession(taskId: String, sessionId: String) {
        requireTask(taskId)
        targetOf(sessionId)
        val conversation = conversationOf(sessionId)
            ?: throw TaskException("No agent runs in session '$sessionId', so there is no conversation to name. Start the agent there first, or pass a conversationId.")
        storeMainConversation(taskId, conversation)
    }

    /**
     * Name a conversation, directly, as the one that speaks for a task. A helper conversation (one
     * started inside another) cannot speak: its host does.
     *
     * @param taskId the task.
     * @param conversationId a conversation id or record id the tracker knows, open or closed.
     * @throws TaskException for an unknown task or conversation.
     */
    fun setMainConversation(taskId: String, conversationId: String) {
        requireTask(taskId)
        val known = tracker.find(conversationId)
            ?: throw TaskException("Unknown conversation '$conversationId'. Use the conversationId list_tasks or list_sessions shows.")
        storeMainConversation(taskId, known.host ?: known.conversationId)
    }

    /**
     * Set or clear a task's one-line status. A newline (with the space around it) becomes one space,
     * the text is cut to 120 characters, and an empty text clears it.
     *
     * [questionWaits] is set afresh by each call and kept only with a status. [paused] is set afresh
     * by each call too, but an empty text does not clear it: work can be paused with nothing to say.
     * A pause keeps the time it began while calls repeat it.
     *
     * @param taskId the task; a file's task is fine.
     * @param text the status.
     * @param questionWaits whether a question waits for the person.
     * @param paused whether the work is stopped on purpose.
     * @return the status as stored, or null when cleared.
     * @throws TaskException for an unknown task.
     */
    fun setStatus(taskId: String, text: String, questionWaits: Boolean = false, paused: Boolean = false): String? {
        requireTask(taskId)
        val clean = text.replace(NEWLINES, " ").trim().take(STATUS_MAX).trim().ifEmpty { null }
        synchronized(lock) {
            val now = clock()
            ensureStarted(taskId)
            val pausedAt = if (paused) tasks[taskId]?.pausedAt ?: now else null
            repo.setTaskStatus(taskId, clean, now, questionWaits, pausedAt)
            tasks = tasks + (taskId to tasks.getValue(taskId).copy(
                status = clean, statusAt = clean?.let { now }, questionWaits = clean != null && questionWaits, pausedAt = pausedAt,
            ))
        }
        recompute()
        return clean
    }

    /** The conversation running in a pane right now (its open record's), or null when no agent runs there. */
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

    /** Make sure the task has a stored row with a start to write to. Call with [lock] held. */
    private fun ensureStarted(taskId: String) {
        if (tasks[taskId]?.startedAt != null) return
        stampStart(taskId, _state.value.tasks.firstOrNull { it.id == taskId }?.startedAt ?: clock())
    }

    /** Store the task's start, making its row if it has none, and remember it. Call with [lock] held. */
    private fun stampStart(taskId: String, at: Long) {
        repo.stampTaskStart(taskId, at)
        val row = tasks[taskId] ?: TaskRow(taskId)
        tasks = tasks + (taskId to row.copy(startedAt = row.startedAt ?: at))
    }

    /** Whether [sessionId] names a pane Lunamux knows: one an agent runs in, or one in the layout. */
    private fun isKnownPane(sessionId: String): Boolean =
        tracker.openRecordOf(sessionId) != null || leafOfSession(sessionId) != null

    /**
     * Move sessions into a task, out of whatever task they were in. A pane with no agent yet is
     * promised the task for the agent that starts in it.
     *
     * @param taskId the task to join.
     * @param sessionIds the terminal sessions to move.
     * @throws TaskException for an unknown task or session; nothing changes then.
     */
    fun addToTask(taskId: String, sessionIds: List<String>) {
        requireTask(taskId)
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, taskId) }
        recompute()
    }

    /**
     * Take sessions out of their task (or cancel a promise), so each is a task of its own again.
     *
     * @param sessionIds the terminal sessions.
     * @return for each session, the task it is in now: null once removed, since no file declares members.
     * @throws TaskException for an unknown session; nothing changes then.
     */
    fun removeFromTask(sessionIds: List<String>): Map<String, String?> {
        val targets = sessionIds.map(::targetOf)
        targets.forEach { apply(it, null) }
        recompute()
        return sessionIds.associateWith { sid -> tracker.openRecordOf(sid)?.let(::taskIdOf) }
    }

    /**
     * Rename an agent-made task.
     *
     * @param taskId the task.
     * @param title the new name, cleaned like [createTask]'s.
     * @throws TaskException for an empty title, a file's task (the file owns its title) or an unknown task.
     */
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
     * Every task there is and what is in it, for `list_tasks`: file tasks first, then agent-made ones
     * a file does not shadow, each sorted by id and including tasks with no conversation.
     *
     * @return the listings.
     */
    fun listing(): List<TaskListing> {
        val records = tracker.snapshot()
        val summaries = tracker.summaries.value
        val known = synchronized(lock) { knownIds() }
        val byTask = records.filter { it.taskId in known }.groupBy { it.taskId }
        fun sessionsOf(taskId: String): List<TaskListSession> =
            byTask[taskId].orEmpty().map { r ->
                TaskListSession(
                    sessionId = r.terminalSessionId.takeIf { r.state != AgentSessionState.ENDED },
                    title = tracker.summaryFor(r).title,
                    state = r.state,
                )
            }
        fun listing(id: String, title: String, source: String): TaskListing {
            val (row, main) = synchronized(lock) { tasks[id] to mainOf(id, summaries) }
            val mainSession = main?.let { conversation ->
                records.firstOrNull { it.conversationId == conversation && it.state != AgentSessionState.ENDED }?.terminalSessionId
            }
            val startedAt = _state.value.tasks.firstOrNull { it.id == id }?.startedAt ?: row?.startedAt ?: row?.createdAt
            return TaskListing(
                id, title, source, sessionsOf(id), main, mainSession,
                row?.status, row?.questionWaits == true, row?.pausedAt != null, startedAt,
            )
        }
        val fileIds = files.value.map { it.id }.toSet()
        val agentMade = synchronized(lock) { tasks.values.filter { it.title != null && it.id !in fileIds } }
        return files.value.sortedBy { it.id }.map { listing(it.id, it.title, "file") } +
            agentMade.sortedBy { it.id }.map { listing(it.id, it.title.orEmpty(), "agents") }
    }

    /**
     * The panes a task's sessions are in: the layout pane of each open conversation of it, and of
     * each pane promised it whose agent has not started. Used to place a task's new window.
     *
     * @param taskId the task.
     * @return layout leaf ids, possibly repeated.
     */
    fun openLeavesOf(taskId: String): List<String> {
        val sessions = tracker.snapshot()
            .filter { it.state != AgentSessionState.ENDED && it.leafId != null && taskIdOf(it) == taskId }
            .mapNotNull { it.leafId }
        return sessions + tracker.pendingLeavesOf(taskId)
    }

    /**
     * Check that a task exists, before something is created for it.
     *
     * @param taskId the task's id.
     * @throws TaskException when it does not.
     */
    fun requireTask(taskId: String) {
        val known = synchronized(lock) { tasks[taskId]?.title != null } || files.value.any { it.id == taskId }
        if (!known) throw unknownTask(taskId)
    }

    /** A pane or a record an operation applies to: the open record when an agent runs, else the pane's leaf. */
    private class Target(val recordId: String?, val leafId: String?)

    /** Resolve a terminal session id to a [Target], or refuse when no pane has it. */
    private fun targetOf(sessionId: String): Target {
        val rec = tracker.openRecordOf(sessionId)
        val leaf = rec?.leafId ?: leafOfSession(sessionId)
        if (rec == null && leaf == null) {
            throw TaskException("Unknown session '$sessionId'. Use a session id from list_sessions, or \$LUNAMUX_SESSION_ID for your own pane.")
        }
        return Target(rec?.id, leaf)
    }

    /** Put the target in [taskId] (or in none): its record when an agent runs, else a promise for its pane. */
    private fun apply(target: Target, taskId: String?) {
        if (target.recordId != null) tracker.setTask(target.recordId, taskId)
        else if (target.leafId != null) tracker.setPendingTask(target.leafId, taskId)
    }

    private fun unknownTask(taskId: String) =
        TaskException("Unknown task '$taskId'. Use list_tasks to see the tasks there are, or create_task to make one.")

    /** Trim, collapse inner whitespace and cut to 120 characters; refuse an empty title. */
    private fun cleanTitle(title: String): String {
        val clean = title.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) throw TaskException("A task needs a title.")
        return clean.take(TITLE_MAX)
    }

    /** The readable head of a task id: the lowercased title with other characters as single dashes, at most 40 long. */
    private fun slug(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(SLUG_MAX).trim('-').ifEmpty { "task" }

    internal companion object {
        private const val TITLE_MAX = 120
        private const val SLUG_MAX = 40
        private const val SUFFIX_LENGTH = 4
        private const val STATUS_MAX = 120
        private val NEWLINES = Regex("\\s*[\\r\\n]+\\s*")

        /** One part of a count-only status: a number, an optional word, then a state ("2 need you"). */
        private val COUNT_PART = Regex(
            "\\d+\\s+(?:[a-z]+\\s+)?(?:working|waiting|idle|done|paused|running|ended|closed|finished|queued|blocked|needs? you)",
            RegexOption.IGNORE_CASE,
        )

        /** The separators between parts of a count-only status: a comma, `·`, `;`, `/` or the word "and". */
        private val COUNT_SPLIT = Regex("\\s*(?:[,·;/]|\\band\\b)\\s*", RegexOption.IGNORE_CASE)

        /**
         * Whether a status only counts states ("2 working, 1 waiting"), which the overview already
         * shows from the conversations themselves and so leaves out. A status that says anything else
         * ("Mango deploy running", "3 PRs open") is kept.
         *
         * @param status the stored status line.
         * @return true when every part of it is a count of a state.
         */
        fun isCountsOnly(status: String): Boolean {
            val parts = status.trim().split(COUNT_SPLIT).filter { it.isNotBlank() }
            return parts.isNotEmpty() && parts.all { COUNT_PART.matchEntire(it.trim()) != null }
        }

        /**
         * Whether [conversation], named by task [taskId], speaks for another task instead: it sits in
         * that task and that task names it too. A conversation speaks for one task at a time, the one
         * it is in. A manager that finishes one task and starts the next from the same conversation is
         * named by both; without this the finished task kept a live speaker and stayed in the
         * overview as a second row opening the same pane.
         *
         * @param taskId the task asking.
         * @param conversation the conversation stored for that task.
         * @param summaries every conversation, to find the task [conversation] is in.
         * @param tasks the stored rows, to read the other task's own main conversation.
         * @return true when the other task holds it, so [taskId] has no main conversation.
         */
        internal fun spokenForElsewhere(
            taskId: String, conversation: String, summaries: List<AgentSessionSummary>, tasks: Map<String, TaskRow>,
        ): Boolean {
            val holder = summaries.firstOrNull { it.id == conversation }?.taskId
            if (holder == null || holder == taskId) return false
            return tasks[holder]?.mainConversation == conversation
        }
    }
}
