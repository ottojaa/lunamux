/**
 * Checks that the tab list [OverviewBackingViewModel] builds for the phone and tablet overview
 * leaves out background tabs while it still shows a hidden tab that is the active one.
 *
 * The view model reads its layout from a [WindowSocket]; the test feeds it a minimal fake whose
 * layout it can replace, collects [OverviewBackingViewModel.run] on an unconfined dispatcher and
 * reads [OverviewBackingViewModel.stateFlow].
 *
 * @see OverviewBackingViewModel
 * @see se.soderbjorn.lunamux.listedTabs
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/** A socket that holds one layout and never sends anything. */
private class FixedSocket(layout: WindowConfig) : WindowSocket {
    override val config: StateFlow<WindowConfig?> = MutableStateFlow(layout)
    override val states: StateFlow<Map<String, String?>> = MutableStateFlow(emptyMap())
    override val envelopes: SharedFlow<WindowEnvelope> = MutableSharedFlow()
    override val connected: StateFlow<Boolean> = MutableStateFlow(true)
    override suspend fun awaitInitialConfig() {}
    override suspend fun awaitSessionReady() {}
    override suspend fun send(command: WindowCommand) {}
    override suspend fun close() {}
}

class OverviewBackingViewModelBackgroundTest {

    private fun tabsShown(layout: WindowConfig): List<String> = runBlocking {
        val vm = OverviewBackingViewModel(FixedSocket(layout))
        val job = launch(@OptIn(ExperimentalCoroutinesApi::class) Dispatchers.Unconfined) { vm.run() }
        val ids = vm.stateFlow.value.tabs.map { it.id }
        job.cancel()
        ids
    }

    private val tabs = listOf(
        TabConfig(id = "a", title = "Docs"),
        TabConfig(id = "b", title = "Agent work", isBackground = true),
        TabConfig(id = "c", title = "Scratch", isHidden = true),
    )

    @Test
    fun `the tab list leaves out a background tab and a hidden tab that is not active`() {
        assertEquals(listOf("a"), tabsShown(WindowConfig(tabs = tabs, activeTabId = "a")))
    }

    @Test
    fun `the tab list keeps a hidden tab while it is the active one but never a background tab`() {
        assertEquals(listOf("a", "c"), tabsShown(WindowConfig(tabs = tabs, activeTabId = "c")))
    }
}
