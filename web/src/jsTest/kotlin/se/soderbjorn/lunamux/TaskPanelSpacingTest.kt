/**
 * A browser test of the task panel's Recent activity block, laid out with the page's own stylesheet.
 *
 * An entry that wraps onto three lines must be followed by a gap of at least one line of text
 * before the next entry, at the panel's width and at a narrower one, so two entries never run
 * together. The test draws the block with the real builder, puts it in a column of the real
 * `task-panel` rules and measures the boxes the browser lays out.
 *
 * Contains [TaskPanelSpacingTest].
 *
 * @see buildActivity
 */
package se.soderbjorn.lunamux

import kotlinx.browser.document
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunamux.client.viewmodel.TaskPanelViewModel

/** Tests for the space between two entries of the panel's Recent activity block. */
class TaskPanelSpacingTest {

    private var style: HTMLElement? = null
    private var panel: HTMLElement? = null

    /** Loads the page's stylesheet into the test document. */
    @BeforeTest
    fun loadStyles() {
        val css = js("atob")(STYLES_CSS_BASE64) as String
        val element = document.createElement("style") as HTMLElement
        element.textContent = css
        document.head!!.appendChild(element)
        style = element
    }

    /** Removes what the test added to the document. */
    @AfterTest
    fun cleanUp() {
        style?.remove()
        panel?.remove()
    }

    /**
     * Draws the activity block in a panel column [width] px wide and returns the boxes of the
     * entries it holds.
     *
     * @param width the column's width in px.
     * @param entries the activity lines, newest first.
     * @return each entry's bounding box as (top, bottom), and the line height of the text in px.
     */
    private fun measure(width: Int, entries: List<String>): Pair<List<Pair<Double, Double>>, Double> {
        val column = document.createElement("aside") as HTMLElement
        column.className = "task-panel"
        column.style.width = "${width}px"
        column.style.flex = "none"
        val block = buildActivity(
            TaskPanelViewModel.Block(entries.map { TaskPanelViewModel.ActivityRow("5m", it) }, 0, entries.size),
        )
        column.appendChild(block)
        document.body!!.appendChild(column)
        panel = column
        val rows = column.querySelectorAll(".tp-tl")
        val boxes = (0 until rows.length).map {
            val rect = (rows.item(it) as HTMLElement).getBoundingClientRect()
            rect.top to rect.bottom
        }
        val lineHeight = js("parseFloat")(js("getComputedStyle")(rows.item(0)).lineHeight) as Double
        return boxes to lineHeight
    }

    private val longEntry =
        "Reviewer asked for the retry budget to be raised before the nightly job runs against the staging cluster again"
    private val shortEntry = "Release thread opened for sign-off"

    /** An entry that wraps onto three or more lines is followed by a gap of a full line. */
    @Test
    fun aWrappedEntryIsFollowedByAGapOfAtLeastOneLine() {
        for (width in listOf(320, 240)) {
            val (boxes, lineHeight) = measure(width, listOf(longEntry, shortEntry))
            val (firstTop, firstBottom) = boxes[0]
            val secondTop = boxes[1].first
            assertTrue(
                (firstBottom - firstTop) >= lineHeight * 3 - 1,
                "at $width px the first entry should wrap onto three lines or more, its height is ${firstBottom - firstTop}",
            )
            assertTrue(
                secondTop - firstBottom >= lineHeight - 1,
                "at $width px the gap is ${secondTop - firstBottom} px, under one line of $lineHeight px",
            )
            assertEquals(2, boxes.size)
        }
    }
}
