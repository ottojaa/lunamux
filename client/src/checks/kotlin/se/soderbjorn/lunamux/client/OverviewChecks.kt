/**
 * The rules the overview, the footer count and the task panel must keep, written as checks over
 * the state the screens are drawn from. They run against a live server (the `overviewChecks`
 * Gradle task) and against made-up state (the checks' own test).
 *
 * Every number a check expects is counted here from the raw sessions and the raw layout, never
 * through the view models' helpers, so a wrong count in the screens cannot agree with itself.
 */
package se.soderbjorn.lunamux.client

import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Enter
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.State
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel
import se.soderbjorn.lunamux.isListed

object OverviewChecks {
    private const val ENDED = "ended"
    private const val NEEDS_YOU = "needs_you"
    private const val WORKING = "working"

    /** How long a marked tab may stay listed after its conversation stopped waiting. */
    const val HIDE_AGAIN_GRACE_MS = 5_000L

    /** One finished check: [problems] is empty when it passed; [note] is printed after PASS. */
    data class Check(val id: String, val name: String, val problems: List<String>, val note: String? = null) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** The state the checks look at, with the footer's numbers as the screen shows them. */
    data class Snapshot(
        val sessions: List<AgentSessionSummary>,
        val tasks: List<AgentTask>,
        val config: WindowConfig,
        val overview: State,
        val panel: TaskPanelViewModel.State,
        val footerWaiting: Int,
        val footerWorking: Int,
        val expectedMain: Map<String, String> = emptyMap(),
    )

    fun evaluate(s: Snapshot): List<Check> = listOf(footer(s), visibleOnce(s), noDuplicates(s), returnReachesMain(s), askingHasQuestion(s), panel(s))

    fun render(checks: List<Check>): String = buildString {
        for (c in checks) {
            appendLine("${if (c.ok) "PASS" else "FAIL"} ${c.id} ${c.name}${c.note?.takeIf { c.ok }?.let { " ($it)" } ?: ""}")
            c.problems.forEach { appendLine("     - $it") }
        }
    }

    // ---- raw reading of the layout and the sessions ----

    private fun tabsOf(config: WindowConfig): List<TabConfig> = if (config.worlds.isEmpty()) config.tabs else config.worlds.flatMap { it.tabs }

    private fun tabOf(config: WindowConfig, leafId: String?): TabConfig? =
        leafId?.let { id -> tabsOf(config).firstOrNull { t -> t.panes.any { it.leaf.id == id } } }

    private fun isActive(config: WindowConfig, tab: TabConfig): Boolean {
        val world = config.worlds.firstOrNull { it.id == config.activeWorldId } ?: config.worlds.firstOrNull()
        return (world?.activeTabId ?: config.activeTabId) == tab.id
    }

    private fun name(c: AgentSessionSummary) = c.title ?: c.id

    private fun rows(s: Snapshot) = s.overview.now + s.overview.earlier

    private fun byKey(s: Snapshot) = s.sessions.associateBy { it.id } + s.sessions.associateBy { it.conversationId }

    /** The conversations a row stands for: the task's main one and its live sessions, or the lone one. */
    private fun conversationsOf(row: Row, s: Snapshot): Set<String> {
        val task = row.taskId?.let { id -> s.tasks.firstOrNull { it.id == id } }
        if (task == null) return setOfNotNull(row.recordId?.let(byKey(s)::get)?.conversationId)
        val live = s.sessions.filter { it.taskId == task.id && it.state != ENDED }
        return (setOfNotNull(task.mainConversation) + live.map { it.conversationId }).toSet()
    }

    private fun waitingOf(row: Row): Int =
        if (row.stateWord == "needs you") 1 else Regex("^(\\d+) need you").find(row.stateWord)?.groupValues?.get(1)?.toInt() ?: 0

    // ---- the checks ----

    /** C1: the footer, the overview's own count and the rows add up to what the server says waits and works. */
    private fun footer(s: Snapshot): Check {
        val waiting = s.sessions.count { it.state == NEEDS_YOU }
        val working = s.sessions.count { it.state == WORKING }
        val shown = rows(s).sumOf(::waitingOf)
        val problems = buildList {
            if (s.footerWaiting != waiting) add("the footer counts ${s.footerWaiting} waiting, the sessions have $waiting")
            if (s.footerWorking != working) add("the footer counts ${s.footerWorking} working, the sessions have $working")
            if (s.overview.needYouCount != waiting) add("the overview counts ${s.overview.needYouCount} waiting, the sessions have $waiting")
            if (shown != waiting) add("the rows say $shown need you, the sessions have $waiting")
        }
        return Check("C1", "the footer counts equal what the rows and the sessions add up to", problems)
    }

    /** C2: a live conversation in a listed tab is in NOW once; a background agent has a row of its own only while it waits. */
    private fun visibleOnce(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        for (c in s.sessions.filter { it.state != ENDED && tabOf(s.config, it.leafId)?.isListed == true }) {
            val n = s.overview.now.count { c.conversationId in conversationsOf(it, s) }
            if (n != 1) problems += "\"${name(c)}\" is in $n rows of NOW, not one"
        }
        for (row in rows(s).filter { it.taskId == null }) {
            val c = s.sessions.firstOrNull { it.id == row.recordId } ?: continue
            if (c.background && c.state != ENDED && c.state != NEEDS_YOU) problems += "the background agent \"${name(c)}\" has a row of its own"
        }
        return Check("C2", "every live conversation in a listed tab is in NOW once; a background agent has a row only while it waits", problems)
    }

    /** C3: no two rows are the same conversation. */
    private fun noDuplicates(s: Snapshot): Check {
        val seen = HashMap<String, String>()
        val problems = mutableListOf<String>()
        for (row in rows(s)) for (conv in conversationsOf(row, s)) {
            val first = seen.putIfAbsent(conv, row.title)
            if (first != null) problems += "conversation $conv is on two rows: \"$first\" and \"${row.title}\""
        }
        return Check("C3", "no two rows are the same conversation", problems)
    }

    /**
     * C4: ↵ on a task's row reaches or resumes its main conversation. Another conversation of the
     * task may stand in when it asks, or when the main one sits in a background tab that cannot be
     * jumped to.
     */
    private fun returnReachesMain(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        for (task in s.tasks) {
            val main = task.mainConversation ?: continue
            s.expectedMain[task.id]?.let { want ->
                if (!main.startsWith(want)) problems += "task ${task.id} has main conversation $main, expected one starting $want"
            }
            val row = rows(s).firstOrNull { it.taskId == task.id } ?: continue
            val target = byKey(s)[row.recordId]
            val mainSession = s.sessions.firstOrNull { it.conversationId == main }
            val mainHidden = mainSession?.let { it.background && it.state != ENDED } == true
            when (row.enter) {
                Enter.JUMP, Enter.RESUME -> when {
                    target == null && row.enter == Enter.RESUME && row.recordId == main -> Unit
                    target == null -> problems += "↵ on \"${row.title}\" names no conversation"
                    row.enter == Enter.JUMP && tabOf(s.config, target.leafId)?.isListed == false ->
                        problems += "↵ on \"${row.title}\" jumps to a conversation in a tab that is not listed"
                    target.conversationId == main -> Unit
                    target.conversationId !in conversationsOf(row, s) ->
                        problems += "↵ on \"${row.title}\" goes to conversation ${target.conversationId}, which is not a conversation of the task"
                    target.state != NEEDS_YOU && !mainHidden ->
                        problems += "↵ on \"${row.title}\" goes to ${target.conversationId}, which is neither the main one $main nor one that asks"
                }
                Enter.SHOW_WAITING -> {
                    if (mainSession?.state == NEEDS_YOU && target?.conversationId != main) problems += "↵ on \"${row.title}\" opens a background agent while the main conversation waits"
                    if (target == null) problems += "↵ on \"${row.title}\" names no conversation"
                    else if (target.conversationId != main && target.conversationId !in conversationsOf(row, s)) problems += "↵ on \"${row.title}\" shows a tab that is not the task's"
                }
                Enter.NONE, Enter.OPEN -> if (!mainHidden) problems += "↵ on \"${row.title}\" does nothing (${row.enter}) although its main conversation $main can be reached"
            }
        }
        return Check("C4", "↵ on a task's row reaches or resumes the main conversation by conversation id", problems)
    }

    /** C5: every conversation that needs you has a row whose ↵ opens a conversation that asks. */
    private fun askingHasQuestion(s: Snapshot): Check {
        val problems = mutableListOf<String>()
        for (c in s.sessions.filter { it.state == NEEDS_YOU }) {
            val tab = tabOf(s.config, c.leafId)
            if (tab == null) { problems += "\"${name(c)}\" needs you but has no pane"; continue }
            if (!tab.isListed && !tab.isBackground) problems += "\"${name(c)}\" needs you in a tab that is hidden and not a background tab"
            val row = rows(s).firstOrNull { c.conversationId in conversationsOf(it, s) }
            if (row == null) { problems += "\"${name(c)}\" needs you and no row leads to it"; continue }
            if (row.enter != Enter.JUMP && row.enter != Enter.SHOW_WAITING) { problems += "\"${name(c)}\" needs you but ↵ on \"${row.title}\" does not open it"; continue }
            val target = byKey(s)[row.recordId]
            val targetTab = tabOf(s.config, target?.leafId)
            if (target == null || target.state != NEEDS_YOU) problems += "\"${name(c)}\" needs you but ↵ on \"${row.title}\" opens \"${target?.let(::name) ?: "nothing"}\", which does not ask"
            else if (targetTab?.isListed == false && row.enter != Enter.SHOW_WAITING) problems += "\"${name(target)}\" needs you in a background tab but ↵ jumps instead of showing the tab"
        }
        return Check("C5", "every needs-you leads to a visible question", problems)
    }

    /** P1: the panel's background agents are alive (working, waiting or idle), and no list shows more than five before its "+ N more". */
    private fun panel(s: Snapshot): Check {
        val p = s.panel
        val name = "the panel lists only live background agents and cuts every list at five"
        if (!p.visible) return Check("P1", name, emptyList(), "no panel in the active tab")
        val problems = mutableListOf<String>()
        p.agents.shown.filter { it.state == ENDED }.forEach { problems += "the panel lists \"${it.title}\" in state ${it.state}" }
        fun block(label: String, shown: Int, more: Int, total: Int) {
            if (more > 0 && shown != TaskPanelViewModel.SHOWN) problems += "$label shows $shown entries before \"+ $more more\", not ${TaskPanelViewModel.SHOWN}"
            if (shown + more != total) problems += "$label: $shown shown and $more more do not add up to $total"
        }
        block("Background agents", p.agents.shown.size, p.agents.more, p.agents.total)
        block("Links", p.links.shown.size, p.links.more, p.links.total)
        block("Recent activity", p.activity.shown.size, p.activity.more, p.activity.total)
        return Check("P1", name, problems)
    }

    /**
     * C6 over time: no background tab becomes listed without being marked, and a marked tab hides
     * again once its conversation has ended. Fed every layout the server pushes.
     */
    class TabWatch {
        private val wasListed = HashMap<String, Boolean>()
        private val markedSince = HashMap<String, Long>()
        private val problems = mutableListOf<String>()
        private var observed = 0

        fun observe(config: WindowConfig, sessions: List<AgentSessionSummary>, nowMillis: Long) {
            observed++
            for (tab in tabsOf(config)) {
                val waiting = sessions.any { it.state == NEEDS_YOU && tabOf(config, it.leafId)?.id == tab.id }
                val live = sessions.any { it.state != ENDED && tabOf(config, it.leafId)?.id == tab.id }
                if (wasListed[tab.id] == false && tab.isListed) {
                    if (!tab.shownWhileWaiting) problems += "tab \"${tab.title}\" became listed without being marked as a background agent's"
                    else if (!live) problems += "tab \"${tab.title}\" was shown while no conversation in it is live"
                }
                // A shown tab has a reason to stay: its agent waits, or it is the tab the person is looking at.
                val stuck = tab.isBackground && tab.shownWhileWaiting && (!live || (!waiting && !isActive(config, tab)))
                if (stuck) {
                    val since = markedSince.getOrPut(tab.id) { nowMillis }
                    if (nowMillis - since > HIDE_AGAIN_GRACE_MS) problems += "tab \"${tab.title}\" stays listed though nothing in it waits and it is not being looked at"
                } else markedSince.remove(tab.id)
                wasListed[tab.id] = tab.isListed
            }
        }

        fun result(): Check = Check("C6", "no hidden tab appears on its own", problems.distinct(), "watched $observed layouts")
    }
}
