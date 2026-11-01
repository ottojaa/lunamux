/**
 * The small glyph the agents overview draws to say "this agent is working": in the state column of
 * a working row, and in the header of a task while any of its sessions works.
 *
 * It is one component whose look is entirely its [WorkingGlyphSpec] (the frames and their
 * timings), so changing the glyph means changing [BRAILLE_PAIR_TURN], not the drawing code.
 * Each glyph is two stacked layers; a shared clock sets the incoming layer to the next frame and
 * lets CSS fade it in while the other fades out. A glyph that is not busy (a session that has
 * gone quiet) is drawn still and dimmed; every glyph under the reduced-motion preference is drawn
 * still but keeps the accent colour. The look of the box and its resting state are the `.ao-work*`
 * rules in `styles.css`.
 *
 * @see workingGlyph
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
    liveGlyphs.removeAll { !it.box.isConnected }
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
 * Build the working glyph.
 *
 * Called by the agents overview for the state column of each working row and of a task
 * header with a working session.
 *
 * @param active whether the work is live (heard from lately, see
 *   [se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.isWorkingLive]); false
 *   draws the first frame still and dimmed.
 * @param spec the frames and timings.
 * @return a small inline element, hidden from assistive technology (the row's text says the rest).
 */
internal fun workingGlyph(active: Boolean, spec: WorkingGlyphSpec = BRAILLE_PAIR_TURN): HTMLElement {
    val box = element("ao-work")
    box.setAttribute("aria-hidden", "true")
    val reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches
    if (!active || reduceMotion || spec.frames.size < 2) {
        box.classList.add(if (active) "ao-work-still" else "ao-work-rest")
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

private fun element(className: String): HTMLElement =
    (document.createElement("span") as HTMLElement).also { it.className = className }
