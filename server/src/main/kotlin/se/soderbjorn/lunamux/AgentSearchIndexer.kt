/**
 * Background worker that keeps the searchable text of agent conversations up to date.
 *
 * Reading a transcript can take a while, so none of it happens under the tracker's lock or on a
 * request thread. This file contains [AgentSearchIndexer], which runs its jobs one at a time on a
 * single daemon thread: index a conversation that just ended, backfill the ones from the last month
 * that have no text yet (and drop the text of older ones), and import Claude Code history.
 *
 * @see AgentSessionSearch
 * @see ClaudeHistoryImport
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Writes the `agent_session_text` of ended Claude conversations and imports Claude Code history.
 *
 * Created once by `Application`; the tracker calls [indexEnded] when a record closes.
 *
 * @param repo where the text is stored.
 * @param projects where Claude Code keeps its transcripts, for [importHistory].
 */
class AgentSearchIndexer(
    private val repo: SettingsRepository,
    private val projects: () -> File = ClaudeHistoryImport::projectsDir,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(AgentSearchIndexer::class.java)

    /** Tells whether a Claude conversation of unknown id is open in a folder, so import skips its file. */
    @Volatile
    var runningIn: (String) -> Boolean = { false }

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-search-indexer").apply { isDaemon = true }
    }

    /**
     * Queue the transcript text of a record that just ended.
     *
     * @param record the closed record; only a Claude one with a transcript is read.
     */
    fun indexEnded(record: AgentSessionRecord) {
        val path = record.transcriptPath ?: return
        if (record.agent != AgentWords.CLAUDE) return
        submit("index ${record.id}") {
            storeText(record.id, path)
            // A server that stays up for weeks would otherwise keep text past the month search looks back.
            repo.dropOldSearchText(clock() - AgentSessionSearch.SEARCH_WINDOW_MS)
        }
    }

    /** Queue a pass that reads the month's conversations without text and drops the text of older ones. */
    fun backfill() = submit("backfill") {
        val cutoff = clock() - AgentSessionSearch.SEARCH_WINDOW_MS
        repo.dropOldSearchText(cutoff)
        repo.loadAgentSessionsMissingText(cutoff).forEach { (id, path) -> storeText(id, path) }
    }

    /** Queue an import of Claude Code's own transcript history. */
    fun importHistory() = submit("import") { ClaudeHistoryImport.run(repo, projects(), runningIn, clock()) }

    /** Wait until every queued job has finished. */
    fun awaitIdle() {
        worker.submit {}.get()
    }

    private fun storeText(id: String, path: String) {
        // An unreadable or deleted transcript stores "" so it is not retried at every start.
        repo.setAgentSessionSearchText(id, TranscriptLines.read(File(path))?.text.orEmpty())
    }

    private fun submit(what: String, job: () -> Unit) {
        worker.execute { runCatching(job).onFailure { log.warn("Search indexer job '{}' failed", what, it) } }
    }
}
