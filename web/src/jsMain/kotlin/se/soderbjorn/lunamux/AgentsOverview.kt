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
 * Three alternative hierarchies of the same table can be switched on with `?ao=a`, `?ao=b` or
 * `?ao=c` (and ⌥0 to ⌥3 while the overlay is open); with none requested the drawing is the one
 * described above. They change only how the rows are drawn, never which rows there are or what a
 * key does: see [OverviewDirections].
 *
 * @see AgentsOverviewBackingViewModel
 * @see OverviewDirections
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
    OverviewDirections.load()
    chip.setAttribute("data-dir", OverviewDirections.current.key)
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
 * or "agents idle"), joined by a faint dot. The narrow-feed direction draws only a glyph and a
 * count for each ("◆ 2  ⠶ 1", or "○ idle"); the other directions differ from this only in the
 * stylesheet.
 *
 * @param state the view model's current state.
 */
private fun renderChip(state: AgentsOverviewBackingViewModel.State) {
    val chip = chipElement ?: return
    chip.style.display = if (state.chipVisible) "" else "none"
    chip.setAttribute("data-dir", OverviewDirections.current.key)
    chip.textContent = ""
    if (OverviewDirections.current == OverviewDirection.NARROW_FEED) {
        if (state.needYouCount > 0) chip.appendChild(span("ao-chip-need", "◆ ${state.needYouCount}"))
        if (state.workingCount > 0) chip.appendChild(span("ao-chip-work", "⠶ ${state.workingCount}"))
        if (state.needYouCount == 0 && state.workingCount == 0) chip.appendChild(span("ao-chip-rest", "○ idle"))
        return
    }
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
    panel.setAttribute("data-dir", OverviewDirections.current.key)
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
    OverviewDirections.forgetFolds()
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
 * (jumped to, resumed or opened), as ↵ would. In the directions that fold finished task members, a
 * click on the fold shows them; an item of the answer above the list acts like the row it names.
 *
 * @param target the element that was clicked, or null.
 */
private fun onListClick(target: HTMLElement?) {
    val el = target?.closest(".ao-row, .ao-card-item") as? HTMLElement ?: return
    if (el.classList.contains("ao-more")) { agentsOverview.showMoreEarlier(); return }
    el.getAttribute("data-fold")?.let { taskId ->
        OverviewDirections.openFold(taskId)
        lastState?.let { renderList(it) }
        return
    }
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
 * and esc steps back one level. While directions are switched on, ⌥0 to ⌥3 draw the overview in
 * today's way or in direction A, B or C.
 *
 * @param e the key press in the search box.
 */
private fun onSearchKey(e: KeyboardEvent) {
    val state = agentsOverview.state
    val input = searchInput
    when {
        OverviewDirections.switching && e.altKey && OverviewDirections.forCode(e.code) != null -> {
            e.preventDefault()
            applyDirection(OverviewDirections.forCode(e.code)!!)
        }
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
 * Draw the chip and, if it is open, the overlay in [direction] from now on, and remember the choice.
 *
 * Called by [onSearchKey] when ⌥0 to ⌥3 is pressed.
 *
 * @param direction the direction to switch to.
 * @see OverviewDirections.select
 */
private fun applyDirection(direction: OverviewDirection) {
    OverviewDirections.select(direction)
    overlayElement?.querySelector(".ao-panel")?.setAttribute("data-dir", direction.key)
    lastState?.let { renderChip(it); if (it.open) renderList(it) }
}

/**
 * Rebuild the list and the footer from [state]: search results under a "N MATCHES" heading while
 * searching, otherwise the "NOW" section and the two EARLIER bands, "LAST FEW DAYS" and
 * "LAST TWO WEEKS". The selected row is highlighted and
 * carries the peek; the close confirmation follows the row it asks about. A direction other than
 * today's adds the answer above the sections and may draw a run of finished task members as one
 * fold line.
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
    fun add(rows: List<Row>) {
        val items = OverviewDirections.plan(rows, state)
        items.forEachIndexed { i, item ->
            val sameTaskAfter = items.getOrNull(i + 1)?.let { it.taskId != null && it.taskId == item.taskId } == true
            if (item is ListItem.Fold) {
                list.appendChild(buildFoldRow(item, last = !sameTaskAfter))
                return@forEachIndexed
            }
            val row = (item as ListItem.One).row
            val selected = row.id == state.selectedId
            // A unit that starts right after a task gets the gap a header gets, so a lone session
            // never reads as one of the task's members.
            val gap = AgentsOverviewBackingViewModel.startsAfterTask(items.getOrNull(i - 1)?.last, row, state.tasks)
            list.appendChild(
                if (row.kind == Kind.TASK) {
                    buildRow(row, state, selected, now, kids = sameTaskAfter, last = false)
                } else {
                    buildRow(row, state, selected, now, kids = false, last = inPanel(row, state) && !sameTaskAfter, gap = gap)
                },
            )
            state.confirm?.takeIf { it.rowId == row.id }?.let { list.appendChild(buildConfirm(it)) }
        }
    }
    if (!state.matchMode) buildAnswer(state)?.let { list.appendChild(it) }
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
    if (OverviewDirections.switching) {
        foot.appendChild(span("ao-dir", "${OverviewDirections.current.tag}  ⌥0–3"))
    }
}

/**
 * Choose the footer's entries from the selection: the view model's key hints for it, drawn with
 * the classes that let "move" and "back" give way when the panel narrows.
 *
 * @param state the view model's current state.
 * @return the entries, in the order they are drawn.
 */
private fun footerSegments(state: AgentsOverviewBackingViewModel.State): List<FooterSegment> {
    val row = state.selectedRow
    val backToTask = row?.kind == Kind.SESSION && inPanel(row, state)
    return AgentsOverviewBackingViewModel.footerHints(state.footer, backToTask).map {
        FooterSegment(
            it.key, it.word,
            when (it.role) {
                AgentsOverviewBackingViewModel.HintRole.MOVE -> "ao-seg-move"
                AgentsOverviewBackingViewModel.HintRole.BACK -> "ao-seg-back"
                AgentsOverviewBackingViewModel.HintRole.OTHER -> ""
            },
        )
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
 * task member also carries its pull request after the status, so the "where" column keeps one meaning.
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
    // The pull request is detail text after the status; the "where" column always names a place.
    row.pullRequest?.let { cell.appendChild(span("ao-pr-inline", " · $it")) }
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
 * @param gap whether the row starts a unit right after a task (see
 *   [AgentsOverviewBackingViewModel.startsAfterTask]) and so gets a gap above it.
 * @return the row element, carrying the row's id as `data-id` for click routing.
 */
private fun buildRow(
    row: Row,
    state: AgentsOverviewBackingViewModel.State,
    selected: Boolean,
    now: Long,
    kids: Boolean,
    last: Boolean,
    gap: Boolean = false,
): HTMLElement {
    val isHead = row.kind == Kind.TASK
    val task = state.tasks[row.taskId].takeIf { isHead }
    val member = inPanel(row, state)
    val classes = mutableListOf("ao-row")
    if (isHead) classes += "ao-head"
    if (isHead && kids) classes += "ao-kids"
    if (member) classes += "ao-mem"
    if (last) classes += "ao-last"
    if (gap) classes += "ao-gap"
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

    val direction = OverviewDirections.current
    val title = span("ao-t", "")
    if (row.field == "title") title.appendChild(highlighted(row.title, row.matchStart, row.matchLength))
    else title.textContent = row.title
    if (!isHead && row.agent.isNotEmpty() && row.agent != "claude") title.appendChild(span("ao-agent", " ${row.agent}"))
    val where = if (isHead) task?.repo ?: "" else row.where ?: ""
    // In the answer-first direction the folder is not a column: it trails the title, dim.
    if (direction == OverviewDirection.ANSWER_FIRST && where.isNotEmpty()) title.appendChild(span("ao-where", "  $where"))
    el.appendChild(title)

    el.appendChild(span("ao-w", where))
    // Outside today's drawing a search hit is one line: the match context lives in the status cell.
    val oneLineMatch = direction != OverviewDirection.TODAY && !isHead && state.matchMode
    el.appendChild(
        when {
            isHead -> countsCell(task)
            oneLineMatch -> matchCell(row, now)
            else -> statusCell(row, now)
        },
    )
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

    if (!oneLineMatch && row.field != null && row.field != "title" && row.snippet != null) {
        val snip = div("ao-snippet")
        snip.appendChild(span("ao-snippet-label", fieldLabel(row.field) + "  "))
        snip.appendChild(highlighted(row.snippet!!, row.matchStart, row.matchLength))
        el.appendChild(snip)
    }
    // Every result that sits in a task says so; a match on the task's own title highlights it there.
    row.taskTitle?.takeIf { !oneLineMatch }?.let { taskTitle ->
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

/**
 * The status cell of a search hit drawn as one line: where the query matched ("first prompt",
 * "branch", "in task") and the matching text with the match underlined, then, for a session in a
 * task, the task's title. A hit with an error, or one that matched the title, keeps its ordinary
 * status, so an error is never replaced and the title is highlighted where it is.
 *
 * Called by [buildRow] for the directions that draw a hit as one line instead of adding
 * snippet lines under it.
 *
 * @param row the search hit.
 * @param now the current epoch millis, for the ordinary status.
 * @return the cell.
 * @see statusCell
 */
private fun matchCell(row: Row, now: Long): HTMLElement {
    val field = row.field
    val snippet = row.snippet
    val taskTitle = row.taskTitle
    if (row.error != null) return statusCell(row, now)
    if (field == "task" && taskTitle != null) {
        val cell = span("ao-n", "")
        cell.appendChild(span("ao-snippet-label", fieldLabel("task") + " "))
        cell.appendChild(highlighted(taskTitle, row.matchStart, row.matchLength))
        if (row.status.isNotEmpty()) {
            cell.appendChild(span("ao-n-faint", " · "))
            cell.appendChild(document.createTextNode(row.status))
        }
        return cell
    }
    val cell = if (field != null && field != "title" && snippet != null) {
        span("ao-n", "").also {
            it.appendChild(span("ao-snippet-label", fieldLabel(field) + "  "))
            it.appendChild(highlighted(snippet, row.matchStart, row.matchLength))
        }
    } else {
        statusCell(row, now)
    }
    if (taskTitle != null) cell.appendChild(span("ao-n-faint", " · ${fieldLabel("task")} $taskTitle"))
    return cell
}

/**
 * The one line that stands for a run of finished task members: "✓ 8 done" and their pull
 * requests. It hangs on the task's tree lines like the rows it hides; a click shows them.
 *
 * Called by [renderList] for a [ListItem.Fold].
 *
 * @param fold the folded run.
 * @param last whether it is the last unit of its task on screen, which ends the tree line.
 * @return the row, carrying the task's id as `data-fold` for [onListClick].
 */
private fun buildFoldRow(fold: ListItem.Fold, last: Boolean): HTMLElement {
    val el = div("ao-row ao-fold ao-mem" + if (last) " ao-last" else "")
    el.setAttribute("data-fold", fold.taskId ?: "")
    el.appendChild(span("ao-gut", ""))
    el.appendChild(span("ao-s ao-s-fold", "✓"))
    el.appendChild(span("ao-t", "${fold.rows.size} done"))
    el.appendChild(span("ao-n", fold.rows.mapNotNull { it.pullRequest }.joinToString("  ")))
    return el
}

/**
 * What the overview says at a glance, drawn above the list in the directions that have an answer:
 * a plain count line in the quiet table, a card of the sessions that need the person in the
 * answer-first direction, one pinned line naming the first of them in the narrow feed. Nothing in
 * today's drawing. It reads the same counts as the topbar chip, so the two agree.
 *
 * Called by [renderList] before the sections, except while searching.
 *
 * @param state the view model's current state.
 * @return the element, or null when the direction has none.
 * @see OverviewDirections.answer
 */
private fun buildAnswer(state: AgentsOverviewBackingViewModel.State): HTMLElement? {
    val answer = OverviewDirections.answer(state)
    return when (OverviewDirections.current) {
        OverviewDirection.TODAY -> null
        OverviewDirection.QUIET_TABLE -> div("ao-answer").also { it.appendChild(answerCounts(answer, withNeeds = true)) }
        OverviewDirection.ANSWER_FIRST -> buildAnswerCard(answer, state)
        OverviewDirection.NARROW_FEED -> buildAnswerLine(answer, state)
    }
}

/**
 * The counts of [answer] as one line: "1 needs you · 2 working", or "Nothing needs you" in place of
 * the first part.
 *
 * @param answer what the overview says.
 * @param withNeeds whether to start with the needs-you part; a card that already lists those
 *   sessions leaves it out.
 * @return the line.
 */
private fun answerCounts(answer: Answer, withNeeds: Boolean): HTMLElement {
    val line = span("ao-answer-counts", "")
    val parts = mutableListOf<HTMLElement>()
    if (withNeeds) {
        parts += if (answer.needCount > 0) span("ao-cn", AgentsOverviewBackingViewModel.needYouText(answer.needCount))
        else span("ao-answer-calm", "Nothing needs you")
    }
    if (answer.workingCount > 0) parts += span("", "${answer.workingCount} working")
    else if (answer.needCount == 0) parts += span("", "nothing working")
    parts.forEachIndexed { i, part ->
        if (i > 0) line.appendChild(span("ao-n-faint", " · "))
        line.appendChild(part)
    }
    return line
}

/**
 * One session that needs the person, as an item of the answer: the diamond, its title, its
 * question, and its task and age. A click acts on the session as a click on its row does.
 *
 * @param row the session's row.
 * @param state the view model's state, for the title of the task it belongs to.
 * @return the item, carrying the row's id as `data-id`.
 */
private fun buildAnswerItem(row: Row, state: AgentsOverviewBackingViewModel.State): HTMLElement {
    val item = div("ao-card-item")
    item.setAttribute("data-id", row.id)
    item.appendChild(span("ao-card-g", "◆"))
    val body = div("ao-card-body")
    body.appendChild(div("ao-card-title").also { it.textContent = row.title })
    if (row.status.isNotEmpty()) body.appendChild(div("ao-card-status").also { it.textContent = row.status })
    item.appendChild(body)
    val place = state.tasks[row.taskId]?.title ?: row.where ?: ""
    val age = if (row.sinceMillis > 0) formatAge(row.sinceMillis) else ""
    item.appendChild(span("ao-card-meta", listOf(place, age).filter { it.isNotEmpty() }.joinToString(" · ")))
    return item
}

/**
 * The answer-first card: up to three sessions that need the person, large, then the rest of the
 * counts; "Nothing needs you" in a calm line when there is none.
 *
 * @param answer what the overview says.
 * @param state the view model's current state.
 * @return the card.
 */
private fun buildAnswerCard(answer: Answer, state: AgentsOverviewBackingViewModel.State): HTMLElement {
    val card = div("ao-card")
    if (answer.needs.isEmpty()) {
        card.classList.add("ao-card-calm")
        card.appendChild(answerCounts(answer, withNeeds = true))
        return card
    }
    answer.needs.take(CARD_ITEMS).forEach { card.appendChild(buildAnswerItem(it, state)) }
    val hidden = answer.needs.size - CARD_ITEMS
    val foot = div("ao-card-foot")
    if (hidden > 0) foot.appendChild(span("ao-n-faint", "+ $hidden more need you, below  ·  "))
    foot.appendChild(answerCounts(answer, withNeeds = false))
    card.appendChild(foot)
    return card
}

/**
 * The narrow feed's pinned answer: the first session that needs the person with its question, and
 * how many more do; "Nothing needs you" with the working count when there is none. It stays at
 * the top of the list while the list scrolls.
 *
 * @param answer what the overview says.
 * @param state the view model's current state.
 * @return the line.
 */
private fun buildAnswerLine(answer: Answer, state: AgentsOverviewBackingViewModel.State): HTMLElement {
    val line = div("ao-answer ao-answer-c")
    val first = answer.needs.firstOrNull()
    if (first == null) {
        line.appendChild(answerCounts(answer, withNeeds = true))
        return line
    }
    line.appendChild(buildAnswerItem(first, state))
    if (answer.needs.size > 1) line.appendChild(span("ao-card-more", "+ ${answer.needs.size - 1} more"))
    return line
}

/** How many sessions the answer-first card lists before it points at the table. */
private const val CARD_ITEMS = 3

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
 * @param sinceMillis epoch millis the age counts from: when the row entered its state, an idle or
 *   ended row's last sign of life, or the newest activity a task header summarises.
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
