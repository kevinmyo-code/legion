package com.kevin.legion.location

import android.location.Location
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`). CRUD against [PlaceController]'s
 * now-engine-backed internals. [LocationController.state] has no public setter (it is only ever
 * written by a real `LocationListener`), so [setFix] reaches its private backing field via
 * reflection - the same shape a GPS-dependent unit test needs regardless of which store
 * [PlaceController] writes to; this is not a cutover-specific test hazard.
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
    fun `tagPlace writes an engine record readable back through all`() = runBlocking {
        val ack = PlaceController.tagPlace(context, "my work")
        assertTrue(ack.isNotBlank())

        val places = PlaceController.all(context)
        assertEquals(1, places.size)
        assertEquals("work", places.single().label)

        // places gains no reader here - the legacy table must still be empty.
        assertTrue(CarDatabase.getDatabase(context).placeDao().getAll().isEmpty())
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

    @Test
    fun `currentLabel matches the nearest saved place within radius`() = runBlocking {
        PlaceController.tagPlace(context, "work")
        assertEquals("work", PlaceController.currentLabel(context))

        setFix(40.0, -74.0) // far away - New York, nowhere near Houston
        assertNull(PlaceController.currentLabel(context))
    }
}
