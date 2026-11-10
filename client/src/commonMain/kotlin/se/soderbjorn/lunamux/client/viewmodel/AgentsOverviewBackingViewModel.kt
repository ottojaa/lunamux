/**
 * The agents overview (⌘⇧A): every agent conversation Lunamux knows about, as rows the person can
 * jump to, resume, peek into or close.
 *
 * [buildOverview] is a pure function from what the server pushed (sessions, tasks, search matches,
 * an inspection of the selected row), the layout and the person's choices to the rows on screen.
 * [AgentsOverviewBackingViewModel] holds those choices (query, selection and one [OverviewMode])
 * and calls [buildOverview] on every change, so a key handler reading
 * [AgentsOverviewBackingViewModel.state] always sees the result of the key before it.
 *
 * A row is one conversation, or one task. NOW lists every live conversation in a listed tab and
 * every task with a live conversation, grouped by repo, in the order their work began. STOPPED BY
 * THE RESTART lists what Lunamux cut off when it last stopped, with one key to resume all. EARLIER
 * lists the rest by recency in two bands (three days, two weeks), five rows and a "+ N more" line.
 * Typing two or more characters switches to the server's search, one row per task.
 *
 * Every row has one headline ([AgentsOverviewBackingViewModel.Row.detail]) that says where it is:
 * the question while it needs the person, what it is doing while it works, and the last thing it
 * said once it is done or closed. The task panel shows the same line ([headlineOf]).
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskStep
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts.ENDED
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts.NEEDS_YOU
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts.WORKING
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Ask
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Agent
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.FooterKey
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Kind
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Progress
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.State
import se.soderbjorn.lunamux.effectiveActiveTabId

/** What the person is doing besides browsing; the overview is in exactly one of these. */
sealed interface OverviewMode {
    object Browsing : OverviewMode

    /** A question about [rowId] (resume it? start one?) waits for ↵. */
    data class Asking(val rowId: String, val recordId: String?, val enter: Enter) : OverviewMode

    /** Closing [rowId]'s session waits for a yes; [onClose] says which button has focus. */
    data class Confirming(val rowId: String, val onClose: Boolean = false) : OverviewMode

    /** The selected row's screen is shown. */
    object Peeking : OverviewMode

    /** [rowId] was asked to open a tab; the overview stays up until the active tab is not [fromTab]. */
    data class Resuming(val rowId: String, val fromTab: String?) : OverviewMode
}

/** The person's choices: the one input to [buildOverview] the server does not send. */
internal data class OverviewUi(
    val open: Boolean = false,
    val query: String = "",
    val selectedId: String? = null,
    val selectedHint: Int = 0,
    val showAllEarlier: Boolean = false,
    val mode: OverviewMode = OverviewMode.Browsing,
)

/** Everything the server pushed that the rows are made from. */
internal data class OverviewInputs(
    val sessions: List<AgentSessionSummary>,
    val tasks: List<AgentTask>,
    val layout: Layout,
    val matches: WindowEnvelope.AgentSessionMatches?,
    val inspection: WindowEnvelope.AgentSessionInspection?,
    val error: String?,
    val panels: Map<String, List<TaskPanelBlock>> = emptyMap(),
)

private const val TASK_PREFIX = "task:"
private const val OPEN_WORK_RANK = 2
private const val EARLIER_LIMIT = 5
private const val RECENT_MS = 3L * 24 * 60 * 60_000L
private const val WINDOW_MS = 14L * 24 * 60 * 60_000L
private const val MIN_QUERY = 2
private const val RESTART = "restart"
private const val STOPPED_WINDOW_MS = 24L * 60 * 60_000L

/**
 * The state behind the agents overview: which rows show, which is selected, and what ↵ does on it.
 *
 * The web and Android screens create one, call the mutators below for each key or click, collect
 * [stateFlow] and draw it. Rows are rebuilt by [buildOverview] from what the server pushed through
 * [windowState] plus the person's choices (query, selection, an open question or peek); nothing is
 * stored twice. Commands that need the server (jump, resume, close, search) go out through [send].
 *
 * @param windowState where the server's pushes (sessions, tasks, layout, search answers) arrive.
 * @param scope runs the rebuild on every push and the inspection request when the selection moves.
 * @param clock the time rows are aged against; tests pass a fixed one.
 * @param send sends a command to the server.
 * @see TaskPanelViewModel
 * @see AgentFacts
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /** What ↵ does on a row. */
    enum class Enter { JUMP, SHOW_WAITING, OPEN, RESUME, NONE }

    enum class Kind { NEEDS_YOU, WORKING, DONE, FINISHED }

    /** How far a task's plan is: [fraction] 0 to 1 for the bar, [count] as "13/14". */
    data class Progress(val fraction: Double, val count: String)

    /** One conversation of a task, listed under its row while the row is selected. */
    data class Agent(val recordId: String, val title: String, val doing: String, val state: String, val sinceMillis: Long)

    data class FooterKey(val key: String, val label: String)

    /** The question under a row before a resume or a start; [inTab] resumes in the pane it ran in. */
    data class Ask(val rowId: String, val tag: String, val question: String, val keys: List<FooterKey>, val inTab: Boolean = false)

    /**
     * One row of the overview. [tabName] is the name the person gave the tab it runs in (null for
     * an unnamed or closed tab); the row leads with it and [title] follows as the description.
     * [detail] is the headline (see [headlineOf]), or the snippet of a search hit. [extra] is what
     * else matters for opening it (its folder, a search hit's place, a resume note, an error).
     * [where] is the repo or folder, which NOW groups by.
     */
    data class Row(
        val id: String,
        val taskId: String?,
        val title: String,
        val where: String?,
        val kind: Kind,
        val stateWord: String,
        val detail: String,
        val extra: String,
        val tabName: String? = null,
        val progress: Progress? = null,
        val agents: List<Agent> = emptyList(),
        /** Lunamux stopped under it within the last day and it can be resumed: it lists under STOPPED BY THE RESTART. */
        val stopped: Boolean = false,
        val ask: Ask? = null,
        val sinceMillis: Long,
        val enter: Enter,
        val recordId: String?,
        val isLive: Boolean,
        val canClose: Boolean,
        val error: String? = null,
        val field: String? = null,
        val snippet: String? = null,
        val matchStart: Int = 0,
        val matchLength: Int = 0,
    ) {
        val needsYou: Boolean get() = kind == Kind.NEEDS_YOU
        val working: Boolean get() = kind == Kind.WORKING
        val finished: Boolean get() = kind == Kind.FINISHED
        val matchInTitle: Boolean get() = matchLength > 0 && snippet == null
        val matchInSnippet: Boolean get() = matchLength > 0 && snippet != null
    }

    /** The selected row's screen: live while its pane exists, else what was saved. */
    data class Peek(
        val live: Boolean,
        val title: String,
        val closedSinceMillis: Long?,
        val lines: List<String>,
        val loading: Boolean,
        val emptyText: String?,
    )

    data class Confirm(val rowId: String, val title: String, val warnings: List<String>, val cancelFocused: Boolean)

    data class State(
        val open: Boolean = false,
        val query: String = "",
        val matchMode: Boolean = false,
        val matches: List<Row> = emptyList(),
        val searching: Boolean = false,
        val now: List<Row> = emptyList(),
        val stopped: List<Row> = emptyList(),
        /** When the restart that stopped [stopped] happened. */
        val stoppedAtMillis: Long? = null,
        val recent: List<Row> = emptyList(),
        val fortnight: List<Row> = emptyList(),
        val earlierHidden: Int = 0,
        val needYouCount: Int = 0,
        val workingCount: Int = 0,
        val doneCount: Int = 0,
        val selectedId: String? = null,
        val peek: Peek? = null,
        val confirm: Confirm? = null,
        val ask: Ask? = null,
        val footer: List<FooterKey> = defaultFooter(Enter.JUMP),
    ) {
        val earlier: List<Row> get() = recent + fortnight
        val rows: List<Row> get() = if (matchMode) matches else now + stopped + recent + fortnight
        val selected: Int get() = rows.indexOfFirst { it.id == selectedId }
        val selectedRow: Row? get() = rows.firstOrNull { it.id == selectedId }
        val matchHeading: String?
            get() = if (!matchMode || searching) null else "${matches.size} ${if (matches.size == 1) "MATCH" else "MATCHES"}"
        val emptyText: String
            get() = when {
                matchMode && searching -> "Searching…"
                matchMode -> "Nothing matches “${query.trim()}”. Search covers titles, first prompts, branches, links and transcripts."
                else -> "No agent sessions yet"
            }
    }

    private var ui = OverviewUi()
    private var inspected: String? = null
    private val current = MutableStateFlow(State())

    val stateFlow: Flow<State> = current
    val state: State get() = current.value

    init {
        // The one place a server push enters; every other change comes through a mutator below.
        scope.launch {
            combine(
                listOf<Flow<Any?>>(
                    windowState.agentSessions, windowState.agentTasks, windowState.config,
                    windowState.agentSessionError, windowState.agentMatches, windowState.agentInspection,
                    windowState.taskPanels,
                ),
            ) { }.collect {
                rebuild()
            }
        }
    }

    /**
     * Builds the rows from the latest inputs and publishes them. Every mutator and every push ends
     * here, so the selected row is asked about (once per row) whenever the overview is open.
     */
    private fun rebuild() {
        val mode = ui.mode
        if (mode is OverviewMode.Resuming && windowState.config.value?.effectiveActiveTabId != mode.fromTab) ui = OverviewUi()
        var built = buildOverview(inputs(), ui, clock())
        // A question, confirmation or peek whose row is gone is withdrawn for good, so it cannot come back.
        val gone = when (ui.mode) {
            is OverviewMode.Asking -> built.ask == null
            is OverviewMode.Confirming -> built.confirm == null
            is OverviewMode.Peeking -> built.peek == null
            else -> false
        }
        if (gone) {
            ui = ui.copy(mode = OverviewMode.Browsing)
            built = buildOverview(inputs(), ui, clock())
        }
        current.value = built
        if (ui.open) scope.launch { inspectSelected() }
    }

    private fun inputs() = OverviewInputs(
        windowState.agentSessions.value, windowState.agentTasks.value, Layout(windowState.config.value),
        windowState.agentMatches.value, windowState.agentInspection.value, windowState.agentSessionError.value,
        windowState.taskPanels.value,
    )

    private fun change(edit: (OverviewUi) -> OverviewUi) {
        ui = edit(ui)
        rebuild()
    }

    /** Moving the selection or typing ends any question, confirmation or peek, but not a resume in flight. */
    private fun OverviewUi.browsing(): OverviewUi = if (mode is OverviewMode.Resuming) this else copy(mode = OverviewMode.Browsing)

    /** Opens the overview on a clean slate with the row that needs the person selected. */
    fun open() {
        windowState.clearAgentSessionError()
        inspected = null
        ui = OverviewUi(open = true)
        val first = buildOverview(inputs(), ui, clock())
        change { it.copy(selectedId = first.selectedId, selectedHint = maxOf(first.selected, 0)) }
    }

    /** Closes the overview and forgets the query, selection and any open question. */
    fun close() {
        windowState.clearAgentSessionError()
        inspected = null
        ui = OverviewUi()
        rebuild()
    }

    /** ⌘K: opens the overview, or closes it when it is open. */
    fun toggle() = if (state.open) close() else open()

    /** Typing in the search box: [query] replaces the old one and the selection starts over. */
    fun setQuery(query: String) {
        windowState.clearAgentSessionError()
        change { it.browsing().copy(query = query, selectedId = null, selectedHint = 0) }
    }

    /** Asks the server to search for the current query; the answer arrives with the next push. */
    suspend fun search() {
        val query = ui.query.trim()
        if (query.length >= MIN_QUERY) send(WindowCommand.SearchAgentSessions(query))
    }

    /** ↑/↓: moves the selection by [delta] rows, revealing the hidden earlier rows when stepping past the last one. */
    fun moveSelection(delta: Int) {
        if (state.rows.isEmpty()) return
        val at = maxOf(state.selected, 0)
        // Stepping past the last shown earlier row reveals the rest and moves onto them.
        if (delta > 0 && at == state.rows.lastIndex && !state.matchMode && state.earlierHidden > 0) showMoreEarlier()
        val rows = state.rows
        val next = (at + delta).coerceIn(0, rows.lastIndex)
        change { it.browsing().copy(selectedId = rows[next].id, selectedHint = next) }
    }

    /** A click or hover on row [rowId]: selects it (a no-op when it already is). */
    fun select(rowId: String) {
        if (state.ask?.rowId == rowId && state.selectedId == rowId) return
        val index = state.rows.indexOfFirst { it.id == rowId }
        if (index >= 0) change { it.browsing().copy(selectedId = rowId, selectedHint = index) }
    }

    /** "+ N more": shows every earlier row, not only the first few. */
    fun showMoreEarlier() = change { it.copy(showAllEarlier = true) }

    /** ↵: answers an open confirmation, else does what the selected row offers. */
    suspend fun activateSelected() {
        (ui.mode as? OverviewMode.Confirming)?.let { if (it.onClose) confirmClose() else cancelClose(); return }
        state.selectedRow?.let { activate(it) }
    }

    /** Does what [row] offers on ↵ or a click: go to it, show it, start it, or ask before resuming it. */
    suspend fun activate(row: Row) {
        when (row.enter) {
            Enter.JUMP -> windowState.agentSessions.value.firstOrNull { it.id == row.recordId }?.let { jump(it) }
            Enter.SHOW_WAITING -> row.recordId?.let { send(WindowCommand.ShowWaitingAgent(it)); close() }
            Enter.OPEN, Enter.RESUME -> if (state.ask?.rowId == row.id) answerAsk(row) else askAbout(row)
            Enter.NONE -> Unit
        }
    }

    private suspend fun jump(s: AgentSessionSummary) {
        val commands = Layout(windowState.config.value).jumpCommands(s)
        if (commands.isEmpty()) return
        commands.forEach { send(it) }
        close()
    }

    private fun askAbout(row: Row) {
        if (row.ask == null) return
        select(row.id)
        if (state.selectedId == row.id) change { it.copy(mode = OverviewMode.Asking(row.id, row.recordId, row.enter)) }
    }

    private suspend fun answerAsk(row: Row) {
        windowState.clearAgentSessionError()
        val from = windowState.config.value?.effectiveActiveTabId
        change { it.copy(mode = OverviewMode.Resuming(row.id, from)) }
        when (row.enter) {
            Enter.RESUME -> row.recordId?.let {
                val inTab = row.ask?.inTab == true
                send(WindowCommand.ResumeAgentSession(it, inTab = inTab))
                // Typed into a pane that is already there: the tab may be the one on screen, so there is no new tab to wait for.
                if (inTab) close()
            }
            Enter.OPEN -> row.taskId?.let { send(WindowCommand.OpenTask(it)) }
            else -> Unit
        }
    }

    /** ⇧↵: resumes everything the restart stopped, each in its own tab (the one it ran in when that is still free), and closes the overview. */
    suspend fun resumeStopped() {
        val rows = state.stopped.filter { it.recordId != null }
        if (rows.isEmpty()) return
        windowState.clearAgentSessionError()
        rows.forEach { send(WindowCommand.ResumeAgentSession(it.recordId!!, inTab = it.ask?.inTab == true)) }
        close()
    }

    /** Asks the server about the selected row (its resume note, uncommitted work, saved screen). */
    suspend fun inspectSelected(force: Boolean = false) {
        val row = state.selectedRow ?: return
        val recordId = row.recordId ?: return
        val key = "$recordId/${row.isLive}"
        if (!force && inspected == key) return
        inspected = key
        send(WindowCommand.InspectAgentSession(recordId))
    }

    /** →: shows what the selected row's conversation last said, and asks the server for its saved screen. */
    suspend fun peek() {
        if (state.selectedRow?.recordId == null) return
        change { it.copy(mode = OverviewMode.Peeking) }
        inspectSelected(force = true)
    }

    /** Re-reads the screen of a live row; called every couple of seconds while peeking. */
    suspend fun refreshPeek() {
        if (state.peek?.live == true) inspectSelected(force = true)
    }

    /** Ends a peek (→ again or esc), going back to browsing. */
    fun closePeek() = change { if (it.mode is OverviewMode.Peeking) it.copy(mode = OverviewMode.Browsing) else it }

    /** esc: steps out of a question, confirmation or peek first, then closes the overview. */
    fun back() {
        if (ui.mode is OverviewMode.Browsing || ui.mode is OverviewMode.Resuming) close()
        else change { it.copy(mode = OverviewMode.Browsing) }
    }

    /** ⌘⌫ or the close button: asks to close the selected row's session when it has one to close. */
    suspend fun requestClose() {
        val row = state.selectedRow?.takeIf { it.canClose } ?: return
        change { it.copy(mode = OverviewMode.Confirming(row.id)) }
        inspectSelected(force = true)
    }

    /** Tab or arrow in the close question: moves the focus between its two buttons. */
    fun moveConfirmFocus() =
        change { (it.mode as? OverviewMode.Confirming)?.let { c -> it.copy(mode = c.copy(onClose = !c.onClose)) } ?: it }

    /** Dismisses the close question without closing anything. */
    fun cancelClose() = change { if (it.mode is OverviewMode.Confirming) it.copy(mode = OverviewMode.Browsing) else it }

    suspend fun confirmClose() {
        val confirming = ui.mode as? OverviewMode.Confirming ?: return
        val recordId = state.rows.firstOrNull { it.id == confirming.rowId }?.recordId
        val session = windowState.agentSessions.value.firstOrNull { it.id == recordId }
        val sessionId = session?.let { Layout(windowState.config.value).spotOf(it)?.leaf?.sessionId }
        change { it.copy(mode = OverviewMode.Browsing) }
        if (sessionId != null) send(WindowCommand.CloseSession(sessionId))
    }

    companion object {
        fun defaultFooter(enter: Enter?, canPeek: Boolean = true, stopped: Int = 0): List<FooterKey> = listOfNotNull(
            FooterKey("↵", if (enter == Enter.SHOW_WAITING) SHOW_WAITING_HINT else "open").takeIf { enter != Enter.NONE },
            FooterKey("⇧↵", "resume all $stopped").takeIf { stopped > 0 },
            FooterKey("→", "peek").takeIf { canPeek },
            FooterKey("↑↓", "move"),
            FooterKey("esc", "close"),
        )

        const val SHOW_WAITING_HINT = "open · a tab until you leave it"
        const val STATE_NEEDS_YOU = "needs you"
        const val STATE_WORKING = "working"
        const val STATE_DONE = "done"
        const val STATE_FINISHED = "closed"
        const val MAIN_AGENT_TITLE = "Main conversation"
        const val NO_TRANSCRIPT_NOTE = "nothing to resume: its transcript is gone"
        const val MARKER = "◆"
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"

        /** The state word of a task whose main conversation waits on [n] of its agents. */
        fun agentsWorkingText(n: Int): String = if (n == 1) "1 agent working" else "$n agents working"
    }
}

// ---- the pure part ------------------------------------------------------------------------------

private class Ctx(val inputs: OverviewInputs, val ui: OverviewUi, val now: Long) {
    val layout get() = inputs.layout
    private val resumingRow = (ui.mode as? OverviewMode.Resuming)?.rowId
    fun errorOf(rowId: String): String? = inputs.error.takeIf { rowId == resumingRow }
    fun noteOf(recordId: String?): String? = inputs.inspection?.takeIf { it.recordId == recordId }?.resumeNote
}

/** A task together with its sessions and the decisions made about its row. */
private class TaskUnit(
    val task: AgentTask,
    val sessions: List<AgentSessionSummary>,
    val since: Long,
    val recency: Long,
    val inNow: Boolean,
    val started: Long,
    val enter: Enter,
    val target: AgentSessionSummary?,
    val mainId: String?,
    val resumeId: String?,
)

/** The rows, selection, peek, confirmation and footer for [inputs] and the person's choices [ui] at time [now]. */
internal fun buildOverview(inputs: OverviewInputs, ui: OverviewUi, now: Long): State {
    val c = Ctx(inputs, ui, now)
    val sessions = inputs.sessions
    val query = ui.query.trim()
    val matchMode = query.length >= MIN_QUERY
    val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
    fun inspectionOf(id: String) = inputs.inspection?.takeIf { it.recordId == id }

    val byTask = sessions.filter { it.taskId != null }.groupBy { it.taskId!! }
    val units = inputs.tasks.mapNotNull { unitOf(it, byKey, byTask[it.id].orEmpty()) }
    val unitsById = units.associateBy { it.task.id }
    val unitBySession = buildMap { units.forEach { u -> u.sessions.forEach { put(it.id, u); put(it.conversationId, u) } } }

    val answer = inputs.matches?.takeIf { matchMode && it.query == query }
    val matchRows = answer?.matches.orEmpty()
        .map { m -> m to (byKey[m.summary.id] ?: byKey[m.summary.conversationId] ?: m.summary) }
        .map { (m, s) -> Triple(m, s, s.taskId?.let(unitsById::get) ?: unitBySession[s.id]) }
        .distinctBy { (_, s, unit) -> unit?.task?.id ?: s.taskId ?: s.conversationId }
        .map { (m, s, unit) ->
            val base = if (unit != null) taskRow(unit, c)
            else sessionRow(s, earlier = s.state == ENDED, c = c, taskTitle = if (s.taskId != null) m.taskTitle ?: AgentFacts.titleOf(s) else null)
            matchRow(base, m, s, c, unit?.mainId)
        }

    // Singles are conversations of no task; a background one shows only while it waits.
    val claimed = units.flatMap { u -> u.sessions.map { it.id } }.toSet()
    val singles = sessions.filter {
        it.id !in claimed && !(it.nothingSaid && !AgentFacts.isLive(it)) && (!it.background || (it.state == NEEDS_YOU && AgentFacts.isLive(it)))
    }
    val nowSingles = singles.filter { AgentFacts.isLive(it) }
    // Grouped by repo, the groups and the rows in each in the order their work began, so no row moves when a state flips.
    val nowRows = (
        nowSingles.map { Triple(AgentFacts.startedOf(it), it.conversationId, sessionRow(it, earlier = false, c = c)) } +
            units.filter { it.inNow }.map { Triple(it.started, it.task.id, taskRow(it, c)) }
        ).sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        .groupBy { it.where }.values.flatten()

    val nowIds = nowSingles.mapTo(HashSet()) { it.id }
    var earlierAll = (
        singles.filter { it.id !in nowIds }.map { s -> sinceOf(s) to sessionRow(s, earlier = true, c = c) } +
            units.filter { !it.inNow }.map { it.recency to taskRow(it, c) }
        ).filter { now - it.first <= WINDOW_MS }.sortedByDescending { it.first }
    val stopped = earlierAll.filter { it.second.stopped }.map { it.second }
    earlierAll = earlierAll.filter { !it.second.stopped }
    val earlierShown = if (ui.showAllEarlier) earlierAll else earlierAll.take(EARLIER_LIMIT)
    val recent = earlierShown.filter { now - it.first <= RECENT_MS }.map { it.second }
    val fortnight = earlierShown.filter { now - it.first > RECENT_MS }.map { it.second }

    val listed = if (matchMode) matchRows else nowRows + stopped + recent + fortnight
    val selectedId = when {
        listed.isEmpty() -> null
        ui.selectedId != null && listed.any { it.id == ui.selectedId } -> ui.selectedId
        ui.selectedId != null -> listed[ui.selectedHint.coerceIn(0, listed.lastIndex)].id
        matchMode -> listed.first().id
        else -> listed.firstOrNull { it.needsYou }?.id ?: listed.first().id
    }
    val selectedRow = listed.firstOrNull { it.id == selectedId }
    val mode = ui.mode

    val peek = if (mode is OverviewMode.Peeking && selectedRow?.recordId != null) {
        val session = byKey[selectedRow.recordId]
        peekOf(
            live = selectedRow.isLive,
            title = session?.let(AgentFacts::titleOf) ?: selectedRow.title,
            closedSinceMillis = if (selectedRow.isLive) null else session?.let(AgentFacts::activityOf) ?: selectedRow.sinceMillis,
            said = inspectionOf(selectedRow.recordId)?.takeIf { it.live == selectedRow.isLive },
        )
    } else null

    val confirm = (mode as? OverviewMode.Confirming)?.let { m ->
        listed.firstOrNull { it.id == m.rowId }?.let { row ->
            AgentsOverviewBackingViewModel.Confirm(
                row.id, row.title,
                byKey[row.recordId]?.let { closeWarnings(it, inspectionOf(it.id)) }.orEmpty(),
                cancelFocused = !m.onClose,
            )
        }
    }
    val ask = (mode as? OverviewMode.Asking)?.let { m ->
        selectedRow?.takeIf { it.id == m.rowId && it.enter == m.enter && it.recordId == m.recordId }?.ask
    }
    val tally = AgentFacts.tally(sessions, inputs.layout).total
    return State(
        open = ui.open,
        query = ui.query,
        matchMode = matchMode,
        matches = if (matchMode) matchRows else emptyList(),
        searching = matchMode && answer == null,
        now = if (matchMode) emptyList() else nowRows,
        stopped = if (matchMode) emptyList() else stopped,
        stoppedAtMillis = stopped.maxOfOrNull { it.sinceMillis },
        recent = if (matchMode) emptyList() else recent,
        fortnight = if (matchMode) emptyList() else fortnight,
        earlierHidden = earlierAll.size - earlierShown.size,
        needYouCount = tally.waiting,
        workingCount = tally.working,
        doneCount = nowRows.count { it.kind == Kind.DONE },
        selectedId = selectedId,
        peek = peek,
        confirm = confirm,
        ask = ask,
        footer = ask?.keys ?: AgentsOverviewBackingViewModel.defaultFooter(
            selectedRow?.enter, canPeek = selectedRow?.recordId != null, stopped = if (matchMode) 0 else stopped.size,
        ),
    )
}

/** When a conversation last mattered: its end or last activity once finished, else its last state change. */
private fun sinceOf(s: AgentSessionSummary): Long =
    if (s.state == ENDED || s.state == AgentFacts.DONE) AgentFacts.activityOf(s) else s.stateSince

private fun rankOf(state: String) = when (state) {
    NEEDS_YOU -> 0
    WORKING -> 1
    else -> OPEN_WORK_RANK
}

private fun unitOf(t: AgentTask, byKey: Map<String, AgentSessionSummary>, hosted: List<AgentSessionSummary>): TaskUnit? {
    val main = t.mainConversation?.let(byKey::get)
    // The task's own main conversation counts even when the tracker has not tagged it with the task yet.
    val own = main?.takeIf { (it.taskId == null || it.taskId == t.id) && hosted.none { h -> h.id == it.id } }
    val sessions = hosted.sortedWith(compareBy({ AgentFacts.startedOf(it) }, { it.id })) + listOfNotNull(own)
    val mainLive = main != null && AgentFacts.isLive(main)
    val questionWaits = t.questionWaits && !mainLive && t.mainConversation != null
    if (sessions.isEmpty() && !t.hasOpen && !questionWaits) return null
    val rank = when {
        sessions.any { it.state == NEEDS_YOU } -> 0
        sessions.any { it.state == WORKING } -> 1
        else -> OPEN_WORK_RANK
    }
    val since = when {
        sessions.isEmpty() -> t.startedAtMillis ?: 0L
        rank == OPEN_WORK_RANK -> sessions.maxOf { it.stateSince }
        else -> sessions.filter { rankOf(it.state) == rank }.minOf { it.stateSince }
    }
    val open = sessions.filter { AgentFacts.isLive(it) }
    val started = t.startedAtMillis ?: sessions.minOfOrNull(AgentFacts::startedOf) ?: since
    val recency = sessions.maxOfOrNull { maxOf(it.stateSince, AgentFacts.activityOf(it)) } ?: t.startedAtMillis ?: 0L
    val resumeId = t.mainConversation.takeIf { main == null }
    val (enter, target) = enterOf(t, main, mainLive, sessions, open, resumeId)
    return TaskUnit(t, sessions, since, recency, open.isNotEmpty() || mainLive || questionWaits, started, enter, target, main?.id, resumeId)
}

/** Decides what ↵ does on a task's row and which of its conversations that targets. */
private fun enterOf(
    t: AgentTask,
    main: AgentSessionSummary?,
    mainLive: Boolean,
    sessions: List<AgentSessionSummary>,
    open: List<AgentSessionSummary>,
    resumeId: String?,
): Pair<Enter, AgentSessionSummary?> {
    val asking = open.filter { it.state == NEEDS_YOU }.minByOrNull { it.stateSince }
    val mainAsks = main != null && main.state == NEEDS_YOU
    // A live main conversation is always reachable: a hidden tab is shown until the person leaves it.
    if (mainLive && main != null && (asking == null || mainAsks)) {
        return (if (main.background) Enter.SHOW_WAITING else Enter.JUMP) to main
    } else if (asking != null) {
        return (if (asking.background) Enter.SHOW_WAITING else Enter.JUMP) to asking
    }
    val urgency = compareBy<AgentSessionSummary>(
        { rankOf(it.state) },
        { if (it.state == NEEDS_YOU) it.stateSince else -(it.lastActivityAt ?: it.stateSince) },
    )
    val listed = open.filter { !it.background }.minWithOrNull(urgency)
    return when {
        main != null && !main.noTranscript -> Enter.RESUME to main
        resumeId != null -> Enter.RESUME to null
        listed != null -> Enter.JUMP to listed
        t.hasOpen -> Enter.OPEN to null
        else -> {
            val resumable = sessions.filter { !it.noTranscript }.maxByOrNull(AgentFacts::activityOf)
            when {
                resumable != null || sessions.isEmpty() -> Enter.RESUME to resumable
                else -> Enter.NONE to (main ?: sessions.maxBy(AgentFacts::activityOf))
            }
        }
    }
}

private fun quoted(text: String) = "\"$text\""

private fun extraOf(vararg parts: String?): String = parts.filter { !it.isNullOrBlank() }.joinToString(" · ")

private fun taskRow(u: TaskUnit, c: Ctx): Row {
    val t = u.task
    val id = "$TASK_PREFIX${t.id}"
    val working = u.sessions.filter(AgentFacts::isWorking)
    val waitingAll = u.sessions.filter(AgentFacts::isWaiting)
    val waiting = waitingAll.minByOrNull { it.stateSince }
    val (kind, word) = when {
        waitingAll.size >= 2 -> Kind.NEEDS_YOU to AgentsOverviewBackingViewModel.needYouText(waitingAll.size)
        waiting != null -> Kind.NEEDS_YOU to AgentsOverviewBackingViewModel.STATE_NEEDS_YOU
        working.any { it.id == u.mainId } || (u.mainId == null && working.size == 1) -> Kind.WORKING to AgentsOverviewBackingViewModel.STATE_WORKING
        working.isNotEmpty() -> Kind.WORKING to AgentsOverviewBackingViewModel.agentsWorkingText(working.size)
        !u.inNow -> Kind.FINISHED to AgentsOverviewBackingViewModel.STATE_FINISHED
        else -> Kind.DONE to AgentsOverviewBackingViewModel.STATE_DONE
    }
    val blocks = c.inputs.panels[t.id].orEmpty()
    val main = u.sessions.firstOrNull { it.id == u.mainId }
    val detail = waiting?.let(::headlineOf)
        ?: t.status?.trim()?.takeIf { it.isNotEmpty() }
        ?: currentStepOf(blocks)
        ?: working.sortedByDescending { it.lastActivityAt ?: it.stateSince }.firstNotNullOfOrNull(::headlineOf)
        ?: (main ?: u.sessions.maxByOrNull(AgentFacts::activityOf))?.let(::headlineOf)
        ?: ""
    val target = u.target
    val single = u.sessions.singleOrNull()
    val repo = t.repo ?: u.sessions.takeIf { s -> s.isNotEmpty() && s.none { it.repo == null } }?.map { it.repo }?.distinct()?.singleOrNull()
    val error = c.errorOf(id)
    val where = repo ?: u.sessions.map(AgentFacts::folderOf).distinct().singleOrNull()
    val cutOff = target?.takeIf { !u.inNow && u.enter == Enter.RESUME && stoppedByRestart(it, c.now) }
    return Row(
        id = id,
        taskId = t.id,
        title = t.title,
        where = where,
        kind = kind,
        stateWord = word,
        detail = cutOff?.let(::wasLine) ?: detail,
        stopped = cutOff != null,
        extra = extraOf(
            folderNote(target, where), c.noteOf(target?.id),
            AgentsOverviewBackingViewModel.NO_TRANSCRIPT_NOTE.takeIf { u.enter == Enter.NONE && target?.noTranscript == true },
            error,
        ),
        tabName = c.tabNameOf(main?.takeIf(AgentFacts::isLive) ?: target, t.title),
        progress = progressOf(blocks),
        agents = u.sessions.filter(AgentFacts::isLive).takeIf { it.size > 1 || it.any { s -> s.id != u.mainId } }.orEmpty().sortedBy { it.id != u.mainId }.map { s ->
            Agent(s.id, if (s.id == u.mainId) AgentsOverviewBackingViewModel.MAIN_AGENT_TITLE else AgentFacts.titleOf(s), headlineOf(s).orEmpty(), s.state, s.stateSince)
        },
        ask = askOf(id, u.enter, own = if (target != null) target.id == u.mainId else u.resumeId != null, inTab = target?.inTab == true),
        sinceMillis = if (!u.inNow) u.recency else u.since,
        enter = u.enter,
        recordId = target?.id ?: u.resumeId.takeIf { u.enter == Enter.RESUME },
        isLive = target != null && AgentFacts.isLive(target),
        canClose = single != null && AgentFacts.isLive(single) && !single.background,
        error = error,
    )
}

private fun sessionRow(s: AgentSessionSummary, earlier: Boolean, c: Ctx, taskTitle: String? = null): Row {
    val live = AgentFacts.isLive(s)
    val id = if (taskTitle != null) "$TASK_PREFIX${s.taskId}" else s.conversationId
    val needs = AgentFacts.isWaiting(s)
    val works = AgentFacts.isWorking(s)
    val enter = when {
        live && s.background -> Enter.SHOW_WAITING
        live -> Enter.JUMP
        s.noTranscript -> Enter.NONE
        else -> Enter.RESUME
    }
    val error = c.errorOf(id)
    val title = taskTitle ?: AgentFacts.titleOf(s)
    val (kind, word) = when {
        needs -> Kind.NEEDS_YOU to AgentsOverviewBackingViewModel.STATE_NEEDS_YOU
        works -> Kind.WORKING to AgentsOverviewBackingViewModel.STATE_WORKING
        earlier -> Kind.FINISHED to AgentsOverviewBackingViewModel.STATE_FINISHED
        else -> Kind.DONE to AgentsOverviewBackingViewModel.STATE_DONE
    }
    return Row(
        id = id,
        taskId = s.taskId.takeIf { taskTitle != null },
        title = title,
        where = s.repo ?: AgentFacts.folderOf(s),
        kind = kind,
        stateWord = word,
        detail = if (enter == Enter.RESUME && stoppedByRestart(s, c.now)) wasLine(s) else headlineOf(s).orEmpty(),
        stopped = enter == Enter.RESUME && stoppedByRestart(s, c.now),
        extra = extraOf(folderNote(s, s.repo ?: AgentFacts.folderOf(s)), c.noteOf(s.id), AgentsOverviewBackingViewModel.NO_TRANSCRIPT_NOTE.takeIf { enter == Enter.NONE }, error),
        tabName = c.tabNameOf(s, title),
        ask = askOf(id, enter, own = false, inTab = s.inTab),
        sinceMillis = sinceOf(s),
        enter = enter,
        recordId = s.id,
        isLive = live,
        canClose = live && !s.background,
        error = error,
    )
}

/** Turns a task or conversation row into the row for a search hit: where it matched, and the snippet. */
private fun matchRow(base: Row, m: AgentSessionMatch, s: AgentSessionSummary, c: Ctx, mainId: String?): Row {
    val live = AgentFacts.isLive(s)
    val enter = when {
        live -> Enter.JUMP
        s.noTranscript -> Enter.NONE
        else -> Enter.RESUME
    }
    val sessionTitle = AgentFacts.titleOf(s)
    // A hit in the title is stale when the title has changed since the server matched it.
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
    val place = "in ${fieldLabel(m.field)}" + if (sessionTitle != base.title && m.field != "task") " of $sessionTitle" else ""
    return base.copy(
        detail = snippet ?: base.detail,
        extra = extraOf(
            AgentsOverviewBackingViewModel.NO_TRANSCRIPT_NOTE.takeIf { enter == Enter.NONE } ?: m.resumeNote ?: c.noteOf(s.id),
            base.error, place,
        ),
        ask = askOf(base.id, enter, own = s.id == mainId, inTab = s.inTab),
        enter = enter,
        recordId = s.id,
        isLive = live,
        field = m.field,
        snippet = snippet,
        matchStart = if (highlight) m.start else 0,
        matchLength = if (highlight) m.length else 0,
    )
}

private fun fieldLabel(field: String): String = when (field) {
    "task" -> "task title"
    "prompt" -> "first prompt"
    "screen" -> "last screen"
    else -> field
}

/**
 * The one line that says where a conversation is, the same on every screen: the question while it
 * needs the person, what it is doing while it works (in its own words when it gave a status, else
 * its tool call), and the last thing it said once it is done or closed. Null when it has nothing to say. Called for overview rows and the task panel's header.
 */
internal fun headlineOf(s: AgentSessionSummary): String? {
    val now = s.nowLine?.trim()?.takeIf { it.isNotEmpty() }
    return when (s.state) {
        NEEDS_YOU -> (AgentSessionText.previewOf(s.question) ?: now)?.let(::quoted)
        WORKING -> s.status?.trim()?.takeIf { it.isNotEmpty() } ?: now
        else -> s.lastSaid?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** [s]'s folder for the selected row, when it says more than the repo or folder name [where] the row already sits under (a worktree, a subfolder). */
private fun folderNote(s: AgentSessionSummary?, where: String?): String? =
    s?.cwdShown?.takeIf { it != where && it.trimEnd('/').substringAfterLast('/') != where }

/** Whether Lunamux stopped under [s] within the last day. */
private fun stoppedByRestart(s: AgentSessionSummary, now: Long): Boolean =
    s.state == ENDED && s.endReason == RESTART && now - (s.endedAt ?: s.stateSince) <= STOPPED_WINDOW_MS

/** What a conversation the restart stopped says on its row: the state it was in, then the last thing it said. */
private fun wasLine(s: AgentSessionSummary): String = extraOf(
    when (s.endedFrom) {
        WORKING -> "was working"
        NEEDS_YOU -> "was waiting for you"
        else -> "was done"
    },
    headlineOf(s),
)

/** The steps of a task's plan, when an agent wrote them. */
internal fun stepsOf(blocks: List<TaskPanelBlock>): List<TaskStep> =
    blocks.firstNotNullOfOrNull { it.content as? TaskBlockContent.Steps }?.items.orEmpty()

/**
 * How far a task is, as the row and the panel's bar show it: the count its progress block gives,
 * else the done steps of its plan; null without either.
 */
internal fun progressOf(blocks: List<TaskPanelBlock>): Progress? {
    val p = blocks.firstNotNullOfOrNull { it.content as? TaskBlockContent.Progress }
    val steps = stepsOf(blocks)
    val done = p?.done?.takeIf { p.total != null } ?: steps.count { it.state == TaskStep.DONE }.takeIf { steps.isNotEmpty() } ?: return null
    val total = (p?.total ?: steps.size).takeIf { it > 0 } ?: return null
    return Progress((done.toDouble() / total).coerceIn(0.0, 1.0), "$done/$total")
}

/** The step in hand: the progress block's phase or label, else the plan's current step. */
internal fun currentStepOf(blocks: List<TaskPanelBlock>): String? =
    blocks.firstNotNullOfOrNull { b -> (b.content as? TaskBlockContent.Progress)?.let { it.phase ?: it.label }?.trim()?.takeIf { it.isNotEmpty() } }
        ?: stepsOf(blocks).firstOrNull { it.state == TaskStep.CURRENT }?.text

private val DEFAULT_TAB_NAME = Regex("^Tab \\d+$")

/**
 * The name the person gave the listed tab [s] runs in (or ran in, when a restart stopped it and the
 * tab is still there); null for a default name, a background tab, or one that only repeats [title].
 */
private fun Ctx.tabNameOf(s: AgentSessionSummary?, title: String): String? {
    if (s == null || !(AgentFacts.isLive(s) || stoppedByRestart(s, now))) return null
    val name = layout.spotOf(s)?.takeIf { it.listed }?.tab?.title?.trim() ?: return null
    return name.takeIf { it.isNotEmpty() && !DEFAULT_TAB_NAME.matches(it) && !it.equals(title.trim(), ignoreCase = true) }
}

private fun askOf(rowId: String, enter: Enter, own: Boolean, inTab: Boolean): Ask? = when (enter) {
    Enter.RESUME -> Ask(
        rowId,
        if (own) "conversation closed" else "session closed",
        if (inTab) "Resume it in its tab?" else "Resume it in a new tab?",
        listOf(FooterKey("↵", "resume"), FooterKey("esc", "cancel")),
        inTab,
    )
    Enter.OPEN -> Ask(rowId, "no conversation", "Start one in a new tab?", listOf(FooterKey("↵", "start"), FooterKey("esc", "cancel")))
    else -> null
}

/** What closing the session loses: work in progress, a question unanswered, uncommitted or unpushed work. */
private fun closeWarnings(s: AgentSessionSummary, inspection: WindowEnvelope.AgentSessionInspection?): List<String> {
    val out = mutableListOf<String>()
    if (s.state == WORKING) out += "It is working right now" + (s.nowLine?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
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
        out += parts.joinToString(" · ") + inspection?.branch?.let { " on $it" }.orEmpty() + " stay in its folder."
    }
    return out
}

/**
 * The screen popover for one session, shared by the overview and the task panel: [said] is the
 * server's answer for it (null while it loads), already checked to be live or saved to match [live].
 */
internal fun peekOf(live: Boolean, title: String, closedSinceMillis: Long?, said: WindowEnvelope.AgentSessionInspection?) =
    AgentsOverviewBackingViewModel.Peek(
        live = live,
        title = title,
        closedSinceMillis = closedSinceMillis,
        lines = said?.screen.orEmpty(),
        loading = said == null,
        emptyText = if (said != null && said.screen.isEmpty()) (if (live) "(the screen is empty)" else "(no saved screen)") else null,
    )
