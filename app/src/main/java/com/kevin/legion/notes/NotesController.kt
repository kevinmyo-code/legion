package com.kevin.legion.notes

import android.content.Context
import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseEventsBackend
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EngineRecord
import com.kevin.legion.data.local.EventReplica
import com.kevin.legion.data.local.EventSkipReplica
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.ListItemSkip
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.upsert
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
 *
 * **Backend-erp Phase 4, aspect 4 of 5 - Notes+Dates merged
 * (`.scratch/backend-erp/issues/05-migration-path.md`, `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`).
 * DUAL-PATH, exactly [com.kevin.legion.location.PlaceController]/[com.kevin.legion.pantry.PantryController]'s
 * shape** - every function funnels through [backend] first, resolved the same way (an override for
 * tests, else [SupabaseClientProvider.get] wrapped in [SupabaseEventsBackend], else null meaning
 * "not configured"):
 * - **Not configured**: the ENGINE path above, completely unchanged - clone-and-run with zero
 *   Supabase setup still works, byte for byte.
 * - **Configured**: reads come from the Room [EventReplica] replica ([allEngineItems], [itemById],
 *   [skippedDates] - see each one's own doc comment); writes go straight to
 *   [com.kevin.legion.backend.EventsBackend] and the replica is written **only on a genuine server
 *   ACK** ([applyChange]'s single write funnel) - never ahead of it, never on a failure. A failed
 *   remote write returns null/false exactly like a failed engine write already did (CLAUDE.md
 *   section 7's outcome-verb rule), and Room is left completely untouched.
 *
 * **`ListItem.syncId` carries a DIFFERENT meaning depending on which path produced the [ListItem]**
 * - the unconfigured (engine) path's [toListItem] sets it to [EngineRecord.guid]; the configured
 * (replica) path's [EventReplica.toListItem] sets it to [EventReplica.serverId], the uuid every
 * mutator hands [EventsBackend] as the row to act on. See that mapper's own doc comment - this is
 * written down there because it is exactly the kind of thing that reads as a promise the code does
 * not keep unless the one place both meanings originate says so.
 *
 * **[ListItem.id] preservation is the entire point of the replica's id-carry contract** (ticket
 * 11, mirrored by commit b17bc88's `records.id` carry) - `notes/AlarmScheduler.kt` uses it as an
 * `AlarmManager` `PendingIntent` request code, and `list_item_skips.itemId`/
 * `workout_set_logs.sourceListItemId`/`muted_reminders.recordId` are all soft foreign keys against
 * it. [applyChange] always writes the replica at [ListItem.id]'s OWN existing value, never a fresh
 * one - see that function's own doc comment.
 */
object NotesController {
    private fun db(context: Context) = CarDatabase.getDatabase(context)
    private fun store(context: Context): RecordStore {
        val db = db(context)
        return RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
    }

    /** Test seam: settable from a unit test so an [EventsBackend] fake can be injected without a
     * real [SupabaseClientProvider] / network - same mechanism as
     * [com.kevin.legion.location.PlaceController.backendOverride]/
     * [com.kevin.legion.pantry.PantryController.backendOverride]. Defaults to null, meaning
     * "resolve normally"; production code never sets this. */
    @Volatile
    internal var backendOverride: EventsBackend? = null

    /** Resolves the active backend, or null when Supabase is not configured - the signal every
     * function below branches on. Never performs network I/O itself. */
    private fun backend(context: Context): EventsBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseEventsBackend(client)
    }

    /** Public sibling of [backend] that only exposes the yes/no, never the backend instance
     * itself - [AlarmScheduler.rescheduleAll] needs to know which path it is walking (engine vs.
     * replica) without needing an [EventsBackend] to call anything on. See that function's own
     * doc comment for why the answer changes what the start-up sweep is allowed to do. */
    fun isBackendConfigured(context: Context): Boolean = backend(context) != null

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
     * result. **Stays legacy either way** - the backend-erp dual path does not touch this function;
     * a display convenience, not a real foreign key, has nothing for a server row to back.
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
     * invents a second mapping. The UNCONFIGURED path's own mapper - [ListItem.syncId] here is
     * [EngineRecord.guid]; see [EventReplica.toListItem] for the CONFIGURED path's counterpart and
     * why the same property holds a different value there. */
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

    /**
     * [EventReplica] -> [ListItem] - the CONFIGURED read path's counterpart to [toListItem]'s
     * engine-side mapping above. **[ListItem.syncId] carries a DIFFERENT meaning on this path**:
     * here it is the server row's own uuid ([EventReplica.serverId]), the id every write-path
     * mutator hands back to [EventsBackend.upsert]/[EventsBackend.softDelete]/
     * [EventsBackend.skipOccurrence] as the row to act on - NOT [EngineRecord.guid], which is what
     * the SAME field holds on the unconfigured path's [toListItem]. Two different backing values
     * under one property name is exactly the kind of thing that reads as a promise the code does
     * not keep unless it is written down here, in the one place both meanings originate.
     *
     * [ListItem.id] is [EventReplica.id] - preserved, never reminted, on every call site that
     * builds one of these (see this object's own class doc's "id preservation" paragraph).
     */
    private fun EventReplica.toListItem(listId: Long): ListItem = ListItem(
        id = id,
        listId = listId,
        text = title,
        done = done,
        doneAt = doneAt,
        sortOrder = sortOrder ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAtMs,
        syncId = serverId,
        deleted = deleted,
        startsAt = startsAt,
        endsAt = endsAt,
        allDay = allDay,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDate,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAt,
        missedDismissedAt = missedDismissedAt,
        loggedAt = loggedAt,
    )

    /**
     * [ListItem] -> [EventFields] - the wire shape [EventsBackend.upsert] expects, mirroring
     * [com.kevin.legion.backend.EventsReconcile]'s own Notes `Item` -> [EventFields] mapping field
     * for field (that object's `noteEvents` block) so the live write path built here and the
     * one-time migration path agree on what a Notes item looks like server-side. [source] is left
     * at [EventFields]'s own default ("legion") rather than repeating
     * `DatesAspectSeeder.SOURCE_LEGION` by name, matching [com.kevin.legion.backend.EventsReconcile]'s
     * identical value for a Notes-originated row. `location`/`notes`/`googleEventId` have no
     * [ListItem] counterpart - a Notes item never carries them - matching that same mapping's nulls
     * for the identical fields.
     *
     * [EventFields.createdAtMs] is always [ListItem.createdAt] here, never null - by the time any
     * mutator calls this, the item already exists and already has a real creation time (from
     * either read path's own mapper above), so there is nothing "unknown" left to omit.
     */
    private fun ListItem.toEventFields(): EventFields = EventFields(
        title = text,
        startsAtMs = startsAt,
        createdAtMs = createdAt,
        endsAtMs = endsAt,
        allDay = allDay,
        location = null,
        notes = null,
        googleEventId = null,
        done = done,
        doneAtMs = doneAt,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDateMs = repeatEndDate,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAtMs = missedAt,
        missedDismissedAtMs = missedDismissedAt,
        loggedAtMs = loggedAt,
    )

    /**
     * [RemoteEvent] -> [EventReplica], field for field - this file's own copy of
     * [com.kevin.legion.backend.EventsReconcile]'s identical private mapper. That one is private to
     * its own file and shaped for a batch refill with a different id-carry contract (derive-or-
     * autoincrement across many rows at once); duplicating twenty field assignments here is the
     * smaller cost against exporting a reconciliation-only helper or complicating a batch-oriented
     * signature for this file's single-row caller.
     * @param id the [EventReplica.id]/[ListItem.id] this row must be minted or updated at - see
     * [applyChange]'s own doc comment for why this is always [ListItem.id]'s existing value on a
     * live write, never freshly allocated.
     */
    private fun RemoteEvent.toReplica(id: Long) = EventReplica(
        id = id,
        serverId = serverId,
        title = title,
        createdAt = createdAtMs,
        startsAt = startsAtMs,
        endsAt = endsAtMs,
        allDay = allDay,
        location = location,
        notes = notes,
        source = source,
        googleEventId = googleEventId,
        done = done,
        doneAt = doneAtMs,
        sortOrder = sortOrder,
        triggerPlaceLabel = triggerPlaceLabel,
        repeatKind = repeatKind,
        repeatEvery = repeatEvery,
        repeatDaysOfWeek = repeatDaysOfWeek,
        repeatDay = repeatDay,
        repeatMonth = repeatMonth,
        repeatEndKind = repeatEndKind,
        repeatEndDate = repeatEndDateMs,
        repeatEndCount = repeatEndCount,
        exact = exact,
        exactDowngraded = exactDowngraded,
        missedAt = missedAtMs,
        missedDismissedAt = missedDismissedAtMs,
        loggedAt = loggedAtMs,
        updatedAtMs = updatedAtMs,
        deleted = deleted,
    )

    /** Every non-trashed item, converted - the one place every read below funnels through, so
     * there is exactly one query per read regardless of which path answers it. **Configured**:
     * reads the Room [EventReplica] replica, never the network - cache-first (ticket 01 ruling 9).
     * **Unconfigured**: every non-trashed engine `Item` record, exactly as before cutover. */
    private suspend fun allEngineItems(context: Context): List<ListItem> {
        val listId = theList(context).id
        if (backend(context) != null) {
            return db(context).eventReplicaDao().getAllActive().map { it.toListItem(listId) }
        }
        val db = db(context)
        val sch = schema(context)
        return db.engineRecordDao().activeByRecordType(sch.recordTypeId)
            .map { toListItem(it, sch.fieldIds, listId) }
    }

    private fun fieldValues(sch: NotesAspectSeeder.Schema, vararg pairs: Pair<String, Any?>): Map<Long, Any?> =
        pairs.associate { (name, value) -> sch.fieldIds.getValue(name) to value }

    // ------------------------------------------------------------------------------- the write funnel

    /**
     * The one write funnel every field-updating mutator below passes through - collapsing what used
     * to be an identical `store(context).update(item.id, fieldValues, now).succeeded()` line
     * repeated in every mutator into a single configured/unconfigured branch, same role
     * [com.kevin.legion.location.PlaceController]/[com.kevin.legion.pantry.PantryController] give
     * their own per-function backend branches.
     *
     * - **Configured**: [backend]'s target is [item]'s OWN [ListItem.syncId], which on the
     *   configured read path IS the server row's uuid (see [EventReplica.toListItem]'s own doc
     *   comment for why that is a DIFFERENT meaning of the same property than the unconfigured path
     *   uses - this only holds because every caller reaches [item] through one of this file's own
     *   read functions, never the engine or replica directly). [mutated] - the SAME change,
     *   expressed as the post-write [ListItem] rather than an engine field map - becomes the
     *   [EventFields] sent over the wire. Room is written ONLY on the genuine ACK, and ALWAYS at
     *   [item]'s own existing [ListItem.id] - never a freshly minted one, since that id is an
     *   `AlarmManager` `PendingIntent` request code and a soft foreign key elsewhere (this object's
     *   own class doc). A failed remote write returns null and touches Room not at all.
     * - **Unconfigured**: [engineFields] is written through [RecordStore.update] exactly as every
     *   mutator wrote it before this fix - byte-identical, including the same failure-returns-null,
     *   no-false-success posture. [refetch] mirrors the old per-mutator `itemById(...) ?: item`
     *   fallback precisely, falling back to the ORIGINAL [item] (never [mutated]) on the
     *   practically-impossible case the record vanished between the write and the refetch.
     *
     * Returns null on ANY failure, written or not - callers must never treat a null as "unchanged
     * and safe to carry on with the stale item", the same discipline this file's mutators already
     * established for their own null-on-failure contracts before this fix.
     */
    private suspend fun applyChange(
        context: Context,
        item: ListItem,
        engineFields: Map<Long, Any?>,
        mutated: ListItem,
        now: Long = System.currentTimeMillis(),
    ): ListItem? {
        val backend = backend(context)
        if (backend != null) {
            val remote = backend.upsert(item.syncId.ifEmpty { null }, mutated.toEventFields()).getOrElse { return null }
            // Room is written ONLY here, after a genuine server ACK - never ahead of it, and never
            // on the failure branch above. id = item.id PRESERVES the existing local identity;
            // see this function's own doc comment for why that is load-bearing, not tidy.
            val replicaRow = remote.toReplica(id = item.id)
            db(context).eventReplicaDao().upsert(replicaRow)
            return replicaRow.toListItem(theList(context).id)
        }

        val ok = store(context).update(item.id, engineFields, now).succeeded()
        if (!ok) return null
        return refetch(context, item.id, item)
    }

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

    /** **Configured**: reads the [EventReplica] replica by [EventReplica.id]. **Unconfigured**: the
     * unchanged engine read by [EngineRecord.id]. Both share [ListItem.id] as the same physical id
     * space (this object's own class doc's "id preservation" paragraph), so a caller never needs to
     * know which path produced the id it is looking up. */
    suspend fun itemById(context: Context, id: Long): ListItem? {
        val listId = theList(context).id
        if (backend(context) != null) {
            val replica = db(context).eventReplicaDao().getById(id) ?: return null
            if (replica.deleted) return null
            return replica.toListItem(listId)
        }
        val record = db(context).engineRecordDao().getById(id) ?: return null
        if (record.deletedAt != null) return null
        val sch = schema(context)
        if (record.recordTypeId != sch.recordTypeId) return null
        return toListItem(record, sch.fieldIds, listId)
    }

    suspend fun openItemsForList(context: Context, listId: Long): List<ListItem> =
        allEngineItems(context).filter { !it.done }

    /** **Configured**: [EventsBackend.upsert] with `serverId = null` creates the row; the local
     * [ListItem.id] then comes straight from the replica insert
     * ([com.kevin.legion.data.local.EventReplicaDao.upsert]'s own return value), never a value this
     * function invents. **Unconfigured**: unchanged. Both branches `error(...)` on a failed write -
     * same contract as before this fix, since a caller of this specific function has never had a
     * way to receive "it didn't save" other than a thrown error (unlike every other mutator in this
     * file, which returns null/false). */
    suspend fun addItem(context: Context, listId: Long, text: String): ListItem {
        val trimmed = text.trim()
        val now = System.currentTimeMillis()
        val nextOrder = allEngineItems(context).size

        val backend = backend(context)
        if (backend != null) {
            val fields = EventFields(
                title = trimmed,
                startsAtMs = null,
                createdAtMs = now,
                done = false,
                sortOrder = nextOrder,
            )
            val remote = backend.upsert(serverId = null, fields = fields).getOrElse {
                error("NotesController.addItem: remote write failed - ${it.message}")
            }
            val replicaRow = remote.toReplica(id = 0)
            val id = db(context).eventReplicaDao().upsert(replicaRow)
            return replicaRow.copy(id = id).toListItem(listId)
        }

        val sch = schema(context)
        val values = fieldValues(
            sch,
            NotesAspectSeeder.FIELD_TEXT to trimmed,
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
     * writing nothing further, if the write itself fails on either path (senior review, 2026-08-24:
     * "tick... ignore RecordStore.WriteResult and return true" was a real §7 outcome-verb violation -
     * a failed write must never look like a successful tick to the spoken layer). */
    suspend fun tick(context: Context, item: ListItem): Boolean {
        if (item.repeatKind != null) return false
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val mutated = item.copy(done = true, doneAt = now)
        val ok = applyChange(
            context, item,
            fieldValues(sch, NotesAspectSeeder.FIELD_DONE to true, NotesAspectSeeder.FIELD_DONE_AT to now),
            mutated, now,
        ) != null
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** Returns false, writing nothing further, on a failed write on either path - same §7 fix as [tick]. */
    suspend fun untick(context: Context, item: ListItem): Boolean {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val mutated = item.copy(done = false, doneAt = null, loggedAt = null)
        val result = applyChange(
            context, item,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_DONE to false,
                NotesAspectSeeder.FIELD_DONE_AT to null,
                NotesAspectSeeder.FIELD_LOGGED_AT to null,
            ),
            mutated, now,
        )
        if (result == null) return false
        // Ticket 09 ("a ticked workout is one act, not two rows"): a correction propagates to the
        // training history - see the pre-cutover version of this comment, unchanged reasoning,
        // still a DAO call rather than a WorkoutController import (notes/ stays a foundation layer).
        db(context).workoutSetLogDao().deleteBySourceListItemId(item.id)
        return true
    }

    /** No confirmation (ticket 05). **Configured**: [EventsBackend.softDelete], and the replica row
     * is removed only on a genuine `true` ACK - `Result.success(false)` (already deleted, or a
     * stale id) is a normal outcome, never reported as a delete having happened, same posture
     * [com.kevin.legion.location.PlaceController.forgetPlace] already established. **Unconfigured**:
     * unchanged, trashed via [RecordStore.delete]. Only a confirmed delete on either path cancels
     * the alarm and reports success. */
    suspend fun removeItem(context: Context, item: ListItem): Boolean {
        val backend = backend(context)
        val trashed = if (backend != null) {
            val didDelete = backend.softDelete(item.syncId).getOrElse { return false }
            if (didDelete) db(context).eventReplicaDao().deleteByServerId(item.syncId)
            didDelete
        } else {
            store(context).delete(item.id) is RecordStore.DeleteResult.Trashed
        }
        if (trashed) AlarmScheduler.cancel(context, item.id)
        return trashed
    }

    suspend fun renameItem(context: Context, item: ListItem, text: String): Boolean {
        val sch = schema(context)
        val trimmed = text.trim()
        val mutated = item.copy(text = trimmed)
        return applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_TEXT to trimmed), mutated) != null
    }

    /** Returns false (writing nothing further) the moment EITHER swap-half fails on either path,
     * rather than reporting success on a half-applied reorder - the two writes are not wrapped in a
     * single transaction on either path, so this is the closest this function can get to "no false
     * success" without a new capability on both [RecordStore] and [EventsBackend]. */
    suspend fun moveItem(context: Context, item: ListItem, siblingsInOrder: List<ListItem>, up: Boolean): Boolean {
        val index = siblingsInOrder.indexOfFirst { it.id == item.id }
        if (index < 0) return false
        val swapIndex = if (up) index - 1 else index + 1
        if (swapIndex !in siblingsInOrder.indices) return false
        val other = siblingsInOrder[swapIndex]
        val sch = schema(context)
        val now = System.currentTimeMillis()

        val firstMutated = item.copy(sortOrder = other.sortOrder)
        val firstOk = applyChange(
            context, item,
            fieldValues(sch, NotesAspectSeeder.FIELD_SORT_ORDER to other.sortOrder),
            firstMutated, now,
        ) != null
        val secondMutated = other.copy(sortOrder = item.sortOrder)
        val secondOk = applyChange(
            context, other,
            fieldValues(sch, NotesAspectSeeder.FIELD_SORT_ORDER to item.sortOrder),
            secondMutated, now,
        ) != null
        return firstOk && secondOk
    }

    /** Returns null (writing nothing further - no alarm scheduled) on a failed write on either
     * path, the `ListItem`-returning counterpart of [tick]'s Boolean fix. A non-null return is a
     * genuine, confirmed write - callers must not treat a null as "unchanged" and silently carry on
     * with the stale pre-call item, the same "no false success" posture as every Boolean fix in this
     * file. */
    suspend fun setTime(context: Context, item: ListItem, startsAt: Long, endsAt: Long?, allDay: Boolean): ListItem? {
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val mutated = item.copy(
            startsAt = startsAt, endsAt = endsAt, allDay = allDay,
            triggerPlaceLabel = null, missedAt = null, missedDismissedAt = null,
        )
        val result = applyChange(
            context, item,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_STARTS_AT to startsAt,
                NotesAspectSeeder.FIELD_ENDS_AT to endsAt,
                NotesAspectSeeder.FIELD_ALL_DAY to allDay,
                NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to null, // at most one trigger - charting decision 4
                NotesAspectSeeder.FIELD_MISSED_AT to null,
                NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT to null,
            ),
            mutated, now,
        ) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    suspend fun clearTime(context: Context, item: ListItem): Boolean {
        val sch = schema(context)
        val mutated = item.copy(startsAt = null, endsAt = null, triggerPlaceLabel = null)
        val ok = applyChange(
            context, item,
            fieldValues(sch, NotesAspectSeeder.FIELD_STARTS_AT to null, NotesAspectSeeder.FIELD_ENDS_AT to null, NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to null),
            mutated,
        ) != null
        if (ok) AlarmScheduler.cancel(context, item.id)
        return ok
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setPlaceTrigger(context: Context, item: ListItem, placeLabel: String): ListItem? {
        val sch = schema(context)
        val mutated = item.copy(startsAt = null, endsAt = null, triggerPlaceLabel = placeLabel)
        val result = applyChange(
            context, item,
            fieldValues(
                sch,
                NotesAspectSeeder.FIELD_STARTS_AT to null,
                NotesAspectSeeder.FIELD_ENDS_AT to null,
                NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL to placeLabel,
            ),
            mutated,
        ) ?: return null
        // A place trigger has no clock to watch - nothing for AlarmManager to schedule.
        AlarmScheduler.cancel(context, item.id)
        return result
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setRepeat(context: Context, item: ListItem, rule: RepeatRule?, end: RepeatEnd): ListItem? {
        val sch = schema(context)
        val cols = repeatColumnsFor(rule, end)
        val mutated = item.copy(
            repeatKind = cols.repeatKind, repeatEvery = cols.repeatEvery, repeatDaysOfWeek = cols.repeatDaysOfWeek,
            repeatDay = cols.repeatDay, repeatMonth = cols.repeatMonth, repeatEndKind = cols.repeatEndKind,
            repeatEndDate = cols.repeatEndDate, repeatEndCount = cols.repeatEndCount,
        )
        val result = applyChange(
            context, item,
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
            mutated,
        ) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    /** See [setTime]'s doc comment for the null-on-failure contract this shares. */
    suspend fun setExact(context: Context, item: ListItem, exact: Boolean): ListItem? {
        val sch = schema(context)
        val mutated = item.copy(exact = exact)
        val result = applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_EXACT to exact), mutated) ?: return null
        scheduleAlarmFor(context, result)
        return result
    }

    /** [AlarmScheduler.schedule]'s own downgrade-persistence write - routed through [applyChange]
     * like every other field write in this file. Takes [itemId] rather than a [ListItem] (matching
     * its pre-existing signature), so it reads the item once itself; a missing/deleted item is a
     * plain false, matching what a direct [RecordStore.update] against a nonexistent id already
     * returned before this fix. */
    suspend fun setExactDowngraded(context: Context, itemId: Long, downgraded: Boolean): Boolean {
        val item = itemById(context, itemId) ?: return false
        val sch = schema(context)
        val mutated = item.copy(exactDowngraded = downgraded)
        return applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_EXACT_DOWNGRADED to downgraded), mutated) != null
    }

    /** [AlarmScheduler.rescheduleAll]'s own missed-marking write - ticket 12: "stored once, never
     * recomputed." No `missedAt IS NULL` guard here (unlike the legacy DAO query) - callers
     * ([AlarmScheduler.rescheduleAll]) already check that before calling, matching the legacy
     * comment's own "idempotent by construction... only calls this when missedAt IS NULL". Same
     * itemId-reads-itself shape as [setExactDowngraded]. */
    suspend fun markMissed(context: Context, itemId: Long): Boolean {
        val item = itemById(context, itemId) ?: return false
        val sch = schema(context)
        val now = System.currentTimeMillis()
        val mutated = item.copy(missedAt = now)
        return applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_MISSED_AT to now), mutated, now) != null
    }

    /** [GoalChecklistSync]'s own idempotence-anchor write - see this file's own class doc for why
     * `loggedAt` needed a new field on the engine side. Same itemId-reads-itself shape as
     * [setExactDowngraded]. */
    suspend fun markLogged(context: Context, itemId: Long, loggedAt: Long = System.currentTimeMillis()): Boolean {
        val item = itemById(context, itemId) ?: return false
        val sch = schema(context)
        val mutated = item.copy(loggedAt = loggedAt)
        return applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_LOGGED_AT to loggedAt), mutated, loggedAt) != null
    }

    /** Skip a single occurrence, never move one (ticket 04). **Configured**:
     * [EventsBackend.skipOccurrence] against [item]'s server uuid, and the skip replica row is
     * written only on a genuine ACK - the legacy `list_item_skips` write stays on the unconfigured
     * path only, matching every other write in this file's "Room only on ACK" posture.
     * **Unconfigured**: unchanged - [ListItemSkip.itemId] stores the ENGINE record's id, not a
     * legacy `list_items.id` (see `engine/migration/EngineDataMigrationWave1`'s rekey pass for
     * existing rows written before cutover). Fire-and-forget on either path, matching this
     * function's pre-existing `Unit` return - [scheduleAlarmFor] always runs afterward regardless of
     * whether the skip write itself landed, same as before this fix. */
    suspend fun skipOccurrence(context: Context, item: ListItem, skippedDateEpochMillis: Long) {
        val backend = backend(context)
        if (backend != null) {
            val ok = backend.skipOccurrence(item.syncId, skippedDateEpochMillis).isSuccess
            if (ok) {
                db(context).eventSkipReplicaDao().insert(
                    EventSkipReplica(eventServerId = item.syncId, skipDateEpochMs = skippedDateEpochMillis),
                )
            }
        } else {
            db(context).listItemSkipDao().insert(ListItemSkip(itemId = item.id, skippedDate = skippedDateEpochMillis))
        }
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
            // Routed through skippedDates() rather than the DAO directly, so a configured install's
            // skip data (which lives in eventSkipReplicaDao, not listItemSkipDao - see
            // skipOccurrence's own doc comment) is consulted correctly here too.
            val skips = skippedDates(context, item)
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
        val now = System.currentTimeMillis()
        val mutated = item.copy(missedDismissedAt = now)
        applyChange(context, item, fieldValues(sch, NotesAspectSeeder.FIELD_MISSED_DISMISSED_AT to now), mutated, now)
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

    /** **Configured**: [item]'s server uuid's skips, from the [EventSkipReplica] replica -
     * never the network. **Unconfigured**: unchanged. */
    suspend fun skippedDates(context: Context, item: ListItem): Set<Long> {
        if (backend(context) != null) {
            return db(context).eventSkipReplicaDao().forEvent(item.syncId).toSet()
        }
        return db(context).listItemSkipDao().skippedDatesForItem(item.id).toSet()
    }

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
