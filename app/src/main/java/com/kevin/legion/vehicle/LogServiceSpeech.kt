package com.kevin.legion.vehicle

/**
 * What logging a service SAYS. The words [VehicleController.logServiceDirect] speaks, kept beside
 * each other and out of the controller.
 *
 * Why its own file: the controller is the project's largest (CLAUDE.md's 600-line rule, and detekt
 * holds the 1000-line class ceiling), and the car plan's slice 0 added a second message set for the
 * mileage-less case. Copy is the part of that function that is not logic, so it is the part that
 * moves. Every writer and reader of these strings is `logServiceDirect` and its tests; nothing here
 * decides anything.
 *
 * One rule binds all of it, CLAUDE.md section 7's outcome rule: a sentence may not name a figure
 * the row does not hold. That is why [atMiles] has an empty half and why [loggedServiceMessage] has
 * two sets rather than one with a gap in it.
 */

/**
 * " at 42300 miles", or nothing at all when no reading was stated. The empty half is the point: a
 * spoken sentence may not name a mileage the record does not carry, and it may not say "at 0 miles"
 * either.
 */
internal fun atMiles(miles: Int?): String = miles?.let { " at $it miles" } ?: ""

/**
 * The question an ambiguous spoken service name is answered with (ticket 31): it names the
 * candidates and writes nothing, the same "which one?" posture `place_call` takes on two matching
 * contacts.
 */
internal fun ambiguousServiceQuestion(candidates: List<String>): String =
    "That could be ${candidates.joinToString(" or ")} - which one, or is this a new item?"

/**
 * The confirmation for a service that matched a scheduled item.
 *
 * One set per thing the schedule can honestly promise, which is four and not one with a blank. With
 * no mileage on the row the schedule cannot keep the mileage axis, so "I'll let you know when it's
 * due again" would promise something the row cannot hold. With no MONTHS interval it cannot time
 * the item by date either, so "I'm timing that one by date" names a schedule the item does not have
 * - the same rule in the other direction ([needsMileageReading] is the state such
 * an item lands in, and the due surfaces ask for the reading there). And an item with NEITHER
 * interval - the shape an earlier unmatched log creates - is timed by nothing at all and may not be
 * told it is on a mileage interval.
 *
 * No set ASKS for the number: a reading he offers next is a reading (`set_odometer`), and a reply
 * that invites one here invites a second log of the same work, so the asking lives on the surfaces
 * that report what is due rather than on this confirmation. Those surfaces ask for an odometer
 * reading, and `set_odometer` is where such a reading lands on the item that is waiting for it
 * ([pinStatedReadingToWaitingItems]) - the ask and the write are the same route, which is what
 * round 2 of the review on PR #5 found they were not.
 */
internal fun loggedServiceMessage(
    serviceName: String,
    statedMileage: Int?,
    intervalMonths: Int?,
    intervalMiles: Int?,
): String {
    val timedByDate = intervalMonths != null
    val timedByMiles = intervalMiles != null
    return when {
        statedMileage != null -> listOf(
            "Nice, logged the $serviceName at $statedMileage miles. I'll let you know when it's due again.",
            "Got it - $serviceName done at $statedMileage. One less thing to worry about.",
            "Logged: $serviceName at $statedMileage miles.",
        ).random()

        timedByDate -> listOf(
            "Nice, logged the $serviceName. No mileage on it, so I'm timing that one by date.",
            "Got it - $serviceName done today. No reading given, so it's timed by date.",
            "Logged: $serviceName, today, no mileage.",
        ).random()

        timedByMiles -> listOf(
            "Nice, logged the $serviceName. It's tracked by mileage only, and I've no reading for it, " +
                "so I can't say when it's next due.",
            "Got it - $serviceName done today, no reading given. That one's on a mileage interval, " +
                "so it isn't timed until I have a mileage.",
            "Logged: $serviceName, today, no mileage - and mileage is the only thing that item is tracked on.",
        ).random()

        else -> listOf(
            "Nice, logged the $serviceName. There's no interval on it, so nothing times it yet - " +
                "tell me one when you want it tracked.",
            "Got it - $serviceName done today. It has no interval on file, so I can't say when it's next due.",
            "Logged: $serviceName, today. No interval on that item yet, so nothing is timing it.",
        ).random()
    }
}

/**
 * What a refused stated reading answers: [odometerMessage] is `setOdometer`'s own refusal, and this
 * adds the half it cannot know - the whole call was refused, so the SERVICE is not on file either.
 * Without the second sentence the driver is left believing work he really had done was logged.
 *
 * Two refusals reach here and they need different second halves ([odometerReadingPlausible] tells
 * them apart). A reading outside the odometer's range is the driver's own figure to correct, and
 * the car is on file, so saying it again lands both; a car with no row at all is not about the
 * reading, and "say it again with the reading you meant" is not the remedy for it - registering
 * the car is, which `setOdometer`'s own message already states.
 */
internal fun serviceNotLoggedMessage(
    odometerMessage: String,
    serviceName: String,
    readingPlausible: Boolean,
): String = if (readingPlausible) {
    "$odometerMessage I haven't logged the $serviceName either, so tell me again once that's done."
} else {
    "$odometerMessage I haven't logged the $serviceName either - say it again with the reading you " +
        "meant, or just say it was done and I'll log it with no mileage."
}

/**
 * [note] in front of [message], or [message] alone. The note is `setOdometer`'s own low-reading
 * question (`VehicleController.odometerQuestionNote`): a stated reading goes through the one
 * odometer writer, so when that reading rewinds the odometer the driver hears about it here, in the
 * same sentence as the log. Without this the note was computed and thrown away, and a misheard
 * 42,300 against a 50,000 estimate moved the odometer back 7,700 miles in silence.
 */
internal fun noteThen(note: String?, message: String): String = note?.let { "$it $message" } ?: message

/**
 * The service was logged but its schedule item disappeared between the read and the reset (ticket
 * 05's no-op law: a write that changed nothing is reported, never assumed). It says what DID land.
 */
internal fun serviceItemVanishedMessage(serviceName: String, statedMileage: Int?): String =
    "Logged the $serviceName${atMiles(statedMileage)}, but the schedule item disappeared before I " +
        "could reset it - it may have just been deleted."

/**
 * Nothing on the schedule matched, so an item was created under the canonical form of what was said
 * and this SAYS so (ticket 08): a bad canonicalisation is then visible immediately, rather than
 * found days later on a database pull the way the silently-created Brake Fluid / Brake Pads rows
 * were.
 */
internal fun newServiceItemMessage(canonicalName: String, statedMileage: Int?): String =
    "Logged the $canonicalName${atMiles(statedMileage)}. Nothing on your schedule matched, so I've " +
        "added $canonicalName as an item - it has no interval yet, tell me one when you want it tracked."
