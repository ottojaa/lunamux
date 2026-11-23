/**
 * Tests for the overview's window commands: opening a task from its file, resuming a closed
 * conversation (in a new tab or an existing pane), the commands' answers reaching only the asker,
 * search finding a task by its title, and a background tab being shown by `SetActiveTab`.
 *
 * Tabs are made through a fake [TabOpener] so no terminal starts, except the `SetActiveTab` test,
 * which drives the real [WindowState] and closes what it opens.
 *
 * @see handleAgentCommand
 * @see openTaskError
 * @see resumeAgentError
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import se.soderbjorn.lunamux.persistence.AgentSessionRecord
import se.soderbjorn.lunamux.persistence.AgentSessionState
import se.soderbjorn.lunamux.persistence.SettingsRepository
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCommandsTest {
    private data class Opened(val folder: String, val title: String, val command: String?)

    private val dir = File.createTempFile("lunamux-commands", "").apply { delete(); mkdirs(); deleteOnExit() }
    private val repo = SettingsRepository(File(dir, "test.db"))
    private val files = MutableStateFlow<List<TaskFile>>(emptyList())
    private val tracker = AgentSessionTracker(repo, folderOf = { null }).also { it.start() }
    private val index = TaskIndex(repo, tracker, files, leafOfSession = { null })
    private val hooks = ClaudeHooks(File(dir, "settings.json"))
    private val overview = AgentOverview(
        repo, tracker, index, hooks,
        AgentsFeed(CoroutineScope(Dispatchers.Unconfined), tracker.summaries, index.state, hooks.connected),
    )
    private val opened = mutableListOf<Opened>()
    private val opener = TabOpener { f, t, c -> opened += Opened(f, t, c) }
    private val folder = File(dir, "app").apply { mkdirs() }

    private fun taskFile(id: String, open: TaskOpen?) = TaskFile(id, "Title $id", repo = null, open = open, modified = 5)

    private fun closedRecord(id: String = "r1", cwd: String? = folder.path, transcript: String? = null) = AgentSessionRecord(
        id = id, agent = "claude", agentSessionId = "conv-$id", source = "hooks", cwd = cwd, title = "Fix it",
        firstPrompt = "do it", transcriptPath = transcript,
        state = AgentSessionState.ENDED, stateSince = System.currentTimeMillis() - 1_000, endedAt = System.currentTimeMillis() - 1_000,
        endReason = "closed",
    ).also { repo.saveAgentSession(it) }

    // ── OpenTask ─────────────────────────────────────────────────────

    /** The three refusals say exactly what the person reads; nothing is opened for them. */
    @Test
    fun `open task answers in words when it cannot open`() {
        assertEquals("That task no longer exists.", openTaskError(index, "nope", opener))

        files.value = listOf(taskFile("plain", null), taskFile("gone", TaskOpen("claude", File(dir, "missing").path)))
        index.recompute()
        assertEquals("Title plain does not say how to open it.", openTaskError(index, "plain", opener))
        assertEquals(
            "The folder of Title gone no longer exists: ${File(dir, "missing").path}",
            openTaskError(index, "gone", opener),
        )
        assertTrue(opened.isEmpty())
    }

    /** A task opens a new tab in its file's folder, named like the task, with the file's command. */
    @Test
    fun `open task starts the file's command in the file's folder`() {
        files.value = listOf(taskFile("t", TaskOpen("claude --model opus", folder.path)))
        index.recompute()
        assertNull(openTaskError(index, "t", opener))
        assertEquals(listOf(Opened(folder.path, "Title t", "claude --model opus")), opened)
    }

    // ── ResumeAgent ──────────────────────────────────────────────────

    /** A closed conversation resumes in a new tab in its folder with `claude --resume <id>`. */
    @Test
    fun `resume opens a tab running claude --resume`() {
        closedRecord()
        val cmd = WindowCommand.ResumeAgent(recordId = "conv-r1")
        assertNull(resumeAgentError(overview, cmd, WindowConfig(), opener))
        assertEquals(listOf(Opened(folder.path, "Fix it", "claude --resume conv-r1")), opened)
    }

    /** What cannot be resumed is answered, in the words of [planResume]. */
    @Test
    fun `resume answers an error when it cannot`() {
        assertEquals("session no longer exists", resumeAgentError(overview, WindowCommand.ResumeAgent(recordId = "zzz"), WindowConfig(), opener))
        closedRecord("r2", cwd = null)
        assertEquals(
            "No folder was recorded for this session.",
            resumeAgentError(overview, WindowCommand.ResumeAgent(recordId = "conv-r2"), WindowConfig(), opener),
        )
        closedRecord("r3", transcript = File(dir, "gone.jsonl").path)
        assertEquals(
            "Its transcript is gone, so it can't be resumed.",
            resumeAgentError(overview, WindowCommand.ResumeAgent(recordId = "conv-r3"), WindowConfig(), opener),
        )
        assertTrue(opened.isEmpty())
    }

    /** Resuming a task resumes the conversation that speaks for it, using the task's open action when it fits. */
    @Test
    fun `resume of a task resumes its main conversation`() {
        closedRecord()
        files.value = listOf(taskFile("t", TaskOpen("claude --model opus", folder.path)))
        index.recompute()
        index.setMainConversation("t", "conv-r1")
        assertEquals("That task has no conversation to resume.", resumeAgentError(overview, WindowCommand.ResumeAgent(taskId = "other"), WindowConfig(), opener))
        assertNull(resumeAgentError(overview, WindowCommand.ResumeAgent(taskId = "t"), WindowConfig(), opener))
        assertEquals("claude --resume conv-r1 --model opus", opened.single().command)
    }

    // ── Answers go only to the asker ─────────────────────────────────

    private fun sentList(): MutableList<WindowEnvelope> = java.util.Collections.synchronizedList(mutableListOf())

    /** Wait until [sent] holds [count] answers: details and search are read off the socket's loop. */
    private fun awaitSent(sent: List<WindowEnvelope>, count: Int) {
        val end = System.currentTimeMillis() + 10_000
        while (sent.size < count && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue(sent.size >= count, "expected $count answers, got ${sent.size}")
    }

    private fun context(sent: MutableList<WindowEnvelope>) = WindowConnectionContext(
        send = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        settingsRepo = repo,
        usageMonitor = ClaudeUsageMonitor(),
        agents = overview,
    )

    /** A refused command is answered on the asking connection, naming what it was about. */
    @Test
    fun `an unknown task answers an error to the asker`() = runBlocking {
        val sent = sentList()
        handleAgentCommand(WindowCommand.OpenTask("nope"), context(sent), overview)
        assertEquals(listOf<WindowEnvelope>(WindowEnvelope.AgentError("nope", "That task no longer exists.")), sent)
    }

    /** Details of a conversation that is gone are an error, and of a closed one are an answer to the asker only. */
    @Test
    fun `read details answers the asker`() = runBlocking {
        closedRecord()
        val sent = sentList()
        val ctx = context(sent)
        handleAgentCommand(WindowCommand.ReadAgentDetails(recordId = "conv-r1"), ctx, overview)
        handleAgentCommand(WindowCommand.ReadAgentDetails(recordId = "zzz"), ctx, overview)
        awaitSent(sent, 2)
        sent.sortBy { it is WindowEnvelope.AgentError }
        val details = assertIs<WindowEnvelope.AgentDetails>(sent[0])
        assertEquals("r1", details.recordId)
        assertEquals(WindowEnvelope.AgentError("zzz", "That conversation no longer exists."), sent[1])
    }

    /** Switching the hooks off and on changes the connected flag the frame carries. */
    @Test
    fun `set claude hooks changes the flag in the frame`() = runBlocking {
        val sent = sentList()
        val ctx = context(sent)
        handleAgentCommand(WindowCommand.SetClaudeHooks(true), ctx, overview)
        assertTrue(hooks.connected.value)
        assertTrue(overview.feed.frame.value.contains("\"claudeConnected\":true"), overview.feed.frame.value)
        handleAgentCommand(WindowCommand.SetClaudeHooks(false), ctx, overview)
        assertTrue(!hooks.connected.value)
        assertTrue(sent.isEmpty(), "success is not answered; the frame says it")
    }

    // ── Search ───────────────────────────────────────────────────────

    /** A task's title finds its conversations, and a closed one carries what resuming would do. */
    @Test
    fun `search finds a conversation by its task's title and notes a vanished folder`() = runBlocking {
        val gone = File(dir, "gone/deeper")
        val rec = closedRecord("r9", cwd = gone.path).copy(taskId = "tk")
        repo.saveAgentSession(rec)
        files.value = listOf(taskFile("tk", null).copy(title = "Zebra migration"))
        index.recompute()
        val reloaded = AgentSessionTracker(repo, folderOf = { null }).also { it.start() }
        val hits = AgentSessionSearch.search(
            repo, reloaded, "zebra",
            taskTitleOf = { r -> index.taskIdOf(r)?.let(index::titleOf) },
            resumeNoteOf = { r -> resumeNoteOf(r, index.openOfMain(r)) },
        )
        val hit = hits.single()
        assertEquals("task", hit.field)
        assertEquals("Zebra migration", hit.taskTitle)
        assertTrue(hit.resumeNote!!.startsWith("Its folder is gone."), hit.resumeNote)
    }

    /** A search is answered to the asker with the query it was for, and a short query finds nothing. */
    @Test
    fun `search answers the asker with its query`() = runBlocking {
        closedRecord("r5").also { repo.saveAgentSession(it.copy(title = "Zebra crossing")) }
        val sent = sentList()
        val ctx = context(sent)
        handleAgentCommand(WindowCommand.SearchAgents("zebra"), ctx, overview)
        handleAgentCommand(WindowCommand.SearchAgents("z"), ctx, overview)
        awaitSent(sent, 2)
        val byQuery = sent.filterIsInstance<WindowEnvelope.AgentMatches>().associateBy { it.query }
        assertEquals(listOf("conv-r5"), byQuery.getValue("zebra").matches.map { it.summary.id })
        assertTrue(byQuery.getValue("z").matches.isEmpty())
    }

    // ── SetActiveTab on a background tab ─────────────────────────────

    /** A background tab with no live agent in it is refused with an error naming the tab, and shown otherwise. */
    @Test
    fun `set active tab on a background tab shows it or answers an error`() = runBlocking {
        val bg = WindowState.addTab(background = true)
        try {
            val sent = sentList()
            handleWindowCommand(windowJson.encodeToString<WindowCommand>(WindowCommand.SetActiveTab(bg)), context(sent))
            val err = assertIs<WindowEnvelope.AgentError>(sent.single())
            assertEquals(bg, err.about)
            assertNull(WindowState.config.value.shownTabId)
        } finally {
            WindowState.leaveShownTab()
            WindowState.closeTab(bg)
        }
    }

    /** A window never closes a background tab: only the agent that owns it does, over MCP. */
    @Test
    fun `a window cannot close a background tab`() = runBlocking {
        val bg = WindowState.addTab(background = true)
        try {
            handleWindowCommand(windowJson.encodeToString<WindowCommand>(WindowCommand.CloseTab(bg)), context(sentList()))
            assertTrue(WindowState.config.value.worlds.flatMap { it.tabs }.any { it.id == bg }, "the background tab is still there")
        } finally {
            WindowState.closeTab(bg)
        }
    }
}
