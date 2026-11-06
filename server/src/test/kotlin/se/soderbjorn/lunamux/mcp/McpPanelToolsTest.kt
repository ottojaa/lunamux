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
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.TaskLink
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
              {"id":"links","type":"links","items":[{"label":"PR","url":"https://example.com/1"}]},
              {"id":"tl","type":"timeline","items":[{"at":"2026-05-01T12:00:00Z","text":"started"},{"at":1700000000000,"text":"later"}]},
              {"id":"prog","type":"progress","done":2,"total":5,"label":"checks"},
              {"id":"ph","type":"progress","phase":"review"}
            ]""",
            ""","by":"release agent"""",
        )
        assertTrue(!isError(r), textOf(r))
        val stored = store.blocksOf(taskId)
        assertEquals(listOf("links", "tl", "prog", "ph"), stored.map { it.id })
        val tl = (stored[1].content as TaskBlockContent.Timeline).items
        assertEquals(1_777_636_800_000L, tl[0].atMillis)
        assertEquals(1_700_000_000_000L, tl[1].atMillis)

        val read = blocksJson()
        assertEquals(4, read.size)
        assertEquals("release agent", read[0].jsonObject["writtenBy"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:05Z", read[0].jsonObject["writtenAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `links with a kind and a time are set, upserted, removed and read back the same`() {
        val r = set(
            """[
              {"id":"links","type":"links","title":"Links","items":[
                {"kind":"PR","label":"Fix the footer","url":"https://example.com/pull/12","at":"2026-05-01T12:00:00Z"},
                {"kind":"doc","label":"Notes","url":"file:///tmp/notes.md","at":1700000000000},
                {"label":"No kind or time","url":"http://example.com/x"},
                {"kind":"  ","label":"Blank kind","url":"http://example.com/y","at":null}
              ]},
              {"id":"keep","type":"progress","phase":"kept"}
            ]""",
        )
        assertTrue(!isError(r), textOf(r))
        assertEquals(
            listOf(
                TaskLink("PR", "Fix the footer", "https://example.com/pull/12", 1_777_636_800_000L),
                TaskLink("doc", "Notes", "file:///tmp/notes.md", 1_700_000_000_000L),
                TaskLink("link", "No kind or time", "http://example.com/x", null),
                TaskLink("link", "Blank kind", "http://example.com/y", null),
            ),
            (store.blocksOf(taskId)[0].content as TaskBlockContent.Links).items,
        )
        val read = blocksJson()[0].jsonObject
        assertEquals("links", read["type"]!!.jsonPrimitive.content)
        val items = read["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("PR", "doc", "link", "link"), items.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("2026-05-01T12:00:00Z", items[0]["at"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20Z", items[1]["at"]!!.jsonPrimitive.content)
        assertNull(items[2]["at"], "a link with no time reads back with none")

        val written = blocksJson()[0].toString()
        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":$written}""")), "what get_task_panel returns is accepted back")
        assertEquals(listOf("links", "keep"), store.blocksOf(taskId).map { it.id })
        assertEquals(blocksJson()[0].toString().substringBefore(",\"writtenBy\""), written.substringBefore(",\"writtenBy\""), "and reads back the same")

        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"links","type":"links","items":[{"kind":"art","label":"Only one","url":"https://example.com/1"}]}}""")))
        assertEquals(listOf("links", "keep"), store.blocksOf(taskId).map { it.id }, "replaced in place")
        assertEquals(listOf(TaskLink("art", "Only one", "https://example.com/1")), (store.blocksOf(taskId)[0].content as TaskBlockContent.Links).items)

        assertTrue(!isError(call("remove_task_block", """{"taskId":"$taskId","id":"links"}""")))
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `a block of a type the panel no longer has is refused as unknown and the panel keeps what it had`() {
        set("""[{"id":"keep","type":"progress","phase":"old"}]""")
        val msg = refusal(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"d","type":"text","text":"hello"}}"""))
        assertTrue("unknown type 'text'" in msg && "links, timeline, progress" in msg, msg)
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `a link with a javascript url is refused and the panel keeps what it had`() {
        set("""[{"id":"keep","type":"progress","phase":"old"}]""")
        val msg = refusal(set("""[{"id":"d","type":"links","items":[{"kind":"PR","label":"click me","url":"javascript:alert(1)"}]}]"""))
        assertTrue("'click me'" in msg && "http" in msg, msg)
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `a link refusal names the field at fault`() {
        assertTrue("link 1 needs a text 'label'" in refusal(set("""[{"id":"d","type":"links","items":[{"url":"https://a.b"}]}]""")))
        assertTrue("link 1 needs a text 'url'" in refusal(set("""[{"id":"d","type":"links","items":[{"label":"x"}]}]""")))
        assertTrue("'at'" in refusal(set("""[{"id":"d","type":"links","items":[{"label":"x","url":"https://a.b","at":"yesterday"}]}]""")))
        assertTrue("'kind'" in refusal(set("""[{"id":"d","type":"links","items":[{"kind":5,"label":"x","url":"https://a.b"}]}]""")))
        assertTrue("kind" in refusal(set("""[{"id":"d","type":"links","items":[{"kind":"a-kind-that-is-far-too-long","label":"x","url":"https://a.b"}]}]""")))
        assertTrue("'at'" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":"yesterday","text":"x"}]}]""")), "the timeline still names 'at'")
        assertTrue("31" in refusal(set("""[{"id":"d","type":"links","items":[${List(31) { """{"label":"d","url":"https://a.b"}""" }.joinToString(",")}]}]""")))
    }

    @Test
    fun `upsert replaces one block in place and remove drops another, without touching the rest`() {
        set("""[{"id":"a","type":"progress","phase":"1"},{"id":"b","type":"progress","phase":"2"},{"id":"c","type":"progress","phase":"3"}]""")
        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"b","type":"progress","phase":"two"}}""")))
        assertTrue(!isError(call("remove_task_block", """{"taskId":"$taskId","id":"c"}""")))
        assertEquals(listOf("a", "b"), store.blocksOf(taskId).map { it.id })
        assertEquals(TaskBlockContent.Progress(phase = "two"), store.blocksOf(taskId)[1].content)
    }

    @Test
    fun `a panel survives a server restart`() {
        set("""[{"id":"a","type":"progress","phase":"kept"}]""", ""","by":"w"""")
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(listOf("a"), reopened.blocksOf(taskId).map { it.id })
        assertEquals("w", reopened.blocksOf(taskId)[0].writtenBy)
    }

    @Test
    fun `writtenBy is the name given, else the caller's session title, else nothing`() {
        val pane = PaneSnapshot("boss", "leaf-boss", "/work/boss", null, customName = "Release agent", screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-boss") })
        assertEquals("Release agent", tracker.openRecordOf("boss")?.title)
        set("""[{"id":"a","type":"progress","phase":"x"}]""", caller = "boss")
        assertEquals("Release agent", store.blocksOf(taskId)[0].writtenBy)
        set("""[{"id":"a","type":"progress","phase":"x"}]""", ""","by":"named"""", caller = "boss")
        assertEquals("named", store.blocksOf(taskId)[0].writtenBy)
        set("""[{"id":"a","type":"progress","phase":"x"}]""", caller = "no-such-pane")
        assertNull(store.blocksOf(taskId)[0].writtenBy)
        assertTrue("limit is 40" in refusal(set("""[{"id":"a","type":"progress","phase":"x"}]""", ""","by":"${"n".repeat(41)}"""")))
    }

    @Test
    fun `refusals name the offender`() {
        assertTrue("Unknown task 'ghost'" in refusal(call("set_task_panel", """{"taskId":"ghost","blocks":[]}""")))
        assertTrue("Unknown task 'ghost'" in refusal(call("get_task_panel", """{"taskId":"ghost"}""", scope = McpScope.READ)))
        assertTrue("'agents'" in refusal(set("""[{"id":"x","type":"agents"}]""")))
        assertTrue("reserved" in refusal(set("""[{"id":"agents","type":"progress","phase":"x"}]""")))
        assertTrue("'sparkle'" in refusal(set("""[{"id":"x","type":"sparkle"}]""")))
        assertTrue("'bad id'" in refusal(set("""[{"id":"bad id","type":"progress","phase":"x"}]""")))
        assertTrue("Block 'tl'" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":"yesterday","text":"x"}]}]""")))
        assertTrue("'nope'" in refusal(call("remove_task_block", """{"taskId":"$taskId","id":"nope"}""")))
    }

    @Test
    fun `get_task_panel needs only a read token and the writing tools need a write token`() {
        set("""[{"id":"a","type":"progress","phase":"x"}]""")
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
