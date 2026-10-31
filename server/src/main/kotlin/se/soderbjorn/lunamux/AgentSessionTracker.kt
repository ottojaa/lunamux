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
import java.util.concurrent.TimeUnit

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
 * @property screenText reads the rendered screen as plain text.
 */
data class PaneSnapshot(
    val terminalSessionId: String,
    val leafId: String?,
    val cwd: String?,
    val programTitle: String?,
    val agent: String? = null,
    val state: String? = null,
    val alive: Boolean = true,
    val screenText: () -> String? = { null },
)

/**
 * Git facts about a folder, read once when a record opens.
 *
 * @property repo folder name of the repository's main checkout.
 * @property branch current branch, null on a detached HEAD.
 * @property worktreePath root of the linked worktree the folder is in; null in a main checkout.
 */
data class GitInfo(val repo: String? = null, val branch: String? = null, val worktreePath: String? = null)

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
 * @see PaneSnapshot
 */
class AgentSessionTracker(
    private val repo: SettingsRepository,
    private val gitInfo: (String?) -> GitInfo = ::readGitInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val log = LoggerFactory.getLogger(AgentSessionTracker::class.java)
    private val lock = Any()

    /** Records held in memory: every open one plus the most recent ended ones. */
    private val records = LinkedHashMap<String, AgentSessionRecord>()

    /** Panes whose screen still shows an agent we already ended; do not reopen until it clears. */
    private val awaitScreenClear = HashSet<String>()

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
     * server starts taking connections.
     */
    fun start() = synchronized(lock) {
        repo.endOpenAgentSessions(clock())
        records.clear()
        repo.loadRecentEndedAgentSessions(RECENT_ENDED).forEach { records[it.id] = it }
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
    fun onPoll(panes: List<PaneSnapshot>) = synchronized(lock) {
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
            if (rec.source != SOURCE_SCREEN) continue
            val state = when (pane.state) {
                "waiting" -> NEEDS_YOU
                "working" -> WORKING
                else -> IDLE
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
    fun onHook(pane: PaneSnapshot?, event: JsonObject) = synchronized(lock) {
        if (pane == null) return@synchronized
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
                set(WORKING, nowLine = null)
                if (rec.firstPrompt == null) rec = rec.copy(firstPrompt = event.str("prompt")?.take(PROMPT_MAX))
                if (rec.title == null) rec = rec.copy(title = cleanTitle(pane.programTitle))
            }
            "PreToolUse" -> set(WORKING, nowLine = toolLine(event))
            "PostToolUse" -> set(WORKING)
            "Notification" -> {
                // The idle reminder fires a minute after a turn ends and is not a request.
                if (event.str("notification_type") == "idle_prompt") return@synchronized
                set(NEEDS_YOU, nowLine = null, question = event.str("message")?.take(QUESTION_MAX))
            }
            "Stop" -> {
                val last = event.str("last_assistant_message")?.trim().orEmpty()
                if (last.endsWith("?")) {
                    set(NEEDS_YOU, nowLine = null, question = last.substringAfterLast("\n\n").take(QUESTION_MAX))
                } else set(IDLE, nowLine = null)
            }
            "SessionEnd" -> {
                end(rec, pane.screenText())
                awaitScreenClear.add(pane.terminalSessionId)
                publish()
                return@synchronized
            }
            else -> return@synchronized
        }
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
                return current.copy(agentSessionId = agentId, source = SOURCE_HOOKS, transcriptPath = transcript)
                    .also { put(it) }
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
            ).also { put(it) }
        }
        return open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript)
    }

    private fun open(
        pane: PaneSnapshot, agent: String, source: String, agentSessionId: String?, transcriptPath: String?,
    ): AgentSessionRecord {
        val git = gitInfo(pane.cwd)
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
            transcriptPath = transcriptPath,
            links = ticketKeys(git.branch),
            state = IDLE,
            stateSince = now,
            createdAt = now,
        ).also { put(it) }
    }

    private fun end(rec: AgentSessionRecord, screen: String?) {
        val now = clock()
        put(
            rec.copy(
                state = ENDED,
                stateSince = now,
                endedAt = now,
                nowLine = null,
                question = null,
                lastScreen = screen?.let(::lastLines) ?: rec.lastScreen,
            ),
        )
        trim()
    }

    private fun put(rec: AgentSessionRecord) {
        val stored = rec.copy(links = (ticketKeys(rec.branch) + ticketKeys(rec.title)).distinct())
        records[stored.id] = stored
        runCatching { repo.saveAgentSession(stored) }
            .onFailure { log.warn("Failed to persist agent session {}", stored.id, it) }
    }

    /** Drop the oldest ended records from memory beyond [RECENT_ENDED]; they stay in the database. */
    private fun trim() {
        val ended = records.values.filter { it.state == ENDED }.sortedByDescending { it.endedAt }
        ended.drop(RECENT_ENDED.toInt()).forEach { records.remove(it.id) }
    }

    private fun openRecords() = records.values.filter { it.state != ENDED }

    private fun openFor(terminalSessionId: String) =
        records.values.firstOrNull { it.state != ENDED && it.terminalSessionId == terminalSessionId }

    private fun publish() {
        _summaries.value = records.values.map {
            AgentSessionSummary(
                id = it.id, agent = it.agent, state = it.state, stateSince = it.stateSince,
                title = it.title, links = it.links, repo = it.repo, branch = it.branch, cwd = it.cwd,
                nowLine = it.nowLine, question = it.question, leafId = it.leafId, endedAt = it.endedAt,
            )
        }
    }

    private companion object {
        const val CLAUDE = "claude"
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_HOOKS = "hooks"
        const val NEEDS_YOU = "needs_you"
        const val WORKING = "working"
        const val IDLE = "idle"
        const val ENDED = "ended"
        const val RECENT_ENDED = 50L
        const val PROMPT_MAX = 500
        const val QUESTION_MAX = 400
    }
}

private const val TOOL_LINE_MAX = 160
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
 * (which can hold a whole file) is never kept.
 *
 * @param event a PreToolUse hook payload.
 * @return for example `Bash ./gradlew test`, or null without a tool name.
 */
internal fun toolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    val arg = listOf("command", "file_path", "pattern", "path", "url", "query")
        .firstNotNullOfOrNull { input?.str(it) }
        ?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
    return (if (arg == null) tool else "$tool $arg").take(TOOL_LINE_MAX)
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
    )
}

private fun git(cwd: String, vararg args: String): String? = runCatching {
    val p = ProcessBuilder(listOf("git", "-C", cwd) + args).redirectErrorStream(false).start()
    p.outputStream.close()
    val out = p.inputStream.bufferedReader().readText().trim()
    if (!p.waitFor(3, TimeUnit.SECONDS)) { p.destroyForcibly(); return@runCatching null }
    if (p.exitValue() == 0) out else null
}.getOrNull()

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
        agent = TerminalSessions.agentOf(id),
        state = states[id],
        alive = session.isProcessAlive(),
        screenText = { session.screenText() },
    )
}
