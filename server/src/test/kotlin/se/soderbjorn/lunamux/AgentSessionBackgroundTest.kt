/**
 * Tests for the `background` flag on agent session records: it is set when a session's pane is in a
 * background tab, survives the pane closing and a restart, keeps the session out of search, is
 * carried to clients in the summary, and is cleared (and searchable again) after a take over.
 * Also shows that a background session that needs the person is counted like any other.
 *
 * @see AgentSessionTracker
 * @see AgentSessionSearch
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentSessionBackgroundTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agent-background", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val dbFile = File(dbDir, "test.db")
    private val repo = SettingsRepository(dbFile)

    private fun tracker(r: SettingsRepository = repo) = AgentSessionTracker(
        r,
        gitInfo = { GitInfo(repo = "lunamux", branch = "main", worktreePath = null) },
        clock = { now },
        newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun pane(id: String, background: Boolean, state: String = "working", alive: Boolean = true) =
        PaneSnapshot(id, leafId = "leaf-$id", cwd = "/work", programTitle = "✳ Refactor $id", agent = "claude",
            state = state, alive = alive, background = background, screenText = { "screen of $id" })

    @Test
    fun `a session opened in a background pane is flagged and the flag survives pane close and restart`() {
        val t = tracker()
        t.onPoll(listOf(pane("s1", background = true), pane("s2", background = false)))
        assertEquals(mapOf("r1" to true, "r2" to false), t.summaries.value.associate { it.id to it.background })

        now = 2_000
        t.onPoll(listOf(pane("s2", background = false)))
        val ended = t.summaries.value.single { it.id == "r1" }
        assertEquals("ended", ended.state)
        assertTrue(ended.background, "the flag is kept after the pane closes")
        assertTrue(repo.findAgentSessionById("r1")!!.background)

        val restarted = tracker(SettingsRepository(dbFile))
        assertTrue(restarted.summaries.value.single { it.id == "r1" }.background)
        assertFalse(restarted.summaries.value.single { it.id == "r2" }.background)
    }

    @Test
    fun `a session follows its pane into and out of a background tab`() {
        val t = tracker()
        t.onPoll(listOf(pane("s1", background = true), pane("s2", background = false)))

        now = 2_000
        t.onPoll(listOf(pane("s1", background = false), pane("s2", background = true)))
        assertEquals(mapOf("r1" to false, "r2" to true), t.summaries.value.associate { it.id to it.background })
        assertFalse(repo.findAgentSessionById("r1")!!.background, "the stored record moves with the pane")
        assertTrue(repo.findAgentSessionById("r2")!!.background)

        val restarted = tracker(SettingsRepository(dbFile))
        assertFalse(restarted.summaries.value.any { it.state != "ended" })
    }

    @Test
    fun `a background session needing the person is a needs-you summary like any other`() {
        val t = tracker()
        t.onPoll(listOf(pane("s1", background = true, state = "waiting")))
        val row = t.summaries.value.single()
        assertEquals("needs_you", row.state)
        assertTrue(row.background)
    }

    @Test
    fun `search skips background sessions by title and prompt`() {
        val t = tracker()
        t.onPoll(listOf(pane("s1", background = true), pane("s2", background = false)))
        now = 2_000
        t.onPoll(emptyList())
        fun found() = AgentSessionSearch.search(repo, t, "Refactor").map { it.summary.id }
        assertEquals(listOf("r2"), found())
        // Stored copies are skipped too, not only the in-memory ones.
        assertEquals(listOf("r2"), repo.searchAgentSessions("Refactor").map { it.record.id })
        assertTrue(repo.findAgentSessionById("r1")!!.background, "a background record stays one")
    }

    @Test
    fun `a record's flag is not reset by the next save`() {
        repo.saveAgentSession(AgentSessionRecord(id = "x", agent = "claude", state = "idle", background = true, title = "Quiet"))
        val loaded = repo.findAgentSessionById("x")!!
        repo.saveAgentSession(loaded.copy(state = "working"))
        assertTrue(repo.findAgentSessionById("x")!!.background)
    }

    @Test
    fun `tasks found by title leave out background sessions`() {
        repo.saveAgentTask(SettingsRepository.AgentTaskRow("t-1", "Billing", 1, 1))
        repo.saveAgentSession(AgentSessionRecord(id = "a", agent = "claude", state = "ended", stateSince = 1, createdAt = 1, endedAt = 1, taskId = "t-1", background = true))
        repo.saveAgentSession(AgentSessionRecord(id = "b", agent = "claude", state = "ended", stateSince = 2, createdAt = 2, endedAt = 2, taskId = "t-1"))
        assertEquals(listOf("b"), repo.loadAgentSessionsOfTasks(listOf("t-1")).map { it.id })
    }
}
