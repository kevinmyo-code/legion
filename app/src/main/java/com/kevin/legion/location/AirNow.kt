package com.kevin.legion.location

import android.util.Log
import com.kevin.legion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AirNow's `/aq/observation/latLong/current/` lookup (command-center ticket 08) - the first
 * consumer of [BuildConfig.AIRNOW_API_KEY], which has sat in `local.properties`/`build.gradle.kts`
 * unconsumed since it was added. Same posture as [AreaInfo]: one keyless-degrades-in-words client,
 * `internal`-testable pure parse split from the network call, plain-JVM reachable.
 *
 * **AirNow is a KEYED source, unlike every category [AreaInfo] already serves** - [current]'s
 * [Reading.NoKey] branch is the reason a missing key must never be silently treated as "no data
 * nearby": those are two different sentences (CLAUDE.md §4 rule 5's posture on "unreadable and
 * empty are different"), and the ticket's own rule is explicit - **a missing reading is NEVER
 * rendered as clean air**. `ui/world/AreaCard` is the only caller today; if `AreaInfo` ever grows
 * an `air` category (its own doc explains why that is deliberately not done yet - a login-walled
 * endpoint page that has not been read), that category should call THIS client, not a second one.
 */
object AirNow {
    private const val TAG = "AirNow"
    private const val TIMEOUT_MS = 10_000
    private const val OBSERVATION_URL = "https://www.airnowapi.org/aq/observation/latLong/current/"

    /** 25 miles - AirNow's own documented default search radius for this endpoint when a caller
     * does not have a specific station in mind, which a live GPS fix never does. */
    private const val SEARCH_RADIUS_MILES = 25

    /** One reading, or one of three reasons there isn't one - never a bare null, so a card can
     * never accidentally treat "couldn't tell" as "the air is fine" (the ticket's own rule). */
    sealed class Reading {
        /** [aqi] is AirNow's own 0-500+ index; [category] is its own plain-English band ("Good",
         * "Moderate", "Unhealthy for Sensitive Groups", ...); [pollutant] names which measured
         * parameter (PM2.5, O3, ...) produced the worst (highest) AQI among the station's readings -
         * AirNow reports one row per pollutant, and the worst one is what the AQI "headline" number
         * conventionally means. [reportingArea] is AirNow's own station/city label, folded into the
         * area card's location line rather than repeated on its own. */
        data class Ok(
            val aqi: Int,
            val category: String,
            val pollutant: String,
            val reportingArea: String,
        ) : Reading()

        /** [BuildConfig.AIRNOW_API_KEY] is blank - a clone-and-run build with no key configured,
         * not a network problem. Distinct from [Unreachable] so the card can say "not set up"
         * instead of "couldn't reach", which would send someone to check their Wi-Fi for a problem
         * that is actually in `local.properties`. */
        object NoKey : Reading()

        /** The HTTP call itself failed - no connection, a timeout, or a non-2xx response. */
        object Unreachable : Reading()

        /** AirNow was reached and answered, but returned no station within [SEARCH_RADIUS_MILES] -
         * a genuinely different fact from [Unreachable] (the network is fine; there is simply no
         * nearby monitor) and must never collapse into it or into silence. */
        object NoData : Reading()
    }

    /** Fetches the current observation nearest ([lat], [lon]). Never throws - every branch above
     * is a real [Reading] value, per CLAUDE.md §7's offline-degrades-in-words rule. */
    suspend fun current(lat: Double, lon: Double): Reading = withContext(Dispatchers.IO) {
        val key = BuildConfig.AIRNOW_API_KEY
        if (key.isBlank()) return@withContext Reading.NoKey
        val url = "$OBSERVATION_URL?format=application/json&latitude=$lat&longitude=$lon" +
            "&distance=$SEARCH_RADIUS_MILES&API_KEY=$key"
        val body = httpGet(url) ?: return@withContext Reading.Unreachable
        parse(body) ?: Reading.NoData
    }

    /**
     * Pure parse, `internal` so it is unit-testable against a canned response body with no
     * network. AirNow returns one flat object per measured pollutant at the nearest station, e.g.
     * `{"ReportingArea":"Houston","StateCode":"TX","ParameterName":"PM2.5","AQI":42,
     * "CategoryName":"Good", ...}` - this picks the entry with the HIGHEST `AQI` (the "headline"
     * reading a station reports), matching how AirNow's own consumer-facing site presents a single
     * number per station. Returns null on an empty array (no station in range) or an unparseable
     * body, both of which [current] turns into [Reading.NoData] - this function itself never
     * fabricates a reading from a partial or malformed row.
     */
    internal fun parse(body: String): Reading.Ok? {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return null
        var best: JSONObject? = null
        for (i in 0 until arr.length()) {
            val row = arr.optJSONObject(i) ?: continue
            if (!row.has("AQI")) continue
            val aqi = row.optInt("AQI", -1)
            if (aqi < 0) continue
            if (best == null || aqi > best!!.optInt("AQI", -1)) best = row
        }
        val row = best ?: return null
        return Reading.Ok(
            aqi = row.optInt("AQI"),
            category = row.optString("CategoryName").ifBlank { "unknown category" },
            pollutant = row.optString("ParameterName").ifBlank { "unknown pollutant" },
            reportingArea = row.optString("ReportingArea").ifBlank { "the nearest station" },
        )
    }

    /** Blocking HTTP GET (call within an IO context, as [current] already ensures). Returns the
     * raw body, or null on any error - same shape as [AreaInfo]'s own `httpGet`, kept local rather
     * than shared since both are private single-file helpers with no third caller yet. */
    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }
            try {
                if (conn.responseCode >= 400) {
                    Log.w(TAG, "HTTP ${conn.responseCode} for $urlStr")
                    return null
                }
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP GET failed for $urlStr: ${e.message}")
            null
        }
    }
}
