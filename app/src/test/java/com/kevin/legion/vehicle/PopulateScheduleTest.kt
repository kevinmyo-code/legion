package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [buildPopulateDiff], ticket 14's diff builder
 * (`.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`). No Room, no
 * Context - [buildPopulateDiff] is a plain function over two lists, same posture as
 * [VehicleControllerIsDueTest] and every other pure-logic test on this map.
 *
 * The base dataset in [realShapeExisting] mirrors ticket 01's on-device audit of Kevin's actual
 * schedule: ten items, one CONFIRMED with a real interval (Oil Change), two anchored orphans with
 * no interval at all (Brake Fluid/Brake Pads - created by [VehicleController.logServiceDirect]
 * matching nothing on the schedule), and seven unanchored SEEDED/CONFIRMED items of the kind
 * ticket 01 found riddled with duplicate concepts and at least one outright invention.
 */
class PopulateScheduleTest {

    /** Ticket 01's real shape: 10 items, Oil Change CONFIRMED with a real interval, Brake Fluid/
     * Brake Pads anchored with no interval, 7 unanchored (mixed SEEDED/CONFIRMED). */
    private fun realShapeExisting(): List<MaintenanceItem> = listOf(
        MaintenanceItem(
            vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 3,
            intervalSource = "CONFIRMED", lastDoneMileage = 185_000, lastDoneDate = 1_000L,
        ),
        MaintenanceItem(
            vehicleId = "V1", serviceName = "Brake Fluid", intervalSource = "SEEDED",
            lastDoneMileage = 180_000, lastDoneDate = 900L,
        ),
        MaintenanceItem(
            vehicleId = "V1", serviceName = "Brake Pads", intervalSource = "SEEDED",
            lastDoneMileage = 180_000, lastDoneDate = 900L,
        ),
        // 7 unanchored items.
        MaintenanceItem(vehicleId = "V1", serviceName = "Air Filter", intervalMiles = 12_000, intervalSource = "SEEDED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Cabin Air Filter", intervalMonths = 12, intervalSource = "SEEDED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Spark Plugs", intervalMiles = 30_000, intervalSource = "SEEDED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Coolant Flush", intervalMonths = 24, intervalSource = "SEEDED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Transmission Fluid", intervalMiles = 30_000, intervalSource = "CONFIRMED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Brake Fluid Flush", intervalMonths = 24, intervalSource = "SEEDED"),
        MaintenanceItem(vehicleId = "V1", serviceName = "Battery", intervalMonths = 48, intervalSource = "SEEDED"),
    )

    @Test
    fun `categorises Kevin's real shape into would-add, would-change and not-in-schedule`() {
        // The factory lookup (post prompt-fix, Schedule A): Oil Change's months axis differs (3 on
        // file, factory says 6), Air Filter and Coolant Flush match exactly, Spark Plugs' miles
        // differ, and Transfer Case Fluid is genuinely new. Everything else on file (Cabin Air
        // Filter, Transmission Fluid, Brake Fluid Flush, Battery, Brake Fluid, Brake Pads) is simply
        // absent from the factory's own vocabulary - ticket 02's actual finding for a 1998 XJ.
        val factory = listOf(
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 6),
            MaintenanceItem(vehicleId = "V1", serviceName = "Air Filter", intervalMiles = 12_000),
            MaintenanceItem(vehicleId = "V1", serviceName = "Spark Plugs", intervalMiles = 60_000),
            MaintenanceItem(vehicleId = "V1", serviceName = "Coolant Flush", intervalMonths = 24),
            MaintenanceItem(vehicleId = "V1", serviceName = "Transfer Case Fluid", intervalMiles = 30_000),
        )

        val diff = buildPopulateDiff(factory, realShapeExisting())!!

        // WOULD ADD: only the genuinely new factory item.
        assertEquals(listOf("Transfer Case Fluid"), diff.wouldAdd.map { it.serviceName })

        // WOULD CHANGE: Oil Change (months 3 -> 6) and Spark Plugs (miles 30,000 -> 60,000). Air
        // Filter and Coolant Flush match exactly and must NOT appear anywhere in the diff.
        assertEquals(setOf("Oil Change", "Spark Plugs"), diff.wouldChange.map { it.serviceName }.toSet())
        val oilChangeRow = diff.wouldChange.first { it.serviceName == "Oil Change" }
        assertEquals(7_500, oilChangeRow.currentMiles)
        assertEquals(3, oilChangeRow.currentMonths)
        assertEquals("CONFIRMED", oilChangeRow.currentSource)
        assertEquals(7_500, oilChangeRow.proposedMiles)
        assertEquals(6, oilChangeRow.proposedMonths)

        // NOT IN FACTORY SCHEDULE: every active item the factory list never matched - Air Filter and
        // Coolant Flush are NOT here (they matched and agreed), Oil Change and Spark Plugs are NOT
        // here (they matched and are in wouldChange instead).
        assertEquals(
            setOf("Brake Fluid", "Brake Pads", "Cabin Air Filter", "Transmission Fluid", "Brake Fluid Flush", "Battery"),
            diff.notInFactorySchedule.map { it.serviceName }.toSet(),
        )

        // Nothing tombstoned in this dataset.
        assertTrue(diff.wouldRestore.isEmpty())
    }

    @Test
    fun `an invented SEEDED row and a hand-typed CONFIRMED row both land in not-in-schedule but carry different provenance`() {
        // The factory list is deliberately NON-empty and deliberately agrees with the one row it
        // does name, so nothing it contributes shows up in the diff. It used to be `emptyList()`
        // here purely as a shortcut to force every existing row into notInFactorySchedule - which
        // is the exact input that is now a refused lookup (ticket 17), and this test asserting a
        // sensible answer for it is part of why the real bug looked covered.
        val factory = listOf(
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 3),
        )

        val diff = buildPopulateDiff(factory, realShapeExisting())!!

        val brakeFluidFlush = diff.notInFactorySchedule.first { it.serviceName == "Brake Fluid Flush" }
        val transmissionFluid = diff.notInFactorySchedule.first { it.serviceName == "Transmission Fluid" }

        // The wording ticket 14 asks for lives downstream (the UI reads intervalSource); this pins
        // that the flag SURVIVES the diff build untouched, which is what makes that wording possible.
        assertEquals("SEEDED", brakeFluidFlush.intervalSource)
        assertEquals("CONFIRMED", transmissionFluid.intervalSource)
    }

    @Test
    fun `a CONFIRMED row that differs from the factory proposal is shown, not silently overwritten`() {
        // buildPopulateDiff is pure - it cannot write anything by construction. This test pins the
        // half of ticket 05's "a CONFIRMED row is never silently overwritten" rule that IS this
        // function's job: the row must be SURFACED as a question, never silently dropped because it
        // was already confirmed, and never silently accepted on its behalf.
        val existing = listOf(
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 3,
                intervalSource = "CONFIRMED", lastDoneMileage = 185_000, lastDoneDate = 1_000L,
            ),
        )
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 6))

        val diff = buildPopulateDiff(factory, existing)!!

        assertEquals(1, diff.wouldChange.size)
        assertEquals("CONFIRMED", diff.wouldChange.single().currentSource)
        // Not silently dropped into "not in schedule" either - it matched by name, so it is a
        // CHANGE question, not a delete question.
        assertTrue(diff.notInFactorySchedule.isEmpty())
    }

    @Test
    fun `a tombstoned item the factory still lists is a would-restore row, never would-add or not-in-schedule`() {
        val existing = listOf(
            MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 6_000, deleted = true),
        )
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 7_500))

        val diff = buildPopulateDiff(factory, existing)!!

        assertEquals(listOf("Tire Rotation"), diff.wouldRestore.map { it.serviceName })
        assertEquals(7_500, diff.wouldRestore.single().proposedMiles)
        assertTrue("A tombstoned match must not also read as new", diff.wouldAdd.isEmpty())
        assertTrue("A tombstoned match must not also read as an active not-in-schedule row", diff.notInFactorySchedule.isEmpty())
    }

    @Test
    fun `a deleted item the factory does NOT list produces no row at all - it is not resurrected and not re-flagged`() {
        // Oil Change is carried on both sides, in agreement, so the factory list is non-empty (a
        // real lookup, not the refused one - ticket 17) while still contributing no diff row of its
        // own. What is under test is only the tombstoned Cabin Air Filter the factory never names.
        val existing = listOf(
            MaintenanceItem(vehicleId = "V1", serviceName = "Cabin Air Filter", intervalMonths = 12, deleted = true),
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 6),
        )
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500, intervalMonths = 6))

        val diff = buildPopulateDiff(factory, existing)!!

        assertTrue(diff.isEmpty)
    }

    @Test
    fun `near-miss names collapse to one comparison via the shared comparator, never two`() {
        // "Engine Air Filter" and "Air Filter" both canonicalise to "Air Filter" via
        // VehicleController.SERVICE_KEYWORDS' "air filter" entry - the exact duplicate-concept
        // pattern ticket 01 found across Kevin's real 54 rows (Air Filter / Air Filter Replacement /
        // Engine Air Filter as three separate rows). The diff must treat them as ONE item.
        val existing = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Air Filter", intervalMiles = 12_000, intervalSource = "SEEDED"))
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Engine Air Filter", intervalMiles = 15_000))

        val diff = buildPopulateDiff(factory, existing)!!

        // A near-miss that DISAGREES is a would-change question, not two independent rows.
        assertEquals(1, diff.wouldChange.size)
        assertEquals("Air Filter", diff.wouldChange.single().serviceName)
        assertTrue(diff.wouldAdd.isEmpty())
        assertTrue(diff.notInFactorySchedule.isEmpty())
    }

    @Test
    fun `near-miss names that already agree produce no row anywhere`() {
        val existing = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Air Filter", intervalMiles = 12_000, intervalSource = "SEEDED"))
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Engine Air Filter", intervalMiles = 12_000))

        val diff = buildPopulateDiff(factory, existing)!!

        assertTrue("Values already agree - nothing to review", diff.isEmpty)
    }

    @Test
    fun `isEmpty is true only when every one of the five categories is empty`() {
        // An agreeing pair: the factory names one item, the driver already has it with the same
        // intervals, so every category is empty. (This used to be `buildPopulateDiff(emptyList(),
        // emptyList())`, which is now a refused lookup rather than an empty diff - ticket 17.)
        val agreed = buildPopulateDiff(
            listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500)),
            listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500)),
        )!!
        assertTrue(agreed.isEmpty)
        val oneAdd = buildPopulateDiff(listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change")), emptyList())!!
        assertTrue(!oneAdd.isEmpty)
    }

    /**
     * Ticket 17, the on-device finding: an empty factory list is a FAILED LOOKUP, never a fact about
     * the car. The model is told in [VehicleController.lookupServiceIntervals]'s own prompt to return
     * `[]` when it cannot find the schedule, so this is the common failure, not an exotic one - and
     * before this guard it produced a diff proposing that the driver delete their entire schedule,
     * oil change included, under the words "the factory schedule doesn't list it".
     */
    @Test
    fun `an empty factory list is refused outright, never rendered as everything-not-in-schedule`() {
        assertNull(buildPopulateDiff(factoryItems = emptyList(), existingItems = realShapeExisting()))
        // Refused whatever the driver has on file, including nothing at all - the emptiness of the
        // FACTORY side is what decides it, and no existing-side shape may talk it back into a diff.
        assertNull(buildPopulateDiff(factoryItems = emptyList(), existingItems = emptyList()))
        assertNull(
            buildPopulateDiff(
                factoryItems = emptyList(),
                existingItems = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", deleted = true)),
            ),
        )
    }

    // --- possibleMatch: the near-miss category (ticket 14 review, BLOCKING 1b) --------------

    /**
     * The acceptance bar the review spec names by hand: "feed two differently-worded factory
     * strings for one non-keyword concept through buildPopulateDiff across two calls and assert no
     * duplicate wouldAdd." "Wheel Alignment Check" has no [VehicleController.SERVICE_KEYWORDS]
     * entry, so both phrasings canonicalize to two DIFFERENT titlecase-fallback strings - exactly
     * the gap [VehicleController.nearMissServiceName] exists to close.
     */
    @Test
    fun `a near-miss factory phrasing across two populate runs does not duplicate wouldAdd`() {
        val firstRunFactory = listOf(
            MaintenanceItem(
                vehicleId = "V1",
                serviceName = VehicleController.canonicalizeServiceName("Check the wheel alignment"),
                intervalMiles = 15_000,
            ),
        )
        val firstDiff = buildPopulateDiff(firstRunFactory, existingItems = emptyList())!!
        assertEquals(listOf("Check The Wheel Alignment"), firstDiff.wouldAdd.map { it.serviceName })
        assertTrue(firstDiff.possibleMatch.isEmpty())

        // Simulate the driver accepting that WOULD ADD row (ui/fleet/PopulateWrites.kt's
        // writePopulateAdd stores it verbatim, CONFIRMED).
        val afterAccept = listOf(
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Check The Wheel Alignment",
                intervalMiles = 15_000, intervalSource = "CONFIRMED",
            ),
        )

        // A re-run of the SAME populate, worded differently by the LLM this time - the exact
        // ticket-01 shape (Air Filter / Air Filter Replacement / Engine Air Filter), just for a
        // concept the keyword table has no entry for.
        val secondRunFactory = listOf(
            MaintenanceItem(
                vehicleId = "V1",
                serviceName = VehicleController.canonicalizeServiceName("Wheel alignment check"),
                intervalMiles = 15_000,
            ),
        )
        val secondDiff = buildPopulateDiff(secondRunFactory, existingItems = afterAccept)!!

        assertTrue("must not duplicate as a new item: ${secondDiff.wouldAdd}", secondDiff.wouldAdd.isEmpty())
        assertEquals(1, secondDiff.possibleMatch.size)
        assertEquals("Check The Wheel Alignment", secondDiff.possibleMatch.single().existingName)
        assertEquals("Wheel Alignment Check", secondDiff.possibleMatch.single().factoryName)
        // Values agree (both 15,000 mi) - still a QUESTION, never silently dropped as "matched and
        // agrees" the way an exact canonical match would be. The near-miss comparator is not
        // certain enough of the identity to skip asking.
        assertTrue(secondDiff.notInFactorySchedule.isEmpty())
    }

    @Test
    fun `a near-miss match is never silently folded into wouldChange`() {
        val existing = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Check The Wheel Alignment", intervalMiles = 15_000, intervalSource = "SEEDED"))
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Wheel Alignment Check", intervalMiles = 20_000))

        val diff = buildPopulateDiff(factory, existing)!!

        assertTrue("a near-miss must never land in wouldChange - it is not a confident match", diff.wouldChange.isEmpty())
        assertTrue("a near-miss must never land in wouldAdd - it is not confidently new", diff.wouldAdd.isEmpty())
        assertEquals(1, diff.possibleMatch.size)
        val row = diff.possibleMatch.single()
        assertEquals("Wheel Alignment Check", row.factoryName)
        assertEquals("Check The Wheel Alignment", row.existingName)
        assertEquals(15_000, row.currentMiles)
        assertEquals(20_000, row.proposedMiles)
        // The matched active row must not ALSO surface as "not in the factory schedule".
        assertTrue(diff.notInFactorySchedule.isEmpty())
    }

    @Test
    fun `an exact keyword match is never demoted to a possible match`() {
        // "Engine Air Filter" and "Air Filter" both hit the SERVICE_KEYWORDS table exactly - this
        // must stay a confident wouldChange, not fall into possibleMatch just because a near-miss
        // comparator now also exists in the pipeline.
        val existing = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Air Filter", intervalMiles = 12_000, intervalSource = "SEEDED"))
        val factory = listOf(MaintenanceItem(vehicleId = "V1", serviceName = "Engine Air Filter", intervalMiles = 15_000))

        val diff = buildPopulateDiff(factory, existing)!!

        assertEquals(1, diff.wouldChange.size)
        assertTrue(diff.possibleMatch.isEmpty())
    }
}
