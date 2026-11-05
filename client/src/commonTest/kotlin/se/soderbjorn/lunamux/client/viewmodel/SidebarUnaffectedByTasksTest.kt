/**
 * Guards that the sidebar is drawn from the layout and the per-pane states alone: the tasks and
 * agent sessions the agents overview folds into one row per task never reach it.
 *
 * The sidebar shows a live dot from the pane state map and the tab and pane names from the
 * config, and nothing in the web client's sidebar reads the agent session or task lists. The test
 * runs [OverviewBackingViewModel], the shared model that draws the tabs, their panes and each
 * pane's state from those same two inputs, before and after the task and session lists change
 * around a task with an own session and a background one, and checks that its output is the same;
 * it also checks that the agents overview itself neither writes to the layout nor sends anything
 * on its own.
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
                TabConfig("tab-1", "one", panes = panes("leaf-main")),
                TabConfig("tab-bg", "agents", panes = panes("leaf-bg"), isBackground = true),
            ),
            activeTabId = "tab-1",
        )
        windowState.updateConfig(config)
        windowState.updateStates(mapOf("s-leaf-main" to "working", "s-leaf-bg" to "working"))
        val vm = AgentsOverviewBackingViewModel(windowState, clock = { 10_000 }) { sent += it }
        vm.open()
        val states = windowState.states.value
        val layout = windowState.config.value

        // The model that draws tabs, panes and pane states from the layout and the pane states.
        val tabsModel = OverviewBackingViewModel(RepositorySocket(windowState))
        val scope = CoroutineScope(@OptIn(ExperimentalCoroutinesApi::class) Dispatchers.Unconfined)
        scope.launch { tabsModel.run() }
        val tabsBefore = tabsModel.stateFlow.value
        assertEquals(listOf("tab-1"), tabsBefore.tabs.map { it.id }, "the model reads the layout (a background tab is not listed)")

        windowState.updateAgentSessions(
            listOf(summary("main", "leaf-main", taskId = null, background = false), summary("bg", "leaf-bg", taskId = "g1", background = true)),
            true,
        )
        windowState.updateAgentTasks(
            listOf(AgentTask(id = "g1", title = "Release", repo = "proj", source = "agents", hasOpen = false, members = emptyList(), mainConversation = "main")),
        )

        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id }, "the overview folds them into one row")
        assertEquals(tabsBefore, tabsModel.stateFlow.value, "the tabs, panes and their states are drawn from the layout alone")
        scope.cancel()
        assertEquals(states, windowState.states.value, "the live dots are the pane states, which no task touches")
        assertEquals(layout, windowState.config.value, "the tab and pane names are the layout, which no task touches")
        assertTrue(sent.isEmpty(), "the overview sends nothing until a person acts")
    }
}
