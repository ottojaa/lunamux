/**
 * Tests for the MCP task tools: `create_task`, `add_to_task`, `remove_from_task`, `rename_task`,
 * `list_tasks` and `create_window` with a `taskId`. Each tool is driven through
 * [McpServer.handleMessage] against a real [TaskIndex] and tracker, and checked for what it
 * changes, what it answers and, for bad arguments, that it names the offender and changes nothing.
 *
 * @see registerMcpTaskTools
 * @see se.soderbjorn.lunamux.TaskIndex
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class McpTaskToolsTest {
    private val dir = File.createTempFile("lunamux-mcp-task", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val panes = mutableSetOf<String>()
    private var nextId = 0
    private val tracker = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { 1_000L + nextId }, newId = { "r${++nextId}" },
    ).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { if (it in panes) "leaf-$it" else WindowState.leafIdOfSession(it) })

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
    }

    private fun call(tool: String, argsJson: String = "{}", scope: McpScope = McpScope.READ_WRITE): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            scope, null,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(result: JsonObject) = result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun payloadOf(result: JsonObject) = mcpJson.parseToJsonElement(textOf(result))
    private fun isError(result: JsonObject) = result["isError"]?.jsonPrimitive?.content == "true"

    private fun session(id: String) {
        panes += id
        val pane = PaneSnapshot(id, "leaf-$id", "/work/$id", null, screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$id") })
    }

    private fun taskOf(sessionId: String) = tracker.openRecordOf(sessionId)!!.taskId

    @Test
    fun `the initialize instructions say when to group, and to join an existing task when continuing work`() {
        val handled = runBlocking {
            McpServer.handleMessage(
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
                McpScope.READ_WRITE, null,
            )
        }
        val text = mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertTrue("create_task" in text && "create_window with a taskId" in text, text)
        assertTrue("continue earlier work" in text && "list_tasks first" in text && "instead of creating another" in text, text)
    }

    @Test
    fun `create_task returns an id and puts the named sessions in the task`() {
        session("s1"); session("s2")
        val result = call("create_task", """{"title":"Split the importer","sessionIds":["s1","s2"]}""")
        assertTrue(!isError(result), textOf(result))
        val id = payloadOf(result).jsonObject["taskId"]!!.jsonPrimitive.content
        assertTrue(id.startsWith("split-the-importer-"))
        assertEquals(id, taskOf("s1"))
        assertEquals(id, taskOf("s2"))
    }

    @Test
    fun `add_to_task joins sessions to a task, including a task file's id`() {
        session("s1"); session("s2")
        val id = payloadOf(call("create_task", """{"title":"A"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        assertTrue(!isError(call("add_to_task", """{"taskId":"$id","sessionIds":["s1"]}""")))
        assertEquals(id, taskOf("s1"))

        files.value = listOf(TaskFile("prog", "Programme", null, null, 1))
        assertTrue(!isError(call("add_to_task", """{"taskId":"prog","sessionIds":["s2"]}""")))
        assertEquals("prog", taskOf("s2"))
    }

    @Test
    fun `remove_from_task takes sessions out and answers where each one is now`() {
        session("s1")
        val id = payloadOf(call("create_task", """{"title":"A","sessionIds":["s1"]}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        assertEquals(id, taskOf("s1"))
        val result = call("remove_from_task", """{"sessionIds":["s1"]}""")
        assertTrue(!isError(result), textOf(result))
        assertNull(taskOf("s1"))
        assertEquals(JsonPrimitive(null as String?), payloadOf(result).jsonObject["sessions"]!!.jsonObject["s1"]!!.let { if (it is kotlinx.serialization.json.JsonNull) JsonPrimitive(null as String?) else it })
        val bad = call("remove_from_task", """{"sessionIds":["ghost"]}""")
        assertTrue(isError(bad)); assertTrue(textOf(bad).contains("ghost"))
        assertTrue(isError(call("remove_from_task", "{}")))
    }

    @Test
    fun `the tools that change tasks need a write token and list_tasks does not`() {
        for (tool in listOf("create_task", "add_to_task", "remove_from_task", "rename_task")) {
            val r = call(tool, "{}", scope = McpScope.READ)
            assertTrue(isError(r), tool)
            assertTrue(textOf(r).contains("read+write"), tool)
        }
        assertTrue(!isError(call("list_tasks", scope = McpScope.READ)))
    }

    @Test
    fun `create_window with a taskId promises the task to the new pane and rejects an unknown task before opening one`() {
        val id = payloadOf(call("create_task", """{"title":"Two sessions"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        val before = WindowState.config.value.tabs.sumOf { it.panes.size }
        val bad = call("create_window", """{"taskId":"missing"}""")
        assertTrue(isError(bad)); assertTrue(textOf(bad).contains("missing"))
        assertEquals(before, WindowState.config.value.tabs.sumOf { it.panes.size }, "no pane was opened")

        val tabId = payloadOf(call("create_tab", """{"title":"Task tab"}""")).jsonObject["tabId"]!!.jsonPrimitive.content
        try {
            val created = payloadOf(call("create_window", """{"tabId":"$tabId","taskId":"$id"}""")).jsonObject
            val sessionId = created["sessionId"]!!.jsonPrimitive.content
            assertNotNull(WindowState.leafIdOfSession(sessionId))
            val leaf = WindowState.leafIdOfSession(sessionId)!!
            tracker.onHook(
                PaneSnapshot(sessionId, leaf, "/work/new", null, screenText = { "x" }),
                buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-new") },
            )
            assertEquals(id, tracker.openRecordOf(sessionId)!!.taskId)
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    /** Open a tab and return its id; the new tab becomes the active one. */
    private fun newTab(title: String) =
        payloadOf(call("create_tab", """{"title":"$title"}""")).jsonObject["tabId"]!!.jsonPrimitive.content

    /** A window in [tabId] whose agent has started and joined [taskId]; returns the pane the agent runs in. */
    private fun agentWindowIn(tabId: String, taskId: String, agentId: String): PaneSnapshot {
        val created = payloadOf(call("create_window", """{"tabId":"$tabId","taskId":"$taskId"}""")).jsonObject
        val sessionId = created["sessionId"]!!.jsonPrimitive.content
        val pane = PaneSnapshot(sessionId, WindowState.leafIdOfSession(sessionId)!!, "/work/$agentId", null, screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", agentId) })
        return pane
    }

    private fun tabOfWindow(created: JsonObject) = WindowState.tabIdOfPane(created["windowId"]!!.jsonPrimitive.content)

    @Test
    fun `a task's next window opens in the tab its sessions are in, not the active tab`() {
        val taskTab = newTab("Task tab")
        val activeTab = newTab("Active tab")
        try {
            val id = payloadOf(call("create_task", """{"title":"Split the importer"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            agentWindowIn(taskTab, id, "a-first")
            call("activate_tab", """{"tabId":"$activeTab"}""")
            val plain = payloadOf(call("create_window", "{}")).jsonObject
            assertEquals(activeTab, tabOfWindow(plain), "the other tab is the one in front")

            val second = payloadOf(call("create_window", """{"taskId":"$id"}""")).jsonObject
            assertEquals(taskTab, second["tabId"]!!.jsonPrimitive.content)
            assertEquals(taskTab, tabOfWindow(second))
        } finally {
            call("close_tab", """{"tabId":"$activeTab"}""")
            call("close_tab", """{"tabId":"$taskTab"}""")
        }
    }

    @Test
    fun `with no tab for the task the window opens in the active tab, and an explicit tabId wins`() {
        val taskTab = newTab("Task tab")
        val activeTab = newTab("Active tab")
        try {
            call("activate_tab", """{"tabId":"$activeTab"}""")
            val empty = payloadOf(call("create_task", """{"title":"Nothing open yet"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            val first = payloadOf(call("create_window", """{"taskId":"$empty"}""")).jsonObject
            assertEquals(activeTab, tabOfWindow(first), "a task with no pane has no tab, so the active tab as before")

            val id = payloadOf(call("create_task", """{"title":"Has a tab"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            agentWindowIn(taskTab, id, "a-has-tab")
            val explicit = payloadOf(call("create_window", """{"taskId":"$id","tabId":"$activeTab"}""")).jsonObject
            assertEquals(activeTab, tabOfWindow(explicit), "an explicit tabId beats the task's tab")

            val plain = payloadOf(call("create_window", "{}")).jsonObject
            assertEquals(activeTab, tabOfWindow(plain), "without a taskId nothing changes")
        } finally {
            call("close_tab", """{"tabId":"$activeTab"}""")
            call("close_tab", """{"tabId":"$taskTab"}""")
        }
    }

    @Test
    fun `run_command that creates a window follows the task's tab and joins the task`() {
        val taskTab = newTab("Task tab")
        val activeTab = newTab("Active tab")
        try {
            val id = payloadOf(call("create_task", """{"title":"Runs in its tab"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            agentWindowIn(taskTab, id, "a-run")
            call("activate_tab", """{"tabId":"$activeTab"}""")
            val ran = payloadOf(call("run_command", """{"command":"true","taskId":"$id","waitForExit":false}""")).jsonObject
            assertEquals(taskTab, WindowState.tabIdOfPane(ran["windowId"]!!.jsonPrimitive.content))
            assertTrue(WindowState.leafIdOfSession(ran["sessionId"]!!.jsonPrimitive.content) in tracker.pendingLeavesOf(id), "the new pane is promised the task")
            val explicit = payloadOf(call("run_command", """{"command":"true","taskId":"$id","tabId":"$activeTab","waitForExit":false}""")).jsonObject
            assertEquals(activeTab, WindowState.tabIdOfPane(explicit["windowId"]!!.jsonPrimitive.content))
            val bad = call("run_command", """{"command":"true","taskId":"missing","waitForExit":false}""")
            assertTrue(isError(bad)); assertTrue(textOf(bad).contains("missing"))
        } finally {
            call("close_tab", """{"tabId":"$activeTab"}""")
            call("close_tab", """{"tabId":"$taskTab"}""")
        }
    }

    @Test
    fun `list_tasks lists the sessions of each task with their state`() {
        session("s1")
        val id = payloadOf(call("create_task", """{"title":"A","sessionIds":["s1"]}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        val task = payloadOf(call("list_tasks", scope = McpScope.READ)).jsonArray.map { it.jsonObject }
            .single { it["taskId"]!!.jsonPrimitive.content == id }
        val s = task["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("s1", s["sessionId"]!!.jsonPrimitive.content)
        assertEquals("done", s["state"]!!.jsonPrimitive.content)
    }
}
