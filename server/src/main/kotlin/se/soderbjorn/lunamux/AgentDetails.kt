/**
 * What the overview shows when the person looks closer at one conversation or one task: the
 * pane's screen, what was asked and the git work left behind.
 *
 * This file contains [AgentDetailsReader], which builds the answer to `ReadAgentDetails` for a
 * conversation ([AgentDetailsReader.ofRecord]) or a task ([AgentDetailsReader.ofTask]), and
 * [InFlight], the guard that drops a repeated request while the first is still being read. All of
 * it reads files and runs `git`, so the window routes call it off the socket's loop and under a
 * time limit.
 *
 * @see WindowEnvelope.AgentDetails
 * @see GitCatalog.folderWork
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How long one details request may take before it is given up on: 8 seconds. */
const val INSPECT_TIMEOUT_MS = 8_000L

/**
 * Remembers which requests are being answered, so a second one for the same thing from the same
 * connection can be dropped. A peek that asks again every couple of seconds would otherwise queue
 * up behind a slow `git`.
 *
 * One guard is shared by all connections; the key says whose request it is.
 */
class InFlight {
    private val keys: MutableSet<Any> = ConcurrentHashMap.newKeySet()

    /**
     * Claim [key] for the length of [block], or do nothing when it is already claimed.
     *
     * @param key who is asking for what.
     * @param block the work; runs at most once per claim.
     * @return true when [block] ran, false when the request was dropped.
     */
    suspend fun <T> once(key: Any, block: suspend () -> T): Boolean {
        if (!keys.add(key)) return false
        try {
            block()
        } finally {
            keys.remove(key)
        }
        return true
    }
}

/** Builds [WindowEnvelope.AgentDetails] answers. Stateless apart from a cache of read transcripts. */
object AgentDetailsReader {
    /** The most worktrees one task's details list. */
    const val MAX_WORKTREES = 6

    /** One transcript's story as last read, with the size and time of the file it came from. */
    private class ReadStory(val size: Long, val modified: Long, val story: TranscriptStory)

    private val stories = ConcurrentHashMap<String, ReadStory>()

    /**
     * The story of the transcript at [path]. A transcript can be tens of megabytes and every open
     * window asks, so it is read again only when the file has changed.
     *
     * @param path the transcript file.
     * @return the story; empty when the file cannot be read.
     */
    internal fun storyOf(path: String): TranscriptStory {
        val file = File(path)
        val size = file.length()
        val modified = file.lastModified()
        stories[path]?.takeIf { it.size == size && it.modified == modified }?.let { return it.story }
        return TranscriptLines.story(file).also {
            if (stories.size > 64) stories.clear()
            stories[path] = ReadStory(size, modified, it)
        }
    }

    /**
     * The details of one conversation: its screen (the pane's own while it runs, the last saved one
     * once it ended), what the person asked it, the work left in the folder it worked in, and for a
     * closed one what resuming would do.
     *
     * @param rec the conversation's record.
     * @param tasks the tasks, for the `open` action a resume would use; null when there are none.
     * @return the answer.
     */
    fun ofRecord(rec: AgentSessionRecord, tasks: TaskIndex?): WindowEnvelope.AgentDetails {
        val open = !rec.isEnded
        val snapshot = if (open) rec.terminalSessionId?.let { livePaneSnapshot(it) } else null
        val rows = snapshot?.screenRows?.invoke()
        val (screen, styled) = if (rows != null) PeekScreen.styled(rows)
        else PeekScreen.plain(if (snapshot != null) snapshot.screenText() else rec.lastScreen) to emptyList()
        val story = rec.transcriptPath?.let(::storyOf) ?: TranscriptStory()
        // The transcript knows where the conversation went on to work; the record only where it started.
        val dir = listOfNotNull(story.cwd, rec.cwd).firstOrNull { it.isNotBlank() && File(it).isDirectory }
        val work = GitCatalog.folderWork(dir)?.let(::worktreeOf)
        return WindowEnvelope.AgentDetails(
            recordId = rec.conversationId,
            live = snapshot != null,
            screen = screen,
            styled = styled,
            turns = story.turns,
            work = listOfNotNull(work),
            note = if (open) null else resumeNoteOf(rec, tasks?.openOfMain(rec)),
        )
    }

    /**
     * The work left in the git worktrees a task's conversations worked in. Every conversation of the
     * task counts, closed ones too while their folder is there, since a run's worktree with unpushed
     * commits is what the person most needs to see. Conversations in one worktree count once, and a
     * clean worktree is left out.
     *
     * @param taskId the task.
     * @param records every conversation record the tracker knows.
     * @return the answer, the most recently worked-in worktree first, at most [MAX_WORKTREES].
     */
    fun ofTask(taskId: String, records: List<AgentSessionRecord>): WindowEnvelope.AgentDetails {
        // Each folder once, with the last time anything happened in it.
        val lastByFolder = HashMap<String, Long>()
        for (rec in records) {
            if (rec.taskId != taskId) continue
            val dir = rec.cwd?.takeIf { it.isNotBlank() } ?: continue
            val at = rec.endedAt ?: rec.stateSince
            if ((lastByFolder[dir] ?: Long.MIN_VALUE) < at) lastByFolder[dir] = at
        }
        val lastByRoot = HashMap<String, Pair<Long, GitCatalog.FolderWork>>()
        for ((dir, at) in lastByFolder) {
            val work = GitCatalog.folderWork(dir) ?: continue
            if ((lastByRoot[work.root]?.first ?: Long.MIN_VALUE) < at) lastByRoot[work.root] = at to work
        }
        val worktrees = lastByRoot.values.sortedByDescending { it.first }.map { it.second }
            .filter { it.changed > 0 || it.untracked > 0 || (it.unpushed ?: 0) > 0 }
            .take(MAX_WORKTREES)
            .map(::worktreeOf)
        return WindowEnvelope.AgentDetails(taskId = taskId, work = worktrees)
    }

    /** [work] in the shape the wire carries; the worktree is named by its folder. */
    private fun worktreeOf(work: GitCatalog.FolderWork): WorktreeWork = WorktreeWork(
        name = File(work.root).name.ifEmpty { work.root },
        branch = work.branch,
        changed = work.changed,
        added = work.added,
        removed = work.removed,
        untracked = work.untracked,
        unpushed = work.unpushed,
    )
}
