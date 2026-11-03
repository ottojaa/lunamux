/**
 * The pure rules behind the agents overview's row cues: the order rows keep (by when their work
 * started, never by what state they are in), the shared words of titles that belong to one thread of
 * work, and the glyph that says which part of its tab a pane fills.
 *
 * All are plain functions over session summaries, titles and rectangles so they can be tested
 * without a view model; [AgentsOverviewBackingViewModel] calls the first two while it builds the
 * list, and the web layer calls [placeGlyph] when it works out where each pane sits.
 *
 * @see AgentsOverviewBackingViewModel.Row.dimPrefix
 * @see AgentsOverviewBackingViewModel.Row.place
 * @see AgentsOverviewBackingViewModel.Who
 */
package se.soderbjorn.lunamux.client.viewmodel

import se.soderbjorn.lunamux.AgentSessionSummary

/** The value of [AgentSessionSummary.startedBy] for a session a person typed the first line of. */
internal const val STARTED_BY_USER = "user"

/** The value of [AgentSessionSummary.startedBy] for a session an MCP tool typed the first line of. */
internal const val STARTED_BY_TOOL = "tool"

/**
 * When a session's work began, which never moves: the time its record was created.
 *
 * An older server does not send it; the time the session entered its current state stands in then,
 * which only moves the row when its state changes.
 *
 * Called by the view model to order NOW and the members of a task.
 *
 * @param s the session.
 * @return epoch millis.
 * @see AgentSessionSummary.startedAt
 */
internal fun startOf(s: AgentSessionSummary): Long = s.startedAt ?: s.stateSince

/**
 * Who started a session, read from the value the server stores.
 *
 * Called by the view model for every session row.
 *
 * @param startedBy [AgentSessionSummary.startedBy]: `user`, `tool`, or null for a record that has none.
 * @return [AgentsOverviewBackingViewModel.Who.USER] or [AgentsOverviewBackingViewModel.Who.TOOL] for
 *   the two known values, else [AgentsOverviewBackingViewModel.Who.NONE], so an unknown value from a
 *   newer server draws nothing rather than a wrong cue.
 */
internal fun whoOf(startedBy: String?): AgentsOverviewBackingViewModel.Who = when (startedBy) {
    STARTED_BY_USER -> AgentsOverviewBackingViewModel.Who.USER
    STARTED_BY_TOOL -> AgentsOverviewBackingViewModel.Who.TOOL
    else -> AgentsOverviewBackingViewModel.Who.NONE
}

/**
 * How many leading characters of each title are drawn dim, so the words that tell the rows of one
 * thread apart stand out.
 *
 * For each title, the longest run of whole leading words it shares with the title of another row of
 * the same thread, as long as at least one word is left after it. Words are the whitespace-separated
 * pieces, compared exactly: "export:" and "export" are different words. A row with no thread, or
 * alone in its thread, has nothing to share. Titles of different threads never count against each
 * other, whatever they say.
 *
 * Called by the view model on the rows it is about to list, in list order.
 *
 * @param threads for each row, the key of the thread of work it belongs to, or null for none.
 * @param titles for each row, its title; the same length and order as [threads].
 * @return for each row, the length in characters of the dim prefix: the end of the last shared word
 *   plus the space after it, so the rest starts at a letter; 0 when nothing is shared.
 * @see AgentsOverviewBackingViewModel.Row.dimPrefix
 */
internal fun dimPrefixLengths(threads: List<String?>, titles: List<String>): List<Int> {
    val words = titles.map { wordsOf(it) }
    val byThread = threads.indices.filter { threads[it] != null }.groupBy { threads[it] }
    val out = IntArray(titles.size)
    for (members in byThread.values) {
        if (members.size < 2) continue
        for (i in members) {
            val mine = words[i]
            var shared = 0
            for (j in members) {
                if (j == i) continue
                shared = maxOf(shared, commonLeadingWords(mine.map { it.text }, words[j].map { it.text }))
            }
            // At least one word must stay bold, or the whole row would read as dim.
            shared = minOf(shared, mine.size - 1)
            if (shared > 0) out[i] = mine[shared].start
        }
    }
    return out.toList()
}

/**
 * The glyph that says which part of its tab a pane fills, from the pane's rectangle.
 *
 * A pane that fills (nearly) the whole tab has no glyph. A pane that fills (nearly) one full side
 * and about half of the other is a half: `▌` left, `▐` right, `▀` top, `▄` bottom. A pane about
 * half as wide and half as tall is a quarter: `▘` top-left, `▝` top-right, `▖` bottom-left, `▗`
 * bottom-right. Any other shape (a third, a sliver, a floating window) takes the quarter its centre
 * lies in, so every pane gets some answer; a centre exactly on a middle line counts as the right
 * or bottom side.
 *
 * Called by the web layer's place calculation for each pane of a tab, with the rectangle from the
 * toolkit's layout or the pane's own stored geometry.
 *
 * @param x left edge as a fraction of the tab's width.
 * @param y top edge as a fraction of the tab's height.
 * @param width width as a fraction of the tab's width.
 * @param height height as a fraction of the tab's height.
 * @return the empty string for a pane that fills its tab, else one glyph.
 * @see AgentsOverviewBackingViewModel.Row.place
 */
fun placeGlyph(x: Double, y: Double, width: Double, height: Double): String {
    val wide = width >= FILLS
    val tall = height >= FILLS
    val halfWide = width in HALF_LOW..HALF_HIGH
    val halfTall = height in HALF_LOW..HALF_HIGH
    val cx = x + width / 2
    val cy = y + height / 2
    val right = cx >= 0.5
    val bottom = cy >= 0.5
    return when {
        wide && tall -> ""
        tall && halfWide -> if (right) "▐" else "▌"
        wide && halfTall -> if (bottom) "▄" else "▀"
        else -> if (bottom) (if (right) "▗" else "▖") else (if (right) "▝" else "▘")
    }
}

/** The share of a tab's width or height from which a pane counts as filling it. */
private const val FILLS = 0.9

/** The range of shares around one half in which a side counts as half of the tab. */
private const val HALF_LOW = 0.4
private const val HALF_HIGH = 0.6

/** One whitespace-separated piece of a title and where it begins in it. */
private class Word(val text: String, val start: Int)

/** Split [title] into [Word]s, keeping where each begins. */
private fun wordsOf(title: String): List<Word> {
    val out = ArrayList<Word>()
    var i = 0
    while (i < title.length) {
        while (i < title.length && title[i].isWhitespace()) i++
        if (i >= title.length) break
        val start = i
        while (i < title.length && !title[i].isWhitespace()) i++
        out += Word(title.substring(start, i), start)
    }
    return out
}

/** How many words [a] and [b] share from their start. */
private fun commonLeadingWords(a: List<String>, b: List<String>): Int {
    var n = 0
    while (n < a.size && n < b.size && a[n] == b[n]) n++
    return n
}
