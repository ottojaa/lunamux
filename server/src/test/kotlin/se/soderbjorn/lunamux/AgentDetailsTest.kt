/**
 * Tests for [AgentDetailsReader] and [InFlight]: what the details of one conversation or one task
 * hold (screen, what was asked, the git work left behind, what resuming would do) and that a
 * repeated request is dropped while the first is being read.
 *
 * The git tests run `git` in temporary folders; each test makes its own folders because
 * [GitCatalog.folderWork] caches per folder for a few seconds.
 *
 * @see AgentDetailsReader
 * @see InFlight
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentDetailsTest {
    private fun tmp(): File = File.createTempFile("lunamux-details", "").apply { delete(); mkdirs(); deleteOnExit() }

    private fun git(dir: File, vararg args: String) {
        val p = ProcessBuilder(listOf("git", "-c", "user.email=t@example.com", "-c", "user.name=t", "-c", "commit.gpgsign=false") + args)
            .directory(dir).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "git ${args.toList()} failed")
    }

    /** A repository with one commit; [dirty] leaves one tracked file changed and one new file. */
    private fun repo(dirty: Boolean = true): File {
        val d = tmp()
        git(d, "init", "-q", "-b", "work")
        File(d, "a.txt").writeText("one\n")
        git(d, "add", "."); git(d, "commit", "-q", "-m", "first")
        if (dirty) {
            File(d, "a.txt").writeText("one\ntwo\n")
            File(d, "new.txt").writeText("x\n")
        }
        return d
    }

    private fun closed(id: String, cwd: String?, taskId: String? = null, at: Long = 10, transcript: String? = null) = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = "conv-$id", source = "hooks", cwd = cwd, taskId = taskId,
        title = "t", state = AgentSessionState.ENDED, stateSince = at, endedAt = at, endReason = "closed",
        transcriptPath = transcript, lastScreen = "line one\n\nline two\n",
    )

    private fun transcript(vararg lines: String): File = File.createTempFile("transcript", ".jsonl").apply {
        writeText(lines.joinToString("\n")); deleteOnExit()
    }

    private fun user(text: String, at: String, cwd: String? = null) =
        """{"type":"user",${cwd?.let { "\"cwd\":\"$it\"," } ?: ""}"message":{"role":"user","content":"$text"},"timestamp":"$at"}"""

    private fun reply(text: String, at: String) =
        """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"$text"}]},"timestamp":"$at"}"""

    /** A closed conversation's details: the saved screen, what was asked, and the work in its folder. */
    @Test
    fun `a closed conversation shows its saved screen, its turns and its folder's work`() {
        val dir = repo()
        val t = transcript(
            user("fix the bug", "2026-11-17T14:50:00.000Z", cwd = dir.path),
            reply("done, fixed it", "2026-11-17T14:51:00.000Z"),
        )
        val d = AgentDetailsReader.ofRecord(closed("r1", dir.path, transcript = t.path), null)
        assertEquals("r1", d.recordId)
        assertFalse(d.live)
        assertEquals(listOf("line one", "", "line two"), d.screen.map { it.trimEnd() }.dropLastWhile { it.isEmpty() }.let { if (it.size == 3) it else d.screen })
        assertEquals("fix the bug", d.turns.single().prompt)
        assertEquals("done, fixed it", d.turns.single().reply)
        val w = d.work.single()
        assertEquals("work", w.branch)
        assertEquals(1, w.changed)
        assertEquals(1, w.untracked)
    }

    /** A repository with no remote says nothing about unpushed commits; one with a remote counts them. */
    @Test
    fun `unpushed is unknown without a remote and counted with one`() {
        val plain = repo(dirty = false)
        assertNull(AgentDetailsReader.ofRecord(closed("a", plain.path), null).work.single().unpushed)

        val withRemote = repo(dirty = false)
        val bare = tmp()
        git(bare, "init", "-q", "--bare")
        git(withRemote, "remote", "add", "origin", bare.path)
        git(withRemote, "push", "-q", "-u", "origin", "work")
        File(withRemote, "b.txt").writeText("b\n")
        git(withRemote, "add", "."); git(withRemote, "commit", "-q", "-m", "second")
        assertEquals(1, AgentDetailsReader.ofRecord(closed("b", withRemote.path), null).work.single().unpushed)
    }

    /** A folder that is gone gives no work and no failure, and a closed row says what resuming does. */
    @Test
    fun `a vanished folder gives no work and a note`() {
        val gone = File(tmp(), "gone/deeper")
        val d = AgentDetailsReader.ofRecord(closed("r", gone.path), null)
        assertTrue(d.work.isEmpty())
        assertTrue(d.note!!.startsWith("Its folder is gone. It resumes in "), d.note)
    }

    /** The transcript says where the conversation went on to work, so the work is read there. */
    @Test
    fun `work is read in the folder the transcript moved to`() {
        val started = tmp()
        val moved = repo()
        val t = transcript(user("go", "2026-11-17T14:50:00.000Z", cwd = moved.path))
        val d = AgentDetailsReader.ofRecord(closed("r", started.path, transcript = t.path), null)
        assertEquals(1, d.work.single().changed)
    }

    /** A transcript is read again only when the file changed. */
    @Test
    fun `the story of an unchanged transcript is not read twice`() {
        val t = transcript(user("hello", "2026-11-17T14:50:00.000Z"))
        assertSame(AgentDetailsReader.storyOf(t.path), AgentDetailsReader.storyOf(t.path))
        val before = AgentDetailsReader.storyOf(t.path)
        t.appendText("\n" + user("again", "2026-11-17T14:55:00.000Z"))
        t.setLastModified(t.lastModified() + 5_000)
        assertEquals(before.turns.size + 1, AgentDetailsReader.storyOf(t.path).turns.size)
    }

    /** A task's work lists each worktree once, leaves clean ones out and puts the latest first. */
    @Test
    fun `task work lists each dirty worktree once, latest first, and drops clean ones`() {
        val older = repo(); val newer = repo(); val clean = repo(dirty = false)
        val recs = listOf(
            closed("a", older.path, "T", at = 10),
            closed("b", newer.path, "T", at = 30),
            closed("b2", newer.path, "T", at = 5),
            closed("c", clean.path, "T", at = 40),
            closed("x", repo().path, "OTHER", at = 50),
        )
        val d = AgentDetailsReader.ofTask("T", recs)
        assertEquals("T", d.taskId)
        assertEquals(listOf(newer.name, older.name), d.work.map { it.name })
    }

    /** One task's details never list more than six worktrees. */
    @Test
    fun `task work is capped at six worktrees`() {
        val recs = (1..8).map { closed("r$it", repo().path, "T", at = it.toLong()) }
        assertEquals(6, AgentDetailsReader.ofTask("T", recs).work.size)
    }

    /** A second request for the same thing while the first is read is dropped; a later one runs. */
    @Test
    fun `a repeat while one is in flight is dropped`() = runBlocking {
        val guard = InFlight()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var runs = 0
        val first = async { guard.once("k") { runs++; started.complete(Unit); release.await() } }
        started.await()
        assertFalse(guard.once("k") { runs++ }, "the repeat is dropped")
        assertTrue(guard.once("other") { runs++ }, "another key is not")
        release.complete(Unit)
        assertTrue(first.await())
        assertTrue(guard.once("k") { runs++ }, "after it finished, the same request runs again")
        assertEquals(3, runs)
    }
}
