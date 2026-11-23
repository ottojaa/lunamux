/**
 * Browser checks for the working spinner in the agents overview: it must turn in step with the wall
 * clock however often a row is built, and a row that did not change must stay the same element so
 * its animation is never restarted.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.MutationObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpinnerTest {
    /** Each frame starts at the clock's phase, one step behind the one before it, so any copy built at any time is in step. */
    @Test
    fun framesAreOffsetFromTheClockNotFromTheirOwnStart() {
        val before = kotlin.js.Date.now().toLong()
        val box = spinner()
        val after = kotlin.js.Date.now().toLong()
        assertEquals(4, box.children.length)
        val delays = (0 until 4).map {
            -(box.children.item(it) as org.w3c.dom.HTMLElement).style.animationDelay.removeSuffix("ms").toLong()
        }
        // Frame i is i steps further into its cycle's past than frame 0 (mod the cycle).
        val cycle = 1200L
        val step = 300L
        for (i in 1 until 4) assertEquals(((delays[0] - i * step) % cycle + cycle) % cycle, delays[i])
        val phase = delays[0]
        assertTrue(phase in 0 until cycle, "phase $phase inside one cycle")
        assertTrue(before >= 0 && after >= before)
    }

    /** A row whose item is equal to the one it was built from stays the same element, and a row dropped before it does not move it (moving restarts its animation). */
    @Test
    fun unchangedRowsAreReusedAndNeverMoved() {
        data class Item(val id: Int, val rev: Int)
        val host = div("ao-test-host")
        document.body!!.appendChild(host)
        fun draw(items: List<Item>) = syncChildren(host, items, { "r${it.id}" }) { div("ao-row").also { row -> row.setAttribute("data-key", "mine") } }
        draw(listOf(Item(1, 0), Item(2, 0), Item(3, 0)))
        val first = host.children.item(0)
        val last = host.children.item(2)
        val moves = MutationObserver { _, _ -> }
        moves.observe(host, js("({ childList: true })"))
        draw(listOf(Item(1, 0), Item(3, 0)))
        val records = moves.takeRecords()
        moves.disconnect()
        assertSame(first, host.children.item(0))
        assertSame(last, host.children.item(1))
        assertEquals("mine", first!!.getAttribute("data-key"), "the row keeps the attributes its builder gave it")
        assertTrue(records.none { it.addedNodes.length > 0 }, "a kept row was moved")
        host.remove()
    }
}
