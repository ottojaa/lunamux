/**
 * The agents overview's one push: every conversation, every task and whether Claude Code's hooks
 * are connected, as a single frame that is the same for every connection.
 *
 * This file contains [buildAgentsFrame], the pure function that turns one snapshot into the
 * frame and makes sure nothing in it points outside it, and [AgentsFeed], which keeps the encoded
 * frame current and shares it. The window routes send [AgentsFeed.frame] to every connection when
 * it changes; nothing is computed per connection.
 *
 * @see WindowEnvelope.Agents
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Make the frame for one snapshot of the three facts it carries.
 *
 * The tracker's summaries and the [TaskIndex]'s tasks are published by two owners, so a snapshot
 * can catch one a moment ahead of the other. This repairs what that could break: a conversation
 * whose task is not in the frame belongs to no task, and a task whose main conversation is not in
 * the frame has none. Everything else is passed through untouched.
 *
 * @param summaries every conversation row.
 * @param tasks the tasks, with the ids of all that exist.
 * @param claudeConnected whether Claude Code's hooks point at Lunamux.
 * @return the frame, in which every `taskId` and every `main` resolves.
 */
fun buildAgentsFrame(
    summaries: List<AgentSessionSummary>,
    tasks: TaskState,
    claudeConnected: Boolean,
): WindowEnvelope.Agents {
    val taskIds = tasks.tasks.mapTo(HashSet()) { it.id }
    val sessions = summaries.map { if (it.taskId != null && it.taskId !in taskIds) it.copy(taskId = null) else it }
    val sessionIds = sessions.mapTo(HashSet()) { it.id }
    val fixedTasks = tasks.tasks.map { if (it.main != null && it.main !in sessionIds) it.copy(main = null) else it }
    return WindowEnvelope.Agents(sessions, fixedTasks, claudeConnected)
}

/**
 * Keeps the agents frame current, encoded once per change.
 *
 * @param scope where the collector runs; it lives as long as the server.
 * @param summaries the tracker's published rows.
 * @param tasks the task index's published state.
 * @param claudeConnected the hooks' connected flag.
 */
class AgentsFeed(
    scope: CoroutineScope,
    summaries: StateFlow<List<AgentSessionSummary>>,
    tasks: StateFlow<TaskState>,
    claudeConnected: StateFlow<Boolean>,
) {
    private fun encode(s: List<AgentSessionSummary>, t: TaskState, c: Boolean): String =
        windowJson.encodeToString<WindowEnvelope>(buildAgentsFrame(s, t, c))

    /**
     * The encoded frame, current. A connection sends its value on connect and then every change;
     * equal frames are not published twice, so all connections send the same bytes.
     */
    val frame: StateFlow<String> = combine(summaries, tasks, claudeConnected, ::encode)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, encode(summaries.value, tasks.value, claudeConnected.value))
}
