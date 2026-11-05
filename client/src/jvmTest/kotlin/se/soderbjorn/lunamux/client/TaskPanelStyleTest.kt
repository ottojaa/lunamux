/**
 * Holds in place the spacing of the task panel's activity lines by reading the panel's rules out of
 * the web module's `styles.css`.
 *
 * An entry that wraps to three lines must be followed by a gap of at least one line before the next
 * entry, so two entries never run together at the panel's narrowest width. The rule that gives the
 * gap is checked here; the rendered result (a three-line entry above a one-line entry at 320 and
 * 240 px) is measured in a browser by `TaskPanelSpacingTest` in the web module's tests.
 *
 * Contains [TaskPanelStyleTest].
 *
 * @see WorkingGlyphStyleTest
 */
package se.soderbjorn.lunamux.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests for the `.tp-tl` rules of `web/src/jsMain/resources/styles.css`. */
class TaskPanelStyleTest {

    /** The declarations of the rule whose selector is exactly [selector], by property. */
    private fun rule(selector: String): Map<String, String> {
        val css = File(System.getProperty("lunamux.stylesCss")).readText()
        val body = Regex("""(?m)^${Regex.escape(selector)}\s*\{([^}]*)}""").find(css)?.groupValues?.get(1)
        assertTrue(body != null, "styles.css has no `$selector` rule")
        return body.split(';').mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i < 0) null else decl.substring(0, i).trim() to decl.substring(i + 1).trim()
        }.toMap()
    }

    /** The space above the second and later entries is more than one line of the text. */
    @Test
    fun activityEntriesAreOneLineApart() {
        val gap = rule(".tp-tl + .tp-tl")["margin-top"]
        assertTrue(gap != null && gap.endsWith("em"), "the gap between entries is not set in em: $gap")
        assertTrue(gap.removeSuffix("em").toDouble() >= 1.3, "the gap between entries is under one line: $gap")
    }

    /** An entry's text wraps inside its own column, so a long one grows downward and never over its neighbour. */
    @Test
    fun anEntryWrapsInsideItsOwnColumn() {
        val text = rule(".tp-tl-text")
        assertEquals("0", text["min-width"])
        assertEquals("anywhere", text["overflow-wrap"])
        assertEquals("flex", rule(".tp-tl")["display"])
    }
}
