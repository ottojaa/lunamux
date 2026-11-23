/**
 * Tests for the conversation rules of the agents tracker that the task index leans on and that no
 * other test pins: how a hook opens a record, what ends it and why, that a finished turn is done
 * whatever it said last, how a tool call reads on a working row, and how a conversation's own
 * status line is cleaned. They run the real [AgentSessionTracker] over a throwaway SQLite database
 * with a fake clock, as [AgentSessionTrackerTest] does.
 *
 * @see AgentSessionTracker
 * @see toolLine
 * @see rawToolLine
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AgentTrackerRulesTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agent-rules", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))

    private fun tracker() = AgentSessionTracker(
        repo, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun pane(id: String = "s1") =
        PaneSnapshot(id, leafId = "leaf-$id", cwd = "/work/repo", programTitle = null, agent = null, state = null, alive = true)

    private fun event(name: String, vararg more: Pair<String, String>): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", "a-1")
        more.forEach { (k, v) -> put(k, v) }
    }

    private fun tool(tool: String, input: String): JsonObject = buildJsonObject {
        put("hook_event_name", "PreToolUse")
        put("session_id", "a-1")
        put("tool_name", tool)
        put("tool_input", Json.parseToJsonElement(input))
    }

    @Test
    fun `a hook in a pane with no record opens a claude record fed by hooks`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "prompt" to "go"))
        val rec = assertNotNull(t.openRecordOf("s1"))
        assertEquals("claude", rec.agent)
        assertEquals("hooks", rec.source)
        assertEquals("a-1", rec.conversationId)
    }

    @Test
    fun `a finished turn is done whatever its last words were, and they are kept`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "prompt" to "go"))
        t.onHook(pane(), event("Stop", "last_assistant_message" to "Want me to go ahead?"))
        assertEquals(Shown.DONE, t.summaries.value.single().shown)
        assertEquals("Want me to go ahead?", t.openRecordOf("s1")?.lastSaid)
    }

    @Test
    fun `SessionEnd ends the record and the reason follows the hook`() {
        for ((hook, ended) in listOf("clear" to "cleared", "prompt_input_exit" to "quit", "resume" to "replaced", "logout" to "exit")) {
            val t = tracker()
            val p = pane("s-$hook")
            t.onHook(p, event("UserPromptSubmit", "prompt" to "go"))
            t.onHook(p, event("SessionEnd", "reason" to hook))
            assertNull(t.openRecordOf(p.terminalSessionId), "$hook closes it")
            assertEquals(ended, t.summaries.value.single().ended, hook)
        }
    }

    @Test
    fun `a tool call reads in words on the row, and says nothing when it names nothing`() {
        assertEquals("Run the build", toolLine(tool("Bash", """{"command":"./gradlew build","description":"Run the build"}""")))
        assertNull(toolLine(tool("Bash", """{"command":"ls"}""")), "no description, nothing")
        assertEquals("Editing A.kt", toolLine(tool("Edit", """{"file_path":"/x/y/A.kt"}""")))
        assertEquals("Reading A.kt", toolLine(tool("Read", """{"file_path":"/x/y/A.kt"}""")))
        assertEquals("Searching for \"foo\"", toolLine(tool("Grep", """{"pattern":"foo"}""")))
        assertNull(toolLine(tool("Glob", """{}""")), "Glob without a pattern")
        assertEquals("Fetching example.com", toolLine(tool("WebFetch", """{"url":"https://example.com/a?b=c"}""")))
        assertEquals("Linear: list issues", toolLine(tool("mcp__claude_ai_Linear__list_issues", "{}")))
        assertNull(toolLine(tool("SomethingNew", "{}")), "an unknown tool")
        assertEquals(160, toolLine(tool("Bash", """{"description":"${"x".repeat(300)}"}"""))!!.length)
    }

    @Test
    fun `the raw tool line is the tool and the first line of its main argument`() {
        assertEquals("Bash rm -rf build", rawToolLine(tool("Bash", """{"command":"rm -rf build\nls"}""")))
        assertEquals("Read /x/y/A.kt", rawToolLine(tool("Read", """{"file_path":"/x/y/A.kt"}""")))
        assertEquals("Glob", rawToolLine(tool("Glob", "{}")), "no argument, the tool alone")
        assertEquals(160, rawToolLine(tool("Bash", """{"command":"${"y".repeat(300)}"}"""))!!.length)
    }

    @Test
    fun `a conversation's own status is one trimmed line of 120 characters, blank clears, and a kept one holds the time`() {
        val t = tracker()
        t.onHook(pane(), event("UserPromptSubmit", "prompt" to "go"))
        assertEquals("one two", t.setStatus("s1", null, "  one \n  two  "))
        assertEquals("one two", t.summaries.value.single().line)
        assertEquals(120, t.setStatus("s1", null, "z".repeat(200))!!.length)
        assertNull(t.setStatus("s1", null, "   "))
        assertNull(t.summaries.value.single().line.takeIf { t.summaries.value.single().lineSet })

        t.setStatus("s1", null, "waiting on review", keep = true)
        t.onHook(pane(), event("Stop"))
        val since = t.summaries.value.single().since
        assertEquals(Shown.WAITING, t.summaries.value.single().shown)
        now += 5_000
        t.setStatus("s1", null, "waiting on review", keep = true)
        assertEquals(since, t.summaries.value.single().since, "the same text keeps its time")
        t.setStatus("s1", null, "waiting on something else", keep = true)
        assertEquals(since + 5_000, t.summaries.value.single().since, "a new text starts a new wait")
    }
}
