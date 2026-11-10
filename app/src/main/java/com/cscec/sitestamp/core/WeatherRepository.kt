package com.cscec.sitestamp.core

import android.content.Context
import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 现场天气（水印要素之一）。
 *
 * 走 Open-Meteo 免费接口，无需 key。失败静默——天气取不到绝不能挡住拍照，
 * 所以任何异常都返回空串，渲染端按"无天气行"处理。
 * 缓存 10 分钟且位移小于 3 km 不重查，避免连拍时反复请求。
 */
class WeatherRepository(private val context: Context) {

    private val _weatherText = MutableStateFlow("")
    val weatherText: StateFlow<String> = _weatherText.asStateFlow()

    private var lastQueryAt = 0L
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var inFlight = false

    suspend fun refresh(lat: Double?, lng: Double?) {
        if (lat == null || lng == null) return
        if (inFlight) return
        val now = System.currentTimeMillis()
        if (now - lastQueryAt < CACHE_MS && movedMeters(lat, lng) < 3000f) return
        inFlight = true
        try {
            val text = withContext(Dispatchers.IO) { query(lat, lng) }
            if (text.isNotEmpty()) {
                _weatherText.value = text
                lastQueryAt = now
                lastLat = lat
                lastLng = lng
            }
        } finally {
            inFlight = false
        }
    }

    private fun movedMeters(lat: Double, lng: Double): Float {
        if (lastLat.isNaN() || lastLng.isNaN()) return Float.MAX_VALUE
        val out = FloatArray(1)
        Location.distanceBetween(lastLat, lastLng, lat, lng, out)
        return out[0]
    }

    private fun query(lat: Double, lng: Double): String {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=" +
                String.format(Locale.US, "%.4f", lat) +
                "&longitude=" + String.format(Locale.US, "%.4f", lng) +
                "&current=temperature_2m,weather_code&timezone=auto"
        )
        var conn: HttpURLConnection? = null
        return try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return ""
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val current = json.optJSONObject("current") ?: return ""
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val code = current.optInt("weather_code", -1)
            val desc = describe(code)
            if (temp.isNaN()) desc else "$desc ${temp.toInt()}℃"
        } catch (_: Exception) {
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
                // 忽略
            }
        }
    }

    /** WMO weather code 转现场口语。 */
    private fun describe(code: Int): String = describeWeatherCode(code)

    private companion object {
        const val CACHE_MS = 10 * 60 * 1000L
    }
}

/** WMO weather code 转现场口语。 */
internal fun describeWeatherCode(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "晴间多云"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55, 56, 57 -> "毛毛雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66, 67 -> "冻雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "米雪"
    80, 81 -> "阵雨"
    82 -> "强阵雨"
    85, 86 -> "阵雪"
    95 -> "雷阵雨"
    96, 99 -> "雷暴冰雹"
    else -> "天气"
}
