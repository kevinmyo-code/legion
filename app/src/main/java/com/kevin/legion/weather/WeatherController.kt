package com.kevin.legion.weather

import android.util.Log
import com.kevin.legion.location.LocationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/**
 * Current weather at the driver's location, used to flavor the startup greeting.
 *
 * Source is Open-Meteo - free and keyless, so no API key or billing. Cached with
 * a TTL so [current] is an instant, non-blocking read for the system prompt; the
 * foreground service refreshes it on a slow loop, so a session never waits on the
 * network. Returns null until the first successful fetch (no GPS / offline).
 */
object WeatherController {
    private const val TAG = "WeatherController"
    private const val TTL_MS = 30 * 60 * 1000L
    private const val TIMEOUT_MS = 5000

    data class WeatherInfo(
        val tempF: Int,
        val description: String,
        /** Rough/hazardous conditions (rain, snow, fog, storm) worth a "drive safe". */
        val caution: Boolean,
    )

    @Volatile private var cached: WeatherInfo? = null
    @Volatile private var fetchedAt = 0L

    /** Last known weather, or null if not fetched yet. Non-blocking. */
    fun current(): WeatherInfo? = cached

    /**
     * Refreshes the cache if it's stale and a GPS fix is available. Returns the
     * (possibly unchanged) cached value. Safe to call repeatedly.
     */
    suspend fun refresh(): WeatherInfo? = withContext(Dispatchers.IO) {
        if (cached != null && System.currentTimeMillis() - fetchedAt < TTL_MS) return@withContext cached
        val loc = LocationController.state.value ?: return@withContext cached

        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}" +
                "&longitude=${loc.longitude}&current=temperature_2m,weather_code,is_day" +
                "&temperature_unit=fahrenheit"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
        }
        try {
            if (conn.responseCode >= 400) {
                Log.w(TAG, "Open-Meteo error ${conn.responseCode}")
                return@withContext cached
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val cur = JSONObject(body).optJSONObject("current") ?: return@withContext cached
            val info = parse(cur)
            cached = info
            fetchedAt = System.currentTimeMillis()
            info
        } catch (e: Exception) {
            Log.w(TAG, "Weather fetch failed: ${e.message}")
            cached
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(current: JSONObject): WeatherInfo {
        val tempF = current.optDouble("temperature_2m", Double.NaN)
            .let { if (it.isNaN()) 0 else it.roundToInt() }
        val code = current.optInt("weather_code", -1)
        val isDay = current.optInt("is_day", 1) == 1
        val (description, caution) = describe(code, isDay)
        return WeatherInfo(tempF = tempF, description = description, caution = caution)
    }

    /**
     * Maps a WMO weather code (+ day/night) to a plain description and whether
     * conditions are rough (rain, snow, fog, storm) and worth a "drive safe".
     */
    private fun describe(code: Int, isDay: Boolean): Pair<String, Boolean> = when (code) {
        0 -> (if (isDay) "bright and clear" else "clear") to false
        1, 2 -> "partly cloudy" to false
        3 -> "grey and overcast" to false
        45, 48 -> "foggy" to true
        in 51..57 -> "drizzly" to false
        in 61..67 -> "rainy" to true
        in 71..77, in 85..86 -> "snowy" to true
        in 80..82 -> "rainy with showers" to true
        in 95..99 -> "stormy" to true
        else -> (if (isDay) "clear" else "dark out") to false
    }
}
