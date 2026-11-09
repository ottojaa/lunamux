/**
 * Row type for the `agent_sessions` table: one record per run of an agentic CLI (Claude Code,
 * Codex, Gemini) inside a Lunamux pane.
 *
 * This file contains [AgentSessionRecord], the immutable value the
 * [se.soderbjorn.lunamux.AgentSessionTracker] keeps in memory and persists through
 * [SettingsRepository.saveAgentSession].
 */
package se.soderbjorn.lunamux.persistence

/**
 * One agentic-CLI session as stored in `agent_sessions`.
 *
 * Read back at startup and by the "resume" path.
 */
data class AgentSessionRecord(
    val id: String,
    val agent: String,
    val agentSessionId: String? = null,
    val source: String = "screen",
    val leafId: String? = null,
    val terminalSessionId: String? = null,
    val cwd: String? = null,
    val repo: String? = null,
    val branch: String? = null,
    val worktreePath: String? = null,
    val title: String? = null,
    val firstPrompt: String? = null,
    val transcriptPath: String? = null,
    val links: List<String> = emptyList(),
    val state: String = "done",
    val stateSince: Long = 0,
    val nowLine: String? = null,
    val question: String? = null,
    val lastScreen: String? = null,
    val createdAt: Long = 0,
    val endedAt: Long? = null,
    val repoRoot: String? = null,
    val lastSaid: String? = null,
    val taskId: String? = null,
    val background: Boolean = false,
) {
    /**
     * The conversation this record is a run of: the agent's own session id when it is known, else
     * this record's id.
     *
     * A resumed conversation has one record per run in a pane, but is one conversation; the tracker
     * publishes one summary per conversation by this key. Codex and Gemini report no id, so each of
     * their runs is its own conversation.
     */
    val conversationId: String get() = agentSessionId ?: id
}
