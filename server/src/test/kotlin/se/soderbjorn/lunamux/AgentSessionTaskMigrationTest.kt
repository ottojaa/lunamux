/**
 * Tests that an `agent_sessions` table that has `last_said` but no `task_id` is upgraded in place
 * when the database is opened, keeping its rows, that the new column and the `agent_tasks` table
 * then round-trip, and that a fresh database has both.
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

class AgentSessionTaskMigrationTest {
    @Test
    fun `a table without task_id gains it, keeps its rows and stores the task`() {
        val dir = File.createTempFile("lunamux-migrate-task", "").apply { delete(); mkdirs(); deleteOnExit() }
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
                        search_text TEXT, last_said TEXT)""",
                )
                s.execute(
                    """INSERT INTO agent_sessions (id, agent, source, links, state, state_since, created_at, ended_at, title, search_text, last_said)
                       VALUES ('prev', 'claude', 'hooks', '', 'ended', 5, 5, 5, 'Previous session', 'kept words', 'Said it')""",
                )
                s.execute("PRAGMA user_version = 1")
            }
        }
        val repo = SettingsRepository(db)
        val loaded = repo.findAgentSessionById("prev")!!
        assertEquals("Previous session", loaded.title)
        assertEquals("Said it", loaded.lastSaid)
        assertNull(loaded.taskId)
        assertEquals(emptyList(), repo.loadAgentTasks())

        repo.saveAgentSession(loaded.copy(taskId = "fix-login-ab12"))
        assertEquals("fix-login-ab12", repo.findAgentSessionById("prev")!!.taskId)
        assertEquals("fix-login-ab12", repo.searchAgentSessions("kept words").single().record.taskId)
        assertEquals("fix-login-ab12", repo.loadRecentEndedAgentSessions(10).single().taskId)
        // The upsert still carries the stored search text over.
        assertEquals("prev", repo.searchAgentSessions("kept words").single().record.id)

        repo.saveAgentTask(SettingsRepository.AgentTaskRow("fix-login-ab12", "Fix login", 10, 10))
        repo.saveAgentTask(SettingsRepository.AgentTaskRow("fix-login-ab12", "Fix the login", 10, 20))
        assertEquals(listOf(SettingsRepository.AgentTaskRow("fix-login-ab12", "Fix the login", 10, 20)), repo.loadAgentTasks())
    }

    @Test
    fun `a fresh database stores taskId on a new record and keeps tasks`() {
        val dir = File.createTempFile("lunamux-fresh-task", "").apply { delete(); mkdirs(); deleteOnExit() }
        val db = File(dir, "fresh.db")
        val repo = SettingsRepository(db)
        repo.saveAgentSession(AgentSessionRecord(id = "r", agent = "claude", state = "idle", taskId = "t1"))
        repo.saveAgentTask(SettingsRepository.AgentTaskRow("t1", "One", 1, 1))
        assertEquals("t1", repo.findAgentSessionById("r")!!.taskId)

        val reopened = SettingsRepository(db)
        assertEquals("t1", reopened.findAgentSessionById("r")!!.taskId)
        assertEquals("One", reopened.loadAgentTasks().single().title)
    }
}
