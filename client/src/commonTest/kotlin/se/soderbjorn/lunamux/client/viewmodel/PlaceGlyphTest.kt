/**
 * Tests for [placeGlyph]: the glyph a pane gets from the rectangle it fills in its tab, for a pane
 * that fills the tab, halves, quarters and the odd shapes in between.
 *
 * @see placeGlyph
 */
package se.soderbjorn.lunamux.client.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceGlyphTest {

    @Test
    fun aPaneFillingItsTabHasNoGlyph() {
        assertEquals("", placeGlyph(0.0, 0.0, 1.0, 1.0))
        assertEquals("", placeGlyph(0.0, 0.0, 0.95, 0.92), "a sliver of margin still fills it")
    }

    @Test
    fun halvesTakeTheSideTheyFill() {
        assertEquals("▌", placeGlyph(0.0, 0.0, 0.5, 1.0))
        assertEquals("▐", placeGlyph(0.5, 0.0, 0.5, 1.0))
        assertEquals("▀", placeGlyph(0.0, 0.0, 1.0, 0.5))
        assertEquals("▄", placeGlyph(0.0, 0.5, 1.0, 0.5))
    }

    @Test
    fun quartersTakeTheirCorner() {
        assertEquals("▘", placeGlyph(0.0, 0.0, 0.5, 0.5))
        assertEquals("▝", placeGlyph(0.5, 0.0, 0.5, 0.5))
        assertEquals("▖", placeGlyph(0.0, 0.5, 0.5, 0.5))
        assertEquals("▗", placeGlyph(0.5, 0.5, 0.5, 0.5))
    }

    @Test
    fun anOddShapeTakesTheQuarterItsCentreLiesIn() {
        assertEquals("▘", placeGlyph(0.0, 0.0, 0.7, 0.3), "wide and short in the top left")
        assertEquals("▗", placeGlyph(0.6, 0.7, 0.4, 0.3), "small in the bottom right")
        assertEquals("▖", placeGlyph(0.0, 0.6, 0.3, 0.4), "a third-wide strip at the bottom left")
        assertEquals("▝", placeGlyph(0.7, 0.0, 0.3, 0.5), "right of centre, top")
    }

    @Test
    fun aFloatingPaneInTheMiddleTakesTheRightBottomSide() {
        assertEquals("▗", placeGlyph(0.25, 0.25, 0.5, 0.5))
    }

    @Test
    fun aThirdIsAQuarterAndAnAlmostFullHeightHalfIsAHalf() {
        assertEquals("▘", placeGlyph(0.0, 0.0, 0.33, 0.33))
        assertEquals("▐", placeGlyph(0.5, 0.0, 0.5, 0.95))
    }
}
