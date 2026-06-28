package com.github.mofosyne.tagdrop.util

import org.junit.Assert.*
import org.junit.Test

/** Exercises [looksLikePixelArt]'s small-image heuristic (SPEC §7, "Pixel art"). */
class PixelArtHeuristicTest {

    @Test fun trueForSquareImagesAtOrBelowThreshold() {
        assertTrue(looksLikePixelArt(1, 1))
        assertTrue(looksLikePixelArt(16, 16))
        assertTrue(looksLikePixelArt(PIXEL_ART_HEURISTIC_MAX_PX, PIXEL_ART_HEURISTIC_MAX_PX))
    }

    @Test fun trueForNonSquareImagesAtOrBelowThreshold() {
        assertTrue(looksLikePixelArt(8, 64))
        assertTrue(looksLikePixelArt(64, 8))
    }

    @Test fun falseWhenEitherDimensionExceedsThreshold() {
        assertFalse(looksLikePixelArt(PIXEL_ART_HEURISTIC_MAX_PX + 1, PIXEL_ART_HEURISTIC_MAX_PX))
        assertFalse(looksLikePixelArt(PIXEL_ART_HEURISTIC_MAX_PX, PIXEL_ART_HEURISTIC_MAX_PX + 1))
        assertFalse(looksLikePixelArt(1920, 1080))
    }

    @Test fun falseForZeroOrNegativeDimensions() {
        assertFalse(looksLikePixelArt(0, 0))
        assertFalse(looksLikePixelArt(-1, 16))
        assertFalse(looksLikePixelArt(16, -1))
    }
}
