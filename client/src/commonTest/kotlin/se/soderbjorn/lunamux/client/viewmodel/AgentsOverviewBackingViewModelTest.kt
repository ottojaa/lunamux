/**
 * Tests for [AgentsOverviewBackingViewModel]: how the server's session list
 * becomes NOW and EARLIER rows, the chip counts, the title chain, the
 * search over every session (match mode, stale answers, resume notes), the
 * keyboard selection, the peek, the close confirmation, and the commands that
 * jump, resume, search, inspect and close send.
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.WorldConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class AgentsOverviewBackingViewModelTest {

    /** Runs a suspend block that never actually suspends (the fake `send` returns at once) and rethrows its failure. */
    private fun runNow(block: suspend () -> Unit) {
        var outcome: Result<Unit>? = null
        block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        outcome!!.getOrThrow()
    }

    private val sent = mutableListOf<WindowCommand>()
    private val windowState = WindowStateRepository()
    // A fixed clock well past the idle grace, so the sessions built with small `since` values are
    // old enough for EARLIER and young enough for its first band.
    private val now = 1_000_000L
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) { sent += it }

    private fun session(
        id: String, state: String, since: Long = 1_000, title: String? = null, agent: String = "claude",
        leaf: String? = "leaf-$id", endedAt: Long? = null, branch: String? = null, cwd: String? = "/work/proj",
        nowLine: String? = null, question: String? = null, firstPrompt: String? = null, lastSaid: String? = null,
        cwdHome: Boolean = false, repo: String? = "proj", lastActivityAt: Long? = null, startedAt: Long? = null,
    ) = AgentSessionSummary(
        id = id, agent = agent, state = state, stateSince = since, title = title, links = emptyList(),
        repo = repo, branch = branch, cwd = cwd, nowLine = nowLine, question = question, leafId = leaf, endedAt = endedAt,
        firstPrompt = firstPrompt, lastSaid = lastSaid, cwdHome = cwdHome, startedAt = startedAt,
        // A working session is moving (not quiet) unless a test says otherwise, as the chip counts only those.
        lastActivityAt = lastActivityAt ?: if (state == "working") now - 1_000 else null,
    )

    private fun leaf(id: String, custom: String? = null) =
        Pane(LeafNode(id = id, sessionId = "s-$id", title = id, customName = custom), 0.0, 0.0, 1.0, 1.0, 0)

    private fun layout(vararg leaves: Pane, world: String? = null) {
        val tab = TabConfig(id = "tab-1", title = "one", panes = leaves.toList())
        windowState.updateConfig(
            if (world == null) WindowConfig(tabs = listOf(tab), activeTabId = "tab-1")
            else WindowConfig(
                worlds = listOf(
                    WorldConfig("w1", "one", tabs = listOf(TabConfig("other", "other"))),
                    WorldConfig("w2", "two", tabs = listOf(tab)),
                ),
                activeWorldId = "w1",
            ),
        )
    }

    @Test
    fun nowIsOrderedByWhenEachSessionStartedWhateverItsState() {
        windowState.updateAgentSessions(
            listOf(
                session("w-new", "working", since = 5_000, startedAt = 300),
                session("n-new", "needs_you", since = 4_000, startedAt = 200),
                session("w-old", "working", since = 1_000, startedAt = 100),
                session("n-old", "needs_you", since = 2_000, startedAt = 400),
            ),
            claudeConnected = true,
        )
        assertEquals(listOf("w-old", "n-new", "w-new", "n-old"), vm.state.now.map { it.id })
    }

    @Test
    fun earlierListsOpenIdleAndClosedSessionsTogetherNewestFirstAndCutsOnlyTheSecondBand() {
        val rows = (1..7).map { session("e$it", if (it % 2 == 0) "idle" else "ended", since = it * 1_000L, endedAt = it * 1_000L) }
        windowState.updateAgentSessions(rows, false)
        assertEquals(listOf("e7", "e6", "e5", "e4", "e3", "e2", "e1"), vm.state.recent.map { it.id }, "all of the first band shows")
        assertTrue(vm.state.fortnight.isEmpty())
        assertEquals(0, vm.state.earlierHidden)
    }

    @Test
    fun chipCountsEqualTheNowSection() {
        windowState.updateAgentSessions(
            listOf(session("a", "needs_you"), session("b", "working"), session("c", "working"), session("d", "idle")),
            true,
        )
        val s = vm.state
        assertEquals(1, s.needYouCount)
        assertEquals(2, s.workingCount)
        assertEquals(s.now.size, s.needYouCount + s.workingCount)
        assertTrue(s.chipVisible)
    }

    @Test
    fun chipStaysWhenAllAgentsAreIdleAndSaysSoAndOnlyHidesWithNoSessionsAtAll() {
        assertFalse(vm.state.chipVisible)
        windowState.updateAgentSessions(listOf(session("d", "idle"), session("e", "ended", endedAt = 5)), true)
        assertTrue(vm.state.chipVisible)
        assertNull(vm.state.chipNeeds)
        assertEquals("agents idle", vm.state.chipRest)
    }

    @Test
    fun chipWordingFollowsTheCounts() {
        fun chip(vararg rows: AgentSessionSummary): Pair<String?, String?> {
            windowState.updateAgentSessions(rows.toList(), true)
            return vm.state.chipNeeds to vm.state.chipRest
        }
        assertEquals("2 need you" to "1 working", chip(session("a", "needs_you"), session("b", "needs_you"), session("c", "working")))
        assertEquals("1 needs you" to null, chip(session("a", "needs_you")))
        assertEquals(null to "3 working", chip(session("a", "working"), session("b", "working"), session("c", "working")))
        assertEquals(null to "agents idle", chip(session("a", "idle")))
    }

    @Test
    fun titleIsTheSessionTitleElseTheFirstWordsOfItsPromptNeverWhereItRan() {
        layout(leaf("leaf-a", custom = "Pane name"), leaf("leaf-c"), leaf("leaf-d"))
        windowState.updateAgentSessions(
            listOf(
                session("a", "working", title = "Server title"),
                session("c", "working", branch = "feature/ABC-12-x", firstPrompt = "Why does the nightly backup email never arrive?"),
                session("d", "working"),
            ),
            true,
        )
        val titles = vm.state.now.associate { it.id to it.title }
        assertEquals("Server title", titles["a"], "the server already folds the pane's name into the title")
        assertEquals("Why does the nightly backup email never arrive?", titles["c"])
        assertEquals("Claude session", titles["d"], "no branch, folder or repository name stands in for a title")
    }

    @Test
    fun aTitleThatOnlySaysWhereTheSessionRanIsReplacedByItsPrompt() {
        fun title(title: String?, branch: String? = null, home: Boolean = false, repo: String? = "proj", cwd: String = "/work/proj"): String {
            windowState.updateAgentSessions(
                listOf(session("x", "working", title = title, branch = branch, cwdHome = home, repo = repo, cwd = cwd, firstPrompt = "**Fix** the `login` bug")),
                true,
            )
            return vm.state.now.single().title
        }
        assertEquals("Fix the login bug", title("~", home = true, repo = null, cwd = "/Users/me"))
        assertEquals("Fix the login bug", title("HEAD"))
        assertEquals("Fix the login bug", title("aov-21-impl", branch = "aov-21-impl"))
        assertEquals("Fix the login bug", title("proj"))
        assertEquals("Fix the login bug", title(null, branch = "feature/x"))
        assertEquals("Login rewrite", title("Login rewrite"))
    }

    @Test
    fun theSameTitleRuleHoldsInASearchResult() = runNow {
        val ended = session("a", "ended", title = "HEAD", endedAt = 10, firstPrompt = "Plan the release")
        windowState.updateAgentSessions(listOf(ended), false)
        vm.open()
        vm.setQuery("release")
        answer("release", AgentSessionMatch(ended, "prompt", "Plan the release", 9, 7))
        assertEquals("Plan the release", vm.state.rows.single().title)
    }

    @Test
    fun aPreviewIsPlainTextAndNeverAFence() {
        windowState.updateAgentSessions(
            listOf(
                session("md", "ended", endedAt = 10, lastSaid = "**4. Tell me how it went,** then decide"),
                session("fence", "ended", endedAt = 20, lastSaid = "```", firstPrompt = "## Fix `the` login"),
                session("head", "ended", endedAt = 30, lastSaid = "- run `gradle test` now"),
            ),
            false,
        )
        val status = vm.state.rows.associate { it.id to it.status }
        assertEquals("4. Tell me how it went, then decide", status["md"])
        assertEquals("❯ Fix the login", status["fence"], "a bare fence says nothing, so the first prompt shows")
        assertEquals("run gradle test now", status["head"])
    }

    @Test
    fun aRowNeverReadsNowUnderAHeadingThatSaysDays() {
        val hour = 60L * 60 * 1000
        val day = 24 * hour
        val clockNow = 100 * day
        val vmAtDay100 = AgentsOverviewBackingViewModel(windowState, clock = { clockNow }) { sent += it }
        // "stale" entered its state a minute ago but its last sign of life was two days ago (the
        // state time was refreshed without the session doing anything); "fresh" is inside the idle
        // grace; "justDone" ended a minute ago and gets the same grace; "done" ended three hours
        // ago. The heading a row sits under and the age it shows are read from the same moment.
        windowState.updateAgentSessions(
            listOf(
                session("stale", "idle", since = clockNow - 60_000, lastActivityAt = clockNow - 2 * day),
                session("fresh", "idle", since = clockNow - 60_000, lastActivityAt = clockNow - 30_000),
                session("done", "ended", endedAt = clockNow - 3 * hour, since = clockNow - 3 * hour),
                session("justDone", "ended", endedAt = clockNow - 60_000, since = clockNow - 60_000),
            ),
            true,
        )
        val s = vmAtDay100.state
        assertEquals(setOf("fresh", "justDone"), s.now.map { it.id }.toSet(), "a session that ended a minute ago is not under a days heading")
        assertEquals(listOf("done", "stale"), s.recent.map { it.id })
        assertEquals(clockNow - 3 * hour, s.recent.first { it.id == "done" }.sinceMillis)
        assertEquals(clockNow - 2 * day, s.recent.first { it.id == "stale" }.sinceMillis, "its age is when it was last active, not a minute")
        s.recent.forEach { assertTrue(clockNow - it.sinceMillis >= hour, "${it.id} is under a days heading but reads as a few minutes old") }
    }

    @Test
    fun aSessionHasTheSameAgeWhetherBrowsedOrFoundBySearch() {
        val day = 24L * 60 * 60 * 1000
        val clockNow = 100 * day
        val vmAtDay100 = AgentsOverviewBackingViewModel(windowState, clock = { clockNow }) { sent += it }
        val stale = session("stale", "idle", since = clockNow - 60_000, lastActivityAt = clockNow - 2 * day)
        windowState.updateAgentSessions(listOf(stale), true)
        val browsed = vmAtDay100.state.recent.single().sinceMillis
        vmAtDay100.setQuery("needle")
        answer("needle", AgentSessionMatch(stale, "prompt", "x needle", 2, 6, null))
        assertEquals(clockNow - 2 * day, browsed)
        assertEquals(browsed, vmAtDay100.state.rows.single().sinceMillis, "search shows the age the list shows")
    }

    @Test
    fun theFooterNamesEachKeyOnceAndEachActionWithOneWord() {
        val all = AgentsOverviewBackingViewModel.Footer.entries.flatMap { f ->
            listOf(true, false).flatMap { AgentsOverviewBackingViewModel.footerHints(f, it).map { h -> f to h } }
        }
        AgentsOverviewBackingViewModel.Footer.entries.forEach { f ->
            val hints = AgentsOverviewBackingViewModel.footerHints(f, backToTask = true)
            assertEquals(hints.map { it.word }.distinct(), hints.map { it.word }, "$f says one thing twice")
        }
        assertEquals(listOf("→ expand", "↑↓ move", "esc close"), AgentsOverviewBackingViewModel.footerHints(AgentsOverviewBackingViewModel.Footer.TASK_COLLAPSED, false).map { "${it.key} ${it.word}" })
        assertEquals(listOf("← collapse", "↑↓ move", "esc close"), AgentsOverviewBackingViewModel.footerHints(AgentsOverviewBackingViewModel.Footer.TASK_EXPANDED, false).map { "${it.key} ${it.word}" })
        assertTrue(all.none { it.second.word == "space" || it.second.key == "space" })
        assertEquals(listOf("←", "↑↓", "esc"), AgentsOverviewBackingViewModel.footerHints(AgentsOverviewBackingViewModel.Footer.RUN_ATTACH, true).map { it.key })
        // "open" is only a task header's own action, so a row with no pane says there is nothing to jump to.
        assertTrue(all.none { it.second.word.contains("to open") })
    }

    @Test
    fun theSameSessionGetsTheSameWordWhetherBrowsedOrFoundBySearch() = runNow {
        val closed = session("a", "ended", title = "Plan", endedAt = 10)
        windowState.updateAgentSessions(listOf(closed), false)
        val browsed = vm.state.rows.single().actionLabel
        vm.open()
        vm.setQuery("plan")
        answer("plan", AgentSessionMatch(closed, "title", null, 0, 4))
        assertEquals(browsed, vm.state.rows.single().actionLabel)
        assertEquals("resume", browsed)
    }

    @Test
    fun aQueryUnderTwoCharactersFiltersNothingAndSendsNoSearch() = runNow {
        windowState.updateAgentSessions(listOf(session("a", "working", title = "Alpha"), session("b", "working", title = "Beta")), true)
        vm.setQuery("a")
        vm.search()
        assertFalse(vm.state.matchMode)
        assertEquals(2, vm.state.rows.size)
        assertTrue(sent.none { it is WindowCommand.SearchAgentSessions })
    }

    @Test
    fun twoCharactersSearchEverySessionAndWaitForTheAnswer() = runNow {
        windowState.updateAgentSessions(listOf(session("a", "working")), true)
        vm.open()
        vm.setQuery(" fl ")
        vm.search()
        assertEquals(
            listOf<WindowCommand>(WindowCommand.SearchAgentSessions("fl")),
            sent.filterIsInstance<WindowCommand.SearchAgentSessions>(),
        )
        val waiting = vm.state
        assertTrue(waiting.matchMode && waiting.searching)
        assertTrue(waiting.rows.isEmpty())
        assertEquals("Searching…", waiting.emptyText)
        assertNull(waiting.matchHeading)
    }

    private fun answer(query: String, vararg m: AgentSessionMatch) =
        windowState.updateAgentMatches(WindowEnvelope.AgentSessionMatches(query, m.toList()))

    @Test
    fun matchesShowInTheServersOrderWithTheirFieldAndSnippetAndNoNowEarlierSplit() {
        windowState.updateAgentSessions(listOf(session("live", "working", title = "Live one")), true)
        vm.setQuery("needle")
        answer(
            "needle",
            AgentSessionMatch(session("old", "ended", leaf = null, endedAt = 5, title = "Needle plan"), "title", null, 0, 6, null),
            AgentSessionMatch(session("live", "working", title = "Live one"), "transcript", "…a needle here…", 3, 6, null),
            AgentSessionMatch(
                session("gone", "ended", leaf = null, endedAt = 4, title = "Other"), "prompt", "fix needle", 4, 6,
                "Worktree removed. Resume recreates it from branch x.",
            ),
        )
        val s = vm.state
        assertTrue(s.matchMode)
        assertEquals(listOf("old", "live", "gone"), s.rows.map { it.id })
        assertTrue(s.now.isEmpty() && s.earlier.isEmpty())
        assertEquals("3 MATCHES", s.matchHeading)
        assertEquals("title", s.rows[0].field)
        assertNull(s.rows[0].taskTitle)
        assertEquals(0 to 6, s.rows[0].matchStart to s.rows[0].matchLength)
        assertEquals("…a needle here…", s.rows[1].snippet)
        assertEquals("Worktree removed. Resume recreates it from branch x.", s.rows[2].resumeNote)
        assertEquals("resume", s.rows[2].actionLabel)
    }

    @Test
    fun aMatchThroughItsTaskCarriesTheTaskTitleAndKeepsTheHighlightInsideIt() {
        windowState.updateAgentSessions(emptyList(), true)
        vm.setQuery("access")
        answer(
            "access",
            AgentSessionMatch(
                session("old", "ended", leaf = null, endedAt = 5, title = "wire the thing"), "task", null, 0, 6, null,
                taskTitle = "Access control",
            ),
            AgentSessionMatch(
                session("own", "ended", leaf = null, endedAt = 4, title = "Access notes"), "title", null, 0, 6, null,
                taskTitle = "Billing",
            ),
        )
        val rows = vm.state.rows
        assertEquals(listOf("task", "title"), rows.map { it.field })
        assertEquals(listOf("Access control", "Billing"), rows.map { it.taskTitle })
        assertEquals(0 to 6, rows[0].matchStart to rows[0].matchLength, "the match sits inside the task title")
        assertEquals("wire the thing", rows[0].title)
    }

    @Test
    fun anAnswerForAnOlderQueryIsIgnored() {
        windowState.updateAgentSessions(emptyList(), true)
        vm.setQuery("needle")
        answer("need", AgentSessionMatch(session("x", "ended", leaf = null, endedAt = 1), "title", null, 0, 4, null))
        assertTrue(vm.state.searching)
        assertTrue(vm.state.rows.isEmpty())
        answer("needle")
        assertFalse(vm.state.searching)
        assertEquals(
            "Nothing matches “needle”. Search covers titles, first prompts, branches, links and transcripts.",
            vm.state.emptyText,
        )
    }

    @Test
    fun aMatchTakesItsStateFromTheLiveSessionList() {
        layout(leaf("leaf-a"))
        vm.setQuery("needle")
        windowState.updateAgentSessions(listOf(session("a", "needs_you")), true)
        answer("needle", AgentSessionMatch(session("a", "ended", leaf = "leaf-a", endedAt = 5), "prompt", "x needle", 2, 6, null))
        val row = vm.state.rows.single()
        assertEquals("needs_you", row.state)
        assertTrue(row.isLive)
        assertEquals("jump", row.actionLabel)
    }

    @Test
    fun theChipCountsAreNotNarrowedByTheQuery() {
        windowState.updateAgentSessions(listOf(session("a", "working"), session("b", "working")), true)
        vm.setQuery("zzz")
        answer("zzz")
        assertEquals(2, vm.state.workingCount)
    }

    @Test
    fun selectingARowAsksTheServerOnceAndShowsItsResumeNote() = runNow {
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 5)), true)
        vm.open()
        vm.inspectSelected()
        vm.inspectSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.InspectAgentSession("old")), sent)
        assertNull(vm.state.rows.single().resumeNote)
        windowState.updateAgentInspection(WindowEnvelope.AgentSessionInspection("old", live = false, resumeNote = "Worktree removed."))
        assertEquals("Worktree removed.", vm.state.rows.single().resumeNote)
    }

    @Test
    fun peekShowsTheLiveScreenOrTheSavedScrollbackOrSaysThereIsNone() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(
            listOf(session("a", "working", title = "Live"), session("old", "ended", leaf = null, endedAt = 5, title = "Old")),
            true,
        )
        vm.open()
        vm.peek()
        assertTrue(vm.state.peek!!.loading)
        windowState.updateAgentInspection(WindowEnvelope.AgentSessionInspection("a", live = true, screen = listOf("one", "two")))
        var peek = vm.state.peek!!
        assertTrue(peek.live)
        assertEquals(listOf("one", "two"), peek.lines)
        assertNull(peek.closedSinceMillis)

        vm.moveSelection(1)
        assertNull(vm.state.peek, "moving the selection closes the peek")
        vm.peek()
        windowState.updateAgentInspection(WindowEnvelope.AgentSessionInspection("old", live = false))
        peek = vm.state.peek!!
        assertFalse(peek.live)
        assertEquals(5L, peek.closedSinceMillis)
        assertEquals("(no saved screen)", peek.emptyText)
        vm.closePeek()
        assertNull(vm.state.peek)
    }

    @Test
    fun refreshingAsksAgainOnlyWhileALivePeekIsOpen() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "working")), true)
        vm.open()
        vm.refreshPeek()
        assertTrue(sent.isEmpty())
        vm.peek()
        windowState.updateAgentInspection(WindowEnvelope.AgentSessionInspection("a", live = true, screen = listOf("x")))
        sent.clear()
        vm.refreshPeek()
        assertEquals(listOf<WindowCommand>(WindowCommand.InspectAgentSession("a")), sent)
        vm.closePeek()
        sent.clear()
        vm.refreshPeek()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun closeAsksFirstWithWarningsAndCancelFocusedAndNothingIsClosedYet() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(
            listOf(session("a", "working", title = "Fix tests", nowLine = "Bash npm test")),
            true,
        )
        vm.open()
        windowState.updateAgentInspection(
            WindowEnvelope.AgentSessionInspection("a", live = true, uncommitted = 3, unpushed = 1, branch = "feat"),
        )
        vm.requestClose()
        val confirm = vm.state.confirm!!
        assertEquals("Fix tests", confirm.title)
        assertTrue(confirm.cancelFocused)
        assertEquals(
            listOf(
                "It is working right now: Bash npm test",
                "3 uncommitted changes · 1 unpushed commit on feat stay in its folder.",
            ),
            confirm.warnings,
        )
        assertTrue(sent.none { it is WindowCommand.CloseSession })
    }

    @Test
    fun aSessionWaitingForYouSaysWhatItAsks() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "needs_you", question = "Delete the table?")), true)
        vm.open()
        vm.requestClose()
        assertEquals(listOf("It is waiting for you: Delete the table?"), vm.state.confirm!!.warnings)
    }

    @Test
    fun enterOnTheFocusedCancelKeepsTheSessionAndEnterOnCloseClosesIt() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "idle")), true)
        vm.open()
        vm.requestClose()
        vm.activateSelected()
        assertNull(vm.state.confirm)
        assertTrue(sent.none { it is WindowCommand.CloseSession })

        vm.requestClose()
        vm.moveConfirmFocus()
        assertFalse(vm.state.confirm!!.cancelFocused)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseSession("s-leaf-a")), sent.filterIsInstance<WindowCommand.CloseSession>())
        assertNull(vm.state.confirm)
    }

    @Test
    fun escStepsBackFromConfirmThenPeekThenTheOverlay() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "idle")), true)
        vm.open()
        vm.peek()
        vm.requestClose()
        assertNull(vm.state.peek, "asking to close replaces the peek")
        vm.back()
        assertNull(vm.state.confirm)
        assertTrue(vm.state.open)
        vm.peek()
        vm.back()
        assertNull(vm.state.peek)
        assertTrue(vm.state.open)
        vm.back()
        assertFalse(vm.state.open)
    }

    @Test
    fun theKeyboardCloseIsIgnoredWhileTypingButTheButtonStillWorks() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "idle", title = "alpha")), true)
        vm.open()
        vm.setQuery("al")
        answer("al", AgentSessionMatch(session("a", "idle", title = "alpha"), "title", null, 0, 2, null))
        vm.requestClose(fromKeyboard = true)
        assertNull(vm.state.confirm)
        vm.requestClose()
        assertEquals("alpha", vm.state.confirm!!.title)
    }

    @Test
    fun anEndedRowCannotBeClosed() = runNow {
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 5)), true)
        vm.open()
        vm.requestClose()
        assertNull(vm.state.confirm)
        assertFalse(vm.state.rows.single().canClose)
    }

    @Test
    fun selectionStartsAtTheTopStopsAtTheEndsAndResetsOnTyping() {
        windowState.updateAgentSessions(listOf(session("a", "working"), session("b", "working", since = 2_000)), true)
        vm.open()
        assertEquals(0, vm.state.selected)
        vm.moveSelection(-1)
        assertEquals(0, vm.state.selected)
        vm.moveSelection(1)
        vm.moveSelection(1)
        assertEquals(1, vm.state.selected)
        vm.setQuery("")
        assertEquals(0, vm.state.selected)
    }

    @Test
    fun emptyListHasNoSelection() {
        vm.open()
        assertEquals(-1, vm.state.selected)
    }

    @Test
    fun enterOnALiveRowFocusesItsPaneAndClosesTheOverlay() = runNow {
        layout(leaf("leaf-a"))
        windowState.updateAgentSessions(listOf(session("a", "needs_you")), true)
        vm.open()
        vm.activateSelected()
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-a")), sent)
        assertFalse(vm.state.open)
    }

    @Test
    fun jumpingIntoAnotherWorldSwitchesWorldFirst() = runNow {
        layout(leaf("leaf-a"), world = "w2")
        windowState.updateAgentSessions(listOf(session("a", "working")), true)
        vm.activateSelected()
        assertEquals(
            listOf(
                WindowCommand.SetActiveWorld("w2"),
                WindowCommand.SetActiveTab("tab-1"),
                WindowCommand.SetFocusedPane("tab-1", "leaf-a"),
            ),
            sent,
        )
    }

    @Test
    fun enterOnAnEndedRowResumesAndKeepsTheOverlayUntilTheNewTabIsActive() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        assertEquals("resume", vm.state.rows.single().actionLabel)
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgentSession("old")), sent)
        assertTrue(vm.state.open)

        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-2"))
        assertFalse(vm.state.open)
    }

    @Test
    fun returningToTheTabAResumeStartedFromDoesNotReopenTheOverlay() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        vm.activateSelected()
        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-2"))
        assertFalse(vm.state.open)

        windowState.updateConfig(WindowConfig(tabs = listOf(TabConfig("tab-1", "one"), TabConfig("tab-2", "two")), activeTabId = "tab-1"))
        assertFalse(vm.state.open)
    }

    @Test
    fun aFailedResumeShowsItsMessageOnTheRow() = runNow {
        layout(leaf("leaf-x"))
        windowState.updateAgentSessions(listOf(session("old", "ended", leaf = null, endedAt = 9)), true)
        vm.open()
        vm.activateSelected()
        windowState.setAgentSessionError("folder no longer exists")
        assertEquals("folder no longer exists", vm.state.rows.single().error)
        assertTrue(vm.state.open)
        vm.setQuery("")
        assertNull(vm.state.rows.single().error)
    }

    @Test
    fun geminiOffersOpenFolderInsteadOfResume() {
        windowState.updateAgentSessions(listOf(session("g", "ended", agent = "gemini", leaf = null, endedAt = 3)), true)
        assertEquals("open folder", vm.state.rows.single().actionLabel)
    }
}
