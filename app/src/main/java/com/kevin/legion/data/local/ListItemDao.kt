package com.kevin.legion.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/** Data Access Object for [ListItem]. */
@Dao
interface ListItemDao {
    @Insert
    suspend fun insert(item: ListItem): Long

    @Query("SELECT * FROM list_items WHERE id = :id AND deleted = 0")
    suspend fun getById(id: Long): ListItem?

    @Query("SELECT * FROM list_items WHERE listId = :listId AND deleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun forList(listId: Long): List<ListItem>

    @Query("SELECT * FROM list_items WHERE listId = :listId AND done = 0 AND deleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun openForList(listId: Long): List<ListItem>

    @Query("SELECT COUNT(*) FROM list_items WHERE listId = :listId AND done = 0 AND deleted = 0")
    suspend fun openCountForList(listId: Long): Int

    /**
     * Every non-deleted item across EVERY list - the one-stream inbox screen's source
     * (`ui/notes/InboxScreen.kt`).
     *
     * Deliberately unordered here beyond a stable tiebreak: the screen's own ordering is
     * due-date-first with undated items after, which SQL cannot express in one clause without
     * `startsAt IS NULL` sorting games that would silently disagree with the pure resolver
     * ([com.kevin.legion.ui.notes.buildInboxRows]) that every test exercises. Ordering stays in the
     * resolver, in one place, testable without Room.
     *
     * Includes DONE items - the inbox shows them ticked and struck through rather than vanishing,
     * so a tick is undoable without hunting. Callers filter if they want open-only.
     */
    @Query("SELECT * FROM list_items WHERE deleted = 0 ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun allActive(): List<ListItem>

    // updatedAt bumped alongside doneAt so cross-device sync LWW would see the tick as a fresh
    // write (mirrors CarTaskDao.markDone). The controller refuses this call entirely for a
    // recurring item (repeatKind != null) BEFORE it ever reaches the DAO - ticket 04.
    @Query("UPDATE list_items SET done = 1, doneAt = :doneAt, updatedAt = :doneAt WHERE id = :id")
    suspend fun markDone(id: Long, doneAt: Long)

    @Query("UPDATE list_items SET done = 0, doneAt = NULL, updatedAt = :at WHERE id = :id")
    suspend fun markUndone(id: Long, at: Long)

    @Query("UPDATE list_items SET deleted = 1, updatedAt = :at WHERE id = :id")
    suspend fun deleteById(id: Long, at: Long)

    /** Renames an item's text by hand - the single-list/editor screen's edit affordance (phase 2b). */
    @Query("UPDATE list_items SET text = :text, updatedAt = :at WHERE id = :id")
    suspend fun updateText(id: Long, text: String, at: Long)

    /** Manual reorder (up/down) within a list - the single-list/editor screen's reorder affordance
     * (phase 2b). Position is not fuzzy-addressable by voice (ticket 05: "never by position"), so
     * this is a hand-only path; nothing in `LiveToolbox` calls it. */
    @Query("UPDATE list_items SET sortOrder = :sortOrder, updatedAt = :at WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, at: Long)

    /**
     * Sets an item's time, and **clears any MISSED flag** (audit fix, 2026-08-07).
     *
     * `missedAt` was previously only ever cleared by an explicit dismiss, never by giving the item a
     * new time. So the MISSED banner's own repair flow was broken in a circle: tap the missed
     * reminder, pick a new future time, save - and it came straight back to the MISSED list, now
     * labelled "was due" with a stale timestamp, while a perfectly good alarm sat armed for the
     * future. Ticket 12 built MISSED so a dropped reminder is reported rather than silent; a MISSED
     * list that cannot be emptied by fixing the thing trains the driver to ignore it, which ends in
     * the same place as never reporting it at all.
     */
    @Query(
        "UPDATE list_items SET startsAt = :startsAt, endsAt = :endsAt, allDay = :allDay, " +
            "missedAt = NULL, missedDismissedAt = NULL, updatedAt = :at WHERE id = :id"
    )
    suspend fun setTime(id: Long, startsAt: Long?, endsAt: Long?, allDay: Boolean, at: Long)

    /** Clears any trigger (time and place both) - used before setting one, to enforce "at most one". */
    @Query("UPDATE list_items SET startsAt = NULL, endsAt = NULL, triggerPlaceLabel = NULL, updatedAt = :at WHERE id = :id")
    suspend fun clearTrigger(id: Long, at: Long)

    @Query(
        "UPDATE list_items SET repeatKind = :repeatKind, repeatEvery = :repeatEvery, " +
            "repeatDaysOfWeek = :repeatDaysOfWeek, repeatDay = :repeatDay, repeatMonth = :repeatMonth, " +
            "repeatEndKind = :repeatEndKind, repeatEndDate = :repeatEndDate, repeatEndCount = :repeatEndCount, " +
            "updatedAt = :at WHERE id = :id"
    )
    suspend fun setRepeat(
        id: Long,
        repeatKind: String?,
        repeatEvery: Int?,
        repeatDaysOfWeek: String?,
        repeatDay: Int?,
        repeatMonth: Int?,
        repeatEndKind: String?,
        repeatEndDate: Long?,
        repeatEndCount: Int?,
        at: Long,
    )

    /** Every open recurring item, across every list - the calendar/agenda query source (ticket 08). */
    @Query("SELECT * FROM list_items WHERE deleted = 0 AND repeatKind IS NOT NULL")
    suspend fun allRecurring(): List<ListItem>

    /**
     * Every OPEN, non-recurring timed item whose startsAt falls in a window (ticket 08's agenda query).
     *
     * **`done = 0` added 2026-08-07 (audit).** Without it the agenda kept rendering ticked one-off
     * events as still upcoming for the rest of the 90-day window - they vanished from the list view
     * (which filters `done`) while the calendar went on showing them, indistinguishable from real
     * pending events. Every other "active" query in this DAO already filtered `done`; this one was
     * the outlier. The alarm itself was always cancelled correctly on tick, so this was a screen
     * telling the driver something untrue rather than a missed reminder - which is still the shape
     * of failure this repo keeps shipping.
     */
    @Query("SELECT * FROM list_items WHERE deleted = 0 AND done = 0 AND repeatKind IS NULL AND startsAt BETWEEN :from AND :to ORDER BY startsAt ASC")
    suspend fun timedInWindow(from: Long, to: Long): List<ListItem>

    /** Every open item carrying a place trigger matching [label] - the rewired `set_reminder`/
     * arrival-monitor path (ticket 05's absorption, finishing phase 1's split-brain). */
    @Query("SELECT * FROM list_items WHERE triggerPlaceLabel = :label AND done = 0 AND deleted = 0 ORDER BY createdAt")
    suspend fun openWithPlaceTrigger(label: String): List<ListItem>

    /** Every open item carrying ANY place trigger, across every list. */
    @Query("SELECT * FROM list_items WHERE triggerPlaceLabel IS NOT NULL AND done = 0 AND deleted = 0 ORDER BY createdAt")
    suspend fun openWithAnyPlaceTrigger(): List<ListItem>

    /** Sets (or moves) [id]'s place trigger - the caller clears any existing trigger first via
     * [clearTrigger], mirroring [setTime]'s "at most one trigger" pattern (charting decision 4). */
    @Query("UPDATE list_items SET triggerPlaceLabel = :label, updatedAt = :at WHERE id = :id")
    suspend fun setPlaceTrigger(id: Long, label: String, at: Long)

    /** Every open, non-deleted item with a time trigger - `notes/AlarmScheduler.rescheduleAll`'s
     * one full scan (ticket 03). Includes done items too (filtered by the caller) since a done
     * item's stale alarm still needs to be recognized and skipped, not just ignored. */
    @Query("SELECT * FROM list_items WHERE deleted = 0 AND startsAt IS NOT NULL")
    suspend fun allWithTimeTrigger(): List<ListItem>

    @Query("UPDATE list_items SET exact = :exact, updatedAt = :at WHERE id = :id")
    suspend fun setExact(id: Long, exact: Boolean, at: Long)

    @Query("UPDATE list_items SET exactDowngraded = :downgraded, updatedAt = :at WHERE id = :id")
    suspend fun setExactDowngraded(id: Long, downgraded: Boolean, at: Long)

    /** Marks [id] missed NOW - idempotent by construction, since `rescheduleAll` only calls this
     * when `missedAt IS NULL` (ticket 12: stored once, never recomputed). */
    @Query("UPDATE list_items SET missedAt = :at WHERE id = :id")
    suspend fun markMissed(id: Long, at: Long)

    /** Every open, undone, non-deleted item currently reported missed - the resolver a future
     * screen or voice read-back surfaces (ticket 12: "reported, never silent"). */
    @Query("SELECT * FROM list_items WHERE deleted = 0 AND done = 0 AND missedAt IS NOT NULL AND missedDismissedAt IS NULL ORDER BY startsAt ASC")
    suspend fun missedItems(): List<ListItem>

    /** Clears an item's MISSED-list membership without touching `done` - dismissing the REPORT,
     * not completing the underlying task (ticket 12: "firing changes nothing on the item"). */
    @Query("UPDATE list_items SET missedDismissedAt = :at WHERE id = :id")
    suspend fun dismissMissed(id: Long, at: Long)
}
