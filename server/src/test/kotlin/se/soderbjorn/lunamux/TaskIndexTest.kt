/**
 * Tests for [TaskIndex] and the task support in [AgentSessionTracker]: which task each session
 * belongs to (explicit join, file match by session id, file match by folder, none), how a
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

    private fun member(
        key: String, title: String = key, state: String = "idle", sessionId: String? = null,
        matchSessionId: String? = null, matchFolder: String? = null,
    ) = TaskFileMember(AgentTaskMember(key, title, state = state, line = "file line", sinceMillis = 7, sessionId = sessionId), matchSessionId, matchFolder)

    private fun file(id: String, vararg members: TaskFileMember, open: TaskOpen? = null, title: String = "File $id") =
        TaskFile(id, title, repo = null, open = open, members = members.toList(), modified = 99)

    private fun resolve(records: List<AgentSessionRecord>, files: List<TaskFile> = emptyList(), rows: List<SettingsRepository.AgentTaskRow> = emptyList()) =
        TaskIndex.resolve(records, files, rows)

    // ── Precedence (GI-15, GI-16, GI-17, GI-18, GI-19) ──────────────────────────────────

    @Test
    fun `a member matches the newest record with its agent session id and follows it live`() {
        val snap = resolve(
            listOf(
                rec("old", agentSessionId = "abc", createdAt = 1),
                rec("new", agentSessionId = "abc", createdAt = 9, state = "needs_you").copy(question = "Merge?"),
                rec("other", agentSessionId = "zzz", createdAt = 99),
            ),
            listOf(file("f", member("m", matchSessionId = "abc"))),
        )
        val m = snap.tasks.single().members.single()
        assertEquals("new", m.recordId)
        assertEquals("needs_you", m.state)
        assertEquals("Merge?", m.line)
        assertEquals(500, m.sinceMillis)
        assertEquals("m", m.title)
        assertEquals("f", snap.assignment["new"])
        assertNull(snap.assignment["other"])
    }

    @Test
    fun `a member matches an open record in its folder or below, not a sibling with the same prefix`() {
        val wt = folder("wt")
        val sub = File(wt, "sub").apply { mkdirs() }.canonicalPath
        val records = listOf(
            rec("sibling", folder("wt-other")),
            rec("ended", wt, state = "ended"),
            rec("below", sub, createdAt = 3),
            rec("exact", wt, createdAt = 7),
        )
        val f = listOf(file("f", member("m", matchFolder = wt)))
        assertEquals("exact", resolve(records, f).tasks.single().members.single().recordId)
        assertEquals("below", resolve(records.filter { it.id != "exact" }, f).tasks.single().members.single().recordId)
        val none = resolve(records.filter { it.id != "exact" && it.id != "below" }, f).tasks.single().members.single()
        assertNull(none.recordId)
        assertEquals("idle", none.state)
        assertEquals("file line", none.line)
    }

    @Test
    fun `a session id match beats a folder match for the same member`() {
        val wt = folder("wt")
        val snap = resolve(
            listOf(rec("by-folder", wt, createdAt = 9), rec("by-id", folder("elsewhere"), agentSessionId = "abc", createdAt = 1)),
            listOf(file("f", member("m", matchSessionId = "abc", matchFolder = wt))),
        )
        assertEquals("by-id", snap.tasks.single().members.single().recordId)
        assertNull(snap.assignment["by-folder"])
    }

    @Test
    fun `a matched member follows its record live and falls back to the file once it ends`() {
        val wt = folder("wt")
        val f = listOf(file("f", member("m", state = "needs_you", matchFolder = wt)))
        assertEquals("working", resolve(listOf(rec("r", wt)), f).tasks.single().members.single().state)
        val ended = resolve(listOf(rec("r", wt, state = "ended")), f).tasks.single().members.single()
        assertEquals("needs_you", ended.state)
        assertEquals("file line", ended.line)
        assertNull(ended.recordId)
    }

    @Test
    fun `an ended record that a member names by session id gives the member its record id and keeps the file's state`() {
        val snap = resolve(listOf(rec("r", agentSessionId = "abc", state = "ended")), listOf(file("f", member("m", state = "idle", matchSessionId = "abc"))))
        val m = snap.tasks.single().members.single()
        assertEquals("r", m.recordId)
        assertEquals("idle", m.state)
        assertEquals("f", snap.assignment["r"])
    }

    @Test
    fun `two members never take the same record`() {
        val wt = folder("wt")
        val snap = resolve(listOf(rec("only", wt)), listOf(file("f", member("a", matchFolder = wt), member("b", matchFolder = wt))))
        assertEquals(listOf("only", null), snap.tasks.single().members.map { it.recordId })
    }

    @Test
    fun `a record an agent put in a task is not taken by another task's folder match, and wins over a file match`() {
        val wt = folder("wt")
        val files = listOf(file("a", member("m", matchFolder = wt)), file("b"))
        val snap = resolve(listOf(rec("r", wt, taskId = "b")), files)
        assertEquals("b", snap.assignment["r"])
        assertNull(snap.tasks.first { it.id == "a" }.members.single().recordId)
    }

    @Test
    fun `a record put in a file's task by an agent can still be that file's member`() {
        val wt = folder("wt")
        val snap = resolve(listOf(rec("r", wt, taskId = "f")), listOf(file("f", member("m", matchFolder = wt))))
        assertEquals("r", snap.tasks.single().members.single().recordId)
        assertEquals("f", snap.assignment["r"])
    }

    @Test
    fun `a record the agent put in a file's task that no member matches belongs to the task without being a member`() {
        val snap = resolve(listOf(rec("r", folder("x"), taskId = "f")), listOf(file("f", member("m"))))
        assertEquals("f", snap.assignment["r"])
        assertNull(snap.tasks.single().members.single().recordId)
    }

    @Test
    fun `a task id no file and no row knows is ignored, so the record is a task of its own`() {
        val wt = folder("wt")
        val snap = resolve(listOf(rec("r", wt, taskId = "ghost")), listOf(file("f", member("m", matchFolder = wt))))
        assertEquals("f", snap.assignment["r"], "the file match still applies")
        assertNull(resolve(listOf(rec("r2", wt, taskId = "ghost"))).assignment["r2"])
    }

    @Test
    fun `file order is by id, so the same input always resolves the same way`() {
        val wt = folder("wt")
        val records = listOf(rec("r", wt))
        val a = file("a", member("m", matchFolder = wt))
        val b = file("b", member("m", matchFolder = wt))
        assertEquals("a", resolve(records, listOf(b, a)).assignment["r"])
        assertEquals("a", resolve(records, listOf(a, b)).assignment["r"])
    }

    @Test
    fun `agent-made tasks show only once they hold a session, and a file with the same id is one task with the file's title`() {
        val rows = listOf(
            SettingsRepository.AgentTaskRow("empty", "Empty", 1, 1),
            SettingsRepository.AgentTaskRow("used", "Used", 1, 1),
            SettingsRepository.AgentTaskRow("shared", "Row title", 1, 1),
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

    @Test
    fun `a task is marked as having an open action only when its file declares one`() {
        val snap = resolve(emptyList(), listOf(file("a", open = TaskOpen("claude", "/tmp")), file("b")))
        assertEquals(listOf(true, false), snap.tasks.map { it.hasOpen })
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

    private fun storedMain(task: String) = repo.loadTaskMeta().single { it.taskId == task }.mainConversation

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
    fun `the caller's pane, not the first listed pane, is the main conversation when the caller is known`() {
        session("s1")
        session("s2")
        val id = index.createTask("Plan", listOf("s1"), callerSessionId = "s2")
        assertEquals("a-s2", storedMain(id))
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

    @Test
    fun `a task file's task keeps its main conversation the same way`() {
        files.value = listOf(file("prog", member("m", title = "Run one")))
        session("s1", "a-1")
        index.recompute()
        index.setOwnSession("prog", "s1")
        assertEquals("a-1", storedMain("prog"))
        assertEquals("a-1", index.state.value.tasks.single { it.id == "prog" }.mainConversation)
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

    @Test
    fun `a promise wins over the task an earlier record of the conversation had`() {
        session("s1", "a-1")
        val old = index.createTask("Old", listOf("s1"))
        val newer = index.createTask("New", emptyList())
        tracker.onSessionDestroyed("s1", null)
        pane("s2")
        index.addToTask(newer, listOf("s2"))
        tracker.onHook(pane("s2"), event("SessionStart", "a-1"))
        assertEquals(newer, tracker.openRecordOf("s2")!!.taskId)
        assertTrue(old != newer)
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
    fun `task ids never repeat, even for the same title`() {
        val a = index.createTask("Same", emptyList())
        val b = index.createTask("Same", emptyList())
        assertTrue(a != b)
    }

    @Test
    fun `a title that is blank is refused and one that is long is cut`() {
        assertFailsWith<TaskException> { index.createTask("   ", emptyList()) }
        index.createTask("x".repeat(500), emptyList())
        assertTrue(index.listing().single().title.length <= 120)
    }

    @Test
    fun `create_task with a session that does not exist changes nothing and names the session`() {
        session("s1")
        val e = assertFailsWith<TaskException> { index.createTask("T", listOf("s1", "nope")) }
        assertTrue("nope" in e.message!!)
        assertTrue(index.listing().isEmpty())
        assertNull(tracker.openRecordOf("s1")!!.taskId)
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
    fun `add_to_task works on a task file's id and the session is then in that file's task`() {
        files.value = listOf(file("prog", member("m", title = "Run one")))
        session("s1")
        index.recompute()
        index.addToTask("prog", listOf("s1"))
        val rec = tracker.openRecordOf("s1")!!
        assertEquals("prog", rec.taskId)
        assertEquals("prog", index.state.value.assignment[rec.id])
        assertEquals("prog", tracker.summaries.value.single().taskId)
    }

    @Test
    fun `add_to_task refuses an unknown task or session and changes nothing`() {
        session("s1")
        val task = index.createTask("A", emptyList())
        val noTask = assertFailsWith<TaskException> { index.addToTask("missing", listOf("s1")) }
        assertTrue("missing" in noTask.message!!)
        val noSession = assertFailsWith<TaskException> { index.addToTask(task, listOf("s1", "ghost")) }
        assertTrue("ghost" in noSession.message!!)
        assertNull(tracker.openRecordOf("s1")!!.taskId)
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
    fun `remove_from_task leaves a session in the file task whose member still matches it`() {
        val wt = folder("wt")
        files.value = listOf(file("prog", member("m", matchFolder = wt)))
        session("s1", cwd = wt)
        index.addToTask("prog", listOf("s1"))
        val after = index.removeFromTask(listOf("s1"))
        assertNull(tracker.openRecordOf("s1")!!.taskId)
        assertEquals("prog", after["s1"], "the file still places it")
    }

    @Test
    fun `remove_from_task withdraws a promise made to a pane with no agent`() {
        val task = index.createTask("A", emptyList())
        pane("s7")
        index.addToTask(task, listOf("s7"))
        index.removeFromTask(listOf("s7"))
        tracker.onHook(pane("s7"), event("SessionStart", "a-7"))
        assertNull(tracker.openRecordOf("s7")!!.taskId)
    }

    @Test
    fun `rename_task renames an agent-made task, refuses a file task and an unknown one`() {
        session("s1")
        val id = index.createTask("Before", listOf("s1"))
        index.renameTask(id, "After")
        assertEquals("After", index.state.value.tasks.single().title)
        assertEquals("After", repo.loadAgentTasks().single().title)
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
        files.value = listOf(file("prog", member("m", title = "Declared", state = "working")))
        session("s1")
        val mine = index.createTask("Mine", listOf("s1"))
        val empty = index.createTask("Empty", emptyList())
        val listing = index.listing().associateBy { it.id }
        assertEquals("file", listing.getValue("prog").source)
        assertEquals(listOf("Declared"), listing.getValue("prog").members.map { it.title })
        assertEquals(listOf("s1"), listing.getValue(mine).members.map { it.sessionId })
        assertEquals("agents", listing.getValue(mine).source)
        assertTrue(listing.getValue(empty).members.isEmpty())
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

    @Test
    fun `fileTask finds a file by id`() {
        files.value = listOf(file("prog", open = TaskOpen("claude go", "/tmp")))
        assertEquals("claude go", index.fileTask("prog")!!.open!!.command)
        assertNull(index.fileTask("nope"))
    }
}
