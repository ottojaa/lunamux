/**
 * Tests for [TaskPanelViewModel]: which task's panel shows (the task whose own session sits in the
 * active tab, nothing in any other tab or without one, nothing while the person has it hidden),
 * the agents block (member order, state words, ages, dimmed finished members, and what a click on
 * each kind of line does), the stored blocks (byline, newest-first timeline, progress, parsed
 * text, links and documents that may open, the empty sentence), buttons that go dead with the own session,
 * and the hand-off to the watch (which opens it, and shows a tab, from a selected line).
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskButton
import se.soderbjorn.lunamux.TaskDocument
import se.soderbjorn.lunamux.TaskFact
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskTimelineItem
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.BlockBody
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel.MemberAction
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TaskPanelViewModel]: when the panel shows, the agents block and its click actions, the stored
 * blocks with their bylines, button presses, and what a selected line offers and does.
 *
 * Run by `:client:jvmTest` and `:client:jsTest`.
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
    private val watch = WatchViewModel(windowState) { sent += it }
    private val vm = TaskPanelViewModel(windowState, watch, clock = { now }, zone = { TimeZone.UTC }) { sent += it }

    private fun session(
        id: String, state: String = "working", since: Long = now - 125_000, title: String = "agent $id",
        leaf: String? = "leaf-$id", taskId: String? = "t1", background: Boolean = false, nowLine: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, cwd = "/work/x", repo = "x",
        nowLine = nowLine, leafId = leaf, taskId = taskId, background = background, startedAt = since,
    )

    private fun pane(leafId: String) = Pane(LeafNode(id = leafId, sessionId = "pty-$leafId", title = leafId), 0.0, 0.0, 1.0, 1.0, 0)

    /** tab-1 (active) holds the own session, tab-2 a listed worker, tab-bg a background one. */
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

    private fun task(members: List<AgentTaskMember> = emptyList(), lead: String? = "own", id: String = "t1") =
        AgentTask(id = id, title = "Release notes", members = members, leadRecordId = lead, startedAtMillis = 1)

    private fun member(key: String, title: String, state: String, recordId: String? = null, sessionId: String? = null, label: String? = null, line: String? = null, since: Long = now - 3_600_000) =
        AgentTaskMember(key = key, title = title, state = state, sinceMillis = since, recordId = recordId, sessionId = sessionId, label = label, line = line)

    private fun give(tasks: List<AgentTask>, sessions: List<AgentSessionSummary>) {
        windowState.updateAgentTasks(tasks)
        windowState.updateAgentSessions(sessions, true)
    }

    private fun block(id: String, content: TaskBlockContent, by: String? = "release notes agent", at: Long = now - 300_000, title: String? = null) =
        TaskPanelBlock(id, title, content, by, at)

    @Test
    fun `the panel shows for the task whose own session is in the active tab`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        val s = vm.state
        assertTrue(s.visible)
        assertEquals("t1", s.taskId)
        assertEquals("Release notes", s.title)
    }

    @Test
    fun `another tab shows no panel, and neither does a task without an own session`() {
        layout(active = "tab-2")
        give(listOf(task()), listOf(session("own")))
        assertFalse(vm.state.visible)
        assertNull(vm.state.taskId)

        layout()
        give(listOf(task(lead = null)), listOf(session("own")))
        assertFalse(vm.state.visible)

        give(listOf(task(lead = "missing")), listOf(session("own")))
        assertFalse(vm.state.visible)
    }

    @Test
    fun `the panel follows the task's lead record when a newer ended record sits on the same leaf`() {
        // A reused terminal id leaves an ended record and a live one on one leaf. Which of them is
        // the task's lead is the server's to say (its index test covers that); given a lead that
        // points at the live record, the panel shows for that task and lists no record twice.
        layout()
        val ended = session("old", state = "ended", leaf = "leaf-own", since = now - 10)
        val live = session("own", leaf = "leaf-own", since = now - 1_000_000)
        give(listOf(task(lead = "own")), listOf(live, ended))
        assertTrue(vm.state.visible)
        assertEquals("t1", vm.state.taskId)
        assertFalse(vm.state.agents.any { it.recordId == "own" }, "the own session is not one of its own agents")
    }

    @Test
    fun `no layout and no tasks show nothing`() {
        assertFalse(vm.state.visible)
        layout()
        assertFalse(vm.state.visible)
    }

    @Test
    fun `the person's choice hides the panel and toggle flips it`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        vm.toggle()
        assertFalse(vm.state.visible)
        assertFalse(vm.state.open)
        vm.toggle()
        assertTrue(vm.state.visible)
        vm.setOpen(false)
        assertFalse(vm.state.visible)
        vm.setOpen(true)
        assertTrue(vm.state.visible)
    }

    @Test
    fun `the agents block lists members in the task's order with words, ages and dimmed finished ones`() {
        layout()
        give(
            listOf(
                task(
                    listOf(
                        member("m1", "Writer", "working", recordId = "bg1"),
                        member("m2", "Reviewer", "needs_you", recordId = "w1"),
                        member("m3", "Old run", "ended", recordId = "gone"),
                        member("m4", "Waiting one", "idle", recordId = "idle1"),
                    ),
                ),
            ),
            listOf(
                session("own"), session("bg1", background = true, leaf = "leaf-bg", nowLine = "Reading CHANGELOG.md"),
                session("w1", leaf = "leaf-w", state = "needs_you"),
                session("gone", state = "ended", leaf = null), session("idle1", state = "idle", leaf = null),
            ),
        )
        val lines = vm.state.agents
        assertEquals(listOf("m1", "m2", "m3", "m4"), lines.map { it.key })
        assertEquals(listOf("working", "needs you", "exited", "waiting"), lines.map { it.stateWord })
        assertEquals(listOf(false, false, true, false), lines.map { it.dimmed })
        assertEquals("1h", lines[0].age)
        assertEquals("Reading CHANGELOG.md", lines[0].nowLine)
        assertNull(lines[1].nowLine)
    }

    @Test
    fun `a finished member the file labels reads exited with the label`() {
        layout()
        give(
            listOf(task(listOf(member("done", "Marks", "ended", label = "merged")))),
            listOf(session("own")),
        )
        assertEquals("exited · merged", vm.state.agents.first { it.key == "done" }.stateWord)
    }

    @Test
    fun `the lines of an agent-made task are its other hosted sessions, never the one beside the panel`() {
        layout()
        give(
            listOf(task()),
            listOf(session("own", since = 100), session("w1", since = 200, leaf = "leaf-w"), session("other", taskId = "t2")),
        )
        assertEquals(listOf("w1"), vm.state.agents.map { it.key })
        assertEquals(listOf("agent w1"), vm.state.agents.map { it.title })
    }

    @Test
    fun `each kind of line has its click action`() {
        layout()
        give(
            listOf(
                task(
                    listOf(
                        member("bg", "bg", "working", recordId = "bg1"),
                        member("listed", "listed", "working", recordId = "w1"),
                        member("file", "file", "idle", sessionId = "abc", label = "in review"),
                        member("nofile", "nofile", "idle"),
                        member("done", "done", "ended", recordId = "gone"),
                    ),
                ),
            ),
            listOf(
                session("own"), session("bg1", background = true, leaf = "leaf-bg"), session("w1", leaf = "leaf-w"),
                session("gone", state = "ended", leaf = null),
            ),
        )
        val byKey = vm.state.agents.associateBy { it.key }
        assertEquals(MemberAction.WATCH, byKey.getValue("bg").action)
        assertEquals(MemberAction.JUMP, byKey.getValue("listed").action)
        assertEquals(MemberAction.OPEN, byKey.getValue("file").action)
        assertEquals(MemberAction.NONE, byKey.getValue("nofile").action)
        assertEquals(MemberAction.NONE, byKey.getValue("done").action)
        assertEquals("in review", byKey.getValue("file").stateWord, "an unhosted member keeps the file's word")
    }

    @Test
    fun `clicking a line sends the command for its action`() {
        layout()
        give(
            listOf(task(listOf(member("listed", "listed", "working", recordId = "w1"), member("file", "file", "idle", sessionId = "abc")))),
            listOf(session("own"), session("w1", leaf = "leaf-w")),
        )
        val byKey = vm.state.agents.associateBy { it.key }
        runNow { vm.activateMember(byKey.getValue("listed")) }
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-w")), sent)
        sent.clear()
        runNow { vm.activateMember(byKey.getValue("file")) }
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTaskMember("t1", "file")), sent)
    }

    @Test
    fun `the stored blocks come with a byline, newest first, and parse their text`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        windowState.updateTaskPanel(
            "t1",
            listOf(
                block("note", TaskBlockContent.Text("hello **you**"), title = "Status"),
                block("facts", TaskBlockContent.Facts(listOf(TaskFact("Branch", "main")))),
                block(
                    "tl",
                    TaskBlockContent.Timeline(listOf(TaskTimelineItem(now - 7_200_000, "first"), TaskTimelineItem(now - 60_000, "second"))),
                    by = null, at = now - 10_000,
                ),
                block("p1", TaskBlockContent.Progress(done = 2, total = 5, label = "checks")),
                block("p2", TaskBlockContent.Progress(phase = "review")),
                block("l", TaskBlockContent.Links(listOf(TaskLink("ok", "https://a.example"), TaskLink("bad", "javascript:alert(1)")))),
            ),
        )
        val blocks = vm.state.blocks.associateBy { it.id }
        assertEquals(listOf("note", "facts", "tl", "p1", "p2", "l"), vm.state.blocks.map { it.id })
        assertEquals("Status", blocks.getValue("note").title)
        assertEquals("release notes agent · 5m", blocks.getValue("note").byline)
        assertEquals("now", blocks.getValue("tl").byline, "no writer: the age alone")
        assertEquals(
            BlockBody.Text(PanelMarkdown.parse("hello **you**")), blocks.getValue("note").body,
        )
        assertEquals(BlockBody.Facts(listOf("Branch" to "main")), blocks.getValue("facts").body)
        assertEquals(
            listOf("second", "first"), (blocks.getValue("tl").body as BlockBody.Timeline).items.map { it.text },
        )
        assertEquals("1m", (blocks.getValue("tl").body as BlockBody.Timeline).items[0].age)
        assertEquals(BlockBody.Progress("2 of 5", 0.4, "checks"), blocks.getValue("p1").body)
        assertEquals(BlockBody.Progress("review", null, null), blocks.getValue("p2").body)
        assertEquals(BlockBody.Links(listOf("ok" to "https://a.example")), blocks.getValue("l").body)
        assertNull(vm.state.emptyText)
    }

    @Test
    fun `a documents block keeps its order, shows a time as a clock time, and drops an address that must not open`() {
        now = 1_800_000_000_000L // a Friday, 08:00 UTC
        layout()
        give(listOf(task()), listOf(session("own")))
        windowState.updateTaskPanel(
            "t1",
            listOf(
                block(
                    "docs",
                    TaskBlockContent.Documents(
                        listOf(
                            TaskDocument("Design page", "https://a.example/design", now - 2 * 3_600_000),
                            TaskDocument("Yesterday", "https://a.example/y", now - 11 * 3_600_000),
                            TaskDocument("Old", "https://a.example/old", now - 20 * 86_400_000),
                            TaskDocument("No time", "file:///tmp/notes.md"),
                            TaskDocument("Bad", "javascript:alert(1)", now),
                        ),
                    ),
                    title = "Documents",
                ),
            ),
        )
        val view = vm.state.blocks.single()
        assertEquals("Documents", view.title)
        assertEquals(
            BlockBody.Documents(
                listOf(
                    TaskPanelViewModel.DocumentRow("Design page", "https://a.example/design", "06:00"),
                    TaskPanelViewModel.DocumentRow("Yesterday", "https://a.example/y", "Thu 21:00"),
                    TaskPanelViewModel.DocumentRow("Old", "https://a.example/old", "26 Dec 08:00"),
                    TaskPanelViewModel.DocumentRow("No time", "file:///tmp/notes.md", null),
                ),
            ),
            view.body,
        )
    }

    @Test
    fun `a panel with no stored blocks says so`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        assertEquals("Nothing else here yet. Agents can add blocks with set_task_panel.", vm.state.emptyText)
        assertTrue(vm.state.blocks.isEmpty())
    }

    @Test
    fun `an html text block stays literal in the view model`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        windowState.updateTaskPanel("t1", listOf(block("x", TaskBlockContent.Text("<img src=x onerror=alert(1)>"))))
        val body = vm.state.blocks.single().body as BlockBody.Text
        assertEquals(listOf<MdBlock>(MdBlock.Paragraph(listOf(MdInline.Text("<img src=x onerror=alert(1)>")))), body.nodes)
    }

    @Test
    fun `a pressed button names only the task, block and button`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        windowState.updateTaskPanel("t1", listOf(block("act", TaskBlockContent.Actions(listOf(TaskButton("go", "Go", "/ship"))))))
        val buttons = (vm.state.blocks.single().body as BlockBody.Actions).buttons
        assertEquals(listOf(TaskPanelViewModel.ButtonView("go", "Go", true)), buttons)
        runNow { vm.press("act", "go") }
        assertEquals(listOf<WindowCommand>(WindowCommand.PressTaskButton("t1", "act", "go")), sent)
        sent.clear()
        runNow { vm.press("act", "nope"); vm.press("nope", "go") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `buttons are disabled and press nothing while the own session is not running`() {
        layout()
        give(listOf(task()), listOf(session("own", state = "ended")))
        windowState.updateTaskPanel("t1", listOf(block("act", TaskBlockContent.Actions(listOf(TaskButton("go", "Go", "/ship"))))))
        assertTrue(vm.state.visible)
        assertFalse((vm.state.blocks.single().body as BlockBody.Actions).buttons.single().enabled)
        runNow { vm.press("act", "go") }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a background member's line offers watch and show as tab, a visible one offers open, the rest offer nothing`() {
        layout()
        give(
            listOf(
                task(
                    listOf(
                        member("bg", "bg", "working", recordId = "bg1"),
                        member("listed", "listed", "working", recordId = "w1"),
                        member("file", "file", "idle", sessionId = "abc"),
                        member("nofile", "nofile", "idle"),
                        member("done", "done", "ended", recordId = "gone"),
                    ),
                ),
            ),
            listOf(
                session("own"), session("bg1", background = true, leaf = "leaf-bg"), session("w1", leaf = "leaf-w"),
                session("gone", state = "ended", leaf = null),
            ),
        )
        val hints = vm.state.agents.associate { it.key to it.hint }
        assertEquals("↵ watch · ⌘↵ show as tab", hints["bg"])
        assertEquals("↵ open", hints["listed"])
        assertEquals("↵ open", hints["file"])
        assertNull(hints["nofile"])
        assertNull(hints["done"])
    }

    @Test
    fun `activating a background member's line opens the watch and sends nothing`() {
        layout()
        give(
            listOf(task(listOf(member("bg", "bg", "working", recordId = "bg1")))),
            listOf(session("own"), session("bg1", background = true, leaf = "leaf-bg", title = "Writer")),
        )
        runNow { vm.activateMember(vm.state.agents.single()) }
        assertEquals(WatchViewModel.Watch("bg1", "t1", "tab-1", "Writer", "pty-leaf-bg", true), watch.state)
        assertTrue(sent.isEmpty(), "watching sends nothing")
    }

    @Test
    fun `show as tab from a selected line shows the tab and leaves no watch open`() {
        layout()
        give(
            listOf(task(listOf(member("bg", "bg", "working", recordId = "bg1")))),
            listOf(session("own"), session("bg1", background = true, leaf = "leaf-bg")),
        )
        runNow { vm.showAsTab(vm.state.agents.single()) }
        assertEquals(listOf(WindowCommand.TakeOverTab("tab-bg"), WindowCommand.SetFocusedPane("tab-bg", "leaf-bg")), sent)
        assertNull(watch.state)
    }

    @Test
    fun `show as tab from a line that is not a background member does nothing`() {
        layout()
        give(
            listOf(task(listOf(member("listed", "listed", "working", recordId = "w1"), member("file", "file", "idle", sessionId = "abc")))),
            listOf(session("own"), session("w1", leaf = "leaf-w")),
        )
        runNow { vm.state.agents.forEach { vm.showAsTab(it) } }
        assertTrue(sent.isEmpty())
        assertNull(watch.state)
    }

    @Test
    fun `clearing the panels on reconnect removes blocks the server no longer holds`() {
        layout()
        give(listOf(task()), listOf(session("own")))
        windowState.updateTaskPanel("t1", listOf(block("note", TaskBlockContent.Text("old"))))
        assertEquals(1, vm.state.blocks.size)

        windowState.clearTaskPanels()
        assertTrue(vm.state.blocks.isEmpty())
        assertEquals(TaskPanelViewModel.EMPTY_TEXT, vm.state.emptyText)
    }

    @Test
    fun `link targets are only http, https and file`() {
        assertEquals("https://a.example", vm.linkTarget("https://a.example"))
        assertEquals("file:///tmp/x", vm.linkTarget("file:///tmp/x"))
        assertNull(vm.linkTarget("javascript:alert(1)"))
        assertNull(vm.linkTarget("data:text/html,x"))
    }

    @Test
    fun `a clock time is read in the viewer's zone and names the day when it is not today`() {
        val now = 1_800_000_000_000L // Friday 15 January 2027, 08:00 UTC
        val hour = 3_600_000L
        val utc = TimeZone.UTC
        assertEquals("06:00", TaskPanelViewModel.clockOf(now - 2 * hour, now, utc))
        assertEquals("08:00", TaskPanelViewModel.clockOf(now, now, utc))
        assertEquals("Thu 21:00", TaskPanelViewModel.clockOf(now - 11 * hour, now, utc))
        assertEquals("Sat 08:00", TaskPanelViewModel.clockOf(now - 6 * 24 * hour, now, utc), "six days back still names the weekday")
        assertEquals("8 Jan 08:00", TaskPanelViewModel.clockOf(now - 7 * 24 * hour, now, utc), "a week back names the date")
        assertEquals("16 Jan 08:00", TaskPanelViewModel.clockOf(now + 24 * hour, now, utc), "a time ahead of the clock names the date")
        // The same instants, read nine hours ahead of UTC (it is 17:00 on Friday there) and twelve behind (20:00 on Thursday).
        val ahead = UtcOffset(hours = 9).asTimeZone()
        val behind = UtcOffset(hours = -12).asTimeZone()
        assertEquals("15:00", TaskPanelViewModel.clockOf(now - 2 * hour, now, ahead))
        assertEquals("Thu 23:00", TaskPanelViewModel.clockOf(now - 18 * hour, now, ahead))
        assertEquals("18:00", TaskPanelViewModel.clockOf(now - 2 * hour, now, behind))
        assertEquals("Wed 23:00", TaskPanelViewModel.clockOf(now - 21 * hour, now, behind))
    }

    @Test
    fun `ages and state words`() {
        assertEquals("now", TaskPanelViewModel.ageOf(1_000, 30_000))
        assertEquals("now", TaskPanelViewModel.ageOf(90_000, 30_000))
        assertEquals("2m", TaskPanelViewModel.ageOf(0, 150_000))
        assertEquals("3h", TaskPanelViewModel.ageOf(0, 3 * 3_600_000L + 5))
        assertEquals("2d", TaskPanelViewModel.ageOf(0, 2 * 86_400_000L))
        assertEquals("waiting", TaskPanelViewModel.wordOf("idle"))
    }
}
