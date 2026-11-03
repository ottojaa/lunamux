/**
 * The mark a thread of work wears: one of 36 small signs (six shapes in six hues) that the agents
 * overview, the pane header and the tab all draw the same way.
 *
 * The server decides a mark and stores it (`AgentSessionSummary.mark`, `AgentTask.mark`); this
 * file only draws the number. Shape is `mark / 6` and hue `mark % 6`. Each shape is a tiny inline
 * SVG, so it looks the same in every font and in both themes; the hue comes from the
 * `--ao-mark-0` … `--ao-mark-5` tokens that `styles.css` defines per appearance.
 *
 * This file contains [buildSigil], which makes the element, and the code that keeps the marks on
 * panes and tabs current ([sigilForPane], [sigilForTab], [updateSigils], [startSigilMarks]).
 *
 * @see se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row.mark
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.viewmodel.tabMark

/** The SVG namespace the shapes are created in. */
private const val SIGIL_SVG_NS = "http://www.w3.org/2000/svg"

/** How many hues the mark palette has; the shape is `mark / HUES`, the hue `mark % HUES`. */
private const val HUES = 6

/** How many shapes there are. */
private const val SHAPES = 6

/**
 * Make the element that draws [mark].
 *
 * Called by the agents overview for every row that has a mark, and by [updateSigils] for the pane
 * header and the tab. The element is a `span.ao-sigil` holding one SVG, coloured by the
 * `ao-sigil-h<hue>` class through `currentColor`, so a theme change needs no redraw.
 *
 * @param mark 0 until 35; anything else is folded into range so a bad value still draws something.
 * @return the element, with `data-mark` set to the folded number.
 * @see updateSigils
 */
internal fun buildSigil(mark: Int): HTMLElement {
    val m = ((mark % (SHAPES * HUES)) + SHAPES * HUES) % (SHAPES * HUES)
    val el = document.createElement("span") as HTMLElement
    el.className = "ao-sigil ao-sigil-h${m % HUES}"
    el.setAttribute("data-mark", m.toString())
    el.setAttribute("aria-hidden", "true")
    val svg = document.createElementNS(SIGIL_SVG_NS, "svg")
    svg.setAttribute("viewBox", "0 0 12 12")
    svg.setAttribute("focusable", "false")
    shapeParts(m / HUES).forEach { (kind, attrs, filled) ->
        val part = document.createElementNS(SIGIL_SVG_NS, kind)
        attrs.forEach { (name, value) -> part.setAttribute(name, value) }
        part.setAttribute("class", if (filled) "ao-sigil-fill" else "ao-sigil-line")
        svg.appendChild(part)
    }
    el.appendChild(svg)
    return el
}

/** One piece of a shape: the SVG element name, its attributes and whether it is filled rather than outlined. */
private data class Piece(val kind: String, val attrs: List<Pair<String, String>>, val filled: Boolean)

/**
 * The pieces that draw shape number [shape]: a half-filled square, a filled square, a half-filled
 * circle, a circle with a dot, a triangle and a square with two filled corners.
 *
 * Called by [buildSigil].
 *
 * @param shape 0 until 5.
 * @return the pieces, back to front.
 */
private fun shapeParts(shape: Int): List<Piece> {
    fun rect(x: Number, y: Number, w: Number, h: Number, filled: Boolean) =
        Piece("rect", listOf("x" to "$x", "y" to "$y", "width" to "$w", "height" to "$h"), filled)
    fun circle(r: Number, filled: Boolean) =
        Piece("circle", listOf("cx" to "6", "cy" to "6", "r" to "$r"), filled)
    return when (shape) {
        0 -> listOf(rect(1, 1, 10, 10, false), rect(1, 1, 5, 10, true))
        1 -> listOf(rect(1, 1, 10, 10, true))
        2 -> listOf(circle(5, false), Piece("path", listOf("d" to "M6 1 A5 5 0 0 0 6 11 Z"), true))
        3 -> listOf(circle(5, false), circle(2.2, true))
        4 -> listOf(Piece("path", listOf("d" to "M6 1 L11 10.5 L1 10.5 Z"), true))
        else -> listOf(rect(1, 1, 10, 10, false), rect(1, 1, 5, 5, true), rect(6, 6, 5, 5, true))
    }
}

/**
 * The mark of the thread running in the pane [leafId], taken from the live (not ended) session
 * the server reports there.
 *
 * Called by [updateSigils] for each pane header. An ended record keeps its `leafId`, so only open
 * sessions count: a pane that has been reused must not wear the mark of its past tenant.
 *
 * @param leafId the pane's layout leaf id.
 * @return the mark, or null when no open session in that pane belongs to a thread.
 */
internal fun sigilForPane(leafId: String): Int? =
    lunamuxClient.windowState.agentSessions.value
        .firstOrNull { it.leafId == leafId && it.state != "ended" && it.mark != null }?.mark

/**
 * The mark a tab wears: the one its marked panes share.
 *
 * Called by [updateSigils] for each tab in the strip. Every pane in the tab that runs a marked
 * session counts; when they all wear one mark the tab wears it, and when they wear two or more
 * it wears none (one mark would be a lie). Marks are compared, not thread keys: a session in a
 * task wears the task's mark, so what the tab draws is what its panes draw. A tab with no marked
 * pane wears none.
 *
 * @param leafIds the layout leaf ids of the tab's panes.
 * @return the mark, or null.
 * @see tabMark
 */
internal fun sigilForTab(leafIds: Collection<String>): Int? =
    tabMark(lunamuxClient.windowState.agentSessions.value, leafIds)

/**
 * Bring the marks on pane headers and tabs up to date with the sessions and the layout.
 *
 * Called when the layout or the states are repainted ([updateStateIndicators]), when the server
 * pushes new sessions ([startSigilMarks]) and by [fillSigilSlot] when a slot is first built. Looks
 * for the slots the badge builders left behind (`[data-sigil-pane]`, `[data-sigil-tab]`) and sets
 * each to its mark, or empties it. A slot whose mark did not change is left alone, so the DOM
 * does not churn on every push.
 *
 * @see sigilForPane
 * @see sigilForTab
 */
internal fun updateSigils() {
    val slots = document.querySelectorAll("[data-sigil-pane], [data-sigil-tab]")
    for (i in 0 until slots.length) {
        val slot = slots.item(i) as? HTMLElement ?: continue
        fillSigilSlot(slot, markOfSlot(slot.getAttribute("data-sigil-pane"), slot.getAttribute("data-sigil-tab")))
    }
}

/**
 * The mark a badge slot should show, given the pane or tab it belongs to.
 *
 * Called by [updateSigils] for slots already on the page and by [withSigil] for a slot that is
 * just being built.
 *
 * @param paneId the pane's layout leaf id for a pane-header slot, else null.
 * @param tabId the tab's id for a tab slot, else null.
 * @return the mark, or null.
 */
private fun markOfSlot(paneId: String?, tabId: String?): Int? {
    if (paneId != null) return sigilForPane(paneId)
    if (tabId == null) return null
    val cfg = appVm.stateFlow.value.config ?: return null
    val tabs = cfg.activeWorldOrNull()?.tabs ?: cfg.tabs
    return tabs.firstOrNull { it.id == tabId }?.let { tab -> sigilForTab(tab.panes.map { it.leaf.id }) }
}

/**
 * Wrap a status dot with the slot the mark goes in, for the pane header and the tab strip.
 *
 * Called by the toolkit's `paneHeaderBadge` and `tabTrailingBadge` hooks, which each accept a
 * single element. The wrapper is a plain inline span holding the mark's slot and then the dot,
 * so the toolkit's badge slot puts the mark ahead of the pane header's title and after the tab's
 * name, with the dot next to it where it always was. The slot is filled at once and refilled by
 * [updateSigils].
 *
 * @param attr `data-sigil-pane` or `data-sigil-tab`, saying which kind of slot this is.
 * @param id the pane's leaf id or the tab's id.
 * @param dot the existing status dot, or null when the pane has none.
 * @return the wrapper to hand to the toolkit.
 */
internal fun withSigil(attr: String, id: String, dot: HTMLElement?): HTMLElement {
    val wrapper = document.createElement("span") as HTMLElement
    wrapper.className = "ao-sigil-badge"
    val slot = document.createElement("span") as HTMLElement
    slot.className = "ao-sigil-slot"
    slot.setAttribute(attr, id)
    fillSigilSlot(slot, markOfSlot(id.takeIf { attr == "data-sigil-pane" }, id.takeIf { attr == "data-sigil-tab" }))
    wrapper.appendChild(slot)
    dot?.let { wrapper.appendChild(it) }
    return wrapper
}

/**
 * Put [mark] in [slot], or empty it.
 *
 * Called by [updateSigils] and by the badge builders to paint a slot at construction. Does nothing
 * when the slot already shows [mark].
 *
 * @param slot the `span` a badge builder made.
 * @param mark the mark to show, or null for none.
 */
internal fun fillSigilSlot(slot: Element, mark: Int?) {
    val shown = slot.getAttribute("data-mark")?.toIntOrNull()
    if (shown == mark) return
    slot.textContent = ""
    if (mark == null) slot.removeAttribute("data-mark") else {
        slot.setAttribute("data-mark", mark.toString())
        slot.appendChild(buildSigil(mark))
    }
}

/**
 * Keep pane and tab marks current as the server pushes the session list.
 *
 * Called once from [connectWindow]. The sessions arrive separately from the layout, so the badge
 * slots are refilled from here as well as from [updateStateIndicators].
 */
@OptIn(DelicateCoroutinesApi::class)
internal fun startSigilMarks() {
    GlobalScope.launch { lunamuxClient.windowState.agentSessions.collect { updateSigils() } }
}
