/**
 * The agents overview in the web client: a topbar chip that says how many agent sessions need the
 * user and how many work, and the ⌘⇧A overlay that lists every task as one aligned row under
 * "NOW", then what finished or went quiet under "EARLIER" in two bands, "last few days" and
 * "last two weeks" (a heading with nothing under it is not drawn).
 *
 * Every row is the same six-column grid: a marker (◆ when the task needs the person, ▸ on the
 * selected row), the title, where it runs, one state word, a detail line and the age. Brightness
 * and colour follow the state: a working or needs-you row has a bright title, a done one a
 * mid-grey title, and a finished one in EARLIER a dim row (a task in EARLIER whose agent still moves stays bright, with the glyph). The state word is cyan with the app's working glyph in
 * front while an agent moves ([workingGlyph]), yellow when the task needs the person, a muted
 * green when it is done. The selected row carries one more line under it with the tab its session
 * sits in and the like, and the detail's full text when (and only when) the row cut it short or
 * hides it in a narrow panel; it has none when there is nothing to put on it. ↵ opens the row, ⌘↵ takes a
 * background tab over, → peeks at the session ↵ would open, and ⌘⌫ closes a live one after an
 * inline confirmation. Typing two or more characters swaps the lists for search results over every
 * task ever run, one row per task. EARLIER shows its newest five rows and a "+ N more" line for
 * the rest. The footer is the same for every selection.
 *
 * All state and behaviour live in the shared [AgentsOverviewBackingViewModel]; this file only
 * draws its state as plain DOM and turns key presses and clicks into calls on it. Rows are found
 * again by their id, never by their position, so a redraw while the list changes under the
 * pointer cannot move a click onto another row. The chip and the overlay read the same state, so
 * their counts always agree.
 *
 * @see AgentsOverviewBackingViewModel
 * @see workingGlyph
 * @see registerAgentsOverviewHotkey
 * @see buildAgentsOverviewTopbarAction
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
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunula.web.hotkey.Hotkey
import se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec
import se.soderbjorn.lunula.web.hotkey.HotkeyBindings
import se.soderbjorn.lunula.web.shell.TopbarAction

/**
 * Stable action id for the **agents overview** hotkey (default ⌘⇧A). Persistence
 * key for custom chords, and the id the Keyboard-shortcuts sidebar row references.
 *
 * @see registerAgentsOverviewHotkey
 */
internal const val AGENTS_OVERVIEW_HOTKEY_ACTION_ID: String = "Lunamux.agentsOverview.toggle"

/** The shared view model; created on first use, after the window socket exists. */
private val agentsOverview: AgentsOverviewBackingViewModel by lazy {
    AgentsOverviewBackingViewModel(lunamuxClient.windowState) { windowSocket.send(it) }
}

private var chipElement: HTMLElement? = null
private var overlayElement: HTMLElement? = null
private var searchInput: HTMLInputElement? = null
private var listElement: HTMLElement? = null
private var shownRows: Map<String, Row> = emptyMap()
private var searchTimer: Int? = null
private var peekTimer: Int? = null
private var clockTimer: Int? = null
private var lastState: AgentsOverviewBackingViewModel.State? = null

/** Pause after the last key press before the query is sent to the server. */
private const val SEARCH_DEBOUNCE_MS = 150

/** How often an open live peek asks for a fresh screen. */
private const val PEEK_REFRESH_MS = 2_000

/** How often the open list redraws on its own, so ages move and a working row that has gone quiet stops moving. */
private const val CLOCK_TICK_MS = 30_000

/** The footer's keys and what they do. It is the same for every selection. */
private val FOOTER = listOf("↵" to "open", "↑↓" to "move", "esc" to "close")

/**
 * Register the configurable **agents overview** hotkey (default ⌘⇧A) with the
 * toolkit's [HotkeyBindings], so it appears in the Keyboard-shortcuts sidebar
 * and is user-changeable. Called once at boot from [bootViaToolkitShell].
 *
 * @see AGENTS_OVERVIEW_HOTKEY_ACTION_ID
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

/**
 * Build the topbar chip ("◆ 2 need you · 1 working") that opens the overview. It shows from the
 * first agent session on, so it also says "agents idle", and starts following the view model's
 * state as soon as it is built.
 *
 * Called by [bootViaToolkitShell] when it assembles the topbar.
 *
 * @return the topbar action wrapping the chip button.
 * @see AgentsOverviewBackingViewModel.State.chipVisible
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun buildAgentsOverviewTopbarAction(): TopbarAction {
    val chip = document.createElement("button") as HTMLElement
    chip.asDynamic().type = "button"
    chip.className = "ao-chip"
    chip.style.display = "none"
    chip.title = "Agents overview"
    chip.addEventListener("click", { _: Event -> agentsOverview.toggle() })
    chipElement = chip

    GlobalScope.launch { agentsOverview.stateFlow.collect { renderAgentsOverview(it) } }

    return TopbarAction(
        id = "tt-topbar-agents-overview",
        label = "Agents overview",
        onActivate = { agentsOverview.toggle() },
        element = chip,
    )
}

/** Redraw the chip, and the overlay if it is open, from [state]. */
private fun renderAgentsOverview(state: AgentsOverviewBackingViewModel.State) {
    lastState = state
    renderChip(state)
    if (state.open) {
        ensureOverlay(state.query)
        renderList(state)
    } else {
        closeOverlayElement()
    }
    keepPeekFresh(state.peek?.live == true)
    // The chip counts a session as working only while it is moving, which the clock alone can end.
    keepClockTicking(state.open || state.workingCount > 0)
}

/**
 * Start or stop the timer that redraws the open list every [CLOCK_TICK_MS], keeping the scroll
 * position. Nothing else changes the picture while the server is silent, and a hung agent is
 * exactly the case where it must change.
 *
 * @param needed whether the overlay is open, or a session is counted as working (which only the
 *   clock can stop, once it goes quiet).
 */
private fun keepClockTicking(needed: Boolean) {
    if (needed && clockTimer == null) {
        clockTimer = kotlinx.browser.window.setInterval({
            // An idle session leaves NOW on the clock alone, so the state is built again first.
            agentsOverview.tick()
            val state = lastState
            val list = listElement
            if (state != null && state.open && list != null) {
                val top = list.scrollTop
                renderList(state)
                list.scrollTop = top
            }
        }, CLOCK_TICK_MS)
    } else if (!needed) {
        clockTimer?.let { kotlinx.browser.window.clearInterval(it) }
        clockTimer = null
    }
}

/**
 * Start or stop the timer that refreshes an open live peek, so the screen follows the
 * agent while it is shown and nothing polls once it is closed.
 *
 * @param needed whether a live peek is open.
 */
private fun keepPeekFresh(needed: Boolean) {
    if (needed && peekTimer == null) {
        peekTimer = kotlinx.browser.window.setInterval(
            { launchAgentsOverview { agentsOverview.refreshPeek() } },
            PEEK_REFRESH_MS,
        )
    } else if (!needed) {
        peekTimer?.let { kotlinx.browser.window.clearInterval(it) }
        peekTimer = null
    }
}

/** Send the query to the server once typing pauses; a newer key press replaces the pending send. */
private fun searchSoon() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = kotlinx.browser.window.setTimeout(
        { searchTimer = null; launchAgentsOverview { agentsOverview.search() } },
        SEARCH_DEBOUNCE_MS,
    )
}

/**
 * Redraw the topbar chip: "◆ 2 need you" in the warning colour, then the dim rest ("1 working",
 * or "agents idle"), joined by a faint dot.
 *
 * @param state the view model's current state.
 */
private fun renderChip(state: AgentsOverviewBackingViewModel.State) {
    val chip = chipElement ?: return
    chip.style.display = if (state.chipVisible) "" else "none"
    chip.textContent = ""
    val needs = state.chipNeeds
    val rest = state.chipRest
    if (needs != null) chip.appendChild(span("ao-chip-need", "◆ $needs"))
    if (needs != null && rest != null) chip.appendChild(span("ao-chip-sep", "·"))
    if (rest != null) chip.appendChild(span("ao-chip-rest", rest))
}

/** Create the backdrop, search line, list and footer the first time the overlay opens. */
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
    FOOTER.forEach { (key, word) ->
        val seg = span("ao-seg", "")
        seg.setAttribute("data-key", key)
        seg.appendChild(span("ao-k", key))
        seg.appendChild(document.createTextNode(word))
        foot.appendChild(seg)
    }

    panel.appendChild(line)
    panel.appendChild(list)
    panel.appendChild(foot)
    backdrop.appendChild(panel)
    document.body?.appendChild(backdrop)
    overlayElement = backdrop
    searchInput = input
    listElement = list
    kotlinx.browser.window.addEventListener("resize", resizeListener)
    input.focus()
}

/** Re-checks whether the selected row cuts its detail when the window, and so the panel, changes size. */
private val resizeListener: (Event) -> Unit = { fitSelectedFocus() }

/** Remove the overlay from the page and forget every element that belonged to it. */
private fun closeOverlayElement() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = null
    kotlinx.browser.window.removeEventListener("resize", resizeListener)
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    shownRows = emptyMap()
}

/**
 * Turn a click in the list into a view-model call, finding the row by its id.
 *
 * The "+ N more" line shows the rest of EARLIER; any other row is selected and opened,
 * as ↵ would.
 *
 * @param target the element that was clicked, or null.
 */
private fun onListClick(target: HTMLElement?) {
    val el = target?.closest(".ao-row") as? HTMLElement ?: return
    if (el.classList.contains("ao-more")) { agentsOverview.showMoreEarlier(); return }
    val row = el.getAttribute("data-id")?.let { shownRows[it] } ?: return
    agentsOverview.select(row.id)
    launchAgentsOverview { agentsOverview.activate(row) }
}

/**
 * Do what a footer segment's key does, when the segment is clicked.
 *
 * @param key the segment's key as drawn (`↵` or `esc`); the others do nothing.
 */
private fun onFooterKey(key: String) {
    when (key) {
        "↵" -> launchAgentsOverview { agentsOverview.activateSelected() }
        "esc" -> agentsOverview.back()
    }
}

/**
 * Turn a key press in the search box into a view-model call.
 *
 * ↑ and ↓ move the selection (which also closes a peek or a confirmation). → peeks at the session
 * ↵ would open when the caret is at the end of the text, and ← goes back out of the peek. ⌘⌫ asks
 * to close the selected session (only while the box is empty and the row is one live session),
 * ⌘↵ takes the selected session's background tab over (in the list and in its peek), and esc steps
 * back one level: the peek, then the question, then the overlay. Space types into the search.
 *
 * @param e the key press in the search box.
 */
private fun onSearchKey(e: KeyboardEvent) {
    val state = agentsOverview.state
    val input = searchInput
    when {
        e.key == "ArrowDown" -> { e.preventDefault(); agentsOverview.moveSelection(1) }
        e.key == "ArrowUp" -> { e.preventDefault(); agentsOverview.moveSelection(-1) }
        e.key == "Enter" && (e.metaKey || e.ctrlKey) -> { e.preventDefault(); launchAgentsOverview { agentsOverview.takeOverSelected() } }
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
        e.key == "ArrowLeft" && state.peek != null -> {
            e.preventDefault()
            agentsOverview.closePeek()
        }
        e.key == "Backspace" && e.metaKey && state.query.isEmpty() -> {
            e.preventDefault()
            launchAgentsOverview { agentsOverview.requestClose(fromKeyboard = true) }
        }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun launchAgentsOverview(block: suspend () -> Unit) {
    GlobalScope.launch { block() }
}

/**
 * Rebuild the list from [state]: search results under a "N MATCHES" heading while searching,
 * otherwise the "NOW" section and the two EARLIER bands. The selected row is highlighted and
 * carries its extra line and the peek; the close confirmation follows the row it asks about.
 *
 * @param state the view model's current state.
 */
private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    list.textContent = ""
    shownRows = state.rows.associateBy { it.id }
    if (state.rows.isEmpty()) {
        list.appendChild(div("ao-empty").also { it.textContent = state.emptyText })
        return
    }
    fun add(rows: List<Row>) = rows.forEach { row ->
        list.appendChild(buildRow(row, state, row.id == state.selectedId))
        state.confirm?.takeIf { it.rowId == row.id }?.let { list.appendChild(buildConfirm(it)) }
    }
    if (state.matchMode) {
        state.matchHeading?.let { list.appendChild(div("ao-sec").also { h -> h.textContent = it }) }
        add(state.matches)
    } else {
        if (state.now.isNotEmpty()) {
            list.appendChild(div("ao-sec").also { it.textContent = "NOW" })
            add(state.now)
        }
        if (state.recent.isNotEmpty()) {
            list.appendChild(div("ao-sec").also { it.textContent = "EARLIER · LAST FEW DAYS" })
            add(state.recent)
        }
        if (state.fortnight.isNotEmpty()) {
            list.appendChild(div("ao-sec").also { it.textContent = "EARLIER · LAST TWO WEEKS" })
            add(state.fortnight)
        }
        // The line closes EARLIER whichever band the last shown row is in.
        if (state.earlierHidden > 0) list.appendChild(buildMoreLine(state.earlierHidden))
    }
    fitSelectedFocus()
    (list.querySelector(".ao-sel") as? HTMLElement)?.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
}

/**
 * Whether the row element [el] cut its detail short: the detail cell is cut off with an ellipsis,
 * or is not drawn at all (the narrow layout hides it). A row with no detail never cuts anything.
 *
 * Reads the layout, so it only means something for an element that is in the page.
 *
 * @param el a row element built by [buildRow].
 * @return true when the whole detail is not visible in the row.
 */
private fun detailIsCut(el: HTMLElement): Boolean {
    val cell = el.querySelector(".ao-n") as? HTMLElement ?: return false
    if (cell.textContent.isNullOrBlank()) return false
    if (kotlinx.browser.window.getComputedStyle(cell).display == "none") return true
    return cell.scrollWidth > cell.clientWidth
}

/**
 * Put the detail's full text on the selected row's second line when the row cut it, and take it off
 * again when it no longer does (a wider panel).
 *
 * The row is drawn first without the repeat and measured once it is in the page; only when the
 * answer differs from what the row shows does its second line change, so the call is cheap and
 * cannot loop. A selected row whose detail fits keeps a second line only for what the view model
 * put in [Row.extra], and none when that is empty.
 *
 * Called at the end of [renderList] and from the window's resize event.
 *
 * @see buildFocus
 * @see detailIsCut
 */
private fun fitSelectedFocus() {
    val list = listElement ?: return
    val el = list.querySelector(".ao-sel") as? HTMLElement ?: return
    if (list.clientWidth == 0) return
    val row = el.getAttribute("data-id")?.let { shownRows[it] } ?: return
    val cut = detailIsCut(el)
    if (cut == el.hasAttribute("data-cut")) return
    if (cut) el.setAttribute("data-cut", "1") else el.removeAttribute("data-cut")
    (el.querySelector(".ao-focus") as? HTMLElement)?.remove()
    buildFocus(row, repeatDetail = cut)?.let { focus ->
        val peek = el.querySelector(".ao-peek")
        if (peek != null) el.insertBefore(focus, peek) else el.appendChild(focus)
    }
}

/**
 * [text] as a fragment with `[start, start + length)` wrapped in a highlight mark.
 *
 * @param text the text to show.
 * @param start where the match begins.
 * @param length how many characters it covers; 0 or less shows the text plain.
 */
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
 * One row of the table in the six-column grid: marker, title, where, state word, detail, age.
 *
 * The marker is ◆ for a task that needs the person and ▸ on the selected row (which wins over
 * ◆). The row's class says how bright it is: `ao-live` for a task that works or needs the person,
 * `ao-done` for one that is done in NOW, `ao-old` for one that finished and sits in EARLIER; a
 * working row in EARLIER is live, because the state wins over the band. The state word carries
 * the state's colour, and "working" has the working glyph in front. The selected row has
 * one more line under it, spanning the columns after the marker, with the tab ↵ opens and the like
 * (and the detail's full text when the row cut it, which [fitSelectedFocus] finds out), when there is any; a failed resume or open shows in the error colour inside it. An open
 * peek follows.
 *
 * @param row the row to draw.
 * @param state the view model's state, for the open peek.
 * @param selected whether it is the highlighted row.
 * @return the row element, carrying the row's id as `data-id` for click routing.
 */
private fun buildRow(row: Row, state: AgentsOverviewBackingViewModel.State, selected: Boolean): HTMLElement {
    val classes = mutableListOf("ao-row")
    if (selected) classes += "ao-sel"
    classes += when {
        row.finished -> "ao-old"
        row.working || row.needsYou -> "ao-live"
        else -> "ao-done"
    }
    val el = div(classes.joinToString(" "))
    el.setAttribute("data-id", row.id)

    el.appendChild(span(if (row.needsYou && !selected) "ao-m ao-m-need" else "ao-m", if (selected) "▸" else row.marker))

    val title = span("ao-t", "")
    if (row.matchInTitle) title.appendChild(highlighted(row.title, row.matchStart, row.matchLength))
    else title.textContent = row.title
    el.appendChild(title)

    el.appendChild(span("ao-w", row.where ?: ""))
    el.appendChild(buildState(row))

    val detail = span("ao-n", "")
    if (row.matchInSnippet) detail.appendChild(highlighted(row.detail, row.matchStart, row.matchLength))
    else detail.textContent = row.detail
    el.appendChild(detail)

    el.appendChild(span("ao-age", if (row.sinceMillis <= 0) "" else formatAge(row.sinceMillis)))

    if (selected) buildFocus(row, repeatDetail = false)?.let { el.appendChild(it) }
    state.peek?.takeIf { selected }?.let { el.appendChild(buildPeek(it)) }
    return el
}

/**
 * The state cell: the state word in the colour of its state, and for a working row the working
 * glyph in front of it.
 *
 * Called by [buildRow] for every row.
 *
 * @param row the row to draw.
 * @return the cell, whose class says which colour the word takes.
 * @see workingGlyph
 */
private fun buildState(row: Row): HTMLElement {
    val cell = span(
        "ao-sw" + when {
            row.needsYou -> " ao-sw-need"
            row.working -> " ao-sw-work"
            !row.finished -> " ao-sw-done"
            else -> ""
        },
        "",
    )
    if (row.working) {
        cell.appendChild(workingGlyph())
        cell.appendChild(document.createTextNode(" ${row.stateWord}"))
    } else {
        cell.textContent = row.stateWord
    }
    return cell
}

/**
 * The selected row's extra line: the detail's full text when the row cut it, then what the view
 * model put in [Row.extra] (where a search matched, a resume note), then the tab the session sits
 * in. The tab at the end is drawn dimmer than the text before it, and a failed resume or open in
 * the error colour, never dimmed. The parts are joined by " · ".
 *
 * Called by [buildRow] without the repeat, and by [fitSelectedFocus] with it once the row is known
 * to cut its detail.
 *
 * @param row the selected row.
 * @param repeatDetail whether to start the line with [Row.detail] (the row's own cell cut it short
 *   or hides it). A search match in that text keeps its underline.
 * @return the line, spanning every column after the marker; null when there is nothing to put on
 *   it, so the selected row never carries an empty line.
 * @see Row.tab
 * @see Row.error
 */
private fun buildFocus(row: Row, repeatDetail: Boolean): HTMLElement? {
    val focus = div("ao-focus")
    fun join(node: org.w3c.dom.Node) {
        if (focus.hasChildNodes()) focus.appendChild(document.createTextNode(" · "))
        focus.appendChild(node)
    }
    if (repeatDetail && row.detail.isNotBlank()) {
        join(
            if (row.matchInSnippet) highlighted(row.detail, row.matchStart, row.matchLength)
            else document.createTextNode(row.detail),
        )
    }
    val tab = row.tab
    // The tab note is the last part of the extra line; what it is joined to by " · " belongs to the dim part.
    val tabStart = if (tab.isNotEmpty() && row.extra.endsWith(tab)) row.extra.length - tab.length else -1
    val main = if (tabStart >= 0) row.extra.substring(0, tabStart).removeSuffix(" · ") else row.extra
    val error = row.error
    val at = if (error != null) main.indexOf(error) else -1
    if (main.isNotEmpty()) {
        val part = document.createDocumentFragment()
        if (error != null && at >= 0) {
            part.appendChild(document.createTextNode(main.substring(0, at)))
            part.appendChild(span("ao-n-error", error))
            part.appendChild(document.createTextNode(main.substring(at + error.length)))
        } else {
            part.appendChild(document.createTextNode(main))
        }
        join(part)
    }
    if (tabStart >= 0) focus.appendChild(span("ao-tab", if (focus.hasChildNodes()) " · $tab" else tab))
    return if (focus.hasChildNodes()) focus else null
}

/**
 * The dim "+ 6 more" line that ends EARLIER, after whichever of its two bands holds the last shown
 * row. It is not a row anyone can select; a click shows the rest.
 *
 * @param hidden how many EARLIER entries, across both bands, are still hidden.
 * @return the line, laid out on the same grid as a row.
 */
private fun buildMoreLine(hidden: Int): HTMLElement {
    val el = div("ao-row ao-more")
    el.appendChild(span("ao-m", ""))
    el.appendChild(span("ao-t", "+ $hidden more"))
    return el
}

/**
 * The popover under the selected row: a heading and the last lines of the session's screen, and,
 * for a session in a background tab, a line saying ⌘↵ takes the tab over.
 *
 * @param peek the peek's data from the view model.
 * @return the popover element.
 */
private fun buildPeek(peek: AgentsOverviewBackingViewModel.Peek): HTMLElement {
    val box = div("ao-peek")
    box.addEventListener("click", { e: Event -> e.stopPropagation() })
    box.appendChild(div("ao-peek-title").also {
        it.textContent = if (peek.live) "LIVE SCREEN · ${peek.title}".uppercase()
        else "SAVED SCROLLBACK · CLOSED ${formatAge(peek.closedSinceMillis ?: 0L)}".uppercase()
    })
    when {
        peek.loading -> box.appendChild(div("ao-peek-line").also { it.textContent = "…" })
        peek.emptyText != null -> box.appendChild(div("ao-peek-line").also { it.textContent = peek.emptyText })
        else -> peek.lines.forEach { line -> box.appendChild(div("ao-peek-line").also { it.textContent = line }) }
    }
    if (peek.background) {
        // The screen is read-only: the way to type into it is to take its tab over.
        box.appendChild(div("ao-peek-hint").also { it.textContent = "⌘↵ take over this tab to work in it" })
    }
    return box
}

/** The inline "Close “title”?" bar with its warnings; the focused button shows a ring. */
private fun buildConfirm(confirm: AgentsOverviewBackingViewModel.Confirm): HTMLElement {
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

/**
 * Compact age for a row: `now`, `5m`, `3h` or `2d`.
 *
 * @param sinceMillis epoch millis the age counts from: when the row entered its state, or the
 *   newest activity of a row in EARLIER.
 * @return the age text shown at the row's right.
 */
private fun formatAge(sinceMillis: Long): String {
    val seconds = ((kotlin.js.Date.now() - sinceMillis) / 1000).toLong().coerceAtLeast(0)
    return when {
        seconds < 60 -> "now"
        seconds < 3600 -> "${seconds / 60}m"
        seconds < 86_400 -> "${seconds / 3600}h"
        else -> "${seconds / 86_400}d"
    }
}

private fun div(className: String): HTMLElement =
    (document.createElement("div") as HTMLElement).also { it.className = className }

private fun span(className: String, text: String): HTMLElement =
    (document.createElement("span") as HTMLElement).also { it.className = className; it.textContent = text }
