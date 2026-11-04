/**
 * Tests for what a task keeps besides its name: the session that speaks for it (`create_task`'s
 * caller header, `set_task_lead`), its one-line status (`set_task_status`) and the start the
 * overview orders rows by. Tools are driven through [McpServer.handleMessage] against a real
 * [TaskIndex], tracker and database.
 *
 * @see registerMcpTaskTools
 * @see se.soderbjorn.lunamux.TaskIndex
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.AgentSessionTracker
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.GitInfo
import se.soderbjorn.lunamux.PaneSnapshot
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTaskMetaTest {
    private val dir = File.createTempFile("lunamux-mcp-meta", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val panes = mutableSetOf<String>()
    private var nextId = 0
    private var now = 5_000L
    private val tracker = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }
    private val index = TaskIndex(
        repo, tracker, files, leafOfSession = { if (it in panes) "leaf-$it" else WindowState.leafIdOfSession(it) },
        clock = { now },
        sessionOfLeaf = { leaf -> panes.firstOrNull { "leaf-$it" == leaf } ?: WindowState.sessionIdOfLeaf(leaf) },
    )

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
    }

    private fun call(tool: String, argsJson: String = "{}", caller: String? = null): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            McpScope.READ_WRITE, null, caller,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun payload(r: JsonObject) = mcpJson.parseToJsonElement(textOf(r)).jsonObject
    private fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.content == "true"

    private fun session(id: String) {
        panes += id
        now += 10
        val pane = PaneSnapshot(id, "leaf-$id", "/work/$id", null, screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$id") })
    }

    private fun create(title: String, sessions: String = "[]", caller: String? = null) =
        payload(call("create_task", """{"title":"$title","sessionIds":$sessions}""", caller))

    private fun taskInState(id: String) = index.state.value.tasks.single { it.id == id }

    @Test
    fun `the caller's pane is the task's own session when the request names it`() {
        session("boss"); session("worker")
        val made = create("Plan", """["worker"]""", caller = "boss")
        assertEquals("boss", made["ownSession"]!!.jsonPrimitive.content)
    }

    @Test
    fun `without a usable caller the first named session is the task's own session`() {
        session("a"); session("b")
        assertEquals("a", create("One", """["a","b"]""")["ownSession"]!!.jsonPrimitive.content)
        // A header naming no pane is not trusted.
        assertEquals("b", create("Two", """["b","a"]""", caller = "no-such-pane")["ownSession"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a task nobody named has no own session`() {
        val made = create("Empty")
        assertEquals("null", made["ownSession"].toString())
        assertNull(index.listing().single { it.id == made["taskId"]!!.jsonPrimitive.content }.ownSessionId)
    }

    @Test
    fun `set_task_lead names a pane, answers with it and resolves to the record running in that pane`() {
        session("a"); session("b")
        val id = create("Work", """["a"]""")["taskId"]!!.jsonPrimitive.content
        val first = taskInState(id).leadRecordId
        assertEquals(tracker.openRecordOf("a")!!.id, first)

        val set = call("set_task_lead", """{"taskId":"$id","sessionId":"b"}""")
        assertTrue(!isError(set), textOf(set))
        assertEquals("b", payload(set)["ownSession"]!!.jsonPrimitive.content)
        assertEquals(tracker.openRecordOf("b")!!.id, taskInState(id).leadRecordId)

        // The same pane starts another run: the newest record of that pane speaks for the task.
        tracker.onHook(
            PaneSnapshot("b", "leaf-b", "/work/b", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionEnd"); put("session_id", "a-b") },
        )
        now += 50
        tracker.onHook(
            PaneSnapshot("b", "leaf-b", "/work/b", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-b2") },
        )
        index.recompute()
        assertEquals("r3", tracker.openRecordOf("b")!!.id)
        assertEquals("r3", taskInState(id).leadRecordId)
    }

    @Test
    fun `set_task_lead refuses an unknown task or pane with add_to_task's words and changes nothing`() {
        session("a")
        val id = create("Work", """["a"]""")["taskId"]!!.jsonPrimitive.content
        val noTask = call("set_task_lead", """{"taskId":"ghost","sessionId":"a"}""")
        assertTrue(isError(noTask))
        assertTrue("Unknown task 'ghost'" in textOf(noTask))
        val noPane = call("set_task_lead", """{"taskId":"$id","sessionId":"ghost-pane"}""")
        val addToTask = call("add_to_task", """{"taskId":"$id","sessionIds":["ghost-pane"]}""")
        assertTrue(isError(noPane))
        assertEquals(textOf(addToTask), textOf(noPane))
        assertEquals("a", index.listing().single { it.id == id }.ownSessionId)
    }

    @Test
    fun `a task file's task can be given an own session too`() {
        session("a")
        files.value = listOf(TaskFile("file-task", "From a file", null, null, emptyList(), 100L))
        val set = call("set_task_lead", """{"taskId":"file-task","sessionId":"a"}""")
        assertTrue(!isError(set), textOf(set))
        assertEquals(tracker.openRecordOf("a")!!.id, taskInState("file-task").leadRecordId)
    }

    @Test
    fun `a status has its line breaks turned into spaces, its ends trimmed and 120 characters at most`() {
        session("a")
        val id = create("Work", """["a"]""")["taskId"]!!.jsonPrimitive.content
        val set = call("set_task_status", """{"taskId":"$id","text":"  first line\n\n  second\r\nthird  "}""", caller = "a")
        assertTrue(!isError(set), textOf(set))
        assertEquals("first line second third", payload(set)["status"]!!.jsonPrimitive.content)
        assertEquals("first line second third", taskInState(id).status)
        assertNotNull(taskInState(id).statusAtMillis)

        val long = "x".repeat(300)
        call("set_task_status", """{"taskId":"$id","text":"$long"}""")
        assertEquals(120, taskInState(id).status!!.length)
        // A cut that would leave a trailing space does not keep it.
        call("set_task_status", """{"taskId":"$id","text":"${"y".repeat(119)} zzz"}""")
        assertEquals("y".repeat(119), taskInState(id).status)
    }

    @Test
    fun `an empty status clears it and list_tasks reports status and own session`() {
        session("a")
        val id = create("Work", """["a"]""")["taskId"]!!.jsonPrimitive.content
        call("set_task_status", """{"taskId":"$id","text":"halfway"}""")
        val listed = payload2(call("list_tasks")).single { it["taskId"]!!.jsonPrimitive.content == id }
        assertEquals("halfway", listed["status"]!!.jsonPrimitive.content)
        assertEquals("a", listed["ownSession"]!!.jsonPrimitive.content)

        call("set_task_status", """{"taskId":"$id","text":"  \n "}""")
        assertNull(taskInState(id).status)
        assertNull(taskInState(id).statusAtMillis)
        assertEquals("null", payload2(call("list_tasks")).single { it["taskId"]!!.jsonPrimitive.content == id }["status"].toString())
    }

    @Test
    fun `set_task_status refuses an unknown task`() {
        val r = call("set_task_status", """{"taskId":"ghost","text":"x"}""")
        assertTrue(isError(r))
        assertTrue("Unknown task 'ghost'" in textOf(r))
    }

    @Test
    fun `lead, status and start survive a restart`() {
        session("a")
        val id = create("Work", """["a"]""")["taskId"]!!.jsonPrimitive.content
        call("set_task_status", """{"taskId":"$id","text":"kept"}""")
        val before = taskInState(id)
        now += 1_000_000
        val reopened = TaskIndex(repo, tracker, files, leafOfSession = { null }, clock = { now })
        val after = reopened.state.value.tasks.single { it.id == id }
        assertEquals("kept", after.status)
        assertEquals(before.leadRecordId, after.leadRecordId)
        assertEquals(before.startedAtMillis, after.startedAtMillis)
    }

    @Test
    fun `after a server restart the own session resolves to its ended record, so enter resumes it`() {
        session("boss")
        val id = create("Work", "[\"boss\"]")["taskId"]!!.jsonPrimitive.content
        val record = taskInState(id).leadRecordId
        assertNotNull(record)

        // A restart: the tracker ends the records the old process left open and loads them again.
        now += 60_000
        var next = 100
        val restarted = AgentSessionTracker(
            repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "n${++next}" },
        ).also { it.start() }
        val reopened = TaskIndex(repo, restarted, files, leafOfSession = { null }, clock = { now })

        val after = reopened.state.value.tasks.single { it.id == id }
        assertEquals(record, after.leadRecordId, "the ended record is found by the pane the task remembers")
        assertEquals("ended", restarted.snapshot().single { it.id == record }.state)
    }

    @Test
    fun `a task's start is the earliest session start, stamped once and never moved`() {
        session("early"); session("late")
        val early = tracker.openRecordOf("early")!!.createdAt
        val id = create("Work", """["late"]""")["taskId"]!!.jsonPrimitive.content
        // Only the later session is in it so far.
        assertEquals(tracker.openRecordOf("late")!!.createdAt, taskInState(id).startedAtMillis)
        call("add_to_task", """{"taskId":"$id","sessionIds":["early"]}""")
        assertEquals(tracker.openRecordOf("late")!!.createdAt, taskInState(id).startedAtMillis)
        assertTrue(early < taskInState(id).startedAtMillis!!)
    }

    private fun payload2(r: JsonObject) = mcpJson.parseToJsonElement(textOf(r)).jsonArray.map { it.jsonObject }
}
