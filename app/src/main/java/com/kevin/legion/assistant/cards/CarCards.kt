package com.kevin.legion.assistant.cards

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.vehicle.VehicleController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray

/**
 * Car cards from get_next_service, check_recalls, get_codes and get_code_history, each from its own
 * tool's data. Each names the one part it read in [ConversationCard.Car.parts]; a part it did not
 * read is absent ("not checked"), so the holder merges them into one car
 * ([ConversationCards.mergeCar]) without any of them claiming what it did not read.
 */
object CarCards {

    /** The car with its next service, as get_next_service computed it. */
    fun service(
        label: String,
        vehicle: Vehicle,
        next: VehicleController.NextService?,
        zone: ZoneId,
        nowMs: Long = System.currentTimeMillis(),
    ): ConversationCard.Car {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val due = dueOf(next, mileageOf(vehicle), today)
        val note = scheduleNotes(next, due, today).joinToString(" ")
        return car(label, vehicle, zone, mapOf(CarPart.SERVICE to note), nowMs).copy(nextService = due)
    }

    /** The car with the recall list NHTSA returned. An empty list is a real "none on file". */
    fun recalls(label: String, vehicle: Vehicle, summaries: List<String>, zone: ZoneId): ConversationCard.Car {
        val note = if (summaries.isEmpty()) "NHTSA lists no recalls for this car." else RECALLS_SCOPE
        return car(label, vehicle, zone, mapOf(CarPart.RECALLS to note)).copy(recalls = summaries.map { Recall(it) })
    }

    /** The car with the codes read off the port now; a null meaning is a code with no known meaning. */
    fun codes(label: String, vehicle: Vehicle, codes: List<Pair<String, String?>>, zone: ZoneId): ConversationCard.Car {
        val note = if (codes.isEmpty()) "No trouble codes stored." else "Codes as read off the OBD port now."
        return car(label, vehicle, zone, mapOf(CarPart.CODES to note))
            .copy(codes = codes.map { (code, meaning) -> TroubleCode(code, meaning) })
    }

    /**
     * The car with get_code_history's logged events (newest first, as the tool returns them):
     * the codes of every event not cleared since, each code once. Cleared events are counted in
     * the note, never drawn as live codes.
     */
    fun codeHistory(label: String, vehicle: Vehicle, events: JSONArray, zone: ZoneId): ConversationCard.Car {
        val rows = (0 until events.length()).mapNotNull { events.optJSONObject(it) }
        val live = rows.filter { !it.optBoolean("cleared", false) }
        val codes = live.flatMap { e ->
            val list = e.optJSONArray("codes") ?: JSONArray()
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }.distinctBy { it.optString("code") }
            .map { TroubleCode(it.optString("code"), it.optString("meaning").ifBlank { null }) }
        val cleared = rows.size - live.size
        val note = when {
            rows.isEmpty() -> "No trouble-code events are recorded for this car."
            else -> listOfNotNull(
                "From the code log, newest ${rows.first().optString("date")}.",
                "$cleared logged event${if (cleared == 1) " was" else "s were"} cleared since.".takeIf { cleared > 0 },
            ).joinToString(" ")
        }
        return car(label, vehicle, zone, mapOf(CarPart.CODES to note)).copy(codes = codes)
    }

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

    private fun car(
        label: String,
        vehicle: Vehicle,
        zone: ZoneId,
        parts: Map<CarPart, String>,
        nowMs: Long = System.currentTimeMillis(),
    ) = ConversationCard.Car(
        title = label,
        name = label,
        mileage = mileageOf(vehicle),
        mileageAsOf = asOf(vehicle, zone),
        nextService = null,
        recalls = emptyList(),
        codes = emptyList(),
        parts = parts,
        extraNote = mileageNote(vehicle, nowMs),
    )

    /** NHTSA's list is by model, not this car's VIN (the engine's card says the same). */
    private const val RECALLS_SCOPE = "Recalls are NHTSA's list for the model, not a check of this car's VIN."

    private fun mileageOf(vehicle: Vehicle): Int? = VehicleController.currentMileage(vehicle).takeIf { it > 0 }

    /** The date of the driver's own last odometer reading, null if he never gave one. */
    private fun asOf(vehicle: Vehicle, zone: ZoneId): String? =
        vehicle.odometerBaselineAt.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }

    private fun mileageNote(vehicle: Vehicle, nowMs: Long = System.currentTimeMillis()): String? =
        VehicleController.mileageCaveat(vehicle, nowMs)?.let { "Mileage is $it." }
}
