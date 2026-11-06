/**
 * The small glyph the agents overview draws in front of "working" in the state cell of a row whose
 * task has an agent working.
 *
 * It is one component whose look is entirely its [WorkingGlyphSpec] (the frames and their
 * timings), so changing the glyph means changing [BRAILLE_PAIR_TURN], not the drawing code.
 * Each glyph is two stacked layers; a shared clock sets the incoming layer to the next frame and
 * lets CSS fade it in while the other fades out. Under the reduced-motion preference the glyph is
 * drawn still, in the same colour and glow. The look of the box is the `.ao-work*` rules in
 * `styles.css`.
 *
 * A glyph asked for with a key is built once and handed out again for that key, so a list that
 * is drawn again puts the same moving element back where it was and the motion never starts over.
 *
 * @see workingGlyph
 * @see keepWorkingGlyphs
 * @see WorkingGlyphSpec
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/**
 * The frames of a working glyph and how they move.
 *
 * @property frames the characters shown one after another, in order and then round again.
 * @property stepMs how long each frame is the current one.
 * @property fadeMs how long the fade from one frame to the next takes; it may exceed [stepMs], in
 *   which case a fade is cut short by the next step, as two stacked CSS transitions are.
 */
internal data class WorkingGlyphSpec(val frames: List<String>, val stepMs: Int, val fadeMs: Int)

/**
 * Two braille dots on the middle rows of the cell turning clockwise, a frame every 300 ms with
 * each frame cross-fading into the next over 400 ms. The default of [workingGlyph].
 */
internal val BRAILLE_PAIR_TURN = WorkingGlyphSpec(frames = listOf("⠆", "⠒", "⠰", "⠤"), stepMs = 300, fadeMs = 400)

/** One live glyph the shared clock drives: its box, its two layers and which one is in front. */
private class LiveGlyph(val box: HTMLElement, val layers: Array<HTMLElement>, var front: Int, val spec: WorkingGlyphSpec)

private val liveGlyphs = ArrayList<LiveGlyph>()

/** The glyphs handed out by key, so a redraw takes the same element again. */
private val keyedGlyphs = HashMap<String, HTMLElement>()

/** Handle of the shared clock, or null while no live glyph exists. */
private var clock: Int? = null

/**
 * The index of the frame that is current at [nowMillis]. Taken from the time of day rather than
 * from when a glyph was built, so all glyphs agree and a redraw of the list does not restart the
 * motion.
 */
private fun frameAt(spec: WorkingGlyphSpec, nowMillis: Double): Int =
    ((nowMillis / spec.stepMs).toLong() % spec.frames.size).toInt()

/**
 * Advance every live glyph to the frame that is now current, dropping any whose element has left
 * the page, and stop the clock when none is left. Called by the clock every step.
 */
private fun tick() {
    val now = kotlin.js.Date.now()
    // A glyph kept by key stays in the clock while its list is away, so it is still turning when it returns.
    liveGlyphs.removeAll { !it.box.isConnected && keyedGlyphs.values.none { k -> k === it.box } }
    for (g in liveGlyphs) {
        val next = 1 - g.front
        g.layers[next].textContent = g.spec.frames[frameAt(g.spec, now)]
        g.layers[next].style.opacity = "1"
        g.layers[g.front].style.opacity = "0"
        g.front = next
    }
    if (liveGlyphs.isEmpty()) clock?.let { window.clearInterval(it) }.also { clock = null }
}

/**
 * Build the working glyph, or hand out the one already built for [key].
 *
 * Called by the agents overview's row builder for the state cell of each row that reads "working"
 * or "N working", by the footer's working count and by the task panel's agent lines. Such a
 * place is moving by definition, so the glyph is always live. A glyph that was built for [key]
 * and is still in use is returned as it is: appending it to a new parent moves it, with its
 * frame intact; a fade in progress at that moment is cut short by the move, as the browser
 * cancels a transition on a node that is taken out and put back.
 *
 * @param key what the glyph stands for (a row, a footer, an agent line), or null for one that
 *   nothing draws twice.
 * @param spec the frames and timings.
 * @return a small inline element, hidden from assistive technology (the row's text says the rest).
 */
internal fun workingGlyph(key: String? = null, spec: WorkingGlyphSpec = BRAILLE_PAIR_TURN): HTMLElement {
    key?.let { k -> keyedGlyphs[k]?.let { return it } }
    val glyph = newWorkingGlyph(spec)
    key?.let { keyedGlyphs[it] = glyph }
    return glyph
}

/**
 * Drop every keyed glyph whose key starts with [prefix] and is not in [keys]. Called after a list
 * is drawn, with the keys it drew (none when it is taken down), so a glyph of a row that is gone
 * stops costing a tick. Glyphs of other lists, which use other prefixes, are left alone.
 *
 * @param prefix the part of the key that names the list ("overview:", "panel:").
 * @param keys the keys the latest draw used.
 */
internal fun keepWorkingGlyphs(prefix: String, keys: Set<String>) {
    val gone = keyedGlyphs.filterKeys { it.startsWith(prefix) && it !in keys }
    gone.keys.forEach { keyedGlyphs.remove(it) }
    liveGlyphs.removeAll { g -> gone.values.any { it === g.box } }
    if (liveGlyphs.isEmpty()) clock?.let { window.clearInterval(it) }.also { clock = null }
}

private fun newWorkingGlyph(spec: WorkingGlyphSpec): HTMLElement {
    val box = element("ao-work")
    box.setAttribute("aria-hidden", "true")
    val reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    if (reduceMotion || spec.frames.size < 2) {
        box.textContent = spec.frames.first()
        return box
    }
    val layers = Array(2) { element("ao-work-layer").also { l -> l.style.transition = "opacity ${spec.fadeMs}ms ease-in-out"; box.appendChild(l) } }
    layers[0].textContent = spec.frames[frameAt(spec, kotlin.js.Date.now())]
    layers[0].style.opacity = "1"
    liveGlyphs.add(LiveGlyph(box, layers, 0, spec))
    if (clock == null) clock = window.setInterval({ tick() }, spec.stepMs)
    return box
}

/** A `span` with [className], the building block of the glyph's box and layers. */
private fun element(className: String): HTMLElement =
    (document.createElement("span") as HTMLElement).also { it.className = className }
