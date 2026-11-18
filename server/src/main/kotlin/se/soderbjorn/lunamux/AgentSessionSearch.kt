/**
 * The two questions the agents overview asks about sessions beyond the pushed list: "which sessions
 * mention this?" and "what is going on in this one?".
 *
 * This file contains [AgentSessionSearch], which ranks and snips search hits from the database, and
 * builds the screen / unsaved-work / resume details used by peek, the close confirmation and the
 * resume note. `WindowRoutes` calls it for [WindowCommand.SearchAgentSessions] and
 * [WindowCommand.InspectAgentSession] and sends the answers to the asking connection only.
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.AgentSessionHit
import java.io.File
import java.nio.file.Path

/** Search ranking, snippets and per-session details for the agents overview. */
object AgentSessionSearch {
    /** The most matches returned for one query. */
    const val MAX_MATCHES = 50

    /** The fewest characters a query needs before it searches. */
    const val MIN_QUERY = 2

    /** Characters kept on each side of a match in a snippet. */
    private const val SNIPPET_SIDE = 40

    /** Rank of each matched field: lower is better. */
    private val TIER = mapOf(
        "title" to 0, "tab" to 0, "task" to 1, "prompt" to 2, "branch" to 3, "links" to 3, "transcript" to 4, "screen" to 4,
    )

    /**
     * Find and rank the sessions that contain [query]. Called by `WindowRoutes` for
     * [WindowCommand.SearchAgentSessions].
     *
     * @param repo the stored sessions and their search text.
     * @param tracker the live sessions; null when only stored ones are searched.
     * @param query what the person typed; under [MIN_QUERY] characters finds nothing.
     * @param tasks the tasks, so a session is found by its task's title.
     * @param tabNames the name the person gave the listed tab each conversation runs in, by terminal
     *   session id ([WindowState.listedTabNames]): the overview leads a row with it, so it is found by it.
     * @return the matches, best first, at most [MAX_MATCHES].
     */
    fun search(
        repo: SettingsRepository,
        tracker: AgentSessionTracker?,
        query: String,
        tasks: TaskIndex? = null,
        tabNames: Map<String, String> = emptyMap(),
    ): List<AgentSessionMatch> {
        val q = query.trim()
        if (q.length < MIN_QUERY) return emptyList()
        val hits = repo.searchAgentSessions(q)
        val matchingTasks = tasks?.taskIdsTitled(q).orEmpty()
        val seen = hits.mapTo(HashSet()) { it.record.id }
        val live = tracker?.snapshot().orEmpty().filter { !it.background }
        val viaTask = if (tasks == null || matchingTasks.isEmpty()) emptyList() else {
            val stored = repo.loadAgentSessionsOfTasks(matchingTasks)
            (stored + live.filter { tasks.taskIdOf(it) in matchingTasks }).distinctBy { it.id }
                .filter { seen.add(it.id) }.map { AgentSessionHit(it, null, null) }
        }
        val viaTab = live.filter { tabNameOf(it, tabNames)?.contains(q, ignoreCase = true) == true && seen.add(it.id) }
            .map { AgentSessionHit(it, null, null) }
        return (hits + viaTask + viaTab)
            .mapNotNull { hit ->
                val rec = (tracker?.cached(hit.record.id) ?: hit.record).takeUnless { it.background || it.nothingSaid() || it.transcriptMissing() } ?: return@mapNotNull null
                val taskTitle = tasks?.taskIdOf(rec)?.let(tasks::titleOf)
                val found = bestField(rec, tabNameOf(rec, tabNames), taskTitle, hit.transcriptWindow, hit.screenWindow, q) ?: return@mapNotNull null
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

    /** Gather what peek, the close confirmation and the resume note need for one session. */
    fun inspect(recordId: String, tracker: AgentSessionTracker?, tasks: TaskIndex? = null): WindowEnvelope.AgentSessionInspection? {
        val rec = tracker?.find(recordId) ?: return null
        val open = rec.state != "ended"
        val snapshot = if (open) rec.terminalSessionId?.let { livePaneSnapshot(it) } else null
        val rows = snapshot?.screenRows?.invoke()
        val (screen, styled) = if (rows != null) PeekScreen.styled(rows)
        else PeekScreen.plain(if (snapshot != null) snapshot.screenText() else rec.lastScreen) to emptyList()
        val folder = rec.cwd?.takeIf { it.isNotBlank() && File(it).isDirectory }?.let { Path.of(it) }
        return WindowEnvelope.AgentSessionInspection(
            recordId = recordId,
            live = snapshot != null,
            screen = screen,
            styled = styled,
            uncommitted = folder?.let(GitCatalog::uncommittedCount),
            unpushed = folder?.let(GitCatalog::unpushedCount),
            branch = folder?.let { GitCatalog.runGit(it, "branch", "--show-current")?.trim()?.takeIf(String::isNotEmpty) }
                ?: rec.branch,
            resumeNote = if (open) null else resumeNoteOf(rec, tasks?.openOfMain(rec)),
        )
    }

    /** What the person asked in one conversation and the work left in its folder, for the panel of a session with no task. */
    fun story(recordId: String, tracker: AgentSessionTracker?): WindowEnvelope.AgentStory? {
        val rec = tracker?.find(recordId) ?: return null
        val read = rec.transcriptPath?.let(::storyOf) ?: TranscriptStory()
        // The transcript knows where the conversation went on to work; the record only where it started.
        val dir = listOfNotNull(read.cwd, rec.cwd).firstOrNull { it.isNotBlank() && File(it).isDirectory }
        val folder = dir?.let { Path.of(it) }
        val git = readGitInfo(dir)
        return WindowEnvelope.AgentStory(
            recordId = recordId,
            turns = read.turns,
            uncommitted = folder?.let(GitCatalog::uncommittedCount),
            unpushed = folder?.let(GitCatalog::unpushedCount),
            branch = git.branch,
            folder = git.repo ?: dir?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
        )
    }

    /** One transcript's story as last read, with the file's size and time: a transcript can be tens of megabytes, and every window asks. */
    private class ReadStory(val size: Long, val modified: Long, val story: TranscriptStory)

    private val stories = java.util.concurrent.ConcurrentHashMap<String, ReadStory>()

    /** [AgentTranscriptText.story] of the file at [path], read again only when the file has changed. */
    private fun storyOf(path: String): TranscriptStory {
        val file = File(path)
        val size = file.length()
        val modified = file.lastModified()
        stories[path]?.takeIf { it.size == size && it.modified == modified }?.let { return it.story }
        return AgentTranscriptText.story(file).also {
            if (stories.size > 64) stories.clear()
            stories[path] = ReadStory(size, modified, it)
        }
    }

    /** The last segment of the record's working folder, as the overview names the folder; null when it has none. */
    private fun folderName(rec: AgentSessionRecord): String? =
        rec.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    /** The name of the listed tab an open conversation runs in; a closed one's tab has moved on to other work. */
    private fun tabNameOf(rec: AgentSessionRecord, tabNames: Map<String, String>): String? =
        rec.terminalSessionId?.takeIf { rec.state != "ended" }?.let(tabNames::get)

    /** One match inside a record: its field, the text to show and where the query sits in it. */
    private class Found(val field: String, val snippet: String?, val start: Int, val length: Int)

    private fun bestField(
        rec: AgentSessionRecord, tabName: String?, taskTitle: String?, transcript: String?, screen: String?, q: String,
    ): Found? {
        rec.title?.takeIf { AgentSessionText.isMeaningfulTitle(it, rec.branch, rec.repo, folderName(rec)) }?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("title", null, at, q.length)
        }
        tabName?.let { t ->
            val at = t.indexOf(q, ignoreCase = true)
            if (at >= 0) return Found("tab", null, at, q.length)
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

    /** About 40 characters either side of the first match of [q] in [text], on one line. */
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
