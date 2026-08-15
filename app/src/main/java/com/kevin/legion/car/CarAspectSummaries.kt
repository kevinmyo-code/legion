package com.kevin.legion.car

import android.content.Context
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.LedgerEntity
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.Recurrence
import com.kevin.legion.notes.endFromItem
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.vehicle.VehicleController
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Wave 3 of the Android Auto probe (`.scratch/android-auto/issues/08-what-the-browse-tree-holds.md`,
 * built PROVISIONALLY per Kevin's direct ask - see that ticket's still-open status). One function
 * per root row, each returning a (title, subtitle) pair that IS the display - Kevin asked for "some
 * kind of UI display to see my aspects" and the row itself carries a LIVE deterministic value rather
 * than a static label, so there is no separate screen to build for this wave.
 *
 * **Every number here is read from an existing controller, never invented and never an LLM call**
 * (CLAUDE.md §4 rule 5, §7's Gemini-call checklist item, and the brief's explicit "no SubAgent,
 * ever" for a tap). Where a controller's own figure is provisional/unreconciled, that word is
 * folded into the subtitle text itself, never carried only as a colour or a tag - there is no colour
 * channel available on an Android Auto media row anyway, which makes the wording the ONLY channel.
 */
object CarAspectSummaries {

    /**
     * Fleet row: the active vehicle's display name plus the soonest not-yet-due maintenance item on
     * either axis (miles or time) - the same [VehicleController.nextService] read `ui/TodayScreen.kt`
     * and `ui/FleetScreen.kt` already build their own DUE rows from, not a new query.
     */
    suspend fun fleet(context: Context): Pair<String, String> {
        val vehicle = VehicleController.currentVehicle(context)
        val label = vehicle.name.ifBlank { VehicleController.displayLabel(vehicle) }.ifBlank { "Fleet" }
        val mileage = VehicleController.currentMileage(vehicle)
        val next = VehicleController.nextService(context, vehicle)
        val subtitle = when {
            next == null -> "$mileage mi · no maintenance schedule yet"
            next.odometerUnset -> "odometer not set · say your mileage to enable due-dates"
            next.byMiles != null ->
                "$mileage mi · ${next.byMiles.serviceName} in ${next.byMiles.remaining} mi"
            next.byTime != null ->
                "$mileage mi · ${next.byTime.serviceName} in ${next.byTime.remaining} days"
            next.allDue -> "$mileage mi · everything scheduled is already due"
            else -> "$mileage mi · nothing due yet"
        }
        return "Fleet · $label" to subtitle
    }

    /**
     * Today row: today's local-window agenda count, the same [NotesController] window
     * `ui/TodayScreen.kt`'s AGENDA pane reads (one-off items plus today's recurring occurrences).
     * Deliberately does NOT merge in Google Calendar events the way the phone screen does - that
     * needs `READ_CALENDAR`, permission surfaces belong to an Activity, and this service has none;
     * the subtitle says "on your list" rather than "scheduled" so it never overclaims completeness
     * it cannot check.
     */
    suspend fun today(context: Context): Pair<String, String> {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.now(zone)
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        val oneOff = NotesController.timedItemsInWindow(context, dayStart, dayEnd).count { !it.done }
        val recurring = NotesController.allRecurringItems(context).sumOf { item ->
            val startsAt = item.startsAt
            val rule = startsAt?.let { ruleFromItem(item) }
            if (startsAt == null || rule == null) {
                0
            } else {
                val skips = NotesController.skippedDates(context, item)
                Recurrence.occurrencesInWindow(startsAt, rule, endFromItem(item), skips, dayStart, dayEnd).size
            }
        }
        val count = oneOff + recurring
        val subtitle = if (count == 0) "nothing on your list today" else "$count on your list today"
        return "Today" to subtitle
    }

    /**
     * Money row: this month's total spend across [LedgerEntity.US] - the exact figure
     * `ui/TodayScreen.kt`'s SYSTEMS SWEEP ledger row and `ui/LedgerScreen.kt`'s budget section both
     * read from [LedgerController.budgetVsActual]. `Long` cents throughout (CLAUDE.md §4 rule 3);
     * formatted here, never converted to a `Double`.
     *
     * Any row still carrying [com.kevin.legion.ledger.IngestMethod.UNRECONCILED] rows, a pending
     * category guess, or a month whose account coverage is incomplete gets " (unverified)" appended
     * in WORDS (CLAUDE.md §4 rule 7 - never colour-only, and there is no colour channel here anyway).
     */
    suspend fun money(context: Context): Pair<String, String> {
        val month = YearMonth.now()
        val budget = LedgerController.budgetVsActual(context, LedgerEntity.US, month)
        val totalCents = budget.uncategorized.spentCents + budget.lines.sumOf { it.gap.actual }
        val unverified = budget.uncategorized.hasProvisionalRows ||
            budget.lines.any { it.hasProvisionalRows || it.hasPendingCategoryGuesses } ||
            !budget.isComplete
        val amount = formatCents(totalCents)
        val subtitle = if (unverified) {
            "$amount spent this month (unverified)"
        } else {
            "$amount spent this month"
        }
        return "Money" to subtitle
    }

    /** `Long` cents to a dollar string. No `Double` anywhere in this path (CLAUDE.md §4 rule 3). */
    private fun formatCents(cents: Long): String {
        val whole = cents / 100
        val fraction = (cents % 100).let { if (it < 0) -it else it }
        return "$%d.%02d".format(whole, fraction)
    }
}
