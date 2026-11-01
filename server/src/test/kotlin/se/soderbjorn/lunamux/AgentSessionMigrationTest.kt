/**
 * Tests that an `agent_sessions` table written by an earlier version (without
 * the repository-root and search-text columns) is upgraded in place when the
 * database is opened, keeping its rows.
 *
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionMigrationTest {
    @Test
    fun `an old agent_sessions table gains the new columns and keeps its rows`() {
        val dir = File.createTempFile("lunamux-migrate", "").apply { delete(); mkdirs(); deleteOnExit() }
        val db = File(dir, "old.db")
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE agent_sessions (
                        id TEXT NOT NULL PRIMARY KEY, agent TEXT NOT NULL, agent_session_id TEXT, source TEXT NOT NULL,
                        leaf_id TEXT, terminal_session_id TEXT, cwd TEXT, repo TEXT, branch TEXT, worktree_path TEXT,
                        title TEXT, first_prompt TEXT, transcript_path TEXT, links TEXT NOT NULL DEFAULT '',
                        state TEXT NOT NULL, state_since INTEGER NOT NULL, now_line TEXT, question TEXT,
                        last_screen TEXT, created_at INTEGER NOT NULL, ended_at INTEGER)""",
                )
                s.execute(
                    """INSERT INTO agent_sessions (id, agent, source, links, state, state_since, created_at, ended_at, title)
                       VALUES ('old', 'claude', 'hooks', '', 'ended', 5, 5, 5, 'Old session')""",
                )
                s.execute("PRAGMA user_version = 1")
            }
        }
        val repo = SettingsRepository(db)
        val loaded = repo.findAgentSessionById("old")!!
        assertEquals("Old session", loaded.title)
        assertNull(loaded.repoRoot)
        repo.setAgentSessionSearchText("old", "searchable words")
        assertEquals("old", repo.searchAgentSessions("searchable").single().record.id)
    }
}
