/**
 * Immutable, style-resolved snapshot of a live terminal screen, plus the
 * extraction that produces one from a headless Termux emulator.
 *
 * A [TerminalFrame] is what a terminal thumbnail draws: the current screen
 * buffer (never scrollback) as rows of same-style runs whose colors are
 * already resolved to ARGB — palette lookups, bright-bold, inverse video and
 * dim are applied here, at snapshot time, so the renderer is a dumb painter.
 * Snapshots are deep-immutable and safe to hand across threads; the mutable
 * emulator is only touched inside [snapshotFrame], which the caller must run
 * on the emulator's own dispatcher under its lock (see [MiniTerminalRegistry]).
 *
 * The run extraction mirrors the server's `GridSerializer.rowRuns` (walk
 * columns, split when the packed style long changes, read cell text through
 * `findStartOfColumn` so surrogates/combining marks/wide glyphs stay intact,
 * trim trailing default-styled blanks). The color resolution is a port of the
 * vendored `TerminalRenderer.drawTextRun` (terminal-view) — ported rather than
 * referenced because that code is welded to its Canvas pass and vendored files
 * stay unmodified.
 *
 * @see MiniTerminalRegistry
 * @see TerminalThumbnail
 */
package se.soderbjorn.lunamux.android.ui

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/**
 * A run of consecutive same-style cells within one screen row.
 *
 * Produced by [snapshotFrame]; drawn by [TerminalThumbnail]. Colors are fully
 * resolved ARGB — no palette indices survive past extraction.
 *
 * @property startCol  0-based grid column the run starts at (background rects
 *   and text are both anchored here, so grid alignment survives any glyph
 *   advance drift inside the run).
 * @property widthCols number of grid cells the run covers (wide glyphs count
 *   their real cell width).
 * @property text      the run's characters; empty for an SGR-invisible run,
 *   whose background still paints.
 * @property fg        resolved ARGB foreground (bright-bold, inverse and dim
 *   already applied).
 * @property bg        resolved ARGB background (inverse already applied).
 * @property bold      whether to draw with fake-bold.
 * @property underline whether to underline.
 */
data class ThumbRun(
    val startCol: Int,
    val widthCols: Int,
    val text: String,
    val fg: Int,
    val bg: Int,
    val bold: Boolean,
    val underline: Boolean,
)

/**
 * One immutable snapshot of a live terminal screen (current buffer only — for
 * an alt-screen TUI this is the alt screen, which is exactly what the session
 * looks like).
 *
 * @property cols        the grid width the frame was captured at (the server's
 *   authoritative PTY width — a thumbnail never votes its own).
 * @property rows        the grid height the frame was captured at.
 * @property defaultBg   resolved ARGB default background; fills the letterbox
 *   and every cell no run covers.
 * @property lines       exactly [rows] entries; each row's runs are ordered by
 *   [ThumbRun.startCol] with trailing default-styled blanks trimmed (an empty
 *   list is a blank row).
 * @property cursorRow   cursor row, or -1 when the cursor is hidden (DECTCEM).
 * @property cursorCol   cursor column (meaningless when [cursorRow] is -1).
 * @property cursorColor resolved ARGB cursor color.
 * @property revision    monotonic per-session counter; makes [equals] cheap for
 *   StateFlow conflation and stamps frames for debugging.
 */
data class TerminalFrame(
    val cols: Int,
    val rows: Int,
    val defaultBg: Int,
    val lines: List<List<ThumbRun>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorColor: Int,
    val revision: Long,
) {
    /** Cheap equality via [revision]: two frames of one session never share a revision. */
    override fun equals(other: Any?): Boolean =
        other is TerminalFrame && other.revision == revision && other.cols == cols && other.rows == rows

    override fun hashCode(): Int = revision.hashCode()
}

/**
 * Snapshot [emulator]'s current screen into an immutable [TerminalFrame].
 *
 * MUST be called on the emulator's single-thread dispatcher while holding the
 * emulator lock — it reads live rows that the PTY event collector mutates.
 * Called only by [MiniTerminalRegistry]'s per-entry publisher.
 *
 * @param emulator the headless, externally-fed emulator to read.
 * @param revision monotonic stamp for the produced frame.
 * @return the resolved snapshot; rows out of bounds degrade to blank rather
 *   than throwing (a read-only observer must never take the preview down).
 */
internal fun snapshotFrame(emulator: TerminalEmulator, revision: Long): TerminalFrame {
    val palette = emulator.mColors.mCurrentColors
    val defaultBg = palette[TextStyle.COLOR_INDEX_BACKGROUND]
    val rows = emulator.mRows
    val cols = emulator.mColumns
    val screen = emulator.screen
    val lines = ArrayList<List<ThumbRun>>(rows)
    for (y in 0 until rows) {
        val row = runCatching { screen.getRow(y) }.getOrNull()
        lines.add(if (row == null) emptyList() else rowRuns(row, cols, palette, defaultBg))
    }
    val cursorVisible = emulator.shouldCursorBeVisible()
    return TerminalFrame(
        cols = cols,
        rows = rows,
        defaultBg = defaultBg,
        lines = lines,
        cursorRow = if (cursorVisible) emulator.cursorRow else -1,
        cursorCol = emulator.cursorCol,
        cursorColor = palette[TextStyle.COLOR_INDEX_CURSOR],
        revision = revision,
    )
}

/**
 * Convert one screen row into resolved [ThumbRun]s: walk columns, split when
 * the packed style changes, trim trailing default-styled blanks (padding, not
 * content — a styled trailing space such as a colored status bar is kept).
 *
 * @param row       the live row to read.
 * @param cols      the emulator's width, clamped to what the row actually has
 *   (a mid-reflow row can be narrower; degrade to a short row, never throw).
 * @param palette   the emulator's current 256+3 color table.
 * @param defaultBg resolved default background, for the trailing-blank rule.
 * @return the row's runs, empty for a blank row.
 */
private fun rowRuns(row: TerminalRow, cols: Int, palette: IntArray, defaultBg: Int): List<ThumbRun> {
    val width = minOf(cols, row.columnCount)
    val lastCol = lastContentColumn(row, width)
    if (lastCol < 0) return emptyList()
    val runs = ArrayList<ThumbRun>()
    val sb = StringBuilder()
    var runStyle = row.getStyle(0)
    var runStartCol = 0
    var col = 0
    while (col <= lastCol) {
        val style = row.getStyle(col)
        if (style != runStyle && sb.isNotEmpty()) {
            runs.add(resolveRun(runStartCol, col - runStartCol, sb.toString(), runStyle, palette))
            sb.setLength(0)
            runStartCol = col
        }
        runStyle = style
        col += emitCell(sb, row, col, width)
    }
    if (sb.isNotEmpty()) {
        runs.add(resolveRun(runStartCol, col - runStartCol, sb.toString(), runStyle, palette))
    }
    return runs
}

/**
 * Resolve one run's packed style long into a [ThumbRun] with final ARGB
 * colors. Port of the color pipeline in `TerminalRenderer.drawTextRun`
 * (terminal-view): indexed → palette with bright-bold promotion, inverse
 * fg/bg swap, xterm dim (×2/3 RGB), invisible → empty text.
 *
 * @param startCol  grid column the run starts at.
 * @param widthCols grid cells the run covers.
 * @param text      the run's characters.
 * @param style     the packed Termux style long shared by every cell in the run.
 * @param palette   the emulator's current color table.
 * @return the resolved run.
 */
private fun resolveRun(startCol: Int, widthCols: Int, text: String, style: Long, palette: IntArray): ThumbRun {
    var fg = TextStyle.decodeForeColor(style)
    var bg = TextStyle.decodeBackColor(style)
    val effect = TextStyle.decodeEffect(style)
    val bold = (effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0
    val underline = (effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0
    val invisible = (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0
    if ((fg and -0x1000000) != -0x1000000) {
        // Indexed color; bold promotes the first 8 to their bright variants.
        if (bold && fg in 0..7) fg += 8
        fg = palette[fg]
    }
    if ((bg and -0x1000000) != -0x1000000) {
        bg = palette[bg]
    }
    if ((effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0) {
        val tmp = fg
        fg = bg
        bg = tmp
    }
    if ((effect and TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0) {
        // xterm/libvte dim: scale each channel to 2/3.
        val red = (fg shr 16 and 0xFF) * 2 / 3
        val green = (fg shr 8 and 0xFF) * 2 / 3
        val blue = (fg and 0xFF) * 2 / 3
        fg = -0x1000000 + (red shl 16) + (green shl 8) + blue
    }
    return ThumbRun(
        startCol = startCol,
        widthCols = widthCols,
        text = if (invisible) "" else text,
        fg = fg,
        bg = bg,
        bold = bold,
        underline = underline,
    )
}

/**
 * Append the cell at [col] to [sb] and return its width in columns. Reads the
 * cell's chars through `findStartOfColumn` so surrogate pairs, combining marks
 * and wide glyphs come out intact (same walk as the server's serializer).
 *
 * @param sb    receives the cell's characters (a space for a zero-length cell).
 * @param row   the row being read.
 * @param col   the cell's column.
 * @param width the row's readable width, for clamping the end lookup.
 * @return the number of columns the cell spans (≥ 1).
 */
private fun emitCell(sb: StringBuilder, row: TerminalRow, col: Int, width: Int): Int {
    val text = row.mText
    val start = row.findStartOfColumn(col)
    val cp = Character.codePointAt(text, start)
    var w = WcWidth.width(cp)
    if (w < 1) w = 1
    val end = row.findStartOfColumn(minOf(col + w, width))
    val len = end - start
    if (len <= 0) {
        sb.append(' ')
    } else {
        sb.append(String(text, start, len))
    }
    return w
}

/**
 * Last column (0-based) that is not a default-styled space; -1 if the row is
 * entirely blank. Mirrors the server serializer's trailing-blank rule.
 *
 * @param row   the row to scan.
 * @param width the row's readable width.
 * @return the last content column, or -1.
 */
private fun lastContentColumn(row: TerminalRow, width: Int): Int {
    var c = width - 1
    while (c >= 0) {
        val cp = Character.codePointAt(row.mText, row.findStartOfColumn(c))
        if (cp != ' '.code || !isDefaultStyle(row.getStyle(c))) return c
        c--
    }
    return -1
}

/** Whether [style] is the default style (default fg/bg indices, no effects). */
private fun isDefaultStyle(style: Long): Boolean =
    TextStyle.decodeForeColor(style) == TextStyle.COLOR_INDEX_FOREGROUND &&
        TextStyle.decodeBackColor(style) == TextStyle.COLOR_INDEX_BACKGROUND &&
        TextStyle.decodeEffect(style) == 0
