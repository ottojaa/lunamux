/**
 * Tests that a database written when tasks lived in two tables is moved into the single `tasks`
 * table when it is opened, losing no task, main conversation, status or start time, and that
 * opening it again changes nothing.
 *
 * @see se.soderbjorn.lunamux.persistence.TasksMigration
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.TaskRow
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening a database that has the old task tables moves every task into `tasks` once. */
class TasksMigrationTest {
    private fun scratchDb(): File {
        val dir = File.createTempFile("lunamux-migrate-tasks", "").apply { delete(); mkdirs(); deleteOnExit() }
        return File(dir, "previous.db")
    }

    private fun tableNames(db: File): Set<String> = DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { r ->
                generateSequence { if (r.next()) r.getString(1) else null }.toSet()
            }
        }
    }

    /** Every kind of old task lands in `tasks` with what it had, and a second start leaves it alone. */
    @Test
    fun `old task tables move into tasks with every main, status and start, and a second start changes nothing`() {
        val db = scratchDb()
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.execute("CREATE TABLE agent_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                s.execute(
                    """CREATE TABLE task_meta (
                        task_id TEXT NOT NULL PRIMARY KEY, started_at INTEGER NOT NULL, lead_session TEXT,
                        status TEXT, status_by TEXT, status_at INTEGER, lead_leaf TEXT, lead_agent_session TEXT)""",
                )
                s.execute(
                    """CREATE TABLE agent_sessions (
                        id TEXT NOT NULL PRIMARY KEY, agent TEXT NOT NULL, agent_session_id TEXT, source TEXT NOT NULL,
                        leaf_id TEXT, terminal_session_id TEXT, links TEXT NOT NULL DEFAULT '',
                        state TEXT NOT NULL, state_since INTEGER NOT NULL, created_at INTEGER NOT NULL, ended_at INTEGER)""",
                )
                s.execute("INSERT INTO agent_tasks VALUES ('started', 'Started', 10, 20), ('unstarted', 'Unstarted', 11, 11), ('pane-only', 'Pane only', 12, 12), ('leaf-only', 'Leaf only', 13, 13)")
                s.execute(
                    """INSERT INTO task_meta (task_id, started_at, status, status_by, status_at, lead_agent_session, lead_session, lead_leaf) VALUES
                       ('started', 5, 'halfway', 'a', 7, 'conv-1', NULL, NULL),
                       ('from-file', 9, NULL, NULL, NULL, NULL, NULL, NULL),
                       ('pane-only', 6, NULL, NULL, NULL, NULL, 's4', NULL),
                       ('leaf-only', 8, NULL, NULL, NULL, NULL, 's9', 'n3')""",
                )
                // The conversations the two pane-only mains were running: the newer record wins, and a
                // record of another task is never taken.
                s.execute(
                    """INSERT INTO agent_sessions (id, agent_session_id, agent, source, terminal_session_id, leaf_id, state, state_since, created_at) VALUES
                       ('old-run', 'conv-old', 'claude', 'hooks', 's4', NULL, 'ended', 1, 1),
                       ('new-run', 'conv-pane', 'claude', 'hooks', 's4', NULL, 'ended', 1, 3),
                       ('leaf-run', NULL, 'claude', 'hooks', NULL, 'n3', 'ended', 1, 2)""",
                )
                s.execute("UPDATE agent_sessions SET terminal_session_id = 's4' WHERE id = 'new-run'")
                s.execute("ALTER TABLE agent_sessions ADD COLUMN task_id TEXT")
                s.execute("UPDATE agent_sessions SET task_id = 'pane-only' WHERE id IN ('old-run', 'new-run')")
                s.execute("UPDATE agent_sessions SET task_id = 'leaf-only' WHERE id = 'leaf-run'")
                s.execute("PRAGMA user_version = 1")
            }
        }

        val first = SettingsRepository(db).loadTasks().associateBy { it.id }
        assertEquals(
            mapOf(
                "started" to TaskRow("started", "Started", "conv-1", "halfway", 7, false, 5, 10, 20),
                "unstarted" to TaskRow("unstarted", "Unstarted", createdAt = 11, updatedAt = 11),
                "from-file" to TaskRow("from-file", startedAt = 9),
                "pane-only" to TaskRow("pane-only", "Pane only", "conv-pane", startedAt = 6, createdAt = 12, updatedAt = 12),
                "leaf-only" to TaskRow("leaf-only", "Leaf only", "leaf-run", startedAt = 8, createdAt = 13, updatedAt = 13),
            ),
            first,
        )
        assertFalse("agent_tasks" in tableNames(db) || "task_meta" in tableNames(db))

        val second = SettingsRepository(db).loadTasks().associateBy { it.id }
        assertEquals(first, second)
    }

    /** The question flag is stored with the status it belongs to and goes with it. */
    @Test
    fun `a status stores its question flag and clearing the status clears the flag`() {
        val repo = SettingsRepository(scratchDb())
        repo.insertTask("t", "Task", 1)
        repo.stampTaskStart("t", 2)
        repo.setTaskStatus("t", "which database?", 9, questionWaits = true)
        assertTrue(repo.loadTasks().single().questionWaits)
        repo.setTaskStatus("t", "migrating", 10)
        assertFalse(repo.loadTasks().single().questionWaits)
        repo.setTaskStatus("t", "which one?", 11, questionWaits = true)
        repo.setTaskStatus("t", null, 12, questionWaits = true)
        val cleared = repo.loadTasks().single()
        assertNull(cleared.status)
        assertFalse(cleared.questionWaits)
        assertEquals(2L, cleared.startedAt, "the start stays")
    }
}
