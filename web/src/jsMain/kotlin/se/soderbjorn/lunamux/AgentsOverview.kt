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
 * green when it is done. The selected row carries more under it: the status in full when (and only when)
 * the row cut it short or hides it in a narrow panel, any text the model put on the row (where a
 * search matched, a failed resume), then chips: the working folder of the session ↵ goes to and,
 * when it tells something the title does not, the tab that session is in. ↵ opens the row (a
 * session in a background tab is watched, and one that is not running is resumed only after a
 * question under the row, answered by a second ↵), ⌘↵ takes a background tab over, → peeks at the
 * session ↵ would open, and ⌘⌫ closes a live one after an inline confirmation. Typing two or more
 * characters swaps the lists for search results over every task ever run, one row per task.
 * EARLIER shows its newest five rows and a "+ N more" line for the rest. The footer's keys come
 * from the model, so they follow the selection and the question.
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
    AgentsOverviewBackingViewModel(lunamuxClient.windowState, watch = watchViewModel::watch) { windowSocket.send(it) }
}

private var chipElement: HTMLElement? = null
private var overlayElement: HTMLElement? = null
private var searchInput: HTMLInputElement? = null
private var listElement: HTMLElement? = null
private var footerElement: HTMLElement? = null
private var footerShown: String? = null
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
        renderFooter(state)
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

    panel.appendChild(line)
    panel.appendChild(list)
    panel.appendChild(foot)
    backdrop.appendChild(panel)
    document.body?.appendChild(backdrop)
    overlayElement = backdrop
    searchInput = input
    listElement = list
    footerElement = foot
    footerShown = null
    kotlinx.browser.window.addEventListener("resize", resizeListener)
    input.focus()
}

/**
 * Draw the footer's keys from [state]: the model says which keys apply (the default three, or
 * "↵ resume · esc cancel" while a question is asked). It is rebuilt only when the keys changed.
 *
 * @param state the view model's current state.
 */
private fun renderFooter(state: AgentsOverviewBackingViewModel.State) {
    val foot = footerElement ?: return
    val shown = state.footer.joinToString("|") { "${it.key} ${it.label}" }
    if (shown == footerShown) return
    footerShown = shown
    foot.textContent = ""
    state.footer.forEach { key ->
        val seg = span("ao-seg", "")
        seg.setAttribute("data-key", key.key)
        seg.appendChild(span("ao-k", key.key))
        seg.appendChild(document.createTextNode(key.label))
        foot.appendChild(seg)
    }
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
    footerElement = null
    footerShown = null
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
 * ↑ and ↓ move the selection (which also cancels a question, a peek or a confirmation). → peeks at the session
 * ↵ would open when the caret is at the end of the text, and ← goes back out of the peek. ⌘⌫ asks
 * to close the selected session (only while the box is empty and the row is one live session),
 * ⌘↵ takes the selected session's background tab over (in the list and in its peek), and esc steps
 * back one level: the question the first ↵ asks, then a close confirmation, then the peek, then
 * the overlay. Space types into the search.
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
 * Tell the view model whether the selected row cut its detail short, so it can set
 * [Row.statusFull] and the row repeats the status in full under itself, and stop when it no longer
 * does (a wider panel).
 *
 * The row is drawn first and measured once it is in the page. The model keeps the answer, so an
 * answer that has not changed changes nothing and the draw settles after one more pass.
 *
 * Called at the end of [renderList] and from the window's resize event.
 *
 * @see detailIsCut
 * @see AgentsOverviewBackingViewModel.setDetailCut
 */
private fun fitSelectedFocus() {
    val list = listElement ?: return
    val el = list.querySelector(".ao-sel") as? HTMLElement ?: return
    if (list.clientWidth == 0) return
    val id = el.getAttribute("data-id") ?: return
    agentsOverview.setDetailCut(id, detailIsCut(el))
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
 * more under it, spanning the columns after the marker (see [buildFocus]); the question the first
 * ↵ asks takes the place of the text there. An open peek follows.
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

    if (selected) buildFocus(row, state.ask?.takeIf { it.rowId == row.id })?.let { el.appendChild(it) }
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
 * What the selected row shows under its columns: the status in full when the row cut it, then the
 * chips ([Row.chips]): the folder, and the tab when it tells something, then any text the model put
 * in [Row.extra] (where a search matched, a failed resume or open in the error colour).
 *
 * While a question is asked ([ask]) the bar with the question stands in place of the status and the
 * text, and the chips stay under it. Called by [buildRow] for the selected row.
 *
 * @param row the selected row.
 * @param ask the question asked about this row, or null.
 * @return the block, spanning every column after the marker; null when there is nothing to put on
 *   it, so the selected row never carries an empty line.
 * @see Row.statusFull
 * @see Row.chips
 * @see Row.error
 */
private fun buildFocus(row: Row, ask: AgentsOverviewBackingViewModel.Ask?): HTMLElement? {
    val focus = div("ao-focus")
    val full = row.statusFull
    if (ask != null) {
        focus.appendChild(buildAsk(ask))
    } else if (full != null) {
        focus.appendChild(
            div("ao-full").also {
                if (row.matchInSnippet) it.appendChild(highlighted(full, row.matchStart, row.matchLength))
                else it.textContent = full
            },
        )
    }
    if (row.chips.isNotEmpty()) {
        val chips = div("ao-chips")
        // While a question is up only its own keys answer it: a click on a chip is not an answer.
        if (ask != null) chips.addEventListener("click", { e: Event -> e.stopPropagation() })
        row.chips.forEach { chip ->
            val kind = if (chip.kind == AgentsOverviewBackingViewModel.ChipKind.TAB) "ao-pill-tab" else "ao-pill-dir"
            chips.appendChild(span("ao-pill $kind", chip.text))
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

/**
 * The question bar under a row: a dim tag naming what is missing, the question, and the keys that
 * answer it. Clicking a key does what the key does; a click anywhere else in the bar does nothing,
 * so it is never taken for an answer.
 *
 * @param ask the question from the view model.
 * @return the bar.
 * @see AgentsOverviewBackingViewModel.Ask
 */
private fun buildAsk(ask: AgentsOverviewBackingViewModel.Ask): HTMLElement {
    val bar = div("ao-ask")
    bar.addEventListener("click", { e: Event ->
        e.stopPropagation()
        val key = ((e.target as? HTMLElement)?.closest(".ao-ask-key") as? HTMLElement)?.getAttribute("data-key")
        if (!key.isNullOrEmpty()) onFooterKey(key)
    })
    val q = span("ao-ask-q", "")
    q.appendChild(span("ao-ask-tag", ask.tag))
    q.appendChild(document.createTextNode(ask.question))
    bar.appendChild(q)
    val keys = span("ao-ask-keys", "")
    ask.keys.forEach { k ->
        val key = span("ao-ask-key", "")
        key.setAttribute("data-key", k.key)
        key.appendChild(span("ao-kbd", k.key))
        key.appendChild(document.createTextNode(k.label))
        keys.appendChild(key)
    }
    bar.appendChild(keys)
    return bar
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
        // The screen is read-only: the way to type into it is to show its tab.
        box.appendChild(div("ao-peek-hint").also { it.textContent = "⌘↵ show as tab" })
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
