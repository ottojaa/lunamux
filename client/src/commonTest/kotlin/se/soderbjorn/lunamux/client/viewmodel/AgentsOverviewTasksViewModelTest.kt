/**
 * Tests for the task part of [AgentsOverviewBackingViewModel]: every task is one row, and a
 * session in no task is a task of one. They cover where a task sits (it stays in NOW while it has
 * open work and goes to EARLIER once every session reads done or ended), the state word of a row
 * ("needs you", "N working", "working", "done", "finished") and the detail and extra line behind
 * it, what ↵ opens in every branch (the task's own session, a live session in a listed tab, the
 * task's `open` action, a watch of a background session, a resume that asks first, nothing), ⌘↵ and → on a task,
 * which rows ⌘⌫ may close, how a search folds the sessions of one task into one row, the selection
 * staying on its row, the stable order of NOW, and that a working session counts as working however long it has been silent.
 * A task's sessions are the sessions that carry its id; the task file only declares the task.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsOverviewTasksViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once) and rethrows its failure. */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private var now = 10_000_000L
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { sent += it }

    private fun session(
        id: String, state: String, since: Long = 1_000, endedAt: Long? = null, title: String = "session $id",
        repo: String? = "proj", cwd: String = "/work/proj", links: List<String> = emptyList(),
        nowLine: String? = null, question: String? = null, lastActivityAt: Long? = null,
        lastSaid: String? = null, firstPrompt: String? = null, cwdHome: Boolean = false, taskId: String? = null,
        startedAt: Long? = null, background: Boolean = false, cwdShown: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, links = links,
        repo = repo, branch = null, cwd = cwd, cwdHome = cwdHome, cwdShown = cwdShown, nowLine = nowLine, question = question,
        leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt, lastActivityAt = lastActivityAt,
        lastSaid = lastSaid, firstPrompt = firstPrompt, taskId = taskId, startedAt = startedAt, background = background,
    )

    /** Set once a test lays out its own tabs; [show] then leaves the layout alone. */
    private var laidOut = false

    private fun panes(ids: List<String>) =
        ids.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }

    private fun layout(vararg leafIds: String) {
        laidOut = true
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes(leafIds.toList()))), activeTabId = "tab-1"))
    }

    /** Lays out [first] in the listed tab 1 and [second] in the listed tab 2. */
    private fun layoutTwoTabs(first: List<String>, second: List<String>) {
        laidOut = true
        windowState.updateConfig(
            WindowConfig(
                tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes(first)), TabConfig(id = "tab-2", title = "two", panes = panes(second))),
                activeTabId = "tab-1",
            ),
        )
    }

    /** Lays out [normal] panes in the one listed tab and [background] panes in a background tab of their own. */
    private fun layoutWithBackground(normal: List<String>, background: List<String>) {
        laidOut = true
        windowState.updateConfig(
            WindowConfig(
                tabs = listOf(
                    TabConfig(id = "tab-1", title = "one", panes = panes(normal)),
                    TabConfig(id = "tab-bg", title = "agents", panes = panes(background), isBackground = true),
                ),
                activeTabId = "tab-1",
            ),
        )
    }

    /** A task as the server pushes it: the sessions that carry [id] are its members and are passed to [show]. */
    private fun task(
        id: String = "g1", hasOpen: Boolean = false, source: String = "file", repo: String? = "proj",
        startedAt: Long? = null, mainConversation: String? = null, status: String? = null,
    ) = AgentTask(
        id = id, title = "Release $id", repo = repo, source = source, hasOpen = hasOpen,
        startedAtMillis = startedAt, mainConversation = mainConversation, status = status,
    )

    /** A task whose sessions are all hosted ones that carry its id, with [main] as its own session. */
    private fun hostedTask(main: String? = null, hasOpen: Boolean = false, status: String? = null) =
        task(source = "agents", hasOpen = hasOpen, mainConversation = main, status = status)

    /**
     * Shows [tasks] and [sessions]. Unless the test laid out its own tab, every session with a pane
     * is put in one open tab, as NOW lists only sessions in open tabs; [showAway] leaves them out.
     */
    private fun show(tasks: List<AgentTask>, vararg sessions: AgentSessionSummary) {
        if (!laidOut) layout(*sessions.mapNotNull { it.leafId }.toTypedArray()).also { laidOut = false }
        showAway(tasks, *sessions)
    }

    /** Shows [tasks] and [sessions] without touching the layout, so sessions are in an open tab only if the test put them there. */
    private fun showAway(tasks: List<AgentTask>, vararg sessions: AgentSessionSummary) {
        windowState.updateAgentSessions(sessions.toList(), true)
        windowState.updateAgentTasks(tasks)
    }

    /** The one row with [id]. */
    private fun row(id: String) = vm.state.rows.single { it.id == id }

    /** The jump to [leaf] in the listed tab [tab]. */
    private fun jumpTo(leaf: String, tab: String = "tab-1") =
        listOf(WindowCommand.SetActiveTab(tab), WindowCommand.SetFocusedPane(tab, leaf))

    // ---- placement ----

    @Test
    fun aTaskSitsInNowWhereItStartedWhateverStateItsSessionsAreIn() {
        show(
            listOf(task(startedAt = 2_000)),
            session("a", "needs_you", since = 2_000, taskId = "g1"),
            session("b", "working", since = 100, taskId = "g1"),
            session("old-working", "working", since = 500),
            session("late-needs", "needs_you", since = 3_000),
            session("early-needs", "needs_you", since = 1_000),
        )
        assertEquals(listOf("old-working", "early-needs", "task:g1", "late-needs"), vm.state.now.map { it.id })
    }

    @Test
    fun aTaskWhoseSessionsHaveNoPaneMovesToEarlierEvenThoughNoneEnded() {
        layout("leaf-busy")
        show(
            listOf(task(id = "g1"), task(id = "g2")),
            // clocked an hour back, past the ten minutes a task that just finished stays in NOW
            session("a", "done", since = now - HOUR, taskId = "g1"),
            session("b", "done", since = now - HOUR, taskId = "g1"),
            session("c", "done", since = now - HOUR, taskId = "g2"),
            session("d", "ended", since = now - HOUR, endedAt = now - HOUR, taskId = "g2"),
            session("busy", "working", since = 1),
        )
        assertEquals(listOf("busy"), vm.state.now.map { it.id })
        assertEquals(setOf("task:g1", "task:g2"), vm.state.earlier.map { it.id }.toSet())
    }

    @Test
    fun aTaskThatFinishedIsInEarlierAtOnceAndReadsFinished() {
        val ended = now - MINUTE
        show(
            listOf(
                task(id = "file"),
                task(id = "closed"),
                task(id = "oldFile"),
                task(id = "hosted", source = "agents"),
            ),
            session("a", "ended", since = ended, endedAt = ended, taskId = "file"),
            session("b", "ended", since = ended - 5_000, endedAt = ended - 5_000, taskId = "file"),
            session("c", "ended", since = ended, endedAt = ended, taskId = "closed"),
            session("d", "ended", since = ended - 5_000, endedAt = ended - 5_000, taskId = "closed"),
            session("e", "ended", since = now - HOUR, endedAt = now - HOUR, taskId = "oldFile"),
            session("f", "ended", since = now - HOUR, endedAt = now - HOUR, taskId = "oldFile"),
            session("h1", "ended", since = ended, endedAt = ended, taskId = "hosted"),
            session("h2", "ended", since = ended - 5_000, endedAt = ended - 5_000, taskId = "hosted"),
        )
        assertTrue(vm.state.now.isEmpty(), "no conversation is open, so nothing waits in NOW for a grace to pass")
        assertEquals(setOf("task:file", "task:closed", "task:hosted", "task:oldFile"), vm.state.earlier.map { it.id }.toSet())
        assertEquals(setOf("finished"), vm.state.earlier.map { it.stateWord }.toSet())
        assertEquals("task:oldFile", vm.state.earlier.last().id, "newest first")
    }
    @Test
    fun aTaskWhoseSessionsAllEndedMovesToEarlierAmongTheOtherUnitsNewestFirst() {
        show(
            listOf(
                task(id = "g1"),
                task(id = "g2", hasOpen = true),
            ),
            session("a", "ended", since = 4_000, endedAt = 4_000, taskId = "g1"),
            session("b", "ended", since = 2_000, endedAt = 2_000, taskId = "g1"),
            session("idle-open", "done", since = 1),
            session("closed-new", "ended", endedAt = 5_000),
            session("closed-old", "ended", endedAt = 3_000),
        )
        assertEquals(listOf("idle-open"), vm.state.now.map { it.id }, "a done conversation in an open pane is in NOW")
        assertEquals(
            listOf("closed-new", "task:g1", "closed-old", "task:g2"),
            vm.state.earlier.map { it.id },
        )
    }

    @Test
    fun aTaskIsOneUnitInTheEarlierLimit() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        val tasks = (1..6).map { task(id = "g$it") }
        val sessions = (1..6).flatMap {
            listOf(
                session("m$it", "ended", endedAt = now - 5 * DAY + it, taskId = "g$it"),
                session("n$it", "ended", endedAt = now - 5 * DAY + it, taskId = "g$it"),
            )
        }
        show(tasks, *sessions.toTypedArray())
        assertTrue(vm.state.recent.isEmpty())
        assertEquals(EARLIER_LIMIT, vm.state.fortnight.size)
        assertEquals(1, vm.state.earlierHidden)
    }

    @Test
    fun theFirstBandTakesWhatIsAtMostThreeDaysOldAndTheSecondUpToFourteenWithBothEdgesInclusive() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        show(
            emptyList(),
            session("three", "ended", endedAt = now - 3 * DAY),
            session("over-three", "ended", endedAt = now - 3 * DAY - 1),
            session("fourteen", "ended", endedAt = now - 14 * DAY),
            session("over-fourteen", "ended", endedAt = now - 14 * DAY - 1),
            session("fresh", "ended", endedAt = now - HOUR), // past the ten minutes a just-ended session stays under NOW
        )
        assertEquals(listOf("fresh", "three"), vm.state.recent.map { it.id })
        assertEquals(listOf("over-three", "fourteen"), vm.state.fortnight.map { it.id })
        assertEquals(listOf("fresh", "three", "over-three", "fourteen"), vm.state.rows.map { it.id }, "nothing older is listed")
    }

    @Test
    fun aTasksRecencyIsItsNewestSessionSoOneRecentSessionKeepsTheWholeTaskInTheFirstBand() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        show(
            listOf(
                task(id = "mixed"),
                task(id = "slow"),
                task(id = "ancient"),
            ),
            session("mixed-old", "ended", endedAt = now - 20 * DAY, taskId = "mixed"),
            session("mixed-new", "ended", endedAt = now - DAY, taskId = "mixed"),
            session("slow-a", "ended", endedAt = now - 5 * DAY, taskId = "slow"),
            session("slow-b", "ended", endedAt = now - 10 * DAY, taskId = "slow"),
            session("ancient-a", "ended", endedAt = now - 20 * DAY, taskId = "ancient"),
            session("ancient-b", "ended", endedAt = now - 30 * DAY, taskId = "ancient"),
        )
        assertEquals(listOf("task:mixed"), vm.state.recent.map { it.id })
        assertEquals(listOf("task:slow"), vm.state.fortnight.map { it.id })
        assertEquals(now - DAY, row("task:mixed").sinceMillis)
        assertEquals(now - 5 * DAY, row("task:slow").sinceMillis)
        assertFalse("task:ancient" in vm.state.rows.map { it.id }, "older than two weeks is only found by search")
    }

    @Test
    fun theEarlierLimitCountsRowsAcrossBothBandsAndMoreRevealsTheRest() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        val recent = (1..3).map { session("r$it", "ended", endedAt = now - HOUR - it * 1_000L) }
        val older = (1..7).map { session("o$it", "ended", endedAt = now - 5 * DAY - it) }
        show(emptyList(), *(recent + older).toTypedArray())
        assertEquals(3, vm.state.recent.size, "the newest rows come first, so the first band is whole here")
        assertEquals(EARLIER_LIMIT - 3, vm.state.fortnight.size, "the second band takes what is left of the five")
        assertEquals(5, vm.state.earlierHidden)
        vm.showMoreEarlier()
        assertEquals(7, vm.state.fortnight.size)
        assertEquals(0, vm.state.earlierHidden)
    }

    @Test
    fun theDownArrowCrossesFromTheFirstBandToTheSecondAndEarlierRowsOfEndedSessionsResume() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        layout()
        show(
            listOf(task(id = "g1")),
            session("s-a", "ended", endedAt = now - DAY, taskId = "g1"),
            session("s-b", "ended", endedAt = now - 2 * DAY, taskId = "g1"),
            session("later", "ended", endedAt = now - 6 * DAY),
        )
        assertEquals(listOf("task:g1"), vm.state.recent.map { it.id })
        assertEquals(listOf("later"), vm.state.fortnight.map { it.id })
        assertEquals(listOf(Enter.RESUME, Enter.RESUME), vm.state.earlier.map { it.enter })
        vm.select("task:g1")
        vm.moveSelection(1)
        assertEquals("later", vm.state.selectedId, "↓ from the last row of band 1 lands on the first of band 2")
        vm.moveSelection(-1)
        assertEquals("task:g1", vm.state.selectedId)
    }

    // ---- the footer count ----

    @Test
    fun theFooterCountsEverySessionWithAPaneOnceWhetherOrNotATaskHoldsIt() {
        layoutWithBackground(listOf("leaf-a", "leaf-b"), listOf("leaf-c", "leaf-d"))
        show(
            listOf(hostedTask(main = "a")),
            session("a", "needs_you", taskId = "g1"),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("c", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
            session("d", "needs_you", background = true),
            session("no-pane", "needs_you"),
        )
        assertEquals(2, vm.state.needYouCount, "a and d; the session with no pane is nobody's to answer")
        assertEquals(2, vm.state.workingCount, "b and c, one of them in a background tab")
    }

    @Test
    fun aTaskRowCountsASilentSessionAsWorking() {
        layout("leaf-w1", "leaf-w2")
        show(
            listOf(task()),
            session("w1", "working", taskId = "g1", lastActivityAt = now - MINUTE),
            session("w2", "working", taskId = "g1", lastActivityAt = now - 6 * MINUTE),
        )
        assertEquals("2 working", row("task:g1").stateWord, "both work, the silent one as much as the other")
        assertEquals(2, vm.state.workingCount)
    }

    // ---- state words ----

    @Test
    fun aTaskWithASessionWaitingOnYouReadsNeedsYouWithTheMarkerAndItsQuestionInQuotes() {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task()),
            session("r1", "needs_you", taskId = "g1", question = "Which branch?"),
            session("r2", "working", taskId = "g1", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
        )
        val r = row("task:g1")
        assertEquals("needs you", r.stateWord)
        assertEquals("◆", r.marker)
        assertEquals("\"Which branch?\"", r.detail)
        assertTrue(r.needsYou)
    }

    @Test
    fun oneMovingSessionReadsWorkingAndSeveralReadNWorkingBackgroundSessionsCountedToo() {
        layoutWithBackground(listOf("leaf-n", "leaf-only"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(main = "n", status = "Notes drafted"), task(id = "g2", source = "agents")),
            session("n", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("bg", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
            session("only", "working", taskId = "g2", lastActivityAt = now - 1_000),
        )
        assertEquals("2 working", row("task:g1").stateWord, "the background session counts although it is no row")
        assertEquals("working", row("task:g2").stateWord)
        assertEquals("", row("task:g1").marker)
        assertEquals(listOf("task:g1", "task:g2"), vm.state.rows.map { it.id }.filter { it.startsWith("task:") })
    }

    @Test
    fun aTaskInNowWithNothingMovingOrWaitingReadsDoneAndOneInEarlierReadsFinished() {
        layout("leaf-a", "leaf-chat")
        show(
            listOf(
                task(id = "review"),
                task(id = "closed"),
            ),
            session("a", "done", taskId = "review"),
            session("c", "ended", since = now - HOUR, endedAt = now - HOUR, taskId = "closed"),
            session("d", "ended", since = now - HOUR, endedAt = now - HOUR, taskId = "closed"),
            session("chat", "done", since = now - MINUTE),
            session("old", "ended", endedAt = now - HOUR),
        )
        assertEquals("done", row("task:review").stateWord)
        assertEquals("finished", row("task:closed").stateWord)
        assertEquals("done", row("chat").stateWord, "a finished conversation in an open tab is in NOW")
        assertEquals("finished", row("old").stateWord)
        assertTrue(row("old").finished)
        assertFalse(row("chat").finished)
    }

    @Test
    fun aLoneSessionHasTheSameStateWordsAndNoStatus() {
        layout("leaf-n", "leaf-w", "leaf-i")
        show(
            emptyList(),
            session("n", "needs_you", question = "Which branch?"),
            session("w", "working", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("i", "done", since = now - MINUTE),
        )
        assertEquals(listOf("needs you", "working", "done"), listOf("n", "w", "i").map { row(it).stateWord })
        assertEquals(listOf("\"Which branch?\"", "Edit a.kt", ""), listOf("n", "w", "i").map { row(it).detail })
        assertEquals("◆", row("n").marker)
        assertEquals("", row("w").marker)
        assertTrue(listOf("n", "w", "i").all { row(it).taskId == null })
    }

    // ---- detail ----

    @Test
    fun theDetailIsTheStatusThenTheQuotedQuestionThenTheMovingSessionsLineThenNothing() {
        show(
            listOf(
                task(id = "status", status = "waiting on the schema review"),
                task(id = "question"),
                task(id = "line"),
                task(id = "empty"),
            ),
            session("r1", "needs_you", taskId = "status", question = "Which branch?"),
            session("r2", "working", taskId = "status", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("r3", "needs_you", taskId = "question", question = "Which branch?"),
            session("r3b", "working", taskId = "question", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("r4", "working", taskId = "line", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("r5", "done", taskId = "empty"),
        )
        assertEquals("waiting on the schema review", row("task:status").detail)
        assertEquals("\"Which branch?\"", row("task:question").detail)
        assertEquals("Edit a.kt", row("task:line").detail)
        assertEquals("", row("task:empty").detail)
    }

    // ---- extra ----

    @Test
    fun theExtraLineHasNoTabAndTheDetailStaysAFieldOfItsOwn() {
        layoutTwoTabs(listOf("leaf-x"), listOf("leaf-a"))
        show(
            listOf(hostedTask()),
            session("x", "working", nowLine = "Edit x.kt", lastActivityAt = now - 1_000),
            session("a", "needs_you", taskId = "g1", question = "Which branch?"),
        )
        assertEquals("", row("task:g1").extra)
        assertEquals("", row("x").extra)
        assertEquals("\"Which branch?\"", row("task:g1").detail, "the detail stays a field of its own; the view repeats it only when it cuts it")
        assertEquals("Edit x.kt", row("x").detail)
    }

    @Test
    fun enterOnATaskWhoseOwnSessionIsLiveJumpsToItsPane() = runNow {
        layout("leaf-main", "leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "done", taskId = "g1", since = 1_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        assertEquals(Enter.JUMP, vm.state.selectedRow?.enter)
        vm.activateSelected()
        assertEquals(jumpTo("leaf-main"), sent)
    }

    @Test
    fun enterOnATaskWhoseOwnSessionIsGoneResumesItWhenThereIsNothingToOpen() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", taskId = "g1", since = 500, endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        val r = row("task:g1")
        assertEquals(Enter.RESUME, r.enter)
        assertFalse(r.isLive)
        assertEquals("main", r.recordId)
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionJumpsToTheLiveSessionInAListedTabThatWaitedLongest() = runNow {
        layout("leaf-r1", "leaf-r2", "leaf-r3")
        show(
            listOf(task()),
            session("r1", "needs_you", since = 2_000, taskId = "g1"),
            session("r2", "needs_you", since = 1_000, taskId = "g1"),
            session("r3", "working", taskId = "g1", lastActivityAt = 9_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.JUMP, r.enter)
        assertEquals("r2", r.recordId)
        vm.activate(r)
        assertEquals(jumpTo("leaf-r2"), sent)
    }

    @Test
    fun aClosedMainConversationDoesNotHideAWaitingMemberInAListedTab() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", taskId = "g1", since = 500, endedAt = now - 5_000),
            session("m", "needs_you", taskId = "g1", since = 2_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.JUMP, r.enter)
        assertEquals("m", r.recordId)
    }

    @Test
    fun aTaskWithNoOwnSessionAndNobodyWaitingJumpsToTheWorkingSessionWithTheFreshestSignOfLife() = runNow {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task()),
            session("r1", "working", taskId = "g1", lastActivityAt = 7_000),
            session("r2", "working", taskId = "g1", lastActivityAt = 9_000),
        )
        vm.activate(row("task:g1"))
        assertEquals(jumpTo("leaf-r2"), sent)
    }

    @Test
    fun aTaskWithNoMainConversationOpensAWaitingBackgroundMemberAsATabBeforeAnythingElse() = runNow {
        layoutWithBackground(listOf("leaf-listed"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(hasOpen = true)),
            session("bg", "needs_you", taskId = "g1", since = 1_000, background = true),
            session("listed", "working", taskId = "g1", since = 2_000, lastActivityAt = now - 1_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.SHOW_WAITING, r.enter)
        assertEquals("bg", r.recordId)
        vm.activate(r)
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("bg")), sent)
    }
    @Test
    fun aTaskWithNothingLiveAndNoOpenActionResumesItsNewestSession() = runNow {
        layout("leaf-other")
        show(
            listOf(hostedTask()),
            session("older", "ended", taskId = "g1", since = 100, endedAt = now - 2 * HOUR),
            session("newer", "ended", taskId = "g1", since = 200, endedAt = now - HOUR),
        )
        val r = row("task:g1")
        assertEquals(Enter.RESUME, r.enter)
        assertEquals("newer", r.recordId)
        vm.activate(r)
        vm.activate(r)
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("newer")), sent)
    }

    @Test
    fun aTaskWhoseOpenActionIsTheOnlyWayInRunsItAndAnEndedSessionIsNeverTheJumpTarget() = runNow {
        layout("leaf-w", "leaf-other")
        show(
            listOf(
                task(id = "g1", hasOpen = true),
                task(id = "g2", hasOpen = true),
            ),
            session("r1", "ended", endedAt = 5_000, taskId = "g1"),
            session("w", "working", taskId = "g1"),
        )
        assertEquals("w", row("task:g1").recordId)
        assertEquals(Enter.OPEN, row("task:g2").enter)
        vm.activate(row("task:g2"))
        vm.activate(row("task:g2"))
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g2")), sent)
    }

    @Test
    fun theOverlayStaysOpenAfterAnOpenActionUntilTheNewTabIsActive() = runNow {
        layout("leaf-other")
        show(listOf(task(hasOpen = true)))
        vm.open()
        vm.activate(row("task:g1"))
        vm.activate(row("task:g1"))
        assertTrue(vm.state.open)
        windowState.updateConfig(windowState.config.value!!.copy(activeTabId = "new-tab"))
        assertFalse(vm.state.open)
    }

    // ---- ⌘↵, → and ⌘⌫ ----

    @Test
    fun rightOnATaskPeeksTheSessionEnterWouldOpenAndDoesNothingForATaskWithoutOne() = runNow {
        layout("leaf-r1")
        show(
            listOf(task(), task(id = "g2", hasOpen = true)),
            session("r1", "needs_you", taskId = "g1"),
        )
        vm.open()
        vm.select("task:g1")
        vm.peek()
        assertEquals(listOf<WindowCommand>(WindowCommand.InspectAgentSession("r1")), sent)
        assertTrue(vm.state.peek != null)
        vm.closePeek()
        assertNull(vm.state.peek)
        sent.clear()
        vm.select("task:g2")
        vm.peek()
        assertTrue(sent.isEmpty(), "a task with no session has nothing to peek")
        assertNull(vm.state.peek)
    }

    @Test
    fun escapeStepsBackFromTheQuestionThenThePeekThenTheOverlay() = runNow {
        layout("leaf-a")
        show(emptyList(), session("a", "working"))
        vm.open()
        vm.peek()
        vm.requestClose()
        assertTrue(vm.state.confirm != null)
        vm.back()
        assertNull(vm.state.confirm)
        vm.peek()
        vm.back()
        assertNull(vm.state.peek)
        assertTrue(vm.state.open)
        vm.back()
        assertFalse(vm.state.open)
    }

    @Test
    fun closingIsOfferedOnlyForARowThatIsOneLiveSession() = runNow {
        layout("leaf-r1", "leaf-r2", "leaf-r3", "leaf-lone")
        show(
            listOf(
                task(id = "many"),
                task(id = "one"),
                task(id = "gone"),
            ),
            session("r1", "working", taskId = "many", lastActivityAt = now - 1_000),
            session("r2", "working", taskId = "many", lastActivityAt = now - 1_000),
            session("r3", "working", taskId = "one", lastActivityAt = now - 1_000),
            session("r4", "ended", taskId = "gone", endedAt = now - 5_000),
            session("lone", "working", lastActivityAt = now - 1_000),
        )
        assertEquals(
            mapOf("task:many" to false, "task:one" to true, "task:gone" to false, "lone" to true),
            listOf("task:many", "task:one", "task:gone", "lone").associateWith { row(it).canClose },
        )
        vm.open()
        vm.select("task:many")
        vm.requestClose(fromKeyboard = true)
        assertNull(vm.state.confirm, "⌘⌫ on a task of several sessions asks nothing")
        assertTrue(sent.isEmpty())
        vm.select("task:one")
        vm.requestClose(fromKeyboard = true)
        assertEquals("task:one", vm.state.confirm?.rowId)
        vm.confirmClose()
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseSession("s-leaf-r3")), sent.filterIsInstance<WindowCommand.CloseSession>())
    }

    @Test
    fun aTaskWithOneSessionIsOneTaskRowNamedByTheTaskAndStillClosable() {
        layout("leaf-r1")
        show(
            listOf(task()),
            session("r1", "needs_you", taskId = "g1", question = "Which branch?"),
        )
        val rows = vm.state.rows
        assertEquals(listOf("task:g1"), rows.map { it.id })
        assertEquals("Release g1", rows[0].title)
        assertEquals("\"Which branch?\"", rows[0].detail)
        assertEquals("g1", rows[0].taskId)
        assertEquals("r1", rows[0].recordId)
        assertTrue(rows[0].canClose)
    }

    @Test
    fun aTaskWithSeveralSessionsIsOneRowAndItsSessionsAreNotListedAgain() {
        layout("leaf-r1", "leaf-r2", "leaf-r3")
        show(
            listOf(hostedTask(main = "r1")),
            session("r1", "needs_you", taskId = "g1", question = "Which branch?"),
            session("r2", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("r3", "done", taskId = "g1"),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertFalse(vm.state.rows.single().canClose)
    }

    @Test
    fun hostedSessionsThatCarryATaskIdFormATaskRowWithoutAnyTaskFile() {
        layout("leaf-a", "leaf-b", "leaf-loner")
        show(
            listOf(hostedTask()),
            session("a", "working", taskId = "g1", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("b", "needs_you", since = 2_000, taskId = "g1", question = "Which branch?"),
            session("loner", "working", since = 99_999),
        )
        // b needs you but began after a, so the task keeps the place of its first session.
        assertEquals(listOf("task:g1", "loner"), vm.state.now.map { it.id })
        val r = row("task:g1")
        assertEquals("needs you", r.stateWord)
        assertEquals("\"Which branch?\"", r.detail)
        assertEquals(1, vm.state.needYouCount)
    }

    @Test
    fun theSessionsThatCarryAFileTasksIdAreItsRowAndNothingElseDecidesWhoIsInIt() {
        layout("leaf-r1", "leaf-x", "leaf-near")
        show(
            listOf(task(hasOpen = true)),
            session("r1", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("x", "needs_you", taskId = "g1"),
            // Opened in the folder the task's open action runs in, but it carries no task id.
            session("near", "working", cwd = "/work/proj", lastActivityAt = now - 1_000),
        )
        assertEquals(setOf("task:g1", "near"), vm.state.rows.map { it.id }.toSet())
        assertEquals("needs you", row("task:g1").stateWord, "every session that carries the id counts with the task")
        assertEquals("working", row("near").stateWord, "a session without the id keeps its own row")
    }

    @Test
    fun withNoTasksEveryRowIsAnOrdinarySessionWithTheSessionsIdAndNoTask() {
        show(emptyList(), session("a", "working"), session("b", "done"))
        assertEquals(setOf("a", "b"), vm.state.rows.map { it.id }.toSet())
        assertTrue(vm.state.rows.all { it.taskId == null })
    }

    @Test
    fun aTaskRowShowsTheRepositoryItsSessionsShareElseTheirFolderAndNoneWhenTheyDiffer() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(task(source = "agents", repo = null)),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "done", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("lunamux", row("task:g1").where)

        show(
            listOf(task(source = "agents", repo = null)),
            session("a", "working", taskId = "g1", repo = null, cwd = "/work/notes"),
            session("b", "done", taskId = "g1", repo = null, cwd = "/work/notes/"),
        )
        assertEquals("notes", row("task:g1").where)

        show(
            listOf(task(source = "agents", repo = null)),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "done", taskId = "g1", repo = "other", cwd = "/work/other"),
        )
        assertNull(row("task:g1").where)

        show(
            listOf(task(source = "file", repo = "named")),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "done", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("named", row("task:g1").where, "the file's repository wins")
    }

    @Test
    fun aRowOutsideARepositoryShowsItsFolderAndHomeAsATilde() {
        layout("leaf-home", "leaf-scratch", "leaf-repo")
        show(
            emptyList(),
            session("home", "working", title = "Chat at home", repo = null, cwd = "/Users/someone", cwdHome = true),
            session("scratch", "working", title = "Notes", repo = null, cwd = "/work/scratch/"),
            session("repo", "working", title = "In a repo", repo = "proj", cwd = "/work/proj"),
        )
        val rows = vm.state.rows.associateBy { it.id }
        assertEquals("~", rows.getValue("home").where)
        assertEquals("scratch", rows.getValue("scratch").where)
        assertEquals("proj", rows.getValue("repo").where)
    }

    // ---- search ----

    /** Answers the search for [query] with [matches]. */
    private fun answer(query: String, vararg matches: AgentSessionMatch) {
        vm.setQuery(query)
        windowState.updateAgentMatches(WindowEnvelope.AgentSessionMatches(query, matches.toList()))
    }

    @Test
    fun aSearchFoldsTwoMatchedSessionsOfOneTaskIntoOneRowThatNamesWhereAndWhichSessionMatched() {
        layout("leaf-a", "leaf-b")
        val writer = session("a", "done", taskId = "g1", title = "Writer")
        val reviewer = session("b", "done", taskId = "g1", title = "Reviewer")
        show(listOf(hostedTask(status = "drafting")), writer, reviewer)
        vm.open()
        answer(
            "needle",
            AgentSessionMatch(reviewer, "transcript", "…a needle here…", 3, 6, null),
            AgentSessionMatch(writer, "transcript", "…another needle…", 8, 6, null),
        )
        val matches = vm.state.matches
        assertEquals(1, matches.size, "two sessions of one task are one result")
        val r = matches.single()
        assertEquals("task:g1", r.id)
        assertEquals("b", r.recordId, "the best match stands for the task and ↵ opens its session")
        assertEquals("transcript", r.field)
        assertEquals("in transcript of Reviewer", r.extra)
        assertEquals("…a needle here…", r.detail)
        assertEquals("1 MATCH", vm.state.matchHeading)
    }

    @Test
    fun aSearchResultOpensTheSessionThatMatchedEvenWhenThatIsNotTheOneEnterOpensForTheTask() = runNow {
        layout("leaf-main", "leaf-m")
        val main = session("main", "done", taskId = "g1", title = "Main")
        val m = session("m", "done", taskId = "g1", title = "Mate")
        show(listOf(hostedTask(main = "main")), main, m)
        vm.open()
        answer("mate", AgentSessionMatch(m, "title", null, 0, 4, null))
        assertEquals("m", vm.state.matches.single().recordId)
        vm.activateSelected()
        assertEquals(jumpTo("leaf-m"), sent)
    }

    @Test
    fun aSearchMatchOnATasksOwnSessionThatCarriesNoTaskIdIsTheTasksRowNotARowOfItsOwn() = runNow {
        layout("leaf-m")
        val main = session("main", "ended", endedAt = now - 5_000, title = "Main chat")
        val m = session("m", "working", taskId = "g1", title = "Mate", lastActivityAt = now - 1_000)
        show(listOf(hostedTask(main = "main")), main, m)
        vm.open()
        answer("needle", AgentSessionMatch(main, "transcript", "…a needle…", 3, 6, null))
        val only = vm.state.matches.single()
        assertEquals("task:g1", only.id, "the own session's match is the task's row")
        assertEquals("main", only.recordId)
        vm.activateSelected()
        assertEquals("conversation closed", vm.state.ask?.tag, "the own conversation, not just some session")

        answer(
            "needle",
            AgentSessionMatch(main, "transcript", "…a needle…", 3, 6, null),
            AgentSessionMatch(m, "transcript", "…another needle…", 8, 6, null),
        )
        assertEquals(listOf("task:g1"), vm.state.matches.map { it.id }, "two matches of one task are one result, whichever carries the id")
    }

    @Test
    fun anOwnSessionThatBelongsToAnotherTaskIsNotCountedInThisTasksRowOrTwiceAsWorking() = runNow {
        layout("leaf-x", "leaf-m")
        val x = session("x", "working", taskId = "g2", lastActivityAt = now - 1_000)
        val m = session("m", "working", taskId = "g1", lastActivityAt = now - 1_000)
        show(
            listOf(hostedTask(main = "x"), task(id = "g2", source = "agents")),
            x, m,
        )
        assertEquals("working", row("task:g1").stateWord, "the other task's session is not this task's")
        assertEquals("working", row("task:g2").stateWord)
        assertEquals(2, vm.state.workingCount, "each moving session is counted once")
        // Not counted here, but still the session that made the task: ↵ goes to it.
        vm.open()
        vm.select("task:g1")
        assertEquals(Enter.JUMP, vm.state.selectedRow?.enter)
        assertEquals("x", vm.state.selectedRow?.recordId)
        vm.activateSelected()
        assertEquals(jumpTo("leaf-x"), sent)
    }

    @Test
    fun aClickOnTheRowThatIsAskingAnswersTheQuestionInsteadOfWithdrawingIt() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", taskId = "g1", endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.activate(vm.state.selectedRow!!)
        assertEquals("task:g1", vm.state.ask?.rowId)
        // What a click does: select the row, then open it.
        vm.select("task:g1")
        assertEquals("task:g1", vm.state.ask?.rowId, "selecting the asking row keeps its question")
        vm.activate(vm.state.selectedRow!!)
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)
    }

    // ---- selection ----

    @Test
    fun theOverlayOpensOnTheFirstRowThatNeedsYouInListOrder() {
        show(
            listOf(task()),
            session("g-run", "working", since = 1_500, taskId = "g1", lastActivityAt = now - 1_000),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
            session("early", "needs_you", since = 2_000),
        )
        vm.open()
        assertEquals("early", vm.state.selectedId)
        assertEquals("◆", vm.state.selectedRow?.marker)
    }
    @Test
    fun theSelectionStaysOnItsRowWhenRowsAppearAboveIt() {
        show(emptyList(), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working", since = 3_000))
        vm.open()
        vm.moveSelection(1)
        assertEquals("b", vm.state.selectedId)
        show(emptyList(), session("z", "needs_you", since = 10), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working", since = 3_000))
        assertEquals("b", vm.state.selectedId)
        assertEquals(2, vm.state.selected)
    }

    @Test
    fun whenTheSelectedRowDisappearsTheRowInItsPlaceIsSelected() {
        show(emptyList(), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working", since = 3_000))
        vm.open()
        vm.moveSelection(1)
        show(emptyList(), session("a", "needs_you", since = 1_000), session("c", "working", since = 3_000))
        assertEquals("c", vm.state.selectedId)
    }

    // ---- idle grace ----

    @Test
    fun aDoneConversationStaysInNowForAsLongAsItsPaneIsOpenInStartOrder() {
        show(
            emptyList(),
            session("recent", "done", since = now - 60_000, startedAt = 3_000),
            session("old", "done", since = now - 11 * 60_000, startedAt = 1_000),
            session("busy-then-done", "done", since = now - 20 * 60_000, lastActivityAt = now - 30_000, startedAt = 2_000),
        )
        assertEquals(listOf("old", "busy-then-done", "recent"), vm.state.now.map { it.id }, "no grace: the pane decides, the start orders")
        assertEquals("done", row("old").stateWord)
        assertTrue(vm.state.earlier.isEmpty())
    }
    @Test
    fun aClosedConversationLeavesNowAtOnceAndADoneOneInAnOpenPaneStays() {
        show(
            listOf(task()),
            session("run", "done", since = now - 1_000_000, taskId = "g1"),
            session("graced", "done", since = now - 1_000),
            session("work", "working", since = now - 5_000),
            session("need", "needs_you", since = now - 9_000),
            session("closed", "ended", since = now - 2_000, endedAt = now - 1_000),
            session("longClosed", "ended", since = now - 3_600_000, endedAt = now - 3_600_000),
        )
        assertEquals(listOf("task:g1", "need", "work", "graced"), vm.state.now.map { it.id })
        assertEquals(listOf("closed", "longClosed"), vm.state.earlier.map { it.id })
        assertEquals(1, vm.state.needYouCount)
    }
    @Test
    fun aTaskStaysInNowWhileOneOfItsSessionsIsInAnOpenTabAndCountsOnlyTheSessionsWithAPane() {
        layout("leaf-a")
        show(
            listOf(task()),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id })
        assertEquals("working", row("task:g1").stateWord)
        assertEquals(1, vm.state.workingCount, "b has no pane, so it is neither a row nor a count")
    }

    @Test
    fun nowKeepsItsOrderWhenStatesFlipAndWhenTheSameFactsArriveAgainAfterARestart() {
        fun facts(aState: String, bState: String) = arrayOf(
            session("a", aState, since = 9_000, startedAt = 1_000),
            session("b", bState, since = 9_500, startedAt = 2_000),
            session("c", "working", since = 100, startedAt = 3_000),
            session("t1", "working", since = 7_000, startedAt = 2_500, taskId = "g1"),
            session("t2", "working", since = 7_000, startedAt = 2_600, taskId = "g1"),
        )
        layout("leaf-a", "leaf-b", "leaf-c", "leaf-t1", "leaf-t2")
        val tasks = listOf(task(source = "agents", startedAt = 2_500))
        showAway(tasks, *facts("working", "working"))
        val before = vm.state.now.map { it.id }
        assertEquals(listOf("a", "b", "task:g1", "c"), before)
        showAway(tasks, *facts("needs_you", "working"))
        assertEquals(before, vm.state.now.map { it.id })
        showAway(tasks, *facts("working", "needs_you"))
        assertEquals(before, vm.state.now.map { it.id })
        // A restart brings the same facts (the server keeps each start), and the order is the same.
        windowState.updateAgentSessions(emptyList(), true)
        windowState.updateAgentTasks(emptyList())
        showAway(tasks, *facts("needs_you", "needs_you"))
        assertEquals(before, vm.state.now.map { it.id })
    }

    // ---- one row per task, the ask before a resume, the chips ----

    /** Lays out [leaves] in one listed tab named [title]. */
    private fun layoutNamed(title: String, vararg leaves: String) {
        laidOut = true
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = title, panes = panes(leaves.toList()))), activeTabId = "tab-1"))
    }

    /** The footer as it reads: each key and its label, joined with " · ". */
    private fun footerText() = vm.state.footer.joinToString(" · ") { "${it.key} ${it.label}" }

    @Test
    fun aTasksOwnLiveSessionIsPartOfItsRowAndCountsInTheStateWordWhetherOrNotItCarriesTheTaskId() {
        layout("leaf-main", "leaf-a", "leaf-b")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id }, "the own session is never a row of its own")
        assertEquals("3 working", row("task:g1").stateWord)

        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("3 working", row("task:g1").stateWord)
    }

    @Test
    fun anEndedOwnSessionWithNoTaskIdStaysInTheTasksRunsSoItsResumeIsNeverASecondRow() = runNow {
        layout("leaf-a", "leaf-main")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", endedAt = now - 5_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("working", row("task:g1").stateWord, "an ended session is not moving")
        // The resumed conversation comes back as a live session with the same record: still the same row.
        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("2 working", row("task:g1").stateWord)
    }

    @Test
    fun theFooterReadsOpenForAPaneThatIsShownAndAnswerItForAWaitingBackgroundMember() = runNow {
        layoutWithBackground(listOf("leaf-main", "leaf-m"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        assertEquals("↵ open · ↑↓ move · esc close", footerText())
        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("bg", "needs_you", taskId = "g1", background = true),
        )
        assertEquals(Enter.SHOW_WAITING, row("task:g1").enter)
        assertEquals("↵ answer it · opens as a tab until it works on · ↑↓ move · esc close", footerText())
    }
    @Test
    fun aMainConversationInABackgroundTabThatAsksIsShownAsItsTabNotJumpedTo() = runNow {
        layoutWithBackground(listOf("leaf-listed"), listOf("leaf-main"))
        show(
            listOf(hostedTask(main = "main")),
            session("main", "needs_you", taskId = "g1", background = true),
            session("listed", "working", lastActivityAt = now - 1_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.SHOW_WAITING, r.enter, "an unlisted tab cannot be jumped to")
        assertEquals("main", r.recordId)
        vm.activate(r)
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("main")), sent)
    }

    @Test
    fun aMainConversationNoSummaryHoldsAnyMoreIsResumedByItsStoredIdNotReplacedByAnotherSession() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "conv-from-last-month")),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("older", "ended", taskId = "g1", endedAt = now - 9_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.RESUME, r.enter)
        assertEquals("conv-from-last-month", r.recordId)
        assertEquals("conversation closed", r.ask?.tag)
        vm.open()
        vm.select("task:g1")
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("conv-from-last-month")), sent)
    }

    @Test
    fun theFirstEnterOnAClosedOwnConversationAsksAndTheSecondResumesItInANewTab() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", taskId = "g1", endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        assertNull(vm.state.ask)
        assertEquals("↵ open · ↑↓ move · esc close", footerText(), "before the ask the footer is the default one")
        vm.activateSelected()
        val ask = vm.state.ask!!
        assertEquals("task:g1", ask.rowId)
        assertEquals("conversation closed", ask.tag)
        assertEquals("Resume it in a new tab?", ask.question)
        assertEquals("↵ resume · esc cancel", ask.keys.joinToString(" · ") { "${it.key} ${it.label}" })
        assertEquals("↵ resume · esc cancel", footerText())
        assertTrue(sent.isEmpty())
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main")), sent)
        assertNull(vm.state.ask)
    }

    @Test
    fun escapeMovingOrANewQueryCancelsTheAskAndTheNextEnterAsksAgain() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "ended", taskId = "g1", endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("x", "working", lastActivityAt = now - 2_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.activateSelected()
        assertTrue(vm.state.ask != null)
        vm.back()
        assertNull(vm.state.ask)
        assertTrue(vm.state.open, "esc cancelled the ask and left the overlay open")

        vm.activateSelected()
        vm.moveSelection(1)
        assertNull(vm.state.ask, "moving cancels it")
        vm.moveSelection(-1)
        vm.activateSelected()
        assertTrue(vm.state.ask != null)
        vm.setQuery("x")
        assertNull(vm.state.ask)
        assertTrue(sent.isEmpty(), "no cancelled ask ever resumed anything")
    }

    @Test
    fun theStatusIsRepeatedInFullOnlyForTheSelectedRowWhenItsCellCutIt() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(hostedTask(main = "main", status = "A very long status that no cell can hold")),
            session("main", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", lastActivityAt = now - 1_000, nowLine = "Edit b.kt"),
        )
        vm.open()
        vm.select("task:g1")
        assertNull(row("task:g1").statusFull, "not cut: nothing repeated")
        vm.setDetailCut("task:g1", true)
        assertEquals("A very long status that no cell can hold", row("task:g1").statusFull)
        assertNull(row("b").statusFull, "only the selected row")
        vm.setDetailCut("b", true)
        assertNull(row("b").statusFull, "an answer about a row that is not selected is ignored")
        vm.setDetailCut("task:g1", false)
        assertNull(row("task:g1").statusFull)
        vm.setDetailCut("task:g1", true)
        vm.select("b")
        vm.setDetailCut("b", false)
        vm.select("task:g1")
        assertNull(row("task:g1").statusFull, "the answer is forgotten when the selection moves")
    }

    @Test
    fun theFolderChipIsTheFolderOfTheSessionEnterGoesToWithHomeAsTilde() {
        layout("leaf-main")
        show(
            listOf(hostedTask(main = "main")),
            session("main", "working", taskId = "g1", lastActivityAt = now - 1_000, cwdShown = "~/code/lunamux"),
        )
        assertEquals(listOf(AgentsOverviewBackingViewModel.Chip(AgentsOverviewBackingViewModel.ChipKind.FOLDER, "~/code/lunamux")), row("task:g1").chips.filter { it.kind == AgentsOverviewBackingViewModel.ChipKind.FOLDER })

        show(emptyList(), session("home", "working", lastActivityAt = now - 1_000, cwdShown = "~"))
        assertEquals("~", row("home").chips.single { it.kind == AgentsOverviewBackingViewModel.ChipKind.FOLDER }.text)

        show(emptyList(), session("nowhere", "working", lastActivityAt = now - 1_000, cwdShown = null))
        assertTrue(row("nowhere").chips.none { it.kind == AgentsOverviewBackingViewModel.ChipKind.FOLDER })
    }

    /** The text of the tab chip of the one row, or null when it has none. */
    private fun tabChip(id: String) = row(id).chips.singleOrNull { it.kind == AgentsOverviewBackingViewModel.ChipKind.TAB }?.text

    @Test
    fun theTabChipNamesTheTabUnlessItsNameAlreadyHasEveryWordOfTheTitle() {
        // The task is titled "Release g1".
        fun withTab(name: String): String? {
            layoutNamed(name, "leaf-main")
            show(listOf(hostedTask(main = "main")), session("main", "working", taskId = "g1", lastActivityAt = now - 1_000))
            return tabChip("task:g1")
        }
        assertEquals("notes", withTab("notes"), "a tab that says something else is named")
        assertNull(withTab("Release g1"), "the same words")
        assertNull(withTab("RELEASE: g1!"), "case and punctuation are ignored")
        assertNull(withTab("g1 and the release notes"), "every word, in any order and with more around")
        assertEquals("release", withTab("release"), "one word missing is a different tab")
        assertEquals("releases g1", withTab("releases g1"), "a word matches whole, never as a prefix or a fuzzy likeness")
        assertEquals("xrelease g1", withTab("xrelease g1"), "nor as a substring")
    }

    private companion object {
        const val EARLIER_LIMIT = AgentsOverviewBackingViewModel.EARLIER_LIMIT
        const val MINUTE = 60_000L
        const val HOUR = 60L * 60_000L
        const val DAY = 24L * 60 * 60_000L
    }
}
