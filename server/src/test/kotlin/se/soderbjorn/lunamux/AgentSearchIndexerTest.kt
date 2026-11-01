/**
 * Tests for [AgentSearchIndexer]: that an ended Claude record gets its
 * conversation text written for search, that records saved before search
 * existed are filled in by the start-up backfill, and that records the
 * indexer has no transcript for are left alone.
 *
 * @see AgentSearchIndexer
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSearchIndexerTest {
    private val root = File.createTempFile("lunamux-indexer", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(root, "test.db"))
    private val indexer = AgentSearchIndexer(repo) { File(root, "no-projects") }

    private fun transcript(name: String, said: String): String {
        val f = File(root, "$name.jsonl")
        f.writeText("""{"type":"user","message":{"role":"user","content":"$said"}}""" + "\n")
        return f.path
    }

    private fun ended(id: String, agent: String = "claude", transcriptPath: String?) = AgentSessionRecord(
        id = id, agent = agent, transcriptPath = transcriptPath, state = "ended",
        stateSince = 1, createdAt = 1, endedAt = 1,
    ).also(repo::saveAgentSession)

    private fun hits(word: String) = repo.searchAgentSessions(word).map { it.record.id }

    @Test
    fun `a record that ends gets its conversation text written`() {
        val rec = ended("a", transcriptPath = transcript("a", "remember the zebrafish"))
        assertTrue(hits("zebrafish").isEmpty())

        indexer.indexEnded(rec)
        indexer.awaitIdle()
        assertEquals(listOf("a"), hits("zebrafish"))
    }

    @Test
    fun `records without a Claude transcript are not indexed`() {
        indexer.indexEnded(ended("codex", agent = "codex", transcriptPath = transcript("c", "codexword")))
        indexer.indexEnded(ended("none", transcriptPath = null))
        indexer.awaitIdle()
        assertTrue(hits("codexword").isEmpty())
    }

    @Test
    fun `the backfill fills records saved before search existed and marks unreadable ones`() {
        ended("old", transcriptPath = transcript("old", "an ancient narwhal"))
        ended("gone", transcriptPath = File(root, "deleted.jsonl").path)

        indexer.backfill()
        indexer.awaitIdle()
        assertEquals(listOf("old"), hits("narwhal"))
        // The unreadable one is stored as empty text, so the next start does not try again.
        assertTrue(repo.loadAgentSessionsMissingSearchText().isEmpty())
    }
}
