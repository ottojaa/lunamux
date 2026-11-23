/**
 * The agents overview's wire model: what the server says about each agent conversation, in a form
 * a client draws without deciding anything itself.
 *
 * This file contains [Shown] (the one state a row is drawn in), [AgentSessionSummary] (one row),
 * [AgentTurn] (one thing the person asked), [WorktreeWork] (the git work left in a folder),
 * [ScreenRun] (a styled run of a pane's screen, for the peek) and [AgentWindows] (how far back the
 * overview looks). The server's `AgentSessionTracker` builds the summaries and the protocol
 * envelopes carry them; the screens only read them.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.Serializable

/**
 * The one state an overview row is drawn in, decided by the server from what the conversation is
 * doing, what it left running and why it ended.
 *
 * @see AgentSessionSummary.shown
 */
@Serializable
enum class Shown {
    /** The conversation asks the person something, or waits for them. */
    NEEDS_YOU,

    /** A turn is running. */
    WORKING,

    /** The turn ended but the conversation waits for something (a kept line, background shells). */
    WAITING,

    /** A task whose work was stopped on purpose; never a conversation. */
    PAUSED,

    /** The conversation sits at its prompt with nothing outstanding. */
    DONE,

    /** The conversation was cut off by Lunamux stopping, recently enough to be picked up again. */
    STOPPED,

    /** The conversation has ended. */
    CLOSED,

    /** A state a newer server sent that this build does not know; drawn like [DONE]. */
    UNKNOWN,
}

/**
 * One row of the agents overview: one agent conversation.
 *
 * @property id the conversation's id: the agent's own session id when it has one, else the record's.
 * @property agent `claude`, `codex` or `gemini`.
 * @property shown the state the row is drawn in. Defaulted to [Shown.UNKNOWN] so a decoder that
 *   coerces unknown values can fall back to it.
 * @property since when the row entered [shown], in epoch milliseconds.
 * @property title what the conversation is about, never blank.
 * @property line the row's one line of detail (a question, a status, a tool call, the last reply);
 *   null when the conversation has none or has ended.
 * @property lineSet whether [line] was written for this state (a question, a kept line or the
 *   conversation's own status) rather than taken from its last reply.
 * @property ended why a closed conversation ended (`exit`, `cleared`, `quit`, `closed`, `replaced`
 *   or `restart`); null while it is open.
 * @property listed false for a closed conversation that said nothing or whose transcript is gone,
 *   which the overview leaves out of its lists.
 * @property resumable whether a closed conversation can be picked up again.
 * @property inTab whether a closed conversation's own pane still sits at an idle shell in the same
 *   folder, so resuming can reuse it.
 * @property taskId the task the conversation belongs to, or null.
 * @property leafId the pane the conversation runs (or last ran) in.
 * @property startedAt when the conversation began, in epoch milliseconds.
 * @property repo the git repository's name, or null outside one.
 * @property folder the name of the folder it works in.
 * @property recap what the conversation is and where it stands, from Claude Code's own recap.
 */
@Serializable
data class AgentSessionSummary(
    val id: String,
    val agent: String,
    val shown: Shown = Shown.UNKNOWN,
    val since: Long = 0,
    val title: String = "",
    val line: String? = null,
    val lineSet: Boolean = false,
    val ended: String? = null,
    val listed: Boolean = true,
    val resumable: Boolean = false,
    val inTab: Boolean = false,
    val taskId: String? = null,
    val leafId: String? = null,
    val startedAt: Long = since,
    val repo: String? = null,
    val folder: String? = null,
    val recap: String? = null,
)

/**
 * One search result: a conversation's row and where the query was found in it.
 *
 * @property summary the conversation's row, built the same way as the overview's.
 * @property field where it matched: `title`, `tab`, `task`, `prompt`, `branch`, `transcript` or `screen`.
 * @property snippet the words around the match for the long fields, null for a short one.
 * @property start where the match starts, in the snippet (or in the field when there is none).
 * @property length how long the match is.
 * @property resumeNote for a closed conversation: what resuming it would do, when that needs saying.
 * @property taskTitle the title of the task the conversation belongs to.
 */
@Serializable
data class AgentSessionMatch(
    val summary: AgentSessionSummary,
    val field: String,
    val snippet: String? = null,
    val start: Int = 0,
    val length: Int = 0,
    val resumeNote: String? = null,
    val taskTitle: String? = null,
)

/**
 * One thing the person asked a conversation.
 *
 * @property atMillis when it was asked.
 * @property prompt the first line of the prompt.
 * @property tookMillis how long the agent then worked; null while it still does.
 * @property reply the first line of the last thing the agent wrote in answer; null until it has.
 */
@Serializable
data class AgentTurn(
    val atMillis: Long,
    val prompt: String,
    val tookMillis: Long? = null,
    val reply: String? = null,
)

/**
 * The work left in a folder under git.
 *
 * @property name the folder's name.
 * @property branch its current branch; null when detached.
 * @property changed tracked files changed, staged or not.
 * @property added lines added against the last commit; null when unknown.
 * @property removed lines removed against the last commit; null when unknown.
 * @property untracked new paths git does not track, a new folder counting once.
 * @property unpushed commits on no remote; null when the repository has no remote.
 */
@Serializable
data class WorktreeWork(
    val name: String,
    val branch: String? = null,
    val changed: Int = 0,
    val added: Int? = null,
    val removed: Int? = null,
    val untracked: Int = 0,
    val unpushed: Int? = null,
)

/**
 * One run of same-styled text on a row of a pane's screen, as the peek draws it. A colour is null
 * for the terminal's default, 0–255 for a palette entry, or [RGB] plus a 24-bit `0xRRGGBB`.
 */
@Serializable
data class ScreenRun(
    val text: String,
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Foreground and background swap, as a terminal draws a cursor or a selected item. */
    val inverse: Boolean = false,
) {
    companion object {
        /** The flag on [fg] or [bg] that marks a 24-bit colour in the low 24 bits. */
        const val RGB = 1 shl 24
    }
}

/** How far back the overview looks, shared so the server's history and the clients' lists agree. */
object AgentWindows {
    /** How long a closed conversation stays in the overview's history: 31 days. */
    const val HISTORY_MS = 31L * 24 * 60 * 60 * 1000

    /** How long a closed conversation counts as recent: 3 days. */
    const val RECENT_MS = 3L * 24 * 60 * 60 * 1000

    /** How long after Lunamux stopped under a conversation it reads as stopped rather than closed: 24 hours. */
    const val STOPPED_MS = 24L * 60 * 60 * 1000
}
