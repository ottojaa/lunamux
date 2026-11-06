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

    /** Codex takes its own id when known and asks which one otherwise; Gemini has no resume. */
    @Test
    fun codexResumesByIdWhenKnownAndGeminiHasNoResume() {
        assertEquals("codex resume c-9", resumeCommandOf(AgentSessionRecord(id = "r1", agent = "codex", agentSessionId = "c-9")))
        assertEquals("codex resume", resumeCommandOf(AgentSessionRecord(id = "r2", agent = "codex")))
        assertNull(resumeCommandOf(AgentSessionRecord(id = "r3", agent = "gemini", agentSessionId = "g-1")))
    }

    // ── The command a task declares for its main conversation ───────────────────────────

    private val uuid = "b7714ac5-1111-2222-3333-444455556666"

    /** A conversation that ran in the declared folder `/work`, which the pure cases below declare. */
    private val inWork = AgentSessionRecord(id = "r1", agent = "claude", agentSessionId = uuid, cwd = "/work")

    private fun declaredFor(rec: AgentSessionRecord, command: String, folder: String = "/work") =
        declaredResumeFor(rec, TaskOpen(command, folder))

    /** A declared command that lacks the id gets `--resume <id>` after its program, nothing else changed. */
    @Test
    fun aDeclaredCommandLackingTheIdGetsResumeAfterItsProgram() {
        assertEquals(
            "claude --resume $uuid --model opus --name 'work'",
            declaredFor(inWork, "claude --model opus --name 'work'"),
        )
        assertEquals("claude --resume $uuid", declaredFor(inWork, "  claude  "))
    }

    /**
     * A declared line that cannot take `--resume <id>` safely is not used: one that already resumes, continues or names
     * another conversation, names another program, or chains commands would otherwise resume the wrong one or none.
     */
    @Test
    fun aDeclaredLineThatCannotTakeResumeIsNotUsed() {
        for (line in listOf(
            "claude --resume old-conversation",
            "claude --resume=old-conversation --model opus",
            "claude -r old-conversation",
            "claude --continue",
            "claude -c",
            "claude --session-id 11111111-2222-3333-4444-555555555555",
            "claude --session-id=11111111-2222-3333-4444-555555555555",
            "record-pane --pane old && claude --resume old",
            "cd /work && claude --model opus",
            "FOO=1 claude --model opus",
            "tool --model opus",
            "claude --model opus; echo done",
            "claude \"\$(date)\"",
            "'claude' --model opus",
        )) {
            assertNull(declaredFor(inWork, line), line)
        }
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
    private fun session(pane: String): AgentSessionRecord =
        sessionIn(pane, File(base, "p-$pane").apply { mkdirs() }.canonicalPath)

    /** Start a Claude conversation `a-<pane>` in [cwd] and return its record. */
    private fun sessionIn(pane: String, cwd: String): AgentSessionRecord {
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
        val main = sessionIn("s1", declaredFolder)
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(declaredFolder, folder)
        assertEquals("claude --resume a-s1 --model opus", command)
    }

    /** A declared folder that is gone does not block the resume: the declared line is typed in the nearest folder above it. */
    @Test
    fun aDeclaredFolderThatIsGoneTakesTheDeclaredLineInTheNearestFolderAboveIt() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --resume a-s1 --model opus", File(base, "gone/inner").path)))
        val main = session("s1")
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(base.path, folder)
        assertEquals("claude --resume a-s1 --model opus", command)
    }

    /**
     * The realistic case: the main's tab was closed, so its record has ended, and the client asks by conversation id.
     * A declared line that records its pane and then resumes the conversation names the conversation already, so it is typed as written.
     */
    @Test
    fun aClosedMainAskedForByConversationIdIsResumedWithTheDeclaredCommandAsWritten() {
        val declared = "record-pane --pane a-s1 && claude --resume a-s1"
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

    /**
     * A declared line that has to be given the id is typed only where the conversation ran: `claude --resume`
     * finds a conversation from its own folder alone, so a main that ran elsewhere is resumed the plain way.
     */
    @Test
    fun aDeclaredLineLackingTheIdIsNotTypedInAFolderTheConversationDidNotRunIn() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", declaredFolder)))
        val main = session("s1")
        assertTrue(main.cwd != declaredFolder)
        index.recompute()
        index.setOwnSession("t1", "s1")
        resume(main)
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals(main.cwd, folder)
        assertEquals("claude --resume a-s1", command)
    }

    /** A main that ran in the declared folder takes the declared line even when the folder is written untidily. */
    @Test
    fun aDeclaredLineLackingTheIdIsTypedWhereTheConversationRan() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", "$declaredFolder/./")))
        sessionIn("s1", declaredFolder)
        index.recompute()
        index.setOwnSession("t1", "s1")
        tracker.onHook(
            PaneSnapshot("s1", leafId = "leaf-s1", cwd = declaredFolder, programTitle = null, agent = null, screenText = { "" }),
            buildJsonObject {
                put("hook_event_name", "SessionEnd")
                put("session_id", "a-s1")
            },
        )
        resume("a-s1")
        assertTrue(sent.isEmpty(), "no error expected: $sent")
        val (folder, _, command) = opened.single()
        assertEquals("$declaredFolder/./", folder)
        assertEquals("claude --resume a-s1 --model opus", command)
    }

    /** The route refuses such an id too, when the task's main conversation is the one asked for. */
    @Test
    fun theRouteTypesNothingForAnIdWithShellCharactersWhateverTheTaskDeclares() {
        files.value = listOf(taskFile("t1", TaskOpen("claude --model opus", declaredFolder)))
        repo.saveAgentSession(
            AgentSessionRecord(id = "bad1", agent = "claude", agentSessionId = "x; rm -rf ~", cwd = declaredFolder, state = "ended", endedAt = 900L),
        )
        index.recompute()
        index.setMainConversation("t1", "x; rm -rf ~")
        resume("bad1")
        assertTrue(opened.isEmpty(), "nothing may be typed: $opened")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
    }

    /** A conversation that no longer exists is answered with an error and opens nothing. */
    @Test
    fun anUnknownConversationIsAnErrorAndOpensNothing() {
        resume(AgentSessionRecord(id = "nope", agent = "claude"))
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())
        assertTrue(opened.isEmpty())
    }
}
