/**
 * The agents overview in the web client: a topbar chip that counts the agent
 * sessions needing the user and the ones at work, and the ⌘⇧A overlay that
 * lists every agentic-CLI session (Claude Code, Codex, Gemini) under "Now"
 * and "Earlier".
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
private var shownRows: List<Row> = emptyList()

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
    renderChip(state)
    if (state.open) {
        ensureOverlay(state.query)
        renderList(state)
    } else {
        closeOverlayElement()
    }
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
    input.placeholder = "Search sessions"
    input.value = query
    input.setAttribute("autocomplete", "off")
    input.setAttribute("spellcheck", "false")
    input.addEventListener("input", { _: Event -> agentsOverview.setQuery(input.value) })
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
    panel.appendChild(div("ao-hint").also {
        it.textContent = "type to search · ↑↓ select · ↵ jump or resume · esc close"
    })
    backdrop.appendChild(panel)
    document.body?.appendChild(backdrop)
    overlayElement = backdrop
    searchInput = input
    listElement = list
    input.focus()
}

private fun closeOverlayElement() {
    overlayElement?.remove()
    overlayElement = null
    searchInput = null
    listElement = null
    shownRows = emptyList()
}

/** Turn a key press in the search box into a view-model call. */
private fun onSearchKey(e: KeyboardEvent) {
    when (e.key) {
        "ArrowDown" -> { e.preventDefault(); agentsOverview.moveSelection(1) }
        "ArrowUp" -> { e.preventDefault(); agentsOverview.moveSelection(-1) }
        "Enter" -> { e.preventDefault(); launchAgentsOverview { agentsOverview.activateSelected() } }
        "Escape" -> { e.preventDefault(); agentsOverview.close() }
    }
}

@OptIn(DelicateCoroutinesApi::class)
private fun launchAgentsOverview(block: suspend () -> Unit) {
    GlobalScope.launch { block() }
}

/** Rebuild the "Now" and "Earlier" sections and highlight the selected row. */
private fun renderList(state: AgentsOverviewBackingViewModel.State) {
    val list = listElement ?: return
    list.textContent = ""
    shownRows = state.rows
    if (state.rows.isEmpty()) {
        val text = if (state.query.isBlank()) "No agent sessions yet" else "Nothing matches “${state.query}”"
        list.appendChild(div("ao-empty").also { it.textContent = text })
        return
    }
    var index = 0
    if (state.now.isNotEmpty()) {
        list.appendChild(div("ao-heading").also { it.textContent = "NOW" })
        state.now.forEach { list.appendChild(buildRow(it, index, index == state.selected)); index++ }
    }
    if (state.earlier.isNotEmpty()) {
        list.appendChild(div("ao-heading").also { it.textContent = "EARLIER" })
        state.earlier.forEach { list.appendChild(buildRow(it, index, index == state.selected)); index++ }
        if (state.earlierHidden > 0) {
            val more = div("ao-more")
            more.textContent = "More (${state.earlierHidden})"
            more.addEventListener("click", { _: Event -> agentsOverview.showMoreEarlier() })
            list.appendChild(more)
        }
    }
    (list.querySelector(".ao-selected") as? HTMLElement)?.asDynamic().scrollIntoView(js("({block: 'nearest'})"))
}

/** One session row: state icon, agent, title with links and repo, now line, age, action. */
private fun buildRow(row: Row, index: Int, selected: Boolean): HTMLElement {
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
    main.appendChild(
        if (row.agent == "claude") span("ao-glyph", "✳") else span("ao-tag", row.agent),
    )
    main.appendChild(span("ao-title", row.title))
    row.links.forEach { main.appendChild(span("ao-link", it)) }
    row.repo?.let { main.appendChild(span("ao-repo", it)) }
    el.appendChild(main)

    val now = div("ao-now")
    val error = row.error
    if (error != null) {
        now.classList.add("ao-error")
        now.textContent = error
    } else {
        now.textContent = when {
            needs -> row.question ?: row.nowLine ?: "Needs you"
            else -> row.nowLine ?: if (row.state == "working") "Working" else ""
        }
    }
    el.appendChild(now)

    el.appendChild(span("ao-age", formatAge(row.sinceMillis)))
    el.appendChild(span("ao-action", row.actionLabel))
    return el
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
