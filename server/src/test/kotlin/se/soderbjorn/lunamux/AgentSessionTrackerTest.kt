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
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals("done", t.summaries.value.single().state)
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
        assertEquals("done", t.summaries.value.single().state)

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "please run the tests"))
        t.onHook(p, event("PreToolUse", "a-1", "tool_name" to "Bash"))
        var row = t.summaries.value.single()
        assertEquals("working", row.state)
        assertEquals("Running a command", row.nowLine, "a call with no input says what it is, never a bare tool name")
        assertEquals("Refactor tracker", row.title)
        assertEquals("please run the tests", repo.findAgentSessionById(row.id)!!.firstPrompt)
        assertEquals("please run the tests", row.firstPrompt, "the summary carries the first prompt for the overview")

        t.onHook(p, toolEvent("Bash", """{"command":"./gradlew test\nsecond line","description":"Run the server tests"}"""))
        assertEquals("Run the server tests", t.summaries.value.single().nowLine, "the row says what the call does in words")

        now = 3_000
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Allow Bash ./gradlew test?", row.question, "a permission prompt names the actual command, not the row's words or the hook's generic text")
        assertNull(row.nowLine)

        t.onHook(p, event("PostToolUse", "a-1"))
        assertEquals("working", t.summaries.value.single().state)
        assertNull(t.summaries.value.single().question)
    }

    @Test
    fun `stop means done, whatever the last message ends with`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done.\n\nShall I also update the docs?"))
        var row = t.summaries.value.single()
        assertEquals("done", row.state)
        assertNull(row.question, "a closing question is not a prompt the person has to answer")
        assertEquals("Shall I also update the docs?", row.lastSaid, "what it said stays readable")

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "yes"))
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "All done."))
        row = t.summaries.value.single()
        assertEquals("done", row.state)
        assertNull(row.question)
    }

    @Test
    fun `a finished turn that ends in a reply is done even when the reply closes with an offer`() {
        val t = tracker()
        val p = pane()
        val replies = listOf(
            "I'd do 1 and skip 2, since your CLAUDE.md already covers it. Want me to go ahead?",
            "Both options work.\n\nThe first is smaller.\nThe second is safer, shall I take it?",
            "All done. Shall I also update the docs?",
            "Which do you want: the alias or the rename?",
        )
        for (reply in replies) {
            t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "go"))
            t.onHook(p, event("Stop", "a-1", "last_assistant_message" to reply))
            val row = t.summaries.value.single()
            assertEquals("done", row.state, reply)
            assertNull(row.question, reply)
        }
    }

    @Test
    fun `a permission request asks for you and an approval or sign-in notice does not`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done."))
        t.onHook(p, event("Notification", "a-1", "notification_type" to "auth_success", "message" to "Signed in"))
        assertEquals("done", t.summaries.value.single().state)
        t.onHook(p, event("Notification", "a-1", "notification_type" to "permission_prompt", "message" to "Claude needs your permission to use Bash"))
        val row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Claude needs your permission to use Bash", row.question)
    }

    @Test
    fun `a question put through AskUserQuestion shows the question itself, not the hook's generic text`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, toolEvent("AskUserQuestion", """{"questions":[{"question":"Aurora or Borealis?","header":"Name","options":[]}]}"""))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission"))
        val row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Aurora or Borealis?", row.question)
    }

    @Test
    fun `a prompt after the last tool call finished does not name that tool call`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, toolEvent("Bash", """{"command":"ls"}"""))
        t.onHook(p, event("PostToolUse", "a-1"))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission"))
        assertEquals("Claude needs your permission", t.summaries.value.single().question)
    }

    @Test
    fun `a notification of another type than a permission prompt keeps the hook's text while a tool runs`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, toolEvent("mcp__srv__tool", """{"query":"x"}"""))
        t.onHook(p, event("Notification", "a-1", "message" to "srv needs some input", "notification_type" to "elicitation_dialog"))
        assertEquals("srv needs some input", t.summaries.value.single().question)
    }

    @Test
    fun `a permission prompt typed as such names the tool call`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, toolEvent("Bash", """{"command":"ls"}"""))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission", "notification_type" to "permission_prompt"))
        assertEquals("Allow Bash ls?", t.summaries.value.single().question)
    }

    @Test
    fun `a prompt with no tool call pending keeps the hook's own text`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done."))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude needs your permission"))
        assertEquals("Claude needs your permission", t.summaries.value.single().question)
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
        assertEquals("done", row.state)
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
        assertEquals("done", t.summaries.value.single().state)
    }

    @Test
    fun `the idle reminder is recognised by its text when the type is missing`() {
        val t = tracker()
        val p = pane()
        t.onHook(p, event("Stop", "a-1", "last_assistant_message" to "Done."))
        t.onHook(p, event("Notification", "a-1", "message" to "Claude is waiting for your input"))
        assertEquals("done", t.summaries.value.single().state)
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
    fun `a prompt answered with No leaves needs_you once it is gone from the screen`() {
        val t = tracker()
        t.onHook(pane(), event("PreToolUse", "a-1", "tool_name" to "Bash"))
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))

        // No go-ahead and no work follows a refusal: the screen shows neither the prompt nor work.
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        assertEquals("needs_you", t.summaries.value.single().state)
        now = 5_000
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        val row = t.summaries.value.single()
        assertEquals("done", row.state)
        assertNull(row.question)
        assertEquals(now, row.lastActivityAt)

        // A hook that arrives later still moves the row on, and a later prompt is asked afresh.
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        assertEquals("needs_you", t.summaries.value.single().state)
    }

    @Test
    fun `a prompt the screen never showed is not cleared by a screen without it`() {
        val t = tracker()
        t.onHook(pane(), event("PreToolUse", "a-1", "tool_name" to "Bash"))
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals("needs_you", t.summaries.value.single().state)
    }

    @Test
    fun `a prompt approved long after it was asked is a fresh sign of life`() {
        val t = tracker()
        t.onHook(pane(), event("PreToolUse", "a-1", "tool_name" to "Bash"))
        now = 2_000
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))

        now = 2_000 + 10 * 60_000
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val row = t.summaries.value.single()
        assertEquals("working", row.state)
        assertEquals(now, row.lastActivityAt, "the answer must not leave the old stamp from the prompt in place")
    }

    @Test
    fun `a screen that keeps showing work renews its stamp only once it is stale`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val first = t.summaries.value.single().lastActivityAt

        now += 3_000
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(first, t.summaries.value.single().lastActivityAt, "a poll a few seconds later keeps the published value, so no new frame is sent")

        now += 40_000
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(now, t.summaries.value.single().lastActivityAt)
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
        assertEquals("done", row.state)
        assertNull(row.endedAt)
        assertEquals("leaf-s9", row.leafId)
    }

    @Test
    fun `a freshly resumed session at its prompt is done, not needs_you, whichever way its record is found`() {
        // The pane was reopened and the screen showed a dialog before the hook named the conversation.
        val reopen = tracker()
        reopen.onHook(pane("s1"), event("SessionStart", "a-1"))
        reopen.onSessionDestroyed("s1", "old screen")
        reopen.onPoll(listOf(pane("s9", agent = "claude", state = "waiting")))
        assertEquals(2, reopen.summaries.value.size, "the screen opened a record of its own")
        assertEquals("needs_you", reopen.summaries.value.single { it.state != "ended" }.state)
        reopen.onHook(pane("s9", agent = "claude", state = "waiting"), event("SessionStart", "a-1"))
        val reopened = reopen.summaries.value.single()
        assertEquals("done", reopened.state)
        assertNull(reopened.question)

        // The conversation has no record yet: the screen's record is adopted.
        val adopt = tracker(SettingsRepository(File(dbDir, "adopt.db")))
        adopt.onPoll(listOf(pane("s5", agent = "claude", state = "waiting")))
        assertEquals("needs_you", adopt.summaries.value.single().state)
        adopt.onHook(pane("s5", agent = "claude", state = "waiting"), event("SessionStart", "b-1"))
        val adopted = adopt.summaries.value.single()
        assertEquals("done", adopted.state)
        assertEquals("hooks", adopt.cached(adopted.id)!!.source)

        // Its next prompt still raises needs_you, from the hook.
        adopt.onHook(pane("s5"), event("Notification", "b-1", "message" to "Claude needs your permission to use Bash"))
        assertEquals("needs_you", adopt.summaries.value.single().state)
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
        assertEquals(1_000, row.endedAt, "it ended when it was last known alive, not when the server came back")
        assertNotEquals(null, restarted.find(row.id))
    }

    @Test
    fun `a restart ends a record at its transcript's last write when that is later than its state`() {
        val transcript = File.createTempFile("transcript", ".jsonl").apply {
            deleteOnExit()
            setLastModified(4_000)
        }
        val t = tracker()
        t.onHook(pane(), event("SessionStart", "a-1", "transcript_path" to transcript.path))
        now = 9_000
        assertEquals(4_000, tracker().summaries.value.single().endedAt)
    }

    @Test
    fun `a restart never ends a record in the future of the restart`() {
        val transcript = File.createTempFile("transcript", ".jsonl").apply {
            deleteOnExit()
            setLastModified(50_000)
        }
        val t = tracker()
        t.onHook(pane(), event("SessionStart", "a-1", "transcript_path" to transcript.path))
        now = 9_000
        assertEquals(9_000, tracker().summaries.value.single().endedAt)
    }

    @Test
    fun `a restart brings back ended records from the last fourteen days, the edge included, and no older`() {
        val day = 24L * 60 * 60 * 1000
        now = 100 * day
        fun ended(id: String, at: Long, source: String = "hooks") = repo.saveAgentSession(
            AgentSessionRecord(id = id, agent = "claude", source = source, state = "ended", stateSince = at,
                createdAt = at, endedAt = at),
        )
        ended("fresh", now - day)
        ended("edge", now - 14 * day)
        ended("stale", now - 14 * day - 1)
        ended("imported", now - day, source = "import")
        assertEquals(setOf("fresh", "edge"), tracker().summaries.value.map { it.id }.toSet())
    }

    @Test
    fun `ended records beyond the newest three hundred stay out of memory but in the database`() {
        now = 1_000_000
        repeat(310) { i ->
            repo.saveAgentSession(
                AgentSessionRecord(id = "e$i", agent = "claude", source = "hooks", state = "ended",
                    stateSince = now - i, createdAt = now - i, endedAt = now - i),
            )
        }
        val t = tracker()
        assertEquals(300, t.summaries.value.size)
        assertEquals(false, t.summaries.value.any { it.id == "e300" })
        assertNotEquals(null, t.find("e300"))
    }

    @Test
    fun `a conversation older than the ones kept in memory is found by its conversation id`() {
        val day = 24L * 60 * 60 * 1000
        now = 100 * day
        repo.saveAgentSession(
            AgentSessionRecord(id = "old-record", agent = "claude", agentSessionId = "conv-old", source = "hooks",
                state = "ended", stateSince = now - 30 * day, createdAt = now - 30 * day, endedAt = now - 30 * day),
        )
        val t = tracker()
        assertEquals(false, t.summaries.value.any { it.conversationId == "conv-old" })
        assertEquals("old-record", t.find("conv-old")?.id)
        assertEquals("old-record", t.find("old-record")?.id)
        assertEquals(null, t.find("conv-unknown"))
    }

    @Test
    fun `a record that ends drops the ended records that have aged out of the window`() {
        val day = 24L * 60 * 60 * 1000
        now = 100 * day
        val t = tracker()
        t.onHook(pane(), event("SessionStart", "a-1"))
        t.onHook(pane(), event("SessionEnd", "a-1"))
        assertEquals(1, t.summaries.value.size)
        // The first record stays in memory until another one ends and trims.
        now += 15 * day
        t.onHook(pane("s2"), event("SessionStart", "a-2"))
        t.onHook(pane("s2"), event("SessionEnd", "a-2"))
        assertEquals(1, t.summaries.value.size)
        assertEquals(now, t.summaries.value.single().endedAt)
    }

    /** Builds a PreToolUse payload for [toolLine] from a tool name and a JSON `tool_input` object. */
    private fun call(tool: String, input: String = "{}"): JsonObject =
        Json.parseToJsonElement("""{"tool_name":"$tool","tool_input":$input}""") as JsonObject

    @Test
    fun `a Bash call says what it does with the description Claude Code sent`() {
        assertEquals(
            "Run the server tests",
            toolLine(call("Bash", """{"command":"./gradlew :server:test","description":"Run the server tests"}""")),
        )
        assertEquals(
            "Run the server tests",
            toolLine(call("Bash", """{"command":"x","description":"\n  Run the server tests\nsecond line"}""")),
            "only the description's first line is kept",
        )
    }

    @Test
    fun `a Bash call without a description says it is running a command, never the program name`() {
        assertEquals("Running a command", toolLine(call("Bash", """{"command":"cat > /tmp/aov45-desc.txt 2>/dev/null; D=/private/x"}""")))
        assertEquals("Running a command", toolLine(call("Bash", """{"command":"./gradlew test\nsecond"}""")))
        assertEquals("Running a command", toolLine(call("Bash", """{"command":"GIT_PAGER=cat LC_ALL=C /usr/bin/git log"}""")))
        assertEquals("Running a command", toolLine(call("Bash", """{"command":"   "}""")))
        assertEquals("Running a command", toolLine(call("Bash", """{"description":"  "}""")))
    }

    @Test
    fun `a row never shows the command text of a Bash call`() {
        val line = toolLine(call("Bash", """{"command":"cat > /tmp/aov45-desc.txt 2>/dev/null; D=/private/tmp/very/long/path"}"""))!!
        assertFalse("/tmp" in line || ">" in line || ";" in line, "got: $line")
    }

    @Test
    fun `the file tools name the file and not where it lives`() {
        assertEquals("Editing card-check", toolLine(call("Edit", """{"file_path":"/Users/o/bin/card-check","old_string":"a","new_string":"b"}""")))
        assertEquals("Editing card-check", toolLine(call("MultiEdit", """{"file_path":"/Users/o/bin/card-check","edits":[]}""")))
        assertEquals("Reading tick.py", toolLine(call("Read", """{"file_path":"/work/scripts/tick.py"}""")))
        assertEquals("Writing notes.md", toolLine(call("Write", """{"file_path":"/a/b/notes.md","content":"${"x".repeat(500)}"}""")))
        assertEquals("Editing Tab.ipynb", toolLine(call("NotebookEdit", """{"notebook_path":"/a/Tab.ipynb"}""")))
        assertEquals("Listing src", toolLine(call("LS", """{"path":"/a/b/src/"}""")))
    }

    @Test
    fun `a file tool without a path still says what it does`() {
        assertEquals("Editing a file", toolLine(call("Edit")))
        assertEquals("Reading a file", toolLine(call("Read", """{"file_path":""}""")))
        assertEquals("Writing a file", toolLine(call("Write")))
    }

    @Test
    fun `searches name their pattern`() {
        assertEquals("Searching for \"fun toolLine\"", toolLine(call("Grep", """{"pattern":"fun toolLine","path":"/a/b"}""")))
        assertEquals("Finding files matching \"**/*.kt\"", toolLine(call("Glob", """{"pattern":"**/*.kt"}""")))
        assertEquals("Searching the web for \"kotlin flow\"", toolLine(call("WebSearch", """{"query":"kotlin flow"}""")))
        assertEquals("Searching the code", toolLine(call("Grep")))
        assertEquals("Finding files", toolLine(call("Glob")))
    }

    @Test
    fun `a web fetch names the host`() {
        assertEquals("Fetching example.com", toolLine(call("WebFetch", """{"url":"https://example.com/a/b?c=d","prompt":"x"}""")))
        assertEquals("Fetching example.com", toolLine(call("WebFetch", """{"url":"https://user@example.com#frag"}""")))
        assertEquals("Fetching a web page", toolLine(call("WebFetch")))
    }

    @Test
    fun `an MCP tool is the server and the tool name in words`() {
        assertEquals("lunamux: list tasks", toolLine(call("mcp__lunamux__list_tasks", """{"project":"p"}""")))
        assertEquals("claude-in-chrome: tabs context mcp", toolLine(call("mcp__claude-in-chrome__tabs_context_mcp")))
        assertEquals("Claude Docs: batch", toolLine(call("mcp__claude_ai_Claude_Docs__batch", """{"batch":[]}""")))
        assertEquals("Using mcp", toolLine(call("mcp__")), "an MCP name without a server and tool falls back to the plain phrase")
    }

    @Test
    fun `an agent and the background task tools get a plain verb phrase`() {
        assertEquals("Running an agent: Review the diff", toolLine(call("Task", """{"description":"Review the diff","prompt":"long"}""")))
        assertEquals("Running an agent: Review the diff", toolLine(call("Agent", """{"description":"Review the diff"}""")))
        assertEquals("Running an agent", toolLine(call("Agent")))
        assertEquals("Stopping a background task", toolLine(call("TaskStop", """{"task_id":"b1"}""")))
        assertEquals("Stopping a background task", toolLine(call("KillShell", """{"shell_id":"b1"}""")))
        assertEquals("Checking a background task", toolLine(call("TaskOutput", """{"task_id":"b1"}""")))
        assertEquals("Updating the task list", toolLine(call("TodoWrite", """{"todos":[{"content":"x"}]}""")))
        assertEquals("Using the lunamux skill", toolLine(call("Skill", """{"skill":"lunamux"}""")))
        assertEquals("Presenting a plan", toolLine(call("ExitPlanMode", """{"plan":"x"}""")))
        assertEquals("Looking up tools", toolLine(call("ToolSearch", """{"query":"select:X"}""")))
    }

    @Test
    fun `a tool this code does not know is named without its arguments`() {
        assertEquals("Using FancyNewTool", toolLine(call("FancyNewTool", """{"command":"rm -rf /","file_path":"/secret/path"}""")))
    }

    @Test
    fun `a question put to the person keeps showing the question`() {
        val asked = call("AskUserQuestion", """{"questions":[{"question":"Which port should it use?","options":[]}]}""")
        assertEquals("Asks: Which port should it use?", toolLine(asked))
        assertEquals("Asks: Which port should it use?", rawToolLine(asked))
        assertEquals("Asking a question", toolLine(call("AskUserQuestion")), "with no question there is nothing to quote")
    }

    @Test
    fun `a words line is cut at the row limit`() {
        val line = toolLine(call("Bash", """{"description":"${"w".repeat(500)}"}"""))!!
        assertEquals(160, line.length)
    }

    @Test
    fun `the raw tool line keeps only the name and the first line of the main argument`() {
        val big = "x".repeat(500)
        val line = rawToolLine(call("Write", """{"file_path":"/a/b.kt","content":"$big"}"""))
        assertEquals("Write /a/b.kt", line)
        assertEquals("Grep todo", rawToolLine(call("Grep", """{"pattern":"todo"}""")))
        assertEquals("Bash ./gradlew test", rawToolLine(call("Bash", """{"command":"./gradlew test\nsecond","description":"x"}""")))
        assertNull(rawToolLine(Json.parseToJsonElement("""{"x":1}""") as JsonObject))
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

    @Test
    fun `every folded hook event stamps the activity time, which the state clock does not follow`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(1_000, t.summaries.value.single().lastActivityAt, "a screen that shows work is a sign of life for a record no hook feeds")

        now = 10_000
        t.onHook(pane(agent = "claude", state = "working"), toolEvent("Bash", """{"command":"ls"}"""))
        now = 40_000
        t.onHook(pane(agent = "claude", state = "working"), event("PostToolUse"))
        var row = t.summaries.value.single()
        assertEquals(40_000, row.lastActivityAt)
        assertEquals(1_000, row.stateSince, "it has been working since the first poll, so the state clock stays put")

        now = 90_000
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        t.onHook(pane(agent = "claude", state = "working"), event("Notification", "a-1", "notification_type" to "idle_prompt"))
        assertEquals(40_000, t.summaries.value.single().lastActivityAt, "once hooks feed it, a poll or the idle reminder is not activity")

        t.onSessionDestroyed("s1", "bye")
        row = t.summaries.value.single()
        assertEquals("ended", row.state)
        assertNull(row.lastActivityAt)
    }

    @Test
    fun `a first prompt is cut to its first non-blank line and a sane length`() {
        assertEquals("fix the bug", firstPromptLine("\n  fix the bug  \nthen more\n"))
        assertNull(firstPromptLine("   \n\t"))
        assertEquals(160, firstPromptLine("x".repeat(500))!!.length)
    }

    @Test
    fun `what it last said skips a line that is only a code fence`() {
        assertEquals("npm test", lastSaidOf("Done.\n\n```\nnpm test\n```"))
        assertEquals("Done. Tests pass.", lastSaidOf("Intro\n\nDone. Tests pass.\nMore"))
        assertEquals("Ran it", lastSaidOf("Ran it\n\n```"))
        assertEquals("", lastSaidOf("   "))
    }
}
