/**
 * Tests that a database written by the first version of the `agent_sessions` table (no repository
 * root, search text, last said or task columns, and no `tasks` table) is upgraded in place when it is
 * opened: its rows stay, and each new column then stores and returns what is put in it.
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
    fun `an old agent_sessions table gains its newer columns and keeps its rows`() {
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
        assertNull(loaded.lastSaid)
        assertNull(loaded.taskId)
        assertEquals(emptyList(), repo.loadTasks())

        repo.setAgentSessionSearchText("old", "searchable words")
        repo.saveAgentSession(loaded.copy(lastSaid = "Three runs are ready.", taskId = "fix-login-ab12"))
        repo.insertTask("fix-login-ab12", "Fix login", 10)
        repo.renameTask("fix-login-ab12", "Fix the login", 20)

        val found = repo.searchAgentSessions("searchable").single().record
        assertEquals("Three runs are ready.", found.lastSaid, "saving a record keeps the stored search text")
        assertEquals("fix-login-ab12", found.taskId)
        assertEquals(
            listOf(SettingsRepository.TaskRow("fix-login-ab12", title = "Fix the login", createdAt = 10, updatedAt = 20)),
            repo.loadTasks(),
        )
        assertEquals("fix-login-ab12", SettingsRepository(db).findAgentSessionById("old")!!.taskId, "and it survives a reopen")
    }
}
