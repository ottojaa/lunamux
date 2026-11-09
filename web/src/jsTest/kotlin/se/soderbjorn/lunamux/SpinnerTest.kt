/**
 * Browser check for the working spinner: it must never be seen to restart or go dark.
 *
 * The check lays real rows out with the page's own stylesheet, then, for a little over two seconds,
 * reads each spinner's rendered opacity many times a second while rows are redrawn through
 * [syncChildren] (same items, changed items, a row dropped and added again). A spinner is fine when
 * its four frames always add up to about one full glyph and they sit where the wall clock says. A
 * copy that starts its turn afresh each time it is built, or a fade that is cut off, fails it; the
 * second test proves that by checking a deliberately broken spinner.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import org.w3c.dom.HTMLElement
import org.w3c.dom.MutationObserver
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SpinnerTest {
    private val cycleMs = 1200
    private val stepMs = 300

    /** A spinner that ignores the clock: each copy starts its turn from the beginning, so a redrawn row restarts it. */
    private fun brokenSpinner(): HTMLElement {
        val box = spinner()
        for (i in 0 until box.children.length) (box.children.item(i) as HTMLElement).style.animationDelay = "${-i * stepMs}ms"
        return box
    }

    /** A spinner whose turn is cut short: each frame starts over before its cycle is done, so a fade is cut off mid-way. */
    private fun cutShortSpinner(): HTMLElement {
        val box = spinner()
        for (i in 0 until box.children.length) (box.children.item(i) as HTMLElement).style.animationDuration = "1000ms"
        return box
    }

    private fun opacities(spin: HTMLElement): List<Double> =
        (0 until spin.children.length).map { window.getComputedStyle(spin.children.item(it)!!).opacity.toDouble() }

    /** What the opacities should be at wall-clock [now]: frame i is lit for the quarter of the cycle that starts at i * step. */
    private fun expected(now: Long): List<Double> {
        val at = (now % cycleMs).toInt()
        return (0 until 4).map { i ->
            val p = (((at - i * stepMs) % cycleMs + cycleMs) % cycleMs).toDouble() / cycleMs * 100
            when {
                p < 8 -> p / 8
                p < 25 -> 1.0
                p < 33 -> 1 - (p - 25) / 8
                else -> 0.0
            }
        }
    }

    private suspend fun pause(ms: Int) = Promise<Unit> { resolve, _ -> window.setTimeout({ resolve(Unit) }, ms) }.await()

    /**
     * Redraws rows for [forMs] and returns the problems seen: samples where the four frames did not
     * add up to a glyph, or where they were not where the clock puts them.
     */
    private suspend fun watch(forMs: Int, make: () -> HTMLElement): List<String> {
        val host = div("ao-test-host")
        document.body!!.appendChild(host)
        data class Item(val id: Int, val rev: Int)
        fun draw(items: List<Item>) = syncChildren(host, items, { "r${it.id}" }) { item ->
            div("ao-row").also { row -> row.appendChild(make()); row.appendChild(span("ao-t", "row ${item.id} rev ${item.rev}")) }
        }
        val problems = ArrayList<String>()
        var samples = 0
        val started = kotlin.js.Date.now().toLong()
        var round = 0
        while (kotlin.js.Date.now().toLong() - started < forMs) {
            round++
            // Redraw: the same rows, then a changed row, then a row dropped and added again.
            when (round % 4) {
                0 -> draw(listOf(Item(1, 0), Item(2, 0), Item(3, 0)))
                1 -> draw(listOf(Item(1, round), Item(2, 0), Item(3, 0)))
                2 -> draw(listOf(Item(1, round), Item(3, 0)))
                else -> draw(listOf(Item(1, round), Item(2, round), Item(3, 0)))
            }
            for (i in 0 until host.children.length) {
                val spin = host.children.item(i)!!.querySelector(".ao-work") as HTMLElement
                val seen = opacities(spin)
                samples++
                val now = kotlin.js.Date.now().toLong()
                val sum = seen.sum()
                val want = expected(now)
                val off = seen.indices.maxOf { kotlin.math.abs(seen[it] - want[it]) }
                // Slack for the frame or two between building a row and its first drawing.
                if (sum < 0.85 || sum > 1.2 || off > 0.35) problems += "t=${now - started}ms sum=$sum off=$off seen=$seen want=$want"
            }
            pause(10)
        }
        host.remove()
        println("spinner check: ${problems.size} of $samples samples off")
        return problems
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun spinnerKeepsTurningWhileRowsAreRedrawn(): Promise<Unit> = GlobalScope.promise {
        val problems = watch(2500) { spinner() }
        assertTrue(problems.isEmpty(), "spinner restarted or went dark: ${problems.take(3)}")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun aSpinnerThatRestartsIsCaught(): Promise<Unit> = GlobalScope.promise {
        val problems = watch(2500) { brokenSpinner() }
        assertTrue(problems.isNotEmpty(), "the check passed a spinner that restarts")
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun aCutOffFadeIsCaught(): Promise<Unit> = GlobalScope.promise {
        val problems = watch(2500) { cutShortSpinner() }
        assertTrue(problems.isNotEmpty(), "the check passed a spinner whose fade is cut off")
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
