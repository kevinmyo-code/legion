package com.kevin.legion.location

import android.location.Location
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.migration.EnginePlacesRetirementCopy
import com.kevin.legion.engine.places.PlacesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`) originally made this the engine-backed
 * suite for [PlaceController]'s UNCONFIGURED path. **Ticket 15 step 1**
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`) repointed that path onto the
 * legacy `places` table, so this file now covers CRUD against `places` via the unconfigured branch
 * plus [EnginePlacesRetirementCopy]'s one-time reconcile - the step that keeps a place tagged
 * directly through the engine (before this repoint, or on an install still mid-soak) from being
 * silently dropped the moment the read flips. [PlaceControllerBackendTest] covers the CONFIGURED
 * path and is untouched by this ticket. [LocationController.state] has no public setter (it is
 * only ever written by a real `LocationListener`), so [setFix] reaches its private backing field
 * via reflection - unrelated to which store [PlaceController] writes to.
 */
@RunWith(RobolectricTestRunner::class)
class PlaceControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        setFix(29.7604, -95.3698)
    }

    @After
    fun clearFix() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

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

    /** Writes a Place record directly through the engine, bypassing [PlaceController] entirely -
     * simulates a place tagged before ticket 15's repoint (or on an install still mid-soak), which
     * only [EnginePlacesRetirementCopy] should ever be able to see and copy forward. */
    private suspend fun createEnginePlace(label: String, lat: Double, lon: Double): RecordStore.WriteResult {
        val db = CarDatabase.getDatabase(context)
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        return store.create(
            recordTypeId = schema.recordTypeId,
            fieldValues = mapOf(
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL) to label,
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE) to lat,
                schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE) to lon,
            ),
            provenance = RecordProvenance.USER,
        )
    }

    @Test
    fun `tagPlace writes places directly, readable back through all`() = runBlocking {
        val ack = PlaceController.tagPlace(context, "my work")
        assertTrue(ack.isNotBlank())

        val places = PlaceController.all(context)
        assertEquals(1, places.size)
        assertEquals("work", places.single().label)

        // The repoint (ticket 15 step 1) means `places` IS the unconfigured store now - the old
        // "places gains no reader here" assertion this test used to carry is exactly backwards
        // post-repoint.
        assertEquals(1, CarDatabase.getDatabase(context).placeDao().getAll().size)
    }

    @Test
    fun `re-tagging the same label upserts in place, never a second row`() = runBlocking {
        PlaceController.tagPlace(context, "work")
        setFix(30.0, -96.0)
        PlaceController.tagPlace(context, "work")

        val places = PlaceController.all(context)
        assertEquals("a re-tag must overwrite, not duplicate - the known v1 gap the wave 1 carve doc flagged", 1, places.size)
        assertEquals(30.0, places.single().latitude, 0.0001)
    }

    @Test
    fun `forgetPlace removes a saved place and reports missing labels`() = runBlocking {
        PlaceController.tagPlace(context, "home")
        val forgotten = PlaceController.forgetPlace(context, "home")
        assertTrue(forgotten.isNotBlank())
        assertTrue(PlaceController.all(context).isEmpty())

        val missing = PlaceController.forgetPlace(context, "home")
        assertTrue(missing.contains("don't have"))
    }

    // -------------------------------------------------------------- outcome-verb honesty (should-fix 3)

    @Test
    fun `forget returns true on a real delete and false for an unknown label, never a false success`() = runBlocking {
        PlaceController.tagPlace(context, "home")
        assertTrue("a real delete must report true", PlaceController.forget(context, "home"))
        assertTrue("the place must actually be gone", PlaceController.all(context).isEmpty())
        assertTrue(
            "forget on an unknown label must report false, not silently claim success",
            !PlaceController.forget(context, "home"),
        )
    }

    @Test
    fun `currentLabel matches the nearest saved place within radius`() = runBlocking {
        PlaceController.tagPlace(context, "work")
        assertEquals("work", PlaceController.currentLabel(context))

        setFix(40.0, -74.0) // far away - New York, nowhere near Houston
        assertNull(PlaceController.currentLabel(context))
    }

    // -------------------------------------------------------------- ticket 15 step 1: engine -> places reconcile

    @Test
    fun `the one-time copy moves engine Places into places, idempotently`() = runBlocking {
        val write = createEnginePlace("gym", 29.75, -95.4)
        assertTrue(write is RecordStore.WriteResult.Success)

        val first = EnginePlacesRetirementCopy.copyIfNeeded(context)
        assertEquals(1, first.copied)
        assertFalse(first.alreadyDone)

        val afterFirst = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, afterFirst.size)
        assertEquals("gym", afterFirst.single().label)
        assertEquals(29.75, afterFirst.single().latitude, 0.0001)

        // Running it again changes nothing - both the fast-path completion flag AND, independently,
        // the per-label existence check (if the flag were ever cleared) must be no-ops the second
        // time.
        val second = EnginePlacesRetirementCopy.copyIfNeeded(context)
        assertEquals(0, second.copied)
        assertTrue(second.alreadyDone)
        assertEquals(1, CarDatabase.getDatabase(context).placeDao().getAll().size)
    }

    @Test
    fun `a label already present in places is not duplicated or overwritten by the copy`() = runBlocking {
        // `places` already has "home" at one set of coordinates (e.g. from the configured path,
        // or an earlier wave 1 forward-copy) - it must win the tie, never be clobbered by a
        // possibly-stale engine record for the same label.
        CarDatabase.getDatabase(context).placeDao().upsert(
            com.kevin.legion.data.local.TaggedPlace(label = "home", latitude = 1.0, longitude = 2.0, timestamp = 500L),
        )
        createEnginePlace("home", 99.0, 99.0)

        val result = EnginePlacesRetirementCopy.copyIfNeeded(context)
        assertEquals("the engine's stale 'home' must be skipped, not copied over the legacy row", 0, result.copied)

        val rows = CarDatabase.getDatabase(context).placeDao().getAll()
        assertEquals(1, rows.size)
        assertEquals(1.0, rows.single().latitude, 0.0001)
    }

    @Test
    fun `unconfigured reads return engine-only places after the repoint`() = runBlocking {
        // Nothing ever calls PlaceController.tagPlace here - this place exists ONLY in the engine,
        // simulating data written before ticket 15's repoint landed. all() must still surface it.
        createEnginePlace("dentist", 29.8, -95.5)

        val places = PlaceController.all(context)
        assertEquals(1, places.size)
        assertEquals("dentist", places.single().label)
    }

    @Test
    fun `the engine's Place records still exist after the copy - nothing is deleted`() = runBlocking {
        createEnginePlace("gym", 29.75, -95.4)
        EnginePlacesRetirementCopy.copyIfNeeded(context)

        val db = CarDatabase.getDatabase(context)
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val engineRecords = db.engineRecordDao().activeByRecordType(schema.recordTypeId)
        assertEquals("ticket 15: nothing is deleted until every aspect is repointed and soaked", 1, engineRecords.size)
    }
}
