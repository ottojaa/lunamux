/**
 * Tests for [PeekScreen]: which rows of a screen the agents overview's peek shows, and that a live
 * pane's rows keep the styles the terminal draws them with.
 *
 * @see PeekScreen
 */
package se.soderbjorn.lunamux

import se.soderbjorn.lunamux.pty.SessionGrid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeekScreenTest {
    private fun window(vararg rows: String, max: Int = 6) = PeekScreen.window(rows.toList(), String::isBlank, max)

    @Test
    fun `blank rows stay between items but a run of them counts as one, and none is kept at either end`() {
        assertEquals(listOf("● one", "", "● two", "  more"), window("", "● one", "", "", "", "● two", "  more", "", ""))
    }

    @Test
    fun `a window that would open inside an item opens at the next one`() {
        val rows = arrayOf("● first", "  first, more", "", "● second", "  second, more", "", "● third", "> prompt")
        assertEquals(listOf("● second", "  second, more", "", "● third", "> prompt"), window(*rows))
        assertEquals(listOf("● second", "  second, more", "", "● third", "> prompt"), window(*rows, max = 5), "a window already at an item keeps it")
    }

    @Test
    fun `an item longer than half the window is shown from wherever the window starts`() {
        val rows = arrayOf("● long", "a", "b", "c", "d", "", "> prompt")
        assertEquals(listOf("b", "c", "d", "", "> prompt"), window(*rows, max = 5))
    }

    @Test
    fun `a closed screen kept as text gets the same window`() {
        assertEquals(listOf("● done", "", "> next"), PeekScreen.plain("● done  \n\n\n> next\n\n"))
        assertEquals(emptyList(), PeekScreen.plain(null))
    }

    @Test
    fun `a live row keeps its colours, weight, slant and dimness`() {
        val grid = SessionGrid(40, 6)
        val paint = "\u001b[32m●\u001b[0m \u001b[1mBash\u001b[0m(ls)\r\n\r\n" +
            "\u001b[2;3mrecap: it is done\u001b[0m\r\n\r\n" +
            "\u001b[38;2;255;136;0m❯\u001b[0m \u001b[7ms\u001b[27m\u001b[90mtart the next item\u001b[0m"
        val bytes = paint.toByteArray()
        grid.feed(bytes, bytes.size)
        val (lines, styled) = PeekScreen.styled(grid.screenRows())
        assertEquals(listOf("● Bash(ls)", "", "recap: it is done", "", "❯ start the next item"), lines)
        assertEquals(lines.size, styled.size)
        val tool = styled[0]
        assertEquals(2, tool.first { it.text == "●" }.fg)
        assertTrue(tool.first { it.text == "Bash" }.bold)
        val recap = styled[2].first { it.text.startsWith("recap") }
        assertTrue(recap.dim && recap.italic)
        val prompt = styled[4]
        assertEquals(ScreenRun.RGB or 0xff8800, prompt.first { it.text == "❯" }.fg)
        assertTrue(prompt.first { it.text == "s" }.inverse)
        val ghost = prompt.first { it.text.startsWith("tart") }
        assertEquals(8, ghost.fg, "the suggested prompt is drawn grey, so it reads apart from typed text")
        assertFalse(ghost.bold)
        assertTrue(styled[1].isEmpty(), "a blank row has no runs")
    }
}
