package com.kevin.legion.location

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `get_reported_crime_history` (map decision 7, ticket 02 §E). A SEPARATE tool from [AreaInfo] -
 * different shape of answer, a historical count bound to a named agency and year, not a live
 * condition.
 *
 * **`is_area_safe` is never built, here or anywhere** - see
 * `.scratch/hands-and-senses/research/14-location-intel.md` §5's "honesty verdict": the FBI Crime
 * Data Explorer's finest unit is a whole reporting AGENCY's jurisdiction (a city PD, a county
 * sheriff, a campus force - never a block or neighborhood), its freshest COMPLETE year runs roughly
 * 13 months behind, and participation is voluntary and incomplete (~87% of the covered population
 * via NIBRS, per the FBI's own 2024 release). None of that supports an individual "is it safe"
 * judgement, and CLAUDE.md §4 rule 5 forbids surfacing an inference the source itself does not make
 * as if it were fact - not even hedged as an "estimate", because there is no estimate to make. This
 * tool ships ONLY the honest, narrower question: what has this agency reported, historically.
 *
 * Source is the FBI Crime Data Explorer (`api.usa.gov/crime/fbi/cde`), via an `api.data.gov`
 * `DEMO_KEY` - works for exploration per research §5 (`live`-confirmed 2026-08-21); a real free
 * self-service key is a straightforward future upgrade, never required to ship this.
 */
object CrimeHistory {
    private const val TAG = "CrimeHistory"
    private const val TIMEOUT_MS = 10_000
    private const val API_KEY = "DEMO_KEY"
    private const val BASE_URL = "https://api.usa.gov/crime/fbi/cde"

    /** The two offense families FBI CDE's summarized endpoint distinguishes (confirmed live against
     * both real path segments 2026-08-21). Property crime is the other half of what a driver is
     * likely to actually ask about ("has there been much break-in trouble around here") - violent
     * crime alone would silently answer a narrower question than the one asked. */
    enum class OffenseType(val pathSegment: String, val spoken: String) {
        VIOLENT("violent-crime", "violent crime"),
        PROPERTY("property-crime", "property crime"),
    }

    /**
     * Resolves the live fix to a state, finds the nearest (or named, via [place]) reporting agency
     * in it, and reports that agency's most recent COMPLETE calendar year of [offenseType] offenses.
     * Never claims safety - the caller (`LiveToolbox`'s tool description) is what keeps a driver who
     * asks "is it safe here" routed to a plain refusal before this ever runs; this function only
     * ever answers the narrower, honest question.
     *
     * Degrades in words at every step: no state resolvable, no agency found (by name or by
     * proximity), or the network/parse itself failing are three different worded outcomes, never a
     * collapsed blank.
     */
    suspend fun historyNear(
        context: Context,
        lat: Double,
        lon: Double,
        place: String?,
        offenseType: OffenseType,
    ): String = withContext(Dispatchers.IO) {
        val abbr = stateAbbreviation(context, lat, lon)
            ?: return@withContext "Couldn't tell which state that is, so I can't look up crime history."

        val agenciesBody = httpGet("$BASE_URL/agency/byStateAbbr/$abbr?API_KEY=$API_KEY")
            ?: return@withContext "Couldn't reach the FBI's Crime Data Explorer to check right now."
        val agency = pickAgency(flattenAgencies(agenciesBody), place, lat, lon)
            ?: return@withContext if (place.isNullOrBlank()) {
                "The FBI's Crime Data Explorer doesn't list a reporting agency near there."
            } else {
                "I couldn't find a reporting agency matching \"$place\" in the FBI's Crime Data Explorer."
            }

        val ori = agency.optString("ori")
        val agencyName = agency.optString("agency_name").ifBlank { "That agency" }
        if (ori.isBlank()) return@withContext "Couldn't identify $agencyName's reporting record to look up."

        val summarizedBody = httpGet(
            "$BASE_URL/summarized/agency/$ori/${offenseType.pathSegment}" +
                "?from=01-2015&to=12-2030&type=counts&API_KEY=$API_KEY",
        ) ?: return@withContext "Couldn't reach the FBI's Crime Data Explorer to check right now."

        val yearTotal = latestCompleteYearTotal(summarizedBody)
            ?: return@withContext "$agencyName hasn't reported a complete year of ${offenseType.spoken} data yet."

        "FBI Crime Data Explorer: $agencyName reported ${yearTotal.second} ${offenseType.spoken} " +
            "offenses in ${yearTotal.first}, the most recent complete year on file. This is " +
            "agency-level, voluntarily-reported data, roughly a year behind, and it does not " +
            "measure how safe any specific place is."
    }

    // --- Agency resolution -----------------------------------------------------------------

    /** `byStateAbbr` returns a dict keyed by COUNTY NAME, each value a JSON array of agency
     * objects (confirmed live 2026-08-21) - flattens that into one list, county grouping discarded
     * since [pickAgency] matches on `agency_name`/`counties` directly off each agency row. */
    internal fun flattenAgencies(agenciesBody: String): List<JSONObject> {
        val root = runCatching { JSONObject(agenciesBody) }.getOrNull() ?: return emptyList()
        val flat = mutableListOf<JSONObject>()
        for (countyKey in root.keys()) {
            val arr = root.optJSONArray(countyKey) ?: continue
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { flat += it }
            }
        }
        return flat
    }

    /**
     * With a [place] given (the user named one, e.g. "Houston" or "Harris County"), matches against
     * every agency's own `agency_name`/`counties` (case-insensitive substring) and, among matches,
     * returns the nearest to the live fix - resolves an ambiguous common name (e.g. multiple
     * "Springfield"-named agencies) without inventing a tie-break the user didn't ask for. Returns
     * null (a genuine "not found") rather than silently falling back to nearest-overall when a named
     * place matches nothing - a driver who named a specific place and got an unrelated agency's
     * figures back would be a worse failure than an honest "couldn't find that".
     *
     * With no [place], picks the single nearest agency in the state to the live fix - each agency
     * row carries its own jurisdiction's `latitude`/`longitude` (confirmed live), which is the
     * agency's location, never an offense location (research §5's granularity warning).
     */
    internal fun pickAgency(agencies: List<JSONObject>, place: String?, lat: Double, lon: Double): JSONObject? {
        if (agencies.isEmpty()) return null
        if (!place.isNullOrBlank()) {
            val needle = place.trim().lowercase(Locale.US)
            // `counties` on the real feed is a bare county name ("HARRIS", "LEE") with no "County"
            // suffix (confirmed live 2026-08-21), but a person naturally says "Harris County" - so
            // the county side of the match strips that suffix before comparing. agency_name keeps
            // the FULL needle: an agency can legitimately have "County" IN its own name (e.g. "Lee
            // County Sheriff's Office"), so stripping there would just as easily break a match.
            val countyNeedle = needle.removeSuffix(" county").trim()
            val matches = agencies.filter {
                it.optString("agency_name").lowercase(Locale.US).contains(needle) ||
                    it.optString("counties").lowercase(Locale.US).contains(countyNeedle)
            }
            return matches.minByOrNull {
                haversineMiles(lat, lon, it.optDouble("latitude"), it.optDouble("longitude"))
            }
        }
        return agencies.minByOrNull {
            haversineMiles(lat, lon, it.optDouble("latitude"), it.optDouble("longitude"))
        }
    }

    // --- Historical figure -------------------------------------------------------------------

    /**
     * FBI CDE's summarized endpoint keys its monthly series by an AGENCY-NAME-DEPENDENT string
     * ("`<Agency Name> Offenses`" vs. "`<Agency Name> Clearances`" - confirmed live 2026-08-21), so
     * this locates the "Offenses" series by its fixed suffix rather than hardcoding any agency name.
     * Values are `"MM-YYYY" -> Int?`, `null` for months not yet published. Returns
     * (year, summed total) for the most recent calendar year with all TWELVE months present and
     * non-null - a partial year summed and spoken as if complete would understate the true total
     * and read as good news that isn't. Null if no year is ever complete (a brand-new agency, or an
     * unparseable response).
     */
    internal fun latestCompleteYearTotal(summarizedBody: String): Pair<Int, Int>? {
        val root = runCatching { JSONObject(summarizedBody) }.getOrNull() ?: return null
        val actuals = root.optJSONObject("offenses")?.optJSONObject("actuals") ?: return null
        val offensesKey = actuals.keys().asSequence().firstOrNull { it.endsWith(" Offenses") } ?: return null
        val monthly = actuals.optJSONObject(offensesKey) ?: return null

        val byYear = mutableMapOf<Int, MutableMap<Int, Int>>()
        for (key in monthly.keys()) {
            if (monthly.isNull(key)) continue
            val (monthStr, yearStr) = key.split("-").takeIf { it.size == 2 } ?: continue
            val month = monthStr.toIntOrNull() ?: continue
            val year = yearStr.toIntOrNull() ?: continue
            val count = monthly.optInt(key, -1)
            if (count < 0) continue
            byYear.getOrPut(year) { mutableMapOf() }[month] = count
        }
        val completeYear = byYear.entries.filter { it.value.size == 12 }.maxByOrNull { it.key } ?: return null
        return completeYear.key to completeYear.value.values.sum()
    }

    // --- Shared plumbing ---------------------------------------------------------------------

    private fun stateAbbreviation(context: Context, lat: Double, lon: Double): String? = runCatching {
        @Suppress("DEPRECATION")
        android.location.Geocoder(context, Locale.getDefault())
            .getFromLocation(lat, lon, 1)
            ?.firstOrNull()
            ?.adminArea
            ?.let { UsStates.abbreviationFor(it) }
    }.getOrNull()

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

    /** Great-circle distance in miles. Local copy, same posture as [AreaInfo]'s own private
     * `haversineMiles` and [com.kevin.legion.location.GeofenceManager]'s `haversineMeters` - kept
     * per-file rather than shared so each stays a self-contained, independently testable unit. */
    private fun haversineMiles(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMiles = 3958.8
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMiles * c
    }
}
