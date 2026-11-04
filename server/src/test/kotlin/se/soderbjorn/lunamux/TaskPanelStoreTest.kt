/**
 * Tests for [TaskPanelStore], [TaskPanelLimits] and [pressTaskButton]: what a task's panel keeps,
 * every size and shape it refuses, that blocks survive a restart, and that a button press only
 * ever types a stored line into a live own session.
 *
 * @see TaskPanelStore
 * @see TaskPanelLimits
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [TaskPanelStore]: ordering, replacing and removing blocks, persistence across a restart, every
 * limit and refusal, and what a button press types into the task's own session.
 *
 * Run by `:server:test`.
 *
 * @see TaskPanelStore
 */
class TaskPanelStoreTest {
    private val dir = File.createTempFile("lunamux-panel", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val dbFile = File(dir, "test.db")
    private val repo = SettingsRepository(dbFile)
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private var now = 10_000L
    private val tracker = AgentSessionTracker(repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" }).also { it.start() }
    private var nextId = 0
    private var paneGone = false
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { "leaf-$it" }, clock = { now }, sessionOfLeaf = { if (paneGone) null else it.removePrefix("leaf-") })
    private val store = TaskPanelStore(repo, index, clock = { now })
    private val taskId = index.createTask("Release", emptyList())

    private fun text(id: String, body: String = "hello", title: String? = null) =
        TaskPanelBlock(id, title, TaskBlockContent.Text(body), writtenAtMillis = 0)

    private fun refused(block: TaskPanelBlock): String =
        assertFailsWith<TaskException> { store.upsert(taskId, block, null) }.message!!

    @Test
    fun `set_task_panel keeps the order given and stamps writer and server time`() {
        now = 42_000
        store.setPanel(taskId, listOf(text("b"), text("a")), "release agent")
        val stored = store.blocksOf(taskId)
        assertEquals(listOf("b", "a"), stored.map { it.id })
        assertTrue(stored.all { it.writtenBy == "release agent" && it.writtenAtMillis == 42_000L })
    }

    @Test
    fun `set_task_panel replaces every block, so the old ones are gone`() {
        store.setPanel(taskId, listOf(text("a"), text("b")), null)
        store.setPanel(taskId, listOf(text("c")), null)
        assertEquals(listOf("c"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `upsert replaces a block where it stands and appends a new one`() {
        store.setPanel(taskId, listOf(text("a"), text("b"), text("c")), null)
        store.upsert(taskId, text("b", "changed"), "other")
        assertEquals(listOf("a", "b", "c"), store.blocksOf(taskId).map { it.id })
        assertEquals(TaskBlockContent.Text("changed"), store.blocksOf(taskId)[1].content)
        assertEquals("other", store.blocksOf(taskId)[1].writtenBy)
        store.upsert(taskId, text("d"), null)
        assertEquals(listOf("a", "b", "c", "d"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `remove keeps the others in order and the last removal empties the task`() {
        store.setPanel(taskId, listOf(text("a"), text("b"), text("c")), null)
        store.remove(taskId, "b")
        assertEquals(listOf("a", "c"), store.blocksOf(taskId).map { it.id })
        store.remove(taskId, "a"); store.remove(taskId, "c")
        assertTrue(taskId !in store.state.value)
    }

    @Test
    fun `removing a block the task does not have names it and lists the ones there are`() {
        store.setPanel(taskId, listOf(text("a")), null)
        val msg = assertFailsWith<TaskException> { store.remove(taskId, "ghost") }.message!!
        assertTrue("'ghost'" in msg && "'a'" in msg, msg)
    }

    @Test
    fun `an unknown task is refused by every call`() {
        assertTrue("Unknown task 'nope'" in assertFailsWith<TaskException> { store.setPanel("nope", emptyList(), null) }.message!!)
        assertTrue("Unknown task 'nope'" in assertFailsWith<TaskException> { store.upsert("nope", text("a"), null) }.message!!)
        assertTrue("Unknown task 'nope'" in assertFailsWith<TaskException> { store.remove("nope", "a") }.message!!)
        assertTrue("Unknown task 'nope'" in assertFailsWith<TaskException> { store.blocksOfKnown("nope") }.message!!)
    }

    @Test
    fun `blocks survive a restart with their order, writer and time`() {
        now = 7_000
        store.setPanel(
            taskId,
            listOf(
                text("t", "**bold**", "Title"),
                TaskPanelBlock("f", null, TaskBlockContent.Facts(listOf(TaskFact("k", "v"))), writtenAtMillis = 0),
                TaskPanelBlock("p", null, TaskBlockContent.Progress(done = 1, total = 3, label = "x"), writtenAtMillis = 0),
                TaskPanelBlock("a", null, TaskBlockContent.Actions(listOf(TaskButton("go", "Go", "/go"))), writtenAtMillis = 0),
            ),
            "writer",
        )
        val before = store.blocksOf(taskId)
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(before, reopened.blocksOf(taskId))
        assertEquals(listOf("t", "f", "p", "a"), reopened.blocksOf(taskId).map { it.id })
    }

    // ── Limits ──────────────────────────────────────────────────────────

    @Test
    fun `the id must be 1 to 64 characters of letters, digits and dot underscore colon dash`() {
        assertTrue("'bad id'" in refused(text("bad id")))
        assertTrue("not allowed" in refused(text("")))
        assertTrue("not allowed" in refused(text("x".repeat(65))))
        assertTrue("not allowed" in refused(text("slash/id")))
        store.upsert(taskId, text("ok.id_1:2-3"), null)
        store.upsert(taskId, text("y".repeat(64)), null)
    }

    @Test
    fun `the id agents is reserved`() {
        assertTrue("reserved" in refused(text("agents")))
        assertTrue("reserved" in refused(text("Agents")))
    }

    @Test
    fun `a panel holds at most 24 blocks and a full panel still lets a block be replaced`() {
        store.setPanel(taskId, (1..24).map { text("b$it") }, null)
        assertEquals(24, store.blocksOf(taskId).size)
        assertTrue("24" in refused(text("one-too-many")))
        store.upsert(taskId, text("b7", "new"), null)
        assertEquals(24, store.blocksOf(taskId).size)
        assertTrue("24" in assertFailsWith<TaskException> { store.setPanel(taskId, (1..25).map { text("c$it") }, null) }.message!!)
        assertEquals(24, store.blocksOf(taskId).size, "a refused set changes nothing")
    }

    @Test
    fun `repeated ids in one set are refused and name the id`() {
        val msg = assertFailsWith<TaskException> { store.setPanel(taskId, listOf(text("a"), text("a")), null) }.message!!
        assertTrue("'a'" in msg, msg)
    }

    @Test
    fun `title is 80 characters at most`() {
        store.upsert(taskId, text("t", title = "x".repeat(80)), null)
        assertTrue("title" in refused(text("t", title = "x".repeat(81))))
    }

    @Test
    fun `text is 4000 characters at most`() {
        store.upsert(taskId, text("t", "x".repeat(4000)), null)
        assertTrue("'t'" in refused(text("t", "x".repeat(4001))))
    }

    @Test
    fun `facts and links hold 30 items at most, with label and value lengths`() {
        fun facts(n: Int, label: String = "k", value: String = "v") =
            TaskPanelBlock("f", null, TaskBlockContent.Facts(List(n) { TaskFact(label, value) }), writtenAtMillis = 0)
        store.upsert(taskId, facts(30), null)
        assertTrue("31" in refused(facts(31)))
        store.upsert(taskId, facts(1, "l".repeat(80), "v".repeat(200)), null)
        assertTrue("label" in refused(facts(1, "l".repeat(81))))
        assertTrue("value" in refused(facts(1, value = "v".repeat(201))))
        assertTrue("no label" in refused(facts(1, label = " ")))

        fun links(n: Int, url: String = "https://example.com") =
            TaskPanelBlock("l", null, TaskBlockContent.Links(List(n) { TaskLink("l", url) }), writtenAtMillis = 0)
        store.upsert(taskId, links(30), null)
        assertTrue("31" in refused(links(31)))
        assertTrue("address" in refused(links(1, "https://example.com/" + "a".repeat(2000))))
    }

    @Test
    fun `a link keeps http, https and file addresses only`() {
        fun link(url: String) = TaskPanelBlock("l", null, TaskBlockContent.Links(listOf(TaskLink("x", url))), writtenAtMillis = 0)
        for (ok in listOf("http://a.b/c", "https://a.b", "HTTPS://A.B", "file:///tmp/x.md")) store.upsert(taskId, link(ok), null)
        for (bad in listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,x", "vbscript:x", "ftp://a.b", "//a.b", "a.b", "https://a b", "mailto:a@b.c")) {
            val msg = refused(link(bad))
            assertTrue("'x'" in msg && "http" in msg, "$bad -> $msg")
        }
    }

    @Test
    fun `a timeline keeps the newest 50 entries by time, in the order they came`() {
        val items = (0 until 60).map { TaskTimelineItem(atMillis = (if (it % 2 == 0) it else 1000 - it).toLong(), text = "e$it") }
        store.upsert(taskId, TaskPanelBlock("t", null, TaskBlockContent.Timeline(items), writtenAtMillis = 0), null)
        val kept = (store.blocksOf(taskId).single().content as TaskBlockContent.Timeline).items
        assertEquals(50, kept.size)
        val newest = items.sortedByDescending { it.atMillis }.take(50).toSet()
        assertEquals(newest, kept.toSet())
        assertEquals(items.filter { it in newest }, kept, "the order given is kept")
        assertTrue("entry 1" in refused(TaskPanelBlock("t", null, TaskBlockContent.Timeline(listOf(TaskTimelineItem(1, "x".repeat(201)))), writtenAtMillis = 0)))
    }

    @Test
    fun `progress needs done with total or a phase and done within total`() {
        fun progress(c: TaskBlockContent.Progress) = TaskPanelBlock("p", null, c, writtenAtMillis = 0)
        store.upsert(taskId, progress(TaskBlockContent.Progress(done = 2, total = 5)), null)
        store.upsert(taskId, progress(TaskBlockContent.Progress(phase = "review")), null)
        assertTrue("needs" in refused(progress(TaskBlockContent.Progress())))
        assertTrue("both" in refused(progress(TaskBlockContent.Progress(done = 1))))
        assertTrue("between" in refused(progress(TaskBlockContent.Progress(done = 6, total = 5))))
        assertTrue("between" in refused(progress(TaskBlockContent.Progress(done = -1, total = 5))))
        assertTrue("phase" in refused(progress(TaskBlockContent.Progress(phase = "p".repeat(81)))))
    }

    @Test
    fun `actions hold 8 buttons at most with label 40, text 500 and no control characters`() {
        fun actions(vararg b: TaskButton) = TaskPanelBlock("a", null, TaskBlockContent.Actions(b.toList()), writtenAtMillis = 0)
        store.upsert(taskId, actions(TaskButton("go", "l".repeat(40), "t".repeat(500))), null)
        assertTrue("9" in refused(actions(*Array(9) { TaskButton("b$it", "L", "t") })))
        assertTrue("label" in refused(actions(TaskButton("go", "l".repeat(41), "t"))))
        assertTrue("text" in refused(actions(TaskButton("go", "L", "t".repeat(501)))))
        for (bad in listOf("a\nb", "a\rb", "a\tb", "a\u001b[31mb", "a\u007fb")) {
            assertTrue("control" in refused(actions(TaskButton("go", "L", bad))), bad)
        }
        assertTrue("control" in refused(actions(TaskButton("go", "L\nM", "t"))))
        assertTrue("two buttons" in refused(actions(TaskButton("go", "A", "t"), TaskButton("go", "B", "t"))))
        assertTrue("at least one" in refused(actions()))
    }

    // ── pressTaskButton ─────────────────────────────────────────────────

    private val written = mutableListOf<Pair<String, String>>()
    private val live = mutableSetOf("own")

    private fun press(blockId: String = "act", buttonId: String = "go") =
        pressTaskButton(store, index, tracker, taskId, blockId, buttonId) { sid, bytes ->
            if (sid !in live) false else { written += sid to bytes.toString(Charsets.UTF_8); true }
        }

    private fun openAgentIn(pane: String) {
        now += 10
        tracker.onHook(
            PaneSnapshot(pane, "leaf-$pane", "/work", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$pane") },
        )
    }

    private fun withButton() {
        store.setPanel(taskId, listOf(TaskPanelBlock("act", null, TaskBlockContent.Actions(listOf(TaskButton("go", "Go", "/release 4.2"))), writtenAtMillis = 0)), null)
    }

    @Test
    fun `a press types the stored text and Enter into the task's own session and nothing else`() {
        openAgentIn("own"); openAgentIn("other")
        index.setOwnSession(taskId, "own")
        withButton()
        assertTrue(press())
        assertEquals(listOf("own" to "/release 4.2\r"), written)
    }

    @Test
    fun `an unknown block, button or task writes nothing`() {
        openAgentIn("own"); index.setOwnSession(taskId, "own"); withButton()
        assertFalse(press(blockId = "ghost"))
        assertFalse(press(buttonId = "ghost"))
        assertFalse(pressTaskButton(store, index, tracker, "ghost-task", "act", "go") { _, _ -> true })
        store.upsert(taskId, text("act"), null)
        assertFalse(press(), "a block that is not an actions block has no buttons")
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a task without an own session refuses the press`() {
        withButton()
        assertFalse(press())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `an own session whose agent has ended is refused even though its terminal is still there`() {
        openAgentIn("own"); index.setOwnSession(taskId, "own"); withButton()
        tracker.onHook(
            PaneSnapshot("own", "leaf-own", "/work", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionEnd"); put("session_id", "a-own") },
        )
        assertTrue("own" in live, "the terminal is still alive: it is now a bare shell")
        assertFalse(press())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `an own session whose pane is gone is refused, even though its old terminal id is still alive`() {
        openAgentIn("own"); index.setOwnSession(taskId, "own"); withButton()
        paneGone = true
        assertTrue("own" in live, "the old terminal id may now belong to another pane")
        assertFalse(press())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `an own session whose terminal is gone is refused`() {
        openAgentIn("own"); index.setOwnSession(taskId, "own"); withButton()
        live.clear()
        assertFalse(press())
        assertNull(written.firstOrNull())
    }
}
