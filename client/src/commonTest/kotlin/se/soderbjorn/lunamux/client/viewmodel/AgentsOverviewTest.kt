/**
 * Tests for the agents overview, read the way a person reads it: which rows they see and in what
 * order, what Return does on each, what the footer says, and what a search, a resume, a peek and a
 * close ask them. They drive the real board and view model from `Agents` frames and a layout.
 *
 * Contains [AgentsOverviewTest].
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionMatch
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.Pane
import se.soderbjorn.lunamux.Shown
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.Enter
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.client.runNow
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentsOverviewTest {
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val now = 100L * day
    private val windowState = WindowStateRepository(clock = { now })

    /** What the overview sends, apart from its look at the selected row, which every push triggers. */
    private val sent = mutableListOf<WindowCommand>()
    private val vm = AgentsOverviewBackingViewModel(windowState, clock = { now }) {
        if (it !is WindowCommand.ReadAgentDetails) sent += it
    }

    private fun pane(leaf: String) = Pane(LeafNode(id = leaf, sessionId = "s-$leaf", title = leaf), 0.0, 0.0, 1.0, 1.0, 0)

    private var tabs: List<TabConfig> = emptyList()

    /** One listed tab holding a pane `leaf-<id>` per id, and one background tab likewise. */
    private fun layout(listed: List<String>, background: List<String> = emptyList(), tabName: String = "one") {
        tabs = listOfNotNull(
            TabConfig("tab-1", tabName, panes = listed.map { pane("leaf-$it") }),
            TabConfig("tab-bg", "agents", panes = background.map { pane("leaf-$it") }, isBackground = true).takeIf { background.isNotEmpty() },
        )
        windowState.updateConfig(WindowConfig(tabs = tabs, activeTabId = "tab-1"))
    }

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
        title: String = "session $id",
        recap: String? = null,
    ) = AgentSessionSummary(
        id = id, agent = "claude", shown = shown, since = since, title = title, line = line, ended = ended,
        listed = listed, resumable = resumable, inTab = inTab, taskId = taskId, leafId = "leaf-$id".takeIf { placed && ended == null },
        startedAt = since, repo = "proj", recap = recap,
    )

    private fun task(main: String?, status: String? = null, question: Boolean = false, paused: Boolean = false, canOpen: Boolean = false) =
        AgentTask(id = "g1", title = "Release", repo = "proj", main = main, startedAt = now - 5 * minute, statusAt = now - 5 * minute, status = status, questionWaits = question, pausedAt = if (paused) now - minute else null, canOpen = canOpen)

    private fun frame(vararg all: AgentSessionSummary, tasks: List<AgentTask> = emptyList()) =
        windowState.updateAgents(WindowEnvelope.Agents(all.toList(), tasks))

    private fun ids(rows: List<AgentsOverviewBackingViewModel.Row>) = rows.map { it.id }

    @Test
    fun theRowThatNeedsYouIsSelectedAndShowsTheQuestion() {
        layout(listOf("a", "b"))
        frame(s("a"), s("b", Shown.NEEDS_YOU, line = "Overwrite the file?"))
        vm.open()
        assertEquals("b", vm.state.selectedId)
        assertEquals(AgentsOverviewBackingViewModel.Under.QUESTION, vm.state.selectedRow!!.under())
    }

    @Test
    fun aTaskIsOneRowAndNowKeepsStartOrderWhateverTheStatesDo() {
        layout(listOf("main", "solo"), background = listOf("b1"))
        frame(
            s("main", taskId = "g1", since = now - 50 * minute), s("b1", taskId = "g1"), s("solo", since = now - 20 * minute),
            tasks = listOf(task("main").copy(startedAt = null)),
        )
        vm.open()
        assertEquals(listOf("task:g1", "solo"), ids(vm.state.now))
        frame(
            s("main", Shown.DONE, "g1", since = now - 50 * minute), s("b1", taskId = "g1"), s("solo", Shown.NEEDS_YOU, since = now - 20 * minute),
            tasks = listOf(task("main").copy(startedAt = null)),
        )
        assertEquals(listOf("task:g1", "solo"), ids(vm.state.now), "no row moves when a state flips")
    }

    @Test
    fun nowIsGroupedByRepoWithoutMovingRowsInsideAGroup() {
        layout(listOf("a", "b", "c"))
        frame(
            s("a", since = now - 30 * minute), s("b", since = now - 20 * minute).copy(repo = "other"), s("c", since = now - 10 * minute),
        )
        vm.open()
        assertEquals(listOf("a", "c", "b"), ids(vm.state.now))
    }

    @Test
    fun returnOnALiveRowGoesToItsTabAndClosesTheOverview() = runNow {
        layout(listOf("a"))
        frame(s("a"))
        vm.open()
        vm.activateSelected()
        assertEquals(listOf(WindowCommand.SetActiveTab("tab-1"), WindowCommand.SetFocusedPane("tab-1", "leaf-a")), sent)
        assertFalse(vm.state.open)
    }

    @Test
    fun aBackgroundConversationGetsARowOnlyWhileItAsksAndReturnShowsItsTab() = runNow {
        layout(listOf("a"), background = listOf("bg"))
        frame(s("a"), s("bg", Shown.WORKING))
        vm.open()
        assertEquals(listOf("a"), ids(vm.state.now))
        frame(s("a"), s("bg", Shown.NEEDS_YOU, line = "Which one?"))
        assertEquals(listOf("a", "bg"), ids(vm.state.now))
        vm.select("bg")
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.SetActiveTab("tab-bg")), sent)
        assertTrue(vm.state.open, "the overview stays up until the tab is shown, so a refusal stays visible")
        windowState.updateAgentError(WindowEnvelope.AgentError("tab-bg", "that tab is gone"))
        assertEquals("that tab is gone", vm.state.error)
    }

    @Test
    fun aLiveConversationNobodyCanPlaceStillHasARowThatSaysWhy() {
        layout(listOf("a"))
        frame(s("a"), s("lost", placed = false))
        vm.open()
        val row = vm.state.now.first { it.id == "lost" }
        assertEquals(Enter.NONE, row.work.enter)
        assertNotNull(row.work.why)
    }

    @Test
    fun finishedConversationsFallIntoRecentAndFortnightAndOldOnesDisappear() {
        layout(emptyList())
        frame(
            s("r", Shown.CLOSED, ended = "exit", since = now - 2 * day, placed = false),
            s("f", Shown.CLOSED, ended = "exit", since = now - 10 * day, placed = false),
            s("old", Shown.CLOSED, ended = "exit", since = now - 40 * day, placed = false),
        )
        vm.open()
        assertEquals(listOf("r"), ids(vm.state.recent))
        assertEquals(listOf("f"), ids(vm.state.fortnight))
        assertFalse(vm.state.rows.any { it.id == "old" })
    }

    @Test
    fun earlierShowsFiveAndStepsPastTheLastRevealTheRest() {
        layout(emptyList())
        frame(*(1..8).map { s("c$it", Shown.CLOSED, ended = "exit", since = now - it * minute, placed = false) }.toTypedArray())
        vm.open()
        assertEquals(5, vm.state.rows.size)
        assertEquals(3, vm.state.earlierHidden)
        repeat(4) { vm.moveSelection(1) }
        vm.moveSelection(1)
        assertEquals(8, vm.state.rows.size)
        assertEquals(5, vm.state.selected)
    }

    @Test
    fun resumingAClosedConversationAsksFirstAndOffersItsOwnTabWhenItIsStillThere() = runNow {
        layout(emptyList())
        frame(s("c", Shown.CLOSED, ended = "exit", resumable = true, placed = false, inTab = true))
        vm.open()
        vm.activateSelected()
        assertTrue(sent.isEmpty(), "the first Return only asks")
        assertEquals("Resume it in its tab?", vm.state.ask!!.question)
        assertEquals(listOf("↵", "esc"), vm.state.ask!!.keys.map { it.key })
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.ResumeAgent(recordId = "c", inTab = true)), sent)
    }

    @Test
    fun aConversationWithNoTranscriptIsNeverOfferedForResumeSoTheRowSaysThereIsNothingToResume() = runNow {
        layout(emptyList())
        frame(s("c", Shown.CLOSED, ended = "exit", resumable = false, placed = false))
        vm.open()
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        assertContains(vm.state.extra, "nothing to resume: its transcript is gone")
    }

    @Test
    fun aSearchShowsSearchingThenTheMatchesWithTheirSnippetAndWhereTheyMatched() = runNow {
        layout(listOf("a", "b"))
        val a = s("a", title = "Fix login")
        frame(a, s("b"))
        vm.open()
        vm.setQuery("login")
        vm.search()
        assertEquals(listOf<WindowCommand>(WindowCommand.SearchAgents("login")), sent)
        assertTrue(vm.state.searching)
        assertEquals("Searching…", vm.state.emptyText)
        windowState.updateAgentMatches(
            WindowEnvelope.AgentMatches("login", listOf(AgentSessionMatch(a, "prompt", snippet = "fix the login bug", start = 8, length = 5))),
        )
        assertEquals("1 MATCH", vm.state.matchHeading)
        val hit = vm.state.matches.single().hit!!
        assertEquals("fix the login bug", hit.snippet)
        assertEquals("in first prompt", hit.label)
        assertEquals(AgentsOverviewBackingViewModel.Under.SNIPPET, vm.state.matches.single().under())
    }

    @Test
    fun aSearchWithNoMatchesSaysWhatSearchCovers() {
        layout(listOf("a"))
        frame(s("a"))
        vm.open()
        vm.setQuery("zzz")
        windowState.updateAgentMatches(WindowEnvelope.AgentMatches("zzz"))
        assertContains(vm.state.emptyText, "Search covers titles, tab names, first prompts")
    }

    @Test
    fun aSearchHitInOneConversationOfATaskIsOneRowThatOpensThatConversation() = runNow {
        layout(listOf("m"), background = listOf("h"))
        val h = s("h", taskId = "g1")
        frame(s("m", taskId = "g1"), h, tasks = listOf(task("m")))
        vm.open()
        vm.setQuery("shell")
        windowState.updateAgentMatches(WindowEnvelope.AgentMatches("shell", listOf(AgentSessionMatch(h, "screen", snippet = "a shell", start = 2, length = 5))))
        assertEquals(listOf("task:g1"), ids(vm.state.matches))
    }

    @Test
    fun closeAsksWhatTheAgentIsDoingAndOnlyConfirmingSendsTheClose() = runNow {
        layout(listOf("a"))
        frame(s("a", Shown.WORKING, line = "Editing Release.kt"))
        vm.open()
        vm.requestClose()
        val confirm = vm.state.confirm!!
        assertTrue(confirm.cancelFocused)
        assertEquals("It is working right now: Editing Release.kt", confirm.warnings.first())
        vm.activateSelected()
        assertTrue(sent.none { it is WindowCommand.CloseSession }, "↵ on the cancel button cancels")
        assertNull(vm.state.confirm)
        vm.requestClose()
        vm.moveConfirmFocus()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.CloseSession("s-leaf-a")), sent)
    }

    @Test
    fun aBackgroundConversationsRowCannotBeClosedFromTheOverview() = runNow {
        layout(listOf("a"), background = listOf("bg"))
        frame(s("a"), s("bg", Shown.NEEDS_YOU, line = "?"))
        vm.open()
        vm.select("bg")
        vm.requestClose()
        assertNull(vm.state.confirm)
    }

    @Test
    fun peekAsksForTheScreenAndTheFooterSaysArrowPeek() = runNow {
        layout(listOf("a"))
        frame(s("a"))
        vm.open()
        assertContains(vm.state.footer.map { it.key }, "→")
        vm.peek()
        assertTrue(vm.state.peek!!.loading)
        assertTrue(windowState.agentDetails.value == null)
        windowState.updateAgentDetails(WindowEnvelope.AgentDetails(recordId = "a", live = true, screen = listOf("$ ls")))
        assertEquals(listOf("$ ls"), vm.state.peek!!.lines)
        vm.back()
        assertNull(vm.state.peek)
        assertTrue(vm.state.open, "esc leaves the peek first")
    }

    @Test
    fun returnOnATaskReachesWhoeverNeedsYouThenItsMainConversation() = runNow {
        layout(listOf("m"), background = listOf("h"))
        frame(s("m", taskId = "g1"), s("h", Shown.NEEDS_YOU, "g1", line = "Which?"), tasks = listOf(task("m")))
        vm.open()
        vm.activateSelected()
        assertTrue(sent.contains(WindowCommand.SetActiveTab("tab-bg")))
    }

    @Test
    fun aClosedMainWhoseQuestionWaitsStaysInNowAndAsksBeforeResuming() = runNow {
        layout(emptyList())
        frame(
            s("m", Shown.CLOSED, "g1", ended = "exit", placed = false, resumable = true),
            tasks = listOf(task("m", status = "Which one?", question = true)),
        )
        vm.open()
        assertEquals(listOf("task:g1"), ids(vm.state.now))
        vm.activateSelected()
        assertTrue(sent.isEmpty())
        assertNotNull(vm.state.ask)
    }

    @Test
    fun aPausedTaskSaysSoAndStaysInNow() {
        layout(listOf("m"))
        frame(s("m", Shown.PAUSED, "g1"), tasks = listOf(task("m", paused = true)))
        vm.open()
        assertEquals("paused", vm.state.now.single().work.word)
    }

    @Test
    fun aTaskWithNoConversationStartsItsOpenCommandAfterAsking() = runNow {
        layout(emptyList())
        frame(tasks = listOf(task(null, canOpen = true)))
        vm.open()
        vm.activateSelected()
        vm.activateSelected()
        assertEquals(listOf<WindowCommand>(WindowCommand.OpenTask("g1")), sent)
    }

    @Test
    fun aRowShowsOneLineUnderItsNameTheMostTellingOneItHas() {
        layout(listOf("a", "b"), tabName = "release")
        frame(
            s("a", line = "Editing", recap = "Fixing the login"),
            s("b", recap = "Fixing the login"),
        )
        vm.open()
        val a = vm.state.rows.first { it.id == "a" }
        assertEquals(AgentsOverviewBackingViewModel.Under.RECAP, a.copy(work = a.work.copy(lineSet = false)).under())
        assertEquals(AgentsOverviewBackingViewModel.Under.RECAP, vm.state.rows.first { it.id == "b" }.under())
    }

    @Test
    fun whatTheRestartStoppedHasItsOwnSectionAndResumesWithOneKey() = runNow {
        layout(emptyList())
        frame(
            s("x", Shown.STOPPED, ended = "restart", resumable = true, placed = false, since = now - 3 * 60 * minute),
            s("y", Shown.STOPPED, ended = "restart", resumable = true, placed = false, since = now - 4 * 60 * minute),
        )
        vm.open()
        assertEquals(2, vm.state.stopped.size)
        assertContains(vm.state.footer.map { it.label }, "resume all 2")
        vm.resumeStopped()
        assertEquals(2, sent.filterIsInstance<WindowCommand.ResumeAgent>().size)
        assertFalse(vm.state.open)
    }

    @Test
    fun agesAreInTheLargestWholeUnit() {
        assertEquals("now", ageText(now - 30_000, now))
        assertEquals("4m", ageText(now - 4 * minute, now))
        assertEquals("3h", ageText(now - 3 * 60 * minute, now))
        assertEquals("2d", ageText(now - 2 * day, now))
    }
}
