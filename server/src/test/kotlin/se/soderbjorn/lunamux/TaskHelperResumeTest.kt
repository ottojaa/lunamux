/**
 * The owner's case: a task whose stored main conversation is a helper that left no transcript, in a
 * pane that also ran the task's real conversation. The task has to keep answering with the real
 * conversation and resume it, however the panes were shuffled afterwards, and a conversation with no
 * transcript is neither resumed nor reopened (which would move its record into the pane that tried).
 *
 * Seeds the database the way the owner's backup holds these rows (ids shortened), starts the tracker
 * on it, then plays the hooks that followed.
 *
 * @see TaskIndex.speakerOf
 * @see resumeAgentSession
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskHelperResumeTest {
    private val dir = File.createTempFile("lunamux-helper-resume", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val work = File(dir, "work").apply { mkdirs() }.canonicalPath
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val t0 = System.currentTimeMillis() - 1_000_000

    private val real = "e37a2e3a"
    private val helper = "8bc0181b"

    /** A transcript on disk, which is what makes a conversation resumable. */
    private fun transcript(name: String) = File(dir, "$name.jsonl").apply { writeText("{}") }.path

    private fun row(
        id: String, conversation: String, leaf: String, from: Long, to: Long,
        task: String? = null, transcript: String? = null, background: Boolean = false,
    ) = repo.saveAgentSession(
        AgentSessionRecord(
            id = id, agent = "claude", agentSessionId = conversation, source = "hooks", leafId = leaf, cwd = work,
            transcriptPath = transcript, state = "ended", createdAt = t0 + from, endedAt = t0 + to, taskId = task,
            background = background, firstPrompt = "said something",
        ),
    )

    init {
        // Pane n347 as in the backup: an untagged conversation, the helper (no transcript), the real
        // conversation tagged with the task; pane n349 holds an older run of the real one, in a background tab.
        row("r-36e0", "36e043c0", "n347", 1, 6, transcript = transcript("36e043c0"))
        row("r-c2f1", helper, "n347", 10, 40, transcript = File(dir, "gone.jsonl").path)
        row("r-5294", "52940c67", "n347", 30, 31)
        row("r-f019", real, "n347", 50, 90, task = "measure", transcript = transcript(real))
        row("r-4df9", real, "n349", 5, 45, task = "measure", transcript = transcript(real), background = true)
        // A later run of the same task in a background tab: it must not take the name from the real conversation.
        row("r-bg", "bbbbbbbb", "n350", 95, 96, task = "measure", transcript = transcript("bg"), background = true)
        repo.insertTask("measure", "Measure a feature while it's built", t0)
        repo.setTaskMainConversation("measure", helper)
    }

    // Lazy, so that a test can change the stored rows before the server starts on them.
    private val tracker by lazy { AgentSessionTracker(repo, gitInfo = { GitInfo() }, clock = { t0 + 1000 }).also { it.start() } }
    private val index by lazy { TaskIndex(repo, tracker, MutableStateFlow(emptyList()), leafOfSession = { null }) }

    private fun hook(event: String, conversation: String, leaf: String, terminal: String, transcript: String? = null) = tracker.onHook(
        PaneSnapshot(terminal, leafId = leaf, cwd = work, programTitle = null, agent = null, screenText = { "" }),
        buildJsonObject {
            put("hook_event_name", event); put("session_id", conversation)
            transcript?.let { put("transcript_path", it) }
        },
    )

    private fun speaker(): String? = index.also { it.recompute() }.listing().single { it.id == "measure" }.mainConversation

    /** What ↵ on a row does for [conversation]: the errors sent back and the commands typed. */
    private fun resume(conversation: String): Pair<List<WindowEnvelope>, List<String?>> {
        val sent = mutableListOf<WindowEnvelope>()
        val typed = mutableListOf<String?>()
        runBlocking {
            resumeAgentSession(
                conversation,
                WindowConnectionContext(
                    send = { sent += it }, scope = CoroutineScope(Dispatchers.Unconfined), settingsRepo = repo,
                    usageMonitor = ClaudeUsageMonitor(), taskIndex = index, agentTracker = tracker,
                ),
            ) { _, _, command -> typed += command }
        }
        return sent to typed
    }

    @Test
    fun `a task naming a helper with no transcript resumes its real conversation, and trying the helper changes nothing`() {
        assertEquals(real, speaker(), "as the server starts on the backup")
        assertEquals(listOf<String?>("claude --resume $real"), resume(real).second)

        // Later the real conversation was reopened in the other pane, and the helper's id was tried there.
        hook("SessionStart", real, "n349", "term-new")
        hook("SessionStart", helper, "n349", "term-new")
        hook("SessionEnd", helper, "n349", "term-new")
        assertEquals(real, speaker(), "the real conversation still speaks for the task")
        val helperRecord = tracker.snapshot().single { it.id == "r-c2f1" }
        assertEquals(listOf("n347", "ended", t0 + 40), listOf(helperRecord.leafId, helperRecord.state, helperRecord.endedAt), "the helper stayed in its pane and closed")
        assertEquals(listOf("r-c2f1"), tracker.snapshot().filter { it.agentSessionId == helper }.map { it.id }, "no record was added for the helper")
        assertTrue(tracker.summaries.value.none { it.conversationId == helper && it.state != "ended" }, "the helper does not look live")

        val (sent, typed) = resume(helper)
        assertEquals(emptyList<String?>(), typed, "nothing was typed for the helper")
        assertIs<WindowEnvelope.AgentSessionError>(sent.single())

        // Claude writes the file lazily: once the person types in that conversation, its hooks name a file that exists.
        hook("UserPromptSubmit", helper, "n349", "term-new", transcript = transcript("8bc0181b"))
        assertTrue(tracker.summaries.value.any { it.conversationId == helper && it.state != "ended" }, "a conversation that now has a transcript is real")
    }

    @Test
    fun `on the data as the failed resume left it, the helper reopened in the other pane and closed again, the real conversation is still resumed`() {
        // The stored helper record as the owner's database holds it after the failed press: moved to pane
        // n349, reopened and closed again later than anything else, still without a transcript.
        val moved = repo.findAgentSessionById("r-c2f1")!!
        repo.saveAgentSession(moved.copy(leafId = "n349", terminalSessionId = "term-new", endedAt = t0 + 200))
        assertEquals(real, speaker())
        assertEquals(listOf<String?>("claude --resume $real"), resume(speaker()!!).second)
    }
}
