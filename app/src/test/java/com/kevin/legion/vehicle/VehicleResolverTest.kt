package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
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
 * [VehicleResolver.resolveVehicle] - fleet-wide voice, ticket 01. Robolectric
 * only because it needs a real [android.content.Context] for Room + the
 * active-vehicle SharedPreferences, same shape as [CompanionProfileTest].
 *
 * [CarDatabase.getDatabase] is a process-static singleton (see
 * [RoomTestReset]'s doc for why that specifically breaks under Robolectric's
 * per-method reset), so [clearState] forces a fresh instance before every
 * test rather than trusting Robolectric to hand back a usable one.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleResolverTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // Resetting the SINGLETON is not the same as resetting the DATA, and
        // the difference is a real bug this test hit on 2026-08-07: a fresh
        // Room instance opens the SAME underlying database, so vehicles seeded
        // by an earlier test survive into the next one. The roster then reads
        // 4 where the test seeded 2, and because JUnit does not guarantee
        // method order it fails only sometimes - it passed alone and failed in
        // the full suite, which is the worst way for a test to be wrong.
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun vehicle(
        obdMac: String,
        name: String,
        make: String = "Mitsubishi",
        model: String = "Outlander",
        year: Int = 2020,
        trim: String = "",
        archived: Boolean = false,
    ) = Vehicle(
        obdMac = obdMac, name = name, make = make, model = model, year = year, trim = trim,
        personaPrompt = "", archived = archived,
    )

    private suspend fun seed(vararg vehicles: Vehicle) {
        val dao = CarDatabase.getDatabase(context).vehicleDao()
        // Clear FIRST, and from inside the test's own coroutine rather than
        // @Before. Robolectric gives a fresh sandbox per test CLASS, not per
        // method, so rows seeded by one method otherwise survive into the next
        // and a roster assertion reads more cars than it seeded (observed
        // 2026-08-07: expected 2, got 4 - and only in the full suite, never
        // alone, because method order is not guaranteed).
        //
        // Deliberately NOT in @Before and NOT `clearAllTables()`: both open a
        // second write against the same file while another connection still
        // holds it, and SQLITE_BUSY replaced one flaky test with eight.
        dao.deleteAll()
        for (v in vehicles) dao.upsert(v)
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


    /** Test 1: blank/null spoken resolves to the active car. */
    @Test
    fun `blank spoken resolves to the active car`() = runBlocking {
        seed(vehicle("AA:BB", "Outlander"))
        ActiveVehicle.select(context, "AA:BB")

        val blank = VehicleResolver.resolveVehicle(context, "")
        val nul = VehicleResolver.resolveVehicle(context, null)

        assertTrue(blank is VehicleMatch.Resolved)
        assertEquals("AA:BB", (blank as VehicleMatch.Resolved).vehicle.obdMac)
        assertTrue(nul is VehicleMatch.Resolved)
        assertEquals("AA:BB", (nul as VehicleMatch.Resolved).vehicle.obdMac)
    }

    /** Test 2: exact name match, case-insensitive. */
    @Test
    fun `exact name match is case-insensitive`() = runBlocking {
        seed(vehicle("AA:BB", "Outlander"), vehicle("CC:DD", "Miata", model = "MX-5"))

        val match = VehicleResolver.resolveVehicle(context, "oUtLaNdEr")

        assertTrue(match is VehicleMatch.Resolved)
        assertEquals("AA:BB", (match as VehicleMatch.Resolved).vehicle.obdMac)
    }

    /** Test 3: model match when the name does not match. */
    @Test
    fun `model matches when name does not`() = runBlocking {
        seed(vehicle("AA:BB", "The Outlander", model = "Outlander"), vehicle("CC:DD", "Miata", model = "MX-5"))

        val match = VehicleResolver.resolveVehicle(context, "Outlander")

        assertTrue(match is VehicleMatch.Resolved)
        assertEquals("AA:BB", (match as VehicleMatch.Resolved).vehicle.obdMac)
    }

    /** Test 4: contains-match across the assembled description ("grand cherokee"). */
    @Test
    fun `contains-match hits the assembled description`() = runBlocking {
        seed(vehicle("AA:BB", "The Jeep", make = "Jeep", model = "Grand Cherokee", year = 1998, trim = "Laredo"))

        val match = VehicleResolver.resolveVehicle(context, "grand cherokee")

        assertTrue(match is VehicleMatch.Resolved)
        assertEquals("AA:BB", (match as VehicleMatch.Resolved).vehicle.obdMac)
    }

    /** Test 5: two cars sharing a model -> Ambiguous, never a pick. */
    @Test
    fun `two cars sharing a model are ambiguous`() = runBlocking {
        seed(
            vehicle("AA:BB", "First Outlander", model = "Outlander"),
            vehicle("CC:DD", "Second Outlander", model = "Outlander"),
        )

        val match = VehicleResolver.resolveVehicle(context, "Outlander")

        assertTrue(match is VehicleMatch.Ambiguous)
        assertEquals(2, (match as VehicleMatch.Ambiguous).candidates.size)
    }

    /** Test 6: unmatched string -> Unknown carrying the full known roster. */
    @Test
    fun `unmatched string is Unknown and carries the roster`() = runBlocking {
        seed(vehicle("AA:BB", "Outlander"), vehicle("CC:DD", "Miata", model = "MX-5"))

        val match = VehicleResolver.resolveVehicle(context, "Ferrari")

        assertTrue(match is VehicleMatch.Unknown)
        val unknown = match as VehicleMatch.Unknown
        assertEquals("Ferrari", unknown.requested)
        assertEquals(2, unknown.known.size)
    }

    /** Test 7: archived car excluded from matching, and its Unknown says archived. */
    @Test
    fun `archived car is excluded and its refusal says archived`() = runBlocking {
        seed(vehicle("AA:BB", "Retired Car", archived = true))

        val match = VehicleResolver.resolveVehicle(context, "Retired Car")

        assertTrue(match is VehicleMatch.Unknown)
        val unknown = match as VehicleMatch.Unknown
        assertTrue("expected the refusal to mention it's archived: ${unknown.requested}", unknown.requested.contains("archived", ignoreCase = true))
    }

    /** Test 8: exact name beats a contains-match that would also hit another car (tier order). */
    @Test
    fun `exact name wins over a looser contains-match on another car`() = runBlocking {
        // "Outlander" as a bare name is an exact match for AA:BB. It ALSO
        // appears inside CC:DD's full description (model "Outlander Sport"),
        // which the contains-match tier would hit too - exact name must win
        // before the contains tier is ever tried.
        seed(
            vehicle("AA:BB", "Outlander", model = "Outlander"),
            vehicle("CC:DD", "The Sport One", model = "Outlander Sport"),
        )

        val match = VehicleResolver.resolveVehicle(context, "Outlander")

        assertTrue(match is VehicleMatch.Resolved)
        assertEquals("AA:BB", (match as VehicleMatch.Resolved).vehicle.obdMac)
    }
}
