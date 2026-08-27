package com.kevin.legion.location

import android.location.Location
import com.kevin.legion.backend.PlacesBackend
import com.kevin.legion.backend.PlacesBackendException
import com.kevin.legion.backend.RemotePlace
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.MutableStateFlow
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
 * PlaceController's CONFIGURED path (backend-erp Phase 4, places -
 * .scratch/backend-erp/issues/05-migration-path.md). Exercised entirely through
 * PlaceController.backendOverride and an in-memory FakePlacesBackend - never a real
 * SupabaseClient - so ticket 01 ruling 9 ("Room is written on server ACK, never ahead of it") and
 * the CLAUDE.md section 7 outcome-verb rule can both be asserted without a network.
 * PlaceControllerTest (this package) covers the UNCONFIGURED (engine) path and is untouched by
 * this ticket.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceControllerBackendTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakePlacesBackend(
        seed: List<RemotePlace> = emptyList(),
        var upsertFails: Boolean = false,
        var deleteFails: Boolean = false,
    ) : PlacesBackend {
        val rows = seed.associateBy { it.label }.toMutableMap()
        var upsertCalls = 0
        var clock = 1_000L

        override suspend fun fetchActive(): Result<List<RemotePlace>> =
            Result.success(rows.values.filterNot { it.deleted })

        override suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace> {
            if (upsertFails) return Result.failure(PlacesBackendException("simulated network failure"))
            upsertCalls++
            val row = RemotePlace(label, latitude, longitude, updatedAtMs = ++clock, deleted = false)
            rows[label] = row
            return Result.success(row)
        }

        override suspend fun softDelete(label: String): Result<Boolean> {
            if (deleteFails) return Result.failure(PlacesBackendException("simulated network failure"))
            val existing = rows[label]
            if (existing == null || existing.deleted) return Result.success(false)
            rows[label] = existing.copy(deleted = true, updatedAtMs = ++clock)
            return Result.success(true)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        setFix(29.7604, -95.3698)
    }

    @After
    fun clearOverride() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

        PlaceController.backendOverride = null
        setFix(null, null)
    }

    private fun setFix(lat: Double?, lon: Double?) {
        val field = LocationController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(LocationController) as MutableStateFlow<Location?>
        flow.value = if (lat == null || lon == null) {
            null
        } else {
            Location("test").apply { latitude = lat; longitude = lon }
        }
    }

    @Test
    fun `a successful tagPlace writes the replica exactly once and returns the ack`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        val ack = PlaceController.tagPlace(context, "work")

        assertTrue(ack.isNotBlank())
        assertEquals(1, backend.upsertCalls)
        val replica = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, replica.size)
        assertEquals("work", replica.single().label)
    }

    @Test
    fun `a FAILED remote write leaves the replica completely untouched and returns no ack`() = runBlocking {
        val backend = FakePlacesBackend(upsertFails = true)
        PlaceController.backendOverride = backend

        val result = PlaceController.tagPlace(context, "work")

        assertTrue(
            "a failed write must say in words that it did not save, never a bare success",
            result.contains("didn't save") || result.contains("went wrong"),
        )
        assertTrue(
            "Room must never be written ahead of a server ACK (ticket 01 ruling 9)",
            CarDatabase.getDatabase(context).placeDao().getAll().isEmpty(),
        )
    }

    @Test
    fun `re-tagging an existing label updates the replica rather than duplicating it`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        PlaceController.tagPlace(context, "work")
        setFix(30.0, -96.0)
        PlaceController.tagPlace(context, "work")

        val replica = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, replica.size)
        assertEquals(30.0, replica.single().latitude, 0.0001)
    }

    @Test
    fun `forget on a label the server does not have returns false without claiming a delete`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend

        val message = PlaceController.forgetPlace(context, "nowhere")
        assertTrue(message.contains("don't have"))

        val boolResult = PlaceController.forget(context, "nowhere")
        assertFalse(boolResult)
    }

    @Test
    fun `forgetPlace on a real label deletes server-side and clears the replica`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")

        val ack = PlaceController.forgetPlace(context, "home")

        assertTrue(ack.isNotBlank())
        assertTrue(CarDatabase.getDatabase(context).placeDao().getAll().isEmpty())
    }

    @Test
    fun `a FAILED re-tag leaves the PREVIOUS row intact rather than blanking it`() = runBlocking {
        // The sibling failure test asserts an EMPTY replica stays empty, which a no-op would also
        // satisfy. This is the version that a no-op cannot pass: a good row is already stored, the
        // refresh of it fails, and the old value must still be there afterwards - unchanged, not
        // blanked and not half-written to the new coordinates.
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")
        val before = CarDatabase.getDatabase(context).placeDao().getAll().single()

        backend.upsertFails = true
        val result = PlaceController.tagPlace(context, "home")

        assertTrue(
            "a failed write must say it did not save",
            result.contains("didn't save") || result.contains("went wrong"),
        )
        val after = CarDatabase.getDatabase(context).placeDao().getAll().single()
        assertEquals("the stored label must survive a failed refresh", before.label, after.label)
        assertEquals("and its coordinates must not be half-written", before.latitude, after.latitude, 0.0)
        assertEquals(before.longitude, after.longitude, 0.0)
    }

    @Test
    fun `a FAILED remote delete leaves the replica untouched and says so in words`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        PlaceController.tagPlace(context, "home")
        backend.deleteFails = true

        val message = PlaceController.forgetPlace(context, "home")

        assertTrue(message.contains("nothing was deleted"))
        assertEquals(1, CarDatabase.getDatabase(context).placeDao().getAll().size)
    }

    @Test
    fun `all reads the replica when configured and never touches the backend`() = runBlocking {
        val backend = FakePlacesBackend(
            seed = listOf(RemotePlace("work", 29.7604, -95.3698, updatedAtMs = 500L, deleted = false)),
        )
        PlaceController.backendOverride = backend
        // Seed the replica directly - all() must read Room, not call fetchActive itself.
        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(label = "work", latitude = 29.7604, longitude = -95.3698, timestamp = 500L),
        )

        val places = PlaceController.all(context)

        assertEquals(1, places.size)
        assertEquals("work", places.single().label)
    }

    @Test
    fun `currentLabel performs no network IO - configured but the backend is never called`() = runBlocking {
        val backend = FakePlacesBackend()
        PlaceController.backendOverride = backend
        CarDatabase.getDatabase(context).placeDao().upsert(
            TaggedPlace(label = "work", latitude = 29.7604, longitude = -95.3698, timestamp = 500L),
        )

        val label = PlaceController.currentLabel(context)

        assertEquals("work", label)
        assertEquals(0, backend.upsertCalls)
    }
}
