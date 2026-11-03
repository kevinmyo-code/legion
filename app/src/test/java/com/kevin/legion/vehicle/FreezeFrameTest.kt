package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CodeClearEvent
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.util.TempUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-03, Kevin: "i asked for freeze frames". `get_code_history` passed three of the eight
 * stored PIDs as a bracketed string and said nothing when a code had no snapshot. Every reading now
 * reaches the model as `{parameter, value, unit}`, and a missing one is said in words.
 */
class FreezeFrameTest {

    private val full = JSONObject()
        .put("rpm", 2112.5).put("coolant_c", 88.0).put("speed_kmh", 64.0).put("load_pct", 41.2)
        .put("maf_gs", 12.34).put("stft_pct", -3.1).put("ltft_pct", 7.8).put("iat_c", 31.0)
        .put("map_kpa", 52.0).put("fuel_sys", 2.0)
        .toString()

    @Test
    fun `every stored reading comes back with its unit`() {
        val f = FreezeFrame.fields(full, TempUnit.CELSIUS)
        assertEquals(
            listOf(
                FreezeFrameField("Engine speed", "2113", "rpm"),
                FreezeFrameField("Coolant temp", "88", "°C"),
                FreezeFrameField("Vehicle speed", "40", "mph"),
                FreezeFrameField("Engine load", "41", "%"),
                FreezeFrameField("Mass air flow", "12.3", "g/s"),
                FreezeFrameField("Short-term fuel trim", "-3.1", "%"),
                FreezeFrameField("Long-term fuel trim", "+7.8", "%"),
                FreezeFrameField("Intake air temp", "31", "°C"),
                FreezeFrameField("Manifold pressure", "52", "kPa"),
                FreezeFrameField("Fuel system", "Closed loop", ""),
            ),
            f,
        )
        assertEquals("190", FreezeFrame.fields(full, TempUnit.FAHRENHEIT)[1].value)
    }

    @Test
    fun `absent keys are omitted, unknown keys are kept, blank is empty`() {
        val f = FreezeFrame.fields("""{"rpm":800,"baro_kpa":101}""", TempUnit.CELSIUS)
        assertEquals(listOf("Engine speed", "baro_kpa"), f.map { it.name })
        assertTrue(FreezeFrame.fields("", TempUnit.CELSIUS).isEmpty())
        assertTrue(FreezeFrame.fields("not json", TempUnit.CELSIUS).isEmpty())
    }

    private fun event(id: Long, ts: Long, codes: String, ff: String) =
        CodeEvent(
            id = id, vehicleId = "car:jeep", timestamp = ts, mileage = 187_400,
            codesJson = codes, freezeFrameJson = ff,
        )

    @Test
    fun `a code history row hands the model the whole freeze frame`() {
        val row = CarToolbelt.codeEventJson(
            event(1, 1_000, """["P0420"]""", full),
            mapOf("P0420" to ("Catalyst efficiency below threshold" to "")),
            emptyList(),
            TempUnit.CELSIUS,
        )
        val ff = row.getJSONArray("freezeFrame")
        assertEquals(10, ff.length())
        assertEquals("Engine speed", ff.getJSONObject(0).getString("parameter"))
        assertEquals("2113", ff.getJSONObject(0).getString("value"))
        assertEquals("rpm", ff.getJSONObject(0).getString("unit"))
        val meaning = row.getJSONArray("codes").getJSONObject(0).getString("meaning")
        assertEquals("Catalyst efficiency below threshold", meaning)
        assertEquals("about 187,400 mi (estimated)", row.getString("mileage"))
        assertFalse(row.has("snapshot"))
        assertFalse(row.getBoolean("cleared"))
    }

    @Test
    fun `a code with no freeze frame says so instead of inventing one`() {
        val clear = CodeClearEvent(
            vehicleId = "car:jeep", timestamp = 2_000, outcome = "CLEARED", codesBeforeJson = "[\"P1282\"]",
        )
        val noFrame = event(2, 1_000, """["P1282"]""", "")
        val row = CarToolbelt.codeEventJson(noFrame, emptyMap(), listOf(clear), TempUnit.CELSIUS)
        assertTrue(row.isNull("freezeFrame"))
        assertEquals("No snapshot was saved for this code.", row.getString("snapshot"))
        assertFalse(row.getJSONArray("codes").getJSONObject(0).has("meaning"))
        assertTrue(row.getBoolean("cleared"))
    }
}
