/**
 * Tests for [AgentsOverviewSeed]: that a seeded scratch database holds the ages and groupings
 * the agents overview is reviewed with, that seeding twice changes nothing, and that it refuses
 * a real installation's paths.
 *
 * @see AgentsOverviewSeed
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
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
    fun `the seed holds about twenty ended sessions spread over the ages the overview is read at`() {
        val summary = AgentsOverviewSeed.seed(db, tasksDir, now)
        assertEquals(32, summary.sessions)
        assertEquals(6, tasksDir.listFiles { f -> f.name.endsWith(".json") }!!.size)

        val repo = repo()
        val kept = repo.loadRecentEndedAgentSessions(now - 14 * day, 300)
        assertTrue(kept.all { it.state == "ended" && it.endedAt != null && it.source == "hooks" })
        val ages = kept.map { (now - it.endedAt!!) / day }.toSet()
        assertTrue(0L in ages && 2L in ages && 6L in ages && 12L in ages, "ages today, 2, 6 and 12 days: $ages")
        assertEquals(26, kept.size, "the sessions of 30 days or more are older than the window")
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
        val open = listOf("seed-open-done", "seed-open-working").map { repo().findAgentSessionById(it)!! }
        assertTrue(open.all { it.state != "ended" && it.endedAt == null })
        assertEquals(2 * day, now - open.first { it.id == "seed-open-done" }.stateSince)
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
    fun `the seed holds a task with its own session, a status and two background members that search never finds`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        val meta = repo.loadTaskMeta().single { it.taskId == "seed-task-notes" }
        assertEquals("5eed0002-0000-4000-8000-000000000001", meta.mainConversation)
        assertEquals("Notes drafted, waiting on the changelog check", meta.status)
        val members = repo.loadRecentEndedAgentSessions(0, 300).filter { it.taskId == "seed-task-notes" }
        assertEquals(3, members.size)
        assertEquals(setOf("seed-notes-2", "seed-notes-3"), members.filter { it.background }.map { it.id }.toSet())

        val tasks = TaskIndex(repo, AgentSessionTracker(repo), MutableStateFlow(emptyList()), { null }, { now })
        val own = AgentSessionSearch.search(repo, null, "release notes", tasks).map { it.summary.id }
        assertTrue("seed-notes-1" in own, "the task's own session is found: $own")
        assertTrue(own.none { it in setOf("seed-notes-2", "seed-notes-3") }, "a background session is never found, not even through its task: $own")
        val byWords = AgentSessionSearch.search(repo, null, "changelog entry", tasks).map { it.summary.id }
        assertTrue("seed-notes-2" !in byWords, "nor by its own words: $byWords")
    }

    @Test
    fun `the seeded task has a panel of every useful kind, all written by one agent, that a restart keeps`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val rows = repo().loadTaskPanelRows().filter { it.taskId == "seed-task-notes" }
        assertEquals(listOf("progress", "links", "brief", "decisions", "activity"), rows.map { it.blockId })
        assertEquals(setOf("progress", "links", "timeline"), rows.map { it.kind }.toSet())
        assertEquals(setOf("release notes agent"), rows.map { it.writtenBy }.toSet())
        val activity = rows.single { it.blockId == "activity" }
        assertTrue("<img src=x onerror=alert(1)>" in activity.body, "markup is stored as the text it is")
        val shown = rows.filter { it.kind == "links" }.sumOf { r -> r.body.split("\"url\"").size - 1 }
        assertTrue(shown > 5, "the links are more than the five the panel shows, so it says \"+ N more\": $shown")
        // Reading it back through the store, as a server start does, gives the same blocks.
        val index = TaskIndex(repo(), AgentSessionTracker(repo()), MutableStateFlow(emptyList()), { null }, { now })
        val stored = TaskPanelStore(repo(), index, { now }).blocksOf("seed-task-notes")
        assertEquals(rows.size, stored.size)
        // The seed skips the store's checks when it writes, so hold its blocks to them here: an agent
        // writing the same blocks (as the live script does) must not be refused.
        stored.forEach { TaskPanelLimits.check(it) }
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(rows.size, repo().loadTaskPanelRows().count { it.taskId == "seed-task-notes" }, "seeding again replaces, never adds")
    }

    @Test
    fun `the seed turns mcp on with a read-write token so the live script can drive the scratch server`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        val repo = repo()
        assertTrue(repo.isMcpEnabled())
        val client = DeviceAuth.ClientInfo(type = "Test", hostname = null, selfReportedIp = null, remoteAddress = "127.0.0.1")
        assertEquals(DeviceAuth.MCP_SCOPE_READ_WRITE, DeviceAuth.authorizeMcp(AgentsOverviewSeed.MCP_TOKEN, client, repo))
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(1, DeviceAuth.listMcpTokens(repo).size, "seeding again does not add a second token")
    }

    @Test
    fun `seeding again replaces the records instead of adding to them`() {
        AgentsOverviewSeed.seed(db, tasksDir, now)
        AgentsOverviewSeed.seed(db, tasksDir, now + day)
        assertEquals(30, repo().loadRecentEndedAgentSessions(0, 300).size)
        assertEquals(6, repo().loadAgentTasks().size)
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
