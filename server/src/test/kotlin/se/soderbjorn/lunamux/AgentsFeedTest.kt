/**
 * Tests for [buildAgentsFrame] and [AgentsFeed]: the frame carries nothing that points outside it,
 * and it is encoded once per change and shared, so every connection sends the same bytes.
 *
 * @see AgentsFeed
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentsFeedTest {
    private fun row(id: String, task: String? = null) = AgentSessionSummary(id = id, agent = "claude", taskId = task)
    private fun task(id: String, main: String? = null) = AgentTask(id = id, title = "Task $id", main = main)

    /** Every `taskId` and every `main` resolves inside the frame, even if the two owners published at different moments. */
    @Test
    fun `a frame never points outside itself`() {
        val frame = buildAgentsFrame(
            summaries = listOf(row("c1", "t1"), row("c2", "gone"), row("c3", null)),
            tasks = TaskState(listOf(task("t1", main = "c1"), task("t2", main = "missing")), known = setOf("t1", "t2")),
            claudeConnected = true,
        )
        val sessionIds = frame.sessions.map { it.id }.toSet()
        val taskIds = frame.tasks.map { it.id }.toSet()
        assertTrue(frame.sessions.all { it.taskId == null || it.taskId in taskIds })
        assertTrue(frame.tasks.all { it.main == null || it.main in sessionIds })
        assertEquals("t1", frame.sessions.first { it.id == "c1" }.taskId, "a good link is kept")
        assertNull(frame.sessions.first { it.id == "c2" }.taskId)
        assertEquals("c1", frame.tasks.first { it.id == "t1" }.main)
        assertNull(frame.tasks.first { it.id == "t2" }.main)
        assertTrue(frame.claudeConnected)
    }

    /** A snapshot that is already consistent comes through unchanged. */
    @Test
    fun `a consistent snapshot is passed through as it is`() {
        val rows = listOf(row("c1", "t1"))
        val tasks = TaskState(listOf(task("t1", "c1")), setOf("t1"))
        val frame = buildAgentsFrame(rows, tasks, false)
        assertEquals(rows, frame.sessions)
        assertEquals(tasks.tasks, frame.tasks)
    }

    /** One encoded value serves every reader; it changes when a fact does, and not otherwise. */
    @Test
    fun `the encoded frame is shared and changes only with the facts`() {
        val summaries = MutableStateFlow(listOf(row("c1", "t1")))
        val tasks = MutableStateFlow(TaskState(listOf(task("t1", "c1")), setOf("t1")))
        val connected = MutableStateFlow(false)
        val feed = AgentsFeed(CoroutineScope(Dispatchers.Unconfined), summaries, tasks, connected)

        val first = feed.frame.value
        assertSame(first, feed.frame.value, "every connection reads the same string")
        assertTrue(first.contains("\"type\":\"agents\""), first)

        connected.value = true
        val second = feed.frame.value
        assertNotEquals(first, second)

        summaries.value = listOf(row("c1", "t1"))
        assertSame(second, feed.frame.value, "an equal snapshot is not encoded again")

        connected.value = false
        assertEquals(first, feed.frame.value)
    }
}
