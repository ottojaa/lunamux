/**
 * Backing ViewModel for the agents overview: the toolbar chip and the
 * ⌘⇧A overlay that list every agentic-CLI session (Claude Code, Codex,
 * Gemini) Lunamux knows about.
 *
 * The server pushes one list of session summaries; this ViewModel turns it
 * into the two sections the overlay shows (NOW and EARLIER), the chip
 * counts and the keyboard selection. Typing two or more characters switches the
 * overlay to the server's search over every session ever recorded (titles,
 * first prompts, branches, links and transcripts). The ViewModel carries out
 * what a row offers: jump to its pane, resume it in a new tab, peek at its
 * screen, and close it after a confirmation that names what would be lost.
 * The view only renders [State] and forwards key presses.
 *
 * Every row is one line of a single grid whose second column is the state glyph. Groups declared
 * by group files are expansion panels: a header row whose counts say what its runs are doing,
 * followed by the runs that need the user, or by every run, most urgent first, once the panel is
 * expanded. A group stays in NOW while any of its runs has not ended, ranked beside single
 * sessions by its most urgent run, and its header opens the group's lead session. A run Lunamux
 * does not host opens with `claude attach <session id>` in a new tab; the id comes from the group
 * file and the server builds the command. The lead session is a row of its box only while it
 * needs the user or works; otherwise the header's "open" is how to reach it.
 *
 * The selection is a row id, not an index, so it stays on its row while rows appear and
 * disappear, and it opens on the ◆ that has waited longest.
 *
 * @see se.soderbjorn.lunamux.AgentSessionSummary
 * @see se.soderbjorn.lunamux.AgentGroup
 * @see WindowStateRepository.agentSessions
 * @see WindowStateRepository.agentGroups
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
import se.soderbjorn.lunamux.AgentGroup
import se.soderbjorn.lunamux.AgentGroupMember
import se.soderbjorn.lunamux.AgentSessionSummary
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
     * @property title the display title: the session's own title (the server keeps a
     *   name the user gave the pane, even after the pane closes), else the branch,
     *   else the folder name.
     * @property links ticket keys found in the branch or title.
     * @property repo repository folder name, or null.
     * @property nowLine the last tool line, or null.
     * @property question what a `needs_you` row is asking, or null.
     * @property sinceMillis epoch millis the row entered its state (when it ended, for ended rows);
     *   the view turns it into an age.
     * @property isLive whether the agent's pane is still open, so ↵ jumps to it.
     * @property canResume whether ↵ opens a new tab for it (an ended row).
     * @property actionLabel the word of the ↵ action: `jump`, `resume` or `open folder` for a
     *   session; `jump`, `resume` or `open` for a run; `open` for a group header; empty for a run
     *   Lunamux has no session for and the file gave no session id, which has nothing to open.
     * @property error a message from the last failed resume of this row, or null.
     * @property canClose whether × and ⌘⌫ can close it: it has an open pane.
     * @property field in a search result, where the query was found: `title`, `prompt`,
     *   `branch`, `links`, `transcript` or `screen`; null outside a search.
     * @property snippet in a search result, the text around the match (null for a title match,
     *   where the title itself is highlighted).
     * @property matchStart where the match begins, inside [snippet] or, for a title match, [title].
     * @property matchLength how many characters the match covers; 0 when there is nothing to highlight.
     * @property resumeNote what Resume will do when it is not the plain case (for example
     *   "Worktree removed. Resume recreates it from branch x."); shown for search results and the
     *   selected row, null otherwise.
     * @property kind what the row is: a session (or group run) or a group header. Only
     *   [RowKind.SESSION] rows exist in a search.
     * @property groupId the group a header or run belongs to; null for a single session.
     * @property unhosted true for a group run Lunamux does not host (it has no session record);
     *   it shows what the group file says, and ↵ opens it with `claude attach` when [sessionId] is set.
     * @property label a group run's status word from the group file ("in review", "done"), shown
     *   before [status]; null for a run that works or needs the user, whose live line says it all.
     * @property activityMillis for a working row, epoch millis of its last sign of life: the newer
     *   of the session's last activity (when Lunamux hosts it) and the time its group file was last
     *   refreshed (when the file declares the run). Null for a row that is not working. The view
     *   animates the working glyph only while [isWorkingLive] says so.
     * @property fileRefreshedMillis for a group run Lunamux does not host, epoch millis the group
     *   file that declares it was last refreshed; null for every other row. The view marks the row
     *   stale when [isStale] says so.
     * @property glyph which state glyph column B draws; the working glyph still needs [isWorkingLive]
     *   to say whether it moves.
     * @property status the one-line status for column E, without [label]: the question of a ◆ row,
     *   the last tool line of a working row, what an idle or closed row last said, falling back to
     *   "❯ " plus its first prompt. Never blank on a row without a [label].
     * @property where column D: the repository of a session or header, or the pull request ("#22")
     *   of a run; null when there is none.
     * @property sessionId for a run Lunamux does not host, the id the group file gave for it, which
     *   `claude attach` takes; null otherwise.
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
        val groupId: String? = null,
        val unhosted: Boolean = false,
        val label: String? = null,
        val activityMillis: Long? = null,
        val fileRefreshedMillis: Long? = null,
        val glyph: Glyph = Glyph.NONE,
        val status: String = "",
        val where: String? = repo,
        val sessionId: String? = null,
    )

    /** What a [Row] stands for. */
    enum class RowKind {
        /** A session, or a group run (then [Row.groupId] is set). */
        SESSION,

        /** The header of a group. [Row.id] is `group:<id>`. */
        GROUP,
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

        /** Nothing: a group header (it shows the working glyph through [Group.activityMillis]). */
        NONE,
    }

    /** Which key hints the footer shows; chosen by the selected row. */
    enum class Footer {
        /** A live session or a run in a Lunamux pane. */
        LIVE,

        /** A closed session. */
        CLOSED,

        /** A collapsed group header. */
        GROUP_COLLAPSED,

        /** An expanded group header. */
        GROUP_EXPANDED,

        /** A run Lunamux has no pane for and the file gave no session id for. */
        NO_SESSION,

        /** A run Lunamux has no pane for but `claude attach` can open. */
        RUN_ATTACH,

        /** Nothing is selected. */
        NONE,
    }

    /**
     * What the view needs to draw one group's header.
     *
     * @property id the group's id.
     * @property title the group's name.
     * @property repo repository name, or null.
     * @property needYouCount runs that need the user; drawn highlighted before [summary].
     * @property workingCount runs at work.
     * @property summary the rest of the header's count line, without the needs-you part
     *   ("1 working · 1 in review · 2 done"): in the fixed order working, in review, queued,
     *   other labels as the file gave them, paused, done, with empty counts left out; empty when
     *   every run needs the user.
     * @property expanded whether every run shows rather than only those that need the user.
     * @property leadLive whether the lead session is open, so ↵ on the header jumps to it.
     * @property activityMillis the latest sign of life among the working runs (see
     *   [Row.activityMillis]); null when none works, so the header's working glyph is empty. The
     *   header draws the moving glyph while [isWorkingLive] says that stamp is fresh.
     */
    data class Group(
        val id: String,
        val title: String,
        val repo: String?,
        val needYouCount: Int,
        val workingCount: Int,
        val summary: String,
        val expanded: Boolean,
        val leadLive: Boolean,
        val activityMillis: Long? = null,
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
     *   then [matches] instead of [now] and [earlier].
     * @property matches the server's answer for the current query, best match first; empty until it arrives.
     * @property searching true while a [matchMode] answer for the current query is still on its way.
     * @property now needs-you rows first, then working rows, longest waiting first.
     * @property earlier idle and ended rows, newest first, cut to [EARLIER_LIMIT] until "More" is pressed.
     * @property earlierHidden how many earlier rows "More" would reveal.
     * @property needYouCount rows asking for the user; the chip's first number.
     * @property workingCount rows at work; the chip's second number.
     * @property selectedId the id of the highlighted row, or null when there are no rows.
     * @property peek the screen popover, or null while it is closed.
     * @property confirm the close confirmation, or null while none is asked.
     * @property groups the groups drawn in [now] and [earlier], by group id.
     * @property hasSessions whether any agent session or group has ever been recorded, which is
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
        val earlier: List<Row> = emptyList(),
        val earlierHidden: Int = 0,
        val needYouCount: Int = 0,
        val workingCount: Int = 0,
        val selectedId: String? = null,
        val peek: Peek? = null,
        val confirm: Confirm? = null,
        val groups: Map<String, Group> = emptyMap(),
        val hasSessions: Boolean = false,
        val footer: Footer = Footer.NONE,
    ) {
        /** The rows in the order the keyboard walks them: [matches], or [now] then [earlier]. */
        val rows: List<Row> get() = if (matchMode) matches else now + earlier

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
        val expandedGroups: Set<String> = emptySet(),
    )

    /** Everything [build] reads, taken from [windowState] and [ui] in one go. */
    private data class Inputs(
        val sessions: List<AgentSessionSummary>,
        val groups: List<AgentGroup>,
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
            windowState.agentGroups,
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
                v[1] as List<AgentGroup>,
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
        windowState.agentGroups.value,
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
            ui.value = Ui(expandedGroups = current.expandedGroups)
        }
    }

    /**
     * Show the overlay with an empty search and the ◆ row that has waited longest selected (the
     * first row when nothing needs the user). Groups the user expanded stay expanded. Called by
     * ⌘⇧A and the chip.
     */
    fun open() {
        windowState.clearAgentSessionError()
        inspected = null
        val fresh = Ui(open = true, expandedGroups = ui.value.expandedGroups)
        val first = build(inputs().copy(ui = fresh))
        ui.value = fresh.copy(selectedId = first.selectedId, selectedHint = maxOf(first.selected, 0))
    }

    /**
     * Hide the overlay and forget the search. Groups the user expanded stay expanded for as long
     * as the app runs. Called by esc and after a jump or resume.
     */
    fun close() {
        windowState.clearAgentSessionError()
        inspected = null
        ui.value = Ui(expandedGroups = ui.value.expandedGroups)
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
     * Called by the view when a header is clicked, before it toggles the group.
     *
     * @param rowId the [Row.id] to select.
     */
    fun select(rowId: String) {
        val rows = state.rows
        val index = rows.indexOfFirst { it.id == rowId }
        if (index >= 0) ui.update { it.copy(selectedId = rowId, selectedHint = index, peekOpen = false, confirmRowId = null) }
    }

    /** Reveal the earlier rows beyond the first [EARLIER_LIMIT]. Called by the "More" link. */
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
        val recordId = recordIdOf(row, windowState.agentGroups.value) ?: return
        val key = "$recordId/${row.isLive}"
        if (!force && inspected == key) return
        inspected = key
        send(WindowCommand.InspectAgentSession(recordId))
    }

    /**
     * Open the peek popover on the selected row and fetch its screen. Called for →
     * (with the caret at the end of the search text). On a group header it peeks the lead
     * session, and does nothing when the lead has no record.
     */
    suspend fun peek() {
        val current = state
        val row = current.selectedRow ?: return
        if (recordIdOf(row, windowState.agentGroups.value) == null) return
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
     * Do [row]'s action: jump to a live pane, resume an ended session, open a group's lead
     * session, or attach to a run Lunamux does not host. Does nothing for a run with no session.
     *
     * Called when a row is clicked and for ↵ (through [activateSelected]).
     *
     * @param row the row to act on.
     */
    suspend fun activate(row: Row) {
        when {
            row.kind == RowKind.GROUP -> openLead(row)
            row.unhosted -> openMember(row)
            row.isLive -> jump(row)
            row.canResume -> resume(row)
        }
    }

    /**
     * Expand a collapsed group or collapse an expanded one, keeping the highlight on its header.
     *
     * Called by a click on a group header (outside its "open"), and by [toggleSelected].
     *
     * @param groupId the group to toggle.
     */
    fun toggleGroup(groupId: String) = setExpanded(groupId, groupId !in ui.value.expandedGroups)

    private fun setExpanded(groupId: String, expanded: Boolean) {
        val header = state.rows.indexOfFirst { it.kind == RowKind.GROUP && it.groupId == groupId }
        ui.update {
            it.copy(
                expandedGroups = if (expanded) it.expandedGroups + groupId else it.expandedGroups - groupId,
                selectedId = if (header >= 0) "$GROUP_PREFIX$groupId" else it.selectedId,
                selectedHint = if (header >= 0) header else it.selectedHint,
                peekOpen = false,
                confirmRowId = null,
            )
        }
    }

    /**
     * Step into the selected row: on a group header expand it, or, when it already is expanded,
     * select its first run; on a session or run peek at its screen.
     *
     * Called for → (with the caret at the end of the search text).
     */
    suspend fun stepIn() {
        val current = state
        val row = current.selectedRow ?: return
        if (row.kind != RowKind.GROUP) return peek()
        val groupId = row.groupId ?: return
        if (current.groups[groupId]?.expanded != true) return setExpanded(groupId, true)
        val index = current.rows.indexOfFirst { it.id == row.id }
        current.rows.getOrNull(index + 1)?.takeIf { it.groupId == groupId && it.kind == RowKind.SESSION }?.let { select(it.id) }
    }

    /**
     * Step out of the selected row: close an open peek; otherwise on a run select its header, and
     * on an expanded header collapse it.
     *
     * Called for ← (with the caret at the start of the search text).
     */
    fun stepOut() {
        val current = state
        val row = current.selectedRow ?: return
        when {
            current.peek != null -> closePeek()
            row.kind == RowKind.SESSION && row.groupId != null -> select("$GROUP_PREFIX${row.groupId}")
            row.kind == RowKind.GROUP && current.groups[row.groupId]?.expanded == true -> setExpanded(row.groupId!!, false)
        }
    }

    /**
     * Toggle what the selected row holds: a group header's runs, or a session's peek. Does
     * nothing while a search is typed, because a space then belongs to the query.
     *
     * Called for space.
     */
    suspend fun toggleSelected() {
        val current = state
        if (current.query.isNotEmpty()) return
        val row = current.selectedRow ?: return
        when {
            row.kind == RowKind.GROUP -> row.groupId?.let(::toggleGroup)
            current.peek != null -> closePeek()
            else -> peek()
        }
    }

    /**
     * Open a group's lead session: jump to it when it is open, else ask the server to start it
     * in a new tab. The overlay stays open until the new tab is active or the server says why
     * it could not, exactly as for [resume].
     *
     * Called for ↵ on a group header and for a click on its "open".
     *
     * @param row the header row; its [Row.groupId] names the group.
     */
    suspend fun openLead(row: Row) {
        val groupId = row.groupId ?: return
        val leadRecord = windowState.agentGroups.value.firstOrNull { it.id == groupId }?.lead?.recordId
        if (leadRecord != null && state.groups[groupId]?.leadLive == true) {
            jumpToRecord(leadRecord)
            return
        }
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.OpenAgentGroupLead(groupId))
    }

    /**
     * Open a run Lunamux does not host in a new tab with `claude attach`. Only the group and the
     * session id go to the server, which checks the id against the group file and builds the
     * command. The overlay stays open until the new tab is active or the server says why it
     * could not. Does nothing for a run whose file gave no session id.
     *
     * Called by [activate] for ↵ or a click on such a run.
     *
     * @param row the run; its [Row.groupId] and [Row.sessionId] name what to attach to.
     */
    suspend fun openMember(row: Row) {
        val groupId = row.groupId ?: return
        val sessionId = row.sessionId ?: return
        windowState.clearAgentSessionError()
        ui.update {
            it.copy(resumingId = row.id, resumeFromTab = windowState.config.value?.effectiveActiveTabId)
        }
        send(WindowCommand.OpenAgentGroupMember(groupId, sessionId))
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

    /** One run of a group, paired with the session summary it matched (null when Lunamux does not host it). */
    private class Run(val member: AgentGroupMember, val session: AgentSessionSummary?, val fileIndex: Int)

    /** A group with its runs, ready to become rows. */
    private class GroupUnit(
        val group: AgentGroup,
        val runs: List<Run>,
        val rank: Int,
        val since: Long,
        val leadLive: Boolean,
        val leadId: String?,
        val ended: Boolean,
    )

    /** How a run is counted and ordered: the bucket a header count and the expanded order use. */
    private class Bucket(val order: Int, val name: String)

    private fun build(input: Inputs): State {
        val (sessions, groupFiles, config, error, matches, inspection, ui) = input
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
            )
        }

        val units = groupFiles.map { g -> unitOf(g, byId, panes) }
        // A session a group run matched, or the open lead, is drawn inside its group, not again as a single.
        val claimed = units.flatMap { u -> u.runs.mapNotNull { it.session?.id } }.toSet() +
            units.mapNotNull { u -> u.group.lead.recordId?.takeIf { u.leadLive && u.group.lead.named } }
        val unclaimed = sessions.filter { it.id !in claimed }
            .map { s -> s to toRow(s, panes[s.leafId], resumeError(s.id)) }
        val singles = unclaimed.map { (_, row) -> row }

        // A group stays in NOW until all its runs have ended; it is ranked beside the single sessions
        // that need the user or work, by its most urgent run.
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
            units.filter { !it.ended }
                .forEach { add(Placed(it.rank, it.since, groupRows(it, groupFiles, panes, ui, resumeError))) }
        }.sortedWith(
            compareBy<Placed> { it.rank }.thenBy { if (it.rank >= OPEN_WORK_RANK) -it.since else it.since },
        )
        val now = urgent.flatMap { it.rows }

        // EARLIER: open idle singles first, then finished programmes and closed sessions, newest first.
        class Earlier(val since: Long, val rows: List<Row>)
        val gracedIds = graced.mapTo(HashSet()) { it.id }
        val idleSingles = singles.filter { it.state != NEEDS_YOU && it.state != WORKING && it.state != ENDED && it.id !in gracedIds }
            .sortedByDescending { it.sinceMillis }
        val closedUnits = (
            singles.filter { it.state == ENDED }.map { Earlier(it.sinceMillis, listOf(it)) } +
                units.filter { it.ended }.map { Earlier(it.since, groupRows(it, groupFiles, panes, ui, resumeError)) }
            ).sortedByDescending { it.since }
        val earlierUnits = idleSingles.map { Earlier(it.sinceMillis, listOf(it)) } + closedUnits
        val shownEarlier = if (ui.showAllEarlier) earlierUnits else earlierUnits.take(EARLIER_LIMIT)
        val earlier = shownEarlier.flatMap { it.rows }

        val listed = if (matchMode) matchRows else now + earlier
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
            if (row.id == selectedId) row.copy(resumeNote = recordIdOf(row, groupFiles)?.let(::inspectionOf)?.resumeNote) else row
        }
        val selectedRow = shown.firstOrNull { it.id == selectedId }

        val peek = if (ui.peekOpen && selectedRow != null) {
            val said = recordIdOf(selectedRow, groupFiles)?.let(::inspectionOf)?.takeIf { it.live == selectedRow.isLive }
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
        val groupStates = units.associate { it.group.id to groupOf(it, ui) }
        val groupNeeds = units.sumOf { u -> u.runs.count { it.member.state == NEEDS_YOU } }
        val groupWorking = units.sumOf { u -> u.runs.count { it.member.state == WORKING } }
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            matchMode = matchMode,
            matches = if (matchMode) shown else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else shown.take(now.size),
            earlier = if (matchMode) emptyList() else shown.drop(now.size),
            earlierHidden = earlierUnits.size - shownEarlier.size,
            needYouCount = singles.count { it.state == NEEDS_YOU } + groupNeeds,
            workingCount = singles.count { it.state == WORKING } + groupWorking,
            selectedId = selectedId,
            peek = peek,
            confirm = confirm,
            groups = if (matchMode) emptyMap() else groupStates,
            hasSessions = sessions.isNotEmpty() || groupFiles.any { it.members.isNotEmpty() },
            footer = footerOf(selectedRow, groupStates),
        )
    }

    /** The ◆ row that has waited longest, or null when no listed row needs the user. */
    private fun oldestNeedsYou(rows: List<Row>): Row? =
        rows.filter { it.glyph == Glyph.NEEDS_YOU }.minByOrNull { it.sinceMillis }

    /** Which key hints go with [row]. */
    private fun footerOf(row: Row?, groups: Map<String, Group>): Footer = when {
        row == null -> Footer.NONE
        row.kind == RowKind.GROUP -> if (groups[row.groupId]?.expanded == true) Footer.GROUP_EXPANDED else Footer.GROUP_COLLAPSED
        row.unhosted -> if (row.sessionId != null) Footer.RUN_ATTACH else Footer.NO_SESSION
        row.isLive -> Footer.LIVE
        else -> Footer.CLOSED
    }

    /**
     * Pair [g]'s runs with the sessions they match and work out where the group sits. The lead
     * session joins the runs only while it needs the user or works.
     */
    private fun unitOf(g: AgentGroup, byId: Map<String, AgentSessionSummary>, panes: Map<String?, PaneLocation>): GroupUnit {
        // Open means a session Lunamux lists whose pane is in the layout, so Jump has somewhere to go.
        val leadSummary = g.lead.recordId?.let(byId::get)
        val leadLive = g.lead.live && leadSummary != null && panes[leadSummary.leafId] != null
        val leadRun = leadSummary?.takeIf { leadLive && g.lead.named && (it.state == NEEDS_YOU || it.state == WORKING) }
            ?.let { Run(leadMemberOf(g, it), it, -1) }
        val runs = listOfNotNull(leadRun) + g.members.mapIndexed { i, m -> Run(m, m.recordId?.let(byId::get), i) }
        val ended = runs.all { it.member.state == ENDED }
        val rank = when {
            runs.any { it.member.state == NEEDS_YOU } -> 0
            runs.any { it.member.state == WORKING } -> 1
            else -> OPEN_WORK_RANK
        }
        val since = when {
            runs.isEmpty() -> 0L
            rank == OPEN_WORK_RANK -> runs.maxOf { it.member.sinceMillis }
            else -> runs.filter { rankOf(it.member.state) == rank }.minOf { it.member.sinceMillis }
        }
        return GroupUnit(g, runs, rank, since, leadLive, leadSummary?.id.takeIf { leadLive }, ended)
    }

    /** The record behind [row] that peek and inspection ask the server about, or null when there is none. */
    private fun recordIdOf(row: Row, groups: List<AgentGroup>): String? = when {
        row.kind == RowKind.GROUP -> groups.firstOrNull { it.id == row.groupId }?.lead?.recordId
        row.kind == RowKind.SESSION && !row.unhosted -> row.id
        else -> null
    }

    private fun rankOf(state: String) = when (state) {
        NEEDS_YOU -> 0
        WORKING -> 1
        else -> OPEN_WORK_RANK
    }

    /** Which count and which place in the expanded order [m] has, from its state and the file's label. */
    private fun bucketOf(m: AgentGroupMember): Bucket {
        if (m.state == NEEDS_YOU) return Bucket(0, "needs you")
        if (m.state == WORKING) return Bucket(1, "working")
        if (m.state == ENDED) return Bucket(6, "done")
        val label = m.label?.trim().orEmpty()
        val lower = label.lowercase()
        return when {
            lower.startsWith("in review") -> Bucket(2, "in review")
            lower == "queued" || lower == "waiting" -> Bucket(3, "queued")
            lower.startsWith("paused") || lower.startsWith("held") -> Bucket(5, "paused")
            lower == "done" || lower == "ready" -> Bucket(6, "done")
            label.isEmpty() -> Bucket(4, "idle")
            else -> Bucket(4, label)
        }
    }

    /** The header the view draws for [u]. */
    private fun groupOf(u: GroupUnit, ui: Ui): Group {
        val g = u.group
        val buckets = u.runs.map { bucketOf(it.member) }
        val counted = buckets.filter { it.order > 0 }.groupBy { it.name }
            .entries.sortedBy { (_, list) -> list.first().order }
        return Group(
            id = g.id, title = g.title, repo = g.repo,
            needYouCount = u.runs.count { it.member.state == NEEDS_YOU },
            workingCount = u.runs.count { it.member.state == WORKING },
            summary = counted.joinToString(" · ") { (name, list) -> "${list.size} $name" }
                .ifEmpty { if (u.runs.isEmpty()) "no runs" else "" },
            expanded = g.id in ui.expandedGroups,
            leadLive = u.leadLive,
            activityMillis = u.runs.filter { it.member.state == WORKING }
                .mapNotNull { workingActivity(it, g.refreshedAtMillis) }.maxOrNull(),
        )
    }

    /**
     * The last sign of life of a working run: the newer of its hosted session's last activity and
     * the time the group file declaring it was last refreshed. A run outside Lunamux has only the
     * file; a file that is still being refreshed keeps its working runs live.
     */
    private fun workingActivity(run: Run, refreshed: Long?): Long? {
        val hosted = run.session?.let { it.lastActivityAt ?: it.stateSince }
        return listOfNotNull(hosted, refreshed).maxOrNull()
    }

    /**
     * The row the open lead session of [g] shows as among its runs: titled after the group's tag
     * ("Programme session"), with the session's own state, question or last tool line and age.
     */
    private fun leadMemberOf(g: AgentGroup, s: AgentSessionSummary) = AgentGroupMember(
        title = leadTitle(g.tag),
        state = s.state,
        line = if (s.state == NEEDS_YOU) s.question ?: s.nowLine else s.nowLine,
        sinceMillis = s.stateSince,
        recordId = s.id,
    )

    /** What the lead session is called among its group's runs: "Programme session", or "Lead session" without a tag. */
    private fun leadTitle(tag: String?): String =
        (tag?.trim()?.takeIf { it.isNotEmpty() } ?: "lead").lowercase().replaceFirstChar { it.uppercase() } + " session"

    /**
     * The rows of one group: its header, then, while collapsed, the runs that need the user, or,
     * once expanded, every run in the order needs you (longest waiting first), working, in review,
     * queued, other labels, paused, done; the file's order within a bucket.
     */
    private fun groupRows(
        u: GroupUnit,
        groupFiles: List<AgentGroup>,
        panes: Map<String?, PaneLocation>,
        ui: Ui,
        resumeError: (String) -> String?,
    ): List<Row> {
        val g = u.group
        val expanded = g.id in ui.expandedGroups
        val header = Row(
            id = "$GROUP_PREFIX${g.id}", agent = "", state = stateOfRank(u.rank), title = g.title,
            links = emptyList(), repo = g.repo, nowLine = null, question = null, sinceMillis = u.since,
            isLive = u.leadLive, canResume = !u.leadLive,
            actionLabel = "open",
            error = resumeError("$GROUP_PREFIX${g.id}"), kind = RowKind.GROUP, groupId = g.id,
            glyph = Glyph.NONE,
            status = "",
        )
        val rows = u.runs.map { run ->
            run to runRow(g, run, panes, resumeError)
        }
        val shown = if (expanded) {
            rows.sortedWith(
                compareBy<Pair<Run, Row>> { bucketOf(it.first.member).order }
                    .thenBy { if (it.first.member.state == NEEDS_YOU) it.first.member.sinceMillis else 0L },
            )
        } else rows.filter { it.first.member.state == NEEDS_YOU }.sortedBy { it.first.member.sinceMillis }
        return listOf(header) + shown.map { it.second }
    }

    private fun stateOfRank(rank: Int) = when (rank) {
        0 -> NEEDS_YOU
        1 -> WORKING
        else -> IDLE
    }

    /** One run of [g] as a row: the file's state and wording, over the hosted session's pane when there is one. */
    private fun runRow(g: AgentGroup, run: Run, panes: Map<String?, PaneLocation>, resumeError: (String) -> String?): Row {
        val m = run.member
        val s = run.session
        // A run the file gives a session id is identified by it, so a writer that inserts or reorders
        // members does not move the selection onto another run; without one, its place in the file.
        val rowId = if (s != null) s.id else "$MEMBER_PREFIX${g.id}:${m.sessionId ?: run.fileIndex}"
        val base = if (s != null) {
            toRow(s, panes[s.leafId], resumeError(s.id))
        } else {
            Row(
                id = rowId, agent = "", state = m.state, title = m.title,
                links = m.links, repo = null, nowLine = null, question = null, sinceMillis = m.sinceMillis,
                isLive = false, canResume = false, actionLabel = "",
                error = resumeError(rowId), unhosted = true,
                fileRefreshedMillis = g.refreshedAtMillis, sessionId = m.sessionId,
            )
        }
        val bucket = bucketOf(m)
        val pr = m.links.firstOrNull { PR_LINK.matches(it) }
        val line = m.line?.trim()?.takeIf { it.isNotEmpty() }
        val firstPrompt = s?.firstPrompt?.takeIf { it.isNotBlank() }?.let { "❯ $it" }
        val said = s?.lastSaid?.trim()?.takeIf { it.isNotEmpty() }
        val hostedLine = s?.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        val status = when (m.state) {
            NEEDS_YOU -> line ?: s?.question ?: "waiting at a prompt"
            WORKING -> line ?: hostedLine ?: firstPrompt ?: if (s == null && m.label != null) "" else "—"
            // A finished run's line that only repeats its pull request is dropped: the PR has its own column.
            else -> (line?.takeIf { it != pr } ?: said ?: firstPrompt)
                ?: if (bucket.order == 6 || m.label != null) "" else "—"
        }
        return base.copy(
            title = m.title,
            state = m.state,
            // Only the keys the file gives; the ones read from a session's branch are not the feed's.
            links = m.links,
            actionLabel = when {
                base.isLive -> "jump"
                s != null -> "resume"
                m.sessionId != null -> "open"
                else -> ""
            },
            sinceMillis = m.sinceMillis,
            nowLine = if (m.state == NEEDS_YOU) null else line,
            question = if (m.state == NEEDS_YOU) line else null,
            groupId = g.id,
            // The file's word ("coding") goes with a state only the file vouches for: a hosted run's
            // live state wins over what the file said, so its label is dropped while it works.
            label = m.label?.trim()?.takeIf { it.isNotEmpty() && (m.state == IDLE || m.state == ENDED || (m.state == WORKING && s == null)) },
            activityMillis = if (m.state == WORKING) workingActivity(run, g.refreshedAtMillis) else null,
            glyph = when (bucket.order) {
                0 -> Glyph.NEEDS_YOU
                1 -> Glyph.WORKING
                5 -> Glyph.PAUSED
                6 -> Glyph.DONE
                else -> Glyph.WAITING
            },
            status = status,
            where = pr,
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
        val title = s.title?.takeIf { it.isNotBlank() }
            ?: s.branch?.takeIf { it.isNotBlank() }
            ?: folderOf(s)
            ?: s.agent
        val ended = s.state == ENDED
        return Row(
            id = s.id,
            agent = s.agent,
            state = s.state,
            title = title,
            links = s.links,
            repo = s.repo,
            nowLine = s.nowLine?.takeIf { it.isNotBlank() },
            question = s.question?.takeIf { it.isNotBlank() },
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

    /** A session's status line: its question, last tool line or last words, else its first prompt, else a dash. */
    private fun statusOf(s: AgentSessionSummary): String {
        val firstPrompt = s.firstPrompt?.takeIf { it.isNotBlank() }?.let { "❯ $it" }
        return when (s.state) {
            NEEDS_YOU -> s.question?.takeIf { it.isNotBlank() } ?: s.nowLine?.takeIf { it.isNotBlank() } ?: "waiting at a prompt"
            WORKING -> s.nowLine?.takeIf { it.isNotBlank() } ?: firstPrompt ?: "—"
            else -> s.lastSaid?.takeIf { it.isNotBlank() } ?: firstPrompt ?: "—"
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

        /** How many EARLIER rows show before "More". */
        const val EARLIER_LIMIT = 5

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
         * How long a group file may go without being refreshed before what it says about members
         * Lunamux does not host is treated as stale. The writer touches an unchanged file at least
         * every two minutes while a run works, so twenty minutes of silence means the
         * writer stopped and the file's states are no longer news.
         */
        const val STALE_AFTER_MS = 20L * 60_000L

        /**
         * Whether a group file has gone stale: it was last refreshed [STALE_AFTER_MS] or more ago.
         * A pure function of the clock, like [isQuiet], so the web view's periodic redraw can flip a
         * row to stale with no new input from the server.
         *
         * Called by the web overview on every redraw for each row with a [Row.fileRefreshedMillis].
         *
         * @param refreshedMillis epoch millis the group file was last refreshed.
         * @param nowMillis the current epoch millis.
         * @return true when the gap is at least [STALE_AFTER_MS].
         * @see Row.fileRefreshedMillis
         */
        fun isStale(refreshedMillis: Long, nowMillis: Long): Boolean = nowMillis - refreshedMillis >= STALE_AFTER_MS

        /**
         * Whether a working row's glyph should move: it has a sign of life that is not yet
         * [QUIET_AFTER_MS] old. For a run Lunamux does not host that sign is the group file's
         * refresh time, so a file that is still being refreshed keeps its working runs live.
         *
         * Called by the web overview for each row and header when it draws the state glyph.
         *
         * @param activityMillis [Row.activityMillis] or [Group.activityMillis]; null means nothing works.
         * @param nowMillis the current epoch millis.
         * @return true when the glyph should animate.
         * @see isQuiet
         */
        fun isWorkingLive(activityMillis: Long?, nowMillis: Long): Boolean =
            activityMillis != null && !isQuiet(activityMillis, nowMillis)

        /**
         * "2 need you" / "1 needs you": the plural form shared by the chip and the group headers.
         *
         * @param n how many need the user.
         * @return the count and the right verb.
         */
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"

        /**
         * The prefix of a working row's status when its sign of life is old: "stale 25m · " for a
         * run whose group file has not been refreshed for [STALE_AFTER_MS], else "quiet 6m · " once
         * [QUIET_AFTER_MS] has passed; empty otherwise.
         *
         * Called by the web overview for each working row on every redraw.
         *
         * @param row a row of the list.
         * @param nowMillis the current epoch millis.
         * @return the prefix including its trailing separator, or an empty string.
         */
        fun agePrefix(row: Row, nowMillis: Long): String {
            val file = row.fileRefreshedMillis
            if (row.state == WORKING && file != null && isStale(file, nowMillis)) {
                return "stale ${(nowMillis - file) / 60_000L}m · "
            }
            val activity = row.activityMillis
            if (row.state == WORKING && activity != null && isQuiet(activity, nowMillis)) {
                return "quiet ${(nowMillis - activity) / 60_000L}m · "
            }
            return ""
        }

        private const val GROUP_PREFIX = "group:"
        private const val MEMBER_PREFIX = "member:"
        private const val OPEN_WORK_RANK = 2
        private const val GRACE_RANK = 3
        private const val IDLE = "idle"
        private val PR_LINK = Regex("#\\d+")
        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
        private const val GEMINI = "gemini"
    }
}
