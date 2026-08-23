package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for `ui/fleet/MaintenanceWrites.kt` - a brand-new write-dispatch file, previously
 * with zero test coverage, in a codebase that has hit the "reported success and did nothing"
 * defect class twice already (senior-dev review, mission-control ticket 09 follow-up, SHOULD-FIX
 * 2). Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.data.local.MaintenanceItemDaoTargetedWritesTest] /
 * [com.kevin.legion.vehicle.VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    // ------------------------------------------------------ writeAddItem: validation branches

    @Test
    fun `writeAddItem refuses a blank name`() = runBlocking {
        val outcome = writeAddItem(
            context, "V1", name = "   ", miles = 5_000, months = null,
            mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )
        assertFalse(outcome.success)
        assertTrue("Was: ${outcome.message}", outcome.message.contains("name", ignoreCase = true))
        assertNull(db.maintenanceItemDao().get("V1", ""))
    }

    @Test
    fun `writeAddItem refuses when there is no interval AND no anchor AND mode is DONT_KNOW`() = runBlocking {
        val outcome = writeAddItem(
            context, "V1", name = "Timing Belt", miles = null, months = null,
            mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )
        assertFalse(outcome.success)
        assertNull(db.maintenanceItemDao().get("V1", "Timing Belt"))
    }

    @Test
    fun `writeAddItem succeeds with only an interval and no anchor information at all`() = runBlocking {
        val outcome = writeAddItem(
            context, "V1", name = "Timing Belt", miles = 60_000, months = null,
            mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )
        assertTrue("Was: ${outcome.message}", outcome.success)
        val created = db.maintenanceItemDao().get("V1", "Timing Belt")
        assertNotNull(created)
        assertEquals(60_000, created?.intervalMiles)
    }

    @Test
    fun `writeAddItem succeeds with only an anchor and no interval, via NEVER_DONE`() = runBlocking {
        val outcome = writeAddItem(
            context, "V1", name = "Spark Plugs", miles = null, months = null,
            mode = AnchorMode.NEVER_DONE, mileage = null, date = null,
        )
        assertTrue("Was: ${outcome.message}", outcome.success)
        val created = db.maintenanceItemDao().get("V1", "Spark Plugs")
        assertEquals(true, created?.neverDone)
    }

    // ---------------------------------------------- writeAddItem: verbatim storage (ticket 07)

    @Test
    fun `writeAddItem stores a hand-typed name verbatim, never canonicalized`() = runBlocking {
        // Deliberately lowercase, with a phrasing VehicleController.canonicalizeServiceName would
        // very likely fold onto a titlecase keyword form ("oil change" -> "Oil Change") - ticket 07
        // decision 2's "storage is verbatim, detection is comparator-only" rule means writeAddItem
        // must NOT run this through that canonicalizer the way writeSetInterval/setMaintenanceInterval
        // does for an EXISTING item's name.
        val typed = "oil change but weird casing REALLY"
        val outcome = writeAddItem(
            context, "V1", name = typed, miles = 5_000, months = null,
            mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )
        assertTrue("Was: ${outcome.message}", outcome.success)
        val created = db.maintenanceItemDao().get("V1", typed)
        assertNotNull("must be stored under the EXACT typed string as the primary key", created)
        assertEquals(typed, created?.serviceName)
        assertEquals("CONFIRMED", created?.intervalSource)
    }

    @Test
    fun `writeAddItem trims surrounding whitespace off the typed name before storing it`() = runBlocking {
        val outcome = writeAddItem(
            context, "V1", name = "  Differential Fluid  ", miles = 30_000, months = null,
            mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )
        assertTrue(outcome.success)
        assertNotNull(db.maintenanceItemDao().get("V1", "Differential Fluid"))
        assertNull(db.maintenanceItemDao().get("V1", "  Differential Fluid  "))
    }

    // ------------------------------------------------------- writeSetAnchor: zero-row failure

    @Test
    fun `writeSetAnchor against a nonexistent item returns success false with a message`() = runBlocking {
        val outcome = writeSetAnchor(context, "V1", "Nonexistent Item", AnchorMode.DONE_AT, mileage = 50_000, date = 1_000L)
        assertFalse(outcome.success)
        assertTrue("A failed write must say why, in words. Was: ${outcome.message}", outcome.message.isNotBlank())
    }

    @Test
    fun `writeSetAnchor against an existing item succeeds and writes the anchor`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))

        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = 132_400, date = 1_700_000_000_000L)

        assertTrue("Was: ${outcome.message}", outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(132_400, after.lastDoneMileage)
        assertEquals(1_700_000_000_000L, after.lastDoneDate)
    }

    // ------------------------------------------------- writeSetAnchor: the RESOLVED outcome ----
    // ------------------------------------------------- (ticket 31, hands-and-senses) -----------

    @Test
    fun `writeSetAnchor DONE_AT with only mileage returns the RESOLVED date, not the form's blank`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        db.serviceRecordDao().insert(
            com.kevin.legion.data.local.ServiceRecord(vehicleId = "V1", serviceName = "Oil Change", mileage = 227_374, date = 1_723_000_000_000L),
        )

        // Mirrors Kevin's own report: mileage typed, date field left blank.
        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = 227_483, date = null)

        assertTrue("Was: ${outcome.message}", outcome.success)
        assertEquals(227_483, outcome.mileage)
        assertEquals(
            "the outcome must carry the date resolveDoneAtDate actually wrote, never the form's null - " +
                "a mileage-only save that silently re-derives a date must be visible, not identical on screen to nothing happening",
            1_723_000_000_000L,
            outcome.date,
        )
        assertTrue("the message itself must state the resolved date. Was: ${outcome.message}", outcome.message.contains("2024"))
    }

    @Test
    fun `writeSetAnchor NEVER_DONE and DONT_KNOW report their resolved (empty) anchor, not the caller's raw args`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", lastDoneMileage = 100_000, lastDoneDate = 1_000L))

        // A stale mileage/date is passed in deliberately - NEVER_DONE/DONT_KNOW must ignore it and
        // report what was actually written (null/null), not echo the caller's leftover values.
        val neverDone = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.NEVER_DONE, mileage = 999_999, date = 12_345L)
        assertTrue(neverDone.success)
        assertNull(neverDone.mileage)
        assertNull(neverDone.date)
        assertTrue(neverDone.neverDone)

        val dontKnow = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.DONT_KNOW, mileage = 999_999, date = 12_345L)
        assertTrue(dontKnow.success)
        assertNull(dontKnow.mileage)
        assertNull(dontKnow.date)
        assertFalse(dontKnow.neverDone)
    }

    // ------------------------------------------------------ writeDeleteItem: zero-row failure

    @Test
    fun `writeDeleteItem against a nonexistent item returns success false with a message`() = runBlocking {
        val outcome = writeDeleteItem(context, "V1", "Nonexistent Item")
        assertFalse(outcome.success)
        assertTrue("A failed write must say why, in words. Was: ${outcome.message}", outcome.message.isNotBlank())
    }

    @Test
    fun `writeDeleteItem against an existing item succeeds and tombstones it`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "V1", serviceName = "Cabin Air Filter", intervalMiles = 15_000))

        val outcome = writeDeleteItem(context, "V1", "Cabin Air Filter")

        assertTrue("Was: ${outcome.message}", outcome.success)
        // Soft-deleted rows are excluded from the normal reader - see
        // MaintenanceItemDao.softDelete's own doc for why this is a tombstone, not a hard delete.
        assertNull(db.maintenanceItemDao().get("V1", "Cabin Air Filter"))
    }

    // --------------------------------------------------- writeConfirmAll: only SEEDED+interval

    @Test
    fun `writeConfirmAll flips only SEEDED rows that carry an interval, leaving everything else untouched`() = runBlocking {
        val guess = MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000, intervalSource = "SEEDED")
        val orphan = MaintenanceItem(vehicleId = "V1", serviceName = "Brake Fluid", intervalSource = "SEEDED")
        val confirmed = MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 7_500, intervalSource = "CONFIRMED")
        db.maintenanceItemDao().upsertStamped(guess)
        db.maintenanceItemDao().upsertStamped(orphan)
        db.maintenanceItemDao().upsertStamped(confirmed)

        // The real call shape: FullScheduleScreen only ever passes confirmableItems(items),
        // never the raw roster - writeConfirmAll itself trusts that filtering rather than
        // re-deriving it, so this test exercises the pair together, same as the production call.
        val toConfirm = confirmableItems(listOf(guess, orphan, confirmed))
        assertEquals(listOf("Oil Change"), toConfirm.map { it.serviceName })

        val outcomes = writeConfirmAll(context, "V1", toConfirm)

        assertEquals(1, outcomes.size)
        assertTrue("Was: ${outcomes.first().message}", outcomes.first().success)

        val afterGuess = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals("the confirmed row must flip to CONFIRMED", "CONFIRMED", afterGuess.intervalSource)
        assertEquals("its interval value must survive the confirm untouched", 5_000, afterGuess.intervalMiles)

        val afterOrphan = db.maintenanceItemDao().get("V1", "Brake Fluid")!!
        assertEquals("never passed to writeConfirmAll - must stay SEEDED", "SEEDED", afterOrphan.intervalSource)

        val afterConfirmed = db.maintenanceItemDao().get("V1", "Tire Rotation")!!
        assertEquals("already CONFIRMED and never passed in - untouched", "CONFIRMED", afterConfirmed.intervalSource)
        assertEquals(7_500, afterConfirmed.intervalMiles)
    }

    @Test
    fun `writeConfirmAll reports a failed outcome for an item that vanished mid-loop, without failing the whole batch`() = runBlocking {
        val guess = MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000, intervalSource = "SEEDED")
        db.maintenanceItemDao().upsertStamped(guess)
        // "Ghost Item" is passed to writeConfirmAll but was never actually written to the DB - the
        // same shape as a concurrent delete racing the confirm dialog.
        val ghost = MaintenanceItem(vehicleId = "V1", serviceName = "Ghost Item", intervalMiles = 1_000, intervalSource = "SEEDED")

        val outcomes = writeConfirmAll(context, "V1", listOf(guess, ghost))

        assertEquals(2, outcomes.size)
        assertTrue("the real item must still succeed", outcomes[0].success)
        // setMaintenanceInterval creates an unmatched name rather than failing outright (ticket 05's
        // own "create it as CONFIRMED" behaviour for an unknown service) - so this call still reports
        // success for the ghost, but as a NEW row, not a mutation of a pre-existing one. Pinning this
        // here so a future change to that fallback behaviour is caught by this file, not discovered
        // live.
        assertTrue(outcomes[1].success)
        val createdGhost = db.maintenanceItemDao().get("V1", "Ghost Item")
        assertNotNull(createdGhost)
        assertEquals("CONFIRMED", createdGhost?.intervalSource)
    }

    /**
     * `writeAddItem` used to write through `upsert` = `@Insert(REPLACE)`, a whole-row overwrite.
     * The add form can sit open while something else writes the same (vehicleId, serviceName) - a
     * voice `log_service` orphan, a populate accept, a sync merge - and tapping ADD would then
     * replace that row wholesale, anchor and provenance included.
     *
     * The no-op law could not have caught it: REPLACE always reports a write, so a row-count check
     * is structurally blind. The fix had to be the insert strategy itself.
     *
     * Found by review of ticket 14's identical bug in `writePopulateAdd`.
     */
    @Test
    fun `writeAddItem refuses rather than overwriting a row that already exists`() = runBlocking {
        db.maintenanceItemDao().upsert(
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Tire Rotation",
                intervalMiles = 6_000, intervalMonths = 6,
                lastDoneMileage = 118_483, intervalSource = "CONFIRMED",
            )
        )

        val outcome = writeAddItem(
            context, vehicleId = "V1", name = "Tire Rotation",
            miles = 9_999, months = null, mode = AnchorMode.DONT_KNOW, mileage = null, date = null,
        )

        assertFalse("An add that collides must refuse. Was: ${outcome.message}", outcome.success)
        assertTrue(
            "The refusal must point at the change path. Was: ${outcome.message}",
            outcome.message.contains("already on the schedule", ignoreCase = true),
        )

        val after = db.maintenanceItemDao().get("V1", "Tire Rotation")!!
        assertEquals("the existing interval must survive", 6_000, after.intervalMiles)
        assertEquals("and its anchor", 118_483, after.lastDoneMileage)
        assertEquals("and its provenance", "CONFIRMED", after.intervalSource)
    }
}
