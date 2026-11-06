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
        id: String, state: String, taskId: String? = null, background: Boolean = false, ended: Boolean = false, since: Long = 1_000,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = "conversation $id", repo = "proj", cwd = "/work/proj",
        leafId = "leaf-$id".takeIf { !ended }, endedAt = 500L.takeIf { ended }, lastActivityAt = now - 1_000,
        taskId = taskId, background = background,
    )

    private fun task(main: String?) = AgentTask(
        id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, mainConversation = main,
    )

    private fun snapshot(
        expectedMain: Map<String, String> = emptyMap(),
        footer: AgentCounts.Tally = AgentCounts.of(windowState.agentSessions.value, windowState.config.value),
    ) = OverviewChecks.Snapshot(
        sessions = windowState.agentSessions.value,
        footer = footer,
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
    fun theFooterCountMustEqualTheRows() {
        usualCase(bgState = "needs_you")
        val s = snapshot()
        val tampered = s.copy(overview = s.overview.copy(now = s.overview.now.map { it.copy(stateWord = "working", marker = "") }, needYouCount = 0))
        val c1 = OverviewChecks.evaluate(tampered).first { it.id == "C1" }
        assertFalse(c1.ok)
        assertTrue(c1.problems.first().contains("the footer counts 1 waiting, the rows add up to 0"))
    }

    @Test
    fun aFooterThatCountsWrongFailsEvenWhenTheRowsAgreeWithEachOther() {
        usualCase(bgState = "needs_you")
        val wrong = AgentCounts.Tally(AgentCounts.Counts(waiting = 2, working = 0), emptyMap())
        val c1 = OverviewChecks.evaluate(snapshot(footer = wrong)).first { it.id == "C1" }
        assertFalse(c1.ok)
        assertTrue(c1.problems.any { it.contains("the footer counts 2 waiting, the rows add up to 1") })
        assertTrue(c1.problems.any { it.contains("the footer counts 2 waiting, the sessions have 1") })
        assertTrue(c1.problems.any { it.contains("the footer counts 0 working, the sessions have 2") })
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
        fun c4With(recordId: String): OverviewChecks.Check {
            val wrong = s.overview.now.map { if (it.taskId == "g1") it.copy(recordId = recordId) else it }
            return OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = wrong))).first { it.id == "C4" }
        }
        val hidden = c4With("b2")
        assertFalse(hidden.ok)
        assertTrue(hidden.problems.single().contains("not listed"), "a jump to a conversation in a hidden tab shows nothing")
        val foreign = c4With("solo")
        assertFalse(foreign.ok)
        assertTrue(foreign.problems.single().contains("not a conversation of the task"))
        windowState.updateAgentSessions(
            listOf(conversation("main", "working", taskId = "g1"), conversation("solo", "working", taskId = "g1")),
            true,
        )
        val own = OverviewChecks.evaluate(snapshot().let { t -> t.copy(overview = t.overview.copy(now = t.overview.now.map { if (it.taskId == "g1") it.copy(recordId = "solo") else it })) }).first { it.id == "C4" }
        assertFalse(own.ok)
        assertTrue(own.problems.single().contains("neither the main one main nor one that asks"))
    }

    @Test
    fun aShownTabMustBeAConversationOfTheTask() {
        usualCase(bgState = "needs_you")
        val s = snapshot()
        val row = s.overview.now.single { it.taskId == "g1" }
        assertEquals(AgentsOverviewBackingViewModel.Enter.SHOW_WAITING, row.enter)
        assertEquals(emptyList(), failed(OverviewChecks.evaluate(s)), "the real row passes")
        // The same decision aimed at an agent that no task holds.
        val foreign = s.overview.now.map { if (it.taskId == "g1") it.copy(recordId = "solo") else it }
        val c4 = OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = foreign))).first { it.id == "C4" }
        assertFalse(c4.ok)
        assertTrue(c4.problems.single().contains("not a conversation of the task"))
    }

    @Test
    fun aRowThatOpensNothingIsRightOnlyWhileTheMainConversationIsInAHiddenTab() {
        usualCase()
        val s = snapshot()
        for (enter in listOf(AgentsOverviewBackingViewModel.Enter.NONE, AgentsOverviewBackingViewModel.Enter.OPEN)) {
            val dead = s.overview.now.map { if (it.taskId == "g1") it.copy(enter = enter) else it }
            val c4 = OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = dead))).first { it.id == "C4" }
            assertFalse(c4.ok, "$enter on a task whose main conversation is in a listed tab is a dead Return")
            assertTrue(c4.problems.single().contains("does nothing"))
        }
    }

    @Test
    fun theMainConversationMustBeTheOneTheRunExpects() {
        usualCase()
        assertTrue(OverviewChecks.evaluate(snapshot(mapOf("g1" to "ma"))).first { it.id == "C4" }.ok)
        assertFalse(OverviewChecks.evaluate(snapshot(mapOf("g1" to "b7714ac5"))).first { it.id == "C4" }.ok)
    }

    @Test
    fun aSessionOpenedInsideATasksFolderKeepsItsOwnRowWithNoFileNamingIt() {
        usualCase()
        // The task is a file task with an `open` action; nothing but the task id a session carries
        // puts it in a task. (The server decides the folder rule; see TaskIndexTest.)
        windowState.updateAgentTasks(listOf(task("main").copy(source = "file", hasOpen = true)))
        val rows = snapshot().overview.now
        val own = rows.single { it.recordId == "solo" }
        assertEquals("solo", own.id, "the session has a row of its own, not folded into the task's")
        assertTrue("task:g1" in rows.map { it.id }, "the task keeps its row")
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
    fun aReturnThatJumpsToAHiddenMainConversationOrToAnotherTasksConversationFailsTheReturnCheck() {
        layout(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = panes(listOf("leaf-solo"))),
                    TabConfig(id = "tab-bg", title = "agents", panes = panes(listOf("leaf-main")), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
        windowState.updateAgentSessions(listOf(conversation("main", "working", taskId = "g1", background = true), conversation("solo", "working")), true)
        windowState.updateAgentTasks(listOf(task("main")))
        val s = snapshot()
        fun c4With(recordId: String): OverviewChecks.Check {
            val changed = s.overview.now.map { if (it.taskId == "g1") it.copy(recordId = recordId, enter = AgentsOverviewBackingViewModel.Enter.JUMP) else it }
            return OverviewChecks.evaluate(s.copy(overview = s.overview.copy(now = changed))).first { it.id == "C4" }
        }
        val hidden = c4With("main")
        assertFalse(hidden.ok, "a jump to a tab that is not listed shows nothing")
        assertTrue(hidden.problems.single().contains("not listed"))
        val foreign = c4With("solo")
        assertFalse(foreign.ok, "a conversation of another task is not what Return on this row may open")
        assertTrue(foreign.problems.single().contains("not a conversation of the task"))
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
