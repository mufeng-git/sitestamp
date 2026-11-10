package com.cscec.sitestamp.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // 上一次发起 Geocoder 请求的坐标，位移不足 50 米时不重复请求（Geocoder 有配额）
    private var lastGeocodedLat: Double? = null
    private var lastGeocodedLng: Double? = null

    @SuppressLint("MissingPermission")
    fun requestUpdate() {
        if (!hasPermission()) {
            Log.d("SiteStamp", "requestUpdate: no permission")
            return
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        Log.d("SiteStamp", "requestUpdate: providers=${providers.joinToString()}")

        // 优先尝试 GPS provider
        val gpsProvider = lm.getProvider(LocationManager.GPS_PROVIDER)
        if (gpsProvider != null) {
            try {
                val gpsLast = lm.getLastKnownLocation(gpsProvider.name)
                if (gpsLast != null) {
                    updateSnapshot(gpsLast)
                    Log.d("SiteStamp", "requestUpdate: got last GPS loc")
                } else {
                    Log.d("SiteStamp", "requestUpdate: no last GPS loc, will wait for update")
                }
            } catch (_: SecurityException) {
            }
        }

        // 网络 provider 作为兜底
        val netProvider = lm.getProvider(LocationManager.NETWORK_PROVIDER)
        if (netProvider != null) {
            try {
                val netLast = lm.getLastKnownLocation(netProvider.name)
                if (netLast != null) {
                    updateSnapshot(netLast)
                    Log.d("SiteStamp", "requestUpdate: got last network loc")
                }
            } catch (_: SecurityException) {
            }
        }

        // 请求持续更新（GPS 优先）
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(loc: Location) {
                Log.d("SiteStamp", "requestUpdate: location changed: ${loc.latitude}, ${loc.longitude}, acc=${loc.accuracy}")
                updateSnapshot(loc)
            }
            override fun onProviderDisabled(provider: String) {
                Log.d("SiteStamp", "requestUpdate: provider disabled: $provider")
            }
            override fun onProviderEnabled(provider: String) {
                Log.d("SiteStamp", "requestUpdate: provider enabled: $provider")
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
            }
        }
        for (provider in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // API 30+：Executor 重载
                    lm.requestLocationUpdates(provider, 1000L, 0f, ContextCompat.getMainExecutor(context), listener)
                } else {
                    // API 26-29：老重载必须带 Looper
                    @Suppress("DEPRECATION")
                    lm.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                }
            } catch (_: SecurityException) {
                Log.e("SiteStamp", "requestUpdate: SecurityException for provider $provider")
            }
        }
        Log.d("SiteStamp", "requestUpdate: location updates requested for ${providers.size} providers")
    }

    private fun updateSnapshot(loc: Location) {
        _location.value = LocationSnapshot(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracy = loc.accuracy,
            address = _location.value.address,
            isValid = true
        )
        resolveAddressIfNeeded(loc)
    }

    private fun resolveAddressIfNeeded(loc: Location) {
        val prevLat = lastGeocodedLat
        val prevLng = lastGeocodedLng
        if (prevLat != null && prevLng != null) {
            val distance = FloatArray(1)
            Location.distanceBetween(prevLat, prevLng, loc.latitude, loc.longitude, distance)
            if (distance[0] < ADDRESS_MIN_MOVE_METERS) return
        }
        // 先记录本次请求坐标，后续未移动足够距离的更新直接跳过
        lastGeocodedLat = loc.latitude
        lastGeocodedLng = loc.longitude
        scope.launch {
            val address = resolveAddress(loc.latitude, loc.longitude)
            if (address != null) {
                _location.value = _location.value.copy(address = address)
            }
        }
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
        val acc = snapshot.accuracy?.let { " (±${String.format(Locale.US, "%.0f", it)}m)" } ?: ""
        return String.format(Locale.US, "GPS：%.6f, %.6f%s", lat, lng, acc)
    }

    fun formatAddress(snapshot: LocationSnapshot): String {
        return snapshot.address?.takeIf { it.isNotBlank() } ?: "地址：不可用"
    }

    private companion object {
        const val ADDRESS_MIN_MOVE_METERS = 50f
    }
}
