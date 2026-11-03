/**
 * Tests for [AgentsOverviewSeed]: that a seeded scratch database holds the ages and groupings
 * the agents overview is reviewed with, that seeding twice changes nothing, and that it refuses
 * a real installation's paths.
 *
 * @see AgentsOverviewSeed
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentsOverviewSeedTest {
    private val root = File.createTempFile("lunamux-seed", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val db = File(root, "scratch.db")
    private val tasksDir = File(root, "tasks")
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun repo() = SettingsRepository(
        db,
        sharedThemesPath = File(root, "seed-themes.json").path,
        appSettingsPath = File(root, "seed-ui-settings.json").path,
    )

    @Test
    fun `the seed holds about twenty ended sessions spread over the ages the overview is read at`() {
        val summary = AgentsOverviewSeed.seed(db, tasksDir, now)
        assertEquals(34, summary.sessions)
        assertEquals(7, tasksDir.listFiles { f -> f.name.endsWith(".json") }!!.size)

        val repo = repo()
        val kept = repo.loadRecentEndedAgentSessions(now - 14 * day, 300)
        assertTrue(kept.all { it.state == "ended" && it.endedAt != null && it.source == "hooks" })
        val ages = kept.map { (now - it.endedAt!!) / day }.toSet()
        assertTrue(0L in ages && 2L in ages && 6L in ages && 12L in ages, "ages today, 2, 6 and 12 days: $ages")
        assertEquals(28, kept.size, "the sessions of 30 days or more are older than the window")
        assertTrue(repo.loadRecentEndedAgentSessions(0, 300).any { (now - it.endedAt!!) >= 30 * day })
    }

    @Test
    fun `the seed holds the awkward cases the overview has to read correctly`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        val byId = repo.loadRecentEndedAgentSessions(0, 300).associateBy { it.id }

        val home = byId.getValue("seed-home")
        assertEquals(null, home.title)
        assertEquals(System.getProperty("user.home"), home.cwd)

        assertEquals(null, byId.getValue("seed-branch-only").title)
        assertEquals("aov-21-impl", byId.getValue("seed-branch-only").branch)
        assertEquals("aov-11-impl", byId.getValue("seed-run-id-title").title)

        assertTrue(byId.getValue("seed-markdown").lastSaid!!.startsWith("**"))
        assertEquals("```", byId.getValue("seed-bare-fence").lastSaid)
    }

    @Test
    fun `two sessions are left open with an old state for a restart to end`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val open = listOf("seed-open-idle", "seed-open-working").map { repo().findAgentSessionById(it)!! }
        assertTrue(open.all { it.state != "ended" && it.endedAt == null })
        assertEquals(2 * day, now - open.first { it.id == "seed-open-idle" }.stateSince)
    }

    @Test
    fun `a session of thirty days ago is found through its task's title alone`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        val tasks = TaskIndex(repo, AgentSessionTracker(repo), MutableStateFlow(emptyList()), { null }, { now })
        val hits = AgentSessionSearch.search(repo, null, "invoice", tasks)
        val viaTask = hits.filter { it.field == "task" }
        assertEquals(3, viaTask.size)
        assertTrue(viaTask.all { it.taskTitle == "Invoice export migration" })
        // The Invoice export family names itself in its sessions' titles, so those match directly.
        assertEquals(5, hits.count { it.field == "title" })
    }

    @Test
    fun `seeding again replaces the records instead of adding to them`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(32, repo().loadRecentEndedAgentSessions(0, 300).size)
        assertEquals(5, repo().loadAgentTasks().size)
    }

    @Test
    fun `a real installation's database or task folder is refused and nothing is written`() {
        val home = System.getProperty("user.home")
        assertFailsWith<IllegalArgumentException> {
            AgentsOverviewSeed.seed(File(home, "Library/Application Support/Termtastic/x.db"), tasksDir, now)
        }
        assertFailsWith<IllegalArgumentException> { AgentsOverviewSeed.seed(File(root, "termtastic.db"), tasksDir, now) }
        assertFailsWith<IllegalArgumentException> { AgentsOverviewSeed.seed(db, File(home, ".lunamux/tasks"), now) }
        assertTrue(!db.exists() && !tasksDir.exists())
    }

    @Test
    fun `the Invoice export task, its sessions and nothing else share one mark in the half-circle range`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        val marks = ThreadMarks(repo, gitInfo = { GitInfo() }, home = System.getProperty("user.home"), defaultBranchOf = { null })
        val tracker = AgentSessionTracker(repo, clock = { now }, marks = marks).also { it.start() }
        val watcher = TaskFilesWatcher(listOf(tasksDir)) {}.also { it.reload() }
        val tasks = TaskIndex(repo, tracker, watcher.files, { null }, { now }, marks = marks)
        tasks.recompute()

        val summaries = tracker.summaries.value
        val invoice = summaries.filter { it.id.startsWith("seed-invoice-") }
        assertEquals(5, invoice.size)
        val taskMark = tasks.state.value.tasks.single { it.id == "seed-invoice-export" }.mark
        assertTrue(taskMark in 12..17, "the Invoice export mark is a half-circle: $taskMark")
        assertTrue(invoice.all { it.mark == taskMark }, "loose sessions wear the task's mark: ${invoice.map { it.mark }}")

        // Other branches of the same repository wear other marks; the home-folder session none.
        val rebuild = summaries.single { it.id == "seed-open-working" }
        val migration = summaries.single { it.id == "seed-bare-fence" }
        assertTrue(rebuild.mark != null && migration.mark != null && rebuild.mark != migration.mark)
        assertTrue(rebuild.mark != taskMark && migration.mark != taskMark)
        assertEquals(null, summaries.single { it.id == "seed-home" }.mark)
    }

    @Test
    fun `the Invoice export family keeps a half-circle mark in whichever scratch folder it is seeded`() {
        repeat(25) { i ->
            val dir = File.createTempFile("lunamux-seed-$i-", "").apply { delete(); mkdirs(); deleteOnExit() }
            AgentsOverviewSeed.seed(File(dir, "scratch.db"), File(dir, "tasks"), now)
            val repo = SettingsRepository(
                File(dir, "scratch.db"),
                sharedThemesPath = File(dir, "seed-themes.json").path,
                appSettingsPath = File(dir, "seed-ui-settings.json").path,
            )
            val marks = ThreadMarks(repo, gitInfo = { GitInfo() }, home = System.getProperty("user.home"), defaultBranchOf = { null })
            val tracker = AgentSessionTracker(repo, clock = { now }, marks = marks).also { it.start() }
            val watcher = TaskFilesWatcher(listOf(File(dir, "tasks"))) {}.also { it.reload() }
            val tasks = TaskIndex(repo, tracker, watcher.files, { null }, { now }, marks = marks)
            tasks.recompute()
            val taskMark = tasks.state.value.tasks.single { it.id == "seed-invoice-export" }.mark
            assertTrue(taskMark in 12..17, "folder $i: the Invoice export mark is a half-circle: $taskMark")
            val invoice = tracker.summaries.value.filter { it.id.startsWith("seed-invoice-") }
            assertTrue(invoice.all { it.mark == taskMark }, "folder $i: loose sessions wear the task's mark: ${invoice.map { it.mark }}")
        }
    }
}
