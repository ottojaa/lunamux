/**
 * Tests for [Osc52Filter] and for [SessionGrid]'s use of it.
 *
 * The bug under test: the vendored emulator gives up on an OSC string longer than 8192
 * characters and prints the rest as text, so one large clipboard copy (OSC 52) from a program
 * such as Claude Code left a wall of base64 in the grid, in every attach redraw and in every
 * persisted scrollback blob. The grid tests use payloads past that limit, so they fail without
 * the filter.
 *
 * @see Osc52Filter
 * @see SessionGrid.feed
 */
package se.soderbjorn.lunamux.pty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Osc52FilterTest {

    private val esc = "\u001b"
    private val bel = "\u0007"
    private val st = "$esc\\"

    /** A payload longer than the emulator's OSC limit, with a marker at the end that must not show. */
    private val wall = "QUJD".repeat(2500) + "TAILMARK"

    /** Run [chunks] through one filter, in order, and join what it emits. */
    private fun run(vararg chunks: String): String {
        val filter = Osc52Filter()
        val out = StringBuilder()
        for (c in chunks) {
            val b = c.toByteArray(Charsets.ISO_8859_1)
            filter.filter(b, b.size) { bytes, n -> out.append(String(bytes, 0, n, Charsets.ISO_8859_1)) }
        }
        return out.toString()
    }

    private fun SessionGrid.feedText(s: String, chunk: Int = Int.MAX_VALUE) {
        val b = s.toByteArray(Charsets.UTF_8)
        var i = 0
        while (i < b.size) {
            val n = minOf(chunk, b.size - i)
            feed(b.copyOfRange(i, i + n), n)
            i += n
        }
    }

    @Test
    fun `a sequence ended by BEL or ST is removed and its neighbours are kept`() {
        assertEquals("ab", run("a${esc}]52;c;QUJD${bel}b"))
        assertEquals("ab", run("a${esc}]52;c;QUJD${st}b"))
        assertEquals("ab", run("a${esc}]52;;QUJD${bel}b"))
        assertEquals("ab", run("a${esc}]52;c;${bel}b"))
    }

    @Test
    fun `any other text and sequence passes through unchanged`() {
        val text = "plain\r\n${esc}[1mbold${esc}[0m ${esc}]0;title${bel}${esc}]7;file:///tmp${st}${esc}]520;x${bel}${esc}]5;y${bel}${esc}x"
        assertEquals(text, run(text))
        assertEquals(text, run(*text.map { it.toString() }.toTypedArray()), "one byte at a time")
    }

    @Test
    fun `a sequence split at every possible point is still removed`() {
        val whole = "pre${esc}]52;c;QUJDREVG$st${"post"}"
        for (cut in 1 until whole.length) {
            assertEquals("prepost", run(whole.substring(0, cut), whole.substring(cut)), "cut at $cut")
        }
        assertEquals("prepost", run(*whole.map { it.toString() }.toTypedArray()))
        val bell = "pre${esc}]52;c;QUJDREVG${bel}post"
        for (cut in 1 until bell.length) {
            assertEquals("prepost", run(bell.substring(0, cut), bell.substring(cut)), "bell, cut at $cut")
        }
    }

    @Test
    fun `an escape inside the payload that is not a terminator ends the sequence without eating what follows`() {
        assertEquals("${esc}[31mred", run("${esc}]52;c;QUJD${esc}[31mred"))
        assertEquals("x", run("${esc}]52;c;QUJD\u0018x"))
    }

    @Test
    fun `a sequence that never ends does not hide the rest of the stream forever`() {
        val out = run("${esc}]52;c;" + "A".repeat((1 shl 20) + 10) + "tail")
        assertTrue(out.endsWith("tail"), "output after the cap comes through")
        assertTrue(out.length < 100, "the payload up to the cap is gone")
    }

    @Test
    fun `an overlong clipboard copy leaves nothing in the grid`() {
        for (terminator in listOf(bel, st)) {
            val grid = SessionGrid(80, 24)
            grid.feedText("before\r\n${esc}]52;c;$wall${terminator}after\r\n", chunk = 4096)
            val t = grid.transcriptText()
            assertFalse(t.contains("QUJD"), "no base64 in the transcript")
            assertFalse(t.contains("TAILMARK"), "the tail of the payload does not print")
            assertTrue(t.contains("before") && t.contains("after"), "text around it is kept: $t")
        }
    }

    @Test
    fun `an overlong clipboard copy does not survive persist and restore`() {
        val grid = SessionGrid(80, 24)
        grid.feedText("before\r\n${esc}]52;c;$wall${bel}after\r\n", chunk = 1000)
        val blob = grid.synthesizeForPersist()

        // The stored form is clean, and so is a restore of a blob that still holds the raw sequence
        // (what an older server wrote) fed in one piece or in pieces, followed by the restore marker.
        val raw = "before\r\n${esc}]52;c;$wall${st}after\r\n".toByteArray(Charsets.UTF_8)
        val restored = listOf(blob, raw).map { source ->
            SessionGrid(80, 24).also {
                it.feed(source, source.size)
                it.feed(RestoreMarker.MODE_RESET, RestoreMarker.MODE_RESET.size)
            }
        }
        for (g in restored) {
            val t = g.transcriptText()
            assertFalse(t.contains("QUJD") || t.contains("TAILMARK"), "restored grid shows no payload: ${t.take(120)}")
            assertTrue(t.contains("before") && t.contains("after"))
            assertFalse(String(g.synthesizeForPersist(), Charsets.UTF_8).contains("TAILMARK"))
        }
    }

    @Test
    fun `a restored blob that ends inside a clipboard sequence does not swallow the restore marker or the new shell`() {
        val unterminated = "kept\r\n${esc}]52;c;QUJDREVG".toByteArray(Charsets.UTF_8)
        val grid = SessionGrid(80, 24)
        grid.feed(unterminated, unterminated.size)
        grid.feed(RestoreMarker.MODE_RESET, RestoreMarker.MODE_RESET.size)
        grid.feedText("\r\nnew prompt")
        val t = grid.transcriptText()
        assertTrue(t.contains("kept") && t.contains("new prompt"), t)
        assertFalse(t.contains("?1000"), "the marker is consumed as a sequence, not printed")
    }
}
