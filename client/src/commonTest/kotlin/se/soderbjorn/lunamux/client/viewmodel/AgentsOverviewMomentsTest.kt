/**
 * Tests for the overview's five everyday moments, each told end to end through
 * [AgentsOverviewBackingViewModel] and [AgentCounts]: the usual case, Return on a live main
 * conversation, the main conversation asking, a background member asking, and a main conversation
 * that is closed while its members work.
 *
 * Contains [AgentsOverviewMomentsTest].
 *
 * @see AgentsOverviewTasksViewModelTest
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WorldConfig
import se.soderbjorn.lunamux.listedTabs
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One test per moment, each reading the rows and the counts the person would see. */
class AgentsOverviewMomentsTest {
    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val now = 10_000_000L
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { sent += it }

    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private fun panes(ids: List<String>) =
        ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }

    /** One listed tab with [listed] panes and one background tab with [hidden] panes. */
    private fun layout(listed: List<String>, hidden: List<String> = emptyList()) {
        val tabs = mutableListOf(TabConfig(id = "tab-1", title = "one", panes = panes(listed)))
        if (hidden.isNotEmpty()) tabs += TabConfig(id = "tab-bg", title = "agents", panes = panes(hidden), isBackground = true)
        windowState.updateConfig(WindowConfig(tabs = tabs, activeTabId = "tab-1"))
    }

    private fun conversation(
        id: String, state: String, agent: String = "claude", taskId: String? = null, background: Boolean = false,
        ended: Boolean = false, since: Long = 1_000, question: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = agent, state = state, stateSince = since, title = "conversation $id", repo = "proj", cwd = "/work/proj",
        leafId = "leaf-$id".takeIf { !ended }, endedAt = (now - since).takeIf { ended }, lastActivityAt = now - 1_000,
        taskId = taskId, background = background, question = question,
    )

    private fun task(main: String?) = AgentTask(
        id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, mainConversation = main,
    )

    private fun show(tasks: List<AgentTask>, vararg conversations: AgentSessionSummary) {
        windowState.updateAgentSessions(conversations.toList(), true)
        windowState.updateAgentTasks(tasks)
    }

    private fun row(id: String) = vm.state.rows.single { it.id == id }

    /** Moment 1: the usual case. */
    @Test
    fun theUsualCaseShowsTheMainRowACodexRowADoneRowFiveEarlierEntriesAndThreeWorking() {
        layout(listOf("leaf-main", "leaf-codex", "leaf-done"), hidden = listOf("leaf-b1", "leaf-b2"))
        val earlier = (1..7).map { conversation("old$it", "ended", ended = true, since = it * 60_000L) }
        show(
            listOf(task("main")),
            conversation("main", "done", taskId = "g1"),
            conversation("b1", "working", taskId = "g1", background = true),
            conversation("b2", "working", taskId = "g1", background = true),
            conversation("codex", "working", agent = "codex", since = 2_000),
            conversation("done", "done", since = 3_000),
            *earlier.toTypedArray(),
        )
        assertEquals(listOf("task:g1", "codex", "done"), vm.state.now.map { it.id })
        assertEquals("2 working", row("task:g1").stateWord)
        assertEquals("working", row("codex").stateWord)
        assertEquals("done", row("done").stateWord)
        assertEquals(setOf("finished"), vm.state.earlier.map { it.stateWord }.toSet())
        assertEquals(5, vm.state.earlier.size)
        assertEquals(2, vm.state.earlierHidden, "five shown, then \"+ 2 more\"")
        assertEquals(0, vm.state.needYouCount)
        assertEquals(3, vm.state.workingCount)
        assertFalse(vm.state.rows.any { it.recordId in setOf("b1", "b2") }, "a background agent never gets a row")
    }

    /** Moment 2: Return goes to the live main conversation. */
    @Test
    fun returnOnALiveMainConversationJumpsToItsPane() = runNow {
        layout(listOf("leaf-main"))
        show(listOf(task("main")), conversation("main", "working", taskId = "g1"))
        assertEquals(Enter.JUMP, row("task:g1").enter)
        vm.activate(row("task:g1"))
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-main")), sent)
    }

    /** Moment 3: the main conversation asks. */
    @Test
    fun whenTheMainConversationAsksTheRowSaysNeedsYouAndTheFooterCountsOne() {
        layout(listOf("leaf-main"))
        show(listOf(task("main")), conversation("main", "needs_you", taskId = "g1", question = "Run the tests?"))
        assertEquals("needs you", row("task:g1").stateWord)
        assertEquals("◆", row("task:g1").marker)
        assertEquals(1, vm.state.needYouCount)
        assertEquals(1, AgentCounts.of(windowState.agentSessions.value, windowState.config.value, now).total.waiting)
        // The tab strip's dot is drawn by the web page from the terminal state of the panes in the tab, which is
        // not part of this view model, so it is not asserted here: the moment's screenshot on the live check shows it.
    }

    /**
     * Moment 3, closed: the task's status says a question waits, so the row stays among the live ones with
     * the status as its detail, nobody is counted as waiting, and Return resumes the conversation, which asks.
     * Clearing the flag lets the row go to EARLIER.
     */
    @Test
    fun aClosedMainWhoseStatusSaysAQuestionWaitsStaysInNowAndResumesOnReturn() = runNow {
        layout(listOf("leaf-other"))
        val closed = conversation("main", "ended", taskId = "g1", ended = true)
        val asking = task("main").copy(status = "Which database should the migration use?", questionWaits = true)
        show(listOf(asking), conversation("other", "working"), closed)
        assertTrue(vm.state.now.any { it.id == "task:g1" }, "the row is among the live ones")
        assertEquals("done", row("task:g1").stateWord)
        assertEquals("Which database should the migration use?", row("task:g1").detail)
        assertEquals(0, vm.state.needYouCount, "no conversation is waiting, so the footer does not count it")
        assertEquals(0, AgentCounts.of(windowState.agentSessions.value, windowState.config.value, now).total.waiting)
        assertEquals(Enter.RESUME, row("task:g1").enter)
        vm.open(); vm.select("task:g1")
        vm.activateSelected()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)

        // The question is answered: the next status does not repeat the flag.
        show(listOf(task("main").copy(status = "Migrating", questionWaits = false)), conversation("other", "working"), closed)
        assertFalse(vm.state.now.any { it.id == "task:g1" })
        assertEquals("finished", vm.state.earlier.single { it.id == "task:g1" }.stateWord)
    }

    /** The same after a restart: the main is no longer in memory, only the task file's open action and the flag remain. */
    @Test
    fun aMainThatIsGoneFromMemoryStillKeepsItsRowWhileAQuestionWaits() = runNow {
        layout(listOf("leaf-other"))
        val asking = task("main").copy(hasOpen = true, status = "Which database?", questionWaits = true)
        show(listOf(asking), conversation("other", "working"))
        assertTrue(vm.state.now.any { it.id == "task:g1" })
        assertEquals("Which database?", row("task:g1").detail)
        assertEquals(Enter.RESUME, row("task:g1").enter)
        assertEquals(0, vm.state.needYouCount)
        vm.open(); vm.select("task:g1")
        vm.activateSelected()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)
    }

    /** A flag on a task that has no main conversation has nothing to resume, so it keeps no row of its own. */
    @Test
    fun aQuestionFlagOnATaskWithNoMainConversationKeepsNoRow() {
        layout(listOf("leaf-other"))
        show(listOf(task(null).copy(status = "Which database?", questionWaits = true)), conversation("other", "working"))
        assertFalse(vm.state.rows.any { it.id == "task:g1" })
    }

    /** Moment 4: a background member asks; the tab stays hidden. */
    @Test
    fun whenABackgroundMemberAsksTheRowAndTheFooterShowItAndNoTabIsListed() {
        layout(listOf("leaf-main"), hidden = listOf("leaf-bg"))
        show(
            listOf(task("main")),
            conversation("main", "working", taskId = "g1"),
            conversation("bg", "needs_you", taskId = "g1", background = true),
        )
        assertEquals("◆", row("task:g1").marker)
        assertEquals("needs you", row("task:g1").stateWord)
        assertEquals(1, vm.state.needYouCount)
        assertEquals(Enter.SHOW_WAITING, row("task:g1").enter)
        assertEquals(listOf("tab-1"), windowState.config.value!!.listedTabs.map { it.id }, "nothing hidden appears by itself")
    }

    /** Moment 4b: Return on it asks the server to show the tab. */
    @Test
    fun returnOnAWaitingBackgroundMemberSendsShowWaitingAgent() = runNow {
        layout(listOf("leaf-main"), hidden = listOf("leaf-bg"))
        show(
            listOf(task("main")),
            conversation("main", "working", taskId = "g1"),
            conversation("bg", "needs_you", taskId = "g1", background = true),
        )
        vm.activate(row("task:g1"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("bg")), sent)
        // The member works on: the row is an ordinary jump again.
        show(
            listOf(task("main")),
            conversation("main", "working", taskId = "g1"),
            conversation("bg", "working", taskId = "g1", background = true),
        )
        assertEquals(Enter.JUMP, row("task:g1").enter)
        assertEquals(0, vm.state.needYouCount)
    }

    /** Moment 5: the main conversation is closed while two members work, also after a restart. */
    @Test
    fun aClosedMainConversationWithTwoMembersWorkingStaysInNowAndResumesByConversationId() = runNow {
        layout(listOf("leaf-other"), hidden = listOf("leaf-b1", "leaf-b2"))
        val facts = arrayOf(
            conversation("main", "ended", taskId = "g1", ended = true),
            conversation("b1", "working", taskId = "g1", background = true),
            conversation("b2", "working", taskId = "g1", background = true),
        )
        show(listOf(task("main")), *facts)
        assertTrue(vm.state.now.any { it.id == "task:g1" })
        assertEquals("2 working", row("task:g1").stateWord)
        vm.open(); vm.select("task:g1")
        vm.activateSelected()
        assertEquals("conversation closed", vm.state.ask!!.tag)
        assertEquals("Resume it in a new tab?", vm.state.ask!!.question)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)

        // A restart reuses pane ids for other tabs and sends the same conversations again.
        sent.clear()
        layout(listOf("leaf-main"), hidden = listOf("leaf-b1", "leaf-b2"))
        show(listOf(task("main")), *facts)
        vm.close(); vm.open(); vm.select("task:g1")
        vm.activateSelected(); vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent, "still by conversation, not by pane")
    }

    /** The footer's per-world split leaves out a conversation with no pane in the layout. */
    @Test
    fun countsByWorldSplitByThePaneAndLeaveOutAConversationWithNoPane() {
        val config = WindowConfig(
            worlds = listOf(
                WorldConfig("w1", "Home", tabs = listOf(TabConfig("t0", "t0", panes = panes(listOf("leaf-a"))))),
                WorldConfig("w2", "Work", tabs = listOf(TabConfig("t1", "t1", panes = panes(listOf("leaf-b", "leaf-c"))))),
            ),
            activeWorldId = "w1",
        )
        val counts = AgentCounts.of(
            listOf(
                conversation("a", "needs_you"),
                conversation("b", "working"),
                conversation("c", "working", background = true),
                conversation("gone", "working", ended = false).copy(leafId = null),
                conversation("old", "ended", ended = true),
            ),
            config, now,
        ).byWorld
        assertEquals(AgentCounts.Counts(1, 0), counts["w1"])
        assertEquals(AgentCounts.Counts(0, 2), counts["w2"])
        assertEquals(setOf("w1", "w2"), counts.keys)
    }

    /**
     * The total and the per-world lines come from the same sessions: a task whose agents sit in two
     * worlds and in a background tab adds up, and a live session is counted once.
     */
    @Test
    fun theTotalIsTheSumOfThePerWorldLinesForATaskWhoseAgentsSitInSeveralWindows() {
        val config = WindowConfig(
            worlds = listOf(
                WorldConfig(
                    "w1", "Home",
                    tabs = listOf(
                        TabConfig("t0", "t0", panes = panes(listOf("leaf-a"))),
                        TabConfig("tbg", "agents", panes = panes(listOf("leaf-bg")), isBackground = true),
                    ),
                ),
                WorldConfig("w2", "Work", tabs = listOf(TabConfig("t1", "t1", panes = panes(listOf("leaf-b", "leaf-c"))))),
            ),
            activeWorldId = "w1",
        )
        val sessions = listOf(
            conversation("a", "needs_you", taskId = "g1"),
            conversation("bg", "working", taskId = "g1", background = true),
            conversation("b", "working", taskId = "g1"),
            conversation("c", "needs_you", taskId = "g1"),
            conversation("lone", "working"),
            conversation("gone", "working").copy(leafId = null),
            conversation("old", "ended", taskId = "g1", ended = true),
        )
        val tally = AgentCounts.of(sessions, config, now)
        assertEquals(AgentCounts.Counts(2, 2), tally.total, "a live session counts once, however many windows the task spans")
        assertEquals(
            tally.total,
            AgentCounts.Counts(tally.byWorld.values.sumOf { it.waiting }, tally.byWorld.values.sumOf { it.working }),
            "the total is the sum of the lines",
        )
        // "lone" and "gone" have no pane any world holds, so they are in neither the lines nor the total.
        assertEquals(setOf("w1", "w2"), tally.byWorld.keys)
    }
}
