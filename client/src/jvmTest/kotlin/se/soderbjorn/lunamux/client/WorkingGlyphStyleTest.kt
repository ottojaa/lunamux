/**
 * Holds the box and the weight of the agents overview's working glyph in place by reading its CSS
 * rule out of the web module's `styles.css`.
 *
 * There is no browser test setup for the web module, and what breaks the glyph is a style, not
 * code: the glyph is a braille pair drawn in a monospace face with no braille, so the system falls
 * back to Apple Braille, and inheriting the bold weight of the word beside it (the state cell of a
 * working row is bold) picks that font's outlined six-dot face, which draws a ring at every dot
 * the pair leaves unlit. These tests fail when the `.ao-work` rule stops saying its own weight or
 * stops being as high as a row's line.
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

    /** The glyph's box is as high as a row's line, so it never grows its row. */
    @Test
    fun glyphBoxIsAsHighAsARowLine() {
        val rule = glyphRule()
        assertEquals("20px", rule["height"])
        assertEquals(rule["height"], rule["line-height"])
        assertEquals("2ch", rule["width"])
    }

    /** The row's line is 20px, the height the glyph's box is held to above. */
    @Test
    fun rowLineIsTheGlyphBoxHeight() {
        val css = stylesheet()
        val row = Regex("""(?m)^\.ao-row\s*\{([^}]*)}""").find(css)?.groupValues?.get(1)
        assertTrue(row != null && Regex("""line-height:\s*20px""").containsMatchIn(row), "`.ao-row` line is not 20px")
    }
}
