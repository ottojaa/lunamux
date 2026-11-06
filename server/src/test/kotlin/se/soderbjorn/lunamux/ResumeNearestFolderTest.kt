/**
 * Tests for resuming a closed conversation whose folder is gone: [resumeAgentSession] opens the tab
 * in the nearest folder above it that exists and types the same command as always, [resumeNoteOf]
 * words the line under the row with that same folder, and no git command runs.
 *
 * The cases use a real repository and a linked worktree that was deleted by hand, the situation
 * the server used to repair by recreating the worktree. A fake opener stands in for
 * [openTabRunning], so no terminal is started.
 *
 * @see resumeAgentSession
 * @see resumeNoteOf
 * @see nearestExistingFolder
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumeNearestFolderTest {
    private val base = File.createTempFile("lunamux-nearest", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val main = File(base, "main")
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val repo = SettingsRepository(File(base, "settings.db"))
    private val tracker = AgentSessionTracker(repo)
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { null })
    private val sent = mutableListOf<WindowEnvelope>()
    private val opened = mutableListOf<Triple<String, String, String?>>()
    private val uuid = "b7714ac5-1111-2222-3333-444455556666"

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git", "-c", "user.name=t", "-c", "user.email=t@t", *args))
            .directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.toList()} failed: $out" }
        return out.trim()
    }

    /** A repository `main` with a branch `feat` checked out in a linked worktree `wt` next to it. */
    private fun makeWorktree(): File {
        main.mkdirs()
        git(main, "init", "-b", "trunk")
        File(main, "a.txt").writeText("a")
        git(main, "add", ".")
        git(main, "commit", "-m", "one")
        val wt = File(base, "wt")
        git(main, "worktree", "add", "-b", "feat", wt.path)
        return wt
    }

    private fun closedRecord(cwd: String?, id: String = "r1") = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = uuid, state = "ended", stateSince = 1, createdAt = 1, endedAt = 1,
        cwd = cwd, worktreePath = cwd, branch = "feat", repoRoot = main.path,
    ).also { repo.saveAgentSession(it) }

    private fun ctx() = WindowConnectionContext(
        send = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = repo,
        usageMonitor = ClaudeUsageMonitor(),
        agentTracker = tracker,
        taskIndex = index,
    )

    private fun resume(id: String = "r1") = runBlocking {
        resumeAgentSession(id, ctx()) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    /** A removed worktree opens in the folder it sat in, with the plain resume line, and git is left alone. */
    @Test
    fun `a removed worktree resumes in its parent folder and nothing is recreated`() {
        val wt = makeWorktree()
        val record = closedRecord(wt.path)
        wt.deleteRecursively()
        val worktreesBefore = git(main, "worktree", "list", "--porcelain")
        val branchesBefore = git(main, "branch", "--list")

        resume()

        assertTrue(sent.isEmpty(), "no error: $sent")
        assertEquals(Triple(base.path, "feat", "claude --resume $uuid"), opened.single())
        assertFalse(wt.exists(), "the worktree is not recreated")
        assertEquals(worktreesBefore, git(main, "worktree", "list", "--porcelain"), "no worktree is added or pruned")
        assertEquals(branchesBefore, git(main, "branch", "--list"), "no branch is created")
        assertEquals("Its folder is gone. It resumes in ${base.path}.", resumeNoteOf(record, null))
    }

    /** When the parent is gone as well the walk goes on up, and the note names where it ended. */
    @Test
    fun `a parent that is also gone walks further up`() {
        val deep = File(base, "gone/deeper/wt").path
        val record = closedRecord(deep)

        resume()

        assertEquals(Triple(base.path, "feat", "claude --resume $uuid"), opened.single())
        assertEquals("Its folder is gone. It resumes in ${base.path}.", resumeNoteOf(record, null))
        assertEquals(base.path, nearestExistingFolder(deep))
    }

    /** A folder that exists is used as it is, with no note. */
    @Test
    fun `a folder that exists resumes in place and says nothing`() {
        val record = closedRecord(main.apply { mkdirs() }.path)

        resume()

        assertEquals(main.path, opened.single().first)
        assertNull(resumeNoteOf(record, null))
    }

    /** A conversation that never recorded a folder is refused with the old message, and no tab opens. */
    @Test
    fun `a conversation with no recorded folder is refused`() {
        val record = closedRecord(null)

        resume()

        assertEquals("No folder was recorded for this session.", assertIs<WindowEnvelope.AgentSessionError>(sent.single()).message)
        assertTrue(opened.isEmpty())
        assertEquals("No folder was recorded for this session.", resumeNoteOf(record, null))
        assertEquals("No folder was recorded for this session.", resumeNoteOf(record.copy(cwd = "  "), null))
    }

    /** A conversation with no id to resume is still refused, whatever its folder. */
    @Test
    fun `a conversation without a session id is refused even when its folder is gone`() {
        repo.saveAgentSession(closedRecord(File(base, "gone").path).copy(agentSessionId = null))

        resume()

        assertEquals(
            "This conversation has no session id Lunamux can resume.",
            assertIs<WindowEnvelope.AgentSessionError>(sent.single()).message,
        )
        assertTrue(opened.isEmpty())
    }

    /** A path with no existing ancestor has nowhere to walk to. */
    @Test
    fun `a relative path with nothing above it has no nearest folder`() {
        assertNull(nearestExistingFolder("no-such-folder-anywhere/deeper"))
        assertEquals(base.path, nearestExistingFolder(base.path))
    }

    /**
     * A task's main conversation resumes with the task's declared line in the declared folder; when that
     * folder is gone the declared line is typed in the nearest folder above it, and the note names it.
     */
    @Test
    fun `a declared open folder that is gone takes the declared line in the nearest folder above it`() {
        val declaredFolder = File(base, "declared/inner")
        files.value = listOf(
            TaskFile("t1", "Task one", repo = null, open = TaskOpen("claude 'start'", declaredFolder.path), modified = 99),
        )
        index.recompute()
        val record = closedRecord(declaredFolder.path)
        index.setMainConversation("t1", uuid)
        val declared = index.openOfMain(record)

        resume()

        assertEquals(Triple(base.path, "feat", "claude --resume $uuid 'start'"), opened.single())
        assertEquals("Its folder is gone. It resumes in ${base.path}.", resumeNoteOf(record, declared))
    }
}
