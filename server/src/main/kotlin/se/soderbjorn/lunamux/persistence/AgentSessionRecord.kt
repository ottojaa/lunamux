/**
 * Row type for the `agent_sessions` table: one record per agentic-CLI conversation (Claude Code,
 * Codex, Gemini) that ran inside a Lunamux pane.
 *
 * This file contains [AgentSessionRecord], the immutable value the
 * [se.soderbjorn.lunamux.AgentSessionTracker] keeps in memory and persists through
 * [SettingsRepository.saveAgentSession], and [AgentSessionState], the words its `state` takes.
 */
package se.soderbjorn.lunamux.persistence

/** The values of [AgentSessionRecord.state]. */
object AgentSessionState {
    /** The agent asks the person something. */
    const val NEEDS_YOU = "needs_you"

    /** A turn is running. */
    const val WORKING = "working"

    /** The agent sits at its prompt. */
    const val DONE = "done"

    /** The record is closed; [AgentSessionRecord.endReason] says why. */
    const val ENDED = "ended"
}

/**
 * One agentic-CLI conversation as stored in `agent_sessions`. A Claude conversation has one record
 * however many panes it ran in: resuming reopens the record it ended.
 *
 * @property id Lunamux's id for the record.
 * @property agent `claude`, `codex` or `gemini`.
 * @property agentSessionId the agent's own conversation id; null until a hook names it, and always
 *   null for Codex and Gemini.
 * @property source `hooks` (Claude's hook events feed the state), `screen` (the terminal's screen
 *   does) or `import` (history read from Claude's own files, found only by search).
 * @property leafId the layout pane it runs (or last ran) in; survives restarts.
 * @property terminalSessionId the live terminal behind that pane; null for imported history.
 * @property cwd the folder the agent runs in.
 * @property repo the git repository's name, when [cwd] is in one.
 * @property branch the branch checked out when it began.
 * @property title the pane's name or the program's title; null until either is known.
 * @property firstPrompt the first thing the person asked.
 * @property transcriptPath Claude's transcript file for the conversation.
 * @property state one of [AgentSessionState].
 * @property stateSince when [state] last changed.
 * @property nowLine memory only: what a running turn is doing, in words.
 * @property question memory only: what a `needs_you` conversation asks.
 * @property lastScreen the last lines of the pane's screen, saved when the record closed.
 * @property createdAt when the record opened.
 * @property endedAt when it closed; null while open.
 * @property lastSaid what the last finished reply came to, in one sentence.
 * @property taskId the task it belongs to.
 * @property background whether its pane is in a background tab.
 * @property endReason why it closed: `restart` (Lunamux stopped under it), `exit`, `quit`,
 *   `cleared` (/clear), `closed` (its pane went) or `replaced`; null while open.
 * @property host for a helper (a `claude` started inside another conversation): the conversation it
 *   ran inside.
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
    val title: String? = null,
    val firstPrompt: String? = null,
    val transcriptPath: String? = null,
    val state: String = AgentSessionState.DONE,
    val stateSince: Long = 0,
    val nowLine: String? = null,
    val question: String? = null,
    val lastScreen: String? = null,
    val createdAt: Long = 0,
    val endedAt: Long? = null,
    val lastSaid: String? = null,
    val taskId: String? = null,
    val background: Boolean = false,
    val endReason: String? = null,
    val host: String? = null,
) {
    /** The conversation this record is a run of: the agent's own id when known, else this record's id. */
    val conversationId: String get() = agentSessionId ?: id

    /** Whether the record is closed. */
    val isEnded: Boolean get() = state == AgentSessionState.ENDED

    /**
     * Whether this closed Claude conversation named a transcript file that is gone, so
     * `claude --resume` could not find it. A record that never named a file is not judged.
     */
    fun transcriptMissing(): Boolean =
        isEnded && agent == "claude" && transcriptPath != null && !java.io.File(transcriptPath).isFile

    /**
     * Whether this closed conversation was fed by hooks yet never saw a prompt, an answer or a
     * title: the person started `claude` and left, or cancelled `/resume`.
     */
    fun nothingSaid(): Boolean =
        isEnded && agent == "claude" && source == "hooks" && firstPrompt == null && lastSaid == null && title == null

    /**
     * The line that picks the conversation back up, typed into a shell; null when it cannot be (an
     * agent that cannot resume, or an id that is not a plain id and so must not reach a terminal).
     */
    fun resumeCommand(): String? {
        val id = agentSessionId?.takeIf { it.isNotEmpty() && it.length <= 100 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }
        return when (agent) {
            "claude" -> id?.let { "claude --resume $it" }
            "codex" -> id?.let { "codex resume $it" } ?: "codex resume"
            else -> null
        }
    }
}
