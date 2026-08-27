package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.places.PlacesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * PlacesReconcile - the Phase 4 step 1/2 one-time (re-runnable) job
 * (.scratch/backend-erp/issues/05-migration-path.md). Exercised entirely against an in-memory
 * FakePlacesBackend and a real (Robolectric) engine, never a network. Covers: idempotent re-runs,
 * a one-sided diff being reported rather than silently reconciled, and that the engine record is
 * never touched.
 */
@RunWith(RobolectricTestRunner::class)
class PlacesReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakePlacesBackend(
        seed: List<RemotePlace> = emptyList(),
    ) : PlacesBackend {
        val rows = seed.associateBy { it.label }.toMutableMap()
        var clock = 1_000L

        override suspend fun fetchActive(): Result<List<RemotePlace>> =
            Result.success(rows.values.filterNot { it.deleted })

        override suspend fun upsert(label: String, latitude: Double, longitude: Double): Result<RemotePlace> {
            val row = RemotePlace(label, latitude, longitude, updatedAtMs = ++clock, deleted = false)
            rows[label] = row
            return Result.success(row)
        }

        override suspend fun softDelete(label: String): Result<Boolean> {
            val existing = rows[label] ?: return Result.success(false)
            rows[label] = existing.copy(deleted = true, updatedAtMs = ++clock)
            return Result.success(true)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Creates an active engine record directly against RecordStore, bypassing PlaceController so
     * this suite does not depend on PlaceController's own branching. */
    private suspend fun createEngineRecord(label: String, lat: Double, lon: Double) {
        val db = CarDatabase.getDatabase(context)
        val sch = PlacesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        store.create(
            sch.recordTypeId,
            mapOf(
                sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL) to label,
                sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE) to lat,
                sch.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE) to lon,
            ),
            RecordProvenance.USER,
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `run uploads every active engine record and fills the replica`() = runBlocking {
        createEngineRecord("home", 29.7604, -95.3698)
        createEngineRecord("work", 30.2672, -97.7431)
        val backend = FakePlacesBackend()

        val report = PlacesReconcile.run(context, backend).getOrThrow()

        assertEquals(2, report.engineCount)
        assertEquals(2, report.uploaded)
        assertEquals(2, report.serverCountAfter)
        assertEquals(2, report.replicaCountAfter)
        assertTrue(report.isClean)
        assertEquals(2, backend.rows.size)
        assertEquals(2, CarDatabase.getDatabase(context).placeDao().getAll().size)
    }

    @Test
    fun `running it twice is idempotent - same server and replica state, still a clean diff`() = runBlocking {
        createEngineRecord("home", 29.7604, -95.3698)
        val backend = FakePlacesBackend()

        val first = PlacesReconcile.run(context, backend).getOrThrow()
        val second = PlacesReconcile.run(context, backend).getOrThrow()

        assertEquals(first.serverCountAfter, second.serverCountAfter)
        assertEquals(first.replicaCountAfter, second.replicaCountAfter)
        assertTrue(second.isClean)
        assertEquals(1, backend.rows.size)
    }

    @Test
    fun `a label present only on the server is reported, never silently folded in`() = runBlocking {
        createEngineRecord("home", 29.7604, -95.3698)
        val backend = FakePlacesBackend(
            seed = listOf(RemotePlace("garage", 1.0, 2.0, updatedAtMs = 10L, deleted = false)),
        )

        val report = PlacesReconcile.run(context, backend).getOrThrow()

        assertTrue("garage" in report.onlyOnServer)
        assertTrue(report.onlyOnEngine.isEmpty())
        assertEquals(false, report.isClean)
    }

    @Test
    fun `never deletes or trashes the engine record - the engine stays the truth until the diff is clean`() = runBlocking {
        createEngineRecord("home", 29.7604, -95.3698)
        val backend = FakePlacesBackend()
        val db = CarDatabase.getDatabase(context)
        val sch = PlacesAspectSeeder.ensureSeeded(context)

        PlacesReconcile.run(context, backend).getOrThrow()

        val stillActive = db.engineRecordDao().activeByRecordType(sch.recordTypeId)
        assertEquals(1, stillActive.size)
    }
}
