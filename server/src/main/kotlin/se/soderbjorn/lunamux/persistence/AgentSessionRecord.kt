/**
 * Row type for the `agent_sessions` table: one record per run of an
 * agentic CLI (Claude Code, Codex, Gemini) inside a Lunamux pane.
 *
 * This file contains [AgentSessionRecord], the immutable value the
 * [se.soderbjorn.lunamux.AgentSessionTracker] keeps in memory and persists
 * through [SettingsRepository.saveAgentSession].
 *
 * @see SettingsRepository
 * @see se.soderbjorn.lunamux.AgentSessionTracker
 */
package se.soderbjorn.lunamux.persistence

/**
 * One agentic-CLI session as stored in `agent_sessions`.
 *
 * Created by the tracker when a pane first shows an agent or a hook reports
 * a new session, updated on every state change, and closed (state
 * `ended`) when the pane or process goes away. Read back at startup and by
 * the "resume" path.
 *
 * @property id Lunamux's own record id (stable across restarts; not the agent's id).
 * @property agent `claude`, `codex` or `gemini`.
 * @property agentSessionId the agent's own session id (Claude's `session_id`), if known.
 * @property source `hooks` when Claude Code hook events feed the record, `screen` when only the
 *   terminal is watched, `import` for a Claude Code transcript found on disk (history, found by search only).
 * @property leafId the layout leaf (pane) the agent runs in; leaf ids survive restarts.
 * @property terminalSessionId the live terminal session id while the pane is open
 *   (these change on every server start).
 * @property cwd working directory of the pane when the record opened.
 * @property repo top-level folder name of the git repository, or null outside one.
 * @property branch current branch at open time, or null.
 * @property worktreePath the worktree's root when the pane sits in a linked worktree,
 *   null in a main checkout.
 * @property title the agent's own title for the session, frozen once set.
 * @property firstPrompt the first prompt typed into the session (hooks only).
 * @property transcriptPath path of the agent's transcript file (hooks only).
 * @property links ticket keys (`ABC-12`) found in the branch name or title.
 * @property state `needs_you`, `working`, `done` or `ended`. `done` is a turn that finished: the agent
 *   waits at its prompt and nothing it asked is blocking it.
 * @property stateSince epoch millis when [state] last changed.
 * @property nowLine what the last tool call does in words ("Running the server tests"), or null.
 * @property question what the agent is asking when [state] is `needs_you`.
 * @property lastScreen the last 200 plain-text screen lines, written when the record ends.
 * @property createdAt epoch millis when the record opened.
 * @property endedAt epoch millis when it ended, or null while open.
 * @property repoRoot absolute path of the repository's main checkout (the parent of
 *   `--git-common-dir`), or null outside git and for records made before it was kept.
 *   Resume uses it to recreate a removed worktree.
 * @property lastSaid the first line of the last paragraph of the agent's most recent reply
 *   (at most 160 characters), or null before it has said anything. A member row shows it
 *   as the status line when the session has no tool line to show.
 * @property taskId the task an agent put this session in through the MCP tools, or null when it
 *   made no choice. A task file can still place a session that has none.
 * @property background whether the session runs (or ran) in a background tab an agent opened:
 *   out of sight, found by no search, but still counted in its task and still able to ask for
 *   the person. Stays set after the pane closes; taking the tab over clears it.
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
     * The conversation this record is a run of: the agent's own session id when it is known,
     * else this record's id.
     *
     * A resumed conversation has one record per run in a pane, but is one conversation; the
     * tracker publishes one summary per conversation by this key. Codex and Gemini report no id,
     * so each of their runs is its own conversation.
     *
     * Called by the tracker when it groups records and by the task index when it stores or
     * resolves a task's main conversation.
     */
    val conversationId: String get() = agentSessionId ?: id
}
