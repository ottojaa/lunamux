/**
 * The one place that decides what a conversation's row in the agents overview shows.
 *
 * This file contains [summaryOf], a pure function from a record (and, for an open one, its [Turn])
 * to the [AgentSessionSummary] the clients draw: the headline state, the line under the title, the
 * title itself, and the flags that say whether the row is listed and can be resumed. The tracker
 * uses it for every open and recently closed conversation; search uses it for a conversation found
 * only on disk, so both read the same way.
 *
 * @see AgentSessionTracker
 * @see AgentSessionSearch
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState.DONE
import se.soderbjorn.lunamux.persistence.AgentSessionState.NEEDS_YOU
import se.soderbjorn.lunamux.persistence.AgentSessionState.WORKING

/**
 * Build the row for [rec].
 *
 * The headline: a closed record is `STOPPED` when Lunamux stopped under it within a day, else
 * `CLOSED`; an open one needs the person when it asks (or its kept status says it waits for them),
 * works while a turn runs, and otherwise sits at its prompt, `WAITING` when it left something
 * pending (a kept status or a background shell) and `DONE` when not. The line under the title says
 * what that state is about, and [AgentSessionSummary.lineSet] whether the conversation worded it
 * itself.
 *
 * @param rec the record.
 * @param turn what the tracker remembers of its running turn; null for a record not in memory.
 * @param now the time, for the stopped window.
 * @param inTab whether a tab already shows the conversation (the window state's answer).
 * @return the row.
 * @see AgentSessionTracker.summaries
 */
internal fun summaryOf(rec: AgentSessionRecord, turn: Turn?, now: Long, inTab: Boolean = false): AgentSessionSummary {
    val ended = rec.isEnded
    val kept = if (ended) null else turn?.kept
    val shells = if (ended) 0 else turn?.shells?.count ?: 0
    val shown = when {
        ended -> if (rec.endReason == AgentWords.END_RESTART && now - (rec.endedAt ?: 0L) < AgentWindows.STOPPED_MS) Shown.STOPPED else Shown.CLOSED
        rec.state == NEEDS_YOU || kept?.needsYou == true -> Shown.NEEDS_YOU
        rec.state == WORKING -> Shown.WORKING
        kept != null || shells > 0 -> Shown.WAITING
        else -> Shown.DONE
    }
    val waitText = kept?.text ?: if (shells > 0) shellsText(shells) else null
    val question = rec.question?.let { AgentSessionText.previewOf(it) }?.let { "\"$it\"" }
    val status = turn?.status
    val line: String? = when (shown) {
        Shown.NEEDS_YOU -> if (rec.state == NEEDS_YOU) question ?: rec.nowLine else kept?.text
        Shown.WORKING -> status ?: rec.nowLine
        Shown.WAITING -> waitText
        Shown.DONE -> AgentSessionText.saidOf(rec.lastSaid)
        else -> null
    }
    val lineSet = when (shown) {
        Shown.NEEDS_YOU -> rec.state != NEEDS_YOU || question != null
        Shown.WORKING -> status != null
        Shown.WAITING -> kept != null
        else -> false
    }
    val folderName = rec.cwd?.let { if (prettifyPath(it) == "~") "~" else it.trimEnd('/').substringAfterLast('/') }
    val title = rec.title?.takeIf { AgentSessionText.isMeaningfulTitle(it, rec.branch, rec.repo, folderName) }
        ?: AgentSessionText.titleFromPrompt(rec.firstPrompt)
        ?: "${agentName(rec.agent)} session"
    val since = when {
        ended -> rec.endedAt ?: rec.stateSince
        shown == Shown.WAITING -> kept?.since ?: turn?.shells?.since ?: rec.stateSince
        else -> rec.stateSince
    }
    return AgentSessionSummary(
        id = rec.conversationId,
        agent = rec.agent,
        shown = shown,
        since = since,
        title = title,
        line = line,
        lineSet = lineSet,
        ended = rec.endReason.takeIf { ended },
        listed = !(ended && (rec.nothingSaid() || rec.transcriptMissing())),
        resumable = ended && rec.resumeCommand() != null && !rec.transcriptMissing(),
        inTab = inTab,
        taskId = rec.taskId,
        leafId = rec.leafId,
        startedAt = rec.createdAt,
        repo = rec.repo,
        folder = folderName,
        recap = if (ended) null else turn?.follower?.recap,
    )
}

/** The words for [count] background shells left running. */
private fun shellsText(count: Int): String =
    if (count == 1) "1 background shell running" else "$count background shells running"

/** The name of [agent] as the row's fallback title says it. */
private fun agentName(agent: String): String = when (agent) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "gemini" -> "Gemini"
    else -> agent.replaceFirstChar { it.uppercase() }
}
