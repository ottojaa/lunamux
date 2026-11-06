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
 * @see resumeNoteOf
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository.searchAgentSessions
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.AgentSessionHit
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
    private val TIER = mapOf(
        "title" to 0, "task" to 1, "prompt" to 2, "branch" to 3, "links" to 3, "transcript" to 4, "screen" to 4,
    )

    /**
     * Find and rank the sessions that contain [query].
     *
     * Case-insensitive substring match over the session's title, the title of its task, first
     * prompt, branch, ticket links, the conversation text and the last screen. Each session
     * appears once, labelled with its best field. Order: title, then task title, then first
     * prompt, then branch or links, then transcript or screen; within a tier open sessions come
     * first, then the newest.
     *
     * A stored title that only says where the session ran (`~`, `HEAD`, a folder, the branch or
     * a run id) is not searched, because the row shows a title made from the prompt instead and
     * a hit on a word the row never shows would look like a mistake.
     *
     * A task's title is not a column of the session, so the sessions of every task whose title
     * matches are read by task id as well, however old they are, and merged with the rows the
     * ordinary query found.
     *
     * @param repo the store to search.
     * @param tracker supplies the live record for sessions that are open, so their state is current.
     * @param query what the user typed; surrounding spaces are ignored.
     * @param tasks says which task each session is in and what that task is called; null searches
     *   the sessions' own fields only.
     * @return at most [MAX_MATCHES] matches, best first; empty for a query shorter than [MIN_QUERY].
     */
    fun search(
        repo: SettingsRepository,
        tracker: AgentSessionTracker?,
        query: String,
        tasks: TaskIndex? = null,
    ): List<AgentSessionMatch> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val hits = repo.searchAgentSessions(q)
        val matchingTasks = tasks?.taskIdsTitled(q).orEmpty()
        val seen = hits.mapTo(HashSet()) { it.record.id }
        val viaTask = if (tasks == null || matchingTasks.isEmpty()) emptyList() else {
            val stored = repo.loadAgentSessionsOfTasks(matchingTasks)
            val live = tracker?.snapshot().orEmpty().filter { tasks.taskIdOf(it) in matchingTasks && !it.background }
            (stored + live).distinctBy { it.id }.filter { seen.add(it.id) }.map { AgentSessionHit(it, null, null) }
        }
        return (hits + viaTask)
            .mapNotNull { hit ->
                val rec = (tracker?.cached(hit.record.id) ?: hit.record).takeUnless { it.background } ?: return@mapNotNull null
                val taskTitle = tasks?.taskIdOf(rec)?.let(tasks::titleOf)
                val found = bestField(rec, taskTitle, hit.transcriptWindow, hit.screenWindow, q) ?: return@mapNotNull null
                Triple(rec, found, taskTitle)
            }
            .sortedWith(
                compareBy<Triple<AgentSessionRecord, Found, String?>> { TIER.getValue(it.second.field) }
                    .thenBy { if (it.first.state == "ended") 1 else 0 }
                    .thenByDescending { it.first.endedAt ?: it.first.stateSince },
            )
            .take(MAX_MATCHES)
            .map { (rec, found, taskTitle) ->
                AgentSessionMatch(
                    summary = rec.toSummary(),
                    field = found.field,
                    snippet = found.snippet,
                    start = found.start,
                    length = found.length,
                    resumeNote = if (rec.state == "ended") resumeNoteOf(rec, tasks?.openOfMain(rec)) else null,
                    taskTitle = taskTitle,
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
     * @param tasks says which `open` action a task's main conversation resumes with, so the note
     *   names the folder Resume really opens; null when no task is known.
     * @return the details, or null when the record no longer exists.
     */
    fun inspect(recordId: String, tracker: AgentSessionTracker?, tasks: TaskIndex? = null): WindowEnvelope.AgentSessionInspection? {
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
            resumeNote = if (open) null else resumeNoteOf(rec, tasks?.openOfMain(rec)),
        )
    }

    /** The last segment of the record's working folder, as the overview names the folder; null when it has none. */
    private fun folderName(rec: AgentSessionRecord): String? =
        rec.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** One match inside a record: its field, the text to show and where the query sits in it. */
    private class Found(val field: String, val snippet: String?, val start: Int, val length: Int)

    private fun bestField(
        rec: AgentSessionRecord, taskTitle: String?, transcript: String?, screen: String?, q: String,
    ): Found? {
        rec.title?.takeIf { AgentSessionText.isMeaningfulTitle(it, rec.branch, rec.repo, folderName(rec)) }?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("title", null, at, q.length)
        }
        taskTitle?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("task", null, at, q.length)
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
