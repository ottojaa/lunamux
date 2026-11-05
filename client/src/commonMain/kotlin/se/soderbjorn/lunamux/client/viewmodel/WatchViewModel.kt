/**
 * The shared view model of the watch: the read-only live view of a background agent's terminal
 * that opens over the pane the person is looking at, and the one action it offers, showing the
 * agent's hidden tab as an ordinary tab.
 *
 * Contains [WatchViewModel] and its state type [WatchViewModel.Watch]. The task panel opens it from
 * an agent's line, and any other surface that lists agents can open the same watch with
 * [WatchViewModel.watch], so there is one view, one header and one set of keys wherever a person
 * starts it. Web draws it today; another client can draw the same state.
 *
 * @see TaskPanelViewModel
 * @see WindowCommand.TakeOverTab
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId

/**
 * View model of the watch.
 *
 * Created once per window. The view collects [stateFlow] and draws the overlay for as long as it is
 * non-null; surfaces that start a watch call [watch], and the overlay's keys call [showAsTab] and
 * [close]. A watch belongs to the tab that was active when it opened: it is dropped, not just
 * hidden, as soon as that tab is no longer the active one or the watched session has ended, so it
 * never comes back with the tab.
 *
 * @param windowState the source of the sessions, tasks and layout.
 * @param send sends a command to the server, normally `windowSocket::send`.
 * @see TaskPanelViewModel.activateMember
 */
class WatchViewModel(
    private val windowState: WindowStateRepository,
    private val send: suspend (WindowCommand) -> Unit,
) {
    /**
     * What the open watch shows.
     *
     * @property recordId the session record being watched.
     * @property taskId the task the session belongs to, or null when it belongs to none.
     * @property tabId the tab the watch lies over, which is the active tab it was opened in.
     * @property title the session's name, for the bar.
     * @property sessionId the terminal session to mirror, or null when it cannot be found (the view
     *   then shows that there is nothing to mirror).
     * @property canShowAsTab whether the session runs in a background tab, which [showAsTab] turns
     *   into an ordinary one.
     */
    data class Watch(
        val recordId: String,
        val taskId: String?,
        val tabId: String,
        val title: String,
        val sessionId: String?,
        val canShowAsTab: Boolean,
    ) {
        /** The words on the overlay's header; they name ⌘↵ only while it does something. */
        val header: String get() = if (canShowAsTab) HEADER else HEADER_READ_ONLY
    }

    /**
     * A watch on [recordId], opened while tab [tabId] was active.
     *
     * @property recordId the session record watched.
     * @property tabId the tab that was active when the watch opened.
     */
    internal data class Scope(val recordId: String, val tabId: String)

    private val scope = MutableStateFlow<Scope?>(null)

    /**
     * The open watch, or null; emits whenever the sessions, tasks, layout or the watch itself
     * change. Collected by the view, which keeps one overlay in step with it.
     */
    val stateFlow: Flow<Watch?> = combine(
        windowState.agentSessions, windowState.agentTasks, windowState.config, scope,
    ) { sessions, tasks, config, used -> used to build(sessions, tasks, config, used) }
        .onEach { (used, built) -> forgetStale(used, built) }
        .map { (_, built) -> built }

    /**
     * The open watch right now, for key handlers that must act on what is on screen. Reading it
     * also forgets a watch that no longer shows.
     */
    val state: Watch?
        get() {
            val used = scope.value
            return buildNow(used).also { forgetStale(used, it) }
        }

    /**
     * Open the watch on a session, over the tab that is active now. Does nothing when the session
     * has ended or sits in no pane, so there is never an overlay with nothing behind it. Called
     * from an agent's line in the task panel, and by any other list of agents.
     *
     * @param recordId the session record to watch.
     * @see Watch
     */
    fun watch(recordId: String) {
        val tabId = windowState.config.value?.effectiveActiveTabId ?: return
        val opened = Scope(recordId, tabId)
        if (buildNow(opened) != null) scope.value = opened
    }

    /**
     * Show the watched session's background tab as an ordinary tab: it appears in the sidebar and
     * the tab strip and becomes the active tab of its world, and its pane gets focus. The session
     * keeps running and stays in its task, and nothing moves into the tab that was active. The
     * watch closes first, so the overlay never stays on top of the tab that has just appeared.
     * Does nothing when no watch is open or the session is not in a background tab. Called for ⌘↵
     * while the watch is open.
     *
     * @see showRecordAsTab
     * @see WindowCommand.TakeOverTab
     */
    suspend fun showAsTab() {
        val open = state ?: return
        if (!open.canShowAsTab) return
        close()
        showRecordAsTab(open.recordId)
    }

    /**
     * Show a session's background tab as an ordinary tab without opening a watch on it, so a
     * list of agents can offer the same key on a selected line and no overlay is ever drawn. Sends
     * [WindowCommand.TakeOverTab] and then [WindowCommand.SetFocusedPane] for the tab. Does nothing
     * when the session is not live in a background tab. Called by [showAsTab] and by lists of agents.
     *
     * @param recordId the session record whose tab is shown.
     * @see WindowCommand.TakeOverTab
     */
    suspend fun showRecordAsTab(recordId: String) {
        val session = windowState.agentSessions.value.firstOrNull { it.id == recordId } ?: return
        if (!session.background || session.state == ENDED) return
        val leafId = session.leafId ?: return
        val where = windowState.config.value?.let { TaskMembers.locate(it, leafId) } ?: return
        send(WindowCommand.TakeOverTab(where.tab.id))
        send(WindowCommand.SetFocusedPane(where.tab.id, where.leaf.id))
    }

    /** Close the watch. Called for esc. */
    fun close() {
        scope.value = null
    }

    /**
     * Drop the remembered watch when the build that used [used] shows none: another tab became
     * active or the session ended. Without this the watch would come back, and take every key, the
     * next time the person returned to the tab. Only [used] is dropped: a watch opened after that
     * build started is a newer scope and stays.
     *
     * Internal so a test can finish a build late, which a flow cannot be made to do on cue.
     *
     * @param used the scope the build was made from.
     * @param built what that build showed.
     */
    internal fun forgetStale(used: Scope?, built: Watch?) {
        if (built == null && used != null) scope.compareAndSet(used, null)
    }

    private fun buildNow(scope: Scope?): Watch? = build(
        windowState.agentSessions.value, windowState.agentTasks.value, windowState.config.value, scope,
    )

    private fun build(
        sessions: List<AgentSessionSummary>,
        tasks: List<AgentTask>,
        config: WindowConfig?,
        scope: Scope?,
    ): Watch? {
        if (scope == null || config == null) return null
        if (config.effectiveActiveTabId != scope.tabId) return null
        val session = sessions.firstOrNull { it.id == scope.recordId }?.takeIf { it.state != ENDED } ?: return null
        val where = session.leafId?.let { TaskMembers.locate(config, it) } ?: return null
        val taskId = session.taskId
            ?: tasks.firstOrNull { t -> t.leadRecordId == session.id || t.members.any { it.recordId == session.id } }?.id
        return Watch(
            recordId = session.id,
            taskId = taskId,
            tabId = scope.tabId,
            title = TaskMembers.titleOf(session),
            sessionId = where.leaf.sessionId,
            canShowAsTab = session.background,
        )
    }

    companion object {
        /** The overlay's header while ⌘↵ can show the session as a tab. */
        const val HEADER = "Watching · read-only · ⌘↵ show as tab"

        /** The overlay's header for a session that is not in a background tab, where ⌘↵ does nothing. */
        const val HEADER_READ_ONLY = "Watching · read-only"

        /** What a list of agents says about a line that ↵ watches. */
        const val LINE_HINT = "↵ watch · ⌘↵ show as tab"

        private const val ENDED = "ended"
    }
}
