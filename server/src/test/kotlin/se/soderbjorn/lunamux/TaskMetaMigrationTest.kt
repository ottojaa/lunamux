/**
 * Tests that a `task_meta` table written before the question flag existed is upgraded in place
 * when the database is opened, keeping its rows, and that the flag then round-trips with the
 * status it belongs to.
 *
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Opening a database whose `task_meta` table predates `question_waits` adds the column and loses nothing. */
class TaskMetaMigrationTest {
    /** An old table gains `question_waits`, keeps its status, and stores the flag with a new status. */
    @Test
    fun `a task_meta table without question_waits gains it and keeps its rows`() {
        val dir = File.createTempFile("lunamux-migrate-meta", "").apply { delete(); mkdirs(); deleteOnExit() }
        val db = File(dir, "previous.db")
        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE task_meta (
                        task_id TEXT NOT NULL PRIMARY KEY, started_at INTEGER NOT NULL, lead_session TEXT,
                        status TEXT, status_by TEXT, status_at INTEGER, lead_leaf TEXT, lead_agent_session TEXT)""",
                )
                s.execute(
                    """INSERT INTO task_meta (task_id, started_at, status, status_by, status_at, lead_agent_session)
                       VALUES ('old', 5, 'halfway', 'a', 7, 'conv-1')""",
                )
            }
        }
        val repo = SettingsRepository(db)
        val old = repo.loadTaskMeta().single { it.taskId == "old" }
        assertEquals("halfway", old.status)
        assertEquals("conv-1", old.mainConversation)
        assertFalse(old.questionWaits)

        repo.setTaskStatus("old", "which database?", "a", 9, questionWaits = true)
        assertTrue(repo.loadTaskMeta().single { it.taskId == "old" }.questionWaits)
        repo.setTaskStatus("old", "migrating", "a", 10)
        assertFalse(repo.loadTaskMeta().single { it.taskId == "old" }.questionWaits)
        repo.setTaskStatus("old", null, null, 11, questionWaits = true)
        val cleared = repo.loadTaskMeta().single { it.taskId == "old" }
        assertNull(cleared.status)
        assertFalse(cleared.questionWaits)
    }
}
