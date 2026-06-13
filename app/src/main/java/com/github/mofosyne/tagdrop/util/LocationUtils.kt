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
}
