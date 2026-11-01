package com.cscec.sitestamp.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

data class LocationSnapshot(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Float? = null,
    val address: String? = null,
    val isValid: Boolean = false
)

class LocationRepository(private val context: Context) {

    private val _location = MutableStateFlow(LocationSnapshot())
    val location: StateFlow<LocationSnapshot> = _location.asStateFlow()

    @SuppressLint("MissingPermission")
    fun requestUpdate() {
        if (!hasPermission()) return
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        val loc = lm.getLastKnownLocation(providers) ?: return
        updateSnapshot(loc)
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(provider, 1000L, ::updateSnapshot, Looper.getMainLooper())
            } catch (_: SecurityException) {
            }
        }
    }

    private fun updateSnapshot(loc: Location) {
        _location.value = LocationSnapshot(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            isValid = true
        )
    }

    suspend fun resolveAddress(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
            list.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun formatGps(snapshot: LocationSnapshot): String {
        val lat = snapshot.latitude ?: return "定位未获取"
        val lng = snapshot.longitude ?: return "定位未获取"
        val acc = snapshot.accuracy?.let { " (±${String.format("%.0f", it)}m)" } ?: ""
        return String.format(Locale.US, "GPS：%.6f, %.6f%s", lat, lng, acc)
    }

    fun formatAddress(snapshot: LocationSnapshot): String {
        return snapshot.address?.takeIf { it.isNotBlank() } ?: "地址：不可用"
    }
}
