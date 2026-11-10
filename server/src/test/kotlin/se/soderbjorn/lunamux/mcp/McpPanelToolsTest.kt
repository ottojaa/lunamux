/**
 * Tests for the task panel MCP tools, driven through [McpServer.handleMessage]: that an agent's
 * JSON becomes the right blocks, that a refusal reaches the agent naming the offender, and who a
 * block is written by.
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
import se.soderbjorn.lunamux.TaskStep
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelStore
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `an agent sets a panel of every block kind and reads it back, and what it reads is accepted back`() {
        val r = set(
            """[
              {"id":"links","type":"links","title":"Links","items":[
                {"kind":"PR","label":"Fix the footer","url":"https://example.com/pull/12","at":"2026-05-01T12:00:00Z"},
                {"label":"No kind or time","url":"http://example.com/x"},
                {"kind":"  ","label":"Blank kind","url":"http://example.com/y","at":null}]},
              {"id":"tl","type":"timeline","items":[{"at":"2026-05-01T12:00:00Z","text":"started"},{"at":1700000000000,"text":"later"}]},
              {"id":"prog","type":"progress","done":2,"total":5,"label":"checks"},
              {"id":"ph","type":"progress","phase":"review"}
            ]""",
            ""","by":"release agent"""",
        )
        assertTrue(!isError(r), textOf(r))
        val stored = store.blocksOf(taskId)
        assertEquals(listOf("links", "tl", "prog", "ph"), stored.map { it.id })
        assertEquals(
            listOf(
                TaskLink("PR", "Fix the footer", "https://example.com/pull/12", 1_777_636_800_000L),
                TaskLink("link", "No kind or time", "http://example.com/x", null),
                TaskLink("link", "Blank kind", "http://example.com/y", null),
            ),
            (stored[0].content as TaskBlockContent.Links).items,
        )
        assertEquals(1_700_000_000_000L, (stored[1].content as TaskBlockContent.Timeline).items[1].atMillis)

        val read = blocksJson()
        assertEquals("release agent", read[0].jsonObject["writtenBy"]!!.jsonPrimitive.content)
        assertEquals("1970-01-01T00:00:05Z", read[0].jsonObject["writtenAt"]!!.jsonPrimitive.content)
        assertEquals("2026-05-01T12:00:00Z", read[0].jsonObject["items"]!!.jsonArray[0].jsonObject["at"]!!.jsonPrimitive.content)
        val written = read[0].toString()
        assertTrue(!isError(call("upsert_task_block", """{"taskId":"$taskId","block":$written}""")))
        assertEquals(written.substringBefore(",\"writtenBy\""), blocksJson()[0].toString().substringBefore(",\"writtenBy\""))
    }

    @Test
    fun `upsert replaces a block in place, remove drops another, and only writing needs a write token`() {
        set("""[{"id":"a","type":"progress","phase":"1"},{"id":"b","type":"progress","phase":"2"},{"id":"c","type":"progress","phase":"3"}]""")
        call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"b","type":"progress","phase":"two"}}""")
        call("remove_task_block", """{"taskId":"$taskId","id":"c"}""")
        assertEquals(listOf("a", "b"), store.blocksOf(taskId).map { it.id })
        assertEquals(TaskBlockContent.Progress(phase = "two"), store.blocksOf(taskId)[1].content)
        assertFalse(isError(call("get_task_panel", """{"taskId":"$taskId"}""", scope = McpScope.READ)))
        assertTrue(isError(call("set_task_panel", """{"taskId":"$taskId","blocks":[]}""", scope = McpScope.READ)))
    }

    private fun links(vararg items: String) = """[{"id":"l","type":"links","items":[${items.joinToString(",")}]}]"""

    /** A link item; [at] is raw JSON. */
    private fun item(url: String = "https://a.b", kind: String? = null, at: String? = null) =
        """{"label":"x","url":"$url"${kind?.let { ""","kind":"$it"""" } ?: ""}${at?.let { ""","at":$it""" } ?: ""}}"""

    private fun progress(phase: String? = null, done: Int? = null, total: Int? = null, id: String = "p") =
        """[{"id":"$id","type":"progress"${phase?.let { ""","phase":"$it"""" } ?: ""}${done?.let { ""","done":$it""" } ?: ""}${total?.let { ""","total":$it""" } ?: ""}}]"""

    @Test
    fun `every refusal names the offender and the panel keeps what it had`() {
        set(progress("old", id = "keep"))
        val refused = listOf(
            """[{"id":"d","type":"text","text":"hello"}]""" to "unknown type 'text'",
            progress("x", id = "bad id") to "'bad id'",
            progress("x", id = "") to "not allowed",
            progress("x", id = "x".repeat(65)) to "not allowed",
            progress("x", id = "Agents") to "reserved",
        )
        for ((blocks, why) in refused) assertTrue(why in refusal(set(blocks)), blocks)

        val duplicate = """[{"id":"a","type":"progress","phase":"x"},{"id":"a","type":"progress","phase":"y"}]"""
        assertTrue("'a'" in refusal(set(duplicate)))
        assertTrue("24" in refusal(set((1..25).joinToString(",", "[", "]") { """{"id":"b$it","type":"progress","phase":"x"}""" })))
        assertTrue("title" in refusal(set("""[{"id":"t","title":"${"x".repeat(81)}","type":"progress","phase":"x"}]""")))

        assertTrue("needs" in refusal(set(progress())))
        assertTrue("both" in refusal(set(progress(done = 1))))
        assertTrue("between" in refusal(set(progress(done = 6, total = 5))))
        assertTrue("phase" in refusal(set(progress("p".repeat(201)))))

        for (url in listOf("javascript:alert(1)", "data:text/html,x", "ftp://a.b", "//a.b", "a.b", "https://a b")) {
            assertTrue("'x'" in refusal(set(links(item(url)))), url)
        }
        assertTrue("link 1 needs a text 'label'" in refusal(set(links("""{"url":"https://a.b"}"""))))
        assertTrue("address" in refusal(set(links(item("https://a.b/" + "a".repeat(2000))))))
        assertTrue("31" in refusal(set(links(*Array(31) { item() }))))
        assertTrue("kind" in refusal(set(links(item(kind = "k".repeat(9))))))
        assertTrue("negative" in refusal(set(links(item(at = "-1")))))
        assertTrue("'at'" in refusal(set(links(item(at = "\"yesterday\"")))))
        assertTrue("Block 'tl'" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":"yesterday","text":"x"}]}]""")))
        assertTrue("entry 1" in refusal(set("""[{"id":"tl","type":"timeline","items":[{"at":1,"text":"${"x".repeat(201)}"}]}]""")))

        assertTrue("Unknown task 'ghost'" in refusal(call("get_task_panel", """{"taskId":"ghost"}""", scope = McpScope.READ)))
        assertTrue("'nope'" in refusal(call("remove_task_block", """{"taskId":"$taskId","id":"nope"}""")))
        assertEquals(listOf("keep"), store.blocksOf(taskId).map { it.id })
    }

    @Test
    fun `what the limits allow is kept, with safe addresses, a kind of any word, a full panel still replaced, the newest 50 timeline entries`() {
        for (url in listOf("http://a.b/c", "HTTPS://A.B", "file:///tmp/x.md")) assertFalse(isError(set(links(item(url)))), url)
        assertFalse(isError(set(links(*Array(30) { item() }))))
        assertFalse(isError(set(links(item(kind = "anything", at = "0"), item(kind = "  ")))))
        assertFalse(isError(set((1..24).joinToString(",", "[", "]") { """{"id":"b$it","type":"progress","phase":"x"}""" })))
        assertFalse(isError(call("upsert_task_block", """{"taskId":"$taskId","block":{"id":"b7","type":"progress","phase":"new"}}""")))
        assertEquals(24, store.blocksOf(taskId).size)

        val at = { i: Int -> if (i % 2 == 0) i else 1000 - i }
        set("""[{"id":"t","type":"timeline","items":[${(0 until 60).joinToString(",") { """{"at":${at(it)},"text":"e$it"}""" }}]}]""")
        val kept = (store.blocksOf(taskId).single().content as TaskBlockContent.Timeline).items.map { it.text }
        val newest = (0 until 60).sortedByDescending(at).take(50).toSet()
        assertEquals((0 until 60).filter { it in newest }.map { "e$it" }, kept, "the newest 50 by time, in the order they came")

        val markup = "Pasted from a log: <img src=x onerror=alert(1)>"
        set("""[{"id":"t","type":"timeline","items":[{"at":1,"text":"$markup"}]}]""")
        assertEquals(markup, (store.blocksOf(taskId).single().content as TaskBlockContent.Timeline).items.single().text, "markup is kept as text")
    }

    @Test
    fun `writtenBy is the name given, else the caller's title, else nothing`() {
        val pane = PaneSnapshot("boss", "leaf-boss", "/work/boss", null, customName = "Release agent", screenText = { "x" })
        tracker.onHook(pane, buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-boss") })
        val one = """[{"id":"a","type":"progress","phase":"x"}]"""
        set(one, caller = "boss")
        assertEquals("Release agent", store.blocksOf(taskId)[0].writtenBy)
        set(one, ""","by":"named"""", caller = "boss")
        assertEquals("named", store.blocksOf(taskId)[0].writtenBy)
        set(one, caller = "no-such-pane")
        assertNull(store.blocksOf(taskId)[0].writtenBy)
        assertTrue("limit is 40" in refusal(set(one, ""","by":"${"n".repeat(41)}"""")))
    }

    @Test
    fun `a panel survives a restart with order, writer, time and links, and a block of a kind the panel no longer has is dropped`() {
        set(
            """[{"id":"t","title":"Title","type":"progress","phase":"Writing"},
               {"id":"l","type":"links","items":[{"kind":"PR","label":"With time","url":"https://a.b/1","at":1700000000000},{"label":"No time","url":"file:///tmp/x.md"}]}]""",
            ""","by":"writer"""",
        )
        val old = repo.loadTaskPanelRows().first()
        repo.replaceTaskPanel(taskId, repo.loadTaskPanelRows() + old.copy(blockId = "old", position = 2, kind = "text", body = """{"kind":"text","markdown":"x"}"""))
        val reopened = TaskPanelStore(SettingsRepository(dbFile), index, clock = { now })
        assertEquals(store.blocksOf(taskId), reopened.blocksOf(taskId))
        assertEquals(listOf("t", "l"), reopened.blocksOf(taskId).map { it.id })
        assertEquals("writer", reopened.blocksOf(taskId)[0].writtenBy)
    }

    @Test
    fun `a plan is written as steps, read back the same, and refused when a step is unreadable`() {
        set("""[{"id":"plan","type":"steps","items":[{"text":"Cut the server","state":"done"},{"text":"Fix resume","state":"current"},{"text":"Hand off"}]}]""")
        assertEquals(
            TaskBlockContent.Steps(listOf(TaskStep("Cut the server", "done"), TaskStep("Fix resume", "current"), TaskStep("Hand off", "todo"))),
            store.blocksOf(taskId).single().content,
        )
        val read = blocksJson().single().jsonObject
        assertEquals("steps", read["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("done", "current", "todo"), read["items"]!!.jsonArray.map { it.jsonObject["state"]!!.jsonPrimitive.content })
        assertTrue("state" in refusal(set("""[{"id":"plan","type":"steps","items":[{"text":"x","state":"soon"}]}]""")))
        assertTrue("at least one" in refusal(set("""[{"id":"plan","type":"steps","items":[]}]""")))
    }
}
