/**
 * The check that runs before a person closes a tab with an agent working in it.
 *
 * Closing a tab ends every terminal in it, so a conversation that is still working stops with it.
 * [TabCloseGuard.check] says whether that would happen and, if so, what the person should be told:
 * which agent stops, which agents the closed tab's conversation started that keep running in
 * background tabs, and how to pick the work up again. The screens draw the question; this file
 * holds the facts and the exact words, so every screen asks the same thing.
 *
 * The check is for closes a person starts (the tab's × in the strip, the tab list's close). An
 * agent closing a tab through MCP does not ask, and closing a single pane is not checked.
 *
 * @see TabCloseCheck
 * @see TabCloseGuard
 * @see closeTabAsking
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig

/**
 * One agent that keeps running after the tab is closed: a live session in a background tab that
 * belongs to a task whose own session is in the closing tab.
 *
 * @property title the session's title, as the agents overview shows it.
 * @property state `needs_you`, `working` or `idle`.
 * @property sinceMillis epoch millis the session entered [state], for "working, 4 min".
 */
data class KeepsRunning(val title: String, val state: String, val sinceMillis: Long)

/**
 * What the close question says: the tab, the agent that is working in it, the agents that keep
 * running, and the task to open again to pick the conversation up.
 *
 * Built by [TabCloseGuard.check] and read by the screen that asks.
 *
 * @property tabTitle the closing tab's title.
 * @property agentName the display name of the working agent (`Claude`, `Codex`, `Gemini`).
 * @property keepsRunning the live background sessions that stay up, in the order the tasks list them;
 *   empty when none does.
 * @property taskTitle the title of a task whose own session is in this tab, or null when the tab
 *   holds none.
 * @see TabCloseGuard.check
 */
data class TabCloseCheck(
    val tabTitle: String,
    val agentName: String,
    val keepsRunning: List<KeepsRunning> = emptyList(),
    val taskTitle: String? = null,
) {
    /** The question: Close "Release notes"? */
    val headline: String get() = "Close \"$tabTitle\"?"

    /** What closing does to the working agent. */
    val warning: String get() = "$agentName is still working in this tab. Closing it stops this conversation."

    /**
     * The sentence that introduces the list of agents that keep running, or null when none does.
     * One agent reads "The agent it started keeps running in the background:".
     */
    val keepsRunningIntro: String?
        get() = when (keepsRunning.size) {
            0 -> null
            1 -> "The agent it started keeps running in the background:"
            else -> "The ${keepsRunning.size} agents it started keep running in the background:"
        }

    /**
     * How to get the closed conversation back, or null when this tab holds no task's own session.
     */
    val pickUp: String?
        get() = taskTitle?.let { "To pick up where you left off, open the agents overview and choose \"$it\"." }
}

/**
 * Decides whether closing a tab needs the person's say-so.
 *
 * Used by [closeTabAsking], which every person-initiated tab close goes through.
 *
 * @see TabCloseCheck
 */
object TabCloseGuard {
    private const val WORKING = "working"
    private const val ENDED = "ended"

    /**
     * Whether closing [tabId] would stop an agent that is working, and what to tell the person.
     *
     * Null unless a pane in the tab has a working session. The agents that keep running are the
     * live background sessions of every task whose own session sits in this tab (members the task
     * file lists and sessions an agent put in the task alike), not counting the tab's own.
     *
     * @param tabId the tab about to close.
     * @param config the current layout; the tab is looked up in every world.
     * @param sessions the agent sessions the server reports.
     * @param tasks the tasks the server reports.
     * @return the facts to ask with, or null when closing loses nothing that is working.
     */
    fun check(
        tabId: String,
        config: WindowConfig?,
        sessions: List<AgentSessionSummary>,
        tasks: List<AgentTask>,
    ): TabCloseCheck? {
        val cfg = config ?: return null
        val tab = allTabs(cfg).firstOrNull { it.id == tabId } ?: return null
        val leaves = tab.panes.map { it.leaf.id }.toSet()
        val inTab = sessions.filter { it.state != ENDED && it.leafId in leaves }
        val working = inTab.firstOrNull { it.state == WORKING } ?: return null

        val byId = sessions.associateBy { it.id }
        val ownTasks = tasks.filter { t -> t.leadRecordId?.let(byId::get)?.let { it.state != ENDED && it.leafId in leaves } == true }
        val livePanes = allTabs(cfg).flatMap { t -> t.panes.map { it.leaf.id } }.toSet()
        val keeps = ownTasks.flatMap { t ->
            val memberIds = t.members.mapNotNull { it.recordId }.toSet()
            sessions.filter { s -> s.id in memberIds || s.taskId == t.id }
        }.distinctBy { it.id }.filter {
            it.state != ENDED && it.background && it.leafId != null && it.leafId in livePanes && it.leafId !in leaves
        }
        return TabCloseCheck(
            tabTitle = tab.title,
            agentName = agentName(working.agent),
            keepsRunning = keeps.map { KeepsRunning(titleOf(it), it.state, it.stateSince) },
            taskTitle = ownTasks.firstOrNull()?.title,
        )
    }

    private fun allTabs(config: WindowConfig): List<TabConfig> =
        (config.tabs + config.worlds.flatMap { it.tabs }).distinctBy { it.id }

    private fun agentName(agent: String): String = agent.replaceFirstChar { it.uppercase() }

    private fun titleOf(s: AgentSessionSummary): String =
        s.title?.takeIf { it.isNotBlank() }
            ?: s.firstPrompt?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(60)
            ?: agentName(s.agent)
}

/**
 * Close [tabId] after asking, when the check says a working agent would stop.
 *
 * A tab with nothing working in it closes at once. Otherwise [ask] shows the question and the
 * tab closes only if it answers true. Called by every screen that closes a tab for a person:
 * the web tab strip and the 3D view, and any screen that wants the same question.
 *
 * @param windowState where the layout, sessions and tasks are read from.
 * @param tabId the tab to close.
 * @param send sends a command to the server (the live window connection's send).
 * @param ask shows [TabCloseCheck] and returns whether the person chose to close.
 * @return true when the close was sent, false when the person kept the tab.
 * @see TabCloseGuard.check
 */
suspend fun closeTabAsking(
    windowState: WindowStateRepository,
    tabId: String,
    send: suspend (WindowCommand) -> Unit,
    ask: suspend (TabCloseCheck) -> Boolean,
): Boolean {
    val check = TabCloseGuard.check(
        tabId, windowState.config.value, windowState.agentSessions.value, windowState.agentTasks.value,
    )
    if (check != null && !ask(check)) return false
    send(WindowCommand.CloseTab(tabId = tabId))
    return true
}
