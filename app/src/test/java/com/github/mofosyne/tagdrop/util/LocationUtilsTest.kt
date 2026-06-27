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

    /**
     * SPEC §4.2, "Explicit no fixed point": `prefer_declared_location=true` with no declared
     * coordinates at all is an author assertion that this payload has no reliable fixed point
     * (e.g. mailed, or carried on a moving vehicle) — a live GPS fix MUST NOT be substituted
     * for it. (Until issue #40's fix, `preferDeclared` alone here was a no-op and live GPS won
     * by default — this inverts that.)
     */
    @Test fun preferDeclaredWithoutADeclaredLocationMeansNoFixedPoint() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = null, declaredLng = null, declaredRadiusM = null, preferDeclared = true,
            liveLat = -37.8136, liveLng = 144.9631
        )
        assertNull(resolved.lat)
        assertNull(resolved.lng)
        assertNull(resolved.radiusM)
    }

    @Test fun locationLabelWithoutADeclaredLocationMeansNoFixedPoint() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = null, declaredLng = null, declaredRadiusM = null, preferDeclared = false,
            liveLat = -37.8136, liveLng = 144.9631, locationLabel = "🚋 Tram 40"
        )
        assertNull(resolved.lat)
        assertNull(resolved.lng)
        assertNull(resolved.radiusM)
        assertEquals("🚋 Tram 40", resolved.locationLabel)
    }

    @Test fun locationLabelIsPassThroughAndDoesNotAffectCoordinateResolution() {
        val resolved = LocationUtils.resolveLocation(
            declaredLat = -33.8688, declaredLng = 151.2093, declaredRadiusM = 25.0, preferDeclared = false,
            liveLat = -37.8136, liveLng = 144.9631, locationLabel = "back garden, behind the shed"
        )
        assertEquals(-37.8136, resolved.lat!!, 0.0)
        assertEquals(144.9631, resolved.lng!!, 0.0)
        assertEquals("back garden, behind the shed", resolved.locationLabel)
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
