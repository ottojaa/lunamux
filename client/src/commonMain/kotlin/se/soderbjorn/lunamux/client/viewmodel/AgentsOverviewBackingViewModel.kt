/**
 * Backing ViewModel for the agents overview: the toolbar chip and the
 * ⌘⇧A overlay that list every agentic-CLI session (Claude Code, Codex,
 * Gemini) Lunamux knows about.
 *
 * The server pushes one list of session summaries and one of tasks; this ViewModel turns them
 * into the sections the overlay shows (NOW, then EARLIER in two recency bands: the last few
 * days and the last two weeks), the chip counts and the keyboard selection. Typing two or more
 * characters switches the overlay to the server's search over every session ever recorded
 * (titles, first prompts, branches, links and transcripts). The ViewModel carries out what a
 * row offers: jump to its pane, peek at its screen, take a background tab over, resume it in a
 * new tab, and close it after a confirmation that names what would be lost. The view only
 * renders [AgentsOverviewBackingViewModel.State] and forwards key presses.
 *
 * Every row is one task, drawn as one aligned line. A session that belongs to no task is a task
 * of one. The sessions of a task, background ones included, are never rows of their own: the
 * row counts them in its state word ("needs you", "2 working", "working", "done" or
 * "finished") and shows the task's status as its detail. The selected row has one more line,
 * carries the places that matter for opening it, such as the tab the session sits in, but not the
 * detail (the view puts the detail in front only when the row cut it); a row with nothing to add
 * has an empty [AgentsOverviewBackingViewModel.Row.extra] and the view draws no line. EARLIER
 * lists its newest five rows and a "+ N more" line for the rest.
 *
 * What ↵ opens follows one order. A task with an own session opens that session: its pane, or a
 * read-only peek when the pane is in a background tab; once the session is gone the task's
 * `open` action runs when the task file gives one, else the conversation is resumed. A task
 * with no own session jumps to a live session in a listed tab, else runs its `open` action,
 * else peeks at a live session in a background tab, else resumes its newest session; a
 * background session is never chosen while a listed one is live. A session in no task jumps,
 * peeks when it is in a background tab, or resumes.
 *
 * NOW is the open tabs: a session is in it only while its pane is in a tab that is open, and a
 * task only while one of its sessions is. There are two exceptions. A session that needs the
 * person stays in NOW wherever it runs, so a question is never filed under EARLIER where the
 * "more" cut or the two-week window could hide it. And a session that has just ended, or a task
 * that has just finished, stays for ten minutes so a row never reads "now" under a days heading;
 * a running idle session outside every tab gets no such grace. Inside that, a task stays in NOW
 * while it has open work (a session needs the person or works, or a task file still has a
 * session in review or queued). NOW keeps its rows in the order their work started, never by
 * state, so a row does not move when its state flips. A task is one unit: it stays while any one
 * of its sessions is in an open tab, and a session that needs the person keeps its whole task in
 * NOW. What has finished, gone quiet or is not in an open tab moves to EARLIER, by task: a
 * task's recency is its newest session's activity, so one recent session keeps the whole task in
 * the first band.
 *
 * The selection is a row id, not an index, so it stays on its row while rows appear and
 * disappear, and it opens on the first row that needs the person.
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
import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.activeWorldOrNull
import se.soderbjorn.lunamux.effectiveActiveTabId
import se.soderbjorn.lunamux.isListed
import se.soderbjorn.lunamux.client.WindowStateRepository

/**
 * Where a session's pane sits in a pane layout: the world, tab and leaf a jump has to activate.
 *
 * @property worldId the world holding the tab, or null when worlds are not in use.
 * @property worldName that world's name, or null when worlds are not in use.
 * @property tabId the tab holding the pane.
 * @property tabNumber the tab's 1-based place among the listed tabs of its world, or null for a
 *   background tab, which no tab bar lists.
 * @property leaf the pane's leaf, used for its custom name.
 */
private data class PaneLocation(
    val worldId: String?,
    val worldName: String?,
    val tabId: String,
    val tabNumber: Int?,
    val leaf: LeafNode,
)

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
 *   idle and just-ended sessions are still inside their [IDLE_GRACE_MS]. Tests pass a fixed one.
 * @param send sends a command to the server, normally `windowSocket::send`.
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /** What ↵ does on a [Row]. */
    enum class Enter {
        /** Switch to the pane of [Row.recordId] and focus it. */
        JUMP,

        /** Show a read-only peek of the background session [Row.recordId]. */
        PEEK,

        /** Run the task's `open` action: a new tab in the task's folder. */
        OPEN,

        /** Resume the conversation of [Row.recordId] in a new tab. */
        RESUME,

        /** Nothing: the row has no session Lunamux can open and no `open` action. */
        NONE,
    }

    /**
     * One row of the overlay: a task, or a session that is a task of one.
     *
     * @property id `task:<id>` for a task, else the server's record id of the lone session; what
     *   the selection and a resume's progress are keyed by.
     * @property taskId the task the row stands for, or null for a session in no task.
     * @property title the task's name; for a lone session its display title: the session's own
     *   title when it says what the work is (the server keeps a name the user gave the pane, even
     *   after the pane closes), else the first words of its first prompt, else the agent's name.
     *   Never a path, `~`, `HEAD`, the branch, the folder or a run id: those say where the
     *   session ran, not what it is for.
     * @property where the task's repository name; else the folder the session runs in, as
     *   `~` for the home folder or the last path segment. Null when there is none.
     * @property marker "◆" when the task needs the person, else empty. The view draws ▸ over it
     *   on the selected row.
     * @property stateWord exactly one of [STATE_NEEDS_YOU] (a session waits on the person),
     *   "N working" (two or more sessions are moving, background ones included), [STATE_WORKING]
     *   (one is moving), [STATE_DONE] (in NOW, nothing moving or waiting) or [STATE_FINISHED]
     *   (in EARLIER, nothing moving or waiting).
     * @property detail what the row says after its state word: the task's status; without one the
     *   question a session asks, in quotes, else the line a moving session last reported, else
     *   empty. A lone session has no status. In a search result it is the text around the match.
     * @property extra the line under the selected row, without [detail]: as they apply, what Resume
     *   will do when it is not the plain case ("2 uncommitted changes · 1 unpushed commit"), a note
     *   that there is nothing to jump to, the error of the last failed resume or open, where a
     *   search matched ("in transcript of Reviewer"), and the tab the session [enter] opens sits in
     *   ("tab 2", or "tab 2 in Work" in another world). The view puts [detail] in front of it only
     *   when the row itself cut that text short (or hides it, in a narrow panel), so the same words
     *   are never shown twice. Empty when the row has none of these; the view then draws a line
     *   only if it has the detail to repeat, and never an empty one.
     * @property tab that last part of [extra] on its own ("tab 2", "tab 2 in Work"), empty when the
     *   extra has none, so the view can draw it dimmer than the text before it.
     * @property sinceMillis epoch millis the row's age counts from: the time the task entered its
     *   current state for a task in NOW, its newest session's activity (when it ended, for an
     *   ended one) for a task in EARLIER; the view turns it into an age.
     * @property enter what ↵ does.
     * @property recordId the session ↵, → and ⌘↵ act on: the one [enter] jumps to, peeks at or
     *   resumes, and what a peek and the inspection ask the server about. Null when [enter] is
     *   [Enter.OPEN] or [Enter.NONE]. For a search result it is the session that matched.
     * @property isLive whether [recordId]'s pane is open.
     * @property background whether [recordId]'s pane is in a background tab an agent opened out
     *   of sight; ⌘↵ takes such a tab over.
     * @property canClose whether ⌘⌫ may ask to close the row: it is one session, live in a pane.
     *   A task with several sessions can never be closed from here.
     * @property error a message from the last failed resume or open of this row, or null.
     * @property field in a search result, where the query was found: `title`, `task`, `prompt`,
     *   `branch`, `links`, `transcript` or `screen`; null outside a search.
     * @property snippet in a search result, the text around the match (null for a title or task
     *   match that the row's own [title] shows).
     * @property matchStart where the match begins, inside [snippet] or, when that is null, [title].
     * @property matchLength how many characters the match covers; 0 when there is nothing to highlight.
     */
    data class Row(
        val id: String,
        val taskId: String?,
        val title: String,
        val where: String?,
        val marker: String,
        val stateWord: String,
        val detail: String,
        val extra: String,
        val tab: String = "",
        val sinceMillis: Long,
        val enter: Enter,
        val recordId: String?,
        val isLive: Boolean,
        val background: Boolean,
        val canClose: Boolean,
        val error: String? = null,
        val field: String? = null,
        val snippet: String? = null,
        val matchStart: Int = 0,
        val matchLength: Int = 0,
    ) {
        /** Whether a session of the row waits on the person. */
        val needsYou: Boolean get() = stateWord == STATE_NEEDS_YOU

        /** Whether the row reads "finished": it is in EARLIER and nothing moves. The view dims it. */
        val finished: Boolean get() = stateWord == STATE_FINISHED

        /** Whether the row reads "working" or "N working". */
        val working: Boolean get() = stateWord.endsWith(STATE_WORKING)

        /** Whether the search match is inside [title] rather than [snippet]. */
        val matchInTitle: Boolean get() = matchLength > 0 && snippet == null

        /** Whether the search match is inside [snippet]. */
        val matchInSnippet: Boolean get() = matchLength > 0 && snippet != null
    }

    /**
     * The peek popover beside the selected row.
     *
     * @property live whether it shows the pane's current screen (refreshed while open) rather than
     *   the scrollback saved when the session ended.
     * @property title the title of the session that is peeked at.
     * @property closedSinceMillis when a closed session ended, for "CLOSED <age>"; null when live.
     * @property lines the last non-blank screen lines.
     * @property loading true until the server has answered.
     * @property emptyText what to show instead of [lines] when there is nothing, else null.
     * @property background whether the session runs in a background tab, so the peek offers ⌘↵ to
     *   take the tab over.
     */
    data class Peek(
        val live: Boolean,
        val title: String,
        val closedSinceMillis: Long?,
        val lines: List<String>,
        val loading: Boolean,
        val emptyText: String?,
        val background: Boolean = false,
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
     *   then [matches].
     * @property matches the server's answer for the current query folded to one row per task,
     *   best match first; empty until it arrives.
     * @property searching true while a [matchMode] answer for the current query is still on its way.
     * @property now the rows in NOW, in the order their work started.
     * @property recent EARLIER's first band, "Last few days": rows whose newest activity is at
     *   most [RECENT_MS] old, newest first.
     * @property fortnight EARLIER's second band, "Last two weeks": rows between [RECENT_MS] and
     *   [WINDOW_MS] old, newest first. Nothing older is listed; search finds it.
     *   [recent] and [fortnight] together hold only the newest [EARLIER_LIMIT] rows of EARLIER
     *   until "+ N more" is pressed, so the second band may be empty or cut short.
     * @property earlierHidden how many EARLIER rows "+ N more" would reveal. It describes the list outside a
     *   search only: while [matchMode] is on the lists are empty and a client draws no "+ N more" line,
     *   whatever this holds.
     * @property needYouCount sessions asking for the user; the chip's first number.
     * @property workingCount sessions that work and are moving (neither quiet nor stale, see
     *   [isMoving]); the chip's second number.
     * @property selectedId the id of the highlighted row, or null when there are no rows.
     * @property peek the screen popover, or null while it is closed.
     * @property confirm the close confirmation, or null while none is asked.
     * @property hasSessions whether any agent session or task has ever been recorded, which is
     *   when the toolbar chip shows.
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
        val hasSessions: Boolean = false,
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
            ui.value = Ui()
        }
    }

    /**
     * Show the overlay with an empty search and the first ◆ row in list order selected (the
     * first row when nothing needs the user). Called by ⌘⇧A and the chip.
     */
    fun open() {
        windowState.clearAgentSessionError()
        inspected = null
        val fresh = Ui(open = true)
        val first = build(inputs().copy(ui = fresh))
        ui.value = fresh.copy(selectedId = first.selectedId, selectedHint = maxOf(first.selected, 0))
    }

    /** Hide the overlay and forget the search. Called by esc and after a jump or resume. */
    fun close() {
        windowState.clearAgentSessionError()
        inspected = null
        ui.value = Ui()
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
     * Move the highlight, stopping at the first and last row. ↓ on the last row while EARLIER
     * hides rows ("+ N more") shows them and moves onto the first of them, so the keyboard reaches
     * what a click on that line reveals.
     *
     * @param delta `-1` for ↑, `1` for ↓.
     */
    fun moveSelection(delta: Int) {
        val current = state
        val rows = current.rows
        if (rows.isEmpty()) return
        val at = maxOf(current.selected, 0)
        if (delta > 0 && at == rows.lastIndex && !current.matchMode && current.earlierHidden > 0) {
            showMoreEarlier()
            val revealed = state.rows
            val onto = (at + 1).coerceAtMost(revealed.lastIndex)
            ui.update { it.copy(selectedId = revealed[onto].id, selectedHint = onto, peekOpen = false, confirmRowId = null) }
            return
        }
        val next = (at + delta).coerceIn(0, rows.lastIndex)
        ui.update { it.copy(selectedId = rows[next].id, selectedHint = next, peekOpen = false, confirmRowId = null) }
    }

    /**
     * Highlight the row with [rowId], as a mouse click does. Does nothing for an id that is not listed.
     *
     * Called by the view when a row is clicked, before it opens the row.
     *
     * @param rowId the [Row.id] to select.
     */
    fun select(rowId: String) {
        val rows = state.rows
        val index = rows.indexOfFirst { it.id == rowId }
        if (index >= 0) ui.update { it.copy(selectedId = rowId, selectedHint = index, peekOpen = false, confirmRowId = null) }
    }

    /** Reveal the EARLIER rows beyond the newest [EARLIER_LIMIT]. Called by the "+ N more" line and by ↓ past the last row. */
    fun showMoreEarlier() = ui.update { it.copy(showAllEarlier = true) }

    /**
     * Open the highlighted row (see [activate]).
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
        val row = state.selectedRow ?: return
        val recordId = row.recordId ?: return
        val key = "$recordId/${row.isLive}"
        if (!force && inspected == key) return
        inspected = key
        send(WindowCommand.InspectAgentSession(recordId))
    }

    /**
     * Open the peek popover on the selected row and fetch its screen. Called for → (with the
     * caret at the end of the search text). It peeks the session ↵ would open, and does nothing
     * for a row with none.
     */
    suspend fun peek() {
        if (state.selectedRow?.recordId == null) return
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
     * until [confirmClose]. Does nothing for a row that is not one live session.
     *
     * Called by ⌘⌫.
     *
     * @param fromKeyboard true for ⌘⌫, which is ignored while a search is typed so the
     *   shortcut cannot hit a row the user is not looking at.
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
        val recordId = state.rows.firstOrNull { it.id == id }?.recordId
        val leafId = windowState.agentSessions.value.firstOrNull { it.id == recordId }?.leafId
        val sessionId = if (config != null && leafId != null) locate(config, leafId)?.leaf?.sessionId else null
        ui.update { it.copy(confirmRowId = null, confirmOnClose = false) }
        if (sessionId != null) send(WindowCommand.CloseSession(sessionId))
    }

    /**
     * Open [row] the way [Row.enter] says: jump to its pane, peek at a background session,
     * run the task's `open` action, or resume the conversation. Does nothing for [Enter.NONE].
     *
     * Called when a row is clicked and for ↵ (through [activateSelected]).
     *
     * @param row the row to open.
     */
    suspend fun activate(row: Row) {
        val recordId = row.recordId
        when (row.enter) {
            Enter.JUMP -> recordId?.let { jumpToRecord(it) }
            Enter.PEEK -> { select(row.id); peek() }
            Enter.OPEN -> openTask(row)
            Enter.RESUME -> recordId?.let { resumeRecord(row.id, it) }
            Enter.NONE -> Unit
        }
    }

    /**
     * Take over the background tab of the selected session: it becomes an ordinary tab, the layout
     * switches to it and the overlay closes. Does nothing unless the session ↵ would open runs in
     * a background tab.
     *
     * Called for ⌘↵, in the list and in the peek.
     *
     * @see WindowCommand.TakeOverTab
     */
    suspend fun takeOverSelected() {
        val recordId = state.selectedRow?.recordId ?: return
        val session = windowState.agentSessions.value.firstOrNull { it.id == recordId } ?: return
        val leafId = session.leafId ?: return
        if (!session.background) return
        val where = windowState.config.value?.let { locate(it, leafId) } ?: return
        send(WindowCommand.TakeOverTab(where.tabId))
        send(WindowCommand.SetFocusedPane(where.tabId, leafId))
        close()
    }

    /**
     * Run a task's `open` action: ask the server to open a tab in the folder the task file names
     * and start the session there. The overlay stays open until the new tab is active or the
     * server says why it could not, exactly as for a resume.
     *
     * @param row the task's row; its [Row.taskId] names the task.
     */
    private suspend fun openTask(row: Row) {
        val taskId = row.taskId ?: return
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.OpenTask(taskId))
    }

    /**
     * Switch to the pane the record's agent runs in and focus it.
     *
     * Uses the existing world, tab and focus commands, so the layout
     * changes exactly as if the user had clicked there. Does nothing when the
     * pane is not in the layout.
     *
     * @param recordId the record of a live session.
     */
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
     * Ask the server to open a new tab in the record's folder with the agent's resume command.
     * The overlay stays open until the new tab becomes active or the server reports why it
     * could not; a failure reads on the row that asked.
     *
     * @param rowId the row the overlay shows the progress and any error on.
     * @param recordId the record to resume.
     */
    private suspend fun resumeRecord(rowId: String, recordId: String) {
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = rowId, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.ResumeAgentSession(recordId))
    }

    /**
     * One session of a task, paired with the session summary it matched (null when Lunamux does not
     * host it).
     *
     * @property member what the task says about the session: the file's member, or one made from
     *   the session itself.
     * @property session the hosted session behind it, or null.
     */
    private class Run(val member: AgentTaskMember, val session: AgentSessionSummary?) {
        /**
         * The task file's refresh time when this member is one Lunamux does not host,
         * else null.
         *
         * @param refreshed epoch millis the declaring task file was last refreshed, or null.
         */
        fun fileRefreshed(refreshed: Long?): Long? = refreshed.takeIf { session == null }
    }

    /**
     * Whether [run] is working and really moving (neither quiet nor stale).
     *
     * Called by [build] for the chip's count and by [taskRow] for the state word.
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
     * A task with its sessions, ready to become a row.
     *
     * @property task the task as the server sent it.
     * @property runs its sessions.
     * @property since the time that places the task among those in NOW: since the oldest session that
     *   needs the person (or works) changed state, else the newest change of any session, and for a
     *   task of hosted sessions that went quiet, their last activity.
     * @property recency epoch millis of the newest activity of any session of the task: when an ended
     *   one ended, the last activity of an open one, and the time a task file gives a member. Decides
     *   the EARLIER band and the order inside it.
     * @property graced whether the task has just finished (every session ended, or none needs the
     *   person or works and they all read done or have been idle past [IDLE_GRACE_MS]) and its
     *   [recency] is still inside [IDLE_GRACE_MS]: like a session that has just ended, it stays in
     *   NOW for the grace instead of going to EARLIER with an age of a few minutes.
     * @property inNow whether the task belongs in NOW: it has not finished, and either
     *   a session needs the person (the whole task stays, wherever its other sessions run) or one of
     *   its sessions is in an open tab. Every other task is in EARLIER, even one a task file still
     *   lists work for.
     * @property started epoch millis the task started, which fixes its place in NOW.
     * @property enter what ↵ does on the task's row.
     * @property target the session [enter] acts on, or null for [Enter.OPEN] and [Enter.NONE].
     */
    private class TaskUnit(
        val task: AgentTask,
        val runs: List<Run>,
        val since: Long,
        val recency: Long,
        val graced: Boolean,
        val inNow: Boolean,
        val started: Long,
        val enter: Enter,
        val target: AgentSessionSummary?,
    ) {
        /**
         * Whether the task is in EARLIER: not in NOW, and not inside the grace of a task that has
         * just finished (which keeps its place in NOW, see [graced]). Every place that draws a task
         * in EARLIER asks this, so NOW and EARLIER never both claim the same task.
         */
        val inEarlier: Boolean get() = !inNow && !graced
    }

    /**
     * What a row needs from the layout, the clock and the last answers of the server, gathered once
     * per [build].
     *
     * @property panes where the layout holds each pane, by leaf id.
     * @property config the layout, or null before it has arrived.
     * @property nowMillis the current time.
     * @property error the last error of a resume or open, or null.
     * @property resumingId the row that error belongs to.
     * @property inspection the server's last answer about a session's folder and screen.
     */
    private class Context(
        val panes: Map<String?, PaneLocation>,
        val config: WindowConfig?,
        val nowMillis: Long,
        val error: String?,
        val resumingId: String?,
        val inspection: WindowEnvelope.AgentSessionInspection?,
    ) {
        /** The error of the last resume or open if it was made from row [id], else null. */
        fun errorOf(id: String): String? = error.takeIf { id == resumingId }

        /** What Resume will do for [recordId] when it is not the plain case, from the last inspection. */
        fun noteOf(recordId: String?): String? = inspection?.takeIf { it.recordId == recordId }?.resumeNote

        /** Whether the pane of [s] is open in the layout. */
        fun live(s: AgentSessionSummary): Boolean = s.state != ENDED && panes[s.leafId] != null
    }

    private fun build(input: Inputs): State {
        val (sessions, taskList, config, error, matches, inspection, ui) = input
        val nowMillis = clock()
        val panes = config?.let(::paneLocations).orEmpty()
        val c = Context(panes, config, nowMillis, error, ui.resumingId, inspection)
        val query = ui.query.trim()
        val matchMode = query.length >= MIN_QUERY
        val byId = sessions.associateBy { it.id }
        fun inspectionOf(id: String) = inspection?.takeIf { it.recordId == id }

        val sessionsByTask = sessions.filter { it.taskId != null }.groupBy { it.taskId!! }
        val units = taskList.mapNotNull { t -> unitOf(t, byId, sessionsByTask[t.id].orEmpty(), panes, nowMillis) }
        val unitsById = units.associateBy { it.task.id }

        // The server lists matches best first; the first match of a task is the one that stands for it.
        val answer = matches?.takeIf { matchMode && it.query == query }
        val matchRows = answer?.matches.orEmpty()
            .distinctBy { it.summary.taskId ?: it.summary.id }
            .map { m ->
                val s = byId[m.summary.id] ?: m.summary
                val unit = s.taskId?.let(unitsById::get)
                val base = when {
                    unit != null -> taskRow(unit, c)
                    // A task older than the pushed list is still one row, named by the title the search found.
                    else -> sessionRow(s, earlier = s.state == ENDED || !c.live(s), c = c, taskTitle = if (s.taskId != null) m.taskTitle ?: titleOf(s) else null)
                }
                matchRow(base, m, s, c)
            }

        // A session a task unit holds is drawn inside it, not again as a lone session.
        val claimed = units.flatMap { u -> u.runs.mapNotNull { it.session?.id } }.toSet()
        val singles = sessions.filter { it.id !in claimed }

        // A task stays in NOW while it has open work and leaves once it is settled (see TaskUnit);
        // it is ranked beside the lone sessions that need the user or work, by its most urgent session.
        class Placed(val started: Long, val tie: String, val row: Row)
        // NOW is the sessions in open tabs (a row is live when its pane is in the layout). One that
        // needs the person is listed wherever it runs; one that works, or an idle one inside its grace,
        // only while it is in a tab. The rest go to EARLIER by their last activity.
        // An idle session in a tab stays in NOW for a while after its last activity, so a chat that
        // finishes a turn does not jump to EARLIER and back as the next turn starts. A session that
        // has just ended (its tab closed, say) gets the same grace although it is in no tab, so it
        // never reads a few minutes old under a heading that says days.
        val graced = singles.filter { s ->
            ((c.live(s) && s.state == IDLE) || s.state == ENDED) && inGrace(activityOf(s), nowMillis)
        }
        val nowSingles = singles.filter { it.state == NEEDS_YOU || (c.live(it) && it.state == WORKING) }
        // NOW keeps the order work started in, for tasks and lone sessions alike, so a row never
        // moves because its state changed. Rows that started together keep the order of their ids.
        val urgent = buildList {
            (nowSingles + graced).forEach { add(Placed(startedOf(it), it.id, sessionRow(it, earlier = false, c = c))) }
            // A task that has just finished is graced like a session that has just ended.
            units.filter { it.inNow || it.graced }
                .forEach { add(Placed(it.started, it.task.id, taskRow(it, c))) }
        }.sortedWith(compareBy<Placed> { it.started }.thenBy { it.tie })
        val now = urgent.map { it.row }

        // EARLIER is built from tasks and lone sessions, each placed by its newest activity:
        // within RECENT_MS it is in the first band, up to WINDOW_MS in the second, older not listed.
        class Earlier(val recency: Long, val row: Row)
        val nowIds = (nowSingles + graced).mapTo(HashSet()) { it.id }
        val earlierUnits = (
            singles.filter { it.id !in nowIds }
                // A lone session's age is the very time its band is chosen by (see sinceOf), so a row
                // never reads "now" under a heading that says days.
                .map { s -> Earlier(sinceOf(s), sessionRow(s, earlier = true, c = c)) } +
                units.filter { it.inEarlier }.map { Earlier(it.recency, taskRow(it, c)) }
            ).filter { inWindow(it.recency, nowMillis) }
            .sortedByDescending { it.recency }
        // The newest EARLIER_LIMIT rows show, whichever band they are in; "+ N more" reveals the rest.
        val shownEarlier = if (ui.showAllEarlier) earlierUnits else earlierUnits.take(EARLIER_LIMIT)
        val recent = shownEarlier.filter { inRecent(it.recency, nowMillis) }.map { it.row }
        val fortnight = shownEarlier.filter { !inRecent(it.recency, nowMillis) }.map { it.row }

        val listed = if (matchMode) matchRows else now + recent + fortnight
        // The selection is the row with the remembered id; when that row is gone, the row now in its place.
        val selectedId = when {
            listed.isEmpty() -> null
            ui.selectedId != null && listed.any { it.id == ui.selectedId } -> ui.selectedId
            ui.selectedId != null -> listed[ui.selectedHint.coerceIn(0, listed.lastIndex)].id
            matchMode -> listed.first().id
            else -> listed.firstOrNull { it.needsYou }?.id ?: listed.first().id
        }
        val selectedRow = listed.firstOrNull { it.id == selectedId }

        val peek = if (ui.peekOpen && selectedRow?.recordId != null) {
            val session = byId[selectedRow.recordId]
            val said = inspectionOf(selectedRow.recordId)?.takeIf { it.live == selectedRow.isLive }
            Peek(
                live = selectedRow.isLive,
                title = session?.let(::titleOf) ?: selectedRow.title,
                closedSinceMillis = if (selectedRow.isLive) null else session?.let(::activityOf) ?: selectedRow.sinceMillis,
                lines = said?.screen.orEmpty(),
                loading = said == null,
                emptyText = if (said != null && said.screen.isEmpty()) {
                    if (selectedRow.isLive) "(the screen is empty)" else "(no saved screen)"
                } else null,
                background = session?.background == true,
            )
        } else null

        val confirmRow = ui.confirmRowId?.let { id -> listed.firstOrNull { it.id == id } }
        val confirm = confirmRow?.let { row ->
            Confirm(
                rowId = row.id,
                title = row.title,
                warnings = byId[row.recordId]?.let { closeWarnings(it, inspectionOf(it.id)) }.orEmpty(),
                cancelFocused = !ui.confirmOnClose,
            )
        }

        // Once a resume has switched to the new tab the overlay has done its job;
        // settleResume then resets the inputs so it stays closed.
        val resumed = ui.resumingId != null && config?.effectiveActiveTabId != ui.resumeFromTab
        val taskNeeds = units.sumOf { u -> u.runs.count { it.member.state == NEEDS_YOU } }
        // The chip counts what NOW shows: a task that is not in NOW adds nothing, so a working session
        // outside every open tab is counted only as a member of a task that a question keeps in NOW.
        val taskWorking = units.filter { !it.inEarlier }.sumOf { u -> u.runs.count { runMoving(it, u.task.refreshedAtMillis, nowMillis) } }
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            matchMode = matchMode,
            matches = if (matchMode) matchRows else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else now,
            recent = if (matchMode) emptyList() else recent,
            fortnight = if (matchMode) emptyList() else fortnight,
            earlierHidden = earlierUnits.size - shownEarlier.size,
            needYouCount = singles.count { it.state == NEEDS_YOU } + taskNeeds,
            workingCount = nowSingles.count { isMoving(it.state == WORKING, null, it.lastActivityAt ?: it.stateSince, nowMillis) } + taskWorking,
            selectedId = selectedId,
            peek = peek,
            confirm = confirm,
            hasSessions = sessions.isNotEmpty() || taskList.any { it.members.isNotEmpty() },
        )
    }

    /**
     * When a session started: the time its record opened, which never changes; a server that does
     * not send it leaves the time the session entered its current state.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun startedOf(s: AgentSessionSummary): Long = s.startedAt ?: s.stateSince

    /**
     * The time a lone session's row age counts from: the time it entered its state while it needs
     * the person or works, else its last sign of life, so the list and a search result give it the
     * same age and the age and the EARLIER band are read from one moment.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun sinceOf(s: AgentSessionSummary): Long =
        if (s.state == ENDED || s.state == IDLE) activityOf(s) else s.stateSince

    /**
     * Pair a task's sessions with the hosted sessions they match, work out where the task sits and
     * what ↵ on its row opens.
     *
     * The task's sessions are the members its file lists plus every hosted session that carries
     * its id and no member of the file claimed. A task without sessions and without an `open`
     * action has no unit and no row.
     *
     * @param t the task.
     * @param byId all session summaries by record id.
     * @param hosted the hosted sessions whose [AgentSessionSummary.taskId] is [t]'s id.
     * @param panes where the layout holds each pane, to tell which sessions are open.
     * @param nowMillis the current time, which decides whether a task of idle hosted sessions is
     *   still inside the grace a single idle session gets.
     * @return the unit, or null when the task has nothing to show.
     */
    private fun unitOf(
        t: AgentTask,
        byId: Map<String, AgentSessionSummary>,
        hosted: List<AgentSessionSummary>,
        panes: Map<String?, PaneLocation>,
        nowMillis: Long,
    ): TaskUnit? {
        val fileRuns = t.members.map { m -> Run(m, m.recordId?.let(byId::get)) }
        val matched = fileRuns.mapNotNullTo(HashSet()) { it.session?.id }
        val extraRuns = hosted.filter { it.id !in matched }
            .sortedWith(compareBy({ startedOf(it) }, { it.id }))
            .map { s -> Run(memberOf(s), s) }
        val runs = fileRuns + extraRuns
        if (runs.isEmpty() && !t.hasOpen) return null
        val ended = runs.all { it.member.state == ENDED }
        val urgentRank = when {
            runs.any { it.member.state == NEEDS_YOU } -> 0
            runs.any { it.member.state == WORKING } -> 1
            else -> OPEN_WORK_RANK
        }
        // A task file speaks for its task, so the task stays in NOW while it has work to do, even
        // while nothing moves: a member in review or queued is still open work. Once every member
        // reads done, nothing is moving or waiting and the file has said so itself, so the task is
        // finished, as a task whose runs all ended is. A finished task stays in NOW for the grace
        // after its newest activity and then goes to EARLIER. A task of hosted sessions only has no
        // such word: like one idle session, it stays for the grace after the last activity and then moves.
        val lastActivity = runs.mapNotNull { r -> r.session?.let { it.lastActivityAt ?: it.stateSince } }.maxOrNull()
        val hostedOnly = t.source != "file"
        val quiet = hostedOnly && urgentRank == OPEN_WORK_RANK && !runs.all { it.member.state == ENDED }
        val allDone = runs.isNotEmpty() && runs.all { isDone(it.member) }
        val settled = allDone || (quiet && (lastActivity == null || !inGrace(lastActivity, nowMillis)))
        val since = when {
            runs.isEmpty() -> t.refreshedAtMillis ?: 0L
            quiet -> lastActivity ?: runs.maxOf { it.member.sinceMillis }
            urgentRank == OPEN_WORK_RANK -> runs.maxOf { it.member.sinceMillis }
            else -> runs.filter { rankOf(it.member.state) == urgentRank }.minOf { it.member.sinceMillis }
        }
        // Open means a session Lunamux lists whose pane is in the layout, so Jump has somewhere to go.
        val open = runs.filter { r -> r.session != null && r.session.state != ENDED && panes[r.session.leafId] != null }
        val started = t.startedAtMillis ?: runs.mapNotNull { it.session?.let(::startedOf) }.minOrNull() ?: since
        val recency = runs.maxOfOrNull { r -> maxOf(r.member.sinceMillis, r.session?.let(::activityOf) ?: 0L) }
            ?: t.refreshedAtMillis ?: 0L
        // NOW is the open tabs: a task is in it only while one of its sessions is in a pane the layout
        // holds. A task waiting on the person is the one exception, so a question is never filed away.
        val inNow = !ended && !settled && (urgentRank == 0 || open.isNotEmpty())
        val graced = (ended || settled) && inGrace(recency, nowMillis)
        val (enter, target) = enterOf(t, runs, open, byId)
        return TaskUnit(t, runs, since, recency, graced, inNow, started, enter, target)
    }

    /**
     * What ↵ does on a task's row, and on which session.
     *
     * A task with an own session opens that session: its pane, or a peek when the pane is in a
     * background tab; once the session is gone the task's `open` action, else a resume. A task
     * without one jumps to a live session in a listed tab (the one waiting longest on the person,
     * else the busiest), else runs its `open` action, else peeks at a live background session,
     * else resumes the newest session it has a record of, else offers nothing. A background
     * session is never chosen while a listed one is live.
     *
     * @param t the task.
     * @param runs its sessions.
     * @param open the sessions whose pane is in the layout.
     * @param byId all session summaries by record id.
     * @return what ↵ does and the session it acts on (null for `open` and for nothing).
     */
    private fun enterOf(
        t: AgentTask,
        runs: List<Run>,
        open: List<Run>,
        byId: Map<String, AgentSessionSummary>,
    ): Pair<Enter, AgentSessionSummary?> {
        val own = t.leadRecordId?.let(byId::get)
        if (own != null) {
            val ownOpen = open.any { it.session?.id == own.id }
            return when {
                ownOpen && own.background -> Enter.PEEK to own
                ownOpen -> Enter.JUMP to own
                t.hasOpen -> Enter.OPEN to null
                else -> Enter.RESUME to own
            }
        }
        // The session that most needs the person comes first: the ◆ that has waited longest, then the
        // working session with the freshest sign of life, then any other open one.
        val urgency = compareBy<Run>(
            { rankOf(it.member.state) },
            { if (it.member.state == NEEDS_YOU) it.member.sinceMillis else -(it.session!!.lastActivityAt ?: it.session.stateSince) },
        )
        val listed = open.filter { !it.session!!.background }.minWithOrNull(urgency)
        val hidden = open.filter { it.session!!.background }.minWithOrNull(urgency)
        val newest = runs.mapNotNull { it.session }.maxByOrNull(::activityOf)
        return when {
            listed != null -> Enter.JUMP to listed.session
            t.hasOpen -> Enter.OPEN to null
            hidden != null -> Enter.PEEK to hidden.session
            newest != null -> Enter.RESUME to newest
            else -> Enter.NONE to null
        }
    }

    private fun rankOf(state: String) = when (state) {
        NEEDS_YOU -> 0
        WORKING -> 1
        else -> OPEN_WORK_RANK
    }

    /**
     * Whether a member reads done: its session ended, or a task file's word for it says so while
     * it neither works nor needs the person.
     *
     * @param m the task member.
     * @return true when nothing is left for it to do.
     */
    private fun isDone(m: AgentTaskMember): Boolean = when (m.state) {
        ENDED -> true
        NEEDS_YOU, WORKING -> false
        else -> m.label?.trim()?.lowercase() in DONE_LABELS
    }

    /**
     * The newest sign of life of a hosted session: when it ended, else its last activity, else the
     * time it entered its state.
     *
     * Called wherever a session's recency matters: whether an idle or just-ended session is still
     * inside the NOW grace, where it ranks there, which EARLIER band it falls in and the age of a
     * lone session's row, so the heading and the age are read from one moment.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun activityOf(s: AgentSessionSummary): Long =
        if (s.state == ENDED) s.endedAt ?: s.stateSince else s.lastActivityAt ?: s.stateSince

    /**
     * The repository a task's row shows: the one its file names, else the repository every one of
     * its hosted sessions runs in. A task the agents made has no file, so without this its
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
     * What a member is saying right now: the question a member that waits on the person asks, or
     * the last tool line of one that works, as the task file or the hosted session gives it.
     *
     * @param run the member.
     * @return the text, or null when there is none.
     */
    private fun lineOf(run: Run): String? {
        val s = run.session
        val fromFile = run.member.line?.trim()?.takeIf { it.isNotEmpty() }
        if (run.member.state == NEEDS_YOU) {
            return fromFile ?: AgentSessionText.previewOf(s?.question) ?: s?.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        }
        return fromFile ?: s?.nowLine?.trim()?.takeIf { it.isNotEmpty() }
    }

    /**
     * The row of one task.
     *
     * Its state word counts the sessions that wait on the person or move, background ones
     * included, and its detail is the task's status or, failing that, what a session is saying.
     * The row opens what [TaskUnit.enter] says.
     *
     * Called by [build] for every task in NOW and EARLIER, and as the base of a search result.
     *
     * @param u the task unit.
     * @param c the layout, the clock and the last answers of the server.
     * @return the row, with id `task:<id>`.
     */
    private fun taskRow(u: TaskUnit, c: Context): Row {
        val t = u.task
        val id = "$TASK_PREFIX${t.id}"
        val moving = u.runs.filter { runMoving(it, t.refreshedAtMillis, c.nowMillis) }
        val waiting = u.runs.filter { it.member.state == NEEDS_YOU }.minByOrNull { it.member.sinceMillis }
        // Something that moves is working, wherever the task is filed; a task that only waits or rests reads done in NOW and finished in EARLIER.
        val stateWord = when {
            waiting != null -> STATE_NEEDS_YOU
            moving.size >= 2 -> "${moving.size} $STATE_WORKING"
            moving.size == 1 -> STATE_WORKING
            u.inEarlier -> STATE_FINISHED
            else -> STATE_DONE
        }
        val status = t.status?.trim()?.takeIf { it.isNotEmpty() }
        val detail = status
            ?: waiting?.let(::lineOf)?.let(::quoted)
            ?: moving.sortedByDescending { workingActivity(it, t.refreshedAtMillis) ?: 0L }.firstNotNullOfOrNull(::lineOf)
            ?: ""
        val target = u.target
        val live = target != null && c.live(target)
        val single = u.runs.singleOrNull()?.session
        val folder = u.runs.mapNotNull { it.session }.map(::folderOf).distinct().singleOrNull()
        val error = c.errorOf(id)
        val tab = if (u.enter == Enter.JUMP) tabNote(c.panes[target?.leafId], c.config) else null
        return Row(
            id = id,
            taskId = t.id,
            title = t.title,
            where = headerRepo(u) ?: folder,
            marker = if (waiting != null) MARKER else "",
            stateWord = stateWord,
            detail = detail,
            extra = extraOf(
                c.noteOf(target?.id),
                NOTHING_TO_JUMP.takeIf { u.enter == Enter.NONE },
                error,
                tab,
            ),
            tab = tab.orEmpty(),
            sinceMillis = if (u.inEarlier) u.recency else u.since,
            enter = u.enter,
            recordId = target?.id,
            isLive = live,
            background = live && target!!.background,
            canClose = single != null && c.live(single),
            error = error,
        )
    }

    /**
     * The row of a session that is a task of one.
     *
     * @param s the session.
     * @param earlier whether the row is drawn in EARLIER, where a session that rests reads
     *   "finished" rather than "done".
     * @param c the layout, the clock and the last answers of the server.
     * @param taskTitle when the session belongs to a task that has no unit (a search result found
     *   it in a task older than the pushed list), the task's title: the row then stands for that
     *   task, with id `task:<id>`.
     * @return the row.
     */
    private fun sessionRow(s: AgentSessionSummary, earlier: Boolean, c: Context, taskTitle: String? = null): Row {
        val live = c.live(s)
        val id = if (taskTitle != null) "$TASK_PREFIX${s.taskId}" else s.id
        val needs = s.state == NEEDS_YOU
        val moving = isMoving(s.state == WORKING, null, s.lastActivityAt ?: s.stateSince, c.nowMillis)
        val nowLine = s.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        val detail = when {
            needs -> (AgentSessionText.previewOf(s.question) ?: nowLine)?.let(::quoted) ?: ""
            moving -> nowLine ?: ""
            else -> ""
        }
        val enter = when {
            live && s.background -> Enter.PEEK
            live -> Enter.JUMP
            else -> Enter.RESUME
        }
        val error = c.errorOf(id)
        val tab = if (enter == Enter.JUMP) tabNote(c.panes[s.leafId], c.config) else null
        return Row(
            id = id,
            taskId = s.taskId.takeIf { taskTitle != null },
            title = taskTitle ?: titleOf(s),
            where = s.repo ?: folderOf(s),
            marker = if (needs) MARKER else "",
            stateWord = when {
                needs -> STATE_NEEDS_YOU
                moving -> STATE_WORKING
                earlier -> STATE_FINISHED
                else -> STATE_DONE
            },
            detail = detail,
            extra = extraOf(c.noteOf(s.id), error, tab),
            tab = tab.orEmpty(),
            sinceMillis = sinceOf(s),
            enter = enter,
            recordId = s.id,
            isLive = live,
            background = live && s.background,
            canClose = live,
            error = error,
        )
    }

    /**
     * Turn the row of a task (or of a lone session) into a search result: the row keeps its state
     * word, folder and age, and opens the session that matched; the match itself is carried in
     * [Row.field], [Row.snippet] and the highlight offsets, and the extra line says where it was
     * found and in which session.
     *
     * @param base the row of the task the matched session belongs to.
     * @param m the server's match.
     * @param s the matched session, as the layout knows it now.
     * @param c the layout, the clock and the last answers of the server.
     * @return the search result row.
     */
    private fun matchRow(base: Row, m: AgentSessionMatch, s: AgentSessionSummary, c: Context): Row {
        val live = c.live(s)
        val enter = when {
            live && s.background -> Enter.PEEK
            live -> Enter.JUMP
            else -> Enter.RESUME
        }
        val sessionTitle = titleOf(s)
        // A live title can have changed since the server found the match in it.
        val titleCurrent = m.field != "title" || sessionTitle == (m.summary.title ?: sessionTitle)
        val inTitle = when (m.field) {
            "task" -> true
            "title" -> sessionTitle == base.title
            else -> false
        }
        val snippet = when {
            inTitle -> null
            m.field == "title" -> sessionTitle
            else -> m.snippet
        }
        val highlight = titleCurrent && (inTitle || snippet != null)
        val where = "in ${fieldLabel(m.field)}" + if (sessionTitle != base.title && m.field != "task") " of $sessionTitle" else ""
        val note = m.resumeNote ?: c.noteOf(s.id)
        val tab = if (enter == Enter.JUMP) tabNote(c.panes[s.leafId], c.config) else null
        return base.copy(
            detail = snippet ?: base.detail,
            extra = extraOf(note, base.error, where, tab),
            tab = tab.orEmpty(),
            enter = enter,
            recordId = s.id,
            isLive = live,
            background = live && s.background,
            canClose = base.canClose,
            field = m.field,
            snippet = snippet,
            matchStart = if (highlight) m.start else 0,
            matchLength = if (highlight) m.length else 0,
        )
    }

    /**
     * How a search names the place it found the query.
     *
     * @param field the server's field name.
     * @return the words that follow "in" on the extra line.
     */
    private fun fieldLabel(field: String): String = when (field) {
        "title" -> "title"
        "task" -> "task title"
        "prompt" -> "first prompt"
        "branch" -> "branch"
        "links" -> "links"
        "transcript" -> "transcript"
        "screen" -> "last screen"
        else -> field
    }

    /** Join the parts of an extra line that have something to say, in the order given. */
    private fun extraOf(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

    /** [text] in double quotes, the way a question reads in a row's detail. */
    private fun quoted(text: String): String = "\"$text\""

    /**
     * The words that say which tab a session sits in, for the end of the selected row's extra line:
     * "tab 2", or "tab 2 in Work" when the tab is in another world than the one showing. A
     * session in a background tab sits in no listed tab and gets none.
     *
     * @param where the session's pane location, or null when it has none.
     * @param config the layout.
     * @return the words, or null.
     */
    private fun tabNote(where: PaneLocation?, config: WindowConfig?): String? {
        val number = where?.tabNumber ?: return null
        val shown = config?.activeWorldOrNull()?.id
        return if (where.worldId != null && where.worldId != shown) "tab $number in ${where.worldName}" else "tab $number"
    }

    /** What closing [s] would interrupt or leave behind, one sentence each. */
    private fun closeWarnings(s: AgentSessionSummary, inspection: WindowEnvelope.AgentSessionInspection?): List<String> {
        val out = mutableListOf<String>()
        if (s.state == WORKING) {
            out += "It is working right now" + (s.nowLine?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
        }
        if (s.state == NEEDS_YOU) {
            out += "It is waiting for you" + ((AgentSessionText.previewOf(s.question) ?: s.nowLine?.takeIf { it.isNotBlank() })?.let { ": $it" } ?: ".")
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

    /**
     * A session's display title: what the work is, never where it ran.
     *
     * The session's own title when it names the work, else the first words of its first prompt,
     * else the agent's name. The branch, the folder, `~`, `HEAD` and run ids say where a session ran, not what it
     * is for, so they are never used as a title.
     *
     * Called for every lone session's row, for a task's sessions and for a search.
     *
     * @param s the session.
     * @return the title, never blank.
     * @see AgentSessionText.isMeaningfulTitle
     * @see AgentSessionText.titleFromPrompt
     */
    private fun titleOf(s: AgentSessionSummary): String {
        s.title?.trim()?.takeIf { AgentSessionText.isMeaningfulTitle(it, s.branch, s.repo, folderOf(s)) }?.let { return it }
        return AgentSessionText.titleFromPrompt(s.firstPrompt)
            ?: "${s.agent.replaceFirstChar { it.uppercase() }} session"
    }

    /**
     * The folder a session runs in, as the overview's folder column shows it when there is no
     * repository: "~" for the user's home folder, else the last path segment, else null.
     *
     * Called by [sessionRow] and [taskRow] for the column and by [titleOf] for the title fallback.
     * Home is named "~" so a chat started there is not shown under the user's name.
     *
     * @param s the session.
     * @return the label, or null when the session has no folder.
     */
    private fun folderOf(s: AgentSessionSummary): String? =
        if (s.cwdHome) "~"
        else s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    private fun paneLocations(config: WindowConfig): Map<String?, PaneLocation> {
        val out = HashMap<String?, PaneLocation>()
        fun add(worldId: String?, worldName: String?, tabs: List<se.soderbjorn.lunamux.TabConfig>) {
            var listed = 0
            tabs.forEach { tab ->
                val number = if (tab.isListed) ++listed else null
                tab.panes.forEach { out[it.leaf.id] = PaneLocation(worldId, worldName, tab.id, number, it.leaf) }
            }
        }
        if (config.worlds.isEmpty()) add(null, null, config.tabs) else config.worlds.forEach { add(it.id, it.name, it.tabs) }
        return out
    }

    private fun locate(config: WindowConfig, leafId: String): PaneLocation? = paneLocations(config)[leafId]

    companion object {
        /** The state word of a task that has a session waiting on the person. */
        const val STATE_NEEDS_YOU = "needs you"

        /** The state word of a task with one moving session; with several it reads "N working". */
        const val STATE_WORKING = "working"

        /** The state word of a task in NOW with nothing moving and nothing waiting. */
        const val STATE_DONE = "done"

        /** The state word of a task in EARLIER with nothing moving. */
        const val STATE_FINISHED = "finished"

        /** The mark in the first column of a row that needs the person. */
        const val MARKER = "◆"

        /** What the selected row's extra line says when ↵ has no session Lunamux can open. */
        const val NOTHING_TO_JUMP = "nothing to jump to: this session is not in a Lunamux pane"

        /**
         * How long an idle session stays in NOW after its last activity, and an ended one after
         * it ended, before it moves to EARLIER. It keeps "going on now" from swapping with
         * "earlier" every time a chat ends a turn, and keeps a row aged in minutes out of a band
         * headed in days.
         */
        const val IDLE_GRACE_MS = 10L * 60_000L

        /**
         * Whether a session idle since [activityMillis] is still inside [IDLE_GRACE_MS].
         * The grace ends exactly [IDLE_GRACE_MS] after the activity.
         *
         * Called by [build] for every idle or ended session on each state build.
         *
         * @param activityMillis epoch millis of the session's last activity.
         * @param nowMillis the current epoch millis.
         * @return true while less than [IDLE_GRACE_MS] has passed.
         */
        fun inGrace(activityMillis: Long, nowMillis: Long): Boolean = nowMillis - activityMillis < IDLE_GRACE_MS

        /** How many of the newest EARLIER rows show before "+ N more". */
        const val EARLIER_LIMIT = 5

        /** How old a row's newest activity may be and still sit in EARLIER's first band, "Last few days": 3 days. */
        const val RECENT_MS = 3L * 24 * 60 * 60_000L

        /**
         * How old a row's newest activity may be and still be listed in EARLIER at all, in the
         * second band, "Last two weeks": 14 days. The server keeps at least this much history, so
         * a row listed here is never missing from the pushed list.
         */
        const val WINDOW_MS = 14L * 24 * 60 * 60_000L

        /**
         * Whether activity at [recencyMillis] belongs to the first EARLIER band. The edge is
         * inclusive: a row exactly [RECENT_MS] old is still in the first band.
         *
         * Called by [build] for every EARLIER row.
         *
         * @param recencyMillis epoch millis of the row's newest activity.
         * @param nowMillis the current epoch millis.
         * @return true when the row is at most [RECENT_MS] old.
         */
        fun inRecent(recencyMillis: Long, nowMillis: Long): Boolean = nowMillis - recencyMillis <= RECENT_MS

        /**
         * Whether activity at [recencyMillis] is listed in EARLIER at all. The edge is inclusive: a
         * row exactly [WINDOW_MS] old is listed, one millisecond older is not.
         *
         * @param recencyMillis epoch millis of the row's newest activity.
         * @param nowMillis the current epoch millis.
         * @return true when the row is at most [WINDOW_MS] old.
         */
        fun inWindow(recencyMillis: Long, nowMillis: Long): Boolean = nowMillis - recencyMillis <= WINDOW_MS

        /** The fewest characters that start a search over every session. */
        const val MIN_QUERY = 2

        /**
         * How long a working session may show no sign of life before the overview stops
         * counting it as moving. Hook events arrive around every tool call, so five minutes
         * without one means a hung session or one inside a single very long tool call.
         */
        const val QUIET_AFTER_MS = 5L * 60_000L

        /**
         * Whether a working session has gone quiet: nothing has been heard from it for
         * [QUIET_AFTER_MS]. A pure function of the clock so the view and the tests agree.
         *
         * @param activityMillis epoch millis of the session's last sign of life.
         * @param nowMillis the current epoch millis.
         * @return true when the gap is at least [QUIET_AFTER_MS].
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
         * A pure function of the clock, like [isQuiet], so a periodic redraw can flip a row with no
         * new input from the server.
         *
         * @param refreshedMillis epoch millis the task file was last refreshed.
         * @param nowMillis the current epoch millis.
         * @return true when the gap is at least [STALE_AFTER_MS].
         */
        fun isStale(refreshedMillis: Long, nowMillis: Long): Boolean = nowMillis - refreshedMillis >= STALE_AFTER_MS

        /**
         * "2 need you" / "1 needs you": the plural form of the toolbar chip.
         *
         * @param n how many need the user.
         * @return the count and the right verb.
         */
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"

        /**
         * The prefix of a working session's status when its sign of life is old: "stale 25m · " for a
         * member whose task file has not been refreshed for [STALE_AFTER_MS], else "quiet 6m · " once
         * [QUIET_AFTER_MS] has passed; empty otherwise.
         *
         * Called by [isMoving], so the rule that decides what counts as working lives in one place.
         *
         * @param working whether the session's state is working; any other state has no prefix.
         * @param fileRefreshedMillis epoch millis the declaring task file was last refreshed, for a
         *   member Lunamux does not host; null otherwise.
         * @param activityMillis epoch millis of the last sign of life; null when there is none.
         * @param nowMillis the current epoch millis.
         * @return "stale 25m · ", "quiet 6m · " or an empty string.
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
         * is what the toolbar chip and a row's "N working" count.
         *
         * Called by the view model when it counts working sessions, for lone sessions and for task members.
         *
         * @param working whether the session's state is working.
         * @param fileRefreshedMillis see [agePrefixOf].
         * @param activityMillis see [agePrefixOf].
         * @param nowMillis the current epoch millis.
         * @return true for a working session with a recent sign of life.
         */
        fun isMoving(working: Boolean, fileRefreshedMillis: Long?, activityMillis: Long?, nowMillis: Long): Boolean =
            working && agePrefixOf(true, fileRefreshedMillis, activityMillis, nowMillis).isEmpty()

        private const val TASK_PREFIX = "task:"
        private const val OPEN_WORK_RANK = 2
        private const val IDLE = "idle"
        private val DONE_LABELS = setOf("done", "ready")
        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
    }
}
