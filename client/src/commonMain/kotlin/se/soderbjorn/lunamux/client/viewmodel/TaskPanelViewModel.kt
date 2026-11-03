/**
 * The shared view model of a task's panel: the column of agent-written blocks that sits beside
 * the session that speaks for a task. It decides whether a panel shows at all (only in the tab
 * that holds that session, and only while the person has it open), builds the agents block from
 * the task's members, turns every stored block into something a view can draw without further
 * thought, and carries out what a click does (peek at a background member, jump to a listed one,
 * attach to an unhosted one, take a peeked tab over, press a button).
 *
 * Contains [TaskPanelViewModel] with its state types ([TaskPanelViewModel.State],
 * [TaskPanelViewModel.AgentLine], [TaskPanelViewModel.BlockView]). Web draws it today; another
 * client can draw the same state.
 *
 * @see PanelMarkdown
 * @see se.soderbjorn.lunamux.TaskPanelBlock
 * @see AgentsOverviewBackingViewModel
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import se.soderbjorn.lunamux.AgentSessionSummary
import se.soderbjorn.lunamux.AgentSessionText
import se.soderbjorn.lunamux.AgentTask
import se.soderbjorn.lunamux.AgentTaskMember
import se.soderbjorn.lunamux.LeafNode
import se.soderbjorn.lunamux.TabConfig
import se.soderbjorn.lunamux.TaskBlockContent
import se.soderbjorn.lunamux.TaskPanelBlock
import se.soderbjorn.lunamux.WindowCommand
import se.soderbjorn.lunamux.WindowConfig
import se.soderbjorn.lunamux.client.WindowStateRepository
import se.soderbjorn.lunamux.effectiveActiveTabId

/** Where a pane sits: its world (null in a config without worlds), its tab and the leaf itself. */
private data class PanelPaneLocation(val worldId: String?, val tab: TabConfig, val leaf: LeafNode)

/**
 * View model of the task panel.
 *
 * Created once per window by the web client's panel, which collects [stateFlow], draws it and
 * calls the action functions from clicks and keys. The person's open/closed choice lives here
 * as [State.open]; the client stores it and hands it back with [setOpen].
 *
 * @param windowState the source of the tasks, sessions, layout and stored blocks.
 * @param clock the current epoch millis, read whenever the state is built (ages and the idle
 *   grace depend on it). Tests pass a fixed one.
 * @param send sends a command to the server, normally `windowSocket::send`.
 * @see PanelMarkdown
 */
class TaskPanelViewModel(
    private val windowState: WindowStateRepository,
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

        /** Open a read-only live view of a member running in a background tab. */
        PEEK,

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
     *   file's own word for a member Lunamux does not host.
     * @property age how long the member has been in [state], as `now`, `5m`, `3h` or `2d`.
     * @property nowLine what it is doing or asking, or null.
     * @property dimmed true for a finished member, which stays in the list but recedes.
     * @property action what a click does.
     * @property recordId the session record behind the line, or null for an unhosted member.
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
     * The read-only live view of a background member.
     *
     * @property recordId the session record being watched.
     * @property title the member's name for the bar.
     * @property sessionId the terminal session to mirror, or null when it cannot be found (the
     *   view then shows nothing to mirror).
     * @property canTakeOver whether the member runs in a background tab, which ⌘↵ turns into an ordinary one.
     */
    data class PanelPeek(val recordId: String, val title: String, val sessionId: String?, val canTakeOver: Boolean)

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
     * @property peek the open peek, or null.
     */
    data class State(
        val open: Boolean = true,
        val visible: Boolean = false,
        val taskId: String? = null,
        val title: String = "",
        val agents: List<AgentLine> = emptyList(),
        val blocks: List<BlockView> = emptyList(),
        val emptyText: String? = null,
        val peek: PanelPeek? = null,
    )

    /**
     * What the person has chosen. A peek is remembered with the task and tab it was opened in, so
     * it can only ever show there and is dropped as soon as the panel stops showing that task.
     */
    private data class Ui(val open: Boolean = true, val peek: PeekScope? = null)

    /** A peek on [recordId], opened beside task [taskId] while tab [tabId] was active. */
    private data class PeekScope(val recordId: String, val taskId: String, val tabId: String)

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
    }.onEach(::forgetStalePeek)

    /**
     * The state right now, for key handlers that must act on what is on screen. Reading it also
     * forgets a peek that no longer shows.
     */
    val state: State
        get() = build(
            windowState.agentTasks.value, windowState.agentSessions.value, windowState.config.value,
            windowState.taskPanels.value, ui.value,
        ).also(::forgetStalePeek)

    /**
     * Drop the remembered peek when [built] shows none: the panel was hidden, another tab or task
     * took its place, or the member ended. Without this the peek would come back, and take every
     * key, the next time the person returned to the tab.
     */
    private fun forgetStalePeek(built: State) {
        if (built.peek == null && ui.value.peek != null) ui.update { it.copy(peek = null) }
    }

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
     * Do what a click on [line] does: peek at a background member, jump to a listed one, attach to
     * an unhosted one, or nothing.
     *
     * @param line a line of [State.agents].
     * @see MemberAction
     */
    suspend fun activateMember(line: AgentLine) {
        val taskId = state.taskId ?: return
        when (line.action) {
            MemberAction.PEEK -> {
                val tabId = windowState.config.value?.effectiveActiveTabId
                val recordId = line.recordId
                if (tabId != null && recordId != null) ui.update { it.copy(peek = PeekScope(recordId, taskId, tabId)) }
            }
            MemberAction.JUMP -> jump(line.recordId)
            MemberAction.OPEN -> send(WindowCommand.OpenTaskMember(taskId, line.key))
            MemberAction.NONE -> Unit
        }
    }

    /**
     * Take over the tab of the peeked member: it becomes an ordinary tab and is focused. Does
     * nothing when no peek is open or the member is not in a background tab. Called for ⌘↵ in the peek.
     *
     * @see WindowCommand.TakeOverTab
     */
    suspend fun takeOver() {
        val recordId = ui.value.peek?.recordId ?: return
        val session = windowState.agentSessions.value.firstOrNull { it.id == recordId } ?: return
        val where = session.leafId?.let { windowState.config.value?.let { c -> locate(c, it) } } ?: return
        if (!session.background) return
        send(WindowCommand.TakeOverTab(where.tab.id))
        send(WindowCommand.SetFocusedPane(where.tab.id, where.leaf.id))
        closePeek()
    }

    /** Close the peek. Called for esc. */
    fun closePeek() = ui.update { it.copy(peek = null) }

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
        val peek = ui.peek?.takeIf { ui.open && it.taskId == task.id && it.tabId == activeTab }
            ?.let { peekOf(it.recordId, byId, config) }
        return State(
            open = ui.open,
            visible = ui.open,
            taskId = task.id,
            title = task.title,
            agents = agents,
            blocks = blocks,
            emptyText = EMPTY_TEXT.takeIf { blocks.isEmpty() },
            peek = peek,
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
        val matched = fileMembers.mapNotNullTo(HashSet()) { it.second?.id }
        val extra = sessions.filter { it.taskId == task.id && it.id !in matched }
            .sortedWith(compareBy({ it.startedAt ?: it.stateSince }, { it.id }))
            .map { memberOf(it) to it }
        return (fileMembers + extra).filter { (_, s) -> s?.id != ownId }.map { (m, s) ->
            val where = s?.takeIf { it.state != ENDED }?.leafId?.let { locate(config, it) }
            val action = when {
                s == null && m.sessionId != null -> MemberAction.OPEN
                where != null && s!!.background -> MemberAction.PEEK
                where != null && !where.tab.isBackground -> MemberAction.JUMP
                else -> MemberAction.NONE
            }
            AgentLine(
                key = m.key,
                title = m.title.ifBlank { "Agent session" },
                state = m.state,
                stateWord = (m.label?.takeIf { s == null && it.isNotBlank() }) ?: wordOf(m.state),
                age = ageOf(m.sinceMillis, now),
                nowLine = (m.line ?: s?.nowLine)?.trim()?.takeIf { it.isNotEmpty() },
                dimmed = m.state == ENDED,
                action = action,
                recordId = s?.id,
            )
        }
    }

    private fun memberOf(s: AgentSessionSummary) = AgentTaskMember(
        key = s.id,
        title = titleOf(s),
        links = s.links,
        state = s.state,
        line = if (s.state == NEEDS_YOU) s.question ?: s.nowLine else s.nowLine,
        sinceMillis = s.stateSince,
        recordId = s.id,
    )

    private fun titleOf(s: AgentSessionSummary): String {
        val folder = if (s.cwdHome) "~" else s.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        s.title?.trim()?.takeIf { AgentSessionText.isMeaningfulTitle(it, s.branch, s.repo, folder) }?.let { return it }
        return AgentSessionText.titleFromPrompt(s.firstPrompt) ?: "${s.agent.replaceFirstChar { it.uppercase() }} session"
    }

    private fun peekOf(recordId: String, byId: Map<String, AgentSessionSummary>, config: WindowConfig): PanelPeek? {
        val s = byId[recordId]?.takeIf { it.state != ENDED } ?: return null
        val where = s.leafId?.let { locate(config, it) } ?: return null
        return PanelPeek(recordId, titleOf(s), where.leaf.sessionId, s.background)
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

    private fun locate(config: WindowConfig, leafId: String): PanelPaneLocation? {
        fun find(worldId: String?, tabs: List<TabConfig>): PanelPaneLocation? {
            for (tab in tabs) for (pane in tab.panes) if (pane.leaf.id == leafId) return PanelPaneLocation(worldId, tab, pane.leaf)
            return null
        }
        if (config.worlds.isEmpty()) return find(null, config.tabs)
        for (w in config.worlds) find(w.id, w.tabs)?.let { return it }
        return null
    }

    companion object {
        /** Shown when a task's panel has no stored blocks. */
        const val EMPTY_TEXT = "Nothing else here yet. Agents can add blocks with set_task_panel."

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
