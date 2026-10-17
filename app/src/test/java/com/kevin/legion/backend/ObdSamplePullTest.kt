package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.fleet.TelemetryRange
import com.kevin.legion.vehicle.TelemetryRecorder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [ObdSamplePull] against a fake engine and a real (Robolectric) Room. The Django wire shape has
 * its own test in `DjangoFleetBackendTest`; what is under test here is what the PHONE does with
 * pages: cursor after store, dedupe, no re-upload, unmapped vehicles, and offline.
 */
@RunWith(RobolectricTestRunner::class)
class ObdSamplePullTest {
    private val context = RuntimeEnvironment.getApplication()
    private val mac = "AA:BB:CC:DD:EE:10"
    private val serverId = "vehicle-1"
    private val nowMs = 1_800_000_000_000L
    private val macs = mapOf(serverId to mac)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        listOf("fleet_pull_cursor", "obd_sample_upload_cursor", "obd_pull_status").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    private suspend fun insertVehicle(obdMac: String = mac) {
        CarDatabase.getDatabase(context).vehicleDao().upsert(
            Vehicle(obdMac = obdMac, name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = ""),
        )
    }

    private fun remote(i: Int, pid: String = "010C") =
        RemoteObdSample(serverId, pid, 800.0 + i, "rpm", nowMs - 3_600_000L + i * 1_000L, null, null)

    private fun backendWith(count: Int, pageSize: Int = 5) = FakePullFleetBackend(pageSize).apply {
        samples[serverId] = (1..count).map { remote(it) }.toMutableList()
    }

    private suspend fun rows(): List<OdbSample> =
        CarDatabase.getDatabase(context).odbSampleDao().getRange(mac, "010C", 0L, Long.MAX_VALUE)

    private fun cursor() = FleetPullCursor.lastPulledAtMs(context, ObdSamplePull.CURSOR_PREFIX + serverId)

    @Test
    fun `the cursor advances page by page and a failure keeps what was stored`() = runBlocking {
        insertVehicle()
        val backend = backendWith(12).apply { failAfterPages = 2 }

        val report = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)

        assertFalse(report.succeeded)
        assertEquals(9, rows().size) // two pages survived the failure on page three (page two re-served row 5)
        assertEquals(remote(9).recordedAtMs, cursor()) // the cursor sits on the last STORED page
        assertTrue(ObdPullStatus.line(context).startsWith("Could not update from the engine"))

        backend.failAfterPages = null
        backend.resetServed()
        val second = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)

        assertTrue(second.succeeded)
        assertEquals(3, second.inserted) // only the three it had not got; the overlap re-served the rest
        assertEquals(12, rows().size)
        assertEquals(remote(12).recordedAtMs, cursor())
        assertTrue(ObdPullStatus.line(context).startsWith("Updated from engine"))
    }

    @Test
    fun `a page that fails first leaves the cursor and Room untouched`() = runBlocking {
        insertVehicle()
        CarDatabase.getDatabase(context).odbSampleDao()
            .insert(OdbSample(vehicleId = mac, pid = "010C", value = 1.0, unit = "rpm", timestamp = 5L))
        val backend = backendWith(12).apply { failAfterPages = 0 }

        val report = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)

        assertFalse(report.succeeded)
        assertEquals(0, report.inserted)
        assertEquals(1, rows().size) // the offline case: Room is exactly what it was
        assertEquals(0L, cursor())
        val line = ObdPullStatus.line(context)
        assertTrue(line, line.contains("Showing what this phone has"))
        assertTrue(line, line.contains("never been updated from the engine"))
    }

    @Test
    fun `an overlapping boundary and a repeat pull never duplicate a row`() = runBlocking {
        insertVehicle()
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        // Already on the phone (e.g. recorded here and uploaded): the pull must not add it again.
        dao.insert(OdbSample(vehicleId = mac, pid = "010C", value = 801.0, unit = "rpm", timestamp = remote(1).recordedAtMs))
        val backend = backendWith(12)
        // The engine repeats a boundary instant inside one page too.
        backend.samples[serverId]!!.add(remote(3))

        ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)
        val afterFirst = rows().size
        val second = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)

        assertEquals(12, afterFirst)
        assertEquals(0, second.inserted)
        assertEquals(12, rows().size)
        assertEquals(12, rows().map { it.timestamp }.toSet().size)
    }

    @Test
    fun `an engine vehicle with no local car is skipped in words and never fetched or created`() = runBlocking {
        insertVehicle()
        val backend = backendWith(3)

        val noSidecar = ObdSamplePull.pull(context, backend, listOf("vehicle-unknown"), macs, nowMs)
        val noCar = ObdSamplePull.pull(context, backend, listOf("vehicle-2"), mapOf("vehicle-2" to "AA:00:00:00:00:99"), nowMs)

        assertEquals(1, noSidecar.skippedVehicles.size)
        assertTrue(noSidecar.skippedVehicles.single().contains("vehicle-unknown"))
        assertEquals(1, noCar.skippedVehicles.size)
        assertTrue(backend.requestedSince.isEmpty()) // no request was even made
        assertNull(CarDatabase.getDatabase(context).vehicleDao().getByMac("AA:00:00:00:00:99"))
    }

    @Test
    fun `first run asks for a year, a later run overlaps the cursor`() = runBlocking {
        insertVehicle()
        val backend = backendWith(3)

        ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)
        assertEquals(nowMs - ObdSamplePull.BACKFILL_WINDOW_MS, backend.requestedSince.first())

        backend.requestedSince.clear()
        val later = nowMs + 10 * 24 * 3_600_000L
        ObdSamplePull.pull(context, backend, listOf(serverId), macs, later)
        // Cursor is remote(3); the window start has moved on a lot less than the overlap allows.
        assertEquals(remote(3).recordedAtMs - ObdSamplePull.OVERLAP_MS, backend.requestedSince.first())
    }

    @Test
    fun `the backfill bound is the longest finite range and the retention the phone keeps`() {
        assertEquals(TelemetryRange.YEAR.spanMs, ObdSamplePull.BACKFILL_WINDOW_MS)
        assertEquals(TelemetryRecorder.RETENTION_MS, ObdSamplePull.BACKFILL_WINDOW_MS)
    }

    @Test
    fun `a vehicle that hits the page cap says more is coming and resumes next run`() = runBlocking {
        insertVehicle()
        val count = (ObdSamplePull.MAX_PAGES_PER_VEHICLE + 1) * 2
        val backend = backendWith(count, pageSize = 2)

        val first = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)

        assertTrue(first.moreToFetch)
        assertTrue(ObdPullStatus.line(context).contains("Older history is still downloading"))
    }

    @Test
    fun `status wording says when it last worked and that Room may be behind`() {
        val ok = ObdPullStatus.describe(1_800_000_000_000L, null, false)
        val failedAfterOk = ObdPullStatus.describe(1_800_000_000_000L, "The engine is unreachable.", false)
        assertTrue(ok, ok.startsWith("Updated from engine "))
        assertTrue(failedAfterOk, failedAfterOk.contains("Showing what this phone has"))
        assertTrue(failedAfterOk, failedAfterOk.contains("Last updated from engine"))
        assertEquals("Not updated from the engine yet. Showing what this phone has.", ObdPullStatus.describe(null, null, false))
    }

    // --- no re-upload of pulled rows ------------------------------------------------------------

    private fun remoteVehicleFor(obdMac: String, id: String) = RemoteVehicle(
        serverId = id, name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998, trim = null,
        engine = null, confirmed = true, odometerBaseline = null, odometerBaselineAtMs = null,
        updatedAtMs = 1_000L, deleted = false, originGuid = FleetRecordBridge.vehicleGuid(obdMac), archived = false,
    )

    @Test
    fun `pulled rows are not uploaded again when the upload had caught up`() = runBlocking {
        insertVehicle()
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val backend = backendWith(12).apply { vehicles[mac] = remoteVehicleFor(mac, serverId) }
        dao.insert(OdbSample(vehicleId = mac, pid = "0105", value = 90.0, unit = "C", timestamp = 77L))
        ObdSampleReconcile.run(context, backend).getOrThrow()
        assertEquals(1, backend.uploaded.size) // this phone's own sample, sent once

        val report = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)
        val rerun = ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(12, report.inserted)
        assertEquals(0, report.repostPending)
        assertEquals(0, rerun.uploaded) // the 12 engine rows were stepped over, not re-posted
        assertEquals(1, backend.uploaded.size)

        // And a sample this phone records AFTER the pull is still uploaded: the cursor sits on the
        // last pulled row, not past the end of the table.
        dao.insert(OdbSample(vehicleId = mac, pid = "0105", value = 91.0, unit = "C", timestamp = 78L))
        assertEquals(1, ObdSampleReconcile.run(context, backend).getOrThrow().uploaded)
        assertEquals(2, backend.uploaded.size)
    }

    @Test
    fun `an unsent local row is never skipped, and the re-post it costs is counted`() = runBlocking {
        insertVehicle()
        val dao = CarDatabase.getDatabase(context).odbSampleDao()
        val backend = backendWith(12).apply { vehicles[mac] = remoteVehicleFor(mac, serverId) }
        // Recorded here, not yet uploaded: the upload cursor is BEHIND the top of the table.
        dao.insert(OdbSample(vehicleId = mac, pid = "0105", value = 90.0, unit = "C", timestamp = 77L))

        val report = ObdSamplePull.pull(context, backend, listOf(serverId), macs, nowMs)
        ObdSampleReconcile.run(context, backend).getOrThrow()

        assertEquals(12, report.repostPending)
        assertTrue(backend.uploaded.any { it.pid == "0105" && it.recordedAtMs == 77L }) // the local row went
        assertEquals(13, backend.uploaded.size) // plus the 12 harmless idempotent re-posts
    }
}
