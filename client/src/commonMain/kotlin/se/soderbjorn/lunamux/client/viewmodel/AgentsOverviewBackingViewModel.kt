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
 * Groups declared by group files appear as boxes: a header row, the members that need the
 * user, and a "+ more" row that expands the rest. A group ranks beside single sessions by
 * its most urgent member, and its header opens the group's lead session.
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
 * @param send sends a command to the server, normally `windowSocket::send`.
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
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
     * @property nowLine the last tool line, or null ("Working" is shown instead).
     * @property question what a `needs_you` row is asking, or null.
     * @property sinceMillis epoch millis the row entered its state (when it ended, for ended rows);
     *   the view turns it into an age.
     * @property isLive whether the agent's pane is still open, so ↵ jumps to it.
     * @property canResume whether ↵ opens a new tab for it (an ended row).
     * @property actionLabel the label of the ↵ action: `Jump`, `Resume` or `Open folder`.
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
     * @property kind what the row is: a session (or group member), a group header, or a group's
     *   "+ more" row. Only [RowKind.SESSION] rows exist in a search.
     * @property groupId the group a header, "+ more" row or member belongs to; null for a single session.
     * @property unhosted true for a group member Lunamux does not host (it has no session record);
     *   ↵ on it opens the group's lead session.
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
    )

    /** What a [Row] stands for. */
    enum class RowKind {
        /** A session, or a group member (then [Row.groupId] is set). */
        SESSION,

        /** The header of a group box. [Row.id] is `group:<id>`. */
        GROUP,

        /** The "+ 1 working, 1 in review" row under a collapsed group. [Row.id] is `more:<id>`. */
        GROUP_MORE,
    }

    /**
     * What the view needs to draw one group's header.
     *
     * @property id the group's id.
     * @property title the group's name.
     * @property tag the pill text (for example `PROGRAMME`), or null.
     * @property repo repository name, or null.
     * @property linkLabel the link chip's text, or null when there is no link.
     * @property linkUrl the link chip's `http(s)` address.
     * @property needYouCount members that need the user; drawn highlighted before [summary].
     * @property workingCount members at work.
     * @property summary the rest of the header's count line, without the needs-you part
     *   ("1 working · 1 in review"); empty when every member needs the user.
     * @property sinceMillis when the most urgent member's wait began; the view turns it into an age.
     * @property expanded whether every member shows rather than only those that need the user.
     * @property leadLive whether the lead session is open, so ↵ on the header jumps to it.
     * @property leadLastSaid what the lead session last said, or null.
     */
    data class Group(
        val id: String,
        val title: String,
        val tag: String?,
        val repo: String?,
        val linkLabel: String?,
        val linkUrl: String?,
        val needYouCount: Int,
        val workingCount: Int,
        val summary: String,
        val sinceMillis: Long,
        val expanded: Boolean,
        val leadLive: Boolean,
        val leadLastSaid: String?,
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
     * @property selected index into [rows] of the highlighted row, or -1 when empty.
     * @property peek the screen popover, or null while it is closed.
     * @property confirm the close confirmation, or null while none is asked.
     * @property groups the groups drawn in [now] and [earlier], by group id.
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
        val selected: Int = -1,
        val peek: Peek? = null,
        val confirm: Confirm? = null,
        val groups: Map<String, Group> = emptyMap(),
    ) {
        /** The rows in the order the keyboard walks them: [matches], or [now] then [earlier]. */
        val rows: List<Row> get() = if (matchMode) matches else now + earlier

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

        /** Whether the chip should show: it hides when nothing needs the user and nothing works. */
        val chipVisible: Boolean get() = needYouCount + workingCount > 0
    }

    /** The view-owned inputs; the server-owned ones are combined in from [windowState]. */
    private data class Ui(
        val open: Boolean = false,
        val query: String = "",
        val selected: Int = 0,
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
            ui.value = Ui()
        }
    }

    /** Show the overlay with an empty search and the first row selected. Called by ⌘⇧A and the chip. */
    fun open() {
        windowState.clearAgentSessionError()
        inspected = null
        ui.value = Ui(open = true)
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
     * Change the search text and select the first row. Closes the peek and any
     * confirmation, which belonged to the old selection.
     *
     * With two or more characters the overlay switches to search results; the
     * view then calls [search] once typing pauses.
     *
     * @param query what the user typed.
     */
    fun setQuery(query: String) {
        windowState.clearAgentSessionError()
        ui.update { it.copy(query = query, selected = 0, peekOpen = false, confirmRowId = null) }
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
        val last = state.rows.lastIndex
        ui.update {
            it.copy(selected = (it.selected + delta).coerceIn(0, maxOf(last, 0)), peekOpen = false, confirmRowId = null)
        }
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
        state.rows.getOrNull(state.selected)?.let { activate(it) }
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
        val row = current.rows.getOrNull(current.selected) ?: return
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
        val row = current.rows.getOrNull(current.selected) ?: return
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
        val row = current.rows.getOrNull(current.selected)?.takeIf { it.canClose } ?: return
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
     * Do [row]'s action. Called when a row is clicked.
     *
     * @param row the row to jump to or resume.
     */
    suspend fun activate(row: Row) {
        when {
            row.kind == RowKind.GROUP_MORE -> row.groupId?.let(::expandGroup)
            row.kind == RowKind.GROUP || row.unhosted -> openLead(row)
            row.isLive -> jump(row)
            row.canResume -> resume(row)
        }
    }

    /**
     * Show every member of a group and move the highlight to its header.
     *
     * Called for ↵ or a click on a group's "+ more" row.
     *
     * @param groupId the group to expand.
     * @see toggleGroup
     */
    fun expandGroup(groupId: String) = setExpanded(groupId, true)

    /**
     * Expand a collapsed group or collapse an expanded one, keeping the highlight on its header.
     *
     * Called by a click on a group header's chevron or count line, and for ← on a group's rows.
     *
     * @param groupId the group to toggle.
     */
    fun toggleGroup(groupId: String) = setExpanded(groupId, groupId !in ui.value.expandedGroups)

    private fun setExpanded(groupId: String, expanded: Boolean) {
        val header = state.rows.indexOfFirst { it.kind == RowKind.GROUP && it.groupId == groupId }
        ui.update {
            it.copy(
                expandedGroups = if (expanded) it.expandedGroups + groupId else it.expandedGroups - groupId,
                selected = if (header >= 0) header else it.selected,
                peekOpen = false,
                confirmRowId = null,
            )
        }
    }

    /**
     * Open a group's lead session: jump to it when it is open, else ask the server to start it
     * in a new tab. The overlay stays open until the new tab is active or the server says why
     * it could not, exactly as for [resume].
     *
     * Called for ↵ on a group header and on a member Lunamux does not host.
     *
     * @param row the header or member row; its [Row.groupId] names the group.
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

    /** A group with its members paired to the session summaries they matched, ready to become rows. */
    private class GroupUnit(
        val group: AgentGroup,
        val members: List<Pair<AgentGroupMember, AgentSessionSummary?>>,
        val rank: Int,
        val since: Long,
        val leadLive: Boolean,
    )

    private fun build(input: Inputs): State {
        val (sessions, groupFiles, config, error, matches, inspection, ui) = input
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

        // A session a group member matched is drawn inside its group box, not again as a single.
        val units = groupFiles.map { g ->
            val members = g.members.map { m -> m to m.recordId?.let(byId::get) }
            val rank = members.minOfOrNull { (m, _) -> rankOf(m.state) } ?: IDLE_RANK
            val since = when {
                members.isEmpty() -> 0L
                rank == IDLE_RANK -> members.maxOf { (m, _) -> m.sinceMillis }
                else -> members.filter { (m, _) -> rankOf(m.state) == rank }.minOf { (m, _) -> m.sinceMillis }
            }
            // Open means a session Lunamux lists whose pane is in the layout, so Jump has somewhere to go.
            val leadSummary = g.lead.recordId?.let(byId::get)
            val leadLive = g.lead.live && leadSummary != null && panes[leadSummary.leafId] != null
            GroupUnit(g, members, rank, since, leadLive)
        }
        val claimed = units.flatMap { u -> u.members.mapNotNull { (_, s) -> s?.id } }.toSet()
        val singles = sessions.filter { it.id !in claimed }
            .map { s -> s to toRow(s, panes[s.leafId], resumeError(s.id)) }

        // Groups and single sessions are ranked together: urgency first, then who has waited longest.
        val urgent = buildList {
            singles.filter { (s, _) -> s.state == NEEDS_YOU || s.state == WORKING }
                .forEach { (s, row) -> add(Triple(rankOf(s.state), row.sinceMillis, listOf(row))) }
            units.filter { it.rank < IDLE_RANK }
                .forEach { u -> add(Triple(u.rank, u.since, groupRows(u, panes, ui, resumeError))) }
        }.sortedWith(compareBy({ it.first }, { it.second }))
        val now = urgent.flatMap { it.third }
        val earlierAll = singles.filter { (s, _) -> s.state != NEEDS_YOU && s.state != WORKING }
            .sortedByDescending { (_, row) -> row.sinceMillis }
            .map { it.second }
        val earlier = if (ui.showAllEarlier) earlierAll else earlierAll.take(EARLIER_LIMIT)
        val idleGroups = units.filter { it.rank == IDLE_RANK }.sortedByDescending { it.since }
            .flatMap { groupRows(it, panes, ui, resumeError) }
        val listed = if (matchMode) matchRows else now + idleGroups + earlier
        val selected = if (listed.isEmpty()) -1 else ui.selected.coerceIn(0, listed.size - 1)

        // The selected row of a plain list also tells what Resume will do, from the inspection.
        val shown = if (matchMode || selected < 0) listed else listed.mapIndexed { i, row ->
            if (i == selected) row.copy(resumeNote = recordIdOf(row, groupFiles)?.let(::inspectionOf)?.resumeNote) else row
        }
        val selectedRow = shown.getOrNull(selected)

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
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            matchMode = matchMode,
            matches = if (matchMode) shown else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else shown.take(now.size),
            earlier = if (matchMode) emptyList() else shown.drop(now.size),
            earlierHidden = earlierAll.size - earlier.size,
            needYouCount = singles.count { (s, _) -> s.state == NEEDS_YOU } +
                units.sumOf { u -> u.members.count { (m, _) -> m.state == NEEDS_YOU } },
            workingCount = singles.count { (s, _) -> s.state == WORKING } +
                units.sumOf { u -> u.members.count { (m, _) -> m.state == WORKING } },
            selected = selected,
            peek = peek,
            confirm = confirm,
            groups = if (matchMode) emptyMap() else units.associate { it.group.id to groupOf(it, ui) },
        )
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
        else -> IDLE_RANK
    }

    /** The header the view draws for [u]. */
    private fun groupOf(
        u: GroupUnit,
        ui: Ui,
    ): Group {
        val g = u.group
        val rest = restCounts(u.members.map { it.first }.filter { it.state != NEEDS_YOU })
        return Group(
            id = g.id, title = g.title, tag = g.tag, repo = g.repo, linkLabel = g.linkLabel, linkUrl = g.linkUrl,
            needYouCount = u.members.count { (m, _) -> m.state == NEEDS_YOU },
            workingCount = u.members.count { (m, _) -> m.state == WORKING },
            summary = rest.joinToString(" · ").ifEmpty { if (u.members.isEmpty()) "no members" else "" },
            sinceMillis = u.since,
            expanded = g.id in ui.expandedGroups,
            leadLive = u.leadLive,
            leadLastSaid = g.lead.lastSaid,
        )
    }

    /**
     * "1 working", "1 in review", …: working members first, then the others grouped by their
     * label (an unlabeled one by its state), in the order the file lists them.
     */
    private fun restCounts(members: List<AgentGroupMember>): List<String> {
        val working = members.count { it.state == WORKING }
        val others = members.filter { it.state != WORKING }.groupingBy { it.label ?: it.state }.eachCount()
        return buildList {
            if (working > 0) add("$working working")
            others.forEach { (label, n) -> add("$n $label") }
        }
    }

    /**
     * The rows of one group box: its header, then the members that need the user followed by a
     * "+ more" row, or every member once the group is expanded.
     */
    private fun groupRows(
        u: GroupUnit,
        panes: Map<String?, PaneLocation>,
        ui: Ui,
        resumeError: (String) -> String?,
    ): List<Row> {
        val g = u.group
        val expanded = g.id in ui.expandedGroups
        val leadSummaryLive = u.leadLive
        val header = Row(
            id = "$GROUP_PREFIX${g.id}", agent = "", state = stateOfRank(u.rank), title = g.title,
            links = emptyList(), repo = g.repo, nowLine = null, question = null, sinceMillis = u.since,
            isLive = leadSummaryLive, canResume = !leadSummaryLive,
            actionLabel = if (leadSummaryLive) "Jump" else "Open lead",
            error = resumeError("$GROUP_PREFIX${g.id}"), kind = RowKind.GROUP, groupId = g.id,
        )
        val memberRows = u.members.mapIndexed { i, (m, s) ->
            val base = if (s != null) {
                toRow(s, s.leafId.let(panes::get), resumeError(s.id))
            } else {
                Row(
                    id = "$MEMBER_PREFIX${g.id}:$i", agent = "", state = m.state, title = m.title,
                    links = m.links, repo = null, nowLine = null, question = null, sinceMillis = m.sinceMillis,
                    isLive = false, canResume = false, actionLabel = "Open lead",
                    error = resumeError("$MEMBER_PREFIX${g.id}:$i"), unhosted = true,
                )
            }
            m to base.copy(
                title = m.title,
                state = m.state,
                links = m.links.ifEmpty { base.links },
                sinceMillis = m.sinceMillis,
                nowLine = if (m.state == NEEDS_YOU) null else m.line,
                question = if (m.state == NEEDS_YOU) m.line else null,
                groupId = g.id,
            )
        }
        val hidden = if (expanded) emptyList() else memberRows.filter { (m, _) -> m.state != NEEDS_YOU }
        val shown = if (expanded) memberRows else memberRows.filter { (m, _) -> m.state == NEEDS_YOU }
        val more = if (hidden.isEmpty()) emptyList() else listOf(
            Row(
                id = "$MORE_PREFIX${g.id}", agent = "", state = IDLE,
                title = "+ " + restCounts(hidden.map { it.first }).joinToString(", "),
                links = emptyList(), repo = null, nowLine = null, question = null, sinceMillis = 0,
                isLive = false, canResume = false, actionLabel = "Show", kind = RowKind.GROUP_MORE, groupId = g.id,
            ),
        )
        return listOf(header) + shown.map { it.second } + more
    }

    private fun stateOfRank(rank: Int) = when (rank) {
        0 -> NEEDS_YOU
        1 -> WORKING
        else -> IDLE
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
            ?: s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: s.agent
        val ended = s.state == ENDED
        return Row(
            id = s.id,
            agent = s.agent,
            state = s.state,
            title = title,
            links = s.links,
            repo = s.repo,
            nowLine = s.nowLine,
            question = s.question,
            sinceMillis = if (ended) s.endedAt ?: s.stateSince else s.stateSince,
            isLive = live,
            canResume = !live,
            actionLabel = when {
                live -> "Jump"
                s.agent == GEMINI -> "Open folder"
                else -> "Resume"
            },
            error = error,
            canClose = live,
        )
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
        /** How many EARLIER rows show before "More". */
        const val EARLIER_LIMIT = 5

        /** The fewest characters that start a search over every session. */
        const val MIN_QUERY = 2

        private const val GROUP_PREFIX = "group:"
        private const val MEMBER_PREFIX = "member:"
        private const val MORE_PREFIX = "more:"
        private const val IDLE_RANK = 2
        private const val IDLE = "idle"
        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
        private const val GEMINI = "gemini"
    }
}
