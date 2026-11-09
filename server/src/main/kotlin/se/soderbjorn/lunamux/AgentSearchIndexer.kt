/**
 * Background worker that keeps the searchable text of agent sessions up to date.
 *
 * Reading a transcript can take a while, so none of it happens on the tracker's lock or on a
 * request thread. This file contains [AgentSearchIndexer], which runs three kinds of job one at a
 * time on a single daemon thread: index a session that just ended, backfill sessions recorded
 * before search existed, and import Claude Code history.
 */
package se.soderbjorn.lunamux

import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Writes `search_text` for ended Claude sessions and imports Claude Code history. */
class AgentSearchIndexer(
    private val repo: SettingsRepository,
    private val projects: () -> File = ClaudeHistoryImport::projectsDir,
) {
    private val log = LoggerFactory.getLogger(AgentSearchIndexer::class.java)

    /** Tells whether a Claude session without a known id is open in a folder. */
    @Volatile
    var runningIn: (String) -> Boolean = { false }
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agent-search-indexer").apply { isDaemon = true }
    }

    /** Queue the transcript text of a record that just ended. */
    fun indexEnded(record: AgentSessionRecord) {
        val path = record.transcriptPath ?: return
        if (record.agent != "claude") return
        submit("index ${record.id}") { storeText(record.id, path) }
    }

    /** Queue a pass over ended records that have a transcript but no search text yet. */
    fun backfill() = submit("backfill") {
        repo.loadAgentSessionsMissingSearchText().forEach { (id, path) -> storeText(id, path) }
    }

    /** Queue an import of Claude Code's own transcript history. */
    fun importHistory() = submit("import") { ClaudeHistoryImport.run(repo, projects(), runningIn) }

    /** Wait until every queued job has finished. */
    fun awaitIdle() {
        worker.submit {}.get()
    }

    private fun storeText(id: String, path: String) {
        // An unreadable or deleted transcript stores "" so it is not retried at every start.
        repo.setAgentSessionSearchText(id, AgentTranscriptText.extract(path).orEmpty())
    }

    private fun submit(what: String, job: () -> Unit) {
        worker.execute { runCatching(job).onFailure { log.warn("Search indexer job '{}' failed", what, it) } }
    }
}
