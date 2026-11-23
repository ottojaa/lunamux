/**
 * Tests for the shown-background-tab model of [WindowState]: opening a background tab for the
 * person and returning them, the agents-ended return, what is saved, what a restart reserves,
 * what a phone is sent, and the pane lookups the tracker and resume use.
 *
 * The tests drive the real [WindowState] singleton (with real terminal sessions) and close every
 * tab they open.
 *
 * @see WindowState.show
 * @see WindowState.forSaving
 * @see configForClient
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowStateBackgroundTest {
    private val opened = mutableListOf<String>()

    private fun tabs() = WindowState.config.value.worlds.flatMap { it.tabs }

    /** The newest tab with this id: `rehydrate` resets the id counters, so an older tab another test left behind can share the id. */
    private fun leafOf(tabId: String) = tabs().last { it.id == tabId }.panes.first().leaf.id

    private fun ordinary(): String = WindowState.addTab().also { opened += it }

    private fun background(): String = WindowState.addTab(background = true).also { opened += it }

    private fun clean() {
        WindowState.leaveShownTab()
        opened.asReversed().filter { WindowState.isBackgroundTab(it) }.forEach { WindowState.closeTab(it) }
        opened.asReversed().forEach { WindowState.closeTab(it) }
        opened.clear()
    }

    /**
     * Runs [body] from a state no other test class left behind: [WindowState] is one shared object, and
     * a background tab or a shown tab another class forgot would make this class's ids and refusals depend
     * on the order the classes run in.
     */
    private fun withTabs(body: () -> Unit) {
        WindowState.leaveShownTab()
        tabs().filter { it.isBackground }.map { it.id }.forEach { WindowState.closeTab(it) }
        try { body() } finally { clean() }
    }

    private fun repo() = SettingsRepository(File(File.createTempFile("lunamux-ws", "").apply { delete(); mkdirs(); deleteOnExit() }, "test.db"))

    /** Showing makes the tab visible and active; leaving returns the person to the tab they were on. */
    @Test
    fun `show opens a background tab and leave returns to the tab the person was on`() = withTabs {
        val mine = ordinary()
        val bg = background()
        assertFalse(WindowState.config.value.isVisible(tabs().first { it.id == bg }))
        assertIs<WindowState.ShowResult.Done>(WindowState.show(bg))
        assertEquals(bg, WindowState.config.value.shownTabId)
        assertEquals(bg, WindowState.config.value.activeTabId)
        assertTrue(WindowState.config.value.isVisible(tabs().first { it.id == bg }))
        WindowState.leaveShownTab()
        assertNull(WindowState.config.value.shownTabId)
        assertEquals(mine, WindowState.config.value.activeTabId)
    }

    /** A show for a tab that is gone, is not background, or holds no open agent is answered with a refusal. */
    @Test
    fun `show refuses what it cannot open`() = withTabs {
        val mine = ordinary()
        val bg = background()
        assertIs<WindowState.ShowResult.Refused>(WindowState.show("t-none"))
        assertIs<WindowState.ShowResult.Refused>(WindowState.show(mine))
        assertIs<WindowState.ShowResult.Refused>(WindowState.show(bg, liveLeafIds = setOf("n-other")))
        assertNull(WindowState.config.value.shownTabId)
    }

    /** Showing a second tab keeps the first return point, so leave goes back to where the person began. */
    @Test
    fun `a second show keeps the first return point`() = withTabs {
        val mine = ordinary()
        val one = background()
        val two = background()
        WindowState.show(one)
        WindowState.show(two)
        assertEquals(two, WindowState.config.value.shownTabId)
        assertFalse(WindowState.config.value.isVisible(tabs().first { it.id == one }))
        WindowState.leaveShownTab()
        assertEquals(mine, WindowState.config.value.activeTabId)
    }

    /** Moving to any other tab closes the shown one; it does not stay open behind the person. */
    @Test
    fun `choosing another tab or adding one closes the shown tab`() = withTabs {
        val mine = ordinary()
        val other = ordinary()
        val bg = background()
        WindowState.show(bg)
        WindowState.setActiveTab(mine)
        assertNull(WindowState.config.value.shownTabId)
        assertEquals(mine, WindowState.config.value.activeTabId)
        WindowState.show(bg)
        ordinary()
        assertNull(WindowState.config.value.shownTabId)
        assertTrue(other.isNotEmpty())
    }

    /** When no agent is left in the shown tab the person is sent back; while one is, they stay. */
    @Test
    fun `the shown tab closes when its agents are gone`() = withTabs {
        val mine = ordinary()
        val bg = background()
        WindowState.show(bg)
        WindowState.onLiveLeaves(setOf(leafOf(bg)))
        assertEquals(bg, WindowState.config.value.shownTabId)
        WindowState.onLiveLeaves(emptySet())
        assertNull(WindowState.config.value.shownTabId)
        assertEquals(mine, WindowState.config.value.activeTabId)
    }

    /** What is written never holds a background tab, and the person's own tab is the active one. */
    @Test
    fun `forSaving drops background tabs and the open one`() = withTabs {
        val mine = ordinary()
        val bg = background()
        WindowState.show(bg)
        val saved = WindowState.forSaving(WindowState.config.value)
        assertNull(saved.shownTabId)
        assertTrue(saved.worlds.flatMap { it.tabs }.none { it.isBackground })
        assertEquals(mine, saved.activeTabId)
        assertTrue(saved.worlds.flatMap { it.tabs }.flatMap { it.panes }.all { it.leaf.sessionId.isEmpty() })
    }

    /** Focus follows the shown tab: a background pane takes focus only while its tab is shown. */
    @Test
    fun `a pane in a background tab can take focus only while its tab is shown`() = withTabs {
        ordinary()
        val bg = background()
        assertTrue(WindowState.isBackgroundLeaf(leafOf(bg)))
        assertFalse(WindowState.focusPane(leafOf(bg)), "focus never opens a background tab")
        WindowState.show(bg)
        assertTrue(WindowState.focusPane(leafOf(bg)))
    }

    /**
     * `inTab` is the Resume rule: a closed conversation's pane is in a visible tab and at an idle shell.
     * A pane that now runs a program (after `/clear` it runs the next conversation) reads false, an
     * idle shell reads true, an open record is never `inTab`, and a pane in an unshown background tab
     * reads false until the tab is shown.
     */
    @Test
    fun `inTab means a closed record's pane sits at an idle shell in a visible tab`() = withTabs {
        val mine = ordinary()
        val bg = background()
        fun closed(tab: String) = AgentSessionRecord(
            id = "r", agent = "claude", leafId = leafOf(tab), state = AgentSessionState.ENDED,
        )
        val idle = ResumeProbe(isIdleShell = { true })
        val busy = ResumeProbe(isIdleShell = { false })
        assertTrue(WindowState.paneToResumeIn(idle)(closed(mine)), "idle shell in a visible tab")
        assertFalse(WindowState.paneToResumeIn(busy)(closed(mine)), "the pane runs a program")
        assertFalse(
            WindowState.paneToResumeIn(idle)(closed(mine).copy(state = AgentSessionState.DONE)),
            "an open record is not resumed anywhere",
        )
        assertFalse(WindowState.paneToResumeIn(idle)(closed(bg)), "its tab is not shown")
        WindowState.show(bg)
        assertTrue(WindowState.paneToResumeIn(idle)(closed(bg)))
    }

    /**
     * Decided fix: a show for an agent started a moment ago finds its tab in the layout and opens
     * it at once, and an unknown pane is answered, not ignored.
     */
    @Test
    fun `a fresh background agent can be shown at once`() = withTabs {
        ordinary()
        val bg = background()
        val leaf = leafOf(bg)
        assertIs<WindowState.ShowResult.Done>(WindowState.showLeaf(leaf, liveLeafIds = setOf(leaf)))
        assertEquals(bg, WindowState.config.value.shownTabId)
        WindowState.leaveShownTab()
        assertIs<WindowState.ShowResult.Refused>(WindowState.showLeaf("n-nope", liveLeafIds = setOf("n-nope")))
    }

    /**
     * Decided fix: a restart never hands a new pane the id of a pane a closed record remembers.
     */
    @Test
    fun `a closed record's pane id is not reused after a restart`() = withTabs {
        val repo = repo()
        repo.saveAgentSession(
            AgentSessionRecord(id = "r1", agent = "claude", leafId = "n9000", state = AgentSessionState.ENDED),
        )
        WindowState.rehydrate(WindowConfig(), repo)
        val tab = ordinary()
        val number = leafOf(tab).removePrefix("n").substringBefore('-').toLong()
        assertTrue(number > 9000, "new pane id was ${leafOf(tab)}")
    }

    /** A background tab found in an older saved layout is dropped, yet its pane id stays taken. */
    @Test
    fun `a dropped background tab keeps its ids reserved`() = withTabs {
        val old = WindowConfig(
            worlds = listOf(
                WorldConfig(
                    id = "w1", name = "Home",
                    tabs = listOf(
                        TabConfig(id = "t1", title = "Tab 1"),
                        TabConfig(
                            id = "t7", title = "bg", isBackground = true,
                            panes = listOf(Pane(leaf = LeafNode(id = "n8000", sessionId = "", title = "x"), x = 0.0, y = 0.0, width = 1.0, height = 1.0, z = 1L)),
                        ),
                    ),
                    activeTabId = "t1",
                ),
            ),
            activeWorldId = "w1",
        )
        val live = WindowState.rehydrate(old, repo())
        assertTrue(live.worlds.single().tabs.none { it.isBackground })
        val tab = ordinary()
        assertTrue(leafOf(tab).removePrefix("n").substringBefore('-').toLong() > 8000)
        assertTrue(tab.removePrefix("t").substringBefore('-').toLong() > 7)
        Unit
    }

    /**
     * Decided fix: a phone is never sent an unshown background tab, in the first push or in a
     * later one after the layout changed; the app with the overview (and the shown tab) still is.
     */
    @Test
    fun `a phone is not sent unshown background tabs in any push`() = withTabs {
        ordinary()
        val bg = background()
        fun sent(type: String, cfg: WindowConfig = WindowState.config.value, browser: Boolean = true) =
            configForClient(cfg, type, supportsWorlds = true, supportsAgentPanes = true, supportsWebBrowser = browser)
                .worlds.flatMap { it.tabs }.map { it.id }
        assertFalse(bg in sent("Android"))
        assertFalse(bg in sent("iOS", browser = false), "the browser strip does not skip the background strip")
        assertTrue(bg in sent("Web"))
        WindowState.show(bg)
        assertTrue(bg in sent("Android"), "the shown tab is the person's own")
        WindowState.leaveShownTab()
        assertFalse(bg in sent("Android"), "a later push strips it again")
        val later = background()
        assertFalse(later in sent("Android"))
        assertFalse(later in sent("Android", browser = false))
        assertEquals(sent("Android"), WindowState.config.value.withoutUnshownBackground().worlds.flatMap { it.tabs }.map { it.id })
        assertFalse(bg in WindowState.config.value.withoutUnshownBackground().tabs.map { it.id }, "the legacy mirror is stripped too")
    }
}
