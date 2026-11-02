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
        assertEquals(21, summary.sessions)
        assertEquals(6, tasksDir.listFiles { f -> f.name.endsWith(".json") }!!.size)

        val repo = repo()
        val kept = repo.loadRecentEndedAgentSessions(now - 14 * day, 300)
        assertTrue(kept.all { it.state == "ended" && it.endedAt != null && it.source == "hooks" })
        val ages = kept.map { (now - it.endedAt!!) / day }.toSet()
        assertTrue(0L in ages && 2L in ages && 6L in ages && 12L in ages, "ages today, 2, 6 and 12 days: $ages")
        assertEquals(17, kept.size, "the sessions of 30 days or more are older than the window")
        assertTrue(repo.loadRecentEndedAgentSessions(0, 300).any { (now - it.endedAt!!) >= 30 * day })
    }

    @Test
    fun `a session of thirty days ago is found through its task's title alone`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        val tasks = TaskIndex(repo, AgentSessionTracker(repo), MutableStateFlow(emptyList()), { null }, { now })
        val hits = AgentSessionSearch.search(repo, null, "invoice", tasks)
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.field == "task" && it.taskTitle == "Invoice export migration" })
    }

    @Test
    fun `seeding again replaces the records instead of adding to them`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(21, repo().loadRecentEndedAgentSessions(0, 300).size)
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
}
