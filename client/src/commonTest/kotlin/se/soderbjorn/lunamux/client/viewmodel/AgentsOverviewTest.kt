/**
 * Tests for the agents overview, read the way a person reads it: which rows they see and in what
 * order, what Return does on each, what the footer says, and what a search, a resume, a peek and a
 * close ask them.
 *
 * Contains [AgentsOverviewTest].
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
import se.soderbjorn.lunamux.client.runNow
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsOverviewTest {
    private val now = 100L * 24 * 60 * 60_000
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val windowState = WindowStateRepository()
    /** What the overview sends, apart from its look at the selected row, which every push triggers. */
    private val sent = mutableListOf<WindowCommand>()
    private val inspected = mutableListOf<String>()
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) {
        if (it is WindowCommand.InspectAgentSession) inspected += it.recordId else sent += it
    }

    private fun pane(leaf: String) = Pane(LeafNode(id = leaf, sessionId = "s-$leaf", title = leaf), 0.0, 0.0, 1.0, 1.0, 0)

    /** Two listed tabs and one background tab, with a pane named `leaf-<session id>` for each id given. */
    private fun layout(listed: List<String>, background: List<String> = emptyList(), second: List<String> = emptyList()) {
        windowState.updateConfig(
            WindowConfig(
                tabs = listOfNotNull(
                    TabConfig("tab-1", "one", panes = listed.map { pane("leaf-$it") }),
                    TabConfig("tab-2", "two", panes = second.map { pane("leaf-$it") }).takeIf { second.isNotEmpty() },
                    TabConfig("tab-bg", "agents", panes = background.map { pane("leaf-$it") }, isBackground = true)
                        .takeIf { background.isNotEmpty() },
                ),
                activeTabId = "tab-1",
            ),
        )
    }

    private fun session(
        id: String,
        state: String = "working",
        title: String? = "session $id",
        taskId: String? = null,
        background: Boolean = false,
        placed: Boolean = true,
        since: Long = now - 10 * minute,
        nowLine: String? = null,
        question: String? = null,
        endedAt: Long? = null,
        inTab: Boolean = false,
    ) = AgentSessionSummary(
        id = id, agent = "claude", state = state, stateSince = since, title = title, repo = "proj", cwd = "/work/proj",
        cwdShown = "~/work/proj", nowLine = nowLine, question = question, leafId = "leaf-$id".takeIf { placed && endedAt == null },
        endedAt = endedAt, lastActivityAt = endedAt ?: since, startedAt = since, taskId = taskId, background = background, inTab = inTab,
    )

    private fun sessions(vararg all: AgentSessionSummary) = windowState.updateAgentSessions(all.toList(), true)

    private fun task(main: String?, status: String? = null, id: String = "g1", title: String = "Release") = AgentTask(
        id = id, title = title, repo = "proj", source = "agents", hasOpen = false, mainConversation = main, status = status,
    )

    @Test
    fun aTaskIsOneRowWhoseBackgroundAgentsHaveNoRowOfTheirOwn() {
        layout(listed = listOf("main", "solo"), background = listOf("b1", "b2"))
        sessions(
            session("main", taskId = "g1", nowLine = "Editing Release.kt"),
            session("b1", taskId = "g1", background = true),
            session("b2", taskId = "g1", background = true),
            session("solo", state = "done"),
        )
        windowState.updateAgentTasks(listOf(task("main", status = "Waiting on review")))
        vm.open()
        assertEquals(listOf("task:g1", "solo"), vm.state.now.map { it.id })
        val row = vm.state.now.first()
        assertEquals("Release", row.title)
        assertEquals("3 working", row.stateWord)
        assertEquals("Waiting on review", row.detail, "the task's own status line is what the row says")
        assertEquals(Enter.JUMP, row.enter)
    }

    @Test
    fun theRowThatNeedsYouIsSelectedAndShowsTheQuestion() {
        layout(listed = listOf("a", "b"))
        sessions(
            session("a", nowLine = "Reading the schema"),
            session("b", state = "needs_you", question = "**Drop** the `users` table?"),
        )
        vm.open()
        val selected = assertNotNull(vm.state.selectedRow)
        assertEquals("b", selected.id)
        assertEquals("needs you", selected.stateWord)
        assertEquals("\"Drop the users table?\"", selected.detail, "markdown in the question reads as plain text")
        assertEquals("Reading the schema", vm.state.now.first { it.id == "a" }.detail)
        assertEquals(1, vm.state.needYouCount)
        assertEquals(1, vm.state.workingCount)
    }

    @Test
    fun aBackgroundAgentGetsARowOnlyWhileItWaitsAndReturnShowsItsTab() = runNow {
        layout(listed = listOf("a"), background = listOf("bg"))
        sessions(session("a", state = "done"), session("bg", background = true))
        vm.open()
        assertEquals(listOf("a"), vm.state.now.map { it.id }, "a working background agent stays out of sight")

        sessions(session("a", state = "done"), session("bg", state = "needs_you", background = true, question = "Push it?"))
        val row = vm.state.now.first { it.id == "bg" }
        assertEquals(Enter.SHOW_WAITING, row.enter)
        vm.select("bg")
        assertEquals("answer it · opens as a tab until it works on", vm.state.footer.first().label)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ShowWaitingAgent("bg")), sent)
        assertTrue(!vm.state.open)
    }

    @Test
    fun returnOnALiveRowGoesToItsTabAndClosesTheOverview() = runNow {
        layout(listed = listOf("a"), second = listOf("b"))
        sessions(session("a"), session("b", since = now - 5 * minute))
        vm.open()
        vm.select("b")
        vm.activateSelected()
        assertEquals(
            listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-2"), WindowCommand.SetFocusedPane("tab-2", "leaf-b")),
            sent,
        )
        assertTrue(!vm.state.open)
    }

    @Test
    fun aLiveConversationNobodyCanPlaceStillCountsAndStillHasARow() {
        layout(listed = listOf("a"))
        sessions(session("a"), session("lost", state = "needs_you", placed = false))
        vm.open()
        assertEquals(setOf("a", "lost"), vm.state.now.map { it.id }.toSet())
        assertEquals(1, vm.state.needYouCount, "the server's word that it waits is enough")
        assertEquals(1, vm.state.workingCount)
    }

    @Test
    fun finishedConversationsFallIntoRecentAndFortnightAndOldOnesDisappear() {
        layout(listed = listOf("a"))
        sessions(
            session("a"),
            session("yesterday", state = "ended", endedAt = now - day),
            session("lastWeek", state = "ended", endedAt = now - 7 * day),
            session("ancient", state = "ended", endedAt = now - 30 * day),
        )
        vm.open()
        assertEquals(listOf("a"), vm.state.now.map { it.id })
        assertEquals(listOf("yesterday"), vm.state.recent.map { it.id })
        assertEquals(listOf("lastWeek"), vm.state.fortnight.map { it.id })
        assertTrue(vm.state.rows.none { it.id == "ancient" })
    }

    @Test
    fun earlierShowsFiveAndStepsPastTheLastRevealTheRest() {
        layout(listed = listOf("a"))
        sessions(
            session("a"),
            *(1..8).map { session("old$it", state = "ended", endedAt = now - it * minute) }.toTypedArray(),
        )
        vm.open()
        assertEquals(5, vm.state.earlier.size)
        assertEquals(3, vm.state.earlierHidden)
        repeat(6) { vm.moveSelection(1) }
        assertEquals(8, vm.state.earlier.size, "moving past the fifth earlier row shows the rest")
        assertEquals(0, vm.state.earlierHidden)
    }

    @Test
    fun resumingAClosedConversationAsksFirstAndOffersItsOwnTabWhenItIsStillThere() = runNow {
        layout(listed = listOf("a"))
        sessions(
            session("a"),
            session("shell", state = "ended", endedAt = now - minute, inTab = true),
            session("gone", state = "ended", endedAt = now - 2 * minute),
        )
        vm.open()

        vm.select("shell")
        vm.activateSelected()
        assertEquals("Resume it in its tab?", vm.state.ask?.question)
        assertEquals("↵ resume", vm.state.footer.first().let { "${it.key} ${it.label}" })
        assertTrue(sent.none { it is WindowCommand.ResumeAgentSession }, "nothing is sent before the person agrees")
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("shell", inTab = true)), sent)

        sent.clear()
        vm.open()
        vm.select("gone")
        vm.activateSelected()
        assertEquals("Resume it in a new tab?", vm.state.ask?.question)
        vm.back()
        assertNull(vm.state.ask, "esc withdraws the question")
        assertTrue(vm.state.open, "and leaves the overview open")
        assertTrue(sent.none { it is WindowCommand.ResumeAgentSession })
    }

    @Test
    fun aSearchShowsSearchingThenTheMatchesWithTheirSnippetAndWhereTheyMatched() = runNow {
        layout(listed = listOf("a"))
        val hit = session("a", title = "Fix login")
        sessions(hit, session("old", state = "ended", endedAt = now - day, title = "Old chat"))
        vm.open()
        vm.setQuery("login")
        vm.search()
        assertEquals(listOf<WindowCommand>(WindowCommand.SearchAgentSessions("login")), sent)
        assertTrue(vm.state.searching)
        assertEquals("Searching…", vm.state.emptyText)

        windowState.updateAgentMatches(
            WindowEnvelope.AgentSessionMatches(
                "login",
                listOf(AgentSessionMatch(summary = hit, field = "prompt", snippet = "…fix the login redirect…", start = 9, length = 5)),
            ),
        )
        assertTrue(!vm.state.searching)
        assertEquals("1 MATCH", vm.state.matchHeading)
        val row = vm.state.matches.single()
        assertEquals("…fix the login redirect…", row.snippet)
        assertEquals("login", row.snippet!!.substring(row.matchStart, row.matchStart + row.matchLength))
        assertContains(row.extra, "in first prompt")
        assertTrue(vm.state.now.isEmpty() && vm.state.earlier.isEmpty(), "browsing rows give way to the matches")
    }

    @Test
    fun aSearchWithNoMatchesSaysWhatSearchCovers() {
        layout(listed = listOf("a"))
        sessions(session("a"))
        vm.open()
        vm.setQuery("zzz")
        windowState.updateAgentMatches(WindowEnvelope.AgentSessionMatches("zzz", emptyList()))
        assertContains(vm.state.emptyText, "Nothing matches “zzz”")
    }

    @Test
    fun closeAsksWhatTheAgentIsDoingAndOnlyConfirmingSendsTheCloseWithNoEmptySearchNeeded() = runNow {
        layout(listed = listOf("a", "b"))
        val busy = session("b", nowLine = "Running the migration", since = now - minute)
        sessions(session("a", state = "done"), busy)
        vm.open()
        vm.select("b")
        vm.requestClose()
        val confirm = assertNotNull(vm.state.confirm)
        assertEquals(listOf("It is working right now: Running the migration"), confirm.warnings)
        assertTrue(confirm.cancelFocused, "cancel is the default answer")
        vm.cancelClose()
        assertTrue(sent.none { it is WindowCommand.CloseSession }, "cancel sends nothing")

        // The close works from search results too: there is no empty-search gate.
        vm.setQuery("session")
        windowState.updateAgentMatches(
            WindowEnvelope.AgentSessionMatches("session", listOf(AgentSessionMatch(summary = busy, field = "title"))),
        )
        vm.requestClose()
        assertNotNull(vm.state.confirm)
        vm.moveConfirmFocus()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseSession("s-leaf-b")), sent.filterIsInstance<WindowCommand.CloseSession>())
    }

    @Test
    fun aBackgroundAgentsRowCannotBeClosedFromTheOverview() = runNow {
        layout(listed = listOf("a"), background = listOf("bg"))
        sessions(session("a", state = "done"), session("bg", state = "needs_you", background = true))
        vm.open()
        vm.select("bg")
        vm.requestClose()
        assertNull(vm.state.confirm)
    }

    @Test
    fun peekAsksForTheScreenAndTheFooterSaysArrowPeek() = runNow {
        layout(listed = listOf("a"))
        sessions(session("a"))
        vm.open()
        assertTrue(vm.state.footer.any { it.key == "→" && it.label == "peek" })
        vm.peek()
        assertEquals("a", inspected.last())
        assertTrue(vm.state.peek!!.loading)
        windowState.updateAgentInspection(WindowEnvelope.AgentSessionInspection("a", live = true, screen = listOf("$ make test", "ok")))
        val peek = vm.state.peek!!
        assertEquals(listOf("$ make test", "ok"), peek.lines)
        assertTrue(peek.live)
        vm.back()
        assertNull(vm.state.peek)
        assertTrue(vm.state.open, "esc leaves the peek first")
    }

    private fun taskRow() = vm.state.rows.first { it.id == "task:g1" }

    @Test
    fun returnOnATaskReachesWhoeverNeedsYouThenItsMainConversation() {
        layout(listed = listOf("main", "helper"))
        sessions(session("main", taskId = "g1"), session("helper", taskId = "g1", state = "needs_you", since = now - minute))
        windowState.updateAgentTasks(listOf(task("main")))
        vm.open()
        assertEquals("helper", taskRow().recordId, "a helper that asks is reached while the main one only works")
        sessions(session("main", taskId = "g1", state = "needs_you"), session("helper", taskId = "g1", state = "needs_you", since = now - minute))
        assertEquals("main", taskRow().recordId, "when the main conversation asks too, it comes first")
        sessions(session("main", taskId = "g1"), session("helper", taskId = "g1"))
        assertEquals("main", taskRow().recordId)
        assertEquals(Enter.JUMP, taskRow().enter)
    }

    @Test
    fun aMainConversationInABackgroundTabIsShownWhileItAsksAndOtherwiseOnlyPeekedAt() {
        layout(listed = listOf("helper"), background = listOf("main"))
        sessions(session("main", taskId = "g1", background = true), session("helper", taskId = "g1", state = "done"))
        windowState.updateAgentTasks(listOf(task("main")))
        vm.open()
        assertEquals("helper", taskRow().recordId, "a working main one hides nothing: Return goes to a conversation in a listed tab")
        sessions(session("main", taskId = "g1", background = true))
        assertEquals(Enter.NONE, taskRow().enter)
        assertEquals(AgentsOverviewBackingViewModel.HIDDEN_MAIN_NOTE, taskRow().extra)
        assertTrue(vm.state.footer.none { it.key == "↵" } && vm.state.footer.any { it.key == "→" })
        sessions(session("main", taskId = "g1", background = true, state = "needs_you"))
        assertEquals(Enter.SHOW_WAITING, taskRow().enter)
    }

    @Test
    fun aClosedMainConversationStaysInNowWhileItsQuestionWaitsAndAsksBeforeResuming() = runNow {
        layout(listed = listOf("a"))
        sessions(session("a"), session("main", taskId = "g1", state = "ended", endedAt = now - minute))
        windowState.updateAgentTasks(listOf(task("main").copy(questionWaits = true, status = "A question waits for you")))
        vm.open()
        assertEquals(setOf("a", "task:g1"), vm.state.now.map { it.id }.toSet())
        assertEquals(0, vm.state.needYouCount, "nothing counts until the resumed conversation asks")
        vm.select("task:g1")
        vm.activateSelected()
        assertEquals("conversation closed", vm.state.ask?.tag)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("main", inTab = false)), sent)
    }

    @Test
    fun aTaskWhoseMainConversationIsOnlyAStoredIdResumesThatIdAndOneWithNothingStartsItsOpenCommand() = runNow {
        layout(listed = listOf("a"))
        sessions(session("a", state = "done"))
        windowState.updateAgentTasks(
            listOf(
                task("gone-id", id = "g1").copy(hasOpen = true, startedAtMillis = now - minute),
                task(null, id = "g2", title = "Fresh").copy(hasOpen = true, startedAtMillis = now - 2 * minute),
            ),
        )
        vm.open()
        vm.select("task:g1")
        vm.activateSelected()
        vm.activateSelected()
        assertEquals(WindowCommand.ResumeAgentSession("gone-id", inTab = false), sent.last(), "resumed by its stored id, never another session")
        vm.open()
        vm.select("task:g2")
        vm.activateSelected()
        assertEquals("Start one in a new tab?", vm.state.ask?.question)
        vm.activateSelected()
        assertEquals(WindowCommand.OpenTask("g2"), sent.last())
    }

    @Test
    fun nowKeepsStartOrderWhateverTheStatesDoAndAClosedConversationLeavesAtOnce() {
        layout(listed = listOf("a", "b", "c"))
        sessions(session("a", since = now - 30 * minute), session("b", state = "needs_you", since = now - 20 * minute), session("c", state = "done", since = now - 10 * minute))
        vm.open()
        assertEquals(listOf("a", "b", "c"), vm.state.now.map { it.id })
        sessions(session("a", state = "done", since = now - 30 * minute), session("b", since = now - 20 * minute), session("c", state = "needs_you", since = now - 10 * minute))
        assertEquals(listOf("a", "b", "c"), vm.state.now.map { it.id }, "a state change never moves a row")
        sessions(session("a", state = "ended", endedAt = now - 1), session("b"), session("c", state = "done"))
        assertEquals(listOf("b", "c"), vm.state.now.map { it.id })
        assertEquals(listOf("a"), vm.state.recent.map { it.id })
    }

    @Test
    fun aRowSaysWhereItRunsAndNamesItsTabUnlessTheTabNameSaysTheTitleAlready() {
        layout(listed = listOf("a"), second = listOf("b"), background = listOf("bg"))
        sessions(session("a"), session("b", title = "two"), session("bg", state = "needs_you", background = true))
        vm.open()
        val a = vm.state.now.first { it.id == "a" }
        assertEquals("proj", a.where)
        assertEquals(
            listOf(AgentsOverviewBackingViewModel.Chip(AgentsOverviewBackingViewModel.ChipKind.FOLDER, "~/work/proj"), AgentsOverviewBackingViewModel.Chip(AgentsOverviewBackingViewModel.ChipKind.TAB, "one")),
            a.chips,
        )
        assertEquals(listOf(AgentsOverviewBackingViewModel.ChipKind.FOLDER), vm.state.now.first { it.id == "bg" }.chips.map { it.kind }, "a background tab is not named")
        assertTrue(vm.state.now.first { it.id == "b" }.chips.none { it.kind == AgentsOverviewBackingViewModel.ChipKind.TAB })
    }

    @Test
    fun aSearchHitInOneConversationOfATaskIsOneRowThatOpensThatConversation() {
        layout(listed = listOf("s1", "s2"))
        val s1 = session("s1", taskId = "g1")
        val s2 = session("s2", taskId = "g1")
        sessions(s1, s2)
        windowState.updateAgentTasks(listOf(task("s1")))
        vm.open()
        vm.setQuery("migration")
        windowState.updateAgentMatches(
            WindowEnvelope.AgentSessionMatches(
                "migration",
                listOf(AgentSessionMatch(summary = s2, field = "transcript", snippet = "ran the migration", start = 8, length = 9), AgentSessionMatch(summary = s1, field = "transcript", snippet = "migration", start = 0, length = 9)),
            ),
        )
        val row = vm.state.matches.single()
        assertEquals("task:g1", row.id)
        assertEquals("s2", row.recordId, "the conversation that matched, not the task's main one")
        assertEquals("in transcript of session s2", row.extra)
        assertEquals("1 MATCH", vm.state.matchHeading)
    }
}
