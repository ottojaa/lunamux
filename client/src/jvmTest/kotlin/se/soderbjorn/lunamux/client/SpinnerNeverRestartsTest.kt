/**
 * Holds in place the three things that keep a working spinner turning when the list or footer that
 * shows it is drawn again: the frame comes from the clock, every place that draws a spinner asks
 * for it by key, and the key hands back the element it built before.
 *
 * There is no browser test setup for the web module, so the tests read the web module's sources
 * (paths come from system properties set in `client/build.gradle.kts`). The behaviour itself is
 * watched on a running page by the checker's page probe, whose log goes with the hand-off.
 *
 * Contains [SpinnerNeverRestartsTest].
 *
 * @see WorkingGlyphStyleTest
 */
package se.soderbjorn.lunamux.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Source-level guards for "a spinner never restarts when its row redraws". */
class SpinnerNeverRestartsTest {

    /** The text of the web source file named by the system property [property]. */
    private fun source(property: String): String = File(System.getProperty(property)).readText()

    /** The frame is picked from the time of day, never from when a glyph was built or a counter. */
    @Test
    fun theFrameComesFromTheClock() {
        val glyph = source("lunamux.workingGlyphSource")
        assertTrue(
            Regex("""private fun frameAt\([^)]*\): Int =\s*\(\(nowMillis / spec\.stepMs\)\.toLong\(\) % spec\.frames\.size\)\.toInt\(\)""").containsMatchIn(glyph),
            "frameAt no longer reads the frame from the time",
        )
        assertTrue(glyph.contains("frameAt(g.spec, now)"), "the clock tick does not use frameAt")
        assertTrue(glyph.contains("frameAt(spec, kotlin.js.Date.now())"), "a new glyph does not start on the current frame")
    }

    /** A glyph asked for again with the same key is the element built before, not a new one. */
    @Test
    fun aKeyedGlyphIsHandedOutAgain() {
        val glyph = source("lunamux.workingGlyphSource")
        assertTrue(glyph.contains("key?.let { k -> keyedGlyphs[k]?.let { return it } }"), "workingGlyph does not return the glyph kept for a key")
    }

    /** The overview, the panel and the footer each ask for their spinner by key. */
    @Test
    fun everyPlaceThatDrawsASpinnerGivesAKey() {
        val calls = mapOf(
            "lunamux.agentsOverviewSource" to "workingGlyph(key)",
            "lunamux.taskPanelSource" to "workingGlyph(key)",
            "lunamux.toolkitBootstrapSource" to "workingGlyph(glyphKey)",
        )
        for ((property, call) in calls) {
            val text = source(property)
            assertTrue(text.contains(call), "$property no longer draws its spinner with $call")
            assertFalse(Regex("""workingGlyph\(\s*(null)?\s*\)""").containsMatchIn(text), "$property draws a spinner with no key")
        }
    }
}
