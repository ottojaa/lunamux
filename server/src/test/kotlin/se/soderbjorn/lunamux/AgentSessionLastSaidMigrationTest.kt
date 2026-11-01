/**
 * Tests that an `agent_sessions` table that already has the repository-root and
 * search-text columns but not `last_said` is upgraded in place when the database
 * is opened, keeping its rows, and that the new column then round-trips.
 *
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionLastSaidMigrationTest {
    @Test
    fun `a table without last_said gains it, keeps its rows and stores what was said`() {
        val dir = File.createTempFile("lunamux-migrate", "").apply { delete(); mkdirs(); deleteOnExit() }
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
                        search_text TEXT)""",
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
        assertNull(loaded.lastSaid)

        repo.saveAgentSession(loaded.copy(lastSaid = "Three runs are ready."))
        assertEquals("Three runs are ready.", repo.findAgentSessionById("prev")!!.lastSaid)
        // The upsert carries the stored search text over, as before.
        assertEquals("prev", repo.searchAgentSessions("kept words").single().record.id)
        assertEquals("Three runs are ready.", repo.searchAgentSessions("kept words").single().record.lastSaid)
    }

    @Test
    fun `a fresh database stores lastSaid on a new record`() {
        val dir = File.createTempFile("lunamux-fresh", "").apply { delete(); mkdirs(); deleteOnExit() }
        val repo = SettingsRepository(File(dir, "fresh.db"))
        repo.saveAgentSession(AgentSessionRecord(id = "r", agent = "claude", state = "idle", lastSaid = "Hello"))
        assertEquals("Hello", repo.findAgentSessionById("r")!!.lastSaid)
    }
}
