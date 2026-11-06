/**
 * Backing ViewModel for the agents overview: the ⌘⇧A overlay that lists every agentic-CLI
 * conversation (Claude Code, Codex, Gemini) Lunamux knows about.
 *
 * The server pushes one list of conversation summaries and one of tasks; this ViewModel turns them
 * into the sections the overlay shows (NOW, then EARLIER in two recency bands: the last few
 * days and the last two weeks) and the keyboard selection. Typing two or more
 * characters switches the overlay to the server's search over every session ever recorded
 * (titles, first prompts, branches, links and transcripts). The ViewModel carries out what a
 * row offers: jump to its pane, open a waiting background tab as a marked tab, resume a closed
 * conversation in a new tab (after asking), and close a session after a confirmation that names
 * what would be lost. The view only renders [AgentsOverviewBackingViewModel.State] and forwards
 * key presses.
 *
 * Every row is one conversation, drawn as one aligned line, and a conversation is on one row
 * however many times it was resumed. A task's row is its main conversation's row; the task's other
 * conversations, in a visible tab or a background one, never get a row of their own: the row counts
 * them in its state word ("needs you", "2 need you", "2 working", "working", "done" or
 * "finished") and shows the task's status as its detail. The selected row has more below it: the
 * status in full when the row cut it (see [AgentsOverviewBackingViewModel.Row.statusFull]), then
 * chips for the folder and the tab of the session ↵ opens, then a line of what else matters for
 * opening it ([AgentsOverviewBackingViewModel.Row.extra]: where a search matched, a resume note or
 * an error), never the detail itself; a row with nothing to add has an empty extra, no chips and
 * no block. EARLIER lists its newest five rows and a "+ N more" line for the rest.
 *
 * What ↵ opens follows one order. A task whose main conversation is live in a tab jumps to it,
 * unless a background conversation of the task waits for the person and the main one does not: that
 * one is opened as a marked tab ([Enter.SHOW_WAITING]). A task whose main conversation is closed
 * asks whether to resume it in a new tab, and a task with no conversation asks whether to run its
 * `open` action. Nothing is started or resumed by the first ↵: it asks first
 * ([AgentsOverviewBackingViewModel.Ask]) and the second ↵ does it. A conversation in no task jumps
 * to its pane, or asks to resume it.
 *
 * NOW is every live conversation in a listed tab, once each, and every task with a live conversation
 * of its own, wherever it runs. Everything else is in EARLIER, by recency: a task's recency is its
 * newest conversation's activity. A conversation at its prompt with nothing asked is "done" and
 * stays in NOW while its pane is open; it moves to EARLIER only when the pane closes. NOW keeps
 * its rows in the order their work started, never by state, so a row does not move when its
 * state flips.
 *
 * The selection is a row id, not an index, so it stays on its row while rows appear and
 * disappear, and it opens on the first row that needs the person.
 *
 * @see se.soderbjorn.lunamux.AgentSessionSummary
 * @see se.soderbjorn.lunamux.AgentTask
 * @see WindowStateRepository.agentSessions
 * @see WindowStateRepository.agentTasks
 * @see AgentCounts
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
 * @property tabTitle the tab's name.
 * @property tabNumber the tab's 1-based place among the listed tabs of its world, or null for a
 *   background tab, which no tab bar lists.
 * @property leaf the pane's leaf, used for its custom name.
 */
private data class PaneLocation(
    val worldId: String?,
    val worldName: String?,
    val tabId: String,
    val tabTitle: String,
    val tabNumber: Int?,
    val leaf: LeafNode,
)

/**
 * ViewModel behind the agents overview overlay.
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
 *   working sessions have gone quiet. Tests pass a fixed one.
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

        /**
         * Open the waiting background conversation [Row.recordId] as a marked tab: the server lists
         * its tab, makes it active and hides it again once the conversation works on.
         */
        SHOW_WAITING,

        /** Run the task's `open` action: a new tab in the task's folder. Asks first. */
        OPEN,

        /** Resume the conversation of [Row.recordId] in a new tab. Asks first. */
        RESUME,

        /** Nothing: the row has no session Lunamux can open and no `open` action. */
        NONE,
    }

    /**
     * One row of the overlay: a task, or a session that is a task of one.
     *
     * @property id `task:<id>` for a task, else the conversation id of the lone conversation; what
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
     * @property stateWord exactly one of [STATE_NEEDS_YOU] (one conversation waits on the person),
     *   "N need you" (two or more do), "N working" (two or more conversations are moving,
     *   background ones included), [STATE_WORKING] (one is moving), [STATE_DONE] (in NOW, nothing
     *   moving or waiting) or [STATE_FINISHED] (in EARLIER, nothing moving or waiting).
     * @property detail what the row says after its state word: the task's status; without one the
     *   question a session asks, in quotes, else the line a moving session last reported, else
     *   empty. A lone session has no status. In a search result it is the text around the match.
     * @property extra the text lines under the selected row, without [detail]: as they apply, what
     *   Resume will do when it is not the plain case ("2 uncommitted changes · 1 unpushed commit"),
     *   a note that there is nothing to jump to, the error of the last failed resume or open, and
     *   where a search matched ("in transcript of Reviewer"). Empty when the row has none of these.
     * @property statusFull the status in full ([detail]) while the view has told the model that the
     *   row's cell cut it short (see [setDetailCut]); null otherwise, so the selected row never
     *   repeats words that are already on its line.
     * @property chips the folder and tab of the session [enter] opens, in that order; see [Chip].
     *   Empty when there is no such session or nothing to say about it.
     * @property ask what the first ↵ asks before [enter] starts or resumes anything, or null when
     *   ↵ acts at once.
     * @property sinceMillis epoch millis the row's age counts from: the time the task entered its
     *   current state for a task in NOW, its newest session's activity (when it ended, for an
     *   ended one) for a task in EARLIER; the view turns it into an age.
     * @property enter what ↵ does.
     * @property recordId the session ↵ and → act on: the one [enter] jumps to, shows or
     *   resumes, and what a peek and the inspection ask the server about. Null when [enter] is
     *   [Enter.OPEN], or [Enter.NONE] for a session outside Lunamux's panes; a main conversation in a
     *   background tab with nothing to open keeps its id so it can be peeked at. For a search result it
     *   is the session that matched.
     * @property isLive whether [recordId]'s pane is open.
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
        val statusFull: String? = null,
        val chips: List<Chip> = emptyList(),
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
        /** Whether a conversation of the row waits on the person. */
        val needsYou: Boolean get() = marker == MARKER

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
     * One of the facts shown as a chip under the selected row.
     *
     * @property kind which fact it is, so the view can tell them apart.
     * @property text what the chip says.
     */
    data class Chip(val kind: ChipKind, val text: String)

    /** What a [Chip] tells about the session ↵ opens. */
    enum class ChipKind {
        /** The folder the session works in, with the home folder written `~`. */
        FOLDER,

        /** The tab the session sits in, with the world's name after it for a tab in another world. */
        TAB,
    }

    /**
     * One key of the footer, drawn as the key and the words after it.
     *
     * @property key the key as it is drawn ("↵", "↑↓", "esc").
     * @property label what it does ("open", "move", "close").
     */
    data class FooterKey(val key: String, val label: String)

    /**
     * The question the first ↵ asks before a conversation is resumed or a task is opened. The
     * selected row's expanded part shows it in place of the status and the text lines; the chips
     * stay, so the person sees where the answer will land.
     *
     * @property rowId the row it is about.
     * @property tag the dim tag that names what is missing ("conversation closed"), never a state
     *   that would sound like the whole task's.
     * @property question the question ("Resume it in a new tab?").
     * @property keys the keys that answer it, which are also the footer while it shows.
     */
    data class Ask(val rowId: String, val tag: String, val question: String, val keys: List<FooterKey>)

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
     * @property needYouCount conversations asking for the user, background ones included; the
     *   footer's "waiting" number (see [AgentCounts]).
     * @property workingCount conversations that work and are moving (not quiet, see
     *   [isMoving]), background ones included; the footer's spinner number.
     * @property selectedId the id of the highlighted row, or null when there are no rows.
     * @property peek the screen popover, or null while it is closed.
     * @property confirm the close confirmation, or null while none is asked.
     * @property ask the question the first ↵ asked on the selected row, or null while none is open.
     * @property footer the keys the footer shows: the selected row's ↵, ↑↓ and esc, or only the
     *   answers to [ask] while one is open.
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
        val ask: Ask? = null,
        val footer: List<FooterKey> = defaultFooter(Enter.JUMP),
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
        val askRowId: String? = null,
        val askRecordId: String? = null,
        val askEnter: Enter? = null,
        val cutRowId: String? = null,
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
     * Called by the web view's periodic redraw: a working session goes quiet once five minutes pass
     * without a sign of life, which no server message announces.
     *
     * @see QUIET_AFTER_MS
     */
    fun tick() = clockTick.update { it + 1 }

    /** The row and liveness last inspected, so a redraw does not ask the server again. */
    private var inspected: String? = null

    /**
     * Render-ready state; emits whenever the sessions, layout, error or view inputs change.
     * Collected by the view to redraw the overlay.
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
        val input = Inputs(
            v[0] as List<AgentSessionSummary>,
            v[1] as List<AgentTask>,
            v[2] as WindowConfig?,
            v[3] as String?,
            v[4] as WindowEnvelope.AgentSessionMatches?,
            v[5] as WindowEnvelope.AgentSessionInspection?,
            v[6] as Ui,
        )
        build(input).also { settleAsk(it, input.ui) }
    }.onEach {
        settleResume()
        if (it.open) scope.launch { inspectSelected() }
    }

    /** The state right now, for key handlers that must act on what is on screen. */
    val state: State
        get() {
            settleResume()
            val input = inputs()
            return build(input).also { settleAsk(it, input.ui) }
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
     * Forget an ask that no longer shows, so it cannot come back to life later: when a refresh
     * changed what ↵ would act on, the person's answer was meant for something else.
     *
     * Only a state built from the view inputs as they are now may withdraw the ask: the state
     * [stateFlow] builds can be one step behind [ui], and a state that never saw a fresh ask has no
     * say about it.
     *
     * @param built the state just built.
     * @param from the view inputs [built] was built from.
     */
    private fun settleAsk(built: State, from: Ui) {
        val current = ui.value
        if (current.askRowId != null && built.ask == null && current == from) {
            ui.update { it.copy(askRowId = null, askRecordId = null, askEnter = null) }
        }
    }

    /**
     * Show the overlay with an empty search and the first ◆ row in list order selected (the
     * first row when nothing needs the user). Called by ⌘⇧A, the only way in.
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
     * empty). Closes the peek, any question and any confirmation, which belonged to the old selection.
     *
     * With two or more characters the overlay switches to search results; the
     * view then calls [search] once typing pauses.
     *
     * @param query what the user typed.
     */
    fun setQuery(query: String) {
        windowState.clearAgentSessionError()
        ui.update { it.withoutAsk().copy(query = query, selectedId = null, selectedHint = 0, peekOpen = false, confirmRowId = null, cutRowId = null) }
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

    /** This input with no question asked; every change of the selection starts from here. */
    private fun Ui.withoutAsk() = copy(askRowId = null, askRecordId = null, askEnter = null)

    /**
     * Tell the model whether the selected row's cell cut its status short (or hides it, as a narrow
     * window does), which decides whether [Row.statusFull] is set. The view measures the cell, so
     * only it can know; it calls this after every draw of the selected row. An unchanged answer
     * changes nothing, so the draw settles. The answer is forgotten when the selection moves.
     *
     * @param rowId the selected row the answer is about; an id that is not selected is ignored.
     * @param cut true when the cell cut the status or hides it.
     */
    fun setDetailCut(rowId: String, cut: Boolean) {
        val selected = state.selectedId
        ui.update {
            when {
                cut && selected == rowId -> it.copy(cutRowId = rowId)
                !cut && it.cutRowId == rowId -> it.copy(cutRowId = null)
                else -> it
            }
        }
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
            ui.update { it.withoutAsk().copy(selectedId = revealed[onto].id, selectedHint = onto, peekOpen = false, confirmRowId = null, cutRowId = null) }
            return
        }
        val next = (at + delta).coerceIn(0, rows.lastIndex)
        ui.update { it.withoutAsk().copy(selectedId = rows[next].id, selectedHint = next, peekOpen = false, confirmRowId = null, cutRowId = null) }
    }

    /**
     * Highlight the row with [rowId], as a mouse click does. Does nothing for an id that is not
     * listed, and nothing for the row that is asking its question: a click on it is the answer,
     * and selecting it again would withdraw the question the click is meant to answer.
     *
     * Called by the view when a row is clicked, before it opens the row.
     *
     * @param rowId the [Row.id] to select.
     */
    fun select(rowId: String) {
        val current = state
        if (current.ask?.rowId == rowId && current.selectedId == rowId) return
        val rows = current.rows
        val index = rows.indexOfFirst { it.id == rowId }
        if (index >= 0) ui.update { it.withoutAsk().copy(selectedId = rowId, selectedHint = index, peekOpen = false, confirmRowId = null, cutRowId = null) }
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
        ui.update { it.withoutAsk().copy(peekOpen = true) }
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
     * Step back one level: cancel a question, else a close confirmation, else close the peek, else
     * close the overlay. Called for esc.
     */
    fun back() {
        val current = state
        when {
            current.ask != null -> ui.update { it.withoutAsk() }
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
        ui.update { it.withoutAsk().copy(confirmRowId = row.id, confirmOnClose = false, peekOpen = false) }
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
     * Open [row] the way [Row.enter] says: jump to its pane, show a waiting background tab, or, for a
     * conversation to resume or a task to open, ask first and do it on the second call while the
     * question is still showing. Does nothing for [Enter.NONE].
     *
     * Called when a row is clicked and for ↵ (through [activateSelected]).
     *
     * @param row the row to open.
     */
    suspend fun activate(row: Row) {
        val recordId = row.recordId
        when (row.enter) {
            Enter.JUMP -> recordId?.let { jumpToRecord(it) }
            Enter.SHOW_WAITING -> recordId?.let { showWaiting(it) }
            Enter.OPEN, Enter.RESUME -> if (state.ask?.rowId == row.id) answerAsk(row) else askAbout(row)
            Enter.NONE -> Unit
        }
    }

    /**
     * Select [row] and raise its question, remembering which session ↵ would act on so a refresh
     * that changes it cancels the question instead of answering for something else.
     *
     * @param row a row with [Row.ask].
     */
    private fun askAbout(row: Row) {
        if (row.ask == null) return
        select(row.id)
        if (state.selectedId != row.id) return
        ui.update { it.copy(askRowId = row.id, askRecordId = row.recordId, askEnter = row.enter) }
    }

    /**
     * Do what the question asked: resume the row's conversation, or open its task when there is
     * none to resume.
     *
     * @param row the row the question is about.
     */
    private suspend fun answerAsk(row: Row) {
        ui.update { it.withoutAsk() }
        val recordId = row.recordId
        when (row.enter) {
            Enter.RESUME -> recordId?.let { resumeRecord(row.id, it) }
            Enter.OPEN -> openTask(row)
            else -> Unit
        }
    }

    /**
     * Open the background conversation [recordId] as a marked tab and leave the overlay, which
     * would cover it. The server makes the tab listed and active; it hides it again once the
     * conversation stops waiting.
     *
     * @param recordId the record of the waiting conversation.
     * @see WindowCommand.ShowWaitingAgent
     */
    private suspend fun showWaiting(recordId: String) {
        send(WindowCommand.ShowWaitingAgent(recordId))
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
     * A task with its conversations, ready to become a row.
     *
     * @property task the task as the server sent it.
     * @property sessions its conversations: the sessions that carry its id, oldest first, and its
     *   main conversation when no session of the task is it.
     * @property waiting the conversations that wait on the person and sit in a pane, which are the
     *   ones [AgentCounts] counts.
     * @property since the time that places the task among those in NOW: since the oldest conversation
     *   that needs the person (or works) changed state, else the newest change of any conversation.
     * @property recency epoch millis of the newest activity of any conversation of the task: when an
     *   ended one ended, the last activity of an open one. Decides the EARLIER band and the order
     *   inside it.
     * @property inNow whether the task belongs in NOW: one of its conversations is live in a pane the
     *   layout holds. Every other task is in EARLIER.
     * @property started epoch millis the task started, which fixes its place in NOW.
     * @property enter what ↵ does on the task's row.
     * @property target the session [enter] acts on, or null for [Enter.OPEN] and for [Enter.NONE]
     *   unless the main conversation is live in a background tab (then it is that conversation).
     * @property resumeId the stored id of the task's main conversation when no summary holds it any
     *   more (it is older than the ones kept), else null. ↵ resumes the conversation by this id.
     * @property mainId the record id of the task's main conversation, or null when Lunamux does not
     *   list it.
     */
    private class TaskUnit(
        val task: AgentTask,
        val sessions: List<AgentSessionSummary>,
        val waiting: List<AgentSessionSummary>,
        val since: Long,
        val recency: Long,
        val inNow: Boolean,
        val started: Long,
        val enter: Enter,
        val target: AgentSessionSummary?,
        val mainId: String?,
        val resumeId: String?,
    ) {
        /** Whether the task is in EARLIER: every place that draws a task there asks this. */
        val inEarlier: Boolean get() = !inNow
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
        // A conversation is found by its conversation id as well as by the id of the record that stands
        // for it: a task's main conversation and a search match may name either.
        val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
        fun inspectionOf(id: String) = inspection?.takeIf { it.recordId == id }

        val sessionsByTask = sessions.filter { it.taskId != null }.groupBy { it.taskId!! }
        val units = taskList.mapNotNull { t -> unitOf(t, byKey, sessionsByTask[t.id].orEmpty(), c) }
        val unitsById = units.associateBy { it.task.id }

        // The server lists matches best first; the first match of a task is the one that stands for it.
        // The task's main conversation often carries no task id, so a match on it is routed to its task
        // by the conversations the task holds; otherwise it would be a row of its own beside the task.
        val unitBySession = buildMap {
            units.forEach { u -> u.sessions.forEach { put(it.id, u); put(it.conversationId, u) } }
        }
        val answer = matches?.takeIf { matchMode && it.query == query }
        val matchRows = answer?.matches.orEmpty()
            .map { m -> m to (byKey[m.summary.id] ?: byKey[m.summary.conversationId] ?: m.summary) }
            .map { (m, s) -> Triple(m, s, s.taskId?.let(unitsById::get) ?: unitBySession[s.id]) }
            .distinctBy { (_, s, unit) -> unit?.task?.id ?: s.taskId ?: s.conversationId }
            .map { (m, s, unit) ->
                val base = when {
                    unit != null -> taskRow(unit, c)
                    // A task older than the pushed list is still one row, named by the title the search found.
                    else -> sessionRow(s, earlier = s.state == ENDED || !c.live(s), c = c, taskTitle = if (s.taskId != null) m.taskTitle ?: titleOf(s) else null)
                }
                matchRow(base, m, s, c, unit?.mainId)
            }

        // A conversation a task unit holds is drawn inside it, not again as a lone conversation. A
        // conversation an agent runs out of sight gets no row of its own while it works or rests: it is
        // counted, and a task that holds it shows it. One that waits for the person and that no task
        // holds does get a row, because ↵ on it is the only way to its question.
        val claimed = units.flatMap { u -> u.sessions.map { it.id } }.toSet()
        val singles = sessions.filter { it.id !in claimed && (!it.background || (it.state == NEEDS_YOU && c.live(it))) }

        // NOW is every live conversation in a tab, once each, whatever its state, and every task with a
        // live conversation. NOW keeps the order work started in, for tasks and lone conversations alike,
        // so a row never moves because its state changed. Rows that started together keep the order of
        // their ids.
        class Placed(val started: Long, val tie: String, val row: Row)
        val nowSingles = singles.filter { c.live(it) }
        val urgent = buildList {
            nowSingles.forEach { add(Placed(startedOf(it), it.conversationId, sessionRow(it, earlier = false, c = c))) }
            units.filter { it.inNow }.forEach { add(Placed(it.started, it.task.id, taskRow(it, c))) }
        }.sortedWith(compareBy<Placed> { it.started }.thenBy { it.tie })
        val now = urgent.map { it.row }

        // EARLIER is built from tasks and lone conversations, each placed by its newest activity:
        // within RECENT_MS it is in the first band, up to WINDOW_MS in the second, older not listed.
        class Earlier(val recency: Long, val row: Row)
        val nowIds = nowSingles.mapTo(HashSet()) { it.id }
        val earlierUnits = (
            singles.filter { it.id !in nowIds }
                // A lone conversation's age is the very time its band is chosen by (see sinceOf), so a row
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
        // The view measured whether the selected row's cell cut its status; only then does the row carry it in full.
        fun Row.measured(): Row = if (id == selectedId && id == ui.cutRowId && detail.isNotEmpty()) copy(statusFull = detail) else this

        val peek = if (ui.peekOpen && selectedRow?.recordId != null) {
            val session = byKey[selectedRow.recordId]
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
            )
        } else null

        val confirmRow = ui.confirmRowId?.let { id -> listed.firstOrNull { it.id == id } }
        // A question stands only while ↵ would still act on what it was asked about.
        val ask = selectedRow?.takeIf {
            it.id == ui.askRowId && it.enter == ui.askEnter && it.recordId == ui.askRecordId
        }?.ask
        val footer = ask?.keys ?: defaultFooter(selectedRow?.enter)
        val confirm = confirmRow?.let { row ->
            Confirm(
                rowId = row.id,
                title = row.title,
                warnings = byKey[row.recordId]?.let { closeWarnings(it, inspectionOf(it.id)) }.orEmpty(),
                cancelFocused = !ui.confirmOnClose,
            )
        }

        // Once a resume has switched to the new tab the overlay has done its job;
        // settleResume then resets the inputs so it stays closed.
        val resumed = ui.resumingId != null && config?.effectiveActiveTabId != ui.resumeFromTab
        val counts = AgentCounts.of(sessions, config, nowMillis).total
        return State(
            open = ui.open && !resumed,
            query = ui.query,
            matchMode = matchMode,
            matches = if (matchMode) matchRows.map { it.measured() } else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else now.map { it.measured() },
            recent = if (matchMode) emptyList() else recent.map { it.measured() },
            fortnight = if (matchMode) emptyList() else fortnight.map { it.measured() },
            earlierHidden = earlierUnits.size - shownEarlier.size,
            needYouCount = counts.waiting,
            workingCount = counts.working,
            selectedId = selectedId,
            peek = peek,
            confirm = confirm,
            ask = ask,
            footer = footer,
        )
    }

    /**
     * When a session started: the time its record opened, which never changes; a server that does
     * not send it leaves the time the session entered its current state.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun startedOf(s: AgentSessionSummary): Long = TaskMembers.startedOf(s)

    /**
     * The time a lone conversation's row age counts from: the time it entered its state while it
     * needs the person or works, else its last sign of life, so the list and a search result give it
     * the same age and the age and the EARLIER band are read from one moment.
     *
     * @param s the session.
     * @return epoch millis.
     */
    private fun sinceOf(s: AgentSessionSummary): Long =
        if (s.state == ENDED || s.state == DONE) activityOf(s) else s.stateSince

    /**
     * The task's main conversation as a session summary, when Lunamux lists it and it belongs in
     * the task's own conversations: the summary itself, unless another task hosts it.
     *
     * A conversation hosted under a different task's id is one of that task's conversations;
     * counting it here as well would show it in two rows' state words. The row's ↵ still goes to it
     * (see [mainOf]).
     *
     * Called by [unitOf] to add the conversation to the task's sessions when none of them is it.
     *
     * @param t the task.
     * @param byKey all session summaries by record id and by conversation id.
     * @return the session to count as the task's own, or null.
     */
    private fun ownOf(t: AgentTask, byKey: Map<String, AgentSessionSummary>): AgentSessionSummary? =
        mainOf(t, byKey)?.takeIf { it.taskId == null || it.taskId == t.id }

    /**
     * The task's main conversation: the conversation its `mainConversation` names, when Lunamux
     * lists it. ↵ on the task's row goes to it whichever task hosts it.
     *
     * @param t the task.
     * @param byKey all session summaries by record id and by conversation id.
     * @return the main conversation's summary, or null when none is named or listed.
     */
    private fun mainOf(t: AgentTask, byKey: Map<String, AgentSessionSummary>): AgentSessionSummary? =
        t.mainConversation?.let(byKey::get)

    /**
     * Gather a task's conversations, work out where the task sits and what ↵ on its row opens.
     *
     * The task's conversations are the sessions that carry its id, and its main conversation when
     * none of those is it. A task without conversations and without an `open` action has no unit and
     * no row.
     *
     * @param t the task.
     * @param byKey all session summaries by record id and by conversation id.
     * @param hosted the sessions whose [AgentSessionSummary.taskId] is [t]'s id.
     * @param c the layout and the clock; it tells which sessions are open.
     * @return the unit, or null when the task has nothing to show.
     */
    private fun unitOf(
        t: AgentTask,
        byKey: Map<String, AgentSessionSummary>,
        hosted: List<AgentSessionSummary>,
        c: Context,
    ): TaskUnit? {
        // The main conversation is one of the task's conversations even when it carries no task id: it
        // counts in the state word and is never a row of its own.
        val main = mainOf(t, byKey)
        val own = ownOf(t, byKey)?.takeIf { o -> hosted.none { it.id == o.id } }
        val sessions = hosted.sortedWith(compareBy({ startedOf(it) }, { it.id })) + listOfNotNull(own)
        if (sessions.isEmpty() && !t.hasOpen) return null
        val urgentRank = when {
            sessions.any { it.state == NEEDS_YOU } -> 0
            sessions.any { it.state == WORKING } -> 1
            else -> OPEN_WORK_RANK
        }
        // A task with no session yet is placed by when it started: its file's first sighting.
        val since = when {
            sessions.isEmpty() -> t.startedAtMillis ?: 0L
            urgentRank == OPEN_WORK_RANK -> sessions.maxOf { it.stateSince }
            else -> sessions.filter { rankOf(it.state) == urgentRank }.minOf { it.stateSince }
        }
        // Open means a conversation Lunamux lists whose pane is in the layout, so Jump has somewhere to go.
        val open = sessions.filter { c.live(it) }
        val mainLive = main != null && c.live(main)
        val started = t.startedAtMillis ?: sessions.minOfOrNull(::startedOf) ?: since
        val recency = sessions.maxOfOrNull { maxOf(it.stateSince, activityOf(it)) } ?: t.startedAtMillis ?: 0L
        val waiting = sessions.filter { AgentCounts.isWaiting(it, c.config) }
        val inNow = open.isNotEmpty() || mainLive
        val resumeId = t.mainConversation.takeIf { main == null }
        val (enter, target) = enterOf(t, main, mainLive, sessions, open, resumeId)
        return TaskUnit(t, sessions, waiting, since, recency, inNow, started, enter, target, main?.id, resumeId)
    }

    /**
     * What ↵ does on a task's row, and on which conversation.
     *
     * A live main conversation is jumped to, unless another open conversation of the task waits for
     * the person and the main one does not: that one is jumped to, or opened as a marked tab when it
     * is a background one. A main conversation in a background tab has no tab to jump to: it is
     * opened as a marked tab while it asks, and otherwise ↵ goes to a conversation of the task in a
     * listed tab, else offers nothing, since there is nothing to answer there. With the main
     * conversation closed, a waiting open conversation is reached the same way, else the
     * closed main conversation is resumed (a conversation the task has in a listed tab does not
     * replace it), else a live conversation in a listed tab is jumped to (the one waiting longest
     * on the person, else the busiest), else the task's `open` action runs, else the newest
     * conversation it has a record of is resumed. A task with neither a conversation nor an `open`
     * action has no unit, so there is always something to do.
     *
     * @param t the task.
     * @param main the main conversation, or null.
     * @param mainLive whether [main] is live and its pane is in the layout.
     * @param sessions the task's conversations.
     * @param open the conversations whose pane is in the layout.
     * @param resumeId the stored id of a main conversation no summary holds, or null; it is resumed
     *   like a closed main conversation, by that id.
     * @return what ↵ does and the session it acts on (null for `open`, for a resume by [resumeId]
     *   and for nothing, except that a main conversation in a background tab with nothing to open
     *   still names itself, so the row can be peeked at and shows its folder).
     */
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
        if (mainLive && main != null && (asking == null || mainAsks)) {
            if (!main.background) return Enter.JUMP to main
            // A background tab is not listed, so a jump would show nothing: only its marked tab can be reached.
            if (mainAsks) return Enter.SHOW_WAITING to main
        } else if (asking != null) {
            return (if (asking.background) Enter.SHOW_WAITING else Enter.JUMP) to asking
        }
        // The conversation that most needs the person comes first: the ◆ that has waited longest, then
        // the working one with the freshest sign of life, then any other open one.
        val urgency = compareBy<AgentSessionSummary>(
            { rankOf(it.state) },
            { if (it.state == NEEDS_YOU) it.stateSince else -(it.lastActivityAt ?: it.stateSince) },
        )
        val listed = open.filter { !it.background }.minWithOrNull(urgency)
        val newest = sessions.maxByOrNull(::activityOf)
        return when {
            // Only a main conversation in a background tab gets here: a listed one has returned above.
            mainLive && listed != null -> Enter.JUMP to listed
            mainLive -> Enter.NONE to main
            main != null -> Enter.RESUME to main
            resumeId != null -> Enter.RESUME to null
            listed != null -> Enter.JUMP to listed
            t.hasOpen -> Enter.OPEN to null
            else -> Enter.RESUME to newest
        }
    }

    private fun rankOf(state: String) = when (state) {
        NEEDS_YOU -> 0
        WORKING -> 1
        else -> OPEN_WORK_RANK
    }

    /**
     * The newest sign of life of a hosted session: when it ended, else its last activity, else the
     * time it entered its state.
     *
     * Called wherever a session's recency matters: where it ranks, which EARLIER band it falls in
     * and the age of a lone conversation's row, so the heading and the age are read from one moment.
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
        val hosted = u.sessions
        if (hosted.isEmpty() || hosted.any { it.repo == null }) return null
        return hosted.map { it.repo }.distinct().singleOrNull()
    }

    /**
     * What a conversation is saying right now: the question one that waits on the person asks, or
     * the last tool line of one that works.
     *
     * @param s the conversation.
     * @return the text, or null when there is none.
     */
    private fun lineOf(s: AgentSessionSummary): String? {
        val nowLine = s.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        if (s.state == NEEDS_YOU) return s.question?.trim()?.takeIf { it.isNotEmpty() } ?: nowLine
        return nowLine
    }

    /**
     * The row of one task.
     *
     * Its state word counts the conversations that wait on the person or move, background ones
     * included, and its detail is the task's status or, failing that, what a conversation is saying.
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
        // The row counts the conversations the footer counts, by the same functions.
        val moving = u.sessions.filter { AgentCounts.isWorking(it, c.config, c.nowMillis) }
        val waiting = u.waiting.minByOrNull { it.stateSince }
        // Something that moves is working, wherever the task is filed; a task that only rests reads done in NOW and finished in EARLIER.
        val stateWord = when {
            u.waiting.size >= 2 -> needYouText(u.waiting.size)
            waiting != null -> STATE_NEEDS_YOU
            moving.size >= 2 -> "${moving.size} $STATE_WORKING"
            moving.size == 1 -> STATE_WORKING
            u.inEarlier -> STATE_FINISHED
            else -> STATE_DONE
        }
        val status = t.status?.trim()?.takeIf { it.isNotEmpty() }
        val detail = status
            ?: waiting?.let(::lineOf)?.let(::quoted)
            ?: moving.sortedByDescending { it.lastActivityAt ?: it.stateSince }.firstNotNullOfOrNull(::lineOf)
            ?: ""
        val target = u.target
        val live = target != null && c.live(target)
        val single = u.sessions.singleOrNull()
        val folder = u.sessions.map(::folderOf).distinct().singleOrNull()
        val error = c.errorOf(id)
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
                // ↵ has nothing to do only for a main conversation that runs in a background tab.
                target?.let { if (it.state == WORKING) HIDDEN_MAIN_NOTE else HIDDEN_MAIN_DONE_NOTE }
                    .takeIf { u.enter == Enter.NONE },
                error,
            ),
            chips = chipsOf(t.title, target, c),
            ask = askOf(id, u.enter, own = if (target != null) target.id == u.mainId else u.resumeId != null),
            sinceMillis = if (u.inEarlier) u.recency else u.since,
            enter = u.enter,
            recordId = target?.id ?: u.resumeId.takeIf { u.enter == Enter.RESUME },
            isLive = live,
            canClose = single != null && c.live(single) && !single.background,
            error = error,
        )
    }

    /**
     * The row of a lone conversation, or of a conversation that is a task of one.
     *
     * Its ↵ jumps to a live conversation, opens a waiting background one as a marked tab and
     * resumes one that is not live.
     *
     * @param s the conversation's summary.
     * @param earlier whether the row is drawn in EARLIER, where a conversation that rests reads
     *   "finished" rather than "done".
     * @param c the layout, the clock and the last answers of the server.
     * @param taskTitle when the session belongs to a task that has no unit (a search result found
     *   it in a task older than the pushed list), the task's title: the row then stands for that
     *   task, with id `task:<id>`.
     * @return the row, with the conversation id as its id.
     */
    private fun sessionRow(s: AgentSessionSummary, earlier: Boolean, c: Context, taskTitle: String? = null): Row {
        val live = c.live(s)
        val id = if (taskTitle != null) "$TASK_PREFIX${s.taskId}" else s.conversationId
        // The same rule as the footer: a conversation waits or moves on a row only while its pane is in the layout.
        val needs = AgentCounts.isWaiting(s, c.config)
        val moving = AgentCounts.isWorking(s, c.config, c.nowMillis)
        val nowLine = s.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        val detail = when {
            needs -> (AgentSessionText.previewOf(s.question) ?: nowLine)?.let(::quoted) ?: ""
            moving -> nowLine ?: ""
            else -> ""
        }
        val enter = when {
            live && s.background && needs -> Enter.SHOW_WAITING
            live -> Enter.JUMP
            else -> Enter.RESUME
        }
        val error = c.errorOf(id)
        val title = taskTitle ?: titleOf(s)
        return Row(
            id = id,
            taskId = s.taskId.takeIf { taskTitle != null },
            title = title,
            where = s.repo ?: folderOf(s),
            marker = if (needs) MARKER else "",
            stateWord = when {
                needs -> STATE_NEEDS_YOU
                moving -> STATE_WORKING
                earlier -> STATE_FINISHED
                else -> STATE_DONE
            },
            detail = detail,
            extra = extraOf(c.noteOf(s.id), error),
            chips = chipsOf(title, s, c),
            ask = askOf(id, enter, own = false),
            sinceMillis = sinceOf(s),
            enter = enter,
            recordId = s.id,
            isLive = live,
            canClose = live && !s.background,
            error = error,
        )
    }

    /**
     * Turn the row of a task (or of a lone conversation) into a search result: the row keeps its
     * state word, folder and age, and opens the session that matched; the match itself is carried in
     * [Row.field], [Row.snippet] and the highlight offsets, and the extra line says where it was
     * found and in which session.
     *
     * @param base the row of the task the matched session belongs to.
     * @param m the server's match.
     * @param s the matched session, as the layout knows it now.
     * @param c the layout, the clock and the last answers of the server.
     * @param mainId the record id of the main conversation of the task the row stands for, or null.
     * @return the search result row.
     */
    private fun matchRow(base: Row, m: AgentSessionMatch, s: AgentSessionSummary, c: Context, mainId: String?): Row {
        val live = c.live(s)
        val enter = if (live) Enter.JUMP else Enter.RESUME
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
        return base.copy(
            detail = snippet ?: base.detail,
            extra = extraOf(note, base.error, where),
            chips = chipsOf(base.title, s, c),
            ask = askOf(base.id, enter, own = s.id == mainId),
            enter = enter,
            recordId = s.id,
            isLive = live,
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
     * The chips under the selected row: the folder the session ↵ opens works in, and the tab it sits
     * in. Both describe that one session, so a task with several shows the one ↵ goes to.
     *
     * The folder is written as the server prettifies it (home as `~`) and is left out when the
     * server did not send it. The tab chip is the tab's own name, with the world's name after it
     * for a tab in another world; it is left out for a session in a background tab or in no pane,
     * and when the tab's name already contains every word of the row's title, because the row
     * would then say the same thing twice (see [containsEveryWord]).
     *
     * @param title the row's title.
     * @param target the session ↵ opens, or null when it opens none.
     * @param c the layout.
     * @return the chips, folder first; empty when there is nothing to say.
     */
    private fun chipsOf(title: String, target: AgentSessionSummary?, c: Context): List<Chip> {
        if (target == null) return emptyList()
        val folder = target.cwdShown?.takeIf { it.isNotBlank() }?.let { Chip(ChipKind.FOLDER, it) }
        val where = c.panes[target.leafId]?.takeIf { it.tabNumber != null && c.live(target) }
        val tab = where?.takeIf { it.tabTitle.isNotBlank() && !containsEveryWord(it.tabTitle, title) }?.let {
            val shown = c.config?.activeWorldOrNull()?.id
            val world = it.worldName?.takeIf { _ -> it.worldId != null && it.worldId != shown }
            Chip(ChipKind.TAB, if (world != null) "${it.tabTitle} · $world" else it.tabTitle)
        }
        return listOfNotNull(folder, tab)
    }

    /**
     * Whether [text] contains every word of [title]. A word is a run of letters and digits; case
     * and punctuation are ignored, and a word must match whole: "rel" is not "release". A title
     * with no words is contained by nothing, so a tab is still named for it.
     *
     * @param text the tab's name.
     * @param title the row's title.
     * @return true when no word of [title] is missing from [text].
     */
    private fun containsEveryWord(text: String, title: String): Boolean {
        val wanted = wordsOf(title)
        return wanted.isNotEmpty() && wordsOf(text).containsAll(wanted)
    }

    /** The lower-cased words of [text]: its runs of letters and digits. */
    private fun wordsOf(text: String): Set<String> = buildSet {
        val word = StringBuilder()
        for (ch in text.lowercase()) {
            if (ch.isLetterOrDigit()) {
                word.append(ch)
            } else if (word.isNotEmpty()) {
                add(word.toString())
                word.clear()
            }
        }
        if (word.isNotEmpty()) add(word.toString())
    }

    /**
     * What the first ↵ asks on a row whose [enter] would resume or start something.
     *
     * The tag names what is missing, never the state of the whole task: "conversation closed" when
     * the task's main conversation is the one that is not running, "session closed" for any other
     * session that has ended, and "no conversation" when there is nothing to resume and ↵ would
     * start the task's `open` action.
     *
     * @param rowId the row.
     * @param enter what ↵ does on it.
     * @param own whether the session to resume is the task's main conversation.
     * @return the question, or null when ↵ acts at once.
     */
    private fun askOf(rowId: String, enter: Enter, own: Boolean): Ask? = when (enter) {
        Enter.RESUME -> Ask(
            rowId,
            if (own) "conversation closed" else "session closed",
            "Resume it in a new tab?",
            listOf(FooterKey("↵", "resume"), FooterKey("esc", "cancel")),
        )
        Enter.OPEN -> Ask(
            rowId,
            "no conversation",
            "Start one in a new tab?",
            listOf(FooterKey("↵", "start"), FooterKey("esc", "cancel")),
        )
        else -> null
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
    private fun titleOf(s: AgentSessionSummary): String = TaskMembers.titleOf(s)

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
    private fun folderOf(s: AgentSessionSummary): String? = TaskMembers.folderOf(s)

    private fun paneLocations(config: WindowConfig): Map<String?, PaneLocation> {
        val out = HashMap<String?, PaneLocation>()
        fun add(worldId: String?, worldName: String?, tabs: List<se.soderbjorn.lunamux.TabConfig>) {
            var listed = 0
            tabs.forEach { tab ->
                val number = if (tab.isListed) ++listed else null
                tab.panes.forEach { out[it.leaf.id] = PaneLocation(worldId, worldName, tab.id, tab.title, number, it.leaf) }
            }
        }
        if (config.worlds.isEmpty()) add(null, null, config.tabs) else config.worlds.forEach { add(it.id, it.name, it.tabs) }
        return out
    }

    private fun locate(config: WindowConfig, leafId: String): PaneLocation? = paneLocations(config)[leafId]

    companion object {
        /**
         * The footer while no question is open: what ↵ does on the selected row, then ↑↓ and esc.
         *
         * @param enter what ↵ does on the selected row, or null when no row is selected.
         * @return the keys, ↵ first.
         */
        fun defaultFooter(enter: Enter?): List<FooterKey> = listOfNotNull(
            // A row ↵ cannot open does not advertise it.
            FooterKey("↵", if (enter == Enter.SHOW_WAITING) SHOW_WAITING_HINT else "open").takeIf { enter != Enter.NONE },
            FooterKey("↑↓", "move"),
            FooterKey("esc", "close"),
        )

        /** What ↵ does on a row whose waiting conversation is in a background tab, as the footer says it. */
        const val SHOW_WAITING_HINT = "answer it · opens as a tab until it works on"

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

        /**
         * What the selected row's extra line says when the task's main conversation runs in a
         * background tab, which ↵ cannot jump to, and nothing else of the task is in a listed tab.
         */
        const val HIDDEN_MAIN_NOTE = "it works in a background tab: ↵ has nothing to open until it asks"

        /**
         * What the selected row's extra line says when the task's main conversation has finished its
         * turn in a background tab. It will not ask for the person unless something types to it, and
         * ↵ opens a hidden tab only for a waiting agent, so → (peek) is the way to read it.
         */
        const val HIDDEN_MAIN_DONE_NOTE = "it has finished in a background tab: ↵ has nothing to open, → shows what it said"

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
         * "2 need you" / "1 needs you": the plural form of a row's state word.
         *
         * @param n how many need the user.
         * @return the count and the right verb.
         */
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"

        /**
         * The prefix of a working session's status when its sign of life is old: "quiet 6m · " once
         * [QUIET_AFTER_MS] has passed; empty otherwise.
         *
         * Called by [isMoving], so the rule that decides what counts as working lives in one place.
         *
         * @param working whether the session's state is working; any other state has no prefix.
         * @param activityMillis epoch millis of the last sign of life; null when there is none.
         * @param nowMillis the current epoch millis.
         * @return "quiet 6m · " or an empty string.
         */
        fun agePrefixOf(working: Boolean, activityMillis: Long?, nowMillis: Long): String {
            if (!working) return ""
            if (activityMillis != null && isQuiet(activityMillis, nowMillis)) {
                return "quiet ${(nowMillis - activityMillis) / 60_000L}m · "
            }
            return ""
        }

        /**
         * Whether a working session is really moving: it works and is not quiet. This is what the
         * footer's spinner and a row's "N working" count.
         *
         * Called by [AgentCounts] and by the view model when they count working sessions.
         *
         * @param working whether the session's state is working.
         * @param activityMillis see [agePrefixOf].
         * @param nowMillis the current epoch millis.
         * @return true for a working session with a recent sign of life.
         */
        fun isMoving(working: Boolean, activityMillis: Long?, nowMillis: Long): Boolean =
            working && agePrefixOf(true, activityMillis, nowMillis).isEmpty()

        private const val TASK_PREFIX = "task:"
        private const val OPEN_WORK_RANK = 2
        private const val DONE = "done"
        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"
    }
}
