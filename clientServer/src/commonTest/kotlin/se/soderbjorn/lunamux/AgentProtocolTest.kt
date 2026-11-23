/**
 * Tests for the agents overview's wire format: the new envelopes and commands survive a round trip
 * through [windowJson], and a value this build does not know decodes to its default instead of
 * failing the frame, so an older client keeps working when a newer server adds a state.
 *
 * @see WindowEnvelope.Agents
 * @see WindowCommand.ResumeAgent
 * @see Shown
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentProtocolTest {
    private inline fun <reified T> roundTrip(value: T): T =
        windowJson.decodeFromString(windowJson.encodeToString(value))

    /** The frame, the answers and every new command come back equal. */
    @Test
    fun `the new envelopes and commands round trip`() {
        val envelopes: List<WindowEnvelope> = listOf(
            WindowEnvelope.Agents(
                sessions = listOf(AgentSessionSummary(id = "c1", agent = "claude", shown = Shown.NEEDS_YOU, taskId = "t1")),
                tasks = listOf(AgentTask(id = "t1", title = "T", main = "c1")),
                claudeConnected = true,
            ),
            WindowEnvelope.AgentDetails(
                recordId = "c1", live = true, screen = listOf("a"), styled = listOf(listOf(ScreenRun("a", bold = true))),
                turns = listOf(AgentTurn(1, "p", 2, "r")), work = listOf(WorktreeWork("w", branch = "b", unpushed = 2)), note = "n",
            ),
            WindowEnvelope.AgentMatches("q", listOf(AgentSessionMatch(AgentSessionSummary(id = "c", agent = "codex"), "title", taskTitle = "T"))),
            WindowEnvelope.AgentError(null, "hooks failed"),
            WindowEnvelope.AgentError("c1", "gone"),
        )
        envelopes.forEach { assertEquals(it, roundTrip(it)) }
        val commands: List<WindowCommand> = listOf(
            WindowCommand.ResumeAgent(recordId = "c1", inTab = true),
            WindowCommand.ResumeAgent(taskId = "t1"),
            WindowCommand.ReadAgentDetails(recordId = "c1"),
            WindowCommand.SearchAgents("zebra"),
            WindowCommand.SetClaudeHooks(true),
            WindowCommand.LeaveShownTab("tab"),
            WindowCommand.OpenTask("t1"),
        )
        commands.forEach { assertEquals(it, roundTrip(it)) }
    }

    /** The envelope kind is the `type` the sockets already use. */
    @Test
    fun `the agents frame is tagged with its type`() {
        val text = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.Agents())
        assertEquals(true, text.contains("\"type\":\"agents\""), text)
    }

    /** A state from a newer server reads as [Shown.UNKNOWN] and the rest of the row survives. */
    @Test
    fun `an unknown shown state decodes as unknown`() {
        val row = windowJson.decodeFromString<AgentSessionSummary>("""{"id":"c1","agent":"claude","shown":"BRAND_NEW","title":"kept"}""")
        assertEquals(Shown.UNKNOWN, row.shown)
        assertEquals("kept", row.title)
        val missing = windowJson.decodeFromString<AgentSessionSummary>("""{"id":"c1","agent":"claude"}""")
        assertEquals(Shown.UNKNOWN, missing.shown)
    }

    /** A newer server's extra fields are ignored. */
    @Test
    fun `unknown fields are ignored`() {
        val e = windowJson.decodeFromString<WindowEnvelope>("""{"type":"agentError","message":"m","later":1}""")
        assertEquals(WindowEnvelope.AgentError(null, "m"), e)
    }
}
