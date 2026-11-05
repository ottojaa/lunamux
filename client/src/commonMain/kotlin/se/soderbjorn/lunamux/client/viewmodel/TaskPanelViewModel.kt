/**
 * The shared view model of a task's panel: the column that sits beside the task's main
 * conversation and shows what is happening now. It has exactly four blocks, Agents, Progress,
 * Links and Recent activity, and no list in it is ever drawn in full: each shows its five newest
 * entries, then "+ N more", which opens the rest in place.
 *
 * This file decides whether the panel shows at all (only in the tab where the main conversation
 * is live, and only while the person has it open), builds the four blocks from the task's
 * members and the blocks agents wrote, and carries out what a click or key on an agent line does
 * (jump to a listed one, show a waiting background one as a tab, attach to an unhosted one).
 *
 * Contains [TaskPanelViewModel] with its state types ([TaskPanelViewModel.State],
 * [TaskPanelViewModel.AgentLine], [TaskPanelViewModel.LinkRow], [TaskPanelViewModel.ActivityRow],
 * [TaskPanelViewModel.ProgressView]). Web draws it today; another client can draw the same state.
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
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId
import se.soderbjorn.lunamux.isListed

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
        /** Nothing: the member has no session this client can reach, or it works out of sight. */
        NONE,

        /** Go to the tab of a member running in a tab the person can see. */
        JUMP,

        /** Show a background member that waits on the person as a marked tab until it works on. */
        SHOW_WAITING,

        /** Open a member the task file lists but Lunamux does not host, with its own attach command. */
        OPEN,
    }

    /** The lists of the panel that can open in full. */
    enum class Section { AGENTS, LINKS, ACTIVITY }

    /**
     * One line of the agents block.
     *
     * @property key the member's key, unique within the task; keeps a line's identity through a redraw.
     * @property title the member's name.
     * @property state `needs_you` or `working`; a view picks the glyph from it.
     * @property stateWord the word for [state]: "working" or "needs you".
     * @property age how long the member has been in [state], as `now`, `5m`, `3h` or `2d`.
     * @property nowLine what it is doing or asking, in words, or null. Never a bare tool name.
     * @property action what a click does.
     * @property recordId the session record behind the line, or null for an unhosted member.
     * @property hint the keys that act on the line while it is selected, or null when none does.
     */
    data class AgentLine(
        val key: String,
        val title: String,
        val state: String,
        val stateWord: String,
        val age: String,
        val nowLine: String?,
        val action: MemberAction,
        val recordId: String?,
        val hint: String?,
    )

    /**
     * One row of the links block.
     *
     * @property kind the short word for what the link is: `slack`, `PR`, `issue`, `art`, `doc`,
     *   `brief`, `decision` or `link`.
     * @property title the words shown for the link.
     * @property url where it is; always http, https or file, and opened outside the app.
     * @property time when it was written or changed, as a clock time in the viewer's time zone
     *   (see [clockOf]).
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
     * Everything a view needs to draw the panel.
     *
     * @property open the person's choice: true unless they hid the panel.
     * @property visible whether to draw the column: [open] and a task whose main conversation is
     *   live in the active tab.
     * @property taskId the task being shown, or null when [visible] is false for lack of one.
     * @property title the task's name.
     * @property agents the agents block: members working or waiting on the person, without the main
     *   conversation the panel sits beside.
     * @property progress the progress block, or null when no agent wrote one.
     * @property links the links block, newest first.
     * @property activity the recent-activity block, newest first.
     */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val agents: Block<AgentLine> = EMPTY_BLOCK,
        val progress: ProgressView? = null,
        val links: Block<LinkRow> = EMPTY_BLOCK,
        val activity: Block<ActivityRow> = EMPTY_BLOCK,
    )

    /**
     * What the person has chosen: whether the panel is shown, and which lists are open in full,
     * each as the task id and the list, so opening Links on one task leaves every other task's cut.
     */
    private data class Ui(val open: Boolean = true, val expanded: Set<Pair<String, Section>> = emptySet())

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

    /** Flip [State.open]. Called for ⌘I and the panel's ×. */
    fun toggle() = ui.update { it.copy(open = !it.open) }

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
     * Do what ↵ or a click on [line] does: jump to a listed member, show a waiting background one
     * as a tab, attach to an unhosted one, or nothing.
     *
     * @param line a line of [State.agents].
     * @see MemberAction
     * @see WindowCommand.ShowWaitingAgent
     */
    suspend fun activateMember(line: AgentLine) {
        val taskId = state.taskId ?: return
        when (line.action) {
            MemberAction.JUMP -> jump(line.recordId)
            MemberAction.SHOW_WAITING -> line.recordId?.let { send(WindowCommand.ShowWaitingAgent(it)) }
            MemberAction.OPEN -> send(WindowCommand.OpenTaskMember(taskId, line.key))
            MemberAction.NONE -> Unit
        }
    }

    /**
     * The address a click on a link should open, or null when it must not be opened.
     *
     * @param url the address a block carries.
     * @return [url] when it is http, https or file, else null.
     */
    fun linkTarget(url: String): String? = url.takeIf { PanelMarkdown.isSafeUrl(it) }

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
        val byId = sessions.associateBy { it.id }
        val byKey = byId + sessions.associateBy { it.conversationId }
        val task = tasks.filter { t ->
            val own = t.mainConversation?.let(byKey::get)?.takeIf { it.state != ENDED } ?: return@filter false
            val leaf = own.leafId ?: return@filter false
            locate(config, leaf)?.tab?.id == activeTab
        }.minByOrNull { it.startedAtMillis ?: Long.MAX_VALUE } ?: return hidden
        val own = byKey.getValue(task.mainConversation!!)
        val now = clock()
        val blocks = panels[task.id] ?: emptyList()
        return State(
            open = ui.open,
            visible = ui.open,
            taskId = task.id,
            title = task.title,
            agents = cut(agentLines(task, sessions, byId, config, own, now), task.id, Section.AGENTS, ui),
            progress = progressOf(blocks),
            links = cut(linkRows(blocks, now), task.id, Section.LINKS, ui),
            activity = cut(activityRows(blocks, now), task.id, Section.ACTIVITY, ui),
        )
    }

    /** Cut [all] to [SHOWN] entries unless the person opened [section] of task [taskId]. */
    private fun <T> cut(all: List<T>, taskId: String, section: Section, ui: Ui): Block<T> =
        if ((taskId to section) in ui.expanded || all.size <= SHOWN) Block(all, 0, all.size)
        else Block(all.take(SHOWN), all.size - SHOWN, all.size)

    /**
     * The members working or waiting on the person, the ones waiting first. The main conversation
     * is left out (the panel sits beside it), and so is every member that finished.
     */
    private fun agentLines(
        task: AgentTask,
        sessions: List<AgentSessionSummary>,
        byId: Map<String, AgentSessionSummary>,
        config: WindowConfig,
        own: AgentSessionSummary,
        now: Long,
    ): List<AgentLine> {
        val fileMembers = task.members.map { it to it.recordId?.let(byId::get) }
        val extra = TaskMembers.unlistedSessions(task, byId, sessions.filter { it.taskId == task.id })
            .map { TaskMembers.memberOf(it) to it }
        val lines = (fileMembers + extra)
            .filter { (_, s) -> s == null || s.conversationId != own.conversationId }
            .mapNotNull { (m, s) ->
                val state = s?.state ?: m.state
                if (state != WORKING && state != NEEDS_YOU) return@mapNotNull null
                val where = s?.takeIf { it.state != ENDED }?.leafId?.let { locate(config, it) }
                val action = when {
                    s == null && m.sessionId != null -> MemberAction.OPEN
                    where != null && !where.tab.isListed && state == NEEDS_YOU -> MemberAction.SHOW_WAITING
                    where != null && where.tab.isListed -> MemberAction.JUMP
                    else -> MemberAction.NONE
                }
                AgentLine(
                    key = m.key,
                    title = m.title.ifBlank { "Agent session" },
                    state = state,
                    stateWord = if (state == NEEDS_YOU) "needs you" else "working",
                    age = ageOf(s?.stateSince ?: m.sinceMillis, now),
                    nowLine = inWords(m.line ?: s?.nowLine),
                    action = action,
                    recordId = s?.id,
                    hint = when (action) {
                        MemberAction.SHOW_WAITING -> AgentsOverviewBackingViewModel.SHOW_WAITING_HINT.let { "↵ $it" }
                        MemberAction.JUMP, MemberAction.OPEN -> OPEN_HINT
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
     * Every link and document any block carries, newest first, each once. A link has no time of
     * its own, so it takes the time its block was written.
     */
    private fun linkRows(blocks: List<TaskPanelBlock>, now: Long): List<LinkRow> {
        data class Found(val at: Long, val kind: String, val title: String, val url: String)
        val found = blocks.flatMap { b ->
            when (val c = b.content) {
                is TaskBlockContent.Links -> c.items.map { Found(b.writtenAtMillis, kindOf(it.url, it.label, b.title, false), it.label, it.url) }
                is TaskBlockContent.Documents ->
                    c.items.map { Found(it.updatedMillis ?: b.writtenAtMillis, kindOf(it.url, it.title, b.title, true), it.title, it.url) }
                else -> emptyList()
            }
        }.filter { PanelMarkdown.isSafeUrl(it.url) }
        val zone = zone()
        return found.sortedByDescending { it.at }.distinctBy { it.url }
            .map { LinkRow(it.kind, it.title.ifBlank { it.url }, it.url, clockOf(it.at, now, zone)) }
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

        /** What a line says about ↵ when it goes to the member's own tab or attach command. */
        const val OPEN_HINT = "↵ open"

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
         * The short word for what a link is, from where it points and what it is called.
         *
         * Called for every link row. A block titled "Decisions" or "Briefs" marks its links as
         * such; any other link is told apart by its address.
         *
         * @param url the address.
         * @param label the words shown for it.
         * @param blockTitle the title of the block that holds it, or null.
         * @param isDocument true for an entry of a documents block.
         * @return `decision`, `brief`, `slack`, `PR`, `issue`, `art`, `doc` or `link`.
         */
        fun kindOf(url: String, label: String, blockTitle: String?, isDocument: Boolean): String {
            val u = url.lowercase()
            val where = (blockTitle ?: "").lowercase()
            val words = label.lowercase()
            return when {
                "decision" in where || words.startsWith("you decided") -> "decision"
                "brief" in where || words.startsWith("brief") -> "brief"
                "slack.com" in u -> "slack"
                "github.com" in u && "/pull/" in u -> "PR"
                "github.com" in u && "/issues/" in u -> "issue"
                "claude.ai" in u && "/artifact" in u -> "art"
                isDocument || u.startsWith("file:") -> "doc"
                else -> "link"
            }
        }

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
