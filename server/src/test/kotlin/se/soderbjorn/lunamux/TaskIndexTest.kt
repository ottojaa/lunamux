/**
 * Tests for [TaskIndex]: which tasks it publishes and when (an agent-made task only while a
 * conversation carries it, a file's task always), where a task's start comes from and that it never
 * moves, which conversation speaks for a task (one at a time, never a helper), how a task promised
 * to a pane with no agent reaches the session that starts there, and how a task, its status and its
 * main conversation survive a restart. The operations the MCP task tools expose are tested through
 * those tools, in [se.soderbjorn.lunamux.mcp.McpTaskToolsTest].
 *
 * Runs against a real SQLite database with a fake clock, ids and pane lookup, so no terminal is
 * needed.
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
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskIndexTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-task-index", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val knownPanes = mutableSetOf<String>()
    private var suffixes = generateSequence(1) { it + 1 }.map { "s$it" }.iterator()

    private val tracker = trackerOverRepo()
    private val index = indexOver(tracker) { if (it in knownPanes) "leaf-$it" else null }

    private fun trackerOverRepo() = AgentSessionTracker(
        repo, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" },
        // A process with a larger pid runs underneath one with a smaller: how a helper claude is told from a replacement.
        isUnder = { pid, host -> pid > host },
    ).also { it.start() }

    private fun indexOver(over: AgentSessionTracker, leafOf: (String) -> String? = { null }) = TaskIndex(
        repo, over, files, leafOfSession = leafOf, clock = { now }, newSuffix = { suffixes.next() },
    )

    private fun pane(id: String, agent: String? = null, leaf: String = "leaf-$id") =
        PaneSnapshot(id, leafId = leaf, cwd = "/work/p-$id", programTitle = null, agent = agent)
            .also { knownPanes += id }

    private fun event(name: String, id: String): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", id)
    }

    /** A Claude session running in pane [id], known to the tracker by hook. */
    private fun session(id: String, agentId: String = "a-$id") {
        tracker.onHook(pane(id), event("SessionStart", agentId))
        now += 10
    }

    private fun file(id: String, title: String = "File $id", modified: Long = 99) = TaskFile(id, title, repo = null, open = null, modified = modified)

    private fun published() = index.state.value.tasks
    private fun taskOf(sessionId: String) = tracker.openRecordOf(sessionId)!!.taskId

    @Test
    fun `an agent-made task is published only while a conversation carries it, and a file's task always is`() {
        val id = index.createTask("Refactor", emptyList())
        assertTrue(published().isEmpty(), "nothing carries it yet")
        assertTrue(id in index.state.value.known, "but it exists")

        session("s1")
        index.addToTask(id, listOf("s1"))
        assertEquals(listOf(id), published().map { it.id })

        index.removeFromTask(listOf("s1"))
        assertTrue(published().isEmpty())

        files.value = listOf(file("prog"))
        index.recompute()
        assertEquals(listOf("prog"), published().map { it.id }, "a file's task needs no session")
        assertEquals(setOf(id, "prog"), index.state.value.known)
    }

    @Test
    fun `a record belongs to the task whose id it carries, and an id nothing knows is not a task`() {
        session("s1"); session("s2")
        files.value = listOf(file("f"))
        index.recompute()
        index.addToTask("f", listOf("s1"))
        assertEquals("f", index.taskIdOf(tracker.openRecordOf("s1")!!))
        assertNull(index.taskIdOf(tracker.openRecordOf("s2")!!))

        val record = tracker.openRecordOf("s2")!!
        tracker.setTask(record.id, "nobody-knows")
        assertNull(index.taskIdOf(tracker.openRecordOf("s2")!!), "an unknown id is not a task")
        assertFalse(published().any { it.id == "nobody-knows" })
    }

    @Test
    fun `a task file and a stored row with one id are one task, with the file's title`() {
        session("s1")
        val id = index.createTask("Row title", listOf("s1"))
        files.value = listOf(file(id, title = "File title"))
        index.recompute()
        assertEquals(listOf(id to "File title"), published().map { it.id to it.title })
        assertEquals(listOf("file"), index.listing().map { it.source }, "listed once, as the file's")
    }

    @Test
    fun `a titleless row, which is what a task file leaves behind, is not a task`() {
        session("s1")
        repo.stampTaskStart("file-gone", 3)
        val rebuilt = indexOver(tracker)
        val record = tracker.openRecordOf("s1")!!
        tracker.setTask(record.id, "file-gone")
        rebuilt.recompute()
        assertTrue(rebuilt.state.value.tasks.isEmpty())
        assertFalse("file-gone" in rebuilt.state.value.known)
        assertNull(rebuilt.taskIdOf(tracker.openRecordOf("s1")!!))
    }

    @Test
    fun `a task starts when its first session was created, else when its file was written, else when it was made`() {
        // First session: the earliest summary start wins.
        session("a"); session("b")
        val id = index.createTask("Sessions", listOf("b", "a"))
        assertEquals(tracker.summaries.value.minOf { it.startedAt }, published().single { it.id == id }.startedAt)

        // File with no session: the file's modified time.
        files.value = listOf(file("f", modified = 99))
        index.recompute()
        assertEquals(99L, published().single { it.id == "f" }.startedAt)

        // A made task with no session yet and then one: stamped when first published, from the session.
        now = 50_000
        val lone = index.createTask("Later", emptyList())
        session("c")
        now = 60_000
        index.addToTask(lone, listOf("c"))
        assertEquals(tracker.summaries.value.single { it.id == "a-c" }.startedAt, published().single { it.id == lone }.startedAt)
    }

    @Test
    fun `a task's start is stamped once and never moves, when its sessions come and go or it restarts`() {
        session("a")
        val id = index.createTask("Stable", listOf("a"))
        val first = published().single().startedAt!!
        assertEquals(first, repo.loadTasks().single { it.id == id }.startedAt, "stored the moment it was published")

        tracker.onSessionDestroyed("a", null)
        now += 100_000
        session("b")
        index.addToTask(id, listOf("b"))
        assertEquals(first, published().single().startedAt, "a later, younger session does not move it")

        val restarted = indexOver(tracker)
        assertEquals(first, restarted.state.value.tasks.single { it.id == id }.startedAt)
    }

    @Test
    fun `a title is trimmed, collapsed and cut, an empty one is refused, and an id is a slug with a suffix drawn again on a collision`() {
        val id = index.createTask("  Fix   the\n  importer!  ", emptyList())
        assertEquals("fix-the-importer-s1", id)
        assertEquals("Fix the importer!", index.titleOf(id))

        assertEquals("task-s2", index.createTask("???", emptyList()), "no letters: the slug is 'task'")
        assertEquals(120, index.titleOf(index.createTask("x".repeat(300), emptyList()))!!.length)
        val long = index.createTask("a".repeat(100), emptyList())
        assertEquals(40 + "-s4".length, long.length, "the slug is cut to 40")

        suffixes = listOf("s1", "s1", "s9").iterator()
        val again = index.createTask("Fix the importer!", emptyList())
        assertEquals("fix-the-importer-s9", again, "s1 was taken, so the suffix was drawn again")

        assertEquals("A task needs a title.", assertFailsWith<TaskException> { index.createTask(" \n ", emptyList()) }.message)
    }

    @Test
    fun `a bad session in a request changes nothing`() {
        session("s1")
        val before = repo.loadTasks().size
        val e = assertFailsWith<TaskException> { index.createTask("Nope", listOf("s1", "ghost")) }
        assertEquals("Unknown session 'ghost'. Use a session id from list_sessions, or \$LUNAMUX_SESSION_ID for your own pane.", e.message)
        assertEquals(before, repo.loadTasks().size, "no task was stored")
        assertNull(taskOf("s1"))

        val id = index.createTask("Real", emptyList())
        assertFailsWith<TaskException> { index.addToTask(id, listOf("s1", "ghost")) }
        assertNull(taskOf("s1"))
        assertFailsWith<TaskException> { index.removeFromTask(listOf("s1", "ghost")) }
    }

    @Test
    fun `a rename changes an agent-made title and refuses a file's task and an unknown one`() {
        val id = index.createTask("Old", emptyList())
        index.renameTask(id, "  New   name ")
        assertEquals("New name", index.titleOf(id))
        assertEquals("New name", repo.loadTasks().single { it.id == id }.title, "written through")

        files.value = listOf(file("f"))
        assertEquals(
            "Task 'f' is declared by a task file, which owns its title. Edit the file's title instead.",
            assertFailsWith<TaskException> { index.renameTask("f", "Mine") }.message,
        )
        assertEquals(
            "Unknown task 'nope'. Use list_tasks to see the tasks there are, or create_task to make one.",
            assertFailsWith<TaskException> { index.renameTask("nope", "Mine") }.message,
        )
        assertEquals("A task needs a title.", assertFailsWith<TaskException> { index.renameTask(id, "") }.message)
    }

    @Test
    fun `the caller's pane speaks for the task it makes, the first session when there is no caller, and nobody when no agent runs there`() {
        session("boss"); session("a")
        val byCaller = index.createTask("By caller", listOf("a"), callerSessionId = "boss")
        assertEquals("a-boss", index.listing().single { it.id == byCaller }.mainConversation)

        val byFirst = index.createTask("By first", listOf("a"))
        assertEquals("a-a", index.listing().single { it.id == byFirst }.mainConversation)

        knownPanes += "bare"
        val none = index.createTask("None", listOf("bare"))
        assertNull(index.listing().single { it.id == none }.mainConversation, "no agent runs in the pane")
        assertEquals(
            "No agent runs in session 'bare', so there is no conversation to name. Start the agent there first, or pass a conversationId.",
            assertFailsWith<TaskException> { index.setOwnSession(none, "bare") }.message,
        )
    }

    @Test
    fun `a manager that starts its next task from the same conversation speaks for that one only`() {
        session("m")
        session("run")
        val first = index.createTask("Rebuild the views", listOf("m", "run"))
        tracker.onSessionDestroyed("run", null)
        val next = index.createTask("Assessments never load", listOf("m"))

        val byId = published().associateBy { it.id }
        assertEquals("a-m", byId.getValue(next).main)
        assertNull(byId[first]?.main, "the finished task no longer has a live speaker")
        assertNull(index.listing().single { it.id == first }.mainConversation, "list_tasks says the same")
        assertEquals("a-m", index.listing().single { it.id == next }.mainConversation)
    }

    @Test
    fun `spokenForElsewhere is true only when the conversation sits in another task that names it too`() {
        fun summary(id: String, task: String?) = AgentSessionSummary(id = id, agent = "claude", taskId = task)
        val rows = mapOf(
            "a" to SettingsRepository.TaskRow("a", title = "A", mainConversation = "c1"),
            "b" to SettingsRepository.TaskRow("b", title = "B", mainConversation = "c1"),
            "c" to SettingsRepository.TaskRow("c", title = "C", mainConversation = "other"),
        )
        val summaries = listOf(summary("c1", "b"), summary("loose", null))
        assertTrue(TaskIndex.spokenForElsewhere("a", "c1", summaries, rows), "it sits in b, and b names it")
        assertFalse(TaskIndex.spokenForElsewhere("b", "c1", summaries, rows), "its own task")
        assertFalse(TaskIndex.spokenForElsewhere("a", "loose", summaries, rows), "in no task")
        assertFalse(TaskIndex.spokenForElsewhere("a", "gone", summaries, rows), "unknown")
        assertFalse(TaskIndex.spokenForElsewhere("a", "c1", listOf(summary("c1", "c")), rows), "c names another conversation")
    }

    @Test
    fun `a helper claude started in a task's pane joins the task, shows beside the conversation, and never speaks for it`() {
        val p = pane("s1", agent = "claude")
        tracker.onHook(p, event("SessionStart", "a-main"), pid = 100)
        val task = index.createTask("Plan", listOf("s1"))

        // A plan reviewer starts in the same pane while the conversation is still running; the two
        // conversations' hooks interleave.
        tracker.onHook(p, event("SessionStart", "a-reviewer"), pid = 200)
        tracker.onHook(p, event("UserPromptSubmit", "a-reviewer"), pid = 200)
        tracker.onHook(p, event("PreToolUse", "a-main"), pid = 100)
        index.setOwnSession(task, "s1")
        index.createTask("Another", emptyList(), callerSessionId = "s1")

        val byConversation = tracker.summaries.value.associateBy { it.id }
        assertEquals("a-main", index.listing().single { it.id == task }.mainConversation)
        assertEquals(listOf("working", task), byConversation.getValue("a-reviewer").let { listOf(it.shown.name.lowercase(), it.taskId) })

        // Naming the helper directly stores its host.
        index.setMainConversation(task, "a-reviewer")
        assertEquals("a-main", index.listing().single { it.id == task }.mainConversation, "a helper cannot speak: its host does")
        assertEquals("a-main", repo.loadTasks().single { it.id == task }.mainConversation, "and the host is what is stored")

        tracker.onHook(p, event("SessionEnd", "a-reviewer"), pid = 200)
        assertEquals("a-main", tracker.openRecordOf("s1")!!.agentSessionId)
    }

    @Test
    fun `a task names a conversation directly, open or closed, by conversation id or record id, and an unknown one is refused`() {
        session("a")
        val id = index.createTask("Lead", emptyList())
        val record = tracker.openRecordOf("a")!!
        index.setMainConversation(id, record.id)
        assertEquals("a-a", repo.loadTasks().single { it.id == id }.mainConversation, "the conversation's id is stored, not the record's")

        tracker.onSessionDestroyed("a", null)
        index.setMainConversation(id, "a-a")
        assertEquals("a-a", index.listing().single { it.id == id }.mainConversation, "a closed conversation can still lead")
        assertNull(index.listing().single { it.id == id }.mainSessionId, "but it has no pane")

        assertEquals(
            "Unknown conversation 'nobody'. Use the conversationId list_tasks or list_sessions shows.",
            assertFailsWith<TaskException> { index.setMainConversation(id, "nobody") }.message,
        )
        assertEquals("a-a", index.listing().single { it.id == id }.mainConversation, "refusal changed nothing")
    }

    @Test
    fun `a status is one trimmed line of 120 characters, an empty one clears it, and a question is kept only with a status`() {
        val id = index.createTask("Work", emptyList())
        assertEquals("first line second third", index.setStatus(id, "  first line\n\n  second\r\nthird  "))
        assertEquals("y".repeat(119), index.setStatus(id, "${"y".repeat(119)} zzz"), "a cut never leaves a trailing space")

        index.setStatus(id, "which database?", questionWaits = true)
        assertTrue(index.listing().single().questionWaits)
        index.setStatus(id, "migrating")
        assertFalse(index.listing().single().questionWaits, "set afresh by each call")

        assertNull(index.setStatus(id, "", questionWaits = true))
        val row = repo.loadTasks().single { it.id == id }
        assertEquals(listOf(null, null, false), listOf(row.status, row.statusAt, row.questionWaits), "an empty text clears all of it")

        assertEquals("Unknown task 'x'. Use list_tasks to see the tasks there are, or create_task to make one.",
            assertFailsWith<TaskException> { index.setStatus("x", "hi") }.message)
    }

    @Test
    fun `a paused task keeps the time its pause began while calls repeat it, an empty text included, and a call without it ends it`() {
        session("a")
        val id = index.createTask("Access redesign", listOf("a"))
        now = 7_000
        index.setStatus(id, "Paused by the owner", paused = true)
        assertEquals(7_000L, published().single().pausedAt)

        now = 8_000
        index.setStatus(id, "", paused = true)
        assertEquals(7_000L, published().single().pausedAt, "still paused from when it began, with nothing to say")
        assertNull(published().single().status)
        assertEquals(7_000L, repo.loadTasks().single { it.id == id }.pausedAt, "stored, so a restart keeps it")

        index.setStatus(id, "back at it")
        assertNull(published().single().pausedAt)
    }

    @Test
    fun `a status that only counts states is kept but not shown`() {
        for (counts in listOf("1 working", "2 waiting, 1 working", "1 run working · 3 idle", "2 need you and 1 done")) {
            assertTrue(TaskIndex.isCountsOnly(counts), counts)
        }
        for (text in listOf("Mango deploy running", "3 PRs open", "1 working; deploy to mango next", "", "Waiting on 2 reviews")) {
            assertFalse(TaskIndex.isCountsOnly(text), text)
        }

        session("a")
        val id = index.createTask("Counts", listOf("a"))
        index.setStatus(id, "2 working, 1 waiting")
        assertNull(published().single().status, "the overview already shows the counts")
        assertNull(published().single().statusAt)
        assertEquals("2 working, 1 waiting", index.listing().single().status, "list_tasks still says what was set")
        index.setStatus(id, "Mango deploy running")
        assertEquals("Mango deploy running", published().single().status)
        assertEquals(now, published().single().statusAt)
    }

    @Test
    fun `a question waits only with a status that is shown`() {
        session("a")
        val id = index.createTask("Ask", listOf("a"))
        index.setStatus(id, "which database?", questionWaits = true)
        assertTrue(published().single().questionWaits)
        index.setStatus(id, "", questionWaits = true)
        assertFalse(published().single().questionWaits)
    }

    @Test
    fun `a task promised to a pane with no agent goes to the session that starts there, once`() {
        val task = index.createTask("Split work", emptyList())
        pane("s5")
        index.addToTask(task, listOf("s5"))
        assertEquals(listOf("leaf-s5"), index.openLeavesOf(task), "a promised pane counts as one of the task's")
        tracker.onHook(pane("s5"), event("SessionStart", "a-5"))
        assertEquals(task, tracker.openRecordOf("s5")!!.taskId)

        tracker.onSessionDestroyed("s5", null)
        tracker.onHook(pane("s5"), event("SessionStart", "a-other"))
        assertNull(tracker.openRecordOf("s5")!!.taskId, "the promise was used up")
    }

    @Test
    fun `a promise can be cancelled by removing the pane, and a promise is not stored`() {
        val task = index.createTask("Split work", emptyList())
        pane("s5")
        index.addToTask(task, listOf("s5"))
        index.removeFromTask(listOf("s5"))
        assertTrue(index.openLeavesOf(task).isEmpty())
        tracker.onHook(pane("s5"), event("SessionStart", "a-5"))
        assertNull(tracker.openRecordOf("s5")!!.taskId)
    }

    @Test
    fun `a resumed conversation keeps its task, in another pane`() {
        session("s1", "a-1")
        val task = index.createTask("Refactor", listOf("s1"))
        val first = tracker.openRecordOf("s1")!!
        tracker.onSessionDestroyed("s1", "old screen")
        tracker.onHook(pane("s9"), event("SessionStart", "a-1"))
        assertEquals(listOf(first.id, task), tracker.openRecordOf("s9")!!.let { listOf(it.id, index.taskIdOf(it)) })
    }

    @Test
    fun `the state follows the tracker once started, and an ended record keeps its task`() {
        index.start(CoroutineScope(Dispatchers.Unconfined))
        session("s1")
        val id = index.createTask("Live", emptyList())
        assertTrue(published().isEmpty())
        index.addToTask(id, listOf("s1"))
        assertEquals(id, published().single().id)
        tracker.onSessionDestroyed("s1", null)
        assertEquals(id, index.taskIdOf(tracker.snapshot().single()))
    }

    @Test
    fun `the state follows the task files once started`() {
        index.start(CoroutineScope(Dispatchers.Unconfined))
        assertTrue(published().isEmpty())
        files.value = listOf(file("f"))
        assertEquals(listOf("f"), published().map { it.id })
        files.value = emptyList()
        assertTrue(published().isEmpty())
    }

    @Test
    fun `tasks are published in id order, whatever the order they were made or declared in`() {
        files.value = listOf(file("zeta"), file("alpha"))
        session("s1"); session("s2")
        val b = index.createTask("Bbb", listOf("s1"))
        val a = index.createTask("Aaa", listOf("s2"))
        assertEquals(listOf("alpha", a, b, "zeta").sorted(), published().map { it.id })
    }

    @Test
    fun `list_tasks lists files first and agent-made tasks after, each sorted by id, with the sessions each holds`() {
        files.value = listOf(file("zz"), file("aa"))
        session("s1")
        val made = index.createTask("Made", listOf("s1"))
        val rows = index.listing()
        assertEquals(listOf("aa" to "file", "zz" to "file", made to "agents"), rows.map { it.id to it.source })
        assertEquals("s1", rows.last().sessions.single().sessionId)
        assertEquals("claude session", rows.last().sessions.single().title)
        assertEquals(published().single { it.id == made }.startedAt, rows.last().startedAt)
    }

    @Test
    fun `a task, its status, its pause and its main conversation survive a restart, and a restart does not change what is published`() {
        session("s10")
        val task = index.createTask("Plan", listOf("s10"))
        now = 5_000
        index.setStatus(task, "migrating", questionWaits = true)
        index.setStatus(task, "migrating", questionWaits = true, paused = true)
        val before = published()
        tracker.endAll(emptyList())

        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)
        assertEquals(before, reopened.state.value.tasks, "published exactly as before")
        val row = repo.loadTasks().single { it.id == task }
        assertEquals(listOf("migrating", true, 5_000L), listOf(row.status, row.questionWaits, row.pausedAt))
        assertEquals("a-s10", reopened.listing().single { it.id == task }.mainConversation)
    }

    @Test
    fun `after a restart the task still names its conversation, and a stranger in the old pane is not it`() {
        session("s10")
        val mine = tracker.openRecordOf("s10")!!
        val task = index.createTask("Plan", listOf("s10"))
        tracker.endAll(emptyList())

        val restarted = trackerOverRepo()
        val reopened = indexOver(restarted)
        restarted.onHook(pane("s9", leaf = "leaf-s10"), event("SessionStart", "a-other"))
        reopened.recompute()
        assertEquals("a-s10", reopened.listing().single { it.id == task }.mainConversation)

        // The conversation resumes in a new pane: its earlier record reopens and keeps the task.
        restarted.onHook(pane("s8"), event("SessionStart", "a-s10"))
        reopened.recompute()
        assertEquals(mine.id, restarted.openRecordOf("s8")!!.id, "its earlier record reopened")
        assertEquals(task, restarted.openRecordOf("s8")!!.taskId)
        assertEquals(1, restarted.summaries.value.count { it.id == "a-s10" && it.ended == null })
        assertEquals("s8", reopened.listing().single { it.id == task }.mainSessionId)
    }

    @Test
    fun `the rows are loaded once, and an operation is written through to the table`() {
        val id = index.createTask("Persist", emptyList())
        index.renameTask(id, "Persisted")
        index.setStatus(id, "hello")
        val row = repo.loadTasks().single { it.id == id }
        assertEquals(listOf("Persisted", "hello"), listOf(row.title, row.status))
        assertNotEquals(0L, row.createdAt)
        assertNotNull(row.startedAt, "the status call stamped the task's start")
    }
}
