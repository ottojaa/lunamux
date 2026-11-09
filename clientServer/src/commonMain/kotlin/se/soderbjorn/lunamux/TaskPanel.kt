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
 */
@Serializable
sealed class TaskBlockContent {
    /** The name stored in the `kind` column and used in the agent-facing `type` field. */
    abstract val kindName: String

    /** Links the person can open. */
    @Serializable
    @SerialName("links")
    data class Links(val items: List<TaskLink>) : TaskBlockContent() {
        override val kindName: String get() = "links"
    }

    /** Things that happened, drawn newest first. */
    @Serializable
    @SerialName("timeline")
    data class Timeline(val items: List<TaskTimelineItem>) : TaskBlockContent() {
        override val kindName: String get() = "timeline"
    }

    /**
     * How far along the work is: a count of done out of total, or a named phase, with an optional
     * label.
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

/** One entry in a [TaskBlockContent.Timeline] block. */
@Serializable
data class TaskTimelineItem(val atMillis: Long, val text: String)
