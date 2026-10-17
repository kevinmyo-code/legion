package com.kevin.midnightai.fleetupload

import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.data.local.CodeEvent
import com.kevin.midnightai.data.local.OdbSample
import com.kevin.midnightai.data.local.Vehicle
import com.kevin.midnightai.diag.UploadStatusSink
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The fleet upload against a real [MockWebServer]: batches are chunked at the server's cap, a car
 * resolves to the engine row the phone made (by guid or by dongle MAC), creates only when nothing
 * matches, refuses on ambiguity, and a cursor moves only after a 2xx.
 */
@RunWith(RobolectricTestRunner::class)
class FleetUploaderTest {
    private val mac = "AA:BB:CC:DD:EE:FF"
    private val engineId = "11111111-aaaa-bbbb-cccc-000000000001"
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Route -> response; swapped per test. */
    private var vehicleListBody = emptyList<String>()
    private var batchResponse: () -> MockResponse = { MockResponse().setResponseCode(201).setBody(RESULT) }
    private var codePutResponse: () -> MockResponse = { MockResponse().setResponseCode(200).setBody("{}") }
    private var createResponse: () -> MockResponse = {
        MockResponse().setResponseCode(200).setBody("""{"id":"$engineId"}""")
    }

    private class Sink : UploadStatusSink {
        var last: Triple<Long, Boolean, String>? = null
        override fun record(atMs: Long, ok: Boolean, sentence: String) {
            last = Triple(atMs, ok, sentence)
        }
    }

    private class MemCursors : FleetCursorStore {
        val samples = mutableMapOf<String, Long>()
        val codes = mutableMapOf<String, Long>()
        val engine = mutableMapOf<String, String>()
        override fun sampleCursor(vehicleId: String) = samples[vehicleId] ?: 0L
        override fun setSampleCursor(vehicleId: String, id: Long) { samples[vehicleId] = id }
        override fun codeEventCursor(vehicleId: String) = codes[vehicleId] ?: 0L
        override fun setCodeEventCursor(vehicleId: String, id: Long) { codes[vehicleId] = id }
        override fun engineVehicleId(vehicleId: String) = engine[vehicleId]
        override fun setEngineVehicleId(vehicleId: String, engineId: String) { engine[vehicleId] = engineId }
        override fun clearEngineVehicleId(vehicleId: String) { engine.remove(vehicleId) }
    }

    private class Source(
        val cars: List<Vehicle>,
        val samples: List<OdbSample> = emptyList(),
        val events: List<CodeEvent> = emptyList(),
    ) : FleetUploadSource {
        override suspend fun vehicles() = cars
        override suspend fun samplesAfter(vehicleId: String, afterId: Long, limit: Int) =
            samples.filter { it.vehicleId == vehicleId && it.id > afterId }.sortedBy { it.id }.take(limit)
        override suspend fun sampleCountAfter(vehicleId: String, afterId: Long) =
            samples.count { it.vehicleId == vehicleId && it.id > afterId }
        override suspend fun codeEventsAfter(vehicleId: String, afterId: Long, limit: Int) =
            events.filter { it.vehicleId == vehicleId && it.id > afterId }.sortedBy { it.id }.take(limit)
        override suspend fun codeEventCountAfter(vehicleId: String, afterId: Long) =
            events.count { it.vehicleId == vehicleId && it.id > afterId }
    }

    private val sink = Sink()
    private val cursors = MemCursors()
    private val logged = mutableListOf<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/fleet/vehicles/") && request.method == "GET" ->
                        MockResponse().setResponseCode(200)
                            .setBody("""{"results":[${vehicleListBody.joinToString(",")}],"next":null,"next_after":null}""")
                    path.startsWith("/api/fleet/vehicles/") && request.method == "PUT" -> createResponse()
                    path == "/api/fleet/obd_samples/batch/" -> batchResponse()
                    path.startsWith("/api/fleet/code_events/") -> codePutResponse()
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun stop() {
        runCatching { server.shutdown() }
    }

    private fun car(id: String = mac, confirmed: Boolean = true) = Vehicle(
        obdMac = id, name = "XJ", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = confirmed,
    )

    private fun samples(n: Int, vehicle: String = mac) =
        (1..n).map { OdbSample(id = it.toLong(), vehicleId = vehicle, pid = "010C", value = it.toDouble(), unit = "rpm", timestamp = 1_700_000_000_000L + it * 30_000L) }

    private fun engineRow(id: String, guid: String? = null, lastMac: String? = null, deleted: Boolean = false) =
        """{"id":"$id","origin_guid":${guid?.let { "\"$it\"" } ?: "null"},"last_obd_mac":${lastMac?.let { "\"$it\"" } ?: "null"},""" +
            """"deleted_at":${if (deleted) "\"2026-01-01T00:00:00Z\"" else "null"},"name":"XJ"}"""

    private fun uploader(source: FleetUploadSource, signedIn: Boolean = true, maxBatches: Int = 40): FleetUploader {
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { "ENC($it)" },
            decrypt = { it.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        if (signedIn) config.saveSession("device-token", "22222222-2222-2222-2222-222222222222") else config.clearSession()
        return FleetUploader(source, cursors, EngineHttp(config, OkHttpClient()), sink, log = { logged += it }, maxBatches = maxBatches)
    }

    private fun batchRequests() = requests.filter { it.path == "/api/fleet/obd_samples/batch/" }

    private fun batchSizes() = batchRequests().map {
        (Json.parseToJsonElement(it.body.clone().readUtf8()) as JsonArray).size
    }

    // --- chunking ------------------------------------------------------------------------------

    @Test fun `samples go up in batches no larger than the server cap and the cursor lands on the last row`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        val outcome = uploader(Source(listOf(car()), samples(2305))).upload(FleetUploadReason.MANUAL)

        assertEquals(listOf(1000, 1000, 305), batchSizes())
        assertTrue(batchSizes().all { it <= FleetUploader.OBD_BATCH_MAX })
        assertEquals(2305L, cursors.samples[mac])
        assertEquals(2305, (outcome as FleetUploadOutcome.Sent).samples)
        assertTrue(sink.last!!.second)
        assertEquals("Token device-token", batchRequests().first().getHeader("Authorization"))
    }

    @Test fun `exactly one full batch is sent as one batch`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        uploader(Source(listOf(car()), samples(1000))).upload(FleetUploadReason.MANUAL)
        assertEquals(listOf(1000), batchSizes())
        assertEquals(1000L, cursors.samples[mac])
    }

    @Test fun `the batch cap per run leaves the rest pending and says so`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        val outcome = uploader(Source(listOf(car()), samples(2500)), maxBatches = 2).upload(FleetUploadReason.MANUAL)
        assertEquals(listOf(1000, 1000), batchSizes())
        assertEquals(2000L, cursors.samples[mac])
        assertTrue((outcome as FleetUploadOutcome.Sent).moreWaiting)
    }

    @Test fun `a rerun after success sends nothing`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        val source = Source(listOf(car()), samples(10))
        uploader(source).upload(FleetUploadReason.MANUAL)
        requests.clear()
        val again = uploader(source).upload(FleetUploadReason.MANUAL)
        assertEquals(FleetUploadOutcome.NothingToSend, again)
        assertTrue(requests.isEmpty())
    }

    // --- identity ------------------------------------------------------------------------------

    @Test fun `a car resolves to the engine row whose origin_guid the phone derived from its id`() = runTest {
        vehicleListBody = listOf(
            engineRow("other-1", guid = "00000000-0000-0000-0000-000000000000"),
            engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)),
        )
        uploader(Source(listOf(car()), samples(3))).upload(FleetUploadReason.MANUAL)

        assertTrue(requests.none { it.method == "PUT" })
        val first = Json.parseToJsonElement(batchRequests().single().body.readUtf8()) as JsonArray
        assertEquals(engineId, first[0].jsonObject["vehicle_id"]!!.jsonPrimitive.content)
        assertEquals(engineId, cursors.engine[mac])
    }

    @Test fun `a car with no guid match resolves by last_obd_mac, case-insensitively`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = null, lastMac = mac.lowercase()))
        uploader(Source(listOf(car()), samples(3))).upload(FleetUploadReason.MANUAL)

        assertTrue(requests.none { it.method == "PUT" })
        assertEquals(listOf(3), batchSizes())
        assertEquals(engineId, cursors.engine[mac])
    }

    @Test fun `no match creates the vehicle at the deterministic guid with the dongle mac`() = runTest {
        vehicleListBody = listOf(engineRow("unrelated", guid = "x", lastMac = "11:22:33:44:55:66"))
        uploader(Source(listOf(car()), samples(2))).upload(FleetUploadReason.MANUAL)

        val put = requests.single { it.method == "PUT" }
        assertEquals("/api/fleet/vehicles/${FleetUploader.vehicleGuid(mac)}/", put.path)
        val body = Json.parseToJsonElement(put.body.readUtf8()).jsonObject
        assertEquals(mac, body["last_obd_mac"]!!.jsonPrimitive.content)
        assertEquals("Jeep", body["make"]!!.jsonPrimitive.content)
        assertEquals(engineId, cursors.engine[mac])
        assertEquals(listOf(2), batchSizes())
    }

    @Test fun `two engine vehicles claiming one dongle skips the car, sends nothing and says so`() = runTest {
        vehicleListBody = listOf(
            engineRow("dup-1", lastMac = mac),
            engineRow("dup-2", lastMac = mac),
        )
        val outcome = uploader(Source(listOf(car()), samples(5))).upload(FleetUploadReason.MANUAL)

        assertTrue(outcome is FleetUploadOutcome.Skipped)
        assertTrue(outcome.sentence.contains("claim the same dongle"))
        assertTrue(requests.none { it.method == "PUT" })
        assertTrue(batchRequests().isEmpty())
        assertNull(cursors.samples[mac])
        assertNull(cursors.engine[mac])
        assertTrue(logged.any { it.contains("claim the same dongle") })
        assertEquals(false, sink.last!!.second)
    }

    @Test fun `a guid match and a different mac-only row is ambiguous too`() = runTest {
        vehicleListBody = listOf(
            engineRow("by-guid", guid = FleetUploader.vehicleGuid(mac)),
            engineRow("by-mac", lastMac = mac),
        )
        val outcome = uploader(Source(listOf(car()), samples(1))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Skipped)
        assertTrue(batchRequests().isEmpty())
    }

    @Test fun `a soft-deleted match is ignored and its guid is not resurrected`() = runTest {
        vehicleListBody = listOf(engineRow("gone", guid = FleetUploader.vehicleGuid(mac), lastMac = mac, deleted = true))
        val outcome = uploader(Source(listOf(car()), samples(1))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Skipped)
        assertTrue(requests.none { it.method == "PUT" })
        assertTrue(batchRequests().isEmpty())
    }

    @Test fun `an unconfirmed placeholder car is never created on the engine`() = runTest {
        vehicleListBody = emptyList()
        val outcome = uploader(Source(listOf(car(confirmed = false)), samples(1))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Skipped)
        assertTrue(requests.none { it.method == "PUT" })
    }

    @Test fun `one skipped car does not stop the next car uploading`() = runTest {
        val other = "11:22:33:44:55:66"
        vehicleListBody = listOf(
            engineRow("dup-1", lastMac = mac),
            engineRow("dup-2", lastMac = mac),
            engineRow(engineId, lastMac = other),
        )
        val source = Source(listOf(car(), car(other)), samples(2) + samples(4, other).map { it.copy(id = it.id + 100) })
        val outcome = uploader(source).upload(FleetUploadReason.MANUAL)
        assertEquals(4, (outcome as FleetUploadOutcome.Sent).samples)
        assertEquals(listOf(4), batchSizes())
        assertNull(cursors.samples[mac])
    }

    // --- 2xx only ------------------------------------------------------------------------------

    @Test fun `a 500 leaves every row pending`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        batchResponse = { MockResponse().setResponseCode(500).setBody("boom") }
        val outcome = uploader(Source(listOf(car()), samples(10))).upload(FleetUploadReason.MANUAL)

        assertTrue(outcome is FleetUploadOutcome.Failed)
        assertNull(cursors.samples[mac])
        assertEquals(false, sink.last!!.second)
        assertTrue(outcome.sentence.contains("retried"))
    }

    @Test fun `a 400 leaves rows pending and forgets the cached vehicle so it is looked up again`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        cursors.engine[mac] = "stale-id"
        batchResponse = { MockResponse().setResponseCode(400).setBody("""{"detail":"These vehicle_id values name no vehicle"}""") }
        val outcome = uploader(Source(listOf(car()), samples(10))).upload(FleetUploadReason.MANUAL)

        assertTrue(outcome is FleetUploadOutcome.Failed)
        assertNull(cursors.samples[mac])
        assertNull(cursors.engine[mac])
    }

    @Test fun `a 401 reads as sign in again and leaves rows pending`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        batchResponse = { MockResponse().setResponseCode(401).setBody("") }
        val outcome = uploader(Source(listOf(car()), samples(10))).upload(FleetUploadReason.MANUAL)
        assertTrue((outcome as FleetUploadOutcome.Failed).reason.contains("Sign in again"))
        assertNull(cursors.samples[mac])
    }

    @Test fun `a dropped connection leaves rows pending`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        batchResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        val outcome = uploader(Source(listOf(car()), samples(10))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Failed)
        assertNull(cursors.samples[mac])
    }

    @Test fun `a failure on the second batch keeps the first batch's cursor only`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        var calls = 0
        batchResponse = {
            calls++
            if (calls == 1) MockResponse().setResponseCode(201).setBody(RESULT) else MockResponse().setResponseCode(503)
        }
        val outcome = uploader(Source(listOf(car()), samples(1500))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Failed)
        assertEquals(1000L, cursors.samples[mac])
    }

    @Test fun `not signed in sends nothing, keeps everything and logs it in words`() = runTest {
        val outcome = uploader(Source(listOf(car()), samples(10)), signedIn = false).upload(FleetUploadReason.STARTUP)
        assertEquals(FleetUploadOutcome.NoEngine, outcome)
        assertEquals(0, server.requestCount)
        assertNull(cursors.samples[mac])
        assertTrue(logged.any { it.contains("not signed in") })
    }

    @Test fun `nothing pending sends nothing and does not touch the status line`() = runTest {
        val outcome = uploader(Source(listOf(car()))).upload(FleetUploadReason.MANUAL)
        assertEquals(FleetUploadOutcome.NothingToSend, outcome)
        assertEquals(0, server.requestCount)
        assertNull(sink.last)
    }

    // --- code events ---------------------------------------------------------------------------

    private fun event(id: Long, sync: String = "sync-$id", codes: String = """["P0420"]""") =
        CodeEvent(id = id, vehicleId = mac, timestamp = 1_700_000_000_000L + id, mileage = 100, codesJson = codes, freezeFrameJson = "", syncId = sync)

    @Test fun `code events are PUT by sync id and the cursor follows the 2xx`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        val outcome = uploader(Source(listOf(car()), events = listOf(event(1), event(2)))).upload(FleetUploadReason.MANUAL)

        val puts = requests.filter { it.method == "PUT" }
        assertEquals(listOf("/api/fleet/code_events/sync-1/", "/api/fleet/code_events/sync-2/"), puts.map { it.path })
        assertEquals(2, (outcome as FleetUploadOutcome.Sent).codeEvents)
        assertEquals(2L, cursors.codes[mac])
    }

    @Test fun `a refused code event stops there and is not marked sent`() = runTest {
        vehicleListBody = listOf(engineRow(engineId, guid = FleetUploader.vehicleGuid(mac)))
        var calls = 0
        codePutResponse = {
            calls++
            if (calls == 1) MockResponse().setResponseCode(200).setBody("{}") else MockResponse().setResponseCode(500)
        }
        val outcome = uploader(Source(listOf(car()), events = listOf(event(1), event(2), event(3)))).upload(FleetUploadReason.MANUAL)
        assertTrue(outcome is FleetUploadOutcome.Failed)
        assertEquals(1L, cursors.codes[mac])
    }

    // --- flush rule ----------------------------------------------------------------------------

    @Test fun `the mid-drive flush fires on size, on age, and on any waiting code event`() {
        val day = 24 * 60 * 60 * 1000L
        assertTrue(FleetUpload.shouldFlush(FleetPending(FleetUpload.FLUSH_SAMPLE_THRESHOLD, 0), 0L))
        assertTrue(FleetUpload.shouldFlush(FleetPending(1, 0), FleetUpload.FLUSH_INTERVAL_MS))
        assertTrue(FleetUpload.shouldFlush(FleetPending(0, 1), 0L))
        assertEquals(false, FleetUpload.shouldFlush(FleetPending(10, 0), 60_000L))
        assertEquals(false, FleetUpload.shouldFlush(FleetPending(0, 0), day))
    }

    private companion object {
        const val RESULT = """{"received":1,"inserted":1,"already_present":0,"duplicates_in_batch":0}"""
    }
}
