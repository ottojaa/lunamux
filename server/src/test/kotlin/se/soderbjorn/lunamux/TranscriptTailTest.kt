/**
 * Tests for [TranscriptTail]: when the end of a Claude Code transcript counts as the person having
 * interrupted the turn, and when it does not.
 *
 * @see TranscriptTail
 */
package se.soderbjorn.lunamux

import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTailTest {
    private val dir = File.createTempFile("lunamux-transcript-tail", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun transcript(vararg lines: String): String =
        File(dir, "t${System.nanoTime()}.jsonl").apply { writeText(lines.joinToString("\n") + "\n") }.path

    private val at = "2026-11-06T09:34:05.065Z"
    private val atMillis = Instant.parse(at).toEpochMilli()
    private val prompt = """{"type":"user","timestamp":"2026-11-06T09:34:00.000Z","message":{"role":"user","content":"do it"}}"""
    private val toolResult = """{"type":"user","timestamp":"2026-11-06T09:34:05.063Z","message":{"role":"user","content":[{"type":"tool_result","is_error":true,"content":"rejected"}]}}"""
    private val marker = """{"type":"user","timestamp":"$at","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user for tool use]"}]}}"""
    private val bookkeeping = arrayOf("""{"type":"system","subtype":"turn_duration"}""", """{"type":"last-prompt"}""", """{"type":"cost-state"}""")

    @Test
    fun `an interrupt line that ends the conversation is found whatever bookkeeping follows it`() {
        assertEquals(atMillis, TranscriptTail.interruptedAt(transcript(prompt, toolResult, marker, *bookkeeping)))
    }

    @Test
    fun `the plain interrupt of a turn that was writing is found, as text or as a list`() {
        val asText = """{"type":"user","timestamp":"$at","message":{"role":"user","content":"[Request interrupted by user]"}}"""
        assertEquals(atMillis, TranscriptTail.interruptedAt(transcript(prompt, asText)))
        val asList = """{"type":"user","timestamp":"$at","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}"""
        assertEquals(atMillis, TranscriptTail.interruptedAt(transcript(prompt, asList)))
    }

    @Test
    fun `an interrupt that a later line has followed is history`() {
        val reply = """{"type":"assistant","timestamp":"2026-11-06T09:35:00.000Z","message":{"role":"assistant","content":[{"type":"text","text":"ok"}]}}"""
        assertNull(TranscriptTail.interruptedAt(transcript(prompt, marker, reply)))
        assertNull(TranscriptTail.interruptedAt(transcript(prompt, marker, prompt)))
    }

    @Test
    fun `a tool that failed on its own is not an interrupt`() {
        assertNull(TranscriptTail.interruptedAt(transcript(prompt, toolResult, *bookkeeping)))
    }

    @Test
    fun `a missing file, an empty one and a last line that cannot be read settle nothing`() {
        assertNull(TranscriptTail.interruptedAt(File(dir, "nowhere.jsonl").path))
        assertNull(TranscriptTail.interruptedAt(transcript()))
        assertNull(TranscriptTail.interruptedAt(transcript(marker, """{"type":"assistant","message":{"content":[{"type":"te""")))
    }

    @Test
    fun `a last line larger than the window is not mistaken for the interrupt before it`() {
        val big = """{"type":"user","timestamp":"2026-11-06T09:36:00.000Z","message":{"role":"user","content":"${"x".repeat(300_000)}"}}"""
        assertNull(TranscriptTail.interruptedAt(transcript(prompt, marker, big)))
    }

}
