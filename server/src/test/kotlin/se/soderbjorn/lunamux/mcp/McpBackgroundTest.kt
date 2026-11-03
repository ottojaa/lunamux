/**
 * Tests for background windows and tabs in the MCP tools: `create_tab`, `create_window` and
 * `run_command` with `background`, the refusals for a tab of the wrong kind, that a background tab
 * is never activated by any tool and is reported by `list_layout`, that a task's windows never open
 * in a background tab, and that `close_tab` closes one at once.
 *
 * Driven through [McpServer.handleMessage] against the real [WindowState], like the other MCP tests.
 *
 * @see registerMcpWriteTools
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunamux.AgentSessionTracker
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.GitInfo
import se.soderbjorn.lunamux.TaskFile
import se.soderbjorn.lunamux.TaskIndex
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.listedTabs
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpBackgroundTest {
    private val dir = File.createTempFile("lunamux-mcp-background", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val tracker = AgentSessionTracker(repo, gitInfo = { GitInfo() }).also { it.start() }
    private val index = TaskIndex(repo, tracker, MutableStateFlow<List<TaskFile>>(emptyList()), leafOfSession = { WindowState.leafIdOfSession(it) })

    init {
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo, index)
        registerMcpTaskTools(index)
    }

    private fun call(tool: String, argsJson: String = "{}"): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            McpScope.READ_WRITE, null,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    private fun payloadOf(r: JsonObject) = mcpJson.parseToJsonElement(textOf(r)).jsonObject
    private fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.content == "true"
    private fun str(o: JsonObject, k: String) = o[k]!!.jsonPrimitive.content
    private fun allTabs() = WindowState.config.value.worlds.flatMap { it.tabs }

    private fun cleanup(vararg tabIds: String) = tabIds.forEach { call("close_tab", """{"tabId":"$it"}""") }

    @Test
    fun `create_tab with background opens a tab that is never listed or activated, and list_layout reports it`() {
        val visible = str(payloadOf(call("create_tab", """{"title":"Visible"}""")), "tabId")
        val activeBefore = WindowState.config.value.activeTabId
        val created = payloadOf(call("create_tab", """{"title":"Out of sight","background":true}"""))
        val bg = str(created, "tabId")
        try {
            assertEquals("true", str(created, "background"))
            assertEquals(activeBefore, WindowState.config.value.activeTabId, "a background tab never takes the focus")
            assertTrue(allTabs().single { it.id == bg }.isBackground)
            assertFalse(WindowState.config.value.listedTabs.any { it.id == bg })

            val layout = payloadOf(call("list_layout"))["tabs"]!!.jsonArray.map { it.jsonObject }
            assertEquals("true", str(layout.single { str(it, "tabId") == bg }, "background"))
            assertEquals("false", str(layout.single { str(it, "tabId") == visible }, "background"))
        } finally {
            cleanup(bg, visible)
        }
    }

    @Test
    fun `no tool activates a background tab or a window in it`() {
        val bgCreated = payloadOf(call("create_tab", """{"title":"Hidden","background":true}"""))
        val bg = str(bgCreated, "tabId")
        try {
            val activeBefore = WindowState.config.value.activeTabId
            val tab = call("activate_tab", """{"tabId":"$bg"}""")
            assertTrue(isError(tab)); assertTrue("background" in textOf(tab))
            val window = call("activate_window", """{"windowId":"${str(bgCreated, "windowId")}"}""")
            assertTrue(isError(window)); assertTrue("background" in textOf(window))
            assertEquals(activeBefore, WindowState.config.value.activeTabId)
        } finally {
            cleanup(bg)
        }
    }

    @Test
    fun `create_window background without a tab opens its own background tab named after the window`() {
        val activeBefore = WindowState.config.value.activeTabId
        val created = payloadOf(call("create_window", """{"background":true,"title":"Reviewer"}"""))
        val tabId = str(created, "tabId")
        try {
            val tab = allTabs().single { it.id == tabId }
            assertTrue(tab.isBackground)
            assertEquals("Reviewer", tab.title)
            assertEquals(listOf(str(created, "windowId")), tab.panes.map { it.leaf.id })
            assertEquals(activeBefore, WindowState.config.value.activeTabId)
        } finally {
            cleanup(tabId)
        }
    }

    @Test
    fun `a background window joins a background tab and is refused a normal one, and the other way round`() {
        val bg = str(payloadOf(call("create_tab", """{"background":true}""")), "tabId")
        val normal = str(payloadOf(call("create_tab", """{"title":"Normal"}""")), "tabId")
        try {
            val joined = payloadOf(call("create_window", """{"background":true,"tabId":"$bg"}"""))
            assertEquals(bg, str(joined, "tabId"))
            assertEquals(2, allTabs().single { it.id == bg }.panes.size)

            val wrongForBackground = call("create_window", """{"background":true,"tabId":"$normal"}""")
            assertTrue(isError(wrongForBackground))
            assertTrue("background windows go in a background tab" in textOf(wrongForBackground))
            assertEquals(1, allTabs().single { it.id == normal }.panes.size)

            val wrongForNormal = call("create_window", """{"tabId":"$bg"}""")
            assertTrue(isError(wrongForNormal))
            assertEquals(2, allTabs().single { it.id == bg }.panes.size)
        } finally {
            cleanup(bg, normal)
        }
    }

    @Test
    fun `a task's windows open beside the task in a listed tab, and a background window leaves that rule alone`() {
        val task = str(payloadOf(call("create_task", """{"title":"Split work"}""")), "taskId")
        val normal = str(payloadOf(call("create_tab", """{"title":"Task tab"}""")), "tabId")
        val other = str(payloadOf(call("create_tab", """{"title":"Other"}""")), "tabId")
        var bgTab: String? = null
        try {
            val first = payloadOf(call("create_window", """{"tabId":"$normal","taskId":"$task"}"""))
            assertEquals(normal, str(first, "tabId"))
            // A background member with the task: its own tab, not the task's, and the task keeps it.
            val bgWindow = payloadOf(call("create_window", """{"background":true,"taskId":"$task","title":"Helper"}"""))
            bgTab = str(bgWindow, "tabId")
            assertTrue(bgTab != normal)
            assertTrue(allTabs().single { it.id == bgTab }.isBackground)
            // The next visible window of the task still goes to the task's visible tab.
            val next = payloadOf(call("create_window", """{"taskId":"$task"}"""))
            assertEquals(normal, str(next, "tabId"))
        } finally {
            cleanup(*listOfNotNull(bgTab, normal, other).toTypedArray())
        }
    }

    @Test
    fun `a task whose only pane is in a background tab never sends a visible window there`() {
        val task = str(payloadOf(call("create_task", """{"title":"Only background"}""")), "taskId")
        val bgWindow = payloadOf(call("create_window", """{"background":true,"taskId":"$task"}"""))
        val bg = str(bgWindow, "tabId")
        try {
            val visible = payloadOf(call("create_window", """{"taskId":"$task"}"""))
            val tabId = str(visible, "tabId")
            assertTrue(tabId != bg)
            assertFalse(allTabs().single { it.id == tabId }.isBackground)
            cleanup(tabId)
        } finally {
            cleanup(bg)
        }
    }

    @Test
    fun `run_command that creates a window can run it in the background`() {
        val created = payloadOf(call("run_command", """{"command":"true","background":true,"waitForExit":false}"""))
        val windowId = str(created, "windowId")
        val tabId = WindowState.tabIdOfPane(windowId)!!
        try {
            assertTrue(allTabs().single { it.id == tabId }.isBackground)
        } finally {
            cleanup(tabId)
        }
    }

    @Test
    fun `close_tab closes a background tab without a prompt`() {
        val bg = str(payloadOf(call("create_tab", """{"background":true}""")), "tabId")
        val r = call("close_tab", """{"tabId":"$bg"}""")
        assertFalse(isError(r), textOf(r))
        assertTrue(allTabs().none { it.id == bg })
    }
}
