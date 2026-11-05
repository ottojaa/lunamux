/**
 * Tests for the task panel MCP tools: that an agent's JSON becomes the right blocks, that every
 * refusal reaches the agent as a tool error naming the offender, who a block is written by, and
 * that a panel survives a server restart. Tools are driven through [McpServer.handleMessage]
 * against a real [TaskIndex], tracker, store and database.
 *
 * @see registerMcpPanelTools
 * @see se.soderbjorn.lunamux.TaskPanelStore
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
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
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskDocument
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.TaskPanelStore
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the four panel tools in [registerMcpPanelTools] driven through the MCP dispatcher: setting, upserting,
 * removing and reading blocks, the token scopes, refusals that name the offender, and tool wording.
 *
 * Run by `:server:test`.
 *
 * @see registerMcpPanelTools
 */
class McpPanelToolsTest {
    private val dir = File.createTempFile("lunamux-mcp-panel", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val dbFile = File(dir, "test.db")
    private val repo = SettingsRepository(dbFile)
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private var now = 5_000L
    private var nextId = 0
    private val tracker = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { WindowState.leafIdOfSession(it) }, clock = { now })
    private val store = TaskPanelStore(repo, index, clock = { now })
    private val taskId = index.createTask("Release", emptyList())

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
        registerMcpPanelTools(store, tracker)
    }

    private fun call(tool: String, argsJson: String, caller: String? = null, scope: McpScope = McpScope.READ_WRITE): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            scope, null, caller,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.content == "true"
    private fun blocksJson(): JsonArray = mcpJson.parseToJsonElement(textOf(call("get_task_panel", """{"taskId":"$taskId"}""")))
        .jsonObject["blocks"]!!.jsonArray

    private fun set(blocks: String, extra: String = "", caller: String? = null) =
        call("set_task_panel", """{"taskId":"$taskId","blocks":$blocks$extra}""", caller)

    private fun refusal(r: JsonObject): String {
        assertTrue(isError(r), "expected a refusal, got: ${textOf(r)}")
        return textOf(r)
    }

    @Test
    fun `an agent sets a panel with every block kind, then reads it back`() {
        val r = set(
            """[
              {"id":"note","type":"text","title":"Status","text":"All **green**"},
              {"id":"facts","type":"facts","items":[{"label":"Branch","value":"main"}]},
              {"id":"links","type":"links","items":[{"label":"PR","url":"https://example.com/1"}]},
              {"id":"tl","type":"timeline","items":[{"at":"2026-05-01T12:00:00Z","text":"started"},{"at":1700000000000,"text":"later"}]},
              {"id":"prog","type":"progress","done":2,"total":5,"label":"checks"},
              {"id":"ph","type":"progress","phase":"review"},
              {"id":"act","type":"actions","buttons":[{"id":"go","label":"Ship it","text":"/ship"},{"id":"plain","label":"Status"}]}
            ]""",
            ""","by":"release agent"""",
        )
        assertTrue(!isError(r), textOf(r))
        val stored = store.blocksOf(taskId)
        assertEquals(listOf("note", "facts", "links", "tl", "prog", "ph", "act"), stored.map { it.id })
        assertEquals("Status", stored[0].title)
        val tl = (stored[3].content as TaskBlockContent.Timeline).items
        assertEquals(1_777_636_800_000L, tl[0].atMillis)
        assertEquals(1_700_000_000_000L, tl[1].atMillis)
        val buttons = (stored[6].content as TaskBlockContent.Actions).buttons
        assertEquals("/ship", buttons[0].text)
        assertEquals("Status", buttons[1].text, "text defaults to the label")

        val read = blocksJson()
        assertEquals(7, read.size)
        assertEquals("release agent", read[0].jsonObject["writtenBy"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:05Z", read[0].jsonObject["writtenAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `documents are set, upserted, removed and read back with their time`() {
        val r = set(
            """[
              {"id":"docs","type":"documents","title":"Documents","items":[
                {"title":"Design page","url":"https://example.com/design","updated":"2026-05-01T12:00:00Z"},
                {"title":"Notes","url":"file:///tmp/notes.md","updated":1700000000000},
                {"title":"No time yet","url":"http://example.com/x"}
              ]},
              {"id":"keep","type":"text","text":"kept"}
            ]""",
        )
        assertTrue(!isError(r), textOf(r))
        assertEquals(
            listOf(
                TaskDocument("Design page", "https://example.com/design", 1_777_636_800_000L),
                TaskDocument("Notes", "file:///tmp/notes.md", 1_700_000_000_000L),
                TaskDocument("No time yet", "http://example.com/x", null),
            ),
            (store.blocksOf(taskId)[0].content as TaskBlockContent.Documents).items,
        )
        val read = blocksJson()[0].jsonObject
        assertEquals("documents", read["type"]!!.jsonPrimitive.content)
        val items = read["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("2026-05-01T12:00:00Z", items[0]["updated"]!!.jsonPrimitive.content)
        assertNull(items[2]["updated"], "a document with no time reads back with none")

        val written = blocksJson()[0].toString()
        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":$written}""")), "what get_task_panel returns is accepted back")
        assertEquals(listOf("docs", "keep"), store.blocksOf(taskId).map { it.id })

        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"docs","type":"documents","items":[{"title":"Only one","url":"https://example.com/1","updated":null}]}}""")))
        assertEquals(listOf("docs", "keep"), store.blocksOf(taskId).map { it.id }, "replaced in place")
        assertEquals(listOf(TaskDocument("Only one", "https://example.com/1")), (store.blocksOf(taskId)[0].content as TaskBlockContent.Documents).items)

        assertTrue(!isError(call("remove_task_block", """{"taskId":"$taskId","id":"docs"}""")))
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `a document with a javascript url is refused and the panel keeps what it had`() {
        set("""[{"id":"keep","type":"text","text":"old"}]""")
        val msg = refusal(set("""[{"id":"d","type":"documents","items":[{"title":"click me","url":"javascript:alert(1)"}]}]"""))
        assertTrue("'click me'" in msg && "http" in msg, msg)
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `a document refusal names the field at fault`() {
        assertTrue("document 1 needs a text 'title'" in refusal(set("""[{"id":"d","type":"documents","items":[{"url":"https://a.b"}]}]""")))
        assertTrue("document 1 needs a text 'url'" in refusal(set("""[{"id":"d","type":"documents","items":[{"title":"x"}]}]""")))
        assertTrue("'updated'" in refusal(set("""[{"id":"d","type":"documents","items":[{"title":"x","url":"https://a.b","updated":"yesterday"}]}]""")))
        assertTrue("'at'" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":"yesterday","text":"x"}]}]""")), "the timeline still names 'at'")
        assertTrue("31" in refusal(set("""[{"id":"d","type":"documents","items":[${List(31) { """{"title":"d","url":"https://a.b"}""" }.joinToString(",")}]}]""")))
    }

    @Test
    fun `upsert replaces one block in place and remove drops another, without touching the rest`() {
        set("""[{"id":"a","type":"text","text":"1"},{"id":"b","type":"text","text":"2"},{"id":"c","type":"text","text":"3"}]""")
        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"b","type":"text","text":"two"}}""")))
        assertTrue(!isError(call("remove_task_block", """{"taskId":"$taskId","id":"c"}""")))
        assertEquals(listOf("a", "b"), store.blocksOf(taskId).map { it.id })
        assertEquals(TaskBlockContent.Text("two"), store.blocksOf(taskId)[1].content)
    }

    @Test
    fun `a panel survives a server restart`() {
        set("""[{"id":"a","type":"text","text":"kept"}]""", ""","by":"w"""")
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(listOf("a"), reopened.blocksOf(taskId).map { it.id })
        assertEquals("w", reopened.blocksOf(taskId)[0].writtenBy)
    }

    @Test
    fun `writtenBy is the name given, else the caller's session title, else nothing`() {
        val pane = PaneSnapshot("boss", "leaf-boss", "/work/boss", null, customName = "Release agent", screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-boss") })
        assertEquals("Release agent", tracker.openRecordOf("boss")?.title)
        set("""[{"id":"a","type":"text","text":"x"}]""", caller = "boss")
        assertEquals("Release agent", store.blocksOf(taskId)[0].writtenBy)
        set("""[{"id":"a","type":"text","text":"x"}]""", ""","by":"named"""", caller = "boss")
        assertEquals("named", store.blocksOf(taskId)[0].writtenBy)
        set("""[{"id":"a","type":"text","text":"x"}]""", caller = "no-such-pane")
        assertNull(store.blocksOf(taskId)[0].writtenBy)
        assertTrue("limit is 40" in refusal(set("""[{"id":"a","type":"text","text":"x"}]""", ""","by":"${"n".repeat(41)}"""")))
    }

    @Test
    fun `a javascript url is refused and the panel keeps what it had`() {
        set("""[{"id":"keep","type":"text","text":"old"}]""")
        val msg = refusal(set("""[{"id":"l","type":"links","items":[{"label":"click me","url":"javascript:alert(1)"}]}]"""))
        assertTrue("'click me'" in msg && "http" in msg, msg)
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `refusals name the offender`() {
        assertTrue("Unknown task 'ghost'" in refusal(call("set_task_panel", """{"taskId":"ghost","blocks":[]}""")))
        assertTrue("Unknown task 'ghost'" in refusal(call("get_task_panel", """{"taskId":"ghost"}""", scope = McpScope.READ)))
        assertTrue("'agents'" in refusal(set("""[{"id":"x","type":"agents"}]""")))
        assertTrue("reserved" in refusal(set("""[{"id":"agents","type":"text","text":"x"}]""")))
        assertTrue("'sparkle'" in refusal(set("""[{"id":"x","type":"sparkle"}]""")))
        assertTrue("'bad id'" in refusal(set("""[{"id":"bad id","type":"text","text":"x"}]""")))
        assertTrue("'t'" in refusal(set("""[{"id":"t","type":"text","text":"${"x".repeat(4001)}"}]""")))
        assertTrue("Block 'tl'" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":"yesterday","text":"x"}]}]""")))
        assertTrue("'go'" in refusal(set("""[{"id":"a","type":"actions","buttons":[{"id":"go","label":"L","text":"a\nb"}]}]""")))
        assertTrue("'nope'" in refusal(call("remove_task_block", """{"taskId":"$taskId","id":"nope"}""")))
    }

    @Test
    fun `get_task_panel needs only a read token and the writing tools need a write token`() {
        set("""[{"id":"a","type":"text","text":"x"}]""")
        val read = call("get_task_panel", """{"taskId":"$taskId"}""", scope = McpScope.READ)
        assertTrue(!isError(read), textOf(read))
        val denied = call("set_task_panel", """{"taskId":"$taskId","blocks":[]}""", scope = McpScope.READ)
        assertTrue(isError(denied))
    }

    @Test
    fun `no tool description uses orchestration wording`() {
        val tools = runBlocking {
            McpServer.handleMessage("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""", McpScope.READ_WRITE, null, null)
        }.body!!
        val panel = mcpJson.parseToJsonElement(tools).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject }.filter { it["name"]!!.jsonPrimitive.content in setOf("set_task_panel", "upsert_task_block", "remove_task_block", "get_task_panel") }
        assertEquals(4, panel.size)
        for (t in panel) {
            val words = (t["description"]!!.jsonPrimitive.content + t["inputSchema"].toString()).lowercase()
            for (banned in listOf("orchestrat", "programme", "lead")) assertTrue(banned !in words, "${t["name"]} mentions $banned")
        }
    }
}
