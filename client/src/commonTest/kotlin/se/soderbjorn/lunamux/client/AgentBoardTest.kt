/**
 * Tests for the agents board: the rows it builds from one `Agents` frame and one layout, what
 * Return does on each, how the counts add up, and that a repeated frame or a reconnect changes
 * nothing a screen draws.
 *
 * Contains [AgentBoardTest].
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentBoardTest {
    private val minute = 60_000L
    private val now = 100L * 24 * 60 * minute

    private fun pane(leaf: String) = Pane(LeafNode(id = leaf, sessionId = "s-$leaf", title = leaf), 0.0, 0.0, 1.0, 1.0, 0)

    private fun layout(
        listed: List<String> = emptyList(),
        background: List<String> = emptyList(),
        shown: String? = null,
        tabName: String = "one",
    ) = WindowConfig(
        tabs = listOfNotNull(
            TabConfig("tab-1", tabName, panes = listed.map { pane("leaf-$it") }),
            TabConfig("tab-bg", "agents", panes = background.map { pane("leaf-$it") }, isBackground = true).takeIf { background.isNotEmpty() },
        ),
        activeTabId = "tab-1",
        shownTabId = shown,
    )

    private fun s(
        id: String,
        shown: Shown = Shown.WORKING,
        taskId: String? = null,
        since: Long = now - 10 * minute,
        line: String? = null,
        ended: String? = null,
        placed: Boolean = true,
        resumable: Boolean = false,
        listed: Boolean = true,
        inTab: Boolean = false,
    ) = AgentSessionSummary(
        id = id, agent = "claude", shown = shown, since = since, title = "session $id", line = line, ended = ended,
        listed = listed, resumable = resumable, inTab = inTab, taskId = taskId, leafId = "leaf-$id".takeIf { placed },
        startedAt = since, repo = "proj",
    )

    private fun task(id: String = "g1", main: String? = null, status: String? = null, question: Boolean = false, paused: Boolean = false, canOpen: Boolean = false) =
        AgentTask(id = id, title = "Release", repo = "proj", main = main, status = status, questionWaits = question, pausedAt = if (paused) now - minute else null, canOpen = canOpen)

    private fun board(frame: WindowEnvelope.Agents, config: WindowConfig, previous: AgentBoard = AgentBoard()) = buildBoard(frame, config, now, previous)

    private fun frame(vararg all: AgentSessionSummary, tasks: List<AgentTask> = emptyList()) = WindowEnvelope.Agents(all.toList(), tasks)

    @Test
    fun aTaskIsOneRowAndItsHelpersHaveNoRowOfTheirOwn() {
        val b = board(
            frame(s("main", taskId = "g1", line = "Editing"), s("b1", taskId = "g1"), s("solo", Shown.DONE), tasks = listOf(task(main = "main"))),
            layout(listed = listOf("main", "solo"), background = listOf("b1")),
        )
        assertEquals(setOf("task:g1", "solo"), b.works.map { it.id }.toSet())
        assertEquals(listOf("Main conversation", "session b1"), b.work("task:g1")!!.agents.map { it.title })
    }

    @Test
    fun aTaskReadsNeedsYouForOneAndCountsForSeveral() {
        val one = board(frame(s("a", Shown.NEEDS_YOU, "g1"), s("b", Shown.WORKING, "g1"), tasks = listOf(task(main = "b"))), layout(listOf("a", "b")))
        assertEquals("needs you", one.work("task:g1")!!.word)
        val two = board(frame(s("a", Shown.NEEDS_YOU, "g1"), s("b", Shown.NEEDS_YOU, "g1"), tasks = listOf(task(main = "b"))), layout(listOf("a", "b")))
        assertEquals("2 need you", two.work("task:g1")!!.word)
    }

    @Test
    fun aClosedMainWithAQuestionWaitingStaysInNowAndReadsDoneUncounted() {
        val b = board(
            frame(s("m", Shown.CLOSED, "g1", ended = "exit", placed = false, resumable = true), tasks = listOf(task(main = "m", status = "Which one?", question = true))),
            layout(),
        )
        val w = b.work("task:g1")!!
        assertTrue(w.inNow)
        assertEquals("done", w.word)
        assertEquals(0, b.tally.needsYou)
    }

    @Test
    fun aTaskWhoseMainAsksThroughItsStatusCountsAsNeedingYouInTheFooterAndEveryWorld() {
        val b = board(
            frame(s("m", Shown.DONE, "g1"), tasks = listOf(task(main = "m", status = "Which one?", question = true))),
            layout(listed = listOf("m")),
        )
        assertTrue(b.work("task:g1")!!.asks)
        assertEquals(1, b.tally.needsYou)
        assertEquals(1, b.tallyByWorld.getValue("").needsYou)
    }

    @Test
    fun aPausedTaskSaysSoAndGivesWayToWorkThatRuns() {
        val idle = board(frame(s("m", Shown.DONE, "g1"), tasks = listOf(task(main = "m", paused = true))), layout(listOf("m")))
        assertEquals(Shown.PAUSED, idle.work("task:g1")!!.shown)
        val busy = board(frame(s("m", Shown.WORKING, "g1"), tasks = listOf(task(main = "m", paused = true))), layout(listOf("m")))
        assertEquals(Shown.WORKING, busy.work("task:g1")!!.shown)
    }

    @Test
    fun aBackgroundConversationGetsARowOnlyWhileItAsksAndReturnShowsItsTab() {
        val quiet = board(frame(s("bg", Shown.WORKING)), layout(background = listOf("bg")))
        assertNull(quiet.work("bg"))
        val asks = board(frame(s("bg", Shown.NEEDS_YOU)), layout(background = listOf("bg")))
        val w = asks.work("bg")!!
        assertEquals(Enter.SHOW, w.enter)
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-bg")), asks.enter(w))
        assertEquals(1, asks.tally.needsYou)
    }

    @Test
    fun returnOnALiveRowJumpsToItsPane() {
        val b = board(frame(s("a")), layout(listOf("a")))
        assertEquals(
            listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-a")),
            b.enter(b.work("a")!!),
        )
    }

    @Test
    fun returnOnATaskGoesToTheHelperThatNeedsYouBeforeItsMain() {
        val b = board(
            frame(s("main", Shown.WORKING, "g1"), s("helper", Shown.NEEDS_YOU, "g1"), tasks = listOf(task(main = "main"))),
            layout(listOf("main", "helper")),
        )
        assertEquals("helper", b.work("task:g1")!!.targetId)
    }

    @Test
    fun aLiveConversationNobodyCanPlaceStillCountsAndSaysWhyReturnDoesNothing() {
        val b = board(frame(s("lost", Shown.NEEDS_YOU, placed = false)), layout())
        val w = b.work("lost")!!
        assertEquals(Enter.NONE, w.enter)
        assertNotNull(w.why)
        assertTrue(b.enter(w).isEmpty())
        assertEquals(1, b.tally.needsYou)
    }

    @Test
    fun aClosedConversationWithNoTranscriptSaysThereIsNothingToResume() {
        val b = board(frame(s("old", Shown.CLOSED, ended = "exit", placed = false, resumable = false)), layout())
        val w = b.work("old")!!
        assertEquals(Enter.NONE, w.enter)
        assertEquals("nothing to resume: its transcript is gone", w.why)
    }

    @Test
    fun aClosedConversationThatSaidNothingIsNotListedWhileALiveOneIs() {
        val b = board(
            frame(s("quiet", Shown.CLOSED, ended = "exit", placed = false, listed = false), s("live", Shown.DONE, listed = false)),
            layout(listOf("live")),
        )
        assertNull(b.work("quiet"))
        assertNotNull(b.work("live"))
    }

    @Test
    fun whatTheRestartStoppedIsMarkedStoppedAndResumesInItsTabWhenItIsStillThere() {
        val b = board(
            frame(s("cut", Shown.STOPPED, ended = "restart", since = now - 2 * 60 * minute, placed = false, resumable = true, inTab = true)),
            layout(),
        )
        val w = b.work("cut")!!
        assertTrue(w.stopped)
        assertEquals("cut off", w.word)
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgent(recordId = "cut", inTab = true)), b.enter(w))
        val old = buildBoard(frame(s("cut", Shown.STOPPED, ended = "restart", since = now - 30 * 60 * minute, placed = false, resumable = true)), layout(), now)
        assertEquals(false, old.work("cut")!!.stopped)
    }

    @Test
    fun aRowLeadsWithItsTabsNameUnlessTheTabIsUnnamedHiddenOrSaysTheTitleAlready() {
        assertEquals("one", board(frame(s("a")), layout(listOf("a"))).work("a")!!.tabName)
        assertNull(board(frame(s("a")), layout(listOf("a"), tabName = "Tab 3")).work("a")!!.tabName)
        assertNull(board(frame(s("a")), layout(listOf("a"), tabName = "SESSION a")).work("a")!!.tabName)
        assertNull(board(frame(s("bg", Shown.NEEDS_YOU)), layout(background = listOf("bg"))).work("bg")!!.tabName)
        assertEquals("agents", board(frame(s("bg", Shown.NEEDS_YOU)), layout(background = listOf("bg"), shown = "tab-bg")).work("bg")!!.tabName)
    }

    @Test
    fun theCountsAddUp() {
        val b = board(
            frame(s("n", Shown.NEEDS_YOU), s("w1"), s("w2"), s("h", Shown.WAITING), s("d", Shown.DONE)),
            layout(listOf("n", "w1", "w2", "h", "d")),
        )
        assertEquals(Tally(needsYou = 1, working = 2, waiting = 1, paused = 0, done = 1), b.tally)
    }

    @Test
    fun aRepeatedFrameAndAReconnectLeaveTheBoardTheSubjectAndEveryRowUnchanged() {
        val frame = frame(s("main", Shown.WORKING, "g1", line = "Editing"), s("solo", Shown.NEEDS_YOU), tasks = listOf(task(main = "main")))
        val config = layout(listOf("main", "solo")).let { it.copy(tabs = it.tabs.map { t -> t.copy(focusedPaneId = "leaf-main") }) }
        val repo = WindowStateRepository { now }
        repo.updateConfig(config)
        repo.updateAgents(frame)
        val first = repo.agentBoard.value
        val seen = mutableListOf<AgentBoard>()
        // The board a reader holds, and every later one a reader would be handed.
        seen += first
        repeat(3) { repo.updateAgents(frame.copy()); seen += repo.agentBoard.value }
        // A reconnect re-sends the layout and then the frame; between them the board is the old one.
        repo.updateConfig(config.copy())
        seen += repo.agentBoard.value
        repo.updateAgents(frame.copy())
        seen += repo.agentBoard.value
        seen.forEach { assertEquals(first, it) }
        assertEquals(first.works.map { it.id }, seen.last().works.map { it.id })
        assertEquals("task:g1", first.subjectOf("tab-1")!!.id)
        assertTrue(seen.all { it.subjectOf("tab-1") == first.subjectOf("tab-1") })
    }

    @Test
    fun theSubjectOnlyMovesWhenTheFocusMovesOrItsMembersLeaveTheTab() {
        val config = layout(listOf("main", "solo")).let { it.copy(tabs = it.tabs.map { t -> t.copy(focusedPaneId = "leaf-main") }) }
        val a = board(frame(s("main", Shown.WORKING, "g1"), s("solo", Shown.WORKING), tasks = listOf(task(main = "main"))), config)
        assertEquals("task:g1", a.subjectOf("tab-1")!!.id)
        // A change of state never moves it.
        val b = buildBoard(frame(s("main", Shown.DONE, "g1"), s("solo", Shown.NEEDS_YOU), tasks = listOf(task(main = "main"))), config, now, a)
        assertEquals("task:g1", b.subjectOf("tab-1")!!.id)
        // Focusing the other conversation moves it.
        val moved = config.copy(tabs = config.tabs.map { it.copy(focusedPaneId = "leaf-solo") })
        val c = buildBoard(frame(s("main", Shown.DONE, "g1"), s("solo", Shown.NEEDS_YOU), tasks = listOf(task(main = "main"))), moved, now, b)
        assertEquals("solo", c.subjectOf("tab-1")!!.id)
        // The conversation ending leaves the pane, so the subject stays.
        val d = buildBoard(frame(s("main", Shown.DONE, "g1"), s("solo", Shown.CLOSED, ended = "exit"), tasks = listOf(task(main = "main"))), moved, now, c)
        assertEquals("solo", d.subjectOf("tab-1")?.id)
    }

    @Test
    fun anUnknownStateLooksLikeDone() {
        assertEquals("done", Shown.UNKNOWN.look.word)
        assertEquals("2 agents working", Shown.WORKING.look.count(2))
        assertEquals("paused", Shown.PAUSED.look.count(2))
    }
}
