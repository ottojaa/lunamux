/**
 * Tests for how a connected window's commands treat a background tab: a window never lists one, so
 * its close command is ignored, while the same command still closes a tab the person can see.
 *
 * Contains [BackgroundTabWindowCommandTest].
 *
 * @see handleWindowCommand
 * @see WindowCommand.CloseTab
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Sends `CloseTab` through [handleWindowCommand] against the real [WindowState], like the other
 * tests that open tabs, and closes every tab it opens.
 *
 * Run by `:server:test`.
 */
class BackgroundTabWindowCommandTest {
    private val dir = File.createTempFile("lunamux-bgtab", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val ctx = WindowConnectionContext(
        send = {},
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = SettingsRepository(File(dir, "test.db")),
        usageMonitor = ClaudeUsageMonitor(),
    )

    private fun tabIds() = WindowState.config.value.worlds.flatMap { it.tabs }.map { it.id }

    private fun closeFromWindow(tabId: String) = runBlocking {
        val command: WindowCommand = WindowCommand.CloseTab(tabId)
        handleWindowCommand(windowJson.encodeToString(command), ctx)
    }

    /** A window cannot close a background tab: only the agent that opened it does, through the tool. */
    @Test
    fun aWindowCannotCloseABackgroundTab() {
        val visible = WindowState.addTab()
        val bg = WindowState.addTab(background = true)
        try {
            closeFromWindow(bg)
            assertTrue(bg in tabIds(), "the background tab is still there")
            assertTrue(WindowState.isBackgroundTab(bg))
        } finally {
            WindowState.closeTab(bg); WindowState.closeTab(visible)
        }
    }

    /**
     * A background tab belongs to the agent that opened it, and that agent died with the old process,
     * so the layout is rebuilt without it. The rebuild runs once at start-up on the process-wide
     * state, so it cannot be repeated in a test: this holds the rule in the source, and the
     * restart step of the rehearsal on a copy of real data shows it on a running server.
     */
    @Test
    fun theLayoutIsRebuiltWithoutBackgroundTabsAfterARestart() {
        val source = File("src/main/kotlin/se/soderbjorn/lunamux/WindowState.kt").readText()
        val rebuild = source.substringAfter("private fun rehydrate(").substringBefore("val tabIdSet")
        assertTrue(
            Regex("""if \(tab\.isBackground\) \{.*?\bnull\s*\} else rebuildTab\(tab\)""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(rebuild),
            "rehydrate no longer drops background tabs",
        )
    }

    /** The same command still closes a tab the person can see, so the guard is not a blanket refusal. */
    @Test
    fun aWindowStillClosesAListedTab() {
        val keep = WindowState.addTab()
        val doomed = WindowState.addTab()
        try {
            closeFromWindow(doomed)
            assertFalse(doomed in tabIds())
            assertTrue(keep in tabIds())
        } finally {
            WindowState.closeTab(doomed); WindowState.closeTab(keep)
        }
    }
}
