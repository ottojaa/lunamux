/**
 * Tests for the pane id numbering helper of [WindowState].
 *
 * The node id counter must start above every pane id the server remembers, whether the pane
 * is in the layout or only on an agent session record, so that a new pane is never handed
 * the id of a pane a task remembers. [WindowState.nodeIdNumber] is the one place the number
 * is read out of an id, for both the rehydrated layout and the records.
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowStateNodeIdTest {
    @Test
    fun `the number is read from ids with and without the database nonce`() {
        assertEquals(7L, WindowState.nodeIdNumber("n7-ab12cd"))
        assertEquals(7L, WindowState.nodeIdNumber("n7"))
        assertEquals(120L, WindowState.nodeIdNumber("n120-x"))
    }

    @Test
    fun `an id of another shape has no number`() {
        assertNull(WindowState.nodeIdNumber("leaf-a"))
        assertNull(WindowState.nodeIdNumber(""))
    }
}
