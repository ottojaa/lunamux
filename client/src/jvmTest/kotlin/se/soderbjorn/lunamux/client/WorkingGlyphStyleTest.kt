/**
 * Holds the box and the weight of the agents overview's working glyph in place by reading its CSS
 * rule out of the web module's `styles.css`.
 *
 * What breaks the glyph is a style, not
 * code: the glyph is a braille pair drawn in a monospace face with no braille, so the system falls
 * back to Apple Braille, and inheriting the bold weight of the word beside it (the state cell of a
 * working row is bold) picks that font's outlined six-dot face, which draws a ring at every dot
 * the pair leaves unlit. These tests fail when the `.ao-work` rule stops saying its own weight or
 * stops being the box it was first drawn in, kept inside the height of its row. The frames
 * themselves are read out of `WorkingGlyph.kt` and pinned too: four frames of two lit dots each.
 *
 * @see se.soderbjorn.lunamux.WorkingGlyphSpec
 */
package se.soderbjorn.lunamux.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the `.ao-work` rule of `web/src/jsMain/resources/styles.css`. */
class WorkingGlyphStyleTest {

    /**
     * The text of the web module's `styles.css`.
     *
     * @return the stylesheet, read from the path in the `lunamux.stylesCss` system property.
     */
    private fun stylesheet(): String = File(System.getProperty("lunamux.stylesCss")).readText()

    /**
     * The declarations of the `.ao-work` rule, by property.
     *
     * Reads the stylesheet from the path the build passes in `lunamux.stylesCss` (see
     * `client/build.gradle.kts`, which also makes the stylesheet an input of the test task).
     *
     * @return property name to value, for the rule whose selector is exactly `.ao-work`.
     */
    private fun glyphRule(): Map<String, String> {
        val css = stylesheet()
        val body = Regex("""(?m)^\.ao-work\s*\{([^}]*)}""").find(css)?.groupValues?.get(1)
        assertTrue(body != null, "styles.css has no `.ao-work` rule")
        return body.split(';').mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i < 0) null else decl.substring(0, i).trim() to decl.substring(i + 1).trim()
        }.toMap()
    }

    /** The glyph is always drawn at regular weight, whatever bold word sits beside it. */
    @Test
    fun glyphKeepsItsOwnRegularWeight() {
        assertEquals("400", glyphRule()["font-weight"])
    }

    /**
     * The glyph's box is the 26px one it was first drawn in, centred on the row, and its 3px
     * negative margins take back the row's padding so it never grows the row.
     */
    @Test
    fun glyphBoxIsTheOriginalOneAndDoesNotGrowItsRow() {
        val rule = glyphRule()
        assertEquals("26px", rule["height"])
        assertEquals("26px", rule["line-height"])
        assertEquals("2ch", rule["width"])
        assertEquals("center", rule["text-align"])
        assertEquals("-3px 0", rule["margin"])
    }

    /** The row is a 20px line with 3px of padding above and below, which the glyph's margins give back. */
    @Test
    fun rowPaddingIsWhatTheGlyphMarginsGiveBack() {
        val css = stylesheet()
        val row = Regex("""(?m)^\.ao-row\s*\{([^}]*)}""").find(css)?.groupValues?.get(1)
        assertTrue(row != null && Regex("""line-height:\s*20px""").containsMatchIn(row), "`.ao-row` line is not 20px")
        assertTrue(Regex("""padding:\s*3px 14px""").containsMatchIn(row), "`.ao-row` padding is not 3px 14px")
    }

    /** The text of the web module's `WorkingGlyph.kt`, read from the path in `lunamux.workingGlyphSource`. */
    private fun glyphSource(): String = File(System.getProperty("lunamux.workingGlyphSource")).readText()

    /**
     * The glyph turns two braille dots through the middle rows in four frames, every frame lighting
     * exactly two of the six dots, 300 ms apart and fading over 400 ms. A change of the frames, or of
     * the timing, makes the working glyph look different and fails here.
     */
    @Test
    fun spinnerIsFourFramesOfTwoLitDotsEach() {
        val spec = Regex("""BRAILLE_PAIR_TURN\s*=\s*WorkingGlyphSpec\(frames = listOf\(([^)]*)\),\s*stepMs = (\d+),\s*fadeMs = (\d+)\)""")
            .find(glyphSource())
        assertTrue(spec != null, "WorkingGlyph.kt has no BRAILLE_PAIR_TURN spec")
        val frames = Regex(""""(.)"""").findAll(spec.groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(listOf("⠆", "⠒", "⠰", "⠤"), frames)
        assertEquals(listOf(2, 2, 2, 2), frames.map { Integer.bitCount(it[0].code - 0x2800) }, "two lit dots per frame")
        assertEquals("300", spec.groupValues[2])
        assertEquals("400", spec.groupValues[3])
    }
}
