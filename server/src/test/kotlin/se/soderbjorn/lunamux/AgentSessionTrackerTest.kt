/**
 * Tests for [AgentSessionTracker]: how pane polls and Claude Code hook events open, update and end
 * conversation records, run against a real SQLite database with a fake clock and ids so no terminal
 * is needed. The turns that end without a hook are replayed from real Claude Code 2.1.319
 * recordings. One test pins a fix by name: needs_you holds while a multi-question dialog is redrawn.
 *
 * @see AgentSessionTracker
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentSessionTrackerTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agent-tracker", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))

    private fun tracker(r: SettingsRepository = repo) = AgentSessionTracker(
        r, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun pane(id: String = "s1", agent: String? = null, state: String? = null, title: String? = null, alive: Boolean = true) =
        PaneSnapshot(id, leafId = "leaf-$id", cwd = "/work/repo", programTitle = title, agent = agent, state = state, alive = alive)

    private fun event(name: String, id: String = "a-1", vararg more: Pair<String, String>): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", id)
        more.forEach { (k, v) -> put(k, v) }
    }

    private fun toolEvent(name: String, tool: String, input: String): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", "a-1")
        put("tool_name", tool)
        put("tool_input", Json.parseToJsonElement(input))
    }

    private fun shown(t: AgentSessionTracker) = t.summaries.value.single().shown

    /**
     * A session Claude Code 2.1.319 really ran, replayed against the fake clock: the hook events it
     * sent and its transcript, both stamped by Claude Code's own clock. The transcript on disk holds
     * only the lines stamped no later than the clock. The recordings are in
     * `src/test/resources/claude-2.1.319-interrupts`, one per way a turn ended without a hook.
     */
    private inner class Recording(private val name: String) {
        private fun lines(suffix: String) =
            javaClass.getResource("/claude-2.1.319-interrupts/$name.$suffix.jsonl")!!.readText().lines().filter { it.isNotBlank() }

        private val hooks = lines("hooks").map {
            val o = Json.parseToJsonElement(it).jsonObject
            o["at"]!!.jsonPrimitive.long to o["event"]!!.jsonObject
        }
        private val transcript: List<Pair<Long, String>> = run {
            var stamp = 0L
            lines("transcript").map {
                Json.parseToJsonElement(it).jsonObject["timestamp"]?.jsonPrimitive?.content?.let { ts -> stamp = Instant.parse(ts).toEpochMilli() }
                stamp to it
            }
        }
        private var delivered = 0
        private val file = File(dbDir, "$name.transcript.jsonl")

        /** Send one more hook of this session at [time], as Claude Code would when the person goes on. */
        fun sendAt(t: AgentSessionTracker, time: Long, hookName: String) {
            now = time
            t.onHook(pane(), JsonObject(hooks[0].second + mapOf("hook_event_name" to JsonPrimitive(hookName), "transcript_path" to JsonPrimitive(file.path))))
        }

        fun hookAt(index: Int) = hooks[index].first
        val interruptedAt = transcript.first { it.second.contains("[Request interrupted by user") }.first

        /** Deliver the hooks sent up to [time], then bring the clock and the transcript on disk to [time]. */
        fun playTo(t: AgentSessionTracker, time: Long) {
            while (delivered < hooks.size && hooks[delivered].first <= time) {
                val (at, event) = hooks[delivered++]
                now = at
                write()
                t.onHook(pane(), JsonObject(event + ("transcript_path" to JsonPrimitive(file.path))))
            }
            now = time
            write()
        }

        private fun write() = file.writeText(transcript.filter { it.first <= now }.joinToString("") { it.second + "\n" })
    }

    // Records opened from the screen

    @Test
    fun `a pane that shows an agent opens a record and follows its state`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = null, state = null)))
        assertTrue(t.summaries.value.isEmpty(), "a pane with no agent opens nothing")

        t.onPoll(listOf(pane(agent = "codex", state = "working", title = "✳ Fix flaky test")))
        assertEquals(listOf(Shown.WORKING, "Fix flaky test"), t.summaries.value.single().let { listOf(it.shown, it.title) })

        now = 2_000
        t.onPoll(listOf(pane(agent = "codex", state = "waiting")))
        assertEquals(Shown.NEEDS_YOU, shown(t))

        t.onPoll(listOf(pane(agent = "codex", state = null)))
        assertEquals(Shown.DONE, shown(t))
    }

    @Test
    fun `a record ends when its pane closes`() {
        val t = tracker()
        t.onPoll(listOf(pane("a", agent = "gemini", state = "working")))
        now = 5_000
        t.onSessionDestroyed("a", (1..300).joinToString("\n") { "line $it" } + "\n\n")
        val row = t.summaries.value.single()
        assertEquals(Shown.CLOSED, row.shown)
        val saved = repo.findAgentSession(t.find(row.id)!!.id)!!.lastScreen!!.lines()
        assertEquals(listOf("line 101", "line 300", 200), listOf(saved.first(), saved.last(), saved.size), "the last 200 lines are kept")
    }

    // Records fed by hooks

    @Test
    fun `hook events drive state, tool line and question`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "refactor it"))
        assertEquals(Shown.WORKING, shown(t))
        assertEquals("refactor it", t.find("r1")!!.firstPrompt)

        t.onHook(pane(), toolEvent("PreToolUse", "Read", """{"file_path":"/w/App.kt"}"""))
        assertEquals("Reading App.kt", t.summaries.value.single().line)

        t.onHook(pane(), toolEvent("PreToolUse", "AskUserQuestion", """{"questions":[{"question":"Which one?"}]}"""))
        assertEquals(Shown.NEEDS_YOU, shown(t))
        assertTrue(t.summaries.value.single().line!!.contains("Which one?"))

        t.onHook(pane(), toolEvent("PostToolUse", "AskUserQuestion", "{}"))
        assertEquals(Shown.WORKING, shown(t))

        t.onHook(pane(), event("Stop", "a-1", "last_assistant_message" to "All done."))
        assertEquals(listOf(Shown.DONE, "All done."), t.summaries.value.single().let { listOf(it.shown, it.line) })
    }

    @Test
    fun `a permission request needs you and a permission notification does not`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "permission_prompt", "message" to "Claude needs your permission"))
        assertEquals(Shown.WORKING, shown(t), "the notification is ignored; PermissionRequest is the word")

        t.onHook(pane(), toolEvent("PermissionRequest", "Bash", """{"command":"rm -rf build"}"""))
        assertEquals(Shown.NEEDS_YOU, shown(t))
        assertTrue(t.summaries.value.single().line!!.contains("Allow Bash rm -rf build"))
    }

    @Test
    fun `an elicitation dialog needs you`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "elicitation_dialog", "message" to "Pick a server"))
        assertEquals(Shown.NEEDS_YOU, shown(t))
    }

    @Test
    fun `needs_you holds while a multi-question dialog is redrawn`() {
        val t = tracker()
        t.onHook(pane(), toolEvent("PreToolUse", "AskUserQuestion", """{"questions":[{"question":"First?"},{"question":"Second?"}]}"""))
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        assertEquals(Shown.NEEDS_YOU, shown(t))
        // Between questions the dialog is redrawn: for a poll the prompt is gone, then work shows.
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals(Shown.NEEDS_YOU, shown(t), "the redraw is not an answer")
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(Shown.NEEDS_YOU, shown(t), "nor is work on screen")

        t.onHook(pane(), toolEvent("PostToolUse", "AskUserQuestion", "{}"))
        assertEquals(Shown.WORKING, shown(t), "the hook says it was answered")
    }

    // Turns that end without a hook, from real Claude Code recordings

    @Test
    fun `a prompt refused at once never reaches needs_you and the row settles from the transcript`() {
        val rec = Recording("refused-fast")
        val t = tracker()
        rec.playTo(t, rec.hookAt(2))
        rec.playTo(t, rec.interruptedAt - 1)
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        assertEquals(Shown.WORKING, shown(t), "the transcript says nothing yet, so nothing moves")

        rec.playTo(t, rec.interruptedAt + 50)
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals(Shown.DONE, shown(t))
    }

    @Test
    fun `a refusal after the permission notification settles from the transcript`() {
        val rec = Recording("refused-after-notification")
        val t = tracker()
        rec.playTo(t, rec.hookAt(3))
        assertEquals(Shown.WORKING, shown(t), "a 2.1.319 notification does not put the row in needs_you")
        rec.playTo(t, rec.interruptedAt + 50)
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals(Shown.DONE, shown(t))
    }

    @Test
    fun `an interrupted turn settles from the transcript and a later prompt is not taken for it`() {
        val rec = Recording("interrupted-while-writing")
        val t = tracker()
        rec.playTo(t, rec.hookAt(1))
        rec.playTo(t, rec.interruptedAt - 1)
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(Shown.WORKING, shown(t))

        rec.playTo(t, rec.interruptedAt + 50)
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(Shown.DONE, shown(t), "the transcript is read before the screen, which still shows work for a few polls")

        rec.sendAt(t, rec.interruptedAt + 10_000, "UserPromptSubmit")
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(Shown.WORKING, shown(t), "the interrupt line is old news")
    }

    // Restarts

    @Test
    fun `a restart ends open records when they were last alive or when their transcript was last written`() {
        val transcript = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit(); setLastModified(4_000) }
        val t = tracker()
        t.onHook(pane("s1"), event("SessionStart", "a-1"))
        t.onHook(pane("s2"), event("SessionStart", "a-2", "transcript_path" to transcript.path))
        now = 9_000
        val ends = tracker().summaries.value.associate { it.leafId to it.since }
        assertEquals(mapOf<String?, Long?>("leaf-s1" to 1_000, "leaf-s2" to 4_000), ends, "not when the server came back")
        assertNotNull(tracker().summaries.value.firstOrNull())
    }

    @Test
    fun `endAll closes open records as restart and keeps the screen`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        now = 3_000
        t.endAll(listOf(pane().copy(screenText = { "the screen" })))
        val row = t.summaries.value.single()
        assertEquals(Shown.STOPPED, row.shown)
        assertEquals("restart", row.ended)
        assertEquals("the screen", t.find("r1")!!.lastScreen)
    }
}
