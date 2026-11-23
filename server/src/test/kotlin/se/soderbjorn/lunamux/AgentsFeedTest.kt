/**
 * Tests for [AgentsFeed] and the single snapshot it encodes: the rows and the tasks of a frame come
 * from one [TaskState], so a membership change made through [TaskIndex] never produces a frame in
 * which a conversation lacks its task or a task names a conversation the frame does not hold; and
 * the frame is encoded once per change and shared, so every connection sends the same bytes.
 *
 * @see AgentsFeed
 * @see TaskIndex
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentsFeedTest {
    private var now = 1_000L
    private var nextId = 0
    private val dbDir = File.createTempFile("lunamux-agents-feed", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val tracker = AgentSessionTracker(
        repo, folderOf = { null }, clock = { now }, newId = { "r${++nextId}" }, isUnder = { pid, host -> pid > host },
    ).also { it.start() }
    private val index = TaskIndex(
        repo, tracker, MutableStateFlow(emptyList()),
        leafOfSession = { "leaf-$it" }, clock = { now }, newSuffix = { "x" },
    ).also { it.start(scope) }

    /** Every state [TaskIndex] publishes, in order: what [AgentsFeed] would turn into a frame. */
    private val published = mutableListOf<TaskState>().also { seen -> scope.launch { index.state.collect { seen += it } } }

    private fun startSession(pane: String) {
        val snapshot = PaneSnapshot(pane, leafId = "leaf-$pane", cwd = "/work/$pane", programTitle = null, agent = null)
        val event: JsonObject = buildJsonObject { put("hook_event_name", "SessionStart"); put("session_id", "a-$pane") }
        tracker.onHook(snapshot, event)
        now += 10
    }

    private fun assertSelfContained(state: TaskState) {
        val sessionIds = state.sessions.map { it.id }.toSet()
        val taskIds = state.tasks.map { it.id }.toSet()
        assertTrue(state.sessions.all { it.taskId == null || it.taskId in taskIds }, "a row points at a task outside the frame: $state")
        assertTrue(state.tasks.all { it.main == null || it.main in sessionIds }, "a task names a row outside the frame: $state")
    }

    /**
     * `create_task` and `add_to_task` set the record's task before the index recomputes. The frame is
     * built only from what the index publishes, so no frame ever shows the conversation without its task
     * after it has joined one, and every frame is self-contained.
     */
    @Test
    fun `a membership change through the index never yields a frame that lacks the task`() {
        startSession("s1")
        startSession("s2")
        val first = index.createTask("Refactor", listOf("s1"))
        val second = index.createTask("Later", emptyList())
        index.addToTask(second, listOf("s2"))

        published.forEach(::assertSelfContained)
        val joined = published.indexOfFirst { st -> st.sessions.any { it.taskId == first } }
        assertTrue(joined >= 0, "the new task reached a frame")
        for (st in published.drop(joined)) {
            val row = st.sessions.firstOrNull { it.taskId == first || it.id == published[joined].sessions.first { r -> r.taskId == first }.id }
            assertEquals(first, row?.taskId, "the conversation lost its task in a later frame")
        }
        val last = published.last()
        assertEquals(setOf(first, second), last.tasks.map { it.id }.toSet())
        assertEquals(2, last.sessions.count { it.taskId != null })
    }

    /** A row whose task no longer exists belongs to no task, in the same snapshot that drops the task. */
    @Test
    fun `a row whose task is gone is cleared in the snapshot itself`() {
        startSession("s1")
        val id = index.createTask("Temp", listOf("s1"))
        assertEquals(id, index.state.value.sessions.single().taskId)
        index.removeFromTask(listOf("s1"))
        val state = index.state.value
        assertNull(state.sessions.single().taskId)
        assertTrue(state.tasks.isEmpty())
        published.forEach(::assertSelfContained)
    }

    /** One encoded value serves every reader; it changes when a fact does, and not otherwise. */
    @Test
    fun `the encoded frame is shared and changes only with the facts`() {
        val tasks = MutableStateFlow(TaskState(listOf(AgentSessionSummary(id = "c1", agent = "claude", taskId = "t1")),
            listOf(AgentTask(id = "t1", title = "Task t1", main = "c1")), setOf("t1")))
        val connected = MutableStateFlow(false)
        val feed = AgentsFeed(scope, tasks, connected)

        val first = feed.frame.value
        assertSame(first, feed.frame.value, "every connection reads the same string")
        assertTrue(first.contains("\"type\":\"agents\""), first)

        connected.value = true
        val second = feed.frame.value
        assertNotEquals(first, second)

        tasks.value = tasks.value.copy()
        assertSame(second, feed.frame.value, "an equal snapshot is not encoded again")

        connected.value = false
        assertEquals(first, feed.frame.value)
    }
}
