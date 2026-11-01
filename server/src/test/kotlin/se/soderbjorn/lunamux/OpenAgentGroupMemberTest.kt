/**
 * Tests for the open-run route ([openAgentGroupMember]): what it answers for an unknown group,
 * a run that is not in the file and an id the shell would interpret, and that the line it types
 * is always `claude attach` plus an id the group file declared.
 *
 * A fake opener stands in for [openTabRunning], so no terminal is started.
 *
 * @see openAgentGroupMember
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAgentGroupMemberTest {
    private val base = File.createTempFile("lunamux-member", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val groupsDir = File(base, "groups").apply { mkdirs() }
    private val leadFolder = File(base, "lead").apply { mkdirs() }.canonicalPath
    private val watcher = AgentGroupsWatcher(groupsDir, { emptyList() }, emptyFlow<Unit>(), {})
    private val sent = mutableListOf<WindowEnvelope>()
    private val opened = mutableListOf<Triple<String, String, String?>>()

    private fun ctx() = WindowConnectionContext(
        send = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = SettingsRepository(File(base, "settings.db")),
        usageMonitor = ClaudeUsageMonitor(),
        agentGroups = watcher,
    )

    private fun writeGroup(folder: String = leadFolder, sessionId: String = "ab12cd") {
        File(groupsDir, "g1.json").writeText(
            """{"id":"g1","title":"Programme one","lead":{"command":"claude 'lead'","folder":"$folder"},
            "members":[{"title":"Stale rows","state":"working","sessionId":"$sessionId"},{"title":"No id","state":"idle"}]}""",
        )
        watcher.reload()
    }

    private fun open(groupId: String = "g1", sessionId: String = "ab12cd") = runBlocking {
        openAgentGroupMember(groupId, sessionId, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    @Test
    fun `an unknown group is answered with an error and opens nothing`() {
        open("nope")
        assertEquals(null, assertIs<WindowEnvelope.AgentSessionError>(sent.single()).recordId)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an id that no member of the group carries is answered with an error and opens nothing`() {
        writeGroup()
        open(sessionId = "someone-else")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an id the shell would interpret is refused even when the file declares it`() {
        writeGroup(sessionId = "x; rm -rf ~")
        open(sessionId = "x; rm -rf ~")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
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
    fun `a declared run opens a tab named like it that types claude attach with the id`() {
        writeGroup()
        open()
        assertTrue(sent.isEmpty())
        assertEquals(Triple(leadFolder, "Stale rows", "claude attach ab12cd"), opened.single())
    }
}
