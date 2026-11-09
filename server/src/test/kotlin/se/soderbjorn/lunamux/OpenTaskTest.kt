/**
 * Tests for the open route, [openTask]: when it answers with an error, and that the line it types
 * is the file's own `open.command` in the file's own folder. A fake opener stands in for
 * [openTabRunning], so no terminal is started.
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenTaskTest {
    private val base = File.createTempFile("lunamux-open", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val tasksDir = File(base, "tasks").apply { mkdirs() }
    private val openFolder = File(base, "open").apply { mkdirs() }.canonicalPath
    private val watcher = TaskFilesWatcher(tasksDir) {}
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

    private fun writeTask(open: String? = """{"command":"claude 'lead'","folder":"$openFolder"}""") {
        File(tasksDir, "t1.json").writeText(
            """{"id":"t1","title":"Task one" ${if (open == null) "" else ""","open":$open"""}}""",
        )
        watcher.reload()
        index.recompute()
    }

    private fun callOpenTask(taskId: String = "t1") = runBlocking {
        openTask(taskId, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    @Test
    fun `an unknown task, a task with no open action and an open folder that is gone are each answered with an error and open nothing`() {
        callOpenTask("nope")
        writeTask(open = null)
        callOpenTask()
        writeTask(open = """{"command":"claude","folder":"${File(base, "gone").path}"}""")
        callOpenTask()
        assertEquals(3, sent.size)
        assertTrue(sent.all { it is WindowEnvelope.AgentSessionError } && opened.isEmpty())
    }

    @Test
    fun `the open action types the file's command in the file's folder, named like the task`() {
        writeTask()
        callOpenTask()
        assertTrue(sent.isEmpty())
        assertEquals(Triple(openFolder, "Task one", "claude 'lead'"), opened.single())
    }
}
