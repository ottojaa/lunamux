/**
 * Tests for the programme-group part of [AgentsOverviewBackingViewModel]: where a group box sits
 * among the single sessions, what a collapsed and an expanded box show, how its members count
 * in the chip (held members counted apart from those that need you), what ↵ and the action
 * labels are on a header, a member and a member Lunamux does not host, how the open lead sits
 * inside its box, and when a working row counts as quiet.
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
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.RowKind
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, links = links,
        repo = repo, branch = null, cwd = cwd, nowLine = nowLine, question = question,
        leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt, lastActivityAt = lastActivityAt,
    )

    private fun layout(vararg leafIds: String) {
        val panes = leafIds.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes)), activeTabId = "tab-1"))
    }

    private fun member(
        title: String, state: String, since: Long = 1_000, label: String? = null, recordId: String? = null,
        line: String? = null, links: List<String> = emptyList(),
    ) = AgentGroupMember(title = title, state = state, sinceMillis = since, label = label, recordId = recordId, line = line, links = links)

    private fun group(
        id: String = "g1", members: List<AgentGroupMember>, leadRecord: String? = null, leadLive: Boolean = false,
        lastSaid: String? = null, tag: String? = "Programme",
    ) = AgentGroup(
        id = id, title = "Release $id", tag = tag, repo = "proj",
        lead = AgentGroupLead(folder = "/work/lead", recordId = leadRecord, live = leadLive, lastSaid = lastSaid),
        members = members,
    )

    private fun show(groups: List<AgentGroup>, vararg sessions: AgentSessionSummary) {
        windowState.updateAgentSessions(sessions.toList(), true)
        windowState.updateAgentGroups(groups)
    }

    @Test
    fun aGroupWithAMemberThatNeedsYouSitsInNowRankedByThatMember() {
        show(
            listOf(group(members = listOf(member("a", "needs_you", since = 2_000), member("b", "working", since = 100)))),
            session("old-working", "working", since = 500),
            session("late-needs", "needs_you", since = 3_000),
            session("early-needs", "needs_you", since = 1_000),
        )
        assertEquals(
            listOf("early-needs", "group:g1", "member:g1:0", "more:g1", "late-needs", "old-working"),
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
        // The group's working member has waited longest of the working ones, so its box comes first.
        assertEquals(listOf("n", "group:g1", "more:g1", "w"), vm.state.now.map { it.id })
    }

    @Test
    fun aQuietGroupLeadsEarlierCollapsed() {
        show(
            listOf(group(members = listOf(member("a", "idle"), member("b", "ended")))),
            session("done", "idle", since = 1),
        )
        assertTrue(vm.state.now.isEmpty())
        assertEquals(listOf("group:g1", "more:g1", "done"), vm.state.earlier.map { it.id })
    }

    @Test
    fun collapsedShowsOnlyMembersThatNeedYouAndACountLine() {
        show(listOf(group(members = listOf(
            member("a", "needs_you"), member("b", "working"), member("c", "in review", label = "in review"),
        ))))
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "member:g1:0", "more:g1"), rows.map { it.id })
        assertEquals(RowKind.GROUP, rows[0].kind)
        assertEquals(RowKind.GROUP_MORE, rows[2].kind)
        assertEquals("2 more", rows[2].title)
        val g = vm.state.groups.getValue("g1")
        assertEquals(1, g.needYouCount)
        assertEquals("1 working · 1 in review", g.summary)
        assertFalse(g.expanded)
    }

    @Test
    fun expandingShowsEveryMemberInFileOrderAndDropsTheMoreRow() = runNow {
        show(listOf(group(members = listOf(member("a", "working"), member("b", "needs_you"), member("c", "idle")))))
        vm.activate(vm.state.rows.last())
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "member:g1:0", "member:g1:1", "member:g1:2"), rows.map { it.id })
        assertTrue(vm.state.groups.getValue("g1").expanded)
        assertEquals(0, vm.state.selected, "the highlight stays on the header")
        vm.toggleGroup("g1")
        assertEquals(3, vm.state.rows.size)
    }

    @Test
    fun chipCountsIncludeGroupMembersEvenWhenCollapsed() {
        show(
            listOf(group(members = listOf(member("a", "needs_you"), member("b", "working"), member("c", "working"), member("d", "idle")))),
            session("s1", "needs_you"),
        )
        assertEquals(2, vm.state.needYouCount)
        assertEquals(2, vm.state.workingCount)
    }

    @Test
    fun aMemberMatchedToASessionIsDrawnOnlyInsideTheGroup() {
        layout("leaf-r1")
        show(
            listOf(group(members = listOf(member("Task one", "needs_you", recordId = "r1", line = "Which branch?")))),
            session("r1", "needs_you"),
        )
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "r1"), rows.map { it.id })
        assertEquals("Task one", rows[1].title)
        assertEquals("Which branch?", rows[1].question)
        assertEquals("Jump", rows[1].actionLabel)
    }

    @Test
    fun returnOnAHeaderJumpsToTheLeadWhenItIsOpen() = runNow {
        layout("leaf-lead")
        show(listOf(group(members = listOf(member("a", "working")), leadRecord = "lead", leadLive = true)), session("lead", "idle"))
        val header = vm.state.rows.first { it.kind == RowKind.GROUP }
        assertEquals("Jump", header.actionLabel)
        vm.activate(header)
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-lead")), sent)
    }

    @Test
    fun returnOnAHeaderOpensTheLeadInANewTabWhenItIsNotOpen() = runNow {
        layout("leaf-other")
        show(listOf(group(members = listOf(member("a", "working")))))
        val header = vm.state.rows.first { it.kind == RowKind.GROUP }
        assertEquals("Open", header.actionLabel)
        vm.activate(header)
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
    fun aMemberLunamuxDoesNotHostHasNothingToOpenAndReturnDoesNothing() = runNow {
        show(listOf(group(members = listOf(member("a", "needs_you")))))
        val unhosted = vm.state.rows.first { it.id == "member:g1:0" }
        assertTrue(unhosted.unhosted)
        assertEquals("", unhosted.actionLabel)
        vm.activate(unhosted)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun noRowOffersOpenLead() {
        layout("leaf-r1")
        show(
            listOf(group(members = listOf(member("a", "needs_you"), member("b", "working", recordId = "r1")))),
            session("r1", "working"),
        )
        vm.toggleGroup("g1")
        assertTrue(vm.state.rows.none { it.actionLabel == "Open lead" })
    }

    @Test
    fun aMemberIsJumpWhenItsSessionIsOpenOpenWhenItEndedAndNothingWhenUnmatched() {
        layout("leaf-live")
        show(
            listOf(group(members = listOf(
                member("live one", "working", recordId = "live"),
                member("done one", "idle", recordId = "done"),
                member("nobody's", "idle"),
            ))),
            session("live", "working"),
            session("done", "ended", endedAt = 5_000),
        )
        vm.toggleGroup("g1")
        val labels = vm.state.rows.filter { it.groupId == "g1" && it.kind == RowKind.SESSION }.associate { it.title to it.actionLabel }
        assertEquals(mapOf("live one" to "Jump", "done one" to "Open", "nobody's" to ""), labels)
    }

    @Test
    fun aMemberShowsOnlyTheLinksTheFileGivesNotOnesReadFromItsBranch() {
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
    fun heldMembersAreCountedAsHeldNotAsNeedingYouNorIdle() {
        show(listOf(group(members = listOf(
            member("a", "needs_you"),
            member("b", "working"),
            member("c", "idle", label = "held: not merging yet"),
            member("d", "idle", label = "Held for the owner"),
            member("e", "idle", label = "ready to test"),
            member("f", "working", label = "held up by a build"),
        ))))
        val g = vm.state.groups.getValue("g1")
        assertEquals(1, g.needYouCount)
        assertEquals("2 working · 2 held · 1 ready to test", g.summary)
        // The chip counts what needs you and what works; a held member is neither.
        assertEquals(1, vm.state.needYouCount)
        assertEquals(2, vm.state.workingCount)
    }

    @Test
    fun theOpenLeadIsDrawnInsideItsBoxAsTheProgrammeSessionAndNotAgainAsASingle() {
        layout("leaf-lead")
        show(
            listOf(group(members = listOf(member("a", "idle")), leadRecord = "lead", leadLive = true)),
            session("lead", "working", nowLine = "Bash ./gradlew test", lastActivityAt = 9_000),
        )
        vm.toggleGroup("g1")
        val rows = vm.state.rows
        assertEquals(listOf("group:g1", "lead", "member:g1:0"), rows.map { it.id })
        val lead = rows[1]
        assertEquals("Programme session", lead.title)
        assertEquals("working", lead.state)
        assertEquals("Bash ./gradlew test", lead.nowLine)
        assertEquals("Jump", lead.actionLabel)
        assertEquals(1, vm.state.workingCount)
    }

    @Test
    fun aQuietLeadAddsNothingToTheCountsAndALeadThatNeedsYouLiftsTheGroup() {
        layout("leaf-lead")
        show(
            listOf(group(members = listOf(member("a", "working")), leadRecord = "lead", leadLive = true)),
            session("lead", "idle"),
        )
        assertEquals("1 working", vm.state.groups.getValue("g1").summary)
        assertEquals(0, vm.state.needYouCount)

        show(
            listOf(group(members = listOf(member("a", "idle")), leadRecord = "lead", leadLive = true)),
            session("lead", "needs_you", question = "Which branch?"),
        )
        assertEquals(1, vm.state.needYouCount)
        assertEquals(1, vm.state.groups.getValue("g1").needYouCount)
        val rows = vm.state.now
        assertEquals(listOf("group:g1", "lead", "more:g1"), rows.map { it.id })
        assertEquals("Which branch?", rows[1].question)
    }

    @Test
    fun theMoreRowDoesNotCountAQuietLeadThatTheHeaderCountsLeaveOut() {
        layout("leaf-lead")
        show(
            listOf(group(members = listOf(member("a", "idle", label = "ready to test")), leadRecord = "lead", leadLive = true)),
            session("lead", "idle"),
        )
        val rows = vm.state.rows
        assertEquals("1 ready to test", vm.state.groups.getValue("g1").summary)
        assertEquals("1 more", rows.single { it.id == "more:g1" }.title)
    }

    @Test
    fun aWorkingRowThatLunamuxHostsCarriesItsLastSignOfLifeAndNothingElseDoes() {
        layout("leaf-w1", "leaf-w2", "leaf-i1")
        show(
            listOf(group(members = listOf(member("unhosted", "working", line = "Doing a thing")))),
            session("w1", "working", since = 1_000, lastActivityAt = 7_000),
            session("w2", "working", since = 2_000),
            session("i1", "idle"),
        )
        vm.toggleGroup("g1")
        val byId = vm.state.rows.associateBy { it.id }
        assertEquals(7_000L, byId.getValue("w1").activityMillis)
        assertEquals(2_000L, byId.getValue("w2").activityMillis, "with no activity time it falls back to when it started working")
        assertEquals(null, byId.getValue("i1").activityMillis)
        assertEquals(null, byId.getValue("member:g1:0").activityMillis, "an unhosted member has no sign of life to show")
    }

    @Test
    fun aGroupHeaderCarriesTheLatestSignOfLifeOfItsHostedWorkingMembers() {
        layout("leaf-w1", "leaf-w2")
        show(
            listOf(group(members = listOf(
                member("one", "working", recordId = "w1"),
                member("two", "working", recordId = "w2"),
                member("unhosted", "working"),
            ))),
            session("w1", "working", lastActivityAt = 7_000),
            session("w2", "working", lastActivityAt = 9_000),
        )
        assertEquals(9_000L, vm.state.groups.getValue("g1").activityMillis)

        show(listOf(group(members = listOf(member("unhosted", "working")))))
        assertEquals(null, vm.state.groups.getValue("g1").activityMillis)
    }

    @Test
    fun aWorkingRowGoesQuietAfterFiveMinutesWithoutASignOfLife() {
        val q = AgentsOverviewBackingViewModel.QUIET_AFTER_MS
        assertEquals(5 * 60_000L, q)
        assertFalse(AgentsOverviewBackingViewModel.isQuiet(10_000, 10_000 + q - 1))
        assertTrue(AgentsOverviewBackingViewModel.isQuiet(10_000, 10_000 + q))
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
        val repos = vm.state.rows.associate { it.id to it.repo }
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
    fun theSelectedHeaderCarriesTheLeadsLastWords() {
        show(listOf(group(members = listOf(member("a", "working")), lastSaid = "Merged the first batch.")))
        assertEquals("Merged the first batch.", vm.state.groups.getValue("g1").leadLastSaid)
    }

    @Test
    fun withNoGroupsEveryRowIsAnOrdinarySession() {
        show(emptyList(), session("a", "working"), session("b", "idle"))
        assertTrue(vm.state.rows.all { it.kind == RowKind.SESSION && !it.unhosted })
        assertTrue(vm.state.groups.isEmpty())
    }
}
