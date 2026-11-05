/**
 * Tests for the task part of [AgentsOverviewBackingViewModel]: every task is one row, and a
 * session in no task is a task of one. They cover where a task sits (it stays in NOW while it has
 * open work and goes to EARLIER once every session reads done or ended), the state word of a row
 * ("needs you", "N working", "working", "done", "finished") and the detail and extra line behind
 * it, what ↵ opens in every branch (the task's own session, a live session in a listed tab, the
 * task's `open` action, a watch of a background session, a resume that asks first, nothing), ⌘↵ and → on a task,
 * which rows ⌘⌫ may close, how a search folds the sessions of one task into one row, the selection
 * staying on its row, the stable order of NOW, and when a working session counts as moving, quiet
 * or stale.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.WorldConfig
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
    private val watched = mutableListOf<String>()
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }, watch = { watched += it }) { sent += it }

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

    /** Lays out an empty home world showing and [inWork] in the first tab of a second world named "Work". */
    private fun layoutTwoWorlds(inWork: List<String>) {
        laidOut = true
        windowState.updateConfig(
            WindowConfig(
                worlds = listOf(
                    WorldConfig("w1", "Home", tabs = listOf(TabConfig("t0", "t0"))),
                    WorldConfig("w2", "Work", tabs = listOf(TabConfig("t1", "t1", panes = panes(inWork)))),
                ),
                activeWorldId = "w1",
            ),
        )
    }

    /**
     * A task-file member. The key defaults to the session id; [task] numbers the others from 1 the
     * way the server does, so a member with neither gets the key `#<place in the file>`.
     */
    private fun member(
        title: String, state: String, since: Long = 1_000, label: String? = null, recordId: String? = null,
        line: String? = null, links: List<String> = emptyList(), sessionId: String? = null, key: String? = null,
    ) = AgentTaskMember(
        key = key ?: sessionId ?: "", title = title, state = state, sinceMillis = since, label = label,
        recordId = recordId, line = line, links = links, sessionId = sessionId,
    )

    private fun task(
        id: String = "g1", members: List<AgentTaskMember>, hasOpen: Boolean = false,
        refreshedAtMillis: Long? = null, source: String = "file", repo: String? = "proj", startedAt: Long? = null,
        leadRecordId: String? = null, status: String? = null,
    ) = AgentTask(
        id = id, title = "Release $id", repo = repo, source = source, hasOpen = hasOpen,
        members = members.mapIndexed { i, m -> if (m.key.isEmpty()) m.copy(key = "#${i + 1}") else m },
        refreshedAtMillis = refreshedAtMillis, startedAtMillis = startedAt, leadRecordId = leadRecordId, status = status,
    )

    /** A task whose sessions are all hosted ones that carry its id, with [lead] as its own session. */
    private fun hostedTask(lead: String? = null, hasOpen: Boolean = false, status: String? = null) =
        task(source = "agents", members = emptyList(), hasOpen = hasOpen, leadRecordId = lead, status = status)

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
    fun aTaskSitsInNowWhereItStartedWhateverStateItsMembersAreIn() {
        show(
            listOf(task(startedAt = 2_000, members = listOf(member("a", "needs_you", since = 2_000), member("b", "working", since = 100)))),
            session("old-working", "working", since = 500),
            session("late-needs", "needs_you", since = 3_000),
            session("early-needs", "needs_you", since = 1_000),
        )
        assertEquals(listOf("old-working", "early-needs", "task:g1", "late-needs"), vm.state.now.map { it.id })
    }

    @Test
    fun aWorkingOnlyTaskSitsAtItsFirstSessionsStartNotBehindThoseWhoNeedYou() {
        show(
            listOf(task(members = listOf(member("a", "working", since = 1, recordId = "a"), member("b", "working", since = 2, recordId = "b")))),
            session("a", "working", since = 1),
            session("b", "working", since = 2),
            session("n", "needs_you", since = 9_000),
            session("w", "working", since = 5_000),
        )
        // The task began with its first session (start 1), so it leads; its sessions are not rows of their own.
        assertEquals(listOf("task:g1", "w", "n"), vm.state.now.map { it.id })
    }

    @Test
    fun aTaskStaysInNowWhileASessionHasOpenWorkEvenWhenNothingIsRunning() {
        show(
            listOf(task(members = listOf(member("a", "idle", label = "in review", recordId = "a"), member("b", "ended")))),
            session("busy", "working", since = 1),
            session("a", "idle"),
        )
        assertEquals(listOf("busy", "task:g1"), vm.state.now.map { it.id })
        assertTrue(vm.state.earlier.isEmpty())
    }

    @Test
    fun aFileTaskWhoseSessionsAllReadDoneMovesToEarlierEvenThoughNoneEnded() {
        show(
            listOf(
                // clocked an hour back, past the ten minutes a task that just finished stays in NOW
                task(id = "g1", members = listOf(member("a", "idle", label = "done", since = now - HOUR), member("b", "idle", label = "ready", since = now - HOUR))),
                task(id = "g2", members = listOf(member("c", "idle", label = "done", since = now - HOUR), member("d", "ended", since = now - HOUR))),
            ),
            session("busy", "working", since = 1),
        )
        assertEquals(listOf("busy"), vm.state.now.map { it.id })
        assertEquals(setOf("task:g1", "task:g2"), vm.state.earlier.map { it.id }.toSet())
    }

    @Test
    fun aFileTaskWithADoneSessionStaysInNowWhileAnotherNeedsYouWorksOrIsStillOpenWork() {
        show(
            listOf(
                task(id = "g1", members = listOf(member("a", "idle", label = "done"), member("b", "needs_you"))),
                task(id = "g2", members = listOf(member("c", "idle", label = "done"), member("d", "working", recordId = "d"))),
                task(id = "g3", members = listOf(member("e", "idle", label = "done"), member("f", "idle", label = "in review", recordId = "f"))),
                task(id = "g4", members = listOf(member("g", "idle", label = "done"), member("h", "idle", recordId = "h"))),
            ),
            session("d", "working"), session("f", "idle"), session("h", "idle"),
        )
        assertEquals(setOf("task:g1", "task:g2", "task:g3", "task:g4"), vm.state.now.map { it.id }.filter { it.startsWith("task:") }.toSet())
        assertTrue(vm.state.earlier.isEmpty())
    }

    @Test
    fun aDoneOnlyFileTaskGoesToEarlierOnceItsNewestMemberIsPastTheGrace() {
        show(listOf(task(members = listOf(member("a", "idle", label = "done", since = now - 11 * MINUTE), member("b", "idle", label = "done", since = now - 12 * MINUTE)))))
        assertTrue(vm.state.now.isEmpty())
        assertEquals(listOf("task:g1"), vm.state.earlier.map { it.id })
        assertEquals(now - 11 * MINUTE, vm.state.earlier.single().sinceMillis)
    }

    @Test
    fun aTaskThatJustFinishedStaysInNowForTheGraceSoItsRowNeverReadsMinutesUnderDays() {
        val ended = now - MINUTE
        show(
            listOf(
                // a file task whose members all just read done, and one whose members just ended
                task(id = "file", members = listOf(member("a", "idle", label = "done", since = ended), member("b", "idle", label = "ready", since = ended - 5_000))),
                task(id = "closed", members = listOf(member("c", "ended", since = ended), member("d", "ended", since = ended - 5_000))),
                // the same shapes, finished an hour ago
                task(id = "oldFile", members = listOf(member("e", "idle", label = "done", since = now - HOUR), member("f", "idle", label = "done", since = now - HOUR))),
                task(id = "hosted", source = "agents", members = emptyList()),
            ),
            // a task of hosted sessions that all ended a minute ago
            session("h1", "ended", since = ended, endedAt = ended, taskId = "hosted"),
            session("h2", "ended", since = ended - 5_000, endedAt = ended - 5_000, taskId = "hosted"),
        )
        assertEquals(setOf("task:file", "task:closed", "task:hosted"), vm.state.now.map { it.id }.filter { it.startsWith("task:") }.toSet())
        assertEquals(listOf("task:oldFile"), vm.state.earlier.map { it.id }.filter { it.startsWith("task:") })
        assertTrue(vm.state.earlier.all { it.sinceMillis <= now - 10 * MINUTE }, "nothing under an earlier heading reads younger than the grace")
        assertEquals(setOf("done"), vm.state.now.filter { it.id.startsWith("task:") }.map { it.stateWord }.toSet(), "still in NOW, so not yet finished")

        now += 10 * MINUTE
        show(
            listOf(task(id = "file", members = listOf(member("a", "idle", label = "done", since = ended), member("b", "idle", label = "ready", since = ended - 5_000)))),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals(listOf("task:file"), vm.state.earlier.map { it.id })
        assertEquals(ended, vm.state.earlier.single().sinceMillis)
        assertEquals("finished", vm.state.earlier.single().stateWord)
    }

    @Test
    fun aTaskWhoseSessionsAllEndedMovesToEarlierAmongTheOtherUnitsNewestFirst() {
        show(
            listOf(
                task(id = "g1", members = listOf(member("a", "ended", since = 4_000), member("b", "ended", since = 2_000))),
                task(id = "g2", members = emptyList(), hasOpen = true),
            ),
            session("idle-open", "idle", since = 1),
            session("closed-new", "ended", endedAt = 5_000),
            session("closed-old", "ended", endedAt = 3_000),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals(
            listOf("closed-new", "task:g1", "closed-old", "idle-open", "task:g2"),
            vm.state.earlier.map { it.id },
        )
    }

    @Test
    fun aTaskIsOneUnitInTheEarlierLimit() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        val tasks = (1..6).map {
            task(id = "g$it", members = listOf(member("m", "ended", since = now - 5 * DAY + it), member("n", "ended", since = now - 5 * DAY + it)))
        }
        show(tasks)
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
                task(id = "mixed", members = listOf(member("old", "ended", since = now - 20 * DAY), member("new", "ended", since = now - DAY))),
                task(id = "slow", members = listOf(member("a", "ended", since = now - 5 * DAY), member("b", "ended", since = now - 10 * DAY))),
                task(id = "ancient", members = listOf(member("a", "ended", since = now - 20 * DAY), member("b", "ended", since = now - 30 * DAY))),
            ),
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
    fun aFirstBandLongerThanTheLimitIsCutToo() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        show(emptyList(), *(1..8).map { session("r$it", "ended", endedAt = now - HOUR - it * 1_000L) }.toTypedArray())
        assertEquals(EARLIER_LIMIT, vm.state.recent.size)
        assertTrue(vm.state.fortnight.isEmpty())
        assertEquals(3, vm.state.earlierHidden)
    }

    @Test
    fun theDownArrowCrossesFromTheFirstBandToTheSecondAndEarlierRowsOfEndedSessionsResume() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        layout()
        show(
            listOf(task(id = "g1", members = listOf(member("a", "ended", since = now - DAY, recordId = "s-a"), member("b", "ended", since = now - 2 * DAY, recordId = "s-b")))),
            session("s-a", "ended", endedAt = now - DAY),
            session("s-b", "ended", endedAt = now - 2 * DAY),
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

    // ---- the chip ----

    @Test
    fun chipCountsIncludeTaskSessions() {
        show(
            listOf(task(members = listOf(member("a", "needs_you"), member("b", "working"), member("c", "working"), member("d", "idle")))),
            session("s1", "needs_you"),
        )
        assertEquals(2, vm.state.needYouCount)
        assertEquals(2, vm.state.workingCount)
        assertEquals("2 need you", vm.state.chipNeeds)
        assertEquals("2 working", vm.state.chipRest)
    }

    @Test
    fun theChipCountsAsWorkingOnlyTheSessionsThatAreMovingAndTheClockAloneEndsThat() {
        // Window-wide stamps: hosted singles by their last activity, a member Lunamux does not host by its file's refresh time.
        show(
            listOf(task(id = "f", refreshedAtMillis = now - 25 * MINUTE, members = listOf(member("file-run", "working"), member("other", "idle")))),
            session("live", "working", lastActivityAt = now - MINUTE),
            session("quiet", "working", lastActivityAt = now - 6 * MINUTE),
        )
        // One moving; the quiet single and the stale file member are not counted.
        assertEquals(1, vm.state.workingCount)
        assertEquals("1 working", vm.state.chipRest)
        now += 5 * MINUTE
        vm.tick()
        assertEquals(0, vm.state.workingCount)
        assertEquals("agents idle", vm.state.chipRest)
    }

    @Test
    fun aTaskRowCountsAQuietOrStaleSessionAsNotWorking() {
        layout("leaf-w1", "leaf-w2")
        show(
            listOf(task(refreshedAtMillis = now - 25 * MINUTE, members = listOf(
                member("one", "working", recordId = "w1"),
                member("two", "working", recordId = "w2"),
                member("three", "working"),
                member("four", "idle", label = "in review"),
            ))),
            session("w1", "working", lastActivityAt = now - MINUTE),
            session("w2", "working", lastActivityAt = now - 6 * MINUTE),
        )
        assertEquals("working", row("task:g1").stateWord, "only the first moves: the second is quiet, the third stale")
        assertEquals(1, vm.state.workingCount)
    }

    // ---- state words ----

    @Test
    fun aTaskWithASessionWaitingOnYouReadsNeedsYouWithTheMarkerAndItsQuestionInQuotes() {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task(members = listOf(
                member("One", "needs_you", recordId = "r1", line = "Which branch?"),
                member("Two", "working", recordId = "r2", line = "Edit a.kt"),
            ))),
            session("r1", "needs_you"),
            session("r2", "working", lastActivityAt = now - 1_000),
        )
        val r = row("task:g1")
        assertEquals("needs you", r.stateWord)
        assertEquals("◆", r.marker)
        assertEquals("\"Which branch?\"", r.detail)
        assertTrue(r.needsYou)
    }

    @Test
    fun oneMovingSessionReadsWorkingAndSeveralReadNWorkingBackgroundSessionsCountedToo() {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(lead = "n", status = "Notes drafted"), task(id = "g2", source = "agents", members = emptyList())),
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
    fun aBackgroundSessionThatNeedsYouMakesItsTaskReadNeedsYouWithItsQuestion() {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(lead = "n", status = null)),
            session("n", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("bg", "needs_you", taskId = "g1", background = true, question = "Which release?"),
        )
        val r = row("task:g1")
        assertEquals("needs you", r.stateWord)
        assertEquals("◆", r.marker)
        assertEquals("\"Which release?\"", r.detail)
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
    }

    @Test
    fun aTaskInNowWithNothingMovingOrWaitingReadsDoneAndOneInEarlierReadsFinished() {
        layout("leaf-a", "leaf-chat")
        show(
            listOf(
                task(id = "review", members = listOf(member("a", "idle", label = "in review", recordId = "a"), member("b", "idle", label = "queued"))),
                task(id = "closed", members = listOf(member("c", "ended", since = now - HOUR), member("d", "ended", since = now - HOUR))),
            ),
            session("a", "idle"),
            session("chat", "idle", since = now - MINUTE),
            session("old", "ended", endedAt = now - HOUR),
        )
        assertEquals("done", row("task:review").stateWord)
        assertEquals("finished", row("task:closed").stateWord)
        assertEquals("done", row("chat").stateWord, "a chat inside the grace is still in NOW")
        assertEquals("finished", row("old").stateWord)
        assertTrue(row("old").finished)
        assertFalse(row("chat").finished)
    }

    @Test
    fun aTaskOutsideEveryTabWithSessionsThatMoveStillReadsWorkingNotFinished() {
        showAway(
            listOf(task(members = listOf(member("a", "working", recordId = "a"), member("b", "working")))),
            session("a", "working", lastActivityAt = now - 1_000),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals(listOf("task:g1"), vm.state.earlier.map { it.id })
        assertEquals("2 working", vm.state.earlier.single().stateWord)
        assertEquals(0, vm.state.workingCount, "the chip counts what NOW shows")
    }

    @Test
    fun aSessionThatWorksButHasGoneQuietDoesNotMakeItsTaskReadWorking() {
        layout("leaf-a")
        show(listOf(task(members = listOf(member("a", "working", recordId = "a"), member("b", "idle", label = "queued")))), session("a", "working", lastActivityAt = now - 6 * MINUTE))
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id })
        assertEquals("done", row("task:g1").stateWord)
    }

    @Test
    fun aWorkingSessionOutsideLunamuxIsMovingWhileItsFileIsFreshAndStopsWhenItIsNotRefreshed() {
        show(listOf(task(refreshedAtMillis = now - MINUTE, members = listOf(member("filed", "working", line = "Doing a thing"), member("other", "idle")))))
        assertEquals("working", row("task:g1").stateWord)
        assertEquals("Doing a thing", row("task:g1").detail)
        now += 5 * MINUTE
        vm.tick()
        assertFalse(row("task:g1").working, "six minutes since the file was refreshed")
        now += 20 * MINUTE
        vm.tick()
        assertFalse(row("task:g1").working, "stale")
    }

    @Test
    fun aLoneSessionHasTheSameStateWordsAndNoStatus() {
        layout("leaf-n", "leaf-w", "leaf-i")
        show(
            emptyList(),
            session("n", "needs_you", question = "Which branch?"),
            session("w", "working", nowLine = "Edit a.kt", lastActivityAt = now - 1_000),
            session("i", "idle", since = now - MINUTE),
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
        layout("leaf-r1", "leaf-r2", "leaf-r3", "leaf-r4")
        show(
            listOf(
                task(id = "status", status = "waiting on the schema review", members = listOf(member("a", "needs_you", recordId = "r1", line = "Which branch?"), member("b", "working", recordId = "r2", line = "Edit a.kt"))),
                task(id = "question", members = listOf(member("a", "needs_you", recordId = "r3", line = "Which branch?"), member("b", "working", line = "Edit a.kt"))),
                task(id = "line", members = listOf(member("a", "working", recordId = "r4", line = "Edit a.kt"), member("b", "idle", label = "queued"))),
                task(id = "empty", members = listOf(member("a", "idle", label = "in review"), member("b", "idle", label = "queued"))),
            ),
            session("r1", "needs_you"), session("r2", "working", lastActivityAt = now - 1_000),
            session("r3", "needs_you"), session("r4", "working", lastActivityAt = now - 1_000),
        )
        assertEquals("waiting on the schema review", row("task:status").detail)
        assertEquals("\"Which branch?\"", row("task:question").detail)
        assertEquals("Edit a.kt", row("task:line").detail)
        assertEquals("", row("task:empty").detail)
    }

    @Test
    fun theDetailFallsBackToAnOlderMovingSessionsLineWhenTheNewestHasNone() {
        layout("leaf-new", "leaf-old")
        show(
            listOf(task(members = listOf(member("new", "working", recordId = "new"), member("old", "working", recordId = "old")))),
            session("new", "working", lastActivityAt = now - 1_000),
            session("old", "working", nowLine = "Edit a.kt", lastActivityAt = now - 5_000),
        )
        assertEquals("2 working", row("task:g1").stateWord)
        assertEquals("Edit a.kt", row("task:g1").detail)
    }

    @Test
    fun aBlankStatusLineOrQuestionIsNoDetailAtAll() {
        layout("leaf-w", "leaf-single")
        show(
            listOf(task(status = "   ", members = listOf(
                member("needs", "needs_you", line = "   "),
                member("working", "working", recordId = "w", line = ""),
            ))),
            session("w", "working", nowLine = "   ", lastActivityAt = now - 1_000),
            session("single", "working", nowLine = "  ", question = " ", lastActivityAt = now - 1_000),
        )
        assertEquals("", row("task:g1").detail)
        assertEquals("", row("single").detail)
        assertEquals("needs you", row("task:g1").stateWord)
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
    fun aTaskWhoseOwnSessionRunsInTheBackgroundHasNothingInItsExtraLineButTheDetail() {
        layoutWithBackground(listOf("leaf-m"), listOf("leaf-lead"))
        show(
            listOf(hostedTask(lead = "lead", status = "Notes drafted")),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("lead", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
        )
        assertEquals("Notes drafted", row("task:g1").detail)
        assertEquals("", row("task:g1").extra, "nothing but the detail, which the row shows itself")
    }

    @Test
    fun theExtraLineSaysThereIsNothingToJumpToForATaskNoSessionOfWhichIsInALunamuxPane() {
        show(listOf(task(members = listOf(member("a", "working", line = "Doing a thing"), member("b", "idle", label = "queued")))))
        val r = row("task:g1")
        assertEquals(Enter.NONE, r.enter)
        assertEquals("nothing to jump to: this session is not in a Lunamux pane", r.extra)
        assertEquals("Doing a thing", r.detail)
    }

    @Test
    fun aSessionWithNoDetailAndNothingToNoteHasNoExtraLineAtAll() {
        layoutWithBackground(listOf("leaf-m"), listOf("leaf-quiet"))
        show(emptyList(), session("quiet", "working", background = true, lastActivityAt = now - 1_000))
        val r = row("quiet")
        assertEquals("", r.detail)
        assertEquals("", r.extra, "the view draws no line for an empty extra, so the selected row has no blank second line")
    }

    // ---- what ↵ opens ----

    @Test
    fun aLoneSessionJumpsWatchesWhenInTheBackgroundAndAsksBeforeResumingWhenItsPaneIsGone() = runNow {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(
            emptyList(),
            session("n", "working", lastActivityAt = now - 1_000),
            session("bg", "working", background = true, lastActivityAt = now - 1_000),
            session("gone", "ended", endedAt = now - HOUR),
        )
        assertEquals(listOf(Enter.JUMP, Enter.WATCH, Enter.RESUME), listOf("n", "bg", "gone").map { row(it).enter })
        vm.open()
        vm.activate(row("n"))
        assertEquals(jumpTo("leaf-n"), sent)
        sent.clear()
        vm.open()
        vm.activate(row("bg"))
        assertEquals(listOf("bg"), watched)
        assertTrue(sent.isEmpty(), "watching moves nothing")
        assertFalse(vm.state.open, "the overlay would cover the watch")
        vm.open()
        vm.activate(row("gone"))
        assertTrue(sent.isEmpty(), "the first enter only asks")
        vm.activate(row("gone"))
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("gone")), sent)
    }

    @Test
    fun enterOnATaskWhoseOwnSessionIsLiveJumpsToItsPane() = runNow {
        layout("leaf-lead", "leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "idle", taskId = "g1", since = 1_000),
            session("m", "needs_you", taskId = "g1", since = 2_000),
        )
        vm.open()
        vm.select("task:g1")
        assertEquals(Enter.JUMP, vm.state.selectedRow?.enter)
        vm.activateSelected()
        assertEquals(jumpTo("leaf-lead"), sent)
    }

    @Test
    fun enterOnATaskWhoseOwnSessionRunsInTheBackgroundWatchesIt() = runNow {
        layoutWithBackground(listOf("leaf-m"), listOf("leaf-lead"))
        show(
            listOf(hostedTask(lead = "lead")),
            session("m", "working", taskId = "g1", since = 500, lastActivityAt = now - 1_000),
            session("lead", "working", taskId = "g1", since = 1_000, background = true, lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        assertEquals(Enter.WATCH, vm.state.selectedRow?.enter)
        assertEquals("↵ watch", vm.state.footer.first().let { "${it.key} ${it.label}" })
        sent.clear()
        vm.activateSelected()
        assertEquals(listOf("lead"), watched)
        assertTrue(sent.none { it is WindowCommand.SetActiveTab || it is WindowCommand.SetFocusedPane }, "watching moves nothing")
        assertFalse(vm.state.open)
    }

    @Test
    fun enterOnATaskWhoseOwnSessionIsGoneResumesItEvenWhenTheTaskHasAnOpenAction() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead", hasOpen = true)),
            session("lead", "ended", taskId = "g1", since = 500, endedAt = now - 5_000),
            session("m", "needs_you", taskId = "g1", since = 2_000),
        )
        vm.open()
        vm.select("task:g1")
        val r = row("task:g1")
        assertEquals(Enter.RESUME, r.enter, "the open action starts a conversation; this one exists and is resumed")
        assertFalse(r.isLive)
        assertEquals("lead", r.recordId)
        vm.activateSelected()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("lead")), sent)
        assertTrue(vm.state.open, "the overlay waits for the new tab")
    }

    @Test
    fun enterOnATaskWhoseOwnSessionIsGoneResumesItWhenThereIsNothingToOpen() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", since = 500, endedAt = now - 5_000),
            session("m", "needs_you", taskId = "g1", since = 2_000),
        )
        vm.open()
        vm.select("task:g1")
        val r = row("task:g1")
        assertEquals(Enter.RESUME, r.enter)
        assertFalse(r.isLive)
        assertEquals("lead", r.recordId)
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("lead")), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionJumpsToTheLiveSessionInAListedTabThatWaitedLongest() = runNow {
        layout("leaf-r1", "leaf-r2", "leaf-r3")
        show(
            listOf(task(members = listOf(
                member("late", "needs_you", since = 2_000, recordId = "r1"),
                member("early", "needs_you", since = 1_000, recordId = "r2"),
                member("busy", "working", recordId = "r3"),
            ))),
            session("r1", "needs_you"), session("r2", "needs_you"), session("r3", "working", lastActivityAt = 9_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.JUMP, r.enter)
        assertEquals("r2", r.recordId)
        vm.activate(r)
        assertEquals(jumpTo("leaf-r2"), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionAndNobodyWaitingJumpsToTheWorkingSessionWithTheFreshestSignOfLife() = runNow {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task(members = listOf(member("older", "working", recordId = "r1"), member("fresher", "working", recordId = "r2")))),
            session("r1", "working", lastActivityAt = 7_000), session("r2", "working", lastActivityAt = 9_000),
        )
        vm.activate(row("task:g1"))
        assertEquals(jumpTo("leaf-r2"), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionNeverPicksABackgroundSessionWhileAListedOneIsLive() = runNow {
        layoutWithBackground(listOf("leaf-listed"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(hasOpen = true)),
            // the background session waits for the person and has done longest, yet the listed one is chosen
            session("bg", "needs_you", taskId = "g1", since = 1_000, background = true),
            session("listed", "working", taskId = "g1", since = 2_000, lastActivityAt = now - 1_000),
        )
        val r = row("task:g1")
        assertEquals(Enter.JUMP, r.enter)
        assertEquals("listed", r.recordId)
        assertFalse(r.background)
        vm.activate(r)
        assertEquals(jumpTo("leaf-listed"), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionAndNoneInAListedTabRunsOpenBeforeWatchingABackgroundSession() = runNow {
        layoutWithBackground(listOf("leaf-other"), listOf("leaf-bg"))
        show(
            listOf(hostedTask(hasOpen = true)),
            session("bg", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
        )
        assertEquals(Enter.OPEN, row("task:g1").enter)
        vm.activate(row("task:g1"))
        assertTrue(sent.isEmpty(), "the first enter only asks")
        vm.activate(row("task:g1"))
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
    }

    @Test
    fun aTaskWithNoOwnSessionNoListedOneAndNoOpenActionWatchesALiveBackgroundSession() = runNow {
        layoutWithBackground(listOf("leaf-other"), listOf("leaf-bg"))
        show(
            listOf(hostedTask()),
            session("bg", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
            session("old", "ended", taskId = "g1", endedAt = now - HOUR),
        )
        val r = row("task:g1")
        assertEquals(Enter.WATCH, r.enter)
        assertEquals("bg", r.recordId)
        assertTrue(r.background)
        vm.open()
        vm.activate(r)
        assertEquals(listOf("bg"), watched)
        assertTrue(sent.isEmpty())
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
                task(id = "g1", hasOpen = true, members = listOf(member("gone", "ended", recordId = "r1"), member("w", "working", recordId = "w"))),
                task(id = "g2", hasOpen = true, members = emptyList()),
            ),
            session("r1", "ended", endedAt = 5_000),
            session("w", "working"),
        )
        assertEquals("w", row("task:g1").recordId)
        assertEquals(Enter.OPEN, row("task:g2").enter)
        vm.activate(row("task:g2"))
        vm.activate(row("task:g2"))
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g2")), sent)
    }

    @Test
    fun aTaskWithNoSessionLunamuxKnowsAndNoOpenActionDoesNothingOnEnter() = runNow {
        show(listOf(task(members = listOf(member("a", "idle", label = "queued"), member("b", "idle", label = "queued")))))
        vm.open()
        vm.select("task:g1")
        assertEquals(Enter.NONE, row("task:g1").enter)
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        assertTrue(vm.state.open)
    }

    @Test
    fun theOverlayStaysOpenAfterAnOpenActionUntilTheNewTabIsActive() = runNow {
        layout("leaf-other")
        show(listOf(task(hasOpen = true, members = emptyList())))
        vm.open()
        vm.activate(row("task:g1"))
        vm.activate(row("task:g1"))
        assertTrue(vm.state.open)
        windowState.updateConfig(windowState.config.value!!.copy(activeTabId = "new-tab"))
        assertFalse(vm.state.open)
    }

    // ---- ⌘↵, → and ⌘⌫ ----

    @Test
    fun commandEnterOnATaskTakesOverTheBackgroundSessionEnterWouldPeek() = runNow {
        layoutWithBackground(listOf("leaf-m"), listOf("leaf-lead"))
        show(
            listOf(hostedTask(lead = "lead")),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("lead", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.takeOverSelected()
        assertEquals(
            listOf<WindowCommand>(WindowCommand.TakeOverTab("tab-bg"), WindowCommand.SetFocusedPane("tab-bg", "leaf-lead")),
            sent,
        )
        assertFalse(vm.state.open)
    }

    @Test
    fun commandEnterOnATaskThatJumpsToAListedTabDoesNothing() = runNow {
        layoutWithBackground(listOf("leaf-m"), listOf("leaf-bg"))
        show(
            listOf(hostedTask()),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("bg", "working", taskId = "g1", background = true, lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.takeOverSelected()
        assertTrue(sent.isEmpty())
        assertTrue(vm.state.open)
    }

    @Test
    fun rightOnATaskPeeksTheSessionEnterWouldOpenAndDoesNothingForATaskWithoutOne() = runNow {
        layout("leaf-r1")
        show(
            listOf(
                task(members = listOf(member("a", "needs_you", recordId = "r1"), member("b", "idle"))),
                task(id = "g2", members = listOf(member("c", "idle", label = "queued"), member("d", "idle", label = "queued"))),
            ),
            session("r1", "needs_you"),
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
        assertTrue(sent.isEmpty(), "a task with no session Lunamux hosts has nothing to peek")
        assertNull(vm.state.peek)
    }

    @Test
    fun rightOnALoneSessionPeeksAndLeftClosesThePeek() = runNow {
        layout("leaf-a")
        show(emptyList(), session("a", "working"))
        vm.open()
        vm.peek()
        assertTrue(vm.state.peek != null)
        vm.closePeek()
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
                task(id = "many", members = listOf(member("a", "working", recordId = "r1"), member("b", "working", recordId = "r2"))),
                task(id = "one", members = listOf(member("c", "working", recordId = "r3"))),
                task(id = "gone", members = listOf(member("d", "ended", recordId = "r4"))),
            ),
            session("r1", "working", lastActivityAt = now - 1_000),
            session("r2", "working", lastActivityAt = now - 1_000),
            session("r3", "working", lastActivityAt = now - 1_000),
            session("r4", "ended", endedAt = now - 5_000),
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
    fun closingABackgroundRowAsksTheSameQuestionAsAnyOtherSession() = runNow {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(emptyList(), session("n", "working"), session("bg", "working", since = 2_000, background = true))
        vm.open()
        vm.select("bg")
        vm.requestClose()
        assertEquals("bg", vm.state.confirm?.rowId)
        assertTrue(sent.none { it is WindowCommand.CloseSession }, "nothing closes before the person confirms")
        vm.confirmClose()
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseSession("s-leaf-bg")), sent.filterIsInstance<WindowCommand.CloseSession>())
    }

    // ---- one row per task ----

    @Test
    fun aTaskWithOneSessionIsOneTaskRowNamedByTheTaskAndStillClosable() {
        layout("leaf-r1")
        show(
            listOf(task(members = listOf(member("Task one", "needs_you", recordId = "r1", line = "Which branch?")))),
            session("r1", "needs_you"),
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
            listOf(hostedTask(lead = "r1")),
            session("r1", "needs_you", taskId = "g1", question = "Which branch?"),
            session("r2", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("r3", "idle", taskId = "g1"),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertFalse(vm.state.rows.single().canClose)
    }

    @Test
    fun aTaskWithNoSessionsAndAnOpenActionIsOneRowThatOpens() = runNow {
        show(listOf(task(hasOpen = true, members = emptyList())))
        val r = vm.state.rows.single()
        assertEquals("task:g1", r.id)
        assertEquals(Enter.OPEN, r.enter)
        assertEquals("", r.detail)
        vm.activate(r)
        vm.activate(r)
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
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
    fun aHostedSessionOfAFileTaskThatNoMemberClaimsJoinsTheFilesMembers() {
        layout("leaf-r1", "leaf-x")
        show(
            listOf(task(members = listOf(member("Listed", "working", recordId = "r1")))),
            session("r1", "working"),
            session("x", "needs_you", taskId = "g1"),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("needs you", row("task:g1").stateWord, "a session only Lunamux knows about counts with the task")
    }

    @Test
    fun aTaskTheServerKnowsWithOneHostedSessionIsOneRowThatCanBeClosed() {
        layout("leaf-a")
        show(listOf(hostedTask()), session("a", "working", taskId = "g1"))
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertTrue(row("task:g1").canClose)
    }

    @Test
    fun withNoTasksEveryRowIsAnOrdinarySessionWithTheSessionsIdAndNoTask() {
        show(emptyList(), session("a", "working"), session("b", "idle"))
        assertEquals(setOf("a", "b"), vm.state.rows.map { it.id }.toSet())
        assertTrue(vm.state.rows.all { it.taskId == null })
    }

    @Test
    fun aTaskRowShowsTheRepositoryItsSessionsShareElseTheirFolderAndNoneWhenTheyDiffer() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("lunamux", row("task:g1").where)

        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = null, cwd = "/work/notes"),
            session("b", "idle", taskId = "g1", repo = null, cwd = "/work/notes/"),
        )
        assertEquals("notes", row("task:g1").where)

        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "other", cwd = "/work/other"),
        )
        assertNull(row("task:g1").where)

        show(
            listOf(task(source = "file", repo = "named", members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("named", row("task:g1").where, "the file's repository wins")
    }

    @Test
    fun sessionsWithTheSameTitleAndNoRepositoryEachShowTheFolderTheyRunIn() {
        layout("leaf-a", "leaf-b", "leaf-c")
        show(
            emptyList(),
            session("a", "idle", title = "Counting to 10", repo = "proj"),
            session("b", "idle", title = "Counting to 10", repo = null, cwd = "/Users/me/notes/"),
            session("c", "idle", title = "Something else", repo = null, cwd = "/Users/me/other"),
        )
        assertEquals(mapOf("a" to "proj", "b" to "notes", "c" to "other"), vm.state.rows.associate { it.id to it.where })
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
        val writer = session("a", "idle", taskId = "g1", title = "Writer")
        val reviewer = session("b", "idle", taskId = "g1", title = "Reviewer")
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
    fun aSearchResultForALoneSessionIsItsOwnRowAndAMatchOnTheTitleNeedsNoSessionName() {
        layout("leaf-a")
        val a = session("a", "idle", title = "alpha")
        show(emptyList(), a, session("old", "ended", endedAt = now - HOUR, title = "alpine"))
        vm.open()
        answer("al", AgentSessionMatch(a, "title", null, 0, 2, null))
        val r = vm.state.matches.single()
        assertEquals("a", r.id)
        assertTrue(r.matchInTitle)
        assertEquals("in title", r.extra)
    }

    @Test
    fun aSearchResultOpensTheSessionThatMatchedEvenWhenThatIsNotTheOneEnterOpensForTheTask() = runNow {
        layout("leaf-lead", "leaf-m")
        val lead = session("lead", "idle", taskId = "g1", title = "Lead")
        val m = session("m", "idle", taskId = "g1", title = "Mate")
        show(listOf(hostedTask(lead = "lead")), lead, m)
        vm.open()
        answer("mate", AgentSessionMatch(m, "title", null, 0, 4, null))
        assertEquals("m", vm.state.matches.single().recordId)
        vm.activateSelected()
        assertEquals(jumpTo("leaf-m"), sent)
    }

    @Test
    fun aSearchMatchOnATasksOwnSessionThatCarriesNoTaskIdIsTheTasksRowNotARowOfItsOwn() = runNow {
        layout("leaf-m")
        val lead = session("lead", "ended", endedAt = now - 5_000, title = "Lead chat")
        val m = session("m", "working", taskId = "g1", title = "Mate", lastActivityAt = now - 1_000)
        show(listOf(hostedTask(lead = "lead")), lead, m)
        vm.open()
        answer("needle", AgentSessionMatch(lead, "transcript", "…a needle…", 3, 6, null))
        val only = vm.state.matches.single()
        assertEquals("task:g1", only.id, "the own session's match is the task's row")
        assertEquals("lead", only.recordId)
        vm.activateSelected()
        assertEquals("conversation closed", vm.state.ask?.tag, "the own conversation, not just some session")

        answer(
            "needle",
            AgentSessionMatch(lead, "transcript", "…a needle…", 3, 6, null),
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
            listOf(hostedTask(lead = "x"), task(id = "g2", source = "agents", members = emptyList())),
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
    fun aSessionThatMadeTwoTasksAndBelongsToNeitherIsCountedOnceInTheChip() {
        layout("leaf-x")
        val x = session("x", "working", lastActivityAt = now - 1_000)
        show(
            listOf(
                hostedTask(lead = "x"),
                task(id = "g3", source = "agents", members = emptyList(), leadRecordId = "x"),
            ),
            x,
        )
        assertEquals("working", row("task:g1").stateWord)
        assertEquals("working", row("task:g3").stateWord)
        assertEquals(1, vm.state.workingCount, "one moving session is one working")
    }

    @Test
    fun aClickOnTheRowThatIsAskingAnswersTheQuestionInsteadOfWithdrawingIt() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", endedAt = now - 5_000),
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
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("lead")), sent)
    }

    // ---- selection ----

    @Test
    fun theOverlayOpensOnTheTaskThatNeedsYouFirstInListOrder() {
        show(
            listOf(task(members = listOf(member("g-run", "needs_you", since = 1_500)))),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
            session("early", "needs_you", since = 2_000),
        )
        vm.open()
        assertEquals("task:g1", vm.state.selectedId)
        assertEquals("◆", vm.state.selectedRow?.marker)
    }

    @Test
    fun theOverlayOpensOnTheFirstRowWhenNothingNeedsYou() {
        show(listOf(task(members = listOf(member("a", "working", recordId = "a"), member("b", "idle")))), session("a", "working"), session("w", "working", since = 99_999))
        vm.open()
        assertEquals("task:g1", vm.state.selectedId)
    }

    @Test
    fun withNoSessionsAndNoTasksThereAreNoRowsAndNothingIsSelected() {
        show(emptyList())
        vm.open()
        assertTrue(vm.state.rows.isEmpty())
        assertNull(vm.state.selectedId)
        assertFalse(vm.state.chipVisible)
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

    @Test
    fun peekAndCloseOnlyApplyToRowsWithASessionLunamuxHosts() = runNow {
        show(listOf(task(members = listOf(member("a", "needs_you"), member("b", "needs_you")))))
        vm.open()
        vm.select("task:g1")
        vm.peek()
        assertTrue(sent.isEmpty(), "a task with no session Lunamux hosts has nothing to peek")
        assertFalse(vm.state.rows.any { it.canClose })
    }

    // ---- idle grace ----

    @Test
    fun anIdleSessionStaysInNowForTenMinutesAfterItsLastActivityAndThenMovesToEarlier() {
        val grace = AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        assertEquals(10L * 60_000L, grace)
        layout("leaf-chat")
        show(emptyList(), session("chat", "idle", since = now - grace - 5_000, lastActivityAt = now - grace + 1))
        assertEquals(listOf("chat"), vm.state.now.map { it.id }, "one millisecond short of the grace")
        assertTrue(vm.state.earlier.isEmpty())
        assertEquals("done", vm.state.now.single().stateWord, "idle, never a diamond")

        now += 1
        vm.tick()
        assertTrue(vm.state.now.isEmpty(), "the grace is over exactly ten minutes after the activity")
        assertEquals(listOf("chat"), vm.state.earlier.map { it.id })
        assertEquals("finished", vm.state.earlier.single().stateWord)
    }

    @Test
    fun theGraceFollowsTheLastActivityAndFallsBackToWhenTheSessionWentIdle() {
        show(
            emptyList(),
            session("recent", "idle", since = now - 60_000),
            session("old", "idle", since = now - 11 * 60_000),
            session("busy-then-idle", "idle", since = now - 20 * 60_000, lastActivityAt = now - 30_000),
        )
        assertEquals(listOf("busy-then-idle", "recent"), vm.state.now.map { it.id }, "newest activity first")
        assertEquals(listOf("old"), vm.state.earlier.map { it.id })
    }

    @Test
    fun aGracedIdleSessionAndAJustClosedOneStayInNowInStartOrderLikeAnyOther() {
        show(
            listOf(task(members = listOf(member("run", "idle", label = "queued", recordId = "run"), member("run2", "idle", label = "queued")))),
            session("run", "idle", since = now - 1_000_000),
            session("graced", "idle", since = now - 1_000),
            session("work", "working", since = now - 5_000),
            session("need", "needs_you", since = now - 9_000),
            session("closed", "ended", since = now - 2_000, endedAt = now - 1_000),
            session("longClosed", "ended", since = now - 3_600_000, endedAt = now - 3_600_000),
        )
        assertEquals(listOf("task:g1", "need", "work", "closed", "graced"), vm.state.now.map { it.id })
        assertEquals(listOf("longClosed"), vm.state.earlier.map { it.id })
        assertEquals(1, vm.state.needYouCount)
    }

    @Test
    fun aTaskOfIdleHostedSessionsStaysInNowForTheGraceAndThenMovesToEarlierLikeASingleSession() {
        val grace = AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        layout("leaf-a", "leaf-b")
        show(
            listOf(hostedTask()),
            session("a", "idle", taskId = "g1", since = now - grace - 5_000, lastActivityAt = now - grace + 1),
            session("b", "idle", taskId = "g1", since = now - grace - 9_000),
        )
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id }, "one millisecond short of the grace")
        assertTrue(vm.state.earlier.isEmpty())

        now += 1
        vm.tick()
        assertTrue(vm.state.now.isEmpty(), "the grace is over ten minutes after the last activity of any session")
        assertEquals(listOf("task:g1"), vm.state.earlier.map { it.id })
    }

    @Test
    fun aTaskFileKeepsItsTaskInNowWhileNothingIsRunningAndWorkIsQueued() {
        show(listOf(task(members = listOf(member("a", "idle", label = "queued", recordId = "a"), member("b", "idle", label = "queued")))), session("a", "idle"))
        now += 10 * AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        vm.tick()
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id })
    }

    // ---- tabs ----

    @Test
    fun aFileTaskWithNoSessionInAnOpenTabIsInEarlierNotNowEvenWithWorkQueued() {
        showAway(
            listOf(task(members = listOf(member("a", "idle", label = "queued", recordId = "a"), member("b", "working")))),
            session("a", "idle"),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals("task:g1", vm.state.earlier.first().id)
        assertEquals(0, vm.state.workingCount)
    }

    @Test
    fun aFileTaskWhoseOnlyOpenSessionLeavesItsTabLeavesNow() {
        val t = listOf(task(members = listOf(member("a", "working", recordId = "a"), member("b", "idle", label = "queued"))))
        val a = session("a", "working", lastActivityAt = now - 1_000)
        show(t, a)
        assertEquals("task:g1", vm.state.now.first().id)
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one")), activeTabId = "tab-1"))
        assertTrue(vm.state.now.isEmpty())
        assertEquals("task:g1", vm.state.earlier.first().id)
    }

    @Test
    fun aTaskWithAnOutOfTabQuestionStaysInNowAndIsCounted() {
        showAway(
            listOf(task(members = listOf(member("a", "needs_you", recordId = "a"), member("b", "working")))),
            session("a", "needs_you"),
        )
        assertEquals("task:g1", vm.state.now.first().id)
        assertEquals(1, vm.state.needYouCount)
    }

    @Test
    fun aTaskKeepsAllItsSessionsInNowWhenOnlyOneOfThemIsInAnOpenTab() {
        layout("leaf-a")
        show(
            listOf(task(members = listOf(member("a", "working", recordId = "a"), member("b", "working", recordId = "b")))),
            session("a", "working", lastActivityAt = now - 1_000),
            session("b", "working", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id })
        assertEquals("2 working", row("task:g1").stateWord)
        assertEquals(2, vm.state.workingCount, "the task is the unit: its out-of-tab member counts with the task")
    }

    @Test
    fun aBackgroundSessionIsInNowAndCountedLikeAnyOther() {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg-ask", "leaf-bg-work"))
        show(
            emptyList(),
            session("n", "working", since = 1_000, lastActivityAt = now - 1_000),
            session("bg-ask", "needs_you", since = 2_000, background = true, question = "Which branch?"),
            session("bg-work", "working", since = 3_000, background = true, lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("n", "bg-ask", "bg-work"), vm.state.now.map { it.id })
        assertEquals(1, vm.state.needYouCount, "a background session that needs the person is in the chip")
        assertEquals(2, vm.state.workingCount)
        assertTrue(vm.state.chipVisible)
        assertTrue(row("bg-ask").background)
        assertFalse(row("n").background)
    }

    @Test
    fun enterOnABackgroundRowWatchesInsteadOfJumpingAndCommandEnterTakesTheTabOver() = runNow {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(emptyList(), session("n", "working", lastActivityAt = now - 1_000), session("bg", "needs_you", background = true, since = 2_000))
        vm.open()
        vm.select("bg")
        assertEquals(Enter.WATCH, vm.state.selectedRow?.enter)
        vm.open()
        vm.select("bg")
        vm.peek()
        assertTrue(vm.state.peek != null && vm.state.peek!!.background, "the peek (→) knows it can take the tab over")
        sent.clear()
        vm.takeOverSelected()
        assertEquals(
            listOf<WindowCommand>(WindowCommand.TakeOverTab("tab-bg"), WindowCommand.SetFocusedPane("tab-bg", "leaf-bg")),
            sent,
        )
        assertFalse(vm.state.open, "taking over closes the overlay")
    }

    @Test
    fun takingOverDoesNothingForASessionInAnOrdinaryTab() = runNow {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(emptyList(), session("n", "needs_you"), session("bg", "working", since = 2_000, background = true))
        vm.open()
        vm.select("n")
        vm.takeOverSelected()
        assertTrue(sent.isEmpty())
        assertTrue(vm.state.open)
    }

    // ---- order ----

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
        val tasks = listOf(task(source = "agents", startedAt = 2_500, members = emptyList()))
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
        layout("leaf-lead", "leaf-a", "leaf-b")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "working", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id }, "the own session is never a row of its own")
        assertEquals("3 working", row("task:g1").stateWord)

        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("b", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("3 working", row("task:g1").stateWord)
    }

    @Test
    fun anEndedOwnSessionWithNoTaskIdStaysInTheTasksRunsSoItsResumeIsNeverASecondRow() = runNow {
        layout("leaf-a")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", endedAt = now - 5_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("working", row("task:g1").stateWord, "an ended session is not moving")
        // The resumed conversation comes back as a live session with the same record: still the same row.
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "working", lastActivityAt = now - 1_000),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("2 working", row("task:g1").stateWord)
    }

    @Test
    fun aSessionThatIsNotMovingIsNotCountedAsWorking() {
        layout("leaf-lead", "leaf-a")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "idle", taskId = "g1"),
            session("a", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertEquals("working", row("task:g1").stateWord, "the idle own session is not counted")

        show(emptyList(), session("lone", "idle", since = now - 60_000))
        assertTrue(row("lone").stateWord != "working", "an idle lone session never reads working, it reads ${row("lone").stateWord}")
    }

    @Test
    fun theFooterReadsOpenForAPaneThatIsShownAndWatchForOneInTheBackground() = runNow {
        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(
            emptyList(),
            session("n", "working", lastActivityAt = now - 1_000),
            session("bg", "working", background = true, lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("n")
        assertEquals("↵ open · ↑↓ move · esc close", footerText())
        vm.select("bg")
        assertEquals("↵ watch · ↑↓ move · esc close", footerText())
    }

    @Test
    fun theFirstEnterOnAClosedOwnConversationAsksAndTheSecondResumesItInANewTab() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", endedAt = now - 5_000),
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
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("lead")), sent)
        assertNull(vm.state.ask)
    }

    @Test
    fun aClosedLoneSessionAsksWithADifferentTagThatNamesOnlyTheSession() = runNow {
        layout("leaf-m")
        show(emptyList(), session("old", "ended", endedAt = now - HOUR), session("m", "working", lastActivityAt = now - 1_000))
        vm.open()
        vm.select("old")
        vm.activateSelected()
        assertEquals("session closed", vm.state.ask?.tag)
        assertEquals("Resume it in a new tab?", vm.state.ask?.question)
        assertTrue(listOf("done", "finished").all { vm.state.ask?.tag?.contains(it) != true }, "the tag never sounds like the whole task's state")
    }

    @Test
    fun aTaskWithNothingToResumeAsksToStartOneAndOnlyTheSecondEnterRunsItsOpenAction() = runNow {
        show(listOf(task(hasOpen = true, members = emptyList())))
        vm.open()
        vm.select("task:g1")
        vm.activateSelected()
        assertEquals("no conversation", vm.state.ask?.tag)
        assertEquals("Start one in a new tab?", vm.state.ask?.question)
        assertEquals("↵ start · esc cancel", footerText())
        assertTrue(sent.isEmpty())
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
    }

    @Test
    fun theQuestionAndThePeekReplaceEachOtherAndEscStepsBackThroughThem() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.peek()
        assertTrue(vm.state.peek != null)
        vm.activateSelected()
        assertTrue(vm.state.ask != null, "the first enter asks even while a peek is open")
        assertNull(vm.state.peek, "the question takes the peek's place, so the two are never up together")
        vm.back()
        assertNull(vm.state.ask, "the first esc cancels the question")
        assertTrue(vm.state.open, "and leaves the overlay")
        vm.peek()
        vm.activateSelected()
        vm.peek()
        assertNull(vm.state.ask, "opening the peek withdraws the question")
        assertTrue(vm.state.peek != null)
        vm.back()
        assertNull(vm.state.peek, "esc closes the peek")
        assertTrue(vm.state.open)
        vm.back()
        assertFalse(vm.state.open, "the last one closes the overlay")
    }

    @Test
    fun escapeMovingOrANewQueryCancelsTheAskAndTheNextEnterAsksAgain() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", endedAt = now - 5_000),
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
    fun aRefreshThatChangesWhatEnterWouldDoCancelsTheAsk() = runNow {
        layout("leaf-m")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "ended", taskId = "g1", endedAt = now - 5_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        vm.open()
        vm.select("task:g1")
        vm.activateSelected()
        assertTrue(vm.state.ask != null)
        // The conversation was resumed elsewhere: ↵ now means "go to it", so the question is void.
        layout("leaf-m", "leaf-lead")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "working", taskId = "g1", lastActivityAt = now - 1_000),
            session("m", "working", taskId = "g1", lastActivityAt = now - 1_000),
        )
        assertNull(vm.state.ask)
        assertEquals(Enter.JUMP, row("task:g1").enter)
        assertEquals("↵ open · ↑↓ move · esc close", footerText())
    }

    @Test
    fun theStatusIsRepeatedInFullOnlyForTheSelectedRowWhenItsCellCutIt() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(hostedTask(lead = "lead", status = "A very long status that no cell can hold")),
            session("lead", "working", taskId = "g1", lastActivityAt = now - 1_000),
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
        layout("leaf-lead")
        show(
            listOf(hostedTask(lead = "lead")),
            session("lead", "working", taskId = "g1", lastActivityAt = now - 1_000, cwdShown = "~/code/lunamux"),
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
            layoutNamed(name, "leaf-lead")
            show(listOf(hostedTask(lead = "lead")), session("lead", "working", taskId = "g1", lastActivityAt = now - 1_000))
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

    @Test
    fun aTitleWithNoWordsIsNeverContainedSoItsTabIsStillNamed() {
        layoutNamed("notes", "leaf-a")
        show(emptyList(), session("a", "working", title = "—·—", lastActivityAt = now - 1_000))
        assertEquals("notes", tabChip("a"))
    }

    @Test
    fun wordsWithLettersFromOtherAlphabetsAreComparedWithoutCase() {
        layoutNamed("ÅÄÖ-projektet", "leaf-a")
        show(emptyList(), session("a", "working", title = "åäö Projektet", lastActivityAt = now - 1_000))
        assertNull(tabChip("a"))
    }

    @Test
    fun aTabInAnotherWorldIsNamedWithTheWorldAndASessionInABackgroundTabHasNoTabChip() {
        layoutTwoWorlds(inWork = listOf("leaf-w"))
        show(emptyList(), session("w", "working", title = "build", lastActivityAt = now - 1_000))
        assertEquals("t1 · Work", tabChip("w"))

        layoutWithBackground(listOf("leaf-n"), listOf("leaf-bg"))
        show(emptyList(), session("bg", "working", title = "build", background = true, lastActivityAt = now - 1_000))
        assertNull(tabChip("bg"), "a background tab is not a place a person can find it")
    }

    @Test
    fun aRowHasNoChipsButTheFolderAndTheTab() {
        layoutNamed("notes", "leaf-a")
        show(emptyList(), session("a", "working", title = "build", lastActivityAt = now - 1_000, cwdShown = "~/x", repo = "proj"))
        assertEquals(
            listOf(AgentsOverviewBackingViewModel.ChipKind.FOLDER, AgentsOverviewBackingViewModel.ChipKind.TAB),
            row("a").chips.map { it.kind },
        )
    }

    // ---- moving, quiet and stale ----

    @Test
    fun aTaskFileIsStaleTwentyMinutesAfterItWasLastRefreshed() {
        val limit = AgentsOverviewBackingViewModel.STALE_AFTER_MS
        assertEquals(20 * 60_000L, limit)
        assertFalse(AgentsOverviewBackingViewModel.isStale(10_000, 10_000 + limit - 1))
        assertTrue(AgentsOverviewBackingViewModel.isStale(10_000, 10_000 + limit))
    }

    @Test
    fun aWorkingSessionGoesQuietAfterFiveMinutesWithoutASignOfLife() {
        val q = AgentsOverviewBackingViewModel.QUIET_AFTER_MS
        assertEquals(5 * 60_000L, q)
        assertFalse(AgentsOverviewBackingViewModel.isQuiet(10_000, 10_000 + q - 1))
        assertTrue(AgentsOverviewBackingViewModel.isQuiet(10_000, 10_000 + q))
    }

    private companion object {
        const val EARLIER_LIMIT = AgentsOverviewBackingViewModel.EARLIER_LIMIT
        const val MINUTE = 60_000L
        const val HOUR = 60L * 60_000L
        const val DAY = 24L * 60 * 60_000L
    }
}
