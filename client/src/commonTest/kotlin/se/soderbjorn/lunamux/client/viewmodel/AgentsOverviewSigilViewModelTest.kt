/**
 * Tests for the row cues that tell the rows of the agents overview apart at a glance: the place
 * cue the view computes and the view model passes to live rows, who started a session, the dimmed
 * leading words of the titles of one thread, the order NOW and the members of a task keep (by when
 * their work started, so a state change never moves a row), and the selection landing on the first
 * ◆ row when the overlay opens.
 *
 * @see AgentsOverviewBackingViewModel.Row.place
 * @see AgentsOverviewBackingViewModel.Row.who
 * @see AgentsOverviewBackingViewModel.Row.dimPrefix
 * @see dimPrefixLengths
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Who
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentsOverviewSigilViewModelTest {
    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val now = 10_000_000L
    private val places = MutableStateFlow<Map<String, String>>(emptyMap())
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }, places = places) { sent += it }

    private fun session(
        id: String, state: String = "working", title: String = "session $id", startedAt: Long? = null, since: Long = now - 60_000,
        startedBy: String? = null, threadKey: String? = null, mark: Int? = null, taskId: String? = null, endedAt: Long? = null,
        live: Boolean = endedAt == null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, links = emptyList(),
        repo = "proj", branch = "b", cwd = "/work/proj", leafId = "leaf-$id".takeIf { live }, endedAt = endedAt,
        lastActivityAt = if (state == "working") now - 1_000 else null, taskId = taskId, mark = mark,
        threadKey = threadKey, startedBy = startedBy, startedAt = startedAt,
    )

    private fun layout(vararg leafIds: String) {
        val panes = leafIds.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes)), activeTabId = "tab-1"))
    }

    private fun show(sessions: List<AgentSessionSummary>, tasks: List<AgentTask> = emptyList()) {
        windowState.updateAgentSessions(sessions, true)
        windowState.updateAgentTasks(tasks)
    }

    private fun nowIds() = vm.state.now.map { it.id }

    // ---- place -------------------------------------------------------------------------------

    @Test
    fun aLiveRowWearsThePlaceTheViewComputedForItsPane() {
        layout("leaf-a", "leaf-b")
        places.value = mapOf("leaf-a" to "2▘")
        show(listOf(session("a"), session("b")))
        val rows = vm.state.now.associateBy { it.id }
        assertEquals("2▘", rows.getValue("a").place)
        assertNull(rows.getValue("b").place, "the view found no place for this pane")
    }

    @Test
    fun theStateIsBuiltAgainWhenThePlacesChange() {
        layout("leaf-a")
        show(listOf(session("a")))
        assertNull(vm.state.now.single().place)
        places.value = mapOf("leaf-a" to "1▌")
        assertEquals("1▌", vm.state.now.single().place)
    }

    @Test
    fun anEndedRowAndARowWhosePaneIsGoneHaveNoPlaceEvenIfTheViewKnowsAStaleOne() {
        layout("leaf-a")
        places.value = mapOf("leaf-a" to "1", "leaf-gone" to "3", "leaf-e" to "2")
        show(
            listOf(
                session("a"),
                session("gone", live = false),
                session("e", state = "ended", endedAt = now - 5_000, since = now - 5_000),
            ),
        )
        val rows = vm.state.rows.associateBy { it.id }
        assertEquals("1", rows.getValue("a").place)
        assertNull(rows.getValue("gone").place)
        assertNull(rows.getValue("e").place)
    }

    // ---- who ---------------------------------------------------------------------------------

    @Test
    fun whoIsReadFromHowTheSessionWasStartedAndIsNothingForARecordThatDoesNotSay() {
        show(
            listOf(
                session("typed", startedBy = "user", startedAt = 100),
                session("tool", startedBy = "tool", startedAt = 200),
                session("old", startedBy = null, startedAt = 300),
                session("future", startedBy = "robot", startedAt = 400),
            ),
        )
        val who = vm.state.now.associate { it.id to it.who }
        assertEquals(mapOf("typed" to Who.USER, "tool" to Who.TOOL, "old" to Who.NONE, "future" to Who.NONE), who)
        assertEquals("❯", Who.USER.glyph)
        assertEquals("⚙", Who.TOOL.glyph)
        assertEquals("", Who.NONE.glyph)
    }

    @Test
    fun aMemberATaskFileListsThatNoSessionHostsCountsAsStartedByATool() {
        show(
            emptyList(),
            listOf(
                AgentTask(
                    id = "t", title = "Release", source = "file", mark = 2,
                    members = listOf(
                        AgentTaskMember(key = "a", title = "a", state = "working", sinceMillis = now - 1_000),
                        AgentTaskMember(key = "b", title = "b", state = "working", sinceMillis = now - 1_000),
                    ),
                ),
            ),
        )
        vm.toggleTask("t")
        val rows = vm.state.now.associateBy { it.id }
        assertEquals(Who.NONE, rows.getValue("task:t").who, "a header was started by nobody")
        assertEquals(Who.TOOL, rows.getValue("member:t:a").who)
    }

    // ---- dim prefix --------------------------------------------------------------------------

    @Test
    fun theRowsOfOneThreadDimTheWordsTheirTitlesShare() {
        show(
            listOf(
                session("a", title = "Invoice export: first run against staging", threadKey = "path:/inv", startedAt = 100),
                session("b", title = "Invoice export: first draft of the spec", threadKey = "path:/inv", startedAt = 200),
            ),
        )
        val rows = vm.state.now.associateBy { it.id }
        val dim = "Invoice export: first ".length
        assertEquals(dim, rows.getValue("a").dimPrefix)
        assertEquals(dim, rows.getValue("b").dimPrefix)
        assertEquals("Invoice export: first ", rows.getValue("a").title.take(rows.getValue("a").dimPrefix))
    }

    @Test
    fun aTitleSharedWithARowOfAnotherThreadDimsNothing() {
        show(
            listOf(
                session("a", title = "Invoice export: first run", threadKey = "path:/inv", startedAt = 100),
                session("b", title = "Invoice export: first draft", threadKey = "path:/other", startedAt = 200),
            ),
        )
        assertEquals(listOf(0, 0), vm.state.now.map { it.dimPrefix })
    }

    @Test
    fun theMembersOfATaskAreOneThreadWhateverTheirSessionsWereMarkedWith() {
        show(
            listOf(
                session("a", title = "Ship invoices: build", taskId = "t", threadKey = "path:/x", startedAt = 100),
                session("b", title = "Ship invoices: test", taskId = "t", threadKey = "path:/y", startedAt = 200),
            ),
            listOf(AgentTask(id = "t", title = "Ship invoices", mark = 4)),
        )
        vm.toggleTask("t")
        val members = vm.state.now.filter { it.kind == AgentsOverviewBackingViewModel.RowKind.SESSION }
        assertEquals(listOf("Ship invoices: ".length, "Ship invoices: ".length), members.map { it.dimPrefix })
        assertEquals(0, vm.state.now.first { it.id == "task:t" }.dimPrefix, "a header names the whole thread and is never dimmed")
    }

    @Test
    fun dimPrefixLengthsKeepsAtLeastOneWordBoldAndNeverDimsALoneRowOrOneWithNoThread() {
        // Equal titles share every word; the last one stays bold.
        assertEquals(listOf("one two ".length, "one two ".length), dimPrefixLengths(listOf("t", "t"), listOf("one two three", "one two three")))
        // A row alone in its thread, and a row with no thread, have nothing to share.
        assertEquals(listOf(0, 0, 0), dimPrefixLengths(listOf("t", null, null), listOf("same words", "same words", "same words")))
        // Different words from the start share nothing; words are compared whole.
        assertEquals(listOf(0, 0), dimPrefixLengths(listOf("t", "t"), listOf("export: a", "export b")))
        // The longest run against any other row of the thread wins for each row.
        assertEquals(
            listOf("a b ".length, "a b ".length, "a ".length),
            dimPrefixLengths(listOf("t", "t", "t"), listOf("a b c", "a b d", "a x y")),
        )
        // A single-word title has no word to spare.
        assertEquals(listOf(0, 0), dimPrefixLengths(listOf("t", "t"), listOf("Invoice", "Invoice")))
        assertEquals(emptyList(), dimPrefixLengths(emptyList(), emptyList()))
    }

    // ---- order -------------------------------------------------------------------------------

    @Test
    fun nowAndTheMembersOfATaskKeepTheirPlaceWhenSessionsMoveBetweenWorkingAndNeedingYou() {
        layout("leaf-a", "leaf-b", "leaf-c", "leaf-x", "leaf-y")
        fun all(a: String, b: String, c: String, x: String, y: String) = listOf(
            session("a", state = a, startedAt = 100),
            session("b", state = b, startedAt = 200),
            session("c", state = c, startedAt = 300),
            session("x", state = x, startedAt = 150, taskId = "t"),
            session("y", state = y, startedAt = 160, taskId = "t"),
        )
        val task = listOf(AgentTask(id = "t", title = "Task t", mark = 1))
        show(all("working", "working", "working", "working", "working"), task)
        vm.toggleTask("t")
        val before = vm.state.rows.map { it.id }
        assertEquals(listOf("a", "task:t", "x", "y", "b", "c"), before)
        for (flip in listOf(
            all("needs_you", "working", "working", "working", "working"),
            all("needs_you", "needs_you", "working", "needs_you", "working"),
            all("working", "needs_you", "needs_you", "working", "needs_you"),
            all("needs_you", "needs_you", "needs_you", "needs_you", "needs_you"),
            all("working", "working", "working", "working", "working"),
        )) {
            show(flip, task)
            assertEquals(before, vm.state.rows.map { it.id }, "every row keeps its index")
        }
    }

    private fun fileMember(key: String, state: String, since: Long, recordId: String? = null) =
        AgentTaskMember(key = key, title = "member $key", state = state, sinceMillis = since, recordId = recordId)

    private fun fileTask(startedAtMillis: Long?, vararg members: AgentTaskMember) =
        AgentTask(id = "t", title = "Task t", source = "file", members = members.toList(), refreshedAtMillis = 9_000, mark = 1,
            startedAtMillis = startedAtMillis)

    @Test
    fun aTaskOfFileMembersKeepsItsPlaceWhenAMemberChangesStateAndItsSince() {
        layout("leaf-s")
        val single = session("s", startedAt = 3_000)
        show(listOf(single), listOf(fileTask(1_000, fileMember("a", "working", 1_000), fileMember("b", "working", 5_000))))
        vm.toggleTask("t")
        val before = vm.state.rows.map { it.id }
        assertEquals(listOf("task:t", "member:t:a", "member:t:b", "s"), before)
        // The member the task began with turns to needing the person: its since moves to now.
        show(listOf(single), listOf(fileTask(1_000, fileMember("a", "needs_you", 6_000), fileMember("b", "working", 5_000))))
        assertEquals(before, vm.state.rows.map { it.id })
        // The file is rewritten without any since, so every member reads the file's time.
        show(listOf(single), listOf(fileTask(1_000, fileMember("a", "needs_you", 9_000), fileMember("b", "working", 9_000))))
        assertEquals(before, vm.state.rows.map { it.id })
    }

    @Test
    fun aTaskKeepsItsPlaceWhenAFileMemberIsFirstMatchedToASessionThatStartedLater() {
        layout("leaf-s", "leaf-m")
        val single = session("s", startedAt = 3_000)
        val members = arrayOf(fileMember("a", "working", 1_000), fileMember("b", "working", 5_000))
        show(listOf(single), listOf(fileTask(1_000, *members)))
        val before = vm.state.now.map { it.id }
        assertEquals("task:t", before.first())
        // Lunamux now hosts member a, in a session that began after the single did.
        val hosted = session("m", startedAt = 9_000, taskId = "t")
        show(listOf(single, hosted), listOf(fileTask(1_000, fileMember("a", "working", 9_000, recordId = "m"), members[1])))
        assertEquals("task:t", vm.state.now.first().id)
        assertEquals(listOf("task:t", "s"), nowIds().filter { it == "task:t" || it == "s" })
    }

    @Test
    fun aTaskFromAnOlderServerStillFallsBackToItsMembersTimes() {
        layout("leaf-s")
        show(listOf(session("s", startedAt = 3_000)), listOf(fileTask(null, fileMember("a", "working", 1_000), fileMember("b", "working", 5_000))))
        assertEquals(listOf("task:t", "s"), nowIds().filter { it == "task:t" || it == "s" })
    }

    @Test
    fun aSessionThatBecomesATaskPanelWhenASecondOneJoinsDoesNotMove() {
        layout("leaf-a", "leaf-b", "leaf-x")
        val a = session("a", startedAt = 2_000, taskId = "t")
        val x = session("x", startedAt = 3_000)
        // One hosted session and nothing else: the task is not drawn, the session is a plain row.
        val stamped = AgentTask(id = "t", title = "Task t", source = "agents", mark = 1, startedAtMillis = 2_000)
        show(listOf(a, x), listOf(stamped))
        assertEquals(listOf("a", "x"), nowIds())
        // A second session joins, started after x: the task becomes a panel at the first one's start.
        val b = session("b", startedAt = 4_000, taskId = "t")
        show(listOf(a, x, b), listOf(stamped))
        assertEquals(listOf("task:t", "x"), nowIds().filter { it == "task:t" || it == "x" })
    }

    @Test
    fun aTaskStartsWhereItsEarliestSessionStartedEvenWhenThatSessionHasEnded() {
        layout("leaf-late", "leaf-loose")
        show(
            listOf(
                session("early-ended", state = "ended", startedAt = 50, taskId = "t", endedAt = now - 5_000, since = now - 5_000),
                session("late", startedAt = 500, taskId = "t"),
                session("loose", startedAt = 100),
            ),
            listOf(AgentTask(id = "t", title = "Task t", mark = 1)),
        )
        assertEquals(listOf("task:t", "loose"), nowIds().filter { it == "task:t" || it == "loose" }, "the task began at 50")
    }

    @Test
    fun aSessionWithoutAStartTimeIsOrderedByWhenItEnteredItsState() {
        layout("leaf-new", "leaf-old")
        show(listOf(session("new", since = now - 1_000), session("old", since = now - 90_000)))
        assertEquals(listOf("old", "new"), nowIds())
    }

    @Test
    fun anEndedRowInItsGraceKeepsItsPlaceUntilItLeavesNow() {
        layout("leaf-a", "leaf-c")
        show(
            listOf(
                session("a", startedAt = 100),
                session("b", state = "ended", startedAt = 200, endedAt = now - 60_000, since = now - 60_000),
                session("c", startedAt = 300),
            ),
        )
        assertEquals(listOf("a", "b", "c"), nowIds(), "just ended: still between its neighbours")
    }

    @Test
    fun hostedMembersOfATaskFollowTheFilesMembersByStartTime() {
        layout("leaf-r1", "leaf-h1", "leaf-h2")
        show(
            listOf(
                session("r1", startedAt = 900, taskId = "t"),
                session("h2", state = "needs_you", startedAt = 300, taskId = "t"),
                session("h1", startedAt = 200, taskId = "t"),
            ),
            listOf(
                AgentTask(
                    id = "t", title = "Task t", source = "file", mark = 1,
                    members = listOf(AgentTaskMember(key = "m", title = "Listed", state = "working", sinceMillis = now - 1_000, recordId = "r1")),
                ),
            ),
        )
        vm.toggleTask("t")
        assertEquals(listOf("task:t", "r1", "h1", "h2"), nowIds())
    }

    // ---- selection ---------------------------------------------------------------------------

    @Test
    fun openingSelectsTheFirstDiamondRowOfTheListNotTheOneThatHasWaitedLongest() {
        layout("leaf-w", "leaf-n1", "leaf-n2")
        show(
            listOf(
                session("w", startedAt = 100),
                session("n1", state = "needs_you", startedAt = 200, since = now - 60_000),
                session("n2", state = "needs_you", startedAt = 300, since = now - 600_000),
            ),
        )
        vm.open()
        assertEquals("n1", vm.state.selectedId)
    }

    @Test
    fun openingSelectsTheFirstRowWhenNothingNeedsYou() {
        layout("leaf-w1", "leaf-w2")
        show(listOf(session("w2", startedAt = 200), session("w1", startedAt = 100)))
        vm.open()
        assertEquals("w1", vm.state.selectedId)
    }

    @Test
    fun aCollapsedTaskThatPrecedesALooseDiamondSelectsItsOwnDiamondMemberFirst() {
        layout("leaf-x", "leaf-n")
        show(
            listOf(
                session("x", state = "needs_you", startedAt = 100, taskId = "t"),
                session("y", state = "idle", startedAt = 110, taskId = "t"),
                session("n", state = "needs_you", startedAt = 200),
            ),
            listOf(AgentTask(id = "t", title = "Task t", mark = 1)),
        )
        vm.open()
        assertEquals(listOf("task:t", "x", "n"), nowIds())
        assertEquals("x", vm.state.selectedId)
    }
}
