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

    /**
     * The shown tab is in another world than the person's: after the answer they are back in the
     * world and on the tab they came from, and the other world keeps the tab it had.
     */
    @Test
    fun aTabInAnotherWorldHidesAgainAndTheWorldAndTabTheyCameFromReturn() {
        val home = WindowState.activeWorldId()!!
        val visible = WindowState.addTab(worldId = home)
        val other = WindowState.addWorld("Elsewhere")
        val otherVisible = WindowState.addTab(worldId = other)
        val bg = WindowState.addTab(worldId = other, background = true)
        try {
            WindowState.setActiveWorld(other)
            WindowState.setActiveTab(otherVisible)
            WindowState.setActiveWorld(home)
            WindowState.setActiveTab(visible)

            assertTrue(WindowState.showWaitingAgent(leafOf(bg)))
            assertEquals(other, WindowState.activeWorldId(), "the person is taken to the agent's world")
            assertEquals(bg, WindowState.config.value.worlds.first { it.id == other }.activeTabId)

            WindowState.hideSettledWaitingTabs(emptySet())
            assertFalse(tab(bg).isListed)
            assertEquals(home, WindowState.activeWorldId(), "the world they came from comes back")
            assertEquals(visible, WindowState.config.value.worlds.first { it.id == home }.activeTabId)
            assertEquals(otherVisible, WindowState.config.value.worlds.first { it.id == other }.activeTabId,
                "the other world shows the tab it showed before")
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(otherVisible)
            WindowState.setActiveWorld(home)
            WindowState.closeWorld(other); WindowState.closeTab(visible)
        }
    }

    /** A person who went to another world while the tab was shown is left there. */
    @Test
    fun aPersonWhoMovedOnIsNotPulledBack() {
        val home = WindowState.activeWorldId()!!
        val visible = WindowState.addTab(worldId = home)
        val other = WindowState.addWorld("Elsewhere")
        val bg = WindowState.addTab(worldId = other, background = true)
        try {
            WindowState.setActiveTab(visible)
            WindowState.showWaitingAgent(leafOf(bg))
            WindowState.setActiveWorld(home)
            WindowState.hideSettledWaitingTabs(emptySet())
            assertEquals(home, WindowState.activeWorldId())
            assertFalse(tab(bg).shownWhileWaiting)
        } finally {
            WindowState.closeTab(bg)
            WindowState.setActiveWorld(home)
            WindowState.closeWorld(other); WindowState.closeTab(visible)
        }
    }

    /** A pane no tab holds shows nothing. */
    @Test
    fun anUnknownPaneShowsNothing() {
        assertFalse(WindowState.showWaitingAgent("no-such-pane"))
    }
}
