package com.kevin.midnightai.fleetupload

import com.kevin.midnightai.data.local.CodeEvent
import com.kevin.midnightai.data.local.Drive
import com.kevin.midnightai.data.local.OdbSample
import com.kevin.midnightai.data.local.Vehicle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins every payload field name against `server/api/fleet.py`'s serializers. */
class FleetUploadWireTest {
    private fun sample(value: Double = 41.5, pid: String = "0104", lat: Double? = 29.7, lng: Double? = -95.3) =
        OdbSample(id = 9, vehicleId = "AA:BB:CC:DD:EE:FF", pid = pid, value = value, unit = "%", timestamp = 1_700_000_000_000L, lat = lat, lng = lng)

    @Test fun `an OBD sample uses the batch route's field names`() {
        val o = FleetUploadWire.obdSample("engine-uuid", sample())!!
        assertEquals(
            setOf("vehicle_id", "pid", "value", "unit", "recorded_at", "lat", "lng"),
            o.keys,
        )
        assertEquals("engine-uuid", o["vehicle_id"]!!.jsonPrimitive.content)
        assertEquals("0104", o["pid"]!!.jsonPrimitive.content)
        assertEquals(41.5, o["value"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("%", o["unit"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20Z", o["recorded_at"]!!.jsonPrimitive.content)
        assertEquals(29.7, o["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun `a sample with no fix sends null lat and lng, not zero`() {
        val o = FleetUploadWire.obdSample("e", sample(lat = null, lng = null))!!
        assertEquals(JsonNull, o["lat"])
        assertEquals(JsonNull, o["lng"])
    }

    @Test fun `a sample the server would refuse the whole batch over is left out`() {
        assertNull(FleetUploadWire.obdSample("e", sample(value = Double.NaN)))
        assertNull(FleetUploadWire.obdSample("e", sample(value = Double.POSITIVE_INFINITY)))
        assertNull(FleetUploadWire.obdSample("e", sample(pid = " ")))
    }

    @Test fun `a code event uses code_events field names with codes as an array`() {
        val e = CodeEvent(
            id = 3, vehicleId = "v", timestamp = 1_700_000_000_000L, mileage = 120_500,
            codesJson = """["P0420","P0128"]""", freezeFrameJson = """{"0105":88.0}""", syncId = "sync-1",
        )
        val o = Json.parseToJsonElement(FleetUploadWire.codeEvent("engine-uuid", e)!!).jsonObject
        assertEquals(setOf("vehicle_id", "occurred_at", "mileage", "codes", "freeze_frame"), o.keys)
        assertTrue(o["codes"] is JsonArray)
        assertEquals(2, (o["codes"] as JsonArray).size)
        assertEquals(88.0, o["freeze_frame"]!!.jsonObject["0105"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("120500", o["mileage"]!!.jsonPrimitive.content)
    }

    @Test fun `no freeze frame is null and unreadable codes are refused`() {
        val noFf = CodeEvent(id = 1, vehicleId = "v", timestamp = 1L, codesJson = "[]", freezeFrameJson = "", syncId = "s")
        val o = Json.parseToJsonElement(FleetUploadWire.codeEvent("e", noFf)!!).jsonObject
        assertEquals(JsonNull, o["freeze_frame"])
        assertEquals(JsonNull, o["mileage"])
        assertNull(FleetUploadWire.codeEvent("e", noFf.copy(codesJson = "\"P0420\"")))
        assertNull(FleetUploadWire.codeEvent("e", noFf.copy(codesJson = "not json")))
    }

    @Test fun `a drive uses the drives field names and keeps null gallons null`() {
        val d = Drive(id = 1, vehicleId = "v", startedAt = 1_700_000_000_000L, endedAt = 1_700_000_600_000L, miles = 4.2, gallons = null, syncId = "s")
        val o = Json.parseToJsonElement(FleetUploadWire.drive("engine-uuid", d)!!).jsonObject
        assertEquals(setOf("vehicle_id", "started_at", "ended_at", "miles", "gallons", "end_reason"), o.keys)
        assertEquals("2023-11-14T22:13:20Z", o["started_at"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:23:20Z", o["ended_at"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, o["gallons"])
        assertEquals("ENGINE_OFF", o["end_reason"]!!.jsonPrimitive.content)
        assertEquals("0.25", Json.parseToJsonElement(FleetUploadWire.drive("e", d.copy(gallons = 0.25))!!).jsonObject["gallons"]!!.jsonPrimitive.content)
    }

    @Test fun `a drive the server would refuse is left out`() {
        val d = Drive(id = 1, vehicleId = "v", startedAt = 2000L, endedAt = 1000L, miles = 4.2, syncId = "s")
        assertNull(FleetUploadWire.drive("e", d))
        assertNull(FleetUploadWire.drive("e", d.copy(startedAt = 1000L, endedAt = 2000L, miles = -1.0)))
        assertNull(FleetUploadWire.drive("e", d.copy(startedAt = 1000L, endedAt = 2000L, gallons = Double.NaN)))
    }

    @Test fun `a blank sync id gets a stable stand-in`() {
        val e = CodeEvent(id = 1, vehicleId = "v", timestamp = 77L, codesJson = "[]", syncId = "")
        assertEquals("headunit:v:77", FleetUploadWire.codeEventSyncId(e))
        assertEquals(FleetUploadWire.codeEventSyncId(e), FleetUploadWire.codeEventSyncId(e))
    }

    @Test fun `a vehicle create body uses the vehicles field names and omits the odometer`() {
        val v = Vehicle(obdMac = "AA:BB:CC:DD:EE:FF", name = "XJ", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true, trim = "Sport")
        val o = Json.parseToJsonElement(FleetUploadWire.vehicleCreate(v, "AA:BB:CC:DD:EE:FF")).jsonObject
        assertEquals(
            setOf("name", "make", "model", "year", "trim", "engine", "confirmed", "archived", "last_obd_mac"),
            o.keys,
        )
        assertEquals("1998", o["year"]!!.jsonPrimitive.content)
        assertEquals("AA:BB:CC:DD:EE:FF", o["last_obd_mac"]!!.jsonPrimitive.content)
        assertEquals("false", o["archived"]!!.jsonPrimitive.content)
        val archived = Json.parseToJsonElement(FleetUploadWire.vehicleCreate(v.copy(archived = true), null)).jsonObject
        assertEquals("true", archived["archived"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, archived["last_obd_mac"])
    }

    @Test fun `only a confirmed, fully named, in range car may be created`() {
        val ok = Vehicle(obdMac = "x", name = "XJ", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true)
        assertNull(FleetUploadWire.vehicleCreateRefusal(ok))
        assertNotNull(FleetUploadWire.vehicleCreateRefusal(ok.copy(confirmed = false)))
        assertNotNull(FleetUploadWire.vehicleCreateRefusal(ok.copy(name = " ")))
        assertNotNull(FleetUploadWire.vehicleCreateRefusal(ok.copy(year = 1700)))
    }
}
