/**
 * Tests for two facts the agents tracker reads from outside itself: the background shells a
 * conversation left running (from the process tree, or from the footer of its screen) and a
 * folder's git facts. The tracker is run over a throwaway database with a fake clock; the folder
 * facts are read from a real repository made in a temporary directory.
 *
 * @see AgentSessionTracker
 * @see footerShellsOf
 * @see GitCatalog.folderWork
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AgentShellsAndFolderTest {
    private val dbDir = File.createTempFile("lunamux-agent-shells", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun pane() = PaneSnapshot("s1", leafId = "leaf-s1", cwd = dbDir.path, programTitle = null)

    private fun event(name: String, vararg more: Pair<String, String>): JsonObject = buildJsonObject {
        put("hook_event_name", name)
        put("session_id", "a-1")
        more.forEach { (k, v) -> put(k, v) }
    }

    @Test
    fun `a finished conversation with a background shell under its claude reads as waiting, and done when none is left`() {
        var shells = BackgroundShells(2, 500L)
        val t = AgentSessionTracker(
            SettingsRepository(File(dbDir, "a.db")), folderOf = { null }, clock = { 1_000L },
            newId = { "r1" }, shellsUnder = { shells },
        ).also { it.start() }
        t.onHook(pane(), event("UserPromptSubmit", "prompt" to "go"), pid = 100)
        t.onHook(pane(), event("Stop"), pid = 100)
        t.onPoll(listOf(pane()))
        assertEquals(Shown.WAITING, t.summaries.value.single().shown)
        shells = BackgroundShells.NONE
        t.onPoll(listOf(pane()))
        assertEquals(Shown.DONE, t.summaries.value.single().shown)
    }

    @Test
    fun `the footer of a screen counts the shells it names`() {
        assertEquals(1, footerShellsOf("work\n\n  1 shell · esc to interrupt"))
        assertEquals(2, footerShellsOf("work\n  2 shells"))
        assertEquals(3, footerShellsOf("work\n  3 background shells"))
        assertEquals(0, footerShellsOf("1 shell\nlots\nof\nlater\nlines\nbelow\nit\nnow"))
        assertEquals(0, footerShellsOf(null))
    }

    @Test
    fun `folder work names the repository by its main folder, its branch and what is changed`() {
        val repo = File(dbDir, "my-repo").apply { mkdirs() }
        fun git(vararg args: String) = ProcessBuilder(listOf("git", "-C", repo.path) + args)
            .redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() }
        git("init", "-b", "topic")
        git("config", "user.email", "t@example.com")
        git("config", "user.name", "t")
        File(repo, "a.txt").writeText("one\n")
        git("add", ".")
        git("commit", "-m", "first")
        File(repo, "a.txt").writeText("one\ntwo\n")
        File(repo, "new.txt").writeText("x")
        val work = assertNotNull(GitCatalog.folderWork(repo.path, maxAgeMs = 0))
        assertEquals("my-repo", work.repo)
        assertEquals("topic", work.branch)
        assertEquals(1, work.changed)
        assertEquals(1, work.untracked)
        assertEquals(1, work.added)
    }
}
