/**
 * Tests for [AgentSessionTracker]: how screen polls and Claude Code hook
 * events open, update and end `agent_sessions` records, run against a real
 * SQLite database with fake git, clock and ids so no terminal is needed.
 *
 * @see AgentSessionTracker
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTrackerTest {

    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agent-tracker", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))

    private fun tracker(r: SettingsRepository = repo) = AgentSessionTracker(
        r,
        gitInfo = { GitInfo(repo = "lunamux", branch = "feature/ABC-12-overview", worktreePath = null) },
        clock = { now },
        newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun pane(
        id: String = "s1", agent: String? = null, state: String? = null, title: String? = null,
        screen: String? = "screen of $id", alive: Boolean = true,
    ) = PaneSnapshot(id, leafId = "leaf-$id", cwd = "/work/repo", programTitle = title, agent = agent, state = state,
        alive = alive, screenText = { screen })

    private fun event(name: String, id: String = "a-1", vararg more: Pair<String, String>): JsonObject =
        buildJsonObject {
            put("hook_event_name", name)
            put("session_id", id)
            more.forEach { (k, v) -> put(k, v) }
        }

    private fun toolEvent(tool: String, input: String): JsonObject = buildJsonObject {
        put("hook_event_name", "PreToolUse")
        put("session_id", "a-1")
        put("tool_name", tool)
        put("tool_input", Json.parseToJsonElement(input))
    }

    @Test
    fun `a pane that shows an agent opens a screen record and follows its state`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "codex", state = "working", title = "✳ Fix flaky test")))
        var row = t.summaries.value.single()
        assertEquals("codex", row.agent)
        assertEquals("working", row.state)
        assertEquals("Fix flaky test", row.title)
        assertEquals(listOf("ABC-12"), row.links)
        assertEquals("lunamux", row.repo)

        now = 2_000
        t.onPoll(listOf(pane(agent = "codex", state = "waiting", title = "something else")))
        row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals(2_000, row.stateSince)
        assertEquals("Fix flaky test", row.title, "the title freezes at the first working state")

        t.onPoll(listOf(pane(agent = "codex", state = null)))
        assertEquals("idle", t.summaries.value.single().state)
    }

    @Test
    fun `a pane with no agent opens nothing`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = null, state = null)))
        assertTrue(t.summaries.value.isEmpty())
    }

    @Test
    fun `closing the pane ends the record and keeps the last 200 screen lines`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "gemini", state = "working")))
        now = 5_000
        t.onSessionDestroyed("s1", (1..300).joinToString("\n") { "line $it" } + "\n\n")
        val row = t.summaries.value.single()
        assertEquals("ended", row.state)
        assertEquals(5_000, row.endedAt)
        val saved = repo.findAgentSessionById(row.id)!!.lastScreen!!.lines()
        assertEquals(200, saved.size)
        assertEquals("line 101", saved.first())
        assertEquals("line 300", saved.last())
    }

    @Test
    fun `a session that vanished between polls is ended`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        t.onPoll(emptyList())
        assertEquals("ended", t.summaries.value.single().state)
    }

    @Test
    fun `an exited process ends the record`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        t.onPoll(listOf(pane(agent = "claude", state = "working", alive = false)))
        assertEquals("ended", t.summaries.value.single().state)
    }

    @Test
    fun `hook events drive state, tool line, prompt and question`() {
        val t = tracker()
        val p = pane(title = "Refactor tracker")
        t.onHook(p, event("SessionStart", "a-1", "transcript_path" to "/t/a-1.jsonl"))
        assertEquals("idle", t.summaries.value.single().state)

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "please run the tests"))
        t.onHook(p, event("PreToolUse", "a-1", "tool_name" to "Bash"))
        var row = t.summaries.value.single()
        assertEquals("working", row.state)
        assertEquals("Bash", row.nowLine)
        assertEquals("Refactor tracker", row.title)
        assertEquals("please run the tests", repo.findAgentSessionById(row.id)!!.firstPrompt)

        t.onHook(p, toolEvent("Bash", """{"command":"./gradlew test\nsecond line","description":"x"}"""))
        assertEquals("Bash ./gradlew test", t.summaries.value.single().nowLine)

        now = 3_000
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Claude needs your permission to use Bash", row.question)
        assertNull(row.nowLine)

        t.onHook(p, event("PostToolUse", "a-1"))
        assertEquals("working", t.summaries.value.single().state)
        assertNull(t.summaries.value.single().question)
    }

    @Test
    fun `stop asks when the last message ends with a question mark and idles otherwise`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done.\n\nShall I also update the docs?"))
        var row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Shall I also update the docs?", row.question)

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "yes"))
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "All done."))
        row = t.summaries.value.single()
        assertEquals("idle", row.state)
        assertNull(row.question)
    }

    @Test
    fun `stop remembers the first line of the last paragraph, for questions too`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Long intro.\n\nThree runs are ready.\nSecond line."))
        assertEquals("Three runs are ready.", t.summaries.value.single().lastSaid)

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Which of the two should I merge first?"))
        val row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Which of the two should I merge first?", row.lastSaid)

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "the first"))
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "x".repeat(400)))
        assertEquals(160, t.summaries.value.single().lastSaid!!.length)
    }

    @Test
    fun `the idle reminder notification does not ask for you`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done."))
        t.onHook(p, event("Notification", "a-1", "notification_type" to "idle_prompt", "message" to "Claude is waiting for your input"))
        assertEquals("idle", t.summaries.value.single().state)
    }

    @Test
    fun `the idle reminder is recognised by its text when the type is missing`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done."))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude is waiting for your input"))
        assertEquals("idle", t.summaries.value.single().state)
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        assertEquals("needs_you", t.summaries.value.single().state)
    }

    @Test
    fun `session end closes the record with the screen and the lingering screen state does not reopen it`() {
        val t = tracker()
        val p = pane(screen = "bye")
        t.onHook(p, event("SessionStart", "a-1"))
        t.onHook(p, event("SessionEnd", "a-1"))
        val id = t.summaries.value.single().id
        assertEquals("ended", t.summaries.value.single().state)
        assertEquals("bye", repo.findAgentSessionById(id)!!.lastScreen)

        // The detector holds "working" for a few polls after the agent quits.
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(1, t.summaries.value.size)
        // Once the screen clears, a new run in the same pane is a new record.
        t.onPoll(listOf(pane(agent = null, state = null)))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(2, t.summaries.value.size)
    }

    @Test
    fun `a new session start in the same pane ends the old record`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("SessionStart", "a-1"))
        t.onHook(p, event("SessionStart", "a-2"))
        val rows = t.summaries.value
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.state == "ended" })
        assertEquals(1, rows.count { it.state != "ended" })
    }

    @Test
    fun `a repeated session start with the same id changes nothing`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("SessionStart", "a-1"))
        t.onHook(p, event("SessionStart", "a-1"))
        assertEquals(1, t.summaries.value.size)
    }

    @Test
    fun `a session start adopts the record the screen opened`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val screenId = t.summaries.value.single().id
        t.onHook(pane(agent = "claude", state = "working"), event("SessionStart", "a-1"))
        val rec = repo.findAgentSessionById(screenId)!!
        assertEquals(1, t.summaries.value.size)
        assertEquals("a-1", rec.agentSessionId)
        assertEquals("hooks", rec.source)
    }

    @Test
    fun `hook records ignore screen states`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("PreToolUse", "a-1", "tool_name" to "Bash"))
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals("working", t.summaries.value.single().state)
    }

    @Test
    fun `an approved permission prompt returns the row to working once the screen shows work`() {
        val t = tracker()
        t.onHook(pane(), event("PreToolUse", "a-1", "tool_name" to "Bash"))
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        assertEquals("needs_you", t.summaries.value.single().state)

        // Work on screen before the prompt was ever seen is not an approval.
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("needs_you", t.summaries.value.single().state)

        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val row = t.summaries.value.single()
        assertEquals("working", row.state)
        assertNull(row.question)
    }

    @Test
    fun `resuming a conversation reopens its ended record instead of adding a row`() {
        val t = tracker()
        t.onHook(pane("s1"), event("SessionStart", "a-1"))
        t.onSessionDestroyed("s1", "old screen")
        val id = t.summaries.value.single().id

        t.onHook(pane("s9"), event("SessionStart", "a-1"))
        val row = t.summaries.value.single()
        assertEquals(id, row.id)
        assertEquals("idle", row.state)
        assertNull(row.endedAt)
        assertEquals("leaf-s9", row.leafId)
    }

    @Test
    fun `hooks from an unknown pane are ignored`() {
        val t = tracker()
        t.onHook(null, event("SessionStart", "a-1"))
        assertTrue(t.summaries.value.isEmpty())
    }

    @Test
    fun `a restart closes open records and brings back the recent ended ones`() {
        val t = tracker()
        t.onHook(pane(), event("SessionStart", "a-1"))
        now = 9_000
        val restarted = tracker()
        val row = restarted.summaries.value.single()
        assertEquals("ended", row.state)
        assertEquals(9_000, row.endedAt)
        assertNotEquals(null, restarted.find(row.id))
    }

    @Test
    fun `tool line keeps only the name and the first line of the main argument`() {
        val big = "x".repeat(500)
        val line = toolLine(
            Json.parseToJsonElement("""{"tool_name":"Write","tool_input":{"file_path":"/a/b.kt","content":"$big"}}""") as JsonObject,
        )
        assertEquals("Write /a/b.kt", line)
        assertEquals("Grep todo", toolLine(Json.parseToJsonElement("""{"tool_name":"Grep","tool_input":{"pattern":"todo"}}""") as JsonObject))
        assertNull(toolLine(Json.parseToJsonElement("""{"x":1}""") as JsonObject))
    }

    @Test
    fun `a name the user gave the pane becomes the title and survives the pane closing`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working", title = "✳ Agent title")))
        now = 2_000
        t.onPoll(listOf(PaneSnapshot("s1", "leaf-s1", "/work/repo", "✳ Agent title", "claude", "working", true, "My F2 name") { "x" }))
        assertEquals("My F2 name", t.summaries.value.single().title)
        now = 3_000
        t.onSessionDestroyed("s1", "bye")
        assertEquals("My F2 name", t.summaries.value.single().title)

        val reloaded = tracker(SettingsRepository(File(dbDir, "test.db")))
        assertEquals("My F2 name", reloaded.summaries.value.single().title)
    }

    @Test
    fun `the repository root is recorded when a record opens`() {
        val r = SettingsRepository(File(dbDir, "root.db"))
        val t = AgentSessionTracker(
            r,
            gitInfo = { GitInfo("app", "feat", "/app/.claude/worktrees/feat", "/app") },
            clock = { now },
            newId = { "r${++nextId}" },
        ).also { it.start() }
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("/app", r.findAgentSessionById(t.summaries.value.single().id)!!.repoRoot)
    }

    @Test
    fun `the ended hook is called once for a record that ends`() {
        val ended = mutableListOf<String>()
        val t = AgentSessionTracker(
            repo,
            gitInfo = { GitInfo(repo = "lunamux") },
            clock = { now },
            newId = { "r${++nextId}" },
            onEnded = { ended += it.id },
        ).also { it.start() }
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        t.onSessionDestroyed("s1", "bye")
        t.onPoll(emptyList())
        assertEquals(listOf("r1"), ended)
    }

    @Test
    fun `a Claude session seen only on screen counts as unidentified until a hook names it`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertTrue(t.hasUnidentifiedClaudeIn("/work/repo"))
        assertTrue(!t.hasUnidentifiedClaudeIn("/elsewhere"))

        t.onHook(pane(agent = "claude", state = "working"), event("SessionStart", "a-1"))
        assertTrue(!t.hasUnidentifiedClaudeIn("/work/repo"))
    }
}
