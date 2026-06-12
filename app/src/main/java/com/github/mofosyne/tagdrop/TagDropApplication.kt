package com.github.mofosyne.tagdrop

import android.app.Application
import com.google.android.material.color.DynamicColors

/** Applies Material You dynamic color (Android 12+) so the app matches the system theme. */
class TagDropApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
