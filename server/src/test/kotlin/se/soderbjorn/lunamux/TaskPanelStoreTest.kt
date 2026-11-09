/**
 * Tests for [TaskPanelStore]: what a task's panel keeps and in what order, every size and shape
 * it refuses, and that blocks survive a restart.
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    private fun links(vararg l: TaskLink, id: String = "l") = TaskPanelBlock(id, null, TaskBlockContent.Links(l.toList()), writtenAtMillis = 0)
    private fun link(url: String = "https://a.b", kind: String = "PR", label: String = "x", at: Long? = null) = TaskLink(kind, label, url, at)
    private fun progress(c: TaskBlockContent.Progress) = TaskPanelBlock("p", null, c, writtenAtMillis = 0)
    private fun ids() = store.blocksOf(taskId).map { it.id }

    @Test
    fun `a panel keeps the order given, stamps writer and server time, and a set replaces what was there`() {
        now = 42_000
        store.setPanel(taskId, listOf(text("b"), text("a")), "release agent")
        assertEquals(listOf("b", "a"), ids())
        assertTrue(store.blocksOf(taskId).all { it.writtenBy == "release agent" && it.writtenAtMillis == 42_000L })
        store.setPanel(taskId, listOf(text("c")), null)
        assertEquals(listOf("c"), ids())
    }

    @Test
    fun `upsert replaces a block where it stands or appends it, and remove empties the task with its last block`() {
        store.setPanel(taskId, listOf(text("a"), text("b"), text("c")), null)
        store.upsert(taskId, text("b", "changed"), "other")
        store.upsert(taskId, text("d"), null)
        assertEquals(listOf("a", "b", "c", "d"), ids())
        assertEquals("other", store.blocksOf(taskId)[1].writtenBy)
        store.remove(taskId, "b")
        assertEquals(listOf("a", "c", "d"), ids())
        for (id in listOf("a", "c", "d")) store.remove(taskId, id)
        assertTrue(taskId !in store.state.value)
    }

    @Test
    fun `a refusal names the offender, and a refused set changes nothing`() {
        store.setPanel(taskId, listOf(text("a")), null)
        val ghost = assertFailsWith<TaskException> { store.remove(taskId, "ghost") }.message!!
        assertTrue("'ghost'" in ghost && "'a'" in ghost, ghost)
        assertTrue("'a'" in assertFailsWith<TaskException> { store.setPanel(taskId, listOf(text("a"), text("a")), null) }.message!!)
        assertTrue("24" in assertFailsWith<TaskException> { store.setPanel(taskId, (1..25).map { text("c$it") }, null) }.message!!)
        assertEquals(listOf("a"), ids())
        for (call in listOf<() -> Unit>({ store.setPanel("nope", emptyList(), null) }, { store.upsert("nope", text("a"), null) }, { store.remove("nope", "a") }, { store.blocksOfKnown("nope") })) {
            assertTrue("Unknown task 'nope'" in assertFailsWith<TaskException> { call() }.message!!)
        }
    }

    @Test
    fun `an id is 1 to 64 safe characters and not the reserved agents, and a full panel of 24 still lets a block be replaced`() {
        for (bad in listOf("bad id", "", "x".repeat(65), "slash/id")) assertTrue("not allowed" in refused(text(bad)), bad)
        assertTrue("reserved" in refused(text("Agents")))
        store.upsert(taskId, text("ok.id_1:2-3"), null)
        store.setPanel(taskId, (1..24).map { text("b$it") }, null)
        assertTrue("24" in refused(text("one-too-many")))
        store.upsert(taskId, text("b7", "new"), null)
        assertEquals(24, ids().size)
    }

    @Test
    fun `a title is 80 characters at most, and progress needs done with total or a phase`() {
        store.upsert(taskId, text("t", title = "x".repeat(80)), null)
        assertTrue("title" in refused(text("t", title = "x".repeat(81))))
        store.upsert(taskId, progress(TaskBlockContent.Progress(done = 2, total = 5)), null)
        assertTrue("needs" in refused(progress(TaskBlockContent.Progress())))
        assertTrue("both" in refused(progress(TaskBlockContent.Progress(done = 1))))
        assertTrue("between" in refused(progress(TaskBlockContent.Progress(done = 6, total = 5))))
        assertTrue("phase" in refused(progress(TaskBlockContent.Progress(phase = "p".repeat(81)))))
    }

    @Test
    fun `links keep http, https and file addresses only, at most 30 of them, with a short kind and no negative time`() {
        for (ok in listOf("http://a.b/c", "HTTPS://A.B", "file:///tmp/x.md")) store.upsert(taskId, links(link(ok)), null)
        for (bad in listOf("javascript:alert(1)", "data:text/html,x", "ftp://a.b", "//a.b", "a.b", "https://a b")) {
            val msg = refused(links(link(bad)))
            assertTrue("'x'" in msg && "http" in msg, "$bad -> $msg")
        }
        store.upsert(taskId, links(*Array(30) { link() }), null)
        assertTrue("31" in refused(links(*Array(31) { link() })))
        assertTrue("address" in refused(links(link("https://a.b/" + "a".repeat(2000)))))
        for (bad in listOf("k".repeat(9), " ", "P\nR")) assertTrue("kind" in refused(links(link(kind = bad))), bad)
        store.upsert(taskId, links(link(kind = "anything", at = 0L)), null)
        assertTrue("negative" in refused(links(link(at = -1L))))
        val long = "L".repeat(81)
        val msg = refused(links(link(kind = " ", label = long, at = -1L)))
        assertTrue("label" in msg && long !in msg, "the first problem is named and the text is not echoed: $msg")
    }

    @Test
    fun `a timeline keeps the newest 50 entries by time in the order they came, and an entry is 200 characters at most`() {
        val items = (0 until 60).map { TaskTimelineItem(atMillis = (if (it % 2 == 0) it else 1000 - it).toLong(), text = "e$it") }
        store.upsert(taskId, TaskPanelBlock("t", null, TaskBlockContent.Timeline(items), writtenAtMillis = 0), null)
        val kept = (store.blocksOf(taskId).single().content as TaskBlockContent.Timeline).items
        val newest = items.sortedByDescending { it.atMillis }.take(50).toSet()
        assertEquals(items.filter { it in newest }, kept)
        assertTrue("entry 1" in refused(TaskPanelBlock("t", null, TaskBlockContent.Timeline(listOf(TaskTimelineItem(1, "x".repeat(201)))), writtenAtMillis = 0)))
    }

    @Test
    fun `blocks survive a restart with order, writer, time and link fields, and a block of a kind the panel no longer has is dropped`() {
        now = 7_000
        val content = TaskBlockContent.Links(listOf(link(at = 1_700_000_000_000L), link("file:///tmp/x.md", "link")))
        store.setPanel(
            taskId,
            listOf(text("t", "Writing", "Title"), TaskPanelBlock("l", "Links", content, writtenAtMillis = 0), progress(TaskBlockContent.Progress(done = 1, total = 3, label = "x"))),
            "writer",
        )
        val old = repo.loadTaskPanelRows().first()
        repo.replaceTaskPanel(taskId, repo.loadTaskPanelRows() + old.copy(blockId = "old", position = 3, kind = "text", body = """{"kind":"text","markdown":"x"}"""))
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(store.blocksOf(taskId), reopened.blocksOf(taskId))
        assertEquals(listOf("t", "l", "p"), reopened.blocksOf(taskId).map { it.id })
    }
}
