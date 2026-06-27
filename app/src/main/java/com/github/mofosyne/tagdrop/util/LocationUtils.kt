package com.github.mofosyne.tagdrop.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import androidx.core.content.getSystemService
import org.osmdroid.util.GeoPoint

object LocationUtils {
    /** Best-known device location, or null if unavailable/permission not granted. */
    fun lastKnownLocation(context: Context): GeoPoint? {
        val hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasCoarse && !hasFine) return null

        val locationManager = context.getSystemService<LocationManager>() ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { locationManager.isProviderEnabled(it) }
            .mapNotNull {
                try { locationManager.getLastKnownLocation(it) } catch (e: SecurityException) { null }
            }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    /** Effective (lat, lng, radiusM) triple after resolving a declared location against a live GPS fix. */
    data class ResolvedLocation(val lat: Double?, val lng: Double?, val radiusM: Double?, val locationLabel: String? = null)

    /**
     * Resolves the effective location for a freshly-scanned payload: the author's declared
     * location ([declaredLat]/[declaredLng]) wins over the device's live GPS fix
     * ([liveLat]/[liveLng]) when [preferDeclared] is true and a declared location is present;
     * otherwise live GPS wins when available, falling back to the declared location if not.
     * [declaredRadiusM] (the declared location's circle-of-uncertainty radius) only carries
     * through when the declared location is the one that won — a live GPS fix has no
     * author-declared uncertainty figure.
     *
     * **Explicit no fixed point** (SPEC §4.2): with no declared coordinates, [preferDeclared]
     * set true or a non-null [locationLabel] is read as the author asserting this payload has
     * no reliable fixed point (e.g. mailed, or carried on a moving vehicle like "🚋 Tram 40") —
     * a live GPS fix MUST NOT be substituted for it, so this returns no coordinates at all
     * rather than falling through to live GPS. [locationLabel] always carries through
     * regardless of which branch resolves the coordinates.
     */
    fun resolveLocation(
        declaredLat: Double?, declaredLng: Double?, declaredRadiusM: Double?, preferDeclared: Boolean,
        liveLat: Double?, liveLng: Double?, locationLabel: String? = null
    ): ResolvedLocation {
        val hasDeclared = declaredLat != null && declaredLng != null
        val hasLive = liveLat != null && liveLng != null
        val declaredBothAbsent = declaredLat == null && declaredLng == null
        val explicitlyNoFixedPoint = declaredBothAbsent && (preferDeclared || locationLabel != null)
        return when {
            explicitlyNoFixedPoint -> ResolvedLocation(null, null, null, locationLabel)
            hasDeclared && preferDeclared -> ResolvedLocation(declaredLat, declaredLng, declaredRadiusM, locationLabel)
            hasLive -> ResolvedLocation(liveLat, liveLng, null, locationLabel)
            hasDeclared -> ResolvedLocation(declaredLat, declaredLng, declaredRadiusM, locationLabel)
            else -> ResolvedLocation(null, null, null, locationLabel)
        }
    }
}
