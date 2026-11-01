/**
 * Tests for [AgentTranscriptText]: that only the words of a Claude Code
 * conversation are kept (not tool calls, tool results or sub-agent chatter),
 * that the session facts are read, and that the text is capped from the end.
 *
 * @see AgentTranscriptText
 */
package se.soderbjorn.lunamux

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTranscriptTextTest {
    private fun file(vararg lines: String): File =
        File.createTempFile("transcript", ".jsonl").apply { deleteOnExit(); writeText(lines.joinToString("\n")) }

    @Test
    fun `text parts of user and assistant lines are kept and the rest is dropped`() {
        val f = file(
            """{"type":"user","sessionId":"s-1","cwd":"/w/repo","gitBranch":"main","entrypoint":"cli","timestamp":"2026-10-01T10:00:00.000Z","message":{"role":"user","content":"find demandbase"}}""",
            """{"type":"assistant","timestamp":"2026-10-01T10:00:05.000Z","message":{"role":"assistant","content":[{"type":"thinking","thinking":"secret thoughts"},{"type":"text","text":"Looking now"},{"type":"tool_use","name":"Bash","input":{"command":"grep SECRETCOMMAND"}}]}}""",
            """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"x","content":"TOOLOUTPUT"}]}}""",
            """{"type":"assistant","isSidechain":true,"message":{"role":"assistant","content":[{"type":"text","text":"SIDECHAIN words"}]}}""",
            """{"type":"user","isMeta":true,"message":{"role":"user","content":"META words"}}""",
            "not json at all",
            """{"type":"ai-title","aiTitle":"Find the demandbase plan"}""",
            """{"type":"ai-title","aiTitle":"Final title"}""",
            """{"type":"user","timestamp":"2026-10-01T11:00:00.000Z","message":{"role":"user","content":"thanks"}}""",
        )
        val info = assertNotNull(AgentTranscriptText.read(f))
        assertEquals("find demandbase\nLooking now\nthanks", info.text)
        assertFalse("SECRET" in info.text || "TOOLOUTPUT" in info.text || "SIDECHAIN" in info.text || "META" in info.text)
        assertEquals("s-1", info.sessionId)
        assertEquals("/w/repo", info.cwd)
        assertEquals("main", info.gitBranch)
        assertEquals("cli", info.entrypoint)
        assertEquals("Final title", info.title)
        assertEquals("find demandbase", info.firstPrompt)
        assertEquals(java.time.Instant.parse("2026-10-01T10:00:00Z").toEpochMilli(), info.startedAt)
        assertEquals(java.time.Instant.parse("2026-10-01T11:00:00Z").toEpochMilli(), info.endedAt)
    }

    @Test
    fun `the first prompt skips command wrappers`() {
        val f = file(
            """{"type":"user","message":{"role":"user","content":"<command-name>/clear</command-name>"}}""",
            """{"type":"user","message":{"role":"user","content":"the real question"}}""",
        )
        assertEquals("the real question", AgentTranscriptText.read(f)?.firstPrompt)
    }

    @Test
    fun `text over the cap keeps the end of the conversation`() {
        val chunk = "x".repeat(100_000)
        val f = file(
            *(1..6).map { i ->
                """{"type":"user","message":{"role":"user","content":"${i}$chunk"}}"""
            }.toTypedArray(),
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"THE END"}]}}""",
        )
        val text = assertNotNull(AgentTranscriptText.extract(f.path))
        assertEquals(AgentTranscriptText.MAX_CHARS, text.length)
        assertTrue(text.endsWith("THE END"))
        assertFalse(text.startsWith("1"))
    }

    @Test
    fun `an unreadable transcript gives null and an empty one gives empty text`() {
        assertNull(AgentTranscriptText.extract("/no/such/transcript.jsonl"))
        assertEquals("", AgentTranscriptText.extract(file("").path))
    }

    @Test
    fun `reading stops at the first line of another entrypoint when only one is wanted`() {
        val f = file(
            """{"type":"user","entrypoint":"sdk-cli","message":{"role":"user","content":"headless one"}}""",
            """{"type":"user","message":{"role":"user","content":"headless two"}}""",
        )
        val info = assertNotNull(AgentTranscriptText.read(f, onlyEntrypoint = "cli"))
        assertEquals("sdk-cli", info.entrypoint)
        assertEquals("", info.text)
        assertEquals("headless one\nheadless two", AgentTranscriptText.read(f)!!.text)
    }
}
