/**
 * Keeps one record per agent conversation (Claude Code, Codex, Gemini) that runs in a Lunamux pane,
 * and publishes the row the agents overview shows for each.
 *
 * This file contains [AgentSessionTracker], which owns the records and applies the pure rules of
 * `AgentTurn.kt` ([step] and [route]) to them, persists every change, and publishes
 * [AgentSessionTracker.summaries]; [PaneSnapshot], the facts about one live pane that the tracker is
 * fed with; [livePaneSnapshot], which builds one from a live terminal session; and
 * [TaskException], the error for a request that names no open conversation.
 *
 * Everything the tracker remembers about a record's running turn is one [Turn] per record id. The
 * tracker decides nothing about states itself.
 *
 * @see step
 * @see route
 * @see summaryOf
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.AgentWords.CLAUDE
import se.soderbjorn.lunamux.AgentWords.SOURCE_HOOKS
import se.soderbjorn.lunamux.AgentWords.SOURCE_SCREEN
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState.DONE
import se.soderbjorn.lunamux.persistence.AgentSessionState.ENDED
import se.soderbjorn.lunamux.persistence.AgentSessionState.NEEDS_YOU
import se.soderbjorn.lunamux.persistence.AgentSessionState.WORKING
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.UUID

/** A request named no open conversation, or asked for something that cannot be done. */
class TaskException(message: String) : Exception(message)

/**
 * Everything the tracker needs to know about one live terminal pane.
 *
 * @property terminalSessionId the terminal session behind the pane.
 * @property leafId the pane's place in the layout, when it has one.
 * @property cwd the pane's current folder.
 * @property programTitle the title the program in the pane set.
 * @property agent the agent the pane's foreground process is, or null.
 * @property state what the screen detector says: `waiting`, `working`, or null when no agent shows.
 * @property alive whether the pane's shell process still runs.
 * @property customName the name the person gave the pane.
 * @property background whether the pane sits in a background tab.
 * @property screenRows reads the screen with its styling; only asked when a closed record keeps it.
 * @property screenText reads the screen as text; asked lazily.
 */
data class PaneSnapshot(
    val terminalSessionId: String,
    val leafId: String?,
    val cwd: String?,
    val programTitle: String?,
    val agent: String? = null,
    val state: String? = null,
    val alive: Boolean = true,
    val customName: String? = null,
    val background: Boolean = false,
    val screenRows: () -> List<List<se.soderbjorn.lunamux.pty.StyledRun>>? = { null },
    val screenText: () -> String? = { null },
)

/**
 * The owner of the agent conversation records.
 *
 * Fed by two sources: [onPoll] every few seconds with every live pane (the screen is the only word
 * on a Codex or Gemini conversation, and the fallback for a Claude one), and [onHook] with each
 * Claude Code hook event (the exact word on a Claude conversation). Each change is written through
 * to [repo], and the rows are published as [summaries].
 *
 * Called from the server's session-state poller, from the hook route (`AgentEventRoutes`), from the
 * terminal session manager when a pane closes, and from the MCP and window-state code that read or
 * label conversations.
 *
 * @param repo where records are persisted.
 * @param folderOf reads the repository facts of a folder; cached by the caller for [GIT_CACHE_MS].
 * @param clock the time source.
 * @param newId makes a record id.
 * @param onEnded called with each record as it closes, for the search indexer.
 * @param isUnder whether one process runs underneath another; how a helper `claude` is told from a replacement.
 * @param shellsUnder reads the background shells under a `claude` process.
 * @param isRunning whether a process id still belongs to a running process; a helper whose `claude` is gone ends.
 * @param inTab makes, once per publish, the test for whether a tab already shows a conversation's record.
 */
class AgentSessionTracker(
    private val repo: SettingsRepository,
    private val folderOf: (String?) -> GitCatalog.FolderWork? = { GitCatalog.folderWork(it, GIT_CACHE_MS) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onEnded: (AgentSessionRecord) -> Unit = {},
    private val isUnder: (Long, Long) -> Boolean = ::processIsUnder,
    private val shellsUnder: (Long) -> BackgroundShells = ::backgroundShellsOf,
    private val isRunning: (Long) -> Boolean = { ProcessHandle.of(it).map(ProcessHandle::isAlive).orElse(false) },
    private val inTab: () -> (AgentSessionRecord) -> Boolean = { { false } },
) {
    private val log = LoggerFactory.getLogger(AgentSessionTracker::class.java)
    private val lock = Any()

    /** Open records and the recently closed ones, by record id. A helper is an open record whose turn has a host. */
    private val records = LinkedHashMap<String, AgentSessionRecord>()

    /** What is remembered about each open record's running turn, by record id. */
    private val turns = HashMap<String, Turn>()

    /** Panes whose `claude` ended: the screen may still show the agent, so none reopens until it clears. */
    private val awaitScreenClear = HashSet<String>()

    /** The task a new record in a pane joins, set before the pane's agent has started. */
    private val pendingTasks = HashMap<String, String>()

    private val _summaries = MutableStateFlow<List<AgentSessionSummary>>(emptyList())

    /** One row per conversation: every open record and the recently closed ones. */
    val summaries: StateFlow<List<AgentSessionSummary>> = _summaries.asStateFlow()

    /**
     * Start a run: close what the last run left open, each at the last time it was seen alive, and
     * load the recently closed conversations. Called once at server start.
     *
     * @see SettingsRepository.endOpenAgentSessions
     */
    fun start() = synchronized(lock) {
        repo.endOpenAgentSessions(clock()) { rec ->
            rec.transcriptPath?.let { File(it).takeIf(File::isFile)?.lastModified() }
        }
        records.clear()
        turns.clear()
        repo.loadRecentEndedAgentSessions(clock() - AgentWindows.HISTORY_MS, HISTORY_MAX).forEach { records[it.id] = it }
        publish()
    }

    /**
     * One poll of every live pane: open a record for a pane that shows an agent, close the records
     * whose pane is gone, and let each open record's rules read what the pane and its transcript say.
     * Called every few seconds by the session-state poller; the file and process reads happen here,
     * outside the lock.
     *
     * @param panes every live pane.
     * @see step
     */
    fun onPoll(panes: List<PaneSnapshot>) {
        val toOpen = synchronized(lock) {
            panes.filter { it.alive && it.agent != null && it.state != null && openFor(it.terminalSessionId) == null }
        }
        toOpen.forEach { folderOf(it.cwd) }
        val (followers, idle) = synchronized(lock) {
            openRecords().filter { it.agent == CLAUDE }.forEach { r ->
                val path = r.transcriptPath ?: return@forEach
                val t = turns.getOrPut(r.id) { Turn() }
                if (t.follower?.path != path) turns[r.id] = t.copy(follower = TranscriptFollower(path))
            }
            turns.values.mapNotNull { it.follower } to openRecords().filter { it.state == DONE }
                .mapNotNull { r -> turns[r.id]?.pid?.let { r.id to it } }
        }
        followers.forEach { runCatching { it.refresh() } }
        val shells = idle.associate { (id, pid) -> id to runCatching { shellsUnder(pid) }.getOrDefault(BackgroundShells.NONE) }
        applyPoll(panes, shells)
    }

    private fun applyPoll(panes: List<PaneSnapshot>, shells: Map<String, BackgroundShells>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        for (r in openRecords()) {
            val pane = live[r.terminalSessionId]
            if (pane == null || !pane.alive) end(r, pane?.screenText?.invoke(), AgentWords.END_CLOSED)
        }
        // A helper's own pid is known from its hooks; once that process is gone the helper is over.
        for (r in openRecords()) {
            val t = turns[r.id] ?: continue
            if (t.hostId != null && t.pid != null && !isRunning(t.pid)) end(r, null, AgentWords.END_EXIT)
        }
        val now = clock()
        for (pane in panes) {
            if (pane.agent == null || pane.state == null) awaitScreenClear.remove(pane.terminalSessionId)
            var rec = openFor(pane.terminalSessionId)
            if (rec != null && rec.source == SOURCE_SCREEN && pane.agent != null && pane.agent != rec.agent) {
                end(rec, pane.screenText(), AgentWords.END_REPLACED)
                rec = null
            }
            if (rec == null) {
                if (pane.alive && pane.agent != null && pane.state != null && pane.terminalSessionId !in awaitScreenClear) {
                    rec = open(pane, pane.agent, SOURCE_SCREEN, agentSessionId = null, transcriptPath = null)
                } else continue
            }
            val turn = turns.getOrPut(rec.id) { Turn() }
            // The pane's name and place follow the pane, whichever feed owns the record's state.
            val name = pane.customName?.trim()?.takeIf { it.isNotEmpty() }
            val named = rec.copy(title = name ?: rec.title, background = pane.background)
            val stepped = step(
                named, turn,
                PollSignal(
                    now = now,
                    screen = pane.state,
                    interruptedAt = turn.follower?.interruptedAt,
                    shells = shells[rec.id],
                    footerShells = { footerShellsOf(pane.screenText()) },
                    programTitle = pane.programTitle,
                ),
            )
            turns[rec.id] = stepped.turn
            if (stepped.rec != rec) put(stepped.rec)
        }
        publish()
    }

    /**
     * One Claude Code hook event from the pane [pane]. The conversation it names is found or opened,
     * and the event moves its record. Called by the hook route for every event Claude Code posts.
     *
     * @param pane the live pane the event came from, or null when the pane is gone (ignored).
     * @param event the event's JSON as the hook posted it.
     * @param pid the process id of the `claude` that ran the hook, when it said.
     * @see route
     */
    fun onHook(pane: PaneSnapshot?, event: JsonObject, pid: Long? = null) {
        if (pane == null) return
        val startedIn = event.str("cwd")?.takeIf { it.isNotBlank() && event.str("hook_event_name") == "SessionStart" }
        val at = if (startedIn != null) pane.copy(cwd = startedIn) else pane
        folderOf(at.cwd)
        applyHook(at, event, pid, startedIn)
    }

    private fun applyHook(pane: PaneSnapshot, event: JsonObject, pid: Long?, startedIn: String?) = synchronized(lock) {
        val name = event.str("hook_event_name") ?: return@synchronized
        val agentId = event.str("session_id")
        val now = clock()
        // A conversation that already has an open record in this pane (a helper's, too) carries the event.
        var rec = agentId?.let { id -> openRecords().firstOrNull { it.agentSessionId == id && it.terminalSessionId == pane.terminalSessionId } }
            ?: attach(pane, agentId, event.str("transcript_path"), name, event.str("source"), pid)
            ?: return@synchronized
        var turn = turns.getOrPut(rec.id) { Turn() }
        if (pid != null) turn = turn.copy(pid = pid)
        when (name) {
            "SessionStart" -> {
                if (startedIn != null && startedIn != rec.cwd) {
                    val work = folderOf(startedIn)
                    rec = rec.copy(cwd = startedIn, repo = work?.repo, branch = work?.branch)
                }
                turn = turn.copy(lastActivity = now, lastHookAt = now, idlePolls = 0)
            }
            "SessionEnd" -> {
                val why = when (event.str("reason")) {
                    "clear" -> AgentWords.END_CLEARED
                    "prompt_input_exit" -> AgentWords.END_QUIT
                    "resume" -> AgentWords.END_REPLACED
                    else -> AgentWords.END_EXIT
                }
                val helper = turn.hostId != null
                end(rec, if (helper) null else pane.screenText(), why)
                if (!helper) awaitScreenClear.add(pane.terminalSessionId)
                publish()
                return@synchronized
            }
            else -> {
                val stepped = step(rec, turn, HookSignal(now, event, pane.programTitle))
                rec = stepped.rec
                turn = stepped.turn
            }
        }
        turns[rec.id] = turn
        put(rec)
        publish()
    }

    /**
     * Find the record a hook belongs to when the pane's own record is not already the one that names
     * it: decide with [route], then open, move, adopt or reopen as it says.
     *
     * @return the record to apply the event to, or null when the event is dropped.
     */
    private fun attach(pane: PaneSnapshot, agentId: String?, transcript: String?, hook: String, source: String?, pid: Long?): AgentSessionRecord? {
        val current = openFor(pane.terminalSessionId)
        val earlier = agentId?.let { id ->
            records.values.firstOrNull { it.agent == CLAUDE && it.agentSessionId == id && it.id != current?.id }
                ?: repo.findAgentSessionByConversation(CLAUDE, id)?.takeIf { it.id != current?.id }
        }
        val ref = HookRef(agentId, hook, source, pid, transcript)
        val provisional = current != null && current.agent == CLAUDE && current.agentSessionId == null && current.source == SOURCE_SCREEN
        return when (val where = route(ref, current, earlier, current?.let { turns[it.id]?.pid }, isUnder) { File(it).isFile }) {
            Route.Drop -> null
            Route.Current -> current
            Route.Helper -> open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript, host = current)
                .also { turns[it.id] = Turn(hostId = current!!.id) }
            Route.Adopt -> {
                // The screen guessed a prompt before the hooks could say: a guess is not a question.
                val guess = current!!.state == NEEDS_YOU
                current.copy(
                    agentSessionId = agentId, source = SOURCE_HOOKS, transcriptPath = transcript,
                    taskId = current.taskId ?: agentId?.let(::taskOfEarlier),
                    state = if (guess) DONE else current.state,
                    stateSince = if (guess) clock() else current.stateSince,
                    question = if (guess) null else current.question,
                ).also { put(it) }
            }
            is Route.Reopen -> reopen(where.earlier, pane, null)
            is Route.Replace -> {
                if (provisional) discard(current!!) else end(current!!, pane.screenText(), AgentWords.END_REPLACED)
                awaitScreenClear.remove(pane.terminalSessionId)
                where.earlier?.let { reopen(it, pane, current.takeIf { provisional }) }
                    ?: open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript)
            }
            Route.Open -> {
                awaitScreenClear.remove(pane.terminalSessionId)
                open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript)
            }
        }
    }

    /** Bring a conversation's earlier record back into [pane], as a fresh turn at its prompt. */
    private fun reopen(earlier: AgentSessionRecord, pane: PaneSnapshot, provisional: AgentSessionRecord?): AgentSessionRecord {
        awaitScreenClear.remove(pane.terminalSessionId)
        turns[earlier.id] = Turn()
        return earlier.copy(
            source = SOURCE_HOOKS, leafId = pane.leafId, terminalSessionId = pane.terminalSessionId,
            state = DONE, stateSince = clock(), nowLine = null, question = null, lastScreen = null,
            endedAt = null, endReason = null,
            title = earlier.title ?: provisional?.title,
            taskId = pane.leafId?.let { pendingTasks.remove(it) } ?: earlier.taskId ?: provisional?.taskId,
            background = pane.background,
        ).also { put(it) }
    }

    /** Remove a provisional record that turned out to be the conversation of another record. */
    private fun discard(rec: AgentSessionRecord) {
        records.remove(rec.id)
        turns.remove(rec.id)
        runCatching { repo.deleteAgentSession(rec.id) }.onFailure { log.warn("Failed to delete agent session {}", rec.id, it) }
    }

    private fun taskOfEarlier(agentSessionId: String): String? =
        records.values.filter { it.agent == CLAUDE && it.agentSessionId == agentSessionId && it.taskId != null }
            .maxByOrNull { it.createdAt }?.taskId
            ?: repo.findAgentSessionByConversation(CLAUDE, agentSessionId)?.taskId

    private fun open(
        pane: PaneSnapshot, agent: String, source: String, agentSessionId: String?, transcriptPath: String?,
        host: AgentSessionRecord? = null,
    ): AgentSessionRecord {
        val work = pane.cwd?.let(folderOf)
        val now = clock()
        return AgentSessionRecord(
            id = newId(), agent = agent, agentSessionId = agentSessionId, source = source,
            leafId = pane.leafId, terminalSessionId = pane.terminalSessionId, cwd = pane.cwd,
            repo = work?.repo, branch = work?.branch,
            title = pane.customName?.trim()?.takeIf { it.isNotEmpty() && host == null },
            transcriptPath = transcriptPath, state = DONE, stateSince = now, createdAt = now,
            taskId = if (host != null) host.taskId
            else pane.leafId?.let { pendingTasks.remove(it) } ?: agentSessionId?.let(::taskOfEarlier),
            background = pane.background,
            host = host?.conversationId,
        ).also { put(it) }
    }

    /** Close [rec] (and the helpers running inside it) for [reason], keeping the last lines of [screen]. */
    private fun end(rec: AgentSessionRecord, screen: String?, reason: String) {
        turns.filterValues { it.hostId == rec.id }.keys.forEach { id -> records[id]?.let { end(it, null, AgentWords.END_EXIT) } }
        turns.remove(rec.id)
        val now = clock()
        val ended = rec.copy(
            endReason = reason, state = ENDED, stateSince = now, endedAt = now, nowLine = null, question = null,
            lastScreen = screen?.let(::lastLines) ?: rec.lastScreen,
        )
        put(ended)
        trim()
        runCatching { onEnded(ended) }.onFailure { log.warn("Ended-session hook failed for {}", ended.id, it) }
    }

    private fun put(rec: AgentSessionRecord) {
        records[rec.id] = rec
        runCatching { repo.saveAgentSession(rec) }.onFailure { log.warn("Failed to persist agent session {}", rec.id, it) }
    }

    /** Keep the newest [HISTORY_MAX] closed records of the last month in memory; the database keeps the rest. */
    private fun trim() {
        val oldest = clock() - AgentWindows.HISTORY_MS
        records.values.filter { it.isEnded }.sortedByDescending { it.endedAt }.forEachIndexed { index, rec ->
            if (index >= HISTORY_MAX || (rec.endedAt ?: 0L) < oldest) records.remove(rec.id)
        }
    }

    private fun openRecords() = records.values.filter { !it.isEnded }

    /** The pane's own open record: not a helper running beside it. */
    private fun openFor(terminalSessionId: String) =
        records.values.firstOrNull { !it.isEnded && it.terminalSessionId == terminalSessionId && turns[it.id]?.hostId == null }

    private fun publish() {
        val now = clock()
        val shownInTab = inTab()
        _summaries.value = conversationRecords().map { summaryOf(it, turns[it.id], now, shownInTab(it)) }
    }

    /** One record per conversation: its open one (the newest), else its latest closed one. */
    private fun conversationRecords(): List<AgentSessionRecord> =
        records.values.groupBy { it.conversationId }.values.map { run ->
            run.filter { !it.isEnded }.maxByOrNull { it.createdAt } ?: run.maxBy { it.endedAt ?: it.createdAt }
        }

    /**
     * A pane went away: close its records. Called by the terminal session manager when a session is destroyed.
     *
     * @param terminalSessionId the destroyed session.
     * @param screen its last screen text, kept with the closed record.
     */
    fun onSessionDestroyed(terminalSessionId: String, screen: String?) = synchronized(lock) {
        awaitScreenClear.remove(terminalSessionId)
        openRecords().filter { it.terminalSessionId == terminalSessionId }.forEach { end(it, screen, AgentWords.END_CLOSED) }
        publish()
    }

    /**
     * The server is stopping: close every open record as `restart`, keeping each pane's screen.
     * Called from the shutdown hook.
     *
     * @param panes the panes still live.
     */
    fun endAll(panes: List<PaneSnapshot>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        openRecords().forEach { end(it, live[it.terminalSessionId]?.screenText?.invoke(), AgentWords.END_RESTART) }
        publish()
    }

    /**
     * Find a record by its id, else by its conversation id, in memory first and then in the database.
     *
     * @param id a record id or a conversation id.
     * @return the record, or null.
     */
    fun find(id: String): AgentSessionRecord? = synchronized(lock) {
        records[id] ?: repo.findAgentSession(id)
            ?: records.values.filter { it.conversationId == id }.maxByOrNull { it.createdAt }
            ?: repo.findAgentSessionByConversationId(id)
    }

    /** One record per conversation, as the overview lists them. */
    fun snapshot(): List<AgentSessionRecord> = synchronized(lock) { conversationRecords() }

    /** The open record of the pane showing terminal session [terminalSessionId], if any. */
    fun openRecordOf(terminalSessionId: String): AgentSessionRecord? = synchronized(lock) { openFor(terminalSessionId) }

    /**
     * The overview row for [rec], with the turn the tracker remembers for it when it has one. Search
     * uses it so a hit reads the same as the row in the list.
     *
     * @param rec any record.
     * @return the row.
     */
    fun summaryFor(rec: AgentSessionRecord): AgentSessionSummary =
        synchronized(lock) { summaryOf(rec, turns[rec.id], clock(), inTab()(rec)) }

    /** The in-memory record with [id], or null. */
    fun cached(id: String): AgentSessionRecord? = synchronized(lock) { records[id] }

    /**
     * Put the record [recordId] in task [taskId] (or in none).
     *
     * @return false when there is no such record in memory.
     */
    fun setTask(recordId: String, taskId: String?): Boolean = synchronized(lock) {
        val rec = records[recordId] ?: return false
        if (rec.taskId != taskId) {
            put(rec.copy(taskId = taskId))
            publish()
        }
        true
    }

    /**
     * Say what a conversation is doing, or what it waits for (the `set_status` tool).
     *
     * A plain status shows on a working row and goes with the next prompt. A kept one stays after
     * the turn ends and makes the row `WAITING`, or `NEEDS_YOU` when [needsYou].
     *
     * @param terminalSessionId the caller's own terminal session, when it knows it.
     * @param conversationId the conversation to label, when the caller names it.
     * @param text the words; blank clears.
     * @param keep whether the words outlive the turn.
     * @param needsYou with [keep]: whether what it waits for is the person.
     * @return the words as stored, or null when cleared.
     * @throws TaskException when no open conversation is named.
     */
    fun setStatus(terminalSessionId: String?, conversationId: String?, text: String, keep: Boolean = false, needsYou: Boolean = false): String? =
        synchronized(lock) {
            val rec = conversationOf(terminalSessionId, conversationId)
            val clean = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim().take(STATUS_MAX).trim().ifEmpty { null }
            val turn = turns.getOrPut(rec.id) { Turn() }
            turns[rec.id] = if (keep) {
                val since = turn.kept?.takeIf { it.text == clean }?.since ?: clock()
                turn.copy(kept = clean?.let { Kept(it, needsYou, since) })
            } else turn.copy(status = clean)
            publish()
            clean
        }

    private fun conversationOf(terminalSessionId: String?, conversationId: String?): AgentSessionRecord =
        conversationId?.let { id -> openRecords().firstOrNull { it.conversationId == id } }
            ?: terminalSessionId?.takeIf { conversationId == null }?.let(::openFor)
            ?: throw TaskException("No open agent conversation found. Pass your own \$LUNAMUX_SESSION_ID as sessionId, or your conversation id.")

    /** Remember (or forget) the task the next agent started in the pane with layout leaf [leafId] joins. */
    fun setPendingTask(leafId: String, taskId: String?) = synchronized(lock) {
        if (taskId == null) pendingTasks.remove(leafId) else pendingTasks[leafId] = taskId
        Unit
    }

    /** The layout leaves whose next agent joins task [taskId]. */
    fun pendingLeavesOf(taskId: String): List<String> = synchronized(lock) {
        pendingTasks.filterValues { it == taskId }.keys.toList()
    }

    /** Whether a Claude record in [cwd] is open that no hook has named yet (so history import skips the folder). */
    fun hasUnidentifiedClaudeIn(cwd: String): Boolean = synchronized(lock) {
        openRecords().any { it.agent == CLAUDE && it.agentSessionId == null && it.cwd == cwd }
    }

    companion object {
        /** How long [GitCatalog.folderWork] answers are reused when a record opens. */
        const val GIT_CACHE_MS = 60_000L

        /** The most closed records kept in memory. */
        const val HISTORY_MAX = 300L

        /** The longest a status the conversation worded itself may be. */
        const val STATUS_MAX = 120
    }
}

/**
 * Snapshot the live terminal session [id] for the tracker.
 *
 * Called by the session-state poller for every session and by the hook route for the session a hook
 * names.
 *
 * @param id the terminal session id.
 * @param states the screen detector's verdict per session, from the poller; empty from the hook route.
 * @return the snapshot, or null when the session is gone.
 */
internal fun livePaneSnapshot(id: String, states: Map<String, String?> = emptyMap()): PaneSnapshot? {
    val session = TerminalSessions.get(id) ?: return null
    return PaneSnapshot(
        terminalSessionId = id,
        leafId = WindowState.leafIdOfSession(id),
        cwd = session.cwd.value,
        programTitle = session.programTitle.value,
        customName = WindowState.customNameOfSession(id),
        background = WindowState.isBackgroundSession(id),
        agent = TerminalSessions.agentOf(id),
        state = states[id],
        alive = session.isProcessAlive(),
        screenText = { session.screenText() },
        screenRows = { session.screenRows() },
    )
}
