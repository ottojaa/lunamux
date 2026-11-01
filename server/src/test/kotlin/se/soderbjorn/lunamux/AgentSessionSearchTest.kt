/**
 * Tests for [AgentSessionSearch] and the SQL under it: which sessions a query
 * finds, how the hits are ranked and labelled, how the snippet is cut, and that
 * `%`, `_` and `\` in a query are matched literally.
 *
 * @see AgentSessionSearch
 * @see se.soderbjorn.lunamux.persistence.SettingsRepository.searchAgentSessions
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentSessionSearchTest {
    private val dbDir = File.createTempFile("lunamux-agent-search", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dbDir, "test.db"))

    private fun save(
        id: String, title: String? = null, prompt: String? = null, branch: String? = null,
        state: String = "ended", at: Long = 1_000, screen: String? = null, text: String? = null,
        links: List<String> = emptyList(),
    ) {
        repo.saveAgentSession(
            AgentSessionRecord(
                id = id, agent = "claude", title = title, firstPrompt = prompt, branch = branch, links = links,
                state = state, stateSince = at, createdAt = at, endedAt = if (state == "ended") at else null,
                lastScreen = screen, cwd = "/work/$id",
            ),
        )
        if (text != null) repo.setAgentSessionSearchText(id, text)
    }

    private fun search(q: String) = AgentSessionSearch.search(repo, null, q)

    @Test
    fun `hits are ranked title then prompt then branch or links then transcript`() {
        save("transcript", text = "we talked about demandbase here")
        save("branch", branch = "feature/demandbase-x")
        save("prompt", prompt = "fix the demandbase import")
        save("title", title = "Demandbase plan")
        save("screen", screen = "building demandbase")
        val ranked = search("demandbase")
        assertEquals(listOf("title", "prompt", "branch"), ranked.take(3).map { it.summary.id })
        assertEquals(listOf("title", "prompt", "branch"), ranked.take(3).map { it.field })
        assertEquals(setOf("transcript", "screen"), ranked.drop(3).map { it.summary.id }.toSet())
        assertEquals(setOf("transcript", "screen"), ranked.drop(3).map { it.field }.toSet())
    }

    @Test
    fun `within a tier open sessions come first and then the newest`() {
        save("old", title = "alpha work", at = 1_000)
        save("new", title = "alpha again", at = 2_000)
        save("open", title = "alpha live", state = "working", at = 500)
        assertEquals(listOf("open", "new", "old"), search("alpha").map { it.summary.id })
    }

    @Test
    fun `a title match carries no snippet but the match range inside the title`() {
        save("t", title = "Plan the Demandbase work")
        val hit = search("demandbase").single()
        assertEquals("title", hit.field)
        assertNull(hit.snippet)
        assertEquals("Demandbase", "Plan the Demandbase work".substring(hit.start, hit.start + hit.length))
    }

    @Test
    fun `a transcript hit snips about forty characters either side on one line`() {
        val long = "a".repeat(300) + " before the NEEDLE after " + "b".repeat(300) + "\nsecond line"
        save("x", text = "first line\n$long")
        val hit = search("needle").single()
        assertEquals("transcript", hit.field)
        val snippet = assertNotNull(hit.snippet)
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"), snippet)
        assertEquals("NEEDLE", snippet.substring(hit.start, hit.start + hit.length))
        assertTrue(snippet.length <= 40 + 6 + 40 + 2, snippet.length.toString())
        assertTrue('\n' !in snippet)
    }

    @Test
    fun `a last screen hit is labelled screen`() {
        save("x", screen = "line one\nBUILD FAILED in 2s\nline three")
        val hit = search("build failed").single()
        assertEquals("screen", hit.field)
        assertEquals("BUILD FAILED in 2s", hit.snippet)
    }

    @Test
    fun `percent underscore and backslash in a query are matched literally`() {
        save("pct", prompt = "it is 100% done")
        save("nopct", prompt = "it is 100 done")
        save("us", prompt = "call a_b now")
        save("nous", prompt = "call axb now")
        save("bs", prompt = "path C:\\temp")
        assertEquals(listOf("pct"), search("100%").map { it.summary.id })
        assertEquals(listOf("us"), search("a_b").map { it.summary.id })
        assertEquals(listOf("bs"), search("C:\\t").map { it.summary.id })
    }

    @Test
    fun `a query under two characters has no matches and results are capped at fifty`() {
        (1..60).forEach { save("r$it", title = "common $it", at = it.toLong()) }
        assertTrue(search("c").isEmpty())
        assertTrue(search(" ").isEmpty())
        val hits = search("common")
        assertEquals(50, hits.size)
        assertEquals("r60", hits.first().summary.id)
    }

    @Test
    fun `search never returns the long columns whole`() {
        save("x", screen = "needle on the screen", text = "needle in the text " + "z".repeat(100_000))
        val hit = repo.searchAgentSessions("needle").single()
        assertNull(hit.record.lastScreen)
        assertTrue(assertNotNull(hit.transcriptWindow).length <= 240)
    }

    @Test
    fun `ticket links are searched`() {
        save("x", links = listOf("ABC-12"), title = "no match here")
        val hit = search("abc-12").single()
        assertEquals("links", hit.field)
        assertEquals("ABC-12", hit.snippet)
    }

    @Test
    fun `saving a record again keeps its search text`() {
        save("x", title = "one", text = "kept words")
        save("x", title = "two")
        assertEquals("transcript", search("kept words").single().field)
    }
}
