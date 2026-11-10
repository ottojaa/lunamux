/**
 * `/window` WebSocket and `/api/ui-settings` REST routes.
 *
 * Holds the [WindowConnectionContext] (per-socket reply channel and owned
 * git watchers), the [handleWindowCommand] dispatcher that translates
 * inbound [WindowCommand]s into [WindowState] mutations and unicast
 * envelopes, and the small set of envelope-building helpers used by the
 * dispatcher.
 *
 * Auth helpers — [readAuthToken] and [readClientInfo] — live here so both
 * the `/pty/{id}` and `/window` routes can share them without dragging in
 * the rest of the routing surface.
 *
 * @see WindowState
 * @see DeviceAuth
 */
package se.soderbjorn.lunamux

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.ui.SettingsDialog
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Read the device-auth token from any of three places, in order:
 *  1. `termtastic_auth` cookie
 *  2. `auth` query parameter
 *  3. `X-Termtastic-Auth` header
 */
internal fun ApplicationCall.readAuthToken(): String? {
    val cookie = request.cookies["termtastic_auth"]
    if (!cookie.isNullOrBlank()) return cookie
    val query = request.queryParameters["auth"]
    if (!query.isNullOrBlank()) return query
    val header = request.header("X-Termtastic-Auth")
    if (!header.isNullOrBlank()) return header
    return null
}

/**
 * Read the one-time QR pairing token, if the client sent one, from either:
 *  1. `pairToken` query parameter (WebSocket upgrades can't set headers)
 *  2. `X-Termtastic-Pair-Token` header (REST requests)
 *
 * Threaded into [DeviceAuth.checkFastPath]/[DeviceAuth.authorize] by the
 * `/window` and `/pty` auth call sites — a freshly-scanned device's first
 * request is always the `/window` upgrade, so those are the endpoints that
 * need it. The `/agent` and `/mcp` routes don't carry a pairing token: they
 * are only ever reached by an already-trusted device.
 *
 * @return the raw pairing token, or `null` when absent.
 * @see se.soderbjorn.termtastic.auth.PairingTokens
 */
internal fun ApplicationCall.readPairingToken(): String? {
    val query = request.queryParameters["pairToken"]
    if (!query.isNullOrBlank()) return query
    val header = request.header("X-Termtastic-Pair-Token")
    if (!header.isNullOrBlank()) return header
    return null
}

/**
 * Build a [DeviceAuth.ClientInfo] from the incoming request.
 *
 * Uses `origin.remoteAddress` (the raw socket IP), NOT `origin.remoteHost`:
 * on the Netty engine `remoteHost` reverse-DNS-resolves the client address,
 * and for LAN peers without a PTR record (e.g. a phone connecting via a
 * link-local IPv6 address) that lookup blocks ~5 s on every new connection —
 * window socket, each pty socket, each API call — which made mobile connects
 * and pane opens crawl while localhost clients stayed fast (issue #93).
 */
internal fun ApplicationCall.readClientInfo(): DeviceAuth.ClientInfo {
    fun first(headerName: String, queryName: String): String? {
        val q = request.queryParameters[queryName]
        if (!q.isNullOrBlank()) return q
        val h = request.header(headerName)
        if (!h.isNullOrBlank()) return h
        return null
    }
    val type = first("X-Termtastic-Client-Type", "clientType")?.takeIf { it.isNotBlank() }
        ?: DeviceAuth.UNKNOWN_CLIENT_TYPE
    val hostname = first("X-Termtastic-Client-Host", "clientHost")
    val selfIp = first("X-Termtastic-Client-Ip", "clientIp")
    val version = first("X-Termtastic-Client-Version", "clientVersion")
    return DeviceAuth.ClientInfo(
        type = type,
        hostname = hostname,
        selfReportedIp = selfIp,
        remoteAddress = request.origin.remoteAddress,
        version = version,
    )
}

/** Logger for this file's command handlers. */
private val log = LoggerFactory.getLogger("WindowRoutes")

/** How long an agents-overview inspection may spend on git before it is dropped. */
private const val INSPECT_TIMEOUT_MS = 8_000L

/** The (connection, record) inspections in flight, so a repeated ask for one is not run twice at once. */
private val inspecting = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<WindowConnectionContext, String>>()

/**
 * Lowest client app version whose deserializer knows the `agent` [LeafContent]
 * kind. Older clients have no such subtype registered, so decoding a config
 * that contains one throws and (via the client's whole-frame `runCatching`)
 * silently drops the entire update — freezing the client on its last layout.
 * We therefore strip agent panes from configs sent to clients below this.
 */
private val MIN_AGENT_PANE_VERSION = intArrayOf(1, 5, 0)

/**
 * Whether a client reporting [version] (a `"major.minor.patch"` string) is new
 * enough to render agent-console panes.
 *
 * Called once per `/window` connection to decide whether that socket receives
 * agent panes and agent notifications. Released clients before 1.5 never send a
 * version, so a `null`/blank/unparseable value is treated as *incapable* — the
 * absence of a version is itself the "old client" signal.
 *
 * @param version the self-reported client version, or `null`.
 * @return `true` if [version] parses to at least [MIN_AGENT_PANE_VERSION].
 * @see DeviceAuth.ClientInfo.version
 */
internal fun clientSupportsAgentPanes(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    // Split on '.', '-', '+' so suffixes like "1.5.0-beta" compare by numbers.
    val parts = version.trim().split('.', '-', '+')
    fun component(i: Int): Int =
        parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    for (i in 0 until MIN_AGENT_PANE_VERSION.size) {
        val c = component(i)
        if (c != MIN_AGENT_PANE_VERSION[i]) return c > MIN_AGENT_PANE_VERSION[i]
    }
    return true // exactly equal → supported
}

/**
 * Return a copy of this config with every agent-console pane removed, for
 * clients too old to deserialize the `agent` [LeafContent] kind.
 *
 * Panes live in a flat per-tab list (there is no split tree), so removal is a
 * clean filter — no geometry surgery. Removing the pane outright (rather than
 * substituting a placeholder) is deliberate: it keeps the old client's whole
 * config sync working, where a single unknown `"kind"` would otherwise fail the
 * entire envelope decode. Agent panes are ephemeral anyway, so an old client
 * simply never sees them.
 *
 * @return the config unchanged when it holds no agent panes; otherwise a copy
 *   with them filtered out of every tab.
 * @see clientSupportsAgentPanes
 */
private fun WindowConfig.withoutAgentPanes(): WindowConfig {
    fun strip(tabs: List<TabConfig>): List<TabConfig> = tabs.map { tab ->
        val kept = tab.panes.filterNot { it.leaf.content is AgentContent }
        if (kept.size == tab.panes.size) tab else tab.copy(panes = kept)
    }
    return copy(
        tabs = strip(tabs),
        worlds = worlds.map { it.copy(tabs = strip(it.tabs)) },
    )
}

/**
 * Lowest client app version that understands the `webBrowser` [LeafContent]
 * kind. Clients below this would drop the whole config frame on the unknown
 * `"kind"`, so web-browser panes are filtered out of configs sent to them —
 * exactly like agent panes below [MIN_AGENT_PANE_VERSION]. Gated at the same
 * 1.9 boundary as [MIN_WORLD_VERSION].
 */
private val MIN_WEB_BROWSER_VERSION = intArrayOf(1, 9, 0)

/**
 * Whether a client reporting [version] can render web-browser panes (≥ 1.9). A
 * `null`/blank/unparseable version is treated as *incapable*, mirroring
 * [clientSupportsAgentPanes] / [clientSupportsWorlds].
 *
 * @param version the self-reported client version, or `null`.
 * @return `true` if [version] parses to at least [MIN_WEB_BROWSER_VERSION].
 */
internal fun clientSupportsWebBrowser(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    val parts = version.trim().split('.', '-', '+')
    fun component(i: Int): Int =
        parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    for (i in MIN_WEB_BROWSER_VERSION.indices) {
        val c = component(i)
        if (c != MIN_WEB_BROWSER_VERSION[i]) return c > MIN_WEB_BROWSER_VERSION[i]
    }
    return true // exactly equal → supported
}

/**
 * Return a copy of this config with every web-browser pane removed, for clients
 * too old to deserialize the `webBrowser` [LeafContent] kind. Removal is a clean
 * per-tab filter across the top-level tabs and every world — mirrors
 * [withoutAgentPanes]. A web pane is Electron-only chrome anyway, so an old
 * client simply never sees it.
 *
 * @return the config unchanged when it holds no web panes; otherwise a copy with
 *   them filtered out of every tab.
 * @see clientSupportsWebBrowser
 */
private fun WindowConfig.withoutWebBrowserPanes(): WindowConfig {
    fun strip(tabs: List<TabConfig>): List<TabConfig> = tabs.map { tab ->
        val kept = tab.panes.filterNot { it.leaf.content is WebBrowserContent }
        if (kept.size == tab.panes.size) tab else tab.copy(panes = kept)
    }
    return copy(
        tabs = strip(tabs),
        worlds = worlds.map { it.copy(tabs = strip(it.tabs)) },
    )
}

/**
 * Lowest client app version that understands the `worlds` container. Clients
 * at or above this read [WindowConfig.worlds]; older clients never see the
 * field name and would drop the whole frame on the unknown structure, so
 * configs sent to them are flattened to just the default world's tabs.
 * Mirrors [MIN_AGENT_PANE_VERSION] exactly (the working precedent).
 */
private val MIN_WORLD_VERSION = intArrayOf(1, 9, 0)

/**
 * Whether a client reporting [version] is world-aware (≥ 1.9). A
 * `null`/blank/unparseable version is treated as *incapable* — the absence
 * of a version is itself the "old client" signal, exactly as with
 * [clientSupportsAgentPanes].
 *
 * @param version the self-reported client version, or `null`.
 * @return `true` if [version] parses to at least [MIN_WORLD_VERSION].
 */
internal fun clientSupportsWorlds(version: String?): Boolean {
    if (version.isNullOrBlank()) return false
    val parts = version.trim().split('.', '-', '+')
    fun component(i: Int): Int =
        parts.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
    for (i in MIN_WORLD_VERSION.indices) {
        val c = component(i)
        if (c != MIN_WORLD_VERSION[i]) return c > MIN_WORLD_VERSION[i]
    }
    return true // exactly equal → supported
}

/**
 * Flatten a world-aware config to the pre-1.9 shape: expose only the
 * **default** (first) world's tabs at top level and drop the `worlds`
 * array entirely, so an old ("world-unaware") client sees exactly what it
 * saw before worlds existed — the default world, and only the default
 * world. Applied per-connection right before send, mirroring
 * [withoutAgentPanes].
 *
 * @return the flattened config for an old client.
 */
internal fun WindowConfig.flattenToFirstWorld(): WindowConfig {
    val first = worlds.firstOrNull()
    return copy(
        // A client that predates background tabs would show them, so it never hears of them.
        tabs = (first?.tabs ?: tabs).filter { it.isListed },
        activeTabId = first?.activeTabId ?: activeTabId,
        worlds = emptyList(),
        activeWorldId = null,
    )
}

/**
 * Per-`/window`-connection state passed into [handleWindowCommand]. Holds
 * the unicast reply channel (so e.g. a `fileBrowserDir` reply lands only
 * on the socket that asked) and the connection's owned watcher handles,
 * keyed by paneId.
 */
internal class WindowConnectionContext(
    val send: suspend (WindowEnvelope) -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    val settingsRepo: SettingsRepository,
    val usageMonitor: ClaudeUsageMonitor,
    /**
     * Whether the connected client is world-aware (≥1.9). Gates world
     * lifecycle commands (old clients never send them) and steers a bare
     * [WindowCommand.AddTab] into the right world: new clients add to the
     * active world; old clients always add to the default (first) world.
     */
    val supportsWorlds: Boolean = false,
    /** The agent session record behind the agents overview; null where the overview is not wired up. */
    val agentTracker: AgentSessionTracker? = null,
    /** The Claude Code settings editor behind the App Settings Connect row; null where it is not wired up. */
    val claudeHooks: ClaudeHooks? = null,
    /** Which task each agent session belongs to, behind the overview's task rows; null where tasks are not wired up. */
    val taskIndex: TaskIndex? = null,
) {
    private val gitWatchers = ConcurrentHashMap<String, GitWatchHandle>()

    fun setGitWatcher(paneId: String, handle: GitWatchHandle?) {
        val previous = if (handle == null) gitWatchers.remove(paneId) else gitWatchers.put(paneId, handle)
        previous?.close()
    }

    fun cancelGitWatcher(paneId: String) {
        gitWatchers.remove(paneId)?.close()
    }

    fun closeAll() {
        for (handle in gitWatchers.values) handle.close()
        gitWatchers.clear()
    }
}

/**
 * Mount the `GET` and `POST` `/api/ui-settings` REST endpoints on this
 * [Route]. Both routes gate on [DeviceAuth.authorize].
 */
internal fun Route.uiSettingsRoutes(settingsRepo: SettingsRepository) {
    get("/api/ui-settings") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo, call.readPairingToken())) {
            // Merge in the synthesized legacy compat keys so pre-revamp mobile
            // apps still render approximately right; new apps read the v2 keys.
            DeviceAuth.Decision.APPROVED -> call.respond(settingsRepo.getUiSettingsWithLegacy())
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
    post("/api/ui-settings") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        when (DeviceAuth.authorize(token, info, settingsRepo, call.readPairingToken())) {
            DeviceAuth.Decision.APPROVED -> {
                val incoming = call.receive<JsonObject>()
                settingsRepo.mergeUiSettings(incoming)
                // An old client writing the global theme selection is
                // interpreted as "set the default world's theme pair" so
                // new clients viewing the default world repaint too (B6).
                if (incoming.containsKey(PersistKeys.THEME_V2_SELECTION)) {
                    settingsRepo.themePairFromSelection(incoming[PersistKeys.THEME_V2_SELECTION])?.let { pair ->
                        WindowState.defaultWorldId()?.let { WindowState.setWorldTheme(it, pair) }
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
            DeviceAuth.Decision.REJECTED,
            DeviceAuth.Decision.HEADLESS -> call.respond(HttpStatusCode.Unauthorized)
        }
    }
}

/**
 * Mount the `/window` WebSocket on this [Route]. Pushes the live
 * [WindowConfig], per-session AI state, Claude usage data, and UI
 * settings; receives [WindowCommand]s via [handleWindowCommand].
 */
internal fun Route.windowRoutes(
    settingsRepo: SettingsRepository,
    sessionStates: MutableSharedFlow<Map<String, String?>>,
    usageMonitor: ClaudeUsageMonitor,
    agentTracker: AgentSessionTracker,
    claudeHooks: ClaudeHooks,
    taskIndex: TaskIndex? = null,
    taskPanelStore: TaskPanelStore? = null,
) {
    webSocket("/window") {
        val token = call.readAuthToken()
        val info = call.readClientInfo()
        val pairToken = call.readPairingToken()
        // Two-phase auth: the fast path resolves known/denied tokens
        // without blocking. Unknown tokens require the interactive
        // dialog, so we send a PendingApproval frame first — this lets
        // the client show "Waiting for approval…" instead of timing out.
        val decision = DeviceAuth.checkFastPath(token, info, settingsRepo, pairToken)
            ?: run {
                val pending = windowJson.encodeToString<WindowEnvelope>(
                    WindowEnvelope.PendingApproval("Waiting for approval on the server…")
                )
                send(Frame.Text(pending))
                DeviceAuth.authorize(token, info, settingsRepo, pairToken)
            }
        when (decision) {
            DeviceAuth.Decision.APPROVED -> Unit
            DeviceAuth.Decision.REJECTED -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "device not approved"))
                return@webSocket
            }
            DeviceAuth.Decision.HEADLESS -> {
                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "server cannot prompt (headless)"))
                return@webSocket
            }
        }
        // Older clients can't deserialize the `agent` pane kind (added in 1.5)
        // and would drop the whole config frame on encountering one. Gate agent
        // panes and agent notifications on the self-reported client version.
        val supportsAgentPanes = clientSupportsAgentPanes(info.version)
        // Worlds (≥1.9): old clients get the default world flattened to the
        // legacy top-level tabs with no `worlds` array — pinned to the
        // default world in both directions (their commands resolve against
        // it too; see handleWindowCommand). New clients get the full worlds.
        val supportsWorlds = clientSupportsWorlds(info.version)
        // Web-browser panes (≥1.9): old clients can't deserialize the
        // `webBrowser` pane kind and would drop the whole config frame, so
        // strip them per-connection, exactly like agent panes.
        val supportsWebBrowser = clientSupportsWebBrowser(info.version)

        val pushJob = launch {
            WindowState.config.collect { cfg ->
                val outbound = cfg
                    .let { if (supportsWorlds) it else it.flattenToFirstWorld() }
                    .let { if (supportsAgentPanes) it else it.withoutAgentPanes() }
                    .let { if (supportsWebBrowser) it else it.withoutWebBrowserPanes() }
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.Config(outbound))
                send(Frame.Text(payload))
            }
        }

        val statePushJob = launch {
            sessionStates.collect { states ->
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.State(states))
                send(Frame.Text(payload))
            }
        }

        // Agent sessions follow the tracker, not the 3 s poll, so a hook event
        // reaches the overview straight away. The Claude connection flag rides
        // along because the settings row needs it with the same lifetime.
        claudeHooks.refresh()
        // Tasks ride in the same job, sessions first, so a client that receives a task
        // already holds the records it names. A frame is sent only when its part changed.
        // Each summary carries the task the index decided for it; the tracker only knows the
        // task an agent named explicitly.
        val agentSessionsPushJob = launch {
            var sentSessions: Pair<List<AgentSessionSummary>, Boolean>? = null
            var sentTasks: List<AgentTask>? = null
            combine(
                agentTracker.summaries,
                claudeHooks.connected,
                taskIndex?.state ?: MutableStateFlow(emptyList<AgentTask>()),
            ) { sessions, connected, tasks ->
                val withTasks = sessions.map {
                    it.copy(taskId = it.taskId?.takeIf { id -> taskIndex?.titleOf(id) != null }, inTab = it.endedAt != null && agentTracker.find(it.id)?.let { rec -> paneToResumeIn(rec, taskIndex?.openOfMain(rec)) } != null)
                }
                Triple(withTasks, connected, tasks)
            }.collect { (sessions, connected, tasks) ->
                if (sentSessions != sessions to connected) {
                    sentSessions = sessions to connected
                    send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.AgentSessions(sessions, connected))))
                }
                if (taskIndex != null && sentTasks != tasks) {
                    sentTasks = tasks
                    send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.AgentTasks(tasks))))
                }
            }
        }

        // Task panels: every task that has blocks is sent on connect, then each task whose blocks
        // changed. A task that lost its last block is sent once with an empty list so the window
        // drops it.
        val taskPanelPushJob = taskPanelStore?.let { store ->
            launch {
                var sent: Map<String, List<TaskPanelBlock>> = emptyMap()
                store.state.collect { now ->
                    for ((taskId, blocks) in now) {
                        if (sent[taskId] != blocks) {
                            send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.TaskPanel(taskId, blocks))))
                        }
                    }
                    for (taskId in sent.keys) {
                        if (taskId !in now) {
                            send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.TaskPanel(taskId, emptyList()))))
                        }
                    }
                    sent = now
                }
            }
        }

        val usagePushJob = launch {
            usageMonitor.usageData.collect { data ->
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.ClaudeUsage(data))
                send(Frame.Text(payload))
            }
        }

        // Agent notifications (MCP `notify` tool) fan out to every capable
        // client. The AgentNotify envelope kind also postdates 1.5, so skip the
        // subscription entirely for older clients rather than stream frames they
        // can't decode.
        val agentNoticePushJob = if (supportsAgentPanes) launch {
            se.soderbjorn.lunamux.mcp.McpNotices.flow.collect { env ->
                send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(env)))
            }
        } else null

        val uiSettingsPushJob = launch {
            settingsRepo.uiSettings.collect { s ->
                // Publish the v2 settings MERGED with the synthesized legacy
                // compat keys so old apps connected over the socket also get
                // the legacy shape; new apps consume the v2 keys.
                val withLegacy = settingsRepo.withLegacyCompat(s)
                val payload = windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.UiSettings(withLegacy))
                send(Frame.Text(payload))
            }
        }

        val ctx = WindowConnectionContext(
            send = { env -> send(Frame.Text(windowJson.encodeToString<WindowEnvelope>(env))) },
            scope = this,
            settingsRepo = settingsRepo,
            usageMonitor = usageMonitor,
            supportsWorlds = supportsWorlds,
            agentTracker = agentTracker,
            claudeHooks = claudeHooks,
            taskIndex = taskIndex,
        )

        try {
            for (frame in incoming) {
                if (frame is Frame.Text) handleWindowCommand(frame.readText(), ctx)
            }
        } finally {
            ctx.closeAll()
            pushJob.cancel()
            statePushJob.cancel()
            agentSessionsPushJob.cancel()
            taskPanelPushJob?.cancel()
            usagePushJob.cancel()
            agentNoticePushJob?.cancel()
            uiSettingsPushJob.cancel()
        }
    }
}

/**
 * Build a `fileBrowserDir` envelope for [paneId] by listing [dirRelPath]
 * under the leaf's cwd. `""` lists the root. Empty list when the leaf has
 * no cwd.
 */
private fun buildFileBrowserDirEnvelope(
    paneId: String,
    cwd: String?,
    dirRelPath: String,
    filter: String? = null,
    sort: FileBrowserSort = FileBrowserSort.NAME,
): WindowEnvelope.FileBrowserDir {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else FileBrowserCatalog.listDir(java.nio.file.Paths.get(cwd), dirRelPath, filter, sort)
    return WindowEnvelope.FileBrowserDir(paneId = paneId, dirRelPath = dirRelPath, entries = entries)
}

/** Build a `fileBrowserError` envelope for [paneId]. */
private fun buildFileBrowserErrorEnvelope(paneId: String, message: String): WindowEnvelope.FileBrowserError =
    WindowEnvelope.FileBrowserError(paneId = paneId, message = message)

/** Build a `gitList` envelope for [paneId] from the working tree at [cwd]. */
private fun buildGitListEnvelope(paneId: String, cwd: String?): WindowEnvelope.GitList {
    val entries = if (cwd.isNullOrBlank()) emptyList()
    else GitCatalog.listChanges(java.nio.file.Paths.get(cwd)) ?: emptyList()
    return WindowEnvelope.GitList(paneId = paneId, entries = entries)
}

/**
 * Dispatch a JSON command received on the `/window` WebSocket.
 *
 * Deserialises [text] as a [WindowCommand] and routes it to the
 * appropriate [WindowState] mutation or file/git catalog operation.
 * Results are sent back to the requesting client via [ctx]'s unicast
 * reply channel.
 */
internal suspend fun handleWindowCommand(text: String, ctx: WindowConnectionContext) {
    val cmd = runCatching { windowJson.decodeFromString<WindowCommand>(text) }.getOrNull() ?: return
    when (cmd) {
        is WindowCommand.FileBrowserListDir -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            ctx.send(buildFileBrowserDirEnvelope(cmd.paneId, leaf.cwd, cmd.dirRelPath, content.fileFilter, content.sortBy))
        }
        is WindowCommand.FileBrowserOpenFile -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is FileBrowserContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "No cwd for pane"))
                return
            }
            val read = FileBrowserCatalog.readFile(java.nio.file.Paths.get(cwd), cmd.relPath)
            if (read == null) {
                WindowState.setFileBrowserSelected(cmd.paneId, null)
                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Cannot read ${cmd.relPath}"))
                return
            }
            WindowState.setFileBrowserSelected(cmd.paneId, cmd.relPath)
            val envelope: WindowEnvelope = when (read) {
                is FileBrowserCatalog.FileRead.Binary -> WindowEnvelope.FileBrowserContentMsg(
                    paneId = cmd.paneId,
                    relPath = cmd.relPath,
                    kind = FileContentKind.Binary,
                    html = "",
                    language = null,
                )
                is FileBrowserCatalog.FileRead.Text -> {
                    val ext = cmd.relPath.substringAfterLast('.', "").lowercase()
                    val isHtml = ext == "html" || ext == "htm"
                    val isMarkdown = ext == "md"
                    when {
                        isHtml -> WindowEnvelope.FileBrowserContentMsg(
                            paneId = cmd.paneId,
                            relPath = cmd.relPath,
                            kind = FileContentKind.Html,
                            html = read.content,
                            language = null,
                        )
                        isMarkdown -> {
                            val html = runCatching { MarkdownRenderer.render(read.content) }.getOrElse {
                                ctx.send(buildFileBrowserErrorEnvelope(cmd.paneId, "Render failed"))
                                return
                            }
                            WindowEnvelope.FileBrowserContentMsg(
                                paneId = cmd.paneId,
                                relPath = cmd.relPath,
                                kind = FileContentKind.Markdown,
                                html = html,
                                language = null,
                            )
                        }
                        else -> {
                            val (html, lang) = SyntaxHighlighter.highlightFile(read.content, cmd.relPath)
                            WindowEnvelope.FileBrowserContentMsg(
                                paneId = cmd.paneId,
                                relPath = cmd.relPath,
                                kind = FileContentKind.Text,
                                html = html,
                                language = lang,
                            )
                        }
                    }
                }
            }
            ctx.send(envelope)
        }
        is WindowCommand.FileBrowserSetExpanded ->
            WindowState.setFileBrowserExpanded(cmd.paneId, cmd.dirRelPath, cmd.expanded)
        is WindowCommand.SetFileBrowserLeftWidth -> WindowState.setFileBrowserLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetFileBrowserFontSize -> WindowState.setFileBrowserFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetTerminalFontSize -> WindowState.setTerminalFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetTerminalAutoReflow -> WindowState.setTerminalAutoReflow(cmd.paneId, cmd.enabled)
        is WindowCommand.SetFileBrowserAutoRefresh -> {
            WindowState.setFileBrowserAutoRefresh(cmd.paneId, cmd.enabled)
        }
        is WindowCommand.SetFileBrowserFilter -> {
            val updated = WindowState.setFileBrowserFilter(cmd.paneId, cmd.filter)
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val toRefresh = (updated?.expandedDirs ?: emptySet()) + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated?.fileFilter,
                    updated?.sortBy ?: FileBrowserSort.NAME,
                ))
            }
        }
        is WindowCommand.SetFileBrowserSort -> {
            val updated = WindowState.setFileBrowserSort(cmd.paneId, cmd.sort) ?: return
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val toRefresh = updated.expandedDirs + ""
            for (dir in toRefresh) {
                ctx.send(buildFileBrowserDirEnvelope(
                    cmd.paneId, leaf.cwd, dir, updated.fileFilter, updated.sortBy,
                ))
            }
        }
        is WindowCommand.FileBrowserExpandAll -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val content = leaf.content as? FileBrowserContent ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) return
            val walk = FileBrowserCatalog.listAll(java.nio.file.Paths.get(cwd), content.fileFilter, content.sortBy)
            for ((dir, entries) in walk.listings) {
                ctx.send(WindowEnvelope.FileBrowserDir(
                    paneId = cmd.paneId, dirRelPath = dir, entries = entries,
                ))
            }
            WindowState.setFileBrowserExpandedAll(cmd.paneId, walk.expandedDirs)
        }
        is WindowCommand.FileBrowserCollapseAll -> {
            WindowState.clearFileBrowserExpanded(cmd.paneId)
        }
        is WindowCommand.GitList -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            ctx.send(buildGitListEnvelope(cmd.paneId, leaf.cwd))
        }
        is WindowCommand.GitDiff -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            if (leaf.content !is GitContent) return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "No cwd for pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val hunks = GitCatalog.readDiff(cwdPath, cmd.filePath)
            if (hunks == null) {
                WindowState.setGitSelected(cmd.paneId, null)
                ctx.send(WindowEnvelope.GitError(cmd.paneId, "Cannot read diff for ${cmd.filePath}"))
                return
            }
            val language = GitCatalog.detectLanguage(cmd.filePath)
            val highlightedHunks = hunks.map { hunk ->
                hunk.copy(lines = hunk.lines.map { line ->
                    line.copy(content = SyntaxHighlighter.highlight(line.content, language))
                })
            }
            val oldContent = GitCatalog.readGitFile(cwdPath, cmd.filePath, "HEAD")
            val newContent = GitCatalog.readWorkingFile(cwdPath, cmd.filePath)
            WindowState.setGitSelected(cmd.paneId, cmd.filePath)
            ctx.send(WindowEnvelope.GitDiffResult(
                paneId = cmd.paneId,
                filePath = cmd.filePath,
                hunks = highlightedHunks,
                language = language,
                oldContent = oldContent,
                newContent = newContent,
            ))
        }
        is WindowCommand.SetGitLeftWidth -> WindowState.setGitLeftWidth(cmd.paneId, cmd.px)
        is WindowCommand.SetGitDiffMode -> WindowState.setGitDiffMode(cmd.paneId, cmd.mode)
        is WindowCommand.SetGitGraphicalDiff -> WindowState.setGitGraphicalDiff(cmd.paneId, cmd.enabled)
        is WindowCommand.SetGitDiffFontSize -> WindowState.setGitDiffFontSize(cmd.paneId, cmd.size)
        is WindowCommand.SetGitAutoRefresh -> {
            val newState = WindowState.setGitAutoRefresh(cmd.paneId, cmd.enabled) ?: return
            if (!cmd.enabled) {
                ctx.cancelGitWatcher(cmd.paneId)
                return
            }
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd ?: return
            val handle = GitWatcher.register(
                scope = ctx.scope,
                root = java.nio.file.Paths.get(cwd),
            ) {
                val current = WindowState.findLeaf(cmd.paneId) ?: return@register
                val gitContent = current.content as? GitContent ?: return@register
                ctx.send(buildGitListEnvelope(cmd.paneId, current.cwd))
                val selPath = gitContent.selectedFilePath
                if (selPath != null && !current.cwd.isNullOrBlank()) {
                    val cwdPath = java.nio.file.Paths.get(current.cwd)
                    val diffHunks = GitCatalog.readDiff(cwdPath, selPath)
                    if (diffHunks != null) {
                        val lang = GitCatalog.detectLanguage(selPath)
                        val oldC = GitCatalog.readGitFile(cwdPath, selPath, "HEAD")
                        val newC = GitCatalog.readWorkingFile(cwdPath, selPath)
                        ctx.send(WindowEnvelope.GitDiffResult(
                            paneId = cmd.paneId,
                            filePath = selPath,
                            hunks = diffHunks,
                            language = lang,
                            oldContent = oldC,
                            newContent = newC,
                        ))
                    }
                }
            }
            ctx.setGitWatcher(cmd.paneId, handle)
        }
        is WindowCommand.AddGitToTab -> {
            val newLeaf = WindowState.addGitToTab(cmd.tabId, initialCwd = cmd.cwd)
            if (newLeaf != null) {
                ctx.send(buildGitListEnvelope(newLeaf.id, newLeaf.cwd))
            }
        }
        is WindowCommand.AddWebBrowserToTab -> {
            // Web panes stream no data: creating the leaf is enough. The
            // updated WindowConfig broadcast tells the client to mount the
            // webview (Electron) or the link button (web/mobile).
            WindowState.addWebBrowserToTab(cmd.tabId, initialUrl = cmd.url)
        }
        is WindowCommand.WebBrowserSetUrl -> {
            WindowState.setWebBrowserUrl(cmd.paneId, cmd.url, cmd.title)
        }
        is WindowCommand.Close -> {
            ctx.cancelGitWatcher(cmd.paneId)
            WindowState.closePane(cmd.paneId)
        }
        is WindowCommand.CloseSession -> {
            WindowState.closeSession(cmd.sessionId)
        }
        is WindowCommand.Rename -> WindowState.renamePane(cmd.paneId, cmd.title)
        is WindowCommand.AddTab -> {
            // New clients add to the active world; old clients are pinned to
            // the default world so their new tab never lands in a world they
            // can't see.
            val worldId = if (ctx.supportsWorlds) WindowState.activeWorldId() else WindowState.defaultWorldId()
            WindowState.addTab(worldId = worldId)
        }
        is WindowCommand.ResumeAgentSession -> resumeAgentSession(cmd.recordId, ctx, inTab = cmd.inTab)
        is WindowCommand.OpenTask -> openTask(cmd.taskId, ctx)
        is WindowCommand.SearchAgentSessions -> ctx.scope.launch {
            // Off the socket's loop: the query reads SQLite and may run git for resume notes.
            runCatching {
                val matches = withContext(Dispatchers.IO) {
                    AgentSessionSearch.search(ctx.settingsRepo, ctx.agentTracker, cmd.query, ctx.taskIndex)
                }
                ctx.send(WindowEnvelope.AgentSessionMatches(cmd.query, matches))
            }.onFailure {
                log.warn("Agent session search failed", it)
                // Answer anyway, or the overlay would show "Searching…" until the query changes.
                ctx.send(WindowEnvelope.AgentSessionMatches(cmd.query, emptyList()))
            }
        }
        is WindowCommand.InspectAgentSession -> ctx.scope.launch {
            // A live peek asks again every couple of seconds; while git is still busy on the
            // previous ask for the same row, that ask is enough.
            val key = ctx to cmd.recordId
            if (!inspecting.add(key)) return@launch
            try {
                runCatching {
                    val inspection = withTimeoutOrNull(INSPECT_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { AgentSessionSearch.inspect(cmd.recordId, ctx.agentTracker, ctx.taskIndex) }
                    }
                    if (inspection != null) ctx.send(inspection)
                }.onFailure { log.warn("Agent session inspect failed", it) }
            } finally {
                inspecting.remove(key)
            }
        }
        is WindowCommand.SetClaudeHooks -> {
            val hooks = ctx.claudeHooks ?: return
            val error = if (cmd.connected) hooks.connect() else hooks.disconnect()
            if (error != null) ctx.send(WindowEnvelope.AgentSessionError(null, error))
        }
        // A window lists a background tab only while it is shown for its agent (ShowWaitingAgent).
        // That tab is not the person's to close: it hides again by itself, and only the agent that
        // opened it closes it (the MCP close_tab tool).
        is WindowCommand.CloseTab -> if (!WindowState.isBackgroundTab(cmd.tabId)) WindowState.closeTab(cmd.tabId)
        is WindowCommand.ShowWaitingAgent -> {
            val rec = ctx.agentTracker?.find(cmd.recordId)
            val live = rec?.takeIf { it.background && it.state != "ended" }
            val leaf = live?.leafId
            if (leaf != null) WindowState.showWaitingAgent(leaf, waiting = live.state == "needs_you")
        }
        is WindowCommand.RenameTab -> WindowState.renameTab(cmd.tabId, cmd.title)
        is WindowCommand.MoveTab -> WindowState.moveTab(cmd.tabId, cmd.targetTabId, cmd.before)
        is WindowCommand.SetTabHidden -> WindowState.setTabHidden(cmd.tabId, cmd.hidden)
        is WindowCommand.SetTabHiddenFromSidebar -> WindowState.setTabHiddenFromSidebar(cmd.tabId, cmd.hidden)
        is WindowCommand.AddWorld -> {
            // World lifecycle is world-aware-only; ignore from old clients
            // (they never send these, but guard defensively).
            if (!ctx.supportsWorlds) return
            // Seed the new world's theme pair from the default world's pair,
            // falling back to the current global selection.
            val seed = WindowState.defaultWorldTheme() ?: ctx.settingsRepo.currentThemePair()
            WindowState.addWorld(cmd.name, seed)
        }
        is WindowCommand.RenameWorld -> {
            if (!ctx.supportsWorlds) return
            WindowState.renameWorld(cmd.worldId, cmd.name)
        }
        is WindowCommand.SetActiveWorld -> {
            if (!ctx.supportsWorlds) return
            WindowState.setActiveWorld(cmd.worldId)
        }
        is WindowCommand.SetWorldTheme -> {
            if (!ctx.supportsWorlds) return
            WindowState.setWorldTheme(cmd.worldId, cmd.selection)
            // If the target is the default world, mirror its pair into the
            // legacy global THEME_V2_SELECTION so pre-1.9 clients follow it
            // (they only ever see the default world). Non-default worlds
            // never touch the legacy key (B6 invariant).
            if (cmd.worldId == WindowState.defaultWorldId()) {
                ctx.settingsRepo.mirrorDefaultWorldThemePair(cmd.selection)
            }
        }
        is WindowCommand.CloseWorld -> {
            if (!ctx.supportsWorlds) return
            WindowState.closeWorld(cmd.worldId)
        }
        is WindowCommand.MoveTabToWorld -> {
            if (!ctx.supportsWorlds) return
            WindowState.moveTabToWorld(cmd.tabId, cmd.worldId)
        }
        is WindowCommand.AddPaneToTab -> {
            WindowState.addPaneToTab(cmd.tabId, initialCwd = cmd.cwd)
        }
        is WindowCommand.AddFileBrowserToTab -> {
            val newLeaf = WindowState.addFileBrowserToTab(cmd.tabId, initialCwd = cmd.cwd)
            if (newLeaf != null) {
                ctx.send(buildFileBrowserDirEnvelope(newLeaf.id, newLeaf.cwd, ""))
            }
        }
        is WindowCommand.AddLinkToTab -> WindowState.addLinkToTab(cmd.tabId, cmd.targetSessionId)
        is WindowCommand.SetPaneGeom ->
            WindowState.setPaneGeometry(cmd.paneId, cmd.x, cmd.y, cmd.width, cmd.height)
        is WindowCommand.SetPaneZoom -> WindowState.setPaneZoom(cmd.paneId, cmd.zoom)
        is WindowCommand.SetPaneGrid3d -> WindowState.setPaneGrid3d(cmd.paneId, cmd.cols, cmd.rows)
        is WindowCommand.RaisePane -> WindowState.raisePane(cmd.paneId)
        is WindowCommand.ToggleMaximized -> WindowState.toggleMaximized(cmd.paneId)
        is WindowCommand.ApplyLayout ->
            WindowState.applyLayout(cmd.tabId, cmd.layout, cmd.primaryPaneId)
        is WindowCommand.MovePaneToTab -> WindowState.movePaneToTab(cmd.paneId, cmd.targetTabId)
        is WindowCommand.SwapPanes -> WindowState.swapPanes(cmd.paneAId, cmd.paneBId)
        is WindowCommand.MovePaneWithinTab -> WindowState.movePaneWithinTab(cmd.paneId, cmd.targetPaneId, cmd.before, cmd.retile)
        is WindowCommand.SetActiveTab -> WindowState.setActiveTab(cmd.tabId)
        is WindowCommand.SetFocusedPane -> WindowState.setFocusedPane(cmd.tabId, cmd.paneId)
        is WindowCommand.SetStateOverride -> TerminalSessions.setStateOverride(cmd.sessionId, cmd.mode)
        is WindowCommand.OpenSettings -> SettingsDialog.show(ctx.settingsRepo)
        is WindowCommand.RefreshUsage -> ctx.usageMonitor.requestRefresh()
        is WindowCommand.GetWorktreeDefaults -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            val repoName = GitCatalog.getRepoNameFromRemote(cwdPath)
                ?: repoRoot.fileName?.toString()
                ?: "repo"
            val parentDir = repoRoot.parent?.toString() ?: cwd
            val siblingPath = "$parentDir/$repoName-"
            val dotWorktreesPath = "$parentDir/.worktrees/$repoName-"
            val hasChanges = GitCatalog.hasUncommittedChanges(cwdPath)
            ctx.send(WindowEnvelope.WorktreeDefaults(
                paneId = cmd.paneId,
                repoName = repoName,
                siblingPath = siblingPath,
                dotWorktreesPath = dotWorktreesPath,
                hasUncommittedChanges = hasChanges,
            ))
        }
        is WindowCommand.CreateWorktree -> {
            val leaf = WindowState.findLeaf(cmd.paneId) ?: return
            val cwd = leaf.cwd
            if (cwd.isNullOrBlank()) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "No working directory for this pane"))
                return
            }
            val cwdPath = java.nio.file.Paths.get(cwd)
            val repoRoot = GitCatalog.getRepoRoot(cwdPath)
            if (repoRoot == null) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Not inside a git repository"))
                return
            }
            if (!GitCatalog.isValidBranchName(cmd.branchName)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Invalid branch name: ${cmd.branchName}"))
                return
            }
            val rawWorktreePath = java.nio.file.Paths.get(cmd.worktreePath)
            val worktreePath = if (rawWorktreePath.isAbsolute) {
                rawWorktreePath.normalize()
            } else {
                repoRoot.resolve(rawWorktreePath).normalize()
            }
            if (java.nio.file.Files.exists(worktreePath)) {
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Path already exists: $worktreePath"))
                return
            }

            var stashed = false
            if (cmd.migrateChanges && GitCatalog.hasUncommittedChanges(repoRoot)) {
                stashed = GitCatalog.stashPush(repoRoot, "lunamux-worktree-migrate")
                if (!stashed) {
                    ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, "Failed to stash uncommitted changes"))
                    return
                }
            }

            val createError = GitCatalog.createWorktree(repoRoot, cmd.branchName, worktreePath)
            if (createError != null) {
                if (stashed) GitCatalog.stashPop(repoRoot)
                ctx.send(WindowEnvelope.WorktreeError(cmd.paneId, createError))
                return
            }

            if (stashed) {
                val popError = GitCatalog.stashPop(worktreePath)
                if (popError != null) {
                    ctx.send(WindowEnvelope.WorktreeError(
                        cmd.paneId,
                        "Worktree created but failed to migrate changes: $popError\n" +
                            "Your changes are still in the stash. Run 'git stash pop' manually."
                    ))
                    return
                }
            }

            val newCwd = worktreePath.toAbsolutePath().toString()
            val hostTabId = WindowState.tabIdOfPane(cmd.paneId)
            if (hostTabId != null) {
                WindowState.addPaneToTab(hostTabId, initialCwd = newCwd)
            }
            ctx.send(WindowEnvelope.WorktreeCreated(paneId = cmd.paneId, newCwd = newCwd))
        }
    }
}

/**
 * Whether [id] is safe to type into a shell as an argument: letters, digits, `-` and `_` only.
 * Claude's own ids are UUIDs, but the id in a record came from a hook request body, so it is
 * checked before it reaches the PTY.
 */
private fun isPlainSessionId(id: String): Boolean = id.isNotEmpty() && id.length <= 100 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/** The line that picks a conversation back up, typed into a new tab's shell. */
internal fun resumeCommandOf(rec: AgentSessionRecord): String? {
    val id = rec.agentSessionId?.takeIf(::isPlainSessionId)
    return when (rec.agent) {
        "claude" -> id?.let { "claude --resume $it" }
        "codex" -> id?.let { "codex resume $it" } ?: "codex resume"
        else -> null
    }
}

/** A task's declared `open` command made to resume conversation [id]. */
internal fun declaredResumeLine(command: String?, id: String): String? {
    val line = command?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (id in line) return line
    if (!isPlainClaudeLine(line)) return null
    val firstWordEnd = line.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
    return line.substring(0, firstWordEnd) + " --resume " + id + line.substring(firstWordEnd)
}

/** One `claude` command whose words are plain or quoted text, with nothing that chains or expands. */
private val PLAIN_CLAUDE = Regex("""^(\S*/)?claude(\s+([\w./:@=+-]+|'[^']*'|"[^"$`\\]*"))*$""")

/** A flag that already picks a conversation. */
private val PICKS_CONVERSATION = Regex("""\s(-r|-c|--continue|--resume|--session-id)(\s|=|$)""")

/** Whether [line] is a plain `claude` command that does not yet resume or continue a conversation. */
internal fun isPlainClaudeLine(line: String): Boolean = PLAIN_CLAUDE.matches(line) && !PICKS_CONVERSATION.containsMatchIn(line)

/**
 * The line to type, in the task's declared folder, to resume [rec] as the task's main conversation,
 * or null when the conversation is to be resumed the plain way.
 */
internal fun declaredResumeFor(rec: AgentSessionRecord, open: TaskOpen): String? {
    if (rec.agent != "claude") return null
    val id = rec.agentSessionId?.takeIf(::isPlainSessionId) ?: return null
    val line = declaredResumeLine(open.command, id) ?: return null
    if (line != open.command.trim() && !sameFolder(rec.cwd, open.folder)) return null
    return line
}

/**
 * Whether two folder paths name the same folder, compared as written after tidying (`.`, `..`, a
 * trailing slash). No disk access; a null or blank path is never the same as another.
 */
private fun sameFolder(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    return java.nio.file.Paths.get(a).normalize() == java.nio.file.Paths.get(b).normalize()
}

/**
 * The folder text a closed conversation is picked up from, before any walk up to a folder that
 * exists.
 */
internal fun startFolderOf(rec: AgentSessionRecord, open: TaskOpen?): String? =
    (if (open != null && declaredResumeFor(rec, open) != null) open.folder else rec.cwd)?.takeIf { it.isNotBlank() }

/** The first folder at or above [path] that exists on disk. */
internal fun nearestExistingFolder(path: String): String? {
    if (java.io.File(path).isDirectory) return path
    var dir: java.io.File? = java.io.File(path).parentFile
    while (dir != null && !dir.isDirectory) dir = dir.parentFile
    return dir?.path
}

/** What a row says when its conversation never recorded a folder; also the answer to Resume. */
private const val NO_FOLDER_RECORDED = "No folder was recorded for this session."

/** What a closed row says when its transcript file is gone; also the answer to Resume. */
private const val NO_TRANSCRIPT = "Its transcript is gone, so it can't be resumed."

/** What a row says when its folder is gone and nothing above it exists either. */
private const val NO_FOLDER_ABOVE = "Its folder is gone, and no folder above it exists."

/**
 * The one line shown under a closed row, and in its inspection, when Resume would not simply run in
 * the folder the conversation ran in.
 */
internal fun resumeNoteOf(rec: AgentSessionRecord, open: TaskOpen?): String? {
    // No note: the overview itself says there is nothing to resume, and a folder note would mislead.
    if (rec.transcriptMissing()) return null
    val recorded = startFolderOf(rec, open) ?: return NO_FOLDER_RECORDED
    if (java.io.File(recorded).isDirectory) return null
    val nearest = nearestExistingFolder(recorded) ?: return NO_FOLDER_ABOVE
    return "Its folder is gone. It resumes in $nearest."
}

/** Open a new tab for an agent session and start its agent again. */
internal suspend fun resumeAgentSession(
    recordId: String,
    ctx: WindowConnectionContext,
    inTab: Boolean = false,
    open: (folder: String, title: String, command: String?) -> Unit = ::openTabRunning,
) {
    val rec = ctx.agentTracker?.find(recordId)
    if (rec == null) {
        ctx.send(WindowEnvelope.AgentSessionError(recordId, "session no longer exists"))
        return
    }
    if (rec.transcriptMissing()) {
        ctx.send(WindowEnvelope.AgentSessionError(recordId, NO_TRANSCRIPT))
        return
    }
    val declared = ctx.taskIndex?.openOfMain(rec)
    val recorded = startFolderOf(rec, declared)
    if (recorded == null) {
        ctx.send(WindowEnvelope.AgentSessionError(recordId, NO_FOLDER_RECORDED))
        return
    }
    val folder = withContext(Dispatchers.IO) { nearestExistingFolder(recorded) }
    if (folder == null) {
        ctx.send(WindowEnvelope.AgentSessionError(recordId, NO_FOLDER_ABOVE))
        return
    }
    val command = declared?.let { declaredResumeFor(rec, it) } ?: resumeCommandOf(rec)
    if (command == null && rec.agent != "gemini") {
        ctx.send(WindowEnvelope.AgentSessionError(recordId, "This conversation has no session id Lunamux can resume."))
        return
    }
    val pane = if (inTab && command != null) paneToResumeIn(rec, declared) else null
    if (pane == null || !typeInPane(pane, command!!)) open(folder, rec.title ?: rec.branch ?: java.io.File(recorded).name, command)
}

/**
 * The id of the pane session [rec] ran in, when resuming there is safe: the pane still exists, its
 * shell is in the folder the conversation is picked up from ([startFolderOf]), nothing runs under
 * that shell, and its tab is one the person can see (not an unlisted background tab). The pane is
 * found by its layout leaf, which survives a restart. The terminal session id the record stored
 * does not (ids are handed out again at every start, so an old one can name another pane), so a
 * record with no leaf has no pane to resume in.
 */
internal fun paneToResumeIn(rec: AgentSessionRecord, open: TaskOpen? = null): String? {
    val id = rec.leafId?.let(WindowState::sessionIdOfLeaf) ?: return null
    // A pane in a background tab nobody lists cannot be shown, so typing there would run the agent out of sight.
    val tab = WindowState.config.value.worlds.asSequence().flatMap { it.tabs }
        .firstOrNull { t -> t.panes.any { it.leaf.sessionId == id } }
    if (tab != null && !tab.isListed) return null
    val session = TerminalSessions.get(id) ?: return null
    return id.takeIf { sameFolder(session.cwd.value, startFolderOf(rec, open)) && session.isIdleShell() }
}

/**
 * Show the tab that holds pane session [sessionId] (switching to its world when that is another
 * one) and type [command] into it; false when no tab holds it.
 */
internal fun typeInPane(sessionId: String, command: String): Boolean {
    val (world, tab, pane) = WindowState.config.value.worlds.asSequence()
        .flatMap { w -> w.tabs.map { w to it } }
        .firstNotNullOfOrNull { (w, t) -> t.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { Triple(w, t, it) } } ?: return false
    WindowState.setActiveWorld(world.id)
    WindowState.setActiveTab(tab.id)
    WindowState.setFocusedPane(tab.id, pane.leaf.id)
    TerminalSessions.get(sessionId)?.write((command + "\r").toByteArray(Charsets.UTF_8))
    return true
}

/** Start a task's declared `open` action in a new tab. */
internal suspend fun openTask(
    taskId: String,
    ctx: WindowConnectionContext,
    open: (folder: String, title: String, command: String?) -> Unit = ::openTabRunning,
) {
    val file = ctx.taskIndex?.fileTask(taskId)
    if (file == null) {
        ctx.send(WindowEnvelope.AgentSessionError(null, "That task no longer exists."))
        return
    }
    val action = file.open
    if (action == null) {
        ctx.send(WindowEnvelope.AgentSessionError(null, "${file.title} does not say how to open it."))
        return
    }
    val folderExists = withContext(Dispatchers.IO) { java.io.File(action.folder).isDirectory }
    if (!folderExists) {
        ctx.send(WindowEnvelope.AgentSessionError(null, "The folder of ${file.title} no longer exists: ${action.folder}"))
        return
    }
    open(action.folder, file.title, action.command)
}

/**
 * Open a new tab in the active world, starting in [folder], and type [command] into its shell the
 * way the MCP `run` tool does.
 */
internal fun openTabRunning(folder: String, title: String, command: String?) {
    val tabId = WindowState.addTab(WindowState.activeWorldId(), initialCwd = folder)
    WindowState.renameTab(tabId, title)
    val sessionId = WindowState.config.value.worlds.flatMap { it.tabs }
        .firstOrNull { it.id == tabId }?.panes?.firstOrNull()?.leaf?.sessionId
    if (command != null && sessionId != null) {
        TerminalSessions.get(sessionId)?.write((command + "\r").toByteArray(Charsets.UTF_8))
    }
}
