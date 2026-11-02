/**
 * Alternative visual hierarchies for the agents overview, kept apart from the renderer so that
 * the renderer in `AgentsOverview.kt` stays one drawing of one set of data.
 *
 * Three directions can be switched on beside today's drawing ("0"): "a", a quiet table; "b", an
 * answer card first; "c", a narrow two-line feed. A direction never changes what the overview
 * knows or does. It changes only how the same rows are drawn: which attribute the panel carries
 * (the stylesheet does the rest), where the folder and the match context go, whether a run of
 * finished task members collapses into one line, and what sits above the list as the answer.
 *
 * Nothing here is on by default. With no `?ao=` in the address and nothing stored, the overview is
 * drawn as before and the ⌥0-⌥3 keys do nothing; the direction a person picks with those keys is
 * remembered in the browser's local storage, and an explicit `?ao=` always wins over it.
 *
 * This file holds the pure parts (choice and persistence of the direction, which finished members
 * fold, what the answer says); the DOM that shows them is built in `AgentsOverview.kt`.
 *
 * @see OverviewDirection
 * @see OverviewDirections
 */
package se.soderbjorn.lunamux

import kotlinx.browser.localStorage
import kotlinx.browser.window
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Glyph
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.Row
import se.soderbjorn.lunamux.client.viewmodel.AgentsOverviewBackingViewModel.RowKind

/**
 * One way of drawing the overview's hierarchy.
 *
 * @property key the short name used in `?ao=`, in local storage and as the `data-dir` attribute
 *   the stylesheet selects on.
 * @property tag the name shown in the footer while switching is on.
 * @see OverviewDirections.current
 */
internal enum class OverviewDirection(val key: String, val tag: String) {
    /** The drawing before any direction existed. */
    TODAY("0", "0 · today"),

    /** The same table with weight and contrast rebalanced and fewer glyphs. */
    QUIET_TABLE("a", "A · quiet table"),

    /** A card with the things that need the person above a calm table. */
    ANSWER_FIRST("b", "B · answer first"),

    /** A narrow feed of two-line rows with the answer pinned to the top. */
    NARROW_FEED("c", "C · narrow feed"),
}

/**
 * One drawn unit of the list: a row as the view model has it, or a run of finished task members
 * folded into a single line.
 *
 * @see OverviewDirections.plan
 */
internal sealed class ListItem {
    /** The first view-model row this item stands for. */
    abstract val first: Row

    /** The last view-model row this item stands for. */
    abstract val last: Row

    /** The id of the task the item belongs to, or null for a session outside any task. */
    val taskId: String? get() = first.taskId

    /**
     * A single row, drawn as it is.
     *
     * @property row the view-model row.
     */
    class One(val row: Row) : ListItem() {
        override val first: Row get() = row
        override val last: Row get() = row
    }

    /**
     * Consecutive finished members of one task, drawn as one line until opened.
     *
     * @property rows the folded rows, in list order; never fewer than [OverviewDirections.FOLD_MIN].
     */
    class Fold(val rows: List<Row>) : ListItem() {
        override val first: Row get() = rows.first()
        override val last: Row get() = rows.last()
    }
}

/**
 * What the overview says at a glance, for the directions that put it above the list.
 *
 * @property needs the sessions that need the person, in list order.
 * @property needCount how many sessions need the person; the same number the topbar chip shows.
 * @property workingCount how many sessions are working; the same number the chip shows.
 * @see OverviewDirections.answer
 */
internal class Answer(val needs: List<Row>, val needCount: Int, val workingCount: Int)

/**
 * The chosen direction and the state that belongs to it: whether switching is on, the folds the
 * person has opened, and the folding and answer rules. A single object, because the overview is
 * a single overlay.
 *
 * Called by `AgentsOverview.kt` while it draws, and by its key handler to switch.
 *
 * @see OverviewDirection
 */
internal object OverviewDirections {
    private const val STORAGE_KEY = "lunamux.overview.direction"
    private val PARAM = Regex("[?&]ao=([^&#]*)")

    /** How many finished members in a row make a fold; fewer are drawn as they are. */
    const val FOLD_MIN = 3

    /** The direction being drawn. [OverviewDirection.TODAY] until [load] finds a request for another. */
    var current: OverviewDirection = OverviewDirection.TODAY
        private set

    /**
     * Whether the person asked for directions, by `?ao=` or by an earlier choice. While false the
     * overview is drawn as it always was, with no footer tag and no ⌥ keys.
     */
    var switching: Boolean = false
        private set

    private val openFolds = mutableSetOf<String>()

    /**
     * Read the requested direction: `?ao=` in the address first, else the one stored by an earlier
     * switch, else none. Called once when the topbar chip is built.
     *
     * @see select
     */
    fun load() {
        val fromAddress = PARAM.find(window.location.search)?.groupValues?.get(1)
        val requested = OverviewDirection.values().firstOrNull { it.key == fromAddress?.lowercase() }
            ?: OverviewDirection.values().firstOrNull { it.key == stored() }.takeIf { fromAddress == null }
        switching = fromAddress != null || stored() != null
        current = requested ?: OverviewDirection.TODAY
    }

    /**
     * Switch to [direction] and remember it in this browser, turning switching on.
     *
     * @param direction the direction to draw from now on.
     */
    fun select(direction: OverviewDirection) {
        current = direction
        switching = true
        try {
            localStorage.setItem(STORAGE_KEY, direction.key)
        } catch (_: Throwable) {
            // Storage can be blocked; the choice then lasts until the page is reloaded.
        }
    }

    /**
     * The direction a ⌥ digit key asks for.
     *
     * @param code the key event's `code` (`Digit0` to `Digit3`); the produced character is not
     *   used because ⌥ changes it on a Mac.
     * @return the direction, or null for any other key.
     */
    fun forCode(code: String): OverviewDirection? = when (code) {
        "Digit0" -> OverviewDirection.TODAY
        "Digit1" -> OverviewDirection.QUIET_TABLE
        "Digit2" -> OverviewDirection.ANSWER_FIRST
        "Digit3" -> OverviewDirection.NARROW_FEED
        else -> null
    }

    /**
     * Show the finished members that [taskId]'s fold hides. They stay shown until the overlay closes.
     *
     * @param taskId the task whose fold was clicked.
     * @see forgetFolds
     */
    fun openFold(taskId: String) {
        openFolds += taskId
    }

    /** Fold everything again; called when the overlay closes so the next opening starts folded. */
    fun forgetFolds() {
        openFolds.clear()
    }

    /**
     * Split [rows] into drawn units. In a direction other than [OverviewDirection.TODAY], at least
     * [FOLD_MIN] consecutive finished members of one task become a single [ListItem.Fold], because
     * a long run of "done" lines says the same thing once. A run stays unfolded while the person
     * has opened it and while the selection is inside it, so a row that ↑ and ↓ reach is always
     * on screen. Search results are never folded.
     *
     * @param rows the rows of one section, in order.
     * @param state the view model's state, for the selection, the task panels and search mode.
     * @return the units to draw, covering every row of [rows] once and in order.
     */
    fun plan(rows: List<Row>, state: AgentsOverviewBackingViewModel.State): List<ListItem> {
        if (current == OverviewDirection.TODAY || state.matchMode) return rows.map { ListItem.One(it) }
        val out = mutableListOf<ListItem>()
        var i = 0
        while (i < rows.size) {
            val head = rows[i]
            if (!foldable(head, state)) {
                out += ListItem.One(head)
                i++
                continue
            }
            var j = i
            while (j < rows.size && foldable(rows[j], state) && rows[j].taskId == head.taskId) j++
            val run = rows.subList(i, j)
            val keep = run.size < FOLD_MIN || head.taskId in openFolds || run.any { it.id == state.selectedId }
            if (keep) run.forEach { out += ListItem.One(it) } else out += ListItem.Fold(run)
            i = j
        }
        return out
    }

    /**
     * What the overview says right now, from the same counts the topbar chip reads.
     *
     * @param state the view model's state.
     * @return the sessions that need the person and the two counts.
     */
    fun answer(state: AgentsOverviewBackingViewModel.State): Answer =
        Answer(
            needs = state.rows.filter { it.kind == RowKind.SESSION && it.glyph == Glyph.NEEDS_YOU },
            needCount = state.needYouCount,
            workingCount = state.workingCount,
        )

    private fun foldable(row: Row, state: AgentsOverviewBackingViewModel.State): Boolean =
        row.kind == RowKind.SESSION && row.glyph == Glyph.DONE &&
            row.taskId != null && state.tasks.containsKey(row.taskId)

    private fun stored(): String? = try {
        localStorage.getItem(STORAGE_KEY)
    } catch (_: Throwable) {
        null
    }
}
