/**
 * Tests that an `agent_sessions` table that has `task_id` but no `started_by` is upgraded in place
 * when the database is opened, keeping its rows (which then read as started by nobody known), and
 * that the new column then round-trips through every query that reads a record.
 *
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.Json
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionStartedByMigrationTest {
    @Test
    fun `a table without started_by gains it, keeps its rows and stores who started a session`() {
        val dir = File.createTempFile("lunamux-migrate-started", "").apply { delete(); mkdirs(); deleteOnExit() }
        val db = File(dir, "previous.db")
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE agent_sessions (
                        id TEXT NOT NULL PRIMARY KEY, agent TEXT NOT NULL, agent_session_id TEXT, source TEXT NOT NULL,
                        leaf_id TEXT, terminal_session_id TEXT, cwd TEXT, repo TEXT, branch TEXT, worktree_path TEXT,
                        title TEXT, first_prompt TEXT, transcript_path TEXT, links TEXT NOT NULL DEFAULT '',
                        state TEXT NOT NULL, state_since INTEGER NOT NULL, now_line TEXT, question TEXT,
                        last_screen TEXT, created_at INTEGER NOT NULL, ended_at INTEGER, repo_root TEXT,
                        search_text TEXT, last_said TEXT, task_id TEXT)""",
                )
                s.execute(
                    """INSERT INTO agent_sessions (id, agent, source, links, state, state_since, created_at, ended_at, title, search_text)
                       VALUES ('prev', 'claude', 'hooks', '', 'ended', 5, 5, 5, 'Previous session', 'kept words')""",
                )
                s.execute("PRAGMA user_version = 1")
            }
        }
        val repo = SettingsRepository(db)
        val loaded = repo.findAgentSessionById("prev")!!
        assertEquals("Previous session", loaded.title)
        assertNull(loaded.startedBy)

        repo.saveAgentSession(loaded.copy(startedBy = "tool"))
        assertEquals("tool", repo.findAgentSessionById("prev")!!.startedBy)
        assertEquals("tool", repo.searchAgentSessions("kept words").single().record.startedBy)
        assertEquals("tool", repo.loadRecentEndedAgentSessions(0, 10).single().startedBy)
    }

    @Test
    fun `a fresh database stores startedBy on a new record, also the ones found by task`() {
        val dir = File.createTempFile("lunamux-fresh-started", "").apply { delete(); mkdirs(); deleteOnExit() }
        val repo = SettingsRepository(File(dir, "fresh.db"))
        repo.saveAgentSession(AgentSessionRecord(id = "u", agent = "claude", state = "idle", startedBy = "user", taskId = "t1"))
        repo.saveAgentSession(AgentSessionRecord(id = "n", agent = "claude", state = "idle"))
        assertEquals("user", repo.findAgentSessionById("u")!!.startedBy)
        assertNull(repo.findAgentSessionById("n")!!.startedBy)
        assertEquals("user", repo.loadAgentSessionsOfTasks(listOf("t1")).single().startedBy)
    }

    @Test
    fun `a summary sent without the new fields decodes with them unknown`() {
        val old = """{"id":"x","agent":"claude","state":"idle","stateSince":1}"""
        val row = Json { ignoreUnknownKeys = true }.decodeFromString(AgentSessionSummary.serializer(), old)
        assertNull(row.startedBy)
        assertNull(row.startedAt)
    }
}
