/**
 * The rules the overview, the footer count and the task panel must keep, written as checks over
 * the same state the screens are drawn from, so they can be run against a live server (see the
 * `overviewChecks` Gradle task) and unit tested against made-up state.
 *
 * Contains [OverviewChecks] and its result types.
 *
 * @see AgentsOverviewBackingViewModel
 * @see AgentCounts
 * @see TaskPanelViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.isListed

/**
 * The checks C1 to C7 and the panel's block check, each returning the problems it found.
 *
 * Called by the JVM entry point of the `overviewChecks` Gradle task, which feeds it a live
 * server's state, and by `OverviewChecksTest`, which feeds it state built by hand. The checks use
 * the view models' own output (the overview's rows, the footer's [AgentCounts], the panel's
 * state), so a check that passes says what the person sees.
 */
object OverviewChecks {
    private const val ENDED = "ended"
    private const val NEEDS_YOU = "needs_you"
    private const val WORKING = "working"

    /** How many entries a panel list shows before its "+ N more" row. */
    const val PANEL_SHOWN = 5

    /** How long a marked tab may stay listed after its conversation stopped waiting. */
    const val HIDE_AGAIN_GRACE_MS = 5_000L

    /**
     * What one check found.
     *
     * @property id the short id printed in front of the line (`C1` ...).
     * @property name what the check holds.
     * @property problems what broke it, one line each; empty when the check passed.
     * @property note a line printed after PASS when the check had nothing to look at, or null.
     */
    data class Check(val id: String, val name: String, val problems: List<String>, val note: String? = null) {
        /** Whether the check passed. */
        val ok: Boolean get() = problems.isEmpty()
    }

    /**
     * The state the checks look at, as one snapshot.
     *
     * @property sessions the conversation summaries the server pushed.
     * @property tasks the tasks the server pushed.
     * @property config the window layout.
     * @property overview the overview's state, as its view model computes it.
     * @property panel the panel's state for the active tab, as its view model computes it.
     * @property nowMillis the clock the counts and the quiet-session rule use.
     * @property expectedMain task id to the start of the conversation id its main conversation must
     *   have; empty when no such claim is made.
     */
    data class Snapshot(
        val sessions: List<AgentSessionSummary>,
        val tasks: List<AgentTask>,
        val config: WindowConfig,
        val overview: AgentsOverviewBackingViewModel.State,
        val panel: TaskPanelViewModel.State,
        val nowMillis: Long,
        val expectedMain: Map<String, String> = emptyMap(),
    )

    /**
     * Run C1 to C5, C7 and the panel's block check on one snapshot. C6 needs the layout over time
     * and is [TabWatch]'s.
     *
     * @param s the snapshot.
     * @return the results in id order.
     */
    fun evaluate(s: Snapshot): List<Check> = listOf(
        footerEqualsRows(s),
        everyVisibleConversationOnce(s),
        noDuplicateRows(s),
        returnReachesMain(s),
        everyNeedsYouHasAQuestion(s),
        panelAgentsOnlyWorkingOrWaiting(s),
        panelBlocks(s),
    )

    /** One line per check, `PASS` or `FAIL`, with the problems under a failed one. */
    fun render(checks: List<Check>): String = buildString {
        for (c in checks) {
            appendLine("${if (c.ok) "PASS" else "FAIL"} ${c.id} ${c.name}${c.note?.takeIf { c.ok }?.let { " ($it)" } ?: ""}")
            c.problems.forEach { appendLine("     - $it") }
        }
    }

    /** The conversations each row stands for: a task's main conversation and its other live sessions, whichever way the task holds them (background ones too), or the lone one. */
    private fun conversationsOf(row: AgentsOverviewBackingViewModel.Row, s: Snapshot): Set<String> {
        val byKey = s.sessions.associateBy { it.id } + s.sessions.associateBy { it.conversationId }
        val task = row.taskId?.let { id -> s.tasks.firstOrNull { it.id == id } }
        if (task == null) return setOfNotNull(row.recordId?.let(byKey::get)?.conversationId)
        // The membership rule the rows use: the sessions that carry the task's id, and the main conversation.
        val live = s.sessions.filter { it.taskId == task.id && it.state != ENDED }
        return (setOfNotNull(task.mainConversation) + live.map { it.conversationId }).toSet()
    }

    private fun inListedTab(session: AgentSessionSummary, config: WindowConfig): Boolean {
        val leaf = session.leafId ?: return false
        return TaskMembers.locate(config, leaf)?.tab?.isListed == true
    }

    private fun allRows(s: Snapshot) = s.overview.now + s.overview.earlier

    /** Waiting conversations a row says wait: "needs you" is one, "N need you" is N. */
    private fun waitingOf(row: AgentsOverviewBackingViewModel.Row): Int {
        if (row.stateWord == AgentsOverviewBackingViewModel.STATE_NEEDS_YOU) return 1
        return Regex("^(\\d+) need you").find(row.stateWord)?.groupValues?.get(1)?.toInt() ?: 0
    }

    /** C1: the footer's waiting count is the sum of "needs you" over the overview's rows. */
    private fun footerEqualsRows(s: Snapshot): Check {
        val footer = AgentCounts.of(s.sessions, s.config, s.nowMillis).total.waiting
        val rows = allRows(s).sumOf(::waitingOf)
        val problems = buildList {
            if (footer != rows) add("the footer counts $footer waiting, the rows add up to $rows")
            if (s.overview.needYouCount != footer) add("the overview's own count is ${s.overview.needYouCount}, the footer's is $footer")
        }
        return Check("C1", "the footer count equals the sum of \"needs you\" on the rows", problems)
    }

    /** C2: a live conversation in a visible tab is in NOW exactly once; a background agent has no row of its own unless it waits for you. */
    private fun everyVisibleConversationOnce(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        val nowRows = s.overview.now
        for (c in s.sessions.filter { it.state != ENDED && inListedTab(it, s.config) }) {
            val rows = nowRows.filter { c.conversationId in conversationsOf(it, s) }
            if (rows.size != 1) problems += "\"${c.title ?: c.id}\" is in ${rows.size} rows of NOW, not one"
        }
        for (row in allRows(s).filter { it.taskId == null }) {
            val session = s.sessions.firstOrNull { it.id == row.recordId || it.conversationId == row.recordId } ?: continue
            if (session.background && session.state != ENDED && session.state != NEEDS_YOU) problems += "the background agent \"${session.title ?: session.id}\" has a row of its own"
        }
        return Check("C2", "every live conversation in a visible tab is in NOW once; a background agent has a row only while it waits", problems)
    }

    /** C3: no two rows are the same conversation. */
    private fun noDuplicateRows(s: Snapshot): Check {
        val seen = HashMap<String, String>()
        val problems = mutableListOf<String>()
        for (row in allRows(s)) {
            for (conv in conversationsOf(row, s)) {
                val first = seen[conv]
                if (first == null) seen[conv] = row.title
                else problems += "conversation $conv is on two rows: \"$first\" and \"${row.title}\""
            }
        }
        return Check("C3", "no two rows are the same conversation", problems)
    }

    /**
     * C4: Return on a task's row reaches or resumes its main conversation, by conversation id.
     *
     * Two things may stand in for the main conversation, and each must belong to the task: another
     * conversation that asks for the person (the row then leads to the question), and, when the main
     * conversation runs in a background tab and does not ask, a conversation in a listed tab, since
     * that background tab cannot be jumped to. A jump to any conversation in a tab that is not
     * listed, the main one included, fails: it would show nothing. A row that shows a tab must show
     * the tab of a conversation of the task. A row that opens nothing (NONE, or OPEN) is right only
     * when the main conversation runs in a background tab.
     */
    private fun returnReachesMain(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        for (task in s.tasks) {
            val main = task.mainConversation ?: continue
            s.expectedMain[task.id]?.let { want ->
                if (!main.startsWith(want)) problems += "task ${task.id} has main conversation $main, expected one starting $want"
            }
            val row = allRows(s).firstOrNull { it.taskId == task.id } ?: continue
            when (row.enter) {
                AgentsOverviewBackingViewModel.Enter.JUMP, AgentsOverviewBackingViewModel.Enter.RESUME -> {
                    val target = s.sessions.firstOrNull { it.id == row.recordId || it.conversationId == row.recordId }
                    val place = target?.leafId?.let { TaskMembers.locate(s.config, it) }
                    // A main conversation older than the summaries kept is resumed by the id the task stores.
                    if (target == null && row.enter == AgentsOverviewBackingViewModel.Enter.RESUME && row.recordId == main) Unit
                    else if (target == null) problems += "↵ on \"${row.title}\" names no conversation"
                    else if (row.enter == AgentsOverviewBackingViewModel.Enter.JUMP && place != null && !place.tab.isListed) {
                        // A jump to a tab that is not listed shows nothing, the main conversation's tab included.
                        problems += "↵ on \"${row.title}\" jumps to a conversation in a tab that is not listed"
                    } else if (target.conversationId != main) {
                        if (target.conversationId !in conversationsOf(row, s)) {
                            problems += "↵ on \"${row.title}\" goes to conversation ${target.conversationId}, which is not a conversation of the task"
                        } else if (target.state != NEEDS_YOU && !mainIsUnreachableBackground(s, main)) {
                            problems += "↵ on \"${row.title}\" goes to conversation ${target.conversationId}, which is neither the main one $main nor one that asks"
                        }
                    }
                }
                AgentsOverviewBackingViewModel.Enter.SHOW_WAITING -> {
                    val own = s.sessions.firstOrNull { it.conversationId == main }
                    val target = s.sessions.firstOrNull { it.id == row.recordId || it.conversationId == row.recordId }
                    if (own != null && own.state == NEEDS_YOU && target?.conversationId != main) problems += "↵ on \"${row.title}\" opens a background agent while the main conversation waits"
                    // The tab that is shown must be a conversation of this task, never another task's agent.
                    if (target == null) problems += "↵ on \"${row.title}\" names no conversation"
                    else if (target.conversationId != main && target.conversationId !in conversationsOf(row, s)) {
                        problems += "↵ on \"${row.title}\" shows the tab of conversation ${target.conversationId}, which is not a conversation of the task"
                    }
                }
                AgentsOverviewBackingViewModel.Enter.NONE, AgentsOverviewBackingViewModel.Enter.OPEN -> {
                    // A task with a main conversation has nothing to open only while that conversation sits in a background tab.
                    if (!mainIsUnreachableBackground(s, main)) {
                        problems += "↵ on \"${row.title}\" does nothing (${row.enter}) although its main conversation $main can be reached"
                    }
                }
                else -> Unit
            }
        }
        return Check("C4", "Return on a task's row reaches or resumes the main conversation by conversation id", problems)
    }

    /** Whether the main conversation [main] is open in a background tab, which Return cannot jump to. */
    private fun mainIsUnreachableBackground(s: Snapshot, main: String): Boolean =
        s.sessions.firstOrNull { it.conversationId == main }?.let { it.background && it.state != ENDED } == true

    /** C5: every conversation that needs you has a question on screen, or one Return can show. */
    private fun everyNeedsYouHasAQuestion(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        for (c in s.sessions.filter { it.state == NEEDS_YOU }) {
            val place = c.leafId?.let { TaskMembers.locate(s.config, it) }
            if (place == null) { problems += "\"${c.title ?: c.id}\" needs you but has no pane"; continue }
            if (!place.tab.isListed && !place.tab.isBackground) problems += "\"${c.title ?: c.id}\" needs you in a tab that is hidden and not a background tab"
            val row = allRows(s).firstOrNull { c.conversationId in conversationsOf(it, s) }
            if (row == null) problems += "\"${c.title ?: c.id}\" needs you and no row leads to it"
            else if (row.enter != AgentsOverviewBackingViewModel.Enter.JUMP && row.enter != AgentsOverviewBackingViewModel.Enter.SHOW_WAITING) {
                problems += "\"${c.title ?: c.id}\" needs you but ↵ on its row \"${row.title}\" does not open it"
            } else {
                // Return opens one conversation: it must be one that asks, and a tab that is not listed
                // needs its marked tab. When two wait on one row, the other is reached once this is answered.
                val target = s.sessions.firstOrNull { it.id == row.recordId || it.conversationId == row.recordId }
                val targetPlace = target?.leafId?.let { TaskMembers.locate(s.config, it) }
                if (target == null || target.state != NEEDS_YOU) {
                    problems += "\"${c.title ?: c.id}\" needs you but ↵ on its row \"${row.title}\" opens \"${target?.title ?: target?.id ?: "nothing"}\", which does not ask"
                } else if (targetPlace != null && !targetPlace.tab.isListed && row.enter != AgentsOverviewBackingViewModel.Enter.SHOW_WAITING) {
                    problems += "\"${target.title ?: target.id}\" needs you in a background tab but ↵ on its row \"${row.title}\" jumps instead of showing the tab"
                }
            }
        }
        return Check("C5", "every needs-you leads to a visible question", problems)
    }

    /** C7: the panel's agents are only working or waiting ones. */
    private fun panelAgentsOnlyWorkingOrWaiting(s: Snapshot): Check {
        val p = s.panel
        if (!p.visible) return Check("C7", "the panel's agents are only working or waiting ones", emptyList(), "no panel in the active tab")
        val problems = p.agents.shown.filter { it.state != WORKING && it.state != NEEDS_YOU }
            .map { "the panel lists \"${it.title}\" in state ${it.state}" }
        return Check("C7", "the panel's agents are only working or waiting ones", problems)
    }

    /** The panel check: four blocks, at most five entries before "+ N more", the rest counted. */
    private fun panelBlocks(s: Snapshot): Check {
        val p = s.panel
        if (!p.visible) return Check("P1", "the panel is four blocks with at most five entries before \"+ N more\"", emptyList(), "no panel in the active tab")
        val problems = mutableListOf<String>()
        fun block(name: String, shown: Int, more: Int, total: Int) {
            if (shown > PANEL_SHOWN && more > 0) problems += "$name shows $shown entries and still says \"+ $more more\""
            if (more > 0 && shown != PANEL_SHOWN) problems += "$name shows $shown entries before \"+ $more more\", not $PANEL_SHOWN"
            if (shown + more != total) problems += "$name: $shown shown and $more more do not add up to $total"
        }
        block("Agents", p.agents.shown.size, p.agents.more, p.agents.total)
        block("Links", p.links.shown.size, p.links.more, p.links.total)
        block("Recent activity", p.activity.shown.size, p.activity.more, p.activity.total)
        return Check("P1", "the panel is four blocks with at most five entries before \"+ N more\"", problems)
    }

    /**
     * C6 over time: no background tab becomes listed without being marked, and a marked tab
     * hides again once its conversation no longer waits.
     *
     * Called with every layout the server pushes while the checks run; [result] gives the verdict.
     */
    class TabWatch {
        private val wasListed = HashMap<String, Boolean>()
        private val markedSince = HashMap<String, Long>()
        private val problems = mutableListOf<String>()
        private var observed = 0

        /**
         * Look at one layout.
         *
         * @param config the window layout now.
         * @param sessions the conversation summaries now.
         * @param nowMillis the clock.
         */
        fun observe(config: WindowConfig, sessions: List<AgentSessionSummary>, nowMillis: Long) {
            observed++
            val tabs = if (config.worlds.isEmpty()) config.tabs else config.worlds.flatMap { it.tabs }
            for (tab in tabs) {
                val waiting = sessions.any { it.state == NEEDS_YOU && it.leafId?.let { l -> TaskMembers.locate(config, l)?.tab?.id } == tab.id }
                val before = wasListed[tab.id]
                if (before == false && tab.isListed) {
                    if (!tab.shownWhileWaiting) problems += "tab \"${tab.title}\" became listed without being marked as a background agent's"
                    else if (!waiting) problems += "tab \"${tab.title}\" was shown while no conversation in it waits"
                }
                if (tab.isBackground && tab.shownWhileWaiting) {
                    if (waiting) markedSince.remove(tab.id)
                    else {
                        val since = markedSince.getOrPut(tab.id) { nowMillis }
                        if (nowMillis - since > HIDE_AGAIN_GRACE_MS) problems += "tab \"${tab.title}\" stays listed though nothing in it waits"
                    }
                } else markedSince.remove(tab.id)
                wasListed[tab.id] = tab.isListed
            }
        }

        /** The verdict: C6 passes when every layout observed kept the rule. */
        fun result(): Check = Check(
            "C6", "no hidden tab appears on its own", problems.distinct(),
            "watched $observed layouts",
        )
    }
}
