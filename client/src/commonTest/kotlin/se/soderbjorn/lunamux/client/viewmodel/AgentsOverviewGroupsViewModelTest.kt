/**
 * Tests for the programme-group part of [AgentsOverviewBackingViewModel]: where a group box sits
 * among the single sessions, what a collapsed and an expanded box show, how its members count
 * in the chip, and what ↵ does on a header and on a member Lunamux does not host.
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

    private fun session(id: String, state: String, since: Long = 1_000, endedAt: Long? = null) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = "session $id", links = emptyList(),
        repo = "proj", branch = null, cwd = "/work/proj", nowLine = null, question = null,
        leafId = "leaf-$id".takeIf { endedAt == null }, endedAt = endedAt,
    )

    private fun layout(vararg leafIds: String) {
        val panes = leafIds.map { Pane(LeafNode(id = it, sessionId = "s-$it", title = it), 0.0, 0.0, 1.0, 1.0, 0) }
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig(id = "tab-1", title = "one", panes = panes)), activeTabId = "tab-1"))
    }

    private fun member(title: String, state: String, since: Long = 1_000, label: String? = null, recordId: String? = null, line: String? = null) =
        AgentGroupMember(title = title, state = state, sinceMillis = since, label = label, recordId = recordId, line = line)

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
        assertEquals("+ 1 working, 1 in review", rows[2].title)
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
        assertEquals("Open lead", header.actionLabel)
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
    fun returnOnAMemberLunamuxDoesNotHostOpensTheLead() = runNow {
        show(listOf(group(members = listOf(member("a", "needs_you")))))
        val unhosted = vm.state.rows.first { it.id == "member:g1:0" }
        assertTrue(unhosted.unhosted)
        vm.activate(unhosted)
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenAgentGroupLead("g1")), sent)
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
