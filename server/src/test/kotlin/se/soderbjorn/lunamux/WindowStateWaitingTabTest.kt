/**
 * Tests for how [WindowState] shows a background tab for its agent, from a window command or
 * directly, and hides it again.
 *
 * Contains [WindowStateWaitingTabTest].
 *
 * @see WindowState.showWaitingAgent
 * @see WindowState.hideSettledWaitingTabs
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
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

    /** Switching to another world leaves a looked-at tab: it hides and the tab they came from is active again. */
    @Test
    fun aTabLeftBySwitchingWorldHidesAndTheTabTheyCameFromReturns() {
        val home = WindowState.addWorld("Here")
        WindowState.addTab(worldId = home)
        val visible = WindowState.addTab(worldId = home)
        val bg = WindowState.addTab(worldId = home, background = true)
        val other = WindowState.addWorld("Elsewhere")
        try {
            WindowState.setActiveWorld(home)
            WindowState.setActiveTab(visible)
            WindowState.showWaitingAgent(leafOf(bg), waiting = false)
            WindowState.setActiveWorld(other)
            assertFalse(tab(bg).isListed)
            WindowState.setActiveWorld(home)
            assertEquals(visible, WindowState.config.value.worlds.first { it.id == home }.activeTabId)
        } finally {
            WindowState.setActiveWorld(home)
            WindowState.closeWorld(other); WindowState.closeWorld(home)
        }
    }

    /** A pane no tab holds shows nothing. */
    @Test
    fun anUnknownPaneShowsNothing() {
        assertFalse(WindowState.showWaitingAgent("no-such-pane"))
    }

    private val dir = File.createTempFile("lunamux-looktab", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val tracker = AgentSessionTracker(repo, gitInfo = { GitInfo() }).also { it.start() }
    private val ctx = WindowConnectionContext(
        send = {}, scope = CoroutineScope(Dispatchers.Unconfined), settingsRepo = repo,
        usageMonitor = ClaudeUsageMonitor(), agentTracker = tracker,
    )

    private fun hook(pane: PaneSnapshot, name: String) = tracker.onHook(pane, buildJsonObject {
        put("hook_event_name", name); put("session_id", "claude-1")
    })

    /** A working agent in background tab [bg]'s pane, and the id of the record that shows it. */
    private fun workingAgentIn(bg: String): Pair<PaneSnapshot, String> {
        val pane = tab(bg).panes.first().leaf.let {
            PaneSnapshot(it.sessionId, leafId = it.id, cwd = null, programTitle = null, background = true)
        }
        hook(pane, "SessionStart"); hook(pane, "UserPromptSubmit")
        return pane to tracker.openRecordOf(pane.terminalSessionId)!!.id
    }

    private fun show(recordId: String) = runBlocking {
        val command: WindowCommand = WindowCommand.ShowWaitingAgent(recordId)
        handleWindowCommand(windowJson.encodeToString(command), ctx)
    }

    /**
     * A window can look at a working agent's tab: it is listed and active while the person is on it,
     * and gone again from the tab strip once they go to another tab, or the agent ends.
     */
    @Test
    fun aWorkingAgentsTabIsShownForALookAndHidesWhenLeftOrEnded() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            WindowState.setActiveTab(visible)
            val (pane, recordId) = workingAgentIn(bg)
            show(recordId)
            assertTrue(tab(bg).isListed)
            assertEquals(bg, WindowState.config.value.activeTabId)
            WindowState.hideSettledWaitingTabsFor(tracker.summaries.value)
            assertTrue(tab(bg).isListed, "a working agent keeps its shown tab while the person is on it")

            WindowState.setActiveTab(visible)
            assertFalse(tab(bg).isListed, "leaving the tab hides it")
            assertEquals(visible, WindowState.config.value.activeTabId)

            val other = WindowState.addTab()
            try {
                show(recordId)
                WindowState.setActiveTab(other)
                assertFalse(tab(bg).isListed)
                assertEquals(other, WindowState.config.value.activeTabId, "leaving for any tab stays there")
            } finally { WindowState.closeTab(other) }

            show(recordId)
            hook(pane, "SessionEnd")
            WindowState.hideSettledWaitingTabsFor(tracker.summaries.value)
            assertFalse(tab(bg).isListed, "an agent that ended leaves nothing in the tab strip")
            assertEquals(visible, WindowState.config.value.activeTabId)
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }

    /** A tab shown because its agent waits stays when the person goes to another tab. */
    @Test
    fun aWaitingAgentsTabStaysWhenTheyLeaveIt() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            WindowState.showWaitingAgent(leafOf(bg))
            WindowState.setActiveTab(visible)
            assertTrue(tab(bg).isListed)
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }

    /** The layout that is saved leaves a shown tab hidden and the person's earlier tab active. */
    @Test
    fun aShownTabDoesNotChangeTheSavedLayout() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            WindowState.setActiveTab(visible)
            WindowState.showWaitingAgent(leafOf(bg), waiting = false)
            val saved = WindowState.forSaving(WindowState.config.value).worlds.flatMap { it.tabs }.first { it.id == bg }
            assertFalse(saved.isListed)
            assertEquals(visible, WindowState.forSaving(WindowState.config.value).activeTabId)
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }
}
