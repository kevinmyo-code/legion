package com.kevin.legion.checklists

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
import com.kevin.legion.data.local.TickSource
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatRule
import com.kevin.legion.notes.Recurrence
import com.kevin.legion.notes.parseWeekdays
import java.time.LocalDate
import java.time.ZoneId

/**
 * The single write/read path for recurring checklists (`.scratch/one-today/issues/08-events-are-not-todos.md`'s
 * follow-on, Kevin 2026-09-02 - see [Checklist]'s own class doc for the full quote and the "no
 * nightly job" design this whole file exists to carry out). No UI in this slice - a screen calls
 * in here later, following [com.kevin.legion.goals.GoalController]/[com.kevin.legion.notes.NotesController]'s
 * own controller/DAO split so a future screen and any voice tool can never disagree about a rule.
 *
 * **No sync write-through here.** Unlike [com.kevin.legion.goals.GoalController]
 * (`LastAspectsWriteThrough`), this file writes Room directly - the brief is explicit that no sync
 * code is wired in this ticket. The four sync columns exist on all three entities from day one
 * (see each entity's own class doc) so a later sync ticket adds a write-through funnel here
 * without a second migration.
 *
 * Every day argument in this file is a local epoch day (`LocalDate.toEpochDay().toInt()`),
 * never a millisecond timestamp - matching [ChecklistTick.day]'s own column. [today] is the one
 * place "now" becomes "today" and takes a [ZoneId] purely so a test can pin it; production callers
 * never pass one.
 */
object ChecklistController {

    private fun db(context: Context) = CarDatabase.getDatabase(context)

    /** Today as a local epoch day - the zone defaults to the device's own, same as every other
     * "what day is it" read in this codebase (`ui/agenda/MonthCalendar.kt`, `ui/CalendarScreen.kt`). */
    fun today(zone: ZoneId = ZoneId.systemDefault()): Int =
        LocalDate.now(zone).toEpochDay().toInt()

    /** [epochMs] converted to a local epoch day - trap 1's own conversion, used once here so every
     * caller compares days the same way rather than re-deriving it. [zone] defaults to the
     * device's own; a test pins it explicitly to keep the comparison deterministic. */
    private fun dayOf(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toEpochDay().toInt()

    // ---- checklist CRUD ----------------------------------------------------------------------

    /**
     * [recursDaily] is DEPRECATED (see [Checklist.recursDaily]'s own doc comment) and is no longer
     * read by anything in this file - it is NOT translated into a [scheduleKind] here. A brand-new
     * checklist with no [scheduleKind] argument gets `scheduleKind = null`, which
     * [checklistsForDay]/[appliesOnDay]/[itemsWithTickState] all read as "applies every day, done
     * once ever" - the plain-todo-list default this ticket's brief describes. Only
     * [MIGRATION_65_66] back-fills `scheduleKind = "DAILY", scheduleEvery = 1` for EXISTING
     * `recursDaily = 1` rows, because that migration has to describe a historical fact about rows
     * that already exist; a fresh caller who wants a real schedule passes [scheduleKind] directly,
     * or calls [setSchedule] afterward.
     */
    suspend fun createChecklist(
        context: Context,
        name: String,
        recursDaily: Boolean = true,
        scheduleKind: String? = null,
        scheduleEvery: Int? = null,
        scheduleDaysOfWeek: String? = null,
    ): Checklist {
        val checklist = Checklist(
            name = name,
            recursDaily = recursDaily,
            scheduleKind = scheduleKind,
            scheduleEvery = scheduleEvery,
            scheduleDaysOfWeek = scheduleDaysOfWeek,
        )
        val id = db(context).checklistDao().insert(checklist)
        return checklist.copy(id = id)
    }

    /** Sets or clears a checklist's schedule after creation - [scheduleKind] null clears it back to
     * "applies every day" ([Checklist.scheduleKind]'s own doc comment). All three columns are
     * always written together; a schedule is one fact, not three independently-settable ones. */
    suspend fun setSchedule(
        context: Context,
        checklistId: Long,
        scheduleKind: String?,
        scheduleEvery: Int?,
        scheduleDaysOfWeek: String?,
        at: Long = System.currentTimeMillis(),
    ) {
        db(context).checklistDao().setSchedule(checklistId, scheduleKind, scheduleEvery, scheduleDaysOfWeek, at)
    }

    suspend fun renameChecklist(context: Context, checklistId: Long, name: String, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().rename(checklistId, name, at)
    }

    /** DEPRECATED (see [Checklist.recursDaily]'s own doc comment) - nothing in this controller
     * reads [Checklist.recursDaily] anymore, so calling this no longer changes how any read
     * behaves. Kept only because §5 forbids deleting a working API alongside a non-destructive
     * migration; use [setSchedule] instead. */
    suspend fun setRecursDaily(context: Context, checklistId: Long, recursDaily: Boolean, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().setRecursDaily(checklistId, recursDaily, at)
    }

    suspend fun archiveChecklist(context: Context, checklistId: Long, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().archive(checklistId, at)
    }

    suspend fun unarchiveChecklist(context: Context, checklistId: Long, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().unarchive(checklistId, at)
    }

    /** Soft-deletes the checklist itself. Does NOT touch its items or their ticks - matches
     * [ChecklistItem]/[ChecklistTick]'s own tombstone-only posture, so a checklist's history is
     * never rewritten by deleting the checklist any more than by deleting one of its items
     * (trap 2). Nothing currently reads a deleted checklist's history back, but nothing forbids it
     * either. */
    suspend fun deleteChecklist(context: Context, checklistId: Long, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().deleteById(checklistId, at)
    }

    suspend fun getChecklist(context: Context, checklistId: Long): Checklist? =
        db(context).checklistDao().getById(checklistId)

    suspend fun allChecklists(context: Context, includeArchived: Boolean = false): List<Checklist> =
        db(context).checklistDao().getAll(includeArchived)

    // ---- item CRUD -----------------------------------------------------------------------------

    /** Every live item on [checklistId], structure only - no tick state, no day. The UI's own
     * "add / edit / reorder / remove items" management screen (`ui/checklists/ChecklistsScreen.kt`,
     * one-today ticket 09's UI slice) reads through here rather than [itemsWithTickState], which
     * demands a [day] this screen has no reason to ask for; a day-scoped read belongs to the
     * calendar day view, not to editing a checklist's own structure. */
    suspend fun itemsFor(context: Context, checklistId: Long): List<ChecklistItem> =
        db(context).checklistItemDao().forChecklist(checklistId)

    /** [measureUnit] non-null makes this a measured item ("steps", "kg", "min") - see
     * [ChecklistItem]'s own doc comment for how [measureTarget]/[measureDirection] travel with it,
     * and [tick]'s doc comment for what a measured item then requires of every tick against it. */
    suspend fun addItem(
        context: Context,
        checklistId: Long,
        text: String,
        sortOrder: Int = 0,
        measureUnit: String? = null,
        measureTarget: Double? = null,
        measureDirection: String? = null,
    ): ChecklistItem {
        val item = ChecklistItem(
            checklistId = checklistId,
            text = text,
            sortOrder = sortOrder,
            measureUnit = measureUnit,
            measureTarget = measureTarget,
            measureDirection = measureDirection,
        )
        val id = db(context).checklistItemDao().insert(item)
        return item.copy(id = id)
    }

    /** Sets or clears an item's measure - see [ChecklistItem]'s own doc comment for the three
     * columns this writes together. */
    suspend fun setMeasure(
        context: Context,
        itemId: Long,
        measureUnit: String?,
        measureTarget: Double?,
        measureDirection: String?,
        at: Long = System.currentTimeMillis(),
    ) {
        db(context).checklistItemDao().setMeasure(itemId, measureUnit, measureTarget, measureDirection, at)
    }

    suspend fun editItem(context: Context, itemId: Long, text: String, at: Long = System.currentTimeMillis()) {
        db(context).checklistItemDao().updateText(itemId, text, at)
    }

    suspend fun reorderItem(context: Context, itemId: Long, sortOrder: Int, at: Long = System.currentTimeMillis()) {
        db(context).checklistItemDao().updateSortOrder(itemId, sortOrder, at)
    }

    /** Soft-deletes an item only - trap 2, by name. Never cascades to [ChecklistTick]; a history
     * read for a past day still resolves this item's text via
     * [com.kevin.legion.data.local.ChecklistItemDao.getByIdIncludingDeleted]. */
    suspend fun deleteItem(context: Context, itemId: Long, at: Long = System.currentTimeMillis()) {
        db(context).checklistItemDao().deleteById(itemId, at)
    }

    // ---- tick / untick -------------------------------------------------------------------------

    /** What [tick] actually did. */
    sealed class TickOutcome {
        object Ticked : TickOutcome()
        /** The item is measured ([ChecklistItem.measureUnit] non-null) and the caller supplied no
         * [ChecklistTick.value] - Kevin's ruling, verbatim: "a number is the point". Nothing is
         * written; a valueless tick on a measured item is a SKIP, never a silent done. [message] is
         * for whatever surface called this (a tool result, a screen's toast) to show in words. */
        data class Refused(val message: String) : TickOutcome()
    }

    /**
     * Ticks [itemId] for [day] (defaults to [today]). Idempotent: ticking an already-ticked
     * `(itemId, day)` is a no-op that leaves the original [ChecklistTick.tickedAt] (and its
     * [ChecklistTick.value]/[ChecklistTick.source]) untouched - double-tap does not overwrite when
     * the user actually first tapped or what they first reported.
     *
     * Retroactive by construction: passing yesterday's [day] this morning writes a tick whose
     * [ChecklistTick.day] is yesterday and whose [ChecklistTick.tickedAt] is the real "now" -
     * exactly the split [ChecklistTick]'s own class doc describes.
     *
     * Handles the untick-then-retick-same-day case explicitly: a soft-deleted `(itemId, day)` row
     * already occupies the table's unique slot, so a plain `INSERT ... OR IGNORE` would silently
     * do nothing (see [com.kevin.legion.data.local.ChecklistTickDao.insert]'s own doc comment) -
     * this checks for that tombstone first and revives it via
     * [com.kevin.legion.data.local.ChecklistTickDao.retick] with a fresh [ChecklistTick.tickedAt]
     * (and the caller's fresh [value]/[source]) instead of leaving the tick lost.
     *
     * **Refuses outright on a measured item with a null [value]** - see [TickOutcome.Refused]. This
     * check happens BEFORE any lookup of the existing tick row, so a refusal never touches the
     * table at all, revival included.
     */
    suspend fun tick(
        context: Context,
        itemId: Long,
        day: Int = today(),
        at: Long = System.currentTimeMillis(),
        value: Double? = null,
        source: String = TickSource.USER_REPORTED.name,
    ): TickOutcome {
        val item = db(context).checklistItemDao().getByIdIncludingDeleted(itemId)
        if (item?.measureUnit != null && value == null) {
            return TickOutcome.Refused(
                "\"${item.text}\" is measured in ${item.measureUnit} - give a number to tick it, nothing was recorded.",
            )
        }
        val dao = db(context).checklistTickDao()
        val existing = dao.getForItemOnDayIncludingDeleted(itemId, day)
        when {
            existing == null -> dao.insert(ChecklistTick(itemId = itemId, day = day, tickedAt = at, value = value, source = source))
            !existing.deleted -> Unit // already ticked - idempotent no-op, tickedAt/value/source untouched
            else -> dao.retick(itemId, day, at, value, source)
        }
        return TickOutcome.Ticked
    }

    /** Unticks `(itemId, day)` - a soft delete, so the row (and its [ChecklistTick.tickedAt])
     * survives for [tick]'s revival path rather than being lost. */
    suspend fun untick(context: Context, itemId: Long, day: Int = today(), at: Long = System.currentTimeMillis()) {
        db(context).checklistTickDao().untick(itemId, day, at)
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** One item plus whether it is ticked for [day]/[checklist] - the per-day read a screen
     * renders directly. [value] is the measured tick's actual number ([ChecklistTick.value]) -
     * null on a binary item, and null on a measured item that has not been ticked yet. */
    data class ItemState(val item: ChecklistItem, val ticked: Boolean, val tickedAt: Long?, val value: Double? = null)

    /** What [itemsWithTickState] hands back - the same "an empty read and a failed read are not
     * the same sentence" shape [com.kevin.legion.voice.VoiceNoteController.VoiceNotesForDayResult]
     * uses for the calendar's RECORDED section. [Loaded] with an empty list IS the quiet
     * "no items yet" (or trap-1-gated) case; [Failed] is the other one, and a caller must say so
     * in words (`ui/checklists/ChecklistDayViewLogic.kt`'s [checklistSectionLabel] does exactly
     * this for the day view's collapsed header) rather than falling back to an empty list that
     * would look identical to a genuinely empty checklist. */
    sealed interface ChecklistItemsResult {
        data class Loaded(val items: List<ItemState>) : ChecklistItemsResult
        data class Failed(val reason: String) : ChecklistItemsResult
    }

    /**
     * A checklist's items with their tick state for [day] (defaults to [today]).
     *
     * Trap 1's gate: [ChecklistItemsResult.Loaded] with an EMPTY list, never "every item
     * unticked", when [day] falls before [Checklist.createdAt] - compared as a LOCAL DAY via
     * [dayOf], never as a raw millisecond range, so a timezone change cannot shift which side of
     * the boundary a day falls on. Create "bio" today, ask for last Tuesday, and this comes back
     * empty: nothing was missed, the list did not exist.
     *
     * The mode is derived from [Checklist.scheduleKind], never from the deprecated
     * [Checklist.recursDaily] (one-today ticket 09 decision 4): a NULL [scheduleKind] is a plain
     * todo list with no day axis, so [ItemState.ticked] is true the moment ANY tick exists for
     * that item on ANY day - "done" for it is a fact about the item, not about the day being
     * viewed. A non-null [scheduleKind] (`"DAILY"`/`"WEEKLY"`) is tracked per day: [ItemState.ticked]
     * reflects [day] alone.
     *
     * Deliberately wrapped in [runCatching], same reasoning as
     * [com.kevin.legion.voice.VoiceNoteController.listInRange]'s own doc comment: a `@Query`
     * against a local SQLite database can still throw (a corrupt page, a full disk), and that
     * failure must read differently from a day/checklist with nothing on it - never silently
     * collapsed to an empty list a caller cannot tell apart from a genuinely empty one.
     */
    suspend fun itemsWithTickState(context: Context, checklistId: Long, day: Int = today()): ChecklistItemsResult =
        runCatching {
            val checklist = db(context).checklistDao().getById(checklistId) ?: return@runCatching emptyList<ItemState>()
            if (day < dayOf(checklist.createdAt)) return@runCatching emptyList<ItemState>() // trap 1

            val items = db(context).checklistItemDao().forChecklist(checklistId)
            if (items.isEmpty()) return@runCatching emptyList<ItemState>()

            if (checklist.scheduleKind != null) {
                val ticks = db(context).checklistTickDao().forItemsOnDay(items.map { it.id }, day)
                    .associateBy { it.itemId }
                items.map { item ->
                    val tick = ticks[item.id]
                    ItemState(item, ticked = tick != null, tickedAt = tick?.tickedAt, value = tick?.value)
                }
            } else {
                items.map { item ->
                    val ticks = db(context).checklistTickDao().allForItem(item.id)
                    val latest = ticks.maxByOrNull { it.tickedAt }
                    ItemState(item, ticked = ticks.isNotEmpty(), tickedAt = latest?.tickedAt, value = latest?.value)
                }
            }
        }.fold(
            onSuccess = { ChecklistItemsResult.Loaded(it) },
            onFailure = {
                android.util.Log.w("ChecklistController", "itemsWithTickState: read failed for checklist $checklistId, day $day", it)
                ChecklistItemsResult.Failed(it.message ?: "unknown error")
            },
        )

    /** Every checklist that applies to [day] (defaults to [today]) - trap 1's gate again, this
     * time over the LIST of checklists rather than one checklist's items: a checklist created
     * after [day] is excluded entirely, never returned with an implied "everything unticked".
     * On top of that, [appliesOnDay] applies [Checklist.scheduleKind] - a `WEEKLY MON,WED,FRI`
     * checklist is excluded from a Tuesday just as surely as one that did not exist yet. */
    suspend fun checklistsForDay(context: Context, day: Int = today(), includeArchived: Boolean = false): List<Checklist> =
        db(context).checklistDao().getAll(includeArchived)
            .filter { day >= dayOf(it.createdAt) && appliesOnDay(it, day) }

    /**
     * Whether [checklist]'s schedule applies on [day] - one-today ticket 09's second build (Slice
     * 2). Reuses [Recurrence] (a pure day-generator already handling daily/weekly/every-N) rather
     * than a second hand-rolled recurrence engine, per this ticket's own brief.
     *
     * `null` [Checklist.scheduleKind] (or a malformed schedule - a missing [Checklist.scheduleEvery],
     * an unrecognised kind, an empty/unparseable [Checklist.scheduleDaysOfWeek] on a `"WEEKLY"`
     * checklist) all degrade to "applies every day" - the plain-todo-list default this ticket's
     * brief describes, and the same posture [Recurrence.ruleIsWellFormed] takes internally (a
     * malformed rule produces no occurrences rather than throwing); here a malformed SCHEDULE must
     * not silently hide an otherwise-live checklist, so it degrades the other direction, toward
     * always showing rather than never showing.
     *
     * [Recurrence] operates on epoch-millisecond instants with a real time-of-day; this call anchors
     * both the series' start and the query window at LOCAL MIDNIGHT of the relevant day in [zone],
     * so a generated occurrence lands exactly at the target day's own [windowStart] and the
     * inclusive `windowStart..windowEnd` check in [Recurrence.occurrencesInWindow] catches it.
     */
    private fun appliesOnDay(checklist: Checklist, day: Int, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val kind = checklist.scheduleKind ?: return true
        val every = checklist.scheduleEvery ?: return true
        val rule: RepeatRule = when (kind) {
            "DAILY" -> RepeatRule.Daily(every)
            "WEEKLY" -> {
                val days = parseWeekdays(checklist.scheduleDaysOfWeek.orEmpty())
                if (days.isNullOrEmpty()) return true
                RepeatRule.Weekly(every, days)
            }
            else -> return true
        }
        val startDate = LocalDate.ofEpochDay(dayOf(checklist.createdAt, zone).toLong())
        val startsAtMs = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val targetDate = LocalDate.ofEpochDay(day.toLong())
        val windowStart = targetDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val windowEnd = targetDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return Recurrence.occurrencesInWindow(
            startsAt = startsAtMs,
            rule = rule,
            end = RepeatEnd.Never,
            skippedDates = emptySet(),
            windowStart = windowStart,
            windowEnd = windowEnd,
            zone = zone,
        ).isNotEmpty()
    }

    /** One day's tick state for one item, resolving the item's text even if it has since been
     * soft-deleted (trap 2) - [ChecklistHistoryLine.item] is read via
     * [com.kevin.legion.data.local.ChecklistItemDao.getByIdIncludingDeleted]-backed
     * [com.kevin.legion.data.local.ChecklistItemDao.forChecklistIncludingDeleted], never the
     * live-only [com.kevin.legion.data.local.ChecklistItemDao.forChecklist]. */
    data class ChecklistHistoryLine(val day: Int, val item: ChecklistItem, val ticked: Boolean, val tickedAt: Long?, val value: Double? = null)

    /**
     * Every item's tick state, per day, over `[fromDay, toDay]` (inclusive) - the "look back and
     * see what i did" read from the brief.
     *
     * Both traps apply here at once:
     * - Trap 1: days before [Checklist.createdAt] are excluded from the returned range entirely
     *   (not returned as unticked) - the range this function actually walks is clamped to
     *   `max(fromDay, dayOf(checklist.createdAt))..toDay`.
     * - Trap 2: [com.kevin.legion.data.local.ChecklistItemDao.forChecklistIncludingDeleted] is the
     *   item source, so a line for a soft-deleted item still resolves its [ChecklistItem.text] and
     *   still appears for the days it was actually ticked, even after being dropped from the live
     *   checklist.
     *
     * Returns one [ChecklistHistoryLine] per `(item, day)` in range that has a tick - an unticked
     * day produces no line, matching the "no row means not done, not a job deciding so" posture the
     * whole file is built on. A caller wanting to render blanks for missed days derives that from
     * absence, not from a line this function hands back.
     */
    suspend fun checklistHistory(context: Context, checklistId: Long, fromDay: Int, toDay: Int): List<ChecklistHistoryLine> {
        val checklist = db(context).checklistDao().getById(checklistId) ?: return emptyList()
        val clampedFrom = maxOf(fromDay, dayOf(checklist.createdAt)) // trap 1
        if (clampedFrom > toDay) return emptyList()

        val items = db(context).checklistItemDao().forChecklistIncludingDeleted(checklistId) // trap 2
        if (items.isEmpty()) return emptyList()

        val ticks = db(context).checklistTickDao().forItemsInRange(items.map { it.id }, clampedFrom, toDay)
        val itemsById = items.associateBy { it.id }
        return ticks.mapNotNull { tick ->
            val item = itemsById[tick.itemId] ?: return@mapNotNull null
            ChecklistHistoryLine(day = tick.day, item = item, ticked = true, tickedAt = tick.tickedAt, value = tick.value)
        }.sortedWith(compareBy({ it.day }, { it.item.sortOrder }))
    }
}
