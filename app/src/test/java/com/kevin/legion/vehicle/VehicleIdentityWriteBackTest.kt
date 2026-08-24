package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 04's VIN identity write-back
 * (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`,
 * `13-the-jeep-row-lost-its-identity.md`): [VehicleController.applyDecodedIdentity]'s
 * fill-blanks / agree / conflict / unusable policy, plus
 * [VehicleSpecController.reconcileIdentityFromStoredVin]'s "no VIN on file" branch.
 *
 * [VehicleController.applyDecodedIdentity] takes an already-decoded [VinDecoder.DecodedVin]
 * rather than a raw VIN string, deliberately - it never touches the network itself, which is what
 * makes it testable directly under Robolectric without depending on a real vPIC call (which
 * `VehicleControllerIdentityWritesTest`'s own doc notes fails and is caught under Robolectric
 * today). [reconcileIdentityFromStoredVin]'s NoStoredVin branch is tested the same way, for the
 * same reason: it returns before ever reaching the network.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as every other vehicle
 * test in this package.
 */
@RunWith(RobolectricTestRunner::class)
class VehicleIdentityWriteBackTest {
    private val context = RuntimeEnvironment.getApplication()
    private val dao get() = CarDatabase.getDatabase(context).vehicleDao()
    private val specDao get() = CarDatabase.getDatabase(context).vehicleSpecDao()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun `blank identity fields are filled from the decode`() = runBlocking {
        // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): a real ENGINE Vehicle record must
        // exist - FleetEngineStore.applyDecodedIdentity resolves the row by its deterministic
        // engine guid, which a legacy-only dao.upsert fixture would leave unresolvable.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "REAL:MAC", name = "Old Nickname", make = "", model = "", year = 0,
                trim = "", personaPrompt = "", confirmed = false,
            )
        )
        val decoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "Jeep", model = "Cherokee", trim = "Sport")

        val result = VehicleController.applyDecodedIdentity(context, "REAL:MAC", decoded)

        assertTrue("Expected Applied, got $result", result is IdentityWriteResult.Applied)
        val changed = (result as IdentityWriteResult.Applied).changedFields
        assertEquals(setOf("year", "make", "model", "trim"), changed.toSet())

        val after = dao.getByMac("REAL:MAC")!!
        assertEquals(1998, after.year)
        assertEquals("Jeep", after.make)
        assertEquals("Cherokee", after.model)
        assertEquals("Sport", after.trim)
        // Never touched by this write - see VehicleDao.applyDecodedIdentity's own doc.
        assertEquals("Old Nickname", after.name)
        assertEquals(false, after.confirmed)
    }

    @Test
    fun `a decode that already matches every field on file is a no-op`() = runBlocking {
        dao.upsert(
            Vehicle(
                obdMac = "REAL:MAC", name = "Jeep", make = "Jeep", model = "Cherokee", year = 1998,
                trim = "Sport", personaPrompt = "", confirmed = true,
            )
        )
        val before = dao.getByMac("REAL:MAC")!!.updatedAt
        // Case difference on make deliberately - agreement is case-insensitive.
        val decoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "JEEP", model = "Cherokee", trim = "Sport")

        val result = VehicleController.applyDecodedIdentity(context, "REAL:MAC", decoded)

        assertEquals(IdentityWriteResult.NothingToDo, result)
        val after = dao.getByMac("REAL:MAC")!!
        assertEquals(
            "A no-op must not re-stamp updatedAt - LWW would read it as a newer edit",
            before, after.updatedAt,
        )
    }

    @Test
    fun `a disagreeing field blocks the whole write, not just that field`() = runBlocking {
        // year is BLANK (fillable) and model DISAGREES with the decode - the conflict on model
        // must stop year from being filled too. A half-applied identity is the exact confused
        // state this ticket exists to close, not a smaller version of it.
        dao.upsert(
            Vehicle(
                obdMac = "REAL:MAC", name = "Jeep", make = "Jeep", model = "Cherokee Sport", year = 0,
                trim = "", personaPrompt = "", confirmed = true,
            )
        )
        val before = dao.getByMac("REAL:MAC")!!
        val decoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "Jeep", model = "Cherokee", trim = "")

        val result = VehicleController.applyDecodedIdentity(context, "REAL:MAC", decoded)

        assertTrue("Expected Conflict, got $result", result is IdentityWriteResult.Conflict)
        val conflicts = (result as IdentityWriteResult.Conflict).fields
        assertEquals(1, conflicts.size)
        assertEquals("model", conflicts[0].field)
        assertEquals("Cherokee Sport", conflicts[0].onFile)
        assertEquals("Cherokee", conflicts[0].decoded)

        val after = dao.getByMac("REAL:MAC")!!
        assertEquals("Nothing may be written when any field conflicts", before, after)
    }

    @Test
    fun `an unusable decode changes nothing and says so`() = runBlocking {
        dao.upsert(
            Vehicle(obdMac = "REAL:MAC", name = "Jeep", make = "", model = "", year = 0, personaPrompt = "")
        )
        val before = dao.getByMac("REAL:MAC")!!

        val resultFromNull = VehicleController.applyDecodedIdentity(context, "REAL:MAC", null)
        assertEquals(IdentityWriteResult.Unusable, resultFromNull)

        // isUsable requires BOTH make and model - a decode with neither is unusable even if
        // non-null, and applyDecodedIdentity must not crash or partially act on it.
        val emptyDecoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "", model = "", trim = "")
        val resultFromEmpty = VehicleController.applyDecodedIdentity(context, "REAL:MAC", emptyDecoded)
        assertEquals(IdentityWriteResult.Unusable, resultFromEmpty)

        assertEquals(before, dao.getByMac("REAL:MAC")!!)
    }

    @Test
    fun `applying a decode against an unregistered vehicle id reports NoSuchVehicle`() = runBlocking {
        val decoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "Jeep", model = "Cherokee", trim = "")

        val result = VehicleController.applyDecodedIdentity(context, "never:registered:mac", decoded)

        assertEquals(IdentityWriteResult.NoSuchVehicle, result)
        assertNull(dao.getByMac("never:registered:mac"))
    }

    @Test
    fun `reconcileIdentityFromStoredVin with no vehicle_specs row says so distinctly from a decode failure`() = runBlocking {
        dao.upsert(
            Vehicle(obdMac = "REAL:MAC", name = "Jeep", make = "", model = "", year = 0, personaPrompt = "")
        )
        // No VehicleSpec row at all for REAL:MAC.

        val result = VehicleSpecController.reconcileIdentityFromStoredVin(context, "REAL:MAC")

        assertEquals(VinRefreshResult.NoStoredVin, result)
    }

    @Test
    fun `reconcileIdentityFromStoredVin with a blank vin on the spec row also says NoStoredVin`() = runBlocking {
        dao.upsert(
            Vehicle(obdMac = "REAL:MAC", name = "Jeep", make = "", model = "", year = 0, personaPrompt = "")
        )
        specDao.upsert(VehicleSpec(vehicleId = "REAL:MAC", vin = ""))

        val result = VehicleSpecController.reconcileIdentityFromStoredVin(context, "REAL:MAC")

        assertEquals(VinRefreshResult.NoStoredVin, result)
    }

    @Test
    fun `a driver-typed value with stray whitespace is not a conflict`() = runBlocking {
        // Regression, caught on review 2026-08-15. VinDecoder.parse trims and title-cases what it
        // decodes; correctVehicle stores driver-typed make/model/trim with no trim() at all. So a
        // trailing space off a text field used to read as a real disagreement - and a false
        // conflict is expensive here, because ANY conflict aborts the whole write and shows the
        // driver a difference that does not exist.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "REAL:MAC", name = "Jeep", make = "  Jeep ", model = "Cherokee  ", year = 1998,
                trim = "", personaPrompt = "", confirmed = true,
            )
        )
        val decoded = VinDecoder.DecodedVin(vin = "1FAKEVIN000000001", year = 1998, make = "Jeep", model = "Cherokee", trim = "Sport")

        val result = VehicleController.applyDecodedIdentity(context, "REAL:MAC", decoded)

        // make/model agree once trimmed, year agrees, trim is blank on file -> one real fill.
        assertTrue("Whitespace must not read as a conflict. Got $result", result is IdentityWriteResult.Applied)
        assertEquals(listOf("trim"), (result as IdentityWriteResult.Applied).changedFields)

        val after = dao.getByMac("REAL:MAC")!!
        assertEquals("Sport", after.trim)
        // The untouched fields keep the driver's own text verbatim, whitespace and all - this
        // comparison is about deciding IF they conflict, never about rewriting what he typed.
        assertEquals("  Jeep ", after.make)
        assertEquals("Cherokee  ", after.model)
    }
}
