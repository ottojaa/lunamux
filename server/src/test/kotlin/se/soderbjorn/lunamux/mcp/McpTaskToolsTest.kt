/**
 * Tests for the MCP tools that make and describe tasks (`create_task`, `add_to_task`,
 * `remove_from_task`, `rename_task`, `set_task_lead`, `set_task_status`, `list_tasks`), for
 * `set_status`, and for how `create_window` and `run_command` place the windows of a task. Each tool
 * is driven through [McpServer.handleMessage] against a real [TaskIndex], tracker and database. The
 * refusal texts and the result shapes are asserted as exact strings and objects, since agents read
 * them.
 *
 * @see registerMcpTaskTools
 * @see TaskIndex
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.AgentSessionTracker
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.PaneSnapshot
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpTaskToolsTest {
    private val dir = File.createTempFile("lunamux-mcp-task", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val panes = mutableSetOf<String>()
    private var nextId = 0
    private var now = 5_000L
    private val tracker = AgentSessionTracker(repo, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" }).also { it.start() }
    private val index = TaskIndex(
        repo, tracker, files, leafOfSession = { if (it in panes) "leaf-$it" else WindowState.leafIdOfSession(it) }, clock = { now },
    )

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index, tracker)
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
            PaneSnapshot(id, "leaf-$id", "/work/$id", null),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$id") },
        )
    }

    private fun create(title: String, sessions: String = "[]", caller: String? = null) =
        str(payload(call("create_task", """{"title":"$title","sessionIds":$sessions}""", caller)), "taskId")

    private fun task(id: String) = index.state.value.tasks.single { it.id == id }
    private fun listed(id: String) = payload(call("list_tasks")).jsonArray.single { str(it, "taskId") == id }.jsonObject
    private fun taskOf(sessionId: String) = tracker.openRecordOf(sessionId)!!.taskId
    private fun allTabs() = WindowState.config.value.worlds.flatMap { it.tabs }
    private fun tabOf(created: JsonElement) = WindowState.tabIdOfPane(str(created, "windowId"))

    /** Runs [block] and closes the tabs it opened, which live in the process-wide window state. */
    private fun withTabs(block: (open: (String) -> String) -> Unit) {
        val opened = mutableListOf<String>()
        try {
            block { title -> str(payload(call("create_tab", """{"title":"$title"}""")), "tabId").also { opened += it } }
        } finally {
            opened.forEach { call("close_tab", """{"tabId":"$it"}""") }
        }
    }

    @Test
    fun `the instructions tell agents to group and to join an existing task when continuing work`() {
        val text = rpc(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""",
        )["instructions"]!!.jsonPrimitive.content
        assertTrue("create_window with a taskId" in text && "list_tasks first" in text, text)
    }

    @Test
    fun `only the tools that change tasks need a write token`() {
        for (tool in listOf("create_task", "add_to_task", "remove_from_task", "rename_task", "set_task_lead", "set_task_status", "set_status")) {
            assertTrue("read+write" in refusal(call(tool, "{}", scope = McpScope.READ)), tool)
        }
        assertFalse(isError(call("list_tasks", scope = McpScope.READ)))
    }

    @Test
    fun `the tools a person's agent calls to manage its tasks answer in exactly the shapes agents read`() {
        session("boss"); session("a")
        val made = payload(call("create_task", """{"title":"Plan the release","sessionIds":["a"]}""", caller = "boss")).jsonObject
        val id = made.getValue("taskId").jsonPrimitive.content
        assertEquals(
            buildJsonObject {
                put("taskId", id)
                put("sessions", 1)
                put("mainConversation", buildJsonObject { put("conversationId", "a-boss"); put("sessionId", "boss") })
            },
            made,
        )

        assertEquals("Added 1 session(s) to $id", textOf(call("add_to_task", """{"taskId":"$id","sessionIds":["boss"]}""")))
        assertEquals("Renamed $id", textOf(call("rename_task", """{"taskId":"$id","title":"Ship it"}""")))
        assertEquals(
            buildJsonObject { put("sessions", buildJsonObject { put("a", JsonNull) }) },
            payload(call("remove_from_task", """{"sessionIds":["a"]}""")),
        )
        assertEquals(
            buildJsonObject {
                put("taskId", id)
                put("mainConversation", buildJsonObject { put("conversationId", "a-a"); put("sessionId", "a") })
            },
            payload(call("set_task_lead", """{"taskId":"$id","sessionId":"a"}""")),
        )
        assertEquals(
            buildJsonObject { put("taskId", id); put("status", "tests running"); put("questionWaits", false); put("paused", false) },
            payload(call("set_task_status", """{"taskId":"$id","text":"tests running"}""")),
        )
        assertEquals(
            buildJsonObject { put("taskId", id); put("status", JsonNull); put("questionWaits", false); put("paused", true) },
            payload(call("set_task_status", """{"taskId":"$id","text":"","paused":true}""")),
        )
    }

    @Test
    fun `the refusals are the texts agents are told, and none of them changes anything`() {
        session("s1")
        val id = create("Real", """["s1"]""")
        val unknownTask = { t: String -> "Unknown task '$t'. Use list_tasks to see the tasks there are, or create_task to make one." }
        val unknownSession = { s: String -> "Unknown session '$s'. Use a session id from list_sessions, or \$LUNAMUX_SESSION_ID for your own pane." }

        assertEquals(unknownTask("missing"), refusal(call("add_to_task", """{"taskId":"missing","sessionIds":["s1"]}""")))
        assertEquals(unknownTask("missing"), refusal(call("rename_task", """{"taskId":"missing","title":"X"}""")))
        assertEquals(unknownTask("missing"), refusal(call("set_task_status", """{"taskId":"missing","text":"x"}""")))
        assertEquals(unknownTask("missing"), refusal(call("set_task_lead", """{"taskId":"missing","sessionId":"s1"}""")))
        assertEquals(unknownSession("ghost"), refusal(call("add_to_task", """{"taskId":"$id","sessionIds":["ghost"]}""")))
        assertEquals(unknownSession("ghost"), refusal(call("remove_from_task", """{"sessionIds":["ghost"]}""")))
        assertEquals(unknownSession("ghost"), refusal(call("create_task", """{"title":"X","sessionIds":["ghost"]}""")))
        assertEquals(unknownSession("ghost"), refusal(call("set_task_lead", """{"taskId":"$id","sessionId":"ghost"}""")))
        assertEquals("A task needs a title.", refusal(call("create_task", """{"title":"   "}""")))
        assertEquals("Missing required argument 'title'", refusal(call("rename_task", """{"taskId":"$id","title":""}""")))
        assertEquals("A task needs a title.", refusal(call("rename_task", """{"taskId":"$id","title":"  "}""")))
        assertEquals("sessionIds must name at least one session", refusal(call("add_to_task", """{"taskId":"$id","sessionIds":[]}""")))
        assertEquals("sessionIds must name at least one session", refusal(call("remove_from_task", """{"sessionIds":[]}""")))
        assertEquals("'sessionIds' must be an array of strings", refusal(call("remove_from_task", """{"sessionIds":"s1"}""")))
        assertEquals("Give exactly one of 'sessionId' and 'conversationId'", refusal(call("set_task_lead", """{"taskId":"$id"}""")))
        assertEquals(
            "Give exactly one of 'sessionId' and 'conversationId'",
            refusal(call("set_task_lead", """{"taskId":"$id","sessionId":"s1","conversationId":"a-s1"}""")),
        )
        assertEquals(
            "Unknown conversation 'nobody'. Use the conversationId list_tasks or list_sessions shows.",
            refusal(call("set_task_lead", """{"taskId":"$id","conversationId":"nobody"}""")),
        )
        panes += "bare"
        assertEquals(
            "No agent runs in session 'bare', so there is no conversation to name. Start the agent there first, or pass a conversationId.",
            refusal(call("set_task_lead", """{"taskId":"$id","sessionId":"bare"}""")),
        )
        assertEquals("'text' must be a string", refusal(call("set_task_status", """{"taskId":"$id","text":null}""")))
        assertEquals("'text' must be a string", refusal(call("set_task_status", """{"taskId":"$id","text":["x"]}""")))

        files.value = listOf(TaskFile("prog", "Programme", null, null, 1))
        assertEquals(
            "Task 'prog' is declared by a task file, which owns its title. Edit the file's title instead.",
            refusal(call("rename_task", """{"taskId":"prog","title":"Mine"}""")),
        )
        assertEquals(id, taskOf("s1"), "nothing moved")
        assertEquals("Real", task(id).title)
    }

    @Test
    fun `sessions join and leave a task, a task file's id counts, and bad names change nothing`() {
        session("s1"); session("s2")
        val id = create("Split the importer", """["s1"]""")
        assertTrue(id.startsWith("split-the-importer-"))
        assertEquals(id, taskOf("s1"))
        call("rename_task", """{"taskId":"$id","title":"Renamed"}""")
        assertEquals("Renamed", task(id).title)

        files.value = listOf(TaskFile("prog", "Programme", null, null, 1))
        call("add_to_task", """{"taskId":"prog","sessionIds":["s2"]}""")
        assertEquals("prog", taskOf("s2"))
        call("remove_from_task", """{"sessionIds":["s1"]}""")
        assertNull(taskOf("s1"))
        assertTrue("ghost" in refusal(call("remove_from_task", """{"sessionIds":["ghost"]}""")))
        assertTrue("missing" in refusal(call("add_to_task", """{"taskId":"missing","sessionIds":["s1"]}""")))
        assertNull(taskOf("s1"))
    }

    @Test
    fun `the owner's flow, create_task then set_task_status with paused then list_tasks, reads back what was said`() {
        session("owner"); session("run")
        val id = create("Access redesign", """["owner","run"]""", caller = "owner")
        now = 9_000
        val set = call("set_task_status", """{"taskId":"$id","text":"Paused: laptop congested","paused":true}""", caller = "owner")
        assertEquals("Paused: laptop congested", str(payload(set), "status"))
        assertEquals("true", str(payload(set), "paused"))

        val row = listed(id)
        assertEquals(
            setOf("taskId", "title", "source", "mainConversation", "status", "questionWaits", "paused", "startedAt", "sessions"),
            row.keys,
        )
        assertEquals("agents", str(row, "source"))
        assertEquals("Access redesign", str(row, "title"))
        assertEquals("Paused: laptop congested", str(row, "status"))
        assertEquals("true", str(row, "paused"))
        assertEquals("a-owner", str(row.getValue("mainConversation"), "conversationId"))
        assertTrue(row.getValue("startedAt").jsonPrimitive.content.toLong() > 0)
        assertEquals(
            setOf("owner" to "working", "run" to "working").map { it.first }.toSet(),
            row.getValue("sessions").jsonArray.map { str(it, "sessionId") }.toSet(),
        )
        assertEquals(9_000L, task(id).pausedAt)
    }

    @Test
    fun `list_tasks lists file tasks first and agent-made ones after, each by id, with a null session once closed`() {
        files.value = listOf(TaskFile("zz", "Zed", null, null, 1), TaskFile("aa", "Aye", "lunamux", null, 2))
        session("s1")
        val made = create("Made", """["s1"]""")
        val rows = payload(call("list_tasks")).jsonArray
        assertEquals(listOf("aa" to "file", "zz" to "file", made to "agents"), rows.map { str(it, "taskId") to str(it, "source") })
        assertEquals(JsonArray(emptyList()), rows[0].jsonObject["sessions"], "a task with no conversation lists none")
        assertEquals("s1", str(rows[2].jsonObject["sessions"]!!.jsonArray.single(), "sessionId"))

        tracker.onSessionDestroyed("s1", null)
        val closed = listed(made)["sessions"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, closed["sessionId"], "a closed conversation has no pane")
        assertEquals("ended", str(closed, "state"))
    }

    @Test
    fun `list_tasks says what was set even when the overview hides it, and startedAt is the task's start`() {
        session("a")
        val id = create("Counts", """["a"]""")
        call("set_task_status", """{"taskId":"$id","text":"2 working, 1 waiting"}""")
        assertEquals("2 working, 1 waiting", str(listed(id), "status"))
        assertNull(task(id).status, "the overview hides a status that only counts")
        assertEquals(task(id).startedAt.toString(), str(listed(id), "startedAt"))
    }

    @Test
    fun `the caller's pane speaks for the task it creates, and set_task_lead hands that to another conversation`() {
        session("boss"); session("a"); session("b")
        val id = create("Plan", """["a","b"]""", caller = "boss")
        assertEquals("a-boss", str(listed(id)["mainConversation"]!!, "conversationId"))
        assertTrue(task(id).startedAt!! > 0, "the task's start is stamped when it is made")

        val set = payload(call("set_task_lead", """{"taskId":"$id","sessionId":"b"}""")).jsonObject["mainConversation"]!!
        assertEquals(listOf("a-b", "b"), listOf(str(set, "conversationId"), str(set, "sessionId")))

        // The pane starts another run: the task keeps speaking through the conversation it named.
        val pane = PaneSnapshot("b", "leaf-b", "/work/b", null)
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionEnd"); put("session_id", "a-b") })
        now += 50
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-b2") })
        index.recompute()
        assertEquals("a-b", task(id).main)
        assertEquals(JsonNull, listed(id)["mainConversation"]!!.jsonObject["sessionId"], "closed: no pane")

        call("set_task_lead", """{"taskId":"$id","conversationId":"a-a"}""")
        assertEquals("a-a", task(id).main)
    }

    @Test
    fun `a task made by a caller with no agent has no main conversation, and one made by an unknown caller falls back to the first session`() {
        session("a")
        panes += "bare"
        assertEquals(JsonNull, payload(call("create_task", """{"title":"No agent","sessionIds":["bare"]}""", caller = "bare")).jsonObject["mainConversation"])
        val byFirst = payload(call("create_task", """{"title":"First","sessionIds":["a"]}""", caller = "not-a-pane")).jsonObject
        assertEquals("a-a", str(byFirst.getValue("mainConversation"), "conversationId"))
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
        assertEquals("true", str(listed(id), "questionWaits"))
        call("set_task_status", """{"taskId":"$id","text":"migrating"}""")
        assertFalse(task(id).questionWaits, "a later status says the question is answered")

        val cleared = payload(call("set_task_status", """{"taskId":"$id","text":"","questionWaits":true}"""))
        assertEquals(buildJsonObject { put("taskId", id); put("status", JsonNull); put("questionWaits", false); put("paused", false) }, cleared)
        assertEquals(listOf(null, null, false), task(id).let { listOf(it.status, it.statusAt, it.questionWaits) }, "an empty text clears all")
    }

    @Test
    fun `a paused task keeps the time its pause began while calls repeat it, an empty text included, and a call without it ends it`() {
        session("a")
        val id = create("Access redesign", """["a"]""")
        now = 7_000
        call("set_task_status", """{"taskId":"$id","text":"Paused by the owner: laptop congested","paused":true}""")
        assertEquals(7_000L, task(id).pausedAt)
        assertEquals("true", str(listed(id), "paused"))

        now = 8_000
        call("set_task_status", """{"taskId":"$id","text":"","paused":true}""")
        assertEquals(7_000L, task(id).pausedAt, "still paused from when it began, with nothing to say")
        assertEquals(7_000L, repo.loadTasks().single { it.id == id }.pausedAt, "stored, so a restart keeps it")

        call("set_task_status", """{"taskId":"$id","text":"2 working"}""")
        assertNull(task(id).pausedAt)
        assertEquals("false", str(listed(id), "paused"))
    }

    @Test
    fun `set_status writes the conversation's own line, for the caller's pane or a named conversation, and refuses needsYou without keep`() {
        session("a"); session("b")
        for (pane in listOf("a", "b")) tracker.onHook(PaneSnapshot(pane, "leaf-$pane", "/work/$pane", null), buildJsonObject { put("hook_event_name", "UserPromptSubmit"); put("session_id", "a-$pane"); put("prompt", "go") })
        val summary = { id: String -> tracker.summaries.value.single { it.id == id } }
        assertEquals(
            buildJsonObject { put("status", "Reproducing the failure"); put("keep", false) },
            payload(call("set_status", """{"text":"Reproducing\nthe failure"}""", caller = "a")),
            "line breaks become spaces",
        )
        assertEquals(listOf("Reproducing the failure", true), summary("a-a").let { listOf(it.line, it.lineSet) })

        val kept = payload(call("set_status", """{"text":"CI on #482","keep":true,"sessionId":"b"}""", caller = "a"))
        assertEquals(buildJsonObject { put("status", "CI on #482"); put("keep", true) }, kept)
        assertEquals("Reproducing the failure", summary("a-a").line, "the kept line went to b, not to the caller")
        tracker.onHook(PaneSnapshot("b", "leaf-b", "/work/b", null), buildJsonObject { put("hook_event_name", "Stop"); put("session_id", "a-b") })
        assertEquals(listOf(Shown.WAITING, "CI on #482"), summary("a-b").let { listOf(it.shown, it.line) }, "a kept line outlives the turn")

        assertEquals(
            "'needsYou' goes with 'keep: true': a line for this turn only cannot wait for the person.",
            refusal(call("set_status", """{"text":"merge go","needsYou":true}""", caller = "a")),
        )
        assertEquals("'text' must be a string", refusal(call("set_status", """{"text":null}""", caller = "a")))
        assertEquals(
            "No open agent conversation found. Pass your own \$LUNAMUX_SESSION_ID as sessionId, or your conversation id.",
            refusal(call("set_status", """{"text":"x"}""", caller = "nobody")),
        )
    }

    @Test
    fun `a task's windows open in the tab its agents are in, an explicit tab wins, and an unknown task opens nothing`() = withTabs { open ->
        val taskTab = open("Task tab")
        val activeTab = open("Active tab")
        val id = create("Two windows")
        val before = allTabs().sumOf { it.panes.size }
        assertTrue("missing" in refusal(call("create_window", """{"taskId":"missing"}""")))
        assertTrue("missing" in refusal(call("run_command", """{"command":"true","taskId":"missing","waitForExit":false}""")))
        assertEquals(before, allTabs().sumOf { it.panes.size }, "no pane was opened")

        val first = payload(call("create_window", """{"tabId":"$taskTab","taskId":"$id"}"""))
        val pane = PaneSnapshot(str(first, "sessionId"), WindowState.leafIdOfSession(str(first, "sessionId"))!!, "/work/new", null)
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-new") })
        assertEquals(id, tracker.openRecordOf(str(first, "sessionId"))!!.taskId, "the new pane was promised the task")

        call("activate_tab", """{"tabId":"$activeTab"}""")
        assertEquals(activeTab, tabOf(payload(call("create_window", "{}"))), "without a task the active tab")
        assertEquals(taskTab, tabOf(payload(call("create_window", """{"taskId":"$id"}"""))))
        assertEquals(taskTab, tabOf(payload(call("run_command", """{"command":"true","taskId":"$id","waitForExit":false}"""))))
        assertEquals(activeTab, tabOf(payload(call("create_window", """{"taskId":"$id","tabId":"$activeTab"}"""))))
        assertEquals(activeTab, tabOf(payload(call("create_window", """{"taskId":"${create("Nothing open yet")}"}"""))), "a task with no pane has no tab")
    }

    @Test
    fun `the busiest tab holds most of a task's panes, a tie goes to the first tab in order, and panes in other tabs are ignored`() {
        assertEquals("t2", busiestTab(listOf("t2", "t1", "t2"), listOf("t1", "t2")))
        assertEquals("t1", busiestTab(listOf("t2", "t1"), listOf("t1", "t2")), "tied: the first in tab order")
        assertEquals("t1", busiestTab(listOf("x", "x", "t1"), listOf("t1", "t2")), "a tab not in the order does not count")
        assertNull(busiestTab(listOf("x"), listOf("t1")))
        assertNull(busiestTab(emptyList(), listOf("t1")))
    }

    @Test
    fun `a server with no task index says so, and the window tools then take no taskId`() {
        val bare = SettingsRepository(File(dir, "bare.db"))
        registerMcpWriteTools(bare, null)
        assertEquals("Tasks are not available on this server", refusal(call("create_window", """{"taskId":"t"}""")))
        assertEquals("Tasks are not available on this server", refusal(call("run_command", """{"command":"true","taskId":"t","waitForExit":false}""")))
        registerMcpWriteTools(repo, index)
    }

    @Test
    fun `the task tools are written, with a description that says what they do and the one that is not for the owner`() {
        val tools = rpc("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")["tools"]!!.jsonArray.associate { str(it, "name") to it.jsonObject }
        for (name in listOf("create_task", "add_to_task", "remove_from_task", "rename_task", "set_task_lead", "set_task_status", "list_tasks", "set_status")) {
            assertTrue(name in tools, name)
        }
        val list = tools.getValue("list_tasks")["description"]!!.jsonPrimitive.content
        assertTrue("startedAt" in list, "list_tasks documents startedAt")
        assertFalse("ownSession" in list, "there is no ownSession")
        assertTrue(tools.values.none { "ownSession" in it.toString() })
    }
}
