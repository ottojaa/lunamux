/**
 * Tests for [AgentSessionText]: the rules that turn a session's stored words into what the agents
 * overview shows (plain previews, titles that name the work, programme machinery detection).
 *
 * @see AgentSessionText
 */
package se.soderbjorn.lunamux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionTextTest {
    private val episode = "First claim the programme again (another session may hold it): ~/.claude/skills/feature/fl " +
        "~/.claude/orchestrate/p claim --reason \"manager episode\"\n\nNeeds your judgement: x"

    @Test
    fun `a controller's manager episode is machinery, whichever way it was started`() {
        assertTrue(AgentSessionText.isProgrammeMachinery(episode))
        assertTrue(AgentSessionText.isProgrammeMachinery("\n  First claim the programme again (x)"))
        assertTrue(AgentSessionText.isProgrammeMachinery("/orchestrate episode ~/.claude/orchestrate/p\n\ndigest"))
        assertTrue(AgentSessionText.isProgrammeMachinery("/orchestrate episode"))
    }

    @Test
    fun `a session the owner starts is not machinery, even about orchestrating`() {
        assertFalse(AgentSessionText.isProgrammeMachinery(null))
        assertFalse(AgentSessionText.isProgrammeMachinery(""))
        assertFalse(AgentSessionText.isProgrammeMachinery("/orchestrate"))
        assertFalse(AgentSessionText.isProgrammeMachinery("/orchestrate episodes are confusing, explain"))
        assertFalse(AgentSessionText.isProgrammeMachinery("Why did the first claim the programme again fail?"))
    }

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
        assertEquals("fix the login", AgentSessionText.titleFromPrompt("/review fix the login ~/code/app/src/Login.kt"))
        assertEquals("Open and read", AgentSessionText.titleFromPrompt("Open ./notes/today.md and read"))
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
    }
}
