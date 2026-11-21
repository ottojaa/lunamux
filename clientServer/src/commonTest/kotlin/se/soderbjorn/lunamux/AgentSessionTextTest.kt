/**
 * Tests for [AgentSessionText]: the rules that turn a session's stored words into what the agents
 * overview shows (plain previews, titles that name the work).
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTextTest {
    @Test
    fun `markdown reads as the text it marks up`() {
        assertEquals("4. Tell me how it went, then decide", AgentSessionText.plain("**4. Tell me how it went,** then decide"))
        assertEquals("Findings", AgentSessionText.plain("## Findings"))
        assertEquals("run gradle test now", AgentSessionText.plain("- run `gradle test` now"))
        assertEquals("first second", AgentSessionText.plain("1. first   *second*"))
        assertEquals("see the docs", AgentSessionText.plain("see [the docs](https://example.com/x)"))
        assertEquals("quoted", AgentSessionText.plain("> quoted"))
        assertEquals("gone and bold", AgentSessionText.plain("~~gone~~ and __bold__"))
    }

    @Test
    fun `words with underscores or stars inside are left alone`() {
        assertEquals("rename snake_case_name to camelCase", AgentSessionText.plain("rename snake_case_name to camelCase"))
        assertEquals("2 * 3 * 4 = 24", AgentSessionText.plain("2 * 3 * 4 = 24"))
    }

    @Test
    fun `a preview skips blank lines and lines that are only a fence`() {
        assertNull(AgentSessionText.previewOf(null))
        assertNull(AgentSessionText.previewOf("```"))
        assertNull(AgentSessionText.previewOf("  \n```kotlin\n~~~\n---"))
        assertEquals("npm test", AgentSessionText.previewOf("```\nnpm test\n```"))
        assertEquals("Done", AgentSessionText.previewOf("\n**Done**"))
    }

    @Test
    fun `a title from a prompt is its first few plain words`() {
        assertEquals("Why did the nightly backup email never arrive?", AgentSessionText.titleFromPrompt("Why did the nightly backup email never arrive?"))
        assertEquals("Plan: read the owner's notes and fix the…", AgentSessionText.titleFromPrompt("**Plan:** read the owner's notes and `fix` the data first"))
        assertNull(AgentSessionText.titleFromPrompt(null))
        assertNull(AgentSessionText.titleFromPrompt("```"))
    }

    @Test
    fun `a title from a prompt never carries a slash command or a path`() {
        assertNull(AgentSessionText.titleFromPrompt("/feature-implement /Users/me/.claude/feature-runs/aov-22-impl"))
        assertEquals("fix the login ~/code/app/src/Login.kt", AgentSessionText.titleFromPrompt("/review fix the login ~/code/app/src/Login.kt"))
        assertEquals("Open ./notes/today.md and read", AgentSessionText.titleFromPrompt("Open ./notes/today.md and read"))
    }

    @Test
    fun `a long prompt is cut at a word with an ellipsis`() {
        val t = AgentSessionText.titleFromPrompt("Implementation-of-an-extraordinarily-long-compound-word another extraordinarily-long-compound-word-here")!!
        assertTrue(t.endsWith("…") && t.length <= 58, t)
        assertEquals("one two three four five six seven eight…", AgentSessionText.titleFromPrompt("one two three four five six seven eight nine"))
    }

    @Test
    fun `a title that only says where the session ran is not meaningful`() {
        fun ok(t: String?, branch: String? = null, repo: String? = null, folder: String? = null) =
            AgentSessionText.isMeaningfulTitle(t, branch, repo, folder)
        assertFalse(ok(null))
        assertFalse(ok("  "))
        assertFalse(ok("~"))
        assertFalse(ok("HEAD"))
        assertFalse(ok("head"))
        assertFalse(ok("~/code/app"))
        assertFalse(ok("/Users/me/code/app"))
        assertFalse(ok("aov-21-impl"))
        assertFalse(ok("aov-21-plan"))
        assertFalse(ok("aov-21-impl-r2"))
        assertFalse(ok("feat/login", branch = "feat/login"))
        assertFalse(ok("lunamux-agent-overview", repo = "lunamux-agent-overview"))
        assertFalse(ok("Orchestrate", folder = "orchestrate"))
    }

    @Test
    fun `a title that says what the work is stays`() {
        fun ok(t: String, branch: String? = null) = AgentSessionText.isMeaningfulTitle(t, branch, "lunamux", "lunamux")
        assertTrue(ok("Sidebar flicker on resize", branch = "fix/sidebar-flicker"))
        assertTrue(ok("Fix the login"))
        assertTrue(ok("ABC-12 login"))
        assertTrue(ok("Release 4.2"))
        assertTrue(ok("fix-404-page"))
        assertTrue(ok("react-18-upgrade"))
        assertTrue(ok("node-20"))
        assertTrue(ok("~/.zshrc loads slowly"))
        assertTrue(ok("~5 minutes to first byte"))
    }

    @Test
    fun `a reply comes to the outcome it opens with, or the question it ends on`() {
        fun said(reply: String?) = AgentSessionText.saidOf(reply)
        assertEquals("Both are done.", said("## Result\n\nBoth are **done**. The tests pass.\n\nI can update the reply and show you the text before posting."))
        assertEquals("Want me to go ahead?", said("Fixed the parser.\n\nWant me to go ahead?"))
        assertEquals("The write-up is at notes.md.", said("The write-up is at `notes.md`. Its sections:\n\n- one\n- two"))
        assertEquals("Merged and deployed.", said("**What changed:**\n\n- Merged and deployed.\n- Docs updated."), "a line that leads into a list is passed over")
        assertEquals("Notes: the two worth acting on were the staff gate and the cache", said("**Notes:** the two worth acting on were the staff gate and the cache"))
        assertEquals("4. Tell me how it went, then decide", said("**4. Tell me how it went,** then decide"), "a number before a stop does not end a sentence")
        assertEquals("Use e.g. the cache.", said("Use e.g. the cache."))
        assertNull(said("```"))
        assertNull(said("  "))
        assertNull(said(null))
        val long = said("word ".repeat(100))!!
        assertTrue(long.length <= AgentSessionText.SAID_CHARS + 1 && long.endsWith("word…"), long)
        assertEquals(long, said(long), "reading a kept line again changes nothing")
    }

    @Test
    fun `a recap keeps what the work is and where it stands, whole`() {
        fun recap(text: String?) = AgentSessionText.recapOf(text)
        assertEquals(
            "Upshot's three exports are moving to Gotenberg, and run 1 is stuck because Docker Desktop is off.",
            recap("Upshot's three exports are moving to Gotenberg, and run 1 is stuck because Docker Desktop is off. Please start Docker and tell me, and I'll restart the run."),
        )
        assertEquals(
            "Making the flow robust against stalled reviews. Both fixes are pushed and live: thread watching and stopping servers.",
            recap("Goal: making the flow robust against stalled reviews. Both fixes are pushed and live: thread watching and stopping servers. Next: watch the next run. (disable recaps in /config)"),
            "the Goal lead, the Next sentence and the closing note go",
        )
        assertEquals(
            "You asked for a fixer. It's built and pushed, with your requests landed.",
            recap("You asked for a fixer. It's built and pushed, with your requests landed. Nothing is waiting on you, so just use it. (turn off recaps in /config)"),
            "two sentences at most, never cut inside one",
        )
        assertEquals("The PR #10181 swap to oxlint is green in CI.", recap("The PR `#10181` swap to oxlint is green in CI."))
        assertNull(recap("Next: test it on your phone."))
        assertNull(recap("  "))
        assertNull(recap(null))
    }
}
