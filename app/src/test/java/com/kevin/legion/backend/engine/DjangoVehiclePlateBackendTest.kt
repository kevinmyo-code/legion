package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.vehicle.PlateOutcome
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * memory-and-vault ticket 13: the vehicle row decodes with and without `plate`, and the plate
 * edit reads the engine's own row and PUTs every field of it back to its own `origin_guid`, never
 * to a guid the engine did not hand back. Plates here are made up; no real plate is ever written
 * in code or tests.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoVehiclePlateBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoVehiclePlateBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun row(originGuid: String?, plate: String? = null): String {
        val guid = originGuid?.let { "\"$it\"" } ?: "null"
        val plateField = plate?.let { "\"$it\"" } ?: "null"
        return """{"id": "v1", "name": "the wagon", "make": "Subaru", "model": "Outback", "year": 2016,
            "trim": "Limited", "engine": null, "confirmed": true, "odometer_baseline": 120000,
            "odometer_baseline_at": "2026-10-01T12:00:00Z", "provenance": "USER",
            "created_at": "2026-01-01T00:00:00Z", "updated_at": "2026-10-01T12:00:00Z", "deleted_at": null,
            "origin_guid": $guid, "archived": true, "last_obd_mac": "AA:BB:CC:DD:EE:FF", "plate": $plateField}"""
    }

    private fun page(vararg rows: String) = """{"results": [${rows.joinToString(",")}], "next": null}"""

    @Test
    fun `a vehicle row decodes with and without plate`() {
        val without = """{"id": "v1", "name": "n", "make": "m", "model": "o", "year": 2001,
            "updated_at": "2026-10-01T12:00:00Z"}"""
        assertNull(engineSyncedJson.decodeFromString(DjangoVehicleRow.serializer(), without).toRemote().plate)
        val with = engineSyncedJson.decodeFromString(DjangoVehicleRow.serializer(), row("g1", "ABC 123"))
        assertEquals("ABC 123", with.plate)
        assertEquals("ABC 123", with.toRemote().plate)
    }

    @Test
    fun `the plate PUT goes to the engine's own origin_guid and carries every field`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "GET") json(page(row("guid-1"))) else json(row("guid-1", "ABC 123"))
        }

        val outcome = backend(engine).setPlate("v1", "ABC 123")

        assertEquals(PlateOutcome.Ok("ABC 123"), outcome)
        assertEquals(2, engine.requests.size)
        assertEquals("1", engine.requests[0].url.parameters["active"])
        val put = engine.requests[1]
        assertEquals("PUT", put.method.value)
        assertEquals("/api/fleet/vehicles/guid-1/", put.url.encodedPath)
        val sent = Json.parseToJsonElement((put.body as io.ktor.http.content.TextContent).text).jsonObject
        assertEquals(
            setOf(
                "name", "make", "model", "year", "trim", "engine", "confirmed", "odometer_baseline",
                "odometer_baseline_at", "archived", "last_obd_mac", "plate",
            ),
            sent.keys,
        )
        assertEquals("ABC 123", sent["plate"]!!.jsonPrimitive.content)
        assertEquals("true", sent["archived"]!!.jsonPrimitive.content)
        assertEquals("true", sent["confirmed"]!!.jsonPrimitive.content)
        assertEquals("AA:BB:CC:DD:EE:FF", sent["last_obd_mac"]!!.jsonPrimitive.content)
        assertEquals("2026-10-01T12:00:00Z", sent["odometer_baseline_at"]!!.jsonPrimitive.content)
        assertEquals("Limited", sent["trim"]!!.jsonPrimitive.content)
    }

    @Test
    fun `clearing sends an explicit null plate`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "GET") json(page(row("guid-1", "ABC 123"))) else json(row("guid-1"))
        }

        val outcome = backend(engine).setPlate("v1", null)

        assertEquals(PlateOutcome.Ok(null), outcome)
        val body = (engine.requests[1].body as io.ktor.http.content.TextContent).text
        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals(JsonNull, sent["plate"])
    }

    @Test
    fun `a car with no origin_guid is refused and nothing is written`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(page(row(null))) }

        val outcome = backend(engine).setPlate("v1", "ABC 123")

        assertEquals(PlateOutcome.Refused(DjangoVehiclePlateBackend.NO_GUID), outcome)
        assertEquals("only the read went out", 1, engine.requests.size)
        assertEquals("GET", engine.requests.single().method.value)
    }

    @Test
    fun `a car the engine does not list is refused and nothing is written`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(page()) }

        val outcome = backend(engine).setPlate("v1", "ABC 123")

        assertTrue(outcome is PlateOutcome.Refused)
        assertTrue((outcome as PlateOutcome.Refused).sentence.contains("Nothing was changed"))
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `the engine's refusal is said with its own words`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "GET") {
                json(page(row("guid-1")))
            } else {
                json("""{"plate": ["plate is at most 16 characters; this one is 17."]}""", HttpStatusCode.BadRequest)
            }
        }

        val outcome = backend(engine).setPlate("v1", "X".repeat(17))

        val sentence = (outcome as PlateOutcome.Refused).sentence
        assertTrue(sentence, sentence.contains("at most 16 characters"))
        assertTrue(sentence, sentence.contains("Nothing was changed"))
    }

    @Test
    fun `an unreachable engine sends nothing and says so`() = runBlocking {
        val outcome = DjangoVehiclePlateBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        ).setPlate("v1", "ABC 123")

        assertTrue(outcome is PlateOutcome.Unreachable)
    }

    @Test
    fun `a 2xx that does not decode is unconfirmed, never saved`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "GET") json(page(row("guid-1"))) else json("not json")
        }

        assertTrue(backend(engine).setPlate("v1", "ABC 123") is PlateOutcome.Unconfirmed)
    }
}
