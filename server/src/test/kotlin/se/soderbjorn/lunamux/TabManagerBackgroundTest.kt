/**
 * Tests for background tabs in [TabManager] and [WindowConfig.listedTabs]: a background tab is
 * never listed, never becomes the active tab except by being taken over, is never picked as the
 * tab that takes over when the active one closes, and keeps its place when the person reorders
 * the tabs they can see.
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

    private fun config(vararg tabs: TabConfig, active: String? = tabs.firstOrNull { it.isListed }?.id) =
        WindowConfig(tabs = tabs.toList(), activeTabId = active)

    @Test
    fun `listedTabs leaves out background tabs and keeps the person's hidden ones`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("h", hidden = true))
        assertEquals(listOf("a", "h"), cfg.listedTabs.map { it.id })
        assertEquals(listOf("a", "bg", "h"), cfg.effectiveTabs.map { it.id })
    }

    @Test
    fun `a new background tab is added but never activated`() {
        val cfg = config(tab("a"))
        val next = TabManager.addTab(cfg, "bg", "n1", "s1", background = true)
        assertEquals("a", next.activeTabId)
        assertTrue(next.tabs.single { it.id == "bg" }.isBackground)
        val normal = TabManager.addTab(cfg, "t2", "n2", "s2")
        assertEquals("t2", normal.activeTabId)
        assertFalse(normal.tabs.single { it.id == "t2" }.isBackground)
    }

    @Test
    fun `a background tab cannot be made the active tab`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        assertNull(TabManager.setActiveTab(cfg, "bg"))
    }

    @Test
    fun `closing the active tab never lands on a background tab`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("b"), active = "a")
        assertEquals("b", TabManager.closeTab(cfg, "a")!!.activeTabId)
        val backwards = config(tab("a"), tab("bg", background = true), tab("b"), active = "b")
        assertEquals("a", TabManager.closeTab(backwards, "b")!!.activeTabId)
    }

    @Test
    fun `the last listed tab cannot be closed while a background tab remains`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        assertNull(TabManager.closeTab(cfg, "a"))
        val closed = TabManager.closeTab(cfg, "bg")!!
        assertEquals(listOf("a"), closed.tabs.map { it.id })
        assertEquals("a", closed.activeTabId)
    }

    @Test
    fun `showing a waiting background tab lists it, activates it and keeps it a background tab`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        val shown = TabManager.showWaitingTab(cfg, "bg")!!
        assertEquals("bg", shown.activeTabId)
        assertTrue(shown.tabs.single { it.id == "bg" }.isBackground, "still a background tab")
        assertTrue(shown.tabs.single { it.id == "bg" }.shownWhileWaiting)
        assertEquals(listOf("a", "bg"), shown.listedTabs.map { it.id })
        assertNull(TabManager.showWaitingTab(cfg, "missing"))
        assertNull(TabManager.showWaitingTab(shown, "bg"), "already shown and active")
    }

    @Test
    fun `hiding a settled waiting tab unlists it and returns to the tab that was active`() {
        val cfg = config(tab("a"), tab("b"), tab("bg", background = true))
        val shown = TabManager.showWaitingTab(cfg, "bg")!!
        val hidden = TabManager.hideWaitingTab(shown, "bg", returnTo = "b")!!
        assertEquals("b", hidden.activeTabId)
        assertEquals(listOf("a", "b"), hidden.listedTabs.map { it.id })
        assertFalse(hidden.tabs.single { it.id == "bg" }.shownWhileWaiting)
        assertNull(TabManager.hideWaitingTab(hidden, "bg", "b"), "nothing to hide")
        assertEquals("a", TabManager.hideWaitingTab(shown, "bg", returnTo = "gone")!!.activeTabId, "falls back to the first listed tab")
    }

    @Test
    fun `hiding a waiting tab the person is not on leaves the active tab alone`() {
        val cfg = config(tab("a"), tab("bg", background = true))
        val shown = TabManager.showWaitingTab(cfg, "bg")!!.copy(activeTabId = "a")
        assertEquals("a", TabManager.hideWaitingTab(shown, "bg", returnTo = "a")!!.activeTabId)
    }

    @Test
    fun `reordering visible tabs leaves a background tab where it was`() {
        val cfg = config(tab("a"), tab("bg", background = true), tab("b"), tab("c"))
        val moved = TabManager.moveTab(cfg, "c", "a", before = true)!!
        assertEquals(listOf("c", "a", "bg", "b"), moved.tabs.map { it.id })
        assertTrue(moved.tabs.single { it.id == "bg" }.isBackground)
    }
}
