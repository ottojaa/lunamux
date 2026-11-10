/**
 * Browser checks for layout the panel and the sidebar footer promise: laid out with the page's own
 * stylesheet, a long task title wraps beside the close button with the shortcut under it and the status line sits a small gap under the title, an agent
 * row is as tall whatever the agent is doing (and whether or not its hint shows), and the
 * footer's agent block is as tall with nothing working as with something working.
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.AgentLine
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelLayoutTest {
    /** Puts [el] in a host as wide as the panel (or the sidebar) and returns the host; the caller removes it. */
    private fun mount(el: HTMLElement, width: Int): HTMLElement {
        val host = div("")
        host.style.width = "${width}px"
        host.appendChild(el)
        document.body!!.appendChild(host)
        return host
    }

    @Test
    fun aLongTaskTitleWrapsBesideTheCloseButtonWithTheShortcutUnderIt() {
        val p = PanelParts()
        p.title.textContent = "Release"
        val host = mount(p.root, 320)
        p.status.textContent = "Cutting the release; waiting on CI."
        val oneLineTitle = p.title.getBoundingClientRect()
        val gap = p.status.getBoundingClientRect().top - oneLineTitle.bottom
        val oneLine = oneLineTitle.height
        p.title.textContent = "Rewrite the agents overview and its panel so that it is the right size and keeps everything the owner asked for"
        val title = p.title.getBoundingClientRect()
        val hint = p.root.querySelector(".tp-hint")!!.getBoundingClientRect()
        val close = p.root.querySelector(".tp-close")!!.getBoundingClientRect()
        host.remove()
        assertTrue(title.height > 2 * oneLine, "the title cut off instead of wrapping: height ${title.height} against $oneLine for one line")
        assertTrue(title.right <= close.left, "the title runs under the close button")
        assertTrue(hint.top >= close.bottom - 1, "the shortcut is not under the close button")
        assertTrue(hint.right <= close.right && close.right - hint.right < 8, "the shortcut is not right-aligned to the close button")
        assertTrue(gap in 0.0..8.0, "the headline is not a small gap under the title: $gap px")
    }

    private fun row(title: String, doing: String, state: String, hint: String?) = AgentLine(
        title = title, doing = doing, state = state, sinceMillis = 0, action = if (hint == null) MemberAction.NONE else MemberAction.SHOW,
        recordId = "r", hint = hint,
    )

    @Test
    fun anAgentRowIsAsTallWhateverTheAgentDoesAndWhetherOrNotItsHintShows() {
        val title = "Fix the owner's first test"
        val variants = listOf(
            row(title, "Reading DeviceAuth.kt", AgentFacts.WORKING, "↵ show"),
            row(title, "Compare every flag in the docs against the ones the server reads and list the stale ones, one per line", AgentFacts.WORKING, "↵ show"),
            row(title, "working", AgentFacts.WORKING, "↵ show"),
            row(title, "idle", AgentFacts.DONE, "↵ show"),
            row(title, "Drop the `users` table and every migration that touches it, or keep the table and only drop its index?", AgentFacts.NEEDS_YOU, "↵ open"),
        )
        val rows = variants.map { buildAgent(it) }
        val list = div("tp-block")
        rows.forEach { list.appendChild(it) }
        val host = mount(list, 320)
        val resting = rows.map { it.getBoundingClientRect().height }
        // The stylesheet shows the hint on hover and focus; show it outright and measure again.
        rows.forEach { r -> (r.querySelector(".tp-agent-hint") as? HTMLElement)?.style?.display = "block" }
        val shown = rows.map { it.getBoundingClientRect().height }
        host.remove()
        assertEquals(1, resting.toSet().size, "rows differ in height with what the agent does: $resting")
        assertEquals(resting, shown, "the hint changed a row's height: $resting vs $shown")
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
}
