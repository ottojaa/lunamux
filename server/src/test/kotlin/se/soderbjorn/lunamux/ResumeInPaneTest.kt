/**
 * Tests for finding the pane a closed conversation can be resumed in ([paneToResumeIn]): the pane is
 * found by its layout leaf, which survives a server restart, never by the terminal session id the
 * record stored, which does not; and it is only offered while its shell sits in the folder the
 * conversation is picked up from with nothing running under it, and its tab is one the person can
 * see; and that [typeInPane] shows the tab and types the line into that pane and no other.
 *
 * The cases use a real pane (a real shell) and type nothing into it that could start an agent.
 *
 * @see paneToResumeIn
 * @see typeInPane
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeInPaneTest {
    private val folder = File.createTempFile("lunamux-pane", "").apply { delete(); mkdirs(); deleteOnExit() }.canonicalFile
    private val uuid = "b7714ac5-1111-2222-3333-444455556666"

    /** A record of a closed conversation that ran in the pane with layout leaf [leafId], whose session id is out of date. */
    private fun recordIn(leafId: String, cwd: File = folder) = AgentSessionRecord(
        id = "r1", agent = "claude", agentSessionId = uuid, leafId = leafId, terminalSessionId = "s0-from-before-the-restart",
        cwd = cwd.path, state = "ended", stateSince = 1, createdAt = 1, endedAt = 1,
    )

    /** Wait until [condition] holds; the shell's folder and children are read by polling. */
    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        repeat(150) { if (condition()) return@runBlocking; delay(100) }
        error("timed out waiting for $what")
    }

    /** Run [block] with a new tab whose shell has started in [folder] and sits at its prompt. */
    private fun withPane(block: (leafId: String, session: TermSession) -> Unit) {
        val tab = WindowState.addTab(initialCwd = folder.path)
        try {
            val leaf = WindowState.config.value.worlds.flatMap { it.tabs }.first { it.id == tab }.panes.first().leaf
            val session = TerminalSessions.get(leaf.sessionId)!!
            waitFor("the shell to report its folder") { session.cwd.value?.let { File(it).canonicalFile } == folder }
            waitFor("the shell to be idle") { session.isIdleShell() }
            block(leaf.id, session)
        } finally {
            WindowState.closeTab(tab)
        }
    }

    /** The pane is found by its leaf although the stored session id no longer exists, and answers with the live session. */
    @Test
    fun theLeafFindsThePaneWhenTheStoredSessionIdIsStale() = withPane { leafId, session ->
        val found = paneToResumeIn(recordIn(leafId))
        assertEquals(WindowState.sessionIdOfLeaf(leafId), found)
        assertEquals(true, found != null && TerminalSessions.get(found) === session)
    }

    /** A pane whose shell has moved to another folder is still offered; a pane that is gone is not. */
    @Test
    fun aPaneInAnotherFolderIsOfferedAndNoPaneIsNot() = withPane { leafId, _ ->
        assertEquals(WindowState.sessionIdOfLeaf(leafId), paneToResumeIn(recordIn(leafId, cwd = File(folder, "elsewhere"))))
        assertNull(paneToResumeIn(recordIn("no-such-leaf")))
    }

    /** A folder is typed as one shell word, whatever it holds. */
    @Test
    fun aFolderIsQuotedForTheShell() {
        assertEquals("'/a b/c'", shellQuoted("/a b/c"))
        assertEquals("'/it'\\''s'", shellQuoted("/it's"))
    }

    /** A pane with a program running under its shell is not offered until that program ends. */
    @Test
    fun aBusyPaneIsNotOffered() = withPane { leafId, session ->
        session.write("sleep 4\r".toByteArray())
        waitFor("the shell to be busy") { !session.isIdleShell() }
        assertNull(paneToResumeIn(recordIn(leafId)))
        waitFor("the shell to be idle again") { session.isIdleShell() }
        assertEquals(WindowState.sessionIdOfLeaf(leafId), paneToResumeIn(recordIn(leafId)))
    }

    /** A pane in an unlisted background tab is not offered: it could not be shown, so the agent would run out of sight. */
    @Test
    fun aPaneInAHiddenBackgroundTabIsNotOffered() {
        val tab = WindowState.addTab(initialCwd = folder.path, background = true)
        try {
            val leaf = WindowState.config.value.worlds.flatMap { it.tabs }.first { it.id == tab }.panes.first().leaf
            val session = TerminalSessions.get(leaf.sessionId)!!
            waitFor("the shell to be idle in the folder") { session.cwd.value?.let { File(it).canonicalFile } == folder && session.isIdleShell() }
            assertNull(paneToResumeIn(recordIn(leaf.id)))
        } finally {
            WindowState.closeTab(tab)
        }
    }

    /** The line lands in the named pane (a harmless `touch` stands in for the agent) and its tab becomes the active one. */
    @Test
    fun theLineIsTypedIntoThePaneAndItsTabIsShown() = withPane { leafId, _ ->
        val paneTab = WindowState.config.value.worlds.flatMap { it.tabs }.first { t -> t.panes.any { it.leaf.id == leafId } }.id
        val other = WindowState.addTab(initialCwd = folder.path)
        try {
            assertTrue(WindowState.config.value.effectiveActiveTabId == other)
            val marker = File(folder, "typed-here")
            assertTrue(typeInPane(WindowState.sessionIdOfLeaf(leafId)!!, "touch ${marker.path}"))
            waitFor("the line to run in the pane") { marker.exists() }
            assertEquals(paneTab, WindowState.config.value.effectiveActiveTabId)
        } finally {
            WindowState.closeTab(other)
        }
    }
}
