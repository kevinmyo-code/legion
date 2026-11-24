package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.assistant.cards.ToolCards
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.service.LiveToolbox.agentResult
import com.kevin.legion.service.LiveToolbox.noItem
import com.kevin.legion.service.LiveToolbox.result
import com.kevin.legion.service.LiveToolbox.theCar
import com.kevin.legion.service.LiveToolbox.withResolvedVehicle
import com.kevin.legion.vehicle.CarToolbelt
import com.kevin.legion.vehicle.MaintenanceAgent
import com.kevin.legion.vehicle.TelemetryRecorder
import com.kevin.legion.vehicle.VehicleController
import java.time.YearMonth
import org.json.JSONObject

/**
 * The car service tools (`get_next_service`, `ask_maintenance`, `set_odometer`, `log_service`,
 * `log_past_service`, `set_maintenance_interval`), split out of [LiveToolbox] (shrink pass,
 * 2026-11-22) along the [EngineToolbox.dispatch] pattern: [LiveToolbox.dispatch] runs its guards
 * first (the unnamed-car write refusal included), then asks this object, which answers the names it
 * owns and returns null for every other. Writes go through [VehicleController], whose outcome is
 * the tool's success.
 */
object VehicleServiceToolbox {

    /** Null when [name] is not one of these tools, so [LiveToolbox.dispatch] can fall through. */
    suspend fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? = when (name) {
        "get_next_service" -> withResolvedVehicle(context, args) { getNextService(context, it) }
        "ask_maintenance" -> withResolvedVehicle(context, args) { askMaintenance(context, args, it) }
        // set_odometer/log_service/log_past_service/set_maintenance_interval: success is now
        // DERIVED from the underlying write (ticket 05, "the no-op guard is law now"), never
        // hardcoded - VehicleController's *Direct functions return a WriteOutcome precisely so
        // this dispatch can't assert success above a write that failed the way the rest of the
        // 194 result( calls in this file still do (deliberately out of scope, see ticket 05's
        // answer, "scoped, not swept").
        "set_odometer" -> withResolvedVehicle(context, args) {
            val outcome = VehicleController.setOdometer(context, args.optInt("miles"), it.obdMac)
            result(success = outcome.success, message = outcome.message)
        }
        "log_service" -> logService(context, args)
        "log_past_service" -> withResolvedVehicle(context, args) { logPastService(context, args, it.obdMac) }
        "set_maintenance_interval" -> if (args.optString("service").isBlank()) {
            noItem("Which service?")
        } else withResolvedVehicle(context, args) {
            val outcome = VehicleController.setMaintenanceInterval(
                context,
                args.optString("service"),
                args.optInt("interval_miles", -1).takeIf { it > 0 },
                args.optInt("interval_months", -1).takeIf { it > 0 },
                it.obdMac,
            )
            result(success = outcome.success, message = outcome.message)
        }
        else -> null
    }

    // Phone audit 2026-11-19: a log_service with no service wrote a blank record and item.
    private suspend fun logService(context: Context, args: JSONObject): JSONObject =
        if (args.optString("service").isBlank()) {
            noItem("Which service was it?")
        } else withResolvedVehicle(context, args) {
            // Dollars -> cents at the voice edge (CLAUDE.md §4 rule 3: VehicleController.logServiceDirect
            // only ever sees Long cents) - same Math.round(amount * 100) shape
            // LedgerPendingLog.pendingAmountCents already uses for a spoken dollar figure, with the
            // same non-finite/non-positive guard so a misheard "cost" argument can't write a bogus
            // negative or NaN-derived value.
            val costCents = if (args.has("cost") && !args.isNull("cost")) {
                val dollars = args.optDouble("cost")
                if (!dollars.isNaN() && dollars.isFinite() && dollars > 0.0) Math.round(dollars * 100.0) else null
            } else {
                null
            }
            // Same present-and-positive guard as `cost` above, for the same reason: `optInt`
            // returns 0 for a missing key, and a misheard or zero reading is "he did not say one",
            // not a reading of zero. Below-range values are not silently dropped here - they reach
            // VehicleController.setOdometer's own range check, which questions them in words.
            val mileage = if (args.has("mileage") && !args.isNull("mileage")) {
                args.optInt("mileage", 0).takeIf { miles -> miles > 0 }
            } else {
                null
            }
            val outcome = VehicleController.logServiceDirect(
                context, args.optString("service"), it.obdMac, costCents, mileage,
            )
            result(success = outcome.success, message = outcome.message)
        }

    /**
     * The spoken guess caveat, appended to a sentence naming [candidate] when
     * [VehicleController.ServiceCandidate.isGuess] is set (mission-control ticket 16,
     * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`
     * - "the caveat carry ALOUD because a tag cannot be heard"). [sentenceNoPeriod] is the sentence's
     * own words WITHOUT its trailing period; this function supplies the period either way, so every
     * call site below reads naturally whether or not the caveat fires, rather than every branch
     * having to remember to punctuate both halves itself.
     *
     * Deliberately ONE generic phrase rather than distinguishing SEEDED from LOOKUP the way
     * [MaintenanceAgent.describeItem] does - [VehicleController.ServiceCandidate.isGuess] is a plain
     * boolean (same shape as [com.kevin.legion.ui.fleet.DueRowView.isGuess]), and this is read aloud
     * mid-sentence to someone driving, not a model-facing prompt with room for a full provenance
     * clause.
     */
    private fun withGuessCaveat(sentenceNoPeriod: String, candidate: VehicleController.ServiceCandidate): String =
        sentenceNoPeriod + if (candidate.isGuess) " - though that interval is LEGION's guess, not one you've confirmed." else "."

    /**
     * Instant, zero-token read of what's coming up next - pure DB arithmetic
     * over [VehicleController.nextService], no Gemini call, so it works with no
     * key and no network. Renders plain spoken sentences (this is read aloud),
     * covering the shapes [VehicleController.NextService] can take: the same
     * item leading both axes, two different items each leading one axis, a
     * single axis only, everything already due ([VehicleController.NextService
     * .allDue]), or nothing anchored at all - plus the unknown-count offer,
     * which is how the driver discovers the backfill walkthrough exists
     * without it ever being pushed on them (CLAUDE.md sec 9.1: no unprompted
     * engagement).
     *
     * A [VehicleController.NextService.byTime] candidate at exactly 0 days
     * remaining is phrased as due today rather than "about today out" - see
     * [VehicleController.formatRemaining]'s doc for why that value can't just
     * be dropped into the usual "about X out" template.
     *
     * **Every branch that names a candidate carries [withGuessCaveat]** (ticket 16, this being the
     * spoken surface ticket 06 explicitly required the disclosure carry aloud on). The `allDue` and
     * "nothing due" branches name no candidate at all, so they carry nothing to caveat.
     */
    private suspend fun getNextService(context: Context, vehicle: Vehicle): JSONObject {
        val label = VehicleController.label(vehicle)
        val next = VehicleController.nextService(context, vehicle).also { ToolCards.nextService(vehicle, it) }
            ?: return result(true, noScheduleMessage(label, com.kevin.legion.vehicle.FleetEngineStore.getForVehicle(context, vehicle.obdMac).isEmpty()))

        val miles = VehicleController.ScheduleUnit.MILES
        val days = VehicleController.ScheduleUnit.DAYS

        // The same item can legitimately lead both axes ("3,000 miles or 6
        // months, whichever comes first") - phrase that as one thing rather
        // than repeating the name twice.
        val sameItem = next.byMiles != null && next.byTime != null &&
            next.byMiles.serviceName == next.byTime.serviceName

        val body = when {
            next.allDue -> "Everything I've got a record for is already due."
            next.odometerUnset -> buildString {
                append("I don't have an odometer reading yet, so I can't time anything by mileage. ")
                append(
                    next.byTime?.let {
                        if (it.remaining <= 0L) withGuessCaveat("${it.serviceName} is due today", it)
                        else withGuessCaveat("By time, ${it.serviceName} is next, about ${VehicleController.formatRemaining(it.remaining, days)} out", it)
                    } ?: "Nothing's coming up by time either yet."
                )
            }
            sameItem && next.byTime!!.remaining <= 0L -> withGuessCaveat("${next.byMiles!!.serviceName} is due today", next.byMiles)
            sameItem -> withGuessCaveat(
                "${next.byMiles!!.serviceName}, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} or " +
                    "${VehicleController.formatRemaining(next.byTime!!.remaining, days)}, whichever comes first",
                next.byMiles,
            )
            next.byMiles != null && next.byTime != null -> buildString {
                append(withGuessCaveat("${next.byMiles.serviceName} is soonest by mileage, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out", next.byMiles))
                append(" ")
                append(
                    if (next.byTime.remaining <= 0L) withGuessCaveat("${next.byTime.serviceName} is due today", next.byTime)
                    else withGuessCaveat("${next.byTime.serviceName} is soonest by time, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out", next.byTime)
                )
            }
            next.byMiles != null -> withGuessCaveat("${next.byMiles.serviceName} is next, about ${VehicleController.formatRemaining(next.byMiles.remaining, miles)} out", next.byMiles)
            next.byTime != null -> {
                if (next.byTime.remaining <= 0L) withGuessCaveat("${next.byTime.serviceName} is due today", next.byTime)
                else withGuessCaveat("${next.byTime.serviceName} is next, about ${VehicleController.formatRemaining(next.byTime.remaining, days)} out", next.byTime)
            }
            else -> "Nothing's due or coming up soon that I can time yet."
        }

        val unknownNote = if (next.unknownCount > 0) {
            if (next.unknownCount == 1) {
                " 1 more I don't have a last-done for - say the word and I'll fill it in."
            } else {
                " ${next.unknownCount} more I don't have a last-done for - say the word and I'll fill them in."
            }
        } else ""

        // Ticket 10: "any mileage not taken from the driver's own last reading says so, every time
        // - spoken as well as rendered." The "due in N miles" figure above is itself downstream of
        // TelemetryRecorder's speed-integration estimate whenever next.byMiles is non-null (its
        // math is currentMileage minus a stored anchor), so it carries the same caveat the estimate
        // itself does - never spoken when byMiles is null (nothing miles-derived was said) or when
        // the mileage IS the driver's own confirmed reading (mileageCaveat returns null then).
        val mileageNote = if (next.byMiles != null) {
            VehicleController.mileageCaveat(vehicle)?.let { " Your mileage is $it." } ?: ""
        } else ""

        return result(true, "For ${theCar(label)}: " + body + unknownNote + mileageNote)
    }

    /**
     * [getNextService]'s sentence when nothing can be timed (2026-11-03). A car with NO schedule
     * rows is told so by name; "register the car" was wrong for a car already on file. Pure and
     * internal so a test pins it.
     */
    internal fun noScheduleMessage(label: String, scheduleEmpty: Boolean): String =
        if (scheduleEmpty) {
            "${theCar(label).replaceFirstChar { it.uppercase() }} has no maintenance schedule on file yet, " +
                "so nothing can be timed. Set one up on the Fleet tab, or log a service and it starts from there."
        } else {
            "${theCar(label).replaceFirstChar { it.uppercase() }} has schedule items, but none has a last-done " +
                "date or mileage yet, so nothing can be timed. Say when one was last done and it starts tracking."
        }

    /**
     * Backfills a maintenance item from what the driver remembers - never a
     * fresh completion (that's [logServiceDirect]/`log_service`). The model is
     * told not to call this at all on "I don't know" (see the tool
     * description); this side just parses the date and reports whether an
     * actual anchor was given, degrading to a clear failure on a date it can't
     * read rather than silently writing nothing.
     *
     * A date that fails to parse drops ONLY the date, not the whole call
     * (B6): a driver giving both a mileage anchor AND a garbled date must not
     * lose the perfectly good mileage anchor just because the date half was
     * bad. This only fails outright when NOTHING usable survives.
     *
     * A date parsed as being in the FUTURE is rejected outright (B4) rather
     * than written: [VehicleController.isDue]'s `now - lastDoneDate` math
     * assumes the anchor is in the past, and a future anchor (a mis-heard
     * year, or "2027-01" meant as this year) goes negative there, silently
     * reading as freshly-serviced for years. This tool is explicitly for a
     * PAST service, so a future date can never be a real answer here.
     */
    private suspend fun logPastService(context: Context, args: JSONObject, vehicleId: String? = null): JSONObject {
        val service = args.optString("service")
        if (service.isBlank()) return noItem("Which service was it?")

        val mileage = args.optInt("mileage", -1).takeIf { it > 0 }
        val milesAgo = args.optInt("miles_ago", -1).takeIf { it >= 0 }
        val neverDone = args.optBoolean("never_done", false)
        val dateRaw = args.optString("date", "")

        var dateError: String? = null
        val date = if (dateRaw.isNotBlank()) {
            val parsed = parseIsoDate(dateRaw)
            when {
                parsed == null -> {
                    dateError = "I couldn't read that date - try year-month-day, like 2024-03-14, " +
                        "or just 2024-03 if you don't remember the day."
                    null
                }
                parsed > System.currentTimeMillis() -> {
                    dateError = "That date's in the future, so I can't log it as done already - " +
                        "did you mean an earlier year?"
                    null
                }
                else -> parsed
            }
        } else null

        val hasAnchor = neverDone || mileage != null || milesAgo != null || date != null
        if (!hasAnchor) {
            // Nothing usable survived (either nothing was given, or the only
            // thing given was a bad/future date and there's no other anchor
            // to fall back on) - fail outright rather than writing a blank row.
            return result(success = false, message = dateError ?: "I need something to go on — a mileage, how long ago, a date, or that it's never been done.")
        }

        val outcome = VehicleController.logPastServiceDirect(context, service, mileage, milesAgo, date, neverDone, vehicleId)
        // A bad/future date is a separate soft warning appended to whatever the
        // write itself reported - it does not override outcome.success, which
        // is ticket 05's no-op guard and reflects whether the write actually landed.
        val finalMessage = if (dateError != null) "${outcome.message} $dateError" else outcome.message
        return result(success = outcome.success, message = finalMessage)
    }

    /**
     * Parses a bare ISO date the driver's backfill might give as either a full
     * day (`2024-03-14`) or just a year-month (`2024-03`, treated as the 1st).
     * Returns null rather than throwing on anything else - a mis-shaped date
     * from the model's function-call args must not crash tool dispatch.
     */
    private fun parseIsoDate(raw: String): Long? {
        val trimmed = raw.trim()
        return try {
            java.time.LocalDate.parse(trimmed)
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.YearMonth.parse(trimmed).atDay(1)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * Delegates to the investigating maintenance specialist ([MaintenanceAgent]).
     * Pre-seeds the schedule + current mileage; the worker pulls service history
     * and anything else it needs via the toolbelt. Another mid-conversation
     * round-trip, so Zero typically says "let me check..." first. [vehicle] is
     * the already-resolved car (ticket 01 fleet-wide voice).
     *
     * **Known gap, traced, not fixed by this ticket:** the pre-seeded label,
     * mileage, and schedule below correctly describe [vehicle] even when it's
     * NOT the active car. But [MaintenanceAgent.answer] also lets the model
     * pull FURTHER data mid-investigation via [CarToolbelt.forMaintenance]'s
     * belt (get_service_history, get_trend, get_oil_analyses, ...) - and that
     * belt was not given [vehicle]'s id (see [CarToolbelt.forMaintenance]'s
     * doc), so any such follow-up call still reads the ACTIVE car. A compound
     * "ask_maintenance" question about the second car that needs the
     * specialist to pull more than the pre-seeded schedule can therefore mix
     * the requested car's schedule with the active car's history/trend data.
     * Threading the override into `MaintenanceAgent`/its belt was outside
     * ticket 01 §2's explicit controller list - flagged in the ticket 01
     * build report as a follow-up, not silently left unmentioned.
     */
    private suspend fun askMaintenance(context: Context, args: JSONObject, vehicle: Vehicle): JSONObject {
        val question = args.optString("question")
        // displayLabel deliberately - specialist grounding text, see diagnose_codes' comment above.
        val label = VehicleController.displayLabel(vehicle)

        val items = com.kevin.legion.vehicle.FleetEngineStore.getForVehicle(context, vehicle.obdMac)
        // Ticket 10: the pre-seeded mileage carries its own bare/estimated-and-caveated label now
        // (VehicleController.mileageLabel), not a raw Int the agent's own prompt had to caption
        // "(estimated)" unconditionally - a confirmed reading with nothing accrued since renders
        // bare here too, matching every other surface.
        val mileageLabel = VehicleController.mileageLabel(vehicle)

        return agentResult("Could not reach the maintenance specialist. Try again shortly.") {
            MaintenanceAgent.answer(context, label, mileageLabel, items, question, vehicle.obdMac)
        }
    }
}
