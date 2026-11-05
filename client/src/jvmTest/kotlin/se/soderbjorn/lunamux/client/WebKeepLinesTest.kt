/**
 * Holds in place two things the web page does that have no browser test: ⌘⇧A opens the agents
 * overview, and the task panel keeps its scroll position and focused line when it is drawn again.
 *
 * There is no browser test setup for the web module, so the tests read the web module's sources
 * (paths come from system properties set in `client/build.gradle.kts`). The panel behaviour is
 * also seen on a running page in the rehearsal's page probe.
 *
 * Contains [WebKeepLinesTest].
 *
 * @see SpinnerNeverRestartsTest
 */
package se.soderbjorn.lunamux.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Source-level guards for the overview's hotkey and the panel's redraw. */
class WebKeepLinesTest {

    private fun source(property: String): String = File(System.getProperty(property)).readText()

    /** The overview is bound to ⌘⇧A by default and the binding toggles the overview. */
    @Test
    fun commandShiftAOpensTheAgentsOverview() {
        val overview = source("lunamux.agentsOverviewSource")
        assertTrue(
            Regex("""defaults = listOf\(Hotkey\(key = "a", meta = true, shift = true\)\)""").containsMatchIn(overview),
            "the overview's default key is no longer ⌘⇧A",
        )
        assertTrue(overview.contains("{ agentsOverview.toggle() }"), "the hotkey no longer toggles the overview")
        assertTrue(source("lunamux.toolkitBootstrapSource").contains("registerAgentsOverviewHotkey()"), "the hotkey is no longer registered at start-up")
    }

    /** A redraw reads the scroll position and the focused line first and puts both back afterwards. */
    @Test
    fun theTaskPanelKeepsScrollAndFocusAcrossARedraw() {
        val panel = source("lunamux.taskPanelSource")
        val render = panel.substringAfter("private fun renderTaskPanel(")
        val readScroll = render.indexOf("val scroll = panel.querySelector(\".tp-scroll\")?.scrollTop")
        val readFocus = render.indexOf("val focusedKey = ")
        val rebuild = render.indexOf("panel.removeChild(panel.firstChild!!)")
        val putScroll = render.indexOf("content.scrollTop = scroll")
        val putFocus = render.indexOf("findByFocusKey(panel, key)?.focus()")
        assertTrue(readScroll in 0 until rebuild && readFocus in 0 until rebuild, "scroll and focus must be read before the panel is rebuilt")
        assertTrue(putScroll > rebuild && putFocus > rebuild, "scroll and focus must be put back after the panel is rebuilt")
    }
}
