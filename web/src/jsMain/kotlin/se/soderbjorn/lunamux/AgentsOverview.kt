/**
 * The agents overview in the web client: a topbar chip that counts the agent
 * sessions needing the user and the ones at work, and the ⌘⇧A overlay that
 * lists every agentic-CLI session (Claude Code, Codex, Gemini) under "Now"
 * and "Earlier". Typing two or more characters swaps the lists for search
 * results over every session ever run; → peeks at a session's screen and
 * × / ⌘⌫ close a live one after an inline confirmation. A programme group
 * declared by a tool is drawn as one box: a one-line header, the members that need the
 * user, and a quiet "N more" line that expands it. The footer says what ↵ does on the
 * selected row, which for a group header is to open the group's lead session.
 *
 * All state and behaviour live in the shared
 * [AgentsOverviewBackingViewModel]; this file only draws its state as plain
 * DOM and turns key presses and clicks into calls on it. The chip and the
 * overlay read the same state, so their counts always agree.
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
private var hintElement: HTMLElement? = null
private var shownRows: List<Row> = emptyList()
private var searchTimer: Int? = null
private var peekTimer: Int? = null
private var clockTimer: Int? = null
private var lastState: AgentsOverviewBackingViewModel.State? = null

/** The footer's key legend, shown unless a group header is selected. */
private const val DEFAULT_HINT =
    "type to search · ↑↓ select · ↵ jump or resume · → peek at its screen · ⌘⌫ close session · esc close"

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
 * Build the topbar chip ("◆ 3 need you │ ◌ 3") that opens the overview. It is
 * hidden while nothing needs the user and nothing is working, and starts
 * following the view model's state as soon as it is built.
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

private fun renderChip(state: AgentsOverviewBackingViewModel.State) {
    val chip = chipElement ?: return
    chip.style.display = if (state.chipVisible) "" else "none"
    chip.textContent = ""
    if (state.needYouCount > 0) {
        chip.appendChild(span("ao-chip-need", "◆ ${state.needYouCount} need you"))
    }
    if (state.needYouCount > 0 && state.workingCount > 0) chip.appendChild(span("ao-chip-sep", "│"))
    if (state.workingCount > 0) chip.appendChild(span("ao-chip-work", "◌ ${state.workingCount}"))
}

/** Create the backdrop, search box and list the first time the overlay opens. */
private fun ensureOverlay(query: String) {
    if (overlayElement != null) return
    val backdrop = div("ao-backdrop")
    // Keep keyboard focus in the search box when the pointer lands elsewhere.
    backdrop.addEventListener("mousedown", { e: Event ->
        if (e.target === backdrop) agentsOverview.close()
        if (e.target != searchInput) e.preventDefault()
    })

    val panel = div("ao-panel")
    val input = document.createElement("input") as HTMLInputElement
    input.type = "text"
    input.className = "ao-search"
    input.placeholder = "Search all sessions"
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

    val list = div("ao-list")
    list.addEventListener("click", { e: Event ->
        val index = ((e.target as? HTMLElement)?.closest(".ao-row") as? HTMLElement)
            ?.getAttribute("data-index")?.toIntOrNull()
        shownRows.getOrNull(index ?: -1)?.let { launchAgentsOverview { agentsOverview.activate(it) } }
    })

    panel.appendChild(input)
    panel.appendChild(list)
    val hint = div("ao-hint")
    hint.textContent = DEFAULT_HINT
    panel.appendChild(hint)
    backdrop.appendChild(panel)
    document.body?.appendChild(backdrop)
    overlayElement = backdrop
    searchInput = input
    listElement = list
    hintElement = hint
    input.focus()
}

private fun closeOverlayElement() {
    searchTimer?.let { kotlinx.browser.window.clearTimeout(it) }
    searchTimer = null
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    hintElement = null
    shownRows = emptyList()
}

/**
 * Turn a key press in the search box into a view-model call.
 *
 * ↑ and ↓ move the selection (which also closes a peek or a confirmation), → opens the peek
 * when the caret is at the end of the text, ← closes it, ⌘⌫ asks to close the selected session
 * (only while the search box is empty), and esc steps back one level.
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
        e.key == "ArrowLeft" && state.query.isEmpty() && selectedExpandedGroup(state) != null -> {
            e.preventDefault()
            selectedExpandedGroup(state)?.let { agentsOverview.toggleGroup(it) }
        }
        e.key == "Backspace" && e.metaKey && state.query.isEmpty() -> {
            e.preventDefault()
            launchAgentsOverview { agentsOverview.requestClose(fromKeyboard = true) }
        }
    }
}

/** The id of the expanded group the highlighted row belongs to, or null. */
private fun selectedExpandedGroup(state: AgentsOverviewBackingViewModel.State): String? =
    state.rows.getOrNull(state.selected)?.groupId?.takeIf { state.groups[it]?.expanded == true }

@OptIn(DelicateCoroutinesApi::class)
private fun launchAgentsOverview(block: suspend () -> Unit) {
    GlobalScope.launch { block() }
}

/**
 * Rebuild the list from [state]: search results under a "N MATCHES" heading while searching,
 * otherwise the "Now" and "Earlier" sections. The selected row is highlighted and carries the
 * peek popover; the close confirmation follows the row it asks about.
 */
private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    list.textContent = ""
    shownRows = state.rows
    hintElement?.textContent = groupHint(state) ?: DEFAULT_HINT
    if (state.rows.isEmpty()) {
        list.appendChild(div("ao-empty").also { it.textContent = state.emptyText })
        return
    }
    var index = 0
    fun add(rows: List<Row>) = rows.forEachIndexed { i, row ->
        val selected = index == state.selected
        val endsGroup = row.groupId != null && rows.getOrNull(i + 1)?.groupId != row.groupId
        list.appendChild(
            when (row.kind) {
                Kind.GROUP -> buildGroupHeader(row, state.groups[row.groupId], index, selected, endsGroup, if (selected) state.peek else null)
                Kind.GROUP_MORE -> buildGroupMore(row, index, selected)
                else -> buildRow(row, index, selected, if (selected) state.peek else null).also {
                    if (row.groupId != null) {
                        it.classList.add("ao-member")
                        if (endsGroup) it.classList.add("ao-group-end")
                    }
                }
            },
        )
        state.confirm?.takeIf { it.rowId == row.id }?.let { list.appendChild(buildConfirm(it)) }
        index++
    }
    if (state.matchMode) {
        state.matchHeading?.let { list.appendChild(div("ao-heading").also { h -> h.textContent = it }) }
        add(state.matches)
    } else {
        if (state.now.isNotEmpty()) {
            list.appendChild(div("ao-heading").also { it.textContent = "NOW" })
            add(state.now)
        }
        if (state.earlier.isNotEmpty()) {
            list.appendChild(div("ao-heading").also { it.textContent = "EARLIER" })
            add(state.earlier)
            if (state.earlierHidden > 0) {
                val more = div("ao-more")
                more.textContent = "More"
                more.addEventListener("click", { _: Event -> agentsOverview.showMoreEarlier() })
                list.appendChild(more)
            }
        }
    }
    (list.querySelector(".ao-selected") as? HTMLElement)?.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
}

/**
 * What ↵ does when a group header is selected, and what the lead session last said, for the
 * footer; null for any other selection. Named after the group's tag, so a group tagged
 * `PROGRAMME` reads "↵ opens the programme session".
 */
private fun groupHint(state: AgentsOverviewBackingViewModel.State): String? {
    val row = state.rows.getOrNull(state.selected)?.takeIf { it.kind == Kind.GROUP } ?: return null
    val group = state.groups[row.groupId] ?: return null
    val said = group.leadLastSaid?.let { " · it last said: $it" } ?: ""
    return "↵ opens the ${group.tag?.lowercase() ?: "lead"} session$said"
}

/** Where a search matched, in words, for the line under a result. */
private fun fieldLabel(field: String?): String = when (field) {
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
 * One session row: state icon, agent, title with links and repo, now line, age and action,
 * plus, as the design shows, the search snippet, the resume note and (when selected) the peek.
 */
private fun buildRow(row: Row, index: Int, selected: Boolean, peek: AgentsOverviewBackingViewModel.Peek?): HTMLElement {
    val needs = row.state == "needs_you"
    val live = row.state == "needs_you" || row.state == "working"
    val el = div(
        "ao-row" + (if (needs) " ao-needs" else "") + (if (selected) " ao-selected" else "") +
            (if (live) "" else " ao-history"),
    )
    el.setAttribute("data-index", index.toString())

    el.appendChild(span("ao-icon ao-state-${row.state}", when (row.state) {
        "needs_you" -> "◆"
        "working" -> "◌"
        "idle" -> "●"
        else -> "○"
    }))

    val main = div("ao-main")
    if (row.agent.isNotEmpty()) {
        main.appendChild(if (row.agent == "claude") span("ao-glyph", "✳") else span("ao-tag", row.agent))
    }
    val title = span("ao-title", "")
    if (row.field == "title") title.appendChild(highlighted(row.title, row.matchStart, row.matchLength))
    else title.textContent = row.title
    main.appendChild(title)
    row.links.forEach { main.appendChild(span("ao-link", it)) }
    row.repo?.let { main.appendChild(span("ao-repo", it)) }
    el.appendChild(main)

    // A working row shows a working glyph and, when Lunamux hosts it, what the agent is doing now;
    // once nothing has been heard from it for a while the glyph stops and the age says so.
    val activity = row.activityMillis?.takeIf { row.state == "working" }
    val quiet = activity != null && AgentsOverviewBackingViewModel.isQuiet(activity, kotlin.js.Date.now().toLong())
    val now = div("ao-now")
    val error = row.error
    if (error != null) {
        now.classList.add("ao-error")
        now.textContent = error
    } else {
        if (row.state == "working") {
            now.classList.add("ao-has-work")
            now.appendChild(workingGlyph(active = activity != null && !quiet))
        }
        val text = span("ao-now-text", "")
        row.label?.let { text.appendChild(span("ao-label", "$it  ")) }
        text.appendChild(document.createTextNode(when {
            needs -> row.question ?: row.nowLine ?: "Needs you"
            else -> row.nowLine ?: if (row.state == "working" && activity == null) "Working" else ""
        }))
        now.appendChild(text)
    }
    el.appendChild(now)

    el.appendChild(
        if (quiet) span("ao-age ao-quiet", "quiet ${formatAge(activity!!)}")
        else span("ao-age", formatAge(row.sinceMillis)),
    )
    val actions = span("ao-actions", "")
    if (row.actionLabel.isNotEmpty()) actions.appendChild(span("ao-action", row.actionLabel))
    if (selected && !row.unhosted) {
        actions.appendChild(iconButton("Peek at its screen", "›", peek != null) {
            launchAgentsOverview { if (peek != null) agentsOverview.closePeek() else agentsOverview.peek() }
        })
        if (row.canClose) {
            actions.appendChild(iconButton("Close this session", "✕", false) {
                launchAgentsOverview { agentsOverview.requestClose() }
            })
        }
    }
    el.appendChild(actions)

    if (row.field != null && row.field != "title" && row.snippet != null) {
        val snip = div("ao-snippet")
        snip.appendChild(span("ao-snippet-label", fieldLabel(row.field) + "  "))
        snip.appendChild(highlighted(row.snippet!!, row.matchStart, row.matchLength))
        el.appendChild(snip)
    }
    row.resumeNote?.let { el.appendChild(div("ao-note").also { n -> n.textContent = it }) }
    if (peek != null) el.appendChild(buildPeek(peek))
    return el
}

/**
 * The header of a group box, on one line: chevron, title, repo, link chip when the group has one,
 * the count line with the needs-you part highlighted, and its age. What ↵ opens is told in the
 * footer ([groupHint]); a failed open of the lead adds an error line under the header.
 *
 * Clicking the chevron or the count line shows or hides the members; clicking anywhere else
 * opens the lead like ↵ does.
 *
 * @param row the [Kind.GROUP] row.
 * @param group the group's header data, or null if it vanished since the row was built.
 * @param index the row's position in the list, for click routing.
 * @param selected whether it is the highlighted row.
 * @param endsGroup whether no member rows follow, so the box closes under it.
 * @param peek the open peek of the lead session, if this header is selected and has one.
 */
private fun buildGroupHeader(
    row: Row,
    group: AgentsOverviewBackingViewModel.Group?,
    index: Int,
    selected: Boolean,
    endsGroup: Boolean,
    peek: AgentsOverviewBackingViewModel.Peek?,
): HTMLElement {
    val el = div(
        "ao-row ao-group" + (if (selected) " ao-selected" else "") + (if (endsGroup) " ao-group-end" else ""),
    )
    el.setAttribute("data-index", index.toString())
    val groupId = row.groupId

    val chevron = document.createElement("button") as HTMLElement
    chevron.asDynamic().type = "button"
    chevron.className = "ao-chevron"
    chevron.setAttribute("aria-label", "Show or hide all of the group's sessions")
    chevron.textContent = if (group?.expanded == true) "⌄" else "›"
    chevron.addEventListener("click", { e: Event ->
        e.stopPropagation()
        if (groupId != null) agentsOverview.toggleGroup(groupId)
    })
    el.appendChild(chevron)

    val main = div("ao-main")
    main.appendChild(span("ao-title ao-group-title", row.title))
    if (group != null && group.workingCount > 0) {
        val busy = group.activityMillis?.let { !AgentsOverviewBackingViewModel.isQuiet(it, kotlin.js.Date.now().toLong()) } == true
        main.appendChild(workingGlyph(active = busy))
    }
    group?.repo?.let { main.appendChild(span("ao-repo", it)) }
    val url = group?.linkUrl
    if (url != null) {
        val chip = document.createElement("a") as HTMLElement
        chip.className = "ao-link-chip"
        chip.setAttribute("href", url)
        chip.setAttribute("target", "_blank")
        chip.setAttribute("rel", "noopener noreferrer")
        chip.textContent = group.linkLabel ?: url
        chip.addEventListener("click", { e: Event -> e.stopPropagation() })
        main.appendChild(chip)
    }
    el.appendChild(main)

    val counts = document.createElement("button") as HTMLElement
    counts.asDynamic().type = "button"
    counts.className = "ao-counts"
    if (group != null) {
        if (group.needYouCount > 0) counts.appendChild(span("ao-counts-need", "${group.needYouCount} needs you"))
        // One span per count, kept on one line, so the line only wraps between counts.
        val rest = group.summary.split(" · ").filter { it.isNotEmpty() }
        rest.forEachIndexed { i, part ->
            if (i > 0 || group.needYouCount > 0) counts.appendChild(document.createTextNode(" · "))
            counts.appendChild(span("ao-counts-rest", part))
        }
    }
    counts.addEventListener("click", { e: Event ->
        e.stopPropagation()
        if (groupId != null) agentsOverview.toggleGroup(groupId)
    })
    el.appendChild(counts)

    el.appendChild(span("ao-age", if (row.sinceMillis > 0) formatAge(row.sinceMillis) else ""))
    el.appendChild(span("ao-actions", ""))

    row.error?.let { el.appendChild(div("ao-note ao-error").also { line -> line.textContent = it }) }
    if (peek != null) el.appendChild(buildPeek(peek))
    return el
}

/** The quiet "2 more" line under a collapsed group; ↵ or a click shows every member. */
private fun buildGroupMore(row: Row, index: Int, selected: Boolean): HTMLElement {
    val el = div("ao-row ao-group-more ao-group-end" + if (selected) " ao-selected" else "")
    el.setAttribute("data-index", index.toString())
    el.appendChild(span("ao-more-text", row.title))
    return el
}

/** A small square button on the selected row; clicks do not also activate the row. */
private fun iconButton(label: String, glyph: String, pressed: Boolean, onClick: () -> Unit): HTMLElement {
    val b = document.createElement("button") as HTMLElement
    b.asDynamic().type = "button"
    b.className = "ao-icon-btn" + if (pressed) " ao-pressed" else ""
    b.setAttribute("aria-label", label)
    b.title = label
    b.textContent = glyph
    b.addEventListener("click", { e: Event -> e.stopPropagation(); onClick() })
    return b
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
