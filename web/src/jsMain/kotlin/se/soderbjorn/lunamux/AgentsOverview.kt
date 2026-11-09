/**
 * The agents overview in the web client: the ⌘⇧A overlay with a search line, the list of rows and a
 * footer of keys. NOW lists live conversations and tasks, EARLIER the closed ones in two bands, and
 * typing two or more characters swaps both for search results.
 *
 * Every row is one grid: a ◆ when the row needs the person, the title, where it runs, a state word
 * (the working one has the turning glyph in front), the detail and the age. The selected row shows
 * more under it: the status in full when its detail cell cut it short, a question before a resume,
 * the folder and tab chips, and the peek. That cut is measured here after drawing, because only the
 * browser knows the width.
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
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Ask
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Confirm
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Peek
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings

/** Action id of the overview's hotkey (default ⌘⇧A): the key custom chords are stored under. */
internal const val AGENTS_OVERVIEW_HOTKEY_ACTION_ID: String = "Lunamux.agentsOverview.toggle"

private val agentsOverview: AgentsOverviewBackingViewModel by lazy {
    AgentsOverviewBackingViewModel(lunamuxClient.windowState) { windowSocket.send(it) }
}

private var overlayElement: HTMLElement? = null
private var searchInput: HTMLInputElement? = null
private var listElement: HTMLElement? = null
private var footerElement: HTMLElement? = null
private var searchTimer: Int? = null
private var peekTimer: Int? = null
private var lastSelected: String? = null

/** Pause after the last key press before the query is sent to the server. */
private const val SEARCH_DEBOUNCE_MS = 150

/** How often an open live peek asks for a fresh screen. */
private const val PEEK_REFRESH_MS = 2_000

/** Registers the overview's hotkey with the toolkit, so it shows in the shortcuts sidebar and can be rebound. */
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
    // The sidebar footer counts the same sessions, so it follows the same pushes.
    GlobalScope.launch { lunamuxClient.windowState.agentSessions.collect { updateWorldStatusFooter() } }
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
    kotlinx.browser.window.addEventListener("resize", resizeListener)
    input.focus()
}

private val resizeListener: (Event) -> Unit = { fitSelectedStatus() }

private fun closeOverlayElement() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = null
    kotlinx.browser.window.removeEventListener("resize", resizeListener)
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    footerElement = null
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
private data class Empty(val text: String) : Item { override val key get() = "empty" }
private data class More(val hidden: Int) : Item { override val key get() = "more" }
private data class RowItem(val row: Row, val selected: Boolean, val ask: Ask?, val peek: Peek?) : Item { override val key get() = "r:${row.id}" }
private data class ConfirmItem(val confirm: Confirm) : Item { override val key get() = "c:${confirm.rowId}" }

private fun itemsOf(state: AgentsOverviewBackingViewModel.State): List<Item> {
    if (state.rows.isEmpty()) return listOf(Empty(state.emptyText))
    val out = ArrayList<Item>()
    fun add(rows: List<Row>) = rows.forEach { row ->
        val selected = row.id == state.selectedId
        out += RowItem(row, selected, state.ask?.takeIf { it.rowId == row.id }, state.peek.takeIf { selected })
        state.confirm?.takeIf { it.rowId == row.id }?.let { out += ConfirmItem(it) }
    }
    if (state.matchMode) {
        state.matchHeading?.let { out += Heading(it) }
        add(state.matches)
        return out
    }
    if (state.now.isNotEmpty()) { out += Heading("NOW"); add(state.now) }
    if (state.recent.isNotEmpty()) { out += Heading("EARLIER · LAST FEW DAYS"); add(state.recent) }
    if (state.fortnight.isNotEmpty()) { out += Heading("EARLIER · LAST TWO WEEKS"); add(state.fortnight) }
    if (state.earlierHidden > 0) out += More(state.earlierHidden)
    return out
}

private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    syncChildren(list, itemsOf(state), Item::key) { item ->
        when (item) {
            is Heading -> div("ao-sec").also { it.textContent = item.text }
            is Empty -> div("ao-empty").also { it.textContent = item.text }
            is More -> buildMoreLine(item.hidden)
            is RowItem -> buildRow(item)
            is ConfirmItem -> buildConfirm(item.confirm)
        }
    }
    fitSelectedStatus()
    if (state.selectedId != lastSelected) {
        lastSelected = state.selectedId
        (list.querySelector(".ao-sel") as? HTMLElement)?.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
    }
}

/**
 * Shows the selected row's status in full under it when its detail cell cut the text short (an
 * ellipsis, or hidden in a narrow panel), and takes it away when the cell fits again.
 */
private fun fitSelectedStatus() {
    val list = listElement ?: return
    val row = list.querySelector(".ao-sel") as? HTMLElement ?: return
    if (list.clientWidth == 0 || row.querySelector(".ao-ask") != null) return
    val cell = row.querySelector(".ao-n") as? HTMLElement ?: return
    val hidden = kotlinx.browser.window.getComputedStyle(cell).display == "none"
    val cut = !cell.textContent.isNullOrBlank() && (hidden || cell.scrollWidth > cell.clientWidth)
    val shown = row.querySelector(".ao-full") as? HTMLElement
    if (!cut) {
        shown?.remove()
        (row.querySelector(".ao-focus") as? HTMLElement)?.takeIf { !it.hasChildNodes() }?.remove()
        return
    }
    if (shown != null) return
    val focus = row.querySelector(".ao-focus") as? HTMLElement ?: div("ao-focus").also {
        row.insertBefore(it, row.querySelector(".ao-peek"))
    }
    val full = div("ao-full")
    cell.childNodes.let { nodes -> for (i in 0 until nodes.length) full.appendChild(nodes.item(i)!!.cloneNode(true)) }
    focus.insertBefore(full, focus.firstChild)
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

/**
 * One row of the six-column grid. Its class says how bright it is: `ao-live` while it works or
 * needs the person, `ao-done` when done in NOW, `ao-old` when finished in EARLIER.
 */
private fun buildRow(item: RowItem): HTMLElement {
    val row = item.row
    val brightness = when {
        row.finished -> "ao-old"
        row.working || row.needsYou -> "ao-live"
        else -> "ao-done"
    }
    val el = div("ao-row $brightness" + if (item.selected) " ao-sel" else "")
    el.setAttribute("data-id", row.id)
    el.appendChild(span(if (row.needsYou) "ao-m ao-m-need" else "ao-m", if (row.needsYou) "◆" else ""))
    val title = span("ao-t")
    if (row.matchInTitle) title.appendChild(highlighted(row.title, row.matchStart, row.matchLength)) else title.textContent = row.title
    el.appendChild(title)
    el.appendChild(span("ao-w", row.where ?: ""))
    el.appendChild(buildState(row))
    val detail = span("ao-n")
    if (row.matchInSnippet) detail.appendChild(highlighted(row.detail, row.matchStart, row.matchLength)) else detail.textContent = row.detail
    el.appendChild(detail)
    el.appendChild(ageSpan("ao-age", row.sinceMillis))
    if (item.selected) buildFocus(row, item.ask)?.let { el.appendChild(it) }
    item.peek?.let { el.appendChild(buildPeek(it)) }
    return el
}

/** The state word in its state's colour; a working row has the spinner in front. */
private fun buildState(row: Row): HTMLElement {
    val cell = span(
        "ao-sw" + when {
            row.needsYou -> " ao-sw-need"
            row.working -> " ao-sw-work"
            !row.finished -> " ao-sw-done"
            else -> ""
        },
    )
    if (row.working) {
        cell.appendChild(spinner())
        cell.appendChild(document.createTextNode(" ${row.stateWord}"))
    } else {
        cell.textContent = row.stateWord
    }
    return cell
}

/**
 * What the selected row shows under its columns: the question before a resume or a start, then the
 * chips (folder, and the tab when it says something), then any extra text (where a search matched,
 * a failed resume in the error colour).
 */
private fun buildFocus(row: Row, ask: Ask?): HTMLElement? {
    val focus = div("ao-focus")
    if (ask != null) focus.appendChild(buildAsk(ask))
    if (row.chips.isNotEmpty()) {
        val chips = div("ao-chips")
        // While a question is up only its own keys answer it: a click on a chip is not an answer.
        if (ask != null) chips.addEventListener("click", { e: Event -> e.stopPropagation() })
        row.chips.forEach { chip ->
            chips.appendChild(span("ao-pill " + if (chip.kind == AgentsOverviewBackingViewModel.ChipKind.TAB) "ao-pill-tab" else "ao-pill-dir", chip.text))
        }
        focus.appendChild(chips)
    }
    if (ask == null && row.extra.isNotEmpty()) {
        val line = div("ao-xtra")
        val error = row.error
        val at = if (error != null) row.extra.indexOf(error) else -1
        if (error != null && at >= 0) {
            line.appendChild(document.createTextNode(row.extra.substring(0, at)))
            line.appendChild(span("ao-n-error", error))
            line.appendChild(document.createTextNode(row.extra.substring(at + error.length)))
        } else {
            line.textContent = row.extra
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

/** The popover under the selected row: a heading and the last lines of the session's screen. */
private fun buildPeek(peek: Peek): HTMLElement {
    val box = div("ao-peek")
    box.addEventListener("click", { e: Event -> e.stopPropagation() })
    val closedAge = peek.closedSinceMillis?.let { AgentFacts.ageText(it, kotlin.js.Date.now().toLong()) } ?: "now"
    box.appendChild(div("ao-peek-title").also {
        it.textContent = if (peek.live) "LIVE SCREEN · ${peek.title}".uppercase() else "SAVED SCROLLBACK · CLOSED $closedAge".uppercase()
    })
    when {
        peek.loading -> box.appendChild(div("ao-peek-line").also { it.textContent = "…" })
        peek.emptyText != null -> box.appendChild(div("ao-peek-line").also { it.textContent = peek.emptyText })
        else -> peek.lines.forEach { line -> box.appendChild(div("ao-peek-line").also { it.textContent = line }) }
    }
    return box
}

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
