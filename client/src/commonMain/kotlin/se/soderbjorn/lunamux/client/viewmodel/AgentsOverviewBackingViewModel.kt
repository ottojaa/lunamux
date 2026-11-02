/**
 * Backing ViewModel for the agents overview: the toolbar chip and the
 * ⌘⇧A overlay that list every agentic-CLI session (Claude Code, Codex,
 * Gemini) Lunamux knows about.
 *
 * The server pushes one list of session summaries; this ViewModel turns it
 * into the sections the overlay shows (NOW, then EARLIER in two recency bands: the last few
 * days and the last two weeks), the chip counts and the keyboard selection. Typing two or more characters switches the
 * overlay to the server's search over every session ever recorded (titles,
 * first prompts, branches, links and transcripts). The ViewModel carries out
 * what a row offers: jump to its pane, resume it in a new tab, peek at its
 * screen, and close it after a confirmation that names what would be lost.
 * The view only renders [State] and forwards key presses.
 *
 * Every row is one line of a single grid whose second column is the state glyph. The unit is a
 * task. A task with two or more sessions, or whose task file gives an `open` action, is an
 * expansion panel: a header row whose counts say what its sessions are doing, followed by the
 * sessions that need the user or work, or by every session, most urgent first, once the panel is
 * expanded. A task with one session and no `open` action is drawn as that session's plain row. A
 * task stays in NOW while it has open work (a session needs the person or works, or a task file
 * still has a session in review or queued), ranked beside single sessions by its most urgent
 * session. What has finished or gone quiet moves to EARLIER, by task: a task's
 * recency is its newest session's activity, so one recent session keeps the whole task in the
 * first band. A task there is a collapsed panel that says how many sessions it holds and how
 * long ago it was last active. The header's ↵ goes to the session that most needs the person (a ◆ that
 * has waited longest, else the busiest working one); with no live session it runs the task's
 * `open` action, and with neither it expands or collapses the panel. A session Lunamux does not
 * host opens with `claude attach <session id>` in a new tab; the id comes from the task file and
 * the server builds the command.
 *
 * The selection is a row id, not an index, so it stays on its row while rows appear and
 * disappear, and it opens on the ◆ that has waited longest.
 *
 * @see se.soderbjorn.lunamux.AgentSessionSummary
 * @see se.soderbjorn.lunamux.AgentTask
 * @see WindowStateRepository.agentSessions
 * @see WindowStateRepository.agentTasks
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.effectiveActiveTabId
import se.soderbjorn.lunamux.client.WindowStateRepository

/**
 * Where a [AgentsOverviewBackingViewModel.Row] sits in a pane layout: the
 * world, tab and leaf a jump has to activate.
 *
 * @property worldId the world holding the tab, or null when worlds are not in use.
 * @property tabId the tab holding the pane.
 * @property leaf the pane's leaf, used for its custom name.
 */
private data class PaneLocation(val worldId: String?, val tabId: String, val leaf: LeafNode)

/**
 * ViewModel behind the agents overview chip and overlay.
 *
 * Created once per window by the web client (and usable by the mobile apps
 * later); the view collects [stateFlow] to redraw and calls the action
 * functions from its key handlers.
 *
 * @param windowState the source of the session list, the layout (for custom
 *   pane names and jump targets) and the last server error.
 * @param scope where the fire-and-forget inspection requests (sent when the
 *   selection changes) run. The default runs them in place until they suspend,
 *   so a `send` that returns at once is observable immediately.
 * @param clock the current epoch millis, read whenever the state is built; it decides which
 *   idle sessions are still inside their [IDLE_GRACE_MS]. Tests pass a fixed one.
 * @param send sends a command to the server, normally `windowSocket::send`.
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /**
     * One row of the overlay.
     *
     * @property id the server's record id; what a resume names.
     * @property agent `claude`, `codex` or `gemini`.
     * @property state `needs_you`, `working`, `idle` or `ended`.
     * @property title the display title: the session's own title when it says what the work is
     *   (the server keeps a name the user gave the pane, even after the pane closes), else the first
     *   words of its first prompt, else the agent's name. Never a path, `~`, `HEAD`, the branch, the
     *   folder or a run id: those say where the session ran, not what it is for.
     * @property links ticket keys found in the branch or title.
     * @property repo repository folder name, or null.
     * @property nowLine the last tool line, or null.
     * @property question what a `needs_you` row is asking, or null.
     * @property sinceMillis epoch millis the row entered its state (when it ended, for ended rows);
     *   the view turns it into an age.
     * @property isLive whether the agent's pane is still open, so ↵ jumps to it.
     * @property canResume whether ↵ opens a new tab for it (an ended row).
     * @property actionLabel the word of the ↵ action, one word per action on every row: `jump`
     *   goes to a live pane, `resume` opens a session in a new tab (a closed one, or one the task
     *   file names that `claude attach` opens), `open folder` is a Gemini session's; a task header's
     *   own `open` action is the only `open`; for a task header what ↵ does: `jump` (a
     *   live session needs the person or works), else `open` (the task has an `open` action), else
     *   empty (↵ then expands or collapses it); empty for a session Lunamux has no record of and the
     *   file gave no session id, which has nothing to open.
     * @property error a message from the last failed resume of this row, or null.
     * @property canClose whether × and ⌘⌫ can close it: it has an open pane.
     * @property field in a search result, where the query was found: `title`, `task`, `prompt`,
     *   `branch`, `links`, `transcript` or `screen`; null outside a search.
     * @property snippet in a search result, the text around the match (null for a title match,
     *   where the title itself is highlighted).
     * @property matchStart where the match begins, inside [snippet] or, for a title match, [title],
     *   or, for a task match, [taskTitle].
     * @property matchLength how many characters the match covers; 0 when there is nothing to highlight.
     * @property resumeNote what Resume will do when it is not the plain case (for example
     *   "Worktree removed. Resume recreates it from branch x."); shown for search results and the
     *   selected row, null otherwise.
     * @property kind what the row is: a session (or a task's session) or a task header. Only
     *   [RowKind.SESSION] rows exist in a search.
     * @property taskId the task a header or a task's session belongs to; null for a session that
     *   is a task of its own.
     * @property memberKey for a session the task file lists, the file member's key, which names it
     *   to the server; null for every other row.
     * @property unhosted true for a task member Lunamux does not host (it has no session record);
     *   it shows what the task file says, and ↵ opens it with `claude attach` when [sessionId] is set.
     * @property label a task member's status word from the task file ("in review", "done"), shown
     *   before [status]; null for a member that works or needs the user, whose live line says it all.
     * @property activityMillis for a working row, epoch millis of its last sign of life: the newer
     *   of the session's last activity (when Lunamux hosts it) and the time its task file was last
     *   refreshed (when the file declares the member). Null for a row that is not working. The view
     *   animates the working glyph only while [isWorkingLive] says so.
     * @property fileRefreshedMillis for a task member Lunamux does not host, epoch millis the task
     *   file that declares it was last refreshed; null for every other row. The view marks the row
     *   stale when [isStale] says so.
     * @property glyph which state glyph column B draws; the working glyph still needs [isWorkingLive]
     *   to say whether it moves.
     * @property status the one-line status for column E, without [label]: the question of a ◆ row,
     *   the last tool line of a working row, what an idle or closed row last said, falling back to
     *   "❯ " plus its first prompt. Never blank on a row without a [label].
     * @property where column D: the repository of a session or header, else the folder it runs in
     *   (`~` for the home folder); the same meaning on every row. Null when there is none.
     * @property pullRequest a task member's pull request ("#22"), shown as detail text after the
     *   status and never in [where]; null for a row that has none.
     * @property sessionId for a task member Lunamux does not host, the id the task file gave for it,
     *   which `claude attach` takes; null otherwise.
     * @property taskTitle in a search result, the title of the task the session belongs to, so the
     *   result can say "in task X"; null when the session is in no task or outside a search. For a
     *   `task` match, [matchStart] and [matchLength] mark the query inside it.
     */
    data class Row(
        val id: String,
        val agent: String,
        val state: String,
        val title: String,
        val links: List<String>,
        val repo: String?,
        val nowLine: String?,
        val question: String?,
        val sinceMillis: Long,
        val isLive: Boolean,
        val canResume: Boolean,
        val actionLabel: String,
        val error: String? = null,
        val canClose: Boolean = false,
        val field: String? = null,
        val snippet: String? = null,
        val matchStart: Int = 0,
        val matchLength: Int = 0,
        val resumeNote: String? = null,
        val kind: RowKind = RowKind.SESSION,
        val taskId: String? = null,
        val memberKey: String? = null,
        val unhosted: Boolean = false,
        val label: String? = null,
        val activityMillis: Long? = null,
        val fileRefreshedMillis: Long? = null,
        val glyph: Glyph = Glyph.NONE,
        val status: String = "",
        val where: String? = repo,
        val pullRequest: String? = null,
        val sessionId: String? = null,
        val taskTitle: String? = null,
    )

    /** What a [Row] stands for. */
    enum class RowKind {
        /** A session, or a task's session (then [Row.taskId] is set). */
        SESSION,

        /** The header of a task panel. [Row.id] is `task:<id>`. */
        TASK,
    }

    /** The state glyph a row draws in column B. */
    enum class Glyph {
        /** ◆: one thing that needs the user. */
        NEEDS_YOU,

        /** The braille working glyph (still and dimmed when [isWorkingLive] is false). */
        WORKING,

        /** ○: waiting on someone else, or idle at its prompt. */
        WAITING,

        /** ‖: parked on purpose. */
        PAUSED,

        /** ✓: finished. */
        DONE,

        /** ·: closed; can be resumed. */
        CLOSED,

        /** Nothing: a task header (it shows the working glyph through [Task.activityMillis]). */
        NONE,
    }

    /** Which key hints the footer shows; chosen by the selected row. */
    enum class Footer {
        /** A live session or a task member in a Lunamux pane. */
        LIVE,

        /** A closed session. */
        CLOSED,

        /** A collapsed task header. */
        TASK_COLLAPSED,

        /** An expanded task header. */
        TASK_EXPANDED,

        /** A task member Lunamux has no pane for and the file gave no session id for. */
        NO_SESSION,

        /** A task member Lunamux has no pane for but `claude attach` can open. */
        RUN_ATTACH,

        /** Nothing is selected. */
        NONE,
    }

    /**
     * One key hint of the footer: a key and what it does.
     *
     * @property key the key as drawn, such as `→` or `⌘⌫`; empty for a plain remark.
     * @property word what the key does, or the remark itself.
     * @property role how the hint gives way when the panel narrows.
     */
    data class Hint(val key: String, val word: String, val role: HintRole = HintRole.OTHER)

    /** How a footer [Hint] gives way when the panel gets narrow. */
    enum class HintRole {
        /** "↑↓ move": dropped first. */
        MOVE,

        /** "← back to the task": dropped next. */
        BACK,

        /** Any other hint. */
        OTHER,
    }

    /**
     * What the view needs to draw one task panel's header.
     *
     * @property id the task's id.
     * @property title the task's name.
     * @property repo repository name, or null.
     * @property needYouCount sessions that need the user; drawn highlighted before [summary].
     * @property workingCount sessions at work, quiet or stale ones included: the header's moving
     *   glyph needs them, while [summary] counts a quiet or stale one under that word.
     * @property summary the rest of the header's count line, without the needs-you part
     *   ("1 working · 1 quiet · 1 in review · 2 done"; a quiet or stale session is counted under that
     *   word, as its row says, never as working): in the fixed order working, quiet or stale, in review, queued,
     *   other labels as the file gave them, paused, done, with empty counts left out; empty when
     *   every session needs the user.
     * @property expanded whether every session shows rather than only those that need the user or work.
     * @property jumpRecordId the record of the live session that most needs the person, which ↵ on
     *   the header jumps to: the ◆ that has waited longest, else the working session with the
     *   freshest sign of life; null when no session of the task is open.
     * @property hasOpen whether the task file gives an `open` action: ↵ on the header runs it when
     *   no session is open, and the header always offers it as its own `open` control.
     * @property activityMillis the latest sign of life among the working sessions (see
     *   [Row.activityMillis]); null when none works, so the header's working glyph is empty. The
     *   header draws the moving glyph while [isWorkingLive] says that stamp is fresh.
     * @property ageMillis for a task in EARLIER, epoch millis of its newest session's activity, which
     *   the header shows as its age; null for a task in NOW, whose header shows counts and no age.
     */
    data class Task(
        val id: String,
        val title: String,
        val repo: String?,
        val needYouCount: Int,
        val workingCount: Int,
        val summary: String,
        val expanded: Boolean,
        val jumpRecordId: String?,
        val hasOpen: Boolean,
        val activityMillis: Long? = null,
        val ageMillis: Long? = null,
    )

    /**
     * The peek popover beside the selected row.
     *
     * @property live whether it shows the pane's current screen (refreshed while open) rather than
     *   the scrollback saved when the session ended.
     * @property title the session's title.
     * @property closedSinceMillis when a closed session ended, for "CLOSED <age>"; null when live.
     * @property lines the last non-blank screen lines.
     * @property loading true until the server has answered.
     * @property emptyText what to show instead of [lines] when there is nothing, else null.
     */
    data class Peek(
        val live: Boolean,
        val title: String,
        val closedSinceMillis: Long?,
        val lines: List<String>,
        val loading: Boolean,
        val emptyText: String?,
    )

    /**
     * The inline confirmation before a session is closed.
     *
     * @property rowId the row that would be closed.
     * @property title its title, quoted in the question.
     * @property warnings what would be lost or interrupted, one line each; empty when nothing.
     * @property cancelFocused whether Cancel is the focused button, which it is until the user
     *   moves to "Close session", so a stray ↵ never closes anything.
     */
    data class Confirm(
        val rowId: String,
        val title: String,
        val warnings: List<String>,
        val cancelFocused: Boolean,
    )

    /**
     * Everything the view renders.
     *
     * @property open whether the overlay is showing.
     * @property query the search text.
     * @property matchMode whether the query is long enough to search every session; the list is
     *   .
     * @property matches the server's answer for the current query, best match first; empty until it arrives.
     * @property searching true while a [matchMode] answer for the current query is still on its way.
     * @property now needs-you rows first, then working rows, longest waiting first.
     * @property recent EARLIER's first band, "Last few days": idle and ended units whose newest
     *   activity is at most [RECENT_MS] old, newest first, all shown. A task is one collapsed
     *   header (its sessions follow once expanded).
     * @property fortnight EARLIER's second band, "Last two weeks": units between [RECENT_MS] and
     *   [WINDOW_MS] old, newest first, cut to [EARLIER_LIMIT] units until "More" is pressed.
     *   Nothing older is listed; search finds it.
     * @property earlierHidden how many units of [fortnight] "More" would reveal.
     * @property needYouCount rows asking for the user; the chip's first number.
     * @property workingCount sessions that work and are moving (neither quiet nor stale, see
     *   [isMoving]); the chip's second number.
     * @property selectedId the id of the highlighted row, or null when there are no rows.
     * @property peek the screen popover, or null while it is closed.
     * @property confirm the close confirmation, or null while none is asked.
     * @property tasks the task panels drawn in [now], [recent] and [fortnight], by task id; a task drawn as a
     *   plain session row is not in it.
     * @property hasSessions whether any agent session or task has ever been recorded, which is
     *   when the toolbar chip shows.
     * @property footer which key hints the footer shows for the selected row.
     */
    data class State(
        val open: Boolean = false,
        val query: String = "",
        val matchMode: Boolean = false,
        val matches: List<Row> = emptyList(),
        val searching: Boolean = false,
        val now: List<Row> = emptyList(),
        val recent: List<Row> = emptyList(),
        val fortnight: List<Row> = emptyList(),
        val earlierHidden: Int = 0,
        val needYouCount: Int = 0,
        val workingCount: Int = 0,
        val selectedId: String? = null,
        val peek: Peek? = null,
        val confirm: Confirm? = null,
        val tasks: Map<String, Task> = emptyMap(),
        val hasSessions: Boolean = false,
        val footer: Footer = Footer.NONE,
    ) {
        /** Every EARLIER row: [recent] then [fortnight]. */
        val earlier: List<Row> get() = recent + fortnight

        /** The rows in the order the keyboard walks them: [matches], or [now], [recent] then [fortnight]. */
        val rows: List<Row> get() = if (matchMode) matches else now + recent + fortnight

        /** Index into [rows] of the highlighted row, or -1 when there is none. */
        val selected: Int get() = rows.indexOfFirst { it.id == selectedId }

        /** The highlighted row, or null. */
        val selectedRow: Row? get() = rows.firstOrNull { it.id == selectedId }

        /** The heading over a search answer ("3 MATCHES"), or null outside a search. */
        val matchHeading: String?
            get() = if (!matchMode || searching) null
            else "${matches.size} ${if (matches.size == 1) "MATCH" else "MATCHES"}"

        /** What to show when [rows] is empty. */
        val emptyText: String
            get() = when {
                matchMode && searching -> "Searching…"
                matchMode -> "Nothing matches “${query.trim()}”. " +
                    "Search covers titles, first prompts, branches, links and transcripts."
                else -> "No agent sessions yet"
            }

        /** Whether the chip should show: from the first recorded agent session on, even while all are idle. */
        val chipVisible: Boolean get() = hasSessions

        /**
         * The chip's first part, "2 need you" (the view prefixes ◆ and draws it in the warning
         * colour), or null when nothing needs the user.
         */
        val chipNeeds: String? get() = if (needYouCount > 0) needYouText(needYouCount) else null

        /**
         * The chip's second part: "3 working", or "agents idle" when nothing needs the user and
         * nothing works; null when only the first part is shown.
         */
        val chipRest: String?
            get() = when {
                workingCount > 0 -> "$workingCount working"
                needYouCount == 0 -> "agents idle"
                else -> null
            }
    }

    /** The view-owned inputs; the server-owned ones are combined in from [windowState]. */
    private data class Ui(
        val open: Boolean = false,
        val query: String = "",
        val selectedId: String? = null,
        val selectedHint: Int = 0,
        val showAllEarlier: Boolean = false,
        val resumingId: String? = null,
        val resumeFromTab: String? = null,
        val peekOpen: Boolean = false,
        val confirmRowId: String? = null,
        val confirmOnClose: Boolean = false,
        val expandedTasks: Set<String> = emptySet(),
    )

    /** Everything [build] reads, taken from [windowState] and [ui] in one go. */
    private data class Inputs(
        val sessions: List<AgentSessionSummary>,
        val tasks: List<AgentTask>,
        val config: WindowConfig?,
        val error: String?,
        val matches: WindowEnvelope.AgentSessionMatches?,
        val inspection: WindowEnvelope.AgentSessionInspection?,
        val ui: Ui,
    )

    private val ui = MutableStateFlow(Ui())

    /** Bumped by [tick] so the state is built again when only the clock has moved. */
    private val clockTick = MutableStateFlow(0)

    /**
     * Build the state again against the current time.
     *
     * Called by the web view's periodic redraw: an idle session leaves NOW once its grace
     * period has passed, which no server message announces.
     *
     * @see IDLE_GRACE_MS
     */
    fun tick() = clockTick.update { it + 1 }

    /** The row and liveness last inspected, so a redraw does not ask the server again. */
    private var inspected: String? = null

    /**
     * Render-ready state; emits whenever the sessions, layout, error or view inputs change.
     * Collected by the view to redraw the chip and the overlay.
     */
    val stateFlow: Flow<State> = combine(
        listOf<Flow<Any?>>(
            windowState.agentSessions,
            windowState.agentTasks,
            windowState.config,
            windowState.agentSessionError,
            windowState.agentMatches,
            windowState.agentInspection,
            ui,
            clockTick,
        ),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        build(
            Inputs(
                v[0] as List<AgentSessionSummary>,
                v[1] as List<AgentTask>,
                v[2] as WindowConfig?,
                v[3] as String?,
                v[4] as WindowEnvelope.AgentSessionMatches?,
                v[5] as WindowEnvelope.AgentSessionInspection?,
                v[6] as Ui,
            ),
        )
    }.onEach {
        settleResume()
        if (it.open) scope.launch { inspectSelected() }
    }

    /** The state right now, for key handlers that must act on what is on screen. */
    val state: State
        get() {
            settleResume()
            return build(inputs())
        }

    private fun inputs() = Inputs(
        windowState.agentSessions.value,
        windowState.agentTasks.value,
        windowState.config.value,
        windowState.agentSessionError.value,
        windowState.agentMatches.value,
        windowState.agentInspection.value,
        ui.value,
    )

    /**
     * Forget a resume once its new tab is active, so the overlay is closed for good
     * rather than hidden: returning to the tab the user started from must not
     * bring it back.
     */
    private fun settleResume() {
        val current = ui.value
        if (current.resumingId != null &&
            windowState.config.value?.effectiveActiveTabId != current.resumeFromTab
        ) {
            ui.value = Ui(expandedTasks = current.expandedTasks)
        }
    }

    /**
     * Show the overlay with an empty search and the ◆ row that has waited longest selected (the
     * first row when nothing needs the user). Task panels the user expanded stay expanded. Called by
     * ⌘⇧A and the chip.
     */
    fun open() {
        windowState.clearAgentSessionError()
        inspected = null
        val fresh = Ui(open = true, expandedTasks = ui.value.expandedTasks)
        val first = build(inputs().copy(ui = fresh))
        ui.value = fresh.copy(selectedId = first.selectedId, selectedHint = maxOf(first.selected, 0))
    }

    /**
     * Hide the overlay and forget the search. Task panels the user expanded stay expanded for as long
     * as the app runs. Called by esc and after a jump or resume.
     */
    fun close() {
        windowState.clearAgentSessionError()
        inspected = null
        ui.value = Ui(expandedTasks = ui.value.expandedTasks)
    }

    /** Open the overlay if it is closed, close it otherwise. */
    fun toggle() = if (state.open) close() else open()

    /**
     * Change the search text and select the first row (the longest-waiting ◆ when the query is
     * empty). Closes the peek and any confirmation, which belonged to the old selection.
     *
     * With two or more characters the overlay switches to search results; the
     * view then calls [search] once typing pauses.
     *
     * @param query what the user typed.
     */
    fun setQuery(query: String) {
        windowState.clearAgentSessionError()
        ui.update { it.copy(query = query, selectedId = null, selectedHint = 0, peekOpen = false, confirmRowId = null) }
    }

    /**
     * Ask the server to search every session for the current query. Does nothing for a
     * query under two characters (the overlay then lists as usual). The answer arrives
     * through [WindowStateRepository.agentMatches]; one for an older query is ignored.
     *
     * Called by the view about 150 ms after the last key press.
     *
     * @see setQuery
     */
    suspend fun search() {
        val query = ui.value.query.trim()
        if (query.length >= MIN_QUERY) send(WindowCommand.SearchAgentSessions(query))
    }

    /**
     * Move the highlight, stopping at the first and last row.
     *
     * @param delta `-1` for ↑, `1` for ↓.
     */
    fun moveSelection(delta: Int) {
        val current = state
        val rows = current.rows
        if (rows.isEmpty()) return
        val next = (maxOf(current.selected, 0) + delta).coerceIn(0, rows.lastIndex)
        ui.update { it.copy(selectedId = rows[next].id, selectedHint = next, peekOpen = false, confirmRowId = null) }
    }

    /**
     * Highlight the row with [rowId], as a mouse click does. Does nothing for an id that is not listed.
     *
     * Called by the view when a header is clicked, before it toggles the panel.
     *
     * @param rowId the [Row.id] to select.
     */
    fun select(rowId: String) {
        val rows = state.rows
        val index = rows.indexOfFirst { it.id == rowId }
        if (index >= 0) ui.update { it.copy(selectedId = rowId, selectedHint = index, peekOpen = false, confirmRowId = null) }
    }

    /** Reveal the second band's units beyond the first [EARLIER_LIMIT]. Called by the "+ N more" line. */
    fun showMoreEarlier() = ui.update { it.copy(showAllEarlier = true) }

    /**
     * Do the highlighted row's action: jump to a live pane, resume an ended session.
     * While a close confirmation is showing, ↵ answers it instead: it cancels while Cancel
     * is focused and closes the session once "Close session" is.
     * Called for ↵.
     */
    suspend fun activateSelected() {
        val state = state
        val confirm = state.confirm
        if (confirm != null) {
            if (confirm.cancelFocused) cancelClose() else confirmClose()
            return
        }
        state.selectedRow?.let { activate(it) }
    }

    /**
     * Ask the server about the selected session (its screen, unsaved work and what Resume
     * would do), unless that was already asked for this row in this liveness. The answer
     * arrives through [WindowStateRepository.agentInspection].
     *
     * Called whenever the overlay redraws with a different row selected, and by [peek]
     * and [requestClose], which force a fresh answer.
     *
     * @param force ask again even for the row already inspected.
     */
    suspend fun inspectSelected(force: Boolean = false) {
        val current = state
        val row = current.selectedRow ?: return
        val recordId = recordIdOf(row, current.tasks) ?: return
        val key = "$recordId/${row.isLive}"
        if (!force && inspected == key) return
        inspected = key
        send(WindowCommand.InspectAgentSession(recordId))
    }

    /**
     * Open the peek popover on the selected row and fetch its screen. Called for →
     * (with the caret at the end of the search text). On a task header it peeks the session
     * ↵ would jump to, and does nothing when no session of the task is open.
     */
    suspend fun peek() {
        val current = state
        val row = current.selectedRow ?: return
        if (recordIdOf(row, current.tasks) == null) return
        ui.update { it.copy(peekOpen = true) }
        inspectSelected(force = true)
    }

    /**
     * Fetch a fresh screen for an open live peek. Called by the view every two seconds
     * while [State.peek] is live; it stops when the peek closes.
     */
    suspend fun refreshPeek() {
        if (state.peek?.live == true) inspectSelected(force = true)
    }

    /** Close the peek popover. Called for ←, ↑, ↓ and esc while it is open. */
    fun closePeek() = ui.update { it.copy(peekOpen = false) }

    /**
     * Step back one level: cancel a close confirmation, else close the peek, else close the
     * overlay. Called for esc.
     */
    fun back() {
        val current = state
        when {
            current.confirm != null -> cancelClose()
            current.peek != null -> closePeek()
            else -> close()
        }
    }

    /**
     * Ask whether to close the selected session, showing what would be lost. Nothing is closed
     * until [confirmClose]. Does nothing for a row without an open pane.
     *
     * Called by the × on the selected row, and by ⌘⌫.
     *
     * @param fromKeyboard true for ⌘⌫, which is ignored while a search is typed so the
     *   shortcut cannot hit a row the user is not looking at; the × button passes false.
     */
    suspend fun requestClose(fromKeyboard: Boolean = false) {
        val current = state
        if (fromKeyboard && current.query.isNotEmpty()) return
        val row = current.selectedRow?.takeIf { it.canClose } ?: return
        ui.update { it.copy(confirmRowId = row.id, confirmOnClose = false, peekOpen = false) }
        inspectSelected(force = true)
    }

    /** Move the focus between Cancel and "Close session" in the confirmation. */
    fun moveConfirmFocus() = ui.update { if (it.confirmRowId != null) it.copy(confirmOnClose = !it.confirmOnClose) else it }

    /** Dismiss the close confirmation and keep the session. Called for Cancel and esc. */
    fun cancelClose() = ui.update { it.copy(confirmRowId = null, confirmOnClose = false) }

    /**
     * Close the session the confirmation asked about: ends its terminal session, which closes
     * the pane and the agent with it. The record then moves to the finished sessions and stays
     * searchable and resumable.
     */
    suspend fun confirmClose() {
        val id = ui.value.confirmRowId ?: return
        val config = windowState.config.value
        val leafId = windowState.agentSessions.value.firstOrNull { it.id == id }?.leafId
        val sessionId = if (config != null && leafId != null) locate(config, leafId)?.leaf?.sessionId else null
        ui.update { it.copy(confirmRowId = null, confirmOnClose = false) }
        if (sessionId != null) send(WindowCommand.CloseSession(sessionId))
    }

    /**
     * Do [row]'s action: jump to a live pane, resume an ended session, attach to a task member
     * Lunamux does not host, or, on a task header, go to the session that most needs the person.
     * Does nothing for a member with no session.
     *
     * Called when a row is clicked and for ↵ (through [activateSelected]).
     *
     * @param row the row to act on.
     * @see activateTask
     */
    suspend fun activate(row: Row) {
        when {
            row.kind == RowKind.TASK -> activateTask(row)
            row.unhosted -> openMember(row)
            row.isLive -> jump(row)
            row.canResume -> resume(row)
        }
    }

    /**
     * Do what ↵ on a task header does: jump to the live session that most needs the person (the ◆
     * that has waited longest, else the working session with the freshest sign of life); with no
     * open session, run the task's `open` action; with neither, expand or collapse the panel.
     *
     * Called by [activate] for a header row.
     *
     * @param row the header row; its [Row.taskId] names the task.
     * @see Task.jumpRecordId
     * @see Task.hasOpen
     */
    suspend fun activateTask(row: Row) {
        val taskId = row.taskId ?: return
        val task = state.tasks[taskId] ?: return
        when {
            task.jumpRecordId != null -> jumpToRecord(task.jumpRecordId)
            task.hasOpen -> openTask(row)
            else -> toggleTask(taskId)
        }
    }

    /**
     * Expand a collapsed task panel or collapse an expanded one, keeping the highlight on its header.
     *
     * Called by a click on a task header (outside its "open"), and by [toggleSelected] and by ↵ on a
     * header with nothing to jump to or open.
     *
     * @param taskId the task to toggle.
     */
    fun toggleTask(taskId: String) = setExpanded(taskId, taskId !in ui.value.expandedTasks)

    private fun setExpanded(taskId: String, expanded: Boolean) {
        val header = state.rows.indexOfFirst { it.kind == RowKind.TASK && it.taskId == taskId }
        ui.update {
            it.copy(
                expandedTasks = if (expanded) it.expandedTasks + taskId else it.expandedTasks - taskId,
                selectedId = if (header >= 0) "$TASK_PREFIX$taskId" else it.selectedId,
                selectedHint = if (header >= 0) header else it.selectedHint,
                peekOpen = false,
                confirmRowId = null,
            )
        }
    }

    /**
     * Step into the selected row: on a task header expand it, or, when it already is expanded,
     * select its first session; on a session peek at its screen.
     *
     * Called for → (with the caret at the end of the search text).
     */
    suspend fun stepIn() {
        val current = state
        val row = current.selectedRow ?: return
        if (row.kind != RowKind.TASK) return peek()
        val taskId = row.taskId ?: return
        if (current.tasks[taskId]?.expanded != true) return setExpanded(taskId, true)
        val index = current.rows.indexOfFirst { it.id == row.id }
        current.rows.getOrNull(index + 1)?.takeIf { it.taskId == taskId && it.kind == RowKind.SESSION }?.let { select(it.id) }
    }

    /**
     * Step out of the selected row: close an open peek; otherwise on a task's session select its
     * header, and on an expanded header collapse it.
     *
     * Called for ← (with the caret at the start of the search text).
     */
    fun stepOut() {
        val current = state
        val row = current.selectedRow ?: return
        when {
            current.peek != null -> closePeek()
            row.kind == RowKind.SESSION && row.taskId != null && row.taskId in current.tasks -> select("$TASK_PREFIX${row.taskId}")
            row.kind == RowKind.TASK && current.tasks[row.taskId]?.expanded == true -> setExpanded(row.taskId!!, false)
        }
    }

    /**
     * Toggle what the selected row holds: a task header's sessions, or a session's peek. Does
     * nothing while a search is typed, because a space then belongs to the query.
     *
     * Called for space.
     */
    suspend fun toggleSelected() {
        val current = state
        if (current.query.isNotEmpty()) return
        val row = current.selectedRow ?: return
        when {
            row.kind == RowKind.TASK -> row.taskId?.let(::toggleTask)
            current.peek != null -> closePeek()
            else -> peek()
        }
    }

    /**
     * Run a task's `open` action: ask the server to open a tab in the folder the task file names
     * and start the session there. The overlay stays open until the new tab is active or the
     * server says why it could not, exactly as for [resume].
     *
     * Called by [activateTask] when no session of the task is open, and for a click on the
     * header's `open` control, which the header offers whenever the task has an `open` action,
     * also while a session is live (↵ then jumps to it).
     *
     * @param row the header row; its [Row.taskId] names the task.
     */
    suspend fun openTask(row: Row) {
        val taskId = row.taskId ?: return
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.OpenTask(taskId))
    }

    /**
     * Open a task member Lunamux does not host in a new tab with `claude attach`. Only the task and
     * the member's key go to the server, which finds the session id in the task file and builds the
     * command. The overlay stays open until the new tab is active or the server says why it
     * could not. Does nothing for a member whose file gave no session id.
     *
     * Called by [activate] for ↵ or a click on such a member.
     *
     * @param row the member; its [Row.taskId] and [Row.memberKey] name what to attach to.
     */
    suspend fun openMember(row: Row) {
        val taskId = row.taskId ?: return
        val memberKey = row.memberKey ?: return
        if (row.sessionId == null) return
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.OpenTaskMember(taskId, memberKey))
    }

    /**
     * Switch to the pane the row's agent runs in and focus it.
     *
     * Uses the existing world, tab and focus commands, so the layout
     * changes exactly as if the user had clicked there. Does nothing when the
     * pane is not in the layout.
     *
     * @param row a live row.
     */
    suspend fun jump(row: Row) = jumpToRecord(row.id)

    private suspend fun jumpToRecord(recordId: String) {
        val config = windowState.config.value ?: return
        val leafId = windowState.agentSessions.value.firstOrNull { it.id == recordId }?.leafId ?: return
        val where = locate(config, leafId) ?: return
        if (where.worldId != null && where.worldId != config.activeWorldId) {
            send(WindowCommand.SetActiveWorld(where.worldId))
        }
        send(WindowCommand.SetActiveTab(where.tabId))
        send(WindowCommand.SetFocusedPane(where.tabId, leafId))
        close()
    }

    /**
     * Ask the server to open a new tab in the row's folder with the agent's
     * resume command. The overlay stays open until the new tab becomes active
     * or the server reports why it could not.
     *
     * @param row an ended row.
     */
    suspend fun resume(row: Row) {
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.ResumeAgentSession(row.id))
    }

    /**
     * One session of a task, paired with the session summary it matched (null when Lunamux does not
     * host it).
     *
     * @property member what the task says about the session: the file's member, or one made from
     *   the session itself.
     * @property session the hosted session behind it, or null.
     * @property order its place among the task's sessions: the file's order, then hosted-only sessions.
     * @property fromFile whether a task file lists it, so the server can open it by [AgentTaskMember.key].
     */
    private class Run(val member: AgentTaskMember, val session: AgentSessionSummary?, val order: Int, val fromFile: Boolean) {
        /**
         * The task file's refresh time when this member is one Lunamux does not host (the row's
         * [Row.fileRefreshedMillis]), else null.
         *
         * @param refreshed epoch millis the declaring task file was last refreshed, or null.
         */
        fun fileRefreshed(refreshed: Long?): Long? = refreshed.takeIf { session == null }
    }

    /**
     * Whether [run] is working and really moving (neither quiet nor stale), by the same rule the
     * row's "quiet 6m" prefix follows.
     *
     * Called by [build] for the chip's count.
     *
     * @param run the task member.
     * @param refreshed epoch millis the task file was last refreshed, or null.
     * @param nowMillis the current time.
     * @return true when it is counted as working.
     * @see isMoving
     */
    private fun runMoving(run: Run, refreshed: Long?, nowMillis: Long): Boolean =
        isMoving(run.member.state == WORKING, run.fileRefreshed(refreshed), workingActivity(run, refreshed), nowMillis)

    /**
     * A task with its sessions, ready to become rows.
     *
     * @property task the task as the server sent it.
     * @property runs its sessions.
     * @property panel whether it is drawn as an expansion panel; false means its one session is a plain row.
     * @property rank 0 when a session needs the person, 1 when one works, [GRACE_RANK] for a task of
     *   hosted sessions that went quiet less than [IDLE_GRACE_MS] ago, else [OPEN_WORK_RANK].
     * @property since the time that decides its place among tasks of the same rank.
     * @property ended whether every session has ended.
     * @property settled whether no session needs the person or works and either every session reads
     *   done (a task file's word, or sessions that ended), or no task file speaks for the task and the
     *   last activity of its sessions is older than [IDLE_GRACE_MS]: it belongs in EARLIER like an
     *   idle single session, though none of its sessions has ended.
     * @property recency epoch millis of the newest activity of any session of the task: when an ended
     *   one ended, the last activity of an open one, and the time a task file gives a member. Decides
     *   the EARLIER band and the order inside it.
     * @property jumpRecordId the open session that most needs the person, or null.
     */
    private class TaskUnit(
        val task: AgentTask,
        val runs: List<Run>,
        val panel: Boolean,
        val rank: Int,
        val since: Long,
        val ended: Boolean,
        val settled: Boolean,
        val recency: Long,
        val jumpRecordId: String?,
    )

    /** How a session is counted and ordered: the bucket a header count and the expanded order use. */
    private class Bucket(val order: Int, val name: String)

    private fun build(input: Inputs): State {
        val (sessions, taskList, config, error, matches, inspection, ui) = input
        val nowMillis = clock()
        val panes = config?.let(::paneLocations).orEmpty()
        val query = ui.query.trim()
        val matchMode = query.length >= MIN_QUERY
        val resumeError = { id: String -> error.takeIf { id == ui.resumingId } }
        val byId = sessions.associateBy { it.id }
        fun inspectionOf(id: String) = inspection?.takeIf { it.recordId == id }

        val answer = matches?.takeIf { matchMode && it.query == query }
        val matchRows = answer?.matches.orEmpty().map { m ->
            val s = byId[m.summary.id] ?: m.summary
            val row = toRow(s, panes[s.leafId], resumeError(s.id))
            // A live title can have changed since the server found the match in it.
            val highlight = m.field != "title" || row.title == (m.summary.title ?: row.title)
            row.copy(
                field = m.field,
                snippet = m.snippet,
                matchStart = if (highlight) m.start else 0,
                matchLength = if (highlight) m.length else 0,
                resumeNote = m.resumeNote,
                taskTitle = m.taskTitle,
            )
        }

        val sessionsByTask = sessions.filter { it.taskId != null }.groupBy { it.taskId!! }
        val units = taskList.mapNotNull { t -> unitOf(t, byId, sessionsByTask[t.id].orEmpty(), panes, nowMillis) }
        val taskStates = units.filter { it.panel }.associate { it.task.id to taskOf(it, ui, nowMillis) }
        // A session a task unit holds is drawn inside it, not again as a single.
        val claimed = units.flatMap { u -> u.runs.mapNotNull { it.session?.id } }.toSet()
        // A programme's manager episode is the programme's machinery, not the person's session: it
        // is listed (and counted) only while it needs the person, so the list shows their work.
        val singles = sessions.filter { it.id !in claimed && (it.state == NEEDS_YOU || !AgentSessionText.isProgrammeMachinery(it.firstPrompt)) }
            .map { s -> toRow(s, panes[s.leafId], resumeError(s.id)) }

        // A task stays in NOW while it has open work and leaves once it is settled (see TaskUnit);
        // it is ranked beside the single sessions that need the user or work, by its most urgent session.
        class Placed(val rank: Int, val since: Long, val rows: List<Row>)
        // An idle session stays in NOW for a while after its last activity, so a chat that finishes
        // a turn does not jump to EARLIER and back as the next turn starts.
        val graced = singles.filter { row ->
            row.state == IDLE && byId[row.id]?.let { inGrace(it.lastActivityAt ?: it.stateSince, nowMillis) } == true
        }
        val urgent = buildList {
            singles.filter { it.state == NEEDS_YOU || it.state == WORKING }
                .forEach { add(Placed(rankOf(it.state), it.sinceMillis, listOf(it))) }
            graced.forEach { row ->
                val s = byId.getValue(row.id)
                add(Placed(GRACE_RANK, s.lastActivityAt ?: s.stateSince, listOf(row)))
            }
            units.filter { !it.ended && !it.settled }
                .forEach { add(Placed(it.rank, it.since, taskRows(it, taskStates, panes, ui, resumeError))) }
        }.sortedWith(
            compareBy<Placed> { it.rank }.thenBy { if (it.rank >= OPEN_WORK_RANK) -it.since else it.since },
        )
        val now = urgent.flatMap { it.rows }

        // EARLIER is built from units, a task or a lone session, each placed by its newest activity:
        // within RECENT_MS it is in the first band, up to WINDOW_MS in the second, older not listed.
        class Earlier(val recency: Long, val rows: List<Row>)
        val gracedIds = graced.mapTo(HashSet()) { it.id }
        val earlierUnits = (
            singles.filter { it.state != NEEDS_YOU && it.state != WORKING && it.id !in gracedIds }
                // The row's age is the very time its band is chosen by, so a row never reads "now"
                // under a heading that says days.
                .map { row ->
                    val at = byId[row.id]?.let(::activityOf) ?: row.sinceMillis
                    Earlier(at, listOf(row.copy(sinceMillis = at)))
                } +
                units.filter { it.ended || it.settled }
                    .map { Earlier(it.recency, taskRows(it, taskStates, panes, ui, resumeError)) }
            ).filter { inWindow(it.recency, nowMillis) }
            .sortedByDescending { it.recency }
        val recentUnits = earlierUnits.filter { inRecent(it.recency, nowMillis) }
        val fortnightUnits = earlierUnits.filter { !inRecent(it.recency, nowMillis) }
        val shownFortnight = if (ui.showAllEarlier) fortnightUnits else fortnightUnits.take(EARLIER_LIMIT)
        val recent = recentUnits.flatMap { it.rows }
        val fortnight = shownFortnight.flatMap { it.rows }

        val listed = if (matchMode) matchRows else now + recent + fortnight
        // The selection is the row with the remembered id; when that row is gone, the row now in its place.
        val selectedId = when {
            listed.isEmpty() -> null
            ui.selectedId != null && listed.any { it.id == ui.selectedId } -> ui.selectedId
            ui.selectedId != null -> listed[ui.selectedHint.coerceIn(0, listed.lastIndex)].id
            matchMode -> listed.first().id
            else -> oldestNeedsYou(listed)?.id ?: listed.first().id
        }

        // The selected row of a plain list also tells what Resume will do, from the inspection.
        val shown = if (matchMode || selectedId == null) listed else listed.map { row ->
            if (row.id == selectedId) row.copy(resumeNote = recordIdOf(row, taskStates)?.let(::inspectionOf)?.resumeNote) else row
        }
        val selectedRow = shown.firstOrNull { it.id == selectedId }

        val peek = if (ui.peekOpen && selectedRow != null) {
            val said = recordIdOf(selectedRow, taskStates)?.let(::inspectionOf)?.takeIf { it.live == selectedRow.isLive }
            Peek(
                live = selectedRow.isLive,
                title = selectedRow.title,
                closedSinceMillis = if (selectedRow.isLive) null else selectedRow.sinceMillis,
                lines = said?.screen.orEmpty(),
                loading = said == null,
                emptyText = if (said != null && said.screen.isEmpty()) {
                    if (selectedRow.isLive) "(the screen is empty)" else "(no saved screen)"
                } else null,
            )
        } else null

        val confirmRow = ui.confirmRowId?.let { id -> shown.firstOrNull { it.id == id } }
        val confirm = confirmRow?.let {
            Confirm(
                rowId = it.id,
                title = it.title,
                warnings = closeWarnings(it, inspectionOf(it.id)),
                cancelFocused = !ui.confirmOnClose,
            )
        }

        // Once a resume has switched to the new tab the overlay has done its job;
        // settleResume then resets the inputs so it stays closed.
        val resumed = ui.resumingId != null && config?.effectiveActiveTabId != ui.resumeFromTab
        val taskNeeds = units.sumOf { u -> u.runs.count { it.member.state == NEEDS_YOU } }
        val taskWorking = units.sumOf { u -> u.runs.count { runMoving(it, u.task.refreshedAtMillis, nowMillis) } }
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            matchMode = matchMode,
            matches = if (matchMode) shown else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else shown.take(now.size),
            recent = if (matchMode) emptyList() else shown.drop(now.size).take(recent.size),
            fortnight = if (matchMode) emptyList() else shown.drop(now.size + recent.size),
            earlierHidden = fortnightUnits.size - shownFortnight.size,
            needYouCount = singles.count { it.state == NEEDS_YOU } + taskNeeds,
            workingCount = singles.count { isMoving(it.state == WORKING, it.fileRefreshedMillis, it.activityMillis, nowMillis) } + taskWorking,
            selectedId = selectedId,
            peek = peek,
            confirm = confirm,
            tasks = if (matchMode) emptyMap() else taskStates,
            hasSessions = sessions.isNotEmpty() || taskList.any { it.members.isNotEmpty() },
            footer = footerOf(selectedRow, taskStates),
        )
    }

    /** The ◆ row that has waited longest, or null when no listed row needs the user. */
    private fun oldestNeedsYou(rows: List<Row>): Row? =
        rows.filter { it.glyph == Glyph.NEEDS_YOU }.minByOrNull { it.sinceMillis }

    /** Which key hints go with [row]. */
    private fun footerOf(row: Row?, tasks: Map<String, Task>): Footer = when {
        row == null -> Footer.NONE
        row.kind == RowKind.TASK -> if (tasks[row.taskId]?.expanded == true) Footer.TASK_EXPANDED else Footer.TASK_COLLAPSED
        row.unhosted -> if (row.sessionId != null) Footer.RUN_ATTACH else Footer.NO_SESSION
        row.isLive -> Footer.LIVE
        else -> Footer.CLOSED
    }

    /**
     * Pair a task's sessions with the hosted sessions they match and work out where the task sits.
     *
     * The task's sessions are the members its file lists plus every hosted session that carries
     * its id and no member of the file claimed. A task with one hosted session and no `open`
     * action has no unit: that session stays a plain row of its own. A task with one session the
     * file lists and no `open` action is a unit drawn as that session's row; any other task with
     * sessions or an `open` action is a panel.
     *
     * @param t the task.
     * @param byId all session summaries by record id.
     * @param hosted the hosted sessions whose [AgentSessionSummary.taskId] is [t]'s id.
     * @param panes where the layout holds each pane, to tell which sessions are open.
     * @param nowMillis the current time, which decides whether a task of idle hosted sessions is
     *   still inside the grace a single idle session gets.
     * @return the unit, or null when the task is not drawn as anything but its one session.
     */
    private fun unitOf(
        t: AgentTask,
        byId: Map<String, AgentSessionSummary>,
        hosted: List<AgentSessionSummary>,
        panes: Map<String?, PaneLocation>,
        nowMillis: Long,
    ): TaskUnit? {
        val fileRuns = t.members.mapIndexed { i, m -> Run(m, m.recordId?.let(byId::get), i, true) }
        val matched = fileRuns.mapNotNullTo(HashSet()) { it.session?.id }
        val extraRuns = hosted.filter { it.id !in matched }
            .mapIndexed { i, s -> Run(memberOf(s), s, fileRuns.size + i, false) }
        val runs = fileRuns + extraRuns
        if (runs.isEmpty() && !t.hasOpen) return null
        if (runs.size == 1 && !t.hasOpen && !runs[0].fromFile) return null
        val panel = runs.size >= 2 || t.hasOpen
        val ended = runs.all { it.member.state == ENDED }
        val urgentRank = when {
            runs.any { it.member.state == NEEDS_YOU } -> 0
            runs.any { it.member.state == WORKING } -> 1
            else -> OPEN_WORK_RANK
        }
        // A task file speaks for its task, so the task stays in NOW while it has work to do, even
        // while nothing moves: a member in review or queued is still open work. Once every member
        // reads done, nothing is moving or waiting and the file has said so itself, so the task goes
        // to EARLIER at once, as a finished task does. A task of hosted sessions only has no such
        // word: like one idle session, it stays for the grace after the last activity and then moves.
        val lastActivity = runs.mapNotNull { r -> r.session?.let { it.lastActivityAt ?: it.stateSince } }.maxOrNull()
        val hostedOnly = t.source != "file"
        val quiet = hostedOnly && urgentRank == OPEN_WORK_RANK && !runs.all { it.member.state == ENDED }
        val allDone = runs.isNotEmpty() && runs.all { bucketOf(it.member).order == DONE_ORDER }
        val settled = allDone || (quiet && (lastActivity == null || !inGrace(lastActivity, nowMillis)))
        val rank = if (quiet && !settled) GRACE_RANK else urgentRank
        val since = when {
            runs.isEmpty() -> t.refreshedAtMillis ?: 0L
            quiet -> lastActivity ?: runs.maxOf { it.member.sinceMillis }
            urgentRank == OPEN_WORK_RANK -> runs.maxOf { it.member.sinceMillis }
            else -> runs.filter { rankOf(it.member.state) == urgentRank }.minOf { it.member.sinceMillis }
        }
        // Open means a session Lunamux lists whose pane is in the layout, so Jump has somewhere to go.
        val open = runs.filter { r -> r.session != null && r.session.state != ENDED && panes[r.session.leafId] != null }
        val jump = open.filter { it.member.state == NEEDS_YOU }.minByOrNull { it.member.sinceMillis }
            ?: open.filter { it.member.state == WORKING }.maxByOrNull { it.session!!.lastActivityAt ?: it.session.stateSince }
        val recency = runs.maxOfOrNull { r -> maxOf(r.member.sinceMillis, r.session?.let(::activityOf) ?: 0L) }
            ?: t.refreshedAtMillis ?: 0L
        return TaskUnit(t, runs, panel, rank, since, ended, settled, recency, jump?.session?.id)
    }

    /** The record behind [row] that peek and inspection ask the server about, or null when there is none. */
    private fun recordIdOf(row: Row, tasks: Map<String, Task>): String? = when {
        row.kind == RowKind.TASK -> tasks[row.taskId]?.jumpRecordId
        row.kind == RowKind.SESSION && !row.unhosted -> row.id
        else -> null
    }

    private fun rankOf(state: String) = when (state) {
        NEEDS_YOU -> 0
        WORKING -> 1
        else -> OPEN_WORK_RANK
    }

    /** Which count and which place in the expanded order [m] has, from its state and the file's label. */
    private fun bucketOf(m: AgentTaskMember): Bucket {
        if (m.state == NEEDS_YOU) return Bucket(0, "needs you")
        if (m.state == WORKING) return Bucket(1, "working")
        if (m.state == ENDED) return Bucket(DONE_ORDER, "done")
        val label = m.label?.trim().orEmpty()
        val lower = label.lowercase()
        return when {
            lower.startsWith("in review") -> Bucket(2, "in review")
            lower == "queued" || lower == "waiting" -> Bucket(3, "queued")
            lower.startsWith("paused") -> Bucket(5, "paused")
            lower == "done" || lower == "ready" -> Bucket(DONE_ORDER, "done")
            label.isEmpty() -> Bucket(4, "idle")
            else -> Bucket(4, label)
        }
    }

    /** The header the view draws for [u]. */
    private fun taskOf(u: TaskUnit, ui: Ui, nowMillis: Long): Task {
        val t = u.task
        val repo = headerRepo(u)
        // A working session that is quiet or stale is counted under that word, as its row says it, not as working.
        val buckets = u.runs.map { run ->
            val b = bucketOf(run.member)
            if (b.order != 1) b else {
                val word = agePrefixOf(true, run.fileRefreshed(t.refreshedAtMillis), workingActivity(run, t.refreshedAtMillis), nowMillis)
                    .substringBefore(' ').ifEmpty { b.name }
                Bucket(b.order, word)
            }
        }
        // Grouped by order and word, so a file label that happens to be called "quiet" never merges into the working group
        // of that word; within the working order, "working" comes first, then "quiet", then "stale", while other labels
        // keep the order their members come in.
        val counted = buckets.filter { it.order > 0 }.groupBy { it.order to it.name }
            .entries.sortedWith(compareBy({ (key, _) -> key.first }, { (key, _) -> if (key.second == "working") 0 else 1 }, { (key, _) -> if (key.first == 1) key.second else "" }))
        // In EARLIER a header says how many sessions it holds and how long ago it was last active.
        val inEarlier = u.ended || u.settled
        return Task(
            id = t.id, title = t.title, repo = repo,
            needYouCount = u.runs.count { it.member.state == NEEDS_YOU },
            workingCount = u.runs.count { it.member.state == WORKING },
            summary = if (inEarlier) {
                when (u.runs.size) {
                    0 -> "no sessions"
                    1 -> "1 session"
                    else -> "${u.runs.size} sessions"
                }
            } else {
                counted.joinToString(" · ") { (key, list) -> "${list.size} ${key.second}" }
                    .ifEmpty { if (u.runs.isEmpty()) "no sessions" else "" }
            },
            expanded = t.id in ui.expandedTasks,
            jumpRecordId = u.jumpRecordId,
            hasOpen = t.hasOpen,
            activityMillis = u.runs.filter { it.member.state == WORKING }
                .mapNotNull { workingActivity(it, t.refreshedAtMillis) }.maxOrNull(),
            ageMillis = u.recency.takeIf { inEarlier },
        )
    }

    /**
     * The newest sign of life of a hosted session: when it ended, else its last activity, else the
     * time it entered its state. This is what places a lone session in an EARLIER band.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun activityOf(s: AgentSessionSummary): Long =
        if (s.state == ENDED) s.endedAt ?: s.stateSince else s.lastActivityAt ?: s.stateSince

    /**
     * The repository a task header shows: the one its file names, else the repository every one of
     * its hosted sessions runs in. A task the agents made has no file, so without this its header's
     * repository column would be empty even when all its sessions share one.
     *
     * @param u the task unit.
     * @return the repository folder name, or null when the sessions do not share one.
     */
    private fun headerRepo(u: TaskUnit): String? {
        u.task.repo?.let { return it }
        val hosted = u.runs.mapNotNull { it.session }
        if (hosted.isEmpty() || hosted.any { it.repo == null }) return null
        return hosted.map { it.repo }.distinct().singleOrNull()
    }

    /**
     * The last sign of life of a working session: the newer of its hosted session's last activity
     * and the time the task file declaring it was last refreshed. A member outside Lunamux has only
     * the file; a file that is still being refreshed keeps its working members live.
     */
    private fun workingActivity(run: Run, refreshed: Long?): Long? {
        val hosted = run.session?.let { it.lastActivityAt ?: it.stateSince }
        return listOfNotNull(hosted, refreshed).maxOrNull()
    }

    /**
     * A task member made from a hosted session no task file lists: its own title, state, question
     * or last tool line and age.
     *
     * @param s the hosted session.
     * @return the member, keyed by the record id.
     */
    private fun memberOf(s: AgentSessionSummary) = AgentTaskMember(
        key = s.id,
        title = titleOf(s),
        links = s.links,
        state = s.state,
        line = if (s.state == NEEDS_YOU) s.question ?: s.nowLine else s.nowLine,
        sinceMillis = s.stateSince,
        recordId = s.id,
    )

    /**
     * The rows of one task unit. A plain unit is its one session's row. A panel is its header,
     * then, while collapsed, the sessions that need the user (longest waiting first) and those
     * that work (most recent first), or, once expanded, every session in the order needs you,
     * working, in review, queued, other labels, paused, done; the file's order within a bucket.
     */
    private fun taskRows(
        u: TaskUnit,
        tasks: Map<String, Task>,
        panes: Map<String?, PaneLocation>,
        ui: Ui,
        resumeError: (String) -> String?,
    ): List<Row> {
        val t = u.task
        val rows = u.runs.map { run -> run to runRow(t, run, panes, resumeError) }
        if (!u.panel) return rows.map { it.second }
        val header = tasks.getValue(t.id).let { task ->
            Row(
                id = "$TASK_PREFIX${t.id}", agent = "", state = stateOfRank(u.rank), title = t.title,
                links = emptyList(), repo = headerRepo(u), nowLine = null, question = null,
                sinceMillis = task.ageMillis ?: u.since,
                isLive = task.jumpRecordId != null, canResume = false,
                actionLabel = when {
                    task.jumpRecordId != null -> "jump"
                    task.hasOpen -> "open"
                    else -> ""
                },
                error = resumeError("$TASK_PREFIX${t.id}"), kind = RowKind.TASK, taskId = t.id,
                glyph = Glyph.NONE,
                status = "",
            )
        }
        val shown = if (t.id in ui.expandedTasks) {
            rows.sortedWith(
                compareBy<Pair<Run, Row>> { bucketOf(it.first.member).order }
                    .thenBy { if (it.first.member.state == NEEDS_YOU) it.first.member.sinceMillis else 0L },
            )
        } else {
            rows.filter { it.first.member.state == NEEDS_YOU }.sortedBy { it.first.member.sinceMillis } +
                rows.filter { it.first.member.state == WORKING }.sortedByDescending { it.first.member.sinceMillis }
        }
        return listOf(header) + shown.map { it.second }
    }

    private fun stateOfRank(rank: Int) = when (rank) {
        0 -> NEEDS_YOU
        1 -> WORKING
        else -> IDLE
    }

    /** One session of [t] as a row: the task's state and wording, over the hosted session's pane when there is one. */
    private fun runRow(t: AgentTask, run: Run, panes: Map<String?, PaneLocation>, resumeError: (String) -> String?): Row {
        val m = run.member
        val s = run.session
        // A member that has no session record is identified by its key, which the server makes unique
        // within the task, so a writer that inserts or reorders members does not move the selection.
        val rowId = if (s != null) s.id else "$MEMBER_PREFIX${t.id}:${m.key}"
        val base = if (s != null) {
            toRow(s, panes[s.leafId], resumeError(s.id))
        } else {
            Row(
                id = rowId, agent = "", state = m.state, title = m.title,
                links = m.links, repo = null, nowLine = null, question = null, sinceMillis = m.sinceMillis,
                isLive = false, canResume = false, actionLabel = "",
                error = resumeError(rowId), unhosted = true,
                fileRefreshedMillis = t.refreshedAtMillis, sessionId = m.sessionId,
            )
        }
        val bucket = bucketOf(m)
        val pr = m.links.firstOrNull { PR_LINK.matches(it) }
        val line = m.line?.trim()?.takeIf { it.isNotEmpty() }
        val firstPrompt = AgentSessionText.previewOf(s?.firstPrompt)?.let { "❯ $it" }
        val said = AgentSessionText.previewOf(s?.lastSaid)
        val hostedLine = s?.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        val status = when (m.state) {
            NEEDS_YOU -> line ?: s?.question ?: "waiting at a prompt"
            WORKING -> line ?: hostedLine ?: firstPrompt ?: if (s == null && m.label != null) "" else "—"
            // A finished member's line that only repeats its pull request is dropped: the PR has its own column.
            else -> (line?.takeIf { it != pr } ?: said ?: firstPrompt)
                ?: if (bucket.order == DONE_ORDER || m.label != null) "" else "—"
        }
        return base.copy(
            title = m.title,
            state = m.state,
            // Only the keys the task gives; the ones read from a session's branch are not the feed's.
            links = m.links,
            actionLabel = when {
                base.isLive -> "jump"
                s != null -> "resume"
                // Attaching opens the session in a new tab, which is what "resume" says everywhere else.
                m.sessionId != null -> "resume"
                else -> ""
            },
            sinceMillis = m.sinceMillis,
            nowLine = if (m.state == NEEDS_YOU) null else line,
            question = if (m.state == NEEDS_YOU) line else null,
            taskId = t.id,
            memberKey = m.key.takeIf { run.fromFile },
            // The file's word ("coding") goes with a state only the file vouches for: a hosted
            // member's live state wins over what the file said, so its label is dropped while it works.
            label = m.label?.trim()?.takeIf { it.isNotEmpty() && (m.state == IDLE || m.state == ENDED || (m.state == WORKING && s == null)) },
            activityMillis = if (m.state == WORKING) workingActivity(run, t.refreshedAtMillis) else null,
            glyph = when (bucket.order) {
                0 -> Glyph.NEEDS_YOU
                1 -> Glyph.WORKING
                5 -> Glyph.PAUSED
                6 -> Glyph.DONE
                else -> Glyph.WAITING
            },
            status = status,
            pullRequest = pr,
        )
    }

    /** What closing [row] would interrupt or leave behind, one sentence each. */
    private fun closeWarnings(row: Row, inspection: WindowEnvelope.AgentSessionInspection?): List<String> {
        val out = mutableListOf<String>()
        if (row.state == WORKING) {
            out += "It is working right now" + (row.nowLine?.let { ": $it" } ?: ".")
        }
        if (row.state == NEEDS_YOU) {
            out += "It is waiting for you" + ((row.question ?: row.nowLine)?.let { ": $it" } ?: ".")
        }
        val uncommitted = inspection?.uncommitted ?: 0
        val unpushed = inspection?.unpushed ?: 0
        if (uncommitted > 0 || unpushed > 0) {
            val parts = buildList {
                if (uncommitted > 0) add("$uncommitted uncommitted ${if (uncommitted == 1) "change" else "changes"}")
                if (unpushed > 0) add("$unpushed unpushed ${if (unpushed == 1) "commit" else "commits"}")
            }
            val branch = inspection?.branch?.let { " on $it" }.orEmpty()
            out += parts.joinToString(" · ") + branch + " stay in its folder."
        }
        return out
    }

    private fun toRow(s: AgentSessionSummary, where: PaneLocation?, error: String?): Row {
        val live = s.state != ENDED && where != null
        val title = titleOf(s)
        val ended = s.state == ENDED
        return Row(
            id = s.id,
            agent = s.agent,
            state = s.state,
            title = title,
            links = s.links,
            repo = s.repo,
            nowLine = s.nowLine?.takeIf { it.isNotBlank() },
            question = AgentSessionText.previewOf(s.question),
            sinceMillis = if (ended) s.endedAt ?: s.stateSince else s.stateSince,
            isLive = live,
            canResume = !live,
            activityMillis = if (s.state == WORKING) s.lastActivityAt ?: s.stateSince else null,
            actionLabel = when {
                live -> "jump"
                s.agent == GEMINI -> "open folder"
                else -> "resume"
            },
            error = error,
            canClose = live,
            glyph = when {
                s.state == NEEDS_YOU -> Glyph.NEEDS_YOU
                s.state == WORKING -> Glyph.WORKING
                live -> Glyph.WAITING
                else -> Glyph.CLOSED
            },
            status = statusOf(s),
            where = s.repo ?: folderOf(s),
        )
    }

    /**
     * A session's display title: what the work is, never where it ran.
     *
     * A manager episode is named [AgentSessionText.MANAGER_TITLE]. Otherwise the session's own
     * title when it names the work, else the first words of its first prompt, else the agent's
     * name. The branch, the folder, `~`, `HEAD` and run ids are not titles, so the old fallbacks
     * to them are gone.
     *
     * Called by [toRow] and [memberOf] for every session row, in the list, in a task and in a search.
     *
     * @param s the session.
     * @return the title, never blank.
     * @see AgentSessionText.isMeaningfulTitle
     * @see AgentSessionText.titleFromPrompt
     */
    private fun titleOf(s: AgentSessionSummary): String {
        if (AgentSessionText.isProgrammeMachinery(s.firstPrompt)) return AgentSessionText.MANAGER_TITLE
        s.title?.trim()?.takeIf { AgentSessionText.isMeaningfulTitle(it, s.branch, s.repo, folderOf(s)) }?.let { return it }
        return AgentSessionText.titleFromPrompt(s.firstPrompt)
            ?: "${s.agent.replaceFirstChar { it.uppercase() }} session"
    }

    /**
     * The folder a session runs in, as the overview's folder column shows it when there is no
     * repository: "~" for the user's home folder, else the last path segment, else null.
     *
     * Called by [toRow] for the column and the title fallback. Home is named "~" so a chat started there is not shown under the user's name.
     *
     * @param s the session.
     * @return the label, or null when the session has no folder.
     */
    private fun folderOf(s: AgentSessionSummary): String? =
        if (s.cwdHome) "~"
        else s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /**
     * A session's status line as plain text: its question, last tool line or last words, else its
     * first prompt, else a dash. Markdown is stripped and a line that is only a code fence is
     * skipped, so a reply that opened with a fence still reads as its first real line.
     *
     * Called by [toRow].
     *
     * @param s the session.
     * @return the line for column E, never blank.
     * @see AgentSessionText.previewOf
     */
    private fun statusOf(s: AgentSessionSummary): String {
        val firstPrompt = AgentSessionText.previewOf(s.firstPrompt)?.let { "❯ $it" }
        return when (s.state) {
            NEEDS_YOU -> AgentSessionText.previewOf(s.question) ?: s.nowLine?.takeIf { it.isNotBlank() } ?: "waiting at a prompt"
            WORKING -> s.nowLine?.takeIf { it.isNotBlank() } ?: firstPrompt ?: "—"
            else -> AgentSessionText.previewOf(s.lastSaid) ?: firstPrompt ?: "—"
        }
    }

    private fun paneLocations(config: WindowConfig): Map<String?, PaneLocation> {
        val out = HashMap<String?, PaneLocation>()
        fun add(worldId: String?, tabs: List<TabConfig>) =
            tabs.forEach { tab -> tab.panes.forEach { out[it.leaf.id] = PaneLocation(worldId, tab.id, it.leaf) } }
        if (config.worlds.isEmpty()) add(null, config.tabs) else config.worlds.forEach { add(it.id, it.tabs) }
        return out
    }

    private fun locate(config: WindowConfig, leafId: String): PaneLocation? = paneLocations(config)[leafId]

    companion object {
        /**
         * The key hints the footer shows for [footer]: only keys that do something on the selected
         * row, with "← back to the task" added for a session inside a task panel, and "↑↓ move" and
         * "esc close" always last. Each key appears once: a task header folds with → (or ←) and
         * space, and the arrow alone is named.
         *
         * Called by the web view when it draws the footer; kept here so every client says the same
         * thing and a test can hold the wording.
         *
         * @param footer which set of keys applies to the selection.
         * @param backToTask whether the selected row is a session inside a task panel.
         * @return the hints, in the order they are drawn.
         */
        fun footerHints(footer: Footer, backToTask: Boolean): List<Hint> {
            val move = Hint("↑↓", "move", HintRole.MOVE)
            val esc = Hint("esc", "close")
            val back = Hint("←", "back to the task", HintRole.BACK).takeIf { backToTask }
            return when (footer) {
                Footer.LIVE -> listOfNotNull(Hint("→", "peek"), Hint("⌘⌫", "close session"), back, move, esc)
                Footer.CLOSED -> listOfNotNull(Hint("→", "peek at its saved screen"), back, move, esc)
                Footer.TASK_COLLAPSED -> listOf(Hint("→", "expand"), move, esc)
                Footer.TASK_EXPANDED -> listOf(Hint("←", "collapse"), move, esc)
                Footer.RUN_ATTACH -> listOfNotNull(back, move, esc)
                Footer.NO_SESSION -> listOfNotNull(Hint("", "nothing to open: this session is not in a Lunamux pane"), back, move, esc)
                Footer.NONE -> listOf(move, esc)
            }
        }

        /**
         * How long an idle session stays in NOW after its last activity before it moves to
         * EARLIER. It keeps "going on now" from swapping with "earlier" every time a chat ends
         * a turn.
         */
        const val IDLE_GRACE_MS = 10L * 60_000L

        /**
         * Whether a session idle since [activityMillis] is still inside [IDLE_GRACE_MS].
         * The grace ends exactly [IDLE_GRACE_MS] after the activity.
         *
         * Called by [build] for every idle single session on each state build.
         *
         * @param activityMillis epoch millis of the session's last activity.
         * @param nowMillis the current epoch millis.
         * @return true while less than [IDLE_GRACE_MS] has passed.
         */
        fun inGrace(activityMillis: Long, nowMillis: Long): Boolean = nowMillis - activityMillis < IDLE_GRACE_MS

        /** How many units of EARLIER's second band show before "More". */
        const val EARLIER_LIMIT = 5

        /** How old a unit's newest activity may be and still sit in EARLIER's first band, "Last few days": 3 days. */
        const val RECENT_MS = 3L * 24 * 60 * 60_000L

        /**
         * How old a unit's newest activity may be and still be listed in EARLIER at all, in the
         * second band, "Last two weeks": 14 days. The server keeps at least this much history, so
         * a unit listed here is never missing from the pushed list.
         */
        const val WINDOW_MS = 14L * 24 * 60 * 60_000L

        /**
         * Whether activity at [recencyMillis] belongs to the first EARLIER band. The edge is
         * inclusive: a unit exactly [RECENT_MS] old is still in the first band.
         *
         * Called by [build] for every EARLIER unit.
         *
         * @param recencyMillis epoch millis of the unit's newest activity.
         * @param nowMillis the current epoch millis.
         * @return true when the unit is at most [RECENT_MS] old.
         */
        fun inRecent(recencyMillis: Long, nowMillis: Long): Boolean = nowMillis - recencyMillis <= RECENT_MS

        /**
         * Whether activity at [recencyMillis] is listed in EARLIER at all. The edge is inclusive: a
         * unit exactly [WINDOW_MS] old is listed, one millisecond older is not.
         *
         * @param recencyMillis epoch millis of the unit's newest activity.
         * @param nowMillis the current epoch millis.
         * @return true when the unit is at most [WINDOW_MS] old.
         */
        fun inWindow(recencyMillis: Long, nowMillis: Long): Boolean = nowMillis - recencyMillis <= WINDOW_MS

        /** The fewest characters that start a search over every session. */
        const val MIN_QUERY = 2

        /**
         * How long a working session may show no sign of life before the overview stops
         * drawing it as busy. Hook events arrive around every tool call, so five minutes
         * without one means a hung session or one inside a single very long tool call.
         */
        const val QUIET_AFTER_MS = 5L * 60_000L

        /**
         * Whether a working row has gone quiet: nothing has been heard from it for
         * [QUIET_AFTER_MS]. A pure function of the clock so the web view and the tests agree.
         *
         * Called by the web overview on every redraw (and on its clock tick) for each working
         * row that has an [Row.activityMillis].
         *
         * @param activityMillis epoch millis of the row's last sign of life.
         * @param nowMillis the current epoch millis.
         * @return true when the gap is at least [QUIET_AFTER_MS].
         * @see Row.activityMillis
         */
        fun isQuiet(activityMillis: Long, nowMillis: Long): Boolean = nowMillis - activityMillis >= QUIET_AFTER_MS

        /**
         * How long a task file may go without being refreshed before what it says about members
         * Lunamux does not host is treated as stale. The writer touches an unchanged file at least
         * every two minutes while a member works, so twenty minutes of silence means the
         * writer stopped and the file's states are no longer news.
         */
        const val STALE_AFTER_MS = 20L * 60_000L

        /**
         * Whether a task file has gone stale: it was last refreshed [STALE_AFTER_MS] or more ago.
         * A pure function of the clock, like [isQuiet], so the web view's periodic redraw can flip a
         * row to stale with no new input from the server.
         *
         * Called by the web overview on every redraw for each row with a [Row.fileRefreshedMillis].
         *
         * @param refreshedMillis epoch millis the task file was last refreshed.
         * @param nowMillis the current epoch millis.
         * @return true when the gap is at least [STALE_AFTER_MS].
         * @see Row.fileRefreshedMillis
         */
        fun isStale(refreshedMillis: Long, nowMillis: Long): Boolean = nowMillis - refreshedMillis >= STALE_AFTER_MS

        /**
         * Whether a working row's glyph should move: it has a sign of life that is not yet
         * [QUIET_AFTER_MS] old. For a member Lunamux does not host that sign is the task file's
         * refresh time, so a file that is still being refreshed keeps its working members live.
         *
         * Called by the web overview for each row and header when it draws the state glyph.
         *
         * @param activityMillis [Row.activityMillis] or [Task.activityMillis]; null means nothing works.
         * @param nowMillis the current epoch millis.
         * @return true when the glyph should animate.
         * @see isQuiet
         */
        fun isWorkingLive(activityMillis: Long?, nowMillis: Long): Boolean =
            activityMillis != null && !isQuiet(activityMillis, nowMillis)

        /**
         * "2 need you" / "1 needs you": the plural form shared by the chip and the task headers.
         *
         * @param n how many need the user.
         * @return the count and the right verb.
         */
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"

        /**
         * The prefix of a working row's status when its sign of life is old: "stale 25m · " for a
         * member whose task file has not been refreshed for [STALE_AFTER_MS], else "quiet 6m · " once
         * [QUIET_AFTER_MS] has passed; empty otherwise.
         *
         * Called by the web overview for each working row on every redraw.
         *
         * @param row a row of the list.
         * @param nowMillis the current epoch millis.
         * @return the prefix including its trailing separator, or an empty string.
         */
        fun agePrefix(row: Row, nowMillis: Long): String =
            agePrefixOf(row.state == WORKING, row.fileRefreshedMillis, row.activityMillis, nowMillis)

        /**
         * The one rule behind [agePrefix], [isMoving] and the counts of working sessions, so a row
         * that reads "quiet 6m" is never counted as working: a working session whose task file has
         * not been refreshed for [STALE_AFTER_MS] is "stale", one with no sign of life for
         * [QUIET_AFTER_MS] is "quiet", anything else has no prefix.
         *
         * Called by [agePrefix] for a row, by the view model's counts for a session or a task
         * member, and through [isMoving].
         *
         * @param working whether the session's state is working; any other state has no prefix.
         * @param fileRefreshedMillis epoch millis the declaring task file was last refreshed, for a
         *   member Lunamux does not host; null otherwise.
         * @param activityMillis epoch millis of the last sign of life; null when there is none.
         * @param nowMillis the current epoch millis.
         * @return "stale 25m · ", "quiet 6m · " or an empty string.
         * @see agePrefix
         */
        fun agePrefixOf(working: Boolean, fileRefreshedMillis: Long?, activityMillis: Long?, nowMillis: Long): String {
            if (!working) return ""
            if (fileRefreshedMillis != null && isStale(fileRefreshedMillis, nowMillis)) {
                return "stale ${(nowMillis - fileRefreshedMillis) / 60_000L}m · "
            }
            if (activityMillis != null && isQuiet(activityMillis, nowMillis)) {
                return "quiet ${(nowMillis - activityMillis) / 60_000L}m · "
            }
            return ""
        }

        /**
         * Whether a working session is really moving: it works and is neither quiet nor stale. This
         * is what the toolbar chip and a task's count line call "working".
         *
         * Called by the view model when it counts working sessions, for singles and for task members.
         *
         * @param working whether the session's state is working.
         * @param fileRefreshedMillis see [agePrefixOf].
         * @param activityMillis see [agePrefixOf].
         * @param nowMillis the current epoch millis.
         * @return true for a working session with a recent sign of life.
         */
        fun isMoving(working: Boolean, fileRefreshedMillis: Long?, activityMillis: Long?, nowMillis: Long): Boolean =
            working && agePrefixOf(true, fileRefreshedMillis, activityMillis, nowMillis).isEmpty()

        /**
         * Whether a gap belongs above [row] because it follows a task: [previous] is a task header
         * (collapsed, so none of its sessions are listed) or a session inside a task panel, and
         * [row] is neither a header (which has its own gap) nor a session of that same task. Without
         * it a lone session after a collapsed task reads as one of the task's members, though the
         * unit is the task.
         *
         * Called by the web overview for each row of a band as it draws the list.
         *
         * @param previous the row listed just above [row] in its band, or null for the band's first.
         * @param row the row about to be drawn.
         * @param tasks [State.tasks], which names the tasks drawn as panels.
         * @return true when the row starts a new unit right after a task.
         */
        fun startsAfterTask(previous: Row?, row: Row, tasks: Map<String, Task>): Boolean {
            if (previous == null || row.kind == RowKind.TASK) return false
            val afterTask = previous.kind == RowKind.TASK ||
                (previous.taskId != null && tasks.containsKey(previous.taskId))
            return afterTask && (row.taskId == null || row.taskId != previous.taskId)
        }

        private const val TASK_PREFIX = "task:"
        private const val MEMBER_PREFIX = "member:"
        private const val OPEN_WORK_RANK = 2

        /** The [Bucket] order of a session that is done: counted last and the sign that a task has nothing left to move. */
        private const val DONE_ORDER = 6
        private const val GRACE_RANK = 3
        private const val IDLE = "idle"
        private val PR_LINK = Regex("#\\d+")
        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
        private const val GEMINI = "gemini"
    }
}
