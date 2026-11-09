/**
 * A seeder that fills a scratch database and task folder with the cases the agents overview has
 * to show, so it can be looked at without weeks of real use.
 *
 * It holds [AgentsOverviewSeed]: ended sessions last active within the hour and 2, 6, 12 and 30
 * days ago, three agent-made tasks, two task files, the awkward rows a real installation produces
 * (a home folder, a branch named after a run, a bare code fence, markdown, sessions a restart
 * ends), and it turns the scratch server's MCP endpoint on with a read+write token
 * ([MCP_TOKEN]) so `scripts/seed-agents-overview-live.sh` can drive the server. It is run by the
 * Gradle task `seedAgentsOverview` (through `scripts/seed-agents-overview.sh`) while the scratch
 * server is stopped, and by a test. It only writes to the paths it is given and refuses the
 * folders a real installation uses.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.auth.DeviceAuth
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.system.exitProcess

/** Seeds a scratch database and a scratch task folder for the agents overview. */
object AgentsOverviewSeed {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /**
     * The read+write MCP token the seed adds. It is a fixed, well-known string, which is fine
     * because the seed refuses every real installation's database.
     */
    const val MCP_TOKEN = "aov-seed-mcp-token"

    /** What a run wrote, for the caller to print and the test to check. */
    data class Summary(val sessions: Int, val tasks: Int, val files: Int)

    /** The agent-made tasks: id to title and the age of the task. */
    private val tasks = mapOf(
        "seed-task-limiter" to ("Rate limiter rewrite" to 4 * DAY),
        "seed-task-checkout" to ("Flaky checkout tests" to 7 * DAY),
        // Its sessions say nothing of "invoice", so searching it can only find them through the title.
        "seed-task-invoice" to ("Invoice export migration" to 32 * DAY),
    )

    /** Write the seed into [dbFile] and [tasksDir]. */
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
        repo.setMcpEnabled(true)
        // A server with this poll on launches the real `claude`; a scratch server must never do that.
        repo.setClaudeUsagePollEnabled(false)
        DeviceAuth.addTrustedToken(repo, MCP_TOKEN, DeviceAuth.MCP_LABEL, DeviceAuth.MCP_SCOPE_READ_WRITE)
        for ((id, t) in tasks) repo.insertTask(id, t.first, now - t.second)

        var sessions = 0
        /** Save one session that last did something [ago] ms before [now]; open states have no end. */
        fun put(
            id: String, title: String?, prompt: String?, repoName: String?, branch: String?, ago: Long,
            task: String? = null, state: String = "ended", said: String? = "Merged; the checks pass.",
            cwd: String = workdir,
        ) {
            val at = now - ago
            repo.saveAgentSession(
                AgentSessionRecord(
                    id = id, agent = "claude", agentSessionId = "5eed0000-0000-4000-8000-%012d".format(++sessions),
                    source = "hooks", cwd = cwd, repo = repoName, branch = branch, title = title, firstPrompt = prompt,
                    state = state, stateSince = at, createdAt = at - 40 * MINUTE, endedAt = if (state == "ended") at else null,
                    repoRoot = workdir, lastSaid = said, taskId = task,
                    question = if (state == "needs_you") said else null, nowLine = if (state == "working") said else null,
                ),
            )
        }

        put("seed-a", "Bump kotlinx-serialization", "Bump kotlinx-serialization and fix what breaks", "lunamux", "chore/bump", 25 * MINUTE)
        put("seed-b", "Sidebar flicker on resize", "Why does the sidebar flicker while resizing?", "lunamux", "fix/flicker", 3 * HOUR)
        put("seed-c", "Tidy the release notes script", "Make the release notes script skip merge commits", "release-tools", "main", 2 * DAY)
        put("seed-d", "Spike: websocket backpressure", "Try a bounded queue in front of the window socket", "lunamux", "spike/queue", 6 * DAY)
        put("seed-e", "Rename the config keys", "Rename the config keys with a fallback for the old ones", "work-app", "chore/keys", 12 * DAY)
        put("seed-f", "Dark mode contrast", "The grey on the badge is hard to read in dark mode", "lunamux", "fix/contrast", 30 * DAY)
        put("seed-limiter-1", "Token bucket", "Write a token bucket for the API gateway", "gateway", "feat/bucket", 1 * DAY, "seed-task-limiter")
        put("seed-limiter-2", "Limiter tests", "Cover the limiter with tests for bursts", "gateway", "feat/bucket", 2 * DAY, "seed-task-limiter")
        put("seed-checkout-1", "Find the flaky one", "Run the checkout suite twenty times", "shop", "test/flaky", 5 * DAY, "seed-task-checkout")
        put("seed-checkout-2", "Fix the race", "Fix the race in the cart total test", "shop", "test/flaky", 6 * DAY, "seed-task-checkout")
        put("seed-invoice-1", "Duplicate header row", "Why is the header row of the csv written twice?", "billing", "fix/csv", 30 * DAY, "seed-task-invoice")
        put("seed-invoice-2", "Backfill script", "Write the script that backfills last year", "billing", "feat/backfill", 32 * DAY, "seed-task-invoice")

        // Sessions that name their work badly, or carry markdown in a preview.
        put("seed-home", null, "Why did the nightly backup email never arrive?", null, null, 3 * HOUR, cwd = System.getProperty("user.home"))
        put("seed-branch-only", null, "Add the task rows to the overview", "lunamux", "aov-21-impl", 5 * HOUR)
        put("seed-markdown", "Owner test follow-up", "**Plan:** read the notes and `fix` the data first", "lunamux", "main", 4 * HOUR, said = "**4. Tell me how it went,** then decide")
        put("seed-bare-fence", "Show the migration", "Show me the migration that adds the column", "billing", "feat/column", 6 * HOUR, said = "```")
        // Left open on purpose: a server start ends them, aged from when they last did anything.
        put("seed-open-done", "Trace the slow login", "Why is the login call slow on Mondays?", "gateway", "fix/login", 2 * DAY, state = "done")
        put("seed-open-working", "Rebuild the search index", "Rebuild the index with the new analyser", "billing", "chore/index", 5 * HOUR, state = "working", said = null)

        fileTask(tasksDir, workdir, "seed-access-control", "Access control", "work-app")
        put("seed-access-1", "Role model", "Role model", "work-app", null, 12 * MINUTE, "seed-access-control", "needs_you", "Should admins inherit viewer rights?")
        put("seed-access-2", "Login audit trail", "Login audit trail", "work-app", null, 3 * MINUTE, "seed-access-control", "working", "Writing the audit log migration")
        put("seed-access-3", "Permissions page", "Permissions page", "work-app", null, 40 * MINUTE, "seed-access-control", "done", null)
        fileTask(tasksDir, workdir, "seed-release-42", "Release 4.2", "lunamux")
        put("seed-release-1", "Cut the branch", "Cut the branch", "lunamux", null, 2 * DAY + HOUR, "seed-release-42")
        put("seed-release-2", "Write the notes", "Write the notes", "lunamux", null, 2 * DAY, "seed-release-42")
        return Summary(sessions, tasks.size, 2)
    }

    /** Write the task file for [id]: its title, repo and the command that opens it. */
    private fun fileTask(tasksDir: File, workdir: String, id: String, title: String, repoName: String) {
        val json = buildJsonObject {
            put("id", id)
            put("title", title)
            put("repo", repoName)
            put("open", buildJsonObject { put("command", "echo opened $title"); put("folder", workdir) })
        }
        val tmp = File(tasksDir, ".$id.json.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(tasksDir, "$id.json"))
    }

    /** Refuse a path a real installation uses. */
    private fun refuseRealPaths(dbFile: File, tasksDir: File) {
        val home = File(System.getProperty("user.home")).canonicalFile
        val db = dbFile.absoluteFile.canonicalFile
        val realDbDir = File(home, "Library/Application Support/Termtastic").canonicalFile
        require(!db.path.startsWith(realDbDir.path) && db.name != "termtastic.db" && db.name != "lunamux-dev.db") {
            "Refusing to seed $db: it is a real installation's database. Give a scratch path."
        }
        val dir = tasksDir.absoluteFile.canonicalFile
        require(dir != File(home, ".lunamux/tasks").canonicalFile) {
            "Refusing to seed $dir: it is the folder Lunamux reads task files from. Give a scratch folder."
        }
    }

    /** Command-line entry: `AgentsOverviewSeed <db file> <tasks folder>`. */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println("usage: seed-agents-overview.sh <db file> <tasks folder>")
            exitProcess(2)
        }
        try {
            val s = seed(File(args[0]), File(args[1]))
            println("Seeded ${s.sessions} sessions, ${s.tasks} tasks and ${s.files} task files into ${args[0]} and ${args[1]}")
            println("MCP is on for the scratch server; token for the live script: $MCP_TOKEN")
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message)
            exitProcess(1)
        }
    }
}
