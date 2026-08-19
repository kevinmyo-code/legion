package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
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
 * Controller-level regressions for
 * `.scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md`.
 *
 * [VehicleControllerSeedingTest] pins "a placeholder is never persisted" and
 * `VehicleDaoTargetedWritesTest` pins "each query touches only its own columns".
 * Neither covers the three BEHAVIOUR changes the ticket names by hand, which is
 * the gap senior-dev flagged on review (CLAUDE.md L11: a surfaced verification
 * gap is a gate, not a note):
 *
 *  1. [VehicleController.registerDirect] preserving the columns it used to drop.
 *  2. [VehicleController.setOdometer] refusing rather than silently swallowing a
 *     reading for a car with no row - the false-success that the seeding fix
 *     would otherwise have pushed one layer down.
 *  3. [VehicleController.correctVehicle]'s "Nothing to change there" branch,
 *     which was effectively dead before this change and is now reachable.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleControllerSeedingTest].
 *
 * Note on the interval lookup: as of ticket 14
 * (`.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`), neither
 * [VehicleController.registerDirect] nor `correctVehicle` touches the factory-schedule lookup at
 * all anymore - that automatic seed is deleted. These tests exercise the identity-write path with
 * no schedule interaction whatsoever now, which only makes the original point more true: the write
 * being pinned here never depended on the lookup, and now there is no lookup for it to depend on.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleControllerIdentityWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).vehicleDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * The old insert-branch built a fresh `Vehicle(...)` that never named `trim`,
     * so every `register_vehicle` call silently reset it to "" - along with
     * voiceName, personaTraits, archived and lastOdometerPromptAt. Found on
     * review of the ticket 13 fix, and it was firing on EVERY registration, not
     * only on the rare path that destroyed Kevin's row.
     */
    @Test
    fun `registerDirect on an existing row preserves every column it does not own`() = runBlocking {
        val existing = Vehicle(
            obdMac = "AA:BB:CC:DD:EE:FF",
            name = "Old Nickname",
            make = "Jeep",
            model = "Cherokee",
            year = 1997,
            personaPrompt = "a persona",
            odometerBaseline = 118_374,
            odometerBaselineAt = 1_000L,
            tripMilesSinceBaseline = 12.5,
            lastOdometerPromptAt = 2_000L,
            onboarded = true,
            voiceName = "Charon",
            personaTraits = """{"warmth":3}""",
            trim = "Sport 4.0L",
            confirmed = true,
        )
        dao.upsert(existing)
        ActiveVehicle.select(context, "AA:BB:CC:DD:EE:FF")

        VehicleController.registerDirect(context, year = 1998, make = "Jeep", model = "Cherokee")

        val after = dao.getByMac("AA:BB:CC:DD:EE:FF")!!
        // Identity changed, as asked.
        assertEquals(1998, after.year)
        assertEquals("Jeep", after.make)
        assertEquals("Cherokee", after.model)
        // Everything else survived. Each of these was reset to a default before.
        assertEquals("Sport 4.0L", after.trim)
        assertEquals("Charon", after.voiceName)
        assertEquals("""{"warmth":3}""", after.personaTraits)
        assertEquals("a persona", after.personaPrompt)
        assertEquals("Old Nickname", after.name)
        assertEquals(118_374, after.odometerBaseline)
        assertEquals(1_000L, after.odometerBaselineAt)
        assertEquals(12.5, after.tripMilesSinceBaseline, 0.0001)
        assertEquals(2_000L, after.lastOdometerPromptAt)
        // Exactly one row: a register against an existing id must not fork a second.
        assertEquals(1, dao.getAllIncludingArchived().size)
    }

    /**
     * Since [VehicleController.seedVehicle] stopped persisting, a targeted UPDATE
     * can legitimately name a row that does not exist - and SQL reports success
     * while writing nothing. Saying "Got it, 142,500 on the clock" to that is the
     * same false success ticket 13 was opened to remove, just moved one layer
     * down (lessons.md L16).
     */
    /**
     * Ticket 05 promoted [VehicleController.setOdometer]'s String reply to a
     * [VehicleController.WriteOutcome] so a caller (LiveToolbox) can derive
     * `success` from the write itself rather than string-matching the
     * message - `success` is asserted directly here for the same reason.
     */
    @Test
    fun `setOdometer on a car with no row refuses instead of silently swallowing the reading`() = runBlocking {
        val reply = VehicleController.setOdometer(context, miles = 142_500, vehicleId = "never:registered:mac")

        assertFalse("A write that touched zero rows must report success = false. Was: $reply", reply.success)
        assertTrue(
            "Reply must say the car is not on file, not claim success. Was: $reply",
            reply.message.contains("don't have this car on file", ignoreCase = true),
        )
        assertTrue("Reply should still carry the number so it is not lost to the driver", reply.message.contains("142500"))
        assertNull("No row may be created by an odometer write", dao.getByMac("never:registered:mac"))
        assertTrue(dao.getAllIncludingArchived().isEmpty())
    }

    /** The happy path still works - the guard above must not have broken it. */
    @Test
    fun `setOdometer on a registered car writes the baseline and resets the trip accumulator`() = runBlocking {
        dao.upsert(
            Vehicle(
                obdMac = "REAL:MAC", name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", tripMilesSinceBaseline = 40.0,
            )
        )

        val reply = VehicleController.setOdometer(context, miles = 227_500, vehicleId = "REAL:MAC")

        val after = dao.getByMac("REAL:MAC")!!
        assertEquals(227_500, after.odometerBaseline)
        assertEquals(0.0, after.tripMilesSinceBaseline, 0.0001)
        assertTrue("Baseline timestamp must be stamped", after.odometerBaselineAt > 0L)
        assertTrue("A write that landed must report success = true. Was: $reply", reply.success)
        assertTrue("Reply must not be the refusal. Was: $reply", !reply.message.contains("don't have this car on file", true))
    }

    /**
     * Before the ticket 13 fix, `correctVehicle` injected a fresh `updatedAt`
     * into its own `.copy()`, so `updated == existing` was true only if two calls
     * landed on the same millisecond - the branch was dead in practice. The stamp
     * now lives only in the `setIdentity` call, so the comparison reduces to
     * "did any of the six touched fields actually change".
     */
    @Test
    fun `correctVehicle with nothing to change says so and does not move the sync stamp`() = runBlocking {
        dao.upsert(
            Vehicle(
                obdMac = "REAL:MAC", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", confirmed = true,
            )
        )

        VehicleController.correctVehicle(context, "REAL:MAC", year = 1998, make = "Jeep", model = "Cherokee")
        val stampAfterFirst = dao.getByMac("REAL:MAC")!!.updatedAt

        val second = VehicleController.correctVehicle(context, "REAL:MAC", year = 1998, make = "Jeep", model = "Cherokee")

        assertTrue(
            "Second identical correction must report nothing to change. Was: $second",
            second.contains("Nothing to change", ignoreCase = true),
        )
        assertEquals(
            "A no-op correction must not re-stamp updatedAt - LWW would read it as a newer edit",
            stampAfterFirst,
            dao.getByMac("REAL:MAC")!!.updatedAt,
        )
    }
}
