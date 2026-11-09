/**
 * Tests for the MCP tools that make and describe tasks (`create_task`, `add_to_task`,
 * `remove_from_task`, `rename_task`, `set_task_lead`, `set_task_status`, `list_tasks`) and for how
 * `create_window`, `run_command` and `create_tab` place a task's windows and background windows.
 * Each tool is driven through [McpServer.handleMessage] against a real [TaskIndex], tracker and
 * database. The argument and result shapes themselves are pinned by [McpContractTest].
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
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
import se.soderbjorn.lunamux.listedTabs
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTaskToolsTest {
    private val dir = File.createTempFile("lunamux-mcp-task", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val panes = mutableSetOf<String>()
    private var nextId = 0
    private var now = 5_000L
    private val tracker = AgentSessionTracker(repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" }).also { it.start() }
    private val index = TaskIndex(
        repo, tracker, files, leafOfSession = { if (it in panes) "leaf-$it" else WindowState.leafIdOfSession(it) }, clock = { now },
    )

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
    }

    private fun rpc(body: String, scope: McpScope = McpScope.READ_WRITE, caller: String? = null) = runBlocking {
        mcpJson.parseToJsonElement(McpServer.handleMessage(body, scope, null, caller).body!!).jsonObject["result"]!!.jsonObject
    }

    private fun call(tool: String, args: String = "{}", caller: String? = null, scope: McpScope = McpScope.READ_WRITE) =
        rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$args}}""", scope, caller)

    private fun textOf(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun payload(r: JsonObject): JsonElement = mcpJson.parseToJsonElement(textOf(r))
    private fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.content == "true"
    private fun str(o: JsonElement, k: String) = o.jsonObject[k]!!.jsonPrimitive.content
    private fun refusal(r: JsonObject): String { assertTrue(isError(r), "expected a refusal, got ${textOf(r)}"); return textOf(r) }

    /** A pane running a Claude conversation `a-<id>`. */
    private fun session(id: String) {
        panes += id
        now += 10
        tracker.onHook(
            PaneSnapshot(id, "leaf-$id", "/work/$id", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$id") },
        )
    }

    private fun create(title: String, sessions: String = "[]", caller: String? = null) = str(payload(call("create_task", """{"title":"$title","sessionIds":$sessions}""", caller)), "taskId")
    private fun task(id: String) = index.state.value.tasks.single { it.id == id }
    private fun listed(id: String) = payload(call("list_tasks")).jsonArray.single { str(it, "taskId") == id }.jsonObject
    private fun taskOf(sessionId: String) = tracker.openRecordOf(sessionId)!!.taskId
    private fun allTabs() = WindowState.config.value.worlds.flatMap { it.tabs }
    private fun tabOf(created: JsonElement) = WindowState.tabIdOfPane(str(created, "windowId"))

    /** Runs [block] and closes the tabs it opened, which live in the process-wide window state. */
    private fun withTabs(block: (open: (String, Boolean) -> String) -> Unit) {
        val opened = mutableListOf<String>()
        try {
            block { title, background ->
                str(payload(call("create_tab", """{"title":"$title","background":$background}""")), "tabId").also { opened += it }
            }
        } finally {
            opened.forEach { call("close_tab", """{"tabId":"$it"}""") }
            allTabs().filter { it.title == "Reviewer" || it.title == "Helper" }.forEach { call("close_tab", """{"tabId":"${it.id}"}""") }
        }
    }

    @Test
    fun `the instructions say when to group and to join an existing task when continuing work`() {
        val text = rpc(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        )["instructions"]!!.jsonPrimitive.content
        assertTrue("create_task" in text && "create_window with a taskId" in text, text)
        assertTrue("list_tasks first" in text && "instead of creating another" in text, text)
    }

    @Test
    fun `sessions join and leave a task, a task file's id counts, and bad names change nothing`() {
        session("s1"); session("s2")
        val id = create("Split the importer", """["s1"]""")
        assertTrue(id.startsWith("split-the-importer-"))
        assertEquals(id, taskOf("s1"))
        assertFalse(isError(call("rename_task", """{"taskId":"$id","title":"Renamed"}""")))
        assertEquals("Renamed", task(id).title)

        files.value = listOf(TaskFile("prog", "Programme", null, null, 1))
        assertFalse(isError(call("add_to_task", """{"taskId":"prog","sessionIds":["s2"]}""")))
        assertEquals("prog", taskOf("s2"))

        assertFalse(isError(call("remove_from_task", """{"sessionIds":["s1"]}""")))
        assertNull(taskOf("s1"))
        assertTrue("ghost" in refusal(call("remove_from_task", """{"sessionIds":["ghost"]}""")))
        assertTrue("missing" in refusal(call("add_to_task", """{"taskId":"missing","sessionIds":["s1"]}""")))
        assertNull(taskOf("s1"))
    }

    @Test
    fun `only the tools that change tasks need a write token`() {
        for (tool in listOf("create_task", "add_to_task", "remove_from_task", "rename_task", "set_task_lead", "set_task_status")) {
            assertTrue("read+write" in refusal(call(tool, "{}", scope = McpScope.READ)), tool)
        }
        assertFalse(isError(call("list_tasks", scope = McpScope.READ)))
    }

    @Test
    fun `the caller's pane speaks for the task it creates, and set_task_lead hands that to another conversation`() {
        session("boss"); session("a"); session("b")
        val id = create("Plan", """["a","b"]""", caller = "boss")
        assertEquals("boss", str(payload(call("list_tasks")).jsonArray.single { str(it, "taskId") == id }, "ownSession"))

        val set = call("set_task_lead", """{"taskId":"$id","sessionId":"b"}""")
        assertEquals("a-b", str(payload(set).jsonObject["mainConversation"]!!, "conversationId"))
        assertEquals("b", str(payload(set).jsonObject["mainConversation"]!!, "sessionId"))
        assertEquals("a-b", task(id).mainConversation)

        // The pane starts another run: the task keeps speaking through the conversation it named.
        val pane = PaneSnapshot("b", "leaf-b", "/work/b", null, screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionEnd"); put("session_id", "a-b") })
        now += 50
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-b2") })
        index.recompute()
        assertEquals("a-b", task(id).mainConversation)
        assertNull(index.listing().single { it.id == id }.ownSessionId, "the new run is another conversation")

        val byConversation = call("set_task_lead", """{"taskId":"$id","conversationId":"a-a"}""")
        assertEquals("a-a", str(payload(byConversation).jsonObject["mainConversation"]!!, "conversationId"))
        assertTrue(isError(call("set_task_lead", """{"taskId":"$id"}""")))
        assertTrue(isError(call("set_task_lead", """{"taskId":"$id","sessionId":"a","conversationId":"a-b"}""")))
        assertTrue(isError(call("set_task_lead", """{"taskId":"$id","conversationId":"nobody"}""")))
        assertEquals("a-a", task(id).mainConversation)
    }

    @Test
    fun `a status is one trimmed line of 120 characters at most, an empty one clears it, and questionWaits is set afresh by each call`() {
        session("a")
        val id = create("Work", """["a"]""")
        val set = call("set_task_status", """{"taskId":"$id","text":"  first line\n\n  second\r\nthird  "}""", caller = "a")
        assertEquals("first line second third", str(payload(set), "status"))
        assertEquals("first line second third", listed(id)["status"]!!.jsonPrimitive.content)

        call("set_task_status", """{"taskId":"$id","text":"${"y".repeat(119)} zzz"}""")
        assertEquals("y".repeat(119), task(id).status, "a cut never leaves a trailing space")

        call("set_task_status", """{"taskId":"$id","text":"which database?","questionWaits":true}""")
        assertTrue(task(id).questionWaits)
        assertEquals("true", str(listed(id), "questionWaits"))
        call("set_task_status", """{"taskId":"$id","text":"migrating"}""")
        assertFalse(task(id).questionWaits, "a later status says the question is answered")

        call("set_task_status", """{"taskId":"$id","text":"again?","questionWaits":true}""")
        call("set_task_status", """{"taskId":"$id","text":"","questionWaits":true}""")
        assertNull(task(id).status)
        assertNull(task(id).statusAtMillis)
        assertFalse(task(id).questionWaits, "an empty text clears the flag with the status")
    }

    @Test
    fun `list_tasks gives each task's sessions with their state and its main conversation, with no pane once closed`() {
        session("a")
        val id = create("Work", """["a"]""")
        val open = listed(id)
        assertEquals("a-a", str(open["mainConversation"]!!, "conversationId"))
        assertEquals("a", str(open["mainConversation"]!!, "sessionId"))
        assertEquals("done", str(open["sessions"]!!.jsonArray.single(), "state"))

        tracker.onSessionDestroyed("a", null)
        index.recompute()
        val closed = listed(id)["mainConversation"]!!.jsonObject
        assertEquals("a-a", str(closed, "conversationId"))
        assertEquals("null", closed["sessionId"].toString())
    }

    @Test
    fun `after a server restart the main conversation is still the stored one, closed`() {
        session("boss")
        val id = create("Work", """["boss"]""")
        now += 60_000
        var next = 100
        val restarted = AgentSessionTracker(repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "n${++next}" }).also { it.start() }
        val reopened = TaskIndex(repo, restarted, files, leafOfSession = { null }, clock = { now })
        assertEquals("a-boss", reopened.state.value.tasks.single { it.id == id }.mainConversation)
        assertEquals("ended", restarted.snapshot().single { it.agentSessionId == "a-boss" }.state)
        assertNull(reopened.listing().single { it.id == id }.ownSessionId)
    }

    @Test
    fun `a task's start is its earliest session's start, stamped once and never moved`() {
        session("early"); session("late")
        val id = create("Work", """["late"]""")
        val late = tracker.openRecordOf("late")!!.createdAt
        assertEquals(late, task(id).startedAtMillis)
        call("add_to_task", """{"taskId":"$id","sessionIds":["early"]}""")
        assertEquals(late, task(id).startedAtMillis)
    }

    @Test
    fun `a task's windows open in the tab its agents are in, an explicit tab wins, and an unknown task opens nothing`() = withTabs { open ->
        val taskTab = open("Task tab", false)
        val activeTab = open("Active tab", false)
        val id = create("Two windows")
        val before = allTabs().sumOf { it.panes.size }
        assertTrue("missing" in refusal(call("create_window", """{"taskId":"missing"}""")))
        assertTrue("missing" in refusal(call("run_command", """{"command":"true","taskId":"missing","waitForExit":false}""")))
        assertEquals(before, allTabs().sumOf { it.panes.size }, "no pane was opened")

        val first = payload(call("create_window", """{"tabId":"$taskTab","taskId":"$id"}"""))
        val pane = PaneSnapshot(str(first, "sessionId"), WindowState.leafIdOfSession(str(first, "sessionId"))!!, "/work/new", null, screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-new") })
        assertEquals(id, tracker.openRecordOf(str(first, "sessionId"))!!.taskId, "the new pane was promised the task")

        call("activate_tab", """{"tabId":"$activeTab"}""")
        assertEquals(activeTab, tabOf(payload(call("create_window", "{}"))), "without a task the active tab")
        assertEquals(taskTab, tabOf(payload(call("create_window", """{"taskId":"$id"}"""))))
        assertEquals(taskTab, tabOf(payload(call("run_command", """{"command":"true","taskId":"$id","waitForExit":false}"""))))
        assertEquals(activeTab, tabOf(payload(call("create_window", """{"taskId":"$id","tabId":"$activeTab"}"""))))
        val empty = create("Nothing open yet")
        assertEquals(activeTab, tabOf(payload(call("create_window", """{"taskId":"$empty"}"""))), "a task with no pane has no tab")
    }

    @Test
    fun `a background tab is never listed or activated, and background windows keep to background tabs`() = withTabs { open ->
        val visible = open("Visible", false)
        val activeBefore = WindowState.config.value.activeTabId
        val made = payload(call("create_tab", """{"title":"Out of sight","background":true}"""))
        val bg = str(made, "tabId")
        assertEquals(activeBefore, WindowState.config.value.activeTabId, "a background tab never takes the focus")
        assertTrue(allTabs().single { it.id == bg }.isBackground)
        assertTrue(WindowState.config.value.listedTabs.none { it.id == bg })
        assertEquals("true", str(payload(call("list_layout")).jsonObject["tabs"]!!.jsonArray.single { str(it, "tabId") == bg }, "background"))
        assertTrue("background" in refusal(call("activate_tab", """{"tabId":"$bg"}""")))
        assertTrue("background" in refusal(call("activate_window", """{"windowId":"${str(made, "windowId")}"}""")))

        assertEquals(bg, str(payload(call("create_window", """{"background":true,"tabId":"$bg"}""")), "tabId"))
        assertTrue("background windows go in a background tab" in refusal(call("create_window", """{"background":true,"tabId":"$visible"}""")))
        assertTrue(isError(call("create_window", """{"tabId":"$bg"}""")))

        val own = payload(call("create_window", """{"background":true,"title":"Reviewer"}"""))
        assertTrue(allTabs().single { it.id == str(own, "tabId") }.isBackground)
        val ran = payload(call("run_command", """{"command":"true","background":true,"waitForExit":false}"""))
        assertTrue(allTabs().single { it.id == tabOf(ran) }.isBackground)
        assertEquals(activeBefore, WindowState.config.value.activeTabId)
    }

    @Test
    fun `a task's visible windows never go to a background tab, and close_tab closes a busy or background tab at once`() = withTabs { open ->
        val visible = open("Task tab", false)
        val id = create("Split work")
        call("create_window", """{"tabId":"$visible","taskId":"$id"}""")
        val helper = payload(call("create_window", """{"background":true,"taskId":"$id","title":"Helper"}"""))
        assertNotEquals(visible, str(helper, "tabId"))
        assertEquals(visible, str(payload(call("create_window", """{"taskId":"$id"}""")), "tabId"))

        val busy = payload(call("create_tab", """{"title":"Busy"}"""))
        tracker.onHook(
            PaneSnapshot("pty-busy", str(busy, "windowId"), "/work/busy", null),
            buildJsonObject { put("hook_event_name", "UserPromptSubmit"); put("session_id", "a-busy"); put("prompt", "go") },
        )
        assertEquals("working", tracker.openRecordOf("pty-busy")!!.state)
        assertFalse(isError(call("close_tab", """{"tabId":"${str(busy, "tabId")}"}""")), "an agent's close_tab never waits for a person")
        assertFalse(isError(call("close_tab", """{"tabId":"${str(helper, "tabId")}"}""")))
        assertTrue(allTabs().none { it.id == str(busy, "tabId") || it.id == str(helper, "tabId") })
    }

    @Test
    fun `no task or panel tool is described in orchestration wording`() {
        val tools = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["tools"]!!.jsonArray.map { it.jsonObject }
            .filter { str(it, "name").let { n -> n.endsWith("_task") || n.contains("task") } }
        assertTrue(tools.size >= 7)
        for (t in tools) for (word in listOf("orchestrat", "programme", "manager", "owner")) {
            assertFalse(word in t.toString().lowercase(), "${str(t, "name")} mentions '$word'")
        }
    }
}
