package com.kevin.legion.conditions

import android.content.Context
import android.location.Location
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineMcpClient
import com.kevin.legion.backend.engine.McpCallOutcome
import org.json.JSONArray
import org.json.JSONObject

/** One section of the engine's `check_conditions` answer: its kind, one of `found` / `empty` /
 * `failed` / `not_configured`, and the sentence that already names its source and time. */
data class ConditionsSection(val kind: String, val status: String, val text: String)

/** One NWS alert the engine judged severe (Severe, Extreme, or a hurricane watch or warning). */
data class SevereAlert(
    val id: String,
    val event: String,
    val severity: String,
    val headline: String,
    val area: String,
    val sender: String,
    val ends: String,
)

/** Where conditions were asked about: the phone's own fix, or a saved place by label. */
sealed interface ConditionsPoint {
    data class Here(val lat: Double, val lon: Double) : ConditionsPoint

    data class Place(val label: String) : ConditionsPoint
}

sealed interface ConditionsOutcome {
    /** The engine answered. [text] is the whole answer, in the engine's words. */
    data class Read(
        val text: String,
        val sections: List<ConditionsSection>,
        val severe: List<SevereAlert>,
    ) : ConditionsOutcome

    /** Nothing was read; [sentence] says why (engine down, not signed in, every source failed). */
    data class Failed(val sentence: String) : ConditionsOutcome
}

/**
 * The phone's one door to the engine's `check_conditions` tool (round 5, engine-first): the
 * Conditions screen, the severe-alert watcher and (through `ask_engine`) the voice path all read
 * the same engine tool, so the three cannot disagree (ADR 0035). The phone keeps no copy; offline,
 * every caller gets [ConditionsOutcome.Failed] with the engine client's own sentence.
 */
class ConditionsController(private val client: EngineMcpClient) {

    suspend fun check(
        point: ConditionsPoint,
        kinds: List<String> = emptyList(),
        severeOnly: Boolean = false,
    ): ConditionsOutcome {
        val args = JSONObject()
        when (point) {
            is ConditionsPoint.Here -> args.put("lat", point.lat).put("lon", point.lon)
            is ConditionsPoint.Place -> args.put("place", point.label)
        }
        if (kinds.isNotEmpty()) args.put("kinds", JSONArray(kinds))
        if (severeOnly) args.put("severe_only", true)
        return when (val outcome = client.callTool(TOOL, args, writes = false)) {
            is McpCallOutcome.Failed -> ConditionsOutcome.Failed(outcome.failure.sentence)
            is McpCallOutcome.Called -> {
                val result = outcome.result
                if (result.isError) {
                    ConditionsOutcome.Failed(result.text)
                } else {
                    ConditionsOutcome.Read(result.text, sections(result.structured), severe(result.structured))
                }
            }
        }
    }

    companion object {
        const val TOOL = "check_conditions"

        fun forContext(context: Context): ConditionsController {
            val config = EngineConfig(context.applicationContext)
            return ConditionsController(EngineMcpClient(EngineHttp(config), config::baseUrl))
        }

        internal fun sections(structured: JSONObject?): List<ConditionsSection> {
            val rows = structured?.optJSONArray("sections") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                rows.optJSONObject(i)?.let {
                    ConditionsSection(it.optString("kind"), it.optString("status"), it.optString("text"))
                }
            }
        }

        /** Every alert row the engine marked `severe`, from the `alerts` section only. */
        internal fun severe(structured: JSONObject?): List<SevereAlert> {
            val rows = structured?.optJSONArray("sections") ?: return emptyList()
            val out = mutableListOf<SevereAlert>()
            for (i in 0 until rows.length()) {
                val section = rows.optJSONObject(i) ?: continue
                if (section.optString("kind") != "alerts") continue
                val alerts = section.optJSONArray("rows") ?: continue
                for (j in 0 until alerts.length()) {
                    val a = alerts.optJSONObject(j) ?: continue
                    if (!a.optBoolean("severe") || a.optString("id").isBlank()) continue
                    out += SevereAlert(
                        id = a.optString("id"),
                        event = a.optString("event"),
                        severity = a.optString("severity"),
                        headline = a.optString("headline"),
                        area = a.optString("area"),
                        sender = a.optString("sender"),
                        ends = a.optString("ends").ifBlank { a.optString("expires") },
                    )
                }
            }
            return out
        }
    }
}

/** Words about the phone's fix, for the `ask_engine` sub-agent (never the Live setup payload). */
object ConditionsWords {
    private const val MS_PER_MIN = 60_000L

    /** "lat 30.05012, lon -98.85000 (about 12 m, 3 min old)", or blank with no fix. */
    fun fix(location: Location?, nowMs: Long = System.currentTimeMillis()): String =
        location?.let {
            fixText(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else null, nowMs - it.time)
        } ?: ""

    fun fixText(lat: Double, lon: Double, accuracyM: Float?, ageMs: Long): String {
        val acc = accuracyM?.let { "about ${it.toInt()} m, " } ?: ""
        val minutes = (ageMs.coerceAtLeast(0L) / MS_PER_MIN)
        return "lat %.5f, lon %.5f (%s%d min old)".format(java.util.Locale.US, lat, lon, acc, minutes)
    }
}
