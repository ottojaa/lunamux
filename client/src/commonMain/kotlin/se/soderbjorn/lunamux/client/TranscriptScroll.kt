/**
 * Where a client's terminal view should be scrolled after a chunk of session output.
 *
 * This file contains [TranscriptScroll], the rule a renderer applies once new bytes have been
 * appended to its emulator: follow the bottom, hold the user's reading position, or snap back
 * to the prompt because the transcript underneath them was rewritten.
 *
 * Why it is here rather than in the Android screen that uses it: `androidApp` has no test
 * source set, and the rule needs no Android types — the same reasoning that put
 * [MirrorFit] in this module. The caller supplies three numbers it reads off its own view and
 * emulator; nothing here knows what a view is.
 *
 * ## The bug this exists to prevent
 *
 * A resize resync (the server's `RIS` + `ED3` + history + screen redraw, emitted whenever the
 * PTY's *columns* change) arrives on the same path as ordinary output. Treated as ordinary
 * output it is catastrophic for the scroll position: it scrolls thousands of rows, so the
 * "keep the user where they were reading" arithmetic below lands them at the very TOP of the
 * scrollback, and a separate reconnect-restore then drags them to a stale offset some rows
 * ABOVE the prompt. Both heuristics are correct for replaying the *same* content after a
 * reconnect and wrong for a resync, where the content has been re-laid-out at a new width and
 * every old offset is meaningless. That was the "take over and it never lands on the input
 * field" defect.
 *
 * @see se.soderbjorn.lunamux.client.MirrorFit the other pure client-side geometry rule
 */
package se.soderbjorn.lunamux.client

/**
 * What the renderer should do with its vertical scroll offset after appending a chunk.
 *
 * @see TranscriptScroll.decide
 */
sealed interface ScrollAction {

    /**
     * Snap to the bottom and forget any pending restore: the transcript was rewritten, so no
     * offset into the old one means anything. Emitted for a width resync, and the reason a
     * take-over now lands on the prompt.
     */
    data object Bottom : ScrollAction

    /**
     * The view was already following the bottom — let it keep following. The caller's
     * `onScreenUpdated()` does this by itself; the case is named so the rule is total.
     */
    data object Follow : ScrollAction

    /**
     * The user is reading back through history: put them back where they were, shifted up by
     * the rows that scrolled underneath them so the text they are looking at stays put.
     *
     * @property topRow the offset to restore, `<= 0`, already clamped to the transcript.
     */
    data class Hold(val topRow: Int) : ScrollAction
}

/**
 * The scroll rule, as one pure decision.
 *
 * Called by the Android terminal screen's output collector once per appended chunk, from the
 * `view.post` that repaints. Kept separate from the view so the width-resync case can be
 * pinned by a test — on device it only shows up with a long buffer and the user scrolled up,
 * which is precisely when it is hardest to catch.
 *
 * @see ScrollAction
 */
object TranscriptScroll {

    /**
     * Decide where the view should sit after a chunk has been appended.
     *
     * @param topRowBefore the view's offset before the append: `0` at the bottom, negative
     *   when the user has scrolled up into history.
     * @param scrollCounter rows the emulator scrolled while appending this chunk
     *   (`TerminalEmulator.getScrollCounter()`, read before the repaint clears it).
     * @param transcriptRows rows of history the emulator holds after the append
     *   (`activeTranscriptRows`) — the clamp for a held offset.
     * @param widthResync true when this chunk is the server's resize resync, i.e. a
     *   cols-changing `Size` has landed and this is the `RIS`-bearing redraw that answers it.
     *   Overrides everything else: the content is new, so the only sane place is the bottom.
     * @return what to do with the scroll offset.
     */
    fun decide(
        topRowBefore: Int,
        scrollCounter: Int,
        transcriptRows: Int,
        widthResync: Boolean,
    ): ScrollAction = when {
        // A rewritten transcript outranks a reading position into the old one — including
        // when the user was scrolled up, which is exactly the case the old code got wrong.
        widthResync -> ScrollAction.Bottom
        topRowBefore >= 0 -> ScrollAction.Follow
        else -> ScrollAction.Hold(
            (topRowBefore - scrollCounter).coerceIn(-transcriptRows.coerceAtLeast(0), 0),
        )
    }
}
