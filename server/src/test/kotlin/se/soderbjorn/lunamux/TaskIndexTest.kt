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
        sessionOfLeaf = { leaf -> knownPanes.firstOrNull { "leaf-$it" == leaf } },
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

    // ── The task's own session: the pane it ran in, across restarts that renumber terminal ids ──

    private fun onTerminal(id: String, terminal: String, createdAt: Long, state: String = "ended", leafId: String? = "leaf-$id") =
        rec(id, state = state, createdAt = createdAt, leafId = leafId).copy(terminalSessionId = terminal)

    /** The record [TaskIndex.resolve] gives a task that remembers [leaf] and/or [terminal] as its own session. */
    private fun ownRecordOf(records: List<AgentSessionRecord>, leaf: String? = null, terminal: String? = null): String? =
        TaskIndex.resolve(
            records.map { it.copy(taskId = "t") }, emptyList(), listOf(SettingsRepository.AgentTaskRow("t", "T", 1, 1)),
            mapOf("t" to SettingsRepository.TaskMetaRow("t", 1, leadSession = terminal, leadLeaf = leaf)),
        ).tasks.single().leadRecordId

    @Test
    fun `a pane's running record beats a newer ended one from the same pane`() {
        // An old conversation resumed in the pane keeps its old start, so it is older than the ended
        // runs the pane has had since.
        val records = listOf(
            onTerminal("old-ended", "s10", createdAt = 2, leafId = "leaf-a"),
            onTerminal("live", "s10", createdAt = 5, state = "idle", leafId = "leaf-a"),
            onTerminal("newer-ended", "s10", createdAt = 9, leafId = "leaf-a"),
            onTerminal("elsewhere", "s11", createdAt = 99, state = "working", leafId = "leaf-b"),
        )
        assertEquals("live", ownRecordOf(records, leaf = "leaf-a"))
    }

    @Test
    fun `with nothing running in the pane the newest ended record stands for it`() {
        val records = listOf(
            onTerminal("old-ended", "s10", createdAt = 2, leafId = "leaf-a"),
            onTerminal("newer-ended", "s10", createdAt = 9, leafId = "leaf-a"),
            onTerminal("middle-ended", "s10", createdAt = 5, leafId = "leaf-a"),
        )
        assertEquals("newer-ended", ownRecordOf(records, leaf = "leaf-a"))
    }

    @Test
    fun `a record in the pane that is not ended wins even when it is the oldest`() {
        val records = listOf(
            onTerminal("live-oldest", "s10", createdAt = 1, state = "working", leafId = "leaf-a"),
            onTerminal("ended-newest", "s10", createdAt = 50, leafId = "leaf-a"),
        )
        assertEquals("live-oldest", ownRecordOf(records, leaf = "leaf-a"))
    }

    @Test
    fun `a task whose pane no record ran in has no own record`() {
        assertNull(ownRecordOf(listOf(onTerminal("a", "s1", 1, leafId = "leaf-a")), leaf = "leaf-gone"))
        assertNull(ownRecordOf(listOf(onTerminal("a", "s1", 1, leafId = "leaf-a"))))
    }

    @Test
    fun `after a restart renumbers terminal ids the task follows its pane, not the id`() {
        // The own session ran in leaf-a as s10. The restart gave leaf-a s9, and s10 now belongs to
        // leaf-b with an unrelated agent running in it. The task still stores s10.
        val records = listOf(
            onTerminal("mine-ended", "s10", createdAt = 3, leafId = "leaf-a"),
            onTerminal("mine-now", "s9", createdAt = 4, state = "working", leafId = "leaf-a"),
            onTerminal("stranger", "s10", createdAt = 20, state = "working", leafId = "leaf-b"),
        )
        assertEquals("mine-now", ownRecordOf(records, leaf = "leaf-a", terminal = "s10"))
    }

    @Test
    fun `when the pane is gone the task points at its own latest run, never at another pane's`() {
        val records = listOf(
            onTerminal("mine-old", "s10", createdAt = 3, leafId = "leaf-a"),
            onTerminal("mine-last", "s10", createdAt = 8, leafId = "leaf-a"),
            onTerminal("stranger", "s10", createdAt = 20, state = "working", leafId = "leaf-b"),
            onTerminal("stranger-ended", "s10", createdAt = 30, leafId = "leaf-c"),
        )
        assertEquals("mine-last", ownRecordOf(records, leaf = "leaf-a", terminal = "s10"))
    }

    @Test
    fun `a task stored with only a terminal id resolves to the record running on it now, else the newest`() {
        val live = listOf(
            onTerminal("old-ended", "s10", createdAt = 2),
            onTerminal("live", "s10", createdAt = 5, state = "idle"),
            onTerminal("newer-ended", "s10", createdAt = 9),
        )
        assertEquals("live", ownRecordOf(live, terminal = "s10"))
        assertEquals("newer-ended", ownRecordOf(live.filter { it.id != "live" }, terminal = "s10"))
    }

    @Test
    fun `a record that is not ended counts as running whether or not its pane snapshot had a leaf`() {
        // The tracker's own lookup (openRecordOf) asks only that a record is not ended.
        val records = listOf(
            onTerminal("running", "s10", createdAt = 1, state = "working", leafId = null),
            onTerminal("ended-newer", "s10", createdAt = 50),
        )
        assertEquals("running", ownRecordOf(records, terminal = "s10"))
    }

    // ── The same, through the index: what is stored, adopted and reported ──────────────────

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
        sessionOfLeaf = { leaf -> panesNow.entries.firstOrNull { it.value == leaf }?.key },
    )

    private fun ownRecordOf(index: TaskIndex, task: String) = index.state.value.tasks.single { it.id == task }.leadRecordId

    @Test
    fun `create_task and set_task_lead store the pane's leaf with the terminal id`() {
        session("s1")
        session("s2")
        val id = index.createTask("Plan", listOf("s1"))
        assertEquals("leaf-s1", repo.loadTaskMeta().single { it.taskId == id }.leadLeaf)
        assertEquals("s1", repo.loadTaskMeta().single { it.taskId == id }.leadSession)

        index.setOwnSession(id, "s2")
        assertEquals("leaf-s2", repo.loadTaskMeta().single { it.taskId == id }.leadLeaf)
        assertEquals("s2", repo.loadTaskMeta().single { it.taskId == id }.leadSession)
    }

    @Test
    fun `a restart that renumbers terminal ids leaves the task with its own pane`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())

        // The restart: leaf-b is now s10 with another agent in it, leaf-a is s9.
        panesNow.clear()
        val restarted = trackerOverRepo()
        restarted.onHook(paneIn("leaf-b", "s10"), event("SessionStart", "a-stranger"))
        val reopened = indexOver(restarted)
        paneIn("leaf-a", "s9")
        reopened.recompute()

        assertEquals(mine.id, ownRecordOf(reopened, task), "the stranger on s10 is not the task's session")
        assertEquals("s9", reopened.listing().single { it.id == task }.ownSessionId, "list_tasks names the id the pane has now")

        restarted.onHook(paneIn("leaf-a", "s9"), event("SessionStart", "a-mine"))
        assertEquals(mine.id, restarted.openRecordOf("s9")!!.id, "the conversation resumed in its own pane")
        reopened.recompute()
        assertEquals(mine.id, ownRecordOf(reopened, task))
        assertEquals("s9", reopened.listing().single { it.id == task }.ownSessionId)
    }

    @Test
    fun `when the task's pane is gone list_tasks has no own session and the stranger on its old id is not it`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())

        panesNow.clear()
        val restarted = trackerOverRepo()
        restarted.onHook(paneIn("leaf-b", "s10"), event("SessionStart", "a-stranger"))
        val reopened = indexOver(restarted)

        assertEquals(mine.id, ownRecordOf(reopened, task), "its own latest run, ended")
        assertNull(reopened.listing().single { it.id == task }.ownSessionId)
    }

    /**
     * A restart has ended every record. The task's own conversation, [conversation], ran in leaf-a as
     * s10, after an older conversation had run in the same pane. Returns the restarted tracker and
     * index, the task, and the id of the record of the own conversation.
     */
    private fun restartedWithOwnConversation(conversation: String): Quad {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-older"))
        now += 10
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", conversation))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())
        panesNow.clear()
        val restarted = trackerOverRepo()
        return Quad(restarted, indexOver(restarted), task, mine.id)
    }

    private data class Quad(val tracker: AgentSessionTracker, val index: TaskIndex, val task: String, val own: String)

    @Test
    fun `resuming the own conversation in a new pane moves the task onto it, not onto the older run in the old pane`() {
        val (restarted, reopened, task, own) = restartedWithOwnConversation("a-mine")
        paneIn("leaf-a", "s8")
        reopened.recompute()
        assertEquals(own, ownRecordOf(reopened, task), "ended, in its own pane")

        // Enter resumes it in a tab of its own: the ended record moves to that pane's leaf.
        restarted.onHook(paneIn("leaf-c", "s9"), event("SessionStart", "a-mine"))
        reopened.recompute()

        assertEquals(own, restarted.openRecordOf("s9")!!.id)
        assertEquals("leaf-c", restarted.openRecordOf("s9")!!.leafId)
        assertEquals(own, ownRecordOf(reopened, task))
        assertEquals("leaf-c", repo.loadTaskMeta().single { it.taskId == task }.leadLeaf)
        assertEquals("s9", reopened.listing().single { it.id == task }.ownSessionId)
    }

    @Test
    fun `a resume the screen showed before the hook named it also moves the task`() {
        val (restarted, reopened, task, own) = restartedWithOwnConversation("a-mine")
        paneIn("leaf-a", "s8")

        // The new tab shows claude on the screen first, then the hook names the conversation.
        val newPane = paneIn("leaf-c", "s9").copy(agent = "claude", state = "working")
        restarted.onPoll(listOf(newPane))
        reopened.recompute()
        assertEquals(own, ownRecordOf(reopened, task), "not yet known to be the same conversation")
        restarted.onHook(newPane, event("SessionStart", "a-mine"))
        reopened.recompute()

        val running = restarted.openRecordOf("s9")!!
        assertEquals("a-mine", running.agentSessionId)
        assertEquals(running.id, ownRecordOf(reopened, task), "the running conversation")
        assertEquals("leaf-c", repo.loadTaskMeta().single { it.taskId == task }.leadLeaf)
        assertTrue(own != running.id, "the old record stays ended in the old pane")
    }

    @Test
    fun `a new agent in the own pane keeps speaking for the task, and the old conversation resumed elsewhere does not take it`() {
        val (restarted, reopened, task, _) = restartedWithOwnConversation("a-mine")
        restarted.onHook(paneIn("leaf-a", "s9"), event("SessionStart", "a-other"))
        val other = restarted.openRecordOf("s9")!!
        reopened.recompute()
        assertEquals(other.id, ownRecordOf(reopened, task))
        assertEquals("a-other", repo.loadTaskMeta().single { it.taskId == task }.leadAgentSession)

        restarted.onHook(paneIn("leaf-c", "s8"), event("SessionStart", "a-mine"))
        reopened.recompute()
        assertEquals(other.id, ownRecordOf(reopened, task))
        assertEquals("leaf-a", repo.loadTaskMeta().single { it.taskId == task }.leadLeaf)
    }

    @Test
    fun `naming an own session again forgets the conversation of the old one`() {
        val (restarted, reopened, task, _) = restartedWithOwnConversation("a-mine")
        assertEquals("a-mine", repo.loadTaskMeta().single { it.taskId == task }.leadAgentSession)
        restarted.onHook(paneIn("leaf-d", "s11"), event("SessionStart", "a-new"))
        reopened.setOwnSession(task, "s11")
        // Stored with the new pane, and the next recompute remembers its conversation, not the old one.
        assertEquals("a-new", repo.loadTaskMeta().single { it.taskId == task }.leadAgentSession)
        assertEquals("leaf-d", repo.loadTaskMeta().single { it.taskId == task }.leadLeaf)
    }

    @Test
    fun `a task stored with only a terminal id takes the pane of the session that runs on it, and keeps it`() {
        val first = trackerOverRepo()
        first.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        val mine = first.openRecordOf("s10")!!
        val task = indexOver(first).createTask("Plan", listOf("s10"))
        first.endAll(emptyList())
        // The row as an older version wrote it: a terminal id and no pane.
        repo.setTaskLead(task, "s10", null)

        panesNow.clear()
        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)
        assertEquals(mine.id, ownRecordOf(reopened, task), "by terminal id until something runs on it")
        assertNull(repo.loadTaskMeta().single { it.taskId == task }.leadLeaf, "an ended record proves nothing about the pane")

        restarted.onHook(paneIn("leaf-a", "s10"), event("SessionStart", "a-mine"))
        reopened.recompute()
        assertEquals("leaf-a", repo.loadTaskMeta().single { it.taskId == task }.leadLeaf)

        // The next restart swaps the ids; the task now follows the pane.
        panesNow.clear()
        val again = trackerOverRepo()
        again.onHook(paneIn("leaf-b", "s10"), event("SessionStart", "a-stranger"))
        val third = indexOver(again)
        paneIn("leaf-a", "s9")
        assertEquals(mine.id, ownRecordOf(third, task))
        assertEquals("s9", third.listing().single { it.id == task }.ownSessionId)
    }

    @Test
    fun `an index loading a reused terminal id points the task at the live session after a restart`() {
        // A previous run left an ended record, created later than the session that is live now,
        // on the same terminal id: loaded from the database, it must not take the task's place.
        repo.saveAgentSession(
            onTerminal("gone", "s1", createdAt = 900_000, leafId = "leaf-s1").copy(endedAt = 950_000),
        )
        val restarted = trackerOverRepo()
        restarted.onHook(pane("s1"), event("SessionStart", "a-live"))
        val live = restarted.openRecordOf("s1")!!
        val reopened = TaskIndex(
            repo, restarted, files, leafOfSession = { if (it in knownPanes) "leaf-$it" else null },
            clock = { now }, newSuffix = { suffixes.next() },
        )
        val id = reopened.createTask("Plan", listOf("s1"))

        assertEquals(live.id, ownRecordOf(reopened, id))
        assertTrue(restarted.snapshot().any { it.id == "gone" }, "the ended record is still loaded")
    }

    @Test
    fun `a started index moves the task onto the live session when an older record is resumed after a restart`() {
        // The order the owner saw: the task and its own session exist, the server restarts, a
        // client is already looking, and only then does the old conversation resume. Its record is
        // older than an ended one in the same pane, so only a push after the resume can move the
        // task onto it.
        val older = session("s1", "a-old")
        val task = index.createTask("Plan", listOf("s1"))
        tracker.onSessionDestroyed("s1", "old screen")
        repo.saveAgentSession(
            onTerminal("gone", "s1", createdAt = 900_000, leafId = "leaf-s1").copy(endedAt = 950_000),
        )
        val restarted = trackerOverRepo()
        val reopened = TaskIndex(
            repo, restarted, files, leafOfSession = { if (it in knownPanes) "leaf-$it" else null },
            clock = { now }, newSuffix = { suffixes.next() },
        )
        reopened.start(CoroutineScope(Dispatchers.Unconfined))
        assertEquals("gone", ownRecordOf(reopened, task), "nothing is live yet")

        restarted.onHook(pane("s1"), event("SessionStart", "a-old"))

        assertEquals(older.id, restarted.openRecordOf("s1")!!.id, "the old record is the one that reopened")
        assertEquals(older.id, ownRecordOf(reopened, task))
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
        assertTrue(adopted.id != first.id)
        assertEquals(task, adopted.taskId)
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
