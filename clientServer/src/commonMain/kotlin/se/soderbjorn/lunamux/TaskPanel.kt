/**
 * The shared model of a task's panel: the blocks an agent writes about a task and the content
 * each block can hold. The server stores and validates these ([TaskPanelBlock] in the
 * `task_panel_blocks` table), pushes them to every window as [WindowEnvelope.TaskPanel], and
 * the clients draw them in the panel beside the task's own session.
 *
 * Contains [TaskPanelBlock], the sealed [TaskBlockContent] with its three cases (links, a timeline
 * and progress), and the small item types those cases carry. Everything here is plain data; the
 * limits on sizes and counts are enforced by the server when a block is written, not by these
 * types.
 *
 * @see WindowEnvelope.TaskPanel
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One block an agent wrote into a task's panel.
 *
 * Stored by the server per task and sent to the windows in the order the agents gave. A writer
 * names the block with its own [id] so it can replace or remove that block later without
 * touching the others.
 *
 * Built by the server's panel store when an agent calls `set_task_panel` or `upsert_task_block`;
 * read by the panel's view model, which turns it into a drawn block.
 *
 * @property id the writer's name for the block, unique within the task.
 * @property title a short heading drawn above the block, or null for none.
 * @property content what the block shows.
 * @property writtenBy who wrote it, shown as "writer · age": the name the agent gave, else the
 *   title of the session that called, else null.
 * @property writtenAtMillis when the server stored it, in epoch milliseconds.
 * @see TaskBlockContent
 * @see WindowEnvelope.TaskPanel
 */
@Serializable
data class TaskPanelBlock(
    val id: String,
    val title: String? = null,
    val content: TaskBlockContent,
    val writtenBy: String? = null,
    val writtenAtMillis: Long,
)

/**
 * What a [TaskPanelBlock] shows. One case per kind of block; the panel draws each in its own way
 * and treats every text in them as plain text. A block stored with any other kind is dropped when
 * the server loads it.
 *
 * @see TaskPanelBlock.content
 */
@Serializable
sealed class TaskBlockContent {
    /** The name stored in the `kind` column and used in the agent-facing `type` field. */
    abstract val kindName: String

    /**
     * Links the person can open.
     *
     * @property items the links, in order.
     */
    @Serializable
    @SerialName("links")
    data class Links(val items: List<TaskLink>) : TaskBlockContent() {
        override val kindName: String get() = "links"
    }

    /**
     * Things that happened, drawn newest first.
     *
     * @property items the entries, oldest first as stored.
     */
    @Serializable
    @SerialName("timeline")
    data class Timeline(val items: List<TaskTimelineItem>) : TaskBlockContent() {
        override val kindName: String get() = "timeline"
    }

    /**
     * How far along the work is: a count of done out of total, or a named phase, with an optional
     * label.
     *
     * @property done how many parts are finished, or null when only a [phase] is given.
     * @property total how many parts there are, or null.
     * @property phase the name of the stage the work is in, or null.
     * @property label a short caption, or null.
     */
    @Serializable
    @SerialName("progress")
    data class Progress(
        val done: Int? = null,
        val total: Int? = null,
        val phase: String? = null,
        val label: String? = null,
    ) : TaskBlockContent() {
        override val kindName: String get() = "progress"
    }
}

/**
 * A link in a [TaskBlockContent.Links] block. The server only stores `http`, `https` and `file`
 * addresses.
 *
 * The writer says what the link is and when it happened; the panel shows both as given and
 * guesses neither. A row stored before these fields existed reads back as kind `link` with no
 * time.
 *
 * Built by the server's MCP panel tools from an agent's `links` items, and by the panel store when
 * it reads a stored block; read by the panel's view model for the Links list.
 *
 * @property kind the short word shown for what the link is (any word the writer chose, up to
 *   a short length the server enforces); `link` when the writer gave none.
 * @property label the words shown for the link.
 * @property url where it goes.
 * @property at when the linked thing was made or last changed, in epoch milliseconds, or null when
 *   the writer gave no time. Rows without a time are listed after the ones with one.
 */
@Serializable
data class TaskLink(
    val kind: String = TaskLink.DEFAULT_KIND,
    val label: String,
    val url: String,
    val at: Long? = null,
) {
    companion object {
        /** The kind of a link whose writer gave none, and of a row stored before kinds existed. */
        const val DEFAULT_KIND = "link"
    }
}

/**
 * One entry in a [TaskBlockContent.Timeline] block.
 *
 * @property atMillis when it happened, in epoch milliseconds.
 * @property text what happened.
 */
@Serializable
data class TaskTimelineItem(val atMillis: Long, val text: String)
