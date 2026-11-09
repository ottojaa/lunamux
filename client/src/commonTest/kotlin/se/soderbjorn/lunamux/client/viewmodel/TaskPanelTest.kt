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
        assertEquals("Waiting on review", s.status)
        assertNull(s.setup)
    }

    @Test
    fun anAgentIsNamedByWhatItIsDoingWithItsTitleAsAQuieterSecondLine() {
        layout(listed = listOf("main"), background = listOf("w1", "w2", "w3"))
        fill(
            session("w1", title = "Check the schema", nowLine = "Reading db/schema.sql", since = now - 3 * minute),
            session("w2", state = "needs_you", question = "Drop the `users` table?", title = "Migration", since = now - minute),
            session("w3", title = "Idle one", nowLine = "Running cd", since = now - 5 * minute),
        )
        val lines = vm.state.agents.shown
        assertEquals(listOf("w2", "w3", "w1"), lines.map { it.recordId }, "the agent that asks comes first, then by start")
        assertEquals("Drop the `users` table?", lines[0].name)
        assertEquals("Migration", lines[0].title)
        assertEquals("needs you", lines[0].stateWord)
        assertEquals("Idle one", lines[1].name, "a bare tool name says nothing, so the title is the name")
        assertNull(lines[1].title)
        assertEquals("Reading db/schema.sql", lines[2].name)
        assertEquals("Check the schema", lines[2].title)
    }

    @Test
    fun theMainConversationAndFinishedAgentsAreNotListed() {
        layout(listed = listOf("main"), background = listOf("w1", "w2"))
        fill(session("w1"), session("w2", state = "done"))
        assertEquals(listOf("w1"), vm.state.agents.shown.map { it.recordId })
    }

    @Test
    fun anAgentWhosePaneCannotBeFoundStillShowsWithNothingToClick() = runNow {
        layout(listed = listOf("main"))
        fill(session("lost", placed = false, nowLine = "Compiling"))
        val line = vm.state.agents.shown.single()
        assertEquals("Compiling", line.name)
        assertEquals(MemberAction.NONE, line.action)
        assertNull(line.hint)
        vm.activateMember(line)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun clickingAnAgentJumpsToItsTabOrShowsItWhileItWaitsInABackgroundTab() = runNow {
        layout(listed = listOf("main"), other = listOf("w1"), background = listOf("w2", "w3"))
        fill(
            session("w1", since = now - 3 * minute).copy(background = false),
            session("w2", state = "needs_you", since = now - 2 * minute),
            session("w3", since = now - minute),
        )
        val byId = vm.state.agents.shown.associateBy { it.recordId }
        assertEquals(MemberAction.JUMP, byId.getValue("w1").action)
        assertEquals(MemberAction.SHOW_WAITING, byId.getValue("w2").action)
        assertEquals(MemberAction.NONE, byId.getValue("w3").action, "a working background agent has nothing to open")

        vm.activateMember(byId.getValue("w1"))
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-w1")), sent)
        sent.clear()
        vm.activateMember(byId.getValue("w2"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("w2")), sent)
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
}
