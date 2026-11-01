/**
 * Tests for the programme-group part of [AgentsOverviewBackingViewModel]: where a group sits
 * among the single sessions (it stays in NOW until all its runs have ended), what a collapsed and
 * an expanded group show and in which order, the glyph, status and counts of each run, how the
 * open lead sits among the runs, ↵ on a header, a hosted run and a run opened with
 * `claude attach`, the arrow and space keys, the selection staying on its row, and when a working
 * run counts as live, quiet or stale.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentGroup
import se.soderbjorn.lunamux.AgentGroupLead
import se.soderbjorn.lunamux.AgentGroupMember
import se.soderbjorn.lunamux.AgentSessionSummary
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

class AgentsOverviewGroupsViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once) and rethrows its failure. */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    private val vm = AgentsOverviewBackingViewModel(windowState) { sent += it }

    private fun session(
        id: String, state: String, since: Long = 1_000, endedAt: Long? = null, title: String = "session $id",
        repo: String? = "proj", cwd: String = "/work/proj", links: List<String> = emptyList(),
        nowLine: String? = null, question: String? = null, lastActivityAt: Long? = null,
        lastSaid: String? = null, firstPrompt: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, links = links,
        repo = repo, branch = null, cwd = cwd, nowLine = nowLine, question = question,
        leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt, lastActivityAt = lastActivityAt,
        lastSaid = lastSaid, firstPrompt = firstPrompt,
    )

    private fun layout(vararg leafIds: String) {
        val panes = leafIds.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes)), activeTabId = "tab-1"))
    }

    private fun member(
        title: String, state: String, since: Long = 1_000, label: String? = null, recordId: String? = null,
        line: String? = null, links: List<String> = emptyList(), sessionId: String? = null,
    ) = AgentGroupMember(
        title = title, state = state, sinceMillis = since, label = label, recordId = recordId, line = line,
        links = links, sessionId = sessionId,
    )

    private fun group(
        id: String = "g1", members: List<AgentGroupMember>, leadRecord: String? = null, leadLive: Boolean = false,
        lastSaid: String? = null, tag: String? = "Programme", refreshedAtMillis: Long? = null,
    ) = AgentGroup(
        id = id, title = "Release $id", tag = tag, repo = "proj",
        lead = AgentGroupLead(folder = "/work/lead", recordId = leadRecord, live = leadLive, lastSaid = lastSaid),
        members = members, refreshedAtMillis = refreshedAtMillis,
    )

    private fun show(groups: List<AgentGroup>, vararg sessions: AgentSessionSummary) {
        windowState.updateAgentSessions(sessions.toList(), true)
        windowState.updateAgentGroups(groups)
    }

    @Test
    fun aGroupWithARunThatNeedsYouSitsInNowRankedByThatRun() {
        show(
            listOf(group(members = listOf(member("a", "needs_you", since = 2_000), member("b", "working", since = 100)))),
            session("old-working", "working", since = 500),
            session("late-needs", "needs_you", since = 3_000),
            session("early-needs", "needs_you", since = 1_000),
        )
        assertEquals(
            listOf("early-needs", "group:g1", "member:g1:0", "late-needs", "old-working"),
            vm.state.now.map { it.id },
        )
    }

    @Test
    fun aWorkingOnlyGroupIsInNowBehindEveryoneWhoNeedsYouAndRankedByWait() {
        show(
            listOf(group(members = listOf(member("a", "working", since = 1)))),
            session("n", "needs_you", since = 9_000),
            session("w", "working", since = 5_000),
        )
        // The group's working run has waited longest of the working ones, so it comes first.
        assertEquals(listOf("n", "group:g1", "w"), vm.state.now.map { it.id })
    }

    @Test
    fun aGroupStaysInNowWhileAnyRunHasNotEndedEvenWhenNothingIsRunning() {
        show(
            listOf(group(members = listOf(member("a", "idle", label = "in review"), member("b", "ended")))),
            session("busy", "working", since = 1),
        )
        assertEquals(listOf("busy", "group:g1"), vm.state.now.map { it.id })
        assertTrue(vm.state.earlier.isEmpty())
    }

    @Test
    fun aGroupWhoseRunsAllEndedMovesToEarlierBeneathOpenIdleSessionsAndByAge() {
        show(
            listOf(
                group(id = "g1", members = listOf(member("a", "ended", since = 4_000), member("b", "ended", since = 2_000))),
                group(id = "g2", members = emptyList()),
            ),
            session("idle-open", "idle", since = 1),
            session("closed-new", "ended", endedAt = 5_000),
            session("closed-old", "ended", endedAt = 3_000),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals(
            listOf("idle-open", "closed-new", "group:g1", "closed-old", "group:g2"),
            vm.state.earlier.map { it.id },
        )
    }

    @Test
    fun aGroupIsOneUnitInTheEarlierLimit() {
        val groups = (1..6).map { group(id = "g$it", members = listOf(member("m", "ended", since = it * 100L))) }
        show(groups)
        assertEquals(EARLIER_LIMIT, vm.state.earlier.count { it.kind == RowKind.GROUP })
        assertEquals(1, vm.state.earlierHidden)
    }

    @Test
    fun collapsedShowsTheHeaderAndTheRunsThatNeedYouAndTheCountsSayTheRest() {
        show(listOf(group(members = listOf(
            member("a", "needs_you"), member("b", "working"), member("c", "idle", label = "in review"),
        ))))
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "member:g1:0"), rows.map { it.id })
        assertEquals(RowKind.GROUP, rows[0].kind)
        assertEquals(Glyph.NONE, rows[0].glyph, "a header never carries a state glyph, least of all the ◆")
        val g = vm.state.groups.getValue("g1")
        assertEquals(1, g.needYouCount)
        assertEquals("1 working · 1 in review", g.summary)
        assertFalse(g.expanded)
    }

    @Test
    fun headerCountsComeInAFixedOrderWithNoZerosAndUnknownLabelsAsGiven() {
        show(listOf(group(members = listOf(
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
            "1 working · 1 in review · 2 queued · 1 weird thing · 1 idle · 1 paused · 2 done",
            vm.state.groups.getValue("g1").summary,
        )
    }

    @Test
    fun expandingShowsEveryRunMostUrgentFirstAndKeepsTheHighlightOnTheHeader() {
        show(listOf(group(members = listOf(
            member("a", "working"), member("b", "needs_you"), member("c", "idle", label = "done"),
            member("d", "idle", label = "held"), member("e", "idle", label = "queued"), member("f", "idle", label = "in review"),
        ))))
        vm.open()
        vm.toggleGroup("g1")
        assertEquals(
            listOf("group:g1", "member:g1:1", "member:g1:0", "member:g1:5", "member:g1:4", "member:g1:3", "member:g1:2"),
            vm.state.rows.map { it.id },
        )
        assertTrue(vm.state.groups.getValue("g1").expanded)
        assertEquals("group:g1", vm.state.selectedId)
        vm.toggleGroup("g1")
        assertEquals(2, vm.state.rows.size)
    }

    @Test
    fun needsYouRunsInAnExpandedGroupAreOrderedLongestWaitingFirst() {
        show(listOf(group(members = listOf(member("late", "needs_you", since = 9_000), member("early", "needs_you", since = 1_000)))))
        assertEquals(listOf("group:g1", "member:g1:1", "member:g1:0"), vm.state.rows.map { it.id })
    }

    @Test
    fun anExpandedGroupStaysExpandedWhenTheOverlayIsClosedAndOpenedAgain() {
        show(listOf(group(members = listOf(member("a", "working")))))
        vm.open()
        vm.toggleGroup("g1")
        vm.close()
        assertFalse(vm.state.open)
        vm.open()
        assertTrue(vm.state.groups.getValue("g1").expanded)
    }

    @Test
    fun chipCountsIncludeGroupRunsEvenWhenCollapsed() {
        show(
            listOf(group(members = listOf(member("a", "needs_you"), member("b", "working"), member("c", "working"), member("d", "idle")))),
            session("s1", "needs_you"),
        )
        assertEquals(2, vm.state.needYouCount)
        assertEquals(2, vm.state.workingCount)
        assertEquals("2 need you", vm.state.chipNeeds)
        assertEquals("2 working", vm.state.chipRest)
    }

    @Test
    fun aRunMatchedToASessionIsDrawnOnlyInsideTheGroup() {
        layout("leaf-r1")
        show(
            listOf(group(members = listOf(member("Task one", "needs_you", recordId = "r1", line = "Which branch?")))),
            session("r1", "needs_you"),
        )
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "r1"), rows.map { it.id })
        assertEquals("Task one", rows[1].title)
        assertEquals("Which branch?", rows[1].status)
        assertEquals(Glyph.NEEDS_YOU, rows[1].glyph)
    }

    @Test
    fun returnOnAHeaderJumpsToTheLeadWhenItIsOpen() = runNow {
        layout("leaf-lead")
        show(listOf(group(members = listOf(member("a", "working")), leadRecord = "lead", leadLive = true)), session("lead", "idle"))
        val header = vm.state.rows.first { it.kind == RowKind.GROUP }
        assertEquals("open", header.actionLabel)
        vm.activate(header)
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-lead")), sent)
    }

    @Test
    fun returnOnAHeaderOpensTheLeadInANewTabWhenItIsNotOpen() = runNow {
        layout("leaf-other")
        show(listOf(group(members = listOf(member("a", "working")))))
        vm.activate(vm.state.rows.first { it.kind == RowKind.GROUP })
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenAgentGroupLead("g1")), sent)
    }

    @Test
    fun aLeadWhoseSessionIsNotInTheLayoutIsNotTreatedAsOpen() = runNow {
        layout("leaf-other")
        show(listOf(group(members = listOf(member("a", "working")), leadRecord = "lead", leadLive = true)), session("lead", "idle"))
        assertFalse(vm.state.groups.getValue("g1").leadLive)
        vm.activate(vm.state.rows.first { it.kind == RowKind.GROUP })
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenAgentGroupLead("g1")), sent)
    }

    @Test
    fun aRunWithASessionIdOpensWithClaudeAttachAndOneWithoutHasNothingToOpen() = runNow {
        show(listOf(group(members = listOf(
            member("attachable", "needs_you", sessionId = "ab12cd"),
            member("nothing", "needs_you", since = 2_000),
        ))))
        val rows = vm.state.rows.associateBy { it.title }
        assertTrue(rows.getValue("attachable").unhosted)
        assertEquals("ab12cd", rows.getValue("attachable").sessionId)

        vm.select(rows.getValue("attachable").id)
        assertEquals(Footer.RUN_ATTACH, vm.state.footer)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenAgentGroupMember("g1", "ab12cd")), sent)

        sent.clear()
        vm.select(rows.getValue("nothing").id)
        assertEquals(Footer.NO_SESSION, vm.state.footer)
        vm.activateSelected()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theOverlayStaysOpenAfterAnAttachUntilTheNewTabIsActive() = runNow {
        layout("leaf-other")
        show(listOf(group(members = listOf(member("a", "needs_you", sessionId = "ab12cd")))))
        vm.open()
        vm.activate(vm.state.rows.last())
        assertTrue(vm.state.open)
        windowState.updateConfig(windowState.config.value!!.copy(activeTabId = "new-tab"))
        assertFalse(vm.state.open)
    }

    @Test
    fun aRunIsJumpWhenItsSessionIsOpenResumeWhenItEndedAndOpenOnlyWithASessionId() {
        layout("leaf-live")
        show(
            listOf(group(members = listOf(
                member("live one", "working", recordId = "live"),
                member("done one", "idle", recordId = "done"),
                member("nobody's", "idle"),
                member("attachable", "idle", sessionId = "xy9"),
            ))),
            session("live", "working"),
            session("done", "ended", endedAt = 5_000),
        )
        vm.toggleGroup("g1")
        val labels = vm.state.rows.filter { it.groupId == "g1" && it.kind == RowKind.SESSION }.associate { it.title to it.actionLabel }
        assertEquals(mapOf("live one" to "jump", "done one" to "resume", "nobody's" to "", "attachable" to "open"), labels)
    }

    @Test
    fun aRunShowsOnlyTheLinksTheFileGivesNotOnesReadFromItsBranch() {
        layout("leaf-r1")
        show(
            listOf(group(members = listOf(member("a", "working", recordId = "r1"), member("b", "idle", recordId = "r2", links = listOf("ABC-2"))))),
            session("r1", "working", links = listOf("ABC-1")),
            session("r2", "idle", links = listOf("ABC-9")),
        )
        vm.toggleGroup("g1")
        val links = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.links }
        assertEquals(mapOf("a" to emptyList(), "b" to listOf("ABC-2")), links)
    }

    @Test
    fun theGlyphOfARunFollowsItsStateAndLabel() {
        show(listOf(group(members = listOf(
            member("n", "needs_you"), member("w", "working"), member("r", "idle", label = "in review"),
            member("q", "idle", label = "queued"), member("p", "idle", label = "held up"), member("d", "idle", label = "done"),
            member("e", "ended"), member("i", "idle"), member("x", "idle", label = "unknown"),
        ))))
        vm.toggleGroup("g1")
        val glyphs = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.glyph }
        assertEquals(
            mapOf(
                "n" to Glyph.NEEDS_YOU, "w" to Glyph.WORKING, "r" to Glyph.WAITING, "q" to Glyph.WAITING,
                "p" to Glyph.PAUSED, "d" to Glyph.DONE, "e" to Glyph.DONE, "i" to Glyph.WAITING, "x" to Glyph.WAITING,
            ),
            glyphs,
        )
    }

    @Test
    fun theStatusOfARunIsNeverBlankUnlessItsLabelSaysIt() {
        layout("leaf-w", "leaf-i")
        show(
            listOf(group(members = listOf(
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
        vm.toggleGroup("g1")
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
    fun aLabelIsShownOnlyOnRunsThatAreIdleOrEnded() {
        show(listOf(group(members = listOf(member("w", "working", label = "in review"), member("i", "idle", label = "in review")))))
        vm.toggleGroup("g1")
        val labels = vm.state.rows.filter { it.kind == RowKind.SESSION }.associate { it.title to it.label }
        assertEquals(mapOf("w" to null, "i" to "in review"), labels)
    }

    @Test
    fun theOpenLeadIsARunOnlyWhileItNeedsYouOrWorksAndIsNeverAlsoASingle() {
        layout("leaf-lead")
        show(
            listOf(group(members = listOf(member("a", "idle")), leadRecord = "lead", leadLive = true)),
            session("lead", "working", nowLine = "Bash ./gradlew test", lastActivityAt = 9_000),
        )
        vm.toggleGroup("g1")
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "lead", "member:g1:0"), rows.map { it.id })
        assertEquals("Programme session", rows[1].title)
        assertEquals("Bash ./gradlew test", rows[1].status)
        assertEquals(1, vm.state.workingCount)

        // Once the lead is quiet it is no longer a run, and still not a single.
        show(
            listOf(group(members = listOf(member("a", "idle")), leadRecord = "lead", leadLive = true)),
            session("lead", "idle"),
        )
        assertEquals(listOf("group:g1", "member:g1:0"), vm.state.rows.map { it.id })
        assertEquals(0, vm.state.workingCount)
    }

    @Test
    fun aLeadThatNeedsYouLiftsTheGroupAndShowsItsQuestion() {
        layout("leaf-lead")
        show(
            listOf(group(members = listOf(member("a", "idle")), leadRecord = "lead", leadLive = true)),
            session("lead", "needs_you", question = "Which branch?"),
        )
        assertEquals(1, vm.state.needYouCount)
        assertEquals(listOf("group:g1", "lead"), vm.state.now.map { it.id })
        assertEquals("Which branch?", vm.state.now[1].status)
    }

    @Test
    fun aWorkingRunOutsideLunamuxIsLiveWhileItsFileIsFreshAndGoesQuietWhenItStopsBeingRefreshed() {
        val refreshed = 1_000_000L
        show(listOf(group(refreshedAtMillis = refreshed, members = listOf(member("unhosted", "working", line = "Doing a thing")))))
        vm.toggleGroup("g1")
        val row = vm.state.rows.single { it.id == "member:g1:0" }
        assertEquals(refreshed, row.activityMillis)
        assertEquals(refreshed, row.fileRefreshedMillis)
        assertEquals(refreshed, vm.state.groups.getValue("g1").activityMillis)
        val at = { minutes: Long -> refreshed + minutes * 60_000L }
        assertTrue(AgentsOverviewBackingViewModel.isWorkingLive(row.activityMillis, at(4)))
        assertFalse(AgentsOverviewBackingViewModel.isWorkingLive(row.activityMillis, at(5)))
        assertFalse(AgentsOverviewBackingViewModel.isWorkingLive(null, at(0)))
        assertEquals("", AgentsOverviewBackingViewModel.agePrefix(row, at(4)))
        assertEquals("quiet 6m · ", AgentsOverviewBackingViewModel.agePrefix(row, at(6)))
        assertEquals("stale 25m · ", AgentsOverviewBackingViewModel.agePrefix(row, at(25)))
    }

    @Test
    fun aWorkingRunWithoutAFileTimeHasNoSignOfLife() {
        show(listOf(group(members = listOf(member("unhosted", "working")))))
        vm.toggleGroup("g1")
        assertNull(vm.state.rows.single { it.id == "member:g1:0" }.activityMillis)
        assertNull(vm.state.groups.getValue("g1").activityMillis)
    }

    @Test
    fun aHostedWorkingRunTakesTheNewerOfItsSessionAndItsFile() {
        layout("leaf-w1", "leaf-w2")
        show(
            listOf(group(refreshedAtMillis = 8_000, members = listOf(
                member("one", "working", recordId = "w1"),
                member("two", "working", recordId = "w2"),
            ))),
            session("w1", "working", lastActivityAt = 7_000),
            session("w2", "working", lastActivityAt = 9_000),
        )
        vm.toggleGroup("g1")
        val byId = vm.state.rows.associateBy { it.id }
        assertEquals(8_000L, byId.getValue("w1").activityMillis)
        assertEquals(9_000L, byId.getValue("w2").activityMillis)
        assertNull(byId.getValue("w1").fileRefreshedMillis, "a run with a session follows the session for staleness")
        assertEquals(9_000L, vm.state.groups.getValue("g1").activityMillis)
    }

    @Test
    fun aGroupFileIsStaleTwentyMinutesAfterItWasLastRefreshed() {
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
            listOf(group(members = listOf(member("g-run", "needs_you", since = 1_500)))),
            session("w", "working", since = 1),
            session("late", "needs_you", since = 3_000),
            session("early", "needs_you", since = 2_000),
        )
        vm.open()
        assertEquals("member:g1:0", vm.state.selectedId)
        assertEquals(Footer.NO_SESSION, vm.state.footer)
    }

    @Test
    fun theOverlayOpensOnTheFirstRowWhenNothingNeedsYou() {
        show(listOf(group(members = listOf(member("a", "working")))), session("w", "working", since = 99_999))
        vm.open()
        assertEquals("group:g1", vm.state.selectedId)
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
    fun theSelectionFollowsAGroupAsItsRunsAreExpandedAboveIt() {
        show(
            listOf(group(members = listOf(member("a", "working"), member("b", "idle")))),
            session("s", "working", since = 99_999),
        )
        vm.open()
        vm.moveSelection(1)
        assertEquals("s", vm.state.selectedId)
        vm.toggleGroup("g1")
        assertEquals("group:g1", vm.state.selectedId, "toggling selects the header it belongs to")
    }

    @Test
    fun rightExpandsAHeaderThenEntersItsRunsAndLeftGoesBackUpAndThenCollapses() = runNow {
        show(listOf(group(members = listOf(member("a", "working"), member("b", "idle")))))
        vm.open()
        assertEquals(Footer.GROUP_COLLAPSED, vm.state.footer)
        vm.stepIn()
        assertTrue(vm.state.groups.getValue("g1").expanded)
        assertEquals(Footer.GROUP_EXPANDED, vm.state.footer)
        assertEquals("group:g1", vm.state.selectedId)
        vm.stepIn()
        assertEquals("member:g1:0", vm.state.selectedId)
        vm.stepOut()
        assertEquals("group:g1", vm.state.selectedId)
        assertTrue(vm.state.groups.getValue("g1").expanded)
        vm.stepOut()
        assertFalse(vm.state.groups.getValue("g1").expanded)
        assertEquals("group:g1", vm.state.selectedId)
    }

    @Test
    fun spaceTogglesAHeaderOnlyWhileTheQueryIsEmpty() = runNow {
        show(listOf(group(members = listOf(member("a", "working")))))
        vm.open()
        vm.toggleSelected()
        assertTrue(vm.state.groups.getValue("g1").expanded)
        vm.toggleSelected()
        assertFalse(vm.state.groups.getValue("g1").expanded)
        vm.setQuery("x")
        vm.toggleSelected()
        assertFalse(vm.state.groups.getValue("g1").expanded)
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
    fun twoSessionsWithTheSameTitleAndNoRepositoryShowTheFolderTheyRunIn() {
        layout("leaf-a", "leaf-b", "leaf-c")
        show(
            emptyList(),
            session("a", "idle", title = "Counting to 10", repo = "proj"),
            session("b", "idle", title = "Counting to 10", repo = null, cwd = "/Users/me/notes/"),
            session("c", "idle", title = "Something else", repo = null, cwd = "/Users/me/other"),
        )
        val repos = vm.state.rows.associate { it.id to it.where }
        assertEquals(mapOf("a" to "proj", "b" to "notes", "c" to null), repos)
    }

    @Test
    fun peekAndCloseOnlyApplyToRowsWithAnOpenRecord() = runNow {
        show(listOf(group(members = listOf(member("a", "needs_you")))))
        vm.peek()
        assertTrue(sent.isEmpty(), "a header with no lead record has nothing to peek")
        assertFalse(vm.state.rows.any { it.canClose })
    }

    @Test
    fun withNoGroupsEveryRowIsAnOrdinarySession() {
        show(emptyList(), session("a", "working"), session("b", "idle"))
        assertTrue(vm.state.rows.all { it.kind == RowKind.SESSION && !it.unhosted })
        assertTrue(vm.state.groups.isEmpty())
    }

    private companion object {
        const val EARLIER_LIMIT = AgentsOverviewBackingViewModel.EARLIER_LIMIT
    }
}
