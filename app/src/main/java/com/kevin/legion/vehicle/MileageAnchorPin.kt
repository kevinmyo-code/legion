package com.kevin.legion.vehicle

import android.content.Context

/**
 * The spoken ask for a mileage reading, and where the answer goes.
 *
 * Every surface that reports what is due names an item whose only interval is a mileage one and
 * whose newest service carries no reading ([needsMileageReading]), and asks for a reading, because
 * a reading is the one thing that can bring such an item due. Round 2 of the RED review on PR #5
 * found the ask unanswerable: the only tool that takes an odometer reading is `set_odometer`,
 * `VehicleController.setOdometer` wrote the vehicle's baseline and nothing else, and
 * [needsMileageReading] reads the ITEM's anchor - so the same question was asked again the next
 * time he asked what was due, forever, and Matrix's "Say your mileage and it will" was a promise
 * the code could not keep.
 *
 * **One route, and it is this one.** A stated reading lands on the items that are waiting for one,
 * through the anchor writer the driver's own statements already use
 * ([FleetEngineStore.setAnchor]'s ASSERTED row). The alternatives were each worse: writing today's
 * figure onto the mileage-less OBSERVED row would file a reading as an OBSERVATION of a service
 * that happened at some other mileage, which is the exact thing the car plan's slice 0 exists to
 * stop; and asking instead for "what was it at when you had it done" routes a remembered figure
 * into `set_odometer`, which would rewind the live odometer, or into `log_past_service`, which
 * REFUSES a mileage-only backfill whenever a logged record exists for the item
 * ([VehicleController.logPastServiceDirect]'s `hasRecordAtOrAfter` with `atOrAfterMs = 0`, pinned
 * by `VehicleControllerServiceWritesTest`'s backfill-conflict case) - and the needs-a-reading
 * state, by definition, always has one.
 *
 * **Why an ASSERTED row is the honest home for it.** The row records a driver statement, never an
 * observed event, and that is what this is: asked for a reading against a named service, he gave
 * one. The item's known service DATE rides along unchanged ([FleetEngineStore.setAnchor] writes both axes at once),
 * so nothing that was known is dropped. The figure is the driver's own reading rather than the
 * estimator's guess, and [pinnedReadingNote] says in words which items took it, so a reading given
 * long after the service shows up as an anchor that times the item slightly late rather than as a
 * silent one.
 *
 * **Correcting one.** Logging the service again with the right mileage does it: a fresh OBSERVED
 * row is the newest statement and takes the anchor back, on the phone and on the engine alike
 * (`FleetAnchorParityTest`). EDITING the row in the history screen does NOT, on either side -
 * `ServiceRecordDao.editMileageAndCost` sets `mileage` and `costCents` and leaves `updatedAt`
 * alone, so the edited row still loses to the newer assertion. The two sides agree about that,
 * which is what matters here; that the hands path cannot undo a voice pin is written down as debt
 * rather than fixed inside this change.
 *
 * **Known gap.** A reading pinned once leaves the item no longer waiting, so a SECOND stated
 * reading does not replace it - the remedy above is the way out, and a re-pin that could only ever
 * replace an anchor this route wrote would need a marker the row does not carry.
 *
 * Its own file because it is one job that belongs to neither side of the two it joins:
 * [VehicleController] sits on detekt's class-size ceiling (CLAUDE.md's 1000-line rule) and
 * `MaintenanceAxes.kt`'s predicate is deliberately pure, with no `Context` and no Room.
 */

/**
 * The spoken answer's own clause: " I don't have a mileage reading against the oil change, so I
 * can't time that one by miles - what's on the odometer?", or nothing when no item is waiting.
 *
 * A value rather than three branches inside `VehicleServiceToolbox.getNextService`, so the
 * sentence the main voice surface says is pinned by a test - it was the one asking the
 * unanswerable question and nothing covered its wording. The question it asks is the one
 * [pinStatedReadingToWaitingItems] answers; they are deliberately the same route, and changing one
 * without the other is what round 2 found.
 */
internal fun needsMileageAskNote(names: List<String>): String {
    if (names.isEmpty()) return ""
    val them = if (names.size == 1) "that one" else "those"
    return " I don't have a mileage reading against ${names.joinToString(", ")}, so I can't time " +
        "$them by miles - what's on the odometer?"
}

/**
 * Pins [miles] as the mileage anchor of every item on [mac] that is waiting for a reading, keeping
 * each item's own last-done date. Returns the names that took it, newest-written last, for
 * [pinnedReadingNote] to say; empty when nothing was waiting, which is the ordinary case and costs
 * one read.
 *
 * A zero row count from [FleetEngineStore.setAnchor] (the schedule item vanished between the read
 * and the write) drops that name rather than being reported as pinned - ticket 05's no-op law.
 */
internal suspend fun pinStatedReadingToWaitingItems(
    context: Context,
    mac: String,
    miles: Int,
    now: Long,
): List<String> {
    val waiting = FleetEngineStore.getForVehicle(context, mac).filter { needsMileageReading(it) }
    return waiting
        .filter { FleetEngineStore.setAnchor(context, mac, it.serviceName, miles, it.lastDoneDate, now) == 1 }
        .map { it.serviceName }
}

/**
 * " I didn't have a mileage against the oil change, so I'm timing it from 235000 now." - or null
 * when nothing was waiting.
 *
 * It names the items and the figure they are now timed from, because the whole point of the write
 * is that the question the due surfaces asked has been answered; silence here would leave the
 * driver unable to tell a reading that landed from one that did nothing, which is what round 2
 * found. "Timing it FROM" rather than "that's what it was at": for a service logged weeks ago the
 * reading is later than the work, so the item comes due by that much late, and the sentence says
 * what the schedule will do rather than asserting a mileage for the service itself.
 */
internal fun pinnedReadingNote(names: List<String>, miles: Int): String? {
    if (names.isEmpty()) return null
    val them = if (names.size == 1) "it" else "them"
    return " I didn't have a mileage against ${names.joinToString(", ")}, so I'm timing $them " +
        "from $miles now."
}
