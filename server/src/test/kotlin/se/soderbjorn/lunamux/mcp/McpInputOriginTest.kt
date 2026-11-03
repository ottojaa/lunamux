/**
 * Tests for [se.soderbjorn.lunamux.TermSession.inputOrigin]: which write paths mark a session as
 * typed into by a person and which by an MCP tool, and that only a submitted line changes it.
 *
 * Runs the real MCP write tools and the `/pty` byte path against an agent-console session, which
 * needs no PTY.
 *
 * @see se.soderbjorn.lunamux.InputOrigin
 * @see registerMcpWriteTools
 */
package se.soderbjorn.lunamux.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import se.soderbjorn.lunamux.AgentSession
import se.soderbjorn.lunamux.ClaudeUsageMonitor
import se.soderbjorn.lunamux.InputOrigin
import se.soderbjorn.lunamux.TerminalSessions
import se.soderbjorn.lunamux.acceptClientBytes
import se.soderbjorn.lunamux.typeCommandForUser
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class McpInputOriginTest {

    private fun registerTools() {
        val dir = File.createTempFile("lunamux-mcp-origin", "").apply { delete(); mkdirs(); deleteOnExit() }
        val repo = SettingsRepository(File(dir, "test.db"))
        registerMcpReadTools(repo, ClaudeUsageMonitor())
        registerMcpWriteTools(repo)
    }

    private fun newSession(): Pair<String, AgentSession> {
        val session = AgentSession(renderMode = "transcript", initialCols = 80, initialRows = 24)
        return TerminalSessions.registerAgent(session) to session
    }

    private fun call(tool: String, argsJson: String): JsonObject = runBlocking {
        val handled = McpServer.handleMessage(
            """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool","arguments":$argsJson}}""",
            McpScope.READ_WRITE,
            null,
        )
        mcpJson.parseToJsonElement(handled.body!!).jsonObject["result"]!!.jsonObject
    }

    private fun JsonObject.payload(): JsonObject =
        mcpJson.parseToJsonElement(this["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content).jsonObject

    @Test
    fun `a session nobody has typed into counts as user`() {
        assertEquals(InputOrigin.USER, newSession().second.inputOrigin)
    }

    @Test
    fun `a line typed through the pty route is user input`() {
        val (_, session) = newSession()
        session.inputOrigin = InputOrigin.TOOL
        acceptClientBytes(session, "c1", "claude\r".toByteArray())
        assertEquals(InputOrigin.USER, session.inputOrigin)
    }

    @Test
    fun `focus reports, partial typing and interrupts do not change who started a session`() {
        val (_, session) = newSession()
        session.inputOrigin = InputOrigin.TOOL
        acceptClientBytes(session, "c1", "\u001b[I".toByteArray())
        acceptClientBytes(session, "c1", "cla".toByteArray())
        acceptClientBytes(session, "c1", byteArrayOf(0x03))
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
    }

    @Test
    fun `send_text with submit is tool input and without submit is not`() {
        registerTools()
        val (id, session) = newSession()
        call("send_text", """{"sessionId":"$id","text":"claude"}""")
        assertEquals(InputOrigin.USER, session.inputOrigin, "text that submits nothing starts nothing")
        call("send_text", """{"sessionId":"$id","text":"claude","submit":true}""")
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
    }

    @Test
    fun `send_keys that press enter are tool input`() {
        registerTools()
        val (id, session) = newSession()
        call("send_keys", """{"sessionId":"$id","keys":["a","enter"]}""")
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
    }

    @Test
    fun `run_command is tool input`() {
        registerTools()
        val (id, session) = newSession()
        call("run_command", """{"sessionId":"$id","command":"claude","waitForExit":false}""")
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
    }

    @Test
    fun `the interrupt tool is tool input although it submits no line`() {
        registerTools()
        val (id, session) = newSession()
        call("interrupt", """{"sessionId":"$id"}""")
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
    }

    @Test
    fun `a window a tool creates starts as tool input and a person typing in it takes the credit`() {
        registerTools()
        val tabId = call("create_tab", """{"title":"Origin tab"}""").payload()["tabId"]!!.jsonPrimitive.content
        try {
            val sessionId = call("create_window", """{"tabId":"$tabId"}""").payload()["sessionId"]!!.jsonPrimitive.content
            val session = TerminalSessions.get(sessionId)!!
            assertEquals(InputOrigin.TOOL, session.inputOrigin)
            acceptClientBytes(session, "c1", "claude\r".toByteArray())
            assertEquals(InputOrigin.USER, session.inputOrigin)
        } finally {
            call("close_tab", """{"tabId":"$tabId"}""")
        }
    }

    @Test
    fun `a person typing after a tool takes the credit back`() {
        registerTools()
        val (id, session) = newSession()
        call("run_command", """{"sessionId":"$id","command":"ls","waitForExit":false}""")
        acceptClientBytes(session, "c1", "claude\r".toByteArray())
        assertEquals(InputOrigin.USER, session.inputOrigin)
    }

    @Test
    fun `a command typed for the person by the open-a-tab route is user input`() {
        registerTools()
        val (id, session) = newSession()
        call("run_command", """{"sessionId":"$id","command":"ls","waitForExit":false}""")
        assertEquals(InputOrigin.TOOL, session.inputOrigin)
        typeCommandForUser(session, "claude")
        assertEquals(InputOrigin.USER, session.inputOrigin)
    }
}
