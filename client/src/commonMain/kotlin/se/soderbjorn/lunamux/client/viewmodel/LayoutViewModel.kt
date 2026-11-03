/**
 * Layout-mutation slice of the application backing model. Owns the
 * `WindowSocket` and forwards tab/pane CRUD, geometry, focus, and
 * z-order commands to the server.
 *
 * Used internally by [AppBackingViewModel] which delegates its layout
 * methods here. Splitting the logic keeps `AppBackingViewModel` thin
 * without altering its public API.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.client.TabCloseCheck
import se.soderbjorn.lunamux.client.WindowSocket
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.closeTabAsking

/**
 * Routes layout commands (tabs, panes, geometry, focus) to the server
 * via [windowSocket].
 *
 * @param windowSocket the live `/window` WebSocket for sending commands.
 * @param windowState where the layout, sessions and tasks are read from when a tab close asks first.
 */
internal class LayoutViewModel(
    private val windowSocket: WindowSocket,
    private val windowState: WindowStateRepository? = null,
) {
    /** Tell the server to switch to tab [tabId]. */
    suspend fun setActiveTab(tabId: String) {
        windowSocket.send(WindowCommand.SetActiveTab(tabId = tabId))
    }

    /** Focus [paneId] within [tabId]. */
    suspend fun setFocusedPane(tabId: String, paneId: String) {
        windowSocket.send(WindowCommand.SetFocusedPane(tabId = tabId, paneId = paneId))
    }

    /** Create a new tab with a default terminal pane. */
    suspend fun addTab() {
        windowSocket.send(WindowCommand.AddTab)
    }

    /**
     * Close tab [tabId] and all panes within it.
     *
     * With [ask], a tab that has an agent working in it is closed only if [ask] answers true for
     * the question the tab close guard builds; without it (a screen that asks for itself) the tab
     * closes at once.
     *
     * @param tabId the tab to close.
     * @param ask shows the question and returns whether the person chose to close, or null to close without asking.
     * @return true when the close was sent, false when the person kept the tab.
     * @see se.soderbjorn.lunamux.client.TabCloseGuard
     */
    suspend fun closeTab(tabId: String, ask: (suspend (TabCloseCheck) -> Boolean)? = null): Boolean {
        val state = windowState
        if (ask != null && state != null) return closeTabAsking(state, tabId, { windowSocket.send(it) }, ask)
        windowSocket.send(WindowCommand.CloseTab(tabId = tabId))
        return true
    }

    /** Rename tab [tabId] to [title]. */
    suspend fun renameTab(tabId: String, title: String) {
        windowSocket.send(WindowCommand.RenameTab(tabId = tabId, title = title))
    }

    /** Reorder [tabId] relative to [targetTabId]. */
    suspend fun moveTab(tabId: String, targetTabId: String, before: Boolean) {
        windowSocket.send(WindowCommand.MoveTab(tabId = tabId, targetTabId = targetTabId, before = before))
    }

    /** Close pane [paneId] (terminal, file-browser, or git). */
    suspend fun closePane(paneId: String) {
        windowSocket.send(WindowCommand.Close(paneId = paneId))
    }

    /** Close all panes that share [sessionId] and terminate the PTY. */
    suspend fun closeSession(sessionId: String) {
        windowSocket.send(WindowCommand.CloseSession(sessionId = sessionId))
    }

    /** Set a custom title for [paneId]. */
    suspend fun renamePane(paneId: String, title: String) {
        windowSocket.send(WindowCommand.Rename(paneId = paneId, title = title))
    }

    /**
     * Add a new terminal pane to [tabId]. [cwd] is the directory the new
     * shell should start in; pass the cwd visible on screen at click
     * time (see `resolveCwdForNewPane` in PaneActions). `null` lets the
     * server fall back to `$HOME`.
     */
    suspend fun addPaneToTab(tabId: String, cwd: String? = null) {
        windowSocket.send(WindowCommand.AddPaneToTab(tabId = tabId, cwd = cwd))
    }

    /**
     * Add a file-browser pane to [tabId] rooted at [cwd]. `null` lets
     * the server fall back to its own working directory so the pane is
     * never created with an empty listing.
     */
    suspend fun addFileBrowserToTab(tabId: String, cwd: String? = null) {
        windowSocket.send(WindowCommand.AddFileBrowserToTab(tabId = tabId, cwd = cwd))
    }

    /** Add a git pane to [tabId] rooted at [cwd]. */
    suspend fun addGitToTab(tabId: String, cwd: String? = null) {
        windowSocket.send(WindowCommand.AddGitToTab(tabId = tabId, cwd = cwd))
    }

    /** Add a linked pane to [tabId] sharing [targetSessionId]. */
    suspend fun addLinkToTab(tabId: String, targetSessionId: String) {
        windowSocket.send(WindowCommand.AddLinkToTab(tabId = tabId, targetSessionId = targetSessionId))
    }

    /** Update the geometry (position and size) for [paneId]. */
    suspend fun setPaneGeom(paneId: String, x: Double, y: Double, width: Double, height: Double) {
        windowSocket.send(WindowCommand.SetPaneGeom(paneId = paneId, x = x, y = y, width = width, height = height))
    }

    /** Bring [paneId] to the front of its tab's z-order. */
    suspend fun raisePane(paneId: String) {
        windowSocket.send(WindowCommand.RaisePane(paneId = paneId))
    }

    /** Move [paneId] from its current tab to [targetTabId]. */
    suspend fun movePaneToTab(paneId: String, targetTabId: String) {
        windowSocket.send(WindowCommand.MovePaneToTab(paneId = paneId, targetTabId = targetTabId))
    }

    /** Ask the server to re-send Claude AI usage data. */
    suspend fun refreshUsage() {
        windowSocket.send(WindowCommand.RefreshUsage)
    }

    /** Request the server to open the settings panel. */
    suspend fun openSettings() {
        windowSocket.send(WindowCommand.OpenSettings)
    }
}
