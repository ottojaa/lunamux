/**
 * Tests for [TaskPanelViewModel]: when the panel shows (the task whose main conversation is live in
 * the active tab, nothing elsewhere or while the person hid it), the agents block (only working and
 * waiting members, in words, what a click does), the progress block, the links block (any kind of
 * link, kind shown, newest first), the recent-activity block, and the five-then-"+ N more" rule
 * every list follows.
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.datetime.TimeZone
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskDocument
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskTimelineItem
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.Section
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TaskPanelViewModel]. Run by `:client:jvmTest` and `:client:jsTest`.
 *
 * @see TaskPanelViewModel
 */
class TaskPanelViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once). */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private var now = 10_000_000L
    private val vm = TaskPanelViewModel(windowState, clock = { now }, zone = { TimeZone.UTC }) { sent += it }

    private fun session(
        id: String, state: String = "working", since: Long = now - 125_000, title: String = "agent $id",
        leaf: String? = "leaf-$id", taskId: String? = "t1", nowLine: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, cwd = "/work/x", repo = "x",
        nowLine = nowLine, leafId = leaf, taskId = taskId, startedAt = since,
    )

    private fun pane(leafId: String) = Pane(LeafNode(id = leafId, sessionId = "pty-$leafId", title = leafId), 0.0, 0.0, 1.0, 1.0, 0)

    /** tab-1 (active) holds the main conversation, tab-2 a listed worker, tab-bg a hidden one. */
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

    private fun task(members: List<AgentTaskMember> = emptyList(), main: String? = "own", id: String = "t1") =
        AgentTask(id = id, title = "Release notes", members = members, mainConversation = main, startedAtMillis = 1)

    private fun member(key: String, title: String, state: String, recordId: String? = null, sessionId: String? = null, line: String? = null) =
        AgentTaskMember(key = key, title = title, state = state, sinceMillis = now - 3_600_000, recordId = recordId, sessionId = sessionId, line = line)

    private fun give(tasks: List<AgentTask>, sessions: List<AgentSessionSummary>) {
        windowState.updateAgentTasks(tasks)
        windowState.updateAgentSessions(sessions, true)
    }

    private fun block(id: String, content: TaskBlockContent, at: Long = now - 300_000, title: String? = null) =
        TaskPanelBlock(id, title, content, "an agent", at)

    private fun blocks(vararg b: TaskPanelBlock) = windowState.updateTaskPanel("t1", b.toList())

    // ---- when the panel shows ----

    @Test
    fun `the panel shows for the task whose main conversation is live in the active tab`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        val s = vm.state
        assertTrue(s.visible)
        assertEquals("t1", s.taskId)
        assertEquals("Release notes", s.title)
    }

    @Test
    fun `the panel stays away in another tab, without a live main conversation and while hidden`() {
        layout(active = "tab-2")
        give(listOf(task()), listOf(session("own")))
        assertFalse(vm.state.visible, "another tab")
        layout()
        give(listOf(task(main = null)), listOf(session("own")))
        assertFalse(vm.state.visible, "no main conversation")
        give(listOf(task()), listOf(session("own", state = "ended", leaf = null)))
        assertFalse(vm.state.visible, "main conversation closed")
        give(listOf(task()), listOf(session("own")))
        vm.setOpen(false)
        assertFalse(vm.state.visible)
        assertFalse(vm.state.open)
        vm.toggle()
        assertTrue(vm.state.visible)
    }

    // ---- the agents block ----

    @Test
    fun `agents lists only working and waiting members, the ones waiting first, and never the main conversation`() {
        layout()
        give(
            listOf(task(members = listOf(
                member("1", "Builder", "working", recordId = "w"),
                member("2", "Reviewer", "needs_you", recordId = "r"),
                member("3", "Finished one", "done", recordId = "d"),
                member("4", "Gone one", "ended", recordId = "e"),
            ))),
            listOf(
                session("own"), session("w", leaf = "leaf-w"), session("r", state = "needs_you", leaf = "leaf-bg"),
                session("d", state = "done", leaf = "leaf-w"), session("e", state = "ended", leaf = null),
            ),
        )
        assertEquals(listOf("Reviewer", "Builder"), vm.state.agents.shown.map { it.title })
        assertEquals(listOf("needs you", "working"), vm.state.agents.shown.map { it.stateWord })
    }

    @Test
    fun `an agent line never names a tool`() {
        assertNull(TaskPanelViewModel.inWords("Running cd"))
        assertNull(TaskPanelViewModel.inWords("  Using Grep "))
        assertNull(TaskPanelViewModel.inWords("   "))
        assertNull(TaskPanelViewModel.inWords(null))
        assertEquals("Run the server tests", TaskPanelViewModel.inWords(" Run the server tests "))
        layout()
        give(
            listOf(task(members = listOf(member("1", "Builder", "working", recordId = "w")))),
            listOf(session("own"), session("w", leaf = "leaf-w", nowLine = "Running cd")),
        )
        assertNull(vm.state.agents.shown.single().nowLine)
    }

    @Test
    fun `a click on a listed member jumps, on a waiting hidden one shows it as a tab, on an unhosted one attaches`() = runNow {
        layout()
        give(
            listOf(task(members = listOf(
                member("1", "Listed", "working", recordId = "w"),
                member("2", "Hidden", "needs_you", recordId = "r"),
                member("3", "Elsewhere", "working", sessionId = "bg-9"),
            ))),
            listOf(session("own"), session("w", leaf = "leaf-w"), session("r", state = "needs_you", leaf = "leaf-bg")),
        )
        val lines = vm.state.agents.shown.associateBy { it.title }
        assertEquals(MemberAction.JUMP, lines.getValue("Listed").action)
        assertEquals(MemberAction.SHOW_WAITING, lines.getValue("Hidden").action)
        assertEquals(MemberAction.OPEN, lines.getValue("Elsewhere").action)

        vm.activateMember(lines.getValue("Listed"))
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-w")), sent)
        sent.clear()
        vm.activateMember(lines.getValue("Hidden"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("r")), sent)
        sent.clear()
        vm.activateMember(lines.getValue("Elsewhere"))
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTaskMember("t1", "3")), sent)
    }

    @Test
    fun `a hidden member that only works has nothing to open`() {
        layout()
        give(
            listOf(task(members = listOf(member("1", "Quiet", "working", recordId = "b")))),
            listOf(session("own"), session("b", leaf = "leaf-bg")),
        )
        assertEquals(MemberAction.NONE, vm.state.agents.shown.single().action)
        assertNull(vm.state.agents.shown.single().hint)
    }

    // ---- progress ----

    @Test
    fun `progress shows the current task above the bar and the count below it`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        assertNull(vm.state.progress)
        blocks(block("p", TaskBlockContent.Progress(done = 2, total = 5, phase = "Writing the migration")))
        val p = vm.state.progress!!
        assertEquals("Writing the migration", p.current)
        assertEquals("2 of 5", p.count)
        assertEquals(0.4, p.fraction!!, 0.0001)
    }

    // ---- links ----

    @Test
    fun `links take any kind, show their kind and come newest first`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(
            block("l", TaskBlockContent.Links(listOf(
                TaskLink("Thread with Ana", "https://acme.slack.com/archives/C1/p1"),
                TaskLink("Fix the footer", "https://github.com/o/r/pull/12"),
                TaskLink("Plan", "https://claude.ai/artifact/abc"),
            )), at = now - 60_000),
            block("d", TaskBlockContent.Documents(listOf(TaskDocument("Design brief", "file:///work/brief.md", now - 10_000)))),
            block("x", TaskBlockContent.Links(listOf(TaskLink("Not safe", "javascript:alert(1)"))), at = now),
        )
        val rows = vm.state.links.shown
        assertEquals(listOf("doc", "slack", "PR", "art"), rows.map { it.kind })
        assertEquals("Design brief", rows.first().title)
        assertTrue(rows.none { it.url.startsWith("javascript") })
    }

    @Test
    fun `links name a decision or a brief by the block that holds them`() {
        assertEquals("decision", TaskPanelViewModel.kindOf("https://x.dev/a", "Names", "Decisions", false))
        assertEquals("decision", TaskPanelViewModel.kindOf("https://x.dev/a", "You decided: names", null, false))
        assertEquals("brief", TaskPanelViewModel.kindOf("https://x.dev/a", "Names", "Briefs", false))
        assertEquals("issue", TaskPanelViewModel.kindOf("https://github.com/o/r/issues/3", "x", null, false))
        assertEquals("link", TaskPanelViewModel.kindOf("https://example.com", "x", null, false))
    }

    @Test
    fun `only http, https and file addresses may be opened`() {
        assertEquals("https://a.dev", vm.linkTarget("https://a.dev"))
        assertNull(vm.linkTarget("javascript:alert(1)"))
    }

    // ---- activity ----

    @Test
    fun `recent activity is one line per item, newest first, with its clock time`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(block("t", TaskBlockContent.Timeline(listOf(
            TaskTimelineItem(now - 3_000_000, "Opened the pull request"),
            TaskTimelineItem(now - 1_000_000, "Tests passed"),
        ))))
        assertEquals(listOf("Tests passed", "Opened the pull request"), vm.state.activity.shown.map { it.text })
        assertTrue(vm.state.activity.shown.all { Regex("""\d\d:\d\d|[A-Z][a-z]{2} \d\d:\d\d|\d+ [A-Z][a-z]{2} \d\d:\d\d""").matches(it.time) })
    }

    // ---- five, then "+ N more" ----

    @Test
    fun `nineteen agents, nineteen links and nineteen lines show five of each and plus fourteen more, which open in place`() {
        layout()
        val members = (1..19).map { member("$it", "Worker $it", "working", recordId = "w$it") }
        val sessions = listOf(session("own")) + (1..19).map { session("w$it", leaf = "leaf-w") }
        give(listOf(task(members = members)), sessions)
        blocks(
            block("l", TaskBlockContent.Links((1..19).map { TaskLink("Link $it", "https://example.com/$it") })),
            block("t", TaskBlockContent.Timeline((1..19).map { TaskTimelineItem(now - it * 60_000L, "Step $it") })),
        )
        val s = vm.state
        for ((name, b) in listOf("agents" to s.agents, "links" to s.links, "activity" to s.activity)) {
            assertEquals(5, b.shown.size, name)
            assertEquals(14, b.more, name)
            assertEquals(19, b.total, name)
        }
        vm.showMore(Section.LINKS)
        assertEquals(19, vm.state.links.shown.size)
        assertEquals(0, vm.state.links.more)
        assertEquals(5, vm.state.agents.shown.size, "the other lists stay cut")
        vm.showMore(Section.AGENTS); vm.showMore(Section.ACTIVITY)
        assertEquals(listOf(19, 19, 19), listOf(vm.state.agents.shown.size, vm.state.links.shown.size, vm.state.activity.shown.size))
    }

    @Test
    fun `opening a list on one task leaves the same list of another task cut`() {
        layout()
        val links = TaskBlockContent.Links((1..19).map { TaskLink("Link $it", "https://example.com/$it") })
        give(
            listOf(task(), task(main = "own2", id = "t2")),
            listOf(session("own"), session("own2", leaf = "leaf-w", taskId = "t2")),
        )
        blocks(block("l", links))
        windowState.updateTaskPanel("t2", listOf(block("l", links)))
        vm.showMore(Section.LINKS)
        assertEquals(19, vm.state.links.shown.size)
        layout(active = "tab-2")
        assertEquals("t2", vm.state.taskId)
        assertEquals(5, vm.state.links.shown.size, "the other task's Links list is still cut")
        layout()
        assertEquals(19, vm.state.links.shown.size, "the first task keeps its list open")
    }

    @Test
    fun `a list of exactly five shows no more button`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(block("l", TaskBlockContent.Links((1..5).map { TaskLink("Link $it", "https://example.com/$it") })))
        assertEquals(0, vm.state.links.more)
        assertEquals(5, vm.state.links.shown.size)
    }

    // ---- small helpers ----

    @Test
    fun `ages and clock times read the way the drawing shows them`() {
        assertEquals("now", TaskPanelViewModel.ageOf(1_000, 30_000))
        assertEquals("5m", TaskPanelViewModel.ageOf(0, 5 * 60_000L))
        assertEquals("3h", TaskPanelViewModel.ageOf(0, 3 * 3_600_000L))
        assertEquals("2d", TaskPanelViewModel.ageOf(0, 2 * 86_400_000L))
        assertEquals("now", TaskPanelViewModel.ageOf(10_000, 0), "a time ahead of the clock reads now")
        val noon = 12 * 3_600_000L + 20 * 86_400_000L
        assertEquals("12:00", TaskPanelViewModel.clockOf(noon, noon + 1_000, TimeZone.UTC))
    }
}
