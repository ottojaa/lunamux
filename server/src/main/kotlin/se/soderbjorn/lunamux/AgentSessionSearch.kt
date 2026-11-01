/**
 * The two questions the agents overview asks about sessions beyond the pushed
 * list: "which sessions mention this?" and "what is going on in this one?".
 *
 * This file contains [AgentSessionSearch], which ranks and snips search hits
 * from the database, and builds the screen / unsaved-work / resume details used
 * by peek, the close confirmation and the resume note. `WindowRoutes` calls it
 * for [WindowCommand.SearchAgentSessions] and [WindowCommand.InspectAgentSession]
 * and sends the answers to the asking connection only.
 *
 * @see AgentResumePlanner
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository.searchAgentSessions
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.nio.file.Path

/**
 * Search ranking, snippets and per-session details for the agents overview.
 *
 * Stateless; every function may block on SQLite or git, so callers run them off the
 * socket's coroutine.
 */
object AgentSessionSearch {
    /** The most matches returned for one query. */
    const val MAX_MATCHES = 50

    /** The fewest characters a query needs before it searches. */
    const val MIN_QUERY = 2

    /** Characters kept on each side of a match in a snippet. */
    private const val SNIPPET_SIDE = 40

    /** The most lines of screen returned for peek. */
    private const val PEEK_LINES = 12

    /** Rank of each matched field: lower is better. */
    private val TIER = mapOf("title" to 0, "prompt" to 1, "branch" to 2, "links" to 2, "transcript" to 3, "screen" to 3)

    /**
     * Find and rank the sessions that contain [query].
     *
     * Case-insensitive substring match over title, first prompt, branch, ticket links, the
     * conversation text and the last screen. Each session appears once, labelled with its best
     * field. Order: title, then first prompt, then branch or links, then transcript or screen;
     * within a tier open sessions come first, then the newest.
     *
     * @param repo the store to search.
     * @param tracker supplies the live record for sessions that are open, so their state is current.
     * @param query what the user typed; surrounding spaces are ignored.
     * @return at most [MAX_MATCHES] matches, best first; empty for a query shorter than [MIN_QUERY].
     */
    fun search(repo: SettingsRepository, tracker: AgentSessionTracker?, query: String): List<AgentSessionMatch> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        return repo.searchAgentSessions(q)
            .mapNotNull { hit ->
                val rec = tracker?.cached(hit.record.id) ?: hit.record
                val found = bestField(rec, hit.transcriptWindow, hit.screenWindow, q) ?: return@mapNotNull null
                rec to found
            }
            .sortedWith(
                compareBy<Pair<AgentSessionRecord, Found>> { TIER.getValue(it.second.field) }
                    .thenBy { if (it.first.state == "ended") 1 else 0 }
                    .thenByDescending { it.first.endedAt ?: it.first.stateSince },
            )
            .take(MAX_MATCHES)
            .map { (rec, found) ->
                AgentSessionMatch(
                    summary = rec.toSummary(),
                    field = found.field,
                    snippet = found.snippet,
                    start = found.start,
                    length = found.length,
                    resumeNote = if (rec.state == "ended") AgentResumePlanner.note(AgentResumePlanner.plan(rec)) else null,
                )
            }
    }

    /**
     * Gather what peek, the close confirmation and the resume note need for one session.
     *
     * The screen of an open session is read live; an ended one gives its saved last screen.
     * Unsaved work is read from the session's folder when it still exists.
     *
     * @param recordId the record the user selected.
     * @param tracker where the record is looked up.
     * @return the details, or null when the record no longer exists.
     */
    fun inspect(recordId: String, tracker: AgentSessionTracker?): WindowEnvelope.AgentSessionInspection? {
        val rec = tracker?.find(recordId) ?: return null
        val open = rec.state != "ended"
        val snapshot = if (open) rec.terminalSessionId?.let { livePaneSnapshot(it) } else null
        val screenText = if (snapshot != null) snapshot.screenText() else rec.lastScreen
        val screen = screenText?.lines()?.filter { it.isNotBlank() }?.takeLast(PEEK_LINES).orEmpty()
        val folder = rec.cwd?.takeIf { it.isNotBlank() && File(it).isDirectory }?.let { Path.of(it) }
        return WindowEnvelope.AgentSessionInspection(
            recordId = recordId,
            live = snapshot != null,
            screen = screen,
            uncommitted = folder?.let(GitCatalog::uncommittedCount),
            unpushed = folder?.let(GitCatalog::unpushedCount),
            branch = folder?.let { GitCatalog.runGit(it, "branch", "--show-current")?.trim()?.takeIf(String::isNotEmpty) }
                ?: rec.branch,
            resumeNote = if (open) null else AgentResumePlanner.note(AgentResumePlanner.plan(rec)),
        )
    }

    /** One match inside a record: its field, the text to show and where the query sits in it. */
    private class Found(val field: String, val snippet: String?, val start: Int, val length: Int)

    private fun bestField(rec: AgentSessionRecord, transcript: String?, screen: String?, q: String): Found? {
        rec.title?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("title", null, at, q.length)
        }
        rec.firstPrompt?.let { snip(it, q) }?.let { return Found("prompt", it.first, it.second, q.length) }
        rec.branch?.let { snip(it, q) }?.let { return Found("branch", it.first, it.second, q.length) }
        snip(rec.links.joinToString(", "), q)?.let { return Found("links", it.first, it.second, q.length) }
        transcript?.let { snip(it, q) }?.let { return Found("transcript", it.first, it.second, q.length) }
        screen?.let { snip(it, q) }?.let { return Found("screen", it.first, it.second, q.length) }
        return null
    }

    /**
     * About 40 characters either side of the first match of [q] in [text], on one line.
     *
     * Only the line holding the match is used. A cut end is shown with `…`.
     *
     * @return the snippet and where the match starts inside it, or null when [text] has no match.
     */
    internal fun snip(text: String, q: String): Pair<String, Int>? {
        val at = text.indexOf(q, ignoreCase = true)
        if (at < 0) return null
        val lineStart = text.lastIndexOf('\n', at - 1) + 1
        val lineEnd = text.indexOf('\n', at).let { if (it < 0) text.length else it }
        val from = maxOf(lineStart, at - SNIPPET_SIDE)
        val to = minOf(lineEnd, at + q.length + SNIPPET_SIDE)
        val head = if (from > lineStart) "…" else ""
        val tail = if (to < lineEnd) "…" else ""
        val lead = text.substring(from, at)
        val trimmedLead = lead.trimStart()
        val body = trimmedLead + text.substring(at, to).trimEnd()
        return (head + body + tail) to (head.length + trimmedLead.length)
    }
}
