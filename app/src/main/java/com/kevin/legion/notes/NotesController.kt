package com.kevin.legion.notes

import android.content.Context
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.notes.NotesAspectSeeder
import org.json.JSONObject

/**
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`, `.scratch/aspect-engine/issues/22-cutover-per-aspect.md`).
 * Every function below keeps its ORIGINAL signature and return type - every caller (screens, voice
 * tools, digests, the goal-checklist sweep) flips onto the engine with this file, unchanged (ADR
 * 0035: "the controller keeps its seam"). What changed is entirely internal: reads and writes now
 * go through [RecordStore] against the Notes aspect's `Item` record type
 * (`docs/architecture/wave1-carve-2026-08-23.md`'s field mapping, reused verbatim - not
 * reinvented), and every [ListItem] this file hands back is an in-memory value object assembled
 * from an [EngineRecord]'s JSON payload, never a row actually persisted in the legacy `list_items`
 * table. **`list_items`/`list_item_skips` have ZERO writers from this file after cutover** - see
 * the cutover doc's reader/writer table for the full grep-proven account.
 *
 * **`item_lists` keeps exactly one reader here ([theList]) and gains no new writer.** `ItemList`
 * itself was never migrated onto the engine (wave 1 carve: "dies... every list VERB is already
 * dead code"), and this file's own pre-cutover version already treated it as vestigial - the only
 * two writes it ever made ([theList]'s fallback insert and every mutator's `touch()` call) are both
 * gone now. [theList] still reads the legacy row so an existing install's list NAME survives
 * cutover unchanged, but a fresh install (or one where the legacy row is somehow absent) gets an
 * in-memory placeholder instead of inserting one - "the one list" is now a display convenience, not
 * a real foreign key anything on the engine side points at. Every converted [ListItem]'s
 * [ListItem.listId] is set to [theList]'s id, so every caller that ever compared `item.listId ==
 * list.id` (`GoalChecklistSync`, the `advisor/digest` builders) keeps working with no change of its own.
 *
 * **`loggedAt` needed a new home.** The wave 1 carve deliberately did not carry
 * [ListItem.loggedAt] onto the engine, reasoning it was `GoalChecklistSync`'s own sweep
 * bookkeeping rather than user content - true only as long as the legacy table stayed live. Cutover
 * adds a new field to the SAME Notes `Item` record type ([NotesAspectSeeder.FIELD_LOGGED_AT]) - the
 * engine's whole point is that this needs no Room migration, just a new `FieldDef` row that
 * [NotesAspectSeeder.ensureSeeded] lands idempotently on every install's next seed pass.
 *
 * Two rules this file is still the ONLY enforcement point for, exactly as before cutover:
 * - **A recurring item can never be ticked** ([tick] refuses and returns `false`) - ticket 04.
 * - **At most one trigger per item** - [setTime] always clears any place trigger first - charting
 *   decision 4.
 */
object NotesController {
    private fun db(context: Context) = CarDatabase.getDatabase(context)
    private fun store(context: Context): RecordStore {
        val db = db(context)
        return RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
    }

    /** Name of the one and only list - see [theList]. Kept as a name rather than dropped because
     * the row still has a `name` column and a sync peer still reads it. */
    const val LIST_NAME = "List"

    /** The id every converted [ListItem] carries when no legacy `item_lists` row exists at all
     * (a fresh install, post-cutover, that never had one) - a stable, arbitrary constant rather
     * than a real foreign key, since nothing on the engine side references it. */
    private const val FALLBACK_LIST_ID = 1L

    // ------------------------------------------------------------------------------- the list

    /**
     * **The** list. There is exactly one (Kevin, 2026-08-11). **Read-only since cutover 1** - see
     * this object's own class doc for why the pre-cutover fallback insert and every `touch()` call
     * are both gone. Returns the legacy row if one exists (so an existing install's list name
     * survives unchanged), or an in-memory placeholder otherwise. Every function below that used to
     * take `listId` from this and touch it now only reads [ItemList.id]/[ItemList.name] off the
     * result.
     */
    suspend fun theList(context: Context): ItemList {
        val existing = db(context).itemListDao().getAll(includeArchived = true)
            .filter { !it.archived }
            .minByOrNull { it.createdAt }
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        return ItemList(id = FALLBACK_LIST_ID, name = LIST_NAME, tickable = true, lastUsedAt = now, createdAt = now, updatedAt = now)
    }

    // ---------------------------------------------------------------------- engine <-> ListItem bridge

    private suspend fun schema(context: Context) = NotesAspectSeeder.ensureSeeded(context)

    /** Every field id the schema seeds, resolved once per call - matches
     * `docs/architecture/wave1-carve-2026-08-23.md`'s field mapping table exactly; nothing here
     * invents a second mapping. */
    private fun toListItem(record: EngineRecord, fieldIds: Map<String, Long>, listId: Long): ListItem {
        val payload = JSONObject(record.payload)
        fun s(name: String) = PayloadCodec.readString(payload, fieldIds.getValue(name))
        fun l(name: String) = PayloadCodec.readLong(payload, fieldIds.getValue(name))
        fun i(name: String) = PayloadCodec.readDouble(payload, fieldIds.getValue(name))?.toInt()
        fun b(name: String, default: Boolean = false) = PayloadCodec.readBoolean(payload, fieldIds.getValue(name), default)

        return ListItem(
            id = record.id,
            listId = listId,
            text = s(NotesAspectSeeder.FIELD_TEXT).orEmpty(),
            done = b(NotesAspectSeeder.FIELD_DONE),
            doneAt = l(NotesAspectSeeder.FIELD_DONE_AT),
            sortOrder = i(NotesAspectSeeder.FIELD_SORT_ORDER) ?: 0,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
            syncId = record.guid,
            deleted = record.deletedAt != null,
            startsAt = l(NotesAspectSeeder.FIELD_STARTS_AT),
            endsAt = l(NotesAspectSeeder.FIELD_ENDS_AT),
            allDay = b(NotesAspectSeeder.FIELD_ALL_DAY, default = true),
            triggerPlaceLabel = s(NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL),
            repeatKind = s(NotesAspectSeeder.FIELD_REPEAT_KIND),
            repeatEvery = i(NotesAspectSeeder.FIELD_REPEAT_EVERY),
            repeatDaysOfWeek = s(NotesAspectSeeder.FIELD_REPEAT_DAYS_OF_WEEK),
            repeatDay = i(NotesAspectSeeder.FIELD_REPEAT_DAY),
            repeatMonth = i(NotesAspectSeeder.FIELD_REPEAT_MONTH),
            repeatEndKind = s(NotesAspectSeeder.FIELD_REPEAT_END_KIND),
            repeatEndDate = l(NotesAspectSeeder.FIELD_REPEAT_END_DATE),
            repeatEndCount = i(NotesAspectSeeder.FIELD_REPEAT_END_COUNT),
            exact = b(NotesAspectSeeder.FIELD_EXACT),
            exactDowngraded = b(NotesAspectSeeder.FIELD_EXACT_DOWNGRADED),
            missedAt = l(NotesAspectSeeder.FIELD_MISSED_AT),
            missedDismissedAt = l(NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT),
            loggedAt = l(NotesAspectSeeder.FIELD_LOGGED_AT),
        )
    }

    /** Every non-trashed engine record of the Notes `Item` type, converted - the one place every
     * read below funnels through, so there is exactly one query against the engine per read. */
    private suspend fun allEngineItems(context: Context): List<ListItem> {
        val db = db(context)
        val sch = schema(context)
        val listId = theList(context).id
        return db.engineRecordDao().activeByRecordType(sch.recordTypeId)
            .map { toListItem(it, sch.fieldIds, listId) }
    }

    private fun fieldValues(sch: NotesAspectSeeder.Schema, vararg pairs: Pair<String, Any?>): Map<Long, Any?> =
        pairs.associate { (name, value) -> sch.fieldIds.getValue(name) to value }

    // --------------------------------------------------------------------------------- items

    /** [listId] is accepted for signature compatibility only - since cutover there is exactly one
     * list, every item lives in it regardless of which id is passed (matching the live app's own
     * pre-cutover "one list" reality the wave 1 carve already documented). */
    suspend fun itemsForList(context: Context, listId: Long): List<ListItem> = allEngineItems(context)

    /** Every non-deleted item - the inbox stream's source. */
    suspend fun allItems(context: Context): List<ListItem> = allEngineItems(context)

    suspend fun addItemDue(
        context: Context,
        listId: Long,
        text: String,
        startsAt: Long?,
        allDay: Boolean = true,
    ): ListItem {
        val item = addItem(context, listId, text)
        // setTime returning null (a failed engine write) keeps the freshly-added, undated item
        // rather than fabricating a due date that never actually landed - the append itself already
        // succeeded (addItem does not itself return nullable), so this is a partial, not a total,
        // failure, and the caller still gets a real item back.
        return if (startsAt == null) item else setTime(context, item, startsAt, null, allDay) ?: item
    }

    suspend fun itemById(context: Context, id: Long): ListItem? {
        val db = db(context)
        val record = db.engineRecordDao().getById(id) ?: return null
        if (record.deletedAt != null) return null
        val sch = schema(context)
        if (record.recordTypeId != sch.recordTypeId) return null
        return toListItem(record, sch.fieldIds, theList(context).id)
    }

    suspend fun openItemsForList(context: Context, listId: Long): List<ListItem> =
        allEngineItems(context).filter { !it.done }

    suspend fun addItem(context: Context, listId: Long, text: String): ListItem {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val nextOrder = allEngineItems(context).size
        val values = fieldValues(
            sch,
            NotesAspectSeeder.FIELD_TEXT to text.trim(),
            NotesAspectSeeder.FIELD_DONE to false,
            NotesAspectSeeder.FIELD_SORT_ORDER to nextOrder,
        )
        val result = store(context).create(sch.recordTypeId, values, RecordProvenance.USER, now = now)
        val id = (result as? RecordStore.WriteResult.Success)?.recordId
            ?: error("NotesController.addItem: engine write failed - ${(result as RecordStore.WriteResult.Failure).reason}")
        return itemById(context, id) ?: error("NotesController.addItem: record #$id vanished immediately after create")
    }

    /** Fuzzy-matches [query] against OPEN items only - a done item can't be ticked or removed by voice this way. */
    suspend fun findItem(context: Context, listId: Long, query: String): ItemMatch =
        matchItem(query, openItemsForList(context, listId))

    /** True only when [result] is a [RecordStore.WriteResult.Success] - the one place every
     * Boolean-returning mutator below decides success, so "wrote nothing but said success" (the
     * §7 outcome-verb violation senior review flagged 2026-08-24) cannot creep back in one function
     * at a time. A [RecordStore.WriteResult.Failure] is swallowed to a plain `false` here
     * deliberately - the worded [RecordStore.WriteResult.Failure.reason] is for whoever is closer
     * to the user (LiveToolbox's spoken layer, a screen's own error state), not baked into this
     * controller's return type, so every caller of a `Boolean`-returning function here stays exactly
     * as simple as it was before this fix; a caller that wants the worded reason calls
     * [RecordStore.update]/[RecordStore.create] itself instead of going through this facade. */
    private fun RecordStore.WriteResult.succeeded(): Boolean = this is RecordStore.WriteResult.Success

    /** Refuses (returns false, writes nothing) for a recurring item - ticket 04. Also returns false,
     * writing nothing further, if the engine write itself fails (senior review, 2026-08-24: "tick...
     * ignore RecordStore.WriteResult and return true" was a real §7 outcome-verb violation - a
     * failed write must never look like a successful tick to the spoken layer). */
    suspend fun tick(context: Context, item: ListItem): Boolean {
        if (item.repeatKind != null) return false
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val ok = store(context).update(item.id, fieldValues(sch, NotesAspectSeeder.FIELD_DONE to true, NotesAspectSeeder.FIELD_DONE_AT to now), now).succeeded()
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** Returns false, writing nothing further, on a failed engine write - same §7 fix as [tick]. */
    suspend fun untick(context: Context, item: ListItem): Boolean {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val ok = store(context).update(
            item.id,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_DONE to false,
                NotesAspectSeeder.FIELD_DONE_AT to null,
                NotesAspectSeeder.FIELD_LOGGED_AT to null,
            ),
            now,
        ).succeeded()
        if (!ok) return false
        // Ticket 09 ("a ticked workout is one act, not two rows"): a correction propagates to the
        // training history - see the pre-cutover version of this comment, unchanged reasoning,
        // still a DAO call rather than a WorkoutController import (notes/ stays a foundation layer).
        db(context).workoutSetLogDao().deleteBySourceListItemId(item.id)
        return true
    }

    /** No confirmation (ticket 05) - trashed via [RecordStore.delete], same discipline as every
     * other engine record. Returns false on [RecordStore.DeleteResult.NotFound]/
     * [RecordStore.DeleteResult.AlreadyTrashed]/[RecordStore.DeleteResult.Blocked] - only a real
     * [RecordStore.DeleteResult.Trashed] cancels the alarm and reports success. */
    suspend fun removeItem(context: Context, item: ListItem): Boolean {
        val trashed = store(context).delete(item.id) is RecordStore.DeleteResult.Trashed
        if (trashed) AlarmScheduler.cancel(context, item.id)
        return trashed
    }

    suspend fun renameItem(context: Context, item: ListItem, text: String): Boolean {
        val sch = schema(context)
        return store(context).update(item.id, fieldValues(sch, NotesAspectSeeder.FIELD_TEXT to text.trim()), System.currentTimeMillis()).succeeded()
    }

    /** Returns false (writing nothing further) the moment EITHER swap-half fails, rather than
     * reporting success on a half-applied reorder - the two updates are not wrapped in a single
     * transaction, so this is the closest this function can get to "no false success" without a new
     * [RecordStore] capability. */
    suspend fun moveItem(context: Context, item: ListItem, siblingsInOrder: List<ListItem>, up: Boolean): Boolean {
        val index = siblingsInOrder.indexOfFirst { it.id == item.id }
        if (index < 0) return false
        val swapIndex = if (up) index - 1 else index + 1
        if (swapIndex !in siblingsInOrder.indices) return false
        val other = siblingsInOrder[swapIndex]
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val s = store(context)
        val firstOk = s.update(item.id, fieldValues(sch, NotesAspectSeeder.FIELD_SORT_ORDER to other.sortOrder), now).succeeded()
        val secondOk = s.update(other.id, fieldValues(sch, NotesAspectSeeder.FIELD_SORT_ORDER to item.sortOrder), now).succeeded()
        return firstOk && secondOk
    }

    /** Returns null (writing nothing further - no alarm scheduled) on a failed engine write, the
     * `ListItem`-returning counterpart of [tick]'s Boolean fix. A non-null return is a genuine,
     * confirmed write - callers must not treat a null as "unchanged" and silently carry on with the
     * stale pre-call item, the same "no false success" posture as every Boolean fix in this file. */
    suspend fun setTime(context: Context, item: ListItem, startsAt: Long, endsAt: Long?, allDay: Boolean): ListItem? {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val ok = store(context).update(
            item.id,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_STARTS_AT to startsAt,
                NotesAspectSeeder.FIELD_ENDS_AT to endsAt,
                NotesAspectSeeder.FIELD_ALL_DAY to allDay,
                NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to null, // at most one trigger - charting decision 4
                NotesAspectSeeder.FIELD_MISSED_AT to null,
                NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT to null,
            ),
            now,
        ).succeeded()
        if (!ok) return null
        scheduleAlarmFor(context, item.copy(startsAt = startsAt, endsAt = endsAt, allDay = allDay, triggerPlaceLabel = null))
        return refetch(context, item.id, item)
    }

    suspend fun clearTime(context: Context, item: ListItem): Boolean {
        val sch = schema(context)
        val ok = store(context).update(
            item.id,
            fieldValues(sch, NotesAspectSeeder.FIELD_STARTS_AT to null, NotesAspectSeeder.FIELD_ENDS_AT to null, NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to null),
            System.currentTimeMillis(),
        ).succeeded()
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setPlaceTrigger(context: Context, item: ListItem, placeLabel: String): ListItem? {
        val sch = schema(context)
        val ok = store(context).update(
            item.id,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_STARTS_AT to null,
                NotesAspectSeeder.FIELD_ENDS_AT to null,
                NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to placeLabel,
            ),
            System.currentTimeMillis(),
        ).succeeded()
        if (!ok) return null
        // A place trigger has no clock to watch - nothing for AlarmManager to schedule.
        AlarmScheduler.cancel(context, item.id)
        return refetch(context, item.id, item)
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setRepeat(context: Context, item: ListItem, rule: RepeatRule?, end: RepeatEnd): ListItem? {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val cols = repeatColumnsFor(rule, end)
        val ok = store(context).update(
            item.id,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_REPEAT_KIND to cols.repeatKind,
                NotesAspectSeeder.FIELD_REPEAT_EVERY to cols.repeatEvery,
                NotesAspectSeeder.FIELD_REPEAT_DAYS_OF_WEEK to cols.repeatDaysOfWeek,
                NotesAspectSeeder.FIELD_REPEAT_DAY to cols.repeatDay,
                NotesAspectSeeder.FIELD_REPEAT_MONTH to cols.repeatMonth,
                NotesAspectSeeder.FIELD_REPEAT_END_KIND to cols.repeatEndKind,
                NotesAspectSeeder.FIELD_REPEAT_END_DATE to cols.repeatEndDate,
                NotesAspectSeeder.FIELD_REPEAT_END_COUNT to cols.repeatEndCount,
            ),
            now,
        ).succeeded()
        if (!ok) return null
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

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setExact(context: Context, item: ListItem, exact: Boolean): ListItem? {
        val sch = schema(context)
        val ok = store(context).update(item.id, fieldValues(sch, NotesAspectSeeder.FIELD_EXACT to exact), System.currentTimeMillis()).succeeded()
        if (!ok) return null
        scheduleAlarmFor(context, item.copy(exact = exact))
        return refetch(context, item.id, item)
    }

    /** [AlarmScheduler.schedule]'s own downgrade-persistence write - was a direct DAO call before
     * cutover, now routed through the engine like every other field write in this file. */
    suspend fun setExactDowngraded(context: Context, itemId: Long, downgraded: Boolean): Boolean {
        val sch = schema(context)
        return store(context).update(itemId, fieldValues(sch, NotesAspectSeeder.FIELD_EXACT_DOWNGRADED to downgraded), System.currentTimeMillis()).succeeded()
    }

    /** [AlarmScheduler.rescheduleAll]'s own missed-marking write - ticket 12: "stored once, never
     * recomputed." No `missedAt IS NULL` guard here (unlike the legacy DAO query) - callers
     * ([AlarmScheduler.rescheduleAll]) already check that before calling, matching the legacy
     * comment's own "idempotent by construction... only calls this when missedAt IS NULL". */
    suspend fun markMissed(context: Context, itemId: Long): Boolean {
        val sch = schema(context)
        return store(context).update(itemId, fieldValues(sch, NotesAspectSeeder.FIELD_MISSED_AT to System.currentTimeMillis()), System.currentTimeMillis()).succeeded()
    }

    /** [GoalChecklistSync]'s own idempotence-anchor write - see this file's own class doc for why
     * `loggedAt` needed a new field on the engine side. */
    suspend fun markLogged(context: Context, itemId: Long, loggedAt: Long = System.currentTimeMillis()): Boolean {
        val sch = schema(context)
        return store(context).update(itemId, fieldValues(sch, NotesAspectSeeder.FIELD_LOGGED_AT to loggedAt), loggedAt).succeeded()
    }

    /** Skip a single occurrence, never move one (ticket 04). [ListItemSkip.itemId] now stores the
     * ENGINE record's id, not a legacy `list_items.id` - see
     * `engine/migration/EngineDataMigrationWave1`'s rekey pass for existing rows written before
     * cutover. */
    suspend fun skipOccurrence(context: Context, item: ListItem, skippedDateEpochMillis: Long) {
        db(context).listItemSkipDao().insert(com.kevin.legion.data.local.ListItemSkip(itemId = item.id, skippedDate = skippedDateEpochMillis))
        scheduleAlarmFor(context, item)
    }

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

    private suspend fun refetch(context: Context, id: Long, fallback: ListItem): ListItem =
        itemById(context, id) ?: fallback

    // ------------------------------------------------------------------------- missed / notifications

    suspend fun missedItems(context: Context): List<ListItem> =
        allEngineItems(context).filter { !it.done && it.missedAt != null && it.missedDismissedAt == null }

    suspend fun dismissMissed(context: Context, item: ListItem) {
        val sch = schema(context)
        store(context).update(item.id, fieldValues(sch, NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT to System.currentTimeMillis()), System.currentTimeMillis())
    }

    fun notificationsBlocked(context: Context, item: ListItem): Boolean {
        val hasTrigger = item.startsAt != null || item.triggerPlaceLabel != null
        if (!hasTrigger) return false
        return permissionRefused(context)
    }

    suspend fun anyNotificationsBlocked(context: Context): Boolean {
        if (!permissionRefused(context)) return false
        val items = allEngineItems(context)
        return items.any { it.startsAt != null && !it.done } || items.any { it.triggerPlaceLabel != null && !it.done }
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

    suspend fun timedItemsInWindow(context: Context, from: Long, to: Long): List<ListItem> =
        allEngineItems(context).filter { !it.done && it.repeatKind == null && it.startsAt != null && it.startsAt in from..to }
            .sortedBy { it.startsAt }

    suspend fun allRecurringItems(context: Context): List<ListItem> =
        allEngineItems(context).filter { it.repeatKind != null }

    suspend fun listNamesById(context: Context): Map<Long, String> {
        val list = theList(context)
        return mapOf(list.id to list.name)
    }

    // ------------------------------------------------------------------ place-trigger reads (ReminderController)

    /** Active (not-yet-done) items carrying a place trigger matching [label] - was a direct
     * `ListItemDao.openWithPlaceTrigger` call from `location/ReminderController.kt` before
     * cutover; that controller now calls here instead so `list_items` gains no reader outside this
     * file either. */
    suspend fun openWithPlaceTrigger(context: Context, label: String): List<ListItem> =
        allEngineItems(context).filter { !it.done && it.triggerPlaceLabel == label }

    /** Every open item carrying ANY place trigger - same rewiring as [openWithPlaceTrigger]. */
    suspend fun openWithAnyPlaceTrigger(context: Context): List<ListItem> =
        allEngineItems(context).filter { !it.done && it.triggerPlaceLabel != null }

    /** Every open, non-deleted item with a time trigger - [AlarmScheduler.rescheduleAll]'s one full
     * scan, rewired off `ListItemDao.allWithTimeTrigger`. Includes done items too, matching that
     * DAO method's own "a done item's stale alarm still needs to be recognized and skipped"
     * reasoning - filtered by the caller, not here. */
    suspend fun allWithTimeTrigger(context: Context): List<ListItem> =
        allEngineItems(context).filter { it.startsAt != null }

    // ----------------------------------------------------------------------- awareness helper

    suspend fun openItemCount(context: Context): Int = allEngineItems(context).count { !it.done }
}
