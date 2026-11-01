/**
 * Tests for [ClaudeHistoryImport]: which Claude Code transcripts become
 * searchable session records, that none is imported twice, and that imported
 * sessions stay out of the "Earlier" list while remaining searchable.
 *
 * @see ClaudeHistoryImport
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClaudeHistoryImportTest {
    private val root = File.createTempFile("lunamux-import", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val projects = File(root, "projects").apply { mkdirs() }
    private val repo = SettingsRepository(File(root, "test.db"))
    private var nextId = 0

    private fun transcript(folder: String, session: String, entrypoint: String, cwd: String, said: String) {
        val dir = File(projects, folder).apply { mkdirs() }
        File(dir, "$session.jsonl").writeText(
            """{"type":"user","sessionId":"$session","cwd":"$cwd","gitBranch":"feature/ABC-7-thing","entrypoint":"$entrypoint","timestamp":"2026-10-01T10:00:00.000Z","message":{"role":"user","content":"$said"}}""" + "\n" +
                """{"type":"ai-title","aiTitle":"A title"}""" + "\n",
        )
    }

    private fun import() = ClaudeHistoryImport.run(repo, projects) { "imp${++nextId}" }

    @Test
    fun `interactive sessions are imported once with their facts and text`() {
        transcript("p1", "sess-1", "cli", "/w/app/.claude/worktrees/abc-7/sub", "look for demandbase")
        assertEquals(1, import())
        assertEquals(0, import(), "a second run adds nothing")

        val r = assertNotNull(repo.findAgentSession("claude", "sess-1"))
        assertEquals("import", r.source)
        assertEquals("ended", r.state)
        assertEquals("A title", r.title)
        assertEquals("look for demandbase", r.firstPrompt)
        assertEquals("/w/app", r.repoRoot)
        assertEquals("/w/app/.claude/worktrees/abc-7", r.worktreePath)
        assertEquals("/w/app/.claude/worktrees/abc-7/sub", r.cwd)
        assertEquals("app", r.repo)
        assertEquals(listOf("ABC-7"), r.links)
        assertEquals("prompt", AgentSessionSearch.search(repo, null, "demandbase").single().field)
    }

    @Test
    fun `background and sdk sessions are skipped`() {
        transcript("p1", "sdk", "sdk-cli", "/w/app", "headless run")
        transcript("p1", "desk", "claude-desktop", "/w/app", "desktop run")
        assertEquals(0, import())
        assertTrue(repo.searchAgentSessions("run").isEmpty())
    }

    @Test
    fun `a session Lunamux already recorded is not imported again`() {
        repo.saveAgentSession(
            AgentSessionRecord(id = "own", agent = "claude", agentSessionId = "sess-2", state = "ended",
                stateSince = 1, createdAt = 1, endedAt = 1),
        )
        transcript("p1", "sess-2", "cli", "/w/app", "hello")
        assertEquals(0, import())
        assertEquals(1, repo.loadRecentEndedAgentSessions(50).size)
    }

    @Test
    fun `imported sessions are not in Earlier but a resumed one is`() {
        transcript("p1", "sess-3", "cli", "/w/app", "hello world")
        import()
        assertTrue(repo.loadRecentEndedAgentSessions(50).isEmpty())
        assertEquals(1, repo.searchAgentSessions("hello").size)

        // Resuming it in Lunamux makes the tracker record a hook-sourced record on the same row.
        val imported = repo.findAgentSession("claude", "sess-3")!!
        repo.saveAgentSession(imported.copy(source = "hooks"))
        assertEquals(listOf(imported.id), repo.loadRecentEndedAgentSessions(50).map { it.id })
    }

    @Test
    fun `an unreadable or empty projects folder imports nothing`() {
        assertEquals(0, ClaudeHistoryImport.run(repo, File(root, "nope")) { "x" })
        File(projects, "empty").mkdirs()
        File(projects, "empty/bad.jsonl").writeText("garbage")
        assertEquals(0, import())
    }

    @Test
    fun `a file untouched since the previous complete run is not opened again`() {
        transcript("p1", "sess-4", "sdk-cli", "/w/app", "headless")
        assertEquals(0, import())

        // The same file turning into an interactive transcript is only noticed once it changes:
        // a file older than the last run was already looked at by it.
        val file = File(projects, "p1/sess-4.jsonl")
        transcript("p1", "sess-4", "cli", "/w/app", "now interactive")
        file.setLastModified(System.currentTimeMillis() - 60_000)
        assertEquals(0, import(), "an old file is skipped")
        file.setLastModified(System.currentTimeMillis() + 1_000)
        assertEquals(1, import(), "a modified file is read")
    }

    @Test
    fun `a transcript of a folder with an unidentified running session waits for a later run`() {
        transcript("p1", "sess-5", "cli", "/w/app", "still going")
        val run = { ClaudeHistoryImport.run(repo, projects, runningIn = { it == "/w/app" }) { "imp${++nextId}" } }
        assertEquals(0, run(), "held back so the live session is not recorded twice")
        assertEquals(1, import(), "imported once nothing is running there; the watermark did not move")
    }
}
