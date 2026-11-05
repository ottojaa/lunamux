/**
 * Tests for [WatchViewModel]: what opening a watch shows (the session's name, terminal and task),
 * which sessions can be watched at all, the words of its header, what showing the session as a tab
 * sends (and that it sends nothing else), and that a watch belongs to the tab it was opened in and
 * is dropped, not just hidden, when that tab or the session goes.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [WatchViewModel]: opening, the header's words, showing a tab, and the watch's scope.
 *
 * Run by `:client:jvmTest` and `:client:jsTest`.
 *
 * @see WatchViewModel
 */
class WatchViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once). */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val watch = WatchViewModel(windowState) { sent += it }

    private fun session(
        id: String, state: String = "working", leaf: String? = "leaf-$id", taskId: String? = null,
        background: Boolean = false, title: String = "agent $id",
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = 1, title = title, cwd = "/work/x", repo = "x",
        leafId = leaf, taskId = taskId, background = background, startedAt = 1,
    )

    private fun pane(leafId: String) = Pane(LeafNode(id = leafId, sessionId = "pty-$leafId", title = leafId), 0.0, 0.0, 1.0, 1.0, 0)

    /** tab-1 and tab-2 are ordinary tabs; tab-bg is a background tab holding leaf-bg. */
    private fun layout(active: String = "tab-1") {
        windowState.updateConfig(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = listOf(pane("leaf-own"))),
                    TabConfig(id = "tab-2", title = "two", panes = listOf(pane("leaf-w"))),
                    TabConfig(id = "tab-bg", title = "bg", panes = listOf(pane("leaf-bg")), isBackground = true),
                ),
                activeTabId = active,
            ),
        )
    }

    private fun give(sessions: List<AgentSessionSummary>, tasks: List<AgentTask> = emptyList()) {
        windowState.updateAgentTasks(tasks)
        windowState.updateAgentSessions(sessions, true)
    }

    @Test
    fun `nothing is watched until a watch is opened`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        assertNull(watch.state)
    }

    @Test
    fun `watching a background session shows its name, terminal and task over the active tab`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg", title = "Writer", taskId = "t1")))
        watch.watch("bg1")
        assertEquals(WatchViewModel.Watch("bg1", "t1", "tab-1", "Writer", "pty-leaf-bg", true), watch.state)
        assertTrue(sent.isEmpty(), "watching sends nothing")
    }

    @Test
    fun `a session without a task id belongs to the task that lists it or whose own session it is`() {
        layout()
        val member = AgentTaskMember(key = "m", title = "Writer", state = "working", sinceMillis = 1, recordId = "bg1")
        give(
            listOf(session("bg1", background = true, leaf = "leaf-bg"), session("bg2", background = true, leaf = "leaf-bg")),
            listOf(
                AgentTask(id = "listing", title = "Listing", members = listOf(member), startedAtMillis = 1),
                AgentTask(id = "owning", title = "Owning", leadRecordId = "bg2", startedAtMillis = 2),
            ),
        )
        watch.watch("bg1")
        assertEquals("listing", watch.state?.taskId)
        watch.watch("bg2")
        assertEquals("owning", watch.state?.taskId)
    }

    @Test
    fun `a session in no task is still watched, with no task`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        watch.watch("bg1")
        assertNull(watch.state?.taskId)
        assertEquals("bg1", watch.state?.recordId)
    }

    @Test
    fun `an ended session, a session in no pane and an unknown record open nothing`() {
        layout()
        give(
            listOf(
                session("gone", state = "ended", leaf = null, background = true),
                session("ended-in-pane", state = "ended", leaf = "leaf-bg", background = true),
                session("nopane", leaf = null, background = true),
            ),
        )
        listOf("gone", "ended-in-pane", "nopane", "unknown").forEach {
            watch.watch(it)
            assertNull(watch.state, it)
        }
    }

    @Test
    fun `the header names show as tab while that works, and only read-only when it does not`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg"), session("w1", leaf = "leaf-w")))
        watch.watch("bg1")
        assertEquals("Watching · read-only · ⌘↵ show as tab", watch.state?.header)
        watch.watch("w1")
        assertEquals(false, watch.state?.canShowAsTab)
        assertEquals("Watching · read-only", watch.state?.header)
    }

    @Test
    fun `the words people read are fixed`() {
        assertEquals("Watching · read-only · ⌘↵ show as tab", WatchViewModel.HEADER)
        assertEquals("↵ watch · ⌘↵ show as tab", WatchViewModel.LINE_HINT)
    }

    @Test
    fun `show as tab turns the background tab into an ordinary one, focuses its pane and closes the watch`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg", taskId = "t1")))
        watch.watch("bg1")
        runNow { watch.showAsTab() }
        assertEquals(listOf(WindowCommand.TakeOverTab("tab-bg"), WindowCommand.SetFocusedPane("tab-bg", "leaf-bg")), sent)
        assertNull(watch.state)
    }

    @Test
    fun `show as tab does nothing without a watch, or for a session that is not in a background tab`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg"), session("w1", leaf = "leaf-w")))
        runNow { watch.showAsTab() }
        watch.watch("w1")
        runNow { watch.showAsTab() }
        assertTrue(sent.isEmpty())
        assertEquals("w1", watch.state?.recordId, "the watch stays open: esc still goes back")
    }

    @Test
    fun `close takes the watch down`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        watch.watch("bg1")
        watch.close()
        assertNull(watch.state)
    }

    @Test
    fun `a watch is forgotten when its tab stops being the active one, so it never comes back with the tab`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        watch.watch("bg1")
        assertEquals("bg1", watch.state?.recordId)
        layout(active = "tab-2")
        assertNull(watch.state, "another tab has no watch over it")
        layout(active = "tab-1")
        assertNull(watch.state, "coming back must not bring the watch back")
    }

    @Test
    fun `a watch is forgotten when the session ends or leaves its pane`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        watch.watch("bg1")
        give(listOf(session("bg1", state = "ended", background = true, leaf = "leaf-bg")))
        assertNull(watch.state)
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        assertNull(watch.state, "a session that runs again is not watched again by itself")

        watch.watch("bg1")
        give(listOf(session("bg1", background = true, leaf = null)))
        assertNull(watch.state)
    }

    @Test
    fun `a record shown as a tab straight from a list never opens a watch, not even for an instant`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg")))
        val seen = mutableListOf<WatchViewModel.Watch?>()
        @OptIn(DelicateCoroutinesApi::class)
        val collector = GlobalScope.launch(Dispatchers.Unconfined) { watch.stateFlow.collect { seen += it } }
        runNow { watch.showRecordAsTab("bg1") }
        collector.cancel()
        assertEquals(listOf(WindowCommand.TakeOverTab("tab-bg"), WindowCommand.SetFocusedPane("tab-bg", "leaf-bg")), sent)
        assertTrue(seen.isNotEmpty() && seen.all { it == null }, "every state the view saw was no watch: $seen")
    }

    @Test
    fun `showing a record as a tab does nothing for an ended session, one in an ordinary tab or an unknown record`() {
        layout()
        give(
            listOf(
                session("done", state = "ended", background = true, leaf = "leaf-bg"),
                session("w1", leaf = "leaf-w"),
            ),
        )
        runNow {
            watch.showRecordAsTab("done")
            watch.showRecordAsTab("w1")
            watch.showRecordAsTab("nobody")
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a stale build that showed nothing for an older watch does not close the watch opened since`() {
        layout()
        give(listOf(session("bg1", background = true, leaf = "leaf-bg"), session("bg2", background = true, leaf = "leaf-bg")))
        watch.watch("bg2")
        // A build made while no watch (or the watch on bg1) was open finishes late and shows nothing.
        watch.forgetStale(null, null)
        watch.forgetStale(WatchViewModel.Scope("bg1", "tab-1"), null)
        assertEquals("bg2", watch.state?.recordId, "the newer watch survives")
        watch.forgetStale(WatchViewModel.Scope("bg2", "tab-1"), null)
        assertNull(watch.state, "the scope that showed nothing is the one forgotten")
    }
}
