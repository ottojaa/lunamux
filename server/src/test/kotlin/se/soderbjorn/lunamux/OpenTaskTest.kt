/**
 * Tests for the two open routes, [openTask] and [openTaskMember]: what they answer for an unknown
 * task, a task or member the file does not declare, a session id the shell would interpret and a
 * folder that is gone, and that the line they type is always the file's `open.command` or
 * `claude attach` plus an id the task file declared.
 *
 * A fake opener stands in for [openTabRunning], so no terminal is started.
 *
 * @see openTask
 * @see openTaskMember
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenTaskTest {
    private val base = File.createTempFile("lunamux-open", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val tasksDir = File(base, "tasks").apply { mkdirs() }
    private val openFolder = File(base, "open").apply { mkdirs() }.canonicalPath
    private val matchFolder = File(base, "match").apply { mkdirs() }.canonicalPath
    private val watcher = TaskFilesWatcher(listOf(tasksDir)) {}
    private val repo = SettingsRepository(File(base, "settings.db"))
    private val tracker = AgentSessionTracker(repo)
    private val index = TaskIndex(repo, tracker, watcher.files, leafOfSession = { null })
    private val sent = mutableListOf<WindowEnvelope>()
    private val opened = mutableListOf<Triple<String, String, String?>>()

    private fun ctx() = WindowConnectionContext(
        send = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = repo,
        usageMonitor = ClaudeUsageMonitor(),
        taskIndex = index,
    )

    private fun writeTask(open: String? = """{"command":"claude 'lead'","folder":"$openFolder"}""", sessionId: String = "ab12cd", memberFolder: String? = matchFolder) {
        val match = memberFolder?.let { ""","match":{"folder":"$it"}""" } ?: ""
        File(tasksDir, "t1.json").writeText(
            """{"id":"t1","title":"Task one" ${if (open == null) "" else ""","open":$open"""},
            "members":[{"title":"Stale rows","state":"working","sessionId":"$sessionId" $match},{"title":"No id","state":"idle"}]}""",
        )
        watcher.reload()
        index.recompute()
    }

    private fun callOpenTask(taskId: String = "t1") = runBlocking {
        openTask(taskId, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    private fun callOpenMember(taskId: String = "t1", key: String = "ab12cd") = runBlocking {
        openTaskMember(taskId, key, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    @Test
    fun `an unknown task is answered with an error and opens nothing, for the task and for a member`() {
        callOpenTask("nope")
        callOpenMember("nope")
        assertEquals(2, sent.size)
        assertEquals(null, assertIs<WindowEnvelope.AgentSessionError>(sent.first()).recordId)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a task whose file declares no open action is answered with an error`() {
        writeTask(open = null)
        callOpenTask()
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the open action types the file's command in the file's folder, named like the task`() {
        writeTask()
        callOpenTask()
        assertTrue(sent.isEmpty())
        assertEquals(Triple(openFolder, "Task one", "claude 'lead'"), opened.single())
    }

    @Test
    fun `an open folder that is gone is answered with an error and opens nothing`() {
        writeTask(open = """{"command":"claude","folder":"${File(base, "gone").path}"}""")
        callOpenTask()
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a member key the file does not carry is answered with an error and opens nothing`() {
        writeTask()
        callOpenMember(key = "someone-else")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a member with no session id has nothing to attach to`() {
        writeTask()
        callOpenMember(key = "#2")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `an id the shell would interpret is refused even when the file declares it`() {
        writeTask(sessionId = "x; rm -rf ~")
        callOpenMember(key = "x; rm -rf ~")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a declared run opens a tab named like it that types claude attach with the id, in the open folder`() {
        writeTask()
        callOpenMember()
        assertTrue(sent.isEmpty())
        assertEquals(Triple(openFolder, "Stale rows", "claude attach ab12cd"), opened.single())
    }

    @Test
    fun `without an open folder the tab starts in the member's own folder, else the home folder`() {
        writeTask(open = null)
        callOpenMember()
        assertEquals(matchFolder, opened.single().first)

        opened.clear()
        writeTask(open = null, memberFolder = null)
        callOpenMember()
        assertEquals(System.getProperty("user.home"), opened.single().first)
    }

    @Test
    fun `a folder that is gone is answered with an error and opens nothing`() {
        writeTask(open = null, memberFolder = File(base, "gone").path)
        callOpenMember()
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }
}
