/**
 * Tests for [TaskPanelViewModel]: when the panel shows (the task whose main conversation is live in
 * the active tab, nothing elsewhere or while the person hid it), the agents block (only working and
 * waiting sessions, in words, what a click does), the progress block, the links block (each row's
 * own kind and own time, newest first, undated last), the recent-activity block, and the five-then-"+ N more" rule
 * every list follows.
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
import se.soderbjorn.lunamux.windowJson
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

    private fun task(main: String? = "own", id: String = "t1") =
        AgentTask(id = id, title = "Release notes", mainConversation = main, startedAtMillis = 1)

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
    fun `agents lists only working and waiting sessions of the task, the ones waiting first, and never the main conversation`() {
        layout()
        give(
            listOf(task()),
            listOf(
                session("own"),
                session("w", title = "Builder", leaf = "leaf-w"),
                session("r", title = "Reviewer", state = "needs_you", leaf = "leaf-bg"),
                session("d", title = "Finished one", state = "done", leaf = "leaf-w"),
                session("e", title = "Gone one", state = "ended", leaf = null),
                session("other", title = "Someone else's", leaf = "leaf-w", taskId = "t2"),
                session("none", title = "Carries no task", leaf = "leaf-w", taskId = null),
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
            listOf(task()),
            listOf(session("own"), session("w", title = "Builder", leaf = "leaf-w", nowLine = "Running cd")),
        )
        assertNull(vm.state.agents.shown.single().nowLine)
    }

    @Test
    fun `a click on a session in a listed tab jumps, on a waiting hidden one shows it as a tab`() = runNow {
        layout()
        give(
            listOf(task()),
            listOf(
                session("own"),
                session("w", title = "Listed", leaf = "leaf-w"),
                session("r", title = "Hidden", state = "needs_you", leaf = "leaf-bg"),
            ),
        )
        val lines = vm.state.agents.shown.associateBy { it.title }
        assertEquals(MemberAction.JUMP, lines.getValue("Listed").action)
        assertEquals(MemberAction.SHOW_WAITING, lines.getValue("Hidden").action)

        vm.activateMember(lines.getValue("Listed"))
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-w")), sent)
        sent.clear()
        vm.activateMember(lines.getValue("Hidden"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("r")), sent)
    }

    @Test
    fun `a hidden session that only works has nothing to open`() {
        layout()
        give(
            listOf(task()),
            listOf(session("own"), session("b", title = "Quiet", leaf = "leaf-bg")),
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
    fun `a link row shows its own kind and its own time, newest first, and the block's write time plays no part`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(
            block("l", TaskBlockContent.Links(listOf(
                TaskLink("slack", "Thread with Ana", "https://acme.slack.com/archives/C1/p1", now - 3_000_000),
                TaskLink("PR", "Fix the footer", "https://github.com/o/r/pull/12", now - 60_000),
                TaskLink("art", "Plan", "https://claude.ai/artifact/abc", now - 1_000_000),
            )), at = now),
            block("m", TaskBlockContent.Links(listOf(TaskLink("whatever", "Design brief", "file:///work/brief.md", now - 10_000)))),
            block("x", TaskBlockContent.Links(listOf(TaskLink("PR", "Not safe", "javascript:alert(1)", now))), at = now),
        )
        val rows = vm.state.links.shown
        assertEquals(listOf("whatever", "PR", "art", "slack"), rows.map { it.kind }, "the writer's words, as sent")
        assertEquals(listOf("Design brief", "Fix the footer", "Plan", "Thread with Ana"), rows.map { it.title })
        assertEquals(listOf("02:46", "02:45", "02:30", "01:56"), rows.map { it.time }, "each row's own time, in the viewer's zone")
        assertTrue(rows.none { it.url.startsWith("javascript") })
    }

    @Test
    fun `links without a time come after the dated ones in the order written, and a missing kind reads link`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(
            block("a", TaskBlockContent.Links(listOf(
                TaskLink(label = "Written first", url = "https://example.com/1"),
                TaskLink("doc", "Old but dated", "https://example.com/2", now - 9_000_000),
            ))),
            block("b", TaskBlockContent.Links(listOf(
                TaskLink("  ", "Blank kind", "https://example.com/3"),
                TaskLink("PR", "Dated", "https://example.com/4", now - 1_000),
            ))),
        )
        val rows = vm.state.links.shown
        assertEquals(listOf("Dated", "Old but dated", "Written first", "Blank kind"), rows.map { it.title })
        assertEquals(listOf("PR", "doc", "link", "link"), rows.map { it.kind })
        assertEquals(listOf("02:46", "00:16", "", ""), rows.map { it.time }, "no time given, none shown")
    }

    @Test
    fun `a link listed twice shows once, as its dated row`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(block("a", TaskBlockContent.Links(listOf(
            TaskLink("link", "Same, no time", "https://example.com/same"),
            TaskLink("PR", "Same, dated", "https://example.com/same", now - 120_000),
        ))))
        val rows = vm.state.links.shown
        assertEquals(1, rows.size)
        assertEquals("Same, dated", rows.single().title)
    }

    @Test
    fun `a links block stored before links had a kind and a time still loads, as kind link with no time`() {
        val stored = windowJson.decodeFromString(
            TaskBlockContent.serializer(),
            """{"kind":"links","items":[{"label":"Old row","url":"https://example.com/old"}]}""",
        )
        assertEquals(listOf(TaskLink("link", "Old row", "https://example.com/old", null)), (stored as TaskBlockContent.Links).items)
        layout()
        give(listOf(task()), listOf(session("own")))
        blocks(block("l", stored))
        val row = vm.state.links.shown.single()
        assertEquals("link", row.kind)
        assertEquals("", row.time)
        assertEquals("Old row", row.title)
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
        val sessions = listOf(session("own")) + (1..19).map { session("w$it", title = "Worker $it", leaf = "leaf-w") }
        give(listOf(task()), sessions)
        blocks(
            block("l", TaskBlockContent.Links((1..19).map { TaskLink(label = "Link $it", url = "https://example.com/$it") })),
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
        val links = TaskBlockContent.Links((1..19).map { TaskLink(label = "Link $it", url = "https://example.com/$it") })
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
        blocks(block("l", TaskBlockContent.Links((1..5).map { TaskLink(label = "Link $it", url = "https://example.com/$it") })))
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
