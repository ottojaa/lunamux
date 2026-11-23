/**
 * Window, tab, and pane state management for Lunamux.
 *
 * This file contains the [WindowState] singleton, which is the authoritative
 * source of truth for the entire window layout. Every mutation flows
 * through this object so the resulting [WindowConfig] StateFlow is the
 * single stream clients subscribe to.
 *
 * `WindowState` is a thin dispatcher: it owns the [MutableStateFlow] and
 * the id counters, and delegates the actual config transformations to
 * [TabManager] and [PaneManager]. Pure formatting helpers live in
 * [PathFormatting] and the layout algorithms in [PaneLayouts].
 *
 * Mutations are synchronized; the debounced persistence writer in
 * [Application.main] picks up snapshot changes and writes them to SQLite.
 *
 * @see WindowConfig
 * @see TabManager
 * @see PaneManager
 * @see PathFormatting
 * @see PaneLayouts
 * @see TerminalSessions
 * @see SettingsRepository
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunula.core.*
import se.soderbjorn.lunula.web.layout.LayoutPreset

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.util.concurrent.atomic.AtomicLong

// The @Serializable data classes (WindowConfig, TabConfig, Pane, LeafNode,
// LeafContent + subclasses) live in the :clientServer KMP module so the web
// and android clients can deserialize the same wire types the server produces.

object WindowState {
    private val log = LoggerFactory.getLogger(WindowState::class.java)

    private val nodeIdCounter = AtomicLong(0)
    private val tabIdCounter = AtomicLong(0)
    private val worldIdCounter = AtomicLong(0)

    /**
     * Per-database nonce suffixed onto every newly minted tab / pane id
     * (`t<n>-<nonce>` / `n<n>-<nonce>`). Sequential counters alone are
     * only unique per database, and the toolkit's machine-global layout
     * blob is keyed by these ids — two databases both minting `t1`/`n1`
     * collide there and a stale entry gates the new tab's Auto re-tile
     * off (issue #86 follow-up). Set from
     * [SettingsRepository.instanceIdNonce] in [initialize]; empty until
     * then, which keeps unit tests (and any pre-init path) on the legacy
     * unsuffixed shape.
     */
    @Volatile
    private var idNonce: String = ""

    /** Renders `<base><counter>` plus the `-nonce` suffix when minted. */
    private fun mintId(base: String, counter: AtomicLong): String {
        val n = counter.incrementAndGet()
        return if (idNonce.isEmpty()) "$base$n" else "$base$n-$idNonce"
    }

    private fun newNodeId(): String = mintId("n", nodeIdCounter)
    private fun newTabId(): String = mintId("t", tabIdCounter)
    private fun newWorldId(): String = mintId("w", worldIdCounter)

    /** Default name for the first world (mirrors the toolkit's `DEFAULT_WORLD_NAME`). */
    private const val DEFAULT_WORLD_NAME = "Home"

    private val _config: MutableStateFlow<WindowConfig> = MutableStateFlow(WindowConfig())
    val config: StateFlow<WindowConfig> = _config.asStateFlow()

    // ── World plumbing ───────────────────────────────────────────────
    // `worlds` is the single source of truth. The top-level
    // `tabs`/`activeTabId` are a continuously-maintained mirror of the
    // *default* (first) world, kept only so pre-1.9 ("world-unaware")
    // clients and legacy persistence paths keep working. Every mutation
    // resolves the world that owns the referenced id, transforms a
    // per-world *view* WindowConfig through the existing stateless
    // TabManager/PaneManager, then splices the result back into that world
    // and re-syncs the legacy mirror.

    /** Every tab across every world — used by id lookups spanning worlds. */
    private fun allTabs(cfg: WindowConfig): List<TabConfig> = cfg.worlds.flatMap { it.tabs }

    /**
     * Guarantee [cfg] carries at least one world. If it is world-less
     * (only possible before [initialize], e.g. in unit tests) wrap any
     * existing top-level tabs into a default "Home" world. Returns [cfg]
     * unchanged when it already has worlds.
     */
    private fun ensureWorlds(cfg: WindowConfig): WindowConfig {
        if (cfg.worlds.isNotEmpty()) return cfg
        val worldId = newWorldId()
        val world = WorldConfig(
            id = worldId,
            name = DEFAULT_WORLD_NAME,
            tabs = cfg.tabs,
            activeTabId = cfg.activeTabId,
        )
        return syncLegacyMirror(cfg.copy(worlds = listOf(world), activeWorldId = worldId))
    }

    /** The default (first) world's id, or `null` when there are no worlds. */
    fun defaultWorldId(): String? = _config.value.worlds.firstOrNull()?.id

    /** The default (first) world's theme pair, or `null` if it follows global. */
    fun defaultWorldTheme(): WorldThemeSelection? =
        _config.value.worlds.firstOrNull()?.themeSelection

    /** The active world's id (falls back to the default world). */
    fun activeWorldId(): String? {
        val cfg = _config.value
        return cfg.activeWorldId?.takeIf { id -> cfg.worlds.any { it.id == id } }
            ?: cfg.worlds.firstOrNull()?.id
    }

    // ── Shown background tab ─────────────────────────────────────────
    // A background tab is out of sight until the person opens it from the
    // agents overview. At most one is open at a time (the "shown" tab); it
    // is the active tab of the active world. Leaving it, by any route,
    // goes through [unshow], which puts the person's own tab back.

    /**
     * The one open background tab, and where the person came from.
     *
     * @property tabId the shown background tab.
     * @property returnTabId the tab that was active in the return world, if any.
     * @property returnWorldId the world the person was in, if any.
     */
    private data class ShownTab(val tabId: String, val returnTabId: String?, val returnWorldId: String?)

    /** The open background tab, or `null`. Guarded by `synchronized(this)`; never persisted. */
    private var shown: ShownTab? = null

    /** What [show] answers. */
    sealed interface ShowResult {
        /** The tab is open (or already was). */
        data object Done : ShowResult

        /** The tab was not opened; [reason] says why in plain words. */
        data class Refused(val reason: String) : ShowResult
    }

    /** The id of the world that is active in [cfg] (the first world when none is named). */
    private fun activeWorldIdOf(cfg: WindowConfig): String? =
        cfg.activeWorldId?.takeIf { id -> cfg.worlds.any { it.id == id } } ?: cfg.worlds.firstOrNull()?.id

    /**
     * [cfg] with [open] closed: no tab is shown any more, the world that held it has an ordinary
     * active tab again (the one the person left, when it is still there), and, with [backToWorld],
     * the active world is the one the person came from.
     *
     * @param cfg the layout to change.
     * @param open the shown tab to close.
     * @param backToWorld true to also switch the active world back.
     * @return the changed layout; the legacy mirror is in step.
     */
    private fun withoutShown(cfg: WindowConfig, open: ShownTab, backToWorld: Boolean): WindowConfig {
        var out = cfg.copy(shownTabId = null)
        val world = worldOfTab(out, open.tabId)
        if (world != null && world.activeTabId == open.tabId) {
            val back = open.returnTabId?.takeIf { id ->
                open.returnWorldId == world.id && world.tabs.any { it.id == id && !it.isBackground }
            }
            val fallback = world.tabs.firstOrNull { !it.isBackground && !it.isHidden }?.id
                ?: world.tabs.firstOrNull { !it.isBackground }?.id
            out = out.copy(worlds = out.worlds.map { w ->
                if (w.id == world.id) w.copy(activeTabId = back ?: fallback) else w
            })
        }
        if (backToWorld && open.returnWorldId != null && out.worlds.any { it.id == open.returnWorldId }) {
            out = out.copy(activeWorldId = open.returnWorldId)
        }
        return syncLegacyMirror(out)
    }

    /**
     * Close the shown background tab, if there is one. Called by every route that moves the person
     * to another tab or world, by [leaveShownTab], and by [onLiveLeaves] when its agents are gone.
     *
     * @param backToWorld true to also return to the world the person came from (leave, agent ended);
     *   false when the caller is about to pick the next tab or world itself.
     */
    private fun unshow(backToWorld: Boolean) {
        val open = shown ?: return
        shown = null
        _config.value = withoutShown(_config.value, open, backToWorld)
    }

    /**
     * Open background tab [tabId] for the person: switch to its world, make it the active tab and
     * remember where they came from. Showing another tab while one is open keeps the first return point.
     *
     * @param tabId the background tab to open.
     * @param liveLeafIds the pane ids of the agents the tracker knows are open, or `null` to skip the
     *   check; with it, a tab none of whose panes is in the set is refused.
     * @return [ShowResult.Done], or [ShowResult.Refused] with the reason.
     * @see leaveShownTab
     */
    fun show(tabId: String, liveLeafIds: Set<String>? = null): ShowResult = synchronized(this) {
        val cfg0 = _config.value
        val world = worldOfTab(cfg0, tabId) ?: return@synchronized ShowResult.Refused("That tab no longer exists.")
        val tab = world.tabs.first { it.id == tabId }
        if (!tab.isBackground) return@synchronized ShowResult.Refused("That tab is not a background tab.")
        if (liveLeafIds != null && tab.panes.none { it.leaf.id in liveLeafIds }) {
            return@synchronized ShowResult.Refused("No agent is running in that tab any more.")
        }
        val previous = shown
        if (previous?.tabId == tabId) return@synchronized ShowResult.Done
        val activeWorld = activeWorldIdOf(cfg0)
        val back = previous?.let { it.returnTabId to it.returnWorldId }
            ?: (cfg0.worlds.firstOrNull { it.id == activeWorld }?.activeTabId to activeWorld)
        val cfg = if (previous != null) withoutShown(cfg0, previous, backToWorld = false) else cfg0
        shown = ShownTab(tabId, back.first, back.second)
        val worlds = cfg.worlds.map { w -> if (w.id == world.id) w.copy(activeTabId = tabId) else w }
        _config.value = syncLegacyMirror(cfg.copy(worlds = worlds, activeWorldId = world.id, shownTabId = tabId))
        ShowResult.Done
    }

    /**
     * Open the background tab that holds pane [leafId]. The tab is found in the layout itself, not
     * in anything polled, so an agent that was started a moment ago can be opened at once; an
     * unknown pane is answered with a refusal instead of being ignored.
     *
     * Called by the command handler that opens an agent from the overview.
     *
     * @param leafId the pane id a record remembers.
     * @param liveLeafIds the pane ids of the agents the tracker has open, or `null` to skip that check.
     * @return [ShowResult.Done], or [ShowResult.Refused] with the reason.
     * @see show
     */
    fun showLeaf(leafId: String, liveLeafIds: Set<String>? = null): ShowResult = synchronized(this) {
        val tab = allTabs(_config.value).firstOrNull { t -> t.panes.any { it.leaf.id == leafId } }
            ?: return@synchronized ShowResult.Refused("That agent's window no longer exists.")
        show(tab.id, liveLeafIds)
    }

    /** Close the shown background tab and return the person to where they were. No-op when none is open. */
    fun leaveShownTab() = synchronized(this) { unshow(backToWorld = true) }

    /**
     * The agents the tracker has open changed. When the shown tab no longer holds one, the person
     * is sent back, as if they had left it. Called from the collector of the tracker's summaries.
     *
     * @param liveLeafIds the pane ids of every open agent record.
     */
    fun onLiveLeaves(liveLeafIds: Set<String>) = synchronized(this) {
        val open = shown ?: return@synchronized
        val tab = allTabs(_config.value).firstOrNull { it.id == open.tabId }
        if (tab == null || tab.panes.none { it.leaf.id in liveLeafIds }) unshow(backToWorld = true)
    }

    /**
     * [cfg] as it is written to disk: no open background tab (the person's own tab is the active
     * one again), no background tabs at all, and no terminal session ids.
     *
     * @param cfg the layout to save.
     * @return the layout to persist.
     * @see rehydrate
     */
    fun forSaving(cfg: WindowConfig): WindowConfig = synchronized(this) {
        val open = shown
        val base = if (open != null) withoutShown(cfg, open, backToWorld = true) else cfg.copy(shownTabId = null)
        val worlds = base.worlds.map { w ->
            val tabs = w.tabs.filterNot { it.isBackground }
            w.copy(tabs = tabs, activeTabId = w.activeTabId?.takeIf { id -> tabs.any { it.id == id } } ?: tabs.firstOrNull()?.id)
        }
        syncLegacyMirror(base.copy(worlds = worlds)).withBlankSessionIds()
    }

    /**
     * The test for "a closed conversation can be resumed in the pane it ran in": the record has
     * ended, its pane still exists in a tab the person can see, and nothing runs under that
     * pane's shell. This is the same rule [planResume] uses to pick the pane, so the overview's
     * `inTab` and the Resume command always agree. Built from one snapshot of the layout; the tracker
     * asks for it once per publish. Only a row whose pane sits in a visible tab costs a look at
     * the terminal.
     *
     * @param probe the facts about the terminals; the default asks the live sessions.
     * @return a predicate over agent records.
     * @see reusablePane
     */
    internal fun paneToResumeIn(probe: ResumeProbe = ResumeProbe()): (AgentSessionRecord) -> Boolean {
        val cfg = _config.value
        // Several closed records often share one pane (each /clear leaves one), and the terminal
        // look walks the process table, so each pane is asked about at most once per predicate.
        val idle = HashMap<String, Boolean>()
        val once = ResumeProbe(
            folderIsDirectory = probe.folderIsDirectory,
            isIdleShell = { id -> idle.getOrPut(id) { probe.isIdleShell(id) } },
            sessionCwd = probe.sessionCwd,
        )
        return { rec -> rec.isEnded && reusablePane(rec, cfg, once) != null }
    }

    /**
     * The name of the tab that holds pane [leafId]. Called by search, which matches a conversation
     * by the name of the tab it runs in.
     *
     * @param leafId the pane id a record remembers.
     * @return the tab's title, or null when no tab holds the pane.
     */
    fun tabTitleOfLeaf(leafId: String): String? =
        allTabs(_config.value).firstOrNull { t -> t.panes.any { it.leaf.id == leafId } }?.title

    /** Whether [tabId] is a background tab (shown or not). */
    fun isBackgroundTab(tabId: String): Boolean = allTabs(_config.value).any { it.id == tabId && it.isBackground }

    /** Whether the pane [leafId] sits in a background tab. The layout is the one source of this fact. */
    fun isBackgroundLeaf(leafId: String): Boolean =
        allTabs(_config.value).any { t -> t.isBackground && t.panes.any { it.leaf.id == leafId } }

    /** Whether a pane backed by terminal session [sessionId] sits in a background tab. */
    fun isBackgroundSession(sessionId: String): Boolean =
        allTabs(_config.value).any { t -> t.isBackground && t.panes.any { it.leaf.sessionId == sessionId } }

    /** The terminal session id behind pane [leafId], or `null` when no such pane exists. */
    fun sessionIdOfLeaf(leafId: String): String? =
        allTabs(_config.value).firstNotNullOfOrNull { t -> t.panes.firstOrNull { it.leaf.id == leafId }?.leaf?.sessionId }

    /**
     * Bring pane [leafId] to the person: switch to its world and tab, focus it and raise it.
     * Refused for a pane in a background tab (only [show] opens one).
     *
     * @param leafId the pane to bring forward.
     * @return true when the pane was found in a tab the person can see.
     */
    fun focusPane(leafId: String): Boolean = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, leafId) ?: return@synchronized false
        val tab = world.tabs.first { t -> t.panes.any { it.leaf.id == leafId } }
        if (!cfg.isVisible(tab)) return@synchronized false
        setActiveWorld(world.id)
        setActiveTab(tab.id)
        setFocusedPane(tab.id, leafId)
        raisePane(leafId)
        true
    }

    /**
     * Open a new tab in [worldId] (the active world by default) starting in [folder], name it
     * [title] and type [command] into its shell. The one way resume, the task open action and
     * MCP start something in a tab.
     *
     * @param folder where the shell starts, or `null` for the default folder.
     * @param title the tab's name, or `null` to keep the default.
     * @param command the line to run, or `null` to leave the shell at its prompt.
     * @param background true to open the tab out of sight.
     * @param worldId the world to open in, or `null` for the active one.
     * @return the new tab's id, or an empty string when there was no world to open it in.
     */
    fun openTabRunning(
        folder: String?,
        title: String?,
        command: String?,
        background: Boolean = false,
        worldId: String? = null,
    ): String = synchronized(this) {
        val tabId = addTab(worldId, background = background, initialCwd = folder)
        if (tabId.isEmpty()) return@synchronized tabId
        if (title != null) renameTab(tabId, title)
        val sessionId = allTabs(_config.value).firstOrNull { it.id == tabId }?.panes?.firstOrNull()?.leaf?.sessionId
        if (command != null && sessionId != null) {
            TerminalSessions.get(sessionId)?.write((command + "\r").toByteArray(Charsets.UTF_8))
        }
        tabId
    }

    /** A single world exposed as a flat [WindowConfig] the stateless
     *  TabManager/PaneManager transforms can operate on. */
    private fun viewOf(world: WorldConfig): WindowConfig =
        WindowConfig(tabs = world.tabs, activeTabId = world.activeTabId)

    /** The world owning [tabId], or `null` if no world contains it. */
    private fun worldOfTab(cfg: WindowConfig, tabId: String): WorldConfig? =
        cfg.worlds.firstOrNull { w -> w.tabs.any { it.id == tabId } }

    /** The world owning the pane [paneId], or `null`. */
    private fun worldOfPane(cfg: WindowConfig, paneId: String): WorldConfig? =
        cfg.worlds.firstOrNull { w -> w.tabs.any { t -> t.panes.any { it.leaf.id == paneId } } }

    /** The world owning a pane backed by [sessionId], or `null`. */
    private fun worldOfSession(cfg: WindowConfig, sessionId: String): WorldConfig? =
        cfg.worlds.firstOrNull { w -> w.tabs.any { t -> t.panes.any { it.leaf.sessionId == sessionId } } }

    /**
     * Splice a transformed per-world [newView] back into [worldId] and
     * re-sync the legacy default-world mirror. Returns the new top-level
     * config (not yet published).
     */
    private fun writeBackWorld(cfg: WindowConfig, worldId: String, newView: WindowConfig): WindowConfig {
        val newWorlds = cfg.worlds.map { w ->
            if (w.id == worldId) w.copy(tabs = newView.tabs, activeTabId = newView.activeTabId) else w
        }
        return syncLegacyMirror(cfg.copy(worlds = newWorlds))
    }

    /** Keep top-level `tabs`/`activeTabId` equal to the default world's. */
    private fun syncLegacyMirror(cfg: WindowConfig): WindowConfig {
        val first = cfg.worlds.firstOrNull()
        val active = cfg.activeWorldId?.takeIf { id -> cfg.worlds.any { it.id == id } }
            ?: first?.id
        return cfg.copy(
            tabs = first?.tabs ?: emptyList(),
            activeTabId = first?.activeTabId,
            activeWorldId = active,
        )
    }

    @Volatile
    private var initialized: Boolean = false

    /**
     * Per-tab importance order of panes; head is the active pane.
     * Mutated by focus events (bubble to head), pane creation (new pane
     * at index 0, parent at index 1), and pane removal (drop the closed
     * id). Drives the Auto layout's slot assignment so freshly-created
     * panes claim primary while their parent keeps slot 1. In-memory
     * only — rebuilt from focus/create events after a restart, no
     * backwards-compat persistence required.
     */
    private val paneOrderByTab: MutableMap<String, MutableList<String>> = mutableMapOf()

    /**
     * Pane that was active when each pane was created. Recorded by
     * the four `add…ToTab` methods. Available as a future tie-break
     * heuristic for grouping siblings; not consumed by Auto today.
     */
    private val paneParent: MutableMap<String, String> = mutableMapOf()

    /**
     * Last layout key applied to each tab. `"auto"` means the server
     * re-tiles automatically on every pane add/remove/focus event.
     * Absent entries mean no preset is driving (manual placement).
     */
    private val activeLayoutByTab: MutableMap<String, String> = mutableMapOf()

    /**
     * Bubble [paneId] to the head of [paneOrderByTab]`[tabId]`. Called
     * by [setFocusedPane] and indirectly whenever a pane becomes the
     * active one. Idempotent when already at the head.
     */
    private fun bubbleFocus(tabId: String, paneId: String) {
        val order = paneOrderByTab.getOrPut(tabId) { mutableListOf() }
        val idx = order.indexOf(paneId)
        if (idx == 0) return
        if (idx > 0) order.removeAt(idx)
        order.add(0, paneId)
    }

    /**
     * Record that pane [newPaneId] was created in [tabId] from a parent
     * pane [parentPaneId]. Inserts the new pane at index 0 of the
     * importance order; if a parent is given and present, moves it to
     * index 1 so the originating pane keeps slot 1 in Auto layout.
     */
    private fun recordPaneCreated(tabId: String, newPaneId: String, parentPaneId: String?) {
        val order = paneOrderByTab.getOrPut(tabId) { mutableListOf() }
        order.remove(newPaneId)
        order.add(0, newPaneId)
        if (parentPaneId != null && parentPaneId != newPaneId) {
            paneParent[newPaneId] = parentPaneId
            val parentIdx = order.indexOf(parentPaneId)
            if (parentIdx > 1) {
                order.removeAt(parentIdx)
                order.add(1, parentPaneId)
            }
        }
    }

    /**
     * Drop [paneId] from importance order and parent linkage. Called
     * by [closePane] after the pane is removed from its tab.
     */
    private fun recordPaneRemoved(paneId: String) {
        for (order in paneOrderByTab.values) order.remove(paneId)
        paneParent.remove(paneId)
    }

    /**
     * If [tabId] has a remembered active layout, re-apply it. Used
     * after pane add/remove/focus so Auto re-tiles on demand. Other
     * preset keys also re-apply, keeping geometry consistent.
     * No-op when no layout is remembered for the tab.
     *
     * Must be called while still holding the [WindowState] monitor —
     * uses the synchronized [applyLayout] internally, which is
     * re-entrant on the same thread.
     */
    private fun maybeReapplyLayout(tabId: String) {
        val layout = activeLayoutByTab[tabId] ?: return
        val primary = paneOrderByTab[tabId]?.firstOrNull()
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return
        val newView = PaneManager.applyLayout(viewOf(world), tabId, layout, primary) ?: return
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * One-shot bootstrap: try to restore the persisted window config, otherwise
     * fall back to a fresh default. Must be called from `main()` exactly once,
     * before any other access to [config].
     */
    @Synchronized
    fun initialize(repo: SettingsRepository) {
        if (initialized) return
        initialized = true

        // Resolve the per-database id nonce BEFORE any id (or session) is
        // minted, so the very first cold-start tab/pane already carries it.
        // Existing databases mint a nonce on their first boot with this
        // code; ids persisted before that keep their legacy shape and stay
        // valid (see [idNonce]).
        idNonce = repo.instanceIdNonce()
        TerminalSessions.idNonce = idNonce

        val loaded = repo.loadWindowConfig()
        val cfg = if (loaded != null && loaded.worlds.any { it.tabs.isNotEmpty() }) {
            try {
                rehydrate(loaded, repo)
            } catch (t: Throwable) {
                log.warn("Failed to rehydrate persisted window config; using default", t)
                buildDefault()
            }
        } else {
            buildDefault()
        }
        _config.value = cfg

        val everyTab = allTabs(cfg)

        // Re-engage persisted layout presets so subsequent pane
        // add/remove/focus events fire auto re-tile (or any other
        // preset-driven layout) without the user re-picking from the
        // dropdown. Custom is treated as "no preset is driving" and
        // skipped. Spans every world's tabs.
        for (tab in everyTab) {
            val key = tab.layoutPreset ?: continue
            if (key == "custom") continue
            activeLayoutByTab[tab.id] = key
        }

        // Seed paneOrderByTab from the persisted focus so the head of
        // each tab's importance order matches what the client will
        // restore. Subsequent focus events bubble new entries.
        for (tab in everyTab) {
            val focused = tab.focusedPaneId
            if (focused != null && tab.panes.any { it.leaf.id == focused }) {
                paneOrderByTab[tab.id] = mutableListOf(focused)
            }
        }

        runCatching {
            val live = HashSet<String>()
            for (tab in everyTab) {
                tab.panes.forEach { live.add(it.leaf.id) }
            }
            for (stale in repo.allScrollbackLeafIds() - live) {
                repo.deleteScrollback(stale)
            }
        }.onFailure { log.warn("Scrollback GC failed", it) }
    }

    /**
     * Turn the persisted layout [loaded] into a live one: every terminal pane gets a fresh
     * session replaying its saved scrollback, panes that cannot survive a restart are dropped, and
     * the id counters are seeded above every id the server must never mint again.
     *
     * A background tab is dropped (the saved layout normally has none, see [forSaving], but an
     * older one may), yet its tab and pane ids are still reserved. So are the pane ids of every
     * agent record in [repo], closed ones included: a record remembers its pane id as history, and
     * a new pane that reused it would inherit that history.
     *
     * Called by [initialize] at startup, and by tests.
     *
     * @param loaded the persisted layout.
     * @param repo the settings database, for scrollback and the record pane ids.
     * @return the live layout.
     */
    internal fun rehydrate(loaded: WindowConfig, repo: SettingsRepository): WindowConfig {
        var maxNodeId = 0L
        var maxTabId = 0L
        var maxWorldId = 0L

        fun trackNodeId(id: String) {
            // Ids may carry a `-nonce` suffix (post-#86 shape) — the counter
            // lives in the numeric portion before the dash. Legacy ids have
            // no dash, so substringBefore returns the whole remainder.
            id.removePrefix("n").substringBefore('-').toLongOrNull()
                ?.let { if (it > maxNodeId) maxNodeId = it }
        }

        fun rebuildLeaf(leaf: LeafNode): LeafNode? {
            trackNodeId(leaf.id)
            return when (leaf.content) {
                // PTY-less panes carry no session; their content (the git
                // selection, file-browser tree, or web pane's last URL) is
                // restored verbatim so the pane reopens where it left off.
                is FileBrowserContent, is GitContent, is WebBrowserContent ->
                    leaf.copy(sessionId = "")
                // Agent consoles are ephemeral: a PTY-less virtual session
                // cannot be reattached after a restart (the driving MCP
                // client is gone), so a persisted agent pane is dropped.
                is AgentContent -> null
                is TerminalContent, null -> {
                    // Seed the fresh session with the persisted grid size so
                    // the raw scrollback bytes replay at the width they were
                    // rendered for; legacy records without a size fall back
                    // to the session default.
                    val prior = runCatching { repo.loadScrollback(leaf.id) }.getOrNull()
                    val freshSession = TerminalSessions.create(
                        leaf.cwd, prior?.bytes, prior?.cols, prior?.rows,
                    )
                    leaf.copy(sessionId = freshSession, content = TerminalContent(freshSession))
                }
            }
        }

        fun rebuildTab(tab: TabConfig): TabConfig {
            // Same nonce-aware numeric extraction as [trackNodeId].
            tab.id.removePrefix("t").substringBefore('-').toLongOrNull()
                ?.let { if (it > maxTabId) maxTabId = it }
            val rebuiltPanes = tab.panes.mapNotNull { p ->
                val rebuilt = rebuildLeaf(p.leaf) ?: return@mapNotNull null
                val box = PaneGeometry.normalize(p.x, p.y, p.width, p.height)
                p.copy(
                    leaf = rebuilt,
                    x = box.x, y = box.y, width = box.width, height = box.height,
                )
            }
            val livePaneIds = rebuiltPanes.mapTo(HashSet()) { it.leaf.id }
            val keepFocus = tab.focusedPaneId?.takeIf { it in livePaneIds }
            val activeMatchesPanes = keepFocus == tab.focusedPaneId
            return if (rebuiltPanes == tab.panes && activeMatchesPanes) tab
            else tab.copy(panes = rebuiltPanes, focusedPaneId = keepFocus)
        }

        val rebuiltWorlds = loaded.worlds.map { world ->
            world.id.removePrefix("w").substringBefore('-').toLongOrNull()
                ?.let { if (it > maxWorldId) maxWorldId = it }
            val (background, ordinary) = world.tabs.partition { it.isBackground }
            for (tab in background) {
                // Dropped, but its ids stay taken.
                tab.id.removePrefix("t").substringBefore('-').toLongOrNull()
                    ?.let { if (it > maxTabId) maxTabId = it }
                tab.panes.forEach { trackNodeId(it.leaf.id) }
            }
            val rebuiltTabs = ordinary.map { rebuildTab(it) }
            val tabIdSet = rebuiltTabs.mapTo(HashSet()) { it.id }
            world.copy(
                tabs = rebuiltTabs,
                activeTabId = world.activeTabId?.takeIf { it in tabIdSet },
            )
        }

        runCatching { repo.allAgentSessionLeafIds().forEach(::trackNodeId) }
            .onFailure { log.warn("Could not read the agent records' pane ids", it) }

        nodeIdCounter.set(maxNodeId)
        tabIdCounter.set(maxTabId)
        worldIdCounter.set(maxWorldId)

        val worldIdSet = rebuiltWorlds.mapTo(HashSet()) { it.id }
        val validatedActiveWorld = loaded.activeWorldId?.takeIf { it in worldIdSet }
            ?: rebuiltWorlds.firstOrNull()?.id
        shown = null
        return syncLegacyMirror(
            WindowConfig(worlds = rebuiltWorlds, activeWorldId = validatedActiveWorld),
        )
    }

    /**
     * Build the default (cold-start) config: a single "Home" world holding
     * one auto-tiled terminal tab.
     */
    private fun buildDefault(): WindowConfig {
        val worldId = newWorldId()
        val tab1 = buildDefaultTab()
        val world = WorldConfig(
            id = worldId,
            name = DEFAULT_WORLD_NAME,
            tabs = listOf(tab1),
            activeTabId = tab1.id,
        )
        return syncLegacyMirror(
            WindowConfig(worlds = listOf(world), activeWorldId = worldId),
        )
    }

    /** A fresh terminal tab (one full-bleed auto-tiled pane), for cold start
     *  and for seeding a newly-created world. */
    private fun buildDefaultTab(number: Int = 1): TabConfig {
        val s1 = TerminalSessions.create()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = s1,
            title = "Session ${TerminalSessions.displayNumber(s1)}",
            content = TerminalContent(s1),
        )
        return TabConfig(
            id = newTabId(),
            title = "Tab $number",
            panes = listOf(
                Pane(
                    leaf = leaf,
                    // Full-bleed — the Auto preset's tiling for a single
                    // pane, so the cold-start pane fills the tab instead of
                    // landing as a small floating rectangle (issue #86).
                    x = 0.0, y = 0.0,
                    width = 1.0,
                    height = 1.0,
                    z = 1L,
                ),
            ),
            // Without this the toolkit's tab snapshot has activePaneId=null
            // and the lone pane stays unrendered until the user clicks it.
            focusedPaneId = leaf.id,
            // Default the cold-start tab to auto-tiling too (issue #86).
            layoutPreset = LayoutPreset.Auto.key,
        )
    }

    // ── Tab dispatch ─────────────────────────────────────────────────

    /** Create a new tab with a single terminal pane, switch to it, and
     *  default it to auto-tiling.
     *
     *  A [background] tab is added out of sight and the person's tab and any
     *  shown background tab stay as they are; an ordinary tab hides a shown
     *  background tab (the person is moving on).
     *
     *  @param worldId the world to add to, or `null` for the active world.
     *  @param background true to open the tab as a background tab.
     *  @param initialCwd the folder the tab's first shell starts in, or `null` for the default.
     *  @return the newly minted tab id (used by the MCP `create_tab` tool
     *    to report the created tab; the `/window` command path ignores it).
     */
    fun addTab(worldId: String? = null, background: Boolean = false, initialCwd: String? = null): String = synchronized(this) {
        // Bootstrap a default world if the config is world-less (the
        // pre-[initialize] path some unit tests exercise). Production always
        // has at least the cold-start "Home" world from [buildDefault].
        val cfg = ensureWorlds(_config.value).also { if (it !== _config.value) _config.value = it }
        // Resolve the target world: explicit id, else the active world, else
        // the first world. Callers (WindowRoutes) pass the first world's id
        // for old-client commands so those always land in the default world.
        val wid = worldId ?: cfg.activeWorldId ?: cfg.worlds.firstOrNull()?.id ?: return@synchronized ""
        if (cfg.worlds.none { it.id == wid }) return@synchronized ""
        if (!background) unshow(backToWorld = false)
        val live = _config.value
        val world = live.worlds.first { it.id == wid }
        val cwd = sanitizeIncomingCwd(initialCwd)
        val sessionId = TerminalSessions.create(initialCwd = cwd)
        val tabId = newTabId()
        val nodeId = newNodeId()
        val newView = TabManager.addTab(
            cfg = viewOf(world),
            newTabId = tabId,
            newNodeId = nodeId,
            sessionId = sessionId,
            background = background,
            initialCwd = cwd,
        )
        _config.value = writeBackWorld(live, wid, newView)
        // Register the auto preset in the live map so pane add/remove/focus
        // events on this tab re-tile via maybeReapplyLayout without waiting
        // for a server restart to re-engage the persisted layoutPreset.
        activeLayoutByTab[tabId] = LayoutPreset.Auto.key
        // Mirror the addPaneToTab/addFileBrowserToTab/… bookkeeping for the
        // tab's first pane: seed the importance order (so this pane stays the
        // primary slot when siblings arrive) and run the Auto re-tile so the
        // lone pane's geometry matches the stamped preset — full-bleed. The
        // preset stamp alone never re-laid the pane out, which left it at its
        // small default rectangle instead of full screen (issue #86).
        recordPaneCreated(tabId, nodeId, parentPaneId = null)
        maybeReapplyLayout(tabId)
        tabId
    }

    /**
     * Close [tabId], destroying any PTY sessions no longer referenced. Refused (no change) when
     * only background tabs would be left in its world. Closing the shown background tab sends the
     * person back to where they came from first.
     *
     * @param tabId the tab to close.
     */
    fun closeTab(tabId: String) = synchronized(this) {
        val first = _config.value
        val firstWorld = worldOfTab(first, tabId) ?: return@synchronized
        if (TabManager.closeTab(viewOf(firstWorld), tabId) == null) return@synchronized
        if (shown?.tabId == tabId) unshow(backToWorld = true)
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.closeTab(viewOf(world), tabId) ?: return@synchronized
        commitWithSessionGc(cfg, writeBackWorld(cfg, world.id, newView))
    }

    /**
     * Mark [tabId] as the currently-selected tab (within its own world). A background tab is left
     * alone (only [show] opens one); an ordinary tab chosen in the active world hides a shown
     * background tab.
     *
     * @param tabId the tab to select.
     */
    fun setActiveTab(tabId: String) = synchronized(this) {
        val first = _config.value
        val firstWorld = worldOfTab(first, tabId) ?: return@synchronized
        if (TabManager.setActiveTab(viewOf(firstWorld), tabId) == null) return@synchronized
        if (firstWorld.id == activeWorldIdOf(first)) unshow(backToWorld = false)
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.setActiveTab(viewOf(world), tabId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /** Record the user's focus on [paneId] in [tabId]. */
    fun setFocusedPane(tabId: String, paneId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.setFocusedPane(viewOf(world), tabId, paneId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
        bubbleFocus(tabId, paneId)
        maybeReapplyLayout(tabId)
    }

    /** Move [tabId] before or after [targetTabId] (within their world). */
    fun moveTab(tabId: String, targetTabId: String, before: Boolean) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.moveTab(viewOf(world), tabId, targetTabId, before) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Mark [tabId] as hidden or visible in the tab strip.
     *
     * @see TabConfig.isHidden
     * @see WindowCommand.SetTabHidden
     */
    fun setTabHidden(tabId: String, hidden: Boolean) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.setTabHidden(viewOf(world), tabId, hidden) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Mark [tabId] as hidden or visible in the left sidebar tab tree.
     *
     * @see TabConfig.isHiddenFromSidebar
     * @see WindowCommand.SetTabHiddenFromSidebar
     */
    fun setTabHiddenFromSidebar(tabId: String, hidden: Boolean) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.setTabHiddenFromSidebar(viewOf(world), tabId, hidden) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /** Set the display title of [tabId]. */
    fun renameTab(tabId: String, title: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val newView = TabManager.renameTab(viewOf(world), tabId, title) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    // ── World lifecycle ──────────────────────────────────────────────

    /**
     * Create a new world (with one auto-tiled terminal tab) and make it
     * active. The new world's theme pair is seeded from [seedTheme] (the
     * caller resolves this from the current default/global selection).
     *
     * @param name      the display name for the new world.
     * @param seedTheme the initial theme pair, or `null` to follow global.
     * @return the newly minted world id.
     */
    fun addWorld(name: String, seedTheme: WorldThemeSelection? = null): String = synchronized(this) {
        unshow(backToWorld = false)
        val cfg = _config.value
        val worldId = newWorldId()
        val tab = buildDefaultTab()
        val world = WorldConfig(
            id = worldId,
            name = name.trim().ifEmpty { "World" }.take(80),
            tabs = listOf(tab),
            activeTabId = tab.id,
            themeSelection = seedTheme,
        )
        activeLayoutByTab[tab.id] = LayoutPreset.Auto.key
        _config.value = syncLegacyMirror(
            cfg.copy(worlds = cfg.worlds + world, activeWorldId = worldId),
        )
        worldId
    }

    /** Rename world [worldId]. No-op if unknown or unchanged. */
    fun renameWorld(worldId: String, name: String) = synchronized(this) {
        val cfg = _config.value
        val sanitized = name.trim().take(80)
        if (sanitized.isEmpty()) return@synchronized
        val newWorlds = cfg.worlds.map { w ->
            if (w.id == worldId && w.name != sanitized) w.copy(name = sanitized) else w
        }
        if (newWorlds == cfg.worlds) return@synchronized
        _config.value = syncLegacyMirror(cfg.copy(worlds = newWorlds))
    }

    /**
     * Switch the active world (world-aware clients only). Never touches the
     * legacy default-world mirror old clients follow.
     */
    fun setActiveWorld(worldId: String) = synchronized(this) {
        if (_config.value.activeWorldId == worldId) return@synchronized
        if (_config.value.worlds.none { it.id == worldId }) return@synchronized
        unshow(backToWorld = false)
        val cfg = _config.value
        _config.value = syncLegacyMirror(cfg.copy(activeWorldId = worldId))
    }

    /** Set world [worldId]'s theme pair. The legacy `THEME_V2_SELECTION`
     *  mirror (for the default world) is handled by WindowRoutes/B6. */
    fun setWorldTheme(worldId: String, selection: WorldThemeSelection) = synchronized(this) {
        val cfg = _config.value
        val newWorlds = cfg.worlds.map { w ->
            if (w.id == worldId && w.themeSelection != selection) w.copy(themeSelection = selection) else w
        }
        if (newWorlds == cfg.worlds) return@synchronized
        _config.value = syncLegacyMirror(cfg.copy(worlds = newWorlds))
    }

    /**
     * Close world [worldId], cascading to every tab + PTY session inside it.
     * Refuses to close the last remaining world. When the closed world was
     * active, the neighbour (or first) world becomes active.
     */
    fun closeWorld(worldId: String): Unit = synchronized(this) {
        val cfg = _config.value
        if (cfg.worlds.size <= 1) return@synchronized
        val idx = cfg.worlds.indexOfFirst { it.id == worldId }
        if (idx < 0) return@synchronized
        // A shown tab in this world goes with it: send the person back first.
        if (cfg.worlds[idx].tabs.any { it.id == shown?.tabId }) {
            unshow(backToWorld = true)
            return@synchronized closeWorld(worldId)
        }
        shown = shown?.takeIf { it.returnWorldId != worldId }
            ?: shown?.copy(returnTabId = null, returnWorldId = null)
        val remaining = cfg.worlds.filterNot { it.id == worldId }
        val newActive = if (cfg.activeWorldId == worldId) {
            remaining.getOrNull(idx.coerceAtMost(remaining.size - 1))?.id ?: remaining.first().id
        } else {
            cfg.activeWorldId
        }
        // Drop the closed world's tabs' bookkeeping so stale layout/order
        // entries don't linger.
        cfg.worlds[idx].tabs.forEach { tab ->
            activeLayoutByTab.remove(tab.id)
            paneOrderByTab.remove(tab.id)
        }
        val newCfg = syncLegacyMirror(cfg.copy(worlds = remaining, activeWorldId = newActive))
        // GC any PTY sessions the closed world uniquely referenced.
        commitWithSessionGc(cfg, newCfg)
    }

    /**
     * Move tab [tabId] (with all its panes and their live PTY sessions) out of
     * the world that owns it and into world [destWorldId], appending it to that
     * world's tab list. No sessions are touched — only ownership changes. No-op
     * when the tab or destination world is unknown, when the destination is the
     * tab's current world, or when the tab is the last one in its source world
     * (which would leave that world empty). If the moved tab was its source
     * world's active tab, a surviving sibling is promoted to active there.
     *
     * @param tabId       the id of the tab to move.
     * @param destWorldId the id of the destination world.
     */
    fun moveTabToWorld(tabId: String, destWorldId: String): Unit = synchronized(this) {
        val cfg = _config.value
        val src = worldOfTab(cfg, tabId) ?: return@synchronized
        if (src.id == destWorldId) return@synchronized
        if (cfg.worlds.none { it.id == destWorldId }) return@synchronized
        if (src.tabs.size <= 1) return@synchronized // don't strand the source world empty
        // Nor leave it with nothing but background tabs.
        if (src.tabs.filterNot { it.id == tabId }.all { it.isBackground }) return@synchronized
        if (shown?.tabId == tabId) {
            unshow(backToWorld = true)
            return@synchronized moveTabToWorld(tabId, destWorldId)
        }
        val moving = src.tabs.first { it.id == tabId }
        val srcRemaining = src.tabs.filterNot { it.id == tabId }
        val srcActive = if (src.activeTabId == tabId) srcRemaining.first { !it.isBackground }.id else src.activeTabId
        val newWorlds = cfg.worlds.map { w ->
            when (w.id) {
                src.id -> w.copy(tabs = srcRemaining, activeTabId = srcActive)
                destWorldId -> w.copy(tabs = w.tabs + moving)
                else -> w
            }
        }
        _config.value = syncLegacyMirror(cfg.copy(worlds = newWorlds))
    }

    // ── Lookups ──────────────────────────────────────────────────────

    /** Find a leaf by id across all panes in all worlds. */
    fun findLeaf(paneId: String): LeafNode? = synchronized(this) {
        val cfg = _config.value
        for (tab in allTabs(cfg)) {
            tab.panes.firstOrNull { it.leaf.id == paneId }?.let { return@synchronized it.leaf }
        }
        null
    }

    /** Return the id of the tab that contains [paneId] (across worlds). */
    fun tabIdOfPane(paneId: String): String? = synchronized(this) {
        val cfg = _config.value
        for (tab in allTabs(cfg)) {
            if (tab.panes.any { it.leaf.id == paneId }) return@synchronized tab.id
        }
        null
    }

    /**
     * The id of the pane showing terminal session [sessionId]. Called by the agent tracker's poll
     * to tie a record to its place in the layout.
     *
     * @param sessionId the terminal session.
     * @return the layout leaf id, or null when no pane shows it.
     */
    fun leafIdOfSession(sessionId: String): String? = synchronized(this) {
        findLeafBySession(_config.value, sessionId)?.id
    }

    /**
     * The name the person gave (F2) the pane showing terminal session [sessionId]. Called by the
     * agent tracker's poll to title a record.
     *
     * @param sessionId the terminal session.
     * @return the custom name, or null.
     */
    fun customNameOfSession(sessionId: String): String? = synchronized(this) {
        findLeafBySession(_config.value, sessionId)?.customName
    }

    private fun findLeafBySession(cfg: WindowConfig, sessionId: String): LeafNode? {
        for (tab in allTabs(cfg)) {
            tab.panes.firstOrNull { it.leaf.sessionId == sessionId }?.let { return it.leaf }
        }
        return null
    }

    // ── File-browser content dispatch ────────────────────────────────

    private fun mutateFileBrowser(
        paneId: String,
        transform: (FileBrowserContent) -> FileBrowserContent,
    ): FileBrowserContent? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized null
        val (newView, newState) = PaneManager.updateFileBrowserContent(viewOf(world), paneId, transform)
            ?: return@synchronized null
        _config.value = writeBackWorld(cfg, world.id, newView)
        newState
    }

    fun setFileBrowserSelected(paneId: String, relPath: String?): FileBrowserContent? =
        mutateFileBrowser(paneId) { it.copy(selectedRelPath = relPath) }

    fun setFileBrowserExpanded(paneId: String, dirRelPath: String, expanded: Boolean): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            val next = if (expanded) it.expandedDirs + dirRelPath else it.expandedDirs - dirRelPath
            if (next == it.expandedDirs) it else it.copy(expandedDirs = next)
        }

    fun setFileBrowserLeftWidth(paneId: String, px: Int): FileBrowserContent? {
        val clamped = px.coerceIn(0, 640)
        return mutateFileBrowser(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setFileBrowserAutoRefresh(paneId: String, enabled: Boolean): FileBrowserContent? =
        mutateFileBrowser(paneId) { it.copy(autoRefresh = enabled) }

    fun setFileBrowserFilter(paneId: String, filter: String): FileBrowserContent? {
        val normalized = filter.trim().ifEmpty { null }
        return mutateFileBrowser(paneId) { it.copy(fileFilter = normalized) }
    }

    fun setFileBrowserSort(paneId: String, sort: FileBrowserSort): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            if (it.sortBy == sort) it else it.copy(sortBy = sort)
        }

    fun setFileBrowserExpandedAll(paneId: String, dirs: Set<String>): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            val merged = it.expandedDirs + dirs
            if (merged == it.expandedDirs) it else it.copy(expandedDirs = merged)
        }

    fun clearFileBrowserExpanded(paneId: String): FileBrowserContent? =
        mutateFileBrowser(paneId) {
            if (it.expandedDirs.isEmpty()) it else it.copy(expandedDirs = emptySet())
        }

    fun setFileBrowserFontSize(paneId: String, size: Int): FileBrowserContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateFileBrowser(paneId) { it.copy(fontSize = clamped) }
    }

    // ── Terminal content dispatch ────────────────────────────────────

    private fun mutateTerminal(
        paneId: String,
        transform: (TerminalContent) -> TerminalContent,
    ): TerminalContent? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized null
        val (newView, newState) = PaneManager.updateTerminalContent(viewOf(world), paneId, transform)
            ?: return@synchronized null
        _config.value = writeBackWorld(cfg, world.id, newView)
        newState
    }

    fun setTerminalFontSize(paneId: String, size: Int): TerminalContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateTerminal(paneId) { it.copy(fontSize = clamped) }
    }

    /**
     * Set the per-pane automatic-reflow override for terminal pane [paneId].
     *
     * Called from the [WindowCommand.SetTerminalAutoReflow] dispatch in
     * `WindowRoutes` when the user toggles "Automatic reformat (this window)"
     * in the reformat button's hover popup. Persists into
     * [TerminalContent.autoReflow] so the choice survives reloads.
     *
     * @param paneId the terminal pane to update.
     * @param enabled `true` to keep auto-reflow on for this pane, `false` to
     *   freeze it until the user manually reformats.
     * @return the updated [TerminalContent], or `null` if [paneId] is not a
     *   terminal pane in the current config.
     */
    fun setTerminalAutoReflow(paneId: String, enabled: Boolean): TerminalContent? =
        mutateTerminal(paneId) { it.copy(autoReflow = enabled) }

    // ── Git content dispatch ─────────────────────────────────────────

    private fun mutateGit(
        paneId: String,
        transform: (GitContent) -> GitContent,
    ): GitContent? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized null
        val (newView, newState) = PaneManager.updateGitContent(viewOf(world), paneId, transform)
            ?: return@synchronized null
        _config.value = writeBackWorld(cfg, world.id, newView)
        newState
    }

    fun setGitSelected(paneId: String, filePath: String?): GitContent? =
        mutateGit(paneId) { it.copy(selectedFilePath = filePath) }

    fun setGitLeftWidth(paneId: String, px: Int): GitContent? {
        val clamped = px.coerceIn(0, 640)
        return mutateGit(paneId) { it.copy(leftColumnWidthPx = clamped) }
    }

    fun setGitDiffMode(paneId: String, mode: GitDiffMode): GitContent? =
        mutateGit(paneId) { it.copy(diffMode = mode) }

    fun setGitGraphicalDiff(paneId: String, enabled: Boolean): GitContent? =
        mutateGit(paneId) { it.copy(graphicalDiff = enabled) }

    fun setGitDiffFontSize(paneId: String, size: Int): GitContent? {
        val clamped = size.coerceIn(8, 24)
        return mutateGit(paneId) { it.copy(diffFontSize = clamped) }
    }

    fun setGitAutoRefresh(paneId: String, enabled: Boolean): GitContent? =
        mutateGit(paneId) { it.copy(autoRefresh = enabled) }

    // ── Web-browser content dispatch ─────────────────────────────────

    private fun mutateWebBrowser(
        paneId: String,
        transform: (WebBrowserContent) -> WebBrowserContent,
    ): WebBrowserContent? = synchronized(this) {
        val cfg = _config.value
        // Resolve the world that owns the pane and mutate its tab view, so a web
        // pane in a non-default world is found and written back correctly (the
        // top-level `tabs` are only a mirror of the default world). Mirrors
        // [mutateFileBrowser].
        val world = worldOfPane(cfg, paneId) ?: return@synchronized null
        val (newView, newState) = PaneManager.updateWebBrowserContent(viewOf(world), paneId, transform)
            ?: return@synchronized null
        _config.value = writeBackWorld(cfg, world.id, newView)
        newState
    }

    /**
     * Persist a web pane's committed URL (and optionally page title). Called
     * from [WindowRoutes] when the Electron webview reports a navigation via
     * [WindowCommand.WebBrowserSetUrl]. Mutating `_config` re-broadcasts the
     * layout and triggers the debounced persist, so the pane restores at this
     * URL after a restart.
     *
     * @param paneId the web pane to update
     * @param url the newly committed URL
     * @param title the current page title, or null to leave it unchanged
     * @return the new content, or null if [paneId] is not a web pane
     * @see addWebBrowserToTab
     */
    fun setWebBrowserUrl(paneId: String, url: String, title: String? = null): WebBrowserContent? =
        mutateWebBrowser(paneId) { it.copy(url = url, title = title ?: it.title) }

    // ── Pane CRUD dispatch ───────────────────────────────────────────

    /** Remove the pane [paneId] from its tab and destroy any orphan PTY. */
    fun closePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val tabId = world.tabs.firstOrNull { tab ->
            tab.panes.any { it.leaf.id == paneId }
        }?.id
        val newView = PaneManager.closePane(viewOf(world), paneId) ?: return@synchronized
        commitWithSessionGc(cfg, writeBackWorld(cfg, world.id, newView))
        recordPaneRemoved(paneId)
        if (tabId != null) maybeReapplyLayout(tabId)
    }

    /** Close every pane that references [sessionId] and destroy the PTY. */
    fun closeSession(sessionId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfSession(cfg, sessionId) ?: return@synchronized
        val newView = PaneManager.closeSession(viewOf(world), sessionId) ?: return@synchronized
        commitWithSessionGc(cfg, writeBackWorld(cfg, world.id, newView))
    }

    /** Rename pane [paneId]; an empty title clears the custom name. */
    fun renamePane(paneId: String, title: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.renamePane(viewOf(world), paneId, title) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /** Push a freshly-detected cwd for the pane backed by [sessionId]. */
    fun updatePaneCwd(sessionId: String, cwd: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfSession(cfg, sessionId) ?: return@synchronized
        val newView = PaneManager.updatePaneCwd(viewOf(world), sessionId, cwd) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Apply a program-set terminal title (OSC 0/2) to the pane backed by
     * [sessionId]. Called by the title watcher in `TerminalSessions.create`
     * when the program running in a terminal sets (or clears) its title.
     * Mutating `_config` re-broadcasts the layout to every client and triggers
     * the debounced SQLite persist, exactly like [updatePaneCwd]. A user's
     * manual name always wins (see [computeLeafTitle]).
     *
     * @param sessionId the PTY session id whose pane should be titled.
     * @param rawTitle the raw OSC title payload (sanitized downstream).
     * @see PaneManager.applyProgramTitle
     */
    fun applyProgramTitle(sessionId: String, rawTitle: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfSession(cfg, sessionId) ?: return@synchronized
        val newView = PaneManager.applyProgramTitle(viewOf(world), sessionId, rawTitle) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Drop every stored program title and revert the affected panes to their
     * cwd-based names. Called from `main()` when the "use program-set terminal
     * titles" setting turns off (including at startup while the feature is
     * disabled, which scrubs any titles persisted from an earlier enabled run).
     *
     * @see PaneManager.clearProgramTitles
     */
    fun clearProgramTitles() = synchronized(this) {
        val cfg = _config.value
        var changed = false
        val newWorlds = cfg.worlds.map { world ->
            val newView = PaneManager.clearProgramTitles(viewOf(world))
            if (newView == null) world
            else {
                changed = true
                world.copy(tabs = newView.tabs, activeTabId = newView.activeTabId)
            }
        }
        if (!changed) return@synchronized
        _config.value = syncLegacyMirror(cfg.copy(worlds = newWorlds))
    }

    /**
     * Update the position and size of [paneId]. The user has overridden
     * preset-driven geometry, so the affected tab's `layoutPreset` is
     * cleared (transitions to Custom mode) — subsequent pane add/remove
     * events stop auto re-tiling until the user re-selects a preset.
     */
    fun setPaneGeometry(
        paneId: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
    ) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val tabId = world.tabs.firstOrNull { tab ->
            tab.panes.any { it.leaf.id == paneId }
        }?.id
        val newView = PaneManager.setPaneGeometry(viewOf(world), paneId, x, y, width, height)
            ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
        if (tabId != null) clearLayoutPresetForTab(tabId)
    }

    /** Bring [paneId] to the top of its tab's stacking order. */
    fun raisePane(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.raisePane(viewOf(world), paneId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Set the persisted 3D-world visual zoom multiplier on [paneId].
     *
     * Called from the [WindowCommand.SetPaneZoom] dispatch in `WindowRoutes`
     * when the 3D world zooms or resets a pane, so the magnification rides
     * the normal config broadcast + debounced persistence path into the
     * database. Unlike [setPaneGeometry] this does **not** clear the tab's
     * layout preset — zoom is a 3D presentation value, not 2D tab geometry.
     *
     * @param paneId the pane whose zoom to set.
     * @param zoom the new zoom multiplier (1.0 = unzoomed); clamped, and
     *   ignored when non-finite (see [PaneManager.setPaneZoom]).
     * @see Pane.zoom
     */
    fun setPaneZoom(paneId: String, zoom: Double) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.setPaneZoom(viewOf(world), paneId, zoom) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Set (or clear) the persisted 3D-world grid override on [paneId].
     *
     * Called from the [WindowCommand.SetPaneGrid3d] dispatch in `WindowRoutes`
     * when the 3D world grows/shrinks a pane's grid (or clears it via the
     * "restore native grid" hotkey), so the override rides the normal config
     * broadcast + debounced persistence path into the database. Like
     * [setPaneZoom] this does **not** clear the tab's layout preset — the 3D
     * grid override is a 3D-only value that never touches 2D tab geometry.
     *
     * @param paneId the pane whose 3D grid override to set.
     * @param cols the override column count, or `null` to clear the override.
     * @param rows the override row count, or `null` to clear the override.
     * @see Pane.grid3d
     */
    fun setPaneGrid3d(paneId: String, cols: Int?, rows: Int?) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.setPaneGrid3d(viewOf(world), paneId, cols, rows) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /** Toggle the maximized flag on [paneId]. */
    fun toggleMaximized(paneId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.toggleMaximized(viewOf(world), paneId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Idempotently set [paneId]'s maximized flag to [maximized]. Used by
     * the MCP `maximize_window` tool, which must be race-free against a
     * user's concurrent toggle (see [PaneManager.setMaximized]).
     *
     * @param paneId the pane to (un)maximize
     * @param maximized the desired end state
     */
    fun setMaximized(paneId: String, maximized: Boolean) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.setMaximized(viewOf(world), paneId, maximized) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Set or clear the agent-activity note on [paneId]. Called by the MCP
     * `annotate_window` tool and by the automatic agent-touch marker in
     * `McpWriteTools` — the note renders as a badge on the pane in every
     * connected client, making agent activity visible across devices.
     *
     * @param paneId the pane to annotate
     * @param note badge text, or `null`/blank to clear
     * @see PaneManager.setAgentNote
     */
    fun setAgentNote(paneId: String, note: String?) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        val newView = PaneManager.setAgentNote(viewOf(world), paneId, note) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Apply a layout algorithm to [tabId]. Records the chosen layout in
     * [activeLayoutByTab] so subsequent pane add/remove/focus events can
     * re-tile via [maybeReapplyLayout]. For `"auto"`, the
     * [primaryPaneId] argument is overridden by the head of
     * [paneOrderByTab]`[tabId]` so the most-recently-focused pane always
     * lands in slot 0.
     */
    fun applyLayout(tabId: String, layout: String, primaryPaneId: String?) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized
        val effectivePrimary = if (layout == "auto") {
            paneOrderByTab[tabId]?.firstOrNull() ?: primaryPaneId
        } else {
            primaryPaneId
        }
        val laidOut = PaneManager.applyLayout(viewOf(world), tabId, layout, effectivePrimary)
            ?: return@synchronized
        // Stamp the chosen preset onto the persisted TabConfig so a
        // server restart re-engages auto re-tile without the user
        // having to re-pick from the dropdown.
        val tabIdx = laidOut.tabs.indexOfFirst { it.id == tabId }
        val newView = if (tabIdx >= 0) {
            val tab = laidOut.tabs[tabIdx]
            if (tab.layoutPreset == layout) laidOut
            else laidOut.copy(
                tabs = laidOut.tabs.toMutableList().also {
                    it[tabIdx] = tab.copy(layoutPreset = layout)
                },
            )
        } else laidOut
        _config.value = writeBackWorld(cfg, world.id, newView)
        activeLayoutByTab[tabId] = layout
    }

    /**
     * Internal helper used after manual move/resize to forget the
     * tab's persisted preset so subsequent pane add/remove events
     * don't undo the user's hand-placement. Call from the geometry-
     * setter path. Idempotent.
     */
    private fun clearLayoutPresetForTab(tabId: String) {
        activeLayoutByTab.remove(tabId)
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return
        val tabIdx = world.tabs.indexOfFirst { it.id == tabId }
        if (tabIdx < 0) return
        val tab = world.tabs[tabIdx]
        if (tab.layoutPreset == null) return
        val newView = viewOf(world).copy(
            tabs = world.tabs.toMutableList().also {
                it[tabIdx] = tab.copy(layoutPreset = null)
            },
        )
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Normalise the cwd a freshly-created pane should be created with.
     * Trims/strips blank input and returns `null` for "no cwd". The client
     * passes the directory the user can see on screen at click time
     * (the focused pane's [LeafNode.cwd] from its local [WindowConfig]
     * snapshot) so there is no server-side guessing.
     */
    private fun sanitizeIncomingCwd(explicit: String?): String? =
        explicit?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Floor for non-terminal panes when the client couldn't supply a cwd
     * (e.g. brand-new tab with no terminal in it yet). Returning the
     * server's working directory means the file-browser / git pane shows
     * *something* useful instead of an empty listing with a "FILES" title.
     */
    private fun nonTerminalCwdFallback(): String =
        System.getProperty("user.dir") ?: System.getProperty("user.home") ?: "/"

    /**
     * Spawn a fresh shell as a new pane in [tabId]. Used by the new-window
     * icon and the empty-tab placeholder.
     */
    fun addPaneToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd)
        val sessionId = TerminalSessions.create(initialCwd = effectiveCwd)
        val fallbackTitle = "Session ${TerminalSessions.displayNumber(sessionId)}"
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = sessionId,
            cwd = effectiveCwd,
            title = computeLeafTitle(null, null, effectiveCwd, fallbackTitle),
            content = TerminalContent(sessionId),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        // Promote the new pane to the tab's focused pane so the toolkit's
        // snapshot reports it as the active pane (the renderer then lands
        // the focus ring on it).
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a file-browser pane to [tabId]. */
    fun addFileBrowserToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd) ?: nonTerminalCwdFallback()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            cwd = effectiveCwd,
            title = computeLeafTitle(null, null, effectiveCwd, "Files"),
            content = FileBrowserContent(),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a git overview pane to [tabId]. */
    fun addGitToTab(tabId: String, initialCwd: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val effectiveCwd = sanitizeIncomingCwd(initialCwd) ?: nonTerminalCwdFallback()
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            cwd = effectiveCwd,
            title = computeLeafTitle(null, null, effectiveCwd, "Git"),
            content = GitContent(),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /**
     * Add a web-browser pane to [tabId].
     *
     * Mirrors [addGitToTab]: a PTY-less leaf with an empty session id whose
     * content is a [WebBrowserContent]. No process is spawned and no cwd is
     * needed — the pane is seeded only with [initialUrl] (null opens the
     * client's blank start page). Called from the "New Web Browser" menu item
     * via [WindowCommand.AddWebBrowserToTab].
     *
     * @param tabId the tab to add the web pane to
     * @param initialUrl the URL to seed the pane with, or null for the start page
     * @return the created leaf, or null when [tabId] doesn't exist
     * @see addGitToTab
     */
    fun addWebBrowserToTab(tabId: String, initialUrl: String? = null): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = "",
            cwd = nonTerminalCwdFallback(),
            title = computeLeafTitle(null, null, null, "Web"),
            content = WebBrowserContent(url = initialUrl),
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /**
     * Add an agent-console pane to [tabId], backed by the already
     * registered PTY-less agent session [sessionId]. Called by the MCP
     * `open_console` tool (see `McpConsoleTools`), never from the client
     * command surface — agent panes exist only while their driving MCP
     * client is connected.
     *
     * @param tabId the tab to add the console to
     * @param sessionId the agent session id from [TerminalSessions.registerAgent]
     * @param title display title for the pane
     * @param renderMode `"transcript"` or `"screen"` (see [AgentContent])
     * @param cols requested grid width for screen mode, or null
     * @param rows requested grid height for screen mode, or null
     * @return the created leaf, or null when [tabId] doesn't exist
     */
    fun addAgentToTab(
        tabId: String,
        sessionId: String,
        title: String,
        renderMode: String,
        cols: Int? = null,
        rows: Int? = null,
    ): LeafNode? = synchronized(this) {
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = sessionId,
            title = title,
            customName = title,
            content = AgentContent(renderMode = renderMode, cols = cols, rows = rows),
            agentNote = "agent",
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /** Add a linked terminal pane to [tabId] sharing [targetSessionId]. */
    fun addLinkToTab(tabId: String, targetSessionId: String): LeafNode? = synchronized(this) {
        if (TerminalSessions.get(targetSessionId) == null) return@synchronized null
        val cfg = _config.value
        val world = worldOfTab(cfg, tabId) ?: return@synchronized null
        val idx = world.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return@synchronized null
        val tab = world.tabs[idx]
        val parentPaneId = tab.focusedPaneId
        val sourceTitle = findLeafBySession(cfg, targetSessionId)?.title ?: "Terminal"
        val leaf = LeafNode(
            id = newNodeId(),
            sessionId = targetSessionId,
            title = sourceTitle,
            content = TerminalContent(targetSessionId),
            isLink = true,
        )
        val (ox, oy) = PaneManager.randomSnappedOrigin()
        val newPane = Pane(
            leaf = leaf,
            x = ox, y = oy,
            width = PaneGeometry.DEFAULT_SIZE,
            height = PaneGeometry.DEFAULT_SIZE,
            z = PaneManager.nextZ(tab),
        )
        var newView = PaneManager.appendPane(viewOf(world), idx, newPane)
        TabManager.setFocusedPane(newView, tabId, leaf.id)?.let { newView = it }
        _config.value = writeBackWorld(cfg, world.id, newView)
        recordPaneCreated(tabId, leaf.id, parentPaneId)
        maybeReapplyLayout(tabId)
        leaf
    }

    /**
     * Move [paneId] from its current tab into [targetTabId].
     *
     * Called from `handleWindowCommand` for [WindowCommand.MovePaneToTab]
     * — dispatched by the mobile clients' pane sheets and by the web pane
     * menu's "Move to tab" submenu (issue #89). Beyond the structural move
     * (delegated to [PaneManager.movePaneToTab]) this mirrors the
     * add/close bookkeeping so preset-driven tabs stay tiled:
     *  - the moved pane's importance-order entry migrates from the source
     *    tab to the head of the target tab's order ([recordPaneRemoved] +
     *    [recordPaneCreated]), matching how a freshly added pane ranks;
     *  - both the source tab (now one pane lighter) and the target tab
     *    (one pane heavier) re-apply their remembered layout via
     *    [maybeReapplyLayout], so e.g. two Auto tabs both re-tile.
     *
     * No-op when the pane or target tab doesn't exist, or when the pane
     * already lives in the target tab (PaneManager returns null then).
     *
     * @param paneId      the pane to move
     * @param targetTabId the destination tab id
     * @see PaneManager.movePaneToTab
     */
    fun movePaneToTab(paneId: String, targetTabId: String) = synchronized(this) {
        val cfg = _config.value
        // Panes can only move between tabs within the same world; the
        // per-world view naturally enforces this (a target tab in another
        // world isn't present in the view, so PaneManager returns null).
        val world = worldOfPane(cfg, paneId) ?: return@synchronized
        // Resolve the source tab BEFORE the move so we can re-layout it after.
        val sourceTabId = world.tabs.firstOrNull { tab ->
            tab.panes.any { it.leaf.id == paneId }
        }?.id
        val newView = PaneManager.movePaneToTab(viewOf(world), paneId, targetTabId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
        // Migrate the importance-order entry: drop it from the source tab's
        // order (and clear any parent linkage — the "created next to" pane
        // stays behind in the old tab), then insert it at the head of the
        // target tab's order like a newly created pane.
        recordPaneRemoved(paneId)
        recordPaneCreated(targetTabId, paneId, parentPaneId = null)
        // Re-tile both sides if a preset (e.g. Auto) is driving them.
        if (sourceTabId != null) maybeReapplyLayout(sourceTabId)
        maybeReapplyLayout(targetTabId)
    }

    /** Swap the positions and sizes of two panes that share a tab. */
    fun swapPanes(aId: String, bId: String) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, aId) ?: return@synchronized
        val newView = PaneManager.swapPanes(viewOf(world), aId, bId) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
    }

    /**
     * Reorder [aId] before or after [bId] within their shared tab, then re-tile
     * that tab if a layout preset (e.g. Auto) is driving it — the list order is
     * what auto-tiling reads.
     *
     * @param retile `false` skips the preset re-tile: the 3D world reorders ring
     *   slots without wanting the tab's 2D geometry rewritten underneath it (see
     *   [WindowCommand.MovePaneWithinTab.retile]). The list order still changes,
     *   so the next preset-driven re-tile (a 2D add/remove/focus, or re-picking
     *   the preset) lays out in the new order.
     * @see PaneManager.movePaneWithinTab
     */
    fun movePaneWithinTab(aId: String, bId: String, before: Boolean, retile: Boolean = true) = synchronized(this) {
        val cfg = _config.value
        val world = worldOfPane(cfg, aId) ?: return@synchronized
        val newView = PaneManager.movePaneWithinTab(viewOf(world), aId, bId, before) ?: return@synchronized
        _config.value = writeBackWorld(cfg, world.id, newView)
        if (retile) {
            val tabId = newView.tabs.firstOrNull { tab -> tab.panes.any { it.leaf.id == aId } }?.id
            if (tabId != null) maybeReapplyLayout(tabId)
        }
    }

    // ── Session lifecycle helpers ────────────────────────────────────

    /** Whether [sessionId] is referenced by any leaf in the current config. */
    fun hasSession(sessionId: String): Boolean =
        collectSessionIds(_config.value).contains(sessionId)

    private fun collectSessionIds(cfg: WindowConfig): Set<String> {
        val out = HashSet<String>()
        allTabs(cfg).forEach { tab ->
            tab.panes.forEach { p ->
                if (p.leaf.sessionId.isNotEmpty()) out.add(p.leaf.sessionId)
            }
        }
        return out
    }

    /**
     * Commit [newCfg] and destroy any PTY sessions that were referenced by
     * [oldCfg] but are no longer reachable from [newCfg].
     */
    private fun commitWithSessionGc(oldCfg: WindowConfig, newCfg: WindowConfig) {
        val before = collectSessionIds(oldCfg)
        _config.value = newCfg
        val after = collectSessionIds(newCfg)
        (before - after).forEach { TerminalSessions.destroy(it) }
    }
}
