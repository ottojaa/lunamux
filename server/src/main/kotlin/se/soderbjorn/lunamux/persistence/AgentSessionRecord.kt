/**
 * Row type for the `agent_sessions` table: one record per run of an agentic CLI (Claude Code,
 * Codex, Gemini) inside a Lunamux pane.
 *
 * This file contains [AgentSessionRecord], the immutable value the
 * [se.soderbjorn.lunamux.AgentSessionTracker] keeps in memory and persists through
 * [SettingsRepository.saveAgentSession].
 */
package se.soderbjorn.lunamux.persistence

/** One agentic-CLI session as stored in `agent_sessions`. */
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
    /** Why the record ended: `restart` (Lunamux stopped under it), `exit`, `closed` (its pane went) or `replaced`; null while open and on older rows. */
    val endReason: String? = null,
    /** The state it was in when it ended. */
    val endedFrom: String? = null,
    /** For a helper (a `claude` started inside another conversation): the conversation it ran inside. */
    val host: String? = null,
) {
    /**
     * The conversation this record is a run of: the agent's own session id when it is known, else
     * this record's id.
     */
    val conversationId: String get() = agentSessionId ?: id
}
