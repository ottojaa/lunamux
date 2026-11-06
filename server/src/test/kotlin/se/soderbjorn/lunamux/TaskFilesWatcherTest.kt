/**
 * Tests for [TaskFilesWatcher]: parsing the minimal task file (`id`, `title`, `repo`, `open`),
 * ignoring the keys older files carry, skipping bad files with a single warning, reloading on
 * change and the folder it reads.
 *
 * Which sessions belong to a task is [TaskIndex]'s job and is tested in [TaskIndexTest].
 *
 * @see TaskFilesWatcher
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AppPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskFilesWatcherTest {
    private val dir = File.createTempFile("lunamux-tasks", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val work = File.createTempFile("lunamux-work", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val warnings = mutableListOf<String>()
    private val watcher = TaskFilesWatcher(dir) { warnings += it }

    private fun folder(name: String) = File(work, name).apply { mkdirs() }.canonicalPath

    private fun write(name: String, text: String, modified: Long? = null) {
        val f = File(dir, name)
        f.writeText(text)
        f.setLastModified(modified ?: (f.lastModified() + 2_000))
    }

    private fun taskJson(id: String = "t1", open: String = "") =
        """{"id":"$id","title":"Task $id","repo":"lunamux"${if (open.isEmpty()) "" else ""","open":$open"""}}"""

    private fun open(folder: String = folder("open")) = """{"command":"claude 'go'","folder":"$folder"}"""

    @Test
    fun `a valid file becomes a task and its open command stays on the server`() {
        write("t1.json", taskJson(open = open()))
        watcher.reload()
        val t = watcher.files.value.single()
        assertEquals("t1", t.id)
        assertEquals("Task t1", t.title)
        assertEquals("lunamux", t.repo)
        assertEquals("claude 'go'", t.open!!.command)
        assertEquals(folder("open"), t.open!!.folder)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a file with no open action is a valid task`() {
        write("t1.json", taskJson())
        watcher.reload()
        assertNull(watcher.files.value.single().open)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a file written with the older keys still parses and the keys are ignored`() {
        write(
            "old.json",
            """{"version":2,"id":"old","title":"Old","tag":"PROGRAMME","repo":"lunamux",
            "link":{"label":"AOV","url":"https://example.org/board"},"updatedAt":"2026-11-01T13:31:51Z",
            "lead":{"command":"claude 'lead'","folder":"${folder("lead")}","sessionId":"home-id"},
            "members":[{"title":"m","state":"working","since":"2026-11-01T11:41:43Z",
            "match":{"folder":"${folder("run")}","agentSessionId":"abc"},"sessionId":"s1"}],
            "future":{"x":1}}""",
        )
        watcher.reload()
        val t = watcher.files.value.single()
        assertEquals("old", t.id)
        assertEquals("Old", t.title)
        assertEquals("lunamux", t.repo)
        assertNull(t.open)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a task carries its file's modification time, also through a touch that changes nothing else`() {
        write("t1.json", taskJson(), modified = 1_700_000_000_000)
        watcher.reload()
        assertEquals(1_700_000_000_000, watcher.files.value.single().modified)
        File(dir, "t1.json").setLastModified(1_700_000_600_000)
        watcher.reload()
        assertEquals(1_700_000_600_000, watcher.files.value.single().modified)
    }

    @Test
    fun `invalid files are skipped with one warning each until they change`() {
        write("broken.json", "{ not json")
        write("noid.json", """{"title":"t"}""")
        write("notitle.json", """{"id":"x"}""")
        write("relative.json", taskJson(id = "rel", open = open("work/open")))
        write("nocommand.json", """{"id":"nc","title":"t","open":{"folder":"/tmp"}}""")
        write("good.json", taskJson(id = "ok"))
        watcher.reload()
        watcher.reload()
        watcher.reload()
        assertEquals(listOf("ok"), watcher.files.value.map { it.id })
        assertEquals(5, warnings.size)
        write("broken.json", "{ still not json")
        watcher.reload()
        assertEquals(6, warnings.size)
    }

    @Test
    fun `only json files that are not dot-files are read`() {
        write("t1.json", taskJson(id = "a"))
        write(".t2.json", taskJson(id = "b"))
        write("t3.json.tmp", taskJson(id = "c"))
        watcher.reload()
        assertEquals(listOf("a"), watcher.files.value.map { it.id })
    }

    @Test
    fun `a changed file is read again, a removed file removes the task`() {
        write("t1.json", taskJson())
        watcher.reload()
        assertEquals("Task t1", watcher.files.value.single().title)
        write("t1.json", taskJson().replace("Task t1", "Renamed task"))
        watcher.reload()
        assertEquals("Renamed task", watcher.files.value.single().title)
        File(dir, "t1.json").delete()
        watcher.reload()
        assertTrue(watcher.files.value.isEmpty())
    }

    @Test
    fun `a missing folder is an empty list, not an error`() {
        val w = TaskFilesWatcher(File(dir, "absent")) { warnings += it }
        w.reload()
        assertTrue(w.files.value.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `the open folder is kept in canonical form, so a symlinked folder compares equal`() {
        val real = folder("real")
        val link = File(work, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(real).toPath())
        write("t1.json", taskJson(open = open(link.path)))
        watcher.reload()
        assertEquals(real, watcher.files.value.single().open!!.folder)
    }

    @Test
    fun `for a repeated id the newest file wins and the other is reported once`() {
        write("a.json", taskJson(id = "dup").replace("Task dup", "Older"), modified = 1_000_000)
        write("b.json", taskJson(id = "dup").replace("Task dup", "Newer"), modified = 2_000_000)
        watcher.reload()
        assertEquals("Newer", watcher.files.value.single().title)
        assertNotNull(warnings.singleOrNull { "repeats the id" in it })
        watcher.reload()
        watcher.reload()
        assertEquals(1, warnings.count { "repeats the id" in it })
    }

    @Test
    fun `the folder read is the tasks folder unless a property names another`() {
        val tasks = System.getProperty("lunamux.tasksDir")
        try {
            System.clearProperty("lunamux.tasksDir")
            val home = System.getProperty("user.home")
            assertEquals(File(home, ".lunamux/tasks"), AppPaths.taskDir())

            System.setProperty("lunamux.tasksDir", "/scratch/tasks")
            assertEquals(File("/scratch/tasks"), AppPaths.taskDir())
        } finally {
            if (tasks == null) System.clearProperty("lunamux.tasksDir") else System.setProperty("lunamux.tasksDir", tasks)
        }
    }
}
