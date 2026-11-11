/**
 * Server-side record of every agentic-CLI session (Claude Code, Codex, Gemini) that runs in a
 * Lunamux pane.
 *
 * This file contains [AgentSessionTracker], which owns the `agent_sessions` table, plus the small
 * value types it is fed with ([PaneSnapshot], [GitInfo]) and the helpers that read git metadata and
 * the live panes.
 *
 * Two sources feed the tracker:
 *  - the 3 s screen poll ([AgentSessionTracker.onPoll]) for every agent, from
 *    what [StateDetector] sees on the terminal;
 *  - Claude Code hook events ([AgentSessionTracker.onHook]) posted to
 *    `/api/agent-events`, which are exact and win over the screen for the
 *    records they feed.
 *
 * The tracker publishes its current rows as [AgentSessionTracker.summaries]; `WindowRoutes` pushes
 * that to every client as [WindowEnvelope.AgentSessions].
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

/** What the tracker needs to know about one live terminal session. */
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

/** Git facts about a folder, read once when a record opens. */
data class GitInfo(
    val repo: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val repoRoot: String? = null,
)

/** Owns the `agent_sessions` table and the in-memory view of it. */
class AgentSessionTracker(
    private val repo: SettingsRepository,
    private val gitInfo: (String?) -> GitInfo = ::readGitInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val onEnded: (AgentSessionRecord) -> Unit = {},
    private val isUnder: (Long, Long) -> Boolean = ::processIsUnder,
    private val isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    private val log = LoggerFactory.getLogger(AgentSessionTracker::class.java)
    private val lock = Any()

    /** Records held in memory: every open one plus the most recent ended ones. */
    private val records = LinkedHashMap<String, AgentSessionRecord>()

    /** Panes whose screen still shows an agent we already ended; do not reopen until it clears. */
    private val awaitScreenClear = HashSet<String>()

    /**
     * Open records whose last `PreToolUse` has not been followed by its `PostToolUse` yet, each
     * with that call's [rawToolLine] (the actual command, file or pattern).
     */
    private val toolInFlight = HashMap<String, String?>()

    /** Ids of hook-fed `needs_you` records whose pane screen has been seen showing the prompt. */
    private val promptSeen = HashSet<String>()

    /**
     * For each open hook-fed `working` record, how many polls in a row have shown no agent at all
     * on its pane since its last hook.
     */
    private val idlePolls = HashMap<String, Int>()

    /**
     * When each open record last received a hook event, by record id. Kept in memory only: it is
     * empty after a restart, and a record with no entry publishes no activity time.
     */
    private val lastActivity = HashMap<String, Long>()

    /** When each open hook-fed record's last hook event arrived, by record id. */
    private val lastHookAt = HashMap<String, Long>()

    /**
     * What [TranscriptTail] said about each hook-fed record's transcript, by record id, with the
     * file's size and last write it was read at.
     */
    private val tailReads = ConcurrentHashMap<String, TailRead>()

    /**
     * Git facts per folder, filled by [warmGit] before the lock is taken so a slow repository never
     * holds up closing panes; [open] only reads it.
     */
    private val gitCache = ConcurrentHashMap<String, Pair<Long, GitInfo>>()

    /**
     * Tasks promised to panes whose agent has not started yet, by layout leaf id (leaf ids survive
     * restarts, terminal session ids do not). The next record opened in the leaf takes the task.
     */
    private val pendingTasks = HashMap<String, String>()

    /**
     * Helper conversations: a second `claude` started inside a pane whose own conversation is still
     * open (a plan reviewer, say), by helper record id to the record of the conversation it runs
     * inside. Memory only. A helper takes its host's task, but it is never the pane's open record
     * ([openFor]), so nothing that asks "what runs in this pane" ever answers with it.
     */
    private val helpers = HashMap<String, String>()

    /**
     * The process id of the `claude` behind each open hook-fed record, as its hooks report it. Memory
     * only, and empty for a `claude` whose hooks predate the report. With both ids known, whether a
     * second conversation in a pane is a helper is read from the process tree instead of guessed.
     */
    private val pids = HashMap<String, Long>()

    /**
     * What each open conversation says it is doing, in its own words (the `set_status` tool), by
     * record id. Memory only: it holds for the turn it was said in and goes with the next prompt.
     */
    private val statuses = HashMap<String, String>()

    /** The side-panel sections agents wrote, by conversation; saved, so they survive a restart and a resume. */
    private val sections = PanelSectionStore(repo, clock)

    private val _summaries = MutableStateFlow<List<AgentSessionSummary>>(emptyList())

    /** The rows to show clients, republished after every change. */
    val summaries: StateFlow<List<AgentSessionSummary>> = _summaries.asStateFlow()

    /** Close records a previous server run left open and load the recent ended ones. */
    fun start() = synchronized(lock) {
        repo.endOpenAgentSessions(clock()) { rec ->
            rec.transcriptPath?.let { File(it).takeIf(File::isFile)?.lastModified() }
        }
        records.clear()
        helpers.clear()
        repo.loadRecentEndedAgentSessions(clock() - HISTORY_WINDOW_MS, HISTORY_MAX).forEach { records[it.id] = it }
        publish()
    }

    /** Fold one screen-poll result into the records. */
    fun onPoll(panes: List<PaneSnapshot>) {
        val toOpen = synchronized(lock) {
            panes.filter { it.alive && it.agent != null && it.state != null && openFor(it.terminalSessionId) == null }
        }
        toOpen.forEach { warmGit(it.cwd) }
        applyPoll(panes, interruptsOfHookFed())
    }

    /**
     * When the person interrupted the turn of each hook-fed record that is working or asks them
     * something, read from the end of its transcript (see [TranscriptTail]).
     */
    private fun interruptsOfHookFed(): Map<String, Long> {
        val candidates = synchronized(lock) {
            openRecords()
                .filter { it.source != SOURCE_SCREEN && (it.state == WORKING || it.state == NEEDS_YOU) }
                .mapNotNull { r -> r.transcriptPath?.let { r.id to it } }
        }
        val found = HashMap<String, Long>()
        for ((id, path) in candidates) {
            val file = File(path)
            val size = file.length()
            val written = file.lastModified()
            val read = tailReads[id]?.takeIf { it.size == size && it.written == written }
                ?: TailRead(size, written, TranscriptTail.interruptedAt(path)).also { tailReads[id] = it }
            read.interruptedAt?.let { found[id] = it }
        }
        return found
    }

    private fun applyPoll(panes: List<PaneSnapshot>, interrupts: Map<String, Long>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        endSilentHelpers()
        for (r in openRecords()) {
            val pane = live[r.terminalSessionId]
            if (pane == null || !pane.alive) end(r, pane?.screenText?.invoke(), END_CLOSED)
        }
        for (pane in panes) {
            if (pane.agent == null || pane.state == null) awaitScreenClear.remove(pane.terminalSessionId)
            var rec = openFor(pane.terminalSessionId)
            if (rec != null && rec.source == SOURCE_SCREEN && pane.agent != null && pane.agent != rec.agent) {
                end(rec, pane.screenText(), END_REPLACED)
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
            // A pane can change tabs (a move, or a background tab shown while its agent waits): the
            // record follows the tab it is in now, or search and the overview's jump would treat it
            // as still where it was.
            if (rec.background != pane.background) {
                rec = rec.copy(background = pane.background)
                put(rec)
            }
            if (rec.source != SOURCE_SCREEN) {
                // Claude Code sends no event when a permission prompt is approved, none when it is
                // refused (a refused tool has no PostToolUse) and none when a turn is interrupted
                // (no Stop), so the hooks alone would keep the row as it was for good. The row is
                // settled from what really happened, never from a timer:
                //  - the transcript ends in the person's interrupt line, written after the last
                //    hook: the turn is over, done;
                //  - the screen showed the prompt (waiting) and now shows work: the prompt was
                //    answered Yes, working. A prompt the screen never showed is not settled by a
                //    working screen: that poll may have been taken before the prompt was drawn, or
                //    the detector may read work above a dialog, and an unanswered question must
                //    stay counted. The next hook (the tool's PostToolUse) moves such a row on;
                //  - the screen showed the prompt and now shows neither it nor work: the prompt has
                //    left without a go-ahead, done. The poller already holds the last state for a
                //    few polls, so one poll without the prompt is a prompt that has left;
                //  - a working record whose pane shows no agent at all for [IDLE_POLLS_TO_DONE]
                //    polls in a row since the last hook: the turn ended without a hook, done.
                // A prompt nothing has answered shows none of this, so it stays needs_you for good.
                // A later hook moves the row on again.
                val interruptedAt = interrupts[rec.id]
                val open = rec.state == WORKING || rec.state == NEEDS_YOU
                if (rec.state != WORKING || pane.state != null) idlePolls.remove(rec.id)
                if (open && interruptedAt != null && interruptedAt + HOOK_LAG_MS >= (lastHookAt[rec.id] ?: Long.MAX_VALUE)) {
                    rec = settleDone(rec)
                } else if (rec.state == WORKING) {
                    promptSeen.remove(rec.id)
                    if (pane.state == null) {
                        val idle = (idlePolls[rec.id] ?: 0) + 1
                        if (idle >= IDLE_POLLS_TO_DONE) rec = settleDone(rec) else idlePolls[rec.id] = idle
                    }
                } else if (rec.state != NEEDS_YOU) {
                    promptSeen.remove(rec.id)
                } else if (pane.state == "waiting") {
                    promptSeen.add(rec.id)
                } else if (promptSeen.remove(rec.id)) {
                    if (pane.state == "working") {
                        lastActivity[rec.id] = clock()
                        rec = rec.copy(state = WORKING, stateSince = clock(), question = null)
                        put(rec)
                    } else {
                        rec = settleDone(rec)
                    }
                }
                if (rec.state == WORKING && pane.state == "working") noteWork(rec.id)
                continue
            }
            val state = when (pane.state) {
                "waiting" -> NEEDS_YOU
                "working" -> WORKING
                else -> DONE
            }
            if (state == WORKING) noteWork(rec.id)
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
     * End the turn of the open hook-fed record [rec] without a hook: it becomes done, with no tool
     * line and no question, and the poll counts as its last sign of life.
     */
    private fun settleDone(rec: AgentSessionRecord): AgentSessionRecord {
        promptSeen.remove(rec.id)
        idlePolls.remove(rec.id)
        lastActivity[rec.id] = clock()
        toolInFlight.remove(rec.id)
        return rec.copy(state = DONE, stateSince = clock(), nowLine = null, question = null).also { put(it) }
    }

    /** Note that the screen shows [recordId]'s agent at work: the one sign of life both feeds give. */
    private fun noteWork(recordId: String) {
        val now = clock()
        val last = lastActivity[recordId]
        if (last == null || now - last >= ACTIVITY_REFRESH_MS) lastActivity[recordId] = now
    }

    /** Fold one Claude Code hook event into the records; [pid] is the `claude` that ran the hook, when it said. */
    fun onHook(pane: PaneSnapshot?, event: JsonObject, pid: Long? = null) {
        if (pane == null) return
        // A start says which folder `claude` runs in. The pane's own folder is the shell's as last
        // polled, which is another folder after `cd there && claude` typed as one line.
        val startedIn = event.str("cwd")?.takeIf { it.isNotBlank() && event.str("hook_event_name") == "SessionStart" }
        val at = if (startedIn != null) pane.copy(cwd = startedIn) else pane
        val known = synchronized(lock) { openFor(at.terminalSessionId)?.agentSessionId }
        if (startedIn != null || known == null || known != event.str("session_id")) warmGit(at.cwd)
        applyHook(at, event, pid, startedIn)
    }

    private fun applyHook(pane: PaneSnapshot, event: JsonObject, pid: Long?, startedIn: String? = null) = synchronized(lock) {
        val name = event.str("hook_event_name") ?: return@synchronized
        val agentId = event.str("session_id")
        val transcript = event.str("transcript_path")
        var rec = helpers.keys.firstNotNullOfOrNull { id ->
            records[id]?.takeIf { it.state != ENDED && it.agentSessionId == agentId && it.terminalSessionId == pane.terminalSessionId }
        } ?: attach(pane, agentId, transcript, event.str("source"), name, pid) ?: return@synchronized
        if (pid != null) pids[rec.id] = pid
        val isHelper = rec.id in helpers
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
            "SessionStart" -> if (startedIn != null && startedIn != rec.cwd) {
                val git = gitCache[startedIn]?.second ?: GitInfo()
                rec = rec.copy(
                    cwd = startedIn, repo = git.repo, branch = git.branch, worktreePath = git.worktreePath,
                    repoRoot = git.repoRoot, links = ticketKeys(git.branch),
                )
            }
            "UserPromptSubmit" -> {
                toolInFlight.remove(rec.id)
                statuses.remove(rec.id)
                set(WORKING, nowLine = null)
                if (rec.firstPrompt == null) rec = rec.copy(firstPrompt = event.str("prompt")?.take(PROMPT_MAX))
                if (rec.title == null && !isHelper) rec = rec.copy(title = cleanTitle(pane.programTitle))
            }
            "PreToolUse" -> {
                toolInFlight[rec.id] = rawToolLine(event)
                // A question put through AskUserQuestion is a prompt on screen the moment it starts.
                val asked = askedQuestionOf(event)
                if (asked != null) set(NEEDS_YOU, nowLine = null, question = asked.take(QUESTION_MAX))
                else set(WORKING, nowLine = toolLine(event) ?: rec.nowLine)
            }
            "PostToolUse" -> {
                toolInFlight.remove(rec.id)
                set(WORKING)
            }
            "Notification" -> {
                // Only a prompt the person has to answer needs them: a permission prompt or an
                // elicitation dialog. The idle reminder (a minute after a turn ends) and the sign-in
                // notice are not requests. The type decides; a version that omits it is read by its
                // text, with the idle reminder's wording ruled out first.
                val message = event.str("message")
                val type = event.str("notification_type")
                if (!isPromptNotification(type, message)) return@synchronized
                // The hook text is generic ("Claude needs your permission"). The tool call the prompt
                // is about arrived with the PreToolUse just before, so say what is being asked instead.
                set(NEEDS_YOU, nowLine = null, question = pendingQuestionOf(toolInFlight[rec.id], message, type)?.take(QUESTION_MAX))
            }
            "Stop" -> {
                toolInFlight.remove(rec.id)
                val last = event.str("last_assistant_message")?.trim().orEmpty()
                if (last.isNotEmpty()) rec = rec.copy(lastSaid = lastSaidOf(last))
                // A finished turn is done, whatever its last line says: a reply that ends in
                // "Want me to go ahead?" blocks nothing. Only a prompt on screen needs the person.
                set(DONE, nowLine = null)
            }
            "SessionEnd" -> {
                // A helper's end leaves the pane's screen to its own conversation.
                // The hook says why, and only what it says is kept: /clear, the person leaving at
                // the prompt, another conversation resumed in its place; anything else is just an end.
                val why = when (event.str("reason")) {
                    "clear" -> END_CLEARED
                    "prompt_input_exit" -> END_QUIT
                    "resume" -> END_REPLACED
                    else -> END_EXIT
                }
                end(rec, if (isHelper) null else pane.screenText(), why)
                if (!isHelper) awaitScreenClear.add(pane.terminalSessionId)
                publish()
                return@synchronized
            }
            else -> return@synchronized
        }
        lastActivity[rec.id] = now
        lastHookAt[rec.id] = now
        idlePolls.remove(rec.id)
        put(rec)
        publish()
    }

    private fun endHelpersOf(hostId: String) {
        helpers.filterValues { it == hostId }.keys.forEach { id -> records[id]?.let { end(it, null, END_EXIT) } }
    }

    /**
     * Ends helpers that went quiet: a helper killed without a SessionEnd sends nothing more, and its
     * transcript stops growing, while a live one (also one running in the background after its host
     * stopped) keeps doing one or the other. The screen cannot tell, because the pane shows the host.
     */
    private fun endSilentHelpers() {
        val now = clock()
        for (id in helpers.keys.toList()) {
            val rec = records[id] ?: continue
            // A helper whose process is known ends when the process does, however quiet it was.
            val pid = pids[id]
            if (pid != null) {
                if (!isAlive(pid)) end(rec, null, END_EXIT)
                continue
            }
            val written = rec.transcriptPath?.let { File(it).takeIf(File::isFile)?.lastModified() } ?: 0L
            val last = maxOf(lastHookAt[id] ?: 0L, written, rec.createdAt)
            if (now - last >= HELPER_SILENT_MS) end(rec, null, END_EXIT)
        }
    }

    /** End the open record of a terminal session that is being torn down. */
    fun onSessionDestroyed(terminalSessionId: String, screen: String?) = synchronized(lock) {
        awaitScreenClear.remove(terminalSessionId)
        openRecords().filter { it.terminalSessionId == terminalSessionId }.forEach { end(it, screen, END_CLOSED) }
        publish()
    }

    /** Mark every open record ended by Lunamux stopping, saving each pane's screen. Called at shutdown. */
    fun endAll(panes: List<PaneSnapshot>) = synchronized(lock) {
        val live = panes.associateBy { it.terminalSessionId }
        openRecords().forEach { end(it, live[it.terminalSessionId]?.screenText?.invoke(), END_RESTART) }
        publish()
    }

    /** Look up a record for the resume command. */
    fun find(id: String): AgentSessionRecord? = synchronized(lock) {
        records[id] ?: repo.findAgentSessionById(id)
            ?: records.values.filter { it.conversationId == id }.maxByOrNull { it.createdAt }
            ?: repo.findAgentSessionByConversation(id)
    }

    /**
     * One record per conversation held in memory (the open run of it, else the newest ended one),
     * copied under the lock: the records [summaries] is made of.
     */
    fun snapshot(): List<AgentSessionRecord> = synchronized(lock) { conversationRecords() }

    /** The open record of a terminal session, if an agent runs in it. */
    fun openRecordOf(terminalSessionId: String): AgentSessionRecord? = synchronized(lock) { openFor(terminalSessionId) }

    /** Put a record in a task, or take it out of its task. */
    fun setTask(recordId: String, taskId: String?): Boolean = synchronized(lock) {
        val rec = records[recordId] ?: return false
        if (rec.taskId != taskId) {
            put(rec.copy(taskId = taskId))
            publish()
        }
        true
    }

    /**
     * Sets, or with a blank [text] clears, what an open conversation says it is doing. It is named
     * by [conversationId] (the agent's own session id, which also reaches a helper) or, without
     * one, taken to be the conversation of pane [terminalSessionId]. Returns the text kept, cut to
     * [STATUS_MAX], or null when it was cleared; throws when no open conversation is found.
     */
    fun setStatus(terminalSessionId: String?, conversationId: String?, text: String): String? = synchronized(lock) {
        val rec = conversationOf(terminalSessionId, conversationId)
        val clean = text.replace(Regex("\\s*[\\r\\n]+\\s*"), " ").trim().take(STATUS_MAX).trim().ifEmpty { null }
        if (clean == null) statuses.remove(rec.id) else statuses[rec.id] = clean
        publish()
        clean
    }

    /**
     * Writes section [key] of the side panel for an open conversation, found as [setStatus] finds
     * it. Called for the `set_panel_section` tool.
     *
     * @return the section as kept.
     * @throws TaskException when no open conversation is found or a limit is passed.
     */
    fun setSection(terminalSessionId: String?, conversationId: String?, key: String, heading: String, body: String): PanelSection = synchronized(lock) {
        val kept = sections.set(conversationOf(terminalSessionId, conversationId).conversationId, key, heading, body)
        publish()
        kept
    }

    /** Takes section [key] of an open conversation's side panel away; false when it has none. Called for `remove_panel_section`. */
    fun removeSection(terminalSessionId: String?, conversationId: String?, key: String): Boolean = synchronized(lock) {
        sections.remove(conversationOf(terminalSessionId, conversationId).conversationId, key).also { if (it) publish() }
    }

    /** The open conversation named by [conversationId], else the one of pane [terminalSessionId]; throws when there is none. */
    private fun conversationOf(terminalSessionId: String?, conversationId: String?): AgentSessionRecord =
        conversationId?.let { id -> openRecords().firstOrNull { it.conversationId == id } }
            ?: terminalSessionId?.takeIf { conversationId == null }?.let(::openFor)
            ?: throw TaskException("No open agent conversation found. Pass your own \$LUNAMUX_SESSION_ID as sessionId, or your conversation id.")

    /** Promise a task to the next agent session that opens in a pane. */
    fun setPendingTask(leafId: String, taskId: String?) = synchronized(lock) {
        if (taskId == null) pendingTasks.remove(leafId) else pendingTasks[leafId] = taskId
        Unit
    }

    /** The panes that were promised [taskId] and have no agent yet. */
    fun pendingLeavesOf(taskId: String): List<String> = synchronized(lock) {
        pendingTasks.filterValues { it == taskId }.keys.toList()
    }

    /**
     * The record held in memory (every open one and the recent ended ones), without touching the
     * database.
     */
    fun cached(id: String): AgentSessionRecord? = synchronized(lock) { records[id] }

    /** Whether a Claude session that has no agent session id yet is open in [cwd]. */
    fun hasUnidentifiedClaudeIn(cwd: String): Boolean = synchronized(lock) {
        openRecords().any { it.agent == CLAUDE && it.agentSessionId == null && it.cwd == cwd }
    }

    /**
     * Pick the record a hook event belongs to, creating, adopting or reopening one; null for a late
     * event of a helper that has already ended. An unknown conversation that speaks while the pane's
     * identified conversation is open is a helper when its `claude` runs underneath that
     * conversation's `claude` in the process tree. When either process is unknown (hooks installed
     * before they reported it) that is guessed as before: it is a helper if the pane still shows an
     * agent, unless it is a `clear` or `resume` start, which replaces the conversation.
     */
    private fun attach(pane: PaneSnapshot, agentId: String?, transcript: String?, source: String?, hook: String, pid: Long?): AgentSessionRecord? {
        val current = openFor(pane.terminalSessionId)
        val second = current != null && current.agent == CLAUDE && current.agentSessionId != null && agentId != null &&
            current.agentSessionId != agentId
        val hostPid = current?.let { pids[it.id] }
        val helper = second && if (pid != null && hostPid != null) pid != hostPid && isUnder(pid, hostPid)
        else pane.agent != null && source !in REPLACING_STARTS
        if (current != null && helper) {
            val ended = records.values.any { it.agentSessionId == agentId && it.state == ENDED && it.terminalSessionId == pane.terminalSessionId }
            if (ended && hook != "SessionStart") return null
            return open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript, host = current).also { helpers[it.id] = current.id }
        }
        if (current != null && current.agent == CLAUDE) {
            if (agentId == null || current.agentSessionId == agentId) return current
        }
        val earlier = agentId?.let { id ->
            (records.values.firstOrNull { it.agent == CLAUDE && it.agentSessionId == id && it.id != current?.id }
                ?: repo.findAgentSession(CLAUDE, id)?.takeIf { it.id != current?.id })
        }
        // A hook for a conversation whose transcript is gone (a failed `claude --resume`) must not
        // pull its old record into this pane or make it look live. Unless the hook's own transcript
        // exists: Claude writes the file lazily, so a conversation closed before anything was said
        // is real once the person types.
        if (earlier != null && earlier.transcriptMissing() && (transcript == null || !File(transcript).isFile)) return null
        val provisional = current != null && current.agent == CLAUDE &&
            current.agentSessionId == null && current.source == SOURCE_SCREEN
        if (provisional && earlier == null) {
            // From here on hooks decide when the conversation needs the person, so a needs_you the
            // screen guessed (a picker or a dialog at start-up) is dropped: a session that has just
            // started sits at its prompt, which is done. The event being folded sets it again if
            // it really is a prompt.
            val screenGuess = current!!.state == NEEDS_YOU
            return current.copy(
                agentSessionId = agentId, source = SOURCE_HOOKS, transcriptPath = transcript,
                taskId = current.taskId ?: taskOfEarlier(agentId!!),
                state = if (screenGuess) DONE else current.state,
                stateSince = if (screenGuess) clock() else current.stateSince,
                question = if (screenGuess) null else current.question,
            ).also { put(it) }
        }
        if (current != null) {
            if (provisional) discard(current) else end(current, pane.screenText(), END_REPLACED)
        }
        awaitScreenClear.remove(pane.terminalSessionId)
        if (earlier != null && earlier.state == ENDED) {
            return earlier.copy(
                source = SOURCE_HOOKS,
                leafId = pane.leafId,
                terminalSessionId = pane.terminalSessionId,
                state = DONE,
                stateSince = clock(),
                nowLine = null,
                question = null,
                lastScreen = null,
                endedAt = null,
                endReason = null,
                endedFrom = null,
                title = earlier.title ?: current?.title.takeIf { provisional },
                taskId = pane.leafId?.let { pendingTasks.remove(it) } ?: earlier.taskId ?: current?.taskId.takeIf { provisional },
                background = pane.background,
            ).also { put(it) }
        }
        return open(pane, CLAUDE, SOURCE_HOOKS, agentId, transcript)
    }

    /** Forget a record that duplicates a conversation another record already carries. */
    private fun discard(rec: AgentSessionRecord) {
        records.remove(rec.id)
        forgetTurn(rec.id)
        promptSeen.remove(rec.id)
        runCatching { repo.deleteAgentSession(rec.id) }
            .onFailure { log.warn("Failed to delete agent session {}", rec.id, it) }
    }

    /** Drop what the tracker holds about a record's running turn, once the record is gone. */
    private fun forgetTurn(recordId: String) {
        lastActivity.remove(recordId)
        lastHookAt.remove(recordId)
        pids.remove(recordId)
        statuses.remove(recordId)
        tailReads.remove(recordId)
        toolInFlight.remove(recordId)
        idlePolls.remove(recordId)
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
        host: AgentSessionRecord? = null,
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
            title = pane.customName?.trim()?.takeIf { it.isNotEmpty() && host == null },
            transcriptPath = transcriptPath,
            links = ticketKeys(git.branch),
            state = DONE,
            stateSince = now,
            createdAt = now,
            taskId = if (host != null) host.taskId
            else pane.leafId?.let { pendingTasks.remove(it) } ?: agentSessionId?.let(::taskOfEarlier),
            background = pane.background,
            host = host?.conversationId,
        ).also { put(it) }
    }

    /** Ends [rec], keeping [screen] as its last and [reason] (one of the `END_` words) as why. */
    private fun end(rec: AgentSessionRecord, screen: String?, reason: String) {
        if (helpers.remove(rec.id) == null) endHelpersOf(rec.id)
        forgetTurn(rec.id)
        val now = clock()
        val ended = rec.copy(
            endReason = reason,
            endedFrom = rec.state,
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
     * Drop ended records from memory once they are older than [HISTORY_WINDOW_MS] or beyond the
     * newest [HISTORY_MAX]; they stay in the database and search still finds them.
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
        records.values.firstOrNull { it.state != ENDED && it.terminalSessionId == terminalSessionId && it.id !in helpers }

    /**
     * Publish one summary per conversation. A conversation that ran in several panes over time has
     * several records; its summary is the open record's, else the newest one's.
     */
    private fun publish() {
        _summaries.value = conversationRecords().map { it.toSummary().copy(lastActivityAt = lastActivity[it.id], status = statuses[it.id], sections = sections.of(it.conversationId)) }
    }

    /** One record per conversation: the open one (the newest if several), else the newest ended. */
    private fun conversationRecords(): List<AgentSessionRecord> =
        records.values.groupBy { it.conversationId }.values.map { run ->
            run.filter { it.state != ENDED }.maxByOrNull { it.createdAt } ?: run.maxBy { it.endedAt ?: it.createdAt }
        }

    private companion object {
        const val CLAUDE = "claude"
        const val SOURCE_SCREEN = "screen"
        const val SOURCE_HOOKS = "hooks"
        const val NEEDS_YOU = "needs_you"
        const val WORKING = "working"
        const val DONE = "done"
        const val ENDED = "ended"

        /** Why a record ended: Lunamux stopped under it, the agent exited, its pane went, or another conversation took the pane. */
        const val END_RESTART = "restart"
        const val END_EXIT = "exit"
        const val END_CLEARED = "cleared"
        const val END_QUIT = "quit"
        const val END_CLOSED = "closed"
        const val END_REPLACED = "replaced"

        /** SessionStart sources that replace the pane's conversation rather than start a helper beside it. */
        val REPLACING_STARTS = setOf("clear", "resume")

        /**
         * How far back ended sessions are kept in memory and pushed to clients: the overview lists
         * two weeks. The edge is inclusive here; the client lists no more than this, so a row it
         * shows is never missing from the server.
         */
        const val HISTORY_WINDOW_MS = 31L * 24 * 60 * 60 * 1000

        /**
         * Most ended records held in memory. [publish] sends every record held on each change, so
         * this bounds the payload (a summary is a few hundred bytes, so 300 is about 100 KB at
         * worst) however busy two weeks were.
         */
        const val HISTORY_MAX = 300L

        /** How old a screen-derived activity stamp must be before a working poll renews it. */
        const val ACTIVITY_REFRESH_MS = 30_000L

        /**
         * How much earlier than a hook's arrival an interrupt line may be stamped and still count
         * as newer: the hook reaches the server a moment after Claude Code ran it, and the person's
         * interrupt can land in between.
         */
        const val HOOK_LAG_MS = 1_000L

        /** How long a helper may send no hook and write no transcript before it counts as gone. */
        const val HELPER_SILENT_MS = 10 * 60_000L

        /**
         * Polls in a row (3 s apart, after the poller's own hold of the last state) that must show
         * no agent on a hook-fed working record's pane, since its last hook, before the turn counts
         * as over.
         */
        const val IDLE_POLLS_TO_DONE = 3
        const val PROMPT_MAX = 500
        const val STATUS_MAX = 120
        const val QUESTION_MAX = 400
        const val GIT_CACHE_MS = 60_000L
    }
}

/** Whether process [pid] runs underneath process [ancestor]: one of its parents, however far up, is [ancestor]. */
internal fun processIsUnder(pid: Long, ancestor: Long): Boolean {
    var at = ProcessHandle.of(pid).flatMap { it.parent() }
    repeat(MAX_PROCESS_DEPTH) {
        val handle = at.orElse(null) ?: return false
        if (handle.pid() == ancestor) return true
        at = handle.parent()
    }
    return false
}

private const val MAX_PROCESS_DEPTH = 32

/** One read of a hook-fed record's transcript tail, kept so the file is read again only when it changes. */
internal data class TailRead(val size: Long, val written: Long, val interruptedAt: Long?)

/** The row the clients see for this record. */
internal fun AgentSessionRecord.toSummary() = AgentSessionSummary(
    id = id, agent = agent, state = state, stateSince = stateSince,
    title = title, links = links, repo = repo, branch = branch, cwd = cwd,
    cwdHome = cwd != null && prettifyPath(cwd) == "~",
    cwdShown = cwd?.let(::prettifyPath),
    nowLine = nowLine, question = question, leafId = leafId, endedAt = endedAt,
    lastSaid = lastSaid,
    endReason = endReason,
    endedFrom = endedFrom,
    firstPrompt = firstPrompt?.let(::firstPromptLine),
    taskId = taskId,
    background = background,
    startedAt = createdAt,
    conversationId = conversationId,
    noTranscript = transcriptMissing(),
    nothingSaid = nothingSaid(),
)

/**
 * Whether this closed Claude conversation recorded a transcript file that is no longer on disk, so
 * `claude --resume` could not find it. A record that never named a file is not judged.
 */
internal fun AgentSessionRecord.transcriptMissing(): Boolean =
    state == "ended" && agent == "claude" && transcriptPath != null && !File(transcriptPath).isFile

/**
 * Whether this closed conversation was fed by Claude's hooks yet never saw a prompt submitted or an
 * answer given, and was never titled: the person started `claude` and left (or cancelled `/resume`).
 * Records fed by the screen never carry a prompt, so they are not judged.
 */
internal fun AgentSessionRecord.nothingSaid(): Boolean =
    state == "ended" && agent == "claude" && source == "hooks" && firstPrompt == null && lastSaid == null && title == null

private const val TOOL_LINE_MAX = 160
private const val FIRST_PROMPT_MAX = 160

/** The first line of what the user first asked a session, cut for a status cell. */
internal fun firstPromptLine(prompt: String): String? =
    prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(FIRST_PROMPT_MAX)
private const val LAST_SAID_MAX = 240

/**
 * The part of an assistant reply worth showing as "what it last said": the first line of the last
 * paragraph, cut to [LAST_SAID_MAX] characters at the end of a word, with "…" when it was cut.
 */
internal fun lastSaidOf(message: String): String {
    val paragraphs = message.trim().split("\n\n").asReversed()
    val line = paragraphs.firstNotNullOfOrNull { p ->
        p.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !AgentSessionText.isFenceLine(it) }
    }
    val text = line ?: ""
    if (text.length <= LAST_SAID_MAX) return text
    val head = text.take(LAST_SAID_MAX)
    return head.substring(0, head.lastIndexOf(' ').takeIf { it > LAST_SAID_MAX / 2 } ?: head.length).trimEnd() + "…"
}

private const val SCREEN_LINES = 200
private val TICKET_KEY = Regex("""\b[A-Z][A-Z0-9]+-\d+\b""")

/** Ticket keys such as `ABC-12` found in a branch name or title. */
internal fun ticketKeys(text: String?): List<String> =
    if (text == null) emptyList() else TICKET_KEY.findAll(text).map { it.value }.distinct().toList()

/** The last 200 lines of [screen], trailing blank lines removed. */
internal fun lastLines(screen: String): String =
    screen.trimEnd().lines().takeLast(SCREEN_LINES).joinToString("\n")

/**
 * Tidy a program-set terminal title for display: strips the spinner or status glyph an agent
 * prefixes and drops titles that only name the agent.
 */
internal fun cleanTitle(raw: String?): String? {
    val t = raw?.trim()?.dropWhile { !it.isLetterOrDigit() }?.trim().orEmpty()
    if (t.isEmpty()) return null
    return if (t.lowercase() in setOf("claude", "claude code", "codex", "gemini", "gemini cli")) null else t
}

/** One line saying in words what a tool call does, for the row of a working session; null when the call says nothing specific. */
internal fun toolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    askedLine(tool, input)?.let { return it }
    return wordsFor(tool, input)?.take(TOOL_LINE_MAX)
}

/**
 * The tool call as Claude Code made it: the tool name and its main argument (the command, file
 * path, pattern, URL or query), which is what a permission prompt asks the person to allow.
 */
internal fun rawToolLine(event: JsonObject): String? {
    val tool = event.str("tool_name") ?: return null
    val input = event["tool_input"] as? JsonObject
    askedLine(tool, input)?.let { return it }
    val arg = listOf("command", "file_path", "pattern", "path", "url", "query")
        .firstNotNullOfOrNull { input?.str(it) }
        ?.let(::firstLine)
    return (if (arg == null) tool else "$tool $arg").take(TOOL_LINE_MAX)
}

/** The question an `AskUserQuestion` call puts to the person, or null for any other call. */
internal fun askedQuestionOf(event: JsonObject): String? {
    if (event.str("tool_name") != ASK_TOOL) return null
    val asked = (((event["tool_input"] as? JsonObject)?.get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)
        ?.str("question")
    return asked?.trim()?.takeIf { it.isNotEmpty() } ?: "A question is waiting"
}

/** Whether a Notification hook is a prompt the person has to answer. */
internal fun isPromptNotification(type: String?, message: String?): Boolean = when (type) {
    "permission_prompt", "elicitation_dialog" -> true
    null -> message != null && !message.contains("waiting for your input", ignoreCase = true) &&
        (message.contains("permission", ignoreCase = true) || message.contains("needs your", ignoreCase = true))
    else -> false
}

/** The `Asks: <question>` line for an `AskUserQuestion` call that carries a question, else null. */
private fun askedLine(tool: String, input: JsonObject?): String? {
    if (tool != ASK_TOOL) return null
    val asked = ((input?.get("questions") as? JsonArray)?.firstOrNull() as? JsonObject)?.str("question")
    return if (asked.isNullOrBlank()) null else (ASK_PREFIX + asked.trim()).take(TOOL_LINE_MAX)
}

/** The first non-blank line of [text], trimmed, or null when it has none. */
private fun firstLine(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

/** The last path segment of [path], so a row names the file and not where it lives. */
private fun fileNameOf(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/**
 * The words for one tool call, before the length cut; null when the call names nothing a person
 * could use (a command with no description, a tool with no argument), so the row keeps its last line.
 */
private fun wordsFor(tool: String, input: JsonObject?): String? {
    fun arg(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { input?.str(it)?.let(::firstLine) }
    fun file(vararg keys: String): String? = arg(*keys)?.let(::fileNameOf)?.takeIf { it.isNotEmpty() }
    return when (tool) {
        "Bash" -> arg("description")
        "Edit", "MultiEdit" -> file("file_path")?.let { "Editing $it" }
        "NotebookEdit" -> file("notebook_path", "file_path")?.let { "Editing $it" }
        "Write" -> file("file_path")?.let { "Writing $it" }
        "Read" -> file("file_path")?.let { "Reading $it" }
        "Grep" -> arg("pattern")?.let { "Searching for \"$it\"" }
        "Glob" -> arg("pattern")?.let { "Finding files matching \"$it\"" }
        "LS" -> file("path")?.let { "Listing $it" }
        "WebSearch" -> arg("query")?.let { "Searching the web for \"$it\"" }
        "WebFetch" -> arg("url")?.let { "Fetching ${hostOf(it)}" }
        "Task", "Agent" -> arg("description")?.let { "Running an agent: $it" }
        "Skill" -> arg("skill", "name")?.let { "Using the $it skill" }
        else -> mcpWords(tool)
    }
}

/** The host of [url] (`example.com` for `https://example.com/a?b`), or the url itself when it has none. */
private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfter('@').ifEmpty { url }

/** `<server>: <tool in words>` for an MCP tool named `mcp__<server>__<tool>`, else null. */
private fun mcpWords(tool: String): String? {
    if (!tool.startsWith("mcp__")) return null
    val rest = tool.removePrefix("mcp__")
    val server = rest.substringBefore("__").removePrefix("claude_ai_")
    val name = rest.substringAfter("__", "")
    if (server.isEmpty() || name.isEmpty()) return null
    return "${wordsOf(server)}: ${wordsOf(name)}"
}

/** [name] with underscores read as spaces (`list_tasks` is `list tasks`). */
private fun wordsOf(name: String): String = name.replace('_', ' ').trim()

/** The tool Claude Code uses to put a question to the person, whose text [toolLine] keeps. */
private const val ASK_TOOL = "AskUserQuestion"

/** Marks a [toolLine] that holds a question put to the person rather than a command. */
private const val ASK_PREFIX = "Asks: "

/** What a `needs_you` row should say for a prompt Claude Code raised through a Notification hook. */
internal fun pendingQuestionOf(pending: String?, message: String?, type: String? = null): String? = when {
    pending == null -> message
    pending.startsWith(ASK_PREFIX) -> pending.removePrefix(ASK_PREFIX)
    type == null || type == "permission_prompt" -> "Allow $pending?"
    else -> message
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Read repository, branch and worktree for [cwd] with `git`. */
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

/** Snapshot a live terminal session for the tracker. */
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
