/**
 * What the client knows about agent sessions, in one place: what a state means, a session's title,
 * folder and age, where its pane sits in the layout, and how to go there.
 *
 * The agents overview, the task panel, the sidebar footer, the 3D view and the tab-close question
 * all read these, so they cannot disagree. A session is alive when the server says it has not
 * ended; the layout is consulted only to jump to a session, to name its tab, and to split the
 * footer's counts by world.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.isListed

object AgentFacts {
    const val NEEDS_YOU = "needs_you"
    const val WORKING = "working"
    const val DONE = "done"
    const val ENDED = "ended"

    /** Alive means the server has not ended it; a pane the layout cannot place does not change that. */
    fun isLive(s: AgentSessionSummary): Boolean = s.state != ENDED

    fun isWaiting(s: AgentSessionSummary): Boolean = s.state == NEEDS_YOU

    fun isWorking(s: AgentSessionSummary): Boolean = s.state == WORKING

    /** When the conversation began its work; falls back to its last state change. */
    fun startedOf(s: AgentSessionSummary): Long = s.startedAt ?: s.stateSince

    /** The last time the conversation did anything, or when it ended. */
    fun activityOf(s: AgentSessionSummary): Long =
        if (s.state == ENDED) s.endedAt ?: s.stateSince else s.lastActivityAt ?: s.stateSince

    /** The name shown for a session: its title when that says something, else its first prompt. */
    fun titleOf(s: AgentSessionSummary): String {
        s.title?.trim()?.takeIf { AgentSessionText.isMeaningfulTitle(it, s.branch, s.repo, folderOf(s)) }?.let { return it }
        return AgentSessionText.titleFromPrompt(s.firstPrompt) ?: "${s.agent.replaceFirstChar { it.uppercase() }} session"
    }

    /** The last segment of the session's folder, or `~` for the home folder. */
    fun folderOf(s: AgentSessionSummary): String? =
        if (s.cwdHome) "~" else s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** The question when the agent asks, else the line it shows while working; blank is null. */
    fun lineOf(s: AgentSessionSummary): String? {
        val now = s.nowLine?.trim()?.takeIf { it.isNotEmpty() }
        return if (s.state == NEEDS_YOU) s.question?.trim()?.takeIf { it.isNotEmpty() } ?: now else now
    }

    /** "now", "4m", "3h" or "2d". */
    fun ageText(sinceMillis: Long, nowMillis: Long): String {
        val seconds = ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h"
            else -> "${seconds / 86_400}d"
        }
    }

    /** Waiting and working conversations: all of them, and per world (see [Layout.worldOf]). */
    data class Tally(val total: Counts, val byWorld: Map<String, Counts>)

    data class Counts(val waiting: Int = 0, val working: Int = 0)

    /**
     * Counts the sessions the server says wait or work. A session the layout cannot place counts
     * under the active world, so the world lines always add up to the total.
     */
    fun tally(sessions: List<AgentSessionSummary>, layout: Layout): Tally {
        var total = Counts()
        val byWorld = LinkedHashMap<String, Counts>()
        for (s in sessions) {
            val w = if (isWaiting(s)) 1 else 0
            val m = if (isWorking(s)) 1 else 0
            if (w + m == 0) continue
            total = Counts(total.waiting + w, total.working + m)
            val world = layout.worldOf(s)
            val prev = byWorld[world] ?: Counts()
            byWorld[world] = Counts(prev.waiting + w, prev.working + m)
        }
        return Tally(total, byWorld)
    }
}

/** Where one pane sits: its world (null without worlds), its tab, and the tab's number in the strip. */
data class PaneSpot(
    val worldId: String?,
    val worldName: String?,
    val tab: TabConfig,
    val leaf: LeafNode,
    val tabNumber: Int?,
) {
    /** Background tabs carry no number: they are not in the strip. */
    val listed: Boolean get() = tab.isListed
}

/** The layout as an index from pane to place, built once per layout so lookups stay cheap. */
class Layout(val config: WindowConfig?) {
    private val spots = HashMap<String, PaneSpot>()

    init {
        fun add(worldId: String?, worldName: String?, tabs: List<TabConfig>) {
            var number = 0
            for (tab in tabs) {
                val n = if (tab.isListed) ++number else null
                for (pane in tab.panes) spots[pane.leaf.id] = PaneSpot(worldId, worldName, tab, pane.leaf, n)
            }
        }
        if (config != null) {
            if (config.worlds.isEmpty()) add(null, null, config.tabs) else config.worlds.forEach { add(it.id, it.name, it.tabs) }
        }
    }

    fun spotOf(s: AgentSessionSummary): PaneSpot? = s.leafId?.let(spots::get)

    /** The panes of one tab; empty when the layout has no such tab or it holds no pane. */
    fun panesIn(tabId: String): List<PaneSpot> = spots.values.filter { it.tab.id == tabId }

    /** The world a session counts under: its own, else the active one, else "". */
    fun worldOf(s: AgentSessionSummary): String = spotOf(s)?.worldId ?: config?.activeWorldId ?: ""

    /** The commands that bring a session's pane to the front, in order; empty when it cannot be placed. */
    fun jumpCommands(s: AgentSessionSummary): List<WindowCommand> {
        val spot = spotOf(s) ?: return emptyList()
        return listOfNotNull(
            spot.worldId?.takeIf { it != config?.activeWorldId }?.let { WindowCommand.SetActiveWorld(it) },
            WindowCommand.SetActiveTab(spot.tab.id),
            WindowCommand.SetFocusedPane(spot.tab.id, spot.leaf.id),
        )
    }
}
