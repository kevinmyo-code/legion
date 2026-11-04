package com.kevin.legion.assistant.cards

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.vehicle.VehicleController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Car cards from get_next_service, check_recalls and get_codes, each from its own tool's data. A
 * field the tool did not look up stays null ("not checked"), so the holder can merge the three
 * into one car ([ConversationCards.mergeCar]) without any of them claiming what it did not read.
 */
object CarCards {

    /** The car with its next service, as get_next_service computed it. Recalls and codes: not checked. */
    fun service(
        label: String,
        vehicle: Vehicle,
        next: VehicleController.NextService?,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): ConversationCard.Car {
        val mileage = mileageOf(vehicle)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val due = dueOf(next, mileage, today)
        val note = (scheduleNotes(next, due, today) + listOfNotNull(mileageNote(vehicle, nowMs)) +
            "Recalls and trouble codes were not checked in this answer.").joinToString(" ")
        return car(label, vehicle, zone, due = due, recalls = null, codes = null, note = note)
    }

    /** The car with the recall list NHTSA returned. An empty list is a real "none on file". */
    fun recalls(label: String, vehicle: Vehicle, summaries: List<String>, zone: ZoneId): ConversationCard.Car =
        car(
            label, vehicle, zone, due = null, recalls = summaries.map { Recall(it) }, codes = null,
            note = listOfNotNull(
                "NHTSA lists no recalls for this car.".takeIf { summaries.isEmpty() },
                mileageNote(vehicle),
            ).joinToString(" ").ifBlank { null },
        )

    /** The car with the codes read off the port now; a null meaning is a code with no known meaning. */
    fun codes(label: String, vehicle: Vehicle, codes: List<Pair<String, String?>>, zone: ZoneId): ConversationCard.Car =
        car(
            label, vehicle, zone, due = null, recalls = null,
            codes = codes.map { (code, meaning) -> TroubleCode(code, meaning) },
            note = "No trouble codes stored.".takeIf { codes.isEmpty() },
        )

    /** By mileage when the odometer is known, with the time-axis date when it is the same item. */
    private fun dueOf(next: VehicleController.NextService?, mileage: Int?, today: LocalDate): NextServiceDue? {
        if (next == null || next.allDue) return null
        val miles = next.byMiles?.takeIf { !next.odometerUnset && mileage != null }
        val time = next.byTime
        return when {
            miles != null && mileage != null -> NextServiceDue(
                label = miles.serviceName,
                dueMiles = mileage + miles.remaining.toInt(),
                dueDate = time?.takeIf { it.serviceName == miles.serviceName }
                    ?.let { today.plusDays(it.remaining).toString() },
            )
            time != null ->
                NextServiceDue(label = time.serviceName, dueDate = today.plusDays(time.remaining).toString())
            else -> null
        }
    }

    private fun scheduleNotes(
        next: VehicleController.NextService?,
        due: NextServiceDue?,
        today: LocalDate,
    ): List<String> = buildList {
        when {
            next == null -> add("No maintenance schedule can be timed for this car yet.")
            next.allDue -> add("Everything on the schedule is already due.")
        }
        if (due != null) add("The next service is an estimate from the schedule's intervals.")
        val time = next?.byTime
        if (time != null && due?.dueMiles != null && time.serviceName != due.label) {
            add("By time, ${time.serviceName} is soonest, about ${today.plusDays(time.remaining)}.")
        }
        if (next != null && next.unknownCount > 0) add("${next.unknownCount} more have no last-done on file.")
    }

    @Suppress("LongParameterList") // the car card's own fields, one per argument
    private fun car(
        label: String,
        vehicle: Vehicle,
        zone: ZoneId,
        due: NextServiceDue?,
        recalls: List<Recall>?,
        codes: List<TroubleCode>?,
        note: String?,
    ) = ConversationCard.Car(
        title = label,
        name = label,
        mileage = mileageOf(vehicle),
        mileageAsOf = asOf(vehicle, zone),
        nextService = due,
        recalls = recalls,
        codes = codes,
        note = note,
    )

    private fun mileageOf(vehicle: Vehicle): Int? = VehicleController.currentMileage(vehicle).takeIf { it > 0 }

    /** The date of the driver's own last odometer reading, null if he never gave one. */
    private fun asOf(vehicle: Vehicle, zone: ZoneId): String? =
        vehicle.odometerBaselineAt.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }

    private fun mileageNote(vehicle: Vehicle, nowMs: Long = System.currentTimeMillis()): String? =
        VehicleController.mileageCaveat(vehicle, nowMs)?.let { "Mileage is $it." }
}
