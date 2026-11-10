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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

    /** The layout leaf every [pane] sits in, when a test needs a real one; else one named after the pane. */
    private var leafOverride: String? = null

    private fun pane(
        id: String = "s1", agent: String? = null, state: String? = null, title: String? = null,
        screen: String? = "screen of $id", alive: Boolean = true,
    ) = PaneSnapshot(id, leafId = leafOverride ?: "leaf-$id", cwd = "/work/repo", programTitle = title, agent = agent, state = state,
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

    /**
     * A session Claude Code 2.1.319 really ran, replayed against the fake clock: the hook events it
     * sent (those Lunamux subscribes to) and its transcript, both stamped by Claude Code's own
     * clock. The transcript file on disk holds only the lines stamped no later than the clock, so a
     * poll at time T sees what a poll at time T saw then.
     *
     * The recordings are in `src/test/resources/claude-2.1.319-interrupts`, one per way a turn
     * ended without a hook.
     */
    private inner class Recording(private val name: String) {
        private fun lines(suffix: String) =
            javaClass.getResource("/claude-2.1.319-interrupts/$name.$suffix.jsonl")!!.readText().lines().filter { it.isNotBlank() }

        private val hooks = lines("hooks").map {
            val o = Json.parseToJsonElement(it).jsonObject
            o["at"]!!.jsonPrimitive.long to o["event"]!!.jsonObject
        }

        /** Each transcript line with the stamp it was written at (lines without one follow the one before). */
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

        /** When the hook with this index (in the order sent) was sent. */
        fun hookAt(index: Int) = hooks[index].first

        /** When the person interrupted: the stamp of the transcript's interrupt line. */
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

    private fun onlyState(t: AgentSessionTracker) = t.summaries.value.single().state

    private fun stopped(t: AgentSessionTracker, said: String, prompt: String = "go") {
        t.onHook(pane(), event("UserPromptSubmit", "a-1", "prompt" to prompt))
        t.onHook(pane(), event("Stop", "a-1", "last_assistant_message" to said))
    }

    private fun askPermission(t: AgentSessionTracker, tool: String = "Bash") {
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission to use $tool"))
    }

    // Records opened from the screen

    @Test
    fun `a pane that shows an agent opens a record, follows its state and freezes the title`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = null, state = null)))
        assertTrue(t.summaries.value.isEmpty(), "a pane with no agent opens nothing")

        t.onPoll(listOf(pane(agent = "codex", state = "working", title = "✳ Fix flaky test")))
        var row = t.summaries.value.single()
        assertEquals(listOf("codex", "working", "Fix flaky test", "lunamux"), listOf(row.agent, row.state, row.title, row.repo))
        assertEquals(listOf("ABC-12"), row.links)

        now = 2_000
        t.onPoll(listOf(pane(agent = "codex", state = "waiting", title = "something else")))
        row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals(2_000, row.stateSince)
        assertEquals("Fix flaky test", row.title)

        t.onPoll(listOf(pane(agent = "codex", state = null)))
        assertEquals("done", onlyState(t))
    }

    @Test
    fun `a screen record ends when its pane closes, its agent vanishes or its process exits`() {
        val t = tracker()
        t.onPoll(listOf(pane("a", agent = "gemini", state = "working"), pane("b", agent = "claude", state = "working"), pane("c", agent = "claude", state = "working")))
        now = 5_000
        t.onSessionDestroyed("a", (1..300).joinToString("\n") { "line $it" } + "\n\n")
        t.onPoll(listOf(pane("b", agent = "claude", state = "working", alive = false)))
        val rows = t.summaries.value
        assertEquals(listOf("ended", "ended", "ended"), rows.map { it.state })
        assertEquals(5_000, rows.first { it.agent == "gemini" }.endedAt)
        val saved = repo.findAgentSessionById(rows.first { it.agent == "gemini" }.id)!!.lastScreen!!.lines()
        assertEquals(listOf("line 101", "line 300", 200), listOf(saved.first(), saved.last(), saved.size), "the last 200 lines are kept")
    }

    @Test
    fun `a name the person gave the pane becomes the title and survives the pane closing`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working", title = "✳ Agent title")))
        now = 2_000
        t.onPoll(listOf(PaneSnapshot("s1", "leaf-s1", "/work/repo", "✳ Agent title", "claude", "working", true, "My F2 name") { "x" }))
        t.onSessionDestroyed("s1", "bye")
        assertEquals("My F2 name", t.summaries.value.single().title)
        assertEquals("My F2 name", tracker(SettingsRepository(File(dbDir, "test.db"))).summaries.value.single().title)
    }

    // Records fed by hooks

    @Test
    fun `hook events drive state, tool line, prompt and question`() {
        val t = tracker()
        val p = pane(title = "Refactor tracker")
        t.onHook(p, event("SessionStart", "a-1", "transcript_path" to "/t/a-1.jsonl"))
        assertEquals("done", onlyState(t))

        t.onHook(p, event("UserPromptSubmit", "a-1", "prompt" to "please run the tests"))
        t.onHook(p, event("PreToolUse", "a-1", "tool_name" to "Bash"))
        var row = t.summaries.value.single()
        assertEquals("working", row.state)
        assertNull(row.nowLine, "a call with nothing to say says nothing, never a bare tool name")
        assertEquals("Refactor tracker", row.title)
        assertEquals("please run the tests", row.firstPrompt)

        t.onHook(p, toolEvent("Bash", """{"command":"./gradlew test\nsecond line","description":"Run the server tests"}"""))
        assertEquals("Run the server tests", t.summaries.value.single().nowLine)

        askPermission(t)
        row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertEquals("Allow Bash ./gradlew test?", row.question, "a prompt names the actual command, not the hook's generic text")
        assertNull(row.nowLine)

        t.onHook(p, event("PostToolUse", "a-1"))
        assertEquals("working", onlyState(t))
        assertNull(t.summaries.value.single().question)
    }

    @Test
    fun `stop means done and keeps what was said, and only a permission request asks for you`() {
        val t = tracker()
        t.onHook(pane(), event("Stop", "a-1", "last_assistant_message" to "Long intro.\n\nThree runs are ready.\nSecond line."))
        assertEquals("Three runs are ready.", t.summaries.value.single().lastSaid)

        stopped(t, "Done.\n\nShall I also update the docs?")
        var row = t.summaries.value.single()
        assertEquals(listOf("done", null, "Shall I also update the docs?"), listOf(row.state, row.question, row.lastSaid), "a closing question is not a prompt to answer")
        stopped(t, "x".repeat(400))
        assertEquals(160, t.summaries.value.single().lastSaid!!.length)

        for (type in listOf("auth_success", "idle_prompt")) {
            t.onHook(pane(), event("Notification", "a-1", "notification_type" to type, "message" to "Signed in"))
            assertEquals("done", onlyState(t), type)
        }
        t.onHook(pane(), event("Notification", "a-1", "notification_type" to "permission_prompt", "message" to "Claude needs your permission to use Bash"))
        row = t.summaries.value.single()
        assertEquals(listOf("needs_you", "Claude needs your permission to use Bash"), listOf(row.state, row.question))
    }

    @Test
    fun `a prompt names the tool call it is about, or the question put through AskUserQuestion`() {
        val t = tracker()
        t.onHook(pane(), toolEvent("AskUserQuestion", """{"questions":[{"question":"Aurora or Borealis?","header":"Name","options":[]}]}"""))
        askPermission(t, "it")
        assertEquals("Aurora or Borealis?", t.summaries.value.single().question)

        t.onHook(pane(), toolEvent("Bash", """{"command":"ls"}"""))
        t.onHook(pane(), event("Notification", "a-1", "message" to "Claude needs your permission", "notification_type" to "permission_prompt"))
        assertEquals("Allow Bash ls?", t.summaries.value.single().question)

        t.onHook(pane(), event("PostToolUse", "a-1"))
        askPermission(t, "it")
        assertEquals("Claude needs your permission to use it", t.summaries.value.single().question, "a finished call is not named")
    }

    @Test
    fun `a session start adopts the screen record, a new start in the pane ends the old record and hooks from no pane are ignored`() {
        val t = tracker()
        t.onHook(null, event("SessionStart", "a-0"))
        assertTrue(t.summaries.value.isEmpty())

        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val screenId = t.summaries.value.single().id
        t.onHook(pane(agent = "claude", state = "working"), event("SessionStart", "a-1"))
        assertEquals(listOf("a-1", "hooks"), repo.findAgentSessionById(screenId)!!.let { listOf(it.agentSessionId, it.source) })
        assertEquals(1, t.summaries.value.size)

        t.onHook(pane(), event("SessionStart", "a-2"))
        assertEquals(listOf("done", "ended"), t.summaries.value.map { it.state }.sorted())
    }

    @Test
    fun `session end closes the record with its screen and a lingering screen state does not reopen it`() {
        val t = tracker()
        val p = pane(screen = "bye")
        t.onHook(p, event("SessionStart", "a-1"))
        t.onHook(p, event("SessionEnd", "a-1"))
        assertEquals("ended", onlyState(t))
        assertEquals("bye", repo.findAgentSessionById(t.summaries.value.single().id)!!.lastScreen)

        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(1, t.summaries.value.size, "the detector holds working for a few polls after the agent quits")
        t.onPoll(listOf(pane(agent = null, state = null)))
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(2, t.summaries.value.size, "once the screen clears, a new run is a new record")
    }

    @Test
    fun `resuming a conversation reopens its ended record and a freshly resumed session at its prompt is done`() {
        val t = tracker()
        t.onHook(pane("s1"), event("SessionStart", "a-1"))
        t.onSessionDestroyed("s1", "old screen")
        val id = t.summaries.value.single().id
        // The screen showed a dialog in the reopened pane before the hook named the conversation.
        t.onPoll(listOf(pane("s9", agent = "claude", state = "waiting")))
        assertEquals(listOf("ended", "needs_you"), t.summaries.value.map { it.state })

        t.onHook(pane("s9", agent = "claude", state = "waiting"), event("SessionStart", "a-1"))
        val row = t.summaries.value.single()
        assertEquals(listOf(id, "done", null, "leaf-s9"), listOf(row.id, row.state, row.question, row.leafId))
        assertNull(row.endedAt)

        t.onHook(pane("s9"), event("Notification", "a-1", "message" to "Claude needs your permission to use Bash"))
        assertEquals("needs_you", onlyState(t), "its next prompt still asks")
    }

    // What the screen may and may not change on a hook-fed record

    @Test
    fun `an approved prompt returns to working once the screen shows work, unless no poll ever saw the prompt`() {
        val t = tracker()
        t.onHook(pane(), event("PreToolUse", "a-1", "tool_name" to "Bash"))
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals("working", onlyState(t), "hook records ignore an idle screen")

        askPermission(t)
        // A poll that reads work may have been taken before the prompt was drawn, or may read the
        // work of a background agent above a question: an unanswered question must stay counted.
        repeat(5) {
            now += 3_000
            t.onPoll(listOf(pane(agent = "claude", state = "working")))
            assertEquals("needs_you", onlyState(t))
        }
        t.onHook(pane(), event("PostToolUse", "a-1", "tool_name" to "Bash"))
        assertEquals("working", onlyState(t))

        askPermission(t)
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        now += 10 * 60_000
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        val row = t.summaries.value.single()
        assertEquals(listOf("working", null, now), listOf(row.state, row.question, row.lastActivityAt), "the prompt was seen, then work followed: the person said yes")
    }

    @Test
    fun `a prompt answered with No is done once it is gone from the screen`() {
        val t = tracker()
        askPermission(t)
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        assertEquals("needs_you", onlyState(t))
        now = 5_000
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        val row = t.summaries.value.single()
        assertEquals(listOf("done", null, now), listOf(row.state, row.question, row.lastActivityAt))

        askPermission(t)
        assertEquals("needs_you", onlyState(t), "a later prompt is asked afresh")
    }

    @Test
    fun `a working record stays working through minutes of work on screen and is done after three idle polls`() {
        val t = tracker()
        t.onHook(pane(), toolEvent("Bash", """{"command":"./gradlew test"}"""))
        val since = t.summaries.value.single().stateSince
        repeat(120) {
            now += 3_000
            t.onPoll(listOf(pane(agent = "claude", state = "working")))
        }
        var row = t.summaries.value.single()
        assertEquals(listOf("working", since), listOf(row.state, row.stateSince), "six minutes without a hook")
        assertTrue(now - row.lastActivityAt!! < 30_000L, "work on screen keeps the sign of life current")

        // A killed process or an errored turn sends no Stop; one or two idle polls could be a screen not caught up.
        repeat(2) {
            now += 3_000
            t.onPoll(listOf(pane(agent = "claude", state = null)))
            assertEquals("working", onlyState(t))
        }
        now += 3_000
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        row = t.summaries.value.single()
        assertEquals(listOf("done", null, now), listOf(row.state, row.nowLine, row.stateSince))
    }

    // Turns that end without a hook, from real Claude Code recordings

    @Test
    fun `a prompt refused at once never reaches needs_you and the row settles from the transcript`() {
        val rec = Recording("refused-fast")
        val t = tracker()
        rec.playTo(t, rec.hookAt(2))
        rec.playTo(t, rec.interruptedAt - 1)
        t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
        assertEquals("working", onlyState(t), "the transcript says nothing yet, so nothing moves")

        rec.playTo(t, rec.interruptedAt + 50)
        t.onPoll(listOf(pane(agent = "claude", state = null)))
        assertEquals(listOf("done", null), t.summaries.value.single().let { listOf(it.state, it.nowLine) })
    }

    @Test
    fun `a prompt nobody answered stays needs_you however long the screen shows it`() {
        val rec = Recording("refused-after-notification")
        val t = tracker()
        rec.playTo(t, rec.hookAt(3))
        repeat(3) {
            now += 60 * 60_000L
            t.onPoll(listOf(pane(agent = "claude", state = "waiting")))
            assertEquals("needs_you", onlyState(t))
        }
    }

    @Test
    fun `an interrupted turn settles from the transcript and a later prompt is not taken for it`() {
        val rec = Recording("interrupted-while-writing")
        val t = tracker()
        rec.playTo(t, rec.hookAt(1))
        rec.playTo(t, rec.interruptedAt - 1)
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("working", onlyState(t))

        rec.playTo(t, rec.interruptedAt + 50)
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("done", onlyState(t), "the transcript is read before the screen, which still shows work for a few polls")

        rec.sendAt(t, rec.interruptedAt + 10_000, "UserPromptSubmit")
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("working", onlyState(t), "the interrupt line is old news")
    }

    // Restarts and the window of ended records

    @Test
    fun `a restart ends open records when they were last alive or when their transcript was last written`() {
        val transcript = File.createTempFile("transcript", ".jsonl").apply { deleteOnExit(); setLastModified(4_000) }
        val t = tracker()
        t.onHook(pane("s1"), event("SessionStart", "a-1"))
        t.onHook(pane("s2"), event("SessionStart", "a-2", "transcript_path" to transcript.path))
        now = 9_000
        val ends = tracker().summaries.value.associate { it.leafId to it.endedAt }
        assertEquals(mapOf<String?, Long?>("leaf-s1" to 1_000, "leaf-s2" to 4_000), ends, "not when the server came back")
    }

    @Test
    fun `only the newest three hundred ended records stay in memory, and those older than the window are dropped when another ends`() {
        now = 1_000_000
        repeat(310) { i ->
            repo.saveAgentSession(AgentSessionRecord(id = "e$i", agent = "claude", source = "hooks", state = "ended", stateSince = now - i, createdAt = now - i, endedAt = now - i))
        }
        val t = tracker()
        assertEquals(300, t.summaries.value.size)
        assertNotNull(t.find("e300"), "still in the database")

        val day = 24L * 60 * 60 * 1000
        now += 15 * day
        t.onHook(pane(), event("SessionStart", "a-1"))
        t.onHook(pane(), event("SessionEnd", "a-1"))
        assertEquals(listOf(now), t.summaries.value.map { it.endedAt })
    }

    // The words for a tool call

    private fun call(tool: String, input: String = "{}"): JsonObject =
        Json.parseToJsonElement("""{"tool_name":"$tool","tool_input":$input}""") as JsonObject

    @Test
    fun `a tool call says what it does in words`() {
        val said = mapOf<JsonObject, String?>(
            call("Bash", """{"command":"x","description":"\n  Run the server tests\nsecond line"}""") to "Run the server tests",
            call("Edit", """{"file_path":"/Users/o/bin/card-check"}""") to "Editing card-check",
            call("Read", """{"file_path":"/work/scripts/tick.py"}""") to "Reading tick.py",
            call("LS", """{"path":"/a/b/src/"}""") to "Listing src",
            call("Grep", """{"pattern":"fun toolLine"}""") to "Searching for \"fun toolLine\"",
            call("Glob") to null,
            call("WebSearch", """{"query":"kotlin flow"}""") to "Searching the web for \"kotlin flow\"",
            call("mcp__claude_ai_Claude_Docs__batch") to "Claude Docs: batch",
            call("mcp__claude-in-chrome__tabs_context_mcp") to "claude-in-chrome: tabs context mcp",
            call("mcp__") to null,
            call("Agent", """{"description":"Review the diff"}""") to "Running an agent: Review the diff",
            call("KillShell") to null,
            call("Bash", """{"command":"cd /tmp"}""") to null,
            call("Skill", """{"skill":"lunamux"}""") to "Using the lunamux skill",
            call("AskUserQuestion", """{"questions":[{"question":"Which port?","options":[]}]}""") to "Asks: Which port?",
            call("AskUserQuestion") to null,
        )
        said.forEach { (input, line) -> assertEquals(line, toolLine(input)) }
        assertNull(toolLine(Json.parseToJsonElement("""{"x":1}""") as JsonObject))
    }

    @Test
    fun `the raw tool line keeps the name and the first line of the main argument`() {
        assertEquals("Write /a/b.kt", rawToolLine(call("Write", """{"file_path":"/a/b.kt","content":"${"x".repeat(500)}"}""")))
        assertEquals("Bash ./gradlew test", rawToolLine(call("Bash", """{"command":"./gradlew test\nsecond","description":"x"}""")))
        assertNull(rawToolLine(Json.parseToJsonElement("""{"x":1}""") as JsonObject))
    }

    // What the tracker records and tells others

    @Test
    fun `a record keeps the repository root, tells the ended hook once and counts as unidentified until a hook names it`() {
        val ended = mutableListOf<String>()
        val t = AgentSessionTracker(
            repo, gitInfo = { GitInfo("app", "feat", "/app/.claude/worktrees/feat", "/app") }, clock = { now },
            newId = { "r${++nextId}" }, onEnded = { ended += it.id },
        ).also { it.start() }
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals("/app", repo.findAgentSessionById("r1")!!.repoRoot)
        assertTrue(t.hasUnidentifiedClaudeIn("/work/repo"))
        assertFalse(t.hasUnidentifiedClaudeIn("/elsewhere"))
        t.onHook(pane(agent = "claude", state = "working"), event("SessionStart", "a-1"))
        assertFalse(t.hasUnidentifiedClaudeIn("/work/repo"))

        t.onSessionDestroyed("s1", "bye")
        t.onPoll(emptyList())
        assertEquals(listOf("r1"), ended)
    }

    @Test
    fun `hook events and screens showing work stamp the activity time, which the state clock does not follow`() {
        val t = tracker()
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(1_000, t.summaries.value.single().lastActivityAt)

        now = 40_000
        t.onHook(pane(agent = "claude", state = "working"), event("PostToolUse"))
        assertEquals(listOf(40_000L, 1_000L), t.summaries.value.single().let { listOf(it.lastActivityAt, it.stateSince) })

        now = 90_000
        t.onHook(pane(agent = "claude", state = "working"), event("Notification", "a-1", "notification_type" to "idle_prompt"))
        assertEquals(40_000, t.summaries.value.single().lastActivityAt, "the idle reminder is not activity")
        t.onPoll(listOf(pane(agent = "claude", state = "working")))
        assertEquals(90_000, t.summaries.value.single().lastActivityAt, "a build can run minutes between hooks")

        t.onSessionDestroyed("s1", "bye")
        assertNull(t.summaries.value.single().lastActivityAt)
    }

    @Test
    fun aRecordSaysWhyItEndedAndWhatItWasDoing() {
        val t = tracker()
        t.onHook(pane("s1", agent = "claude"), event("UserPromptSubmit", "a-1", "prompt" to "go"))
        t.onHook(pane("s2", agent = "claude"), event("UserPromptSubmit", "a-2", "prompt" to "go"))
        t.onHook(pane("s2", agent = "claude"), event("Stop", "a-2", "last_assistant_message" to "All done."))
        t.onHook(pane("s3", agent = "claude"), event("UserPromptSubmit", "a-3", "prompt" to "go"))
        t.onHook(pane("s3", agent = "claude"), event("SessionEnd", "a-3"))
        t.onHook(pane("s4", agent = "claude"), event("UserPromptSubmit", "a-4", "prompt" to "go"))
        t.onSessionDestroyed("s4", null)
        // Lunamux stops: what is still open was cut off by it.
        t.endAll(listOf(pane("s1"), pane("s2")))
        fun ended(conversation: String) = t.summaries.value.single { it.conversationId == conversation }.let { it.endReason to it.endedFrom }
        assertEquals("restart" to "working", ended("a-1"))
        assertEquals("restart" to "done", ended("a-2"))
        assertEquals("exit" to "working", ended("a-3"))
        assertEquals("closed" to "working", ended("a-4"))
        // A record a run left open (a crash) is found at the next start and counts as cut off too.
        val crashed = tracker()
        crashed.onHook(pane("s5", agent = "claude"), event("UserPromptSubmit", "a-5", "prompt" to "go"))
        val after = tracker()
        assertEquals("restart" to "working", after.summaries.value.single { it.conversationId == "a-5" }.let { it.endReason to it.endedFrom })
        // Resumed, it is open again and no longer ended by anything.
        after.onHook(pane("s6", agent = "claude"), event("SessionStart", "a-5", "source" to "resume"))
        assertEquals(null, after.summaries.value.single { it.conversationId == "a-5" }.endReason)
    }

    @Test
    fun aSecondClaudeInAPaneIsAHelperOnlyWhenItRunsUnderThePanesOwn() {
        // Process 200 runs under 100; 300 is a claude of its own, started after 100 died without saying so.
        val t = AgentSessionTracker(
            repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
            isUnder = { pid, ancestor -> pid == 200L && ancestor == 100L }, isAlive = { it != 200L },
        ).also { it.start() }
        val p = pane("s1", agent = "claude", state = "working")
        t.onHook(p, event("UserPromptSubmit", "main", "prompt" to "go"), pid = 100)
        t.onHook(p, event("SessionStart", "reviewer", "source" to "resume"), pid = 200)
        assertEquals("main", t.openRecordOf("s1")?.conversationId, "a resume start underneath the conversation is still its helper")
        assertEquals("main", t.snapshot().single { it.conversationId == "reviewer" }.host)
        // The helper's process is gone: it ends at the next poll, with no ten quiet minutes.
        t.onPoll(listOf(p))
        assertEquals("ended", t.snapshot().single { it.conversationId == "reviewer" }.state)
        // A claude that is not underneath takes the pane, though the screen still shows an agent.
        t.onHook(p, event("SessionStart", "fresh", "source" to "startup"), pid = 300)
        assertEquals("fresh", t.openRecordOf("s1")?.conversationId)
        assertEquals("replaced", t.snapshot().single { it.conversationId == "main" }.endReason)
        assertNull(t.snapshot().single { it.conversationId == "fresh" }.host)
    }
}
