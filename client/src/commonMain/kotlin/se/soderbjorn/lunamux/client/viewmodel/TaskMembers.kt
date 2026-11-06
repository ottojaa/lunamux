/**
 * The rules for naming a session and finding its pane, in one place so the agents overview, the
 * task panel and the counts always agree: what a session is called, what folder it runs in, when
 * it started, and where a pane sits in the layout.
 *
 * Contains [TaskMembers] and its [TaskMembers.PanePlace].
 *
 * @see AgentsOverviewBackingViewModel
 * @see TaskPanelViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowConfig

/**
 * Pure helpers over a session and the window layout. Called by [AgentsOverviewBackingViewModel] to
 * build a task's rows, by [TaskPanelViewModel] to build the agents block and by [AgentCounts] to
 * find a pane's world; none keeps its own copy of these rules.
 */
internal object TaskMembers {
    /**
     * Where a pane sits.
     *
     * @property worldId the world holding the tab, or null in a layout without worlds.
     * @property tab the tab holding the pane.
     * @property leaf the pane's leaf.
     */
    data class PanePlace(val worldId: String?, val tab: TabConfig, val leaf: LeafNode)

    /**
     * A session's name: its own title when it says what the session is for, else the start of its
     * first prompt, else the agent's name. The branch, the folder, `~`, `HEAD` and run ids say
     * where a session ran, not what it is for, so they are never used as a title.
     *
     * @param s the session.
     * @return the title, never blank.
     * @see AgentSessionText.isMeaningfulTitle
     * @see AgentSessionText.titleFromPrompt
     */
    fun titleOf(s: AgentSessionSummary): String {
        s.title?.trim()?.takeIf { AgentSessionText.isMeaningfulTitle(it, s.branch, s.repo, folderOf(s)) }?.let { return it }
        return AgentSessionText.titleFromPrompt(s.firstPrompt)
            ?: "${s.agent.replaceFirstChar { it.uppercase() }} session"
    }

    /**
     * The folder a session runs in as a short label: "~" for the user's home folder, else the last
     * path segment, else null. Home is named "~" so a chat started there is not shown under the
     * user's name.
     *
     * @param s the session.
     * @return the label, or null when the session has no folder.
     */
    fun folderOf(s: AgentSessionSummary): String? =
        if (s.cwdHome) "~"
        else s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /**
     * When a session started: the time its record opened, which never changes; a server that does
     * not send it leaves the time the session entered its current state.
     *
     * @param s the session.
     * @return epoch millis.
     */
    fun startedOf(s: AgentSessionSummary): Long = s.startedAt ?: s.stateSince

    /**
     * Find a pane in the layout, across every world.
     *
     * @param config the window layout.
     * @param leafId the pane's id.
     * @return where it sits, or null when no tab holds it.
     */
    fun locate(config: WindowConfig, leafId: String): PanePlace? {
        fun find(worldId: String?, tabs: List<TabConfig>): PanePlace? {
            for (tab in tabs) for (pane in tab.panes) if (pane.leaf.id == leafId) return PanePlace(worldId, tab, pane.leaf)
            return null
        }
        if (config.worlds.isEmpty()) return find(null, config.tabs)
        for (w in config.worlds) find(w.id, w.tabs)?.let { return it }
        return null
    }
}
