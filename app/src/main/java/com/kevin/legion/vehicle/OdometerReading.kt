package com.kevin.legion.vehicle

/**
 * What counts as a plausible odometer reading, as one rule with one home.
 *
 * Ticket 10's range check lived as two literals inside [VehicleController.setOdometer], which was
 * fine while that function was the only place that needed to know. It is not any more: a stated
 * reading on a service log goes through the same writer, and the refusal has to be worded
 * differently depending on WHICH refusal it was - a reading outside this range (the car is on file,
 * so saying it again lands both the reading and the service) or a car with no row at all (nothing
 * about the reading is wrong, and repeating it changes nothing). Round 2 finding 4 on PR #5: one
 * wrapper was wording both as the first.
 */

private const val ODOMETER_MIN_MILES = 100

private const val ODOMETER_MAX_MILES = 999_999

/** True when [miles] is a reading an odometer could actually show on this car. */
internal fun odometerReadingPlausible(miles: Int): Boolean =
    miles in ODOMETER_MIN_MILES..ODOMETER_MAX_MILES

/**
 * What filing a reading SAYS: [questionNote] is `setOdometer`'s own low-reading question when the
 * reading rewinds the odometer, and [pinned] names the maintenance items that took the reading as
 * their anchor ([pinnedReadingNote]).
 *
 * Copy rather than logic, so it lives here and not in [VehicleController], which sits on detekt's
 * class-size ceiling. Each clause is conditional on something real, and the reply never names a
 * write that did not happen.
 */
internal fun odometerFiledMessage(miles: Int, questionNote: String?, pinned: List<String>): String {
    val filed = if (questionNote != null) {
        "$questionNote Filed: $miles on the clock."
    } else {
        listOf(
            "Got it, $miles on the clock. I'll keep track from here.",
            "Noted, $miles miles. Let's see how long till the next thing breaks.",
            "$miles it is. Filed away.",
        ).random()
    }
    return filed + (pinnedReadingNote(pinned, miles) ?: "")
}
