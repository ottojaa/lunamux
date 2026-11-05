/**
 * The shared view model of a task's panel: the column of agent-written blocks that sits beside
 * the session that speaks for a task. It decides whether a panel shows at all (only in the tab
 * that holds that session, and only while the person has it open), builds the agents block from
 * the task's members, turns every stored block into something a view can draw without further
 * thought, and carries out what a click or key on a line does (watch a background member, show it
 * as a tab, jump to a listed one, attach to an unhosted one, press a button).
 *
 * Contains [TaskPanelViewModel] with its state types ([TaskPanelViewModel.State],
 * [TaskPanelViewModel.AgentLine], [TaskPanelViewModel.BlockView]). Web draws it today; another
 * client can draw the same state.
 *
 * @see PanelMarkdown
 * @see WatchViewModel
 * @see se.soderbjorn.lunamux.TaskPanelBlock
 * @see AgentsOverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId

/**
 * View model of the task panel.
 *
 * Created once per window by the web client's panel, which collects [stateFlow], draws it and
 * calls the action functions from clicks and keys. The person's open/closed choice lives here
 * as [State.open]; the client stores it and hands it back with [setOpen].
 *
 * @param windowState the source of the tasks, sessions, layout and stored blocks.
 * @param watch the watch an agent's line opens; shared with every other surface that opens one.
 * @param clock the current epoch millis, read whenever the state is built (ages and the idle
 *   grace depend on it). Tests pass a fixed one.
 * @param send sends a command to the server, normally `windowSocket::send`.
 * @see PanelMarkdown
 * @see WatchViewModel
 */
class TaskPanelViewModel(
    private val windowState: WindowStateRepository,
    private val watch: WatchViewModel,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val send: suspend (WindowCommand) -> Unit,
) {
    /**
     * What a click on an agent line does.
     *
     * @see AgentLine.action
     */
    enum class MemberAction {
        /** Nothing: the member has no session this client can reach. */
        NONE,

        /** Watch a member running in a background tab: its screen opens read-only over the current pane. */
        WATCH,

        /** Go to the tab of a member running in a tab the person can see. */
        JUMP,

        /** Open a member the task file lists but Lunamux does not host, with its own attach command. */
        OPEN,
    }

    /**
     * One line of the agents block.
     *
     * @property key the member's key, unique within the task; keeps a line's identity through a redraw.
     * @property title the member's name.
     * @property state `needs_you`, `working`, `idle` or `ended`; a view picks the glyph from it.
     * @property stateWord the word for [state]: working, needs you, waiting or exited, or the task
     *   file's own word for a member Lunamux does not host; a finished member the file labels reads
     *   `exited · label`.
     * @property age how long the member has been in [state], as `now`, `5m`, `3h` or `2d`.
     * @property nowLine what it is doing or asking, or null.
     * @property dimmed true for a finished member, which stays in the list but recedes.
     * @property action what a click does.
     * @property recordId the session record behind the line, or null for an unhosted member.
     * @property hint the keys that act on the line while it is selected, or null when none does:
     *   [WatchViewModel.LINE_HINT] for a member to watch, [OPEN_HINT] for one to go to.
     */
    data class AgentLine(
        val key: String,
        val title: String,
        val state: String,
        val stateWord: String,
        val age: String,
        val nowLine: String?,
        val dimmed: Boolean,
        val action: MemberAction,
        val recordId: String?,
        val hint: String?,
    )

    /** One entry of a timeline block, newest first. @property age how long ago. @property text what happened. */
    data class TimelineEntry(val age: String, val text: String)

    /**
     * A button of an actions block.
     *
     * @property id the button's id inside its block.
     * @property label the words on it.
     * @property enabled false while the task's own session is not running, when pressing would go nowhere.
     */
    data class ButtonView(val id: String, val label: String, val enabled: Boolean)

    /** What a stored block shows, in the shape a view draws. */
    sealed class BlockBody {
        /** Parsed text. @property nodes the markdown blocks. */
        data class Text(val nodes: List<MdBlock>) : BlockBody()

        /** Label and value pairs. @property items the pairs, in order. */
        data class Facts(val items: List<Pair<String, String>>) : BlockBody()

        /** Links. @property items label and address pairs; every address is http, https or file. */
        data class Links(val items: List<Pair<String, String>>) : BlockBody()

        /** Entries, newest first. @property items the entries. */
        data class Timeline(val items: List<TimelineEntry>) : BlockBody()

        /**
         * Progress.
         *
         * @property text "2 of 5", the phase, or both.
         * @property fraction 0 to 1 when the block has a count, else null.
         * @property label the writer's caption, or null.
         */
        data class Progress(val text: String, val fraction: Double?, val label: String?) : BlockBody()

        /** Buttons. @property buttons them, in order. */
        data class Actions(val buttons: List<ButtonView>) : BlockBody()
    }

    /**
     * One stored block, ready to draw.
     *
     * @property id the writer's id for the block.
     * @property title the heading, or null for none.
     * @property byline "writer · age", or just the age when no writer is known.
     * @property body what to draw.
     */
    data class BlockView(val id: String, val title: String?, val byline: String, val body: BlockBody)

    /**
     * Everything a view needs to draw the panel.
     *
     * @property open the person's choice: true unless they hid the panel.
     * @property visible whether to draw the column: [open] and a task whose own session is in the active tab.
     * @property taskId the task being shown, or null when [visible] is false for lack of one.
     * @property title the task's name.
     * @property agents the agents block's lines, in the task's member order, without the session the
     *   panel sits beside; empty when the task has no other members.
     * @property blocks the stored blocks, in the order the agents gave.
     * @property emptyText the sentence to show when there is nothing besides the agents, else null.
     */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val agents: List<AgentLine> = emptyList(),
        val blocks: List<BlockView> = emptyList(),
        val emptyText: String? = null,
    )

    /** What the person has chosen: whether the panel is shown. */
    private data class Ui(val open: Boolean = true)

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
     * Do what ↵ or a click on [line] does: watch a background member, jump to a listed one,
     * attach to an unhosted one, or nothing.
     *
     * @param line a line of [State.agents].
     * @see MemberAction
     * @see WatchViewModel.watch
     */
    suspend fun activateMember(line: AgentLine) {
        val taskId = state.taskId ?: return
        when (line.action) {
            MemberAction.WATCH -> line.recordId?.let(watch::watch)
            MemberAction.JUMP -> jump(line.recordId)
            MemberAction.OPEN -> send(WindowCommand.OpenTaskMember(taskId, line.key))
            MemberAction.NONE -> Unit
        }
    }

    /**
     * Show the tab of a background member as an ordinary tab, straight from its selected line.
     * No watch is opened on the way, so no overlay is ever drawn. Does nothing for any other kind
     * of line. Called for ⌘↵ on a line.
     *
     * @param line a line of [State.agents].
     * @see WatchViewModel.showRecordAsTab
     */
    suspend fun showAsTab(line: AgentLine) {
        if (line.action != MemberAction.WATCH) return
        line.recordId?.let { watch.showRecordAsTab(it) }
    }

    /**
     * Press a button of an actions block. The server holds the text; only the names travel. Does
     * nothing while the buttons are disabled.
     *
     * @param blockId the block holding the button.
     * @param buttonId the button's id inside the block.
     * @see WindowCommand.PressTaskButton
     */
    suspend fun press(blockId: String, buttonId: String) {
        val current = state
        val taskId = current.taskId ?: return
        val button = current.blocks.firstOrNull { it.id == blockId }
            ?.let { it.body as? BlockBody.Actions }?.buttons?.firstOrNull { it.id == buttonId } ?: return
        if (!button.enabled) return
        send(WindowCommand.PressTaskButton(taskId, blockId, buttonId))
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
        val task = tasks.filter { t ->
            val leaf = t.leadRecordId?.let(byId::get)?.leafId ?: return@filter false
            locate(config, leaf)?.tab?.id == activeTab
        }.minByOrNull { it.startedAtMillis ?: Long.MAX_VALUE } ?: return hidden
        val own = byId.getValue(task.leadRecordId!!)
        val now = clock()
        val live = own.state != ENDED
        val agents = linesOf(task, sessions, byId, config, own.id, now)
        val blocks = (panels[task.id] ?: emptyList()).map { viewOf(it, live, now) }
        return State(
            open = ui.open,
            visible = ui.open,
            taskId = task.id,
            title = task.title,
            agents = agents,
            blocks = blocks,
            emptyText = EMPTY_TEXT.takeIf { blocks.isEmpty() },
        )
    }

    private fun linesOf(
        task: AgentTask,
        sessions: List<AgentSessionSummary>,
        byId: Map<String, AgentSessionSummary>,
        config: WindowConfig,
        ownId: String,
        now: Long,
    ): List<AgentLine> {
        val fileMembers = task.members.map { it to it.recordId?.let(byId::get) }
        val extra = TaskMembers.unlistedSessions(task, byId, sessions.filter { it.taskId == task.id })
            .map { TaskMembers.memberOf(it) to it }
        return (fileMembers + extra).filter { (_, s) -> s?.id != ownId }.map { (m, s) ->
            val where = s?.takeIf { it.state != ENDED }?.leafId?.let { locate(config, it) }
            val action = when {
                s == null && m.sessionId != null -> MemberAction.OPEN
                where != null && s!!.background -> MemberAction.WATCH
                where != null && !where.tab.isBackground -> MemberAction.JUMP
                else -> MemberAction.NONE
            }
            AgentLine(
                key = m.key,
                title = m.title.ifBlank { "Agent session" },
                state = m.state,
                stateWord = stateWordOf(m, s),
                age = ageOf(m.sinceMillis, now),
                nowLine = (m.line ?: s?.nowLine)?.trim()?.takeIf { it.isNotEmpty() },
                dimmed = m.state == ENDED,
                action = action,
                recordId = s?.id,
                hint = when (action) {
                    MemberAction.WATCH -> WatchViewModel.LINE_HINT
                    MemberAction.JUMP, MemberAction.OPEN -> OPEN_HINT
                    MemberAction.NONE -> null
                },
            )
        }
    }

    /** The word under a line: the file's own label for an unhosted member, else the state's word. */
    private fun stateWordOf(m: AgentTaskMember, s: AgentSessionSummary?): String {
        val label = m.label?.takeIf { s == null && it.isNotBlank() }
        return when {
            m.state == ENDED && label != null -> "${wordOf(ENDED)} · $label"
            label != null -> label
            else -> wordOf(m.state)
        }
    }

    private fun viewOf(b: TaskPanelBlock, live: Boolean, now: Long): BlockView {
        val age = ageOf(b.writtenAtMillis, now)
        val body = when (val c = b.content) {
            is TaskBlockContent.Text -> BlockBody.Text(PanelMarkdown.parse(c.markdown))
            is TaskBlockContent.Facts -> BlockBody.Facts(c.items.map { it.label to it.value })
            is TaskBlockContent.Links -> BlockBody.Links(c.items.filter { PanelMarkdown.isSafeUrl(it.url) }.map { it.label to it.url })
            is TaskBlockContent.Timeline ->
                BlockBody.Timeline(c.items.sortedByDescending { it.atMillis }.map { TimelineEntry(ageOf(it.atMillis, now), it.text) })
            is TaskBlockContent.Progress -> {
                val done = c.done
                val total = c.total
                val count = if (done != null && total != null) "$done of $total" else null
                BlockBody.Progress(
                    text = listOfNotNull(count, c.phase?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    fraction = if (done != null && total != null && total > 0) done.toDouble() / total else null,
                    label = c.label?.takeIf { it.isNotBlank() },
                )
            }
            is TaskBlockContent.Actions -> BlockBody.Actions(c.buttons.map { ButtonView(it.id, it.label, live) })
        }
        return BlockView(b.id, b.title, b.writtenBy?.let { "$it · $age" } ?: age, body)
    }

    private fun locate(config: WindowConfig, leafId: String): TaskMembers.PanePlace? = TaskMembers.locate(config, leafId)

    companion object {
        /** Shown when a task's panel has no stored blocks. */
        const val EMPTY_TEXT = "Nothing else here yet. Agents can add blocks with set_task_panel."

        /** What a line says about ↵ when it goes to the member's own tab or attach command. */
        const val OPEN_HINT = "↵ open"

        private const val NEEDS_YOU = "needs_you"
        private const val ENDED = "ended"

        /**
         * A compact age: `now`, `5m`, `3h` or `2d`. A time in the future reads `now`.
         *
         * Called for every line and block; kept here so every client says the same thing.
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
         * The word for a member state.
         *
         * @param state `needs_you`, `working`, `idle` or `ended`.
         * @return working, needs you, waiting or exited.
         */
        fun wordOf(state: String): String = when (state) {
            "working" -> "working"
            NEEDS_YOU -> "needs you"
            ENDED -> "exited"
            else -> "waiting"
        }
    }
}
