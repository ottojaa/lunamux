/**
 * Tests for [tabMark]: a tab wears the one mark its marked panes share, and none when they wear
 * two or more, when none is marked, or when the only marked session has ended.
 *
 * @see tabMark
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PaneMarksTest {
    private fun session(leaf: String, mark: Int?, threadKey: String? = null, state: String = "idle") = AgentSessionSummary(
        id = "s-$leaf", agent = "claude", state = state, stateSince = 0, title = "t", links = emptyList(),
        leafId = leaf, mark = mark, threadKey = threadKey,
    )

    @Test
    fun aTabWearsTheMarkItsPanesShare() {
        assertEquals(14, tabMark(listOf(session("a", 14, "path:/x"), session("b", 14, "path:/x")), listOf("a", "b")))
    }

    @Test
    fun aTabOfTwoThreadsWearsNone() {
        assertNull(tabMark(listOf(session("a", 14), session("b", 31)), listOf("a", "b")))
    }

    @Test
    fun aPaneWhoseSessionWearsATasksMarkDecidesTheTabByThatMarkNotByItsThreadKey() {
        // Two panes of one worktree, both in a task whose mark came from another thread: one mark.
        val inTask = listOf(session("a", 5, "path:/x"), session("b", 5, "path:/x"))
        assertEquals(5, tabMark(inTask, listOf("a", "b")))
        // Two threads of one task wear the task's mark, so the tab wears it too.
        val oneTask = listOf(session("a", 5, "path:/x"), session("b", 5, "path:/y"))
        assertEquals(5, tabMark(oneTask, listOf("a", "b")))
    }

    @Test
    fun aPaneOfAThreadAndAPaneOfATaskWithDifferentMarksLeaveTheTabBare() {
        assertNull(tabMark(listOf(session("a", 14, "path:/x"), session("b", 5, "path:/x")), listOf("a", "b")))
    }

    @Test
    fun anUnmarkedPaneOrOneOutsideTheTabOrAnEndedSessionDoesNotCount() {
        val all = listOf(session("a", 14), session("b", null), session("c", 31), session("d", 7, state = "ended"))
        assertEquals(14, tabMark(all, listOf("a", "b", "d")))
        assertNull(tabMark(all, listOf("b", "d")))
        assertNull(tabMark(emptyList(), listOf("a")))
    }
}
