/**
 * Tests for the task panel, read the way a person reads it: when it shows, what its header and
 * agents say, how the lists are cut, and what a click on an agent does.
 *
 * Contains [TaskPanelTest].
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.datetime.TimeZone
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskStep
import se.soderbjorn.lunamux.TaskTimelineItem
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.runNow
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskPanelTest {
    private val now = 50L * 24 * 60 * 60_000
    private val minute = 60_000L
    private val windowState = WindowStateRepository()
    private val sent = mutableListOf<WindowCommand>()
    private val vm = TaskPanelViewModel(windowState, clock = { now }, zone = { TimeZone.UTC }) { sent += it }

    private fun pane(leaf: String) = Pane(LeafNode(id = leaf, sessionId = "s-$leaf", title = leaf), 0.0, 0.0, 1.0, 1.0, 0)

    /** The active tab holds `listed`; a second listed tab holds `other`; a background tab holds `background`. */
    private fun layout(listed: List<String>, background: List<String> = emptyList(), other: List<String> = emptyList()) =
        windowState.updateConfig(
            WindowConfig(
                tabs = listOfNotNull(
                    TabConfig("tab-1", "one", panes = listed.map { pane("leaf-$it") }),
                    TabConfig("tab-2", "two", panes = other.map { pane("leaf-$it") }).takeIf { other.isNotEmpty() },
                    TabConfig("tab-bg", "agents", panes = background.map { pane("leaf-$it") }, isBackground = true)
                        .takeIf { background.isNotEmpty() },
                ),
                activeTabId = "tab-1",
            ),
        )

    private fun session(
        id: String, state: String = "working", title: String? = "session $id", taskId: String? = "g1", placed: Boolean = true,
        nowLine: String? = null, question: String? = null, since: Long = now - 10 * minute,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, nowLine = nowLine, question = question,
        leafId = "leaf-$id".takeIf { placed }, startedAt = since, taskId = taskId, background = true,
    )

    private fun main(taskId: String? = "g1") = session("main", taskId = taskId).copy(background = false)

    private fun task(status: String? = null) = AgentTask(
        id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, mainConversation = "main", status = status,
    )

    private fun block(content: TaskBlockContent) = TaskPanelBlock(id = content.kindName, content = content, writtenAtMillis = now)

    private fun fill(vararg members: AgentSessionSummary, status: String? = null) {
        windowState.updateAgentSessions(listOf(main()) + members, true)
        windowState.updateAgentTasks(listOf(task(status)))
    }

    @Test
    fun theHeaderCarriesTheTaskTitleAndItsStatusLine() {
        layout(listed = listOf("main"), background = listOf("w1"))
        fill(session("w1"), status = "Waiting on review")
        val s = vm.state
        assertTrue(s.visible && s.beside)
        assertEquals("Release", s.title)
        val head = s.head!!
        assertEquals("Waiting on review", head.headline, "the headline is the overview row's")
        assertEquals("one" to "Release", head.name to head.about, "the tab's name leads, the title follows")
        assertEquals(AgentFacts.WORKING to "working", head.state to head.stateWord)
        assertTrue(head.meta.startsWith("proj"))
        assertNull(s.setup)
    }

    @Test
    fun everyRowKeepsItsTitleAndOneActivityLine() {
        layout(listed = listOf("main"), background = listOf("w1", "w2", "w3", "w4", "w5"))
        fill(
            session("w1", title = "Check the schema", nowLine = "Reading db/schema.sql", since = now - 3 * minute),
            session("w2", state = "needs_you", question = "Drop the `users` table?", title = "Migration", since = now - minute),
            session("w3", title = "Idle one", nowLine = "Running cd", since = now - 5 * minute),
            session("w4", title = "Quiet", state = "done", nowLine = "Reading old.kt", since = now - 4 * minute),
            session("w5", title = "Described", nowLine = "findings", since = now - 2 * minute),
        )
        val lines = vm.state.agents.shown.associateBy { it.recordId }
        assertEquals(listOf("w2", "w3", "w1", "w4", "w5").sorted(), lines.keys.sorted())
        assertEquals("w2", vm.state.agents.shown.first().recordId, "the agent that asks comes first, then by start")
        assertEquals("Migration" to "Drop the `users` table?", lines.getValue("w2").let { it.title to it.doing })
        assertEquals("Idle one" to "Running cd", lines.getValue("w3").let { it.title to it.doing }, "what the agent wrote is shown as it wrote it")
        assertEquals("Check the schema" to "Reading db/schema.sql", lines.getValue("w1").let { it.title to it.doing })
        assertEquals("Quiet" to "idle", lines.getValue("w4").let { it.title to it.doing })
        assertEquals("findings", lines.getValue("w5").doing)
        // With nothing to say an agent's line is the plain state word, so the row keeps its height.
        fill(session("w1", nowLine = null), session("w2", state = "needs_you", question = null, nowLine = null))
        assertEquals("working", vm.state.agents.shown.single { it.recordId == "w1" }.doing)
        assertEquals("needs you", vm.state.agents.shown.single { it.recordId == "w2" }.doing)
    }

    @Test
    fun theMainConversationAndEndedAgentsAreNotListed() {
        layout(listed = listOf("main"), background = listOf("w1", "w2"))
        fill(session("w1"), session("w2", state = "ended"))
        assertEquals(listOf("w1"), vm.state.agents.shown.map { it.recordId })
    }

    @Test
    fun theLiveManagerIsNotListedAsAnAgentWhenItsEndedReviewerSharesItsPane() {
        // The server now names the live manager as the task's main conversation (see TaskIndexTest);
        // the dead plan reviewer from the same pane is over, so neither shows as an agent.
        windowState.updateConfig(
            WindowConfig(tabs = listOf(TabConfig("tab-1", "one", panes = listOf(pane("n345-ieg3")))), activeTabId = "tab-1"),
        )
        val manager = session("dcf42071", taskId = "g1").copy(leafId = "n345-ieg3", background = false)
        val reviewer = session("b5502f2a", state = "ended", taskId = "g1").copy(leafId = "n345-ieg3")
        windowState.updateAgentSessions(listOf(manager, reviewer), true)
        windowState.updateAgentTasks(listOf(task().copy(mainConversation = "dcf42071")))
        assertTrue(vm.state.beside)
        assertTrue(vm.state.agents.shown.isEmpty())
    }

    @Test
    fun anAgentWhosePaneCannotBeFoundStillShowsWithNothingToClick() = runNow {
        layout(listed = listOf("main"))
        fill(session("lost", placed = false, nowLine = "Compiling"))
        val line = vm.state.agents.shown.single()
        assertEquals("Compiling", line.doing)
        assertEquals(MemberAction.NONE, line.action)
        assertNull(line.hint)
        vm.activateMember(line)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun clickingAnAgentJumpsToItsTabOrShowsItsHiddenTabWhateverItIsDoing() = runNow {
        layout(listed = listOf("main"), other = listOf("w1"), background = listOf("w2", "w3"))
        fill(
            session("w1", since = now - 3 * minute).copy(background = false),
            session("w2", state = "needs_you", since = now - 2 * minute),
            session("w3", since = now - minute),
        )
        val byId = vm.state.agents.shown.associateBy { it.recordId }
        assertEquals(MemberAction.JUMP, byId.getValue("w1").action)
        assertEquals(listOf("↵ show", "↵ show"), listOf("w2", "w3").map { byId.getValue(it).hint }, "waiting or working, a hidden tab is shown")
        assertEquals(MemberAction.SHOW, byId.getValue("w3").action)

        vm.activateMember(byId.getValue("w1"))
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-w1")), sent)
        sent.clear()
        vm.activateMember(byId.getValue("w3"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("w3")), sent, "nothing is sent to the agent")
    }

    @Test
    fun anAgentThatPausesStaysListedAsIdleInPlaceAndOnlyItsStateChanges() {
        layout(listed = listOf("main"), background = listOf("w1", "w2"))
        fun lineOf(id: String) = vm.state.agents.shown.single { it.recordId == id }
        val first = session("w1", since = now - 5 * minute)
        val second = session("w2", since = now - minute)
        fill(first, second)
        assertEquals(listOf("w1", "w2"), vm.state.agents.shown.map { it.recordId })
        assertEquals("working", lineOf("w1").doing)

        fill(first.copy(state = "done", nowLine = null), second)
        assertEquals(listOf("w1", "w2"), vm.state.agents.shown.map { it.recordId }, "the row holds its place")
        assertEquals("idle", lineOf("w1").doing)
        assertEquals("↵ show", lineOf("w1").hint, "an idle row in a hidden tab offers the same")

        fill(first, second)
        assertEquals(listOf("w1", "w2"), vm.state.agents.shown.map { it.recordId })
        assertEquals("working", lineOf("w1").doing)

        fill(first.copy(state = "ended"), second)
        assertEquals(listOf("w2"), vm.state.agents.shown.map { it.recordId }, "it leaves only when its session ends")
    }

    @Test
    fun everyListShowsFiveThenSaysHowManyMoreAndOpensInPlace() {
        layout(listed = listOf("main"), background = (1..8).map { "w$it" })
        fill(*(1..8).map { session("w$it", since = now - it * minute) }.toTypedArray())
        windowState.updateTaskPanel(
            "g1",
            listOf(
                block(TaskBlockContent.Links((1..7).map { TaskLink(label = "link $it", url = "https://example.com/$it") })),
                block(TaskBlockContent.Timeline((1..6).map { TaskTimelineItem(now - it * minute, "step $it") })),
            ),
        )
        val s = vm.state
        assertEquals(5, s.agents.shown.size)
        assertEquals(3, s.agents.more)
        assertEquals(2, s.links.more)
        assertEquals(1, s.activity.more)
        assertEquals("step 1", s.activity.shown.first().text, "newest first")
        assertEquals("Yesterday", s.activity.shown.first().day, "a minute before midnight is the day before")

        vm.showMore(TaskPanelViewModel.Section.LINKS)
        assertEquals(7, vm.state.links.shown.size)
        assertEquals(0, vm.state.links.more)
        assertEquals(1, vm.state.activity.more, "the other lists stay cut")
    }

    @Test
    fun linksAreSafeAndListedOnce() {
        layout(listed = listOf("main"))
        fill()
        windowState.updateTaskPanel(
            "g1",
            listOf(
                block(
                    TaskBlockContent.Links(
                        listOf(
                            TaskLink(label = "PR", url = "https://example.com/pr/1", kind = "pr"),
                            TaskLink(label = "again", url = "https://example.com/pr/1"),
                            TaskLink(label = "script", url = "javascript:alert(1)"),
                            TaskLink(label = "file", url = "file:///tmp/report.html"),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(listOf("https://example.com/pr/1", "file:///tmp/report.html"), vm.state.links.shown.map { it.url })
        assertEquals("pr", vm.state.links.shown.first().kind)
        assertNull(vm.linkTarget("javascript:alert(1)"))
        assertEquals("https://example.com/", vm.linkTarget("https://example.com/"))
    }

    @Test
    fun progressShowsWhatIsHappeningAndHowFarAlong() {
        layout(listed = listOf("main"))
        fill()
        windowState.updateTaskPanel("g1", listOf(block(TaskBlockContent.Progress(done = 2, total = 5, phase = "Testing"))))
        val p = vm.state.progress!!
        assertEquals("Testing", p.current)
        assertEquals("2 of 5", p.count)
        assertEquals(0.4, p.fraction)
    }

    @Test
    fun anEmptyPanelSaysHowToSetItUp() {
        layout(listed = listOf("main"))
        fill()
        assertTrue(vm.state.setup!!.lead.startsWith("Nothing here yet"))
        assertTrue(vm.state.setup!!.prompt.contains("Lunamux panel"))
    }

    @Test
    fun besideTheMainConversationItFollowsTheSettingAndElsewhereOnlyCmdIAsksForIt() {
        layout(listed = listOf("main"), other = listOf("plain"))
        windowState.updateAgentSessions(listOf(main(), session("plain", taskId = null).copy(background = false)), true)
        windowState.updateAgentTasks(listOf(task()))
        assertTrue(vm.state.visible)
        vm.setOpen(false)
        assertFalse(vm.state.visible, "the person closed it beside the main conversation")
        vm.setOpen(true)

        windowState.updateConfig(windowState.config.value!!.copy(activeTabId = "tab-2"))
        assertFalse(vm.state.visible, "a tab without the main conversation shows no panel by itself")
        vm.toggleInActiveTab()
        assertTrue(vm.state.visible, "⌘I opens it in any tab")
        assertFalse(vm.state.beside)
        assertTrue(vm.state.setup != null, "with no task there it says how to start one")
        vm.toggleInActiveTab()
        assertFalse(vm.state.visible)
    }

    @Test
    fun timesReadAsAgesAndAsClockTimesInTheViewersZone() {
        val at = 1_700_000_000_000L // Tuesday 22:13 UTC
        val day = 24 * 60 * 60_000L
        assertEquals("22:13", TaskPanelViewModel.clockOf(at - 1_000, at, TimeZone.UTC))
        assertEquals("Sun 22:13", TaskPanelViewModel.clockOf(at - 2 * day, at, TimeZone.UTC))
        assertEquals("4 Nov 22:13", TaskPanelViewModel.clockOf(at - 10 * day, at, TimeZone.UTC))
        assertEquals(listOf("now", "6m", "3h", "2d"), listOf(30_000L, 6 * 60_000L, 3 * 3_600_000L, 2 * day).map { AgentFacts.ageText(at - it, at) })
    }

    @Test
    fun aPlanShowsItsStepsWithTheFinishedOnesFoldedAndCountsThem() {
        layout(listed = listOf("main"))
        fill()
        val states = listOf("done", "done", "done", "current", "todo")
        windowState.updateTaskPanel("g1", listOf(block(TaskBlockContent.Steps(states.mapIndexed { i, s -> TaskStep("step ${i + 1}", s) }))))
        val plan = vm.state.progress!!
        assertEquals("3 of 5", plan.count)
        assertEquals(0.6, plan.fraction)
        assertEquals(2, plan.earlierDone, "all but the last finished step fold into one line")
        assertEquals(listOf("step 3", "step 4", "step 5"), plan.steps.map { it.text })
        assertNull(plan.current, "the list shows the step in hand")
        assertEquals("step 4", vm.state.head?.headline, "with no status, the step in hand is the headline")
    }
}
