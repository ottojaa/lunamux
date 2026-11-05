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
import se.soderbjorn.lunamux.AgentTask

/**
 * Counts waiting and working conversations, background ones included.
 *
 * Called by [AgentsOverviewBackingViewModel] for its [AgentsOverviewBackingViewModel.State] and by
 * the web footer (and its 3D twin) through [AgentCounts.of], so no screen keeps its own copy of
 * the rule.
 */
object AgentCounts {
    private const val ENDED = "ended"
    private const val NEEDS_YOU = "needs_you"
    private const val WORKING = "working"

    /**
     * How many conversations wait for the person and how many are moving.
     *
     * @property waiting conversations that wait for the person.
     * @property working conversations that work and are moving (neither quiet nor stale).
     */
    data class Counts(val waiting: Int, val working: Int)

    /**
     * Count the conversations in [sessions], one per summary (the server sends one per conversation),
     * and the members of [tasks] that Lunamux does not host.
     *
     * A conversation counts while it is not ended, in a visible tab or a background one. A member a
     * task file lists but Lunamux does not host counts only while its task's main conversation is
     * open: before that the question is not the person's to answer, since only resuming the
     * conversation asks it.
     *
     * @param sessions the conversation summaries the server pushed.
     * @param tasks the tasks the server pushed.
     * @param nowMillis the current epoch millis, which decides who has gone quiet.
     * @return the two counts.
     * @see AgentsOverviewBackingViewModel.isMoving
     */
    fun of(sessions: List<AgentSessionSummary>, tasks: List<AgentTask>, nowMillis: Long): Counts {
        val open = sessions.filter { it.state != ENDED }
        var waiting = open.count { it.state == NEEDS_YOU }
        var working = open.count {
            AgentsOverviewBackingViewModel.isMoving(it.state == WORKING, null, it.lastActivityAt ?: it.stateSince, nowMillis)
        }
        val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
        for (task in tasks) {
            val mainOpen = task.mainConversation?.let(byKey::get)?.let { it.state != ENDED } == true
            if (!mainOpen) continue
            for (member in task.members) {
                if (member.recordId?.let(byKey::get) != null) continue
                if (member.state == NEEDS_YOU) waiting++
                if (AgentsOverviewBackingViewModel.isMoving(member.state == WORKING, task.refreshedAtMillis, task.refreshedAtMillis, nowMillis)) working++
            }
        }
        return Counts(waiting, working)
    }
}
