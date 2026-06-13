package com.github.mofosyne.tagdrop

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import org.osmdroid.util.GeoPoint

class MainViewModel : ViewModel() {
    private val _mapFocusPoint = MutableLiveData<GeoPoint?>()
    val mapFocusPoint: LiveData<GeoPoint?> = _mapFocusPoint

    fun focusOnMap(lat: Double, lng: Double) {
        _mapFocusPoint.value = GeoPoint(lat, lng)
    }

    fun clearFocus() {
        _mapFocusPoint.value = null
    }
}
