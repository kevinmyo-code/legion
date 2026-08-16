package com.kevin.legion.data.local

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for `.scratch/fleet-maintenance/issues/13-the-jeep-row-lost-its-identity.md`'s
 * targeted-column fix: every write below MUST touch only the columns its own doc
 * comment in [VehicleDao] names, and nothing else on the row - that is the whole
 * point of replacing whole-row [VehicleDao.upsert] REPLACE writes with these.
 *
 * Each test seeds a [Vehicle] with a DISTINCT, recognisable value in every
 * column, calls exactly one targeted writer, then asserts every column NOT
 * named by that writer's own doc still holds its seeded value untouched - the
 * failure mode this guards against is a future edit to one of these queries
 * accidentally widening its SET clause back toward a whole-row write.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [GoalDaoTest] and [VehicleResolverTest] - see either's doc for why a
 * hand-rolled in-memory DB would not exercise the same wiring.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleDaoTargetedWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).vehicleDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Every column set to a value distinct from every other row's default, so any cross-contamination is loud. */
    private fun fullVehicle(mac: String) = Vehicle(
        obdMac = mac,
        name = "Original Name",
        make = "OriginalMake",
        model = "OriginalModel",
        year = 1999,
        personaPrompt = "original persona",
        odometerBaseline = 50_000,
        odometerBaselineAt = 1_000L,
        tripMilesSinceBaseline = 12.5,
        lastOdometerPromptAt = 2_000L,
        onboarded = false,
        voiceName = "Kore",
        personaTraits = """{"trait":"original"}""",
        trim = "OriginalTrim",
        confirmed = false,
        updatedAt = 3_000L,
        archived = false,
    )

    @Test
    fun `markOdometerPrompted touches only lastOdometerPromptAt and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:01"))
        val before = dao.getByMac("AA:01")!!

        dao.markOdometerPrompted("AA:01", at = 9_999L, now = 8_888L)
        val after = dao.getByMac("AA:01")!!

        assertEquals(9_999L, after.lastOdometerPromptAt)
        assertEquals(8_888L, after.updatedAt)
        // Everything else, byte-for-byte identical to the seeded row.
        assertEquals(before.copy(lastOdometerPromptAt = 9_999L, updatedAt = 8_888L), after)
    }

    @Test
    fun `markOnboarded touches only onboarded and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:02"))
        val before = dao.getByMac("AA:02")!!

        dao.markOnboarded("AA:02", now = 7_777L)
        val after = dao.getByMac("AA:02")!!

        assertEquals(true, after.onboarded)
        assertEquals(7_777L, after.updatedAt)
        assertEquals(before.copy(onboarded = true, updatedAt = 7_777L), after)
    }

    @Test
    fun `setOdometerBaseline touches only the odometer baseline fields and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:03"))
        val before = dao.getByMac("AA:03")!!

        dao.setOdometerBaseline("AA:03", miles = 142_500, at = 6_666L, now = 6_666L)
        val after = dao.getByMac("AA:03")!!

        assertEquals(142_500, after.odometerBaseline)
        assertEquals(6_666L, after.odometerBaselineAt)
        assertEquals(0.0, after.tripMilesSinceBaseline, 0.0)
        assertEquals(6_666L, after.lastOdometerPromptAt)
        assertEquals(6_666L, after.updatedAt)
        assertEquals(
            before.copy(
                odometerBaseline = 142_500,
                odometerBaselineAt = 6_666L,
                tripMilesSinceBaseline = 0.0,
                lastOdometerPromptAt = 6_666L,
                updatedAt = 6_666L,
            ),
            after,
        )
    }

    @Test
    fun `setIdentity touches only identity fields and confirmed and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:04"))
        val before = dao.getByMac("AA:04")!!

        dao.setIdentity("AA:04", year = 2003, make = "BMW", model = "330i", trim = "ZHP", name = "The Beemer", now = 5_555L)
        val after = dao.getByMac("AA:04")!!

        assertEquals(2003, after.year)
        assertEquals("BMW", after.make)
        assertEquals("330i", after.model)
        assertEquals("ZHP", after.trim)
        assertEquals("The Beemer", after.name)
        assertEquals(true, after.confirmed)
        assertEquals(5_555L, after.updatedAt)
        // The odometer, persona and archive state must ride along untouched -
        // this is the exact ticket-13 field-drop registerDirect used to cause.
        assertEquals(before.odometerBaseline, after.odometerBaseline)
        assertEquals(before.odometerBaselineAt, after.odometerBaselineAt)
        assertEquals(before.tripMilesSinceBaseline, after.tripMilesSinceBaseline, 0.0)
        assertEquals(before.voiceName, after.voiceName)
        assertEquals(before.personaTraits, after.personaTraits)
        assertEquals(before.personaPrompt, after.personaPrompt)
        assertEquals(before.archived, after.archived)
        assertEquals(before.lastOdometerPromptAt, after.lastOdometerPromptAt)
    }

    @Test
    fun `setArchived touches only archived and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:05"))
        val before = dao.getByMac("AA:05")!!

        dao.setArchived("AA:05", archived = true, now = 4_444L)
        val after = dao.getByMac("AA:05")!!

        assertEquals(true, after.archived)
        assertEquals(4_444L, after.updatedAt)
        assertEquals(before.copy(archived = true, updatedAt = 4_444L), after)
    }

    @Test
    fun `addTripMiles accumulates across two calls rather than overwriting`() = runBlocking {
        dao.upsert(fullVehicle("AA:06").copy(tripMilesSinceBaseline = 0.0))

        dao.addTripMiles("AA:06", delta = 1.25, now = 100L)
        dao.addTripMiles("AA:06", delta = 2.5, now = 200L)

        val after = dao.getByMac("AA:06")!!
        assertEquals(3.75, after.tripMilesSinceBaseline, 1e-9)
        assertEquals(200L, after.updatedAt)
    }

    @Test
    fun `addTripMiles is a no-op against a mac with no row yet`() = runBlocking {
        // Ticket 13's design: telemetry for a car nobody has registered must
        // not manufacture one. A targeted UPDATE against a missing row simply
        // affects zero rows rather than inserting anything.
        dao.addTripMiles("NEVER:SEEN", delta = 5.0, now = 100L)

        assertEquals(null, dao.getByMac("NEVER:SEEN"))
        assertEquals(0, dao.getAll().size)
    }

    // --- clearThisCarSentinel (ticket 04's label rule) -------------------

    @Test
    fun `clearThisCarSentinel blanks the name on a row carrying the retired sentinel, touching only name and updatedAt`() = runBlocking {
        dao.upsert(fullVehicle("AA:07").copy(name = "this car"))
        val before = dao.getByMac("AA:07")!!

        dao.clearThisCarSentinel(now = 1_111L)
        val after = dao.getByMac("AA:07")!!

        assertEquals("", after.name)
        assertEquals(1_111L, after.updatedAt)
        assertEquals(before.copy(name = "", updatedAt = 1_111L), after)
    }

    @Test
    fun `clearThisCarSentinel leaves every other name alone, including a real driver-typed one`() = runBlocking {
        dao.upsert(fullVehicle("AA:08").copy(name = "1998 Jeep Cherokee"))
        val before = dao.getByMac("AA:08")!!

        dao.clearThisCarSentinel(now = 1_111L)
        val after = dao.getByMac("AA:08")!!

        assertEquals(before, after)
    }

    @Test
    fun `clearThisCarSentinel is idempotent - a no-op once nothing matches`() = runBlocking {
        dao.upsert(fullVehicle("AA:09").copy(name = "this car"))
        dao.clearThisCarSentinel(now = 1_111L)
        val after = dao.getByMac("AA:09")!!
        assertEquals("", after.name)

        // Nothing left matches "this car", so a second pass must not throw and must not
        // re-stamp updatedAt on a row it no longer touches.
        dao.clearThisCarSentinel(now = 2_222L)
        val stillAfter = dao.getByMac("AA:09")!!
        assertEquals(after, stillAfter)
    }
}
