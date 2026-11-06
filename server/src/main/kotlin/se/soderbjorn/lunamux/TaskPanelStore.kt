/**
 * The server's copy of every task's panel: the blocks agents write about a task.
 *
 * This file contains [TaskPanelStore], which keeps the blocks in memory as a [StateFlow] for the
 * windows' push, writes every change through to the `task_panel_blocks` table, and checks every
 * block against [TaskPanelLimits] before it is kept; [TaskPanelLimits.check] is the one place
 * those rules live, so the MCP tools only turn an agent's JSON into blocks. It also holds
 * [pressTaskButton], the one way a button press reaches a terminal.
 *
 * @see se.soderbjorn.lunamux.mcp.registerMcpPanelTools
 * @see TaskPanelBlock
 * @see TaskIndex
 */
package se.soderbjorn.lunamux

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory
import se.soderbjorn.lunamux.persistence.SettingsRepository
import se.soderbjorn.lunamux.persistence.SettingsRepository.TaskPanelRow

private val log = LoggerFactory.getLogger("TaskPanelStore")

/**
 * The sizes and shapes a task panel block may have. Every refusal message names the block, item
 * or field that broke the rule and what the limit is, so an agent can fix its call from the
 * message alone.
 */
object TaskPanelLimits {
    /** Most blocks one task's panel holds. */
    const val MAX_BLOCKS = 24
    /** Longest block id. */
    const val ID_MAX = 64
    /** Longest block title. */
    const val TITLE_MAX = 80
    /** Longest markdown text. */
    const val TEXT_MAX = 4000
    /** Most items in a facts or links block. */
    const val LIST_MAX = 30
    /** Longest fact or link label. */
    const val LABEL_MAX = 80
    /** Longest fact value. */
    const val VALUE_MAX = 200
    /** Longest link address. */
    const val URL_MAX = 2000
    /** Longest link kind, the short word shown before the label. */
    const val KIND_MAX = 12
    /** Most entries a timeline keeps; the newest are kept. */
    const val TIMELINE_MAX = 50
    /** Longest timeline entry. */
    const val TIMELINE_TEXT_MAX = 200
    /** Most buttons in an actions block. */
    const val BUTTONS_MAX = 8
    /** Longest button label. */
    const val BUTTON_LABEL_MAX = 40
    /** Longest button text. */
    const val BUTTON_TEXT_MAX = 500
    /** Longest progress phase or label. */
    const val PHASE_MAX = 80
    /** Longest name of a writer. */
    const val WRITER_MAX = 40
    /** The block id the windows use for the agents block they draw themselves. */
    const val RESERVED_ID = "agents"

    private val idPattern = Regex("[A-Za-z0-9._:-]+")
    private val urlPattern = Regex("(https?|file)://\\S+", RegexOption.IGNORE_CASE)

    /**
     * Whether [s] holds a control character (anything below space, DEL, or a C1 control).
     *
     * @param s the text to look at.
     * @return true when it holds one.
     */
    fun hasControlChars(s: String): Boolean = s.any { it < ' ' || it == '\u007f' || it in '\u0080'..'\u009f' }

    /**
     * Check one block and return the form that is stored.
     *
     * Called by [TaskPanelStore] for every block of a write. Timelines longer than
     * [TIMELINE_MAX] are cut to their newest entries (by time, keeping the order they came in);
     * every other broken rule throws.
     *
     * @param block the block as the agent gave it.
     * @return the block to keep, equal to [block] unless a timeline was cut.
     * @throws TaskException naming the block and the rule it broke.
     */
    fun check(block: TaskPanelBlock): TaskPanelBlock {
        val id = block.id
        if (id.isEmpty() || id.length > ID_MAX || !idPattern.matches(id)) {
            throw TaskException(
                "Block id '${id.take(ID_MAX)}' is not allowed: use 1-$ID_MAX characters from letters, digits, '.', '_', ':' and '-'.",
            )
        }
        if (id.equals(RESERVED_ID, ignoreCase = true)) {
            throw TaskException("Block id '$id' is reserved for the list of the task's sessions, which the panel draws itself. Pick another id.")
        }
        fun limit(what: String, text: String, max: Int) {
            if (text.length > max) throw TaskException("Block '$id': $what is ${text.length} characters, the limit is $max.")
        }
        fun count(what: String, n: Int, max: Int) {
            if (n > max) throw TaskException("Block '$id': $n $what, the limit is $max.")
        }
        block.title?.let { limit("the title", it, TITLE_MAX) }
        val content = when (val c = block.content) {
            is TaskBlockContent.Text -> {
                limit("the text", c.markdown, TEXT_MAX)
                c
            }
            is TaskBlockContent.Facts -> {
                count("facts", c.items.size, LIST_MAX)
                c.items.forEachIndexed { i, f ->
                    if (f.label.isBlank()) throw TaskException("Block '$id': fact ${i + 1} has no label.")
                    limit("the label of fact ${i + 1}", f.label, LABEL_MAX)
                    limit("the value of fact '${f.label}'", f.value, VALUE_MAX)
                }
                c
            }
            is TaskBlockContent.Links -> {
                count("links", c.items.size, LIST_MAX)
                c.items.forEachIndexed { i, l ->
                    if (l.label.isBlank()) throw TaskException("Block '$id': link ${i + 1} has no label.")
                    if (l.kind.isBlank() || hasControlChars(l.kind)) {
                        throw TaskException("Block '$id': the kind of link '${l.label}' must be a short word (1-$KIND_MAX characters) with no control characters.")
                    }
                    limit("the kind of link '${l.label}'", l.kind, KIND_MAX)
                    val at = l.at
                    if (at != null && at < 0) throw TaskException("Block '$id': link '${l.label}' has a negative time.")
                    limit("the label of link ${i + 1}", l.label, LABEL_MAX)
                    limit("the address of link '${l.label}'", l.url, URL_MAX)
                    if (!urlPattern.matches(l.url)) {
                        throw TaskException(
                            "Block '$id': the address of link '${l.label}' is not allowed. Only http://, https:// and file:// addresses are kept.",
                        )
                    }
                }
                c
            }
            is TaskBlockContent.Timeline -> {
                c.items.forEachIndexed { i, t ->
                    if (t.atMillis < 0) throw TaskException("Block '$id': timeline entry ${i + 1} has a negative time.")
                    limit("timeline entry ${i + 1}", t.text, TIMELINE_TEXT_MAX)
                }
                if (c.items.size <= TIMELINE_MAX) c else {
                    val keep = c.items.indices.sortedWith(compareByDescending<Int> { c.items[it].atMillis }.thenByDescending { it })
                        .take(TIMELINE_MAX).toSet()
                    c.copy(items = c.items.filterIndexed { i, _ -> i in keep })
                }
            }
            is TaskBlockContent.Progress -> {
                val hasCount = c.done != null || c.total != null
                if (hasCount && (c.done == null || c.total == null)) {
                    throw TaskException("Block '$id': progress needs both 'done' and 'total', or a 'phase'.")
                }
                if (!hasCount && c.phase.isNullOrBlank()) {
                    throw TaskException("Block '$id': progress needs 'done' and 'total', or a 'phase'.")
                }
                val done = c.done
                val total = c.total
                if (done != null && total != null && (done < 0 || total < 0 || done > total)) {
                    throw TaskException("Block '$id': progress 'done' ($done) must be between 0 and 'total' ($total).")
                }
                c.phase?.let { limit("the phase", it, PHASE_MAX) }
                c.label?.let { limit("the label", it, PHASE_MAX) }
                c
            }
            is TaskBlockContent.Actions -> {
                if (c.buttons.isEmpty()) throw TaskException("Block '$id': an actions block needs at least one button.")
                count("buttons", c.buttons.size, BUTTONS_MAX)
                val seen = mutableSetOf<String>()
                c.buttons.forEach { b ->
                    if (b.id.isEmpty() || b.id.length > ID_MAX || !idPattern.matches(b.id)) {
                        throw TaskException("Block '$id': button id '${b.id.take(ID_MAX)}' is not allowed: use 1-$ID_MAX letters, digits, '.', '_', ':' or '-'.")
                    }
                    if (!seen.add(b.id)) throw TaskException("Block '$id': two buttons are called '${b.id}'.")
                    if (b.label.isBlank()) throw TaskException("Block '$id': button '${b.id}' has no label.")
                    limit("the label of button '${b.id}'", b.label, BUTTON_LABEL_MAX)
                    limit("the text of button '${b.id}'", b.text, BUTTON_TEXT_MAX)
                    if (b.text.isEmpty()) throw TaskException("Block '$id': button '${b.id}' has no text to type.")
                    if (hasControlChars(b.label) || hasControlChars(b.text)) {
                        throw TaskException("Block '$id': button '${b.id}' holds a control character (line break, tab or escape); a button types one plain line.")
                    }
                }
                c
            }
        }
        return block.copy(content = content)
    }
}

/**
 * Holds every task's panel blocks and keeps them across restarts.
 *
 * Created once at boot in `Application.module`. The MCP panel tools call [setPanel], [upsert] and
 * [remove]; the window routes collect [state] to push [WindowEnvelope.TaskPanel] frames and read
 * [buttonOf] when a button is pressed. Reads are lock-free through [state]; writes serialise on
 * one lock and are stored before the new state is published, so a window never sees a block that
 * a restart would lose.
 *
 * @property repo where blocks are stored; this class is the only writer of that table.
 * @property taskIndex tells which tasks exist; a block is only kept for a known task.
 * @property clock the server clock that stamps each written block.
 * @see TaskPanelLimits
 */
class TaskPanelStore(
    private val repo: SettingsRepository,
    private val taskIndex: TaskIndex,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(load())

    /** Every task's blocks in drawing order; a task with no blocks has no entry. */
    val state: StateFlow<Map<String, List<TaskPanelBlock>>> = _state.asStateFlow()

    private fun load(): Map<String, List<TaskPanelBlock>> {
        val byTask = LinkedHashMap<String, MutableList<TaskPanelBlock>>()
        for (row in repo.loadTaskPanelRows()) {
            val content = try {
                windowJson.decodeFromString(TaskBlockContent.serializer(), row.body)
            } catch (e: kotlinx.serialization.SerializationException) {
                log.warn("Dropping unreadable panel block {}/{}: {}", row.taskId, row.blockId, e.message)
                continue
            }
            byTask.getOrPut(row.taskId) { mutableListOf() }
                .add(TaskPanelBlock(row.blockId, row.title, content, row.writtenBy, row.writtenAt))
        }
        return byTask
    }

    /**
     * One task's blocks.
     *
     * @param taskId the task's id.
     * @return its blocks in drawing order, empty when it has none.
     */
    fun blocksOf(taskId: String): List<TaskPanelBlock> = _state.value[taskId].orEmpty()

    /**
     * One task's blocks, for a task that must exist.
     *
     * Called by `get_task_panel`, so an agent that mistypes a task id is told so instead of
     * reading an empty panel.
     *
     * @param taskId the task's id.
     * @return its blocks in drawing order, empty when it has none.
     * @throws TaskException when the task is unknown.
     */
    fun blocksOfKnown(taskId: String): List<TaskPanelBlock> {
        taskIndex.requireTask(taskId)
        return blocksOf(taskId)
    }

    /**
     * Replace all of a task's blocks with [blocks], in the order given.
     *
     * Called by `set_task_panel`. Nothing is changed when any block breaks a rule.
     *
     * @param taskId the task; it must exist.
     * @param blocks the new blocks; their own writer and time are ignored.
     * @param writer who is writing, stored on every block, or null.
     * @return the blocks as stored.
     * @throws TaskException for an unknown task, a repeated id, too many blocks or a broken rule.
     */
    fun setPanel(taskId: String, blocks: List<TaskPanelBlock>, writer: String?): List<TaskPanelBlock> {
        taskIndex.requireTask(taskId)
        if (blocks.size > TaskPanelLimits.MAX_BLOCKS) {
            throw TaskException("A panel holds at most ${TaskPanelLimits.MAX_BLOCKS} blocks; ${blocks.size} were given.")
        }
        val ids = mutableSetOf<String>()
        blocks.forEach { if (!ids.add(it.id)) throw TaskException("Two blocks are called '${it.id}'. Each block needs its own id.") }
        synchronized(lock) {
            val now = clock()
            val stored = blocks.map { TaskPanelLimits.check(it).copy(writtenBy = writer, writtenAtMillis = now) }
            repo.replaceTaskPanel(taskId, stored.mapIndexed { i, b -> rowOf(taskId, i, b) })
            publish(taskId, stored)
            return stored
        }
    }

    /**
     * Replace the block with [block]'s id where it stands, or add it at the end.
     *
     * Called by `upsert_task_block`. Replacing never counts against the block limit; adding a
     * new block to a full panel is refused.
     *
     * @param taskId the task; it must exist.
     * @param block the block to keep; its own writer and time are ignored.
     * @param writer who is writing, or null.
     * @return the block as stored.
     * @throws TaskException for an unknown task, a full panel or a broken rule.
     */
    fun upsert(taskId: String, block: TaskPanelBlock, writer: String?): TaskPanelBlock {
        taskIndex.requireTask(taskId)
        synchronized(lock) {
            val stored = TaskPanelLimits.check(block).copy(writtenBy = writer, writtenAtMillis = clock())
            val current = blocksOf(taskId)
            val at = current.indexOfFirst { it.id == stored.id }
            if (at < 0 && current.size >= TaskPanelLimits.MAX_BLOCKS) {
                throw TaskException(
                    "The panel already holds ${TaskPanelLimits.MAX_BLOCKS} blocks, the limit. Remove one with remove_task_block before adding '${stored.id}'.",
                )
            }
            val position = if (at >= 0) at else current.size
            repo.saveTaskPanelRow(rowOf(taskId, position, stored))
            publish(taskId, if (at >= 0) current.toMutableList().also { it[at] = stored } else current + stored)
            return stored
        }
    }

    /**
     * Remove one block, keeping the order of the others.
     *
     * Called by `remove_task_block`.
     *
     * @param taskId the task; it must exist.
     * @param blockId the block to remove.
     * @throws TaskException for an unknown task or a block the task does not have.
     */
    fun remove(taskId: String, blockId: String) {
        taskIndex.requireTask(taskId)
        synchronized(lock) {
            val current = blocksOf(taskId)
            if (current.none { it.id == blockId }) {
                val have = current.joinToString(", ") { "'${it.id}'" }.ifEmpty { "none" }
                throw TaskException("Task '$taskId' has no block '$blockId'. Its blocks: $have.")
            }
            val rest = current.filter { it.id != blockId }
            repo.replaceTaskPanel(taskId, rest.mapIndexed { i, b -> rowOf(taskId, i, b) })
            publish(taskId, rest)
        }
    }

    /**
     * Find a stored button.
     *
     * Called when a window presses a button: the window only names the button, so the text typed
     * is always what an agent stored.
     *
     * @param taskId the task.
     * @param blockId the actions block.
     * @param buttonId the button inside it.
     * @return the button, or null when the task, block or button is unknown or the block is not an actions block.
     */
    fun buttonOf(taskId: String, blockId: String, buttonId: String): TaskButton? =
        (blocksOf(taskId).firstOrNull { it.id == blockId }?.content as? TaskBlockContent.Actions)
            ?.buttons?.firstOrNull { it.id == buttonId }

    private fun publish(taskId: String, blocks: List<TaskPanelBlock>) {
        _state.value = if (blocks.isEmpty()) _state.value - taskId else _state.value + (taskId to blocks)
    }

    private fun rowOf(taskId: String, position: Int, b: TaskPanelBlock) = TaskPanelRow(
        taskId = taskId,
        blockId = b.id,
        position = position,
        kind = b.content.kindName,
        title = b.title,
        body = windowJson.encodeToString(TaskBlockContent.serializer(), b.content),
        writtenBy = b.writtenBy,
        writtenAt = b.writtenAtMillis,
    )
}

/**
 * Type a stored button's text into its task's own session.
 *
 * Called by the window routes for [WindowCommand.PressTaskButton]. The text and the session both
 * come from the server's own records, never from the window. Nothing is written unless the task's
 * own session has an agent that has not ended: a terminal whose agent has quit is a bare shell,
 * and the text would run there as a command.
 *
 * @param store holds the buttons.
 * @param taskIndex names each task's own session.
 * @param tracker tells whether an agent is still running in that session.
 * @param taskId the task whose panel holds the button.
 * @param blockId the actions block.
 * @param buttonId the button inside it.
 * @param write types bytes into a terminal session and returns whether the session was there to
 *   take them.
 * @return true when the text was typed, false when the press was refused.
 */
fun pressTaskButton(
    store: TaskPanelStore,
    taskIndex: TaskIndex,
    tracker: AgentSessionTracker,
    taskId: String,
    blockId: String,
    buttonId: String,
    write: (sessionId: String, bytes: ByteArray) -> Boolean,
): Boolean {
    val button = store.buttonOf(taskId, blockId, buttonId) ?: return false
    val own = taskIndex.ownSessionOf(taskId) ?: return false
    val record = tracker.openRecordOf(own) ?: return false
    if (record.state == "ended") return false
    return write(own, (button.text + "\r").toByteArray(Charsets.UTF_8))
}
