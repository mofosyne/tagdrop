package com.github.mofosyne.tagdrop.util

import org.junit.Assert.*
import org.junit.Test

/** Exercises [LocationUtils.resolveLocation]'s priority rules (SPEC §4.2). */
class LocationUtilsTest {

    @Test fun declaredWinsWhenPreferredEvenIfLiveAvailable() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = -33.8688, declaredLng = 151.2093, declaredRadiusM = 25.0, preferDeclared = true,
            liveLat = -37.8136, liveLng = 144.9631
        )
        assertEquals(-33.8688, resolved.lat!!, 0.0)
        assertEquals(151.2093, resolved.lng!!, 0.0)
        assertEquals(25.0, resolved.radiusM!!, 0.0)
    }

    @Test fun liveWinsByDefaultWhenBothAvailable() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = -33.8688, declaredLng = 151.2093, declaredRadiusM = 25.0, preferDeclared = false,
            liveLat = -37.8136, liveLng = 144.9631
        )
        assertEquals(-37.8136, resolved.lat!!, 0.0)
        assertEquals(144.9631, resolved.lng!!, 0.0)
        assertNull("a live GPS fix has no author-declared uncertainty figure", resolved.radiusM)
    }

    @Test fun declaredIsFallbackWhenLiveUnavailable() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = -33.8688, declaredLng = 151.2093, declaredRadiusM = 25.0, preferDeclared = false,
            liveLat = null, liveLng = null
        )
        assertEquals(-33.8688, resolved.lat!!, 0.0)
        assertEquals(151.2093, resolved.lng!!, 0.0)
        assertEquals(25.0, resolved.radiusM!!, 0.0)
    }

    @Test fun nullTripleWhenNeitherAvailable() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = null, declaredLng = null, declaredRadiusM = null, preferDeclared = false,
            liveLat = null, liveLng = null
        )
        assertNull(resolved.lat)
        assertNull(resolved.lng)
        assertNull(resolved.radiusM)
    }

    @Test fun preferDeclaredHasNoEffectWithoutADeclaredLocation() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = null, declaredLng = null, declaredRadiusM = null, preferDeclared = true,
            liveLat = -37.8136, liveLng = 144.9631
        )
        assertEquals(-37.8136, resolved.lat!!, 0.0)
        assertEquals(144.9631, resolved.lng!!, 0.0)
        assertNull(resolved.radiusM)
    }

    @Test fun partialDeclaredCoordinateIsTreatedAsAbsent() {
        // Only one of lat/lng present — not a usable declared location, so live still wins
        // (or null if live is also absent), and the partial coordinate is discarded entirely.
        val resolved = LocationUtils.resolveLocation(
            declaredLat = -33.8688, declaredLng = null, declaredRadiusM = 25.0, preferDeclared = true,
            liveLat = -37.8136, liveLng = 144.9631
        )
        assertEquals(-37.8136, resolved.lat!!, 0.0)
        assertEquals(144.9631, resolved.lng!!, 0.0)
        assertNull(resolved.radiusM)
    }
}
