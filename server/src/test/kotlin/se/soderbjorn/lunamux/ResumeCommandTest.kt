/**
 * Tests for the line Lunamux types into a new tab to pick a conversation back up.
 *
 * Contains [ResumeCommandTest].
 *
 * @see resumeCommandOf
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the resume line to the conversation's own id: a conversation is resumed by name, never
 * "the newest one in this folder".
 */
class ResumeCommandTest {
    /** Claude Code resumes the named conversation, and `--continue` is never typed. */
    @Test
    fun claudeResumesTheNamedConversationAndNeverContinues() {
        val cmd = resumeCommandOf(AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = "b7714ac5-1111-2222-3333-444455556666"))
        assertEquals("claude --resume b7714ac5-1111-2222-3333-444455556666", cmd)
        assertEquals(false, cmd!!.contains("--continue"))
    }

    /** A Claude run Lunamux saw only on screen has no conversation id, so there is nothing to type. */
    @Test
    fun claudeWithNoConversationIdHasNothingToTypeInsteadOfContinuing() {
        assertNull(resumeCommandOf(AgentSessionRecord(id = "r1", agent = "claude")))
    }

    /** An id a shell would interpret is refused rather than typed. */
    @Test
    fun anIdWithShellCharactersIsRefused() {
        assertNull(resumeCommandOf(AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = "x; rm -rf ~")))
    }

    /** Codex takes its own id when known and asks which one otherwise; Gemini has no resume. */
    @Test
    fun codexResumesByIdWhenKnownAndGeminiHasNoResume() {
        assertEquals("codex resume c-9", resumeCommandOf(AgentSessionRecord(id = "r1", agent = "codex", agentSessionId = "c-9")))
        assertEquals("codex resume", resumeCommandOf(AgentSessionRecord(id = "r2", agent = "codex")))
        assertNull(resumeCommandOf(AgentSessionRecord(id = "r3", agent = "gemini", agentSessionId = "g-1")))
    }
}
