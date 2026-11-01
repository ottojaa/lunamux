/**
 * Tests for the `POST /api/agent-events` endpoint: the token check, the
 * non-loopback refusal, the immediate 204, and the environment handed to
 * every terminal session.
 *
 * The Ktor test host always reports a loopback peer, so the non-loopback
 * case is driven through the route's injectable peer check.
 *
 * @see agentEventRoutes
 * @see AgentEvents
 */
package se.soderbjorn.lunamux

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentEventRoutesTest {

    private val dir = File.createTempFile("lunamux-agent-events", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val tracker = AgentSessionTracker(
        SettingsRepository(File(dir, "test.db")),
        gitInfo = { GitInfo() },
    ).also { it.start() }

    private val pane = PaneSnapshot("s1", "leaf-1", "/work", null, null, null, true) { null }

    private fun app(isLoopback: (String) -> Boolean = DeviceAuth::isLoopback, body: suspend (io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application {
                routing { agentEventRoutes(tracker, snapshot = { pane }, isLoopback = isLoopback) }
            }
            body(client)
        }

    private val payload = """{"hook_event_name":"UserPromptSubmit","session_id":"a-1","prompt":"hi"}"""

    private fun awaitRows(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (tracker.summaries.value.size < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun `a missing or wrong token is rejected and nothing is recorded`() = app { client ->
        val none = client.post("/api/agent-events") { header(EVENTS_SESSION_HEADER, "s1"); setBody(payload) }
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        val wrong = client.post("/api/agent-events") {
            header(EVENTS_TOKEN_HEADER, "not-the-token")
            header(EVENTS_SESSION_HEADER, "s1")
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        Thread.sleep(100)
        assertTrue(tracker.summaries.value.isEmpty())
    }

    @Test
    fun `a non-loopback caller is rejected even with the right token`() = app(isLoopback = { false }) { client ->
        val resp = client.post("/api/agent-events") {
            header(EVENTS_TOKEN_HEADER, AgentEvents.token)
            header(EVENTS_SESSION_HEADER, "s1")
            setBody(payload)
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        Thread.sleep(100)
        assertTrue(tracker.summaries.value.isEmpty())
    }

    @Test
    fun `a valid event answers 204 and is applied to the tracker`() = app { client ->
        val resp = client.post("/api/agent-events") {
            header(EVENTS_TOKEN_HEADER, AgentEvents.token)
            header(EVENTS_SESSION_HEADER, "s1")
            setBody(payload)
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
        awaitRows(1)
        assertEquals("working", tracker.summaries.value.single().state)
    }

    @Test
    fun `malformed bodies and missing session headers are answered 204 and ignored`() = app { client ->
        val garbage = client.post("/api/agent-events") {
            header(EVENTS_TOKEN_HEADER, AgentEvents.token)
            header(EVENTS_SESSION_HEADER, "s1")
            setBody("not json")
        }
        assertEquals(HttpStatusCode.NoContent, garbage.status)
        val noSession = client.post("/api/agent-events") {
            header(EVENTS_TOKEN_HEADER, AgentEvents.token)
            setBody(payload)
        }
        assertEquals(HttpStatusCode.NoContent, noSession.status)
        Thread.sleep(100)
        assertTrue(tracker.summaries.value.isEmpty())
    }

    @Test
    fun `panes get the three LUNAMUX variables and the token is random per start`() {
        val env = AgentEvents.env("s7-abc")
        assertEquals("s7-abc", env["LUNAMUX_SESSION_ID"])
        assertEquals("https://127.0.0.1:${AgentEvents.port}/api/agent-events", env["LUNAMUX_EVENTS_URL"])
        assertEquals(AgentEvents.token, env["LUNAMUX_EVENTS_TOKEN"])
        assertEquals(64, AgentEvents.token.length)
    }

    @Test
    fun `loopback addresses are accepted and others are not`() {
        assertTrue(DeviceAuth.isLoopback("127.0.0.1"))
        assertFalse(DeviceAuth.isLoopback("192.168.0.20"))
    }
}
