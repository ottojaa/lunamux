/**
 * Search over agent conversations: which ones mention a query, and where.
 *
 * This file contains [AgentSessionSearch], which asks the database for the conversations that
 * contain the query (the month filter runs in SQL before its row cap), lays each hit out with the
 * same [summaryOf] the overview uses, finds the best field of each, and snips the words around the
 * match. A conversation found only on disk keeps its end reason, so it reads as it does in the list.
 *
 * @see AgentSessionTracker.summaryFor
 * @see SettingsRepository.searchAgentSessions
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository

/** Ranks and snips the hits for one search query. */
object AgentSessionSearch {
    /** The most matches returned for one query. */
    const val MAX_MATCHES = 50

    /** The fewest characters a query needs before it searches. */
    const val MIN_QUERY = 2

    /** How far back a closed conversation is still searched: one month. */
    const val SEARCH_WINDOW_MS = 30L * 24 * 60 * 60_000L

    /** Characters kept on each side of a match in a snippet. */
    private const val SNIPPET_SIDE = 40

    /** Rank of each matched field: lower is better. */
    private val TIER = mapOf("title" to 0, "tab" to 0, "task" to 1, "prompt" to 2, "branch" to 3, "transcript" to 4, "screen" to 4)

    /**
     * Find and rank the conversations that contain [query].
     *
     * Called by the window routes for a search command; the answer goes to the asking connection only.
     *
     * @param repo the stored conversations and their search text.
     * @param tracker the live conversations, whose rows and open records are preferred; null for stored only.
     * @param query what the person typed; under [MIN_QUERY] characters finds nothing.
     * @param tabNameOf the name of the tab a record runs in, for matching by it.
     * @param taskTitleOf the title of the task a record belongs to.
     * @param resumeNoteOf what resuming a closed record would do, when that needs saying.
     * @param now the time, for the month window.
     * @return the matches, best first, at most [MAX_MATCHES], one per conversation.
     * @see AgentSessionMatch
     */
    fun search(
        repo: SettingsRepository,
        tracker: AgentSessionTracker?,
        query: String,
        tabNameOf: (AgentSessionRecord) -> String? = { null },
        taskTitleOf: (AgentSessionRecord) -> String? = { null },
        resumeNoteOf: (AgentSessionRecord) -> String? = { null },
        now: Long = System.currentTimeMillis(),
    ): List<AgentSessionMatch> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val rows = repo.searchAgentSessions(q, now - SEARCH_WINDOW_MS)
        val seen = rows.mapTo(HashSet()) { it.record.id }
        val viaTab = tracker?.snapshot().orEmpty()
            .filter { tabNameOf(it)?.contains(q, ignoreCase = true) == true && seen.add(it.id) }
            .map { SettingsRepository.AgentSearchRow(it, null, null) }
        // A task's title finds its conversations too, so a task can be looked up by its name.
        val viaTask = tracker?.snapshot().orEmpty()
            .filter { taskTitleOf(it)?.contains(q, ignoreCase = true) == true && seen.add(it.id) }
            .map { SettingsRepository.AgentSearchRow(it, null, null) }
        val found = (rows + viaTab + viaTask).mapNotNull { row ->
            val rec = tracker?.cached(row.record.id) ?: row.record
            val summary = tracker?.summaryFor(rec) ?: summaryOf(rec, null, now)
            if (!summary.listed) return@mapNotNull null
            val taskTitle = taskTitleOf(rec)
            val at = bestField(rec, tabNameOf(rec), taskTitle, row.transcriptWindow, row.screenWindow, q) ?: return@mapNotNull null
            Triple(summary, at, taskTitle) to rec
        }
        return found
            .sortedWith(
                compareBy<Pair<Triple<AgentSessionSummary, Found, String?>, AgentSessionRecord>> { TIER.getValue(it.first.second.field) }
                    .thenBy { if (it.second.isEnded) 1 else 0 }
                    .thenByDescending { it.second.endedAt ?: it.second.stateSince },
            )
            .distinctBy { it.second.conversationId }
            .take(MAX_MATCHES)
            .map { (hit, rec) ->
                val (summary, at, taskTitle) = hit
                AgentSessionMatch(
                    summary = summary, field = at.field, snippet = at.snippet, start = at.start, length = at.length,
                    resumeNote = if (rec.isEnded) resumeNoteOf(rec) else null, taskTitle = taskTitle,
                )
            }
    }

    /** One match inside a record: its field, the text to show and where the query sits in it. */
    private class Found(val field: String, val snippet: String?, val start: Int, val length: Int)

    private fun bestField(
        rec: AgentSessionRecord, tabName: String?, taskTitle: String?, transcript: String?, screen: String?, q: String,
    ): Found? {
        val folder = rec.cwd?.trimEnd('/')?.substringAfterLast('/')
        rec.title?.takeIf { AgentSessionText.isMeaningfulTitle(it, rec.branch, rec.repo, folder) }?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("title", null, at, q.length)
        }
        for ((field, text) in listOf("tab" to tabName, "task" to taskTitle)) {
            val at = text?.indexOf(q, ignoreCase = true) ?: -1
            if (at >= 0) return Found(field, null, at, q.length)
        }
        for ((field, text) in listOf("prompt" to rec.firstPrompt, "branch" to rec.branch, "transcript" to transcript, "screen" to screen)) {
            text?.let { snip(it, q) }?.let { return Found(field, it.first, it.second, q.length) }
        }
        return null
    }

    /**
     * About 40 characters either side of the first match of [q] in [text], on one line.
     *
     * @param text the field's text.
     * @param q the query.
     * @return the snippet and where the match starts in it, or null when [text] does not hold [q].
     */
    internal fun snip(text: String, q: String): Pair<String, Int>? {
        val first = text.indexOf(q, ignoreCase = true)
        if (first < 0) return null
        val from0 = text.lastIndexOf('\n', first - 1) + 1
        val to0 = text.indexOf('\n', first).let { if (it < 0) text.length else it }
        val raw = text.substring(from0, to0)
        val plain = AgentSessionText.plain(raw)
        val line = if (plain.contains(q, ignoreCase = true)) plain else raw
        val at = line.indexOf(q, ignoreCase = true)
        val from = maxOf(0, at - SNIPPET_SIDE)
        val to = minOf(line.length, at + q.length + SNIPPET_SIDE)
        val head = if (from > 0) "…" else ""
        val tail = if (to < line.length) "…" else ""
        val lead = line.substring(from, at).trimStart()
        return (head + lead + line.substring(at, to).trimEnd() + tail) to (head.length + lead.length)
    }
}
