/**
 * One-time move of the two tables that used to hold tasks into the single `tasks` table.
 *
 * A database written by an earlier version keeps a task's name in `agent_tasks` and everything else
 * about it (start, status, the conversation that speaks for it) in a second table. Opening such a
 * database copies both into `tasks` and drops them. A database that has neither table, which is a
 * new one or one that has been moved already, is left alone, so running this on every start
 * changes nothing after the first.
 *
 * Contains [TasksMigration].
 *
 * @see SettingsRepository
 */
package se.soderbjorn.lunamux.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * Copies the old task tables into `tasks` and removes them.
 *
 * Called by [SettingsRepository] while it opens the database, after every table it needs exists.
 * The copy and the drops happen in one transaction, so a failure leaves the old tables as they were
 * and the next start tries again. It is the only code that still names the old tables and columns.
 *
 * @see SettingsRepository
 */
internal object TasksMigration {
    private const val OLD_TASKS = "agent_tasks"
    private const val OLD_META = "task_meta"

    /**
     * Move every task from the old tables into `tasks`, then drop the old tables.
     *
     * A task's id comes from either old table. Its name, creation and rename times come from
     * `agent_tasks`; its start, status, question flag and main conversation come from `task_meta`.
     * Nothing is invented: a task with no row in one table has empty columns for what that table
     * held. A task whose main is named only by a pane (an older row has no conversation) takes the
     * conversation of the newest session record that ran in that pane, preferring a record that
     * carries the task's id: the pane may have been the person's own session, which never carried
     * it. A record that carries another task's id is never taken. A pane with no record gives no main.
     *
     * @param driver the open database; `tasks` and `agent_sessions` must exist.
     * @param inTransaction runs the given block as one transaction against [driver], rolling it back
     *   if the block throws.
     */
    fun run(driver: SqlDriver, inTransaction: (() -> Unit) -> Unit) {
        val tables = tableNames(driver)
        val hasTasks = OLD_TASKS in tables
        val hasMeta = OLD_META in tables
        if (!hasTasks && !hasMeta) return

        val metaColumns = if (hasMeta) columnNames(driver, OLD_META) else emptySet()
        // An old table may predate a column; what it lacked reads as empty.
        fun meta(column: String) = if (column in metaColumns) "m.$column" else "NULL"
        val sessionOf = { match: String ->
            "(SELECT conversation FROM (SELECT COALESCE(s.agent_session_id, s.id) AS conversation, " +
                "COALESCE(s.task_id = ids.id, 0) AS member, s.created_at AS at " +
                "FROM agent_sessions s WHERE ($match) AND (s.task_id IS NULL OR s.task_id = ids.id)) " +
                "ORDER BY member DESC, at DESC LIMIT 1)"
        }
        val main = "COALESCE(${meta("lead_agent_session")}, CASE WHEN ${meta("lead_leaf")} IS NOT NULL " +
            "THEN ${sessionOf("s.leaf_id = ${meta("lead_leaf")}")} " +
            "ELSE ${sessionOf("s.terminal_session_id = ${meta("lead_session")}")} END)"

        val ids = listOfNotNull(
            if (hasTasks) "SELECT id FROM $OLD_TASKS" else null,
            if (hasMeta) "SELECT task_id FROM $OLD_META" else null,
        ).joinToString(" UNION ")
        val copy = """
            INSERT OR IGNORE INTO tasks(id, title, main_conversation, status, status_at, question_waits,
                                        started_at, created_at, updated_at)
            SELECT ids.id,
                   ${if (hasTasks) "a.title" else "NULL"},
                   ${if (hasMeta) main else "NULL"},
                   ${meta("status")}, ${meta("status_at")}, ${meta("question_waits")}, ${meta("started_at")},
                   ${if (hasTasks) "a.created_at" else "NULL"}, ${if (hasTasks) "a.updated_at" else "NULL"}
            FROM ($ids) ids
            ${if (hasTasks) "LEFT JOIN $OLD_TASKS a ON a.id = ids.id" else ""}
            ${if (hasMeta) "LEFT JOIN $OLD_META m ON m.task_id = ids.id" else ""}
        """.trimIndent()

        inTransaction {
            driver.execute(null, copy, 0)
            if (hasTasks) driver.execute(null, "DROP TABLE $OLD_TASKS", 0)
            if (hasMeta) driver.execute(null, "DROP TABLE $OLD_META", 0)
        }
    }

    /** The names of the tables the database holds. */
    private fun tableNames(driver: SqlDriver): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table'",
        mapper = { cursor: SqlCursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) cursor.getString(0)?.let(names::add)
            QueryResult.Value(names)
        },
        parameters = 0,
    ).value

    /** The names of the columns of [table]. */
    private fun columnNames(driver: SqlDriver, table: String): Set<String> = driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM pragma_table_info('$table')",
        mapper = { cursor: SqlCursor ->
            val names = mutableSetOf<String>()
            while (cursor.next().value) cursor.getString(0)?.let(names::add)
            QueryResult.Value(names)
        },
        parameters = 0,
    ).value
}
