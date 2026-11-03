/**
 * A seeder that fills a scratch database and task folder with the cases the agents overview has
 * to show, so the overview can be looked at (or reviewed) without weeks of real use.
 *
 * It holds [AgentsOverviewSeed]: about twenty ended agent sessions that were last active today,
 * two, six, twelve and thirty days ago, some of them grouped into agent-made tasks, plus a few
 * task files whose members are live. It also seeds the awkward rows a real installation produces:
 * sessions that say nothing about what the work is (a home folder, a branch named after a run, a
 * detached HEAD), previews written in markdown, a bare code fence, and sessions a server restart
 * ends. It also seeds one family of work whose rows all share a thread, "Invoice export": a task
 * file, its two members and five loose sessions in one worktree, next to two sessions of the same
 * repository on other branches, so the marks the overview draws can be compared. It is run by the Gradle task `seedAgentsOverview` (through
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
 * Every session but two is stored as `ended` with an explicit end time, and the live cases come
 * from the task files. The two exceptions are left open on purpose, with a state that is days
 * old: a server ends the sessions still open when it starts, and that restart is the case they
 * reproduce. Sessions are written with source `hooks`, the one the overview lists under EARLIER.
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

    /**
     * One seeded session with the awkward fields spelled out, for the cases the overview has to
     * read correctly whatever the record holds.
     *
     * @property id the record id.
     * @property title the stored title, null when nothing named the work.
     * @property repo the repository folder name, null outside a repository.
     * @property branch the branch, null when none was recorded or HEAD was detached.
     * @property inHome true to give the session the user's home folder as its working folder.
     * @property prompt what was first asked, possibly several lines; null for none.
     * @property lastSaid the stored preview of the last reply, null for none.
     * @property ago how long before the seed time it ended, or for an open record how long it has
     *   been in [state].
     * @property state `ended`, or the open state a restart will end.
     * @property by who started it: `user`, `tool`, or null for a record that predates the field.
     */
    private class Case(
        val id: String,
        val title: String?,
        val repo: String?,
        val branch: String?,
        val inHome: Boolean = false,
        val prompt: String?,
        val lastSaid: String?,
        val ago: Long,
        val state: String = "ended",
        val by: String? = null,
    )

    /**
     * One loose session of the Invoice export worktree: a session that ran there and belongs to
     * no task.
     *
     * @property id the record id.
     * @property title the stored title.
     * @property prompt what was first asked.
     * @property ago how long before the seed time it ended.
     * @property by who started it: `user`, `tool`, or null for a record that predates the field.
     */
    private class Loose(val id: String, val title: String, val prompt: String, val ago: Long, val by: String?)

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
     * The five loose sessions of the *Invoice export* thread: they run in its worktree and belong
     * to no task, so they wear the same mark as the task that is named after it.
     */
    private val invoiceLoose = listOf(
        Loose("seed-invoice-rounding", "Invoice export: fix the rounding in totals", "Totals in the export differ by a cent; fix the rounding", 5 * HOUR, "user"),
        Loose("seed-invoice-columns", "Invoice export: review the CSV columns", "Review the CSV columns against the spec and list the gaps", 7 * HOUR, "user"),
        Loose("seed-invoice-staging", "Invoice export: first run against staging", "Run the export against staging and compare it with last month", 1 * DAY, "tool"),
        Loose("seed-invoice-spec", "Invoice export: first draft of the spec", "Write a first draft of the export spec", 5 * DAY, "tool"),
        Loose("seed-invoice-spike", "Invoice export: spike on streaming the file", "Spike: can the export stream the file instead of building it in memory?", 6 * DAY, null),
    )

    /**
     * The sessions that name their work badly or carry markdown in a preview.
     *
     * Each one is a case the overview must handle: the home folder, a branch named after a run,
     * a stored title that is a run id or a folder name, a preview in bold, a preview that is a
     * bare code fence, and two sessions still open whose state is days old.
     */
    private val cases = listOf(
        Case("seed-home", null, null, null, inHome = true, prompt = "Why did the nightly backup email never arrive?", lastSaid = "The mail relay rejected it; I resent it.", ago = 3 * HOUR, by = "user"),
        Case("seed-branch-only", null, "lunamux-agent-overview", "aov-21-impl", prompt = "Add the task rows to the overview and keep their order stable", lastSaid = "Done; the rows keep their order.", ago = 5 * HOUR, by = "tool"),
        Case("seed-run-id-title", "aov-11-impl", "lunamux", "aov-11-impl", prompt = "Fix the chip count when two sessions share a pane", lastSaid = "The count now ignores a session that has ended.", ago = 20 * HOUR),
        Case("seed-folder-title", "lunamux-agent-overview", "lunamux-agent-overview", "feat/layouts", prompt = "Compare the two layout directions and list what each trades off", lastSaid = "Direction B trades width for a clearer first column.", ago = 1 * DAY),
        Case("seed-markdown", "Owner test follow-up", "lunamux", "main", prompt = "**Plan:** read the owner's notes and `fix` the data first", lastSaid = "**4. Tell me how it went,** then decide whether the panel stays", ago = 4 * HOUR),
        Case("seed-bare-fence", "Show the migration", "billing", "feat/add-column", prompt = "Show me the migration that adds the column", lastSaid = "```", ago = 6 * HOUR),
        Case("seed-open-idle", "Trace the slow login", "gateway", "fix/slow-login", prompt = "Why is the login call slow on Mondays?", lastSaid = "A missing index on sessions.user_id; I added it.", ago = 2 * DAY, state = "idle", by = "user"),
        Case("seed-open-working", "Rebuild the search index", "billing", "chore/search-index", prompt = "Rebuild the search index with the new analyser", lastSaid = null, ago = 5 * HOUR, state = "working", by = "tool"),
    )

    /** The branch the Invoice export worktree has checked out. */
    private const val INVOICE_BRANCH = "invoice-export"

    /**
     * The folder of the Invoice export worktree, created on disk.
     *
     * Called by [seed] and by the seeder's test. The folder's name is `<workdir>/billing-wt<n>/invoice-export`,
     * where `<n>` is the first number for which the thread's own mark (before any stepping aside)
     * lies in the half-circle range (marks 12 to 17), so the seeded family wears the same sign
     * wherever the scratch folder is. A folder that is not a real worktree is enough: the seeded
     * records name it as their worktree, and the task file names it as its folder.
     *
     * @param workdir the canonical scratch folder the database sits in.
     * @return the canonical path of the worktree folder.
     */
    fun invoiceWorktree(workdir: String): String {
        val n = (0..999).first { ThreadMarks.hashMark("path:$workdir/billing-wt$it/$INVOICE_BRANCH") / 6 == 2 }
        return File(workdir, "billing-wt$n/$INVOICE_BRANCH").also { it.mkdirs(); File(workdir, "billing").mkdirs() }.canonicalPath
    }

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
        // Canonical, so the folders the seed names are the strings the server keys its threads on.
        val workdir = dbFile.absoluteFile.parentFile.canonicalPath
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
                    // The first eight were typed by hand, the next five were started by another
                    // session, and the older ones predate the field.
                    startedBy = when { s.n <= 8 -> "user"; s.n <= 13 -> "tool"; else -> null },
                ),
            )
        }
        val home = System.getProperty("user.home")
        for (c in cases) {
            val at = now - c.ago
            val open = c.state != "ended"
            repo.saveAgentSession(
                AgentSessionRecord(
                    id = c.id,
                    agent = "claude",
                    agentSessionId = "5eed0001-0000-4000-8000-%012d".format(c.id.hashCode().toLong() and 0xffffffffL),
                    source = "hooks",
                    cwd = if (c.inHome) home else workdir,
                    repo = c.repo,
                    branch = c.branch,
                    title = c.title,
                    firstPrompt = c.prompt,
                    state = c.state,
                    stateSince = at,
                    createdAt = at - 40 * MINUTE,
                    endedAt = if (open) null else at,
                    repoRoot = if (c.inHome) null else workdir,
                    lastSaid = c.lastSaid,
                    startedBy = c.by,
                ),
            )
        }
        val worktree = invoiceWorktree(workdir)
        // The Invoice export thread is the first the database has met, as if its owner had started it
        // before the rest. It keeps its own mark, in the half-circle range; every other thread, which
        // the server meets later, steps around it, so no collision can move it.
        ThreadMarks(repo, gitInfo = { GitInfo() }, home = System.getProperty("user.home"), defaultBranchOf = { null })
            .markFor("path:$worktree", now)
        for (l in invoiceLoose) {
            val at = now - l.ago
            repo.saveAgentSession(
                AgentSessionRecord(
                    id = l.id,
                    agent = "claude",
                    agentSessionId = "5eed0002-0000-4000-8000-%012d".format(l.id.hashCode().toLong() and 0xffffffffL),
                    source = "hooks",
                    cwd = worktree,
                    repo = "billing",
                    branch = INVOICE_BRANCH,
                    worktreePath = worktree,
                    title = l.title,
                    firstPrompt = l.prompt,
                    state = "ended",
                    stateSince = at,
                    createdAt = at - 40 * MINUTE,
                    endedAt = at,
                    repoRoot = File(workdir, "billing").path,
                    lastSaid = "Done; the export checks pass.",
                    startedBy = l.by,
                ),
            )
        }
        val files = writeTaskFiles(tasksDir, workdir, now, worktree)
        return Summary(sessions.size + cases.size + invoiceLoose.size, tasks.size, files)
    }

    /**
     * Write the task files: one live task that needs the person and works, the Invoice export task, one whose members are all
     * done but have not ended, one with work still in review or queued, one whose file has gone stale, and two whose members have all ended.
     *
     * Called by [seed]. Each file is written under a dot-name and renamed into place, as a real
     * writer would.
     *
     * @param tasksDir the folder to write into.
     * @param workdir an absolute folder; each task file's `open` action gets a folder of its own below it,
     *   as real tasks work in folders of their own and each one is a thread of its own.
     * @param now the time the ages are counted back from.
     * @param worktree the Invoice export worktree, the folder of that family's task file.
     * @return how many files were written.
     */
    private fun writeTaskFiles(tasksDir: File, workdir: String, now: Long, worktree: String): Int {
        fun member(title: String, state: String, ago: Long, line: String? = null, label: String? = null, links: List<String> = emptyList()): JsonObject =
            buildJsonObject {
                put("title", title)
                put("state", state)
                put("since", java.time.Instant.ofEpochMilli(now - ago).toString())
                if (line != null) put("line", line)
                if (label != null) put("label", label)
                if (links.isNotEmpty()) put("links", buildJsonArray { links.forEach { add(JsonPrimitive(it)) } })
            }

        fun task(id: String, title: String, repo: String, members: List<JsonObject>, mtimeAgo: Long, folder: String = File(workdir, "work/$id").also { it.mkdirs() }.path) {
            val json = buildJsonObject {
                put("version", 2)
                put("id", id)
                put("title", title)
                put("repo", repo)
                put("open", buildJsonObject {
                    put("command", "echo opened $title")
                    put("folder", folder)
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
        // requests for its merge rule. Nothing in it moves or waits, so it belongs with the
        // finished work in EARLIER, not in NOW.
        task(
            "seed-release-train", "Release train", "lunamux",
            listOf(
                member("Tasks panel", "idle", HOUR, label = "done", links = listOf("#231")),
                member("Search by task", "idle", 3 * HOUR, label = "done", links = listOf("#232")),
                member("Seed script", "idle", 5 * HOUR, label = "done", links = listOf("#233")),
            ),
            mtimeAgo = 0,
        )
        // Open work that is neither waiting on the person nor running: one pull request in review,
        // one queued. It stays in NOW, because it still has something left to do.
        task(
            "seed-billing-cleanup", "Billing cleanup", "billing",
            listOf(
                member("Refund flow", "idle", 2 * HOUR, label = "in review", links = listOf("#240")),
                member("Receipt emails", "idle", 6 * HOUR, label = "queued"),
            ),
            mtimeAgo = 0,
        )
        // One family of work in one worktree: this task, its two members and the loose sessions
        // above all share a mark. The task's folder is the worktree, so it copies that thread's mark.
        task(
            "seed-invoice-export", "Invoice export", "billing",
            listOf(
                member("Invoice export: parse the ledger csv", "idle", 4 * HOUR, label = "done", links = listOf("#91")),
                member("Invoice export: write the migration", "idle", 90 * MINUTE, label = "in review", links = listOf("#93")),
            ),
            mtimeAgo = 0,
            folder = worktree,
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
        return 7
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
