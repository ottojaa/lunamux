/**
 * The agents overview's one push: every conversation, every task and whether Claude Code's hooks
 * are connected, as a single frame that is the same for every connection.
 *
 * This file contains [AgentsFeed], which keeps the encoded frame current and shares it. The rows and
 * the tasks come from one [TaskState], which [TaskIndex] derives from a single read of the tracker's
 * summaries, so a frame never pairs rows with tasks from a different moment. The window routes send
 * [AgentsFeed.frame] to every connection when it changes; nothing is computed per connection.
 *
 * @see WindowEnvelope.Agents
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps the agents frame current, encoded once per change.
 *
 * @param scope where the collector runs; it lives as long as the server.
 * @param tasks the task index's published state: the rows and the tasks derived from them.
 * @param claudeConnected the hooks' connected flag.
 */
class AgentsFeed(
    scope: CoroutineScope,
    tasks: StateFlow<TaskState>,
    claudeConnected: StateFlow<Boolean>,
) {
    private fun encode(t: TaskState, c: Boolean): String =
        windowJson.encodeToString<WindowEnvelope>(WindowEnvelope.Agents(t.sessions, t.tasks, c))

    /**
     * The encoded frame, current. A connection sends its value on connect and then every change;
     * equal frames are not published twice, so all connections send the same bytes.
     */
    val frame: StateFlow<String> = combine(tasks, claudeConnected, ::encode)
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, encode(tasks.value, claudeConnected.value))
}
