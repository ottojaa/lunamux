/**
 * The `POST /api/agent-events` endpoint Claude Code hooks report to, and the per-server-start
 * credentials that guard it.
 *
 * This file contains [AgentEvents] (the token, URL and the environment variables handed to every
 * terminal session) and [agentEventRoutes] (the route itself). The hook command that calls the
 * endpoint is installed by [ClaudeHooks] and reads the three `LUNAMUX_*` variables from the pane's
 * environment, so a Claude Code run outside Lunamux posts nothing.
 */
package se.soderbjorn.lunamux

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.auth.DeviceAuth
import java.security.MessageDigest
import java.security.SecureRandom

private val log = LoggerFactory.getLogger("AgentEventRoutes")

/** Header carrying [AgentEvents.token]. */
internal const val EVENTS_TOKEN_HEADER = "X-Lunamux-Events-Token"

/** Header carrying the id of the terminal session the hook ran in. */
internal const val EVENTS_SESSION_HEADER = "X-Lunamux-Session"

/** Credentials and addresses for the agent-events endpoint. */
object AgentEvents {
    /** Random secret that must accompany every event; regenerated on each server start. */
    val token: String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** The TLS port the server listens on; `main()` sets it before the first session is created. */
    @Volatile
    var port: Int = SERVER_TLS_PORT

    /** The environment a pane's shell gets so hooks can reach the endpoint. */
    fun env(sessionId: String): Map<String, String> = mapOf(
        "LUNAMUX_SESSION_ID" to sessionId,
        "LUNAMUX_EVENTS_URL" to "https://127.0.0.1:$port/api/agent-events",
        "LUNAMUX_EVENTS_TOKEN" to token,
    )
}

/** One thread, so events are applied in the order they were accepted. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

/** Mount `POST /api/agent-events` on this route. */
internal fun Route.agentEventRoutes(
    tracker: AgentSessionTracker,
    snapshot: (String) -> PaneSnapshot? = { livePaneSnapshot(it) },
    isLoopback: (String) -> Boolean = DeviceAuth::isLoopback,
) {
    post("/api/agent-events") {
        val remote = call.request.origin.remoteAddress
        if (!isLoopback(remote)) {
            log.info("Agent events: rejecting non-loopback caller {}", remote)
            call.respond(HttpStatusCode.Forbidden)
            return@post
        }
        val sent = call.request.header(EVENTS_TOKEN_HEADER).orEmpty()
        if (!MessageDigest.isEqual(sent.toByteArray(), AgentEvents.token.toByteArray())) {
            call.respond(HttpStatusCode.Unauthorized)
            return@post
        }
        val sessionId = call.request.header(EVENTS_SESSION_HEADER)
        val body = call.receiveText()
        call.respond(HttpStatusCode.NoContent)
        if (sessionId.isNullOrBlank()) return@post
        eventScope.launch {
            runCatching {
                val event = Json.parseToJsonElement(body) as? JsonObject ?: return@runCatching
                tracker.onHook(snapshot(sessionId), event)
            }.onFailure { log.debug("Ignoring malformed agent event", it) }
        }
    }
}
