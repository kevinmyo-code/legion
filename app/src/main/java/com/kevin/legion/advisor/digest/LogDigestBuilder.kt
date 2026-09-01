package com.kevin.legion.advisor.digest

import android.content.Context
import com.kevin.legion.advisor.AdvisorAspect
import com.kevin.legion.advisor.DigestBuilder
import com.kevin.legion.advisor.DigestText
import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.OpenerCalendarBriefing
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.util.compactDate

/**
 * LOG's deterministic digest (ticket 17, same shape/window/tier rules as ticket 16's
 * non-negotiables). Read-only over [com.kevin.legion.data.local.ItemListDao]/
 * [com.kevin.legion.data.local.ListItemDao]/[com.kevin.legion.data.local.PlaceReminderDao] and the
 * local `events` table's `kind = 'appointment'` rows - never writes, never blocks on network.
 * `TrustTier` is deliberately NOT stamped on any line here: every figure in this digest is a plain
 * count or a stored fact about LEGION's own record (a task exists, a reminder fired late, an event
 * is on the calendar), never a claim reconciled against - or standing in for - an outside document,
 * so there is no proven/reported distinction to carry (unlike FLEET's odometer/DTC figures, which
 * are the driver's own unreconciled word about the car). [DigestText.withTier] is intentionally
 * unused in this file.
 *
 * **Calendar is READ-ONLY, historically because "Google owns appointments, LEGION owns reminders"**
 * (`.scratch/google-account-integration/`) - **one-today ticket 01 cut the live Google read
 * entirely**, and this builder now reads the local `events` table directly
 * ([com.kevin.legion.data.local.EventDao.activeByKindInWindow]); it stays read-only regardless,
 * nothing here inserts, updates, or deletes anything.
 *
 * **Repeated-deferral flags are a REASONED proxy, not a stored fact** - see [deferralLines]'s own
 * doc comment. No column anywhere in [ListItem] counts how many times an item's `startsAt` has been
 * pushed or how many times its text has been rewritten; the only two real signals available are
 * [ListItem.createdAt] and [ListItem.updatedAt], and this builder is explicit in its own wording
 * that a flagged item is a CANDIDATE the advisor should ask about, not a confirmed defer count.
 */
object LogDigestBuilder : DigestBuilder {
    override val aspect = AdvisorAspect.LOG

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val AGING_THRESHOLD_MS = 7 * DAY_MS
    private const val STALE_THRESHOLD_MS = 30 * DAY_MS
    /** An item is a repeated-deferral CANDIDATE once it has sat open at least this long AND its
     * [ListItem.updatedAt] moved at least [DEFERRAL_TOUCH_GAP_MS] past its [ListItem.createdAt] -
     * i.e. it was touched (rewritten, re-timed) at least once since creation and is STILL open this
     * long after. See [deferralLines]. */
    private const val DEFERRAL_AGE_MS = 14 * DAY_MS
    private const val DEFERRAL_TOUCH_GAP_MS = 3 * DAY_MS
    private const val CALENDAR_HORIZON_MS = 7 * DAY_MS
    private const val MAX_NAMED_EXEMPLARS = 3

    override suspend fun build(context: Context): String {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()

        // Cutover 1: NotesController is engine-backed now - "the one list" is always tickable
        // (NotesController.theList's own doc comment), so tickableListIds collapses to its single
        // id rather than a query across every ItemList row (there has only ever been one live).
        val tickableListIds = setOf(com.kevin.legion.notes.NotesController.theList(context).id)
        val allActive = com.kevin.legion.notes.NotesController.allItems(context)
        val missed = com.kevin.legion.notes.NotesController.missedItems(context)
        val placeReminderCount = db.placeReminderDao().allActive().size
        val placeTriggerItemCount = com.kevin.legion.notes.NotesController.openWithAnyPlaceTrigger(context).size

        // One-today ticket 01, "cut Google entirely": the local `events` table is always readable,
        // so there is no more refused-permission outcome to distinguish from an empty window - see
        // calendarLine's own doc comment for why the `null` branch is kept anyway.
        val calendarEvents = db.eventDao()
            .activeByKindInWindow(EventKind.EVENT, now, now + CALENDAR_HORIZON_MS)
            .map { OpenerCalendarBriefing.BriefingEvent(title = it.title, startMs = it.startsAt ?: now, endMs = it.endsAt ?: (it.startsAt ?: now), allDay = it.allDay) }

        return buildDigestText(
            allActive = allActive,
            tickableListIds = tickableListIds,
            missed = missed,
            placeReminderCount = placeReminderCount + placeTriggerItemCount,
            calendarEvents = calendarEvents,
            now = now,
        )
    }

    /** Pure assembly, no [Context]/Room/[CalendarProvider] - unit-testable directly, same
     * "pure decision logic" split [com.kevin.legion.vehicle.VehicleController.isDue] uses. */
    internal fun buildDigestText(
        allActive: List<ListItem>,
        tickableListIds: Set<Long>,
        missed: List<ListItem>,
        placeReminderCount: Int,
        calendarEvents: List<OpenerCalendarBriefing.BriefingEvent>?,
        now: Long,
    ): String {
        val openTasks = openTaskItems(allActive, tickableListIds)
        val lines = mutableListOf<String>()
        lines += ageBandLines(openTasks, now)
        lines += overdueReminderLines(missed)
        if (placeReminderCount > 0) {
            lines += DigestText.line("PLACE-TRIGGERED reminders active", placeReminderCount.toString())
        }
        lines += calendarLine(calendarEvents, now)
        lines += deferralLines(openTasks, now)
        return lines.joinToString("\n")
    }

    /** "Tasks" for the age-band read: open, non-deleted (already true of [ListItem.allActive]),
     * belonging to a TICKABLE list (excludes pure note lines - [ItemList.tickable] false), with no
     * time trigger (a `startsAt` item is calendar material, [calendarLine]'s job) and not recurring
     * (a recurring item re-arms forward and is never "stale" the way a one-off open task is -
     * [ListItem]'s own doc comment: "a recurring item cannot be ticked"). A place-triggered task
     * (waiting on arrival, no time trigger) still counts - it is genuinely an open task. */
    private fun openTaskItems(allActive: List<ListItem>, tickableListIds: Set<Long>): List<ListItem> =
        allActive.filter {
            !it.done && it.listId in tickableListIds && it.startsAt == null && it.repeatKind == null
        }

    /** Open tasks bucketed by age since [ListItem.createdAt]: FRESH (<7d), AGING (7-30d), STALE
     * (30d+, the playbook's own "stale beyond ~a month" line). An empty task list reads
     * [DigestText.notLogged] - LOG has never been given a task, a fact distinct from "zero tasks are
     * currently open" (which would still show three real zero-count bands). STALE names up to
     * [MAX_NAMED_EXEMPLARS] of the oldest items, since that is the band the LOG playbook's overload-
     * triage posture actually asks about by name. */
    private fun ageBandLines(openTasks: List<ListItem>, now: Long): List<String> {
        if (openTasks.isEmpty()) return listOf(DigestText.line("TASKS", DigestText.notLogged()))
        val fresh = openTasks.count { now - it.createdAt < AGING_THRESHOLD_MS }
        val aging = openTasks.count { now - it.createdAt in AGING_THRESHOLD_MS until STALE_THRESHOLD_MS }
        val stale = openTasks.filter { now - it.createdAt >= STALE_THRESHOLD_MS }.sortedBy { it.createdAt }
        val staleNames = stale.take(MAX_NAMED_EXEMPLARS).joinToString(", ") { it.text }
        val staleSuffix = if (stale.isEmpty()) "" else ": $staleNames"
        return listOf(
            DigestText.line(
                "TASKS open",
                "${openTasks.size} total - fresh(<7d) $fresh, aging(7-30d) $aging, stale(30d+) ${stale.size}$staleSuffix",
            ),
        )
    }

    /** Stored fact, not a heuristic - [com.kevin.legion.data.local.ListItemDao.missedItems] is a
     * one-off timed item found still open past its own [ListItem.startsAt] (ticket 12: "a missed
     * reminder is a STORED fact, not something recomputed"). Names up to [MAX_NAMED_EXEMPLARS]. */
    private fun overdueReminderLines(missed: List<ListItem>): List<String> {
        if (missed.isEmpty()) return listOf(DigestText.line("OVERDUE REMINDERS", "none"))
        val names = missed.take(MAX_NAMED_EXEMPLARS).joinToString(", ") { it.text }
        return listOf(DigestText.line("OVERDUE REMINDERS", "${missed.size}: $names"))
    }

    /** The calendar horizon, READ-ONLY. `null` [calendarEvents] historically meant the retired
     * `CalendarProvider.hasReadPermission` was refused - stated in words, distinct from an honestly
     * empty list (nothing scheduled). **One-today ticket 01 removed the live path that could ever
     * produce a null here** (the local `events` table is always readable), but the branch itself is
     * left in place rather than deleted: it is still a real, pure distinction this function can
     * draw, and [LogDigestBuilderTest] still exercises it directly. A granted-but-empty read is a
     * real fact ("nothing on the calendar the next 7 days"), not a missing record, so it does NOT
     * read [DigestText.notLogged]. */
    private fun calendarLine(calendarEvents: List<OpenerCalendarBriefing.BriefingEvent>?, now: Long): String {
        if (calendarEvents == null) return DigestText.line("CALENDAR next 7d", "calendar permission not granted")
        if (calendarEvents.isEmpty()) return DigestText.line("CALENDAR next 7d", "nothing scheduled")
        val names = calendarEvents.take(MAX_NAMED_EXEMPLARS).joinToString(", ") { "${it.title} (${compactDate(it.startMs)})" }
        return DigestText.line("CALENDAR next 7d", "${calendarEvents.size} event(s): $names")
    }

    /**
     * Repeated-deferral CANDIDATES - see this file's class doc for why this is `reasoned`, not a
     * stored count. A [ListItem] flags when it has been open at least [DEFERRAL_AGE_MS] AND its
     * [ListItem.updatedAt] sits at least [DEFERRAL_TOUCH_GAP_MS] past its [ListItem.createdAt] -
     * i.e. it was edited or re-timed at least once since creation and is STILL open this long
     * after, which is the closest honest signal to "kept getting pushed" the schema actually
     * stores. The wording says "candidate", never "deferred N times" - this cannot count
     * deferrals, only detect that at least one touch happened on an item that is still open and
     * old. Empty reads as an explicit "none flagged", a real computed fact, not [DigestText
     * .notLogged] (the underlying task data is not absent, there is simply nothing to flag).
     */
    private fun deferralLines(openTasks: List<ListItem>, now: Long): List<String> {
        val candidates = openTasks.filter {
            now - it.createdAt >= DEFERRAL_AGE_MS && it.updatedAt - it.createdAt >= DEFERRAL_TOUCH_GAP_MS
        }
        if (candidates.isEmpty()) return listOf(DigestText.line("REPEATED-DEFERRAL CANDIDATES", "none flagged"))
        val names = candidates.take(MAX_NAMED_EXEMPLARS).joinToString(", ") { it.text }
        return listOf(DigestText.line("REPEATED-DEFERRAL CANDIDATES", "${candidates.size}: $names"))
    }
}
