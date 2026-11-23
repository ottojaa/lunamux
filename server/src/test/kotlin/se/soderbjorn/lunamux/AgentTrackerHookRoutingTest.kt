/**
 * Tests for how the agents tracker routes hook events and polls to records: which pane a
 * conversation belongs to, when an event is dropped, what a helper is, and what a record shows
 * after a screen poll. Each test runs the real [AgentSessionTracker] over a throwaway SQLite
 * database with a fake clock and fake process-tree answers, as [AgentTrackerRulesTest] does.
 *
 * @see AgentSessionTracker
 * @see route
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTrackerHookRoutingTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agent-routing", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))

    /** Process 200 runs underneath process 100; nothing else does. */
    private val running = mutableSetOf(100L, 200L)

    private fun tracker() = AgentSessionTracker(
        repo, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" },
        isUnder = { child, parent -> child == 200L && parent == 100L },
        isRunning = { it in running },
    ).also { it.start() }

    private fun pane(id: String = "s1", agent: String? = null, state: String? = null, name: String? = null, screen: String? = null) =
        PaneSnapshot(
            id, leafId = "leaf-$id", cwd = "/work/repo", programTitle = null, agent = agent, state = state, alive = true,
            customName = name, screenText = { screen },
        )

    private fun event(name: String, conversation: String, vararg more: Pair<String, String>): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", conversation)
        more.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun `a screen record ends replaced when the pane's agent changes`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "codex", state = "working")))
        val first = assertNotNull(t.openRecordOf("s1"))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("replaced", t.find(first.id)?.endReason)
        assertEquals("claude", t.openRecordOf("s1")?.agent)
    }

    @Test
    fun `a hook adopts the screen's provisional claude record and a guessed question is not kept`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "needs_you")))
        val guess = assertNotNull(t.openRecordOf("s1"))
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        val adopted = assertNotNull(t.openRecordOf("s1"))
        assertEquals(guess.id, adopted.id, "the same record, no second one")
        assertEquals("a-1", adopted.conversationId)
        assertEquals("hooks", adopted.source)
    }

    @Test
    fun `a hook from another conversation, with no process to tell it is a helper, replaces the pane's record`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        val first = assertNotNull(t.openRecordOf("s1"))
        t.onHook(pane(), event("UserPromptSubmit", "a-2", "prompt" to "again"))
        assertEquals("replaced", t.find(first.id)?.endReason)
        assertEquals("a-2", t.openRecordOf("s1")?.conversationId)
    }

    @Test
    fun `a hook for a conversation whose transcript is gone does not pull the old record into a pane`() {
        val t = tracker()
        val gone = File(dbDir, "missing.jsonl").path
        t.onHook(pane(), event("SessionStart", "a-1", "transcript_path" to gone))
        t.onHook(pane(), event("SessionEnd", "a-1", "reason" to "other"))
        t.onHook(pane("s2"), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        assertNull(t.openRecordOf("s2"))
    }

    @Test
    fun `a helper runs under the conversation's claude, ends with it, and a late event of an ended helper is dropped`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"), pid = 100)
        t.onHook(pane(), event("SessionStart", "h-1"), pid = 200)
        val helper = t.snapshot().single { it.conversationId == "h-1" }
        assertEquals("a-1", t.openRecordOf("s1")?.conversationId, "the pane still belongs to the first conversation")
        t.onHook(pane(), event("SessionEnd", "h-1", "reason" to "other"), pid = 200)
        assertEquals("exit", t.find(helper.id)?.endReason)
        assertNull(t.find(helper.id)?.lastScreen, "a helper keeps no screen")
        assertEquals("a-1", t.openRecordOf("s1")?.conversationId, "its end does not close or block the pane")
        val before = t.snapshot().size
        t.onHook(pane(), event("Stop", "h-1"), pid = 200)
        assertEquals(before, t.snapshot().size, "a late event opens nothing")
        assertEquals("ended", t.find(helper.id)?.state)
    }

    @Test
    fun `a helper ends exit when the conversation it runs in ends`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"), pid = 100)
        t.onHook(pane(), event("SessionStart", "h-1"), pid = 200)
        val helper = t.snapshot().single { it.conversationId == "h-1" }
        t.onHook(pane(), event("SessionEnd", "a-1", "reason" to "prompt_input_exit"), pid = 100)
        assertEquals("exit", t.find(helper.id)?.endReason)
    }

    @Test
    fun `a helper whose process is gone ends exit at the next poll`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"), pid = 100)
        t.onHook(pane(), event("SessionStart", "h-1"), pid = 200)
        val helper = t.snapshot().single { it.conversationId == "h-1" }
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertNull(t.find(helper.id)?.endReason, "still running")
        running.remove(200L)
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("exit", t.find(helper.id)?.endReason)
        assertEquals("a-1", t.openRecordOf("s1")?.conversationId)
    }

    @Test
    fun `after SessionEnd the pane opens no screen record until a poll shows no agent`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("SessionEnd", "a-1", "reason" to "clear"))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertNull(t.openRecordOf("s1"), "the screen may still show the agent that just quit")
        t.onPoll(listOf(pane()))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertNotNull(t.openRecordOf("s1"), "a cleared screen lets a new agent in")
    }

    @Test
    fun `SessionStart in another folder moves the record to it`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("SessionStart", "a-1", "cwd" to "/work/other"))
        assertEquals("/work/other", t.openRecordOf("s1")?.cwd)
    }

    @Test
    fun `a finished turn clears the tool line, the question and the call in flight`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "elicitation_dialog", "message" to "Which one?"))
        assertEquals("Which one?", t.openRecordOf("s1")?.question)
        t.onHook(pane(), event("Stop", "a-1"))
        val rec = assertNotNull(t.openRecordOf("s1"))
        assertNull(rec.question)
        assertNull(rec.nowLine)
        assertEquals("done", rec.state)
    }

    @Test
    fun `no timer ends working or needs_you`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        now += 6 * 60 * 60 * 1000L
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("working", t.openRecordOf("s1")?.state)
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "elicitation_dialog", "message" to "Which?"))
        now += 6 * 60 * 60 * 1000L
        t.onPoll(listOf(pane(agent = "claude", state = "needs_you")))
        assertEquals("needs_you", t.openRecordOf("s1")?.state)
    }

    @Test
    fun `an idle reminder and a sign-in notice do not move the row`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "idle_prompt", "message" to "waiting"))
        assertEquals("working", t.openRecordOf("s1")?.state)
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "auth_success", "message" to "signed in"))
        assertEquals("working", t.openRecordOf("s1")?.state)
    }

    @Test
    fun `a pane's custom name is the row's title, and changes follow`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working", name = "Fix login")))
        assertEquals("Fix login", t.summaries.value.single().title)
        t.onPoll(listOf(pane(agent = "claude", state = "working", name = "Fix signup")))
        assertEquals("Fix signup", t.summaries.value.single().title)
    }

    @Test
    fun `a closed conversation in which nothing was said is not listed`() {
        val t = tracker()
        t.onHook(pane(), event("SessionStart", "a-1"))
        t.onHook(pane(), event("SessionEnd", "a-1", "reason" to "other"))
        assertFalse(t.summaries.value.single().listed)
    }

    @Test
    fun `a closed conversation with a prompt is listed`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-9", "prompt" to "do it"))
        t.onHook(pane(), event("SessionEnd", "a-9", "reason" to "other"))
        assertTrue(t.summaries.value.single().listed)
    }

    @Test
    fun `a record fed by hooks ignores what the screen guesses`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("Stop", "a-1"))
        t.onPoll(listOf(pane(agent = "claude", state = "needs_you")))
        assertEquals("done", t.openRecordOf("s1")?.state, "the screen never sets needs_you on a hook record")
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("done", t.openRecordOf("s1")?.state, "nor working")
    }

    @Test
    fun `a restart loads closed conversations of the last 31 days and no older`() {
        val first = tracker()
        first.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        first.onHook(pane(), event("SessionEnd", "a-1", "reason" to "other"))
        now += 30L * 24 * 60 * 60 * 1000
        assertEquals(1, tracker().summaries.value.size, "30 days on, still there")
        now += 2L * 24 * 60 * 60 * 1000
        assertEquals(0, tracker().summaries.value.size, "32 days on, gone from memory")
    }
}
