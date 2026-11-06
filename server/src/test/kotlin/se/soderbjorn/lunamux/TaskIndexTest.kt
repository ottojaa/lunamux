/**
 * Tests for [TaskIndex] and the task support in [AgentSessionTracker]: which task each session
 * belongs to (the task whose id it carries, else none; a task file lists no sessions), how a
 * resumed conversation keeps its task, how a task promised to a pane with no agent yet is handed
 * to the session that starts there, which record stands for a task's own session (the pane it ran
 * in, also when a restart has renumbered the terminal ids), and the operations behind the MCP task tools.
 *
 * Runs against a real SQLite database with fake git, clock, ids and pane lookup, so no terminal
 * is needed.
 *
 * @see TaskIndex
 * @see AgentSessionTracker
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskIndexTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-task-index", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val work = File(dbDir, "work").apply { mkdirs() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val knownPanes = mutableSetOf<String>()
    private var suffixes = generateSequence(1) { it + 1 }.map { "s$it" }.iterator()

    private val tracker = AgentSessionTracker(
        repo,
        gitInfo = { GitInfo(repo = "lunamux", branch = "main", worktreePath = null) },
        clock = { now },
        newId = { "r${++nextId}" },
    ).also { it.start() }

    private val index = TaskIndex(
        repo, tracker, files,
        leafOfSession = { if (it in knownPanes) "leaf-$it" else null },
        clock = { now },
        newSuffix = { suffixes.next() },
    )

    private fun folder(name: String) = File(work, name).apply { mkdirs() }.canonicalPath

    private fun pane(id: String, agent: String? = null, cwd: String = folder("p-$id")) =
        PaneSnapshot(id, leafId = "leaf-$id", cwd = cwd, programTitle = null, agent = agent, screenText = { "screen" })
            .also { knownPanes += id }

    private fun event(name: String, id: String): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", id)
    }

    /** A Claude session running in pane [id], known to the tracker by hook. */
    private fun session(id: String, agentId: String = "a-$id", cwd: String = folder("p-$id")): AgentSessionRecord {
        tracker.onHook(pane(id, cwd = cwd), event("SessionStart", agentId))
        now += 10
        return tracker.openRecordOf(id)!!
    }

    private fun rec(
        id: String,
        cwd: String? = null,
        state: String = "working",
        agentSessionId: String? = null,
        createdAt: Long = 1,
        taskId: String? = null,
        leafId: String? = "leaf-$id",
    ) = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = agentSessionId, cwd = cwd, state = state, stateSince = 500,
        nowLine = "Bash ls", leafId = leafId, createdAt = createdAt, taskId = taskId,
    )

    private fun file(id: String, open: TaskOpen? = null, title: String = "File $id") =
        TaskFile(id, title, repo = null, open = open, modified = 99)

    private fun resolve(
        records: List<AgentSessionRecord>, files: List<TaskFile> = emptyList(),
        tasks: List<SettingsRepository.TaskRow> = emptyList(),
    ) = TaskIndex.resolve(records, files, tasks.associateBy { it.id })

    // ── Which task a record belongs to: the one whose id it carries ─────────────────────

    @Test
    fun `a record belongs to the task whose id it carries, and a record that carries none belongs to none`() {
        val snap = resolve(
            listOf(rec("in", taskId = "f"), rec("out"), rec("also-in", taskId = "f", state = "ended")),
            listOf(file("f")),
        )
        assertEquals("f", snap.assignment["in"])
        assertEquals("f", snap.assignment["also-in"])
        assertNull(snap.assignment["out"])
    }

    @Test
    fun `a session opened in the folder a task file opens in is not taken into that task`() {
        val wt = folder("wt")
        val snap = resolve(
            listOf(rec("owners", wt, agentSessionId = "abc")),
            listOf(file("f", open = TaskOpen("claude go", wt))),
        )
        assertNull(snap.assignment["owners"], "only the id a session carries places it in a task")
    }

    @Test
    fun `a task starts when its first session was created, else when its file was written`() {
        val withSessions = resolve(listOf(rec("a", taskId = "f", createdAt = 40), rec("b", taskId = "f", createdAt = 25)), listOf(file("f")))
        assertEquals(25, withSessions.tasks.single().startedAtMillis)
        assertEquals(99, resolve(emptyList(), listOf(file("f"))).tasks.single().startedAtMillis)
        val stored = resolve(listOf(rec("a", taskId = "f", createdAt = 40)), listOf(file("f")), tasks = listOf(SettingsRepository.TaskRow("f", startedAt = 7)))
        assertEquals(7, stored.tasks.single().startedAtMillis)
    }

    @Test
    fun `agent-made tasks show only once they hold a session, and a file with the same id is one task with the file's title`() {
        val rows = listOf(
            SettingsRepository.TaskRow("empty", title = "Empty", createdAt = 1, updatedAt = 1),
            SettingsRepository.TaskRow("used", title = "Used", createdAt = 1, updatedAt = 1),
            SettingsRepository.TaskRow("shared", title = "Row title", createdAt = 1, updatedAt = 1),
        )
        val snap = resolve(
            listOf(rec("a", taskId = "used"), rec("b", taskId = "shared")),
            listOf(file("shared", title = "File title")),
            rows,
        )
        assertEquals(listOf("shared", "used"), snap.tasks.map { it.id })
        assertEquals("File title", snap.tasks.first { it.id == "shared" }.title)
        assertEquals("agents", snap.tasks.first { it.id == "used" }.source)
        assertEquals("file", snap.tasks.first { it.id == "shared" }.source)
        assertEquals("shared", snap.assignment["b"])
    }

    // ── The task's main conversation: stored as a conversation, found again in whatever pane it runs ──

    /** Terminal id to leaf id for the panes the next index under test sees, changed to model a restart. */
    private val panesNow = mutableMapOf<String, String>()

    private fun paneIn(leaf: String, terminal: String) =
        PaneSnapshot(terminal, leafId = leaf, cwd = folder("p-$leaf"), programTitle = null, agent = null, screenText = { "screen" })
            .also { panesNow[terminal] = leaf }

    private fun trackerOverRepo() = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun indexOver(over: AgentSessionTracker) = TaskIndex(
        repo, over, files,
        leafOfSession = { panesNow[it] },
        clock = { now }, newSuffix = { suffixes.next() },
    )

    private fun storedMain(task: String) = repo.loadTasks().single { it.id == task }.mainConversation

    @Test
    fun `create_task and set_task_lead turn the pane into its conversation once`() {
        session("s1")
        session("s2")
        val id = index.createTask("Plan", listOf("s1"))
        assertEquals("a-s1", storedMain(id))

        index.setOwnSession(id, "s2")
        assertEquals("a-s2", storedMain(id))
        assertEquals("a-s2", index.listing().single { it.id == id }.mainConversation)
        assertEquals("s2", index.listing().single { it.id == id }.ownSessionId)
    }

    @Test
    fun `set_task_lead with a pane that runs no agent is refused and keeps the old conversation`() {
        session("s1")
        val id = index.createTask("Plan", listOf("s1"))
        pane("s7")
        assertFailsWith<TaskException> { index.setOwnSession(id, "s7") }
        assertEquals("a-s1", storedMain(id))
    }

    @Test
    fun `set_task_lead takes a conversation id, and refuses one the tracker does not hold`() {
        session("s1")
        session("s2")
        val id = index.createTask("Plan", listOf("s1"))
        index.setMainConversation(id, "a-s2")
        assertEquals("a-s2", storedMain(id))
        assertFailsWith<TaskException> { index.setMainConversation(id, "nobody") }
        assertEquals("a-s2", storedMain(id))
    }

    @Test
    fun `after a restart with reused pane ids list_tasks names the stored conversation and its live pane`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())

        // The restart: s10 is another pane with another agent in it, the conversation is closed.
        panesNow.clear()
        val restarted = trackerOverRepo()
        restarted.onHook(paneIn("leaf-b", "s10"), event("SessionStart", "a-stranger"))
        val reopened = indexOver(restarted)
        reopened.recompute()
        val closed = reopened.listing().single { it.id == task }
        assertEquals("a-mine", closed.mainConversation, "the conversation is what is stored")
        assertNull(closed.ownSessionId, "the stranger on s10 is not the task's conversation")

        // The conversation resumes in a pane with another id.
        restarted.onHook(paneIn("leaf-c", "s9"), event("SessionStart", "a-mine"))
        reopened.recompute()
        val open = reopened.listing().single { it.id == task }
        assertEquals("a-mine", open.mainConversation)
        assertEquals("s9", open.ownSessionId)
        assertEquals("a-mine", reopened.state.value.tasks.single { it.id == task }.mainConversation)
    }

    @Test
    fun `a resume the screen showed before the hook named it keeps the task's conversation and gives one record`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())
        panesNow.clear()
        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)

        // The new tab shows claude on the screen first, then the hook names the conversation.
        val newPane = paneIn("leaf-c", "s9").copy(agent = "claude", state = "working")
        restarted.onPoll(listOf(newPane))
        restarted.onHook(newPane, event("SessionStart", "a-mine"))
        reopened.recompute()

        val running = restarted.openRecordOf("s9")!!
        assertEquals(mine.id, running.id, "the earlier record of the conversation is the one that reopened")
        assertEquals(1, restarted.summaries.value.count { it.conversationId == "a-mine" && it.state != "ended" })
        assertEquals("s9", reopened.listing().single { it.id == task }.ownSessionId)
    }

    @Test
    fun `a new agent in the old pane does not take over the task, and the old conversation resumed elsewhere is found`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())
        panesNow.clear()
        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)
        restarted.onHook(paneIn("leaf-a", "s9"), event("SessionStart", "a-other"))
        reopened.recompute()
        assertEquals("a-mine", storedMain(task), "a stranger in the old pane is not the task's conversation")
        assertNull(reopened.listing().single { it.id == task }.ownSessionId)

        restarted.onHook(paneIn("leaf-c", "s8"), event("SessionStart", "a-mine"))
        reopened.recompute()
        assertEquals("s8", reopened.listing().single { it.id == task }.ownSessionId)
    }

    // ── Resume and the pending pane assignment ──────────────────────────────────────────

    @Test
    fun `a resumed conversation keeps its task when its record is reopened in another pane`() {
        val first = session("s1", "a-1")
        val task = index.createTask("Refactor", listOf("s1"))
        tracker.onSessionDestroyed("s1", "old screen")

        tracker.onHook(pane("s9"), event("SessionStart", "a-1"))
        val reopened = tracker.openRecordOf("s9")!!
        assertEquals(first.id, reopened.id)
        assertEquals(task, reopened.taskId)
        index.recompute()
        assertEquals(task, index.state.value.assignment[reopened.id])
    }

    @Test
    fun `a record that adopts a conversation id takes the task the conversation already had`() {
        val first = session("s1", "a-1")
        val task = index.createTask("Refactor", listOf("s1"))
        // A second pane shows Claude on screen before its hook names the conversation.
        tracker.onPoll(listOf(PaneSnapshot("s2", "leaf-s2", folder("p-s2"), null, agent = "claude", state = "idle", screenText = { "x" })))
        tracker.onHook(PaneSnapshot("s2", "leaf-s2", folder("p-s2"), null, agent = "claude", screenText = { "x" }), event("SessionStart", "a-1"))
        val adopted = tracker.openRecordOf("s2")!!
        assertEquals(task, adopted.taskId)
        assertEquals(1, tracker.summaries.value.count { it.conversationId == "a-1" && it.state != "ended" }, "one conversation, one summary")
        assertTrue(adopted.id == first.id || tracker.snapshot().none { it.id != adopted.id && it.agentSessionId == "a-1" && it.state != "ended" })
    }

    @Test
    fun `a task promised to a pane with no agent goes to the session that starts there, once`() {
        val task = index.createTask("Split work", emptyList())
        pane("s5")
        index.addToTask(task, listOf("s5"))
        assertTrue(tracker.snapshot().isEmpty())

        tracker.onHook(pane("s5"), event("SessionStart", "a-5"))
        assertEquals(task, tracker.openRecordOf("s5")!!.taskId)

        tracker.onSessionDestroyed("s5", null)
        tracker.onHook(pane("s5"), event("SessionStart", "a-other"))
        assertNull(tracker.openRecordOf("s5")!!.taskId, "the promise was used up")
    }

    // ── The operations behind the MCP tools ─────────────────────────────────────────────

    @Test
    fun `create_task makes a task with a readable id and puts the sessions in it`() {
        session("s1")
        session("s2")
        val id = index.createTask("  Fix   the flaky login test  ", listOf("s1", "s2"))
        assertEquals("fix-the-flaky-login-test-s1", id)
        val task = index.state.value.tasks.single()
        assertEquals("Fix the flaky login test", task.title)
        assertEquals(2, index.state.value.assignment.values.count { it == id })
        assertEquals(id, tracker.summaries.value.first { it.id == tracker.openRecordOf("s1")!!.id }.taskId)
    }

    @Test
    fun `add_to_task moves a session between tasks`() {
        session("s1")
        val a = index.createTask("A", listOf("s1"))
        val b = index.createTask("B", emptyList())
        index.addToTask(b, listOf("s1"))
        assertEquals(b, tracker.openRecordOf("s1")!!.taskId)
        assertEquals(listOf(b), index.state.value.tasks.map { it.id }, "A holds nothing now")
        assertTrue(a != b)
    }

    @Test
    fun `remove_from_task makes the session a task of its own again and says where each one ended up`() {
        session("s1")
        index.createTask("A", listOf("s1"))
        val after = index.removeFromTask(listOf("s1"))
        assertEquals(mapOf<String, String?>("s1" to null), after)
        assertNull(tracker.openRecordOf("s1")!!.taskId)
        assertTrue(index.state.value.tasks.isEmpty())
    }

    @Test
    fun `rename_task renames an agent-made task, refuses a file task and an unknown one`() {
        session("s1")
        val id = index.createTask("Before", listOf("s1"))
        index.renameTask(id, "After")
        assertEquals("After", index.state.value.tasks.single().title)
        assertEquals("After", repo.loadTasks().single().title)
        assertFailsWith<TaskException> { index.renameTask(id, "  ") }

        files.value = listOf(file("prog"))
        val fileEx = assertFailsWith<TaskException> { index.renameTask("prog", "Nope") }
        assertTrue("file" in fileEx.message!!)
        assertFailsWith<TaskException> { index.renameTask("missing", "Nope") }
    }

    @Test
    fun `tasks survive a restart of the index`() {
        session("s1")
        val id = index.createTask("Persisted", listOf("s1"))
        val again = TaskIndex(repo, tracker, files, leafOfSession = { null })
        assertEquals(id, again.state.value.tasks.single().id)
        assertEquals("Persisted", again.state.value.tasks.single().title)
    }

    @Test
    fun `list_tasks shows every task with its sessions, including a task nothing is in yet`() {
        files.value = listOf(file("prog"))
        session("s1")
        session("s2")
        val mine = index.createTask("Mine", listOf("s1"))
        index.addToTask("prog", listOf("s2"))
        val empty = index.createTask("Empty", emptyList())
        val listing = index.listing().associateBy { it.id }
        assertEquals("file", listing.getValue("prog").source)
        assertEquals(listOf("s2"), listing.getValue("prog").sessions.map { it.sessionId })
        assertEquals(listOf("s1"), listing.getValue(mine).sessions.map { it.sessionId })
        assertEquals("agents", listing.getValue(mine).source)
        assertTrue(listing.getValue(empty).sessions.isEmpty())
    }

    @Test
    fun `the state follows the tracker once started`() {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        index.start(scope)
        session("s1")
        assertTrue(index.state.value.tasks.isEmpty())
        val id = index.createTask("Live", emptyList())
        index.addToTask(id, listOf("s1"))
        assertEquals(id, index.state.value.tasks.single().id)
        tracker.onSessionDestroyed("s1", null)
        assertEquals(id, index.state.value.assignment[tracker.snapshot().single().id], "an ended record keeps its task")
    }

}
