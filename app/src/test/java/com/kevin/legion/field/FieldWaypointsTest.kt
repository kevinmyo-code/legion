package com.kevin.legion.field

import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.PlacesBackendException
import com.kevin.legion.backend.RemotePlace
import com.kevin.legion.backend.RemoteRename
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.location.PlaceController
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Round 5: a field mark is local first. With the engine unreachable it waits in `sync_outbox` and
 * still lists; once the engine answers it becomes a saved place and leaves the outbox. Made-up
 * coordinates only.
 */
@RunWith(RobolectricTestRunner::class)
class FieldWaypointsTest {
    private val context = RuntimeEnvironment.getApplication()

    private class Backend(var down: Boolean) : PlacesBackend {
        val rows = mutableMapOf<String, RemotePlace>()
        override suspend fun fetchActive() = Result.success(rows.values.toList())
        override suspend fun upsert(label: String, latitude: Double, longitude: Double, address: String?): Result<RemotePlace> {
            if (down) return Result.failure(PlacesBackendException("simulated: no signal"))
            val row = RemotePlace(label, latitude, longitude, updatedAtMs = 1L, deleted = false, address = address)
            rows[label] = row
            return Result.success(row)
        }
        override suspend fun rename(from: String, to: String) =
            Result.failure<RemoteRename>(PlacesBackendException("not used"))
        override suspend fun softDelete(label: String) = Result.success(true)
    }

    private lateinit var backend: Backend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = Backend(down = true)
        PlaceController.backendOverride = backend
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
        PlaceController.backendOverride = null
    }

    private suspend fun outbox() =
        CarDatabase.getDatabase(context).outboxDao().pendingForTable(OutboxTarget.FIELD_WAYPOINTS, FieldWaypoints.MAX_ATTEMPTS)

    @Test
    fun `offline, a mark waits on the phone and still lists`() = runBlocking {
        val outcome = FieldWaypoints.mark(context, " car ", 10.5, 20.25, nowMs = 5L)
        assertTrue(outcome.ok)
        assertFalse(outcome.synced)
        assertTrue(outcome.message, outcome.message.contains("on this phone"))
        assertEquals(1, outbox().size)
        val listed = FieldWaypoints.all(context)
        assertEquals(listOf(Waypoint("car", 10.5, 20.25, pending = true)), listed)
        assertTrue(backend.rows.isEmpty())
    }

    @Test
    fun `when the engine answers, the mark becomes a saved place and leaves the outbox`() = runBlocking {
        FieldWaypoints.mark(context, "car", 10.5, 20.25)
        backend.down = false
        val results = FieldWaypoints.drain(context)
        assertTrue(results.values.single() is PlaceController.SaveOutcome.Saved)
        assertTrue(outbox().isEmpty())
        assertEquals(10.5, backend.rows.getValue("car").latitude, 1e-9)
        assertEquals(listOf(Waypoint("car", 10.5, 20.25, pending = false)), FieldWaypoints.all(context))
    }

    @Test
    fun `marking with the engine up saves at once`() = runBlocking {
        backend.down = false
        val outcome = FieldWaypoints.mark(context, "trailhead", 1.0, 2.0)
        assertTrue(outcome.synced)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a failed drain keeps the mark and counts the try`() = runBlocking {
        FieldWaypoints.mark(context, "car", 10.5, 20.25)
        FieldWaypoints.drain(context)
        val row = outbox().single()
        assertEquals(2, row.attempts) // one from mark's own drain, one from this
        assertTrue(row.lastError!!.isNotBlank())
    }

    @Test
    fun `a blank name marks nothing`() = runBlocking {
        val outcome = FieldWaypoints.mark(context, "   ", 1.0, 2.0)
        assertFalse(outcome.ok)
        assertTrue(outbox().isEmpty())
    }
}
