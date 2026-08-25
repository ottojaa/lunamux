/**
 * The contract for the split resync: [GridSerializer.serializeSplit] must be able to serve both
 * kinds of client from one serialization.
 *
 * Two things have to hold, and neither is obvious from reading the code.
 *
 *  1. **A client that cannot apply a backfill must see exactly what it saw before.**
 *     [GridSerializer.joinForLegacy] splices the halves back together by byte offset, so it is
 *     asserted byte-identical to plain [GridSerializer.serialize] — not merely equivalent.
 *     If [GridSerializer.emitHistory] ever carries state between lines, or the `RIS` + `ED3`
 *     prefix changes length, this is the test that catches it.
 *  2. **The screen-first half must stand on its own.** It is what the user looks at while the
 *     backfill is still arriving, so it has to reconstruct the live screen exactly, with only
 *     the scrollback short.
 */
package se.soderbjorn.lunamux.pty

import com.termux.terminal.TerminalEmulator
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplitResyncTest {

    private val esc = "\u001b"

    private fun SessionGrid.feed(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    /** A grid with [lines] committed to history plus a live screen, at 40x8. */
    private fun gridWithHistory(lines: Int): SessionGrid {
        val g = SessionGrid(40, 8)
        for (i in 0 until lines) {
            // Alternate styles so the SGR emission is exercised between lines — the thing
            // that would break the concatenation if it were stateful.
            val sgr = if (i % 3 == 0) "$esc[1;31m" else if (i % 3 == 1) "$esc[32m" else "$esc[0m"
            g.feed("${sgr}history line $i\r\n")
        }
        g.feed("${esc}[0mlive prompt \$ ")
        return g
    }

    private fun split(g: SessionGrid, tail: Int) = g.read { e ->
        GridSerializer.serializeSplit(e, g.historyLines(), g.pendingHistoryLine(), tailLines = tail)
    }

    @Test
    fun `rejoined split is byte-identical to the whole redraw`() {
        for (tail in listOf(1, 5, 20, 200)) {
            val g = gridWithHistory(50)
            val whole = g.synthesizeRedraw()
            val rejoined = GridSerializer.joinForLegacy(split(g, tail))
            assertContentEquals(
                whole,
                rejoined,
                "tail=$tail: a client that cannot apply a backfill must receive the identical redraw",
            )
        }
    }

    @Test
    fun `no history at all still round-trips through the split`() {
        val g = SessionGrid(40, 8)
        g.feed("just a prompt \$ ")
        val s = split(g, 200)
        assertTrue(s.backfill.isEmpty(), "nothing to backfill when there is no history")
        assertContentEquals(g.synthesizeRedraw(), GridSerializer.joinForLegacy(s))
    }

    @Test
    fun `history shorter than the tail leaves the backfill empty`() {
        val g = gridWithHistory(10)
        val s = split(g, 200)
        assertTrue(s.backfill.isEmpty(), "10 lines all fit in a 200-line tail")
        assertContentEquals(g.synthesizeRedraw(), GridSerializer.joinForLegacy(s))
    }

    @Test
    fun `the screen-first half reconstructs the live screen on its own`() {
        val g = gridWithHistory(50)
        val s = split(g, 5)
        // Feed only the first half into a fresh grid of the same width.
        val fresh = SessionGrid(40, 8)
        fresh.feed(s.screenFirst, s.screenFirst.size)
        val expected = g.read { e -> screenText(e) }
        val actual = fresh.read { e -> screenText(e) }
        assertEquals(expected, actual, "the half the user waits for must be complete by itself")
    }

    @Test
    fun `the screen-first half is dramatically smaller than the whole`() {
        val g = gridWithHistory(2_000)
        val s = split(g, 200)
        assertTrue(
            s.screenFirst.size * 4 < s.backfill.size,
            "the point of the split is that the first half is small: " +
                "screenFirst=${s.screenFirst.size} backfill=${s.backfill.size}",
        )
    }

    /** The visible screen as plain text, one row per line, for comparing two grids. */
    private fun screenText(e: TerminalEmulator): String =
        (0 until e.mRows).joinToString("\n") { y ->
            e.screen.getSelectedText(0, y, e.mColumns - 1, y).trimEnd()
        }
}
