/**
 * Tests for the task part of [AgentsOverviewBackingViewModel]: where a task sits among the single
 * sessions (it stays in NOW while it has open work, and goes to EARLIER once every session reads done or ended), when a task is an expansion panel
 * and when it is one plain row, what a collapsed and an expanded panel show and in which order,
 * the glyph, status and counts of each session, which session the header's ↵ goes to (and the
 * task's `open` action, and expanding when there is neither), a session Lunamux does not host and
 * one opened with `claude attach`, the arrow and space keys, the selection staying on its row,
 * and when a working session counts as live, quiet or stale.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Footer
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Glyph
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.RowKind
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
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, links = links,
        repo = repo, branch = null, cwd = cwd, cwdHome = cwdHome, nowLine = nowLine, question = question,
        leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt, lastActivityAt = lastActivityAt,
        lastSaid = lastSaid, firstPrompt = firstPrompt, taskId = taskId,
    )

    private fun layout(vararg leafIds: String) {
        val panes = leafIds.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes)), activeTabId = "tab-1"))
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
        refreshedAtMillis: Long? = null, source: String = "file", repo: String? = "proj",
    ) = AgentTask(
        id = id, title = "Release $id", repo = repo, source = source, hasOpen = hasOpen,
        members = members.mapIndexed { i, m -> if (m.key.isEmpty()) m.copy(key = "#${i + 1}") else m },
        refreshedAtMillis = refreshedAtMillis,
    )

    private fun show(tasks: List<AgentTask>, vararg sessions: AgentSessionSummary) {
        windowState.updateAgentSessions(sessions.toList(), true)
        windowState.updateAgentTasks(tasks)
    }

    @Test
    fun aTaskWithASessionThatNeedsYouSitsInNowRankedByThatSession() {
        show(
            listOf(task(members = listOf(member("a", "needs_you", since = 2_000), member("b", "working", since = 100)))),
            session("old-working", "working", since = 500),
            session("late-needs", "needs_you", since = 3_000),
            session("early-needs", "needs_you", since = 1_000),
        )
        assertEquals(
            listOf("early-needs", "task:g1", "member:g1:#1", "member:g1:#2", "late-needs", "old-working"),
            vm.state.now.map { it.id },
        )
    }

    @Test
    fun aWorkingOnlyTaskIsInNowBehindEveryoneWhoNeedsYouAndRankedByWait() {
        show(
            listOf(task(members = listOf(member("a", "working", since = 1), member("b", "working", since = 2)))),
            session("n", "needs_you", since = 9_000),
            session("w", "working", since = 5_000),
        )
        // The task's working sessions have waited longest of the working ones, so it comes first.
        assertEquals(listOf("n", "task:g1", "member:g1:#2", "member:g1:#1", "w"), vm.state.now.map { it.id })
    }

    @Test
    fun aTaskStaysInNowWhileASessionHasOpenWorkEvenWhenNothingIsRunning() {
        show(
            listOf(task(members = listOf(member("a", "idle", label = "in review"), member("b", "ended")))),
            session("busy", "working", since = 1),
        )
        assertEquals(listOf("busy", "task:g1"), vm.state.now.map { it.id })
        assertTrue(vm.state.earlier.isEmpty())
    }

    @Test
    fun aFileTaskWhoseSessionsAllReadDoneMovesToEarlierEvenThoughNoneEnded() {
        show(
            listOf(
                task(id = "g1", members = listOf(member("a", "idle", label = "done"), member("b", "idle", label = "ready"))),
                task(id = "g2", members = listOf(member("c", "idle", label = "done"), member("d", "ended"))),
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
                task(id = "g2", members = listOf(member("c", "idle", label = "done"), member("d", "working"))),
                task(id = "g3", members = listOf(member("e", "idle", label = "done"), member("f", "idle", label = "in review"))),
                task(id = "g4", members = listOf(member("g", "idle", label = "done"), member("h", "idle"))),
            ),
        )
        assertEquals(setOf("task:g1", "task:g2", "task:g3", "task:g4"), vm.state.now.map { it.id }.filter { it.startsWith("task:") }.toSet())
        assertTrue(vm.state.earlier.isEmpty())
    }

    @Test
    fun aDoneOnlyFileTaskGoesToEarlierAtOnceWithoutTheGraceAHostedTaskGets() {
        show(listOf(task(members = listOf(member("a", "idle", label = "done", since = now - 1_000), member("b", "idle", label = "done", since = now - 2_000)))))
        assertTrue(vm.state.now.isEmpty())
        assertEquals(listOf("task:g1"), vm.state.earlier.map { it.id })
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
    fun aTaskIsOneUnitInTheSecondBandsLimit() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        val tasks = (1..6).map {
            task(id = "g$it", members = listOf(member("m", "ended", since = now - 5 * DAY + it), member("n", "ended", since = now - 5 * DAY + it)))
        }
        show(tasks)
        assertTrue(vm.state.recent.isEmpty())
        assertEquals(EARLIER_LIMIT, vm.state.fortnight.count { it.kind == RowKind.TASK })
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
            session("fresh", "ended", endedAt = now - 1_000),
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
        assertEquals(now - DAY, vm.state.tasks.getValue("mixed").ageMillis)
        assertEquals(now - 5 * DAY, vm.state.tasks.getValue("slow").ageMillis)
        assertFalse("task:ancient" in vm.state.rows.map { it.id }, "older than two weeks is only found by search")
    }

    @Test
    fun moreAppearsOnlyForTheSecondBand() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        val recent = (1..8).map { session("r$it", "ended", endedAt = now - it * 1_000L) }
        val older = (1..7).map { session("o$it", "ended", endedAt = now - 5 * DAY - it) }
        show(emptyList(), *(recent + older).toTypedArray())
        assertEquals(8, vm.state.recent.size, "the first band is never cut")
        assertEquals(EARLIER_LIMIT, vm.state.fortnight.size)
        assertEquals(2, vm.state.earlierHidden)
        vm.showMoreEarlier()
        assertEquals(7, vm.state.fortnight.size)
        assertEquals(0, vm.state.earlierHidden)
    }

    @Test
    fun anEarlierTaskIsACollapsedHeaderWithItsSessionCountAndAgeAndNowKeepsItsCountsWithoutAge() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        show(
            listOf(
                task(id = "done", members = listOf(member("a", "ended", since = now - 2 * DAY), member("b", "ended", since = now - 3 * DAY), member("c", "ended", since = now - 4 * DAY))),
                task(id = "live", members = listOf(member("x", "working", since = now - 100), member("y", "ended", since = now - DAY))),
            ),
        )
        val done = vm.state.tasks.getValue("done")
        assertEquals("3 sessions", done.summary)
        assertEquals(now - 2 * DAY, done.ageMillis)
        assertEquals(listOf("task:done"), vm.state.recent.map { it.id }, "collapsed: the sessions wait behind the header")
        val live = vm.state.tasks.getValue("live")
        assertNull(live.ageMillis)
        assertTrue(live.summary.contains("done"), live.summary)
    }

    @Test
    fun expandingAnEarlierTaskListsItsSessionsAndTheDownArrowCrossesFromTheFirstBandToTheSecond() {
        now = 30 * DAY // the bands are days wide; the usual clock is only hours in
        layout()
        show(
            listOf(task(id = "g1", members = listOf(member("a", "ended", since = now - DAY, recordId = "s-a"), member("b", "ended", since = now - 2 * DAY, recordId = "s-b")))),
            session("s-a", "ended", endedAt = now - DAY),
            session("s-b", "ended", endedAt = now - 2 * DAY),
            session("later", "ended", endedAt = now - 6 * DAY),
        )
        assertEquals(listOf("task:g1"), vm.state.recent.map { it.id })
        vm.select("task:g1")
        vm.toggleTask("g1")
        assertEquals(listOf("task:g1", "s-a", "s-b"), vm.state.recent.map { it.id })
        assertEquals(listOf("resume", "resume"), vm.state.recent.drop(1).map { it.actionLabel })
        vm.select("s-b")
        vm.moveSelection(1)
        assertEquals("later", vm.state.selectedId, "↓ from the last row of band 1 lands on the first of band 2")
        vm.moveSelection(-1)
        assertEquals("s-b", vm.state.selectedId)
    }

    @Test
    fun collapsedShowsTheHeaderAndTheSessionsThatNeedYouOrWorkAndTheCountsSayTheRest() {
        show(listOf(task(members = listOf(
            member("a", "needs_you"), member("b", "working"), member("c", "idle", label = "in review"),
        ))))
        val rows = vm.state.rows
        assertEquals(listOf("task:g1", "member:g1:#1", "member:g1:#2"), rows.map { it.id }, "needs you first, then working")
        assertEquals(RowKind.TASK, rows[0].kind)
        assertEquals(Glyph.NONE, rows[0].glyph, "a header never carries a state glyph, least of all the ◆")
        val g = vm.state.tasks.getValue("g1")
        assertEquals(1, g.needYouCount)
        assertEquals("1 working · 1 in review", g.summary)
        assertFalse(g.expanded)
    }

    @Test
    fun headerCountsComeInAFixedOrderWithNoZerosAndUnknownLabelsAsGiven() {
        show(listOf(task(members = listOf(
            member("a", "idle", label = "done", links = listOf("#22")),
            member("b", "idle", label = "ready"),
            member("c", "idle", label = "held: not merging yet"),
            member("d", "idle", label = "weird thing"),
            member("e", "idle", label = "waiting"),
            member("f", "idle", label = "queued"),
            member("g", "idle", label = "in review · round 2"),
            member("h", "working"),
            member("i", "idle"),
        ))))
        assertEquals(
            "1 working · 1 in review · 2 queued · 1 held: not merging yet · 1 weird thing · 1 idle · 2 done",
            vm.state.tasks.getValue("g1").summary,
        )
    }

    @Test
    fun expandingShowsEverySessionMostUrgentFirstAndKeepsTheHighlightOnTheHeader() {
        show(listOf(task(members = listOf(
            member("a", "working"), member("b", "needs_you"), member("c", "idle", label = "done"),
            member("d", "idle", label = "held"), member("e", "idle", label = "queued"), member("f", "idle", label = "in review"),
        ))))
        vm.open()
        vm.toggleTask("g1")
        assertEquals(
            listOf("task:g1", "member:g1:#2", "member:g1:#1", "member:g1:#6", "member:g1:#5", "member:g1:#4", "member:g1:#3"),
            vm.state.rows.map { it.id },
        )
        assertTrue(vm.state.tasks.getValue("g1").expanded)
        assertEquals("task:g1", vm.state.selectedId)
        vm.toggleTask("g1")
        assertEquals(3, vm.state.rows.size, "collapsed again: the header, the one that needs you and the one that works")
    }

    @Test
    fun needsYouSessionsInAnExpandedTaskAreOrderedLongestWaitingFirst() {
        show(listOf(task(members = listOf(member("late", "needs_you", since = 9_000), member("early", "needs_you", since = 1_000)))))
        assertEquals(listOf("task:g1", "member:g1:#2", "member:g1:#1"), vm.state.rows.map { it.id })
    }

    @Test
    fun anExpandedTaskStaysExpandedWhenTheOverlayIsClosedAndOpenedAgain() {
        show(listOf(task(members = listOf(member("a", "working"), member("b", "idle")))))
        vm.open()
        vm.toggleTask("g1")
        vm.close()
        assertFalse(vm.state.open)
        vm.open()
        assertTrue(vm.state.tasks.getValue("g1").expanded)
    }

    @Test
    fun chipCountsIncludeTaskSessionsEvenWhenCollapsed() {
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
    fun aTaskWithOneSessionIsThatSessionsPlainRowAndNotAPanel() {
        layout("leaf-r1")
        show(
            listOf(task(members = listOf(member("Task one", "needs_you", recordId = "r1", line = "Which branch?")))),
            session("r1", "needs_you"),
        )
        val rows = vm.state.rows
        assertEquals(listOf("r1"), rows.map { it.id })
        assertEquals(RowKind.SESSION, rows[0].kind)
        assertEquals("Task one", rows[0].title)
        assertEquals("Which branch?", rows[0].status)
        assertEquals(Glyph.NEEDS_YOU, rows[0].glyph)
        assertEquals("g1", rows[0].taskId)
        assertEquals("#1", rows[0].memberKey)
        assertTrue(vm.state.tasks.isEmpty())
    }

    @Test
    fun aTaskWithTwoSessionsIsAPanelAndItsHostedSessionsAreNotListedAgain() {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task(members = listOf(
                member("One", "needs_you", recordId = "r1", line = "Which branch?"),
                member("Two", "working", recordId = "r2"),
            ))),
            session("r1", "needs_you"),
            session("r2", "working"),
        )
        assertEquals(listOf("task:g1", "r1", "r2"), vm.state.rows.map { it.id })
        assertEquals(listOf("g1"), vm.state.tasks.keys.toList())
        assertEquals(1, vm.state.rows.count { it.id == "r1" })
    }

    @Test
    fun aTaskWithOneSessionAndAnOpenActionIsAPanel() {
        show(listOf(task(hasOpen = true, members = listOf(member("a", "working")))))
        assertEquals(listOf("task:g1", "member:g1:#1"), vm.state.rows.map { it.id })
        assertTrue(vm.state.tasks.getValue("g1").hasOpen)
    }

    @Test
    fun aTaskWithNoSessionsAndAnOpenActionIsAHeaderWhoseSummarySaysSo() {
        show(listOf(task(hasOpen = true, members = emptyList())))
        assertEquals(listOf("task:g1"), vm.state.rows.map { it.id })
        assertEquals("no sessions", vm.state.tasks.getValue("g1").summary)
        assertEquals("open", vm.state.rows.single().actionLabel)
    }

    @Test
    fun hostedSessionsThatCarryATaskIdFormAPanelWithoutAnyTaskFile() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(task(source = "agents", members = emptyList())),
            session("a", "working", taskId = "g1", nowLine = "Edit a.kt"),
            session("b", "needs_you", taskId = "g1", question = "Which branch?"),
            session("loner", "working", since = 99_999),
        )
        assertEquals(listOf("task:g1", "b", "a", "loner"), vm.state.now.map { it.id })
        assertEquals(1, vm.state.tasks.getValue("g1").needYouCount)
        assertEquals("1 working", vm.state.tasks.getValue("g1").summary)
        assertEquals("Which branch?", vm.state.rows.first { it.id == "b" }.status)
        assertEquals("g1", vm.state.rows.first { it.id == "a" }.taskId)
        assertNull(vm.state.rows.first { it.id == "a" }.memberKey, "a session no file lists has no file member key")
    }

    @Test
    fun aSessionInAPanelKeepsItsRepoOrFolderAndAPullRequestTakesTheColumnWhenTheFileGivesOne() {
        layout("leaf-a", "leaf-b", "leaf-c")
        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "working", taskId = "g1", repo = null, cwd = "/work/notes"),
            session("c", "working", taskId = "g1", repo = "lunamux"),
        )
        val where = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.id to it.where }
        assertEquals(mapOf("a" to "lunamux", "b" to "notes", "c" to "lunamux"), where)

        show(
            listOf(task(members = listOf(member("a", "working", recordId = "a", links = listOf("#22")), member("x", "idle")))),
            session("a", "working", repo = "lunamux"),
        )
        assertEquals("#22", vm.state.rows.first { it.id == "a" }.where)
    }

    @Test
    fun anAgentMadeTaskHeaderShowsTheRepoItsSessionsShareAndNoneWhenTheyDiffer() {
        layout("leaf-a", "leaf-b")
        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("lunamux", vm.state.tasks.getValue("g1").repo)
        assertEquals("lunamux", vm.state.rows.first { it.kind == RowKind.TASK }.repo)

        show(
            listOf(task(source = "agents", repo = null, members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "other"),
        )
        assertNull(vm.state.tasks.getValue("g1").repo)

        show(
            listOf(task(source = "file", repo = "named", members = emptyList())),
            session("a", "working", taskId = "g1", repo = "lunamux"),
            session("b", "idle", taskId = "g1", repo = "lunamux"),
        )
        assertEquals("named", vm.state.tasks.getValue("g1").repo, "the file's repository wins")
    }

    @Test
    fun aTaskWithAnOpenActionStillRunsItFromItsOwnControlWhileASessionIsLive() = runNow {
        layout("leaf-r1")
        show(
            listOf(task(hasOpen = true, members = listOf(member("a", "working", recordId = "r1"), member("b", "idle")))),
            session("r1", "working"),
        )
        val header = vm.state.rows.first { it.kind == RowKind.TASK }
        assertEquals("jump", header.actionLabel, "↵ goes to the live session")
        assertTrue(vm.state.tasks.getValue("g1").hasOpen, "the header still offers the open control")
        vm.openTask(header)
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
    }

    @Test
    fun aTaskTheServerKnowsWithOneHostedSessionIsThatSessionsPlainRow() {
        layout("leaf-a")
        show(listOf(task(source = "agents", members = emptyList())), session("a", "working", taskId = "g1"))
        assertEquals(listOf("a"), vm.state.rows.map { it.id })
        assertTrue(vm.state.tasks.isEmpty())
    }

    @Test
    fun aTaskOfIdleHostedSessionsStaysInNowForTheGraceAndThenMovesToEarlierLikeASingleSession() {
        val grace = AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        layout("leaf-a", "leaf-b")
        show(
            listOf(task(source = "agents", members = emptyList())),
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
        show(listOf(task(members = listOf(member("a", "idle", label = "queued"), member("b", "idle", label = "queued")))))
        now += 10 * AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        vm.tick()
        assertEquals(listOf("task:g1"), vm.state.now.map { it.id })
    }

    @Test
    fun aHostedSessionOfAFileTaskThatNoMemberClaimsJoinsTheFilesMembers() {
        layout("leaf-r1", "leaf-x")
        show(
            listOf(task(members = listOf(member("Listed", "working", recordId = "r1")))),
            session("r1", "working"),
            session("x", "needs_you", taskId = "g1"),
        )
        assertEquals(listOf("task:g1", "x", "r1"), vm.state.rows.map { it.id })
    }

    @Test
    fun twoMembersThatShareASessionIdGetDistinctRowsAndDistinctOpenKeys() = runNow {
        show(listOf(task(members = listOf(
            member("first", "needs_you", since = 1_000, sessionId = "s1", key = "s1"),
            member("second", "needs_you", since = 2_000, sessionId = "s1", key = "s1#2"),
        ))))
        assertEquals(listOf("task:g1", "member:g1:s1", "member:g1:s1#2"), vm.state.rows.map { it.id })
        vm.select("member:g1:s1#2")
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTaskMember("g1", "s1#2")), sent)
    }

    @Test
    fun returnOnAHeaderJumpsToTheSessionThatHasWaitedLongestForThePerson() = runNow {
        layout("leaf-r1", "leaf-r2", "leaf-r3")
        show(
            listOf(task(members = listOf(
                member("late", "needs_you", since = 2_000, recordId = "r1"),
                member("early", "needs_you", since = 1_000, recordId = "r2"),
                member("busy", "working", recordId = "r3"),
            ))),
            session("r1", "needs_you"), session("r2", "needs_you"), session("r3", "working", lastActivityAt = 9_000),
        )
        val header = vm.state.rows.first { it.kind == RowKind.TASK }
        assertEquals("jump", header.actionLabel)
        assertEquals("r2", vm.state.tasks.getValue("g1").jumpRecordId)
        vm.activate(header)
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-r2")), sent)
    }

    @Test
    fun returnOnAHeaderWithNobodyWaitingJumpsToTheWorkingSessionWithTheFreshestSignOfLife() = runNow {
        layout("leaf-r1", "leaf-r2")
        show(
            listOf(task(members = listOf(
                member("older", "working", recordId = "r1"), member("fresher", "working", recordId = "r2"),
            ))),
            session("r1", "working", lastActivityAt = 7_000), session("r2", "working", lastActivityAt = 9_000),
        )
        vm.activate(vm.state.rows.first { it.kind == RowKind.TASK })
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-r2")), sent)
    }

    @Test
    fun returnOnAHeaderWithNoOpenSessionRunsTheOpenActionWhenTheTaskHasOne() = runNow {
        layout("leaf-other")
        show(
            listOf(task(hasOpen = true, members = listOf(member("a", "working", recordId = "r1"), member("b", "idle")))),
            session("r1", "working"),
        )
        val header = vm.state.rows.first { it.kind == RowKind.TASK }
        assertEquals("open", header.actionLabel, "a session whose pane is not in the layout is not open")
        vm.activate(header)
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
    }

    @Test
    fun returnOnAHeaderWithNothingToJumpToOrOpenExpandsAndCollapsesIt() = runNow {
        show(listOf(task(members = listOf(member("a", "idle", label = "queued"), member("b", "idle", label = "queued")))))
        val header = vm.state.rows.first { it.kind == RowKind.TASK }
        assertEquals("", header.actionLabel)
        vm.activate(header)
        assertTrue(sent.isEmpty())
        assertTrue(vm.state.tasks.getValue("g1").expanded)
        vm.activate(vm.state.rows.first { it.kind == RowKind.TASK })
        assertFalse(vm.state.tasks.getValue("g1").expanded)
    }

    @Test
    fun anEndedSessionIsNeverTheJumpTarget() = runNow {
        layout("leaf-w")
        show(
            listOf(task(hasOpen = true, members = listOf(member("gone", "ended", recordId = "r1"), member("w", "working", recordId = "w")))),
            session("r1", "ended", endedAt = 5_000),
            session("w", "working"),
        )
        assertEquals("w", vm.state.tasks.getValue("g1").jumpRecordId)
    }

    @Test
    fun rightOnAHeaderWithAnOpenSessionPeeksAtTheSessionReturnWouldJumpTo() = runNow {
        layout("leaf-r1")
        show(
            listOf(task(members = listOf(member("a", "needs_you", recordId = "r1"), member("b", "idle")))),
            session("r1", "needs_you"),
        )
        vm.open()
        vm.select("task:g1")
        vm.peek()
        assertEquals(listOf<WindowCommand>(WindowCommand.InspectAgentSession("r1")), sent)
    }

    @Test
    fun aWorkingSessionOnlyTheFileVouchesForKeepsItsLabelAndAHostedOneDropsIt() {
        layout("leaf-h")
        show(
            listOf(task(members = listOf(
                member("file only", "working", label = "coding", line = "Edit a.kt"),
                member("bare", "working", since = 2_000, label = "fixing"),
                member("hosted", "working", since = 3_000, label = "queued", recordId = "h", line = "Edit b.kt"),
            ))),
            session("h", "working"),
        )
        vm.toggleTask("g1")
        val rows = vm.state.rows.associateBy { it.title }
        assertEquals("coding", rows.getValue("file only").label)
        assertEquals("Edit a.kt", rows.getValue("file only").status)
        assertEquals("fixing", rows.getValue("bare").label)
        assertEquals("", rows.getValue("bare").status)
        assertNull(rows.getValue("hosted").label)
    }

    @Test
    fun aSessionWithAnIdOpensWithClaudeAttachAndOneWithoutHasNothingToOpen() = runNow {
        show(listOf(task(members = listOf(
            member("attachable", "needs_you", sessionId = "ab12cd"),
            member("nothing", "needs_you", since = 2_000),
        ))))
        val rows = vm.state.rows.associateBy { it.title }
        assertTrue(rows.getValue("attachable").unhosted)
        assertEquals("ab12cd", rows.getValue("attachable").sessionId)

        vm.select(rows.getValue("attachable").id)
        assertEquals(Footer.RUN_ATTACH, vm.state.footer)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTaskMember("g1", "ab12cd")), sent)

        sent.clear()
        vm.select(rows.getValue("nothing").id)
        assertEquals(Footer.NO_SESSION, vm.state.footer)
        vm.activateSelected()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theOverlayStaysOpenAfterAnAttachUntilTheNewTabIsActive() = runNow {
        layout("leaf-other")
        show(listOf(task(members = listOf(member("a", "needs_you", sessionId = "ab12cd")))))
        vm.open()
        vm.activate(vm.state.rows.last())
        assertTrue(vm.state.open)
        windowState.updateConfig(windowState.config.value!!.copy(activeTabId = "new-tab"))
        assertFalse(vm.state.open)
    }

    @Test
    fun aSessionIsJumpWhenItsPaneIsOpenResumeWhenItEndedAndOpenOnlyWithASessionId() {
        layout("leaf-live")
        show(
            listOf(task(members = listOf(
                member("live one", "working", recordId = "live"),
                member("done one", "idle", recordId = "done"),
                member("nobody's", "idle"),
                member("attachable", "idle", sessionId = "xy9"),
            ))),
            session("live", "working"),
            session("done", "ended", endedAt = 5_000),
        )
        vm.toggleTask("g1")
        val labels = vm.state.rows.filter { it.taskId == "g1" && it.kind == RowKind.SESSION }.associate { it.title to it.actionLabel }
        assertEquals(mapOf("live one" to "jump", "done one" to "resume", "nobody's" to "", "attachable" to "open"), labels)
    }

    @Test
    fun aTaskSessionShowsOnlyTheLinksTheFileGivesNotOnesReadFromItsBranch() {
        layout("leaf-r1")
        show(
            listOf(task(members = listOf(member("a", "working", recordId = "r1"), member("b", "idle", recordId = "r2", links = listOf("ABC-2"))))),
            session("r1", "working", links = listOf("ABC-1")),
            session("r2", "idle", links = listOf("ABC-9")),
        )
        vm.toggleTask("g1")
        val links = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.links }
        assertEquals(mapOf("a" to emptyList(), "b" to listOf("ABC-2")), links)
    }

    @Test
    fun theGlyphOfASessionFollowsItsStateAndLabel() {
        show(listOf(task(members = listOf(
            member("n", "needs_you"), member("w", "working"), member("r", "idle", label = "in review"),
            member("q", "idle", label = "queued"), member("p", "idle", label = "held up"), member("d", "idle", label = "done"),
            member("e", "ended"), member("i", "idle"), member("x", "idle", label = "unknown"),
        ))))
        vm.toggleTask("g1")
        val glyphs = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.glyph }
        assertEquals(
            mapOf(
                "n" to Glyph.NEEDS_YOU, "w" to Glyph.WORKING, "r" to Glyph.WAITING, "q" to Glyph.WAITING,
                "p" to Glyph.WAITING, "d" to Glyph.DONE, "e" to Glyph.DONE, "i" to Glyph.WAITING, "x" to Glyph.WAITING,
            ),
            glyphs,
        )
    }

    @Test
    fun aPausedSessionHasItsOwnGlyphAndPlaceAfterOtherLabelsAndNeverCountsAsNeedingYou() {
        show(listOf(task(members = listOf(
            member("d", "idle", label = "done"), member("p", "idle", label = "paused: waiting for the release"),
            member("o", "idle", label = "odd"), member("w", "working"),
        ))))
        vm.toggleTask("g1")
        val sessions = vm.state.rows.filter { it.kind == RowKind.SESSION }
        assertEquals(listOf("w", "o", "p", "d"), sessions.map { it.title })
        assertEquals(Glyph.PAUSED, sessions.first { it.title == "p" }.glyph)
        assertEquals(0, vm.state.needYouCount)
        assertEquals(1, vm.state.workingCount)
    }

    @Test
    fun theStatusOfATaskSessionIsNeverBlankUnlessItsLabelSaysIt() {
        layout("leaf-w", "leaf-i")
        show(
            listOf(task(members = listOf(
                member("needs", "needs_you"),
                member("unhosted work", "working"),
                member("hosted work", "working", recordId = "w"),
                member("hosted idle", "idle", recordId = "i"),
                member("review", "idle", label = "in review · round 2"),
                member("finished", "idle", label = "done", links = listOf("#22"), line = "#22"),
                member("finished too", "idle", label = "done", line = "Merged and deployed"),
            ))),
            session("w", "working", firstPrompt = "Fix the thing"),
            session("i", "idle", lastSaid = "All green.", firstPrompt = "Fix the thing"),
        )
        vm.toggleTask("g1")
        val byTitle = vm.state.rows.filter { it.kind == RowKind.SESSION }.associateBy { it.title }
        assertEquals("waiting at a prompt", byTitle.getValue("needs").status)
        assertEquals("—", byTitle.getValue("unhosted work").status)
        assertEquals("❯ Fix the thing", byTitle.getValue("hosted work").status)
        assertEquals("All green.", byTitle.getValue("hosted idle").status)
        assertEquals("", byTitle.getValue("review").status, "the label is the whole status")
        assertEquals("in review · round 2", byTitle.getValue("review").label)
        assertEquals("", byTitle.getValue("finished").status, "a line that only repeats the PR is dropped")
        assertEquals("#22", byTitle.getValue("finished").where)
        assertEquals("done", byTitle.getValue("finished").label)
        assertEquals("Merged and deployed", byTitle.getValue("finished too").status)
    }

    @Test
    fun aLabelIsShownOnIdleAndEndedSessionsAndNotOnAHostedWorkingOne() {
        layout("leaf-w")
        show(
            listOf(task(members = listOf(member("w", "working", label = "in review", recordId = "w"), member("i", "idle", label = "in review")))),
            session("w", "working"),
        )
        vm.toggleTask("g1")
        val labels = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.label }
        assertEquals(mapOf("w" to null, "i" to "in review"), labels)
    }

    @Test
    fun aWorkingSessionOutsideLunamuxIsLiveWhileItsFileIsFreshAndGoesQuietWhenItStopsBeingRefreshed() {
        val refreshed = 1_000_000L
        show(listOf(task(refreshedAtMillis = refreshed, members = listOf(member("unhosted", "working", line = "Doing a thing"), member("other", "idle")))))
        vm.toggleTask("g1")
        val row = vm.state.rows.single { it.id == "member:g1:#1" }
        assertEquals(refreshed, row.activityMillis)
        assertEquals(refreshed, row.fileRefreshedMillis)
        assertEquals(refreshed, vm.state.tasks.getValue("g1").activityMillis)
        val at = { minutes: Long -> refreshed + minutes * 60_000L }
        assertTrue(AgentsOverviewBackingViewModel.isWorkingLive(row.activityMillis, at(4)))
        assertFalse(AgentsOverviewBackingViewModel.isWorkingLive(row.activityMillis, at(5)))
        assertFalse(AgentsOverviewBackingViewModel.isWorkingLive(null, at(0)))
        assertEquals("", AgentsOverviewBackingViewModel.agePrefix(row, at(4)))
        assertEquals("quiet 6m · ", AgentsOverviewBackingViewModel.agePrefix(row, at(6)))
        assertEquals("stale 25m · ", AgentsOverviewBackingViewModel.agePrefix(row, at(25)))
    }

    @Test
    fun aWorkingSessionWithoutAFileTimeHasNoSignOfLife() {
        show(listOf(task(members = listOf(member("unhosted", "working"), member("other", "idle")))))
        vm.toggleTask("g1")
        assertNull(vm.state.rows.single { it.id == "member:g1:#1" }.activityMillis)
        assertNull(vm.state.tasks.getValue("g1").activityMillis)
    }

    @Test
    fun aHostedWorkingSessionTakesTheNewerOfItsSessionAndItsFile() {
        layout("leaf-w1", "leaf-w2")
        show(
            listOf(task(refreshedAtMillis = 8_000, members = listOf(
                member("one", "working", recordId = "w1"),
                member("two", "working", recordId = "w2"),
            ))),
            session("w1", "working", lastActivityAt = 7_000),
            session("w2", "working", lastActivityAt = 9_000),
        )
        vm.toggleTask("g1")
        val byId = vm.state.rows.associateBy { it.id }
        assertEquals(8_000L, byId.getValue("w1").activityMillis)
        assertEquals(9_000L, byId.getValue("w2").activityMillis)
        assertNull(byId.getValue("w1").fileRefreshedMillis, "a session with a record follows the session for staleness")
        assertEquals(9_000L, vm.state.tasks.getValue("g1").activityMillis)
    }

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

    @Test
    fun theOverlayOpensOnTheDiamondThatHasWaitedLongest() {
        show(
            listOf(task(members = listOf(member("g-run", "needs_you", since = 1_500)))),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
            session("early", "needs_you", since = 2_000),
        )
        vm.open()
        assertEquals("member:g1:#1", vm.state.selectedId)
        assertEquals(Footer.NO_SESSION, vm.state.footer)
    }

    @Test
    fun whenTheRowTheOverlayOpenedOnDisappearsTheRowNowInItsPlaceIsSelected() {
        val a = member("a", "needs_you", since = 1_500)
        show(
            listOf(task(members = listOf(a, member("b", "idle")))),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
        )
        vm.open()
        val index = vm.state.selected
        assertTrue(index > 0)
        // The session answers and goes idle: its row is hidden under the collapsed panel.
        show(
            listOf(task(members = listOf(a.copy(state = "idle"), member("b", "idle")))),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
        )
        assertEquals(vm.state.rows[index.coerceAtMost(vm.state.rows.lastIndex)].id, vm.state.selectedId)
    }

    @Test
    fun aSessionTheFileGivesAnIdKeepsItsRowIdWhenTheWriterReordersTheMembers() {
        val a = member("a", "needs_you", since = 1_000, sessionId = "aaa")
        val b = member("b", "needs_you", since = 2_000, sessionId = "bbb")
        show(listOf(task(members = listOf(a, b))))
        vm.open()
        vm.select("member:g1:bbb")
        show(listOf(task(members = listOf(member("new", "needs_you", since = 500, sessionId = "ccc"), a, b))))
        assertEquals("member:g1:bbb", vm.state.selectedId)
        assertEquals("b", vm.state.rows.single { it.id == vm.state.selectedId }.title)
    }

    @Test
    fun theOverlayOpensOnTheFirstRowWhenNothingNeedsYou() {
        show(listOf(task(members = listOf(member("a", "working"), member("b", "idle")))), session("w", "working", since = 99_999))
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
        show(emptyList(), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working"))
        vm.open()
        vm.moveSelection(1)
        assertEquals("b", vm.state.selectedId)
        show(emptyList(), session("z", "needs_you", since = 10), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working"))
        assertEquals("b", vm.state.selectedId)
        assertEquals(2, vm.state.selected)
    }

    @Test
    fun whenTheSelectedRowDisappearsTheRowInItsPlaceIsSelected() {
        show(emptyList(), session("a", "needs_you", since = 1_000), session("b", "needs_you", since = 2_000), session("c", "working"))
        vm.open()
        vm.moveSelection(1)
        show(emptyList(), session("a", "needs_you", since = 1_000), session("c", "working"))
        assertEquals("c", vm.state.selectedId)
    }

    @Test
    fun theSelectionFollowsATaskAsItsSessionsAreExpandedAboveIt() {
        show(
            listOf(task(members = listOf(member("a", "working"), member("b", "idle")))),
            session("s", "working", since = 99_999),
        )
        vm.open()
        vm.moveSelection(2)
        assertEquals("s", vm.state.selectedId)
        vm.toggleTask("g1")
        assertEquals("task:g1", vm.state.selectedId, "toggling selects the header it belongs to")
    }

    @Test
    fun rightExpandsAHeaderThenEntersItsSessionsAndLeftGoesBackUpAndThenCollapses() = runNow {
        show(listOf(task(members = listOf(member("a", "working"), member("b", "idle")))))
        vm.open()
        assertEquals(Footer.TASK_COLLAPSED, vm.state.footer)
        vm.stepIn()
        assertTrue(vm.state.tasks.getValue("g1").expanded)
        assertEquals(Footer.TASK_EXPANDED, vm.state.footer)
        assertEquals("task:g1", vm.state.selectedId)
        vm.stepIn()
        assertEquals("member:g1:#1", vm.state.selectedId)
        vm.stepOut()
        assertEquals("task:g1", vm.state.selectedId)
        assertTrue(vm.state.tasks.getValue("g1").expanded)
        vm.stepOut()
        assertFalse(vm.state.tasks.getValue("g1").expanded)
        assertEquals("task:g1", vm.state.selectedId)
    }

    @Test
    fun spaceTogglesAHeaderOnlyWhileTheQueryIsEmpty() = runNow {
        show(listOf(task(members = listOf(member("a", "working"), member("b", "idle")))))
        vm.open()
        vm.toggleSelected()
        assertTrue(vm.state.tasks.getValue("g1").expanded)
        vm.toggleSelected()
        assertFalse(vm.state.tasks.getValue("g1").expanded)
        vm.setQuery("x")
        vm.toggleSelected()
        assertFalse(vm.state.tasks.getValue("g1").expanded)
    }

    @Test
    fun rightOnASessionPeeksAndLeftClosesThePeek() = runNow {
        layout("leaf-a")
        show(emptyList(), session("a", "working"))
        vm.open()
        vm.stepIn()
        assertTrue(vm.state.peek != null)
        vm.stepOut()
        assertNull(vm.state.peek)
    }

    @Test
    fun footerFollowsTheSelectedRow() {
        layout("leaf-live")
        show(emptyList(), session("live", "working"), session("closed", "ended", endedAt = 5_000))
        vm.open()
        assertEquals(Footer.LIVE, vm.state.footer)
        vm.select("closed")
        assertEquals(Footer.CLOSED, vm.state.footer)
        show(emptyList())
        assertEquals(Footer.NONE, vm.state.footer)
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
        val repos = vm.state.rows.associate { it.id to it.where }
        assertEquals(mapOf("a" to "proj", "b" to "notes", "c" to "other"), repos)
    }

    @Test
    fun peekAndCloseOnlyApplyToRowsWithAnOpenRecord() = runNow {
        show(listOf(task(members = listOf(member("a", "needs_you"), member("b", "needs_you")))))
        vm.open()
        vm.select("task:g1")
        vm.peek()
        assertTrue(sent.isEmpty(), "a header with no open session has nothing to peek")
        vm.select("member:g1:#1")
        vm.peek()
        assertTrue(sent.isEmpty(), "a session Lunamux does not host has nothing to peek")
        assertFalse(vm.state.rows.any { it.canClose })
    }

    @Test
    fun withNoTasksEveryRowIsAnOrdinarySession() {
        show(emptyList(), session("a", "working"), session("b", "idle"))
        assertTrue(vm.state.rows.all { it.kind == RowKind.SESSION && !it.unhosted })
        assertTrue(vm.state.tasks.isEmpty())
    }

    @Test
    fun anIdleSessionStaysInNowForTenMinutesAfterItsLastActivityAndThenMovesToEarlier() {
        val grace = AgentsOverviewBackingViewModel.IDLE_GRACE_MS
        assertEquals(10L * 60_000L, grace)
        layout("leaf-chat")
        show(emptyList(), session("chat", "idle", since = now - grace - 5_000, lastActivityAt = now - grace + 1))
        assertEquals(listOf("chat"), vm.state.now.map { it.id }, "one millisecond short of the grace")
        assertTrue(vm.state.earlier.isEmpty())
        assertEquals(Glyph.WAITING, vm.state.now.single().glyph, "idle, never a diamond")

        now += 1
        vm.tick()
        assertTrue(vm.state.now.isEmpty(), "the grace is over exactly ten minutes after the activity")
        assertEquals(listOf("chat"), vm.state.earlier.map { it.id })
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
    fun aGracedIdleSessionRanksBehindEverythingThatNeedsYouOrWorksAndAClosedOneNeverGetsGrace() {
        show(
            listOf(task(members = listOf(member("run", "idle", label = "queued"), member("run2", "idle", label = "queued")))),
            session("graced", "idle", since = now - 1_000),
            session("work", "working", since = now - 5_000),
            session("need", "needs_you", since = now - 9_000),
            session("closed", "ended", since = now - 2_000, endedAt = now - 1_000),
        )
        assertEquals(listOf("need", "work", "task:g1", "graced"), vm.state.now.map { it.id })
        assertEquals(listOf("closed"), vm.state.earlier.map { it.id })
        assertEquals(1, vm.state.needYouCount)
    }

    @Test
    fun anEmptyOrBlankLineRendersAsNothingForATaskSessionAndNeverLeavesASeparatorOrAnEmptyStatus() {
        show(
            listOf(task(members = listOf(
                member("needs", "needs_you", line = "   "),
                member("working", "working", line = ""),
                member("review", "idle", label = "in review · round 2", line = "  "),
                member("idle", "idle", line = " "),
            ))),
        )
        vm.toggleTask("g1")
        val byTitle = vm.state.rows.filter { it.kind == RowKind.SESSION }.associateBy { it.title }
        assertEquals("waiting at a prompt", byTitle.getValue("needs").status)
        assertNull(byTitle.getValue("needs").question)
        assertEquals("—", byTitle.getValue("working").status)
        assertNull(byTitle.getValue("working").nowLine)
        assertEquals("", byTitle.getValue("review").status)
        assertEquals("in review · round 2", byTitle.getValue("review").label)
        assertEquals("—", byTitle.getValue("idle").status)
        assertTrue(byTitle.values.none { it.status.isBlank() && it.label == null && it.glyph != Glyph.DONE })
    }

    @Test
    fun aBlankLineOnASessionFallsBackToItsFirstPromptAndTheTaskHeaderStaysCountsOnly() {
        layout("leaf-w")
        show(
            listOf(task(members = listOf(member("hosted", "working", recordId = "w", line = "  "), member("finished", "ended")))),
            session("w", "working", nowLine = "   ", firstPrompt = "Fix the thing"),
            session("single", "working", nowLine = "  ", question = " "),
        )
        vm.toggleTask("g1")
        val rows = vm.state.rows.associateBy { it.id }
        assertEquals("❯ Fix the thing", rows.getValue("w").status)
        assertEquals("—", rows.getValue("single").status)
        assertNull(rows.getValue("single").nowLine)
        val header = rows.getValue("task:g1")
        assertEquals("", header.status)
        assertEquals("1 working · 1 done", vm.state.tasks.getValue("g1").summary)
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

    private companion object {
        const val EARLIER_LIMIT = AgentsOverviewBackingViewModel.EARLIER_LIMIT
        const val DAY = 24L * 60 * 60_000L
    }
}
