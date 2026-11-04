/**
 * Tests for [TabCloseGuard] and [closeTabAsking]: when closing a tab asks, what the question says,
 * and that a person who keeps the tab sends nothing.
 */
package se.soderbjorn.lunamux.client

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig

class TabCloseGuardTest {

    private fun session(
        id: String, state: String, leaf: String? = "leaf-$id", agent: String = "claude", title: String? = "session $id",
        background: Boolean = false, taskId: String? = null, since: Long = 1_000,
    ) = AgentSessionSummary(
        id = id, agent = agent, state = state, stateSince = since, title = title, leafId = leaf,
        background = background, taskId = taskId,
    )

    private fun tab(id: String, title: String, vararg leaves: String, background: Boolean = false) = TabConfig(
        id = id, title = title, isBackground = background,
        panes = leaves.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) },
    )

    private fun config(vararg tabs: TabConfig) = WindowConfig(tabs = tabs.toList(), activeTabId = tabs.first().id)

    private fun task(lead: String?, vararg members: String) = AgentTask(
        id = "g1", title = "Release notes", repo = null, source = "agents", hasOpen = false,
        members = members.mapIndexed { i, id -> AgentTaskMember(key = "#$i", title = id, state = "working", recordId = id) },
        refreshedAtMillis = null, leadRecordId = lead,
    )

    @Test
    fun aTabWithNothingWorkingInItClosesWithoutAsking() {
        val cfg = config(tab("t1", "one", "leaf-a", "leaf-b"), tab("t2", "two", "leaf-c"))
        val sessions = listOf(session("a", "needs_you"), session("b", "idle"), session("c", "working"))
        assertNull(TabCloseGuard.check("t1", cfg, sessions, emptyList()))
        assertNull(TabCloseGuard.check("missing", cfg, sessions, emptyList()))
        assertNull(TabCloseGuard.check("t1", null, sessions, emptyList()))
    }

    @Test
    fun aClosedSessionStillRecordedInTheTabDoesNotAsk() {
        val cfg = config(tab("t1", "one", "leaf-a"), tab("t2", "two", "leaf-c"))
        assertNull(TabCloseGuard.check("t1", cfg, listOf(session("a", "ended")), emptyList()))
    }

    @Test
    fun aWorkingAgentAsksAndTheWordsNameTheTabAndTheAgent() {
        val cfg = config(tab("t1", "Release notes", "leaf-a"), tab("t2", "two", "leaf-c"))
        val check = TabCloseGuard.check("t1", cfg, listOf(session("a", "working")), emptyList())!!
        assertEquals("Close \"Release notes\"?", check.headline)
        assertEquals("Claude is still working in this tab. Closing it stops this conversation.", check.warning)
        assertNull(check.keepsRunningIntro)
        assertNull(check.pickUp)
        assertEquals("Codex is still working in this tab. Closing it stops this conversation.",
            TabCloseGuard.check("t1", cfg, listOf(session("a", "working", agent = "codex")), emptyList())!!.warning)
    }

    @Test
    fun theAgentsItStartedInTheBackgroundAreListedAndTheTaskIsNamedToPickUpFrom() {
        val cfg = config(tab("t1", "one", "leaf-lead"), tab("t2", "two", "leaf-x"), tab("bg", "agents", "leaf-b1", "leaf-b2", "leaf-b3", background = true))
        val sessions = listOf(
            session("lead", "working", taskId = "g1"),
            session("b1", "working", background = true, taskId = "g1", title = "Check the schema", since = 5_000),
            session("b2", "needs_you", background = true, taskId = "g1", title = null),
            session("b3", "ended", background = true, taskId = "g1"),
            session("x", "working", taskId = "g1"),
        )
        val check = TabCloseGuard.check("t1", cfg, sessions, listOf(task("lead", "b2")))!!
        assertEquals(listOf("Check the schema", "Claude"), check.keepsRunning.map { it.title })
        assertEquals(listOf("working", "needs_you"), check.keepsRunning.map { it.state })
        assertEquals(5_000L, check.keepsRunning.first().sinceMillis)
        assertEquals("The 2 agents it started keep running in the background:", check.keepsRunningIntro)
        assertEquals("To pick up where you left off, open the agents overview and choose \"Release notes\".", check.pickUp)
    }

    @Test
    fun oneBackgroundAgentReadsInTheSingular() {
        val cfg = config(tab("t1", "one", "leaf-lead"), tab("bg", "agents", "leaf-b1", background = true))
        val sessions = listOf(session("lead", "working"), session("b1", "idle", background = true, taskId = "g1"))
        val check = TabCloseGuard.check("t1", cfg, sessions, listOf(task("lead")))!!
        assertEquals("The agent it started keeps running in the background:", check.keepsRunningIntro)
    }

    @Test
    fun aTaskThatIsNotThisTabsDoesNotListItsBackgroundAgentsOrNameItsTitle() {
        val cfg = config(tab("t1", "one", "leaf-a"), tab("t2", "two", "leaf-lead"), tab("bg", "agents", "leaf-b1", background = true))
        val sessions = listOf(session("a", "working"), session("lead", "idle"), session("b1", "working", background = true, taskId = "g1"))
        val check = TabCloseGuard.check("t1", cfg, sessions, listOf(task("lead")))!!
        assertTrue(check.keepsRunning.isEmpty())
        assertNull(check.pickUp)
    }

    @Test
    fun theTabIsFoundInAnyWorld() {
        val cfg = WindowConfig(
            worlds = listOf(
                se.soderbjorn.lunamux.WorldConfig("w1", "one", tabs = listOf(tab("t1", "one", "leaf-a"))),
                se.soderbjorn.lunamux.WorldConfig("w2", "two", tabs = listOf(tab("t2", "two", "leaf-b"))),
            ),
            activeWorldId = "w1",
        )
        assertEquals("two", TabCloseGuard.check("t2", cfg, listOf(session("b", "working")), emptyList())?.tabTitle)
    }

    // ---- closeTabAsking ----

    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private fun state(vararg sessions: AgentSessionSummary): WindowStateRepository {
        val repo = WindowStateRepository()
        repo.updateConfig(config(tab("t1", "one", "leaf-a"), tab("t2", "two", "leaf-c")))
        repo.updateAgentSessions(sessions.toList(), true)
        return repo
    }

    @Test
    fun keepingTheTabSendsNothingAndClosingItSendsCloseTab() = runNow {
        val sent = mutableListOf<WindowCommand>()
        val repo = state(session("a", "working"))
        var asked: TabCloseCheck? = null
        assertFalse(closeTabAsking(repo, "t1", { sent += it }) { asked = it; false })
        assertEquals("one", asked?.tabTitle)
        assertTrue(sent.isEmpty())
        assertTrue(closeTabAsking(repo, "t1", { sent += it }) { true })
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseTab("t1")), sent)
    }

    @Test
    fun aTabNothingIsWorkingInClosesWithoutTheQuestion() = runNow {
        val sent = mutableListOf<WindowCommand>()
        val repo = state(session("a", "needs_you"))
        assertTrue(closeTabAsking(repo, "t1", { sent += it }) { error("must not ask") })
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseTab("t1")), sent)
    }
}
