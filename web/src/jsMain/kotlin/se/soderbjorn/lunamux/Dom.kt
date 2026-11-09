/**
 * Small drawing helpers the agents overview, the task panel and the sidebar footer share: element
 * builders, [syncChildren] (redraw a list without rebuilding what did not change), the spinner,
 * and the one timer that keeps the ages in those lists current.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts

internal fun div(className: String): HTMLElement =
    (document.createElement("div") as HTMLElement).also { it.className = className }

internal fun span(className: String, text: String = ""): HTMLElement =
    (document.createElement("span") as HTMLElement).also { it.className = className; it.textContent = text }

private const val KEY_ATTRIBUTE = "data-row-key"
private const val ITEM_PROPERTY = "__item"

/**
 * Makes the children of [parent] match [items], one child per item in the same order. A child whose
 * item is equal to the one it was built from stays untouched, and rows that went away are removed
 * first so the kept ones are never moved (moving an element restarts its CSS animation).
 */
internal fun <T : Any> syncChildren(parent: HTMLElement, items: List<T>, key: (T) -> String, build: (T) -> HTMLElement) {
    val wanted = items.mapTo(HashSet(), key)
    val old = HashMap<String, HTMLElement>()
    for (i in parent.children.length - 1 downTo 0) {
        val child = parent.children.item(i) as HTMLElement
        val k = child.getAttribute(KEY_ATTRIBUTE) ?: continue
        if (k in wanted) old[k] = child else child.remove()
    }
    var next: HTMLElement? = parent.firstElementChild as HTMLElement?
    for (item in items) {
        val k = key(item)
        val before = old.remove(k)
        val kept = before != null && item == before.asDynamic()[ITEM_PROPERTY]
        val child = if (kept) before!! else build(item).also {
            it.setAttribute(KEY_ATTRIBUTE, k)
            it.asDynamic()[ITEM_PROPERTY] = item
        }
        if (!kept && next === before) next = before?.nextElementSibling as HTMLElement?
        if (child === next) next = child.nextElementSibling as HTMLElement? else parent.insertBefore(child, next)
        if (!kept && before != null) {
            val hadFocus = before === document.activeElement
            before.remove()
            if (hadFocus) child.focus()
        }
    }
}

private val SPIN_FRAMES = listOf("⠆", "⠒", "⠰", "⠤")
private const val SPIN_STEP_MS = 300
private const val SPIN_CYCLE_MS = 4 * SPIN_STEP_MS

/** The turning glyph in front of "working". */
internal fun spinner(): HTMLElement {
    val box = span("ao-work")
    box.setAttribute("aria-hidden", "true")
    val phase = kotlin.js.Date.now().toLong() % SPIN_CYCLE_MS
    SPIN_FRAMES.forEachIndexed { i, glyph ->
        val frame = span("ao-work-f", glyph)
        val into = ((phase - i * SPIN_STEP_MS) % SPIN_CYCLE_MS + SPIN_CYCLE_MS) % SPIN_CYCLE_MS
        frame.style.animationDelay = "-${into}ms"
        box.appendChild(frame)
    }
    return box
}

/** An age that moves on by itself: [sinceMillis] is kept on the element for [startAgeClock]. */
internal fun ageSpan(className: String, sinceMillis: Long): HTMLElement {
    val el = span(className, if (sinceMillis <= 0) "" else AgentFacts.ageText(sinceMillis, kotlin.js.Date.now().toLong()))
    if (sinceMillis > 0) el.setAttribute("data-since", sinceMillis.toString())
    return el
}

private var ageClock: Int? = null

/** Starts, once, the timer that rewrites every age on the page in place every 30 seconds. */
internal fun startAgeClock() {
    if (ageClock != null) return
    ageClock = window.setInterval({
        val now = kotlin.js.Date.now().toLong()
        val ages = document.querySelectorAll("[data-since]")
        for (i in 0 until ages.length) {
            val el = ages.item(i) as HTMLElement
            val since = el.getAttribute("data-since")?.toLongOrNull() ?: continue
            val text = AgentFacts.ageText(since, now)
            if (el.textContent != text) el.textContent = text
        }
    }, 30_000)
}
