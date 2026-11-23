/**
 * Browser checks for the sidebar footer's agent block: laid out with the page's own stylesheet it
 * is as tall with nothing working as with something working, and it says each count once.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.Tally
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FooterLayoutTest {
    /** Puts [el] in a host as wide as the sidebar and returns the host; the caller removes it. */
    private fun mount(el: HTMLElement, width: Int): HTMLElement {
        val host = div("")
        host.style.width = "${width}px"
        host.appendChild(el)
        document.body!!.appendChild(host)
        return host
    }

    @Test
    fun theFooterAgentBlockKeepsItsHeightWhenNothingIsWorking() {
        val bar = div("world-status-bar")
        val host = mount(bar, 239)
        renderAgentStatus(bar, listOf(StatusLine(null, null, 2, 1)))
        val busy = bar.getBoundingClientRect().height
        renderAgentStatus(bar, emptyList())
        val quiet = bar.getBoundingClientRect().height
        val text = bar.textContent.orEmpty()
        host.remove()
        assertTrue(text.contains("None working"), "the quiet block says nothing: \"$text\"")
        assertEquals(busy, quiet, "the block changed height at zero")
    }

    @Test
    fun theFooterSaysEachCountOnceAndATotalOnlyOverSeveralBusyWorlds() {
        fun w(name: String, needsYou: Int, working: Int) =
            WorldCounts(name, Tally(needsYou = needsYou, working = working).takeIf { needsYou + working > 0 }) { "#123456" }
        fun read(lines: List<StatusLine>) = lines.map { Triple(it.name, it.waiting, it.working) }
        val total = Tally(needsYou = 1, working = 2)
        assertEquals(listOf(Triple(null, 1, 2)), read(agentStatusLines(total, emptyList())), "no worlds: one unnamed line")
        assertEquals(
            listOf(Triple("Upright", 1, 2)),
            read(agentStatusLines(total, listOf(w("Upright", 1, 2), w("Home", 0, 0)))),
            "one busy world of several: its line alone, no total repeating it",
        )
        assertEquals(
            listOf(Triple("All worlds", 2, 3), Triple("Upright", 1, 2), Triple("Home", 1, 1)),
            read(agentStatusLines(Tally(needsYou = 2, working = 3), listOf(w("Upright", 1, 2), w("Home", 1, 1)))),
        )
        assertEquals(emptyList(), agentStatusLines(Tally(), listOf(w("Upright", 0, 0), w("Home", 0, 0))))
        val bar = div("world-status-bar")
        val host = mount(bar, 239)
        renderAgentStatus(bar, listOf(StatusLine(null, null, 2, 1)))
        val text = bar.textContent.orEmpty()
        host.remove()
        assertTrue("•" !in text, "no bullet beside the spinner: \"$text\"")
    }
}
