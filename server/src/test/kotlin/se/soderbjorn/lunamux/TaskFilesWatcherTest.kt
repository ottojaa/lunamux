/**
 * Tests for [TaskFilesWatcher]: which task files become tasks, which are skipped and said so once,
 * and how a change is picked up. Which sessions belong to a task is [TaskIndexTest]'s subject.
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AppPaths
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `a file becomes a task with its open command, a file with none is a task too, and the keys older files carry are ignored`() {
        write("t1.json", taskJson(open = open()))
        write(
            "old.json",
            """{"version":2,"id":"old","title":"Old","tag":"PROGRAMME","repo":"lunamux","link":{"label":"AOV","url":"https://example.org/board"},
            "lead":{"command":"claude 'lead'","folder":"${folder("lead")}"},"members":[{"title":"m","match":{"folder":"${folder("run")}"}}],"future":{"x":1}}""",
        )
        watcher.reload()
        val (old, t) = watcher.files.value.sortedBy { it.id }
        assertEquals(listOf("Task t1", "lunamux", "claude 'go'", folder("open")), listOf(t.title, t.repo, t.open!!.command, t.open!!.folder))
        assertEquals(listOf("Old", null), listOf(old.title, old.open))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `invalid files are skipped with one warning each until they change, and dot-files, other extensions and a missing folder are not read`() {
        write("broken.json", "{ not json")
        write("noid.json", """{"title":"t"}""")
        write("notitle.json", """{"id":"x"}""")
        write("relative.json", taskJson(id = "rel", open = open("work/open")))
        write("nocommand.json", """{"id":"nc","title":"t","open":{"folder":"/tmp"}}""")
        write("good.json", taskJson(id = "ok"))
        write(".t2.json", taskJson(id = "dot"))
        write("t3.json.tmp", taskJson(id = "tmp"))
        repeat(3) { watcher.reload() }
        assertEquals(listOf("ok"), watcher.files.value.map { it.id })
        assertEquals(5, warnings.size)
        write("broken.json", "{ still not json")
        watcher.reload()
        assertEquals(6, warnings.size)

        val absent = TaskFilesWatcher(File(dir, "absent")) { warnings += it }.apply { reload() }
        assertTrue(absent.files.value.isEmpty() && warnings.size == 6)
    }

    @Test
    fun `a changed file is read again with its new modification time, and a removed file removes the task`() {
        write("t1.json", taskJson(), modified = 1_700_000_000_000)
        watcher.reload()
        assertEquals(1_700_000_000_000, watcher.files.value.single().modified)
        write("t1.json", taskJson().replace("Task t1", "Renamed task"), modified = 1_700_000_600_000)
        watcher.reload()
        assertEquals(listOf("Renamed task", 1_700_000_600_000), watcher.files.value.single().let { listOf(it.title, it.modified) })
        File(dir, "t1.json").delete()
        watcher.reload()
        assertTrue(watcher.files.value.isEmpty())
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
        repeat(3) { watcher.reload() }
        assertEquals("Newer", watcher.files.value.single().title)
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
