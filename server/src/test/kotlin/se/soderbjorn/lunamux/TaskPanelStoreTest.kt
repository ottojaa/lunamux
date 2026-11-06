/**
 * Tests for [TaskPanelStore] and [TaskPanelLimits]: what a task's panel keeps, every size and
 * shape it refuses, and that blocks survive a restart.
 *
 * @see TaskPanelStore
 * @see TaskPanelLimits
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [TaskPanelStore]: ordering, replacing and removing blocks, persistence across a
 * restart, and every limit and refusal.
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
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { "leaf-$it" }, clock = { now })
    private val store = TaskPanelStore(repo, index, clock = { now })
    private val taskId = index.createTask("Release", emptyList())

    private fun text(id: String, body: String = "hello", title: String? = null) =
        TaskPanelBlock(id, title, TaskBlockContent.Progress(phase = body), writtenAtMillis = 0)

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
        assertEquals(TaskBlockContent.Progress(phase = "changed"), store.blocksOf(taskId)[1].content)
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
                text("t", "Writing", "Title"),
                TaskPanelBlock("l", null, TaskBlockContent.Links(listOf(TaskLink("PR", "Fix", "https://a.b/1"))), writtenAtMillis = 0),
                TaskPanelBlock("p", null, TaskBlockContent.Progress(done = 1, total = 3, label = "x"), writtenAtMillis = 0),
            ),
            "writer",
        )
        val before = store.blocksOf(taskId)
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(before, reopened.blocksOf(taskId))
        assertEquals(listOf("t", "l", "p"), reopened.blocksOf(taskId).map { it.id })
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
    fun `links hold 30 items at most, with an address of 2000 characters`() {
        fun links(n: Int, url: String = "https://example.com") =
            TaskPanelBlock("l", null, TaskBlockContent.Links(List(n) { TaskLink("link", "l", url) }), writtenAtMillis = 0)
        store.upsert(taskId, links(30), null)
        assertTrue("31" in refused(links(31)))
        assertTrue("address" in refused(links(1, "https://example.com/" + "a".repeat(2000))))
    }

    @Test
    fun `a link keeps http, https and file addresses only`() {
        fun link(url: String) = TaskPanelBlock("l", null, TaskBlockContent.Links(listOf(TaskLink("link", "x", url))), writtenAtMillis = 0)
        for (ok in listOf("http://a.b/c", "https://a.b", "HTTPS://A.B", "file:///tmp/x.md")) store.upsert(taskId, link(ok), null)
        for (bad in listOf("javascript:alert(1)", "JavaScript:alert(1)", "data:text/html,x", "vbscript:x", "ftp://a.b", "//a.b", "a.b", "https://a b", "mailto:a@b.c")) {
            val msg = refused(link(bad))
            assertTrue("'x'" in msg && "http" in msg, "$bad -> $msg")
        }
    }

    @Test
    fun `a link keeps a short kind of any word and a time that is not negative`() {
        fun link(kind: String = "PR", at: Long? = null) =
            TaskPanelBlock("l", null, TaskBlockContent.Links(listOf(TaskLink(kind, "x", "https://a.b", at))), writtenAtMillis = 0)
        for (ok in listOf("PR", "slack", "decision", "k".repeat(8), "anything")) store.upsert(taskId, link(ok), null)
        assertTrue("kind" in refused(link("k".repeat(9))))
        assertTrue("kind" in refused(link(" ")))
        assertTrue("kind" in refused(link("P\nR")))
        store.upsert(taskId, link(at = 0L), null)
        assertTrue("negative" in refused(link(at = -1L)))
    }

    @Test
    fun `a link with a bad kind and a label that is too long is refused for the label, which is not echoed`() {
        val longLabel = "L".repeat(81)
        val msg = refused(TaskPanelBlock("l", null, TaskBlockContent.Links(listOf(TaskLink(" ", longLabel, "https://a.b", -1L))), writtenAtMillis = 0))
        assertTrue("label" in msg, msg)
        assertTrue(longLabel !in msg, msg)
    }

    @Test
    fun `links survive a restart with their kind and with and without a time`() {
        val content = TaskBlockContent.Links(
            listOf(TaskLink("PR", "With time", "https://a.b/1", 1_700_000_000_000L), TaskLink("link", "No time", "file:///tmp/x.md")),
        )
        store.upsert(taskId, TaskPanelBlock("l", "Links", content, writtenAtMillis = 0), "writer")
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(content, reopened.blocksOf(taskId).single().content)
    }

    @Test
    fun `a stored block of a kind the panel no longer has is dropped on load and the rest stays`() {
        store.upsert(taskId, text("keep"), null)
        val repo = SettingsRepository(dbFile)
        val rows = repo.loadTaskPanelRows()
        repo.replaceTaskPanel(
            taskId,
            rows + rows.single().copy(blockId = "old", position = 1, kind = "text", body = """{"kind":"text","markdown":"x"}"""),
        )
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(listOf("keep"), reopened.blocksOf(taskId).map { it.id })
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
}
