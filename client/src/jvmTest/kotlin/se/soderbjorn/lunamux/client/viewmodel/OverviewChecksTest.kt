/**
 * Tests for [OverviewChecks]: each check passes on the state the view models produce for the usual
 * case and fails, naming the problem, on state made to break the rule it holds.
 *
 * Contains [OverviewChecksTest].
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.OverviewChecks
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OverviewChecksTest {
    private val windowState = WindowStateRepository()
    private val now = 10_000_000L
    private val overviewVm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { }
    private val panelVm = TaskPanelViewModel(windowState, clock = { now }) { }

    private fun panes(ids: List<String>) = ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }

    private fun conversation(id: String, state: String, taskId: String? = null, background: Boolean = false) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = 1_000, title = "conversation $id", repo = "proj", cwd = "/work/proj",
        leafId = "leaf-$id", lastActivityAt = now - 1_000, taskId = taskId, background = background,
    )

    /** A task whose main conversation works in a listed tab and whose two agents work in a background tab. */
    private fun usualCase(bgState: String = "working") {
        windowState.updateConfig(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-main", "leaf-solo"))),
                    TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-b1", "leaf-b2")), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
        windowState.updateAgentSessions(
            listOf(
                conversation("main", "working", taskId = "g1"),
                conversation("b1", bgState, taskId = "g1", background = true),
                conversation("b2", "working", taskId = "g1", background = true),
                conversation("solo", "done"),
            ),
            true,
        )
        windowState.updateAgentTasks(
            listOf(AgentTask(id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, mainConversation = "main")),
        )
    }

    /** What the screens show now; the footer defaults to the counts the overview itself shows. */
    private fun snapshot(
        footerWaiting: Int = overviewVm.state.needYouCount,
        footerWorking: Int = overviewVm.state.workingCount,
        expectedMain: Map<String, String> = emptyMap(),
    ) = OverviewChecks.Snapshot(
        sessions = windowState.agentSessions.value,
        tasks = windowState.agentTasks.value,
        config = windowState.config.value!!,
        overview = overviewVm.state,
        panel = panelVm.state,
        footerWaiting = footerWaiting,
        footerWorking = footerWorking,
        expectedMain = expectedMain,
    )

    private fun check(id: String, s: OverviewChecks.Snapshot) = OverviewChecks.evaluate(s).first { it.id == id }

    private fun withTaskRow(s: OverviewChecks.Snapshot, edit: (AgentsOverviewBackingViewModel.Row) -> AgentsOverviewBackingViewModel.Row) =
        s.copy(overview = s.overview.copy(now = s.overview.now.map { if (it.taskId == "g1") edit(it) else it }))

    @Test
    fun theUsualCasePassesEveryCheck() {
        usualCase()
        val checks = OverviewChecks.evaluate(snapshot())
        assertEquals(emptyList(), checks.filter { !it.ok }.map { it.id }, OverviewChecks.render(checks))
    }

    @Test
    fun aFooterThatCountsWrongFailsEvenWhenTheRowsAgreeWithEachOther() {
        usualCase(bgState = "needs_you")
        val c1 = check("C1", snapshot(footerWaiting = 2, footerWorking = 0))
        assertFalse(c1.ok)
        assertTrue(c1.problems.any { it.contains("the footer counts 2 waiting, the sessions have 1") })
        assertTrue(c1.problems.any { it.contains("the footer counts 0 working, the sessions have 2") })
    }

    @Test
    fun aBackgroundAgentWithARowOfItsOwnFails() {
        usualCase()
        val s = snapshot()
        val ownRow = s.overview.now.first { it.recordId == "solo" }.copy(id = "b1", recordId = "b1", title = "b1")
        val c2 = check("C2", s.copy(overview = s.overview.copy(now = s.overview.now + ownRow)))
        assertFalse(c2.ok)
        assertTrue(c2.problems.any { it.contains("background agent") })
    }

    @Test
    fun aConversationOnTwoRowsFails() {
        usualCase()
        val s = snapshot()
        val twice = s.copy(overview = s.overview.copy(recent = listOf(s.overview.now.first { it.recordId == "solo" })))
        assertFalse(check("C3", twice).ok)
    }

    @Test
    fun returnOnATaskMustReachItsMainConversation() {
        usualCase()
        val s = snapshot()
        assertTrue(check("C4", s).ok)
        val toHidden = check("C4", withTaskRow(s) { it.copy(recordId = "b2") })
        assertFalse(toHidden.ok)
        assertTrue(toHidden.problems.single().contains("not listed"), "a jump into a hidden tab shows nothing")
        val toForeign = check("C4", withTaskRow(s) { it.copy(recordId = "solo") })
        assertTrue(toForeign.problems.single().contains("not a conversation of the task"))
        val dead = check("C4", withTaskRow(s) { it.copy(enter = Enter.NONE) })
        assertTrue(dead.problems.single().contains("does nothing"))
    }

    @Test
    fun theMainConversationMustBeTheOneTheRunExpects() {
        usualCase()
        assertTrue(check("C4", snapshot(expectedMain = mapOf("g1" to "ma"))).ok)
        assertFalse(check("C4", snapshot(expectedMain = mapOf("g1" to "b7714ac5"))).ok)
    }

    @Test
    fun aWaitingBackgroundAgentNeedsItsTabShownNotAJump() {
        usualCase(bgState = "needs_you")
        val s = snapshot()
        assertEquals(Enter.SHOW_WAITING, s.overview.now.single { it.taskId == "g1" }.enter)
        assertTrue(check("C5", s).ok)
        val jumping = check("C5", withTaskRow(s) { it.copy(enter = Enter.JUMP) })
        assertFalse(jumping.ok)
        val wrongTarget = check("C5", withTaskRow(s) { it.copy(recordId = "main", enter = Enter.SHOW_WAITING) })
        assertTrue(wrongTarget.problems.single().contains("which does not ask"))
    }

    @Test
    fun thePanelListsFiveAndSaysHowManyMore() {
        usualCase()
        val s = snapshot()
        val tooMany = TaskPanelViewModel.State(
            visible = true, taskId = "g1", title = "Release",
            links = TaskPanelViewModel.Block((1..7).map { TaskPanelViewModel.LinkRow("link", "l$it", "https://e.example/$it", "10:00") }, 0, 19),
        )
        val p1 = check("P1", s.copy(panel = tooMany))
        assertFalse(p1.ok)
        assertTrue(p1.problems.any { it.contains("Links") })
    }

    @Test
    fun aTabThatBecomesListedOnItsOwnFailsAndAMarkedTabMustHideAgain() {
        fun config(listed: Boolean, marked: Boolean) = WindowConfig(
            tabs = listOf(
                TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-main"))),
                TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-b1")), isBackground = !listed, shownWhileWaiting = marked),
            ),
            activeTabId = "tab-1",
        )
        val waiting = listOf(conversation("b1", "needs_you", background = true))
        val working = listOf(conversation("b1", "working", background = true))

        val good = OverviewChecks.TabWatch()
        good.observe(config(listed = false, marked = false), working, 0)
        good.observe(config(listed = false, marked = true), waiting, 1_000)
        good.observe(config(listed = false, marked = false), working, 2_000)
        assertTrue(good.result().ok, "shown while it waits, hidden after")

        val stuck = OverviewChecks.TabWatch()
        stuck.observe(config(listed = false, marked = true), working, 0)
        stuck.observe(config(listed = false, marked = true), working, OverviewChecks.HIDE_AGAIN_GRACE_MS + 1)
        assertFalse(stuck.result().ok, "listed with nothing waiting past the grace")

        val own = OverviewChecks.TabWatch()
        own.observe(config(listed = false, marked = false), working, 0)
        own.observe(config(listed = true, marked = false), working, 1_000)
        assertTrue(own.result().problems.single().contains("became listed without being marked"))
    }
}
