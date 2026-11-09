/**
 * The task panel: the column beside a task's main conversation that shows what is happening now.
 * It has four blocks (Agents, Progress, Links, Recent activity) under the task's title and status
 * line, and no list is drawn in full: each shows its five newest entries, then "+ N more".
 *
 * The panel shows by itself beside a task's main conversation while the person has it open, and in
 * any other tab only when they ask for it there (⌘I). With nothing to show it says how to set the
 * panel up. Web draws it today; another client can draw the same [TaskPanelViewModel.State].
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId
import se.soderbjorn.lunamux.effectiveTabs

private val SAFE_URL = Regex("^(?:https?|file)://\\S+$", RegexOption.IGNORE_CASE)

/** Whether [url] is an address a link may open: `http`, `https` or `file`, with no spaces. */
internal fun isSafeUrl(url: String): Boolean = SAFE_URL.matches(url)

class TaskPanelViewModel(
    private val windowState: WindowStateRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /** What a click on an agent line does. */
    enum class MemberAction {
        /** Nothing: the session works out of sight, or its pane cannot be found. */
        NONE,

        /** Go to the tab of a session running in a tab the person can see. */
        JUMP,

        /** Show a background session that waits on the person as a marked tab until it works on. */
        SHOW_WAITING,
    }

    /** The lists of the panel that can open in full. */
    enum class Section { AGENTS, LINKS, ACTIVITY }

    /**
     * One line of the agents block. [name] says what the agent is doing (or asking) in words, or
     * the session's title when it says nothing; [title] is the session's title as the quieter
     * second line, null when it is already the name.
     */
    data class AgentLine(
        val name: String,
        val title: String?,
        val state: String,
        val stateWord: String,
        val sinceMillis: Long,
        val action: MemberAction,
        val recordId: String,
        val hint: String?,
    )

    /** One row of the links block; [kind] is the word the writer sent, [time] a clock time or empty. */
    data class LinkRow(val kind: String, val title: String, val url: String, val time: String)

    data class ActivityRow(val time: String, val text: String)

    /** What is being worked on, then how far along: [fraction] 0 to 1 and [count] "2 of 5" are null without a count. */
    data class ProgressView(val current: String?, val fraction: Double?, val count: String?)

    /** One list of the panel, cut to five unless opened: [more] entries are not drawn, [total] are held. */
    data class Block<out T>(val shown: List<T>, val more: Int, val total: Int)

    /** How to set the panel up, shown in place of the four blocks when it has nothing to show. */
    data class Setup(val lead: String, val prompt: String)

    /** Everything a view needs to draw the panel. */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val beside: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val status: String? = null,
        val setup: Setup? = null,
        val agents: Block<AgentLine> = EMPTY_BLOCK,
        val progress: ProgressView? = null,
        val links: Block<LinkRow> = EMPTY_BLOCK,
        val activity: Block<ActivityRow> = EMPTY_BLOCK,
    )

    /** The person's choices: shown beside a main conversation, tabs asked for elsewhere, lists opened in full per task. */
    private data class Ui(
        val open: Boolean = true,
        val askedTabs: Set<String> = emptySet(),
        val expanded: Set<Pair<String, Section>> = emptySet(),
    )

    private val ui = MutableStateFlow(Ui())

    val stateFlow: Flow<State> = combine(
        windowState.agentTasks, windowState.agentSessions, windowState.config, windowState.taskPanels, ui,
    ) { tasks, sessions, config, panels, ui -> build(tasks, sessions, config, panels, ui) }

    /** The state right now, for key handlers that must act on what is on screen. */
    val state: State
        get() = build(
            windowState.agentTasks.value, windowState.agentSessions.value, windowState.config.value,
            windowState.taskPanels.value, ui.value,
        )

    /** Shows or hides the panel beside a main conversation; called with the stored setting. */
    fun setOpen(open: Boolean) = ui.update { it.copy(open = open) }

    fun toggle() = ui.update { it.copy(open = !it.open) }

    /** ⌘I and × in a tab with no main conversation: shows or hides the panel in the active tab only. */
    fun toggleInActiveTab() {
        val tab = windowState.config.value?.effectiveActiveTabId ?: return
        ui.update { it.copy(askedTabs = if (tab in it.askedTabs) it.askedTabs - tab else it.askedTabs + tab) }
    }

    /** Opens a list of the shown task in full, in place; the block's "+ N more". */
    fun showMore(section: Section) {
        val taskId = state.taskId ?: return
        ui.update { it.copy(expanded = it.expanded + (taskId to section)) }
    }

    suspend fun activateMember(line: AgentLine) {
        when (line.action) {
            MemberAction.JUMP -> {
                val session = windowState.agentSessions.value.firstOrNull { it.id == line.recordId } ?: return
                Layout(windowState.config.value).jumpCommands(session).forEach { send(it) }
            }
            MemberAction.SHOW_WAITING -> send(WindowCommand.ShowWaitingAgent(line.recordId))
            MemberAction.NONE -> Unit
        }
    }

    /** [url] when a click may open it, else null. */
    fun linkTarget(url: String): String? = url.takeIf { isSafeUrl(it) }

    private fun build(
        tasks: List<AgentTask>,
        sessions: List<AgentSessionSummary>,
        config: WindowConfig?,
        panels: Map<String, List<TaskPanelBlock>>,
        ui: Ui,
    ): State {
        val hidden = State(open = ui.open)
        if (config == null) return hidden
        val activeTab = config.effectiveActiveTabId ?: return hidden
        val layout = Layout(config)
        val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
        val tasksById = tasks.associateBy { it.id }
        val here = sessions.filter { AgentFacts.isLive(it) && layout.spotOf(it)?.tab?.id == activeTab }
        fun mainIn(t: AgentTask) = t.mainConversation?.let(byKey::get)?.takeIf { it in here }
        val focusedPane = config.effectiveTabs.firstOrNull { it.id == activeTab }?.focusedPaneId
        val focused = here.firstOrNull { it.leafId == focusedPane }
        val task = focused?.taskId?.let(tasksById::get)
            ?: tasks.filter { mainIn(it) != null }.minByOrNull { it.startedAtMillis ?: Long.MAX_VALUE }
            ?: here.firstNotNullOfOrNull { it.taskId?.let(tasksById::get) }
        val beside = task != null && mainIn(task) != null
        val visible = if (beside) ui.open else activeTab in ui.askedTabs
        if (task == null) {
            val session = focused ?: here.firstOrNull()
            return State(
                open = ui.open,
                visible = visible,
                title = session?.let(AgentFacts::titleOf) ?: NO_SESSION_TITLE,
                setup = Setup(if (session != null) SETUP_SESSION else SETUP_NO_SESSION, SETUP_NEW_TASK_PROMPT),
            )
        }
        val now = clock()
        val blocks = panels[task.id].orEmpty()
        val mainId = task.mainConversation?.let(byKey::get)?.conversationId
        val agents = agentLines(task, sessions, layout, mainId)
        val progress = progressOf(blocks)
        val links = linkRows(blocks, now)
        val activity = activityRows(blocks, now)
        val status = task.status?.trim()?.takeIf { it.isNotEmpty() }
        val empty = agents.isEmpty() && progress == null && links.isEmpty() && activity.isEmpty()
        return State(
            open = ui.open,
            visible = visible,
            beside = beside,
            taskId = task.id,
            title = task.title,
            status = status,
            setup = if (empty && status == null) Setup(SETUP_TASK, SETUP_TASK_PROMPT) else null,
            agents = cut(agents, task.id, Section.AGENTS, ui),
            progress = progress,
            links = cut(links, task.id, Section.LINKS, ui),
            activity = cut(activity, task.id, Section.ACTIVITY, ui),
        )
    }

    private fun <T> cut(all: List<T>, taskId: String, section: Section, ui: Ui): Block<T> =
        if ((taskId to section) in ui.expanded || all.size <= SHOWN) Block(all, 0, all.size)
        else Block(all.take(SHOWN), all.size - SHOWN, all.size)

    /**
     * The task's sessions that work or wait on the person, the waiting ones first. The main
     * conversation is left out (the panel sits beside it). A session whose pane the layout cannot
     * place still shows, with nothing to click.
     */
    private fun agentLines(task: AgentTask, sessions: List<AgentSessionSummary>, layout: Layout, mainId: String?): List<AgentLine> {
        val lines = sessions.filter { it.taskId == task.id && it.conversationId != mainId && (AgentFacts.isWorking(it) || AgentFacts.isWaiting(it)) }
            .sortedWith(compareBy({ AgentFacts.startedOf(it) }, { it.id }))
            .map { s ->
                val spot = layout.spotOf(s)
                val action = when {
                    spot == null -> MemberAction.NONE
                    spot.listed -> MemberAction.JUMP
                    AgentFacts.isWaiting(s) -> MemberAction.SHOW_WAITING
                    else -> MemberAction.NONE
                }
                val title = AgentFacts.titleOf(s)
                val doing = inWords(AgentFacts.lineOf(s))
                AgentLine(
                    name = doing ?: title,
                    title = title.takeIf { doing != null },
                    state = s.state,
                    stateWord = if (AgentFacts.isWaiting(s)) "needs you" else "working",
                    sinceMillis = s.stateSince,
                    action = action,
                    recordId = s.id,
                    hint = when (action) {
                        MemberAction.SHOW_WAITING -> "↵ ${AgentsOverviewBackingViewModel.SHOW_WAITING_HINT}"
                        MemberAction.JUMP -> "↵ open"
                        MemberAction.NONE -> null
                    },
                )
            }
        return lines.filter { it.state == AgentFacts.NEEDS_YOU } + lines.filter { it.state != AgentFacts.NEEDS_YOU }
    }

    private fun progressOf(blocks: List<TaskPanelBlock>): ProgressView? {
        val c = blocks.firstNotNullOfOrNull { it.content as? TaskBlockContent.Progress } ?: return null
        val done = c.done
        val total = c.total
        val hasCount = done != null && total != null && total > 0
        return ProgressView(
            current = (c.phase ?: c.label)?.trim()?.takeIf { it.isNotEmpty() },
            fraction = if (hasCount) (done!!.toDouble() / total!!).coerceIn(0.0, 1.0) else null,
            count = if (hasCount) "$done of $total" else null,
        )
    }

    /** Every safe link any block carries, once each, dated ones newest first, then undated in written order. */
    private fun linkRows(blocks: List<TaskPanelBlock>, now: Long): List<LinkRow> {
        val found = blocks.flatMap { (it.content as? TaskBlockContent.Links)?.items.orEmpty() }.filter { isSafeUrl(it.url) }
        val (dated, undated) = found.partition { it.at != null }
        val zone = zone()
        return (dated.sortedByDescending { it.at } + undated).distinctBy { it.url }.map {
            LinkRow(
                kind = it.kind.trim().ifEmpty { TaskLink.DEFAULT_KIND },
                title = it.label.ifBlank { it.url },
                url = it.url,
                time = it.at?.let { at -> clockOf(at, now, zone) }.orEmpty(),
            )
        }
    }

    private fun activityRows(blocks: List<TaskPanelBlock>, now: Long): List<ActivityRow> {
        val zone = zone()
        return blocks.flatMap { b -> (b.content as? TaskBlockContent.Timeline)?.items.orEmpty() }
            .sortedByDescending { it.atMillis }
            .map { ActivityRow(clockOf(it.atMillis, now, zone), it.text) }
    }

    companion object {
        /** What a list shows before "+ N more". */
        const val SHOWN = 5

        const val NO_SESSION_TITLE = "Task panel"

        /** What the set-up view says the panel is for and what it needs. */
        const val SETUP_NOTE = "The panel shows a task's agents, progress and links. Agents fill it with the Lunamux tools create_task and upsert_task_block."

        private const val SETUP_TASK = "Nothing here yet. To fill it, tell an agent in this task:"
        private const val SETUP_SESSION = "This session has no panel yet. To set one up, tell its agent:"
        private const val SETUP_NO_SESSION = "No agent is running in this tab. Start Claude or Codex here, then tell it:"
        private const val SETUP_TASK_PROMPT = "Keep this task's Lunamux panel up to date with progress, links and recent activity."
        private const val SETUP_NEW_TASK_PROMPT = "Make this work a Lunamux task and keep its panel up to date with progress, links and recent activity."

        private val EMPTY_BLOCK = Block<Nothing>(emptyList(), 0, 0)

        /** A tool-call line that names a tool, not an act ("Running cd"): it says nothing the person can use. */
        private val BARE_TOOL = Regex("^(Running|Using|Calling) [\\w./:-]+$")

        /** A line about what a member does if it is in words, else null. */
        fun inWords(line: String?): String? = line?.trim()?.takeIf { it.isNotEmpty() && !BARE_TOOL.matches(it) }

        /**
         * A clock time: `21:31` today, `Tue 21:31` within the last six days, else `4 Oct 21:31`,
         * always in [zone] so the viewer reads it in their own time.
         */
        fun clockOf(atMillis: Long, nowMillis: Long, zone: TimeZone): String {
            val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
            val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
            val time = "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
            val daysAgo = today.toEpochDays() - at.date.toEpochDays()
            fun short(name: String) = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            return when (daysAgo) {
                0 -> time
                in 1..6 -> "${short(at.dayOfWeek.name)} $time"
                else -> "${at.dayOfMonth} ${short(at.month.name)} $time"
            }
        }
    }
}
