/**
 * Tests for [AgentsOverviewBackingViewModel]: how the server's session list
 * becomes NOW and EARLIER rows, the chip counts, the title chain, the
 * title-only search, the keyboard selection, and the commands that jump and
 * resume send.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WorldConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AgentsOverviewBackingViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once) and rethrows its failure. */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val vm = AgentsOverviewBackingViewModel(windowState) { sent += it }

    private fun session(
        id: String, state: String, since: Long = 1_000, title: String? = null, agent: String = "claude",
        leaf: String? = "leaf-$id", endedAt: Long? = null, branch: String? = null, cwd: String? = "/work/proj",
    ) = AgentSessionSummary(
        id = id, agent = agent, state = state, stateSince = since, title = title, links = emptyList(),
        repo = "proj", branch = branch, cwd = cwd, nowLine = null, question = null, leafId = leaf, endedAt = endedAt,
    )

    private fun leaf(id: String, custom: String? = null) =
        Pane(LeafNode(id = id, sessionId = "s-$id", title = id, customName = custom), 0.0, 0.0, 1.0, 1.0, 0)

    private fun layout(vararg leaves: Pane, world: String? = null) {
        val tab = TabConfig(id = "tab-1", title = "one", panes = leaves.toList())
        windowState.updateConfig(
            if (world == null) WindowConfig(tabs = listOf(tab), activeTabId = "tab-1")
            else WindowConfig(
                worlds = listOf(
                    WorldConfig("w1", "one", tabs = listOf(TabConfig("other", "other"))),
                    WorldConfig("w2", "two", tabs = listOf(tab)),
                ),
                activeWorldId = "w1",
            ),
        )
    }

    @Test
    fun nowListsNeedsYouFirstThenWorkingLongestWaitingFirst() {
        windowState.updateAgentSessions(
            listOf(
                session("w-new", "working", since = 5_000),
                session("n-new", "needs_you", since = 4_000),
                session("w-old", "working", since = 1_000),
                session("n-old", "needs_you", since = 2_000),
            ),
            claudeConnected = true,
        )
        assertEquals(listOf("n-old", "n-new", "w-old", "w-new"), vm.state.now.map { it.id })
    }

    @Test
    fun earlierHoldsIdleAndEndedNewestFirstAndCutsAtFiveUntilMore() {
        val rows = (1..7).map { session("e$it", if (it % 2 == 0) "idle" else "ended", since = it * 1_000L, endedAt = it * 1_000L) }
        windowState.updateAgentSessions(rows, false)
        assertEquals(listOf("e7", "e6", "e5", "e4", "e3"), vm.state.earlier.map { it.id })
        assertEquals(2, vm.state.earlierHidden)
        vm.showMoreEarlier()
        assertEquals(7, vm.state.earlier.size)
        assertEquals(0, vm.state.earlierHidden)
    }

    @Test
    fun chipCountsEqualTheNowSection() {
        windowState.updateAgentSessions(
            listOf(session("a", "needs_you"), session("b", "working"), session("c", "working"), session("d", "idle")),
            true,
        )
        val s = vm.state
        assertEquals(1, s.needYouCount)
        assertEquals(2, s.workingCount)
        assertEquals(s.now.size, s.needYouCount + s.workingCount)
        assertTrue(s.chipVisible)
    }

    @Test
    fun chipHidesWhenNothingNeedsYouOrWorks() {
        windowState.updateAgentSessions(listOf(session("d", "idle"), session("e", "ended", endedAt = 5)), true)
        assertFalse(vm.state.chipVisible)
    }

    @Test
    fun titleIsCustomNameThenAgentTitleThenBranchThenFolder() {
        layout(leaf("leaf-a", custom = "My name"), leaf("leaf-b"), leaf("leaf-c"), leaf("leaf-d"))
        windowState.updateAgentSessions(
            listOf(
                session("a", "working", title = "Agent title"),
                session("b", "working", title = "Agent title"),
                session("c", "working", branch = "feature/ABC-12-x"),
                session("d", "working"),
            ),
            true,
        )
        val titles = vm.state.now.associate { it.id to it.title }
        assertEquals("My name", titles["a"])
        assertEquals("Agent title", titles["b"])
        assertEquals("feature/ABC-12-x", titles["c"])
        assertEquals("proj", titles["d"])
    }

    @Test
    fun searchMatchesTitlesOnlyAndIgnoresCase() {
        windowState.updateAgentSessions(
            listOf(session("a", "working", title = "Fix Flaky Test"), session("b", "working", title = "Refactor", branch = "flaky")),
            true,
        )
        vm.setQuery("flaky")
        assertEquals(listOf("a"), vm.state.rows.map { it.id })
        // The chip is not narrowed by the search.
        assertEquals(2, vm.state.workingCount)
    }

    @Test
    fun selectionStartsAtTheTopStopsAtTheEndsAndResetsOnTyping() {
        windowState.updateAgentSessions(listOf(session("a", "working"), session("b", "working", since = 2_000)), true)
        vm.open()
        assertEquals(0, vm.state.selected)
        vm.moveSelection(-1)
        assertEquals(0, vm.state.selected)
        vm.moveSelection(1)
        vm.moveSelection(1)
        assertEquals(1, vm.state.selected)
        vm.setQuery("")
        assertEquals(0, vm.state.selected)
    }

    @Test
    fun emptyListHasNoSelection() {
        vm.open()
        assertEquals(-1, vm.state.selected)
    }

    @Test
    fun enterOnALiveRowFocusesItsPaneAndClosesTheOverlay() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "needs_you")), true)
        vm.open()
        vm.activateSelected()
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-a")), sent)
        assertFalse(vm.state.open)
    }

    @Test
    fun jumpingIntoAnotherWorldSwitchesWorldFirst() = runNow {
        layout(leaf("leaf-a"), world = "w2")
        windowState.updateAgentSessions(listOf(session("a", "working")), true)
        vm.activateSelected()
        assertEquals(
            listOf(
                WindowCommand.SetActiveWorld("w2"),
                WindowCommand.SetActiveTab("tab-1"),
                WindowCommand.SetFocusedPane("tab-1", "leaf-a"),
            ),
            sent,
        )
    }

    @Test
    fun enterOnAnEndedRowResumesAndKeepsTheOverlayUntilTheNewTabIsActive() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        assertEquals("Resume", vm.state.rows.single().actionLabel)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("old")), sent)
        assertTrue(vm.state.open)

        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-2"))
        assertFalse(vm.state.open)
    }

    @Test
    fun returningToTheTabAResumeStartedFromDoesNotReopenTheOverlay() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        vm.activateSelected()
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-2"))
        assertFalse(vm.state.open)

        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-1"))
        assertFalse(vm.state.open)
    }

    @Test
    fun aFailedResumeShowsItsMessageOnTheRow() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        vm.activateSelected()
        windowState.setAgentSessionError("folder no longer exists")
        assertEquals("folder no longer exists", vm.state.rows.single().error)
        assertTrue(vm.state.open)
        vm.setQuery("")
        assertNull(vm.state.rows.single().error)
    }

    @Test
    fun geminiOffersOpenFolderInsteadOfResume() {
        windowState.updateAgentSessions(listOf(session("g", "ended", agent = "gemini", leaf = null, endedAt = 3)), true)
        assertEquals("Open folder", vm.state.rows.single().actionLabel)
    }
}
