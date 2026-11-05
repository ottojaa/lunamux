/**
 * Tests for [TaskFilesWatcher]: parsing task files (the current `open` action and the earlier
 * `lead` spelling of it), skipping bad ones with a single warning, reloading on change, the
 * folders it reads and the keys it gives members.
 *
 * Matching members to sessions is [TaskIndex]'s job and is tested in [TaskIndexTest].
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
    private val watcher = TaskFilesWatcher(listOf(dir)) { warnings += it }

    private fun folder(name: String) = File(work, name).apply { mkdirs() }.canonicalPath

    private fun write(name: String, text: String, modified: Long? = null) {
        val f = File(dir, name)
        f.writeText(text)
        f.setLastModified(modified ?: (f.lastModified() + 2_000))
    }

    private fun taskJson(id: String = "t1", open: String = "", members: String = "", extra: String = "") =
        """{"version":2,"id":"$id","title":"Task $id","tag":"PROGRAMME","repo":"lunamux",
        "link":{"label":"AOV","url":"https://example.org/board"},"updatedAt":"2026-11-01T13:31:51Z"
        ${if (open.isEmpty()) "" else ""","open":$open"""},"members":[$members] $extra}"""

    private fun open(folder: String = folder("open")) = """{"command":"claude 'go'","folder":"$folder"}"""

    private fun member(title: String, state: String = "done", match: String = "", extra: String = "") =
        """{"title":"$title","state":"$state","since":"2026-11-01T11:41:43Z" ${if (match.isEmpty()) "" else ""","match":$match"""} $extra}"""

    @Test
    fun `a valid file becomes a task and its open command stays on the server`() {
        write("t1.json", taskJson(open = open(), members = member("Peek", "needs_you")))
        watcher.reload()
        val t = watcher.files.value.single()
        assertEquals("t1", t.id)
        assertEquals("Task t1", t.title)
        assertEquals("lunamux", t.repo)
        assertEquals("claude 'go'", t.open!!.command)
        assertEquals(folder("open"), t.open!!.folder)
        assertEquals("needs_you", t.members.single().member.state)
        assertEquals(java.time.Instant.parse("2026-11-01T11:41:43Z").toEpochMilli(), t.members.single().member.sinceMillis)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a file with no open action is a valid task`() {
        write("t1.json", taskJson(members = member("Peek")))
        watcher.reload()
        assertNull(watcher.files.value.single().open)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a file written for the lead spelling is read, its lead as the open action`() {
        write(
            "old.json",
            """{"version":1,"id":"old","title":"Old","tag":"PROGRAMME",
            "lead":{"command":"claude 'lead'","folder":"${folder("lead")}","sessionId":"home-id"},
            "members":[${member("m", "working")}]}""",
        )
        watcher.reload()
        val t = watcher.files.value.single()
        assertEquals("claude 'lead'", t.open!!.command)
        assertEquals(folder("lead"), t.open!!.folder)
        assertEquals("working", t.members.single().member.state)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `when both are present open wins over lead`() {
        write("t1.json", """{"id":"t1","title":"T","open":{"command":"new","folder":"${folder("a")}"},"lead":{"command":"old","folder":"${folder("b")}"}}""")
        watcher.reload()
        assertEquals("new", watcher.files.value.single().open!!.command)
    }

    @Test
    fun `a task carries its file's modification time, also through a touch that changes nothing else`() {
        write("t1.json", taskJson(members = member("Peek", "working")), modified = 1_700_000_000_000)
        watcher.reload()
        assertEquals(1_700_000_000_000, watcher.files.value.single().modified)
        File(dir, "t1.json").setLastModified(1_700_000_600_000)
        watcher.reload()
        assertEquals(1_700_000_600_000, watcher.files.value.single().modified)
    }

    @Test
    fun `done is a member state and the older idle is read as done`() {
        write("t1.json", taskJson(members = member("A", "done") + "," + member("B", "idle")))
        watcher.reload()
        assertEquals(listOf("done", "done"), watcher.files.value.single().members.map { it.member.state })
    }

    @Test
    fun `a member without since takes the file's modification time`() {
        write("t1.json", """{"id":"t1","title":"T","members":[{"title":"m","state":"idle"}]}""", modified = 1_700_000_000_000)
        watcher.reload()
        assertEquals(1_700_000_000_000, watcher.files.value.single().members.single().member.sinceMillis)
    }

    @Test
    fun `unknown fields, tag, link, version and updatedAt are accepted and ignored`() {
        write("t1.json", taskJson(extra = ""","future":{"x":1}"""))
        watcher.reload()
        assertEquals("t1", watcher.files.value.single().id)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `invalid files are skipped with one warning each until they change`() {
        write("broken.json", "{ not json")
        write("noid.json", """{"title":"t"}""")
        write("notitle.json", """{"id":"x"}""")
        write("badstate.json", taskJson(id = "bs", members = member("m", "sleeping")))
        write("relative.json", taskJson(id = "rel", open = open("work/open")))
        write("nocommand.json", """{"id":"nc","title":"t","open":{"folder":"/tmp"}}""")
        write("good.json", taskJson(id = "ok"))
        watcher.reload()
        watcher.reload()
        watcher.reload()
        assertEquals(listOf("ok"), watcher.files.value.map { it.id })
        assertEquals(6, warnings.size)
        write("broken.json", "{ still not json")
        watcher.reload()
        assertEquals(7, warnings.size)
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
        write("t1.json", taskJson(members = member("one")))
        watcher.reload()
        assertEquals(1, watcher.files.value.single().members.size)
        write("t1.json", taskJson(members = member("one") + "," + member("two", "working")))
        watcher.reload()
        assertEquals(2, watcher.files.value.single().members.size)
        File(dir, "t1.json").delete()
        watcher.reload()
        assertTrue(watcher.files.value.isEmpty())
    }

    @Test
    fun `a missing folder is an empty list, not an error`() {
        val w = TaskFilesWatcher(listOf(File(dir, "absent"))) { warnings += it }
        w.reload()
        assertTrue(w.files.value.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `several folders are read together`() {
        val other = File(work, "other-tasks").apply { mkdirs() }
        File(other, "x.json").writeText(taskJson(id = "x"))
        write("t1.json", taskJson(id = "a"))
        val w = TaskFilesWatcher(listOf(dir, other)) { warnings += it }
        w.reload()
        assertEquals(listOf("a", "x"), w.files.value.map { it.id })
    }

    @Test
    fun `a member's folders and paths are kept in canonical form, so a symlinked folder compares equal`() {
        val real = folder("real")
        val link = File(work, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(real).toPath())
        write("t1.json", taskJson(open = open(link.path), members = member("m", match = """{"folder":"${link.path}","agentSessionId":"abc"}""")))
        watcher.reload()
        val t = watcher.files.value.single()
        assertEquals(real, t.open!!.folder)
        assertEquals(real, t.members.single().matchFolder)
        assertEquals("abc", t.members.single().matchSessionId)
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
    fun `a member keeps the session id the file gives it, trimmed, and none when it is blank or absent`() {
        write(
            "t1.json",
            taskJson(
                members = listOf(
                    member("Has id", extra = ",\"sessionId\":\"  ab12cd  \""),
                    member("Blank id", extra = ",\"sessionId\":\"   \""),
                    member("No id"),
                ).joinToString(","),
            ),
        )
        watcher.reload()
        assertEquals(listOf("ab12cd", null, null), watcher.files.value.single().members.map { it.member.sessionId })
    }

    @Test
    fun `members get distinct keys, even when two carry one session id or none`() {
        write(
            "t1.json",
            taskJson(
                members = listOf(
                    member("First", extra = ",\"sessionId\":\"same\""),
                    member("Second", extra = ",\"sessionId\":\"same\""),
                    member("Third"),
                    member("Fourth"),
                ).joinToString(","),
            ),
        )
        watcher.reload()
        val keys = watcher.files.value.single().members.map { it.member.key }
        assertEquals(listOf("same", "same#2", "#3", "#4"), keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the folders read are the tasks folder and the earlier one unless a property names them`() {
        val tasks = System.getProperty("lunamux.tasksDir")
        val groups = System.getProperty("lunamux.agentGroupsDir")
        try {
            System.clearProperty("lunamux.tasksDir")
            System.clearProperty("lunamux.agentGroupsDir")
            val home = System.getProperty("user.home")
            assertEquals(listOf(File(home, ".lunamux/tasks"), File(home, ".lunamux/agent-groups")), AppPaths.taskDirs())

            System.setProperty("lunamux.tasksDir", "/scratch/tasks")
            assertEquals(listOf(File("/scratch/tasks")), AppPaths.taskDirs())

            System.clearProperty("lunamux.tasksDir")
            System.setProperty("lunamux.agentGroupsDir", "/scratch/groups")
            assertEquals(listOf(File("/scratch/groups")), AppPaths.taskDirs())

            System.setProperty("lunamux.tasksDir", "/scratch/tasks")
            assertEquals(listOf(File("/scratch/tasks"), File("/scratch/groups")), AppPaths.taskDirs())
        } finally {
            if (tasks == null) System.clearProperty("lunamux.tasksDir") else System.setProperty("lunamux.tasksDir", tasks)
            if (groups == null) System.clearProperty("lunamux.agentGroupsDir") else System.setProperty("lunamux.agentGroupsDir", groups)
        }
    }
}
