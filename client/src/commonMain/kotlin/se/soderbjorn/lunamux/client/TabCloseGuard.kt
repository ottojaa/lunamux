/**
 * The check before a person closes a tab with an agent working in it.
 *
 * Closing a tab ends every terminal in it, so a conversation that is still working stops with it.
 * [TabCloseGuard.check] says whether that would happen and what the person should be told: what
 * the agent is doing, which agents the tab's conversation started that keep running in background
 * tabs, and how to pick the work up again. The screens draw the question; this file holds the facts
 * and the words, so every screen that asks says the same thing. Only closes a person starts ask:
 * an agent closing a tab through MCP does not, and neither does closing a single pane.
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.viewmodel.AgentFacts
import se.soderbjorn.lunamux.client.viewmodel.Layout

/** An agent that keeps running after the tab closes: a live background session of a task whose own session is in the tab. */
data class KeepsRunning(val title: String, val state: String, val sinceMillis: Long)

/**
 * What the close question says. [doing] is what the working agent is doing right now, when it
 * says; [taskTitle] is a task whose own session is in this tab, to pick up again from the overview.
 */
data class TabCloseCheck(
    val tabTitle: String,
    val agentName: String,
    val keepsRunning: List<KeepsRunning> = emptyList(),
    val taskTitle: String? = null,
    val doing: String? = null,
) {
    val headline: String get() = "Close \"$tabTitle\"?"

    /** What closing does to the working agent, including what it is doing. */
    val warning: String
        get() = "$agentName is still working in this tab" + (doing?.let { ": ${it.trimEnd('.')}" } ?: "") + ". Closing it stops this conversation."

    val keepsRunningIntro: String?
        get() = when (keepsRunning.size) {
            0 -> null
            1 -> "The agent it started keeps running in the background:"
            else -> "The ${keepsRunning.size} agents it started keep running in the background:"
        }

    val pickUp: String?
        get() = taskTitle?.let { "To pick up where you left off, open the agents overview and choose \"$it\"." }
}

/** Decides whether closing a tab needs the person's say-so. The Android screens do not ask yet. */
object TabCloseGuard {
    /**
     * What to tell the person about closing [tabId], or null unless a pane in it has a working
     * session. The agents that keep running are the live background sessions of every task whose own
     * session sits in this tab.
     */
    fun check(
        tabId: String,
        config: WindowConfig?,
        sessions: List<AgentSessionSummary>,
        tasks: List<AgentTask>,
    ): TabCloseCheck? {
        val layout = Layout(config)
        val panes = layout.panesIn(tabId)
        val tab = panes.firstOrNull()?.tab ?: return null
        val leaves = panes.map { it.leaf.id }.toSet()
        val working = sessions.firstOrNull { AgentFacts.isWorking(it) && it.leafId in leaves } ?: return null

        val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
        val ownTasks = tasks.filter { t -> t.mainConversation?.let(byKey::get)?.let { AgentFacts.isLive(it) && it.leafId in leaves } == true }
        val ownIds = ownTasks.mapTo(HashSet()) { it.id }
        val keeps = sessions.filter {
            it.taskId in ownIds && AgentFacts.isLive(it) && it.background && layout.spotOf(it) != null && it.leafId !in leaves
        }
        return TabCloseCheck(
            tabTitle = tab.title,
            agentName = working.agent.replaceFirstChar { it.uppercase() },
            keepsRunning = keeps.map { KeepsRunning(AgentFacts.titleOf(it), it.state, it.stateSince) },
            taskTitle = ownTasks.firstOrNull()?.title,
            doing = working.nowLine?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/**
 * Closes [tabId] after asking, when the check says a working agent would stop: [ask] shows the
 * question and the tab closes only if it answers true. Returns whether the close was sent.
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
