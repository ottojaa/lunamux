/**
 * The one place that counts the conversations waiting for the person and the ones working, so the
 * footer under the panes, its 3D twin and the overview's rows all show the same numbers.
 *
 * Contains [AgentCounts].
 *
 * @see AgentsOverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.WindowConfig

/**
 * Counts waiting and working conversations, background ones included, that sit in a pane.
 *
 * Called by [AgentsOverviewBackingViewModel] for its [AgentsOverviewBackingViewModel.State] and its
 * rows, by the checks in [OverviewChecks], and by the web footer (and its 3D twin) through
 * [AgentCounts.of], so no screen keeps its own copy of the rule.
 */
object AgentCounts {
    private const val ENDED = "ended"
    private const val NEEDS_YOU = "needs_you"
    private const val WORKING = "working"

    /**
     * How many conversations wait for the person and how many are working.
     *
     * @property waiting conversations that wait for the person.
     * @property working conversations that work.
     */
    data class Counts(val waiting: Int, val working: Int)

    /**
     * The counts of one layout: the total, and the same conversations split by the world their pane
     * sits in.
     *
     * @property total every waiting and working conversation that sits in a pane.
     * @property byWorld [total] split by world id, only for worlds with at least one waiting or
     *   working conversation; a layout without worlds uses the key `""`. The counts add up to
     *   [total].
     */
    data class Tally(val total: Counts, val byWorld: Map<String, Counts>)

    /**
     * Where a conversation's pane sits, or null when it has none: an ended conversation has no
     * pane, and neither has one whose pane the layout does not hold.
     */
    private fun placeOf(s: AgentSessionSummary, config: WindowConfig?): TaskMembers.PanePlace? {
        if (s.state == ENDED || config == null) return null
        return s.leafId?.let { TaskMembers.locate(config, it) }
    }

    /**
     * Whether [s] is one of the conversations that wait for the person: it asks, and it sits in a
     * pane. Background tabs hold panes too, so a background agent that waits counts.
     *
     * Called by [of] and by the overview's rows, so a row says "needs you" for exactly the
     * conversations the footer counts.
     *
     * @param s the conversation.
     * @param config the window layout, or null before it has arrived.
     * @return true when the conversation counts as waiting.
     */
    fun isWaiting(s: AgentSessionSummary, config: WindowConfig?): Boolean =
        waits(s) && placeOf(s, config) != null

    /** Whether [s] asks, wherever its pane is: the one rule behind [isWaiting] and [of]. */
    private fun waits(s: AgentSessionSummary): Boolean = s.state == NEEDS_YOU

    /**
     * Whether [s] works: the one rule behind [isWorking] and [of]. A conversation works until a hook
     * or the screen says otherwise, however long it has been since it last said anything (a long
     * build is minutes of silence), so no clock takes part.
     */
    private fun works(s: AgentSessionSummary): Boolean = s.state == WORKING

    /**
     * Whether [s] is one of the conversations that work: it works and it sits in a pane.
     *
     * Called by [of] and by the overview's rows, for the same reason as [isWaiting].
     *
     * @param s the conversation.
     * @param config the window layout, or null before it has arrived.
     * @return true when the conversation counts as working.
     */
    fun isWorking(s: AgentSessionSummary, config: WindowConfig?): Boolean =
        works(s) && placeOf(s, config) != null

    /**
     * Count the conversations in [sessions] that wait or work, in one pass: the total and the lines
     * of the worlds come from the same conversations, so the lines always add up to the total.
     *
     * Only a conversation with a pane in [config] counts, one per summary (the server sends one per
     * conversation), whichever tab the pane is in and whichever task the conversation belongs to.
     *
     * @param sessions the conversation summaries the server pushed.
     * @param config the window layout that says where each pane sits, or null before it has arrived.
     * @return the total and the per-world counts.
     */
    fun of(sessions: List<AgentSessionSummary>, config: WindowConfig?): Tally {
        var waiting = 0
        var working = 0
        val byWorld = LinkedHashMap<String, Counts>()
        for (s in sessions) {
            val place = placeOf(s, config) ?: continue
            val w = if (waits(s)) 1 else 0
            val m = if (works(s)) 1 else 0
            if (w == 0 && m == 0) continue
            waiting += w
            working += m
            val world = place.worldId ?: ""
            val prev = byWorld[world] ?: Counts(0, 0)
            byWorld[world] = Counts(prev.waiting + w, prev.working + m)
        }
        return Tally(Counts(waiting, working), byWorld)
    }
}
