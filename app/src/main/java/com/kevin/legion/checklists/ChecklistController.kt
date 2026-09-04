package com.kevin.legion.checklists

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.ChecklistTick
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

    suspend fun createChecklist(context: Context, name: String, recursDaily: Boolean = true): Checklist {
        val checklist = Checklist(name = name, recursDaily = recursDaily)
        val id = db(context).checklistDao().insert(checklist)
        return checklist.copy(id = id)
    }

    suspend fun renameChecklist(context: Context, checklistId: Long, name: String, at: Long = System.currentTimeMillis()) {
        db(context).checklistDao().rename(checklistId, name, at)
    }

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

    suspend fun addItem(context: Context, checklistId: Long, text: String, sortOrder: Int = 0): ChecklistItem {
        val item = ChecklistItem(checklistId = checklistId, text = text, sortOrder = sortOrder)
        val id = db(context).checklistItemDao().insert(item)
        return item.copy(id = id)
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

    /**
     * Ticks [itemId] for [day] (defaults to [today]). Idempotent: ticking an already-ticked
     * `(itemId, day)` is a no-op that leaves the original [ChecklistTick.tickedAt] untouched -
     * double-tap does not overwrite when the user actually first tapped.
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
     * instead of leaving the tick lost.
     */
    suspend fun tick(context: Context, itemId: Long, day: Int = today(), at: Long = System.currentTimeMillis()) {
        val dao = db(context).checklistTickDao()
        val existing = dao.getForItemOnDayIncludingDeleted(itemId, day)
        when {
            existing == null -> dao.insert(ChecklistTick(itemId = itemId, day = day, tickedAt = at))
            !existing.deleted -> Unit // already ticked - idempotent no-op, tickedAt untouched
            else -> dao.retick(itemId, day, at)
        }
    }

    /** Unticks `(itemId, day)` - a soft delete, so the row (and its [ChecklistTick.tickedAt])
     * survives for [tick]'s revival path rather than being lost. */
    suspend fun untick(context: Context, itemId: Long, day: Int = today(), at: Long = System.currentTimeMillis()) {
        db(context).checklistTickDao().untick(itemId, day, at)
    }

    // ---- reads ---------------------------------------------------------------------------------

    /** One item plus whether it is ticked for [day]/[checklist] - the per-day read a screen
     * renders directly. */
    data class ItemState(val item: ChecklistItem, val ticked: Boolean, val tickedAt: Long?)

    /**
     * A checklist's items with their tick state for [day] (defaults to [today]).
     *
     * Trap 1's gate: returns an EMPTY list, never "every item unticked", when [day] falls before
     * [Checklist.createdAt] - compared as a LOCAL DAY via [dayOf], never as a raw millisecond
     * range, so a timezone change cannot shift which side of the boundary a day falls on. Create
     * "bio" today, ask for last Tuesday, and this comes back empty: nothing was missed, the list
     * did not exist.
     *
     * For a NON-recurring checklist ([Checklist.recursDaily] false), [ItemState.ticked] is true
     * the moment ANY tick exists for that item on ANY day, not just [day] - "done" for a one-shot
     * list is a fact about the item, not about the day being viewed.
     */
    suspend fun itemsWithTickState(context: Context, checklistId: Long, day: Int = today()): List<ItemState> {
        val checklist = db(context).checklistDao().getById(checklistId) ?: return emptyList()
        if (day < dayOf(checklist.createdAt)) return emptyList() // trap 1

        val items = db(context).checklistItemDao().forChecklist(checklistId)
        if (items.isEmpty()) return emptyList()

        return if (checklist.recursDaily) {
            val ticks = db(context).checklistTickDao().forItemsOnDay(items.map { it.id }, day)
                .associateBy { it.itemId }
            items.map { item ->
                val tick = ticks[item.id]
                ItemState(item, ticked = tick != null, tickedAt = tick?.tickedAt)
            }
        } else {
            items.map { item ->
                val ticks = db(context).checklistTickDao().allForItem(item.id)
                val latest = ticks.maxByOrNull { it.tickedAt }
                ItemState(item, ticked = ticks.isNotEmpty(), tickedAt = latest?.tickedAt)
            }
        }
    }

    /** Every checklist that applies to [day] (defaults to [today]) - trap 1's gate again, this
     * time over the LIST of checklists rather than one checklist's items: a checklist created
     * after [day] is excluded entirely, never returned with an implied "everything unticked". */
    suspend fun checklistsForDay(context: Context, day: Int = today(), includeArchived: Boolean = false): List<Checklist> =
        db(context).checklistDao().getAll(includeArchived).filter { day >= dayOf(it.createdAt) }

    /** One day's tick state for one item, resolving the item's text even if it has since been
     * soft-deleted (trap 2) - [ChecklistHistoryLine.item] is read via
     * [com.kevin.legion.data.local.ChecklistItemDao.getByIdIncludingDeleted]-backed
     * [com.kevin.legion.data.local.ChecklistItemDao.forChecklistIncludingDeleted], never the
     * live-only [com.kevin.legion.data.local.ChecklistItemDao.forChecklist]. */
    data class ChecklistHistoryLine(val day: Int, val item: ChecklistItem, val ticked: Boolean, val tickedAt: Long?)

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
            ChecklistHistoryLine(day = tick.day, item = item, ticked = true, tickedAt = tick.tickedAt)
        }.sortedWith(compareBy({ it.day }, { it.item.sortOrder }))
    }
}
