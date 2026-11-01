/**
 * The agents overview in the web client: a topbar chip that says how many agent sessions need the
 * user and how many work, and the ⌘⇧A overlay that lists every agentic-CLI session (Claude Code,
 * Codex, Gemini) as one terminal-style table: "Now", then what finished or went quiet under
 * "Last few days" and "Last two weeks" (a heading with nothing under it is not drawn).
 *
 * Every row is the same seven-column grid: a gutter (the task chevron or its tree lines), the
 * state glyph (the only place a state is drawn as a glyph), the title, where it runs, a status
 * line, the age and the row's own action. A task with several sessions is an expansion panel in
 * the same grid: a bold header whose click, →, ← and space fold it, whose action ("jump" to the
 * session that most needs the person, else "open" the task's own session) is its ↵, and whose
 * sessions hang under it on tree lines; a task with one session is that session's plain row. Typing two or more characters
 * swaps the lists for search results over every session ever run; → peeks at a session's screen
 * and ⌘⌫ closes a live one after an inline confirmation. The footer names the keys that work on
 * the selected row.
 *
 * All state and behaviour live in the shared [AgentsOverviewBackingViewModel]; this file only
 * draws its state as plain DOM and turns key presses and clicks into calls on it. Rows are found
 * again by their id, never by their position, so a redraw while the list changes under the
 * pointer cannot move a click onto another row. The chip and the overlay read the same state, so
 * their counts always agree.
 *
 * @see AgentsOverviewBackingViewModel
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
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Footer
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Glyph
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.RowKind as Kind
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
private var footElement: HTMLElement? = null
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
 * One entry of the footer: a key and what it does. An entry without a key is a plain remark.
 *
 * @property key the key as drawn, such as `→` or `⌘⌫`, or empty for a remark.
 * @property word what the key does, or the remark itself.
 * @property cls an extra class: how the entry gives way when the panel narrows.
 */
private data class FooterSegment(val key: String, val word: String, val cls: String = "")

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
    keepClockTicking(state.open)
}

/**
 * Start or stop the timer that redraws the open list every [CLOCK_TICK_MS], keeping the scroll
 * position. Nothing else changes the picture while the server is silent, and a hung agent is
 * exactly the case where it must change.
 *
 * @param needed whether the overlay is open.
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
    input.placeholder = "search every session, closed ones too"
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
    footElement = foot
    input.focus()
}

/** Remove the overlay from the page and forget every element that belonged to it. */
private fun closeOverlayElement() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = null
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    footElement = null
    shownRows = emptyMap()
}

/**
 * Turn a click in the list into a view-model call, finding the row by its id.
 *
 * A task header folds on a click anywhere except its action, which does what ↵ does on the header
 * (jump to the session that most needs the person, else run the task's `open` action, else fold),
 * and its `open` control, which runs the task's `open` action even while a session is live;
 * the "+ N more" line shows the rest of the last two weeks; any other row is selected and done
 * (jumped to, resumed or opened), as ↵ would.
 *
 * @param target the element that was clicked, or null.
 */
private fun onListClick(target: HTMLElement?) {
    val el = target?.closest(".ao-row") as? HTMLElement ?: return
    if (el.classList.contains("ao-more")) { agentsOverview.showMoreEarlier(); return }
    val row = el.getAttribute("data-id")?.let { shownRows[it] } ?: return
    agentsOverview.select(row.id)
    when {
        row.kind == Kind.TASK && target.closest(".ao-open") != null -> launchAgentsOverview { agentsOverview.openTask(row) }
        row.kind == Kind.TASK && target.closest(".ao-x") == null -> row.taskId?.let { agentsOverview.toggleTask(it) }
        else -> launchAgentsOverview { agentsOverview.activate(row) }
    }
}

/**
 * Do what a footer segment's key does, when the segment is clicked.
 *
 * @param key the segment's key as drawn (`→`, `←`, `space`, `⌘⌫` or `esc`); the others do nothing.
 */
private fun onFooterKey(key: String) {
    when (key) {
        "→" -> launchAgentsOverview { agentsOverview.stepIn() }
        "←" -> agentsOverview.stepOut()
        "space" -> launchAgentsOverview { agentsOverview.toggleSelected() }
        "⌘⌫" -> launchAgentsOverview { agentsOverview.requestClose() }
        "esc" -> agentsOverview.back()
    }
}

/**
 * Turn a key press in the search box into a view-model call.
 *
 * ↑ and ↓ move the selection (which also closes a peek or a confirmation). → steps in when the
 * caret is at the end of the text: it unfolds a task, enters it, or peeks at a session. ←
 * steps out while the box is empty or a peek is open, and space folds or unfolds a task
 * while the box is empty. ⌘⌫ asks to close the selected session (only while the box is empty),
 * and esc steps back one level.
 *
 * @param e the key press in the search box.
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
            launchAgentsOverview { agentsOverview.stepIn() }
        }
        e.key == "ArrowLeft" && (state.peek != null || state.query.isEmpty()) -> {
            e.preventDefault()
            agentsOverview.stepOut()
        }
        e.key == " " && state.query.isEmpty() -> {
            e.preventDefault()
            launchAgentsOverview { agentsOverview.toggleSelected() }
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
 * Rebuild the list and the footer from [state]: search results under a "N MATCHES" heading while
 * searching, otherwise the "NOW" section and the two EARLIER bands, "LAST FEW DAYS" and
 * "LAST TWO WEEKS". The selected row is highlighted and
 * carries the peek; the close confirmation follows the row it asks about.
 *
 * @param state the view model's current state.
 */
private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    list.textContent = ""
    shownRows = state.rows.associateBy { it.id }
    renderFooter(state)
    if (state.rows.isEmpty()) {
        list.appendChild(div("ao-empty").also { it.textContent = state.emptyText })
        return
    }
    val now = kotlin.js.Date.now().toLong()
    fun add(rows: List<Row>) = rows.forEachIndexed { i, row ->
        val selected = row.id == state.selectedId
        val sameTaskAfter = rows.getOrNull(i + 1)?.let { it.taskId != null && it.taskId == row.taskId } == true
        list.appendChild(
            if (row.kind == Kind.TASK) {
                buildRow(row, state, selected, now, kids = sameTaskAfter, last = false)
            } else {
                buildRow(row, state, selected, now, kids = false, last = inPanel(row, state) && !sameTaskAfter)
            },
        )
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
            list.appendChild(div("ao-sec").also { it.textContent = "LAST FEW DAYS" })
            add(state.recent)
        }
        if (state.fortnight.isNotEmpty()) {
            list.appendChild(div("ao-sec").also { it.textContent = "LAST TWO WEEKS" })
            add(state.fortnight)
            if (state.earlierHidden > 0) list.appendChild(buildMoreLine(state.earlierHidden))
        }
    }
    (list.querySelector(".ao-sel") as? HTMLElement)?.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
}

/**
 * The keys that work on the selected row, drawn under the list: each key in the text colour and
 * its words dim, as clickable segments.
 *
 * @param state the view model's current state; its footer says which set of keys applies.
 */
private fun renderFooter(state: AgentsOverviewBackingViewModel.State) {
    val foot = footElement ?: return
    foot.textContent = ""
    footerSegments(state).forEach { seg ->
        val el = span("ao-seg ${seg.cls}".trim(), "")
        el.setAttribute("data-key", seg.key)
        if (seg.key.isNotEmpty()) el.appendChild(span("ao-k", seg.key))
        el.appendChild(document.createTextNode(seg.word))
        foot.appendChild(el)
    }
}

/**
 * Choose the footer's entries from the selection: only keys that do something on that row, with
 * "← back to the task" added for a session inside a task panel, and "↑↓ move" and "esc close" always last.
 *
 * @param state the view model's current state.
 * @return the entries, in the order they are drawn.
 */
private fun footerSegments(state: AgentsOverviewBackingViewModel.State): List<FooterSegment> {
    val row = state.selectedRow
    val move = FooterSegment("↑↓", "move", "ao-seg-move")
    val esc = FooterSegment("esc", "close")
    val back = FooterSegment("←", "back to the task", "ao-seg-back").takeIf { row?.kind == Kind.SESSION && inPanel(row, state) }
    return when (state.footer) {
        Footer.LIVE -> listOfNotNull(FooterSegment("→", "peek"), FooterSegment("⌘⌫", "close session"), back, move, esc)
        Footer.CLOSED -> listOfNotNull(FooterSegment("→", "peek at its saved screen"), back, move, esc)
        Footer.TASK_COLLAPSED -> listOf(FooterSegment("→", "expand"), FooterSegment("space", "expand"), move, esc)
        Footer.TASK_EXPANDED -> listOf(FooterSegment("←", "collapse"), FooterSegment("space", "collapse"), move, esc)
        Footer.RUN_ATTACH -> listOfNotNull(back, move, esc)
        Footer.NO_SESSION -> listOfNotNull(FooterSegment("", "nothing to open: this session is not in a Lunamux pane"), back, move, esc)
        Footer.NONE -> listOf(move, esc)
    }
}

/** Where a search matched, in words, for the line under a result. */
private fun fieldLabel(field: String?): String = when (field) {
    "task" -> "in task"
    "prompt" -> "first prompt"
    "branch" -> "branch"
    "links" -> "links"
    "transcript" -> "in transcript"
    "screen" -> "on its last screen"
    else -> ""
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
 * The state glyph of a row, drawn in the second column: ◆ for a session that needs the user, the
 * braille working glyph (moving while the work is live, still and dim once it is not), ○, ‖, ✓
 * or ·; nothing for a header whose task has no working session.
 *
 * @param row the row.
 * @param task the row's task header data when [row] is a header, for its working glyph.
 * @param now the current epoch millis, for the working rule.
 * @return the cell's content, or null for an empty cell.
 */
private fun stateGlyph(row: Row, task: AgentsOverviewBackingViewModel.Task?, now: Long): HTMLElement? = when {
    row.kind == Kind.TASK ->
        task?.takeIf { it.workingCount > 0 }
            ?.let { workingGlyph(AgentsOverviewBackingViewModel.isWorkingLive(it.activityMillis, now)) }
    else -> when (row.glyph) {
        Glyph.NEEDS_YOU -> span("ao-g-need", "◆")
        Glyph.WORKING -> workingGlyph(AgentsOverviewBackingViewModel.isWorkingLive(row.activityMillis, now))
        Glyph.WAITING -> span("ao-g-plain", "○")
        Glyph.PAUSED -> span("ao-g-plain", "‖")
        Glyph.DONE -> span("ao-g-plain", "✓")
        Glyph.CLOSED -> span("ao-g-plain", "·")
        Glyph.NONE -> null
    }
}

/**
 * The status cell of a session: a prefix when the work has gone quiet or its file stale,
 * the file's label, then the line; an error replaces all of it and is never dimmed. A finished
 * session also carries its pull request inline, which only shows once the "where" column is dropped.
 *
 * @param row the row.
 * @param now the current epoch millis.
 * @return the cell.
 */
private fun statusCell(row: Row, now: Long): HTMLElement {
    val cell = span("ao-n", "")
    val error = row.error
    if (error != null) {
        cell.classList.add("ao-n-error")
        cell.textContent = error
        return cell
    }
    val prefix = AgentsOverviewBackingViewModel.agePrefix(row, now)
    if (prefix.isNotEmpty()) cell.appendChild(span("ao-n-faint", prefix))
    val label = row.label
    if (label != null) {
        cell.appendChild(document.createTextNode(label))
        if (row.status.isNotEmpty()) cell.appendChild(span("ao-n-faint", " · "))
    }
    cell.appendChild(document.createTextNode(row.status))
    val pr = row.where?.takeIf { row.glyph == Glyph.DONE && it.startsWith("#") }
    if (pr != null) cell.appendChild(span("ao-pr-inline", " · $pr"))
    return cell
}

/**
 * The count line of a task header: "1 needs you" in the warning colour, then the dim counts,
 * joined by faint dots.
 *
 * @param task the header's data, or null if it vanished since the row was built.
 * @return the cell.
 */
private fun countsCell(task: AgentsOverviewBackingViewModel.Task?): HTMLElement {
    val cell = span("ao-n", "")
    if (task == null) return cell
    val parts = task.summary.split(" · ").filter { it.isNotEmpty() }
    if (task.needYouCount > 0) cell.appendChild(span("ao-cn", AgentsOverviewBackingViewModel.needYouText(task.needYouCount)))
    parts.forEachIndexed { i, part ->
        if (i > 0 || task.needYouCount > 0) cell.appendChild(span("ao-n-faint", " · "))
        cell.appendChild(document.createTextNode(part))
    }
    return cell
}

/**
 * Whether [row] hangs under a task header on screen: it belongs to a task that is drawn as a
 * panel. A task's only session is a plain row and is not.
 *
 * Called by [buildRow], [renderList] and [footerSegments] to choose tree lines and the "back" key.
 *
 * @param row the row.
 * @param state the view model's state, whose [AgentsOverviewBackingViewModel.State.tasks] lists the panels.
 * @return true for a session inside a panel.
 */
private fun inPanel(row: Row, state: AgentsOverviewBackingViewModel.State): Boolean =
    row.kind == Kind.SESSION && row.taskId != null && state.tasks.containsKey(row.taskId)

/**
 * One row of the table, session or task header, in the seven-column grid.
 *
 * The row's colour comes from its state alone: needs-you status in the brightest text, working
 * status mid-bright, the rest dim. The selected row shows its action as "↵ jump" and the like at
 * the right end; a header shows where its ↵ goes ("jump" or "open"), or "↵ expand" / "↵ collapse"
 * when it only folds, and, when its task has an `open` action and ↵ jumps, an `open` control of
 * its own in the age column. Search snippets, the "in task X" line, the resume note, an open peek and
 * a failed open's error span the row's full width underneath.
 *
 * @param row the row to draw.
 * @param state the view model's state, for the header data and the open peek.
 * @param selected whether it is the highlighted row.
 * @param now the current epoch millis.
 * @param kids for a header, whether sessions are drawn under it (so a tree line runs down from it).
 * @param last for a session in a panel, whether it is the last one of its task on screen.
 * @return the row element, carrying the row's id as `data-id` for click routing.
 */
private fun buildRow(
    row: Row,
    state: AgentsOverviewBackingViewModel.State,
    selected: Boolean,
    now: Long,
    kids: Boolean,
    last: Boolean,
): HTMLElement {
    val isHead = row.kind == Kind.TASK
    val task = state.tasks[row.taskId].takeIf { isHead }
    val member = inPanel(row, state)
    val classes = mutableListOf("ao-row")
    if (isHead) classes += "ao-head"
    if (isHead && kids) classes += "ao-kids"
    if (member) classes += "ao-mem"
    if (last) classes += "ao-last"
    if (selected) classes += "ao-sel"
    if (!isHead && row.glyph == Glyph.NEEDS_YOU) classes += "ao-need"
    if (!isHead && row.glyph == Glyph.WORKING) classes += "ao-working"
    if (!isHead && (row.glyph == Glyph.CLOSED || (!row.isLive && !member))) classes += "ao-past"
    if (row.fileRefreshedMillis?.let { AgentsOverviewBackingViewModel.isStale(it, now) } == true) classes += "ao-stale"
    val el = div(classes.joinToString(" "))
    el.setAttribute("data-id", row.id)

    el.appendChild(span("ao-gut", if (isHead) (if (task?.expanded == true) "▾" else "▸") else ""))

    val glyphCell = span("ao-s", "")
    stateGlyph(row, task, now)?.let { glyphCell.appendChild(it) }
    el.appendChild(glyphCell)

    val title = span("ao-t", "")
    if (row.field == "title") title.appendChild(highlighted(row.title, row.matchStart, row.matchLength))
    else title.textContent = row.title
    if (!isHead && row.agent.isNotEmpty() && row.agent != "claude") title.appendChild(span("ao-agent", " ${row.agent}"))
    el.appendChild(title)

    el.appendChild(span("ao-w", if (isHead) task?.repo ?: "" else row.where ?: ""))
    el.appendChild(if (isHead) countsCell(task) else statusCell(row, now))
    // A header in NOW has no age; one in EARLIER shows how long ago its newest session was active.
    // When ↵ on a header jumps to a live session but its task also has an `open` action, that
    // column holds the `open` control so the action stays reachable.
    if (isHead && task?.hasOpen == true && row.actionLabel == "jump") {
        el.appendChild(span("ao-a ao-open", "open"))
    } else {
        val age = if (isHead) task?.ageMillis else row.sinceMillis
        el.appendChild(span("ao-a", if (age == null || age <= 0) "" else formatAge(age)))
    }
    el.appendChild(
        span(
            "ao-x",
            when {
                isHead && row.actionLabel.isEmpty() ->
                    if (selected) "↵ ${if (task?.expanded == true) "collapse" else "expand"}" else ""
                isHead -> if (selected) "↵ ${row.actionLabel}" else row.actionLabel
                selected && row.actionLabel.isNotEmpty() -> "↵ ${row.actionLabel}"
                else -> ""
            },
        ),
    )

    if (row.field != null && row.field != "title" && row.snippet != null) {
        val snip = div("ao-snippet")
        snip.appendChild(span("ao-snippet-label", fieldLabel(row.field) + "  "))
        snip.appendChild(highlighted(row.snippet!!, row.matchStart, row.matchLength))
        el.appendChild(snip)
    }
    // Every result that sits in a task says so; a match on the task's own title highlights it there.
    row.taskTitle?.let { taskTitle ->
        val line = div("ao-snippet ao-task-line")
        line.appendChild(span("ao-snippet-label", "${fieldLabel("task")} "))
        if (row.field == "task") line.appendChild(highlighted(taskTitle, row.matchStart, row.matchLength))
        else line.appendChild(document.createTextNode(taskTitle))
        el.appendChild(line)
    }
    row.resumeNote?.let { el.appendChild(div("ao-note").also { n -> n.textContent = it }) }
    state.peek?.takeIf { selected }?.let { el.appendChild(buildPeek(it)) }
    return el
}

/**
 * The dim "+ 6 more" line that ends the last two weeks. It is not a row anyone can select; a click
 * shows the rest.
 *
 * @param hidden how many entries of that band are still hidden.
 * @return the line, laid out on the same grid as a row.
 */
private fun buildMoreLine(hidden: Int): HTMLElement {
    val el = div("ao-row ao-more")
    el.appendChild(span("ao-gut", ""))
    el.appendChild(span("ao-s", ""))
    el.appendChild(span("ao-t", "+ $hidden more"))
    return el
}

/** The popover under the selected row: a heading and the last lines of the session's screen. */
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
 * @param sinceMillis epoch millis the row entered its state.
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
