/**
 * What the agents overview's peek shows of a pane's screen: which rows, and in which styles.
 *
 * The peek is a small window on a terminal, so it shows the bottom of the screen, where an agent's
 * latest reply, its prompt and its footer are. Blank rows stay, since on an agent's screen they are
 * what separates one item from the next, but a run of them counts as one. The window opens at the
 * start of an item (after a blank row) rather than in the middle of one, and a live pane's rows keep
 * the colours, weight and slant the terminal draws them with, so a reply, a tool call, a dim hint
 * and a suggested prompt read apart as they do in the pane.
 *
 * This file contains [PeekScreen].
 */
package se.soderbjorn.lunamux

import com.termux.terminal.TextStyle
import se.soderbjorn.lunamux.pty.StyledRun

/** Picks and converts the rows of a screen for [AgentSessionSearch.inspect]. */
internal object PeekScreen {
    /** The most rows a peek shows. */
    const val ROWS = 20

    /**
     * The rows of [rows] a peek shows: blank rows squeezed to one and none at either end, the last
     * [max] of them, opening after the first blank row when the window would otherwise begin inside
     * an item. An item longer than half the window is shown from wherever the window starts.
     *
     * @param rows the screen's rows, top first.
     * @param blank whether a row is blank.
     * @param max the most rows kept.
     * @return the rows to show, top first.
     */
    fun <T> window(rows: List<T>, blank: (T) -> Boolean, max: Int = ROWS): List<T> {
        val squeezed = ArrayList<T>()
        for (row in rows) {
            if (blank(row) && (squeezed.isEmpty() || blank(squeezed.last()))) continue
            squeezed += row
        }
        while (squeezed.isNotEmpty() && blank(squeezed.last())) squeezed.removeAt(squeezed.lastIndex)
        if (squeezed.size <= max) return squeezed
        val start = squeezed.size - max
        // The row before the window is blank: it already opens at an item.
        if (blank(squeezed[start - 1])) return squeezed.subList(start, squeezed.size).toList()
        val tail = squeezed.subList(start, squeezed.size)
        val gap = tail.indexOfFirst(blank)
        return (if (gap in 0 until max / 2) tail.subList(gap + 1, tail.size) else tail).toList()
    }

    /**
     * A live screen's rows for the peek, plain and styled alike.
     *
     * @param rows the pane's rows as the server's grid holds them ([se.soderbjorn.lunamux.pty.SessionGrid.screenRows]).
     * @return the plain lines and the styled rows, the same rows in the same order.
     */
    fun styled(rows: List<List<StyledRun>>): Pair<List<String>, List<List<ScreenRun>>> {
        val shown = window(rows, { row -> row.all { it.text.isBlank() && isPlain(it.style) } })
        return shown.map { row -> row.joinToString("") { it.text }.trimEnd() } to shown.map { row -> row.map(::runOf) }
    }

    /**
     * A screen kept as text (a closed conversation's, or a session with no styled grid) for the peek.
     *
     * @param text the screen text, one row per line.
     * @return the lines to show.
     */
    fun plain(text: String?): List<String> = window(text?.lines().orEmpty().map(String::trimEnd), String::isBlank)

    /** Whether a style draws a space as nothing: no background of its own and no inverse. */
    private fun isPlain(style: Long): Boolean =
        TextStyle.decodeBackColor(style) == TextStyle.COLOR_INDEX_BACKGROUND &&
            TextStyle.decodeEffect(style) and TextStyle.CHARACTER_ATTRIBUTE_INVERSE == 0

    /** One grid run as the peek's wire run. */
    private fun runOf(run: StyledRun): ScreenRun {
        val effect = TextStyle.decodeEffect(run.style)
        fun has(bit: Int) = effect and bit != 0
        return ScreenRun(
            text = if (has(TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE)) " ".repeat(run.text.length) else run.text,
            fg = colorOf(TextStyle.decodeForeColor(run.style)),
            bg = colorOf(TextStyle.decodeBackColor(run.style)),
            bold = has(TextStyle.CHARACTER_ATTRIBUTE_BOLD),
            dim = has(TextStyle.CHARACTER_ATTRIBUTE_DIM),
            italic = has(TextStyle.CHARACTER_ATTRIBUTE_ITALIC),
            underline = has(TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE),
            strike = has(TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH),
            inverse = has(TextStyle.CHARACTER_ATTRIBUTE_INVERSE),
        )
    }

    /** A grid colour as the wire's: null for a default, the palette index, or [ScreenRun.RGB] plus the true colour. */
    private fun colorOf(color: Int): Int? = when {
        color and TRUECOLOR == TRUECOLOR -> ScreenRun.RGB or (color and 0xffffff)
        color in 0..255 -> color
        else -> null
    }

    /** The high byte the grid marks a true colour with. */
    private const val TRUECOLOR = 0xff000000.toInt()
}
