/**
 * The shared view model of a task's panel: the column that sits beside the task's main
 * conversation and shows what is happening now. It has exactly four blocks, Agents, Progress,
 * Links and Recent activity, and no list in it is ever drawn in full: each shows its five newest
 * entries, then "+ N more", which opens the rest in place.
 *
 * This file decides whether the panel shows (by itself beside a task's main conversation while
 * the person has it open, and in any other tab only when they ask for it there), builds the four
 * blocks from the task's sessions that carry its id and the blocks agents wrote, says how to set
 * the panel up when it has nothing to show, and carries out what a click or key on an agent line
 * does (jump to a listed one, show a waiting background one as a tab).
 *
 * Contains [TaskPanelViewModel] with its state types ([TaskPanelViewModel.State],
 * [TaskPanelViewModel.AgentLine], [TaskPanelViewModel.LinkRow], [TaskPanelViewModel.ActivityRow],
 * [TaskPanelViewModel.ProgressView], [TaskPanelViewModel.Setup]). Web draws it today; another client can draw the same state.
 *
 * @see AgentsOverviewBackingViewModel
 * @see se.soderbjorn.lunamux.TaskPanelBlock
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
import se.soderbjorn.lunamux.isListed

private val SAFE_URL = Regex("^(?:https?|file)://\\S+$", RegexOption.IGNORE_CASE)

/**
 * Whether [url] is an address a link may open: `http`, `https` or `file`, with no spaces.
 *
 * Called by [TaskPanelViewModel] when it builds the link rows (an unsafe one gets no row) and
 * when a click asks where a link goes, so a link is checked where it is listed and again where it
 * is used.
 *
 * @param url the address to check.
 * @return true when the scheme is one of the three.
 * @see TaskPanelViewModel.linkTarget
 */
internal fun isSafeUrl(url: String): Boolean = SAFE_URL.matches(url)

/**
 * View model of the task panel.
 *
 * Created once per window by the web client's panel, which collects [stateFlow], draws it and
 * calls the action functions from clicks and keys. The person's open/closed choice and which
 * lists they opened in full live here as [State.open] and [State.Block.more]; the client stores the
 * first and hands it back with [setOpen].
 *
 * @param windowState the source of the tasks, sessions, layout and stored blocks.
 * @param clock the current epoch millis, read whenever the state is built (ages depend on it).
 *   Tests pass a fixed one.
 * @param zone the time zone a link's or an activity line's clock time is shown in, normally the
 *   viewer's own. Tests pass a fixed one.
 * @param send sends a command to the server, normally `windowSocket::send`.
 * @see AgentsOverviewBackingViewModel
 */
class TaskPanelViewModel(
    private val windowState: WindowStateRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /**
     * What a click on an agent line does.
     *
     * @see AgentLine.action
     */
    enum class MemberAction {
        /** Nothing: the session has no pane this client can reach, or it works out of sight. */
        NONE,

        /** Go to the tab of a session running in a tab the person can see. */
        JUMP,

        /** Show a background session that waits on the person as a marked tab until it works on. */
        SHOW_WAITING,
    }

    /** The lists of the panel that can open in full. */
    enum class Section { AGENTS, LINKS, ACTIVITY }

    /**
     * One line of the agents block.
     *
     * @property title the session's name.
     * @property state `needs_you` or `working`; a view picks the glyph from it.
     * @property stateWord the word for [state]: "working" or "needs you".
     * @property age how long the session has been in [state], as `now`, `5m`, `3h` or `2d`.
     * @property nowLine what it is doing or asking, in words, or null. Never a bare tool name.
     * @property action what a click does.
     * @property recordId the session record behind the line; unique within the task, so it also keeps a line's identity through a redraw.
     * @property hint the keys that act on the line while it is selected, or null when none does.
     */
    data class AgentLine(
        val title: String,
        val state: String,
        val stateWord: String,
        val age: String,
        val nowLine: String?,
        val action: MemberAction,
        val recordId: String,
        val hint: String?,
    )

    /**
     * One row of the links block.
     *
     * @property kind the word the writer sent for what the link is, as sent (`link` when it sent
     *   none). The panel knows no list of kinds.
     * @property title the words shown for the link.
     * @property url where it is; always http, https or file, and opened outside the app.
     * @property time the link's own time as a clock time in the viewer's time zone (see
     *   [clockOf]), or empty when the writer gave none.
     */
    data class LinkRow(val kind: String, val title: String, val url: String, val time: String)

    /** One line of the recent-activity block. @property time its clock time. @property text what happened, in plain words. */
    data class ActivityRow(val time: String, val text: String)

    /**
     * The progress block: what is being worked on, then how far along it is.
     *
     * @property current the task in hand, drawn above the bar, or null when none is named.
     * @property fraction 0 to 1 for the bar, or null when no count was written.
     * @property count "2 of 5", drawn below the bar, or null when no count was written.
     */
    data class ProgressView(val current: String?, val fraction: Double?, val count: String?)

    /**
     * One list of the panel, cut to five entries unless the person opened it.
     *
     * @property shown the entries to draw, newest first (agents: waiting ones first).
     * @property more how many entries are not drawn; the view shows "+ [more] more" when this is
     *   above zero and calls [showMore] when it is pressed. Zero once the list is open.
     * @property total how many entries the list holds in all.
     */
    data class Block<out T>(val shown: List<T>, val more: Int, val total: Int)

    /**
     * How to set the panel up, shown in place of the four blocks when it has nothing to show.
     *
     * @property lead the sentence before [prompt].
     * @property prompt what to tell the agent.
     */
    data class Setup(val lead: String, val prompt: String)

    /**
     * Everything a view needs to draw the panel.
     *
     * @property open the person's choice for panels beside a main conversation: true unless they
     *   hid it.
     * @property visible whether to draw the column: [open] when [beside], else whether the person
     *   asked for the panel in the active tab.
     * @property beside whether the task's main conversation is live in the active tab.
     * @property taskId the task being shown, or null when the active tab has none.
     * @property title the task's name, else the session's, else [NO_SESSION_TITLE].
     * @property setup how to set the panel up, or null when it has something to show.
     * @property agents the agents block: members working or waiting on the person, without the main
     *   conversation the panel sits beside.
     * @property progress the progress block, or null when no agent wrote one.
     * @property links the links block, newest first.
     * @property activity the recent-activity block, newest first.
     */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val beside: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val setup: Setup? = null,
        val agents: Block<AgentLine> = EMPTY_BLOCK,
        val progress: ProgressView? = null,
        val links: Block<LinkRow> = EMPTY_BLOCK,
        val activity: Block<ActivityRow> = EMPTY_BLOCK,
    )

    /**
     * What the person has chosen: whether the panel is shown beside a main conversation, the tabs
     * they asked for it in elsewhere, and which lists are open in full, each as the task id and the
     * list, so opening Links on one task leaves every other task's cut.
     */
    private data class Ui(
        val open: Boolean = true,
        val askedTabs: Set<String> = emptySet(),
        val expanded: Set<Pair<String, Section>> = emptySet(),
    )

    private val ui = MutableStateFlow(Ui())
    private val clockTick = MutableStateFlow(0)

    /**
     * Ask for a redraw so ages move on. Called by the view on a timer while the panel is visible.
     */
    fun tick() = clockTick.update { it + 1 }

    /**
     * Render-ready state; emits whenever the tasks, sessions, layout, blocks or the person's
     * choices change. Collected by the view.
     */
    val stateFlow: Flow<State> = combine(
        listOf<Flow<Any?>>(
            windowState.agentTasks, windowState.agentSessions, windowState.config, windowState.taskPanels, ui, clockTick,
        ),
    ) { v ->
        @Suppress("UNCHECKED_CAST")
        build(
            v[0] as List<AgentTask>, v[1] as List<AgentSessionSummary>, v[2] as WindowConfig?,
            v[3] as Map<String, List<TaskPanelBlock>>, v[4] as Ui,
        )
    }

    /** The state right now, for key handlers that must act on what is on screen. */
    val state: State
        get() = build(
            windowState.agentTasks.value, windowState.agentSessions.value, windowState.config.value,
            windowState.taskPanels.value, ui.value,
        )

    /**
     * Show or hide the panel by the person's choice. Called at start with the stored setting and
     * whenever the setting arrives from the server.
     *
     * @param open true to show.
     */
    fun setOpen(open: Boolean) = ui.update { it.copy(open = open) }

    /** Flip [State.open]. */
    fun toggle() = ui.update { it.copy(open = !it.open) }

    /**
     * Show or hide the panel in the active tab only. Called for ⌘I and × when [State.beside] is
     * false, so a tab with no main conversation never gets a column it was not asked for.
     */
    fun toggleInActiveTab() {
        val tab = windowState.config.value?.effectiveActiveTabId ?: return
        ui.update { it.copy(askedTabs = if (tab in it.askedTabs) it.askedTabs - tab else it.askedTabs + tab) }
    }

    /**
     * Open a list of the task the panel shows in full, in place. Called when the person presses a
     * block's "+ N more". Does nothing when the panel shows no task.
     *
     * @param section the list to open.
     */
    fun showMore(section: Section) {
        val taskId = state.taskId ?: return
        ui.update { it.copy(expanded = it.expanded + (taskId to section)) }
    }

    /**
     * Do what ↵ or a click on [line] does: jump to a listed session, show a waiting background one
     * as a tab, or nothing.
     *
     * @param line a line of [State.agents].
     * @see MemberAction
     * @see WindowCommand.ShowWaitingAgent
     */
    suspend fun activateMember(line: AgentLine) {
        when (line.action) {
            MemberAction.JUMP -> jump(line.recordId)
            MemberAction.SHOW_WAITING -> send(WindowCommand.ShowWaitingAgent(line.recordId))
            MemberAction.NONE -> Unit
        }
    }

    /**
     * The address a click on a link should open, or null when it must not be opened.
     *
     * @param url the address a block carries.
     * @return [url] when it is http, https or file, else null.
     */
    fun linkTarget(url: String): String? = url.takeIf { isSafeUrl(it) }

    private suspend fun jump(recordId: String?) {
        val config = windowState.config.value ?: return
        val leafId = windowState.agentSessions.value.firstOrNull { it.id == recordId }?.leafId ?: return
        val where = locate(config, leafId) ?: return
        if (where.worldId != null && where.worldId != config.activeWorldId) send(WindowCommand.SetActiveWorld(where.worldId))
        send(WindowCommand.SetActiveTab(where.tab.id))
        send(WindowCommand.SetFocusedPane(where.tab.id, leafId))
    }

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
        val byKey = sessions.associateBy { it.id } + sessions.associateBy { it.conversationId }
        val tasksById = tasks.associateBy { it.id }
        val here = sessions.filter { s ->
            s.state != ENDED && s.leafId?.let { locate(config, it) }?.tab?.id == activeTab
        }
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
                title = session?.let(TaskMembers::titleOf) ?: NO_SESSION_TITLE,
                setup = Setup(if (session != null) SETUP_SESSION else SETUP_NO_SESSION, SETUP_NEW_TASK_PROMPT),
            )
        }
        val now = clock()
        val blocks = panels[task.id] ?: emptyList()
        val mainId = task.mainConversation?.let(byKey::get)?.conversationId
        val agents = agentLines(task, sessions, config, mainId, now)
        val progress = progressOf(blocks)
        val links = linkRows(blocks, now)
        val activity = activityRows(blocks, now)
        val empty = agents.isEmpty() && progress == null && links.isEmpty() && activity.isEmpty()
        return State(
            open = ui.open,
            visible = visible,
            beside = beside,
            taskId = task.id,
            title = task.title,
            setup = if (empty) Setup(SETUP_TASK, SETUP_TASK_PROMPT) else null,
            agents = cut(agents, task.id, Section.AGENTS, ui),
            progress = progress,
            links = cut(links, task.id, Section.LINKS, ui),
            activity = cut(activity, task.id, Section.ACTIVITY, ui),
        )
    }

    /** Cut [all] to [SHOWN] entries unless the person opened [section] of task [taskId]. */
    private fun <T> cut(all: List<T>, taskId: String, section: Section, ui: Ui): Block<T> =
        if ((taskId to section) in ui.expanded || all.size <= SHOWN) Block(all, 0, all.size)
        else Block(all.take(SHOWN), all.size - SHOWN, all.size)

    /**
     * The sessions of the task that work or wait on the person, the ones waiting first. The main
     * conversation is left out (the panel sits beside it), and so is every session that finished or
     * has no pane in the layout: the footer and the rows count only sessions with a pane, and the
     * panel lists the same ones.
     */
    private fun agentLines(
        task: AgentTask,
        sessions: List<AgentSessionSummary>,
        config: WindowConfig,
        mainId: String?,
        now: Long,
    ): List<AgentLine> {
        val lines = sessions.filter { it.taskId == task.id && it.conversationId != mainId }
            .sortedWith(compareBy({ TaskMembers.startedOf(it) }, { it.id }))
            .filter { it.state == WORKING || it.state == NEEDS_YOU }
            .mapNotNull { s -> s.leafId?.let { locate(config, it) }?.let { s to it } }
            .map { (s, where) ->
                val action = when {
                    !where.tab.isListed && s.state == NEEDS_YOU -> MemberAction.SHOW_WAITING
                    where.tab.isListed -> MemberAction.JUMP
                    else -> MemberAction.NONE
                }
                AgentLine(
                    title = TaskMembers.titleOf(s),
                    state = s.state,
                    stateWord = if (s.state == NEEDS_YOU) "needs you" else "working",
                    age = ageOf(s.stateSince, now),
                    nowLine = inWords(if (s.state == NEEDS_YOU) s.question ?: s.nowLine else s.nowLine),
                    action = action,
                    recordId = s.id,
                    hint = when (action) {
                        MemberAction.SHOW_WAITING -> AgentsOverviewBackingViewModel.SHOW_WAITING_HINT.let { "↵ $it" }
                        MemberAction.JUMP -> OPEN_HINT
                        MemberAction.NONE -> null
                    },
                )
            }
        return lines.filter { it.state == NEEDS_YOU } + lines.filter { it.state != NEEDS_YOU }
    }

    /** The first progress block the agents wrote, as a current task, a bar and a count. */
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

    /**
     * Every link any block carries, each once, newest first by the time the writer gave it. Links
     * without a time come after the dated ones, in the order they were written. A link seen twice
     * keeps its dated, newer row.
     */
    private fun linkRows(blocks: List<TaskPanelBlock>, now: Long): List<LinkRow> {
        val found = blocks.flatMap { (it.content as? TaskBlockContent.Links)?.items.orEmpty() }
            .filter { isSafeUrl(it.url) }
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

    /** Every timeline entry of every block, newest first, one line each. */
    private fun activityRows(blocks: List<TaskPanelBlock>, now: Long): List<ActivityRow> {
        val zone = zone()
        return blocks.flatMap { b -> (b.content as? TaskBlockContent.Timeline)?.items.orEmpty() }
            .sortedByDescending { it.atMillis }
            .map { ActivityRow(clockOf(it.atMillis, now, zone), it.text) }
    }

    private fun locate(config: WindowConfig, leafId: String): TaskMembers.PanePlace? = TaskMembers.locate(config, leafId)

    companion object {
        /** What a list shows before "+ N more". */
        const val SHOWN = 5

        /** What a line says about ↵ when it goes to the session's own tab. */
        const val OPEN_HINT = "↵ open"

        /** The panel's title in a tab where no agent runs. */
        const val NO_SESSION_TITLE = "Task panel"

        /** What the set-up view says the panel is for and what it needs. */
        const val SETUP_NOTE = "The panel shows a task's agents, progress and links. Agents fill it with the Lunamux tools create_task and upsert_task_block."

        private const val SETUP_TASK = "Nothing here yet. To fill it, tell an agent in this task:"
        private const val SETUP_SESSION = "This session has no panel yet. To set one up, tell its agent:"
        private const val SETUP_NO_SESSION = "No agent is running in this tab. Start Claude or Codex here, then tell it:"
        private const val SETUP_TASK_PROMPT = "Keep this task's Lunamux panel up to date with progress, links and recent activity."
        private const val SETUP_NEW_TASK_PROMPT = "Make this work a Lunamux task and keep its panel up to date with progress, links and recent activity."

        private val EMPTY_BLOCK = Block<Nothing>(emptyList(), 0, 0)

        private const val NEEDS_YOU = "needs_you"
        private const val WORKING = "working"
        private const val ENDED = "ended"

        /** A tool-call line that names a tool, not an act: "Running cd", "Using Foo". */
        private val BARE_TOOL = Regex("^(Running|Using|Calling) [\\w./:-]+$")

        /**
         * A line about what a member does, if it is in words. A line that only names a command or
         * a tool ("Running cd") says nothing the person can use, so it is dropped and the view shows
         * the state word alone.
         *
         * Called for every agent line.
         *
         * @param line the member's line, or null.
         * @return the trimmed line, or null when it is blank or only names a tool.
         */
        fun inWords(line: String?): String? =
            line?.trim()?.takeIf { it.isNotEmpty() && !BARE_TOOL.matches(it) }

        /**
         * A compact age: `now`, `5m`, `3h` or `2d`. A time in the future reads `now`.
         *
         * Called for every agent line; kept here so every client says the same thing.
         *
         * @param sinceMillis epoch millis the age counts from.
         * @param nowMillis the current epoch millis.
         * @return the age text.
         */
        fun ageOf(sinceMillis: Long, nowMillis: Long): String {
            val seconds = ((nowMillis - sinceMillis) / 1000).coerceAtLeast(0)
            return when {
                seconds < 60 -> "now"
                seconds < 3600 -> "${seconds / 60}m"
                seconds < 86_400 -> "${seconds / 3600}h"
                else -> "${seconds / 86_400}d"
            }
        }

        /**
         * A clock time: `21:31` when it is from today, `Tue 21:31` when it is from the last six
         * days, and `4 Oct 21:31` otherwise (older, or ahead of the clock). Always in [zone], so the
         * viewer reads it in their own time.
         *
         * Called for each link and each activity line, as the approved drawing shows a clock time
         * there where the agent lines show an age ([ageOf]).
         *
         * @param atMillis epoch millis the thing happened.
         * @param nowMillis the current epoch millis, which decides what counts as today.
         * @param zone the time zone both are read in.
         * @return the clock text, with the day when it is not today.
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
