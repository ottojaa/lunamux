/**
 * Tests for [AgentGroupsWatcher]: parsing group files, skipping bad ones with a
 * single warning, reloading on change, and matching members and the lead to
 * session records.
 *
 * @see AgentGroupsWatcher
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.emptyFlow
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentGroupsWatcherTest {
    private val dir = File.createTempFile("lunamux-groups", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val work = File.createTempFile("lunamux-work", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val warnings = mutableListOf<String>()
    private var records = listOf<AgentSessionRecord>()
    private val watcher = AgentGroupsWatcher(dir, { records }, emptyFlow<Unit>(), { warnings += it })

    private fun folder(name: String) = File(work, name).apply { mkdirs() }.canonicalPath

    private fun write(name: String, text: String, modified: Long? = null) {
        val f = File(dir, name)
        f.writeText(text)
        f.setLastModified(modified ?: (f.lastModified() + 2_000))
    }

    private fun groupJson(
        id: String = "g1",
        leadFolder: String = folder("lead"),
        members: String = "",
        extra: String = "",
    ) = """{"version":1,"id":"$id","title":"Group $id","tag":"PROGRAMME","repo":"lunamux",
        "link":{"label":"AOV","url":"https://example.org/board"},"updatedAt":"2026-11-01T13:31:51Z",
        "lead":{"command":"claude 'go'","folder":"$leadFolder"},"members":[$members] $extra}"""

    private fun member(title: String, state: String = "idle", match: String = "", extra: String = "") =
        """{"title":"$title","state":"$state","since":"2026-11-01T11:41:43Z" ${if (match.isEmpty()) "" else ""","match":$match"""} $extra}"""

    private fun rec(
        id: String,
        cwd: String?,
        state: String = "working",
        agentSessionId: String? = null,
        createdAt: Long = 1,
        leafId: String? = "leaf-$id",
    ) = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = agentSessionId, cwd = cwd, state = state,
        stateSince = 500, nowLine = "Bash ls", leafId = leafId, createdAt = createdAt, lastSaid = "said $id",
    )

    @Test
    fun `a valid file becomes a group and its command stays on the server`() {
        write("g1.json", groupJson(members = member("Peek", "needs_you")))
        watcher.reload()
        val g = watcher.groups.value.single()
        assertEquals("g1", g.id)
        assertEquals("PROGRAMME", g.tag)
        assertEquals("AOV", g.linkLabel)
        assertEquals("https://example.org/board", g.linkUrl)
        assertEquals("needs_you", g.members.single().state)
        assertEquals(java.time.Instant.parse("2026-11-01T11:41:43Z").toEpochMilli(), g.members.single().sinceMillis)
        assertNull(g.lead.recordId)
        assertEquals(false, g.lead.live)
        assertEquals("claude 'go'", watcher.find("g1")!!.second)
        assertNull(watcher.find("nope"))
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a group carries its file's modification time, also through a touch that changes nothing else`() {
        write("g1.json", groupJson(members = member("Peek", "working")), modified = 1_700_000_000_000)
        watcher.reload()
        assertEquals(1_700_000_000_000, watcher.groups.value.single().refreshedAtMillis)

        // The writer only touches an unchanged file: same content, newer time.
        File(dir, "g1.json").setLastModified(1_700_000_600_000)
        watcher.reload()
        assertEquals(1_700_000_600_000, watcher.groups.value.single().refreshedAtMillis)
        assertEquals("working", watcher.groups.value.single().members.single().state)
    }

    @Test
    fun `unknown fields are ignored and a link that is not http is dropped`() {
        write("g1.json", groupJson(extra = ""","future":{"x":1}""").replace("https://example.org/board", "javascript:alert(1)"))
        watcher.reload()
        val g = watcher.groups.value.single()
        assertNull(g.linkUrl)
        assertNull(g.linkLabel)
    }

    @Test
    fun `invalid files are skipped with one warning each until they change`() {
        write("broken.json", "{ not json")
        write("noid.json", """{"title":"t","lead":{"command":"c","folder":"/tmp"}}""")
        write("nolead.json", """{"id":"x","title":"t"}""")
        write("badstate.json", groupJson(id = "bs", members = member("m", "sleeping")))
        write("relative.json", groupJson(id = "rel", leadFolder = "work/lead"))
        write("good.json", groupJson(id = "ok"))
        watcher.reload()
        watcher.reload()
        watcher.reload()
        assertEquals(listOf("ok"), watcher.groups.value.map { it.id })
        assertEquals(5, warnings.size)
        write("broken.json", "{ still not json")
        watcher.reload()
        assertEquals(6, warnings.size)
    }

    @Test
    fun `a changed file is read again, a removed file removes the group`() {
        write("g1.json", groupJson(members = member("one")))
        watcher.reload()
        assertEquals(1, watcher.groups.value.single().members.size)
        write("g1.json", groupJson(members = member("one") + "," + member("two", "working")))
        watcher.reload()
        assertEquals(2, watcher.groups.value.single().members.size)
        File(dir, "g1.json").delete()
        watcher.reload()
        assertTrue(watcher.groups.value.isEmpty())
        assertNull(watcher.find("g1"))
    }

    @Test
    fun `a missing folder is an empty list, not an error`() {
        val w = AgentGroupsWatcher(File(dir, "absent"), { records }, emptyFlow<Unit>(), { warnings += it })
        w.reload()
        assertTrue(w.groups.value.isEmpty())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `a member matches the newest record with its agent session id`() {
        write("g1.json", groupJson(members = member("m", "idle", """{"agentSessionId":"abc"}""")))
        records = listOf(
            rec("old", folder("a"), agentSessionId = "abc", createdAt = 1),
            rec("new", folder("b"), agentSessionId = "abc", createdAt = 9, state = "needs_you").copy(question = "Merge?"),
            rec("other", folder("c"), agentSessionId = "zzz", createdAt = 99),
        )
        watcher.reload()
        val m = watcher.groups.value.single().members.single()
        assertEquals("new", m.recordId)
        assertEquals("needs_you", m.state)
        assertEquals("Merge?", m.line)
        assertEquals(500, m.sinceMillis)
        assertEquals("m", m.title)
    }

    @Test
    fun `a member matches an open record in its folder or below, not a sibling with the same prefix`() {
        val wt = folder("wt")
        write("g1.json", groupJson(members = member("m", "idle", """{"folder":"$wt"}""")))
        records = listOf(
            rec("sibling", folder("wt-other")),
            rec("ended", wt, state = "ended"),
            rec("below", File(wt, "sub").apply { mkdirs() }.canonicalPath, createdAt = 3),
            rec("exact", wt, createdAt = 7),
        )
        watcher.reload()
        assertEquals("exact", watcher.groups.value.single().members.single().recordId)
        records = records.filter { it.id != "exact" }
        watcher.resolve()
        assertEquals("below", watcher.groups.value.single().members.single().recordId)
        records = records.filter { it.id != "below" }
        watcher.resolve()
        val m = watcher.groups.value.single().members.single()
        assertNull(m.recordId)
        assertEquals("idle", m.state)
    }

    @Test
    fun `a matched member follows its record live and falls back to the file when it ends`() {
        val wt = folder("wt")
        write("g1.json", groupJson(members = member("m", "needs_you", """{"folder":"$wt"}""", ""","line":"from file" """)))
        records = listOf(rec("r", wt))
        watcher.reload()
        assertEquals("working", watcher.groups.value.single().members.single().state)
        assertEquals("Bash ls", watcher.groups.value.single().members.single().line)
        records = listOf(rec("r", wt, state = "ended"))
        watcher.resolve()
        val m = watcher.groups.value.single().members.single()
        assertEquals("needs_you", m.state)
        assertEquals("from file", m.line)
    }

    @Test
    fun `two members never take the same record`() {
        val wt = folder("wt")
        write("g1.json", groupJson(members = member("a", "idle", """{"folder":"$wt"}""") + "," + member("b", "idle", """{"folder":"$wt"}""")))
        records = listOf(rec("only", wt))
        watcher.reload()
        assertEquals(listOf("only", null), watcher.groups.value.single().members.map { it.recordId })
    }

    @Test
    fun `the lead is live when an open record sits in its folder, and lastSaid comes from it`() {
        val lead = folder("lead")
        write("g1.json", groupJson(leadFolder = lead, members = member("m", "idle", """{"folder":"$lead"}""")))
        watcher.reload()
        assertEquals(false, watcher.groups.value.single().lead.live)

        records = listOf(rec("lead-rec", lead))
        watcher.resolve()
        var l = watcher.groups.value.single().lead
        assertEquals("lead-rec", l.recordId)
        assertEquals(true, l.live)
        assertEquals("said lead-rec", l.lastSaid)
        // The lead's own record is not also taken as a member.
        assertNull(watcher.groups.value.single().members.single().recordId)

        records = listOf(rec("lead-rec", lead, state = "ended"))
        watcher.resolve()
        l = watcher.groups.value.single().lead
        assertEquals("lead-rec", l.recordId)
        assertEquals(false, l.live)
        assertEquals("said lead-rec", l.lastSaid)
    }

    @Test
    fun `paths are compared in canonical form, so a symlinked folder still matches`() {
        val real = folder("real")
        val link = File(work, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), File(real).toPath())
        write("g1.json", groupJson(leadFolder = link.path))
        records = listOf(rec("r", real))
        watcher.reload()
        val lead = watcher.groups.value.single().lead
        assertEquals(real, lead.folder)
        assertEquals(true, lead.live)
    }

    @Test
    fun `for a repeated id the newest file wins and the other is reported`() {
        write("a.json", groupJson(id = "dup").replace("Group dup", "Older"), modified = 1_000_000)
        write("b.json", groupJson(id = "dup").replace("Group dup", "Newer"), modified = 2_000_000)
        watcher.reload()
        assertEquals("Newer", watcher.groups.value.single().title)
        assertNotNull(warnings.singleOrNull { "repeats the id" in it })
        // Polling again with nothing changed must not repeat the warning.
        watcher.reload()
        watcher.reload()
        assertEquals(1, warnings.count { "repeats the id" in it })
    }

    @Test
    fun `a member keeps the session id the file gives it, trimmed, and none when it is blank or absent`() {
        write(
            "g1.json",
            groupJson(
                members = listOf(
                    member("Has id", extra = ",\"sessionId\":\"  ab12cd  \""),
                    member("Blank id", extra = ",\"sessionId\":\"   \""),
                    member("No id"),
                ).joinToString(","),
            ),
        )
        watcher.reload()
        assertEquals(listOf("ab12cd", null, null), watcher.groups.value.single().members.map { it.sessionId })
    }
}
