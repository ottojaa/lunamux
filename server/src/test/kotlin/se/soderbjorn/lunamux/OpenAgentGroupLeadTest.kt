/**
 * Tests for the open-group-lead route ([openAgentGroupLead]): what it answers for an
 * unknown group, a live lead and a missing folder, and that the command it types always
 * comes from the group file.
 *
 * A fake opener stands in for [openTabRunning], so no terminal is started.
 *
 * @see openAgentGroupLead
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAgentGroupLeadTest {
    private val base = File.createTempFile("lunamux-lead", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val groupsDir = File(base, "groups").apply { mkdirs() }
    private val leadFolder = File(base, "lead").apply { mkdirs() }.canonicalPath
    private var records = listOf<AgentSessionRecord>()
    private val watcher = AgentGroupsWatcher(groupsDir, { records }, emptyFlow<Unit>(), {})
    private val sent = mutableListOf<WindowEnvelope>()
    private val opened = mutableListOf<Triple<String, String, String?>>()

    private fun ctx() = WindowConnectionContext(
        send = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = SettingsRepository(File(base, "settings.db")),
        usageMonitor = ClaudeUsageMonitor(),
        agentGroups = watcher,
    )

    private fun writeGroup(folder: String = leadFolder, command: String = "claude 'from the file'") {
        File(groupsDir, "g1.json").writeText(
            """{"id":"g1","title":"Programme one","lead":{"command":${kotlinx.serialization.json.JsonPrimitive(command)},"folder":"$folder"}}""",
        )
        watcher.reload()
    }

    private fun open(id: String = "g1") = runBlocking {
        openAgentGroupLead(id, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    @Test
    fun `an unknown group is answered with an error and opens nothing`() {
        open("nope")
        val error = assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertEquals(null, error.recordId)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a lead that is already open answers nothing and opens nothing`() {
        writeGroup()
        records = listOf(AgentSessionRecord(id = "r", agent = "claude", cwd = leadFolder, state = "idle", leafId = "leaf"))
        watcher.resolve()
        open()
        assertTrue(sent.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a lead folder that is gone is answered with an error and opens nothing`() {
        writeGroup(folder = File(base, "gone").path)
        open()
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a closed lead opens a tab named like the group and types the file's command`() {
        writeGroup(command = "claude 'from the file'")
        open()
        assertTrue(sent.isEmpty())
        assertEquals(Triple(leadFolder, "Programme one", "claude 'from the file'"), opened.single())
    }
}
