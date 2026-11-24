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
 * Two sets, not one with a blank: with no mileage on the row the schedule can only time this item
 * by date, so "I'll let you know when it's due again" would promise something about the mileage
 * axis the row cannot keep. Neither set ASKS for the number - a reading he offers next is a reading
 * (`set_odometer`), and a reply that invites one here invites a second log of the same work.
 */
internal fun loggedServiceMessage(serviceName: String, statedMileage: Int?): String =
    if (statedMileage != null) {
        listOf(
            "Nice, logged the $serviceName at $statedMileage miles. I'll let you know when it's due again.",
            "Got it - $serviceName done at $statedMileage. One less thing to worry about.",
            "Logged: $serviceName at $statedMileage miles.",
        ).random()
    } else {
        listOf(
            "Nice, logged the $serviceName. No mileage on it, so I'm timing that one by date.",
            "Got it - $serviceName done today. No reading given, so it's timed by date.",
            "Logged: $serviceName, today, no mileage.",
        ).random()
    }

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
