/**
 * Tests for background tabs in [TabManager] and [WindowConfig.isVisible]: a background tab is
 * invisible until it is the shown one, never becomes the active tab through the ordinary
 * routes, is never picked as the tab that takes over when the active one closes, and keeps its
 * place when the person reorders the tabs they can see.
 *
 * @see TabManager
 * @see TabConfig.isBackground
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabManagerBackgroundTest {
    private fun tab(id: String, background: Boolean = false, hidden: Boolean = false) =
        TabConfig(id = id, title = id, isBackground = background, isHidden = hidden)

    private fun config(vararg tabs: TabConfig, active: String? = tabs.firstOrNull { !it.isBackground }?.id) =
        WindowConfig(tabs = tabs.toList(), activeTabId = active)

    /** An unshown background tab is not visible; the shown one is; ordinary tabs always are. */
    @Test
    fun `only the shown background tab is visible`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("h", hidden = true))
        assertEquals(listOf("a", "h"), cfg.visibleTabs.map { it.id })
        val shown = cfg.copy(shownTabId = "bg")
        assertEquals(listOf("a", "bg", "h"), shown.visibleTabs.map { it.id })
    }

    /** A background add leaves the active tab alone, takes no tab number, and carries its start folder. */
    @Test
    fun `a new background tab is added but never activated`() {
        val cfg = config(tab("a"))
        val next = TabManager.addTab(cfg, "bg", "n1", "s1", background = true, initialCwd = "/work")
        assertEquals("a", next.activeTabId)
        assertTrue(next.tabs.single { it.id == "bg" }.isBackground)
        assertEquals("/work", next.tabs.single { it.id == "bg" }.panes.single().leaf.cwd)
        val normal = TabManager.addTab(cfg, "t2", "n2", "s2")
        assertEquals("t2", normal.activeTabId)
        assertFalse(normal.tabs.single { it.id == "t2" }.isBackground)
        val withBackground = TabManager.addTab(config(tab("a"), tab("bg", background = true)), "t3", "n3", "s3")
        assertEquals("Tab 2", withBackground.tabs.single { it.id == "t3" }.title, "a background tab takes no number")
    }

    /** The ordinary route to a tab refuses a background one; only the shown-tab path opens it. */
    @Test
    fun `a background tab cannot be made the active tab`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        assertNull(TabManager.setActiveTab(cfg, "bg"))
    }

    /** The tab that takes over after a close is the next ordinary one, in either direction. */
    @Test
    fun `closing the active tab never lands on a background tab`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("b"), active = "a")
        assertEquals("b", TabManager.closeTab(cfg, "a")!!.activeTabId)
        val backwards = config(tab("a"), tab("bg", background = true), tab("b"), active = "b")
        assertEquals("a", TabManager.closeTab(backwards, "b")!!.activeTabId)
    }

    /** The person must always keep one tab: a background tab does not count as it. */
    @Test
    fun `the last ordinary tab cannot be closed while a background tab remains`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        assertNull(TabManager.closeTab(cfg, "a"))
        val closed = TabManager.closeTab(cfg, "bg")!!
        assertEquals(listOf("a"), closed.tabs.map { it.id })
        assertEquals("a", closed.activeTabId)
    }

    /** Reordering the tabs the person sees does not move the hidden one. */
    @Test
    fun `reordering visible tabs leaves a background tab where it was`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("b"), tab("c"))
        val moved = TabManager.moveTab(cfg, "c", "a", before = true)!!
        assertEquals(listOf("c", "a", "bg", "b"), moved.tabs.map { it.id })
        assertTrue(moved.tabs.single { it.id == "bg" }.isBackground)
    }
}
