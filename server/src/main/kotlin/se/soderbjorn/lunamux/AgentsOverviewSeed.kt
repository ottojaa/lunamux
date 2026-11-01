/**
 * A seeder that fills a scratch database and task folder with the cases the agents overview has
 * to show, so the overview can be looked at (or reviewed) without weeks of real use.
 *
 * It holds [AgentsOverviewSeed]: about twenty ended agent sessions that were last active today,
 * two, six, twelve and thirty days ago, some of them grouped into agent-made tasks, plus a few
 * task files whose members are live. It is run by the Gradle task `seedAgentsOverview` (through
 * `scripts/seed-agents-overview.sh`) while the scratch server is stopped, and by a test.
 *
 * The seeder only ever writes to the paths it is given and refuses the folders a real
 * installation uses.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.system.exitProcess

/**
 * Seeds a scratch database and a scratch task folder for the agents overview.
 *
 * Called from [main] (run by the `seedAgentsOverview` Gradle task) and from the seeder's test.
 * Everything it writes has a fixed id (`seed-…` for sessions, `seed-task-…` for agent-made
 * tasks, `seed-*.json` for files), so running it again replaces its own records instead of
 * adding to them, and it leaves whatever else is in the database alone.
 *
 * Every session is stored as `ended` with an explicit end time. A server ends sessions that
 * are still open when it starts, which would otherwise give them all the start time, and the
 * live cases come from the task files instead. Sessions are written with source `hooks`, the
 * one the overview lists under EARLIER.
 *
 * @see SettingsRepository.saveAgentSession
 * @see SettingsRepository.saveAgentTask
 */
object AgentsOverviewSeed {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * What a run wrote, for the caller to print and for the test to check.
     *
     * @property sessions how many session records were written.
     * @property tasks how many agent-made tasks were written.
     * @property files how many task files were written.
     */
    data class Summary(val sessions: Int, val tasks: Int, val files: Int)

    /** One seeded session: its fields and how long before the seed time it ended. */
    private class Seeded(
        val n: Int,
        val task: String?,
        val title: String,
        val prompt: String,
        val repo: String,
        val branch: String,
        val endedAgo: Long,
    )

    /** An agent-made task: its id, its title, and the age of its oldest session. */
    private class SeededTask(val id: String, val title: String, val createdAgo: Long)

    private val tasks = listOf(
        SeededTask("seed-task-rate-limiter", "Rate limiter rewrite", 4 * DAY),
        SeededTask("seed-task-checkout", "Flaky checkout tests", 7 * DAY),
        SeededTask("seed-task-emails", "Onboarding emails", 14 * DAY),
        // The 30-day-old sessions of this task say nothing of "invoice", so searching it can only
        // find them through the task's title.
        SeededTask("seed-task-invoice", "Invoice export migration", 32 * DAY),
        // One session from yesterday and one from ten days ago: the task is as recent as its newest.
        SeededTask("seed-task-token", "Auth token refresh", 11 * DAY),
    )

    private val sessions = listOf(
        // Tasks of their own.
        Seeded(1, null, "Bump kotlinx-serialization", "Bump kotlinx-serialization to the latest minor and fix what breaks", "lunamux", "chore/bump-serialization", 25 * MINUTE),
        Seeded(2, null, "Sidebar flicker on resize", "Why does the sidebar flicker while the window is resized?", "lunamux", "fix/sidebar-flicker", 3 * HOUR),
        Seeded(3, null, "Tidy the release notes script", "Make the release notes script skip merge commits", "release-tools", "main", 2 * DAY),
        Seeded(4, null, "Spike: websocket backpressure", "Try a bounded queue in front of the window socket and measure it", "lunamux", "spike/backpressure", 6 * DAY),
        Seeded(5, null, "Review of the permissions page", "Review the permissions page change and list what is unclear", "work-app", "review/permissions", 6 * DAY + 2 * HOUR),
        Seeded(6, null, "Rename the config keys", "Rename the config keys to the new scheme with a fallback for the old ones", "work-app", "chore/config-keys", 12 * DAY),
        Seeded(7, null, "Explain the tile layout code", "Walk me through how tiles are laid out", "lunamux", "main", 12 * DAY + 3 * HOUR),
        Seeded(8, null, "Dark mode contrast", "The grey on the badge is hard to read in dark mode", "lunamux", "fix/badge-contrast", 30 * DAY),
        // Rate limiter rewrite: three sessions, the newest from yesterday.
        Seeded(9, "seed-task-rate-limiter", "Token bucket", "Write a token bucket for the API gateway", "gateway", "feat/token-bucket", 1 * DAY),
        Seeded(10, "seed-task-rate-limiter", "Limiter tests", "Cover the limiter with tests for bursts and refills", "gateway", "feat/token-bucket", 1 * DAY + 12 * HOUR),
        Seeded(11, "seed-task-rate-limiter", "Limiter docs", "Document the limiter's settings", "gateway", "docs/limiter", 2 * DAY),
        // Flaky checkout tests: second band.
        Seeded(12, "seed-task-checkout", "Find the flaky one", "Run the checkout suite twenty times and name the test that fails", "shop", "test/flaky", 5 * DAY),
        Seeded(13, "seed-task-checkout", "Fix the race", "Fix the race in the cart total test", "shop", "test/flaky", 6 * DAY + 4 * HOUR),
        // Onboarding emails: edge of the second band.
        Seeded(14, "seed-task-emails", "Welcome email", "Draft the welcome email template", "shop", "feat/welcome-email", 11 * DAY),
        Seeded(15, "seed-task-emails", "Reminder email", "Draft the day-three reminder", "shop", "feat/welcome-email", 12 * DAY),
        Seeded(16, "seed-task-emails", "Unsubscribe link", "Add the unsubscribe link and its page", "shop", "feat/unsubscribe", 13 * DAY),
        // Invoice export migration: older than the server keeps, found only by search.
        Seeded(17, "seed-task-invoice", "Duplicate header row", "Why is the header row of the csv written twice?", "billing", "fix/csv-header", 30 * DAY),
        Seeded(18, "seed-task-invoice", "Currency rounding", "Totals differ by a cent after the move; find the rounding", "billing", "fix/rounding", 31 * DAY),
        Seeded(19, "seed-task-invoice", "Backfill script", "Write the script that backfills last year", "billing", "feat/backfill", 32 * DAY),
        // Auth token refresh: one recent, one old, still in the first band.
        Seeded(20, "seed-task-token", "Refresh endpoint", "Add the refresh endpoint", "gateway", "feat/refresh", 1 * DAY + 3 * HOUR),
        Seeded(21, "seed-task-token", "Refresh docs", "Describe the refresh flow for the docs", "gateway", "docs/refresh", 10 * DAY),
    )

    /**
     * Write the seed into [dbFile] and [tasksDir].
     *
     * Called by [main] and the seeder's test. The database is opened through
     * [SettingsRepository], which creates or migrates its schema, so a path that does not exist
     * yet becomes a valid database. The caller must make sure no server is running on it: a
     * server reads its sessions and tasks once when it starts.
     *
     * @param dbFile the scratch database; refused when it is a real installation's.
     * @param tasksDir the scratch task folder; refused when it is the folder Lunamux reads by default.
     * @param now the time the ages are counted back from, epoch millis.
     * @return how much was written.
     * @throws IllegalArgumentException when a path is one a real installation uses.
     */
    fun seed(dbFile: File, tasksDir: File, now: Long = System.currentTimeMillis()): Summary {
        refuseRealPaths(dbFile, tasksDir)
        tasksDir.mkdirs()
        // The Lunula settings files go beside the database, never to the machine's real ones.
        val repo = SettingsRepository(
            dbFile,
            sharedThemesPath = File(dbFile.absoluteFile.parentFile, "seed-themes.json").path,
            appSettingsPath = File(dbFile.absoluteFile.parentFile, "seed-ui-settings.json").path,
        )
        val workdir = dbFile.absoluteFile.parentFile.absolutePath
        for (t in tasks) {
            repo.saveAgentTask(SettingsRepository.AgentTaskRow(t.id, t.title, now - t.createdAgo, now - t.createdAgo))
        }
        for (s in sessions) {
            val ended = now - s.endedAgo
            repo.saveAgentSession(
                AgentSessionRecord(
                    id = "seed-%02d".format(s.n),
                    agent = "claude",
                    agentSessionId = "5eed0000-0000-4000-8000-%012d".format(s.n),
                    source = "hooks",
                    cwd = workdir,
                    repo = s.repo,
                    branch = s.branch,
                    title = s.title,
                    firstPrompt = s.prompt,
                    state = "ended",
                    stateSince = ended,
                    createdAt = ended - 40 * MINUTE,
                    endedAt = ended,
                    repoRoot = workdir,
                    lastSaid = "Merged; the checks pass.",
                    taskId = s.task,
                ),
            )
        }
        val files = writeTaskFiles(tasksDir, workdir, now)
        return Summary(sessions.size, tasks.size, files)
    }

    /**
     * Write the task files: one live task that needs the person and works, one whose members are all
     * done but have not ended, one whose file has gone stale, and two whose members have all ended.
     *
     * Called by [seed]. Each file is written under a dot-name and renamed into place, as a real
     * writer would.
     *
     * @param tasksDir the folder to write into.
     * @param workdir an absolute folder for the files' `open` actions.
     * @param now the time the ages are counted back from.
     * @return how many files were written.
     */
    private fun writeTaskFiles(tasksDir: File, workdir: String, now: Long): Int {
        fun member(title: String, state: String, ago: Long, line: String? = null, label: String? = null, links: List<String> = emptyList()): JsonObject =
            buildJsonObject {
                put("title", title)
                put("state", state)
                put("since", java.time.Instant.ofEpochMilli(now - ago).toString())
                if (line != null) put("line", line)
                if (label != null) put("label", label)
                if (links.isNotEmpty()) put("links", buildJsonArray { links.forEach { add(JsonPrimitive(it)) } })
            }

        fun task(id: String, title: String, repo: String, members: List<JsonObject>, mtimeAgo: Long) {
            val json = buildJsonObject {
                put("version", 2)
                put("id", id)
                put("title", title)
                put("repo", repo)
                put("open", buildJsonObject {
                    put("command", "echo opened $title")
                    put("folder", workdir)
                })
                put("members", JsonArray(members))
            }
            val tmp = File(tasksDir, ".$id.json.tmp")
            tmp.writeText(json.toString())
            val target = File(tasksDir, "$id.json")
            tmp.renameTo(target)
            target.setLastModified(now - mtimeAgo)
        }

        task(
            "seed-access-control", "Access control", "work-app",
            listOf(
                member("Role model", "needs_you", 12 * MINUTE, line = "Should admins inherit viewer rights?", links = listOf("ACC-12")),
                member("Login audit trail", "working", 3 * MINUTE, line = "Writing the audit log migration"),
                member("Permissions page", "idle", 40 * MINUTE, label = "in review", links = listOf("#212")),
            ),
            mtimeAgo = 0,
        )
        // Every member is done but none has ended, the way a programme holds finished pull
        // requests for its merge rule. A file-declared task stays in NOW while a session has not
        // ended, so this shows how that reads when nothing is moving or waiting.
        task(
            "seed-release-train", "Release train", "lunamux",
            listOf(
                member("Tasks panel", "idle", HOUR, label = "done", links = listOf("#231")),
                member("Search by task", "idle", 3 * HOUR, label = "done", links = listOf("#232")),
                member("Seed script", "idle", 5 * HOUR, label = "done", links = listOf("#233")),
            ),
            mtimeAgo = 0,
        )
        task(
            "seed-nightly-sync", "Nightly sync", "ops",
            listOf(member("Sync worker", "working", 2 * HOUR, line = "Copying the ledger")),
            mtimeAgo = 25 * MINUTE,
        )
        task(
            "seed-release-42", "Release 4.2", "lunamux",
            listOf(
                member("Cut the branch", "ended", 2 * DAY + HOUR, label = "done", links = listOf("#201")),
                member("Write the notes", "ended", 2 * DAY, label = "done"),
            ),
            mtimeAgo = 2 * DAY,
        )
        task(
            "seed-docs-sweep", "Docs sweep", "lunamux",
            listOf(
                member("Fix the broken links", "ended", 9 * DAY, label = "done", links = listOf("#190")),
                member("Update screenshots", "ended", 9 * DAY + 4 * HOUR, label = "done"),
            ),
            mtimeAgo = 9 * DAY,
        )
        return 5
    }

    /**
     * Refuse a path a real installation uses.
     *
     * Called by [seed] before it touches anything: the production and dev databases live in
     * `~/Library/Application Support/Termtastic`, and the real tasks are in `~/.lunamux/tasks`
     * (or the older `agent-groups`).
     *
     * @param dbFile the database path to check.
     * @param tasksDir the task folder to check.
     * @throws IllegalArgumentException when either is a real installation's.
     */
    private fun refuseRealPaths(dbFile: File, tasksDir: File) {
        val home = File(System.getProperty("user.home")).canonicalFile
        val db = dbFile.absoluteFile.canonicalFile
        val realDbDir = File(home, "Library/Application Support/Termtastic").canonicalFile
        require(!db.path.startsWith(realDbDir.path) && db.name != "termtastic.db" && db.name != "lunamux-dev.db") {
            "Refusing to seed $db: it is a real installation's database. Give a scratch path."
        }
        val dir = tasksDir.absoluteFile.canonicalFile
        require(dir != File(home, ".lunamux/tasks").canonicalFile && dir != File(home, ".lunamux/agent-groups").canonicalFile) {
            "Refusing to seed $dir: it is the folder Lunamux reads task files from. Give a scratch folder."
        }
    }

    /**
     * Command-line entry: `AgentsOverviewSeed <db file> <tasks folder>`.
     *
     * Run by the `seedAgentsOverview` Gradle task. Prints what it wrote, or the reason it
     * refused, and exits non-zero on a refusal or wrong arguments.
     *
     * @param args the database file and the task folder.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: seed-agents-overview.sh <db file> <tasks folder>")
            exitProcess(2)
        }
        try {
            val s = seed(File(args[0]), File(args[1]))
            println("Seeded ${s.sessions} sessions, ${s.tasks} tasks and ${s.files} task files into ${args[0]} and ${args[1]}")
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    }
}
