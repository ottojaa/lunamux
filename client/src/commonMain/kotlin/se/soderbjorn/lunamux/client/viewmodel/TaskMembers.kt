/**
 * The rules that decide who a task's members are and what they are called, in one place so the
 * agents overview and the task panel always agree: which hosted sessions join a task's file
 * members, what a session is called, what a member made from a bare session says, and where a
 * pane sits in the layout.
 *
 * Contains [TaskMembers] and its [TaskMembers.PanePlace].
 *
 * @see AgentsOverviewBackingViewModel
 * @see TaskPanelViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowConfig

/**
 * Pure helpers over a task, its sessions and the window layout. Called by
 * [AgentsOverviewBackingViewModel] to build a task's rows and by [TaskPanelViewModel] to build the
 * agents block; neither keeps its own copy of these rules.
 */
internal object TaskMembers {
    private const val NEEDS_YOU = "needs_you"

    /**
     * Where a pane sits.
     *
     * @property worldId the world holding the tab, or null in a layout without worlds.
     * @property tab the tab holding the pane.
     * @property leaf the pane's leaf.
     */
    data class PanePlace(val worldId: String?, val tab: TabConfig, val leaf: LeafNode)

    /**
     * The hosted sessions of [task] that no member of its file claims, in the order they started.
     * These join the file's members as members of their own, so an agent that split its work
     * across a second session shows up without the file listing it.
     *
     * @param task the task.
     * @param byId every session summary by record id; a file member's `recordId` is looked up here.
     * @param hosted the sessions whose [AgentSessionSummary.taskId] is the task's id.
     * @return the unclaimed sessions, oldest first, ties broken by id.
     */
    fun unlistedSessions(
        task: AgentTask,
        byId: Map<String, AgentSessionSummary>,
        hosted: List<AgentSessionSummary>,
    ): List<AgentSessionSummary> {
        val matched = task.members.mapNotNullTo(HashSet()) { m -> m.recordId?.let(byId::get)?.id }
        return hosted.filter { it.id !in matched }.sortedWith(compareBy({ startedOf(it) }, { it.id }))
    }

    /**
     * A task member made from a hosted session no task file lists: its own title, state, question
     * or last tool line and age.
     *
     * @param s the hosted session.
     * @return the member, keyed by the record id.
     */
    fun memberOf(s: AgentSessionSummary): AgentTaskMember = AgentTaskMember(
        key = s.id,
        title = titleOf(s),
        links = s.links,
        state = s.state,
        line = if (s.state == NEEDS_YOU) s.question ?: s.nowLine else s.nowLine,
        sinceMillis = s.stateSince,
        recordId = s.id,
    )

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
