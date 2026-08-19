package com.kevin.legion.notes

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.ListItemSkip

/**
 * The general list model absorbing [com.kevin.legion.data.local.CarTask] and
 * [com.kevin.legion.data.local.PlaceReminder]
 * (`.scratch/notes-lists-calendar/issues/01-entity-model-and-cartask-migration.md`). Mirrors the
 * natural-language-helper pattern of [com.kevin.legion.vehicle.CarTaskController]/
 * [com.kevin.legion.location.ReminderController]: `service/LiveToolbox.kt`'s voice tools call
 * these and hand the returned line/result back to be phrased, and matching/copy logic itself is
 * pure (`notes/NotesLogic.kt`) so it's tested without a `Context`.
 *
 * Two rules this file is the ONLY enforcement point for, since neither is a Room CHECK constraint
 * (matching this schema's no-CHECK-constraints posture - see [com.kevin.legion.data.local.ListItem]'s
 * doc comment):
 * - **A recurring item can never be ticked** ([tick] refuses and returns `false`) - ticket 04.
 * - **At most one trigger per item** - [setTime] always clears any place trigger first via
 *   [com.kevin.legion.data.local.ListItemDao.clearTrigger] - charting decision 4.
 */
object NotesController {
    private fun db(context: Context) = CarDatabase.getDatabase(context)

    /** Name of the one and only list - see [theList]. Kept as a name rather than dropped because
     * the row still has a `name` column and a sync peer still reads it. */
    const val LIST_NAME = "List"

    // ------------------------------------------------------------------------------- the list

    /**
     * **The** list. There is exactly one (Kevin, 2026-08-11: "dissolve the car list. merge
     * everything into one list model").
     *
     * [com.kevin.legion.data.local.MIGRATION_12_13] folded every named list - "Car", "Reminders",
     * and anything else - into one row and soft-deleted the rest, so on any migrated install this
     * finds that survivor. It creates one only on a fresh install that has never had a list at all.
     *
     * Every list VERB is gone with the structure: create, rename, archive, unarchive, copy, delete,
     * and the fuzzy name matcher (`findList`) that decided which list a voice command meant. That
     * matcher is what filed an F150 recall appointment under "Car", where the driver would not look.
     * `NotesLogic.matchList`/`ListMatch` survive only as dead code the tests still cover; nothing in
     * `service/` or `ui/` calls them.
     *
     * Always `tickable = true`: the checklist-vs-note split is gone too. Every item ticks, every
     * item may carry a date, one type.
     *
     * Defensive against a partially-applied state (a migration interrupted, a hand-edited DB): it
     * takes the OLDEST surviving list rather than asserting there is exactly one, so a stray second
     * list degrades to "one of them wins consistently" rather than to a crash or a silent second
     * inbox. Items on the loser are still visible - [allItems] reads across every list by design.
     */
    suspend fun theList(context: Context): ItemList {
        val existing = db(context).itemListDao().getAll(includeArchived = true)
            .filter { !it.archived }
            .minByOrNull { it.createdAt }
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val list = ItemList(
            name = LIST_NAME, tickable = true, lastUsedAt = now, createdAt = now, updatedAt = now,
        )
        return list.copy(id = db(context).itemListDao().insert(list))
    }

    // --------------------------------------------------------------------------------- items

    suspend fun itemsForList(context: Context, listId: Long): List<ListItem> =
        db(context).listItemDao().forList(listId)

    /** Every non-deleted item across every list - the inbox stream's source. Ordering is the
     * resolver's job, not this one's (see [com.kevin.legion.data.local.ListItemDao.allActive]). */
    suspend fun allItems(context: Context): List<ListItem> = db(context).listItemDao().allActive()

    /**
     * Appends one item to [listId] and, when [startsAt] is non-null, gives it a due date in the
     * SAME action.
     *
     * This is the fix for the reported bug: [addItem] takes text only, so the only way to put a date
     * on a new item was to add it, find it, tap its text, and fill in a dialog. An item added with a
     * date the driver typed and then rendering with no date is indistinguishable, from the outside,
     * from the date never having been stored. Routing through [setTime] also means the alarm is
     * armed at append time rather than at some later edit that may never happen.
     */
    suspend fun addItemDue(
        context: Context,
        listId: Long,
        text: String,
        startsAt: Long?,
        allDay: Boolean = true,
    ): ListItem {
        val item = addItem(context, listId, text)
        return if (startsAt == null) item else setTime(context, item, startsAt, null, allDay)
    }

    /** Single-item lookup by id - the notification-tap deep link's landing point (ticket 12:
     * "tapping the notification opens the item"). `ui/NotesScreen.kt` reads this to resolve which
     * list to jump into before the item itself can be highlighted. */
    suspend fun itemById(context: Context, id: Long): ListItem? = db(context).listItemDao().getById(id)

    suspend fun openItemsForList(context: Context, listId: Long): List<ListItem> =
        db(context).listItemDao().openForList(listId)

    suspend fun addItem(context: Context, listId: Long, text: String): ListItem {
        val now = System.currentTimeMillis()
        val nextOrder = db(context).listItemDao().forList(listId).size
        val item = ListItem(listId = listId, text = text.trim(), sortOrder = nextOrder, createdAt = now, updatedAt = now)
        val id = db(context).listItemDao().insert(item)
        db(context).itemListDao().touch(listId, now)
        return item.copy(id = id)
    }

    /** Fuzzy-matches [query] against [listId]'s OPEN items only - a done item can't be ticked or removed by voice this way. */
    suspend fun findItem(context: Context, listId: Long, query: String): ItemMatch =
        matchItem(query, db(context).listItemDao().openForList(listId))

    /** Refuses (returns false, writes nothing) for a recurring item - ticket 04. On success,
     * cancels any pending alarm too - a ticked item has nothing left to fire for. */
    suspend fun tick(context: Context, item: ListItem): Boolean {
        if (item.repeatKind != null) return false
        val now = System.currentTimeMillis()
        db(context).listItemDao().markDone(item.id, now)
        db(context).itemListDao().touch(item.listId, now)
        AlarmScheduler.cancel(context, item.id)
        return true
    }

    suspend fun untick(context: Context, item: ListItem) {
        val now = System.currentTimeMillis()
        db(context).listItemDao().markUndone(item.id, now)
        db(context).itemListDao().touch(item.listId, now)
        // Unticking a past-due item does not resurrect its alarm - only rescheduleAll/setTime
        // arm one, and a once-fired one-off's startsAt is still in the past, so there is nothing
        // sane to re-arm here. It simply goes back to being open (and, if it was ever marked
        // missed, stays in the MISSED list until dismissed - untick and dismiss are independent).
    }

    /** No confirmation (ticket 05) - soft-deleted, same tombstone discipline as [com.kevin.legion.data.local.CarTask]. */
    suspend fun removeItem(context: Context, item: ListItem) {
        db(context).listItemDao().deleteById(item.id, System.currentTimeMillis())
        AlarmScheduler.cancel(context, item.id)
    }

    /** Renames [item]'s text by hand - the single-list/editor screen's edit affordance. */
    suspend fun renameItem(context: Context, item: ListItem, text: String) =
        db(context).listItemDao().updateText(item.id, text.trim(), System.currentTimeMillis())

    /**
     * Swaps [item] with its immediate neighbour in [siblingsInOrder] (the list's own open items,
     * already sorted by `sortOrder` - the caller's own [openItemsForList]/[itemsForList] result) one
     * position toward the front ([up] = true) or back. A no-op at either end. Hand-only reorder
     * (ticket 05: addressing an item "never by position" is a VOICE grammar rule about ADDRESSING an
     * item by spoken position - it says nothing about a screen's own drag/tap reorder, which never
     * depends on the user remembering an unseen order the way a spoken command would).
     */
    suspend fun moveItem(context: Context, item: ListItem, siblingsInOrder: List<ListItem>, up: Boolean) {
        val index = siblingsInOrder.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val swapIndex = if (up) index - 1 else index + 1
        if (swapIndex !in siblingsInOrder.indices) return
        val other = siblingsInOrder[swapIndex]
        val now = System.currentTimeMillis()
        db(context).listItemDao().updateSortOrder(item.id, other.sortOrder, now)
        db(context).listItemDao().updateSortOrder(other.id, item.sortOrder, now)
    }

    /**
     * Sets a time trigger, clearing any place trigger first - "at most one trigger" (charting
     * decision 4). Also (re)schedules this item's local alarm - `notes/AlarmScheduler.kt`
     * (ticket 03) - since a time trigger with nothing watching the clock is not a reminder.
     * Returns the item as it now stands in the database, including any [ListItem.exactDowngraded]
     * flip [AlarmScheduler.schedule] may have just made - callers chaining a further [setRepeat]/
     * [setExact] onto the same edit must use the RETURNED item, not the one they passed in.
     */
    suspend fun setTime(context: Context, item: ListItem, startsAt: Long, endsAt: Long?, allDay: Boolean): ListItem {
        val now = System.currentTimeMillis()
        db(context).listItemDao().clearTrigger(item.id, now)
        db(context).listItemDao().setTime(item.id, startsAt, endsAt, allDay, now)
        db(context).itemListDao().touch(item.listId, now)
        scheduleAlarmFor(context, item.copy(startsAt = startsAt, endsAt = endsAt, allDay = allDay, triggerPlaceLabel = null))
        return refetch(context, item.id, item)
    }

    suspend fun clearTime(context: Context, item: ListItem) {
        db(context).listItemDao().clearTrigger(item.id, System.currentTimeMillis())
        AlarmScheduler.cancel(context, item.id)
    }

    /**
     * Sets a place trigger, clearing any time trigger first - mirrors [setTime] ("at most one
     * trigger", charting decision 4). This is the rewired other half of `set_reminder`
     * (`location/ReminderController.kt`) - finishing the absorption phase 1 left split-brained:
     * the tool still writes here, not to the legacy `place_reminders` table.
     */
    suspend fun setPlaceTrigger(context: Context, item: ListItem, placeLabel: String): ListItem {
        val now = System.currentTimeMillis()
        db(context).listItemDao().clearTrigger(item.id, now)
        db(context).listItemDao().setPlaceTrigger(item.id, placeLabel, now)
        db(context).itemListDao().touch(item.listId, now)
        // A place trigger has no clock to watch - nothing for AlarmManager to schedule (ticket 12:
        // "place triggers cannot be missed - no due time"). Cancel defensively in case this item
        // previously carried a time trigger with a live alarm.
        AlarmScheduler.cancel(context, item.id)
        return refetch(context, item.id, item)
    }

    /** Sets or clears (passing a null [rule]) a repeat - ticket 04. Does not itself check
     * tick-ability. Re-derives the item's next alarm the same way [setTime] does. See [setTime]'s
     * doc comment for why the RETURNED item must be used for any further chained call. */
    suspend fun setRepeat(context: Context, item: ListItem, rule: RepeatRule?, end: RepeatEnd): ListItem {
        val now = System.currentTimeMillis()
        val cols = repeatColumnsFor(rule, end)
        db(context).listItemDao().setRepeat(
            item.id, cols.repeatKind, cols.repeatEvery, cols.repeatDaysOfWeek, cols.repeatDay,
            cols.repeatMonth, cols.repeatEndKind, cols.repeatEndDate, cols.repeatEndCount, now,
        )
        db(context).itemListDao().touch(item.listId, now)
        scheduleAlarmFor(
            context,
            item.copy(
                repeatKind = cols.repeatKind, repeatEvery = cols.repeatEvery, repeatDaysOfWeek = cols.repeatDaysOfWeek,
                repeatDay = cols.repeatDay, repeatMonth = cols.repeatMonth, repeatEndKind = cols.repeatEndKind,
                repeatEndDate = cols.repeatEndDate, repeatEndCount = cols.repeatEndCount,
            ),
        )
        return refetch(context, item.id, item)
    }

    /**
     * Sets whether [item] asks for a precise, punctual alarm (ticket 03: "only when the user
     * marks an item exact"). Re-schedules immediately so a downgrade (permission refused) is
     * detected and persisted ([ListItem.exactDowngraded]) right away rather than waiting for the
     * next [AlarmScheduler.rescheduleAll] pass. See [setTime]'s doc comment for the RETURNED item.
     */
    suspend fun setExact(context: Context, item: ListItem, exact: Boolean): ListItem {
        val now = System.currentTimeMillis()
        db(context).listItemDao().setExact(item.id, exact, now)
        scheduleAlarmFor(context, item.copy(exact = exact))
        return refetch(context, item.id, item)
    }

    /** Skip a single occurrence, never move one (ticket 04) - one row, not a materialised
     * occurrence. Re-derives the item's next alarm afterward, so a skipped occurrence's slot is
     * never the one that ends up firing. */
    suspend fun skipOccurrence(context: Context, item: ListItem, skippedDateEpochMillis: Long) {
        db(context).listItemSkipDao().insert(ListItemSkip(itemId = item.id, skippedDate = skippedDateEpochMillis))
        scheduleAlarmFor(context, item)
    }

    /**
     * (Re)computes and schedules [item]'s single next alarm from its CURRENT trigger/repeat
     * columns, or cancels any pending one if there is nothing to schedule - the one place
     * [setTime]/[setRepeat]/[setExact]/[skipOccurrence] all funnel through, so "what should this
     * item's alarm be right now" has exactly one answer. Mirrors
     * [com.kevin.legion.notes.AlarmScheduler.rescheduleAll]'s own per-item logic, scoped to one
     * item instead of a full walk - a past-due one-off is left alone here (never marked missed on
     * this path) since that detection is [AlarmScheduler.rescheduleAll]'s job, run at app
     * start/boot/permission-change, not something a live edit should be doing as a side effect.
     */
    private suspend fun scheduleAlarmFor(context: Context, item: ListItem) {
        val startsAt = item.startsAt
        if (startsAt == null || item.done) {
            AlarmScheduler.cancel(context, item.id)
            return
        }
        if (item.repeatKind == null) {
            if (startsAt < System.currentTimeMillis()) {
                AlarmScheduler.cancel(context, item.id)
                return
            }
            AlarmScheduler.schedule(context, item, startsAt)
        } else {
            val rule = ruleFromItem(item) ?: run { AlarmScheduler.cancel(context, item.id); return }
            val end = endFromItem(item)
            val skips = db(context).listItemSkipDao().skippedDatesForItem(item.id).toSet()
            val next = NextOccurrence.compute(startsAt, rule, end, skips, System.currentTimeMillis())
            if (next == null) {
                AlarmScheduler.cancel(context, item.id)
            } else {
                AlarmScheduler.schedule(context, item, next)
            }
        }
    }

    /** Re-reads [id] from the database, falling back to [fallback] on the (essentially impossible,
     * mid-edit-deletion) chance it's gone - used so every trigger-editing function above returns
     * the item exactly as it now stands, including any flag [AlarmScheduler.schedule] flipped. */
    private suspend fun refetch(context: Context, id: Long, fallback: ListItem): ListItem =
        db(context).listItemDao().getById(id) ?: fallback

    // ------------------------------------------------------------------------- missed / notifications

    /** Every open item currently reported missed (ticket 12: "reported, never silent") - a
     * resolver a future screen or voice read-back can call. No screen reads this yet (phase 2b). */
    suspend fun missedItems(context: Context): List<ListItem> = db(context).listItemDao().missedItems()

    /** Clears [item]'s MISSED-list membership without touching `done` - dismissing the REPORT,
     * not completing the task (ticket 12: "firing changes nothing on the item"). */
    suspend fun dismissMissed(context: Context, item: ListItem) {
        db(context).listItemDao().dismissMissed(item.id, System.currentTimeMillis())
    }

    /** True if [item] carries a trigger but `POST_NOTIFICATIONS` is refused, so it can never
     * actually notify. Ticket 12: "must not fail silently" - a resolver, not a screen, for the
     * same reason [missedItems] is. */
    fun notificationsBlocked(context: Context, item: ListItem): Boolean {
        val hasTrigger = item.startsAt != null || item.triggerPlaceLabel != null
        if (!hasTrigger) return false
        return permissionRefused(context)
    }

    /**
     * True if `POST_NOTIFICATIONS` is refused AND at least one open item ANYWHERE carries a trigger
     * - the same underlying fact [notificationsBlocked] checks per item, hoisted to "is this
     * refusal relevant to anything right now" for `ui/NotesScreen.kt`'s screen-wide banner. Without
     * the second half, a driver with zero reminders would see a scary warning about a permission
     * that, for them, currently guards nothing.
     */
    suspend fun anyNotificationsBlocked(context: Context): Boolean {
        if (!permissionRefused(context)) return false
        val dao = db(context).listItemDao()
        return dao.allWithTimeTrigger().any { !it.done } || dao.openWithAnyPlaceTrigger().isNotEmpty()
    }

    private fun permissionRefused(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return false
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS,
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    suspend fun skippedDates(context: Context, item: ListItem): Set<Long> =
        db(context).listItemSkipDao().skippedDatesForItem(item.id).toSet()

    // ----------------------------------------------------------------------------- agenda (ticket 08)

    /** Every open, non-recurring timed item whose `startsAt` falls in `[from, to]` - the agenda
     * view's non-recurring half. Filters `startsAt IS NOT NULL` against the index ticket 08
     * requires ([com.kevin.legion.data.local.ListItem]'s `Index("startsAt")`), so this never scans
     * the same table's untimed camping-gear rows. */
    suspend fun timedItemsInWindow(context: Context, from: Long, to: Long): List<ListItem> =
        db(context).listItemDao().timedInWindow(from, to)

    /** Every open recurring item, across every list - the agenda view's recurring half, expanded
     * into the visible window by [com.kevin.legion.notes.Recurrence] at the call site (never here -
     * this stays a plain DB read so the expansion math stays testable in isolation). */
    suspend fun allRecurringItems(context: Context): List<ListItem> =
        db(context).listItemDao().allRecurring()

    /** Batched list-name lookup, keyed by id - lets the agenda/MISSED screens resolve every row's
     * list name from one query instead of one per row. */
    suspend fun listNamesById(context: Context): Map<Long, String> =
        db(context).itemListDao().getAllForMatch().associate { it.id to it.name }

    // ----------------------------------------------------------------------- awareness helper

    /**
     * How many open items exist, full stop - [com.kevin.legion.ai.AriaBrain]'s prompt-assembly
     * awareness flag.
     *
     * Counts across every list row rather than [theList]'s alone. On a migrated install those are
     * the same number, but a count that silently under-reports because an item sat on a stray list
     * would be an awareness flag that quietly lies, and a flag that undercounts is worse than no
     * flag: it reads as "nothing pending" rather than as "ask".
     */
    suspend fun openItemCount(context: Context): Int =
        db(context).listItemDao().allActive().count { !it.done }
}
