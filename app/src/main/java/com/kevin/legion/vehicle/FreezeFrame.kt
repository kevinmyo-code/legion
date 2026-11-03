package com.kevin.legion.vehicle

import com.kevin.legion.util.Temp
import com.kevin.legion.util.TempUnit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One freeze-frame reading as a table row: [name] in words ("Coolant temp"), [value] the number or
 * state, [unit] its unit ("°C", "rpm", "%"; blank for a state like the fuel system's).
 */
data class FreezeFrameField(val name: String, val value: String, val unit: String)

/**
 * The ONE formatter for a [com.kevin.legion.data.local.CodeEvent.freezeFrameJson] (2026-11-03,
 * Kevin: "i asked for freeze frames"). The voice tools and the codes card both read it, so what
 * the assistant says and what the screen shows cannot disagree.
 *
 * Before this, the spoken path (`CarToolbelt.freezeHighlights`) passed three of the eight stored
 * PIDs to the model as a bracketed string, so speed, MAF and both fuel trims never reached it.
 *
 * Every key the frame carries comes back, in [ObdBluetoothManager.getFreezeFrame]'s PID order; a
 * key it does not know is still returned, raw, never dropped. A key ABSENT from the frame is
 * omitted, never shown as zero. Blank or unparseable JSON gives an empty list, which callers say
 * in words as [NO_SNAPSHOT].
 */
object FreezeFrame {

    /** Said and shown when a code event has no freeze frame. Never an invented value. */
    const val NO_SNAPSHOT = "No snapshot was saved for this code."

    private const val KMH_TO_MPH = 0.621371

    @Suppress("ReturnCount")
    fun fields(json: String, tempUnit: TempUnit): List<FreezeFrameField> {
        if (json.isBlank()) return emptyList()
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<FreezeFrameField>()
        val seen = mutableSetOf<String>()
        fun num(key: String): Double? =
            if (o.has(key)) o.optDouble(key, Double.NaN).takeUnless { it.isNaN() } else null
        fun add(key: String, build: (Double) -> FreezeFrameField) {
            num(key)?.let { out.add(build(it)); seen.add(key) }
        }
        fun temp(name: String) = { c: Double ->
            FreezeFrameField(name, "%.0f".format(Temp.convert(c, tempUnit)), tempUnit.symbol)
        }
        add("rpm") { FreezeFrameField("Engine speed", it.roundToInt().toString(), "rpm") }
        add("coolant_c", temp("Coolant temp"))
        add("speed_kmh") { FreezeFrameField("Vehicle speed", (it * KMH_TO_MPH).roundToInt().toString(), "mph") }
        add("load_pct") { FreezeFrameField("Engine load", it.roundToInt().toString(), "%") }
        add("maf_gs") { FreezeFrameField("Mass air flow", "%.1f".format(it), "g/s") }
        add("stft_pct") { FreezeFrameField("Short-term fuel trim", "%+.1f".format(it), "%") }
        add("ltft_pct") { FreezeFrameField("Long-term fuel trim", "%+.1f".format(it), "%") }
        add("iat_c", temp("Intake air temp"))
        add("map_kpa") { FreezeFrameField("Manifold pressure", it.roundToInt().toString(), "kPa") }
        add("fuel_sys") { FreezeFrameField("Fuel system", fuelSystemWords(it.toInt()), "") }
        for (key in o.keys()) {
            if (key !in seen) out.add(FreezeFrameField(key, o.opt(key).toString(), ""))
        }
        return out
    }

    /** SAE J1979 PID 03 byte A, in words. An unknown bit pattern is shown as its raw value. */
    @Suppress("MagicNumber") // the SAE J1979 PID 03 bit values themselves
    internal fun fuelSystemWords(state: Int): String = when (state) {
        0 -> "Not reported"
        1 -> "Open loop, not warm yet"
        2 -> "Closed loop"
        4 -> "Open loop, load or decel"
        8 -> "Open loop, system fault"
        16 -> "Closed loop, sensor fault"
        else -> "Raw state $state"
    }

    /** [fields] as the JSON array a voice tool hands the model: `[{parameter, value, unit}]`. */
    fun toJson(fields: List<FreezeFrameField>): JSONArray = JSONArray().apply {
        for (f in fields) put(JSONObject().put("parameter", f.name).put("value", f.value).put("unit", f.unit))
    }

    /** One line for a spoken summary: "Engine speed 2100 rpm, Coolant temp 88°C, ...". */
    fun line(fields: List<FreezeFrameField>): String =
        fields.joinToString(", ") { f ->
            when {
                f.unit.isBlank() -> "${f.name} ${f.value}"
                f.unit.startsWith("°") || f.unit == "%" -> "${f.name} ${f.value}${f.unit}"
                else -> "${f.name} ${f.value} ${f.unit}"
            }
        }
}
