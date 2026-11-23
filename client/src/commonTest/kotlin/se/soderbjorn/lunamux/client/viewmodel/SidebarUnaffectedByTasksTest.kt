/**
 * Guards that the sidebar is drawn from the layout and the per-pane states alone: the tasks and
 * agent sessions the agents overview folds into one row per task never reach it.
 *
 * The test runs [OverviewBackingViewModel], the shared model that draws the tabs, their panes and
 * each pane's state from those two inputs, before and after an `Agents` frame arrives, and checks
 * that its output is the same. It also checks that the agents overview itself writes nothing to
 * the layout and sends nothing on its own.
 *
 * @see se.soderbjorn.lunamux.client.WindowStateRepository
 * @see OverviewBackingViewModel
 * @see AgentsOverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowSocket
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A socket that serves the layout and pane states of one [WindowStateRepository] and sends nothing. */
private class RepositorySocket(repository: WindowStateRepository) : WindowSocket {
    override val config: StateFlow<WindowConfig?> = repository.config
    override val states: StateFlow<Map<String, String?>> = repository.states
    override val envelopes: SharedFlow<WindowEnvelope> = MutableSharedFlow()
    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun awaitInitialConfig() {}
    override suspend fun awaitSessionReady() {}
    override suspend fun send(command: WindowCommand) {}
    override suspend fun close() {}
}

/** Tests that a task with an own and a background session changes nothing in the sidebar's inputs. */
class SidebarUnaffectedByTasksTest {
    private val windowState = WindowStateRepository(clock = { 10_000 })
    private val sent = mutableListOf<WindowCommand>()

    private fun summary(id: String, leaf: String, taskId: String?) = AgentSessionSummary(
        id = id, agent = "claude", shown = Shown.WORKING, since = 1_000, title = "session $id", repo = "proj",
        taskId = taskId, leafId = leaf, startedAt = 1_000,
    )

    @Test
    fun aTaskWithABackgroundAndAnOwnSessionLeavesTheSidebarInputsAsTheyWere() {
        fun panes(vararg ids: String) = ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(
            WindowConfig(
                tabs = listOf(
                    TabConfig("tab-1", "one", panes = panes("leaf-main")),
                    TabConfig("tab-bg", "agents", panes = panes("leaf-bg"), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
        windowState.updateStates(mapOf("s-leaf-main" to "working", "s-leaf-bg" to "working"))
        val vm = AgentsOverviewBackingViewModel(windowState, clock = { 10_000 }) { sent += it }
        vm.open()
        val states = windowState.states.value
        val layout = windowState.config.value

        val tabsModel = OverviewBackingViewModel(RepositorySocket(windowState))
        val scope = CoroutineScope(@OptIn(ExperimentalCoroutinesApi::class) Dispatchers.Unconfined)
        scope.launch { tabsModel.run() }
        val tabsBefore = tabsModel.stateFlow.value
        assertTrue(tabsBefore.tabs.any { it.id == "tab-1" }, "the model reads the layout")

        windowState.updateAgents(
            WindowEnvelope.Agents(
                listOf(summary("main", "leaf-main", null), summary("bg", "leaf-bg", "g1")),
                listOf(AgentTask(id = "g1", title = "Release", repo = "proj", main = "main", startedAt = 1_000, statusAt = 1_000)),
            ),
        )

        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id }.filter { it.startsWith("task:") }, "the overview folds the task into one row")
        assertEquals(tabsBefore, tabsModel.stateFlow.value, "the tabs, panes and their states are drawn from the layout alone")
        scope.cancel()
        assertEquals(states, windowState.states.value, "the live dots are the pane states, which no task touches")
        assertEquals(layout, windowState.config.value, "the tab and pane names are the layout, which no task touches")
        assertTrue(sent.all { it is WindowCommand.ReadAgentDetails }, "the overview only reads (it looks at the selected row) until a person acts")
    }
}
