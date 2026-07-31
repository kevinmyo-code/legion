package com.kevin.legion.vehicle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Decodes a VIN into year / make / model / trim via the NHTSA vPIC API (free,
 * no API key). This is a separate, deterministic lookup - NOT the main Live STS
 * agent and not even a [com.kevin.legion.ai.SubAgent] - because VIN decoding is an
 * exact database lookup, not reasoning: an LLM would be slower and could
 * hallucinate. The Live tool / settings UI calls this on demand so the driver
 * can choose to fill the car's facts from the connected OBD adapter instead of
 * typing them (typing stays the default; many users have no dongle).
 *
 * vPIC is US-market-centric, so coverage on grey-market JDM/Euro imports can be
 * partial - callers treat the result as a confirmable suggestion, never a silent
 * overwrite of driver-entered facts.
 */
object VinDecoder {

    private const val TAG = "VinDecoder"
    private const val VPIC_URL =
        "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues"
    private const val RECALLS_URL =
        "https://api.nhtsa.gov/recalls/recallsByVehicle"

    data class DecodedVin(
        val vin: String,
        val year: Int,
        val make: String,
        val model: String,
        val trim: String,
    ) {
        /** True when at least make + model came back - enough to be useful. */
        val isUsable: Boolean get() = make.isNotBlank() && model.isNotBlank()
    }

    /** The fuller factory-spec field set from vPIC, for the build-details encyclopedia. */
    data class VinSpecs(
        val vin: String,
        val engineCylinders: Int?,
        val displacementL: Double?,
        val engineHp: Int?,
        val engineConfig: String,
        val fuelType: String,
        val transmissionStyle: String,
        val transmissionSpeeds: String,
        val driveType: String,
        val bodyClass: String,
        val doors: Int?,
        val series: String,
        val vehicleType: String,
        val manufacturer: String,
        val plantCity: String,
        val plantCountry: String,
    )

    /** One active NHTSA recall, for on-request reporting (never stored). */
    data class Recall(
        val campaign: String,
        val component: String,
        val summary: String,
        val remedy: String,
    )

    /** Reads the VIN off the connected OBD adapter and decodes it; null if either step fails. */
    suspend fun fromObd(): DecodedVin? {
        if (!ObdBluetoothManager.isConnected) return null
        val vin = ObdBluetoothManager.getVin() ?: return null
        return decode(vin)
    }

    /** Decodes a 17-char [vin] via vPIC into the lean year/make/model/trim. Null on failure. */
    suspend fun decode(vin: String): DecodedVin? = withContext(Dispatchers.IO) {
        val clean = vin.trim().uppercase()
        if (clean.length != 17) return@withContext null
        val raw = httpGet("$VPIC_URL/$clean?format=json") ?: return@withContext null
        parse(clean, raw)
    }

    /** Decodes a 17-char [vin] via vPIC into the fuller factory-spec set. Null on failure. */
    suspend fun decodeSpecs(vin: String): VinSpecs? = withContext(Dispatchers.IO) {
        val clean = vin.trim().uppercase()
        if (clean.length != 17) return@withContext null
        val raw = httpGet("$VPIC_URL/$clean?format=json") ?: return@withContext null
        parseSpecs(clean, raw)
    }

    /**
     * Fetches active NHTSA recalls for a year/make/model (not by VIN - the free
     * recalls endpoint is keyed by year/make/model). Returns an empty list if
     * none / on failure.
     */
    suspend fun fetchRecalls(year: Int, make: String, model: String): List<Recall> = withContext(Dispatchers.IO) {
        if (year <= 0 || make.isBlank() || model.isBlank()) return@withContext emptyList()
        val url = "$RECALLS_URL?make=${enc(make)}&model=${enc(model)}&modelYear=$year"
        val raw = httpGet(url) ?: return@withContext emptyList()
        parseRecalls(raw)
    }

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s.trim(), "UTF-8")

    /** Blocking HTTP GET (call within an IO context). Returns the body, or null on any error. */
    private fun httpGet(urlStr: String): String? {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 15000
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
            Log.w(TAG, "HTTP GET failed: ${e.message}")
            null
        }
    }

    private fun parse(vin: String, json: String): DecodedVin? {
        return try {
            val result = JSONObject(json).optJSONArray("Results")?.optJSONObject(0)
                ?: return null
            val year = result.optString("ModelYear").toIntOrNull() ?: 0
            val make = result.optString("Make").titleCaseIfShouty()
            val model = result.optString("Model").trim()
            val trim = result.optString("Trim").trim()
            val decoded = DecodedVin(vin, year, make, model, trim)
            decoded.takeIf { it.isUsable }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse vPIC response: ${e.message}")
            null
        }
    }

    private fun parseSpecs(vin: String, json: String): VinSpecs? {
        return try {
            val r = JSONObject(json).optJSONArray("Results")?.optJSONObject(0) ?: return null
            VinSpecs(
                vin = vin,
                engineCylinders = field(r, "EngineCylinders").toIntOrNull(),
                displacementL = field(r, "DisplacementL").toDoubleOrNull(),
                engineHp = field(r, "EngineHP").toDoubleOrNull()?.toInt(),
                engineConfig = field(r, "EngineConfiguration"),
                fuelType = field(r, "FuelTypePrimary"),
                transmissionStyle = field(r, "TransmissionStyle"),
                transmissionSpeeds = field(r, "TransmissionSpeeds"),
                driveType = field(r, "DriveType"),
                bodyClass = field(r, "BodyClass"),
                doors = field(r, "Doors").toIntOrNull(),
                series = field(r, "Series"),
                vehicleType = field(r, "VehicleType"),
                manufacturer = field(r, "Manufacturer").titleCaseIfShouty(),
                plantCity = field(r, "PlantCity").titleCaseIfShouty(),
                plantCountry = field(r, "PlantCountry").titleCaseIfShouty(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse vPIC specs: ${e.message}")
            null
        }
    }

    private fun parseRecalls(json: String): List<Recall> {
        return try {
            val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
            (0 until results.length()).mapNotNull { i ->
                val o = results.optJSONObject(i) ?: return@mapNotNull null
                Recall(
                    campaign = o.optString("NHTSACampaignNumber").trim(),
                    component = o.optString("Component").trim(),
                    summary = o.optString("Summary").trim(),
                    remedy = o.optString("Remedy").trim(),
                ).takeIf { it.component.isNotBlank() || it.summary.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse recalls: ${e.message}")
            emptyList()
        }
    }

    /** Reads a vPIC string field, treating its null-ish placeholders as blank. */
    private fun field(result: JSONObject, key: String): String {
        val v = result.optString(key).trim()
        return if (v.equals("Not Applicable", true) || v.equals("null", true) || v == "0") "" else v
    }

    /** vPIC returns makes like "BMW" / "TOYOTA"; title-case multi-letter all-caps words. */
    private fun String.titleCaseIfShouty(): String {
        val t = trim()
        // Leave short acronyms (BMW, GMC, KIA) alone; title-case longer SHOUTY words.
        return if (t.length > 3 && t == t.uppercase()) {
            t.lowercase().replaceFirstChar { it.uppercase() }
        } else t
    }
}
