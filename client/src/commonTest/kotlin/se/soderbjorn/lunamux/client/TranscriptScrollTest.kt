/**
 * Tests for [TranscriptScroll] — where the view lands after a chunk of output.
 *
 * The numbers are the on-device ones: a phone mirroring a 143-column laptop, scrolled a
 * screenful or so up, taking the session over. The resync that answers the take-over scrolls
 * thousands of rows, and it is that magnitude — not the sign — that used to send the view to
 * the top of the scrollback.
 */
package se.soderbjorn.lunamux.client

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptScrollTest {

    @Test
    fun `at the bottom it keeps following`() {
        assertEquals(
            ScrollAction.Follow,
            TranscriptScroll.decide(
                topRowBefore = 0,
                scrollCounter = 12,
                transcriptRows = 4000,
                widthResync = false,
            ),
        )
    }

    @Test
    fun `scrolled up it holds the reading position, shifted by what scrolled under it`() {
        assertEquals(
            ScrollAction.Hold(-42),
            TranscriptScroll.decide(
                topRowBefore = -30,
                scrollCounter = 12,
                transcriptRows = 4000,
                widthResync = false,
            ),
        )
    }

    @Test
    fun `a held offset cannot run off the end of the transcript`() {
        assertEquals(
            ScrollAction.Hold(-100),
            TranscriptScroll.decide(
                topRowBefore = -30,
                scrollCounter = 9_000,
                transcriptRows = 100,
                widthResync = false,
            ),
        )
    }

    /**
     * The defect this class exists for. Without the [widthResync] override the hold
     * arithmetic gives `-30 - 8000`, clamped to `-8152` — the very top of the scrollback —
     * and the user watches ancient history instead of the prompt they just took over to type
     * at.
     */
    @Test
    fun `a width resync goes to the bottom even from deep in the scrollback`() {
        assertEquals(
            ScrollAction.Bottom,
            TranscriptScroll.decide(
                topRowBefore = -30,
                scrollCounter = 8_000,
                transcriptRows = 8_152,
                widthResync = true,
            ),
        )
    }

    @Test
    fun `a width resync from the bottom is still the bottom`() {
        assertEquals(
            ScrollAction.Bottom,
            TranscriptScroll.decide(
                topRowBefore = 0,
                scrollCounter = 8_000,
                transcriptRows = 8_152,
                widthResync = true,
            ),
        )
    }

    /** An empty transcript must not produce a positive clamp bound. */
    @Test
    fun `no history holds at the bottom`() {
        assertEquals(
            ScrollAction.Hold(0),
            TranscriptScroll.decide(
                topRowBefore = -5,
                scrollCounter = 3,
                transcriptRows = 0,
                widthResync = false,
            ),
        )
    }
}
