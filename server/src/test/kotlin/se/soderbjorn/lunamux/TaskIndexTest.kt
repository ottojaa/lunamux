/**
 * Tests for [TaskIndex] and the task support in [AgentSessionTracker]: which task each session
 * belongs to (the task whose id it carries, else none; a task file lists no sessions), how a
 * resumed conversation keeps its task, how a task promised to a pane with no agent yet is handed
 * to the session that starts there, which record stands for a task's own session (the pane it ran
 * in, also when a restart has renumbered the terminal ids). The operations the MCP task tools
 * expose are tested through those tools, in [se.soderbjorn.lunamux.mcp.McpTaskToolsTest].
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

    /** Terminal id to leaf id for the panes the next index under test sees, changed to model a restart. */
    private val panesNow = mutableMapOf<String, String>()

    private fun paneIn(leaf: String, terminal: String) =
        PaneSnapshot(terminal, leafId = leaf, cwd = folder("p-$leaf"), programTitle = null, agent = null, screenText = { "screen" })
            .also { panesNow[terminal] = leaf }

    private fun trackerOverRepo() = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { now }, newId = { "r${++nextId}" },
    ).also { it.start() }

    private fun indexOver(over: AgentSessionTracker) = TaskIndex(
        repo, over, files, leafOfSession = { panesNow[it] }, clock = { now }, newSuffix = { suffixes.next() },
    )

    @Test
    fun `a record belongs to the task whose id it carries, and a session opened where a task file opens does not join it`() {
        val wt = folder("wt")
        val snap = resolve(
            listOf(rec("in", taskId = "f"), rec("out"), rec("ended", taskId = "f", state = "ended"), rec("owner", wt, agentSessionId = "abc")),
            listOf(file("f"), file("g", open = TaskOpen("claude go", wt))),
        )
        assertEquals(listOf("f", "g"), snap.map { it.id })
        assertEquals(99, snap.last().startedAtMillis, "the session in g's folder did not join it, so g did not start with it")
    }

    @Test
    fun `a task starts when its first session was created, else when it was stored, else when its file was written`() {
        val sessions = listOf(rec("a", taskId = "f", createdAt = 40), rec("b", taskId = "f", createdAt = 25))
        assertEquals(25, resolve(sessions, listOf(file("f"))).single().startedAtMillis)
        assertEquals(99, resolve(emptyList(), listOf(file("f"))).single().startedAtMillis)
        assertEquals(7, resolve(sessions, listOf(file("f")), listOf(SettingsRepository.TaskRow("f", startedAt = 7))).single().startedAtMillis)
    }

    @Test
    fun `agent-made tasks show once they hold a session, and a file with the same id is one task with the file's title`() {
        val rows = listOf(
            SettingsRepository.TaskRow("empty", title = "Empty", createdAt = 1, updatedAt = 1),
            SettingsRepository.TaskRow("used", title = "Used", createdAt = 1, updatedAt = 1),
            SettingsRepository.TaskRow("shared", title = "Row title", createdAt = 1, updatedAt = 1),
            SettingsRepository.TaskRow("file-gone", startedAt = 3), // what a task file leaves behind: no title, no task
        )
        val snap = resolve(
            listOf(rec("a", taskId = "used"), rec("b", taskId = "shared"), rec("c", taskId = "file-gone"), rec("d", taskId = "nobody-knows")),
            listOf(file("shared", title = "File title")), rows,
        )
        assertEquals(listOf("shared" to "File title", "used" to "Used"), snap.map { it.id to it.title })
        assertEquals(listOf("file", "agents"), snap.map { it.source })
    }

    @Test
    fun `after a restart the task still names its conversation and finds it in whatever pane it runs, not a stranger in the old one`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())
        panesNow.clear()

        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)
        restarted.onHook(paneIn("leaf-a", "s9"), event("SessionStart", "a-other"))
        reopened.recompute()
        val closed = reopened.listing().single { it.id == task }
        assertEquals(listOf("a-mine", null), listOf(closed.mainConversation, closed.ownSessionId))

        // The conversation resumes in a new tab: the screen shows claude before the hook names it.
        val newPane = paneIn("leaf-c", "s8").copy(agent = "claude", state = "working")
        restarted.onPoll(listOf(newPane))
        restarted.onHook(newPane, event("SessionStart", "a-mine"))
        reopened.recompute()
        assertEquals(mine.id, restarted.openRecordOf("s8")!!.id, "its earlier record reopened")
        assertEquals(1, restarted.summaries.value.count { it.conversationId == "a-mine" && it.state != "ended" })
        assertEquals(listOf("a-mine", "s8"), reopened.listing().single { it.id == task }.let { listOf(it.mainConversation, it.ownSessionId) })
    }

    @Test
    fun `a resumed conversation keeps its task, in another pane or adopted by a record the screen opened`() {
        val first = session("s1", "a-1")
        val task = index.createTask("Refactor", listOf("s1"))
        tracker.onSessionDestroyed("s1", "old screen")
        tracker.onHook(pane("s9"), event("SessionStart", "a-1"))
        assertEquals(listOf(first.id, task), tracker.openRecordOf("s9")!!.let { listOf(it.id, index.taskIdOf(it)) })

        tracker.onPoll(listOf(PaneSnapshot("s2", "leaf-s2", folder("p-s2"), null, agent = "claude", state = "idle", screenText = { "x" })))
        tracker.onHook(PaneSnapshot("s2", "leaf-s2", folder("p-s2"), null, agent = "claude", screenText = { "x" }), event("SessionStart", "a-1"))
        assertEquals(task, tracker.openRecordOf("s2")!!.taskId)
        assertEquals(1, tracker.summaries.value.count { it.conversationId == "a-1" && it.state != "ended" }, "one conversation, one summary")
    }

    @Test
    fun `a task promised to a pane with no agent goes to the session that starts there, once`() {
        val task = index.createTask("Split work", emptyList())
        pane("s5")
        index.addToTask(task, listOf("s5"))
        tracker.onHook(pane("s5"), event("SessionStart", "a-5"))
        assertEquals(task, tracker.openRecordOf("s5")!!.taskId)

        tracker.onSessionDestroyed("s5", null)
        tracker.onHook(pane("s5"), event("SessionStart", "a-other"))
        assertNull(tracker.openRecordOf("s5")!!.taskId, "the promise was used up")
    }

    @Test
    fun `the state follows the tracker once started, and an ended record keeps its task`() {
        index.start(CoroutineScope(Dispatchers.Unconfined))
        session("s1")
        val id = index.createTask("Live", emptyList())
        assertTrue(index.state.value.isEmpty())
        index.addToTask(id, listOf("s1"))
        assertEquals(id, index.state.value.single().id)
        tracker.onSessionDestroyed("s1", null)
        assertEquals(id, index.taskIdOf(tracker.snapshot().single()))
    }
    @Test
    fun `a helper claude started in a task's pane joins the task, shows beside the conversation, and never speaks for the task`() {
        val p = pane("s1", agent = "claude")
        tracker.onHook(p, event("SessionStart", "a-main"))
        val task = index.createTask("Plan", listOf("s1"))

        // A plan reviewer starts in the same pane while the conversation is still running; the
        // two conversations' hooks interleave.
        tracker.onHook(p, event("SessionStart", "a-reviewer"))
        tracker.onHook(p, event("UserPromptSubmit", "a-reviewer"))
        tracker.onHook(p, event("PreToolUse", "a-main"))
        index.setOwnSession(task, "s1")
        index.createTask("Another", emptyList(), callerSessionId = "s1")

        val byConversation = tracker.summaries.value.associateBy { it.conversationId }
        assertEquals("a-main", index.listing().single { it.id == task }.mainConversation)
        assertEquals(listOf("working", task), byConversation.getValue("a-reviewer").let { listOf(it.state, it.taskId) }, "the panel can list it")
        assertEquals("working", byConversation.getValue("a-main").state, "the conversation is not closed while its helper runs")

        tracker.onHook(p, event("SessionEnd", "a-reviewer"))
        assertEquals(listOf("working", "ended"), tracker.summaries.value.sortedBy { it.conversationId }.map { it.state })
        assertEquals("a-main", tracker.openRecordOf("s1")!!.agentSessionId)
    }

    @Test
    fun `a helper that keeps working after its conversation stops stays listed, and one that went silent or lost its conversation leaves`() {
        val p = pane("s1", agent = "claude")
        val states = { tracker.summaries.value.sortedBy { it.conversationId }.map { it.state } }
        tracker.onHook(p, event("SessionStart", "a-main"))
        tracker.onHook(p, event("SessionStart", "a-reviewer"))
        tracker.onHook(p, event("UserPromptSubmit", "a-reviewer"))
        tracker.onHook(p, event("Stop", "a-main"))
        now += 5 * 60_000
        tracker.onHook(p, event("PreToolUse", "a-reviewer"))
        now += 9 * 60_000
        tracker.onPoll(listOf(p))
        assertEquals(listOf("done", "working"), states(), "it still sends hooks after its conversation stopped")

        now += 2 * 60_000
        tracker.onPoll(listOf(p))
        assertEquals(listOf("done", "ended"), states(), "killed without a SessionEnd: quiet for ten minutes")

        tracker.onHook(p, event("SessionStart", "a-second"))
        tracker.onHook(p, event("UserPromptSubmit", "a-second"))
        tracker.onHook(p, event("SessionEnd", "a-main"))
        assertEquals(listOf("ended", "ended", "ended"), states(), "a conversation that ends takes its helpers with it")
    }

    @Test
    fun `a main conversation naming an ended helper is mended to the live conversation it ran inside, and a really closed one is not`() {
        val live = rec("m", agentSessionId = "a-m", taskId = "t", leafId = "leaf", createdAt = 10)
        val helper = rec("h", agentSessionId = "a-h", state = "ended", leafId = "leaf", createdAt = 20)
        val row = SettingsRepository.TaskRow("t", title = "T", mainConversation = "a-h", startedAt = 1)
        assertEquals("a-m", resolve(listOf(live, helper), tasks = listOf(row)).single().mainConversation)

        val closed = rec("m", agentSessionId = "a-m", state = "ended", taskId = "t", leafId = "leaf", createdAt = 10)
        val working = rec("h", agentSessionId = "a-h", taskId = "t", leafId = "leaf", createdAt = 20)
        assertEquals("a-m", resolve(listOf(closed, working), tasks = listOf(row.copy(mainConversation = "a-m"))).single().mainConversation)
    }
}
