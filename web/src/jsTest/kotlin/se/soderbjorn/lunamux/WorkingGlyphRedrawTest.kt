/**
 * A browser test of the working spinner across redraws.
 *
 * A list that shows a spinner is drawn again many times a second while an agent works. The
 * spinner must keep turning through that: it is the same element each time, its frame follows the
 * clock, and it never starts over from its first frame. The test draws a keyed spinner again every
 * 100 ms for a little over two seconds in a real browser and reads the frame on show every 50 ms.
 *
 * Contains [WorkingGlyphRedrawTest].
 *
 * @see workingGlyph
 * @see keepWorkingGlyphs
 */
package se.soderbjorn.lunamux

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** Tests for [workingGlyph] drawn again on a timer. */
class WorkingGlyphRedrawTest {

    /** The frame the glyph shows now: the text of the layer that is not faded out. */
    private fun shown(glyph: HTMLElement): String {
        val layers = glyph.children
        for (i in 0 until layers.length) {
            val layer = layers.item(i) as HTMLElement
            if (layer.style.opacity == "1") return layer.textContent ?: ""
        }
        return ""
    }

    /**
     * Over two seconds of redraws every 100 ms the same element comes back, the frame on show is
     * the clock's frame or the one before it (the clock ticks once per step, so it can be a step
     * behind for part of one), and the frames only ever advance by one place in their cycle.
     */
    @Test
    fun aSpinnerDrawnAgainEveryTenthOfASecondKeepsTurningInStepWithTheClock(): Promise<Unit> {
        val spec = BRAILLE_PAIR_TURN
        val container = document.createElement("div") as HTMLElement
        document.body!!.appendChild(container)
        val first = workingGlyph("test:row")
        container.appendChild(first)
        val problems = ArrayList<String>()
        val seen = ArrayList<String>()
        var lastIndex = spec.frames.indexOf(shown(first))
        var redraws = 0
        val started = kotlin.js.Date.now()
        return Promise { resolve, reject ->
            var redrawTimer = 0
            var sampleTimer = 0
            redrawTimer = window.setInterval({
                val again = workingGlyph("test:row")
                if (again !== first) problems.add("a redraw built a new element")
                container.appendChild(again)
                redraws++
            }, 100)
            sampleTimer = window.setInterval({
                val now = kotlin.js.Date.now()
                val frame = shown(first)
                val index = spec.frames.indexOf(frame)
                val clockIndex = ((now / spec.stepMs).toLong() % spec.frames.size).toInt()
                val behindIndex = (clockIndex + spec.frames.size - 1) % spec.frames.size
                if (index < 0) problems.add("no frame on show at ${now - started} ms")
                else {
                    if (index != clockIndex && index != behindIndex) problems.add("frame $index at ${now - started} ms, the clock says $clockIndex")
                    if (index != lastIndex && index != (lastIndex + 1) % spec.frames.size) problems.add("frame jumped from $lastIndex to $index")
                    if (index != lastIndex) seen.add(frame)
                    lastIndex = index
                }
                if (now - started >= 2300) {
                    window.clearInterval(redrawTimer)
                    window.clearInterval(sampleTimer)
                    keepWorkingGlyphs("test:", emptySet())
                    container.remove()
                    try {
                        assertTrue(redraws >= 20, "only $redraws redraws ran")
                        assertTrue(seen.size >= 6, "the spinner turned only ${seen.size} steps in two seconds")
                        assertEquals(emptyList(), problems)
                        resolve(Unit)
                    } catch (e: Throwable) {
                        reject(e)
                    }
                }
            }, 50)
        }
    }
}
