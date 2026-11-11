/**
 * The task panel: the column beside a task's main conversation that shows what is happening now.
 * Its header says where the work is (the tab's name, the task's title, its state and the same
 * headline the agents overview shows for it). Under it come four blocks in the order a person
 * asks about them: Plan (how far), Background agents (who is on it), Activity (what happened, by
 * day) and Links. No list is drawn in full: each shows its five newest entries, then "+ N more".
 *
 * The panel shows by itself beside a task's main conversation while the person has it open, and in
 * any other tab only when they ask for it there (⌘I). For a session with no task it shows what
 * Lunamux sees by itself: what the person asked, when and how long each turn took, and the work
 * left in its folder, then how to set the panel up. Web draws it today; another client can draw the same [TaskPanelViewModel.State].
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
import se.soderbjorn.lunamux.PanelSection
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskLink
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.TaskStep
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.WindowEnvelope
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId
import se.soderbjorn.lunamux.effectiveTabs

private val SAFE_URL = Regex("^(?:https?|file)://\\S+$", RegexOption.IGNORE_CASE)

/** Whether [url] is an address a link may open: `http`, `https` or `file`, with no spaces. */
internal fun isSafeUrl(url: String): Boolean = SAFE_URL.matches(url)

/**
 * The state behind the task panel that sits beside a main conversation: the task's sessions,
 * links and recent activity, and whether the panel is open.
 *
 * The web and Android screens create one, collect [stateFlow] and draw it. [setOpen], [toggle] and
 * [toggleInActiveTab] show or hide the panel; [showMore] opens a list in full and [showLess] folds it again; a click on an agent
 * line goes out through [send].
 *
 * @param windowState where the server's pushes (sessions, tasks, layout) arrive.
 * @param clock the time activity is aged against; tests pass a fixed one.
 * @param zone the time zone day headings are cut in.
 * @param send sends a command to the server.
 * @see AgentsOverviewBackingViewModel
 * @see AgentFacts
 */
class TaskPanelViewModel(
    private val windowState: WindowStateRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /** What a click on an agent line does. */
    enum class MemberAction {
        /** Nothing: the session's pane cannot be found. */
        NONE,

        /** Go to the tab of a session running in a tab the person can see. */
        JUMP,

        /** Show the hidden tab a session runs in, as a marked background tab, and switch to it. */
        SHOW,
    }

    /** The lists of the panel that can open in full. */
    enum class Section { AGENTS, LINKS, ACTIVITY, TURNS }

    /**
     * One row of the agents block: the session's [title], then one fixed [doing] line that is always
     * there ("working", "needs you" or "idle" when the agent has nothing better to say), so a row's
     * height never depends on what the agent is doing.
     */
    data class AgentLine(
        val title: String,
        val doing: String,
        val state: String,
        val sinceMillis: Long,
        val action: MemberAction,
        val recordId: String,
        val hint: String?,
    )

    /** One row of the links block; [kind] is the word the writer sent, [time] a clock time or empty. */
    data class LinkRow(val kind: String, val title: String, val url: String, val time: String)

    /** One thing that happened: [day] is "Today", "Yesterday" or a date, [time] the clock time in it. */
    data class ActivityRow(val day: String, val time: String, val text: String)

    /**
     * The header: [name] is the tab's name (or the title when the tab has none) and [about] the
     * title under it; [meta] says where and since when; [state] and [stateWord] are the overview
     * row's. The header holds facts only, but for [headline]: the status an agent set in its own
     * words (the task's status line, or a conversation's `set_status`), empty when none did. What
     * the agent last said is not shown: the terminal beside the panel shows it. A conversation
     * whose turn has ended reads "idle" here, since "done" beside a plan reads as the task being done.
     */
    data class Head(
        val name: String,
        val about: String?,
        val meta: String,
        val state: String,
        val stateWord: String,
        val sinceMillis: Long,
        val headline: String,
        val headlineLabel: String = "",
    )

    /**
     * How far along, then what is being worked on: [fraction] 0 to 1 and [count] "2 of 5" are null
     * without a count. [steps] is the plan when an agent wrote one, with the steps done before the
     * last finished one folded into [earlierDone]; [current] is then left to the list.
     */
    data class ProgressView(
        val current: String?,
        val fraction: Double?,
        val count: String?,
        val steps: List<TaskStep> = emptyList(),
        val earlierDone: Int = 0,
    ) {
        /** True when every step is done: the view then draws the plan as one line. */
        val finished: Boolean get() = (fraction ?: 0.0) >= 1.0 && steps.all { it.state == TaskStep.DONE }
    }

    /**
     * One list of the panel, cut to five unless opened: [more] entries are not drawn, [total] are
     * held. [opened] is true when the person opened a longer list in full, so the view offers to
     * fold it again.
     */
    data class Block<out T>(val shown: List<T>, val more: Int, val total: Int, val opened: Boolean = false)

    /** One thing the person asked: [time] as a clock time, the prompt's first line, and how long the turn [took] ("12m", empty while it runs). */
    data class TurnRow(val time: String, val prompt: String, val took: String, val reply: String = "")

    /**
     * What Lunamux sees of a session with no task: [key] names the conversation and its state, so
     * the view asks again when either changes; [turns] and [work] (uncommitted and unpushed lines)
     * are empty until the server has answered.
     */
    data class Story(val key: String, val recordId: String, val turns: Block<TurnRow>, val work: List<String>, val folder: String? = null)

    /**
     * How to set the panel up, shown under the header when the panel has no block to show.
     * [compact] is for a session that simply has no task, the usual case: the view then draws
     * [lead] as one faint line and leaves [prompt] out.
     */
    data class Setup(val lead: String, val prompt: String, val compact: Boolean = false)

    /** Everything a view needs to draw the panel. */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val beside: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val head: Head? = null,
        val setup: Setup? = null,
        val story: Story? = null,
        /** The sections the agents in this tab wrote (`set_panel_section`), drawn above everything else. */
        val sections: List<PanelSection> = emptyList(),
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
        combine(windowState.agentTasks, windowState.agentSessions, windowState.config, windowState.taskPanels, ui) { _, _, _, _, _ -> },
        windowState.agentStory,
    ) { _, _ -> state }

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

    /** Opens a list of the shown task (or session) in full, in place; the block's "+ N more". */
    fun showMore(section: Section) {
        val key = state.taskId ?: state.story?.recordId ?: return
        ui.update { it.copy(expanded = it.expanded + (key to section)) }
    }

    /**
     * Folds a list opened with [showMore] back to its first five. Called by the view's "show less".
     *
     * @param section the list to fold.
     */
    fun showLess(section: Section) {
        val key = state.taskId ?: state.story?.recordId ?: return
        ui.update { it.copy(expanded = it.expanded - (key to section)) }
    }

    /** Asks the server for the story of the session the panel shows; the view calls it when [Story.key] changes. */
    suspend fun readStory() {
        state.story?.let { send(WindowCommand.ReadAgentStory(it.recordId)) }
    }

    suspend fun activateMember(line: AgentLine) {
        when (line.action) {
            MemberAction.JUMP -> {
                val session = windowState.agentSessions.value.firstOrNull { it.id == line.recordId } ?: return
                Layout(windowState.config.value).jumpCommands(session).forEach { send(it) }
            }
            MemberAction.SHOW -> send(WindowCommand.ShowWaitingAgent(line.recordId))
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
        val now = clock()
        // The header is the overview's own row for this task or session, so both say the same thing.
        val rows = buildOverview(
            OverviewInputs(sessions, tasks, layout, null, null, null, panels), OverviewUi(showAllEarlier = true), now,
        ).rows
        fun headOf(rowId: String, startedAt: Long?, status: String?, folder: String? = null): Head? = rows.firstOrNull { it.id == rowId }?.let { row ->
            val said = status?.trim().orEmpty()
            Head(
                name = row.tabName ?: row.title,
                about = row.title.takeIf { row.tabName != null },
                meta = listOfNotNull(folder ?: row.where, startedAt?.let { "since ${clockOf(it, now, zone())}" }).joinToString(" · "),
                state = when {
                    row.needsYou -> AgentFacts.NEEDS_YOU
                    row.working -> AgentFacts.WORKING
                    row.finished -> AgentFacts.ENDED
                    else -> AgentFacts.DONE
                },
                stateWord = if (row.stateWord == AgentsOverviewBackingViewModel.STATE_DONE) STATE_IDLE else row.stateWord,
                sinceMillis = row.sinceMillis,
                headline = said,
                headlineLabel = if (said.isEmpty()) "" else LABEL_STATUS,
            )
        }
        // What the agents in this tab wrote for the panel: every live conversation's sections as one
        // list, the oldest conversation's first. A key written by two of them shows once, the newest.
        val sections = here.sortedBy(AgentFacts::startedOf).flatMap { it.sections }
            .groupBy { it.key }.values.map { same -> same.maxBy { it.writtenAtMillis } }
        val task = focused?.taskId?.let(tasksById::get)
            ?: tasks.filter { mainIn(it) != null }.minByOrNull { it.startedAtMillis ?: Long.MAX_VALUE }
            ?: here.firstNotNullOfOrNull { it.taskId?.let(tasksById::get) }
        val beside = task != null && mainIn(task) != null
        val visible = if (beside) ui.open else activeTab in ui.askedTabs
        if (task == null) {
            val session = focused ?: here.firstOrNull()
            val story = session?.let { storyOf(it, windowState.agentStory.value, now, ui) }
            return State(
                open = ui.open,
                visible = visible,
                title = session?.let(AgentFacts::titleOf) ?: NO_SESSION_TITLE,
                head = session?.let { headOf(it.conversationId, AgentFacts.startedOf(it), it.status, story?.folder) },
                story = story,
                sections = sections,
                setup = if (session != null) Setup(SETUP_SESSION, SETUP_NEW_TASK_PROMPT, compact = true)
                else Setup(SETUP_NO_SESSION, SETUP_NEW_TASK_PROMPT),
            )
        }
        val blocks = panels[task.id].orEmpty()
        val mainId = task.mainConversation?.let(byKey::get)?.conversationId
        val agents = agentLines(task, sessions, layout, mainId)
        val progress = progressOf(blocks)
        val links = linkRows(blocks, now)
        val activity = activityRows(blocks, now)
        val head = headOf("task:${task.id}", task.startedAtMillis, task.status)
        val empty = agents.isEmpty() && progress == null && links.isEmpty() && activity.isEmpty() && sections.isEmpty()
        return State(
            open = ui.open,
            visible = visible,
            beside = beside,
            taskId = task.id,
            title = task.title,
            head = head,
            setup = if (empty && head?.headline.isNullOrEmpty()) Setup(SETUP_TASK, SETUP_TASK_PROMPT) else null,
            sections = sections,
            agents = cut(agents, task.id, Section.AGENTS, ui),
            progress = progress,
            links = cut(links, task.id, Section.LINKS, ui),
            activity = cut(activity, task.id, Section.ACTIVITY, ui),
        )
    }

    private fun storyOf(s: AgentSessionSummary, answer: WindowEnvelope.AgentStory?, now: Long, ui: Ui): Story {
        val known = answer?.takeIf { it.recordId == s.id }
        val zone = zone()
        val turns = known?.turns.orEmpty().map { t ->
            // Today's turns show their time, older ones their day ("Tue", "4 Oct"): the column has room for one.
            val clock = clockOf(t.atMillis, now, zone)
            val took = t.tookMillis?.let { AgentFacts.ageText(0, it) }?.let { if (it == "now") "<1m" else it }
            TurnRow(if (' ' in clock) clock.substringBeforeLast(' ') else clock, t.prompt, took.orEmpty(), t.reply.orEmpty())
        }
        val commits = known?.commits ?: 0
        val uncommitted = known?.uncommitted ?: 0
        val unpushed = known?.unpushed ?: 0
        val changed = listOfNotNull(
            "$commits ${if (commits == 1) "commit" else "commits"} since it started".takeIf { commits > 0 },
            "$uncommitted uncommitted ${if (uncommitted == 1) "change" else "changes"}".takeIf { uncommitted > 0 },
            "$unpushed unpushed ${if (unpushed == 1) "commit" else "commits"}".takeIf { unpushed > 0 },
        )
        // The branch is worth its line only when something changed on it.
        val work = if (changed.isEmpty()) emptyList() else listOfNotNull(known?.branch?.let { "on $it" }) + changed
        return Story("${s.id}/${s.state}/${s.stateSince}", s.id, cut(turns, s.id, Section.TURNS, ui), work, known?.folder)
    }

    private fun <T> cut(all: List<T>, taskId: String, section: Section, ui: Ui): Block<T> =
        when {
            all.size <= SHOWN -> Block(all, 0, all.size)
            (taskId to section) in ui.expanded -> Block(all, 0, all.size, opened = true)
            else -> Block(all.take(SHOWN), all.size - SHOWN, all.size)
        }

    /**
     * The task's live sessions, the waiting ones first, then by start, so a row keeps its place
     * as its session works, idles and works again. The main conversation is left out (the panel
     * sits beside it). A session whose pane the layout cannot place still shows, with nothing to
     * click; one in a hidden tab offers to show that tab.
     */
    private fun agentLines(task: AgentTask, sessions: List<AgentSessionSummary>, layout: Layout, mainId: String?): List<AgentLine> {
        val lines = sessions.filter { it.taskId == task.id && it.conversationId != mainId && AgentFacts.isLive(it) }
            .sortedWith(compareBy({ AgentFacts.startedOf(it) }, { it.id }))
            .map { s ->
                val spot = layout.spotOf(s)
                val action = when {
                    spot == null -> MemberAction.NONE
                    spot.listed -> MemberAction.JUMP
                    else -> MemberAction.SHOW
                }
                val said = s.status?.trim()?.takeIf { it.isNotEmpty() && AgentFacts.isWorking(s) } ?: AgentFacts.lineOf(s)
                AgentLine(
                    title = AgentFacts.titleOf(s),
                    doing = when {
                        AgentFacts.isWaiting(s) -> said ?: "needs you"
                        AgentFacts.isWorking(s) -> said ?: "working"
                        else -> "idle"
                    },
                    state = s.state,
                    sinceMillis = s.stateSince,
                    action = action,
                    recordId = s.id,
                    hint = when (action) {
                        MemberAction.SHOW -> "↵ show"
                        MemberAction.JUMP -> "↵ open"
                        MemberAction.NONE -> null
                    },
                )
            }
        return lines.filter { it.state == AgentFacts.NEEDS_YOU } + lines.filter { it.state != AgentFacts.NEEDS_YOU }
    }

    private fun progressOf(blocks: List<TaskPanelBlock>): ProgressView? {
        val bar = se.soderbjorn.lunamux.client.viewmodel.progressOf(blocks)
        val steps = stepsOf(blocks)
        val current = currentStepOf(blocks)
        if (bar == null && current == null && steps.isEmpty()) return null
        // Everything done before the last finished step folds into one line; that step and the rest show.
        val lastDone = steps.indexOfLast { it.state == TaskStep.DONE }
        val firstShown = if (lastDone > 0 && steps.take(lastDone).all { it.state == TaskStep.DONE }) lastDone else 0
        return ProgressView(
            current = current.takeIf { steps.isEmpty() },
            fraction = bar?.fraction,
            count = bar?.count?.replace("/", " of "),
            steps = steps.drop(firstShown),
            earlierDone = firstShown,
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
            .map { ActivityRow(dayOf(it.atMillis, now, zone), timeOf(it.atMillis, zone), it.text) }
    }

    companion object {
        /** What a list shows before "+ N more". */
        const val SHOWN = 5

        const val NO_SESSION_TITLE = "Task panel"

        /** What the set-up view says the panel is for and what it needs. */
        const val SETUP_NOTE = "The panel shows a task's plan, agents, activity and links. Agents fill it with the Lunamux tools create_task and upsert_task_block."

        private const val SETUP_TASK = "Nothing here yet. To fill it, tell an agent in this task:"
        const val SETUP_SESSION = "Agents write sections here with the Lunamux tool set_panel_section, for example a recap of where this work stands."
        const val STATE_IDLE = "idle"
        const val LABEL_STATUS = "status"
        private const val SETUP_NO_SESSION = "No agent is running in this tab. Start Claude or Codex here, then tell it:"
        private const val SETUP_TASK_PROMPT = "Keep this task's Lunamux panel up to date with progress, links and recent activity."
        private const val SETUP_NEW_TASK_PROMPT = "Make this work a Lunamux task and keep its panel up to date with progress, links and recent activity."

        private val EMPTY_BLOCK = Block<Nothing>(emptyList(), 0, 0)

        /** The clock time alone, `21:31`, in [zone]. */
        fun timeOf(atMillis: Long, zone: TimeZone): String {
            val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
            return "${at.hour.toString().padStart(2, '0')}:${at.minute.toString().padStart(2, '0')}"
        }

        /** The day a moment fell on, as a heading: "Today", "Yesterday", else `Mon 9 Nov`. */
        fun dayOf(atMillis: Long, nowMillis: Long, zone: TimeZone): String {
            val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone).date
            fun short(name: String) = name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            return when (Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date.toEpochDays() - at.toEpochDays()) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> "${short(at.dayOfWeek.name)} ${at.dayOfMonth} ${short(at.month.name)}"
            }
        }

        /**
         * A clock time: `21:31` today, `Tue 21:31` within the last six days, else `4 Oct 21:31`,
         * always in [zone] so the viewer reads it in their own time.
         */
        fun clockOf(atMillis: Long, nowMillis: Long, zone: TimeZone): String {
            val at = Instant.fromEpochMilliseconds(atMillis).toLocalDateTime(zone)
            val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
            val time = timeOf(atMillis, zone)
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
