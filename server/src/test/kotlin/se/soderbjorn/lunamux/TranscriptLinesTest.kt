/**
 * Tests for [TranscriptLines] and [TranscriptFollower]: the one classifier that tells a transcript's
 * lines apart, the search text and story read from a whole file, and the follower that reads only
 * what a growing file gained.
 *
 * @see TranscriptLines
 * @see TranscriptFollower
 */
package se.soderbjorn.lunamux

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptLinesTest {
    private val dir = File.createTempFile("lunamux-transcript-lines", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun recap(text: String) = """{"type":"system","subtype":"away_summary","content":"$text","timestamp":"2026-11-17T14:52:27.512Z"}"""
    private fun user(text: String, extra: String = "") = """{"type":"user",$extra"message":{"role":"user","content":"$text"},"timestamp":"2026-11-17T14:50:00.000Z"}"""
    private val reply = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Done."}]},"timestamp":"2026-11-17T14:51:00.000Z"}"""
    private val interrupt = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]},"timestamp":"2026-11-17T14:52:00.000Z"}"""

    private fun transcript(vararg lines: String): File =
        File(dir, "t${System.nanoTime()}.jsonl").apply { writeText(lines.joinToString("") { it + "\n" }) }

    private fun kind(raw: String) = TranscriptLines.classify(raw)?.kind

    @Test
    fun `lines are told apart once, in one place`() {
        assertEquals(LineKind.PROMPT, kind(user("fix it")))
        assertEquals(LineKind.REPLY, kind(reply))
        assertEquals(LineKind.INTERRUPT, kind(interrupt))
        assertEquals(LineKind.RECAP, kind(recap("Work in hand.")))
        assertEquals(LineKind.OTHER, kind(user("fix it", extra = "\"isSidechain\":true,")), "a helper agent's line is not the conversation")
        assertEquals(LineKind.OTHER, kind(user("x", extra = "\"isMeta\":true,")))
        assertEquals(LineKind.ACTIVITY, kind(user("<command-name>/clear</command-name>")), "a text starting with < is activity, not a prompt")
        assertNull(TranscriptLines.classify("not json"))
    }

    @Test
    fun `the whole-file read gives the text, the first prompt and the cwd`() {
        val file = transcript(user("look for the walrus", extra = "\"cwd\":\"/w/app\",\"sessionId\":\"s-1\","), reply)
        val info = TranscriptLines.read(file)!!
        assertTrue("look for the walrus" in info.text && "Done." in info.text)
        assertEquals("look for the walrus", info.firstPrompt)
        assertEquals("/w/app", info.cwd)
        assertNull(TranscriptLines.read(File(dir, "missing.jsonl")))
    }

    @Test
    fun `the follower keeps the newest recap and whether the interrupt is last`() {
        val file = transcript(user("go"), recap("Old work."), reply, recap("Newest state of the work."), interrupt)
        val f = TranscriptFollower(file.path)
        assertTrue(f.refresh())
        assertEquals("Newest state of the work.", f.recap)
        assertTrue(f.interruptedAt != null)
        assertFalse(f.refresh(), "an unchanged file is not read again")

        file.appendText(user("go on") + "\n")
        assertTrue(f.refresh())
        assertNull(f.interruptedAt, "a prompt after the interrupt makes it old news")
    }

    @Test
    fun `a line still being written waits for the next look and a shrunk file is read afresh`() {
        val file = transcript(user("go"), recap("First state of the work."))
        val f = TranscriptFollower(file.path)
        f.refresh()
        val half = recap("Second state of the work.")
        file.appendText(reply + "\n" + half.take(30))
        f.refresh()
        assertEquals("First state of the work.", f.recap)
        file.appendText(half.drop(30) + "\n")
        assertTrue(f.refresh())
        assertEquals("Second state of the work.", f.recap)

        file.writeText(user("go") + "\n")
        f.refresh()
        assertNull(f.recap, "the file shrank, so what was known is dropped")
    }

    @Test
    fun `a gone file says nothing`() {
        val file = transcript(recap("Some work in hand."))
        val f = TranscriptFollower(file.path)
        f.refresh()
        file.delete()
        assertTrue(f.refresh())
        assertNull(f.recap)
    }
}
