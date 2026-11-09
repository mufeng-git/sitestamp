package com.cscec.sitestamp.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

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

    /** 已注册过监听就不再重复注册，否则每次 onResume 都会叠一层回调。 */
    private var listening = false

    private var lastGeocodedLat: Double? = null
    private var lastGeocodedLng: Double? = null

    @SuppressLint("MissingPermission")
    fun requestUpdate() {
        if (!hasPermission()) {
            Log.d(TAG, "requestUpdate: no permission")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = try {
            lm.getProviders(true)
        } catch (e: Exception) {
            Log.e(TAG, "requestUpdate: getProviders failed", e)
            emptyList()
        }
        Log.d(TAG, "requestUpdate: providers=" + providers.joinToString())

        readLastKnown(lm, LocationManager.GPS_PROVIDER)
        readLastKnown(lm, LocationManager.NETWORK_PROVIDER)

        if (listening) {
            Log.d(TAG, "requestUpdate: already listening")
            return
        }
        listening = true
        for (provider in providers) {
            try {
                lm.requestLocationUpdates(
                    provider,
                    1000L,
                    0f,
                    ContextCompat.getMainExecutor(context),
                    object : android.location.LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            updateSnapshot(loc)
                        }

                        override fun onProviderDisabled(provider: String) {
                            Log.d(TAG, "provider disabled: " + provider)
                        }

                        override fun onProviderEnabled(provider: String) {
                            Log.d(TAG, "provider enabled: " + provider)
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                        }
                    }
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "requestUpdate: SecurityException for " + provider, e)
            } catch (e: Exception) {
                Log.e(TAG, "requestUpdate: failed for " + provider, e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readLastKnown(lm: LocationManager, provider: String) {
        try {
            if (lm.getProvider(provider) == null) return
            val last = lm.getLastKnownLocation(provider) ?: return
            val current = _location.value
            // 已有更准的定位就不覆盖
            if (current.isValid && (current.accuracy ?: Float.MAX_VALUE) <= last.accuracy + 0.001f) return
            updateSnapshot(last)
            Log.d(TAG, "last known from " + provider)
        } catch (e: SecurityException) {
            Log.w(TAG, "readLastKnown SecurityException: " + provider)
        } catch (e: Exception) {
            Log.w(TAG, "readLastKnown failed: " + provider, e)
        }
    }

    private fun updateSnapshot(loc: Location) {
        val current = _location.value
        // 同一位置的抖动不覆盖，避免地址反复重算
        if (current.isValid && sameSpot(current.latitude, current.longitude, loc.latitude, loc.longitude)) {
            _location.value = current.copy(accuracy = loc.accuracy)
            return
        }
        _location.value = LocationSnapshot(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            address = null,
            isValid = true
        )
        Log.d(TAG, "location updated: " + loc.latitude + "," + loc.longitude + " acc=" + loc.accuracy)
    }

    private fun sameSpot(lat1: Double?, lng1: Double?, lat2: Double?, lng2: Double?): Boolean {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return false
        return abs(lat1 - lat2) < GEOCODE_MIN_DELTA && abs(lng1 - lng2) < GEOCODE_MIN_DELTA
    }

    /** 坐标移动超过阈值或首次才需要重新反查地址；Geocoder 有配额，不能每次回调都调。 */
    fun shouldGeocode(lat: Double, lng: Double): Boolean {
        val la = lastGeocodedLat
        val lo = lastGeocodedLng
        if (la == null || lo == null) return true
        return abs(lat - la) >= GEOCODE_MIN_DELTA || abs(lng - lo) >= GEOCODE_MIN_DELTA
    }

    suspend fun resolveAddress(lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
        val result = try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
            list.firstOrNull()?.getAddressLine(0)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "geocode failed", e)
            null
        }
        // 成功与否都记下坐标：失败时也避免每次回调都去撞一次 Geocoder
        lastGeocodedLat = lat
        lastGeocodedLng = lng
        result
    }

    fun setAddress(address: String?) {
        _location.value = _location.value.copy(address = address)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun formatGps(snapshot: LocationSnapshot): String {
        val lat = snapshot.latitude ?: return "定位未获取"
        val lng = snapshot.longitude ?: return "定位未获取"
        val acc = snapshot.accuracy?.let { " (正负" + String.format("%.0f", it) + "m)" } ?: ""
        return String.format(Locale.US, "GPS：%.6f, %.6f%s", lat, lng, acc)
    }

    fun formatAddress(snapshot: LocationSnapshot): String {
        val addr = snapshot.address?.takeIf { it.isNotBlank() }
        return if (addr == null) "地址：正在解析" else "地址：" + addr
    }

    companion object {
        private const val TAG = "SiteStamp"

        /** 约 50 米（纬度 0.0005 度），小于它就认为没移动。 */
        private const val GEOCODE_MIN_DELTA = 0.0005
    }
}
