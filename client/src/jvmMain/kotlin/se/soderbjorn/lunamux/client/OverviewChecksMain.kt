/**
 * Command line entry point that connects to a running Lunamux server as an ordinary client and
 * prints whether the overview, the footer count and the task panel keep their rules.
 *
 * Run through Gradle:
 * `./gradlew :client:overviewChecks -Purl=<server> -Ptoken=<token> [-Pwatch=<seconds>] [-Pmain=<task id>=<conversation id start>]`.
 *
 * It only reads: it never sends a command, so it can run against a copy of real data and leaves
 * the layout as it found it.

The token must be a device token the server already trusts (the one a browser or app stored when
it was approved), not the token for the tool endpoint: the server never opens the layout socket
for that one, and the checker stops after 20 seconds saying so.
 *
 * Contains [main] and its helpers.
 *
 * @see se.soderbjorn.lunamux.client.viewmodel.OverviewChecks
 */
package se.soderbjorn.lunamux.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.OverviewChecks
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import java.net.URI
import kotlin.system.exitProcess

private const val POLL_MS = 500L
/**
 * The version the checker reports. The server hides worlds, agent panes and web panes from clients
 * that report an older version, and the checks need to see all of them.
 */
private const val PROTOCOL_VERSION = "99.0.0"
private const val INITIAL_STATE_TIMEOUT_MS = 20_000L

/**
 * Connect, wait for the first full state, run the checks once, keep watching the tabs for
 * [watchSeconds], and print one PASS or FAIL line per check.
 *
 * Called by the `overviewChecks` Gradle task, which passes the options as arguments.
 *
 * @param args `url=<server>`, `token=<token>`, optionally `watch=<seconds>` and any number of
 *   `main=<task id>=<conversation id start>`.
 */
fun main(args: Array<String>) {
    val opts = args.mapNotNull { a -> a.substringBefore('=', "").takeIf { it.isNotEmpty() }?.let { it to a.substringAfter('=') } }
    fun opt(name: String) = opts.firstOrNull { it.first == name }?.second
    val url = opt("url") ?: usage("-Purl=<server, for example https://localhost:8471>")
    val token = opt("token") ?: usage("-Ptoken=<a device token the server trusts>")
    val watchSeconds = opt("watch")?.toLongOrNull() ?: 0L
    val expectedMain = opts.filter { it.first == "main" }.associate { (_, v) -> v.substringBefore('=') to v.substringAfter('=') }
    val uri = URI(url)
    val server = ServerUrl(uri.host ?: usage("-Purl needs a host"), if (uri.port > 0) uri.port else 443)
    if (server.port == 8443 && server.host in setOf("localhost", "127.0.0.1")) {
        System.err.println("note: port 8443 is the production port; the checks only read, but say so if that is not what you meant")
    }

    val code = runBlocking {
        val client = LunamuxClient(server, token, ClientIdentity(type = "overview-checks", version = PROTOCOL_VERSION))
        val socket = client.openWindowSocket()
        try {
            System.err.println("connected; waiting for the first layout")
            withTimeoutOrNull(INITIAL_STATE_TIMEOUT_MS) { socket.awaitInitialConfig() }
                ?: failToConnect("no layout arrived within ${INITIAL_STATE_TIMEOUT_MS / 1_000} s (the token must be a trusted device token, not the tool-endpoint token)")
            System.err.println("layout received")
            // The agent state follows the layout; give the first push of both a moment to land.
            delay(2_000)
            val overview = AgentsOverviewBackingViewModel(client.windowState) { }
            val panel = TaskPanelViewModel(client.windowState) { }
            overview.open()
            val tabs = OverviewChecks.TabWatch()
            val deadline = System.currentTimeMillis() + watchSeconds * 1_000
            fun runOnce(): List<OverviewChecks.Check> {
                val config = client.windowState.config.value ?: error("the server sent no layout")
                val sessions = client.windowState.agentSessions.value
                val now = System.currentTimeMillis()
                tabs.observe(config, sessions, now)
                return OverviewChecks.evaluate(
                    OverviewChecks.Snapshot(sessions, client.windowState.agentTasks.value, config, overview.state, panel.state, now, expectedMain),
                )
            }
            val first = runOnce()
            var last = first
            while (System.currentTimeMillis() < deadline) {
                delay(POLL_MS)
                last = runOnce()
            }
            // A rule that held at the start and broke later is a failure of the run: report the worst of both.
            val finalChecks = first.zip(last).map { (a, b) -> if (!a.ok) a else b }
            val checks = finalChecks.filter { it.id.startsWith("C") } + tabs.result() + finalChecks.filter { !it.id.startsWith("C") }
            print(OverviewChecks.render(checks.sortedBy { it.id }))
            println("INFO C8 a spinner never restarts when its row redraws: run :web:jsBrowserTest (WorkingGlyphRedrawTest) and the page probe (docs/tasks.md)")
            if (checks.all { it.ok }) 0 else 1
        } finally {
            socket.close()
        }
    }
    exitProcess(code)
}

private fun failToConnect(why: String): Nothing {
    System.err.println("cannot read the server: $why")
    exitProcess(3)
}

private fun usage(missing: String): Nothing {
    System.err.println("missing $missing")
    exitProcess(2)
}
