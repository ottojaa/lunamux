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
    fun `create_task without sessions makes an empty task that list_tasks shows`() {
        val id = payloadOf(call("create_task", """{"title":"Later"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        val listed = payloadOf(call("list_tasks", scope = McpScope.READ)).jsonArray.map { it.jsonObject }
        val mine = listed.single { it["taskId"]!!.jsonPrimitive.content == id }
        assertEquals("Later", mine["title"]!!.jsonPrimitive.content)
        assertEquals("agents", mine["source"]!!.jsonPrimitive.content)
        assertTrue(mine["sessions"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `create_task refuses a missing or blank title and an unknown session, naming it`() {
        assertTrue(textOf(call("create_task", "{}")).contains("title"))
        assertTrue(isError(call("create_task", "{}")))
        assertTrue(isError(call("create_task", """{"title":"  "}""")))
        val bad = call("create_task", """{"title":"T","sessionIds":["ghost-session"]}""")
        assertTrue(isError(bad))
        assertTrue(textOf(bad).contains("ghost-session"))
        val notArray = call("create_task", """{"title":"T","sessionIds":"s1"}""")
        assertTrue(isError(notArray))
    }

    @Test
    fun `add_to_task joins sessions to a task, including a task file's id`() {
        session("s1"); session("s2")
        val id = payloadOf(call("create_task", """{"title":"A"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        assertTrue(!isError(call("add_to_task", """{"taskId":"$id","sessionIds":["s1"]}""")))
        assertEquals(id, taskOf("s1"))

        files.value = listOf(TaskFile("prog", "Programme", null, null, emptyList(), 1))
        assertTrue(!isError(call("add_to_task", """{"taskId":"prog","sessionIds":["s2"]}""")))
        assertEquals("prog", taskOf("s2"))
    }

    @Test
    fun `add_to_task refuses an unknown task, an unknown session and an empty list, and changes nothing`() {
        session("s1")
        val id = payloadOf(call("create_task", """{"title":"A"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        val noTask = call("add_to_task", """{"taskId":"missing","sessionIds":["s1"]}""")
        assertTrue(isError(noTask)); assertTrue(textOf(noTask).contains("missing"))
        val noSession = call("add_to_task", """{"taskId":"$id","sessionIds":["s1","ghost"]}""")
        assertTrue(isError(noSession)); assertTrue(textOf(noSession).contains("ghost"))
        assertTrue(isError(call("add_to_task", """{"taskId":"$id","sessionIds":[]}""")))
        assertTrue(isError(call("add_to_task", """{"sessionIds":["s1"]}""")))
        assertNull(taskOf("s1"))
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
    fun `rename_task renames an agent-made task and explains why a file task cannot be renamed`() {
        val id = payloadOf(call("create_task", """{"title":"Before"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        assertTrue(!isError(call("rename_task", """{"taskId":"$id","title":"After"}""")))
        val listed = payloadOf(call("list_tasks", scope = McpScope.READ)).jsonArray.map { it.jsonObject }
        assertEquals("After", listed.single { it["taskId"]!!.jsonPrimitive.content == id }["title"]!!.jsonPrimitive.content)

        files.value = listOf(TaskFile("prog", "Programme", null, null, emptyList(), 1))
        val file = call("rename_task", """{"taskId":"prog","title":"X"}""")
        assertTrue(isError(file)); assertTrue(textOf(file).contains("file"))
        assertTrue(isError(call("rename_task", """{"taskId":"missing","title":"X"}""")))
        assertTrue(isError(call("rename_task", """{"taskId":"$id","title":""}""")))
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
    fun `a window created for a task before its agent has started still finds the tab`() {
        val taskTab = newTab("Task tab")
        val activeTab = newTab("Active tab")
        try {
            val id = payloadOf(call("create_task", """{"title":"Two quick windows"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            call("create_window", """{"tabId":"$taskTab","taskId":"$id"}""")
            call("activate_tab", """{"tabId":"$activeTab"}""")
            val second = payloadOf(call("create_window", """{"taskId":"$id"}""")).jsonObject
            assertEquals(taskTab, tabOfWindow(second), "the first pane has no agent yet, only the promise of the task")
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
    fun `a worldId limits the task's tab to that world, and falls back to its active tab`() {
        val homeWorld = WindowState.activeWorldId()
        val taskTab = newTab("Task tab")
        val otherWorld = payloadOf(call("create_world", """{"name":"Elsewhere"}""")).jsonObject["worldId"]!!.jsonPrimitive.content
        try {
            val otherTab = WindowState.config.value.worlds.first { it.id == otherWorld }.let { it.activeTabId ?: it.tabs.first().id }
            val id = payloadOf(call("create_task", """{"title":"Lives in the first world"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            agentWindowIn(taskTab, id, "a-home")

            val inOther = payloadOf(call("create_window", """{"taskId":"$id","worldId":"$otherWorld"}""")).jsonObject
            assertEquals(otherTab, inOther["tabId"]!!.jsonPrimitive.content, "the task has no tab in the named world")

            val inHome = payloadOf(call("create_window", """{"taskId":"$id","worldId":"$homeWorld"}""")).jsonObject
            assertEquals(taskTab, inHome["tabId"]!!.jsonPrimitive.content)
        } finally {
            call("close_world", """{"worldId":"$otherWorld"}""")
            call("switch_world", """{"worldId":"$homeWorld"}""")
            call("close_tab", """{"tabId":"$taskTab"}""")
        }
    }

    @Test
    fun `a tab the strip hides still holds its task's windows`() {
        val taskTab = newTab("Task tab")
        val activeTab = newTab("Active tab")
        try {
            val id = payloadOf(call("create_task", """{"title":"Out of sight"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            agentWindowIn(taskTab, id, "a-hidden")
            call("activate_tab", """{"tabId":"$activeTab"}""")
            WindowState.setTabHidden(taskTab, true)
            WindowState.setTabHiddenFromSidebar(taskTab, true)
            val next = payloadOf(call("create_window", """{"taskId":"$id"}""")).jsonObject
            assertEquals(taskTab, tabOfWindow(next), "a hidden tab is still open, so the task's window joins it")
        } finally {
            call("close_tab", """{"tabId":"$activeTab"}""")
            call("close_tab", """{"tabId":"$taskTab"}""")
        }
    }

    @Test
    fun `an ended session no longer holds its task's tab`() {
        val oldTab = newTab("Old tab")
        val activeTab = newTab("Active tab")
        try {
            val id = payloadOf(call("create_task", """{"title":"Finished there"}""")).jsonObject["taskId"]!!.jsonPrimitive.content
            val pane = agentWindowIn(oldTab, id, "a-ended")
            call("activate_tab", """{"tabId":"$activeTab"}""")
            tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionEnd"); put("session_id", "a-ended") })
            val next = payloadOf(call("create_window", """{"taskId":"$id"}""")).jsonObject
            assertEquals(activeTab, tabOfWindow(next))
        } finally {
            call("close_tab", """{"tabId":"$activeTab"}""")
            call("close_tab", """{"tabId":"$oldTab"}""")
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
    fun `a task spread over tabs picks the tab with most of its panes, then the first in layout order`() {
        assertEquals("b", busiestTab(listOf("a", "b", "b"), listOf("a", "b", "c")))
        assertEquals("a", busiestTab(listOf("b", "a"), listOf("a", "b", "c")), "a tie goes to the first tab in layout order")
        assertEquals("b", busiestTab(listOf("gone", "b"), listOf("a", "b")), "a pane in a tab that is not a candidate is ignored")
        assertNull(busiestTab(emptyList(), listOf("a")))
        assertNull(busiestTab(listOf("x"), listOf("a")))
    }

    @Test
    fun `list_tasks lists the sessions of each task with their state`() {
        session("s1")
        val id = payloadOf(call("create_task", """{"title":"A","sessionIds":["s1"]}""")).jsonObject["taskId"]!!.jsonPrimitive.content
        val task = payloadOf(call("list_tasks", scope = McpScope.READ)).jsonArray.map { it.jsonObject }
            .single { it["taskId"]!!.jsonPrimitive.content == id }
        val s = task["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("s1", s["sessionId"]!!.jsonPrimitive.content)
        assertEquals("idle", s["state"]!!.jsonPrimitive.content)
    }
}
