/**
 * Tests that an agent session's summary carries its folder the way a person reads it: the home
 * folder written `~`, a folder under it as `~/…`, any other folder untouched and an unknown folder
 * as null. The overview's folder chip draws this text as it is.
 *
 * @see toSummary
 * @see prettifyPath
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentSessionCwdShownTest {
    private fun shown(cwd: String?) = AgentSessionRecord(id = "r", agent = "claude", state = "idle", cwd = cwd).toSummary().cwdShown

    @Test
    fun `the home folder is shown as a tilde and a folder under it keeps the rest of its path`() {
        val home = System.getProperty("user.home")
        assertEquals("~", shown(home))
        assertEquals("~/code/lunamux", shown("$home/code/lunamux"))
    }

    @Test
    fun `a folder outside home is shown as it is and no folder is shown as null`() {
        assertEquals("/srv/work", shown("/srv/work"))
        assertNull(shown(null))
    }
}
