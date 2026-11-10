/**
 * The shared model of a task's panel: the blocks an agent writes about a task and the content each
 * block can hold. The server stores and validates these ([TaskPanelBlock] in the
 * `task_panel_blocks` table), pushes them to every window as [WindowEnvelope.TaskPanel], and the
 * clients draw them in the panel beside the task's own session.
 *
 * Contains [TaskPanelBlock], the sealed [TaskBlockContent] with its four cases (links, a timeline,
 * progress and the steps of a plan), and the small item types those cases carry. Everything here is plain data; the
 * limits on sizes and counts are enforced by the server when a block is written, not by these
 * types.
 */
package se.soderbjorn.lunamux

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One block an agent wrote into a task's panel. */
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

    /** The steps of the plan, in order, each done, in hand or still to do. */
    @Serializable
    @SerialName("steps")
    data class Steps(val items: List<TaskStep>) : TaskBlockContent() {
        override val kindName: String get() = "steps"
    }
}

/** One step in a [TaskBlockContent.Steps] block; [state] is `done`, `current` or `todo`. */
@Serializable
data class TaskStep(val text: String, val state: String = TODO) {
    companion object {
        const val DONE = "done"
        const val CURRENT = "current"
        const val TODO = "todo"
        val STATES = listOf(DONE, CURRENT, TODO)
    }
}

/**
 * A link in a [TaskBlockContent.Links] block. The server only stores `http`, `https` and `file`
 * addresses.
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
