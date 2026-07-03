package com.github.mofosyne.tagdrop

import android.app.Application
import com.google.android.material.color.DynamicColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Applies Material You dynamic color (Android 12+) so the app matches the system theme. */
class TagDropApplication : Application() {

    /** App-lifetime scope for work that must outlive any single Activity (e.g. source fetches). */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
