/**
 * The agents overview (⌘⇧A): every agent conversation Lunamux knows about, as rows the person can
 * jump to, resume, peek into or close.
 *
 * The rows come from the board ([AgentBoard]), which has already joined the server's facts with the
 * layout; this file only orders and sections them and holds what the person is doing: the search
 * text, the selection and one [OverviewMode]. NOW lists every open row, grouped by repo, in the
 * order their work began. STOPPED BY THE RESTART lists what Lunamux cut off when it last stopped,
 * with one key to resume all. EARLIER lists the rest by recency in two bands (three days, a month),
 * five rows and a "+ N more" line. Typing two or more characters switches to the server's search,
 * one row per task.
 *
 * Contains [AgentsOverviewBackingViewModel], its state types and [ageText].
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
import se.soderbjorn.lunamux.AgentWindows
import se.soderbjorn.lunamux.ScreenRun
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.AgentBoard
import se.soderbjorn.lunamux.client.Enter
import se.soderbjorn.lunamux.client.Tally
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.Work
import se.soderbjorn.lunamux.effectiveActiveTabId

/**
 * What the person is doing besides browsing; the overview is in exactly one of these.
 *
 * Built and read by [AgentsOverviewBackingViewModel].
 */
sealed interface OverviewMode {
    /** Looking at the list. */
    object Browsing : OverviewMode

    /** A question about [rowId] (resume it? start one?) waits for ↵. */
    data class Asking(val rowId: String) : OverviewMode

    /** Closing [rowId]'s session waits for a yes; [onClose] says which button has focus. */
    data class Confirming(val rowId: String, val onClose: Boolean = false) : OverviewMode

    /** The selected row's screen is shown. */
    object Peeking : OverviewMode

    /** [rowId] was asked to open a tab; the overview stays up until the active tab is not [fromTab]. */
    data class Resuming(val rowId: String, val fromTab: String?) : OverviewMode
}

private const val EARLIER_LIMIT = 5
private const val MIN_QUERY = 2

/**
 * "now", "4m", "3h" or "2d": how long ago [sinceMillis] was at [nowMillis].
 *
 * Called by the overview's screens for every row's age and by the footer.
 *
 * @param sinceMillis the earlier time, epoch millis.
 * @param nowMillis the current time, epoch millis.
 * @return the age in its largest whole unit; "now" under a minute.
 */
fun ageText(sinceMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}

/**
 * The state behind the agents overview: which rows show, which is selected, and what ↵ does on it.
 *
 * The web screen creates one, calls the mutators below for each key or click, collects [stateFlow]
 * and draws it. Rows are rebuilt from the board plus the person's choices on every change, so
 * nothing is stored twice. Commands that need the server go out through [send].
 *
 * @param windowState where the board, the search answer, the peek and the errors arrive.
 * @param scope runs the rebuild on every push and the request for the selected row's details.
 * @param clock the time ages and the day cut-offs are measured against; tests pass a fixed one.
 * @param send sends a command to the server.
 * @see AgentBoard
 */
class AgentsOverviewBackingViewModel(
    private val windowState: WindowStateRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /** Where a search hit matched, so the screen can mark the words. */
    enum class HitIn { TITLE, TAB, SNIPPET, ELSEWHERE }

    /**
     * Why a row is in a search answer.
     *
     * @property label where it matched, as the row says it ("in first prompt").
     * @property in which text of the row carries the highlight.
     * @property snippet the words around the match for a long field, else null.
     * @property start where the highlight starts in the text it is drawn in.
     * @property length how long it is; 0 for none.
     * @property note what resuming the matched conversation would do, when that needs saying.
     */
    data class Hit(val label: String, val `in`: HitIn, val snippet: String?, val start: Int, val length: Int, val note: String?)

    /** Which text a row shows under its name. */
    enum class Under { SNIPPET, QUESTION, STATUS, RECAP, TITLE, NONE }

    /**
     * One row on screen: a board row and, in a search, why it matched.
     *
     * @property work the board's row; everything it draws comes from here.
     * @property hit the search hit, or null outside a search.
     */
    data class Row(val work: Work, val hit: Hit? = null) {
        /** The row's id. */
        val id: String get() = work.id

        /** Whether the row is one the restart stopped. */
        val stopped: Boolean get() = work.stopped

        /**
         * The one line a row shows under its name, so a row is never more than two lines: a search
         * snippet, else the title when the hit is in it; else the question while it needs the
         * person; else a status said on purpose; else the recap; else the title when the tab's name
         * led; else what Lunamux read off the screen. What it leaves out is a key away.
         *
         * @return which text to show.
         */
        fun under(): Under = when {
            hit?.snippet != null -> Under.SNIPPET
            hit?.`in` == HitIn.TITLE && work.tabName != null -> Under.TITLE
            work.shown == Shown.NEEDS_YOU && work.line.isNotEmpty() -> Under.QUESTION
            work.lineSet && work.line.isNotEmpty() -> Under.STATUS
            work.recap != null -> Under.RECAP
            work.tabName != null -> Under.TITLE
            work.line.isNotEmpty() -> Under.STATUS
            else -> Under.NONE
        }
    }

    /** One key of the footer or of a question bar. */
    data class FooterKey(val key: String, val label: String)

    /** The question under a row before a resume or a start. */
    data class Ask(val rowId: String, val tag: String, val question: String, val keys: List<FooterKey>)

    /**
     * The selected row's screen: live while its pane exists, else what was saved.
     *
     * @property live whether the pane still exists, so [lines] are its screen right now.
     * @property title the conversation's name, for the peek's heading.
     * @property closedSinceMillis when a closed conversation ended; null while live.
     * @property lines the screen as plain text; empty until the answer arrives.
     * @property styled the same rows with colours; empty when the screen has no styles.
     * @property loading whether the server has not answered yet.
     * @property emptyText what to say when the answer holds no screen; null otherwise.
     */
    data class Peek(
        val live: Boolean,
        val title: String,
        val closedSinceMillis: Long?,
        val lines: List<String>,
        val styled: List<List<ScreenRun>>,
        val loading: Boolean,
        val emptyText: String?,
    )

    /**
     * The close question: what closing [title]'s session loses.
     *
     * @property rowId the row being closed.
     * @property title the conversation's name.
     * @property warnings what would be lost, one line each; empty when nothing is.
     * @property cancelFocused whether Cancel, not Close, has the focus.
     */
    data class Confirm(val rowId: String, val title: String, val warnings: List<String>, val cancelFocused: Boolean)

    /**
     * Everything the screen draws.
     *
     * @property open whether the overlay is showing.
     * @property query the text in the search box.
     * @property matchMode whether a search is on, so [matches] replace the groups.
     * @property matches the search hits, best first.
     * @property searching whether a search answer is still awaited.
     * @property now the NOW group.
     * @property stopped the rows stopped by the last restart.
     * @property stoppedAtMillis when the restart that stopped [stopped] happened.
     * @property recent the rows closed in the last few days.
     * @property fortnight the rows closed in the last month.
     * @property earlierHidden how many EARLIER rows "+ N more" would reveal.
     * @property tally the counts at the end of the search line.
     * @property selectedId the selected row's id; null when no row is.
     * @property peek the open peek, if any.
     * @property confirm the open close question, if any.
     * @property ask the open resume question, if any.
     * @property extra the extra text on the selected row.
     * @property error the error to show on the selected row, if any.
     * @property footer the key hints at the bottom.
     */
    data class State(
        val open: Boolean = false,
        val query: String = "",
        val matchMode: Boolean = false,
        val matches: List<Row> = emptyList(),
        val searching: Boolean = false,
        val now: List<Row> = emptyList(),
        val stopped: List<Row> = emptyList(),
        val stoppedAtMillis: Long? = null,
        val recent: List<Row> = emptyList(),
        val fortnight: List<Row> = emptyList(),
        val earlierHidden: Int = 0,
        val tally: Tally = Tally(),
        val selectedId: String? = null,
        val peek: Peek? = null,
        val confirm: Confirm? = null,
        val ask: Ask? = null,
        val extra: String = "",
        val error: String? = null,
        val footer: List<FooterKey> = footerOf(Enter.JUMP, canPeek = true, stopped = 0),
    ) {
        /** Every row on screen, top to bottom. */
        val rows: List<Row> get() = if (matchMode) matches else now + stopped + recent + fortnight

        /** The index of the selected row in [rows], or -1. */
        val selected: Int get() = rows.indexOfFirst { it.id == selectedId }

        /** The selected row. */
        val selectedRow: Row? get() = rows.firstOrNull { it.id == selectedId }

        /** "N MATCH(ES)" once a search has been answered. */
        val matchHeading: String?
            get() = if (!matchMode || searching) null else "${matches.size} ${if (matches.size == 1) "MATCH" else "MATCHES"}"

        /** What an empty list says. */
        val emptyText: String
            get() = when {
                matchMode && searching -> "Searching…"
                matchMode -> "Nothing matches “${query.trim()}”. Search covers titles, tab names, first prompts, branches, links and transcripts."
                else -> "No agent sessions yet"
            }
    }

    private var query = ""
    private var open = false
    private var selectedId: String? = null
    private var selectedHint = 0
    private var showAllEarlier = false
    private var mode: OverviewMode = OverviewMode.Browsing
    private var asked: String? = null
    private val current = MutableStateFlow(State())

    /** The state as a flow, for the screen to draw. */
    val stateFlow: Flow<State> = current

    /** The latest state; a key handler reads it and always sees the result of the key before. */
    val state: State get() = current.value

    init {
        scope.launch {
            combine(
                listOf<Flow<Any?>>(
                    windowState.agentBoard, windowState.agentMatches, windowState.agentDetails, windowState.agentErrors,
                    windowState.config,
                ),
            ) { }.collect { rebuild() }
        }
    }

    private val board: AgentBoard get() = windowState.agentBoard.value

    /** Builds the state from the latest board and the person's choices and publishes it. */
    private fun rebuild() {
        val m = mode
        if (m is OverviewMode.Resuming && windowState.config.value?.effectiveActiveTabId != m.fromTab) reset()
        var built = build()
        // A question, confirmation or peek whose row is gone is withdrawn for good, so it cannot come back.
        val gone = when (mode) {
            is OverviewMode.Asking -> built.ask == null
            is OverviewMode.Confirming -> built.confirm == null
            is OverviewMode.Peeking -> built.peek == null
            else -> false
        }
        if (gone) {
            mode = OverviewMode.Browsing
            built = build()
        }
        current.value = built
        if (open) scope.launch { inspectSelected() }
    }

    private fun reset() {
        open = false
        query = ""
        selectedId = null
        selectedHint = 0
        showAllEarlier = false
        mode = OverviewMode.Browsing
        asked = null
    }

    private fun change(edit: () -> Unit) {
        edit()
        rebuild()
    }

    private fun forgetErrors() {
        windowState.agentErrors.value.keys.filterNotNull().forEach(windowState::clearAgentError)
    }

    /** Moving the selection or typing ends any question, confirmation or peek, but not a resume in flight. */
    private fun browsing() {
        if (mode !is OverviewMode.Resuming) mode = OverviewMode.Browsing
    }

    /** Opens the overview on a clean slate with the row that needs the person selected. */
    fun open() {
        forgetErrors()
        reset()
        open = true
        val first = build()
        change { selectedId = first.selectedId; selectedHint = maxOf(first.selected, 0) }
    }

    /** Closes the overview and forgets the query, selection and any open question. */
    fun close() {
        forgetErrors()
        reset()
        rebuild()
    }

    /** ⌘⇧A: opens the overview, or closes it when it is open. */
    fun toggle() = if (open) close() else open()

    /**
     * Typing in the search box.
     *
     * @param query the new text; the selection starts over.
     */
    fun setQuery(query: String) {
        forgetErrors()
        change { browsing(); this.query = query; selectedId = null; selectedHint = 0 }
    }

    /** Asks the server to search for the current query; the answer arrives as an `AgentMatches` envelope in [WindowStateRepository.agentMatches]. */
    suspend fun search() {
        val text = query.trim()
        if (text.length >= MIN_QUERY) send(WindowCommand.SearchAgents(text))
    }

    /**
     * ↑/↓: moves the selection, revealing the hidden earlier rows when stepping past the last one.
     *
     * @param delta how many rows, negative for up.
     */
    fun moveSelection(delta: Int) {
        if (state.rows.isEmpty()) return
        val at = maxOf(state.selected, 0)
        if (delta > 0 && at == state.rows.lastIndex && !state.matchMode && state.earlierHidden > 0) showMoreEarlier()
        val rows = state.rows
        val next = (at + delta).coerceIn(0, rows.lastIndex)
        change { browsing(); selectedId = rows[next].id; selectedHint = next }
    }

    /**
     * A click or hover on a row.
     *
     * @param rowId the row; ignored when it is not on screen.
     */
    fun select(rowId: String) {
        if (state.ask?.rowId == rowId && selectedId == rowId) return
        val index = state.rows.indexOfFirst { it.id == rowId }
        if (index >= 0) change { browsing(); selectedId = rowId; selectedHint = index }
    }

    /** "+ N more": shows every earlier row, not only the first few. */
    fun showMoreEarlier() = change { showAllEarlier = true }

    /** ↵: answers an open confirmation, else does what the selected row offers. */
    suspend fun activateSelected() {
        (mode as? OverviewMode.Confirming)?.let { if (it.onClose) confirmClose() else cancelClose(); return }
        state.selectedRow?.let { activate(it) }
    }

    /**
     * Does what [row] offers on ↵ or a click: go to it, show it, or ask before resuming or starting it.
     * When ↵ can't act the row already says why.
     *
     * @param row the row.
     */
    suspend fun activate(row: Row) {
        val w = row.work
        when (w.enter) {
            Enter.JUMP -> {
                board.enter(w).forEach { send(it) }
                close()
            }
            Enter.SHOW -> {
                forgetErrors()
                val from = windowState.config.value?.effectiveActiveTabId
                change { mode = OverviewMode.Resuming(w.id, from) }
                board.enter(w).forEach { send(it) }
            }
            Enter.OPEN, Enter.RESUME -> if (state.ask?.rowId == w.id) answerAsk(w) else askAbout(w)
            Enter.NONE -> Unit
        }
    }

    private fun askAbout(w: Work) {
        select(w.id)
        if (selectedId == w.id) change { mode = OverviewMode.Asking(w.id) }
    }

    private suspend fun answerAsk(w: Work) {
        forgetErrors()
        val from = windowState.config.value?.effectiveActiveTabId
        change { mode = OverviewMode.Resuming(w.id, from) }
        board.enter(w).forEach { send(it) }
        // Typed into a pane that is already there: the tab may be the one on screen, so there is no new tab to wait for.
        if (w.enter == Enter.RESUME && w.resumeInTab) close()
    }

    /** ⇧↵: resumes everything the restart stopped, each in its own tab (the one it ran in when that is still free), and closes the overview. */
    suspend fun resumeStopped() {
        val rows = state.stopped.filter { it.work.enter == Enter.RESUME }
        if (rows.isEmpty()) return
        forgetErrors()
        rows.forEach { r -> board.enter(r.work).forEach { send(it) } }
        close()
    }

    /**
     * Asks the server about the selected row's conversation (its resume note, uncommitted work, saved screen).
     *
     * @param force ask again though the same row was asked about.
     */
    suspend fun inspectSelected(force: Boolean = false) {
        val w = state.selectedRow?.work ?: return
        val target = w.targetId ?: return
        val key = "$target/${w.live}"
        if (!force && asked == key) return
        asked = key
        send(WindowCommand.ReadAgentDetails(recordId = target))
    }

    /** →: shows the selected row's screen and asks the server for it. */
    suspend fun peek() {
        if (state.selectedRow?.work?.targetId == null) return
        change { mode = OverviewMode.Peeking }
        inspectSelected(force = true)
    }

    /** Re-reads the screen of a live row; called every couple of seconds while peeking. */
    suspend fun refreshPeek() {
        if (state.peek?.live == true) inspectSelected(force = true)
    }

    /** Ends a peek (← or esc), going back to browsing. */
    fun closePeek() = change { if (mode is OverviewMode.Peeking) mode = OverviewMode.Browsing }

    /** esc: steps out of a question, confirmation or peek first, then closes the overview. */
    fun back() {
        if (mode is OverviewMode.Browsing || mode is OverviewMode.Resuming) close() else change { mode = OverviewMode.Browsing }
    }

    /** ⌘⌫ or the close button: asks to close the selected row's session when it has one to close. */
    suspend fun requestClose() {
        val row = state.selectedRow?.takeIf { it.work.canClose } ?: return
        change { mode = OverviewMode.Confirming(row.id) }
        inspectSelected(force = true)
    }

    /** Tab or arrow in the close question: moves the focus between its two buttons. */
    fun moveConfirmFocus() = change { (mode as? OverviewMode.Confirming)?.let { mode = it.copy(onClose = !it.onClose) } }

    /** Dismisses the close question without closing anything. */
    fun cancelClose() = change { if (mode is OverviewMode.Confirming) mode = OverviewMode.Browsing }

    /** Closes the session the close question named. */
    suspend fun confirmClose() {
        val confirming = mode as? OverviewMode.Confirming ?: return
        val work = state.rows.firstOrNull { it.id == confirming.rowId }?.work
        val sessionId = work?.targetId?.let(board.conversations::get)?.paneSessionId
        change { mode = OverviewMode.Browsing }
        if (sessionId != null) send(WindowCommand.CloseSession(sessionId))
    }

    // ---- building the state ---------------------------------------------------------------------

    private fun build(): State {
        val b = board
        val now = clock()
        val text = query.trim()
        val matchMode = text.length >= MIN_QUERY
        val answer = windowState.agentMatches.value?.takeIf { matchMode && it.query == text }
        val matchRows = answer?.matches.orEmpty().mapNotNull { m -> b.workOf(m)?.let { Row(it, hitOf(it, m, text)) } }.distinctBy { it.id }

        val inNow = b.works.filter { it.inNow }
        // Grouped by repo, the groups and the rows in each in the order their work began, so no row moves when a state flips.
        val nowRows = inNow.sortedWith(compareBy({ it.started }, { it.id })).groupBy { it.where }.values.flatten().map { Row(it) }
        val history = b.works.filter { !it.inNow && now - it.recency <= AgentWindows.HISTORY_MS }.sortedByDescending { it.recency }
        val stopped = history.filter { it.stopped }.map { Row(it) }
        val earlierAll = history.filter { !it.stopped }
        val earlier = if (showAllEarlier) earlierAll else earlierAll.take(EARLIER_LIMIT)
        val recent = earlier.filter { now - it.recency <= AgentWindows.RECENT_MS }.map { Row(it) }
        val fortnight = earlier.filter { now - it.recency > AgentWindows.RECENT_MS }.map { Row(it) }

        val listed = if (matchMode) matchRows else nowRows + stopped + recent + fortnight
        val chosen = when {
            listed.isEmpty() -> null
            selectedId != null && listed.any { it.id == selectedId } -> selectedId
            selectedId != null -> listed[selectedHint.coerceIn(0, listed.lastIndex)].id
            matchMode -> listed.first().id
            else -> listed.firstOrNull { it.work.shown == Shown.NEEDS_YOU }?.id ?: listed.first().id
        }
        val row = listed.firstOrNull { it.id == chosen }
        val details = row?.work?.targetId?.let { id -> windowState.agentDetails.value?.takeIf { it.recordId == id } }
        val m = mode

        val peek = if (m is OverviewMode.Peeking && row?.work?.targetId != null) {
            val said = details?.takeIf { it.live == row.work.live }
            Peek(
                live = row.work.live, title = row.work.name,
                closedSinceMillis = if (row.work.live) null else row.work.since,
                lines = said?.screen.orEmpty(), styled = said?.styled.orEmpty(), loading = said == null,
                emptyText = if (said != null && said.screen.isEmpty()) (if (row.work.live) "(the screen is empty)" else "(no saved screen)") else null,
            )
        } else null
        val confirm = (m as? OverviewMode.Confirming)?.let { c ->
            listed.firstOrNull { it.id == c.rowId }?.let { Confirm(it.id, it.work.name, closeWarnings(it.work, details), cancelFocused = !c.onClose) }
        }
        val ask = (m as? OverviewMode.Asking)?.let { a -> row?.takeIf { it.id == a.rowId }?.work?.let(::askOf) }
        val error = row?.work?.let(::errorOf)
        val extra = row?.let { r ->
            listOfNotNull(
                r.work.folder, r.hit?.note ?: details?.note?.takeIf { !r.work.live }, r.work.why, error, r.hit?.label,
            ).filter { it.isNotBlank() }.joinToString(" · ")
        }.orEmpty()
        return State(
            open = this.open, query = query, matchMode = matchMode, matches = if (matchMode) matchRows else emptyList(),
            searching = matchMode && answer == null,
            now = if (matchMode) emptyList() else nowRows,
            stopped = if (matchMode) emptyList() else stopped,
            stoppedAtMillis = stopped.maxOfOrNull { it.work.recency },
            recent = if (matchMode) emptyList() else recent,
            fortnight = if (matchMode) emptyList() else fortnight,
            earlierHidden = earlierAll.size - earlier.size,
            tally = b.tally, selectedId = chosen, peek = peek, confirm = confirm, ask = ask,
            extra = extra, error = error,
            footer = ask?.keys ?: footerOf(row?.work?.enter, canPeek = row?.work?.targetId != null, stopped = if (matchMode) 0 else stopped.size),
        )
    }


    /** The failure the server reported for what the person asked of [w]: its conversation, its task or its tab. */
    private fun errorOf(w: Work): String? {
        val errors = windowState.agentErrors.value
        val tab = w.targetId?.let(board.conversations::get)?.tabId
        return listOfNotNull(w.targetId, w.taskId, tab).firstNotNullOfOrNull(errors::get)
    }

    private fun askOf(w: Work): Ask? = when (w.enter) {
        Enter.RESUME -> Ask(
            w.id,
            if (w.targetId != null && w.targetId == w.mainId) "conversation closed" else "session closed",
            if (w.resumeInTab) "Resume it in its tab?" else "Resume it in a new tab?",
            listOf(FooterKey("↵", "resume"), FooterKey("esc", "cancel")),
        )
        Enter.OPEN -> Ask(w.id, "no conversation", "Start one in a new tab?", listOf(FooterKey("↵", "start"), FooterKey("esc", "cancel")))
        else -> null
    }

    /** Turns a search hit into the row's [Hit]: where it matched and which words to mark. */
    private fun hitOf(w: Work, m: AgentSessionMatch, text: String): Hit {
        val title = m.summary.title
        val tab = w.tabName ?: w.name
        val inTitle = when (m.field) {
            "task" -> true
            "title" -> title == w.name
            // The row leads with the tab's name; a hit in the name it shows is marked there.
            "tab" -> tab.regionMatches(m.start, text, 0, m.length, ignoreCase = true)
            else -> false
        }
        val snippet = when {
            inTitle -> null
            m.field == "title" -> title
            else -> m.snippet
        }
        val shown = if (m.field == "tab" && w.tabName != null && inTitle) HitIn.TAB else if (inTitle) HitIn.TITLE else if (snippet != null) HitIn.SNIPPET else HitIn.ELSEWHERE
        val mark = inTitle || snippet != null
        val label = "in ${fieldLabel(m.field)}" + if (title != w.name && m.field != "task" && m.field != "tab") " of $title" else ""
        return Hit(label, shown, snippet, if (mark) m.start else 0, if (mark) m.length else 0, m.resumeNote)
    }

    private fun fieldLabel(field: String): String = when (field) {
        "task" -> "task title"
        "tab" -> "tab name"
        "prompt" -> "first prompt"
        "screen" -> "last screen"
        else -> field
    }

    /** What closing the session loses: work in progress, a question unanswered, uncommitted or unpushed work. */
    private fun closeWarnings(w: Work, details: WindowEnvelope.AgentDetails?): List<String> {
        val out = mutableListOf<String>()
        val line = w.line.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."
        if (w.shown == Shown.WORKING) out += "It is working right now$line"
        if (w.shown == Shown.NEEDS_YOU) out += "It is waiting for you$line"
        val left = details?.work?.firstOrNull()
        val parts = left?.let { workParts(it.changed, it.added, it.removed, it.untracked, it.unpushed) }.orEmpty()
        if (parts.isNotEmpty()) out += parts.joinToString(" · ") + left?.branch?.let { " on $it" }.orEmpty() + " stay in its folder."
        return out
    }

    /** The parts of "N files changed, +A −R · N untracked · N unpushed commits", empty when nothing is left. */
    private fun workParts(changed: Int, added: Int?, removed: Int?, untracked: Int, unpushed: Int?): List<String> = buildList {
        if (changed > 0) {
            val lines = if (added != null && removed != null && added + removed > 0) ", +$added −$removed" else ""
            add("$changed ${if (changed == 1) "file" else "files"} changed$lines")
        }
        if (untracked > 0) add("$untracked untracked")
        unpushed?.takeIf { it > 0 }?.let { add("$it unpushed ${if (it == 1) "commit" else "commits"}") }
    }

    companion object {
        /**
         * The keys the footer shows for a row.
         *
         * @param enter what ↵ does on the selected row, or null with no row.
         * @param canPeek whether the row has a screen to show.
         * @param stopped how many rows ⇧↵ would resume.
         * @return the keys, left to right.
         */
        fun footerOf(enter: Enter?, canPeek: Boolean, stopped: Int): List<FooterKey> = listOfNotNull(
            FooterKey("↵", if (enter == Enter.SHOW) SHOW_HINT else "open").takeIf { enter != Enter.NONE },
            FooterKey("⇧↵", "resume all $stopped").takeIf { stopped > 0 },
            FooterKey("→", "peek").takeIf { canPeek },
            FooterKey("↑↓", "move"),
            FooterKey("esc", "close"),
        )

        /** What ↵ says on a row whose tab is hidden. */
        const val SHOW_HINT = "open · a tab until you leave it"

        /**
         * What every screen says of the conversations that need the person: "1 needs you", "3 need you".
         * Called by the overview's tally, the sidebar footer and the 3D world boxes, so they read alike.
         *
         * @param n how many conversations need the person.
         * @return the count with its verb.
         */
        fun needYouText(n: Int): String = if (n == 1) "1 needs you" else "$n need you"
    }
}
