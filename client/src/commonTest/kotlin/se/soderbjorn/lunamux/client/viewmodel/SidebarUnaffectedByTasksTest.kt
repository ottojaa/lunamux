/**
 * Guards that the sidebar is drawn from the layout and the per-pane states alone: the tasks and
 * agent sessions the agents overview folds into one row per task never reach it.
 *
 * The sidebar shows a live dot from the pane state map and the tab and pane names from the
 * config. These tests change the task and session lists around a task with an own session and a
 * background one, and check that nothing the sidebar reads has changed, and that the overview
 * model itself neither writes to the layout nor sends anything on its own.
 *
 * @see se.soderbjorn.lunamux.client.WindowStateRepository
 * @see AgentsOverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests that a task with an own and a background session changes nothing in the sidebar's inputs. */
class SidebarUnaffectedByTasksTest {

    private val windowState = WindowStateRepository()
    private val sent = mutableListOf<WindowCommand>()

    private fun summary(id: String, leaf: String, taskId: String?, background: Boolean) = AgentSessionSummary(
        id = id, agent = "claude", state = "working", stateSince = 1_000, title = "session $id", links = emptyList(),
        repo = "proj", branch = null, cwd = "/work/proj", cwdShown = "/work/proj", nowLine = null, question = null,
        leafId = leaf, taskId = taskId, background = background, lastActivityAt = 9_000,
    )

    /**
     * A task with an own session in the listed tab and a member in a background tab changes the
     * live dot (the pane state) of nobody, and leaves the layout, the tab names and the pane
     * states exactly as they were.
     */
    @Test
    fun aTaskWithABackgroundAndAnOwnSessionLeavesTheSidebarInputsAsTheyWere() {
        fun panes(vararg ids: String) = ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        val config = WindowConfig(
            tabs = listOf(
                TabConfig("tab-1", "one", panes = panes("leaf-lead")),
                TabConfig("tab-bg", "agents", panes = panes("leaf-bg"), isBackground = true),
            ),
            activeTabId = "tab-1",
        )
        windowState.updateConfig(config)
        windowState.updateStates(mapOf("s-leaf-lead" to "working", "s-leaf-bg" to "working"))
        val vm = AgentsOverviewBackingViewModel(windowState, clock = { 10_000 }) { sent += it }
        vm.open()
        val states = windowState.states.value
        val layout = windowState.config.value

        windowState.updateAgentSessions(
            listOf(summary("lead", "leaf-lead", taskId = null, background = false), summary("bg", "leaf-bg", taskId = "g1", background = true)),
            true,
        )
        windowState.updateAgentTasks(
            listOf(AgentTask(id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, members = emptyList(), leadRecordId = "lead")),
        )

        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id }, "the overview folds them into one row")
        assertEquals(states, windowState.states.value, "the live dots are the pane states, which no task touches")
        assertEquals(layout, windowState.config.value, "the tab and pane names are the layout, which no task touches")
        assertTrue(sent.isEmpty(), "the overview sends nothing until a person acts")
    }
}
