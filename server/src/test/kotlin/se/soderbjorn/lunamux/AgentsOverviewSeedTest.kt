/**
 * Tests for [AgentsOverviewSeed]: that a seeded scratch database holds the ages, groupings and
 * awkward rows the agents overview is looked at with, that seeding twice replaces instead of
 * adding, and that it refuses a real installation's paths.
 *
 * @see AgentsOverviewSeed
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.auth.DeviceAuth
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
    fun `the seed spreads ended sessions over the ages the overview is read at and leaves two open for a restart`() {
        val summary = AgentsOverviewSeed.seed(db, tasksDir, now)
        assertEquals(3, summary.tasks)
        assertEquals(2, tasksDir.listFiles { f -> f.name.endsWith(".json") }!!.size)
        val repo = repo()
        val ages = repo.loadRecentEndedAgentSessions(0, 300).map { (now - it.endedAt!!) / day }.toSet()
        assertTrue(0L in ages && 2L in ages && 6L in ages && 12L in ages && 30L in ages, "ages: $ages")
        val open = listOf("seed-open-done", "seed-open-working").map { repo.findAgentSessionById(it)!! }
        assertTrue(open.all { it.state != "ended" && it.endedAt == null })
        assertEquals(2 * day, now - open.first { it.id == "seed-open-done" }.stateSince)
    }

    @Test
    fun `the seed holds the awkward rows the overview has to read correctly`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val byId = repo().loadRecentEndedAgentSessions(0, 300).associateBy { it.id }
        assertEquals(null, byId.getValue("seed-home").title)
        assertEquals(System.getProperty("user.home"), byId.getValue("seed-home").cwd)
        assertEquals(null, byId.getValue("seed-branch-only").title)
        assertTrue(byId.getValue("seed-markdown").lastSaid!!.startsWith("**"))
        assertEquals("```", byId.getValue("seed-bare-fence").lastSaid)
    }

    @Test
    fun `the seed turns mcp on with a read-write token, and seeding again replaces instead of adding`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val client = DeviceAuth.ClientInfo(type = "Test", hostname = null, selfReportedIp = null, remoteAddress = "127.0.0.1")
        assertTrue(repo().isMcpEnabled())
        assertTrue(!repo().isClaudeUsagePollEnabled(), "a scratch server must not launch the real claude")
        assertEquals(DeviceAuth.MCP_SCOPE_READ_WRITE, DeviceAuth.authorizeMcp(AgentsOverviewSeed.MCP_TOKEN, client, repo()))
        val before = repo().loadRecentEndedAgentSessions(0, 300).size
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(before, repo().loadRecentEndedAgentSessions(0, 300).size)
        assertEquals(3, repo().loadTasks().size)
        assertEquals(1, DeviceAuth.listMcpTokens(repo()).size)
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
