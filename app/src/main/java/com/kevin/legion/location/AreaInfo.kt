package com.kevin.legion.location

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `area_info` (ticket 02, `.scratch/location-intelligence/issues/02-area-info-tool.md`) - what is
 * happening around the live GPS fix, from four keyless government/agency sources. One category-keyed
 * [fetch], not four separate clients, so [com.kevin.legion.service.LiveToolbox] can expose it as ONE
 * tool (map decision 1) rather than spending four prompt-budget slots on one idea.
 *
 * **Every source is named INSIDE the returned string** (map decisions 6/12) - `"NWS: Heat Advisory
 * until 7:00pm"`, never raw JSON handed to the model with a hope it credits the source in its own
 * words. This is CLAUDE.md's reconciliation-gate posture (§4 rule 5) applied to attribution instead
 * of money: a claim the app cannot make honestly on its own is stated as coming from whoever DOES
 * make it, structurally, not as a prompt instruction that can be ignored.
 *
 * **`air` (AirNow) is deliberately NOT a category here.** Ticket 10 (AirNow account) is blocked on a
 * login-walled page that must be read before the endpoint URL and rate limit can be trusted -
 * `.scratch/hands-and-senses/research/14-location-intel.md` §3 found the public index page names
 * the SAME lat/lon capability on both a "retiring fall 2026" list and a surviving list under two
 * differently-worded parent services, and could not resolve which is which without logging in. Do
 * not add `air` by pattern-matching the other four without reading that page first - the entire
 * point of leaving this gap is that guessing the wrong endpoint here breaks silently in ~1-3 months.
 *
 * Every network call degrades in WORDS (CLAUDE.md §7): a fetch failure, a parse failure, or an
 * empty result set are three different things and never collapse into a bare blank or a `0` - see
 * each `format*` function's own "nothing reported" fallback.
 */
object AreaInfo {
    private const val TAG = "AreaInfo"
    private const val TIMEOUT_MS = 10_000

    /**
     * NWS requires a `User-Agent` in place of an API key (identical clause the underlying NWS docs
     * expect) - see research §1. Generic and baked into source rather than personal, so a stranger's
     * own clone-and-run build sends the identical, valid string (CLAUDE.md's clone-and-run rule);
     * NWS's own guidance is that the string "can be anything", it just has to identify the caller.
     */
    private const val NWS_USER_AGENT = "(LEGION voice assistant, https://github.com/kevinmyo-code/legion)"

    /** USGS's own real-time GeoJSON summary feed (map decision 2) - never `fdsnws/event/1/query` on
     * a timer, which USGS explicitly asks automated clients not to hit. `2.5_day` (mag 2.5+, past
     * 24h) is the smallest feed that still surfaces a felt quake without flooding a spoken answer
     * with microseisms. */
    private const val USGS_FEED_URL = "https://earthquake.usgs.gov/earthquakes/feed/v1.0/summary/2.5_day.geojson"

    /** How far out a quake is still worth mentioning for a "what's around me" pull, and how many to
     * speak. Wider than the 150mi/M4.5 automatic-raise threshold (map decision 5) on purpose - this
     * is an on-request pull, not an unprompted interruption, so it can afford to be more generous. */
    private const val QUAKE_RADIUS_MILES = 200.0
    private const val QUAKE_RESULT_LIMIT = 5

    private const val NIFC_QUERY_URL =
        "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/" +
            "WFIGS_Incident_Locations_Current/FeatureServer/0/query"

    /** WFIGS's own field, confirmed live against the query endpoint (not a documented domain - the
     * layer's field metadata carries no enumerated list, so this is read off real incident rows).
     * `WF` is an actual wildfire; other codes seen include `RX` (a PLANNED prescribed burn) and are
     * filtered out here - map decision 3 wants a genuine hazard, and a prescribed burn spoken the
     * same way as a wildfire is exactly the false-alarm failure NIFC was chosen over FIRMS to avoid. */
    private const val NIFC_TYPE_FILTER = "IncidentTypeCategory='WF'"
    private const val FIRE_RADIUS_MILES = 100.0

    private const val FEMA_URL = "https://www.fema.gov/api/open/v2/DisasterDeclarationsSummaries"
    private const val FEMA_RESULT_LIMIT = 5

    /** The four categories `area_info` actually serves. `air` is intentionally absent - see the
     * class doc. Kept as an enum (not a raw string) so [dispatch]/[fetch]'s `when` is exhaustive and
     * a fifth category can't silently fall through to nothing. */
    enum class Category { WEATHER, QUAKE, FIRE, DISASTER }

    /**
     * Fetches and formats [category] for the live fix at ([lat], [lon]). Never throws - every
     * branch's own HTTP/parse failure degrades to a plain "couldn't reach X" sentence, per CLAUDE.md
     * §7's offline-degrades-in-words rule. Callers (`LiveToolbox`) are responsible for the "do we
     * even HAVE a fix" gate (map decision 14: no fallback to a saved/last-known position) - this
     * function assumes the caller already resolved a genuine live coordinate.
     */
    suspend fun fetch(context: Context, category: Category, lat: Double, lon: Double): String =
        withContext(Dispatchers.IO) {
            when (category) {
                Category.WEATHER -> fetchWeather(lat, lon)
                Category.QUAKE -> fetchQuakes(lat, lon)
                Category.FIRE -> fetchFire(lat, lon)
                Category.DISASTER -> fetchDisaster(context, lat, lon)
            }
        }

    // --- Weather: NWS active alerts ---------------------------------------------------------

    private fun fetchWeather(lat: Double, lon: Double): String {
        val body = httpGet(
            "https://api.weather.gov/alerts/active?point=$lat,$lon",
            headers = mapOf("User-Agent" to NWS_USER_AGENT, "Accept" to "application/geojson"),
        ) ?: return "Couldn't reach the National Weather Service to check right now."
        return formatWeatherAlerts(body)
    }

    /**
     * Pure parse+format, `internal` so it is unit-testable against a canned GeoJSON body with no
     * network. Every alert's `event` and, when present, `expires` (an offset-datetime the NWS emits
     * with the alert area's own UTC offset, e.g. `2026-08-22T07:00:00-05:00`) become one clause;
     * multiple active alerts join with `; ` under a single leading `NWS:` (the source only needs
     * naming once - decision 6's "named in the string" is satisfied by the whole return value, not
     * by repeating the source per clause).
     */
    internal fun formatWeatherAlerts(body: String): String {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull()
            ?: return "NWS: nothing reported."
        val parts = mutableListOf<String>()
        for (i in 0 until features.length()) {
            val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val event = props.optString("event").ifBlank { "Alert" }
            val until = formatOffsetClock(props.optString("expires", ""))
            parts += if (until != null) "$event until $until" else event
        }
        if (parts.isEmpty()) return "NWS: nothing reported."
        return "NWS: " + parts.joinToString("; ")
    }

    // --- Quake: USGS real-time summary feed --------------------------------------------------

    private fun fetchQuakes(originLat: Double, originLon: Double): String {
        val body = httpGet(USGS_FEED_URL) ?: return "Couldn't reach the USGS to check right now."
        return formatQuakes(body, originLat, originLon)
    }

    /**
     * Pure parse+format. Every feature's `geometry.coordinates` is `[lon, lat, depthKm]` (GeoJSON
     * order, confirmed live 2026-08-21 against the real feed) - distance and compass bearing are
     * computed here rather than trusting the feed's own free-text `place` string, so the spoken
     * phrasing is uniform ("`82 miles NE`") instead of whatever prose USGS happened to generate for
     * that particular quake (`"66 km WNW of Abra Pampa, Argentina"` for a distant one, but nothing
     * guarantees a consistent unit or distance reference for a NEARBY one).
     */
    internal fun formatQuakes(
        body: String,
        originLat: Double,
        originLon: Double,
        now: Long = System.currentTimeMillis(),
        radiusMiles: Double = QUAKE_RADIUS_MILES,
        limit: Int = QUAKE_RESULT_LIMIT,
    ): String {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull()
            ?: return "USGS: nothing reported."

        data class Nearby(val mag: Double, val miles: Double, val dir: String, val ago: String)
        val nearby = mutableListOf<Nearby>()
        for (i in 0 until features.length()) {
            val feature = features.optJSONObject(i) ?: continue
            val props = feature.optJSONObject("properties") ?: continue
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val mag = props.optDouble("mag", Double.NaN)
            if (mag.isNaN()) continue
            val qLat = coords.optDouble(1)
            val qLon = coords.optDouble(0)
            val miles = haversineMiles(originLat, originLon, qLat, qLon)
            if (miles > radiusMiles) continue
            val time = props.optLong("time", -1L)
            nearby += Nearby(
                mag = mag,
                miles = miles,
                dir = bearing(originLat, originLon, qLat, qLon),
                ago = if (time > 0) com.kevin.legion.util.relativeAge(time, now) else "time unknown",
            )
        }
        if (nearby.isEmpty()) return "USGS: nothing reported."
        val parts = nearby.sortedBy { it.miles }.take(limit)
            .map { "M${formatMagnitude(it.mag)}, ${it.miles.roundToInt()} miles ${it.dir}, ${it.ago}" }
        return "USGS: " + parts.joinToString("; ")
    }

    // --- Fire: NIFC WFIGS named incidents -----------------------------------------------------

    private fun fetchFire(lat: Double, lon: Double): String {
        val url = "$NIFC_QUERY_URL?where=${enc(NIFC_TYPE_FILTER)}" +
            "&outFields=IncidentName,IncidentSize,PercentContained" +
            "&geometry=$lon,$lat&geometryType=esriGeometryPoint&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects&distance=$FIRE_RADIUS_MILES&units=esriSRUnit_StatuteMile" +
            "&returnGeometry=false&f=geojson"
        val body = httpGet(url) ?: return "Couldn't reach the National Interagency Fire Center to check right now."
        return formatFire(body)
    }

    /**
     * Pure parse+format. `IncidentSize` (acres) and `PercentContained` are both nullable on the real
     * feed (confirmed live: a just-reported incident has neither yet) - each is spoken as "not yet
     * reported" rather than silently omitted or rendered as a false zero, which for containment
     * especially would read as "0% contained" (an actively bad fire) instead of "not measured yet".
     */
    internal fun formatFire(body: String): String {
        val features = runCatching { JSONObject(body).optJSONArray("features") }.getOrNull()
            ?: return "NIFC: nothing reported."
        val parts = mutableListOf<String>()
        for (i in 0 until features.length()) {
            val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
            val name = props.optString("IncidentName").ifBlank { "Unnamed incident" }
            val size = if (props.isNull("IncidentSize")) null else props.optDouble("IncidentSize", Double.NaN).takeIf { !it.isNaN() }
            val sizeStr = if (size != null) "${size.roundToInt()} acres" else "size not yet reported"
            val pct = if (props.isNull("PercentContained")) null else props.optInt("PercentContained", -1).takeIf { it >= 0 }
            val pctStr = if (pct != null) "$pct% contained" else "containment not yet reported"
            parts += "$name, $sizeStr, $pctStr"
        }
        if (parts.isEmpty()) return "NIFC: nothing reported."
        return "NIFC: " + parts.joinToString("; ")
    }

    // --- Disaster: FEMA county-level declarations ---------------------------------------------

    /**
     * FEMA declarations are keyed by state + county (`fipsStateCode`/`fipsCountyCode`), not by a
     * point, so this reverse-geocodes the live fix down to a US state first (Android's own
     * `Geocoder`, same mechanism `LiveToolbox.getCurrentLocation` already uses) and queries by
     * state - narrower than the source's own county granularity would allow, but resolving a county
     * NAME to FEMA's numeric `fipsCountyCode` needs a lookup table this ticket does not build, and
     * state-level is still honest: every row returned genuinely is FEMA data for the state the fix
     * is actually in, nothing is invented to sharpen it. `designatedArea` in the formatted output
     * (a county name) tells the listener which part of the state each declaration actually covers.
     */
    private suspend fun fetchDisaster(context: Context, lat: Double, lon: Double): String {
        val abbr = stateAbbreviation(context, lat, lon)
            ?: return "Couldn't tell which state that is, so I can't check FEMA declarations."
        val filter = enc("state eq '$abbr'")
        val url = "$FEMA_URL?\$filter=$filter&\$orderby=declarationDate%20desc&\$top=$FEMA_RESULT_LIMIT"
        val body = httpGet(url) ?: return "Couldn't reach FEMA to check right now."
        return formatDisaster(body)
    }

    /**
     * Pure parse+format (map decision: "FEMA is a declaration, not a hazard - context after the
     * fact, never phrased as an alert" - CLAUDE.md's own §4 framing for anything a source does not
     * itself claim as a live event). Deliberately never says "warning" or "alert" anywhere in this
     * function.
     */
    internal fun formatDisaster(body: String): String {
        val rows = runCatching { JSONObject(body).optJSONArray("DisasterDeclarationsSummaries") }.getOrNull()
            ?: return "FEMA: nothing reported."
        val parts = mutableListOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val title = row.optString("declarationTitle").ifBlank { "Disaster declaration" }
            val incidentType = row.optString("incidentType")
            val area = row.optString("designatedArea")
            val dateStr = formatFemaDate(row.optString("declarationDate")) ?: row.optString("declarationDate")
            val typeClause = if (incidentType.isNotBlank()) " ($incidentType)" else ""
            val areaClause = if (area.isNotBlank()) " for $area" else ""
            parts += "$title$typeClause declared $dateStr$areaClause"
        }
        if (parts.isEmpty()) return "FEMA: nothing reported."
        return "FEMA: " + parts.joinToString("; ")
    }

    /**
     * Reverse-geocodes to a two-letter US state code via Android's `Geocoder`, the same "may return
     * null with no GMS geocoding backend" degradation `getCurrentLocation` already accepts (CLAUDE.md
     * §7's "network calls degrade gracefully"). `Geocoder.getFromLocation`'s `adminArea` is a full
     * state NAME ("Texas"), not the abbreviation FEMA's OData filter expects ("TX"), hence
     * [US_STATE_ABBREVIATIONS].
     */
    private fun stateAbbreviation(context: Context, lat: Double, lon: Double): String? = runCatching {
        @Suppress("DEPRECATION")
        android.location.Geocoder(context, Locale.getDefault())
            .getFromLocation(lat, lon, 1)
            ?.firstOrNull()
            ?.adminArea
            ?.let { UsStates.abbreviationFor(it) }
    }.getOrNull()

    // --- Shared plumbing -----------------------------------------------------------------------

    /** Blocking HTTP GET (call within an IO context, as [fetch] already ensures). Returns the raw
     * body, or null on any error - every caller above turns a null into a worded "couldn't reach"
     * sentence rather than propagating an exception or a silent empty result. */
    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                for ((k, v) in headers) setRequestProperty(k, v)
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private val OFFSET_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mma", Locale.US)

    /** `"2026-08-22T07:00:00-05:00"` -> `"7:00am"`, rendered in the alert's OWN offset (not the
     * device's zone) since that offset already reflects where the alert applies - reformatting it
     * into the device's zone would only be correct when the device happens to BE there, which for a
     * spoken hazard time is exactly the case that must never be assumed silently wrong. Null on any
     * unparseable or missing input; callers speak the bare event name in that case rather than a
     * broken time. */
    private fun formatOffsetClock(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching { OffsetDateTime.parse(iso).format(OFFSET_CLOCK).lowercase(Locale.US) }.getOrNull()
    }

    /** FEMA's `declarationDate` is a plain UTC instant (`"...Z"`) - reuses the project's own
     * [com.kevin.legion.util.documentDate] (UTC, `"MMM d, yyyy"`) rather than a third date format,
     * matching that function's own stated posture for a date a document itself states rather than an
     * instant the device measured. */
    private fun formatFemaDate(iso: String): String? =
        runCatching { com.kevin.legion.util.documentDate(Instant.parse(iso).toEpochMilli()) }.getOrNull()

    private fun formatMagnitude(mag: Double): String =
        String.format(Locale.US, "%.1f", mag)

    /** Great-circle distance in miles. Kept local, same posture as
     * [com.kevin.legion.location.GeofenceManager]'s own private `haversineMeters` - no Android
     * `Location` dependency, so it's testable in a plain JUnit test with no shadow. */
    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMiles * c
    }

    private val COMPASS_POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** 16-point compass bearing FROM (lat1, lon1) TO (lat2, lon2), e.g. "NE". */
    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): String {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
            sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        val degrees = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        val index = ((degrees / 22.5) + 0.5).toInt() % COMPASS_POINTS.size
        return COMPASS_POINTS[index]
    }

}
