/**
 * Backing ViewModel for the agents overview: the toolbar chip and the
 * ⌘⇧A overlay that list every agentic-CLI session (Claude Code, Codex,
 * Gemini) Lunamux knows about.
 *
 * The server pushes one list of session summaries; this ViewModel turns it
 * into the two sections the overlay shows (NOW and EARLIER), the chip
 * counts, the title-only search and the keyboard selection, and carries out
 * the two actions a row offers: jump to its pane, or resume it in a new tab.
 * The view only renders [State] and forwards key presses.
 *
 * @see se.soderbjorn.lunamux.AgentSessionSummary
 * @see WindowStateRepository.agentSessions
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
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
 * @param send sends a command to the server, normally `windowSocket::send`.
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val send: suspend (WindowCommand) -> Unit,
) {
    /**
     * One row of the overlay.
     *
     * @property id the server's record id; what a resume names.
     * @property agent `claude`, `codex` or `gemini`.
     * @property state `needs_you`, `working`, `idle` or `ended`.
     * @property title the display title: custom pane name, else the agent's own
     *   title, else the branch, else the folder name.
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
    )

    /**
     * Everything the view renders.
     *
     * @property open whether the overlay is showing.
     * @property query the search text; matches titles only.
     * @property now needs-you rows first, then working rows, longest waiting first.
     * @property earlier idle and ended rows, newest first, cut to [EARLIER_LIMIT] until "More" is pressed.
     * @property earlierHidden how many matching earlier rows "More" would reveal.
     * @property needYouCount rows asking for the user; the chip's first number.
     * @property workingCount rows at work; the chip's second number.
     * @property selected index into [now] followed by [earlier] of the highlighted row, or -1 when empty.
     */
    data class State(
        val open: Boolean = false,
        val query: String = "",
        val now: List<Row> = emptyList(),
        val earlier: List<Row> = emptyList(),
        val earlierHidden: Int = 0,
        val needYouCount: Int = 0,
        val workingCount: Int = 0,
        val selected: Int = -1,
    ) {
        /** The rows in the order the keyboard walks them: [now] then [earlier]. */
        val rows: List<Row> get() = now + earlier

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
    )

    private val ui = MutableStateFlow(Ui())

    /**
     * Render-ready state; emits whenever the sessions, layout, error or view inputs change.
     * Collected by the view to redraw the chip and the overlay.
     */
    val stateFlow: Flow<State> = combine(
        windowState.agentSessions,
        windowState.config,
        windowState.agentSessionError,
        ui,
    ) { sessions, config, error, ui -> build(sessions, config, error, ui) }

    /** The state right now, for key handlers that must act on what is on screen. */
    val state: State
        get() = build(
            windowState.agentSessions.value,
            windowState.config.value,
            windowState.agentSessionError.value,
            ui.value,
        )

    /** Show the overlay with an empty search and the first row selected. Called by ⌘⇧A and the chip. */
    fun open() {
        windowState.clearAgentSessionError()
        ui.value = Ui(open = true)
    }

    /** Hide the overlay and forget the search. Called by esc and after a jump or resume. */
    fun close() {
        windowState.clearAgentSessionError()
        ui.value = Ui()
    }

    /** Open the overlay if it is closed, close it otherwise. */
    fun toggle() = if (state.open) close() else open()

    /**
     * Change the search text and select the first match.
     *
     * @param query what the user typed; only titles are searched.
     */
    fun setQuery(query: String) {
        windowState.clearAgentSessionError()
        ui.update { it.copy(query = query, selected = 0) }
    }

    /**
     * Move the highlight, stopping at the first and last row.
     *
     * @param delta `-1` for ↑, `1` for ↓.
     */
    fun moveSelection(delta: Int) {
        val last = state.rows.lastIndex
        ui.update { it.copy(selected = (it.selected + delta).coerceIn(0, maxOf(last, 0))) }
    }

    /** Reveal the earlier rows beyond the first [EARLIER_LIMIT]. Called by the "More" link. */
    fun showMoreEarlier() = ui.update { it.copy(showAllEarlier = true) }

    /**
     * Do the highlighted row's action: jump to a live pane, resume an ended session.
     * Called for ↵.
     */
    suspend fun activateSelected() {
        val state = state
        state.rows.getOrNull(state.selected)?.let { activate(it) }
    }

    /**
     * Do [row]'s action. Called when a row is clicked.
     *
     * @param row the row to jump to or resume.
     */
    suspend fun activate(row: Row) {
        if (row.isLive) jump(row) else if (row.canResume) resume(row)
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
    suspend fun jump(row: Row) {
        val config = windowState.config.value ?: return
        val leafId = windowState.agentSessions.value.firstOrNull { it.id == row.id }?.leafId ?: return
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

    private fun build(
        sessions: List<AgentSessionSummary>,
        config: WindowConfig?,
        error: String?,
        ui: Ui,
    ): State {
        val panes = config?.let(::paneLocations).orEmpty()
        val query = ui.query.trim()
        val rows = sessions.map { s -> s to toRow(s, panes[s.leafId], error.takeIf { s.id == ui.resumingId }) }
            .filter { (_, row) -> query.isEmpty() || row.title.contains(query, ignoreCase = true) }

        val now = rows.filter { (s, _) -> s.state == NEEDS_YOU || s.state == WORKING }
            .sortedWith(compareBy({ (s, _) -> if (s.state == NEEDS_YOU) 0 else 1 }, { (_, row) -> row.sinceMillis }))
            .map { it.second }
        val earlierAll = rows.filter { (s, _) -> s.state != NEEDS_YOU && s.state != WORKING }
            .sortedByDescending { (_, row) -> row.sinceMillis }
            .map { it.second }
        val showAll = ui.showAllEarlier || query.isNotEmpty()
        val earlier = if (showAll) earlierAll else earlierAll.take(EARLIER_LIMIT)
        val visible = now.size + earlier.size
        // Once a resume has switched to the new tab the overlay has done its job.
        val resumed = ui.resumingId != null && config?.effectiveActiveTabId != ui.resumeFromTab
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            now = now,
            earlier = earlier,
            earlierHidden = earlierAll.size - earlier.size,
            needYouCount = sessions.count { it.state == NEEDS_YOU },
            workingCount = sessions.count { it.state == WORKING },
            selected = if (visible == 0) -1 else ui.selected.coerceIn(0, visible - 1),
        )
    }

    private fun toRow(s: AgentSessionSummary, where: PaneLocation?, error: String?): Row {
        val live = s.state != ENDED && where != null
        val title = where?.leaf?.customName?.takeIf { it.isNotBlank() }
            ?: s.title?.takeIf { it.isNotBlank() }
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

        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
        private const val GEMINI = "gemini"
    }
}
