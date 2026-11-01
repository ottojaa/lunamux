/**
 * A pane restored after a server restart must not read as a running agent.
 *
 * The persisted scrollback of a pane whose agent was mid-turn when the server stopped still
 * shows the agent's working footer. It is replayed into the pane's canonical grid so the user
 * can scroll back through it, but the state detector reads a different, live-only screen that
 * the replay never reaches. These tests pin that: with a real session started from such a blob,
 * the detector reports nothing, so no agent row can be seeded for a pane that has no agent
 * process behind it.
 *
 * @see TerminalSession.detectState
 * @see TerminalSessions.agentOf
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RestoredPaneDetectionTest {

    /** The text of a Claude pane in the middle of a turn, and of one waiting on an approval. */
    private val workingScreen = "Generating...                esc to interrupt\r\n"
    private val waitingScreen = "Would you like to proceed?\r\n\r\n❯ 1. Yes\r\n  2. No\r\n"

    @Test
    fun `a pane restored from an agent mid-turn reports no agent state`() = runBlocking {
        for (screen in listOf(workingScreen, waitingScreen)) {
            val blob = screen.toByteArray(Charsets.UTF_8)
            val session = TerminalSession.create(initialScrollback = blob, initialCols = 100, initialRows = 30)
            try {
                // The restored text is in the scrollback a user can read…
                assertTrue(session.transcriptText().contains(screen.lines().first().trim()), "restore replayed the blob")
                // …and stays invisible to the detector, before and after the new shell draws its prompt.
                repeat(8) {
                    assertNull(session.detectState(), "no agent state from restored scrollback")
                    delay(100)
                }
            } finally {
                session.shutdown()
            }
        }
    }
}
