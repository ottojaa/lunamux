/**
 * Tests for how [WindowState] shows a background tab while its agent waits for the person and
 * hides it again afterwards.
 *
 * Contains [WindowStateWaitingTabTest].
 *
 * @see WindowState.showWaitingAgent
 * @see WindowState.hideSettledWaitingTabs
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs against the real [WindowState], like the other tests that open tabs, and closes every tab
 * it opens.
 */
class WindowStateWaitingTabTest {
    private fun tab(id: String) = WindowState.config.value.worlds.flatMap { it.tabs }.first { it.id == id }
    private fun leafOf(tabId: String) = tab(tabId).panes.first().leaf.id

    /** A waiting agent's hidden tab becomes listed, active and marked; the one before it is remembered. */
    @Test
    fun showingAWaitingAgentListsActivatesAndMarksItsTab() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            WindowState.setActiveTab(visible)
            assertFalse(tab(bg).isListed)
            assertTrue(WindowState.showWaitingAgent(leafOf(bg)))
            assertTrue(tab(bg).isListed)
            assertTrue(tab(bg).shownWhileWaiting)
            assertTrue(tab(bg).isBackground, "it is still a background tab, only shown for now")
            assertEquals(bg, WindowState.config.value.activeTabId)
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }

    /** Once the agent no longer waits, the tab hides again and the tab the person was on returns. */
    @Test
    fun theTabHidesAgainWhenTheAgentStopsWaitingAndThePreviousTabReturns() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            WindowState.setActiveTab(visible)
            WindowState.showWaitingAgent(leafOf(bg))
            WindowState.hideSettledWaitingTabs(setOf(leafOf(bg)))
            assertTrue(tab(bg).shownWhileWaiting, "still waiting, so it stays")
            WindowState.hideSettledWaitingTabs(emptySet())
            assertFalse(tab(bg).isListed)
            assertFalse(tab(bg).shownWhileWaiting)
            assertEquals(visible, WindowState.config.value.activeTabId)
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }

    /** A pane no tab holds shows nothing. */
    @Test
    fun anUnknownPaneShowsNothing() {
        assertFalse(WindowState.showWaitingAgent("no-such-pane"))
        assertNotNull(WindowState.config.value)
    }
}
