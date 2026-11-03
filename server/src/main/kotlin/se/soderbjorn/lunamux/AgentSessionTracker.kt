/**
 * Server-side record of every agentic-CLI session (Claude Code, Codex,
 * Gemini) that runs in a Lunamux pane.
 *
 * This file contains [AgentSessionTracker], which owns the `agent_sessions`
 * table, plus the small value types it is fed with ([PaneSnapshot],
 * [GitInfo]) and the helpers that read git metadata and the live panes.
 *
 * Two sources feed the tracker:
 *  - the 3 s screen poll ([AgentSessionTracker.onPoll]) for every agent, from
 *    what [StateDetector] sees on the terminal;
 *  - Claude Code hook events ([AgentSessionTracker.onHook]) posted to
 *    `/api/agent-events`, which are exact and win over the screen for the
 *    records they feed.
 *
 * The tracker publishes its current rows as [AgentSessionTracker.summaries];
 * `WindowRoutes` pushes that to every client as
 * [WindowEnvelope.AgentSessions].
 *
 * @see se.soderbjorn.lunamux.persistence.AgentSessionRecord
 * @see agentEventRoutes
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.UUID
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What the tracker needs to know about one live terminal session.
 *
 * Built by [livePaneSnapshot] from [TerminalSessions] and [WindowState] so the
 * tracker itself never calls into either (it is invoked while
 * [WindowState] holds its lock, during session teardown).
 *
 * @property terminalSessionId the live terminal session id.
 * @property leafId the layout leaf showing the session, if any.
 * @property cwd the pane's current working directory.
 * @property programTitle the program-set terminal title (OSC 0/2), if any.
 * @property agent `claude`, `codex` or `gemini` when the screen shows one, else null.
 * @property state the resolved screen state: `waiting`, `working` or null.
 * @property alive whether the session's process is still running.
 * @property customName the name the user gave the pane (F2), if any. It becomes the
 *   record's title so the name outlives the pane.
 * @property background whether the pane sits in a background tab (see [TabConfig.isBackground]).
 * @property screenText reads the rendered screen as plain text. Kept last so callers can pass it as a
 *   trailing lambda.
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
    val screenText: () -> String? = { null },
)

/**
 * Git facts about a folder, read once when a record opens.
 *
 * @property repo folder name of the repository's main checkout.
 * @property branch current branch, null on a detached HEAD.
 * @property worktreePath root of the linked worktree the folder is in; null in a main checkout.
 * @property repoRoot absolute path of the repository's main checkout; null outside git.
 */
data class GitInfo(
    val repo: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val repoRoot: String? = null,
)

/**
 * Owns the `agent_sessions` table and the in-memory view of it.
 *
 * Keeps at most one open record per terminal session. All mutation goes
 * through one lock, so the poll coroutine and the endpoint's coroutines never
 * interleave; nothing inside the lock calls back into [WindowState].
 *
 * Constructed once in `main()` with the shared [SettingsRepository]; tests
 * construct it over a temporary database with a fake [gitInfo] and [clock].
 *
 * @param repo the store the records are persisted to.
 * @param gitInfo reads git metadata for a folder; replaced in tests.
 * @param clock epoch-millis time source; replaced in tests.
 * @param newId mints record ids; replaced in tests.
 * @param onEnded called, with the tracker's lock held, each time a record ends; it must only
 *   hand the record to another thread (the search indexer reads the transcript there).
 * @see PaneSnapshot
 */
class AgentSessionTracker(
    private val repo: SettingsRepository,
    private val gitInfo: (String?) -> GitInfo = ::readGitInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onEnded: (AgentSessionRecord) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(AgentSessionTracker::class.java)
    private val lock = Any()

    /** Records held in memory: every open one plus the most recent ended ones. */
    private val records = LinkedHashMap<String, AgentSessionRecord>()

    /** Panes whose screen still shows an agent we already ended; do not reopen until it clears. */
    private val awaitScreenClear = HashSet<String>()

    /**
     * Ids of open records whose last `PreToolUse` has not been followed by its `PostToolUse` yet.
     * A prompt that arrives while one is in flight is about that tool call; one that arrives with
     * none in flight is about something else, so the row must not name the stale last tool line.
     */
    private val toolInFlight = HashSet<String>()

    /** Ids of hook-fed `needs_you` records whose pane screen has been seen showing the prompt. */
    private val promptSeen = HashSet<String>()

    /**
     * When each open record last received a hook event, by record id. Kept in memory only: it
     * is empty after a restart, and a record with no entry publishes no activity time.
     */
    private val lastActivity = HashMap<String, Long>()

    /**
     * Git facts per folder, filled by [warmGit] before the lock is taken so a slow
     * repository never holds up closing panes; [open] only reads it.
     */
    private val gitCache = ConcurrentHashMap<String, Pair<Long, GitInfo>>()

    /**
     * Tasks promised to panes whose agent has not started yet, by layout leaf id (leaf ids survive
     * restarts, terminal session ids do not). The next record opened in the leaf takes the task.
     * Kept in memory only: a promise the server forgets on restart is one the agent can repeat.
     */
    private val pendingTasks = HashMap<String, String>()

    private val _summaries = MutableStateFlow<List<AgentSessionSummary>>(emptyList())

    /**
     * The rows to show clients, republished after every change.
     * Collected per socket by `WindowRoutes`.
     */
    val summaries: StateFlow<List<AgentSessionSummary>> = _summaries.asStateFlow()

    /**
     * Close records a previous server run left open and load the recent ended ones.
     *
     * Terminal sessions do not survive a restart, so any record still open
     * belongs to a process that is gone. Called once from `main()` before the
     * server starts taking connections. Each is ended when it was last known alive (its state's
     * start, or its transcript file's last write if later), not at the restart, so how long ago
     * a session was used survives the restart.
     */
    fun start() = synchronized(lock) {
        repo.endOpenAgentSessions(clock()) { rec ->
            rec.transcriptPath?.let { File(it).takeIf(File::isFile)?.lastModified() }
        }
        records.clear()
        repo.loadRecentEndedAgentSessions(clock() - HISTORY_WINDOW_MS, HISTORY_MAX).forEach { records[it.id] = it }
        publish()
    }

    /**
     * Fold one screen-poll result into the records.
     *
     * Opens a record for a pane that newly shows an agent, follows
     * `waiting`/`working`/none into `needs_you`/`working`/`idle` for records
     * not fed by hooks, and ends records whose session is gone.
     * Called every 3 s by the state poller with a snapshot of all live panes.
     *
     * @param panes every live terminal session.
     */
    fun onPoll(panes: List<PaneSnapshot>) {
        val toOpen = synchronized(lock) {
            panes.filter { it.alive && it.agent != null && it.state != null && openFor(it.terminalSessionId) == null }
        }
        toOpen.forEach { warmGit(it.cwd) }
        applyPoll(panes)
    }

    private fun applyPoll(panes: List<PaneSnapshot>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        for (r in openRecords()) {
            val pane = live[r.terminalSessionId]
            if (pane == null || !pane.alive) end(r, pane?.screenText?.invoke())
        }
        for (pane in panes) {
            if (pane.agent == null || pane.state == null) awaitScreenClear.remove(pane.terminalSessionId)
            var rec = openFor(pane.terminalSessionId)
            if (rec != null && rec.source == SOURCE_SCREEN && pane.agent != null && pane.agent != rec.agent) {
                end(rec, pane.screenText())
                rec = null
            }
            if (rec == null) {
                if (pane.alive && pane.agent != null && pane.state != null && pane.terminalSessionId !in awaitScreenClear) {
                    rec = open(pane, pane.agent, SOURCE_SCREEN, agentSessionId = null, transcriptPath = null)
                } else continue
            }
            val name = pane.customName?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null && name != rec.title) {
                rec = rec.copy(title = name)
                put(rec)
            }
            if (rec.source != SOURCE_SCREEN) {
                // Claude Code sends no event when a permission prompt is approved, so
                // a long tool would keep the row at needs_you until it finished. The
                // screen is only used for that one step: it must have shown the
                // prompt (waiting) before showing work again clears the row.
                if (rec.state != NEEDS_YOU) promptSeen.remove(rec.id)
                else if (pane.state == "waiting") promptSeen.add(rec.id)
                else if (pane.state == "working" && promptSeen.remove(rec.id)) {
                    // Answering the prompt is a sign of life: without a fresh stamp the row
                    // would keep the time of the prompt and read "quiet" at once.
                    lastActivity[rec.id] = clock()
                    put(rec.copy(state = WORKING, stateSince = clock(), question = null))
                }
                continue
            }
            val state = when (pane.state) {
                "waiting" -> NEEDS_YOU
                "working" -> WORKING
                else -> IDLE
            }
            // A record no hook feeds is followed by the screen alone, so a screen that shows work
            // is the only sign of life it will ever give. The stamp is renewed only once it is
            // [ACTIVITY_REFRESH_MS] old: the overlay needs minute precision, and a stamp that moved
            // on every poll would push a new frame to every client every few seconds.
            if (state == WORKING) {
                val now = clock()
                val last = lastActivity[rec.id]
                if (last == null || now - last >= ACTIVITY_REFRESH_MS) lastActivity[rec.id] = now
            }
            var next = rec
            if (state == WORKING && rec.title == null) next = next.copy(title = cleanTitle(pane.programTitle))
            if (state != rec.state) {
                next = next.copy(state = state, stateSince = clock(), nowLine = null, question = null)
            }
            if (next != rec) put(next)
        }
        publish()
    }

    /**
     * Fold one Claude Code hook event into the records.
     *
     * Called by the `/api/agent-events` handler, off the request thread.
     * Events for a session id the tracker has not seen yet behave as an
     * implicit SessionStart, so connecting hooks while Claude is already
     * running still works.
     *
     * @param pane the pane the event came from; events from unknown panes are ignored.
     * @param event the hook's stdin JSON.
     */
    fun onHook(pane: PaneSnapshot?, event: JsonObject) {
        if (pane == null) return
        val known = synchronized(lock) { openFor(pane.terminalSessionId)?.agentSessionId }
        if (known == null || known != event.str("session_id")) warmGit(pane.cwd)
        applyHook(pane, event)
    }

    private fun applyHook(pane: PaneSnapshot, event: JsonObject) = synchronized(lock) {
        val name = event.str("hook_event_name") ?: return@synchronized
        val agentId = event.str("session_id")
        val transcript = event.str("transcript_path")
        var rec = attach(pane, agentId, transcript)
        val now = clock()
        fun set(state: String, nowLine: String? = rec.nowLine, question: String? = null) {
            rec = rec.copy(
                state = state,
                stateSince = if (state != rec.state) now else rec.stateSince,
                nowLine = nowLine,
                question = question,
            )
        }
        when (name) {
            "SessionStart" -> {}
            "UserPromptSubmit" -> {
                toolInFlight.remove(rec.id)
                set(WORKING, nowLine = null)
                if (rec.firstPrompt == null) rec = rec.copy(firstPrompt = event.str("prompt")?.take(PROMPT_MAX))
                if (rec.title == null) rec = rec.copy(title = cleanTitle(pane.programTitle))
            }
            "PreToolUse" -> {
                toolInFlight.add(rec.id)
                set(WORKING, nowLine = toolLine(event))
            }
            "PostToolUse" -> {
                toolInFlight.remove(rec.id)
                set(WORKING)
            }
            "Notification" -> {
                // The idle reminder fires a minute after a turn ends and is not a request.
                // It is recognised by its type, or by its text in case a version omits the type.
                val message = event.str("message")
                val type = event.str("notification_type")
                if (type == "idle_prompt" || type == "auth_success" ||
                    message?.contains("waiting for your input", ignoreCase = true) == true
                ) return@synchronized
                // The hook text is generic ("Claude needs your permission"). The tool call the prompt
                // is about arrived with the PreToolUse just before, so say what is being asked instead.
                set(NEEDS_YOU, nowLine = null, question = pendingQuestionOf(rec.nowLine.takeIf { rec.id in toolInFlight }, message, type)?.take(QUESTION_MAX))
            }
            "Stop" -> {
                toolInFlight.remove(rec.id)
                val last = event.str("last_assistant_message")?.trim().orEmpty()
                if (last.isNotEmpty()) rec = rec.copy(lastSaid = lastSaidOf(last))
                val asked = bareQuestionOf(last)
                if (asked != null) set(NEEDS_YOU, nowLine = null, question = asked.take(QUESTION_MAX))
                else set(IDLE, nowLine = null)
            }
            "SessionEnd" -> {
                end(rec, pane.screenText())
                awaitScreenClear.add(pane.terminalSessionId)
                publish()
                return@synchronized
            }
            else -> return@synchronized
        }
        lastActivity[rec.id] = now
        put(rec)
        publish()
    }

    /**
     * End the open record of a terminal session that is being torn down.
     *
     * Called from [TerminalSessions.destroy] just before the session shuts
     * down, so the screen can still be read.
     *
     * @param terminalSessionId the session being destroyed.
     * @param screen its screen text at that moment.
     */
    fun onSessionDestroyed(terminalSessionId: String, screen: String?) = synchronized(lock) {
        awaitScreenClear.remove(terminalSessionId)
        openFor(terminalSessionId)?.let { end(it, screen) }
        publish()
    }

    /**
     * Mark every open record ended, saving each pane's screen.
     *
     * Called from the JVM shutdown hook so a clean stop leaves nothing open.
     *
     * @param panes the live panes whose screens to save.
     */
    fun endAll(panes: List<PaneSnapshot>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        openRecords().forEach { end(it, live[it.terminalSessionId]?.screenText?.invoke()) }
        publish()
    }

    /**
     * Look up a record for the resume command.
     *
     * @param id the record id the user picked.
     * @return the record from memory or the database, or null if unknown.
     */
    fun find(id: String): AgentSessionRecord? = synchronized(lock) { records[id] ?: repo.findAgentSessionById(id) }

    /**
     * Every record held in memory (each open one and the recent ended ones), copied under the lock.
     *
     * Called by [TaskIndex] whenever [summaries] changes, because a summary lacks the agent's own
     * session id that task file members are matched by.
     *
     * @return the records, oldest first.
     */
    fun snapshot(): List<AgentSessionRecord> = synchronized(lock) { records.values.toList() }

    /**
     * The open record of a terminal session, if an agent runs in it.
     *
     * Called by [TaskIndex] when an agent joins a pane to a task: the id the MCP tools use for a
     * pane is its terminal session id.
     *
     * @param terminalSessionId the `LUNAMUX_SESSION_ID` of the pane.
     * @return the pane's open record, or null when no agent is running in it.
     */
    fun openRecordOf(terminalSessionId: String): AgentSessionRecord? = synchronized(lock) { openFor(terminalSessionId) }

    /**
     * Put a record in a task, or take it out of its task.
     *
     * Called by [TaskIndex] for the MCP `add_to_task` and `remove_from_task` tools. The change is
     * stored and pushed like any other change to the record.
     *
     * @param recordId the record's id.
     * @param taskId the task to join, or null to leave the task the agent chose.
     * @return false when the record is not held in memory, true otherwise.
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
     * Make the sessions that ran in the panes [leafIds] ordinary sessions again.
     *
     * Called by the `TakeOverTab` handler once a background tab became a normal tab: its records
     * (open and closed, in memory and stored) lose [AgentSessionRecord.background], so search finds
     * them from then on.
     *
     * @param leafIds the layout leaf ids of the tab's panes.
     * @see WindowState.takeOverTab
     */
    fun clearBackground(leafIds: Collection<String>) = synchronized(lock) {
        if (leafIds.isEmpty()) return@synchronized
        var changed = false
        for (leaf in leafIds) {
            runCatching { repo.clearAgentSessionBackgroundByLeaf(leaf) }
                .onFailure { log.warn("Failed to clear background flag for pane {}", leaf, it) }
        }
        for (rec in records.values.toList()) {
            if (rec.background && rec.leafId in leafIds) {
                records[rec.id] = rec.copy(background = false)
                changed = true
            }
        }
        if (changed) publish()
    }

    /**
     * Promise a task to the next agent session that opens in a pane.
     *
     * Called by [TaskIndex] for a pane that has no agent yet (a window just created with a task,
     * or a shell the agent grouped before starting a session in it). [open] and the reopening of a
     * resumed record read the promise and clear it.
     *
     * @param leafId the pane's layout leaf id.
     * @param taskId the task the pane's next session joins, or null to withdraw the promise.
     */
    fun setPendingTask(leafId: String, taskId: String?) = synchronized(lock) {
        if (taskId == null) pendingTasks.remove(leafId) else pendingTasks[leafId] = taskId
        Unit
    }

    /**
     * The panes that were promised [taskId] and have no agent yet.
     *
     * Called by [TaskIndex.openLeavesOf], so a second window created for a task before the first
     * one's agent has started still finds the first one's tab.
     *
     * @param taskId the task's id.
     * @return the layout leaf ids of those panes.
     * @see setPendingTask
     */
    fun pendingLeavesOf(taskId: String): List<String> = synchronized(lock) {
        pendingTasks.filterValues { it == taskId }.keys.toList()
    }

    /**
     * The record held in memory (every open one and the recent ended ones), without
     * touching the database.
     *
     * Called by search so a hit on an open session shows its live state.
     *
     * @param id the record id.
     * @return the in-memory record, or null.
     */
    fun cached(id: String): AgentSessionRecord? = synchronized(lock) { records[id] }

    /**
     * Whether a Claude session that has no agent session id yet is open in [cwd].
     *
     * Called by the history import through [AgentSearchIndexer.runningIn]: such a session was
     * seen on screen only, so its transcript cannot be matched to it by id.
     *
     * @param cwd the folder to check.
     * @return true when an open, unidentified Claude record has exactly that folder.
     */
    fun hasUnidentifiedClaudeIn(cwd: String): Boolean = synchronized(lock) {
        openRecords().any { it.agent == CLAUDE && it.agentSessionId == null && it.cwd == cwd }
    }

    /**
     * Pick the record a hook event belongs to, creating or adopting one.
     *
     * Keeps one open record per pane: the pane's open record is reused when
     * it has the same agent session id, adopted when it was opened from the
     * screen before any id was known, and ended otherwise. An ended record
     * with the same agent session id (a resumed conversation) is reopened in
     * this pane instead of starting a second row.
     */
    private fun attach(pane: PaneSnapshot, agentId: String?, transcript: String?): AgentSessionRecord {
        val current = openFor(pane.terminalSessionId)
        if (current != null && current.agent == CLAUDE) {
            if (agentId == null || current.agentSessionId == agentId) return current
            if (current.agentSessionId == null && current.source == SOURCE_SCREEN) {
                return current.copy(
                    agentSessionId = agentId, source = SOURCE_HOOKS, transcriptPath = transcript,
                    taskId = current.taskId ?: taskOfEarlier(agentId),
                ).also { put(it) }
            }
        }
        if (current != null) end(current, pane.screenText())
        awaitScreenClear.remove(pane.terminalSessionId)
        val earlier = agentId?.let { id ->
            records.values.firstOrNull { it.agent == CLAUDE && it.agentSessionId == id }
                ?: repo.findAgentSession(CLAUDE, id)
        }
        if (earlier != null && earlier.state == ENDED) {
            return earlier.copy(
                source = SOURCE_HOOKS,
                leafId = pane.leafId,
                terminalSessionId = pane.terminalSessionId,
                state = IDLE,
                stateSince = clock(),
                nowLine = null,
                question = null,
                lastScreen = null,
                endedAt = null,
                taskId = pane.leafId?.let { pendingTasks.remove(it) } ?: earlier.taskId,
                background = pane.background,
            ).also { put(it) }
        }
        return open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript)
    }

    /** Read git facts for [cwd] unless a read from the last minute is cached. Never call with the lock held. */
    private fun warmGit(cwd: String?) {
        if (cwd.isNullOrBlank()) return
        val now = clock()
        val cached = gitCache[cwd]
        if (cached != null && now - cached.first < GIT_CACHE_MS) return
        gitCache[cwd] = now to gitInfo(cwd)
    }

    /**
     * The task of the newest earlier record of the same Claude conversation that has one, so that a
     * resumed chat stays in its task even when it gets a new record (its pane was reopened before
     * the hook named the conversation).
     */
    private fun taskOfEarlier(agentSessionId: String): String? =
        records.values.filter { it.agent == CLAUDE && it.agentSessionId == agentSessionId && it.taskId != null }
            .maxByOrNull { it.createdAt }?.taskId
            ?: repo.findAgentSession(CLAUDE, agentSessionId)?.taskId

    private fun open(
        pane: PaneSnapshot, agent: String, source: String, agentSessionId: String?, transcriptPath: String?,
    ): AgentSessionRecord {
        val git = pane.cwd?.let { gitCache[it]?.second } ?: GitInfo()
        val now = clock()
        return AgentSessionRecord(
            id = newId(),
            agent = agent,
            agentSessionId = agentSessionId,
            source = source,
            leafId = pane.leafId,
            terminalSessionId = pane.terminalSessionId,
            cwd = pane.cwd,
            repo = git.repo,
            branch = git.branch,
            worktreePath = git.worktreePath,
            repoRoot = git.repoRoot,
            title = pane.customName?.trim()?.takeIf { it.isNotEmpty() },
            transcriptPath = transcriptPath,
            links = ticketKeys(git.branch),
            state = IDLE,
            stateSince = now,
            createdAt = now,
            taskId = pane.leafId?.let { pendingTasks.remove(it) } ?: agentSessionId?.let(::taskOfEarlier),
            background = pane.background,
        ).also { put(it) }
    }

    private fun end(rec: AgentSessionRecord, screen: String?) {
        lastActivity.remove(rec.id)
        toolInFlight.remove(rec.id)
        val now = clock()
        val ended = rec.copy(
            state = ENDED,
            stateSince = now,
            endedAt = now,
            nowLine = null,
            question = null,
            lastScreen = screen?.let(::lastLines) ?: rec.lastScreen,
        )
        put(ended)
        trim()
        runCatching { onEnded(ended) }.onFailure { log.warn("Ended-session hook failed for {}", ended.id, it) }
    }

    private fun put(rec: AgentSessionRecord) {
        val stored = rec.copy(links = (ticketKeys(rec.branch) + ticketKeys(rec.title)).distinct())
        records[stored.id] = stored
        runCatching { repo.saveAgentSession(stored) }
            .onFailure { log.warn("Failed to persist agent session {}", stored.id, it) }
    }

    /**
     * Drop ended records from memory once they are older than [HISTORY_WINDOW_MS]
     * or beyond the newest [HISTORY_MAX]; they stay in the database and search
     * still finds them. Called after a record ends.
     */
    private fun trim() {
        val oldest = clock() - HISTORY_WINDOW_MS
        val ended = records.values.filter { it.state == ENDED }.sortedByDescending { it.endedAt }
        ended.forEachIndexed { index, rec ->
            if (index >= HISTORY_MAX || (rec.endedAt ?: 0L) < oldest) records.remove(rec.id)
        }
    }

    private fun openRecords() = records.values.filter { it.state != ENDED }

    private fun openFor(terminalSessionId: String) =
        records.values.firstOrNull { it.state != ENDED && it.terminalSessionId == terminalSessionId }

    private fun publish() {
        _summaries.value = records.values.map { it.toSummary().copy(lastActivityAt = lastActivity[it.id]) }
    }

    private companion object {
        const val CLAUDE = "claude"
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_HOOKS = "hooks"
        const val NEEDS_YOU = "needs_you"
        const val WORKING = "working"
        const val IDLE = "idle"
        const val ENDED = "ended"

        /**
         * How far back ended sessions are kept in memory and pushed to clients: the
         * overview lists two weeks. The edge is inclusive here; the client lists no
         * more than this, so a row it shows is never missing from the server.
         */
        const val HISTORY_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Most ended records held in memory. [publish] sends every record held on each
         * change, so this bounds the payload (a summary is a few hundred bytes, so
         * 300 is about 100 KB at worst) however busy two weeks were.
         */
        const val HISTORY_MAX = 300L

        /** How old a screen-derived activity stamp must be before a working poll renews it. */
        const val ACTIVITY_REFRESH_MS = 30_000L
        const val PROMPT_MAX = 500
        const val QUESTION_MAX = 400
        const val GIT_CACHE_MS = 60_000L
    }
}

/**
 * The row the clients see for this record.
 *
 * Used by the tracker's published list and by search results.
 *
 * @receiver the stored record.
 * @return its summary.
 */
internal fun AgentSessionRecord.toSummary() = AgentSessionSummary(
    id = id, agent = agent, state = state, stateSince = stateSince,
    title = title, links = links, repo = repo, branch = branch, cwd = cwd,
    cwdHome = cwd != null && prettifyPath(cwd) == "~",
    nowLine = nowLine, question = question, leafId = leafId, endedAt = endedAt,
    lastSaid = lastSaid,
    firstPrompt = firstPrompt?.let(::firstPromptLine),
    taskId = taskId,
    background = background,
)

private const val TOOL_LINE_MAX = 160
private const val FIRST_PROMPT_MAX = 160

/**
 * The first line of what the user first asked a session, cut for a status cell.
 *
 * Called by [toSummary]; the overview shows it after "❯" for a row that has no tool line or
 * last reply to show.
 *
 * @param prompt the stored first prompt (up to 500 characters, any number of lines).
 * @return its first non-blank line, at most [FIRST_PROMPT_MAX] characters, or null when blank.
 */
internal fun firstPromptLine(prompt: String): String? =
    prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(FIRST_PROMPT_MAX)
private const val LAST_SAID_MAX = 160

/**
 * The part of an assistant reply worth showing as "what it last said": the first line of
 * the last paragraph, cut to [LAST_SAID_MAX] characters. A line that is only a code fence says
 * nothing, so it is passed over; when the last paragraph holds nothing else, the one before it
 * is used.
 *
 * Called by [AgentSessionTracker] on every Stop hook, for questions and statements alike.
 *
 * @param message the full, trimmed reply text.
 * @return the first line of its last paragraph (possibly empty for blank input).
 */
internal fun lastSaidOf(message: String): String {
    val paragraphs = message.trim().split("\n\n").asReversed()
    val line = paragraphs.firstNotNullOfOrNull { p ->
        p.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !AgentSessionText.isFenceLine(it) }
    }
    return (line ?: "").take(LAST_SAID_MAX)
}

/**
 * The question a finished turn leaves for the owner, or null when the turn is just a reply.
 *
 * A turn that ends in a reply, even one that closes with an offer ("... Want me to go ahead?"),
 * is idle: the chat is not blocked on anything. Only a closing paragraph that is a single
 * question counts: one line, one sentence, ending in "?".
 *
 * Called from the tracker's `Stop` hook handling.
 *
 * @param message the assistant's last message of the turn, already trimmed.
 * @return the closing question, or null when the message is a reply.
 * @see QUESTION_SENTENCE_MAX
 */
internal fun bareQuestionOf(message: String): String? {
    val paragraph = message.trim().substringAfterLast("\n\n").trim()
    if (!paragraph.endsWith("?") || paragraph.length > QUESTION_SENTENCE_MAX) return null
    val body = paragraph.dropLast(1)
    if ('\n' in body || SENTENCE_BREAK.containsMatchIn(body.replace(ABBREVIATION, ""))) return null
    return paragraph
}

/** Longest closing paragraph that still reads as one question rather than a reply with an offer. */
private const val QUESTION_SENTENCE_MAX = 200

/**
 * A sentence end inside a paragraph: `.`, `!` or `?` followed by a space. A colon or semicolon does
 * not end one, so "Which do you want: the alias or the rename?" stays a single question.
 */
private val SENTENCE_BREAK = Regex("""[.!?]\s""")

/** Abbreviations whose full stop is not a sentence end ("e.g. the alias"); removed before looking for breaks. */
private val ABBREVIATION = Regex("""\b(?:e\.g|i\.e|etc|vs)\.""", RegexOption.IGNORE_CASE)

private const val SCREEN_LINES = 200
private val TICKET_KEY = Regex("""\b[A-Z][A-Z0-9]+-\d+\b""")

/**
 * Ticket keys such as `ABC-12` found in a branch name or title.
 *
 * @param text a branch name or title, or null.
 * @return the distinct keys in order of appearance.
 */
internal fun ticketKeys(text: String?): List<String> =
    if (text == null) emptyList() else TICKET_KEY.findAll(text).map { it.value }.distinct().toList()

/**
 * The last 200 lines of [screen], trailing blank lines removed.
 *
 * @param screen the rendered screen as plain text.
 * @return the lines to store as `last_screen`.
 */
internal fun lastLines(screen: String): String =
    screen.trimEnd().lines().takeLast(SCREEN_LINES).joinToString("\n")

/**
 * Tidy a program-set terminal title for display: strips the spinner or
 * status glyph an agent prefixes and drops titles that only name the agent.
 *
 * @param raw the raw OSC title, or null.
 * @return the cleaned title, or null when nothing useful is left.
 */
internal fun cleanTitle(raw: String?): String? {
    val t = raw?.trim()?.dropWhile { !it.isLetterOrDigit() }?.trim().orEmpty()
    if (t.isEmpty()) return null
    return if (t.lowercase() in setOf("claude", "claude code", "codex", "gemini", "gemini cli")) null else t
}

/**
 * One line describing a tool call: the tool name and its main argument.
 *
 * Only the command, file path or pattern is read; the rest of `tool_input`
 * (which can hold a whole file) is never kept. A question put to the person
 * through `AskUserQuestion` is kept as `Asks: <question>` instead, so the row
 * shows what is asked; like any line it is cut at [TOOL_LINE_MAX], which is
 * ample for a row.
 *
 * @param event a PreToolUse hook payload.
 * @return for example `Bash ./gradlew test`, or null without a tool name.
 */
internal fun toolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    if (tool == ASK_TOOL) {
        val asked = ((input?.get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("question")
        if (!asked.isNullOrBlank()) return (ASK_PREFIX + asked.trim()).take(TOOL_LINE_MAX)
    }
    val arg = listOf("command", "file_path", "pattern", "path", "url", "query")
        .firstNotNullOfOrNull { input?.str(it) }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    return (if (arg == null) tool else "$tool $arg").take(TOOL_LINE_MAX)
}

/** The tool Claude Code uses to put a question to the person, whose text [toolLine] keeps. */
private const val ASK_TOOL = "AskUserQuestion"

/** Marks a [toolLine] that holds a question put to the person rather than a command. */
private const val ASK_PREFIX = "Asks: "

/**
 * What a `needs_you` row should say for a prompt Claude Code raised through a Notification hook.
 *
 * A question put to the person is shown as the question itself; a permission prompt as
 * `Allow <tool line>?`, so the row names the command instead of the hook's generic text. Any other
 * kind of notification (a tool asking for input, for example) keeps the hook's own message, since
 * it is not a request to allow the tool.
 *
 * Called from the tracker's `Notification` hook handling.
 *
 * @param pending the tool line of the PreToolUse that preceded the prompt, or null.
 * @param message the hook's own text, used when no tool call is pending or the prompt is not a
 *   permission prompt.
 * @param type the hook's `notification_type`, or null from a version that omits it (taken as a
 *   permission prompt).
 * @return the line to show, or null when there is neither.
 * @see toolLine
 */
internal fun pendingQuestionOf(pending: String?, message: String?, type: String? = null): String? = when {
    pending == null -> message
    pending.startsWith(ASK_PREFIX) -> pending.removePrefix(ASK_PREFIX)
    type == null || type == "permission_prompt" -> "Allow $pending?"
    else -> message
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Read repository, branch and worktree for [cwd] with `git`.
 *
 * Called when a record opens. Any failure (no git, not a repository, a
 * timeout) yields an empty [GitInfo].
 *
 * @param cwd the folder to inspect, or null.
 * @return what git reported.
 */
fun readGitInfo(cwd: String?): GitInfo {
    if (cwd.isNullOrBlank() || !File(cwd).isDirectory) return GitInfo()
    val top = git(cwd, "rev-parse", "--show-toplevel") ?: return GitInfo()
    val common = git(cwd, "rev-parse", "--path-format=absolute", "--git-common-dir")
    val mainRoot = common?.let { File(it).parentFile }
    val branch = git(cwd, "branch", "--show-current")?.takeIf { it.isNotEmpty() }
    val isLinked = mainRoot != null && File(top).canonicalPath != mainRoot.canonicalPath
    return GitInfo(
        repo = (mainRoot ?: File(top)).name,
        branch = branch,
        worktreePath = if (isLinked) top else null,
        repoRoot = (mainRoot ?: File(top)).path,
    )
}

private fun git(cwd: String, vararg args: String): String? =
    GitCatalog.runGit(Path.of(cwd), *args)?.trim()

/**
 * Snapshot a live terminal session for the tracker.
 *
 * Called by the state poller, the events endpoint and the shutdown hook.
 * Reads [WindowState] to find the pane that shows the session.
 *
 * @param id the terminal session id.
 * @param states the poll's resolved states, to fill in the screen state.
 * @return the snapshot, or null if the session no longer exists.
 */
internal fun livePaneSnapshot(id: String, states: Map<String, String?> = emptyMap()): PaneSnapshot? {
    val session = TerminalSessions.get(id) ?: return null
    return PaneSnapshot(
        terminalSessionId = id,
        leafId = WindowState.leafIdOfSession(id),
        cwd = session.cwd.value,
        programTitle = session.programTitle.value,
        customName = WindowState.customNameOfSession(id),
        agent = TerminalSessions.agentOf(id),
        state = states[id],
        alive = session.isProcessAlive(),
        screenText = { session.screenText() },
        background = WindowState.isBackgroundSession(id),
    )
}
