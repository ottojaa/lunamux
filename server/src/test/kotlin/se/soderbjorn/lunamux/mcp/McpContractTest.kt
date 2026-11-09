/**
 * Pins the MCP surface agents depend on: every tool's name and argument schema, and the field names
 * each task and panel tool answers with. The expected text is `src/test/resources/mcp-contract.json`,
 * captured before the task code was trimmed; the test fails when a tool, an argument or a result
 * field appears, goes or changes type. Run with `CONTRACT_UPDATE=1` to rewrite the file after
 * a change that is meant.
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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
import se.soderbjorn.lunamux.TaskPanelStore
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class McpContractTest {
    private val dir = File.createTempFile("lunamux-mcp-contract", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private var now = 5_000L
    private var nextId = 0
    private val tracker = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { WindowState.leafIdOfSession(it) }, clock = { now })
    private val store = TaskPanelStore(repo, index, clock = { now })

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
        registerMcpPanelTools(store, tracker)
        registerMcpConsoleTools()
    }

    private fun rpc(method: String, params: String, caller: String? = null): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"$method","params":$params}""", McpScope.READ_WRITE, null, caller,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun call(tool: String, args: String, caller: String? = null): JsonElement {
        val text = rpc("tools/call", """{"name":"$tool","arguments":$args}""", caller)["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
        // A tool that answers in a sentence is recorded as a string.
        return runCatching { mcpJson.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
    }

    private fun session(id: String) {
        now += 10
        tracker.onHook(
            PaneSnapshot(id, "leaf-$id", "/work/$id", null, screenText = { "x" }),
            buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$id") },
        )
    }

    /** The tool list as agents see it, minus the prose: names, argument names, types and which are required. */
    private fun toolSchemas(): JsonElement {
        fun bare(e: JsonElement): JsonElement = when (e) {
            is JsonObject -> JsonObject(e.filterKeys { it != "description" }.mapValues { bare(it.value) })
            is JsonArray -> JsonArray(e.map { bare(it) })
            else -> e
        }
        val tools = rpc("tools/list", "{}")["tools"]!!.jsonArray.filter { !it.jsonObject["name"]!!.jsonPrimitive.content.startsWith("test_") && !it.jsonObject["name"]!!.jsonPrimitive.content.startsWith("__") }.sortedBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        return JsonObject(tools.associate { it.jsonObject["name"]!!.jsonPrimitive.content to bare(it.jsonObject["inputSchema"]!!) })
    }

    /** A result reduced to its field names and value types; a list is shown by its first element. */
    private fun shape(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.toSortedMap().mapValues { shape(it.value) })
        is JsonArray -> JsonArray(e.take(1).map { shape(it) })
        is JsonNull -> JsonPrimitive("null")
        is JsonPrimitive -> JsonPrimitive(if (e.isString) "string" else if (e.content == "true" || e.content == "false") "boolean" else "number")
    }

    private fun results(): JsonElement {
        session("s1"); session("s2"); session("s3")
        val out = linkedMapOf<String, JsonElement>()
        fun record(name: String, r: JsonElement) { out[name] = shape(r) }
        val made = call("create_task", """{"title":"Release","sessionIds":["s1","s2"]}""", caller = "s1")
        record("create_task", made)
        val id = made.jsonObject["taskId"]!!.jsonPrimitive.content
        record("add_to_task", call("add_to_task", """{"taskId":"$id","sessionIds":["s3"]}"""))
        record("remove_from_task", call("remove_from_task", """{"taskId":"$id","sessionIds":["s3"]}"""))
        record("rename_task", call("rename_task", """{"taskId":"$id","title":"Release 2"}"""))
        record("set_task_lead", call("set_task_lead", """{"taskId":"$id","sessionId":"s2"}"""))
        record("set_task_status", call("set_task_status", """{"taskId":"$id","text":"halfway","questionWaits":true}"""))
        record("list_tasks", call("list_tasks", "{}"))
        record(
            "set_task_panel",
            call(
                "set_task_panel",
                """{"taskId":"$id","by":"agent","blocks":[
                  {"id":"links","type":"links","items":[{"label":"PR","url":"https://example.com/1","kind":"pr"}]},
                  {"id":"tl","type":"timeline","items":[{"at":1700000000000,"text":"started"}]},
                  {"id":"prog","type":"progress","done":2,"total":5,"phase":"review","label":"checks"}]}""",
            ),
        )
        record("upsert_task_block", call("upsert_task_block", """{"taskId":"$id","block":{"id":"extra","type":"links","items":[{"label":"Doc","url":"https://example.com/2"}]}}"""))
        record("remove_task_block", call("remove_task_block", """{"taskId":"$id","blockId":"extra"}"""))
        record("get_task_panel", call("get_task_panel", """{"taskId":"$id"}"""))
        return JsonObject(out)
    }

    @Test
    fun `the tools, their arguments and the fields they answer with are what agents were promised`() {
        val actual = Json { prettyPrint = true }.encodeToString(
            JsonElement.serializer(),
            buildJsonObject { put("tools", toolSchemas()); put("results", results()) },
        ) + "\n"
        val golden = File("src/test/resources/mcp-contract.json")
        if (System.getenv("CONTRACT_UPDATE") == "1") golden.writeText(actual)
        assertEquals(golden.readText(), actual)
    }
}
