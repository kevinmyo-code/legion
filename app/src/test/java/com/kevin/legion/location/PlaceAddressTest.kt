package com.kevin.legion.location

import android.location.Location
import com.kevin.legion.data.local.CarDatabase
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
 * Places by address, rename, and no silent destruction (Kevin, 2026-10-09; voice audit finding 2),
 * through [PlaceController] on the UNCONFIGURED path (Room is the store), with a
 * [FakePlaceGeocoder] standing in for the address lookups. The configured path's rename is in
 * [PlaceControllerBackendTest].
 */
@RunWith(RobolectricTestRunner::class)
class PlaceAddressTest {
    private val context = RuntimeEnvironment.getApplication()
    private val geocoder = FakePlaceGeocoder()

    private val katy = GeocodedAddress("123 Main St, Katy, TX 77494", 29.7858, -95.8245)
    private val katyNorth = GeocodedAddress("123 Main St, Katy, TX 77493", 29.8601, -95.8302)

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        PlaceController.geocoderOverride = geocoder
        setFix(29.7604, -95.3698)
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
        PlaceController.geocoderOverride = null
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

    private suspend fun stored() = CarDatabase.getDatabase(context).placeDao().getAll()

    // -- saving by address -------------------------------------------------------------------

    @Test
    fun `one match is saved at the resolved address and read back in words`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy))

        val outcome = PlaceController.savePlace(context, "Katie House", "123 main street katy", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertEquals("Saved \"katie house\" at 123 Main St, Katy, TX 77494.", outcome.message)
        val place = stored().single()
        assertEquals("katie house", place.label)
        assertEquals("123 Main St, Katy, TX 77494", place.address)
        assertEquals(29.7858, place.latitude, 1e-6)
        assertEquals(listOf("123 main street katy"), geocoder.forwardQueries)
        assertEquals("an address save never consults the GPS fix's address", 0, geocoder.reverseCalls)
    }

    @Test
    fun `several matches save nothing and list the candidates`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy, katyNorth))

        val outcome = PlaceController.savePlace(context, "katie house", "123 Main St Katy", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Choose)
        assertFalse(outcome.success)
        assertEquals(listOf(katy, katyNorth), (outcome as PlaceController.SaveOutcome.Choose).candidates)
        assertTrue(outcome.message.startsWith("Nothing was saved."))
        assertTrue(outcome.message.contains("1) 123 Main St, Katy, TX 77494; 2) 123 Main St, Katy, TX 77493"))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `naming one of the offered candidates exactly saves that one`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy, katyNorth))

        val outcome =
            PlaceController.savePlace(context, "katie house", "123 Main St, Katy, TX 77493", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertEquals("123 Main St, Katy, TX 77493", stored().single().address)
    }

    @Test
    fun `no match saves nothing and says it could not find it`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.NotFound

        val outcome = PlaceController.savePlace(context, "katie house", "nowhere lane", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Refused)
        assertTrue(outcome.message.contains("couldn't find an address matching \"nowhere lane\""))
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `no lookup service or no connection refuses in words and never fakes an address`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Unavailable("this phone has no address lookup service")

        val outcome = PlaceController.savePlace(context, "katie house", "123 Main St", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Refused)
        assertEquals(
            "Nothing was saved. I couldn't look up that address: this phone has no address lookup service.",
            outcome.message,
        )
        assertTrue(stored().isEmpty())
    }

    // -- saving where you are ----------------------------------------------------------------

    @Test
    fun `the current spot is saved with its reverse looked up address`() = runBlocking {
        geocoder.reverseAnswer = ReverseLookup.Found("900 Bagby St, Houston, TX 77002")

        val outcome = PlaceController.savePlace(context, "work", rawAddress = null, confirmed = false)

        assertEquals("Saved work where you are now: 900 Bagby St, Houston, TX 77002.", outcome.message)
        assertEquals("900 Bagby St, Houston, TX 77002", stored().single().address)
        assertEquals(29.7604, stored().single().latitude, 1e-6)
    }

    @Test
    fun `a failed reverse lookup still saves by coordinates and says the address is unknown`() = runBlocking {
        geocoder.reverseAnswer = ReverseLookup.Unavailable("the address lookup couldn't be reached (offline)")

        val outcome = PlaceController.savePlace(context, "work", rawAddress = null, confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertTrue(outcome.message.contains("by its coordinates only"))
        assertTrue(outcome.message.contains("address is unknown - the address lookup couldn't be reached"))
        val place = stored().single()
        assertNull(place.address)
        assertEquals(29.7604, place.latitude, 1e-6)
    }

    // -- no silent replacement ---------------------------------------------------------------

    @Test
    fun `saving over a label at a different spot refuses without confirm and changes nothing`() = runBlocking {
        geocoder.reverseAnswer = ReverseLookup.Found("900 Bagby St, Houston, TX 77002")
        PlaceController.savePlace(context, "home", null, confirmed = false)
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy))

        val outcome = PlaceController.savePlace(context, "home", "123 Main St Katy", confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.NeedsConfirm)
        assertTrue(outcome.message.startsWith("Nothing was saved yet."))
        assertTrue("names what would be lost", outcome.message.contains("at 900 Bagby St, Houston, TX 77002"))
        assertTrue(outcome.message.contains("confirmed=true"))
        assertEquals("900 Bagby St, Houston, TX 77002", stored().single().address)
    }

    @Test
    fun `a confirmed save replaces the old spot and says what it replaced`() = runBlocking {
        geocoder.reverseAnswer = ReverseLookup.Found("900 Bagby St, Houston, TX 77002")
        PlaceController.savePlace(context, "home", null, confirmed = false)
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy))

        val outcome = PlaceController.savePlace(context, "home", "123 Main St Katy", confirmed = true)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertTrue(outcome.message.contains("replaces where home was before, at 900 Bagby St"))
        assertEquals(katy.address, stored().single().address)
    }

    @Test
    fun `the screen's confirmed replace of a spot with no address says where you are, not at nothing`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy))
        PlaceController.savePlace(context, "home", "123 Main St Katy", confirmed = false)
        val first = PlaceController.savePlace(context, "home", rawAddress = null, confirmed = false)
        val spot = (first as PlaceController.SaveOutcome.NeedsConfirm).spot

        val outcome = PlaceController.savePlaceAt(context, "home", spot, confirmed = true)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertTrue(
            outcome.message,
            outcome.message.startsWith("Saved home where you are now, by its coordinates only."),
        )
        assertNull(stored().single().address)
    }

    @Test
    fun `re-saving a label at the same spot needs no confirm`() = runBlocking {
        PlaceController.savePlace(context, "home", null, confirmed = false)
        setFix(29.7605, -95.3699) // a few metres

        val outcome = PlaceController.savePlace(context, "home", null, confirmed = false)

        assertTrue(outcome is PlaceController.SaveOutcome.Saved)
        assertEquals(29.7605, stored().single().latitude, 1e-6)
    }

    @Test
    fun `forget refuses without confirm, names the place and its address, and deletes nothing`() = runBlocking {
        geocoder.reverseAnswer = ReverseLookup.Found("900 Bagby St, Houston, TX 77002")
        PlaceController.savePlace(context, "home", null, confirmed = false)

        val outcome = PlaceController.forgetPlace(context, "home", confirmed = false)

        assertFalse(outcome.success)
        assertTrue(outcome.message.startsWith("Nothing was deleted yet."))
        assertTrue(outcome.message.contains("home"))
        assertTrue(outcome.message.contains("900 Bagby St, Houston, TX 77002"))
        assertTrue(outcome.message.contains("rename_place"))
        assertEquals(1, stored().size)

        val confirmed = PlaceController.forgetPlace(context, "home", confirmed = true)
        assertTrue(confirmed.success)
        assertTrue(stored().isEmpty())
    }

    // -- rename ------------------------------------------------------------------------------

    @Test
    fun `rename keeps the coordinates and address and drops the old label`() = runBlocking {
        geocoder.forwardAnswer = ForwardLookup.Found(listOf(katy))
        PlaceController.savePlace(context, "home", "123 Main St Katy", confirmed = false)

        val outcome = PlaceController.renamePlace(context, "Home", "Katie House")

        assertTrue(outcome.success)
        assertEquals(
            "Renamed home to \"katie house\". Same spot, at 123 Main St, Katy, TX 77494.",
            outcome.message,
        )
        val place = stored().single()
        assertEquals("katie house", place.label)
        assertEquals(katy.address, place.address)
        assertEquals(katy.latitude, place.latitude, 1e-6)
    }

    @Test
    fun `rename onto a name already in use refuses and changes nothing`() = runBlocking {
        PlaceController.savePlace(context, "home", null, confirmed = false)
        setFix(29.9, -95.9)
        PlaceController.savePlace(context, "work", null, confirmed = false)

        val outcome = PlaceController.renamePlace(context, "home", "work")

        assertFalse(outcome.success)
        assertTrue(outcome.message.contains("already a saved place called work"))
        assertEquals(setOf("home", "work"), stored().map { it.label }.toSet())
        assertEquals(29.9, stored().single { it.label == "work" }.latitude, 1e-6)
    }

    @Test
    fun `rename of a place that does not exist, or to the same name, refuses`() = runBlocking {
        PlaceController.savePlace(context, "home", null, confirmed = false)

        assertFalse(PlaceController.renamePlace(context, "gym", "the gym 2").success)
        val same = PlaceController.renamePlace(context, "home", "house")
        assertFalse("'house' folds onto 'home', so this is the same name", same.success)
        assertTrue(same.message.contains("already called home"))
        assertEquals(listOf("home"), stored().map { it.label })
    }

    @Test
    fun `rename moves the place's reminders with it`() = runBlocking {
        PlaceController.savePlace(context, "home", null, confirmed = false)
        assertTrue(ReminderController.add(context, "home", "grab the mail").success)

        val outcome = PlaceController.renamePlace(context, "home", "katie house")

        assertTrue(outcome.message, outcome.message.contains("1 reminder moved with it"))
        assertTrue(ReminderController.activeFor(context, "home").isEmpty())
        assertEquals("grab the mail", ReminderController.activeFor(context, "katie house").single().text)
    }
}
