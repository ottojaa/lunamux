/**
 * The agents overview in the web client: the ⌘⇧A overlay with a search line, the list of rows and a
 * footer of keys. NOW lists open conversations and tasks, EARLIER the closed ones in two bands, and
 * typing two or more characters swaps both for search results.
 *
 * A row leads with the mark of its state ([se.soderbjorn.lunamux.client.look]: the turning glyph
 * while it works, ◆ when it needs the person, ◐ while it waits, ‖ while paused), the name of its
 * tab or its title, then a state word and the age. Under that comes at most one line. NOW is
 * grouped by repo. What a restart cut off has its own section, with ⇧↵ to resume all of it. A
 * closed row is one line. The selected row shows more under it: a task's agents, a question before
 * a resume, its folder and any note, and the peek.
 *
 * All state and behaviour live in [AgentsOverviewBackingViewModel]; this file draws its state and
 * turns keys and clicks into calls on it. Lists are drawn with [syncChildren], so a row that did
 * not change is not rebuilt and its glyph keeps turning.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.client.Tone
import se.soderbjorn.lunamux.client.look
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Ask
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Confirm
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.HitIn
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Peek
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Under
import se.soderbjorn.lunamux.client.viewmodel.ageText
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings

/** Action id of the overview's hotkey (default ⌘⇧A): the key custom chords are stored under. */
internal const val AGENTS_OVERVIEW_HOTKEY_ACTION_ID: String = "Lunamux.agentsOverview.toggle"

private val agentsOverview: AgentsOverviewBackingViewModel by lazy {
    AgentsOverviewBackingViewModel(lunamuxClient.windowState, send = { windowSocket.send(it) })
}

private var overlayElement: HTMLElement? = null
private var searchInput: HTMLInputElement? = null
private var listElement: HTMLElement? = null
private var footerElement: HTMLElement? = null
private var tallyElement: HTMLElement? = null
private var searchTimer: Int? = null
private var peekTimer: Int? = null
private var lastSelected: String? = null

/** Pause after the last key press before the query is sent to the server. */
private const val SEARCH_DEBOUNCE_MS = 150

/** How often an open live peek asks for a fresh screen. */
private const val PEEK_REFRESH_MS = 2_000

/**
 * Registers the overview's hotkey with the toolkit, so it shows in the shortcuts sidebar and can be
 * rebound. Called once at boot from the toolkit bootstrap.
 */
internal fun registerAgentsOverviewHotkey() {
    HotkeyBindings.registerAction(
        HotkeyActionSpec(
            id = AGENTS_OVERVIEW_HOTKEY_ACTION_ID,
            label = "Agents overview",
            defaults = listOf(Hotkey(key = "a", meta = true, shift = true)),
        ),
    ) { agentsOverview.toggle() }
}

/** Starts following the view model so the overlay opens, redraws and closes with it. Called once at boot. */
@OptIn(DelicateCoroutinesApi::class)
internal fun startAgentsOverview() {
    startAgeClock()
    GlobalScope.launch { agentsOverview.stateFlow.collect { renderAgentsOverview(it) } }
    // The sidebar footer reads the board, and an Agents frame can arrive with no layout or state push.
    GlobalScope.launch { lunamuxClient.windowState.agentBoard.collect { updateWorldStatusFooter() } }
}

private fun renderAgentsOverview(state: AgentsOverviewBackingViewModel.State) {
    if (state.open) {
        ensureOverlay(state.query)
        syncChildren(footerElement ?: return, state.footer, { "${it.key} ${it.label}" }) { key ->
            span("ao-seg").also {
                it.setAttribute("data-key", key.key)
                it.appendChild(span("ao-k", key.key))
                it.appendChild(document.createTextNode(key.label))
            }
        }
        renderTally(state)
        renderList(state)
    } else {
        closeOverlayElement()
    }
    keepPeekFresh(state.peek?.live == true)
}

/** Runs the peek refresh timer only while a live peek is open, so nothing polls once it is closed. */
private fun keepPeekFresh(needed: Boolean) {
    if (needed && peekTimer == null) {
        peekTimer = kotlinx.browser.window.setInterval({ launchAgentsOverview { agentsOverview.refreshPeek() } }, PEEK_REFRESH_MS)
    } else if (!needed) {
        peekTimer?.let { kotlinx.browser.window.clearInterval(it) }
        peekTimer = null
    }
}

/** Sends the query to the server once typing pauses; a newer key press replaces the pending send. */
private fun searchSoon() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = kotlinx.browser.window.setTimeout(
        { searchTimer = null; launchAgentsOverview { agentsOverview.search() } },
        SEARCH_DEBOUNCE_MS,
    )
}

/** Creates the backdrop, search line, list and footer the first time the overlay opens. */
private fun ensureOverlay(query: String) {
    if (overlayElement != null) return
    val backdrop = div("ao-backdrop")
    // Keep keyboard focus in the search box when the pointer lands elsewhere.
    backdrop.addEventListener("mousedown", { e: Event ->
        if (e.target === backdrop) agentsOverview.close()
        if (e.target != searchInput) e.preventDefault()
    })
    val panel = div("ao-panel")
    val line = div("ao-search-line")
    line.appendChild(span("ao-prompt", "❯"))
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "ao-search"
    input.placeholder = "search every task, closed ones too"
    input.value = query
    input.setAttribute("autocomplete", "off")
    input.setAttribute("spellcheck", "false")
    input.addEventListener("input", { _: Event ->
        agentsOverview.setQuery(input.value)
        searchSoon()
    })
    input.addEventListener("keydown", { e: Event -> onSearchKey(e as KeyboardEvent) })
    input.addEventListener("blur", { _: Event ->
        if (overlayElement != null) kotlinx.browser.window.setTimeout({ input.focus() }, 0)
    })
    line.appendChild(input)
    val tally = span("ao-tally")
    line.appendChild(tally)
    val list = div("ao-list")
    list.addEventListener("click", { e: Event -> onListClick(e.target as? HTMLElement) })
    val foot = div("ao-foot")
    foot.addEventListener("click", { e: Event ->
        val key = ((e.target as? HTMLElement)?.closest(".ao-seg") as? HTMLElement)?.getAttribute("data-key")
        if (!key.isNullOrEmpty()) onFooterKey(key)
    })
    panel.appendChild(line)
    panel.appendChild(list)
    panel.appendChild(foot)
    backdrop.appendChild(panel)
    document.body?.appendChild(backdrop)
    overlayElement = backdrop
    searchInput = input
    listElement = list
    footerElement = foot
    tallyElement = tally
    input.focus()
}

private fun closeOverlayElement() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = null
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    footerElement = null
    tallyElement = null
    lastSelected = null
}

/** A click on "+ N more" shows the rest of EARLIER; a click on any other row selects it and does what ↵ does. */
private fun onListClick(target: HTMLElement?) {
    val el = target?.closest(".ao-row") as? HTMLElement ?: return
    if (el.classList.contains("ao-more")) { agentsOverview.showMoreEarlier(); return }
    val row = el.getAttribute("data-id")?.let { id -> agentsOverview.state.rows.firstOrNull { it.id == id } } ?: return
    agentsOverview.select(row.id)
    launchAgentsOverview { agentsOverview.activate(row) }
}

private fun onFooterKey(key: String) {
    when (key) {
        "↵" -> launchAgentsOverview { agentsOverview.activateSelected() }
        "esc" -> agentsOverview.back()
        "→" -> launchAgentsOverview { agentsOverview.peek() }
        "⇧↵" -> launchAgentsOverview { agentsOverview.resumeStopped() }
    }
}

/**
 * Turns a key press in the search box into a view-model call. ↑ and ↓ move the selection; ↵ opens
 * the row; → at the end of the text peeks and ← leaves the peek; ⌘⌫ asks to close the selected
 * session; esc steps back one level (question, confirmation, peek, overlay).
 */
private fun onSearchKey(e: KeyboardEvent) {
    val state = agentsOverview.state
    val input = searchInput
    when {
        e.key == "ArrowDown" -> { e.preventDefault(); agentsOverview.moveSelection(1) }
        e.key == "ArrowUp" -> { e.preventDefault(); agentsOverview.moveSelection(-1) }
        e.key == "Enter" && e.shiftKey -> { e.preventDefault(); launchAgentsOverview { agentsOverview.resumeStopped() } }
        e.key == "Enter" -> { e.preventDefault(); launchAgentsOverview { agentsOverview.activateSelected() } }
        e.key == "Escape" -> { e.preventDefault(); agentsOverview.back() }
        state.confirm != null && (e.key == "ArrowLeft" || e.key == "ArrowRight" || e.key == "Tab") -> {
            e.preventDefault()
            agentsOverview.moveConfirmFocus()
        }
        e.key == "ArrowRight" && input != null && input.selectionStart == input.value.length -> {
            e.preventDefault()
            launchAgentsOverview { agentsOverview.peek() }
        }
        e.key == "ArrowLeft" && state.peek != null -> { e.preventDefault(); agentsOverview.closePeek() }
        e.key == "Backspace" && e.metaKey -> { e.preventDefault(); launchAgentsOverview { agentsOverview.requestClose() } }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun launchAgentsOverview(block: suspend () -> Unit) {
    GlobalScope.launch { block() }
}

/** What the list holds, top to bottom; each has a key so [syncChildren] can tell what changed. */
private sealed interface Item { val key: String }
private data class Heading(val text: String) : Item { override val key get() = "h:$text" }
private data class Group(val text: String) : Item { override val key get() = "g:$text" }
private data class Empty(val text: String) : Item { override val key get() = "empty" }
private data class More(val hidden: Int) : Item { override val key get() = "more" }
private data class RowItem(val row: Row, val selected: Boolean, val ask: Ask?, val peek: Peek?, val grouped: Boolean, val extra: String, val error: String?) : Item {
    override val key get() = "r:${row.id}"
}
private data class ConfirmItem(val confirm: Confirm) : Item { override val key get() = "c:${confirm.rowId}" }

private fun itemsOf(state: AgentsOverviewBackingViewModel.State): List<Item> {
    if (state.rows.isEmpty()) return listOf(Empty(state.emptyText))
    val out = ArrayList<Item>()
    fun add(rows: List<Row>, grouped: Boolean = false) = rows.forEachIndexed { i, row ->
        if (grouped && (i == 0 || rows[i - 1].work.where != row.work.where)) out += Group(row.work.where ?: "no folder")
        val selected = row.id == state.selectedId
        out += RowItem(
            row, selected, state.ask?.takeIf { it.rowId == row.id }, state.peek.takeIf { selected }, grouped,
            extra = if (selected) state.extra else "", error = state.error.takeIf { selected },
        )
        state.confirm?.takeIf { it.rowId == row.id }?.let { out += ConfirmItem(it) }
    }
    if (state.matchMode) {
        state.matchHeading?.let { out += Heading(it) }
        add(state.matches)
        return out
    }
    if (state.now.isNotEmpty()) { out += Heading("NOW"); add(state.now, grouped = true) }
    if (state.stopped.isNotEmpty()) {
        val at = state.stoppedAtMillis?.let { ageText(it, kotlin.js.Date.now().toLong()) }
            ?.let { if (it == "now") " · JUST NOW" else " · ${it.uppercase()} AGO" }.orEmpty()
        out += Heading("STOPPED BY THE RESTART$at")
        add(state.stopped)
    }
    if (state.recent.isNotEmpty()) { out += Heading("EARLIER · LAST FEW DAYS"); add(state.recent) }
    if (state.fortnight.isNotEmpty()) { out += Heading("EARLIER · LAST MONTH"); add(state.fortnight) }
    if (state.earlierHidden > 0) out += More(state.earlierHidden)
    return out
}

private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    syncChildren(list, itemsOf(state), Item::key) { item ->
        when (item) {
            is Heading -> div("ao-sec").also { it.textContent = item.text }
            is Group -> div("ao-grp").also { it.textContent = item.text }
            is Empty -> div("ao-empty").also { it.textContent = item.text }
            is More -> buildMoreLine(item.hidden)
            is RowItem -> buildRow(item)
            is ConfirmItem -> buildConfirm(item.confirm)
        }
    }
    if (state.selectedId != lastSelected) {
        lastSelected = state.selectedId
        (list.querySelector(".ao-sel") as? HTMLElement)?.let { it.asDynamic().scrollIntoView(js("({block: 'nearest'})")) }
    }
}

/** The counts at the end of the search line, from the board's tally. Zero counts are left out. */
private fun renderTally(state: AgentsOverviewBackingViewModel.State) {
    val t = state.tally
    val parts = listOfNotNull(
        ("ao-sw-need" to "◆ " + AgentsOverviewBackingViewModel.needYouText(t.needsYou)).takeIf { t.needsYou > 0 },
        ("ao-sw-work" to "${t.working} working").takeIf { t.working > 0 },
        ("ao-sw-wait" to "◐ ${t.waiting} waiting").takeIf { t.waiting > 0 },
        ("ao-sw-paused" to "‖ ${t.paused} paused").takeIf { t.paused > 0 },
        ("ao-sw-done" to "${t.done} done").takeIf { t.done > 0 },
    )
    syncChildren(tallyElement ?: return, parts, { it.second }) { (cls, text) -> span(cls, text) }
}

/** [text] as a fragment with `[start, start + length)` wrapped in a highlight mark. */
private fun highlighted(text: String, start: Int, length: Int): org.w3c.dom.DocumentFragment {
    val fragment = document.createDocumentFragment()
    if (length <= 0 || start < 0 || start + length > text.length) {
        fragment.appendChild(document.createTextNode(text))
        return fragment
    }
    fragment.appendChild(document.createTextNode(text.substring(0, start)))
    fragment.appendChild(
        (document.createElement("mark") as HTMLElement).also {
            it.className = "ao-mark"
            it.textContent = text.substring(start, start + length)
        },
    )
    fragment.appendChild(document.createTextNode(text.substring(start + length)))
    return fragment
}

/** Puts [text] into [host], with the row's search hit marked in it when the hit fell in [place]. */
private fun fill(host: HTMLElement, row: Row, place: HitIn, text: String) {
    val hit = row.hit
    if (hit != null && hit.`in` == place) host.appendChild(highlighted(text, hit.start, hit.length)) else host.textContent = text
}

/**
 * One row. Its class says how bright it is: `ao-live` while it works, waits or needs the person,
 * `ao-done` when done in NOW, `ao-old` when closed. A closed row is one line ([buildClosedCells]);
 * any other has its one line under the first.
 */
private fun buildRow(item: RowItem): HTMLElement {
    val row = item.row
    val w = row.work
    val brightness = when {
        !w.shown.look.open -> "ao-old"
        w.shown == Shown.WORKING || w.shown == Shown.NEEDS_YOU || w.shown == Shown.WAITING -> "ao-live"
        else -> "ao-done"
    }
    val el = div("ao-row $brightness" + if (item.selected) " ao-sel" else "")
    el.setAttribute("data-id", row.id)
    if (!w.shown.look.open) buildClosedCells(el, row) else buildLiveCells(el, row, item.grouped)
    if (item.selected && w.agents.isNotEmpty()) el.appendChild(buildAgents(row))
    if (item.selected) buildFocus(row, item.ask, item.extra, item.error)?.let { el.appendChild(it) }
    item.peek?.let { el.appendChild(buildPeek(it)) }
    return el
}

/** The mark that opens a row or an agent line: the turning glyph while working, else the state's own glyph. */
private fun stateMark(shown: Shown): HTMLElement = when (shown) {
    Shown.WORKING -> span("ao-m").also { it.appendChild(spinner()) }
    else -> span("ao-m " + markClass(shown), shown.look.mark)
}

private fun markClass(shown: Shown): String = when (shown) {
    Shown.NEEDS_YOU -> "ao-m-need"
    Shown.WAITING -> "ao-m-wait"
    Shown.PAUSED -> "ao-m-paused"
    else -> ""
}

/** The class of a state word, which gives it its colour. */
private fun wordClassOf(shown: Shown): String = when (shown.look.tone) {
    Tone.ALERT -> "ao-sw-need"
    Tone.WORK -> "ao-sw-work"
    Tone.WAIT -> if (shown == Shown.PAUSED) "ao-sw-paused" else "ao-sw-wait"
    Tone.QUIET -> "ao-sw-done"
}

/**
 * An open row: the mark, the name (the tab's, or the title when the tab has none), the state word
 * and the age; then at most one line under it ([lineUnder]). A row outside a repo group shows where
 * it runs.
 */
private fun buildLiveCells(el: HTMLElement, row: Row, grouped: Boolean) {
    val w = row.work
    el.appendChild(stateMark(w.shown))
    val name = span("ao-t")
    if (w.tabName != null) fill(name, row, HitIn.TAB, w.tabName!!) else fill(name, row, HitIn.TITLE, w.name)
    el.appendChild(name)
    el.appendChild(span("ao-w", if (grouped) "" else w.where ?: ""))
    el.appendChild(span("ao-sw " + wordClassOf(w.shown), w.word))
    el.appendChild(ageSpan("ao-age", w.since))
    lineUnder(row)?.let(el::appendChild)
}

/** The one line under an open row's name, the one [Row.under] picks. */
private fun lineUnder(row: Row): HTMLElement? {
    val w = row.work
    return when (row.under()) {
        Under.SNIPPET -> div("ao-n").also { fill(it, row, HitIn.SNIPPET, row.hit!!.snippet!!) }
        Under.QUESTION, Under.STATUS -> stateLine(row)
        Under.RECAP -> div("ao-about").also { it.textContent = w.recap }
        Under.TITLE -> div("ao-about").also { fill(it, row, HitIn.TITLE, w.name) }
        Under.NONE -> null
    }
}

/**
 * The row's headline as a state line: a hook in the colour of where the row stands, then the text,
 * dim unless it is a question for the person, which is said in full amber.
 */
private fun stateLine(row: Row): HTMLElement {
    val tone = when (row.work.shown) {
        Shown.NEEDS_YOU -> "need"
        Shown.WORKING -> "work"
        Shown.WAITING -> "wait"
        Shown.PAUSED -> "paused"
        else -> "done"
    }
    val line = div("ao-n ao-st ao-st-$tone")
    line.appendChild(span("ao-st-g", "↳"))
    line.appendChild(span("ao-st-x", row.work.line))
    return line
}

/**
 * A closed row, on one line: its title with the last thing it said after it, where it ran, the
 * state word and the age. One the restart stopped, whose tab still waits for it, leads with the
 * tab's name and says its title after it.
 */
private fun buildClosedCells(el: HTMLElement, row: Row) {
    val w = row.work
    el.appendChild(stateMark(w.shown))
    val name = span("ao-t")
    name.appendChild(span("", "").also { if (w.tabName != null) fill(it, row, HitIn.TAB, w.tabName!!) else fill(it, row, HitIn.TITLE, w.name) })
    if (w.tabName != null) name.appendChild(span("ao-said").also { fill(it, row, HitIn.TITLE, w.name) })
    val said = row.hit?.snippet ?: w.line
    if (said.isNotEmpty()) name.appendChild(span("ao-said").also { fill(it, row, HitIn.SNIPPET, said) })
    el.appendChild(name)
    el.appendChild(span("ao-w", w.where ?: ""))
    el.appendChild(span("ao-sw", if (w.stopped) "↵ resume" else w.word))
    el.appendChild(ageSpan("ao-age", w.since))
}

/**
 * The conversations of the selected task, one line each: its mark, its name with what it says after
 * it, and its age. The ones in hidden tabs come last, dimmed and marked, so they are not taken for
 * conversations the person can see in a tab. When they all say the same thing, it is left out: the
 * marks already say it.
 */
private fun buildAgents(row: Row): HTMLElement {
    val box = div("ao-agents")
    val agents = row.work.agents
    val same = agents.size > 1 && agents.all { it.line == agents.first().line }
    agents.forEach { agent ->
        box.appendChild(stateMark(agent.shown))
        val text = span("ao-agent" + if (agent.hidden) " ao-agent-bg" else "")
        text.appendChild(span("ao-agent-name", agent.title))
        if (agent.line.isNotEmpty() && !same) text.appendChild(span("ao-said", agent.line))
        if (agent.hidden) text.appendChild(span("ao-said", "· hidden tab"))
        box.appendChild(text)
        box.appendChild(ageSpan("ao-age", agent.since))
    }
    return box
}

/**
 * What the selected row shows last: the question before a resume or a start, else a done row's last
 * reply and its extra text (its folder, where a search matched, why ↵ cannot act, a failed resume
 * in the error colour).
 */
private fun buildFocus(row: Row, ask: Ask?, extra: String, error: String?): HTMLElement? {
    val focus = div("ao-focus")
    if (ask != null) focus.appendChild(buildAsk(ask))
    val reply = row.work.lastReply
    if (ask == null && reply != null) {
        focus.appendChild(div("ao-xtra").also {
            it.appendChild(span("ao-reply-lbl", "last reply"))
            it.appendChild(span("ao-reply", reply))
        })
    }
    if (ask == null && extra.isNotEmpty()) {
        val line = div("ao-xtra")
        val at = if (error != null) extra.indexOf(error) else -1
        if (error != null && at >= 0) {
            line.appendChild(document.createTextNode(extra.substring(0, at)))
            line.appendChild(span("ao-n-error", error))
            line.appendChild(document.createTextNode(extra.substring(at + error.length)))
        } else {
            line.textContent = extra
        }
        focus.appendChild(line)
    }
    return if (focus.hasChildNodes()) focus else null
}

/** The question bar: a dim tag, the question and the keys that answer it. A click outside a key does nothing. */
private fun buildAsk(ask: Ask): HTMLElement {
    val bar = div("ao-ask")
    bar.addEventListener("click", { e: Event ->
        e.stopPropagation()
        val key = ((e.target as? HTMLElement)?.closest(".ao-ask-key") as? HTMLElement)?.getAttribute("data-key")
        if (!key.isNullOrEmpty()) onFooterKey(key)
    })
    val q = span("ao-ask-q")
    q.appendChild(span("ao-ask-tag", ask.tag))
    q.appendChild(document.createTextNode(ask.question))
    bar.appendChild(q)
    val keys = span("ao-ask-keys")
    ask.keys.forEach { k ->
        val key = span("ao-ask-key")
        key.setAttribute("data-key", k.key)
        key.appendChild(span("ao-kbd", k.key))
        key.appendChild(document.createTextNode(k.label))
        keys.appendChild(key)
    }
    bar.appendChild(keys)
    return bar
}

/** The dim "+ N more" line that ends EARLIER; a click shows the rest. */
private fun buildMoreLine(hidden: Int): HTMLElement {
    val el = div("ao-row ao-more")
    el.appendChild(span("ao-m"))
    el.appendChild(span("ao-t", "+ $hidden more"))
    return el
}

/**
 * The popover under the selected row: a heading and the session's screen, live or saved.
 *
 * @param peek what to show.
 * @return the popover.
 */
private fun buildPeek(peek: Peek): HTMLElement {
    val box = div("ao-peek")
    box.addEventListener("click", { e: Event -> e.stopPropagation() })
    val closedAge = peek.closedSinceMillis?.let { ageText(it, kotlin.js.Date.now().toLong()) } ?: "now"
    box.appendChild(div("ao-peek-title").also {
        it.textContent = if (peek.live) "LIVE SCREEN · ${peek.title}".uppercase() else "SAVED SCROLLBACK · CLOSED $closedAge".uppercase()
    })
    when {
        peek.loading -> box.appendChild(div("ao-peek-line").also { it.textContent = "…" })
        peek.emptyText != null -> box.appendChild(div("ao-peek-line").also { it.textContent = peek.emptyText })
        peek.styled.isNotEmpty() -> {
            val palette = buildTermPalette()
            peek.styled.forEach { row -> box.appendChild(styledLine(row, palette)) }
        }
        // A blank row keeps its height: on an agent's screen it is what separates one item from the next.
        else -> peek.lines.forEach { line -> box.appendChild(div("ao-peek-line").also { it.textContent = line.ifEmpty { " " } }) }
    }
    return box
}

/**
 * One row of a live screen in the terminal's own styles: a span per run with its colours, weight,
 * slant, dimness and lines. Colours resolve against [palette], the terminal theme's (so a peek reads
 * like the pane), with a bold run in one of the low eight colours drawn bright as the terminal does.
 *
 * @param runs the row's runs; none for a blank row.
 * @param palette the terminal's resolved colours.
 * @return the row's element.
 */
private fun styledLine(runs: List<ScreenRun>, palette: TermPalette): HTMLElement {
    val line = div("ao-peek-line")
    line.style.color = palette.defaultFg
    if (runs.isEmpty()) line.textContent = " "
    runs.forEach { run ->
        val el = span("", run.text)
        var fg = run.fg?.let { c -> cssColorOf(if (run.bold && c in 0..7) c + 8 else c, palette) }
        var bg = run.bg?.let { cssColorOf(it, palette) }
        if (run.inverse) {
            val swapped = fg ?: palette.defaultFg
            fg = bg ?: palette.defaultBg
            bg = swapped
        }
        val st = el.style
        fg?.let { st.color = it }
        bg?.let { st.backgroundColor = it }
        if (run.bold) st.fontWeight = "bold"
        if (run.italic) st.fontStyle = "italic"
        if (run.dim) st.opacity = "0.5"
        val lines = listOfNotNull("underline".takeIf { run.underline }, "line-through".takeIf { run.strike })
        if (lines.isNotEmpty()) st.textDecoration = lines.joinToString(" ")
        line.appendChild(el)
    }
    return line
}

/** A [ScreenRun] colour as CSS: a true colour as itself, a palette entry from [palette]. */
private fun cssColorOf(color: Int, palette: TermPalette): String =
    if (color and ScreenRun.RGB != 0) {
        val rgb = color and 0xffffff
        "#" + (rgb or 0x1000000).toString(16).substring(1)
    } else palette.colors256.getOrElse(color) { palette.defaultFg }

/** The inline "Close “title”?" bar with its warnings; the focused button shows a ring. */
private fun buildConfirm(confirm: Confirm): HTMLElement {
    val bar = div("ao-confirm")
    bar.addEventListener("click", { e: Event -> e.stopPropagation() })
    val text = div("ao-confirm-text")
    text.appendChild(span("", "Close “${confirm.title}”? The agent stops. You can find it here and resume it later."))
    confirm.warnings.forEach { text.appendChild(div("ao-confirm-warn").also { w -> w.textContent = it }) }
    bar.appendChild(text)
    val cancel = document.createElement("button") as HTMLElement
    cancel.className = "ao-btn" + if (confirm.cancelFocused) " ao-btn-focus" else ""
    cancel.textContent = "Cancel"
    cancel.addEventListener("click", { _: Event -> agentsOverview.cancelClose() })
    val close = document.createElement("button") as HTMLElement
    close.className = "ao-btn ao-btn-danger" + if (!confirm.cancelFocused) " ao-btn-focus" else ""
    close.textContent = "Close session"
    close.addEventListener("click", { _: Event -> launchAgentsOverview { agentsOverview.confirmClose() } })
    bar.appendChild(cancel)
    bar.appendChild(close)
    return bar
}
