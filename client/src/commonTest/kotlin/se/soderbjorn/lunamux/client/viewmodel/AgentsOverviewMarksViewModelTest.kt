/**
 * Tests that the mark of a thread of work reaches the rows of the agents overview: a task header
 * and its members wear the task's mark, a loose session wears its own, a search hit wears the
 * mark the server gave it, and a row the server gave none stays without one. It also checks that
 * a session summary or task saved before marks existed still decodes, with no mark.
 *
 * @see AgentsOverviewBackingViewModel.Row.mark
 * @see se.soderbjorn.lunamux.AgentSessionSummary.mark
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.windowJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentsOverviewMarksViewModelTest {
    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val now = 10_000_000L
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { sent += it }

    private fun session(id: String, state: String, mark: Int?, taskId: String? = null, endedAt: Long? = null) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = now - 60_000, title = "session $id", links = emptyList(),
        repo = "proj", branch = "b", cwd = "/work/proj", leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt,
        lastActivityAt = if (state == "working") now - 1_000 else null, taskId = taskId, mark = mark,
    )

    private fun member(key: String, state: String, label: String? = null, recordId: String? = null) =
        AgentTaskMember(key = key, title = "member $key", state = state, sinceMillis = now - 60_000, label = label, recordId = recordId)

    @Test
    fun aTaskHeaderAndItsMembersWearTheTasksMarkWhetherOrNotASessionHostsThem() {
        windowState.updateAgentSessions(listOf(session("hosted", "working", mark = 3, taskId = "t")), true)
        windowState.updateAgentTasks(
            listOf(
                AgentTask(
                    id = "t", title = "Invoice export", repo = "proj", source = "file", hasOpen = false, mark = 14,
                    members = listOf(member("a", "working", recordId = "hosted"), member("b", "idle", label = "in review")),
                ),
            ),
        )
        vm.toggleTask("t")
        val rows = vm.state.now.associateBy { it.id }
        assertEquals(14, rows.getValue("task:t").mark)
        assertEquals(14, rows.getValue("member:t:b").mark, "a member no session hosts")
        // A hosted member shows the task's mark, not the one its own session was given first.
        assertEquals(14, rows.getValue("hosted").mark)
    }

    @Test
    fun aLooseSessionWearsItsOwnMarkAndOneWithNoThreadWearsNone() {
        windowState.updateAgentSessions(
            listOf(session("marked", "working", mark = 15), session("plain", "working", mark = null)),
            true,
        )
        val rows = vm.state.now.associateBy { it.id }
        assertEquals(15, rows.getValue("marked").mark)
        assertNull(rows.getValue("plain").mark)
    }

    @Test
    fun aTaskWithoutAMarkLeavesItsHeaderBare() {
        windowState.updateAgentSessions(listOf(session("busy", "working", mark = null)), true)
        windowState.updateAgentTasks(
            listOf(AgentTask(id = "t", title = "Old", repo = null, source = "file", hasOpen = false, refreshedAtMillis = now - 1_000, members = listOf(member("a", "working"), member("b", "idle", label = "in review")))),
        )
        assertNull((vm.state.now + vm.state.earlier + vm.state.fortnight).single { it.id == "task:t" }.mark)
    }

    @Test
    fun aTaskOfOneMemberDrawnAsOnePlainRowWearsTheTasksMark() {
        windowState.updateAgentSessions(listOf(session("busy", "working", mark = null)), true)
        windowState.updateAgentTasks(
            listOf(
                AgentTask(
                    id = "t", title = "Solo", repo = null, source = "file", hasOpen = false, mark = 8,
                    refreshedAtMillis = now - 1_000, members = listOf(member("a", "working")),
                ),
            ),
        )
        val marks = (vm.state.now + vm.state.earlier + vm.state.fortnight).filter { it.id != "busy" }.map { it.mark }
        assertEquals(listOf(8), marks)
    }

    @Test
    fun aSearchHitWearsTheMarkTheServerGaveIt() {
        val ended = session("old", "ended", mark = 16, endedAt = now - 5 * 24 * 3_600_000L)
        windowState.updateAgentSessions(listOf(ended), false)
        vm.open()
        vm.setQuery("session")
        windowState.updateAgentMatches(WindowEnvelope.AgentSessionMatches("session", listOf(AgentSessionMatch(ended, "title", null, 0, 7))))
        assertEquals(16, vm.state.rows.single().mark)
    }

    @Test
    fun aSummaryAndATaskSavedBeforeMarksDecodeWithNoMark() {
        val summary = windowJson.decodeFromString<AgentSessionSummary>(
            """{"id":"r1","agent":"claude","state":"idle","stateSince":5,"links":[]}""",
        )
        assertNull(summary.mark)
        assertNull(summary.threadKey)
        val task = windowJson.decodeFromString<AgentTask>(
            """{"id":"t","title":"T","source":"file","hasOpen":false,"members":[]}""",
        )
        assertNull(task.mark)
    }

    @Test
    fun aMarkSurvivesTheWireRoundTrip() {
        val s = session("x", "idle", mark = 29)
        val back = windowJson.decodeFromString<AgentSessionSummary>(windowJson.encodeToString(AgentSessionSummary.serializer(), s.copy(threadKey = "path:/a")))
        assertEquals(29, back.mark)
        assertEquals("path:/a", back.threadKey)
    }
}
