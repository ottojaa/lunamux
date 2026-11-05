/**
 * Tests for [OverviewChecks]: each check passes on the state the view models produce for the usual
 * cases and fails, naming the problem, on state that breaks the rule it holds.
 *
 * Contains [OverviewChecksTest].
 *
 * @see OverviewChecks
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The checks against state from the real view models, then against state made to break each rule. */
class OverviewChecksTest {
    private val windowState = WindowStateRepository()
    private val now = 10_000_000L
    private val overviewVm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { }
    private val panelVm = TaskPanelViewModel(windowState, clock = { now }) { }

    private fun panes(ids: List<String>) =
        ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }

    private fun layout(config: WindowConfig) = windowState.updateConfig(config)

    private fun conversation(
        id: String, state: String, taskId: String? = null, background: Boolean = false, ended: Boolean = false,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = 1_000, title = "conversation $id", repo = "proj", cwd = "/work/proj",
        leafId = "leaf-$id".takeIf { !ended }, endedAt = 500L.takeIf { ended }, lastActivityAt = now - 1_000,
        taskId = taskId, background = background,
    )

    private fun task(main: String?) = AgentTask(
        id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, members = emptyList(), mainConversation = main,
    )

    private fun snapshot(expectedMain: Map<String, String> = emptyMap()) = OverviewChecks.Snapshot(
        sessions = windowState.agentSessions.value,
        tasks = windowState.agentTasks.value,
        config = windowState.config.value!!,
        overview = overviewVm.state,
        panel = panelVm.state,
        nowMillis = now,
        expectedMain = expectedMain,
    )

    /** A task whose main conversation works in a visible tab and whose two agents work out of sight. */
    private fun usualCase(bgState: String = "working") {
        layout(
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
        windowState.updateAgentTasks(listOf(task("main")))
    }

    private fun failed(checks: List<OverviewChecks.Check>) = checks.filter { !it.ok }.map { it.id }

    @Test
    fun theUsualCasePassesEveryCheck() {
        usualCase()
        assertEquals(emptyList(), failed(OverviewChecks.evaluate(snapshot())), OverviewChecks.render(OverviewChecks.evaluate(snapshot())))
    }

    @Test
    fun aBackgroundAgentThatAsksPassesEveryCheck() {
        usualCase(bgState = "needs_you")
        assertEquals(emptyList(), failed(OverviewChecks.evaluate(snapshot())))
        assertEquals(1, snapshot().overview.needYouCount)
    }

    @Test
    fun theFooterCountMustEqualTheRows() {
        usualCase(bgState = "needs_you")
        val s = snapshot()
        val tampered = s.copy(overview = s.overview.copy(now = s.overview.now.map { it.copy(stateWord = "working", marker = "") }, needYouCount = 0))
        val c1 = OverviewChecks.evaluate(tampered).first { it.id == "C1" }
        assertFalse(c1.ok)
        assertTrue(c1.problems.first().contains("the footer counts 1 waiting, the rows add up to 0"))
    }

    @Test
    fun aConversationOnTwoRowsFailsTheDuplicateCheck() {
        usualCase()
        val s = snapshot()
        val tampered = s.copy(overview = s.overview.copy(recent = listOf(s.overview.now.first { it.recordId == "solo" })))
        assertTrue("C3" in failed(OverviewChecks.evaluate(tampered)))
    }

    @Test
    fun aBackgroundAgentWithARowOfItsOwnFailsTheVisibleConversationCheck() {
        usualCase()
        val s = snapshot()
        val ownRow = s.overview.now.first { it.recordId == "solo" }.copy(id = "b1", recordId = "b1", title = "b1")
        val tampered = s.copy(overview = s.overview.copy(now = s.overview.now + ownRow))
        val c2 = OverviewChecks.evaluate(tampered).first { it.id == "C2" }
        assertFalse(c2.ok)
        assertTrue(c2.problems.any { it.contains("background agent") })
    }

    @Test
    fun returnMustReachTheMainConversation() {
        usualCase()
        val s = snapshot()
        val wrong = s.overview.now.map { if (it.taskId == "g1") it.copy(recordId = "solo") else it }
        val c4 = OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = wrong))).first { it.id == "C4" }
        assertFalse(c4.ok)
        assertTrue(c4.problems.single().contains("neither the main one main nor one that asks"))
    }

    @Test
    fun theMainConversationMustBeTheOneTheRunExpects() {
        usualCase()
        assertTrue(OverviewChecks.evaluate(snapshot(mapOf("g1" to "ma"))).first { it.id == "C4" }.ok)
        assertFalse(OverviewChecks.evaluate(snapshot(mapOf("g1" to "b7714ac5"))).first { it.id == "C4" }.ok)
    }

    @Test
    fun aMemberCountedAsWaitingThatHasNoConversationFailsTheQuestionCheck() {
        usualCase()
        val member = se.soderbjorn.lunamux.AgentTaskMember(key = "m1", title = "Ghost", state = "needs_you")
        windowState.updateAgentTasks(listOf(task("main").copy(members = listOf(member))))
        val c5 = OverviewChecks.evaluate(snapshot()).first { it.id == "C5" }
        assertFalse(c5.ok)
        assertTrue(c5.problems.single().contains("Ghost"))
    }

    @Test
    fun aSessionATaskFileClaimsWithoutCarryingTheTaskIdIsInItsTasksRowOnlyOnce() {
        usualCase()
        val claimed = se.soderbjorn.lunamux.AgentTaskMember(key = "m1", title = "Claimed", state = "done", recordId = "solo")
        windowState.updateAgentTasks(listOf(task("main").copy(members = listOf(claimed))))
        val checks = OverviewChecks.evaluate(snapshot())
        assertEquals(emptyList(), failed(checks), OverviewChecks.render(checks))
    }

    @Test
    fun aWaitingMemberInAListedTabWhileTheMainConversationWorksPassesEveryCheck() {
        layout(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-main", "leaf-w")))), activeTabId = "tab-1"))
        windowState.updateAgentSessions(listOf(conversation("main", "working", taskId = "g1"), conversation("w", "needs_you", taskId = "g1")), true)
        windowState.updateAgentTasks(listOf(task("main")))
        val row = snapshot().overview.now.single { it.taskId == "g1" }
        assertEquals(AgentsOverviewBackingViewModel.Enter.JUMP, row.enter)
        assertEquals("w", row.recordId, "Return goes to the question, not to the main conversation")
        val checks = OverviewChecks.evaluate(snapshot())
        assertEquals(emptyList(), failed(checks), OverviewChecks.render(checks))
    }

    @Test
    fun aMainConversationInABackgroundTabThatAsksNeedsItsTabShownNotAJump() {
        layout(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-solo"))),
                    TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-main")), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
        windowState.updateAgentSessions(listOf(conversation("main", "needs_you", taskId = "g1", background = true), conversation("solo", "done")), true)
        windowState.updateAgentTasks(listOf(task("main")))
        val s = snapshot()
        val row = s.overview.now.single { it.taskId == "g1" }
        assertEquals(AgentsOverviewBackingViewModel.Enter.SHOW_WAITING, row.enter)
        assertEquals(emptyList(), failed(OverviewChecks.evaluate(s)), OverviewChecks.render(OverviewChecks.evaluate(s)))
        val jumping = s.overview.now.map { if (it.taskId == "g1") it.copy(enter = AgentsOverviewBackingViewModel.Enter.JUMP) else it }
        val c5 = OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = jumping))).first { it.id == "C5" }
        assertFalse(c5.ok)
        assertTrue(c5.problems.single().contains("jumps instead of showing the tab"))
    }

    @Test
    fun aMainConversationInABackgroundTabThatDoesNotAskPassesTheReturnCheckWithNothingToOpen() {
        layout(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-solo"))),
                    TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-main")), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
        windowState.updateAgentSessions(listOf(conversation("main", "working", taskId = "g1", background = true), conversation("solo", "done")), true)
        windowState.updateAgentTasks(listOf(task("main")))
        assertEquals(emptyList(), failed(OverviewChecks.evaluate(snapshot())), OverviewChecks.render(OverviewChecks.evaluate(snapshot())))
    }

    @Test
    fun aRowWhoseReturnOpensAnotherConversationThanTheOneThatAsksFailsTheQuestionCheck() {
        usualCase(bgState = "needs_you")
        val s = snapshot()
        val wrong = s.overview.now.map { if (it.taskId == "g1") it.copy(recordId = "main", enter = AgentsOverviewBackingViewModel.Enter.SHOW_WAITING) else it }
        val c5 = OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = wrong))).first { it.id == "C5" }
        assertFalse(c5.ok)
        assertTrue(c5.problems.single().contains("which does not ask"))
    }

    @Test
    fun thePanelListsFiveAndSaysHowManyMore() {
        usualCase()
        val s = snapshot()
        val panel = TaskPanelViewModel.State(
            visible = true, taskId = "g1", title = "Release",
            links = TaskPanelViewModel.Block((1..7).map { TaskPanelViewModel.LinkRow("link", "l$it", "https://e.example/$it", "10:00") }, 0, 19),
        )
        val p1 = OverviewChecks.evaluate(s.copy(panel = panel)).first { it.id == "P1" }
        assertFalse(p1.ok)
        assertTrue(p1.problems.any { it.contains("Links") })
    }

    @Test
    fun aMarkedTabMustHideAgainOnceNothingInItWaits() {
        val watch = OverviewChecks.TabWatch()
        fun config(listedAndMarked: Boolean) = WindowConfig(
            tabs = listOf(
                TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-main"))),
                TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-b1")), isBackground = true, shownWhileWaiting = listedAndMarked),
            ),
            activeTabId = "tab-1",
        )
        val waiting = listOf(conversation("b1", "needs_you", background = true))
        val working = listOf(conversation("b1", "working", background = true))
        watch.observe(config(false), working, 0)
        watch.observe(config(true), waiting, 1_000)
        watch.observe(config(false), working, 2_000)
        assertTrue(watch.result().ok, "shown while it waits, hidden after")
        watch.observe(config(true), working, 3_000)
        watch.observe(config(true), working, 3_000 + OverviewChecks.HIDE_AGAIN_GRACE_MS + 1)
        assertFalse(watch.result().ok, "listed with nothing waiting past the grace")
    }

    @Test
    fun aTabThatBecomesListedWithoutBeingMarkedFails() {
        val watch = OverviewChecks.TabWatch()
        val tab = TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-b1")), isBackground = true)
        watch.observe(WindowConfig(tabs = listOf(tab), activeTabId = "tab-bg"), emptyList(), 0)
        watch.observe(WindowConfig(tabs = listOf(tab.copy(isBackground = false)), activeTabId = "tab-bg"), emptyList(), 1_000)
        val c6 = watch.result()
        assertFalse(c6.ok)
        assertTrue(c6.problems.single().contains("became listed without being marked"))
    }
}
