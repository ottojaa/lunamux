/**
 * Tests for the `background` argument of the MCP write tools and the refusals that keep an agent
 * from opening a background tab on the person's screen: the exact refusal sentences of
 * `create_window`, `activate_tab` and `activate_window`, the `background` result of `create_tab`,
 * the `background` flag of `list_layout`, and `close_tab` closing a background tab.
 *
 * @see registerMcpWriteTools
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.WindowState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpBackgroundToolsTest {
    private fun register() {
        val dir = File.createTempFile("lunamux-mcp-bg", "").apply { delete(); mkdirs(); deleteOnExit() }
        val repo = SettingsRepository(File(dir, "test.db"))
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo)
    }

    private fun call(tool: String, argsJson: String = "{}"): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            McpScope.READ_WRITE,
            null,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun textOf(r: JsonObject) = r["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content

    private fun payloadOf(r: JsonObject) = mcpJson.parseToJsonElement(textOf(r)).jsonObject

    private fun isError(r: JsonObject) = r["isError"]?.jsonPrimitive?.boolean == true

    private fun assertRefused(expected: String, r: JsonObject) {
        assertTrue(isError(r), "expected a refusal, got: ${textOf(r)}")
        assertEquals(expected, textOf(r))
    }

    /** Runs [body] with an ordinary tab and a background tab, and closes both afterwards. */
    private fun withTabs(body: (ordinary: String, background: String, bgWindow: String) -> Unit) {
        register()
        val ordinary = payloadOf(call("create_tab", """{"title":"Mine"}""")).getValue("tabId").jsonPrimitive.content
        val created = payloadOf(call("create_tab", """{"title":"Out of sight","background":true}"""))
        val background = created.getValue("tabId").jsonPrimitive.content
        val window = created.getValue("windowId").jsonPrimitive.content
        try {
            body(ordinary, background, window)
        } finally {
            call("close_tab", """{"tabId":"$background"}""")
            call("close_tab", """{"tabId":"$ordinary"}""")
        }
    }

    /** `create_tab` with `background: true` reports it, leaves the active tab alone, and never switches to it. */
    @Test
    fun `create_tab background opens a tab that is not switched to`() = withTabs { ordinary, background, _ ->
        val created = payloadOf(call("create_tab", """{"background":true}""").also { })
        val extra = created.getValue("tabId").jsonPrimitive.content
        try {
            assertTrue(created.getValue("background").jsonPrimitive.boolean)
            assertTrue(WindowState.isBackgroundTab(extra))
            assertTrue(WindowState.isBackgroundTab(background))
            assertEquals(ordinary, WindowState.config.value.activeTabId)
        } finally {
            call("close_tab", """{"tabId":"$extra"}""")
        }
        val plain = payloadOf(call("create_tab", """{"title":"Plain"}"""))
        assertEquals(false, plain.getValue("background").jsonPrimitive.boolean)
        call("close_tab", """{"tabId":"${plain.getValue("tabId").jsonPrimitive.content}"}""")
    }

    /** `list_layout` says which tabs are background tabs. */
    @Test
    fun `list_layout reports background tabs`() = withTabs { ordinary, background, _ ->
        val text = textOf(call("list_layout"))
        val tabs = mcpJson.parseToJsonElement(text).jsonObject.values.flatMap {
            runCatching { it.jsonArray }.getOrDefault(emptyList())
        }.flatMap { w -> runCatching { w.jsonObject["tabs"]!!.jsonArray }.getOrDefault(emptyList()) } +
            (runCatching { mcpJson.parseToJsonElement(text).jsonObject["tabs"]!!.jsonArray }.getOrDefault(emptyList()))
        val flags = tabs.associate { it.jsonObject["tabId"]!!.jsonPrimitive.content to (it.jsonObject["background"]?.jsonPrimitive?.boolean) }
        assertEquals(true, flags[background], text)
        assertTrue(flags[ordinary] != true, text)
    }

    /** `create_window` refuses a mismatch between `background` and the tab it is given. */
    @Test
    fun `create_window refuses a mismatch with the tab`() = withTabs { ordinary, background, _ ->
        assertRefused(
            "Tab $ordinary is not a background tab: background windows go in a background tab",
            call("create_window", """{"tabId":"$ordinary","background":true}"""),
        )
        assertRefused(
            "Tab $background is a background tab: pass background: true to add a window to it",
            call("create_window", """{"tabId":"$background"}"""),
        )
        val added = call("create_window", """{"tabId":"$background","background":true}""")
        assertTrue(!isError(added), textOf(added))
        assertEquals(background, WindowState.tabIdOfPane(payloadOf(added).getValue("windowId").jsonPrimitive.content))
    }

    /** `run_command` shares the same refusals, and a background `create_window` opens its own background tab. */
    @Test
    fun `run_command refuses like create_window and a new background window opens a background tab`() = withTabs { ordinary, background, _ ->
        assertRefused(
            "Tab $ordinary is not a background tab: background windows go in a background tab",
            call("run_command", """{"command":"true","tabId":"$ordinary","background":true}"""),
        )
        assertRefused(
            "Tab $background is a background tab: pass background: true to add a window to it",
            call("run_command", """{"command":"true","tabId":"$background"}"""),
        )
        val fresh = payloadOf(call("create_window", """{"background":true,"title":"Agent"}"""))
        val windowId = fresh.getValue("windowId").jsonPrimitive.content
        val tab = WindowState.tabIdOfPane(windowId)!!
        try {
            assertTrue(WindowState.isBackgroundTab(tab))
            assertEquals(ordinary, WindowState.config.value.activeTabId)
        } finally {
            call("close_tab", """{"tabId":"$tab"}""")
        }
    }

    /** An agent cannot bring a background tab or its window to the person. */
    @Test
    fun `activate refusals use the exact sentences`() = withTabs { ordinary, background, window ->
        assertRefused(
            "Tab $background is a background tab: only the person can open it, from the agents overview",
            call("activate_tab", """{"tabId":"$background"}"""),
        )
        assertRefused(
            "Window $window is in a background tab: only the person can open it, from the agents overview",
            call("activate_window", """{"windowId":"$window"}"""),
        )
        assertEquals(ordinary, WindowState.config.value.activeTabId)
        assertNull(WindowState.config.value.shownTabId)
    }

    /** The agent that opened a background tab can close it. */
    @Test
    fun `close_tab closes a background tab`() = withTabs { _, _, _ ->
        val extra = payloadOf(call("create_tab", """{"background":true}""")).getValue("tabId").jsonPrimitive.content
        val closed = call("close_tab", """{"tabId":"$extra"}""")
        assertTrue(!isError(closed), textOf(closed))
        assertTrue(WindowState.config.value.worlds.flatMap { it.tabs }.none { it.id == extra })
    }
}
