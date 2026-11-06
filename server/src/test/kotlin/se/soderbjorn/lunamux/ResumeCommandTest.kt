/**
 * Tests for the line Lunamux types into a new tab to pick a conversation back up, and for the
 * route that types it: a task's main conversation is resumed with the command its task file
 * declares, every other conversation with Lunamux's own resume line.
 *
 * Contains [ResumeCommandTest]. A fake opener stands in for the terminal, so no tab is started.
 *
 * @see resumeCommandOf
 * @see resumeAgentSession
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    // ── The command a task declares for its main conversation ───────────────────────────

    private val uuid = "b7714ac5-1111-2222-3333-444455556666"

    /** A declared command that already names the conversation is typed exactly as the file wrote it. */
    @Test
    fun aDeclaredCommandHoldingTheIdIsTypedAsIs() {
        val rec = AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = uuid)
        val declared = "claude --resume $uuid --model opus --name 'lead'"
        assertEquals(declared, resumeCommandOf(rec, TaskOpen(declared, "/work")))
    }

    /** A declared command that lacks the id gets `--resume <id>` after its program, nothing else changed. */
    @Test
    fun aDeclaredCommandLackingTheIdGetsResumeAfterItsProgram() {
        val rec = AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = uuid)
        assertEquals(
            "claude --resume $uuid --model opus --name 'lead'",
            resumeCommandOf(rec, TaskOpen("claude --model opus --name 'lead'", "/work")),
        )
        assertEquals("claude --resume $uuid", resumeCommandOf(rec, TaskOpen("  claude  ", "/work")))
    }

    /** A blank declared command, or none, leaves Lunamux's own line; `--continue` is never typed. */
    @Test
    fun noUsableDeclaredCommandLeavesTheOwnLine() {
        val rec = AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = uuid)
        assertEquals("claude --resume $uuid", resumeCommandOf(rec, null))
        assertEquals("claude --resume $uuid", resumeCommandOf(rec, TaskOpen("   ", "/work")))
        assertTrue(!resumeCommandOf(rec, TaskOpen("claude --model opus", "/work"))!!.contains("--continue"))
    }

    /** A Codex record keeps `codex resume`: the declared command is a Claude command line. */
    @Test
    fun aCodexRecordKeepsItsOwnLineWhateverTheTaskDeclares() {
        val rec = AgentSessionRecord(id = "r1", agent = "codex", agentSessionId = "c-9")
        assertEquals("codex resume c-9", resumeCommandOf(rec, TaskOpen("claude --model opus", "/work")))
    }

    private val base = File.createTempFile("lunamux-resume", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val declaredFolder = File(base, "declared").apply { mkdirs() }.canonicalPath
    private val repo = SettingsRepository(File(base, "settings.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private var nextId = 0
    private val tracker = AgentSessionTracker(
        repo, gitInfo = { GitInfo() }, clock = { 1_000L }, newId = { "r${++nextId}" },
    ).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { "leaf-$it" })
    private val sent = mutableListOf<WindowEnvelope>()
    private val opened = mutableListOf<Triple<String, String, String?>>()

    /** Start a Claude conversation `a-<pane>` in a folder of its own and return its record. */
    private fun session(pane: String): AgentSessionRecord {
        val cwd = File(base, "p-$pane").apply { mkdirs() }.canonicalPath
        val snapshot = PaneSnapshot(pane, leafId = "leaf-$pane", cwd = cwd, programTitle = null, agent = null, screenText = { "" })
        val event: JsonObject = buildJsonObject {
            put("hook_event_name", "SessionStart")
            put("session_id", "a-$pane")
        }
        tracker.onHook(snapshot, event)
        return tracker.openRecordOf(pane)!!
    }

    private fun taskFile(id: String, open: TaskOpen?) = TaskFile(id, "Task $id", repo = null, open = open, modified = 99)

    private fun resume(rec: AgentSessionRecord) = resume(rec.id)

    /** Ask for the resume of [recordId], which is a record id or a conversation id. */
    private fun resume(recordId: String) = runBlocking {
        resumeAgentSession(
            recordId,
            WindowConnectionContext(
                send = { sent += it },
                scope = CoroutineScope(Dispatchers.Unconfined),
                settingsRepo = repo,
                usageMonitor = ClaudeUsageMonitor(),
                taskIndex = index,
                agentTracker = tracker,
            ),
        ) { folder, title, command -> opened += Triple(folder, title, command) }
    }

    /** The main conversation of a task whose file declares `open` is resumed with that command, in that folder. */
    @Test
    fun theMainOfATaskWithAnOpenActionIsResumedWithTheDeclaredCommand() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", declaredFolder)))
        val main = session("s1")
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(declaredFolder, folder)
        assertEquals("claude --resume a-s1 --model opus", command)
    }

    /** A session of the same task that is not its main conversation is resumed the plain way, in its own folder. */
    @Test
    fun aNonMainSessionOfTheSameTaskIsResumedThePlainWay() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", declaredFolder)))
        val main = session("s1")
        val other = session("s2")
        index.recompute()
        index.setOwnSession("t1", "s1")
        index.addToTask("t1", listOf("s2"))
        resume(other)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(other.cwd, folder)
        assertEquals("claude --resume a-s2", command)
        assertEquals(main.cwd != declaredFolder, true)
    }

    /** A task with no `open` action (a task an agent made, or a file without one) resumes its main the plain way. */
    @Test
    fun aTaskWithNoOpenActionResumesItsMainThePlainWay() {
        files.value = listOf(taskFile("t1", null))
        val main = session("s1")
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(main.cwd, folder)
        assertEquals("claude --resume a-s1", command)
    }

    /** A declared folder that is gone does not block the resume: the plain way is used. */
    @Test
    fun aDeclaredFolderThatIsGoneFallsBackToThePlainWay() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", File(base, "gone").path)))
        val main = session("s1")
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(main.cwd, folder)
        assertEquals("claude --resume a-s1", command)
    }

    /**
     * The realistic case: the main's tab was closed, so its record has ended, and the client asks by conversation id.
     * The line a task file written by the flow declares (the pane is recorded, then the conversation resumed) is typed as written.
     */
    @Test
    fun aClosedMainAskedForByConversationIdIsResumedWithTheDeclaredCommandAsWritten() {
        val declared = "fl /prog home --pane a-s1 && claude --resume a-s1"
        files.value = listOf(taskFile("t1", TaskOpen(declared, declaredFolder)))
        session("s1")
        index.recompute()
        index.setOwnSession("t1", "s1")
        tracker.onHook(
            PaneSnapshot("s1", leafId = "leaf-s1", cwd = declaredFolder, programTitle = null, agent = null, screenText = { "" }),
            buildJsonObject {
                put("hook_event_name", "SessionEnd")
                put("session_id", "a-s1")
            },
        )
        assertEquals("ended", tracker.find("a-s1")!!.state)
        resume("a-s1")
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(declaredFolder, folder)
        assertEquals(declared, command)
    }

    /** An id a shell would interpret is refused before the declared command is looked at. */
    @Test
    fun anIdWithShellCharactersIsRefusedEvenWhenTheTaskDeclaresACommand() {
        val rec = AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = "x; rm -rf ~")
        assertNull(resumeCommandOf(rec, TaskOpen("claude --model opus", "/work")))
        assertNull(resumeCommandOf(rec, TaskOpen("claude --resume x; rm -rf ~", "/work")))
    }

    /** A conversation that no longer exists is answered with an error and opens nothing. */
    @Test
    fun anUnknownConversationIsAnErrorAndOpensNothing() {
        resume(AgentSessionRecord(id = "nope", agent = "claude"))
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }
}
