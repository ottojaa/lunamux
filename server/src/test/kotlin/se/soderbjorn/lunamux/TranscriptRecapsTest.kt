/**
 * Tests for [TranscriptRecaps]: which recap of a transcript a row describes itself with, and that a
 * file is read again only for what it gained.
 *
 * @see TranscriptRecaps
 */
package se.soderbjorn.lunamux

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptRecapsTest {
    private val dir = File.createTempFile("lunamux-transcript-recaps", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun recap(text: String) = """{"type":"system","subtype":"away_summary","content":"$text","timestamp":"2026-11-17T14:52:27.512Z"}"""
    private val prompt = """{"type":"user","message":{"role":"user","content":"go on"}}"""
    private val reply = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Done."}]}}"""

    private fun transcript(vararg lines: String): File =
        File(dir, "t${System.nanoTime()}.jsonl").apply { writeText(lines.joinToString("") { it + "\n" }) }

    @Test
    fun `the newest recap describes the conversation, as the overview words it`() {
        val file = transcript(prompt, recap("Old work. Next: nothing."), reply, recap("Goal: moving exports to Gotenberg. Run 1 is booting. Next: it pushes. (turn off recaps in /config)"), reply)
        val recaps = TranscriptRecaps()
        assertTrue(recaps.refresh(file.path))
        assertEquals("Moving exports to Gotenberg. Run 1 is booting.", recaps.known(file.path))
    }

    @Test
    fun `a grown file is read from where the last read stopped, and a line still being written waits`() {
        val file = transcript(prompt, recap("First state of the work."))
        val recaps = TranscriptRecaps()
        recaps.refresh(file.path)
        assertFalse(recaps.refresh(file.path), "an unchanged file is not read again")
        val half = recap("Second state of the work.")
        file.appendText(reply + "\n" + half.take(30))
        assertFalse(recaps.refresh(file.path))
        assertEquals("First state of the work.", recaps.known(file.path))
        file.appendText(half.drop(30) + "\n")
        assertTrue(recaps.refresh(file.path))
        assertEquals("Second state of the work.", recaps.known(file.path))
    }

    @Test
    fun `no recap, a gone file and a forgotten one say nothing`() {
        val recaps = TranscriptRecaps()
        val plain = transcript(prompt, reply)
        assertFalse(recaps.refresh(plain.path))
        assertNull(recaps.known(plain.path))
        val file = transcript(recap("Some work in hand."))
        recaps.refresh(file.path)
        recaps.keepOnly(emptySet())
        assertNull(recaps.known(file.path))
        recaps.refresh(file.path)
        file.delete()
        assertTrue(recaps.refresh(file.path))
        assertNull(recaps.known(file.path))
        assertNull(recaps.known(null))
    }
}
