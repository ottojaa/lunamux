/**
 * Route-level tests for [WindowCommand.PressTaskButton]: the command goes through
 * [handleWindowCommand] to a real registered terminal session, so the dependency guard, the
 * [TerminalSessions] lookup and the bytes that arrive are all the ones a connected window uses.
 *
 * The task's own session is a screen-mode [AgentSession], which turns every byte typed into it
 * into an input event the test can read back.
 *
 * @see pressTaskButton
 * @see handleWindowCommand
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sends `PressTaskButton` through [handleWindowCommand] and reads back what the task's own
 * session received.
 *
 * Run by `:server:test`.
 */
class PressTaskButtonRouteTest {
    private val dir = File.createTempFile("lunamux-press", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private var now = 10_000L
    private var nextId = 0
    private val tracker = AgentSessionTracker(repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" }).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { "leaf-$it" }, clock = { now })
    private val store = TaskPanelStore(repo, index, clock = { now })
    private val taskId = index.createTask("Release", emptyList())
    private val console = AgentSession(renderMode = "screen")
    private val ownSessionId = TerminalSessions.registerAgent(console)

    @AfterTest
    fun tearDown() {
        TerminalSessions.destroy(ownSessionId)
    }

    private fun contextWith(store: TaskPanelStore?, index: TaskIndex?, tracker: AgentSessionTracker?) = WindowConnectionContext(
        send = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = repo,
        usageMonitor = ClaudeUsageMonitor(),
        agentTracker = tracker,
        taskIndex = index,
        taskPanelStore = store,
    )

    private fun press(ctx: WindowConnectionContext, buttonId: String = "go") = runBlocking {
        val command: WindowCommand = WindowCommand.PressTaskButton(taskId, "act", buttonId)
        handleWindowCommand(windowJson.encodeToString(command), ctx)
    }

    /** Everything the own session was sent, as the keys it saw, in order. */
    private fun received(): List<String> = console.pollEvents(100).mapNotNull { it.key }

    private fun givePanelAButton() {
        store.setPanel(taskId, listOf(TaskPanelBlock("act", null, TaskBlockContent.Actions(listOf(TaskButton("go", "Go", "/ship"))), writtenAtMillis = 0)), null)
    }

    private fun startAgentInOwnSession() {
        tracker.onHook(
            PaneSnapshot(ownSessionId, "leaf-$ownSessionId", "/work", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-own") },
        )
        index.setOwnSession(taskId, ownSessionId)
    }

    @Test
    fun `a press arrives in the own session's terminal as the stored text and Enter`() {
        givePanelAButton()
        startAgentInOwnSession()
        press(contextWith(store, index, tracker))
        val keys = received()
        assertEquals(listOf("/", "s", "h", "i", "p"), keys.take(5))
        assertEquals(6, keys.size, "the text plus one Enter, nothing more: $keys")
    }

    @Test
    fun `a window connection without the panel store, task index or tracker types nothing`() {
        givePanelAButton()
        startAgentInOwnSession()
        press(contextWith(null, index, tracker))
        press(contextWith(store, null, tracker))
        press(contextWith(store, index, null))
        assertTrue(received().isEmpty())
    }

    @Test
    fun `a press whose terminal session is gone types nothing and does not fail`() {
        givePanelAButton()
        startAgentInOwnSession()
        TerminalSessions.destroy(ownSessionId)
        press(contextWith(store, index, tracker))
        assertTrue(received().isEmpty())
    }

    @Test
    fun `a button that is not stored types nothing`() {
        givePanelAButton()
        startAgentInOwnSession()
        press(contextWith(store, index, tracker), buttonId = "ghost")
        assertTrue(received().isEmpty())
    }
}
